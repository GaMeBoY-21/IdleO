//! The agent's sessions (ADR 0016): the attempts it runs, their leases, and the statuses it owes the
//! control plane. It lives for the whole process, across reconnects: a lease keeps running down while
//! the stream is gone, and statuses wait until a stream can carry them.
//!
//! - **`StartSession`** is acked once recorded, and a repeated `command_id` is acked and never run
//!   twice. An invalid one is acked and reported FAILED, so the control plane needn't wait.
//! - **Leases**: an attempt's deadline is the lease clock at receipt plus `lease_ttl`; a `LeaseRenew`
//!   for the current attempt extends it. When it passes, the work stops at once (fence down, task
//!   aborted) and the attempt is reported `LEASE_EXPIRED`; it never resumes. Every entry point checks
//!   deadlines before anything else, so after the machine wakes from sleep the first thing that
//!   happens is fencing whatever lapsed meanwhile.
//! - **Statuses** go out on every state change and checkpoint, kept until acked and resent, and all
//!   resent on a new stream. They're in memory for now; the persistent store comes with the Docker
//!   runner.
//! - **Usage** (ADR 0017): one `UsageReport` per whole minute of each attempt, counted on the lease
//!   clock from its first RUNNING, with `minute_index` = `minute_index_base` plus the minute's count,
//!   and the last, partial minute when the job ends, with its real seconds. Nothing after a lease
//!   lapses: only the minutes that ended before the deadline are reported. Usage reports wait in the
//!   same queue as statuses. The agent meters, never the runner.
//! - **Inputs** (ADR 0019, ADR 0022): every uploaded input file is staged into the attempt's scratch folder, in its
//!   input's folder, before the runner starts ([`crate::staging`]); a failure ends the attempt `input_unavailable`
//!   before RUNNING.
//! - **Logs** (ADR 0019): each attempt has its own log sink ([`crate::logs`]); heartbeats and statuses carry the
//!   highest chunk uploaded. When an attempt ends, its terminal status waits until the sink has flushed, at most
//!   [`crate::logs::FLUSH_FOR`].
//! - **Scratch**: each attempt's folder under the agent's work folder is wiped when the attempt ends, and the
//!   whole work folder when the agent starts.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body as AgentBody;
use agent_proto::v1::{
    Ack, InputRef, LeaseRenew, OpenStreamRequest, RefreshCredentials, RunningSession, SessionError, SessionState,
    SessionStatus, StartSession, StopReason, StopSession, UsageReport,
};
use tokio::sync::{Notify, mpsc, watch};
use tokio::task::{AbortHandle, JoinHandle};
use tokio::time::Instant;
use uuid::Uuid;

use crate::lease_clock::LeaseClock;
use crate::logs::{self, LogEvent};
use crate::new_message_id;
use crate::runner::{Fence, Job, Report, SessionRunner};
use crate::staging;
use crate::storage::{Grant, ObjectStore, S3Store};

/// What the supervisor learns from a runner task.
#[derive(Debug)]
pub enum Reported {
    /// The runner said so.
    Report(Report),
    /// The runner's task ended; `panicked` if it panicked.
    Ended {
        /// The task panicked rather than returning.
        panicked: bool,
    },
    /// The attempt's log sink uploaded a chunk, or finished.
    Logs(LogEvent),
}

/// A runner's message about one attempt.
#[derive(Debug)]
pub struct Tagged {
    session_id: Uuid,
    attempt: i32,
    reported: Reported,
}

/// The supervisor, shared between the stream and its own background task.
#[derive(Clone)]
pub struct Shared {
    inner: Arc<Mutex<Supervisor>>,
    changed: Arc<Notify>,
}

impl Shared {
    /// Locks the supervisor. Every method is quick and never waits on I/O.
    pub fn lock(&self) -> MutexGuard<'_, Supervisor> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// Resolves when new statuses are waiting to be sent.
    pub async fn changed(&self) {
        self.changed.notified().await;
    }
}

struct Attempt {
    number: i32,
    /// The lease's end, on the lease clock.
    deadline: Duration,
    /// The hard cap from `max_runtime`, on the lease clock.
    runtime_cap: Duration,
    fence: Fence,
    tasks: Vec<AbortHandle>,
    state: SessionState,
    latest_seq: i32,
    /// `StartSession.minute_index_base`.
    minute_index_base: i32,
    /// The GPUs it runs on, whose utilisation goes in its usage reports.
    gpus: Vec<String>,
    /// The lease clock at its first RUNNING, from which its minutes count.
    running_since: Option<Duration>,
    /// Whole minutes already reported.
    minutes_reported: i32,
    /// Tells the runner to checkpoint and stop.
    stop_signal: Option<watch::Sender<bool>>,
    /// A checkpoint-first stop under way: when its grace ends on the lease clock, and why it was asked.
    stopping: Option<(Duration, String)>,
    /// The job's storage, attempt and read, whose grants `RefreshCredentials` replaces.
    stores: Option<(Arc<S3Store>, Option<Arc<S3Store>>)>,
    /// The highest log chunk uploaded.
    latest_log_seq: i32,
    /// Tells the log sink the attempt has ended, so it flushes and finishes.
    logs_finish: Option<watch::Sender<bool>>,
    /// The log sink's task, aborted only if it outlives its flush.
    sink: Option<AbortHandle>,
    /// The terminal status, waiting for the log sink's flush: its error, exit code, and when it ended.
    pending_end: Option<PendingEnd>,
    /// The attempt's scratch folder: its staged input, wiped when it ends.
    folder: Option<PathBuf>,
}

/// A terminal status waiting for the logs to flush.
struct PendingEnd {
    error: Option<(String, String)>,
    exit_code: Option<i32>,
    /// When the attempt ended, on the lease clock.
    since: Duration,
}

/// A terminal status goes out at the latest this long after its attempt ended, flushed or not.
const END_WAITS_AT_MOST: Duration = Duration::from_secs(logs::FLUSH_FOR.as_secs() + 5);

impl Attempt {
    fn idle(number: i32, at: Duration) -> Self {
        Self {
            number,
            deadline: at,
            runtime_cap: at,
            fence: Fence::new(),
            tasks: Vec::new(),
            state: SessionState::Preparing,
            latest_seq: 0,
            minute_index_base: 0,
            gpus: Vec::new(),
            running_since: None,
            minutes_reported: 0,
            stop_signal: None,
            stopping: None,
            stores: None,
            latest_log_seq: 0,
            logs_finish: None,
            sink: None,
            pending_end: None,
            folder: None,
        }
    }

    fn is_live(&self) -> bool {
        !matches!(
            self.state,
            SessionState::Succeeded | SessionState::Failed | SessionState::Stopped | SessionState::LeaseExpired
        )
    }
}

struct Queued {
    request: OpenStreamRequest,
    session_id: Uuid,
    sent_at: Option<Instant>,
}

/// Mean GPU utilisation of the given GPUs since the last call for them, in percent.
pub type Utilisation = Arc<dyn Fn(&[String]) -> i32 + Send + Sync>;

/// A live attempt's deadlines, read before acting on them: its session, lease end, runtime cap, and the
/// grace end and reason of a stop under way.
type LiveAttempt = (Uuid, Duration, Duration, Option<(Duration, String)>);

/// One minute on the lease clock.
const MINUTE: Duration = Duration::from_secs(60);

/// The sessions this agent runs.
pub struct Supervisor {
    clock: Arc<dyn LeaseClock>,
    utilisation: Utilisation,
    runner: Option<Arc<dyn SessionRunner>>,
    http: Option<reqwest::Client>,
    gpus: HashSet<String>,
    /// Where each attempt's scratch folder goes.
    work_root: PathBuf,
    attempts: HashMap<Uuid, Attempt>,
    seen_commands: HashSet<String>,
    outbox: Vec<Queued>,
    reports: mpsc::UnboundedSender<Tagged>,
    changed: Arc<Notify>,
}

/// A new supervisor, and the receiver its background task ([`supervise`]) reads runner reports from. Attempts'
/// scratch folders go under `work_root`, which is emptied now: nothing runs yet, so anything there is left from
/// before a crash.
///
/// # Errors
/// If a runner is given but the storage client can't be built, or `work_root` can't be emptied.
pub fn new(
    clock: Arc<dyn LeaseClock>,
    utilisation: Utilisation,
    runner: Option<Arc<dyn SessionRunner>>,
    gpus: impl IntoIterator<Item = String>,
    work_root: PathBuf,
) -> Result<(Shared, mpsc::UnboundedReceiver<Tagged>), String> {
    let http = match runner {
        Some(_) => Some(crate::storage::http_client()?),
        None => None,
    };
    if runner.is_some() {
        match std::fs::remove_dir_all(&work_root) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(format!("the work folder couldn't be emptied: {e}")),
        }
    }
    let (reports, received) = mpsc::unbounded_channel();
    let changed = Arc::new(Notify::new());
    let supervisor = Supervisor {
        clock,
        utilisation,
        runner,
        http,
        gpus: gpus.into_iter().collect(),
        work_root,
        attempts: HashMap::new(),
        seen_commands: HashSet::new(),
        outbox: Vec::new(),
        reports,
        changed: Arc::clone(&changed),
    };
    Ok((Shared { inner: Arc::new(Mutex::new(supervisor)), changed }, received))
}

/// Applies runner reports and checks deadlines every `tick`, whether or not a stream is open.
pub async fn supervise(shared: Shared, mut received: mpsc::UnboundedReceiver<Tagged>, tick: Duration) {
    let mut ticks = tokio::time::interval(tick);
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            tagged = received.recv() => match tagged {
                Some(tagged) => shared.lock().apply(tagged),
                None => return,
            },
            _ = ticks.tick() => shared.lock().check_deadlines(),
        }
    }
}

impl Supervisor {
    /// Whether this agent can run sessions at all.
    #[must_use]
    pub fn can_run(&self) -> bool {
        self.runner.is_some()
    }

    /// The attempts still running, for `Hello` and heartbeats.
    pub fn running(&mut self) -> Vec<RunningSession> {
        self.check_deadlines();
        let mut running: Vec<_> = self
            .attempts
            .iter()
            .filter(|(_, attempt)| attempt.is_live())
            .map(|(session_id, attempt)| RunningSession {
                session_id: session_id.to_string(),
                attempt: attempt.number,
                state: attempt.state.into(),
                latest_log_seq: attempt.latest_log_seq,
            })
            .collect();
        running.sort_by(|a, b| a.session_id.cmp(&b.session_id));
        running
    }

    /// A `StartSession`, received at `received_at` on the lease clock. Returns the ack to send, or
    /// `None` for a message too broken to name its session.
    pub fn start(&mut self, message_id: &str, start: StartSession, received_at: Duration) -> Option<OpenStreamRequest> {
        self.check_deadlines();
        let (Ok(session_id), true, true) =
            (Uuid::parse_str(&start.session_id), start.attempt > 0, !start.command_id.is_empty())
        else {
            tracing::warn!("ignored a StartSession without a valid session id, attempt or command id");
            return None;
        };
        if !self.seen_commands.insert(start.command_id.clone()) {
            tracing::info!(%session_id, attempt = start.attempt, "a repeated StartSession; acked, not run again");
            return Some(ack(message_id));
        }
        if let Some(older) = self.attempts.get(&session_id).filter(|a| a.is_live() && a.number != start.attempt) {
            let older = older.number;
            self.end(session_id, SessionState::Stopped, Some(("superseded", "a newer attempt started here")), None);
            tracing::info!(%session_id, older, newer = start.attempt, "stopped an older attempt for a newer one");
        }
        // The new attempt replaces the old one's record: its last status can't wait for its logs any longer.
        self.emit_pending_end(session_id);
        let attempt = start.attempt;
        match self.launch(session_id, start, received_at) {
            Ok(()) => tracing::info!(%session_id, attempt, "started an attempt"),
            Err(reason) => {
                tracing::warn!(%session_id, attempt, %reason, "refused a StartSession");
                self.attempts.insert(session_id, Attempt::idle(attempt, received_at));
                let code = if self.runner.is_none() { "no_runner" } else { "invalid_start_session" };
                self.end(session_id, SessionState::Failed, Some((code, &reason)), None);
            }
        }
        Some(ack(message_id))
    }

    fn launch(&mut self, session_id: Uuid, start: StartSession, received_at: Duration) -> Result<(), String> {
        let (Some(runner), Some(http)) = (self.runner.clone(), self.http.clone()) else {
            return Err("this agent has no session runner".to_owned());
        };
        let lease_ttl = positive(start.lease_ttl).ok_or("lease_ttl is missing or zero")?;
        let spec = start.spec.ok_or("the spec is missing")?;
        if !spec.image.contains("@sha256:") {
            return Err("the image isn't pinned by digest".to_owned());
        }
        if spec.vram_needed_mb <= 0
            || spec.memory_mb <= 0
            || spec.cpu_millicores <= 0
            || spec.pids_limit <= 0
            || spec.scratch_mb <= 0
        {
            return Err("a resource limit in the spec is zero".to_owned());
        }
        positive(spec.checkpoint_interval).ok_or("the checkpoint interval is missing or zero")?;
        let max_runtime = positive(spec.max_runtime).ok_or("max_runtime is missing or zero")?;
        let minute_index_base =
            start.minute_index_base.filter(|base| *base >= 0).ok_or("minute_index_base is missing")?;
        if start.gpu_uuids.is_empty() || start.gpu_uuids.iter().any(|uuid| !self.gpus.contains(uuid)) {
            return Err("gpu_uuids must name this host's GPUs".to_owned());
        }
        let credentials = start.credentials.ok_or("the credentials are missing")?;
        let attempt_prefix = format!("sessions/{session_id}/attempt-{}/", start.attempt);
        let attempt_grant =
            Grant::from_message(credentials.attempt_grant.as_ref().ok_or("no attempt grant")?, received_at)?;
        if attempt_grant.prefixes() != [attempt_prefix.clone()] {
            return Err("the attempt grant doesn't cover exactly this attempt's folder".to_owned());
        }
        let read_grant =
            credentials.read_grant.as_ref().map(|grant| Grant::from_message(grant, received_at)).transpose()?;
        if start.resume_from.as_ref().is_some_and(|from| from.prefix.is_empty() || from.seq <= 0) {
            return Err("resume_from is invalid".to_owned());
        }
        if start.resume_from.is_some() && read_grant.is_none() {
            return Err("resume_from without a read grant".to_owned());
        }
        staging::check_covered(&start.inputs, read_grant.as_ref())?;
        let attempt_s3 = Arc::new(S3Store::new(attempt_grant, http.clone(), Arc::clone(&self.clock))?);
        let read_s3 = match read_grant {
            Some(grant) => Some(Arc::new(S3Store::new(grant, http, Arc::clone(&self.clock))?)),
            None => None,
        };
        let attempt_store: Arc<dyn ObjectStore> = Arc::clone(&attempt_s3) as Arc<dyn ObjectStore>;
        let read_store = read_s3.clone().map(|store| store as Arc<dyn ObjectStore>);
        let fence = Fence::new();
        let (stop_signal, stop_requested) = watch::channel(false);
        let (logs_finish, finish_logs) = watch::channel(false);
        let (number, reports) = (start.attempt, self.reports.clone());
        let (logs, sink) =
            logs::spawn(Arc::clone(&attempt_store), format!("{attempt_prefix}logs/"), finish_logs, move |event| {
                let _ = reports.send(Tagged { session_id, attempt: number, reported: Reported::Logs(event) });
            });
        let folder = self.work_root.join(format!("{session_id}-{}", start.attempt));
        let staging = (!start.inputs.is_empty()).then(|| start.inputs.clone()).zip(read_store.clone());
        let job = Job {
            session_id,
            attempt: start.attempt,
            spec,
            attempt_prefix,
            attempt_store,
            read_store,
            resume_from: start.resume_from,
            fence: fence.clone(),
            stop_requested,
            folder: folder.clone(),
            inputs: BTreeMap::new(),
            logs,
        };
        let tasks = spawn(runner, job, staging, self.reports.clone());
        self.attempts.insert(
            session_id,
            Attempt {
                number: start.attempt,
                deadline: received_at + lease_ttl,
                runtime_cap: received_at + max_runtime,
                fence,
                tasks,
                state: SessionState::Preparing,
                latest_seq: 0,
                minute_index_base,
                gpus: start.gpu_uuids,
                running_since: None,
                minutes_reported: 0,
                stop_signal: Some(stop_signal),
                stopping: None,
                stores: Some((attempt_s3, read_s3)),
                latest_log_seq: 0,
                logs_finish: Some(logs_finish),
                sink: Some(sink.abort_handle()),
                pending_end: None,
                folder: Some(folder),
            },
        );
        Ok(())
    }

    /// A `LeaseRenew`, received at `received_at` on the lease clock: extends the current attempt's
    /// lease; a renewal for any other attempt, or one that already lapsed, is ignored.
    pub fn renew(&mut self, renew: &LeaseRenew, received_at: Duration) {
        self.check_deadlines();
        let ttl = renew.lease_ttl.and_then(|ttl| Duration::try_from(ttl).ok()).filter(|ttl| !ttl.is_zero());
        let (Ok(session_id), Some(ttl)) = (Uuid::parse_str(&renew.session_id), ttl) else {
            tracing::warn!("ignored an invalid LeaseRenew");
            return;
        };
        match self.attempts.get_mut(&session_id) {
            Some(attempt) if attempt.number == renew.attempt && attempt.is_live() => {
                attempt.deadline = attempt.deadline.max(received_at + ttl);
            }
            _ => {
                tracing::debug!(%session_id, attempt = renew.attempt, "ignored a renewal for an attempt not running here");
            }
        }
    }

    /// A `StopSession`: acked; a repeated `command_id` is acked and acted on once. With `checkpoint_first`,
    /// the runner is asked to write a last checkpoint and stop, and the attempt ends when it has, or when
    /// the grace period runs out, whichever is first; without, it ends at once.
    pub fn stop(&mut self, message_id: &str, stop: &StopSession) -> Option<OpenStreamRequest> {
        self.check_deadlines();
        let (Ok(session_id), false) = (Uuid::parse_str(&stop.session_id), stop.command_id.is_empty()) else {
            tracing::warn!("ignored a StopSession without a valid session id or command id");
            return None;
        };
        if self.seen_commands.insert(stop.command_id.clone())
            && self.attempts.get(&session_id).is_some_and(|a| a.number == stop.attempt && a.is_live())
        {
            let reason = match stop.reason() {
                StopReason::RenterRequested => "renter_requested",
                StopReason::CreditExhausted => "credit_exhausted",
                StopReason::MaxSpendReached => "max_spend_reached",
                StopReason::Abuse => "abuse",
                StopReason::Dispute => "dispute",
                StopReason::Superseded => "superseded",
                StopReason::Unspecified => "stopped",
            };
            if let Some(grace) = positive(stop.grace_period).filter(|_| stop.checkpoint_first) {
                let deadline = self.clock.now() + grace;
                if let Some(attempt) = self.attempts.get_mut(&session_id) {
                    attempt.stopping = Some((deadline, reason.to_owned()));
                    if let Some(signal) = &attempt.stop_signal {
                        let _ = signal.send(true);
                    }
                }
                tracing::info!(%session_id, attempt = stop.attempt, reason, ?grace, "stopping an attempt after a checkpoint");
            } else {
                self.end(session_id, SessionState::Stopped, Some((reason, "stopped by the control plane")), None);
                tracing::info!(%session_id, attempt = stop.attempt, reason, "stopped an attempt");
            }
        }
        Some(ack(message_id))
    }

    /// A `RefreshCredentials`, received at `received_at` on the lease clock: the running attempt's grants are
    /// replaced in place, without restarting its job. Acked; credentials for any other attempt are ignored.
    pub fn refresh(
        &mut self,
        message_id: &str,
        refresh: &RefreshCredentials,
        received_at: Duration,
    ) -> Option<OpenStreamRequest> {
        self.check_deadlines();
        let Ok(session_id) = Uuid::parse_str(&refresh.session_id) else {
            tracing::warn!("ignored a RefreshCredentials without a valid session id");
            return None;
        };
        let stores = self
            .attempts
            .get(&session_id)
            .filter(|attempt| attempt.number == refresh.attempt && attempt.is_live())
            .and_then(|attempt| attempt.stores.clone());
        let Some((attempt_store, read_store)) = stores else {
            tracing::info!(%session_id, attempt = refresh.attempt, "ignored fresh credentials for an attempt not running here");
            return Some(ack(message_id));
        };
        let result = (|| {
            let credentials = refresh.credentials.as_ref().ok_or("no credentials")?;
            let grant = credentials.attempt_grant.as_ref().ok_or("no attempt grant")?;
            attempt_store.replace(Grant::from_message(grant, received_at)?)?;
            if let (Some(store), Some(grant)) = (read_store, credentials.read_grant.as_ref()) {
                store.replace(Grant::from_message(grant, received_at)?)?;
            }
            Ok::<(), String>(())
        })();
        match result {
            Ok(()) => tracing::info!(%session_id, attempt = refresh.attempt, "storage credentials refreshed"),
            Err(reason) => tracing::warn!(%session_id, %reason, "refused fresh credentials; keeping the current ones"),
        }
        Some(ack(message_id))
    }

    /// The control plane acked one of our messages.
    pub fn acked(&mut self, message_id: &str) {
        self.outbox.retain(|queued| queued.request.message_id != message_id);
        // An ended attempt is forgotten once the control plane has everything it reported, its last status included.
        let waiting: HashSet<Uuid> = self.outbox.iter().map(|queued| queued.session_id).collect();
        self.attempts.retain(|session_id, attempt| {
            attempt.is_live() || attempt.pending_end.is_some() || waiting.contains(session_id)
        });
    }

    /// Statuses to send now: those never sent on this stream, and those unacked for `resend_after`.
    pub fn due(&mut self, now: Instant, resend_after: Duration) -> Vec<OpenStreamRequest> {
        self.check_deadlines();
        self.outbox
            .iter_mut()
            .filter(|queued| queued.sent_at.is_none_or(|sent| now.duration_since(sent) >= resend_after))
            .map(|queued| {
                queued.sent_at = Some(now);
                queued.request.clone()
            })
            .collect()
    }

    /// A new stream: everything unacked goes out again.
    pub fn unsend_all(&mut self) {
        for queued in &mut self.outbox {
            queued.sent_at = None;
        }
    }

    /// Stops every attempt whose lease or runtime cap has passed, and reports every whole minute
    /// that has ended.
    pub fn check_deadlines(&mut self) {
        let now = self.clock.now();
        let overdue: Vec<Uuid> = self
            .attempts
            .iter()
            .filter(|(_, attempt)| attempt.pending_end.as_ref().is_some_and(|end| now >= end.since + END_WAITS_AT_MOST))
            .map(|(session_id, _)| *session_id)
            .collect();
        for session_id in overdue {
            tracing::warn!(%session_id, "the logs didn't flush in time; sending the last status anyway");
            if let Some(sink) = self.attempts.get_mut(&session_id).and_then(|attempt| attempt.sink.take()) {
                sink.abort();
            }
            self.emit_pending_end(session_id);
        }
        let live: Vec<LiveAttempt> = self
            .attempts
            .iter()
            .filter(|(_, attempt)| attempt.is_live())
            .map(|(session_id, attempt)| (*session_id, attempt.deadline, attempt.runtime_cap, attempt.stopping.clone()))
            .collect();
        for (session_id, deadline, runtime_cap, stopping) in live {
            if now >= deadline {
                // Only the minutes that ended inside the lease; nothing after it, ever.
                self.report_minutes(session_id, deadline);
                tracing::warn!(%session_id, "the lease ran out before it was renewed; the work is stopped");
                self.end(session_id, SessionState::LeaseExpired, Some(("lease_expired", "the lease ran out")), None);
            } else if now >= runtime_cap {
                tracing::warn!(%session_id, "the attempt reached its maximum runtime; the work is stopped");
                self.end(
                    session_id,
                    SessionState::Failed,
                    Some(("max_runtime_exceeded", "ran past max_runtime")),
                    None,
                );
            } else if let Some((grace_end, reason)) = stopping.filter(|(grace_end, _)| now >= *grace_end) {
                tracing::warn!(%session_id, "no checkpoint within the grace period; stopping anyway");
                let _ = grace_end;
                self.end(session_id, SessionState::Stopped, Some((&reason, "stopped by the control plane")), None);
            } else {
                self.report_minutes(session_id, now);
            }
        }
    }

    /// Queues a usage report for every whole minute of the attempt that ended by `until`.
    fn report_minutes(&mut self, session_id: Uuid, until: Duration) {
        loop {
            let Some(attempt) = self.attempts.get_mut(&session_id) else { return };
            let Some(since) = attempt.running_since else { return };
            let minute = u32::try_from(attempt.minutes_reported).unwrap_or(u32::MAX);
            if since + MINUTE * (minute + 1) > until {
                return;
            }
            attempt.minutes_reported += 1;
            let index = attempt.minute_index_base + attempt.minutes_reported - 1;
            self.queue_usage(session_id, index, 60);
        }
    }

    /// The last, partial minute of an attempt that just ended, with the seconds it ran.
    fn report_last_minute(&mut self, session_id: Uuid) {
        let now = self.clock.now();
        self.report_minutes(session_id, now);
        let Some(attempt) = self.attempts.get(&session_id) else { return };
        let Some(since) = attempt.running_since else { return };
        let minute = u32::try_from(attempt.minutes_reported).unwrap_or(u32::MAX);
        let ran = now.saturating_sub(since + MINUTE * minute);
        if ran.is_zero() {
            return;
        }
        let seconds = i32::try_from(ran.as_secs() + u64::from(ran.subsec_nanos() > 0)).unwrap_or(60).clamp(1, 60);
        let index = attempt.minute_index_base + attempt.minutes_reported;
        self.queue_usage(session_id, index, seconds);
    }

    fn queue_usage(&mut self, session_id: Uuid, minute_index: i32, gpu_seconds: i32) {
        let Some(attempt) = self.attempts.get(&session_id) else { return };
        let report = UsageReport {
            session_id: session_id.to_string(),
            attempt: attempt.number,
            minute_index: Some(minute_index),
            gpu_seconds,
            avg_util_pct: (self.utilisation)(&attempt.gpus),
        };
        self.outbox.push(Queued {
            request: OpenStreamRequest { message_id: new_message_id(), body: Some(AgentBody::UsageReport(report)) },
            session_id,
            sent_at: None,
        });
        self.changed.notify_one();
    }

    /// What a runner reported. Anything about an attempt that has ended, or been replaced, is dropped.
    fn apply(&mut self, tagged: Tagged) {
        self.check_deadlines();
        let Some(attempt) = self.attempts.get_mut(&tagged.session_id) else { return };
        if attempt.number != tagged.attempt {
            return;
        }
        // The log sink outlives the job: it reports after the attempt has ended.
        if let Reported::Logs(event) = tagged.reported {
            match event {
                LogEvent::Uploaded(seq) => attempt.latest_log_seq = attempt.latest_log_seq.max(seq),
                LogEvent::Flushed(seq) => {
                    attempt.latest_log_seq = attempt.latest_log_seq.max(seq);
                    attempt.sink = None;
                    self.emit_pending_end(tagged.session_id);
                }
            }
            return;
        }
        if !attempt.is_live() {
            return;
        }
        match tagged.reported {
            Reported::Report(Report::State(state)) => {
                attempt.state = state;
                if state == SessionState::Running && attempt.running_since.is_none() {
                    attempt.running_since = Some(self.clock.now());
                }
                self.queue_status(tagged.session_id, None, None);
            }
            Reported::Report(Report::Checkpoint(seq)) => {
                attempt.latest_seq = attempt.latest_seq.max(seq);
                self.queue_status(tagged.session_id, None, None);
            }
            Reported::Report(Report::Succeeded) => self.end(tagged.session_id, SessionState::Succeeded, None, Some(0)),
            Reported::Report(Report::Stopped) => {
                let reason =
                    attempt.stopping.as_ref().map_or_else(|| "stopped".to_owned(), |(_, reason)| reason.clone());
                self.end(
                    tagged.session_id,
                    SessionState::Stopped,
                    Some((&reason, "stopped by the control plane")),
                    None,
                );
            }
            Reported::Report(Report::Failed { code, message, exit_code }) => {
                self.end(tagged.session_id, SessionState::Failed, Some((&code, &message)), exit_code);
            }
            Reported::Ended { panicked } => {
                let (code, message) = if panicked {
                    ("runner_panicked", "the runner crashed")
                } else {
                    ("runner_stopped", "the runner ended without a result")
                };
                self.end(tagged.session_id, SessionState::Failed, Some((code, message)), None);
            }
            Reported::Logs(_) => unreachable!("handled above"),
        }
    }

    /// Ends an attempt in `state`: fence down, its tasks aborted, its scratch folder wiped, its last minute
    /// reported (unless its lease lapsed, when the caller has reported what ended inside it), and its status
    /// queued once its logs have flushed.
    fn end(&mut self, session_id: Uuid, state: SessionState, error: Option<(&str, &str)>, exit_code: Option<i32>) {
        if state != SessionState::LeaseExpired {
            self.report_last_minute(session_id);
        }
        let now = self.clock.now();
        let Some(attempt) = self.attempts.get_mut(&session_id) else { return };
        attempt.fence.take_down();
        for task in attempt.tasks.drain(..) {
            task.abort();
        }
        attempt.state = state;
        if let Some(folder) = attempt.folder.take() {
            tokio::spawn(async move {
                if let Err(e) = tokio::fs::remove_dir_all(&folder).await
                    && e.kind() != std::io::ErrorKind::NotFound
                {
                    tracing::warn!(%session_id, error = %e, "the attempt's scratch folder couldn't be wiped");
                }
            });
        }
        match attempt.logs_finish.take() {
            Some(finish) => {
                let _ = finish.send(true);
                attempt.pending_end = Some(PendingEnd {
                    error: error.map(|(code, message)| (code.to_owned(), message.to_owned())),
                    exit_code,
                    since: now,
                });
            }
            None => self.queue_status(session_id, error, exit_code),
        }
    }

    /// Queues an ended attempt's terminal status, if it's waiting for its logs.
    fn emit_pending_end(&mut self, session_id: Uuid) {
        let Some(end) = self.attempts.get_mut(&session_id).and_then(|attempt| attempt.pending_end.take()) else {
            return;
        };
        let error = end.error.as_ref().map(|(code, message)| (code.as_str(), message.as_str()));
        self.queue_status(session_id, error, end.exit_code);
    }

    fn queue_status(&mut self, session_id: Uuid, error: Option<(&str, &str)>, exit_code: Option<i32>) {
        let Some(attempt) = self.attempts.get(&session_id) else { return };
        let status = SessionStatus {
            session_id: session_id.to_string(),
            attempt: attempt.number,
            state: attempt.state.into(),
            exit_code,
            error: error.map(|(code, message)| SessionError { code: code.to_owned(), message: message.to_owned() }),
            latest_checkpoint_seq: attempt.latest_seq,
            latest_log_seq: attempt.latest_log_seq,
        };
        self.outbox.push(Queued {
            request: OpenStreamRequest { message_id: new_message_id(), body: Some(AgentBody::SessionStatus(status)) },
            session_id,
            sent_at: None,
        });
        self.changed.notify_one();
    }
}

/// Runs the job in its own task, forwarding its reports tagged with the attempt, then saying it ended. Uploaded
/// inputs are staged first, through the read store; if they can't be, the job never starts and the attempt fails
/// `input_unavailable`, before RUNNING.
fn spawn(
    runner: Arc<dyn SessionRunner>,
    mut job: Job,
    staging: Option<(Vec<InputRef>, Arc<dyn ObjectStore>)>,
    reports: mpsc::UnboundedSender<Tagged>,
) -> Vec<AbortHandle> {
    let (session_id, attempt) = (job.session_id, job.attempt);
    let (sender, mut receiver) = mpsc::unbounded_channel();
    let mut work: JoinHandle<()> = match staging {
        None => tokio::spawn(runner.run(job, sender)),
        Some((inputs, store)) => tokio::spawn(async move {
            let _ = sender.send(Report::State(SessionState::Preparing));
            match staging::stage(store, &inputs, &job.folder, &job.logs).await {
                Ok(folders) => job.inputs = folders,
                Err(message) => {
                    let _ =
                        sender.send(Report::Failed { code: "input_unavailable".to_owned(), message, exit_code: None });
                    return;
                }
            }
            runner.run(job, sender).await;
        }),
    };
    let work_handle = work.abort_handle();
    let tag = move |reported| Tagged { session_id, attempt, reported };
    let watcher = tokio::spawn(async move {
        loop {
            tokio::select! {
                Some(report) = receiver.recv() => {
                    let _ = reports.send(tag(Reported::Report(report)));
                }
                result = &mut work => {
                    while let Ok(report) = receiver.try_recv() {
                        let _ = reports.send(tag(Reported::Report(report)));
                    }
                    let panicked = result.as_ref().is_err_and(tokio::task::JoinError::is_panic);
                    let _ = reports.send(tag(Reported::Ended { panicked }));
                    return;
                }
            }
        }
    });
    vec![work_handle, watcher.abort_handle()]
}

/// A protobuf duration that's present and above zero.
fn positive<D>(value: Option<D>) -> Option<Duration>
where
    Duration: TryFrom<D>,
{
    value.and_then(|value| Duration::try_from(value).ok()).filter(|value| !value.is_zero())
}

fn ack(message_id: &str) -> OpenStreamRequest {
    OpenStreamRequest {
        message_id: new_message_id(),
        body: Some(AgentBody::Ack(Ack { acked_message_id: message_id.to_owned() })),
    }
}

#[cfg(test)]
mod tests {
    use std::future::Future;
    use std::pin::Pin;

    use agent_proto::v1::{Credentials, SessionSpec, StorageGrant, StoragePermission};

    use super::*;
    use crate::lease_clock::FakeLeaseClock;

    const GPU: &str = "GPU-00000000-fake-0000-0000-000000000001";

    /// A runner that runs until aborted; the test speaks for it through the sender it was given.
    #[derive(Default)]
    struct FakeRunner {
        started: Mutex<Vec<(Fence, mpsc::UnboundedSender<Report>)>>,
        stops: Mutex<Vec<watch::Receiver<bool>>>,
    }

    impl SessionRunner for FakeRunner {
        fn run(&self, job: Job, reports: mpsc::UnboundedSender<Report>) -> Pin<Box<dyn Future<Output = ()> + Send>> {
            self.started.lock().unwrap().push((job.fence, reports));
            self.stops.lock().unwrap().push(job.stop_requested);
            Box::pin(std::future::pending())
        }
    }

    struct Setup {
        shared: Shared,
        received: mpsc::UnboundedReceiver<Tagged>,
        clock: Arc<FakeLeaseClock>,
        runner: Arc<FakeRunner>,
        work: tempfile::TempDir,
    }

    fn setup() -> Setup {
        let clock = Arc::new(FakeLeaseClock::default());
        let runner = Arc::new(FakeRunner::default());
        let work = tempfile::tempdir().unwrap();
        let (shared, received) = new(
            Arc::clone(&clock) as Arc<dyn LeaseClock>,
            Arc::new(|_: &[String]| 42),
            Some(Arc::clone(&runner) as Arc<dyn SessionRunner>),
            HashSet::from([GPU.to_owned()]),
            work.path().join("work"),
        )
        .unwrap();
        Setup { shared, received, clock, runner, work }
    }

    /// Applies everything the attempts' tasks have reported so far: their runners' reports, and their log
    /// sinks', so an ended attempt's terminal status, which waits for its logs to flush, is queued.
    async fn settle(setup: &mut Setup) {
        while let Ok(Some(tagged)) = tokio::time::timeout(Duration::from_millis(100), setup.received.recv()).await {
            setup.shared.lock().apply(tagged);
        }
    }

    fn seconds(seconds: i64) -> prost_types::Duration {
        prost_types::Duration { seconds, nanos: 0 }
    }

    fn start(session_id: Uuid, attempt: i32, command_id: &str) -> StartSession {
        StartSession {
            session_id: session_id.to_string(),
            command_id: command_id.to_owned(),
            attempt,
            spec: Some(SessionSpec {
                image: "registry.example/smoke@sha256:0123".to_owned(),
                args: vec!["--duration".to_owned(), "20s".to_owned()],
                vram_needed_mb: 1024,
                memory_mb: 2048,
                cpu_millicores: 2000,
                pids_limit: 256,
                scratch_mb: 1024,
                checkpoint_interval: Some(seconds(5)),
                max_runtime: Some(seconds(300)),
                ..SessionSpec::default()
            }),
            credentials: Some(Credentials {
                attempt_grant: Some(StorageGrant {
                    endpoint: "http://storage.invalid:9000".to_owned(),
                    bucket: "idleo-test".to_owned(),
                    access_key_id: "TESTKEY".to_owned(),
                    secret_access_key: "test-secret".to_owned(),
                    session_token: "test-token".to_owned(),
                    prefixes: vec![format!("sessions/{session_id}/attempt-{attempt}/")],
                    permission: StoragePermission::ReadWrite.into(),
                    expires_in: Some(seconds(3600)),
                }),
                ..Credentials::default()
            }),
            lease_ttl: Some(seconds(120)),
            minute_index_base: Some(0),
            gpu_uuids: vec![GPU.to_owned()],
            resume_from: None,
            input: None,
            inputs: Vec::new(),
        }
    }

    fn statuses(shared: &Shared) -> Vec<SessionStatus> {
        shared
            .lock()
            .due(Instant::now(), Duration::from_secs(3600))
            .into_iter()
            .filter_map(|request| match request.body {
                Some(AgentBody::SessionStatus(status)) => Some(status),
                _ => None,
            })
            .collect()
    }

    fn acked(ack: Option<OpenStreamRequest>, message_id: &str) -> bool {
        matches!(ack.and_then(|a| a.body), Some(AgentBody::Ack(Ack { acked_message_id })) if acked_message_id == message_id)
    }

    #[tokio::test]
    async fn a_start_is_acked_and_a_repeat_is_acked_but_never_run_twice() {
        let setup = setup();
        let session = Uuid::now_v7();

        assert!(acked(setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO), "m1"));
        assert!(acked(setup.shared.lock().start("m2", start(session, 1, "c1"), Duration::ZERO), "m2"));

        assert_eq!(setup.runner.started.lock().unwrap().len(), 1);
        assert_eq!(setup.shared.lock().running().len(), 1);
    }

    #[tokio::test]
    async fn runner_reports_become_statuses_kept_until_acked_and_resent_on_a_new_stream() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        let reports = setup.runner.started.lock().unwrap()[0].1.clone();
        reports.send(Report::State(SessionState::Running)).unwrap();
        reports.send(Report::Checkpoint(1)).unwrap();
        for _ in 0..2 {
            let tagged = setup.received.recv().await.unwrap();
            setup.shared.lock().apply(tagged);
        }

        let sent = statuses(&setup.shared);
        assert_eq!(sent.len(), 2);
        assert_eq!(sent[0].state(), SessionState::Running);
        assert_eq!(sent[1].latest_checkpoint_seq, 1);
        assert!(statuses(&setup.shared).is_empty(), "not resent before resend_after");

        setup.shared.lock().unsend_all();
        let resent = setup.shared.lock().due(Instant::now(), Duration::from_secs(3600));
        assert_eq!(resent.len(), 2, "a new stream gets everything unacked");
        setup.shared.lock().acked(&resent[0].message_id);
        setup.shared.lock().unsend_all();
        assert_eq!(statuses(&setup.shared).len(), 1);
    }

    #[tokio::test]
    async fn a_lapsed_lease_stops_the_work_and_is_reported_once() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        let fence = setup.runner.started.lock().unwrap()[0].0.clone();

        setup.clock.advance(Duration::from_secs(119));
        setup.shared.lock().check_deadlines();
        assert!(fence.is_up());
        setup.clock.advance(Duration::from_secs(1));
        setup.shared.lock().check_deadlines();
        setup.shared.lock().check_deadlines();
        settle(&mut setup).await;

        assert!(!fence.is_up());
        let sent = statuses(&setup.shared);
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].state(), SessionState::LeaseExpired);
        assert_eq!(sent[0].error.as_ref().unwrap().code, "lease_expired");
        assert_eq!(setup.shared.lock().running(), Vec::new());
    }

    #[tokio::test]
    async fn a_renewal_extends_only_the_current_attempt() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 2, "c1"), Duration::ZERO);
        let renewal = |attempt| LeaseRenew { session_id: session.to_string(), attempt, lease_ttl: Some(seconds(120)) };

        setup.clock.advance(Duration::from_secs(100));
        setup.shared.lock().renew(&renewal(1), setup.clock.now());
        setup.clock.advance(Duration::from_secs(20));
        setup.shared.lock().check_deadlines();
        settle(&mut setup).await;
        assert_eq!(
            statuses(&setup.shared)[0].state(),
            SessionState::LeaseExpired,
            "another attempt's renewal is ignored"
        );

        let setup = self::setup();
        setup.shared.lock().start("m1", start(session, 2, "c1"), Duration::ZERO);
        setup.clock.advance(Duration::from_secs(100));
        setup.shared.lock().renew(&renewal(2), setup.clock.now());
        setup.clock.advance(Duration::from_secs(100));
        setup.shared.lock().check_deadlines();
        assert_eq!(setup.shared.lock().running().len(), 1, "renewed at 100 s, so alive until 220 s");
    }

    #[tokio::test]
    async fn after_a_sleep_past_the_deadline_the_first_thing_that_happens_is_the_stop() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        let fence = setup.runner.started.lock().unwrap()[0].0.clone();

        // The lid closes for 20 minutes; on waking, a renewal is the first thing to arrive.
        setup.clock.advance(Duration::from_mins(20));
        setup.shared.lock().renew(
            &LeaseRenew { session_id: session.to_string(), attempt: 1, lease_ttl: Some(seconds(120)) },
            setup.clock.now(),
        );

        assert!(!fence.is_up());
        assert!(setup.shared.lock().running().is_empty(), "the late renewal doesn't revive it");
        settle(&mut setup).await;
        assert_eq!(statuses(&setup.shared)[0].state(), SessionState::LeaseExpired);
    }

    #[tokio::test]
    async fn reports_after_the_fence_are_dropped() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        setup.clock.advance(Duration::from_secs(121));
        setup.shared.lock().check_deadlines();
        settle(&mut setup).await;
        assert_eq!(statuses(&setup.shared).len(), 1, "the lapse");

        // A report that was already on its way when the task was aborted.
        setup.shared.lock().apply(Tagged {
            session_id: session,
            attempt: 1,
            reported: Reported::Report(Report::Succeeded),
        });

        assert_eq!(statuses(&setup.shared), Vec::new());
    }

    #[tokio::test]
    async fn an_invalid_start_is_acked_and_reported_failed() {
        let setup = setup();
        let session = Uuid::now_v7();
        let mut bad = start(session, 1, "c1");
        bad.gpu_uuids = vec!["GPU-someone-else".to_owned()];

        assert!(acked(setup.shared.lock().start("m1", bad, Duration::ZERO), "m1"));

        let sent = statuses(&setup.shared);
        assert_eq!(sent[0].state(), SessionState::Failed);
        assert_eq!(sent[0].error.as_ref().unwrap().code, "invalid_start_session");
        assert!(setup.runner.started.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_stop_ends_the_attempt_once() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        let stop = StopSession {
            session_id: session.to_string(),
            attempt: 1,
            command_id: "s1".to_owned(),
            reason: StopReason::Superseded.into(),
            ..StopSession::default()
        };

        assert!(acked(setup.shared.lock().stop("m2", &stop), "m2"));
        assert!(acked(setup.shared.lock().stop("m3", &stop), "m3"));
        settle(&mut setup).await;

        let sent = statuses(&setup.shared);
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].state(), SessionState::Stopped);
        assert_eq!(sent[0].error.as_ref().unwrap().code, "superseded");
    }

    fn usage(shared: &Shared) -> Vec<UsageReport> {
        shared
            .lock()
            .due(Instant::now(), Duration::from_secs(3600))
            .into_iter()
            .filter_map(|request| match request.body {
                Some(AgentBody::UsageReport(report)) => Some(report),
                _ => None,
            })
            .collect()
    }

    /// Starts an attempt whose minutes begin at `base`, and reports it RUNNING at the clock's now.
    async fn running(setup: &mut Setup, session: Uuid, base: i32) {
        let mut message = start(session, 1, "c1");
        message.minute_index_base = Some(base);
        setup.shared.lock().start("m1", message, setup.clock.now());
        let reports = setup.runner.started.lock().unwrap()[0].1.clone();
        reports.send(Report::State(SessionState::Running)).unwrap();
        let tagged = setup.received.recv().await.unwrap();
        setup.shared.lock().apply(tagged);
    }

    fn renew_now(setup: &Setup, session: Uuid) {
        setup.shared.lock().renew(
            &LeaseRenew { session_id: session.to_string(), attempt: 1, lease_ttl: Some(seconds(120)) },
            setup.clock.now(),
        );
    }

    #[tokio::test]
    async fn each_whole_minute_is_reported_from_running_then_the_partial_last_one() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.clock.advance(Duration::from_secs(7)); // PREPARING takes 7 s; minutes count from RUNNING
        running(&mut setup, session, 4).await;

        setup.clock.advance(Duration::from_secs(59));
        setup.shared.lock().check_deadlines();
        assert_eq!(usage(&setup.shared), Vec::new(), "not yet a minute");
        setup.clock.advance(Duration::from_secs(1));
        renew_now(&setup, session);
        setup.clock.advance(Duration::from_secs(60));
        setup.shared.lock().check_deadlines();
        let whole = usage(&setup.shared);
        assert_eq!(
            whole.iter().map(|r| (r.minute_index, r.gpu_seconds)).collect::<Vec<_>>(),
            vec![(Some(4), 60), (Some(5), 60)]
        );
        assert_eq!(whole[0].avg_util_pct, 42);
        assert_eq!(whole[0].attempt, 1);

        setup.clock.advance(Duration::from_millis(30_500));
        let reports = setup.runner.started.lock().unwrap()[0].1.clone();
        reports.send(Report::Succeeded).unwrap();
        settle(&mut setup).await;

        let queued = setup.shared.lock().due(Instant::now(), Duration::from_secs(3600));
        let Some(AgentBody::UsageReport(last)) = &queued[0].body else { panic!("usage before the final status") };
        assert_eq!((last.minute_index, last.gpu_seconds), (Some(6), 31), "a started second counts");
        assert!(
            matches!(&queued[1].body, Some(AgentBody::SessionStatus(status)) if status.state() == SessionState::Succeeded)
        );
    }

    #[tokio::test]
    async fn after_the_lease_lapses_nothing_more_is_reported() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        running(&mut setup, session, 0).await;

        // Asleep through the deadline at 120 s: the minutes ending at 60 s and 120 s ran inside the
        // lease; nothing after it is reported, not even a partial minute.
        setup.clock.advance(Duration::from_secs(400));
        setup.shared.lock().check_deadlines();

        let indexes: Vec<_> = usage(&setup.shared).iter().map(|r| r.minute_index).collect();
        assert_eq!(indexes, vec![Some(0), Some(1)]);
        setup.clock.advance(Duration::from_secs(120));
        setup.shared.lock().check_deadlines();
        assert_eq!(usage(&setup.shared), Vec::new());
    }

    #[tokio::test]
    async fn unacked_usage_is_resent_on_a_new_stream_and_dropped_once_acked() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        running(&mut setup, session, 0).await;
        setup.clock.advance(Duration::from_secs(61));
        setup.shared.lock().check_deadlines();
        let first = usage(&setup.shared);
        assert_eq!(first.len(), 1);

        setup.shared.lock().unsend_all();
        let resent = setup.shared.lock().due(Instant::now(), Duration::from_secs(3600));
        let usage_message =
            resent.iter().find(|request| matches!(request.body, Some(AgentBody::UsageReport(_)))).unwrap().clone();
        setup.shared.lock().acked(&usage_message.message_id);
        setup.shared.lock().unsend_all();
        assert_eq!(usage(&setup.shared), Vec::new());
    }

    fn stop_after_checkpoint(session: Uuid, command_id: &str) -> StopSession {
        stop_for(session, command_id, StopReason::RenterRequested)
    }

    fn stop_for(session: Uuid, command_id: &str, reason: StopReason) -> StopSession {
        StopSession {
            session_id: session.to_string(),
            attempt: 1,
            command_id: command_id.to_owned(),
            reason: reason.into(),
            checkpoint_first: true,
            grace_period: Some(seconds(60)),
        }
    }

    #[tokio::test]
    async fn a_checkpoint_first_stop_waits_for_the_runners_last_checkpoint() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);

        setup.shared.lock().stop("m2", &stop_after_checkpoint(session, "s1"));

        assert!(*setup.runner.stops.lock().unwrap()[0].borrow(), "the runner was asked");
        assert_eq!(setup.shared.lock().running().len(), 1, "still running while it checkpoints");
        let reports = setup.runner.started.lock().unwrap()[0].1.clone();
        reports.send(Report::Checkpoint(3)).unwrap();
        reports.send(Report::Stopped).unwrap();
        settle(&mut setup).await;
        let last = statuses(&setup.shared).pop().unwrap();
        assert_eq!(last.state(), SessionState::Stopped);
        assert_eq!(last.latest_checkpoint_seq, 3);
        assert_eq!(last.error.unwrap().code, "renter_requested");
    }

    #[tokio::test]
    async fn a_stop_at_the_spending_cap_says_so_in_the_final_status() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);

        setup.shared.lock().stop("m2", &stop_for(session, "s1", StopReason::MaxSpendReached));
        let reports = setup.runner.started.lock().unwrap()[0].1.clone();
        reports.send(Report::Checkpoint(2)).unwrap();
        reports.send(Report::Stopped).unwrap();
        settle(&mut setup).await;

        let last = statuses(&setup.shared).pop().unwrap();
        assert_eq!(last.state(), SessionState::Stopped);
        assert_eq!(last.error.unwrap().code, "max_spend_reached");
    }

    #[tokio::test]
    async fn a_runner_that_doesnt_checkpoint_in_time_is_stopped_when_the_grace_ends() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        let fence = setup.runner.started.lock().unwrap()[0].0.clone();
        setup.shared.lock().stop("m2", &stop_after_checkpoint(session, "s1"));

        setup.clock.advance(Duration::from_secs(59));
        setup.shared.lock().check_deadlines();
        assert!(fence.is_up());
        setup.clock.advance(Duration::from_secs(1));
        setup.shared.lock().check_deadlines();
        settle(&mut setup).await;

        assert!(!fence.is_up());
        assert_eq!(statuses(&setup.shared).pop().unwrap().state(), SessionState::Stopped);
    }

    #[tokio::test]
    async fn fresh_credentials_are_acked_and_ignored_for_another_attempt() {
        let setup = setup();
        let session = Uuid::now_v7();
        let message = start(session, 1, "c1");
        let credentials = message.credentials.clone();
        setup.shared.lock().start("m1", message, Duration::ZERO);

        let refresh =
            |attempt| RefreshCredentials { session_id: session.to_string(), attempt, credentials: credentials.clone() };
        assert!(acked(setup.shared.lock().refresh("m2", &refresh(1), Duration::from_mins(30)), "m2"));
        assert!(acked(setup.shared.lock().refresh("m3", &refresh(2), Duration::from_mins(30)), "m3"));
        assert_eq!(setup.shared.lock().running().len(), 1, "the job carries on");
    }

    #[tokio::test]
    async fn an_input_that_cant_be_staged_fails_the_attempt_before_it_runs() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        let upload = "uploads/0190f3a2-0000-7000-8000-00000000000a/";
        let mut message = start(session, 1, "c1");
        let mut read_grant = message.credentials.as_ref().unwrap().attempt_grant.clone().unwrap();
        read_grant.prefixes = vec![upload.to_owned()];
        read_grant.permission = StoragePermission::ReadOnly.into();
        message.credentials.as_mut().unwrap().read_grant = Some(read_grant);
        message.inputs = vec![InputRef {
            prefix: upload.to_owned(),
            name: "train.jsonl".to_owned(),
            size_bytes: 12,
            slot: "data".to_owned(),
        }];

        setup.shared.lock().start("m1", message, Duration::ZERO);
        let mut sent = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(30);
        while !sent.iter().any(|status: &SessionStatus| status.state() == SessionState::Failed) {
            assert!(Instant::now() < deadline, "no FAILED status: {sent:?}");
            settle(&mut setup).await;
            sent.extend(statuses(&setup.shared));
        }

        assert_eq!(
            sent.iter().map(SessionStatus::state).collect::<Vec<_>>(),
            vec![SessionState::Preparing, SessionState::Failed],
            "never RUNNING, so nothing is billed"
        );
        assert_eq!(sent[1].error.as_ref().unwrap().code, "input_unavailable");
        assert!(setup.runner.started.lock().unwrap().is_empty(), "the job never started");
        assert!(!setup.work.path().join(format!("work/{session}-1")).exists(), "the scratch folder is wiped");
    }

    #[tokio::test]
    async fn an_input_outside_the_read_grant_is_refused() {
        let setup = setup();
        let session = Uuid::now_v7();
        let mut message = start(session, 1, "c1");
        message.inputs = vec![InputRef {
            prefix: "uploads/0190f3a2-0000-7000-8000-00000000000a/".to_owned(),
            name: "train.jsonl".to_owned(),
            size_bytes: 12,
            slot: "data".to_owned(),
        }];

        setup.shared.lock().start("m1", message, Duration::ZERO);

        let sent = statuses(&setup.shared);
        assert_eq!(sent[0].state(), SessionState::Failed);
        assert_eq!(sent[0].error.as_ref().unwrap().code, "invalid_start_session");
    }

    #[tokio::test]
    async fn heartbeats_and_the_last_status_carry_the_newest_log_chunk() {
        let mut setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        for seq in [1, 3, 2] {
            setup.shared.lock().apply(Tagged {
                session_id: session,
                attempt: 1,
                reported: Reported::Logs(LogEvent::Uploaded(seq)),
            });
        }
        assert_eq!(setup.shared.lock().running()[0].latest_log_seq, 3, "only ever raised");

        let reports = setup.runner.started.lock().unwrap()[0].1.clone();
        reports.send(Report::Succeeded).unwrap();
        settle(&mut setup).await;

        let last = statuses(&setup.shared).pop().unwrap();
        assert_eq!(last.state(), SessionState::Succeeded);
        assert_eq!(last.latest_log_seq, 3);
    }

    #[tokio::test]
    async fn without_a_runner_a_start_fails_rather_than_hanging() {
        let (shared, _received) = new(
            Arc::new(FakeLeaseClock::default()),
            Arc::new(|_: &[String]| 0),
            None,
            HashSet::from([GPU.to_owned()]),
            std::env::temp_dir().join("idleo-unused"),
        )
        .unwrap();
        let session = Uuid::now_v7();

        shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);

        assert_eq!(statuses(&shared)[0].error.as_ref().unwrap().code, "no_runner");
    }
}
