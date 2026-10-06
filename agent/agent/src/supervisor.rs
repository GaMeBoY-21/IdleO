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

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body as AgentBody;
use agent_proto::v1::{
    Ack, LeaseRenew, OpenStreamRequest, RunningSession, SessionError, SessionState, SessionStatus, StartSession,
    StopReason, StopSession,
};
use tokio::sync::{Notify, mpsc};
use tokio::task::{AbortHandle, JoinHandle};
use tokio::time::Instant;
use uuid::Uuid;

use crate::lease_clock::LeaseClock;
use crate::new_message_id;
use crate::runner::{Fence, Job, Report, SessionRunner};
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
}

impl Attempt {
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

/// The sessions this agent runs.
pub struct Supervisor {
    clock: Arc<dyn LeaseClock>,
    runner: Option<Arc<dyn SessionRunner>>,
    http: Option<reqwest::Client>,
    gpus: HashSet<String>,
    attempts: HashMap<Uuid, Attempt>,
    seen_commands: HashSet<String>,
    outbox: Vec<Queued>,
    reports: mpsc::UnboundedSender<Tagged>,
    changed: Arc<Notify>,
}

/// A new supervisor, and the receiver its background task ([`supervise`]) reads runner reports from.
///
/// # Errors
/// If a runner is given but the storage client can't be built.
pub fn new(
    clock: Arc<dyn LeaseClock>,
    runner: Option<Arc<dyn SessionRunner>>,
    gpus: impl IntoIterator<Item = String>,
) -> Result<(Shared, mpsc::UnboundedReceiver<Tagged>), String> {
    let http = match runner {
        Some(_) => Some(crate::storage::http_client()?),
        None => None,
    };
    let (reports, received) = mpsc::unbounded_channel();
    let changed = Arc::new(Notify::new());
    let supervisor = Supervisor {
        clock,
        runner,
        http,
        gpus: gpus.into_iter().collect(),
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
        let attempt = start.attempt;
        match self.launch(session_id, start, received_at) {
            Ok(()) => tracing::info!(%session_id, attempt, "started an attempt"),
            Err(reason) => {
                tracing::warn!(%session_id, attempt, %reason, "refused a StartSession");
                self.attempts.insert(
                    session_id,
                    Attempt {
                        number: attempt,
                        deadline: received_at,
                        runtime_cap: received_at,
                        fence: Fence::new(),
                        tasks: Vec::new(),
                        state: SessionState::Preparing,
                        latest_seq: 0,
                    },
                );
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
        if start.minute_index_base.is_none_or(|base| base < 0) {
            return Err("minute_index_base is missing".to_owned());
        }
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
        let attempt_store: Arc<dyn ObjectStore> =
            Arc::new(S3Store::new(attempt_grant, http.clone(), Arc::clone(&self.clock))?);
        let read_store = match read_grant {
            Some(grant) => Some(Arc::new(S3Store::new(grant, http, Arc::clone(&self.clock))?) as Arc<dyn ObjectStore>),
            None => None,
        };
        let fence = Fence::new();
        let job = Job {
            session_id,
            attempt: start.attempt,
            spec,
            attempt_prefix,
            attempt_store,
            read_store,
            resume_from: start.resume_from,
            fence: fence.clone(),
        };
        let tasks = spawn(runner.as_ref(), job, self.reports.clone());
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

    /// A `StopSession`: acked; a repeated `command_id` is acked and acted on once.
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
                StopReason::Abuse => "abuse",
                StopReason::Dispute => "dispute",
                StopReason::Superseded => "superseded",
                StopReason::Unspecified => "stopped",
            };
            self.end(session_id, SessionState::Stopped, Some((reason, "stopped by the control plane")), None);
            tracing::info!(%session_id, attempt = stop.attempt, reason, "stopped an attempt");
        }
        Some(ack(message_id))
    }

    /// The control plane acked one of our messages.
    pub fn acked(&mut self, message_id: &str) {
        self.outbox.retain(|queued| queued.request.message_id != message_id);
        // An ended attempt is forgotten once the control plane has everything it reported.
        let waiting: HashSet<Uuid> = self.outbox.iter().map(|queued| queued.session_id).collect();
        self.attempts.retain(|session_id, attempt| attempt.is_live() || waiting.contains(session_id));
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

    /// Stops every attempt whose lease or runtime cap has passed.
    pub fn check_deadlines(&mut self) {
        let now = self.clock.now();
        let lapsed: Vec<(Uuid, bool)> = self
            .attempts
            .iter()
            .filter(|(_, attempt)| attempt.is_live())
            .filter_map(|(session_id, attempt)| {
                if now >= attempt.deadline {
                    Some((*session_id, true))
                } else if now >= attempt.runtime_cap {
                    Some((*session_id, false))
                } else {
                    None
                }
            })
            .collect();
        for (session_id, lease) in lapsed {
            if lease {
                tracing::warn!(%session_id, "the lease ran out before it was renewed; the work is stopped");
                self.end(session_id, SessionState::LeaseExpired, Some(("lease_expired", "the lease ran out")), None);
            } else {
                tracing::warn!(%session_id, "the attempt reached its maximum runtime; the work is stopped");
                self.end(
                    session_id,
                    SessionState::Failed,
                    Some(("max_runtime_exceeded", "ran past max_runtime")),
                    None,
                );
            }
        }
    }

    /// What a runner reported. Anything about an attempt that has ended, or been replaced, is dropped.
    fn apply(&mut self, tagged: Tagged) {
        self.check_deadlines();
        let Some(attempt) = self.attempts.get_mut(&tagged.session_id) else { return };
        if attempt.number != tagged.attempt || !attempt.is_live() {
            return;
        }
        match tagged.reported {
            Reported::Report(Report::State(state)) => {
                attempt.state = state;
                self.queue_status(tagged.session_id, None, None);
            }
            Reported::Report(Report::Checkpoint(seq)) => {
                attempt.latest_seq = attempt.latest_seq.max(seq);
                self.queue_status(tagged.session_id, None, None);
            }
            Reported::Report(Report::Succeeded) => self.end(tagged.session_id, SessionState::Succeeded, None, Some(0)),
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
        }
    }

    /// Ends an attempt in `state`: fence down, its tasks aborted, and the status queued.
    fn end(&mut self, session_id: Uuid, state: SessionState, error: Option<(&str, &str)>, exit_code: Option<i32>) {
        let Some(attempt) = self.attempts.get_mut(&session_id) else { return };
        attempt.fence.take_down();
        for task in attempt.tasks.drain(..) {
            task.abort();
        }
        attempt.state = state;
        self.queue_status(session_id, error, exit_code);
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
        };
        self.outbox.push(Queued {
            request: OpenStreamRequest { message_id: new_message_id(), body: Some(AgentBody::SessionStatus(status)) },
            session_id,
            sent_at: None,
        });
        self.changed.notify_one();
    }
}

/// Runs the job in its own task, forwarding its reports tagged with the attempt, then saying it ended.
fn spawn(runner: &dyn SessionRunner, job: Job, reports: mpsc::UnboundedSender<Tagged>) -> Vec<AbortHandle> {
    let (session_id, attempt) = (job.session_id, job.attempt);
    let (sender, mut receiver) = mpsc::unbounded_channel();
    let mut work: JoinHandle<()> = tokio::spawn(runner.run(job, sender));
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
    }

    impl SessionRunner for FakeRunner {
        fn run(&self, job: Job, reports: mpsc::UnboundedSender<Report>) -> Pin<Box<dyn Future<Output = ()> + Send>> {
            self.started.lock().unwrap().push((job.fence, reports));
            Box::pin(std::future::pending())
        }
    }

    struct Setup {
        shared: Shared,
        received: mpsc::UnboundedReceiver<Tagged>,
        clock: Arc<FakeLeaseClock>,
        runner: Arc<FakeRunner>,
    }

    fn setup() -> Setup {
        let clock = Arc::new(FakeLeaseClock::default());
        let runner = Arc::new(FakeRunner::default());
        let (shared, received) = new(
            Arc::clone(&clock) as Arc<dyn LeaseClock>,
            Some(Arc::clone(&runner) as Arc<dyn SessionRunner>),
            HashSet::from([GPU.to_owned()]),
        )
        .unwrap();
        Setup { shared, received, clock, runner }
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
        let setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        let fence = setup.runner.started.lock().unwrap()[0].0.clone();

        setup.clock.advance(Duration::from_secs(119));
        setup.shared.lock().check_deadlines();
        assert!(fence.is_up());
        setup.clock.advance(Duration::from_secs(1));
        setup.shared.lock().check_deadlines();
        setup.shared.lock().check_deadlines();

        assert!(!fence.is_up());
        let sent = statuses(&setup.shared);
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].state(), SessionState::LeaseExpired);
        assert_eq!(sent[0].error.as_ref().unwrap().code, "lease_expired");
        assert_eq!(setup.shared.lock().running(), Vec::new());
    }

    #[tokio::test]
    async fn a_renewal_extends_only_the_current_attempt() {
        let setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 2, "c1"), Duration::ZERO);
        let renewal = |attempt| LeaseRenew { session_id: session.to_string(), attempt, lease_ttl: Some(seconds(120)) };

        setup.clock.advance(Duration::from_secs(100));
        setup.shared.lock().renew(&renewal(1), setup.clock.now());
        setup.clock.advance(Duration::from_secs(20));
        setup.shared.lock().check_deadlines();
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
        let setup = setup();
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
        assert_eq!(statuses(&setup.shared)[0].state(), SessionState::LeaseExpired);
    }

    #[tokio::test]
    async fn reports_after_the_fence_are_dropped() {
        let setup = setup();
        let session = Uuid::now_v7();
        setup.shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);
        setup.clock.advance(Duration::from_secs(121));
        setup.shared.lock().check_deadlines();
        statuses(&setup.shared);

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
        let setup = setup();
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

        let sent = statuses(&setup.shared);
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].state(), SessionState::Stopped);
        assert_eq!(sent[0].error.as_ref().unwrap().code, "superseded");
    }

    #[tokio::test]
    async fn without_a_runner_a_start_fails_rather_than_hanging() {
        let (shared, _received) =
            new(Arc::new(FakeLeaseClock::default()), None, HashSet::from([GPU.to_owned()])).unwrap();
        let session = Uuid::now_v7();

        shared.lock().start("m1", start(session, 1, "c1"), Duration::ZERO);

        assert_eq!(statuses(&shared)[0].error.as_ref().unwrap().code, "no_runner");
    }
}
