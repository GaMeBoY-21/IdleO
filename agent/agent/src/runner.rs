//! Running one attempt of a session (ADR 0016). The supervisor hands a [`Job`] to a
//! [`SessionRunner`], which reports progress as [`Report`]s and checks the job's [`Fence`] before
//! anything with a side effect.
//!
//! The only runner today is the development runner ([`dev::DevRunner`], `--dev-runner`, only with
//! `--fake-gpu`), which simulates a job against real object storage. The Docker runner, with the
//! technical design's sandbox, comes with the Windows host.

pub mod dev;

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use agent_proto::v1::{CheckpointRef, SessionSpec, SessionState};
use tokio::sync::{mpsc, watch};
use uuid::Uuid;

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
    /// Becomes true when the control plane asks for a checkpoint and a stop: the runner writes one last
    /// checkpoint, reports [`Report::Stopped`] and returns, within the grace period the supervisor enforces.
    pub stop_requested: watch::Receiver<bool>,
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
