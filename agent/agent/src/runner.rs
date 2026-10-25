//! Running one attempt of a session (ADR 0016). The supervisor hands a [`Job`] to a
//! [`SessionRunner`], which reports progress as [`Report`]s and checks the job's [`Fence`] before
//! anything with a side effect.
//!
//! There are two runners. The development runner ([`dev::DevRunner`], `--dev-runner`, only with `--fake-gpu`) simulates a
//! job against real object storage. The container runner ([`container::ContainerRunner`], ADR 0035) hands the job to
//! `idleo-runner` inside our sealed Linux distribution, which stages its inputs, runs it in a sandboxed container and
//! uploads its checkpoints and outputs, so none of the renter's data touches the owner's Windows disk.

pub mod container;
pub mod dev;

use std::collections::BTreeMap;
use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use agent_proto::v1::{CheckpointRef, Credentials, InputRef, SessionSpec, SessionState};
use tokio::sync::{mpsc, watch};
use uuid::Uuid;

use crate::logs::{LogEvent, LogWriter};
use crate::storage::ObjectStore;

/// One attempt to run.
pub struct Job {
    /// The session.
    pub session_id: Uuid,
    /// Which attempt this is.
    pub attempt: i32,
    /// What to run.
    pub spec: SessionSpec,
    /// `sessions/{session_id}/attempt-{n}/`, where the job writes checkpoints and outputs.
    pub attempt_prefix: String,
    /// Read-write on `attempt_prefix` only.
    pub attempt_store: Arc<dyn ObjectStore>,
    /// Read-only on the input and the resume checkpoint, when there's either.
    pub read_store: Option<Arc<dyn ObjectStore>>,
    /// The complete checkpoint to resume from, chosen by the control plane.
    pub resume_from: Option<CheckpointRef>,
    /// Whether the attempt may still act.
    pub fence: Fence,
    /// Becomes the grace period when the control plane asks for a checkpoint and a stop: the runner writes one last
    /// checkpoint, reports [`Report::Stopped`] and returns, within that grace, which the supervisor enforces too.
    pub stop_requested: watch::Receiver<Option<Duration>>,
    /// The attempt's scratch folder on this machine, wiped when the attempt ends.
    pub folder: PathBuf,
    /// Each uploaded input's staged folder, `{folder}/input/{slot}/`, by slot. A link input is only in `spec.env` as
    /// `SESSION_INPUT_{NAME}_URL`, for the job to fetch inside its sandbox.
    pub inputs: BTreeMap<String, PathBuf>,
    /// Where the job's output goes: the attempt's logs, uploaded in chunks for the renter.
    pub logs: LogWriter,
    /// Every uploaded input file to stage, for a runner that stages them itself ([`SessionRunner::stages_inputs`]).
    pub input_refs: Vec<InputRef>,
    /// The attempt's raw storage grants, for a runner that holds them elsewhere (the container runner passes them to
    /// `idleo-runner`, which keeps them in memory only). Never printed.
    pub credentials: Option<Credentials>,
    /// The lease the attempt started with.
    pub lease_ttl: Duration,
    /// The attempt's first minute index (ADR 0017), which a runner that outlives the agent keeps for it.
    pub minute_index_base: i32,
    /// The GPUs it runs on.
    pub gpus: Vec<String>,
}

/// What a runner reports about its attempt.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Report {
    /// A new state: PREPARING, RUNNING or UPLOADING.
    State(SessionState),
    /// Checkpoint `seq` is complete: its `_COMPLETE` marker is written.
    Checkpoint(i32),
    /// The job exited 0 and its outputs, with their marker, are uploaded.
    Succeeded,
    /// Asked to stop, the job wrote its last checkpoint (if it could) and stopped.
    Stopped,
    /// The attempt's logs are uploaded up to this chunk, or finished (only a runner that owns the logs says so).
    Logs(LogEvent),
    /// The runner saw something in the session an admin should look at, or stopped it for that (ADR 0038). Sent to the
    /// control plane as an `AbuseSignal`, which it acks.
    Abuse {
        /// Which signal.
        kind: jobcore::protocol::AbuseKind,
        /// The process's name or the pool's domain.
        detail: String,
        /// The executable's SHA-256, for a listed build.
        sha256: String,
        /// Dropped connections in all, for connection drops.
        count: u64,
        /// The runner stopped the session because of this.
        stopped: bool,
    },
    /// What the job did in the last minute, which goes on the next usage report (ADR 0038).
    Behavior {
        /// Bytes it sent out.
        net_out_bytes: u64,
        /// Bytes it received.
        net_in_bytes: u64,
        /// Lines it printed.
        printed_lines: u64,
        /// New connections the fair-use limit dropped.
        conn_drops: u64,
    },
    /// The job or its setup failed.
    Failed {
        /// A stable code, for example `invalid_args`.
        code: String,
        /// One short line, never renter data.
        message: String,
        /// The job's exit code, when it exited.
        exit_code: Option<i32>,
    },
}

/// Runs attempts.
pub trait SessionRunner: Send + Sync {
    /// Runs `job` until it ends, reporting through `reports`. Stops as soon as the job's fence goes
    /// down; the supervisor also aborts it then.
    fn run(&self, job: Job, reports: mpsc::UnboundedSender<Report>) -> Pin<Box<dyn Future<Output = ()> + Send>>;

    /// Whether the runner uploads the attempt's logs itself and reports them as [`Report::Logs`]. If not, the supervisor's
    /// own sink does, from what the runner writes to [`Job::logs`].
    fn owns_logs(&self) -> bool {
        false
    }

    /// Whether the runner stages the attempt's inputs itself, where the job runs. If not, the supervisor downloads them to
    /// the attempt's folder first.
    fn stages_inputs(&self) -> bool {
        false
    }

    /// An offer was accepted: get ready for its `StartSession`, which comes in a moment (the container runner starts a
    /// stopped distribution, so the job's first line isn't waiting on a cold start).
    fn warm(&self) {}

    /// A newer abuse blocklist the agent has verified (ADR 0038): the signed file's exact text. The container runner keeps it
    /// and gives it to `idleo-runner` when its distribution is up, and before a session starts.
    fn blocklist(&self, _file: String) {}

    /// The control plane renewed an attempt's lease: it may run `ttl` more from now.
    fn renew(&self, _session_id: Uuid, _attempt: i32, _ttl: Duration) {}

    /// Fresh storage grants for a running attempt.
    fn refresh(&self, _session_id: Uuid, _attempt: i32, _credentials: &Credentials) {}

    /// Attempts that outlived the agent (a job keeps running when the agent restarts), for the supervisor to pick up
    /// again. Called once, before the agent first connects.
    fn adopt(&self) -> Pin<Box<dyn Future<Output = Vec<Adopted>> + Send + '_>> {
        Box::pin(std::future::ready(Vec::new()))
    }
}

/// Starts following an adopted attempt: reports its progress and its end.
pub type Watching = Box<
    dyn FnOnce(
            mpsc::UnboundedSender<Report>,
            watch::Receiver<Option<Duration>>,
            Fence,
        ) -> Pin<Box<dyn Future<Output = ()> + Send>>
        + Send,
>;

/// An attempt that outlived the agent, as its runner says it stands.
pub struct Adopted {
    /// The session.
    pub session_id: Uuid,
    /// Its attempt.
    pub attempt: i32,
    /// Where it is.
    pub state: SessionState,
    /// The newest checkpoint uploaded.
    pub latest_checkpoint_seq: i32,
    /// The newest log chunk uploaded.
    pub latest_log_seq: i32,
    /// How long it has run, on the runner's clock.
    pub running_for: Duration,
    /// How long its lease has left.
    pub lease_remaining: Duration,
    /// How long before `max_runtime` stops it.
    pub runtime_remaining: Duration,
    /// Its first minute index.
    pub minute_index_base: i32,
    /// The GPUs it runs on.
    pub gpus: Vec<String>,
    /// Follows it from here, and tells how it ended if it did while the agent was away.
    pub watching: Watching,
}

/// Whether an attempt may still act: up until its lease lapses or it's stopped, then down for good.
#[derive(Debug, Clone, Default)]
pub struct Fence(Arc<AtomicBool>);

impl Fence {
    /// A fence that's up.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Whether the attempt may still act.
    #[must_use]
    pub fn is_up(&self) -> bool {
        !self.0.load(Ordering::SeqCst)
    }

    /// Takes the fence down; it never comes back up.
    pub fn take_down(&self) {
        self.0.store(true, Ordering::SeqCst);
    }
}
