//! The container runner (ADR 0035): the [`SessionRunner`] that hands an attempt to `idleo-runner`, inside our sealed Linux
//! distribution, and follows it from the outside. The runner stages the inputs, makes the scratch disk, starts the sandboxed
//! container, streams its output to the renter's log and uploads its checkpoints and outputs, so nothing of the renter's
//! touches the owner's Windows disk and none of it passes through this process but one line of status at a time.
//!
//! What stays here is what the agent owns: the offer and the lease. A [`Job`]'s stop becomes a `stop` with the grace the
//! supervisor gave; a lease renewal becomes a `lease` (the runner stops a job whose lease nobody renews, even with no agent
//! to say so); and a job whose future is dropped (the supervisor ended the attempt: its lease lapsed, or its fence went down)
//! is stopped with no grace by a guard, so a job never outlives the attempt that owns it.
//!
//! The runner's events are hints; every few seconds, and after every reconnect, the attempt is compared with the runner's
//! `list`, which is the truth, so a lost event never loses an ending.
//!
//! One transport: a process whose stdin and stdout carry the protocol's lines to the runner's socket: `wsl.exe -d IdleO-… -u
//! root -- /usr/local/bin/idleo-runner attach` on Windows, `docker exec -i <container> … attach` in development.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use agent_proto::v1::{Credentials, SessionState};
use jobcore::link::{Link, RunnerEvent};
use jobcore::logs::LogEvent;
use jobcore::protocol::{
    AgentNotes, Answer, Check, Ended, Event, Failure, GrantWire, Grants, HelloAnswer, InputFile, Phase, Request,
    ResumeFrom, SessionInfo, Spec, Start, code,
};
use tokio::sync::{broadcast, mpsc, watch};
use uuid::Uuid;

use super::{Adopted, Fence, Job, Report, SessionRunner, Watching};

/// The runner's own path in the distribution.
pub const RUNNER_PATH: &str = "/usr/local/bin/idleo-runner";
/// How often an attempt is compared with the runner's own account of it.
const RECONCILE_EVERY: Duration = Duration::from_secs(5);

/// An abuse blocklist's exact text and its signature, as the runner is given them.
type SignedBlocklist = (String, Vec<u8>);

/// The runner that runs sessions in containers.
#[derive(Clone)]
pub struct ContainerRunner {
    link: Link,
    gpu: bool,
    /// Whether the distribution is running, so the agent never starts it just to look: `None` where it always is.
    awake: Option<Arc<dyn Fn() -> bool + Send + Sync>>,
    /// How much of the owner's upload a job may use (ADR 0038), told to the runner at every start.
    upload_cap: Arc<crate::fair_use::UploadCap>,
    /// The newest abuse blocklist the agent has verified, to hand to the runner whenever it has an older one.
    blocklist: Arc<std::sync::Mutex<Option<SignedBlocklist>>>,
}

impl ContainerRunner {
    /// A runner over `link`. `gpu` is whether jobs get the GPU (`--gpus all`): true on a real host, false in the Mac's
    /// development stand-in, whose distribution the runner then must find to be a development one.
    #[must_use]
    pub fn new(link: Link, gpu: bool) -> Self {
        Self {
            link,
            gpu,
            awake: None,
            upload_cap: crate::fair_use::UploadCap::floor_only(crate::fair_use::DEFAULT_FLOOR_KBPS),
            blocklist: Arc::default(),
        }
    }

    /// Takes the upload cap the self-test measures and the config's floor (ADR 0038).
    #[must_use]
    pub fn with_upload_cap(mut self, cap: Arc<crate::fair_use::UploadCap>) -> Self {
        self.upload_cap = cap;
        self
    }

    /// Says how to tell whether the distribution is running.
    #[must_use]
    pub fn with_awake(mut self, awake: impl Fn() -> bool + Send + Sync + 'static) -> Self {
        self.awake = Some(Arc::new(awake));
        self
    }

    /// Whether the distribution is running now.
    #[must_use]
    pub fn is_awake(&self) -> bool {
        self.awake.as_ref().is_none_or(|awake| awake())
    }

    /// The development stand-in: `docker exec -i <container> idleo-runner attach`.
    #[must_use]
    pub fn docker(container: &str) -> Self {
        let container = container.to_owned();
        Self::new(
            Link::process(move || {
                let mut command = tokio::process::Command::new("docker");
                command.args(["exec", "-i", &container, RUNNER_PATH, "attach"]);
                command
            }),
            false,
        )
    }

    /// A laptop's own distribution: `wsl.exe -d <distro> -u root -- idleo-runner attach`, with no console window and
    /// `WSL_UTF8=1`.
    ///
    /// # Errors
    /// If `distro` isn't one of ours (`IdleO-…`): the agent never talks to anyone else's distribution.
    pub fn wsl(distro: &str) -> Result<Self, String> {
        if !distro.starts_with(idleo_release::wsl::DISTRO_PREFIX)
            || distro.len() <= idleo_release::wsl::DISTRO_PREFIX.len()
        {
            return Err(format!("{distro} isn't one of our distributions"));
        }
        let named = distro.to_owned();
        let distro = distro.to_owned();
        Ok(Self::new(
            Link::process(move || {
                let mut command = tokio::process::Command::new("wsl.exe");
                command.args(["-d", &distro, "-u", "root", "--", RUNNER_PATH, "attach"]).env("WSL_UTF8", "1");
                #[cfg(windows)]
                {
                    /// `CREATE_NO_WINDOW`: no console flashes up from a program with none.
                    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
                    command.creation_flags(CREATE_NO_WINDOW);
                }
                command
            }),
            true,
        )
        .with_awake(move || {
            use idleo_release::wsl::{Runner, SystemRunner};
            SystemRunner
                .run("wsl.exe", &["--list", "--running", "--quiet"])
                .is_ok_and(|out| out.ok() && out.stdout.lines().any(|line| line.trim() == named))
        }))
    }

    /// The runner's link, for the tray's pre-download and the self-test.
    #[must_use]
    pub fn link(&self) -> &Link {
        &self.link
    }

    /// What the runner says about itself: its version, whether it has a GPU and the sandbox's rules loaded.
    ///
    /// # Errors
    /// If it can't be reached.
    pub async fn hello(&self) -> Result<HelloAnswer, Failure> {
        match self.link.request(Request::Hello).await? {
            Answer::Hello(hello) => Ok(hello),
            other => Err(Failure::new(code::RUNNER_FAILED, format!("unexpected answer {other:?}"))),
        }
    }

    /// The runner's own checks, for the self-test.
    ///
    /// # Errors
    /// If it can't be reached.
    pub async fn self_check(&self, cuda_image: Option<String>, quick: bool) -> Result<Vec<Check>, Failure> {
        match self.link.request_within(Request::SelfCheck { cuda_image, quick }, Duration::from_secs(180)).await? {
            Answer::SelfCheck { checks } => Ok(checks),
            other => Err(Failure::new(code::RUNNER_FAILED, format!("unexpected answer {other:?}"))),
        }
    }

    /// Downloads an image, reporting `(downloaded bytes, total bytes if known)` as it goes.
    ///
    /// # Errors
    /// If it can't be pulled.
    pub async fn pull(&self, image: &str, mut progress: impl FnMut(u64, u64) + Send) -> Result<(), Failure> {
        let mut events = self.link.events();
        let request = self.link.request_within(Request::Pull { image: image.to_owned() }, Duration::from_hours(4));
        tokio::pin!(request);
        loop {
            tokio::select! {
                answer = &mut request => return answer.map(|_| ()),
                event = events.recv() => {
                    if let Ok(RunnerEvent { event: Event::Pull { image: named, done_bytes, total_bytes }, .. }) = event
                        && named == image
                    {
                        progress(done_bytes, total_bytes);
                    }
                }
            }
        }
    }

    fn start_of(&self, job: &Job) -> Result<Start, Failure> {
        let credentials =
            job.credentials.as_ref().ok_or_else(|| Failure::new(code::INVALID_INPUT, "no storage credentials"))?;
        let attempt =
            credentials.attempt_grant.as_ref().ok_or_else(|| Failure::new(code::INVALID_INPUT, "no attempt grant"))?;
        let spec = &job.spec;
        let positive = |n: i32| u32::try_from(n).unwrap_or(0);
        Ok(Start {
            session: job.session_id.to_string(),
            attempt: job.attempt,
            spec: Spec {
                image: spec.image.clone(),
                args: spec.args.clone(),
                env: spec.env.iter().map(|(name, value)| (name.clone(), value.clone())).collect(),
                memory_mb: positive(spec.memory_mb),
                cpu_millicores: positive(spec.cpu_millicores),
                pids_limit: positive(spec.pids_limit),
                scratch_mb: positive(spec.scratch_mb),
                max_runtime_secs: spec
                    .max_runtime
                    .and_then(|runtime| Duration::try_from(runtime).ok())
                    .map_or(0, |runtime| runtime.as_secs()),
                checkpoint_interval_secs: spec
                    .checkpoint_interval
                    .and_then(|interval| Duration::try_from(interval).ok())
                    .map_or(0, |interval| interval.as_secs()),
                gpu: self.gpu,
            },
            attempt_prefix: job.attempt_prefix.clone(),
            grants: Grants {
                attempt: GrantWire::from_message(attempt),
                read: credentials.read_grant.as_ref().map(GrantWire::from_message),
            },
            resume_from: job.resume_from.as_ref().map(|from| ResumeFrom { prefix: from.prefix.clone(), seq: from.seq }),
            inputs: job
                .input_refs
                .iter()
                .map(|input| InputFile {
                    prefix: input.prefix.clone(),
                    name: input.name.clone(),
                    size_bytes: input.size_bytes,
                    slot: input.slot.clone(),
                })
                .collect(),
            lease_ttl_secs: u32::try_from(job.lease_ttl.as_secs()).unwrap_or(u32::MAX),
            agent: AgentNotes { minute_index_base: job.minute_index_base, gpus: job.gpus.clone() },
            upload_cap_kbps: self.upload_cap.kbps(),
        })
    }
}

/// The distribution as care (care.rs) sees it: the runner downloads images, and, on a laptop, `wsl --terminate` stops it.
pub struct RunnerDistribution {
    runner: Arc<ContainerRunner>,
    /// What stops the distribution when it's idle; none where it isn't ours to stop (the development stand-in).
    stop: Option<Arc<dyn Fn() + Send + Sync>>,
}

impl RunnerDistribution {
    /// Care's view of `runner`'s distribution.
    #[must_use]
    pub fn new(runner: Arc<ContainerRunner>, stop: Option<Arc<dyn Fn() + Send + Sync>>) -> Self {
        Self { runner, stop }
    }

    /// Stops the distribution named `distro`, one of ours, the way `wsl --terminate` does.
    ///
    /// # Errors
    /// If it isn't one of ours.
    pub fn terminate(distro: &str) -> Result<Arc<dyn Fn() + Send + Sync>, String> {
        if !distro.starts_with(idleo_release::wsl::DISTRO_PREFIX)
            || distro.len() <= idleo_release::wsl::DISTRO_PREFIX.len()
        {
            return Err(format!("{distro} isn't one of our distributions"));
        }
        let distro = distro.to_owned();
        Ok(Arc::new(move || {
            let wsl = idleo_release::wsl::Wsl::new(idleo_release::wsl::SystemRunner);
            if let Err(error) = wsl.terminate(&distro) {
                tracing::warn!(%error, "the distribution couldn't be stopped");
            }
        }))
    }
}

impl crate::care::Distribution for RunnerDistribution {
    fn awake(&self) -> bool {
        self.runner.is_awake()
    }

    fn pull<'a>(
        &'a self,
        image: &'a str,
        progress: &'a (dyn Fn(u64, u64) + Send + Sync),
    ) -> Pin<Box<dyn Future<Output = Result<(), String>> + Send + 'a>> {
        Box::pin(async move { self.runner.pull(image, progress).await.map_err(|failure| failure.message) })
    }

    fn cancel_pulls(&self) {
        self.runner.link().notify(Request::CancelPulls);
    }

    fn stop(&self) {
        if let Some(stop) = &self.stop {
            stop();
        }
    }
}

/// Gives the runner `file` and its signature if it is newer than the blocklist the runner reports using (or the runner reports
/// none: it then starts no job until it has one). A runner that refuses it keeps the one it has; a runner too old to know the
/// request answers with a failure, which only the log hears of.
async fn hand_over_blocklist(link: &Link, hello: &HelloAnswer, file: String, signature: Vec<u8>) {
    let Ok(version) = idleo_release::blocklist::version_of(file.as_bytes()) else { return };
    if !hello.blocklist_version.is_empty() && !idleo_release::blocklist::is_newer(&version, &hello.blocklist_version) {
        return;
    }
    match link.request(Request::Blocklist { file, signature }).await {
        Ok(_) => tracing::info!(%version, "the job runner has the newer abuse blocklist"),
        Err(failure) => tracing::warn!(%version, %failure, "the job runner didn't take the abuse blocklist"),
    }
}

/// What has been told to the supervisor, so a repeated fact isn't told twice.
#[derive(Default)]
struct Told {
    phase: Option<Phase>,
    checkpoint: i32,
    log: i32,
    flushed: bool,
}

fn state_of(phase: Phase) -> Option<SessionState> {
    match phase {
        Phase::Preparing => Some(SessionState::Preparing),
        Phase::Running => Some(SessionState::Running),
        Phase::Uploading => Some(SessionState::Uploading),
        Phase::Ended => None,
    }
}

/// The report an ending is.
fn ending(ended: Ended) -> Report {
    match ended {
        Ended::Succeeded => Report::Succeeded,
        Ended::Stopped => Report::Stopped,
        Ended::Failed { code, message, exit_code } => Report::Failed { code, message, exit_code },
    }
}

impl Told {
    /// Tells the supervisor an event's news; true if it was the end.
    fn event(&mut self, event: Event, reports: &mpsc::UnboundedSender<Report>) -> bool {
        match event {
            Event::State { phase } => self.phase(phase, reports),
            Event::Logs { latest_seq, flushed } => self.logs(latest_seq, flushed, reports),
            Event::Checkpoint { seq } => self.checkpoint(seq, reports),
            Event::Exited { ended } => {
                let _ = reports.send(ending(ended));
                return true;
            }
            Event::Abuse { kind, detail, sha256, count, stopped } => {
                let _ = reports.send(Report::Abuse { kind, detail, sha256, count, stopped });
            }
            Event::Behavior { net_out_bytes, net_in_bytes, printed_lines, conn_drops } => {
                let _ = reports.send(Report::Behavior { net_out_bytes, net_in_bytes, printed_lines, conn_drops });
            }
            Event::Pull { .. } => {}
        }
        false
    }

    fn phase(&mut self, phase: Phase, reports: &mpsc::UnboundedSender<Report>) {
        if self.phase != Some(phase) {
            self.phase = Some(phase);
            if let Some(state) = state_of(phase) {
                let _ = reports.send(Report::State(state));
            }
        }
    }

    fn logs(&mut self, latest_seq: i32, flushed: bool, reports: &mpsc::UnboundedSender<Report>) {
        if latest_seq > self.log {
            self.log = latest_seq;
            let _ = reports.send(Report::Logs(LogEvent::Uploaded(latest_seq)));
        }
        if flushed && !self.flushed {
            self.flushed = true;
            let _ = reports.send(Report::Logs(LogEvent::Flushed(latest_seq)));
        }
    }

    fn checkpoint(&mut self, seq: i32, reports: &mpsc::UnboundedSender<Report>) {
        if seq > self.checkpoint {
            self.checkpoint = seq;
            let _ = reports.send(Report::Checkpoint(seq));
        }
    }

    /// Catches up with the runner's own account of the attempt; true if it has ended.
    fn info(&mut self, info: SessionInfo, reports: &mpsc::UnboundedSender<Report>) -> bool {
        self.phase(info.phase, reports);
        self.checkpoint(info.latest_checkpoint_seq, reports);
        if let Some(ended) = info.ended {
            self.logs(info.latest_log_seq, true, reports);
            let _ = reports.send(ending(ended));
            return true;
        }
        self.logs(info.latest_log_seq, false, reports);
        false
    }
}

/// Stops the attempt, with no grace, if the future following it is dropped before it ended: the supervisor ended the
/// attempt (its lease lapsed, its fence went down), and a job must never outlive that.
struct StopOnDrop {
    link: Link,
    session: String,
    attempt: i32,
    over: bool,
}

impl Drop for StopOnDrop {
    fn drop(&mut self) {
        if !self.over {
            self.link.notify(Request::Stop { session: self.session.clone(), attempt: self.attempt, grace_secs: 0 });
        }
    }
}

/// Follows an attempt the runner holds until it ends, reporting as it goes: its events, checked against its own list every
/// few seconds and after every reconnect, the supervisor's stop, and the fence.
async fn follow(
    link: Link,
    mut events: broadcast::Receiver<RunnerEvent>,
    session: String,
    attempt: i32,
    reports: mpsc::UnboundedSender<Report>,
    mut stop_requested: watch::Receiver<Option<Duration>>,
    fence: Fence,
) {
    let mut guard = StopOnDrop { link: link.clone(), session: session.clone(), attempt, over: false };
    let mut connections = link.connections();
    let mut told = Told::default();
    let mut stopping = false;
    let mut reconcile = tokio::time::interval(RECONCILE_EVERY);
    reconcile.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut missing_since: Option<tokio::time::Instant> = None;
    loop {
        let mut check_list = false;
        tokio::select! {
            event = events.recv() => match event {
                Ok(RunnerEvent { session: who, attempt: number, event }) if who == session && number == attempt => {
                    if told.event(event, &reports) {
                        guard.over = true;
                        break;
                    }
                }
                Ok(_) => {}
                // Events were missed: the runner's list says where things stand.
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => check_list = true,
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            },
            changed = stop_requested.changed(), if !stopping => {
                let asked = if changed.is_ok() { *stop_requested.borrow() } else { None };
                {
                    if let Some(grace) = asked {
                        stopping = true;
                        let grace_secs = u32::try_from(grace.as_secs()).unwrap_or(u32::MAX);
                        let request = Request::Stop { session: session.clone(), attempt, grace_secs };
                        if let Err(failure) = link.request(request).await {
                            tracing::warn!(%failure, "the runner couldn't be asked to stop; the supervisor's deadline will end the attempt");
                        }
                    }
                }
            }
            _ = connections.changed() => check_list = true,
            _ = reconcile.tick() => check_list = true,
        }
        if !fence.is_up() {
            // The attempt is over as far as the control plane is concerned: nothing of it may run on.
            break;
        }
        if check_list && let Ok(Answer::List { sessions }) = link.request(Request::List).await {
            let ours = sessions.into_iter().find(|info| info.session == session && info.attempt == attempt);
            if let Some(info) = ours {
                missing_since = None;
                if told.info(info, &reports) {
                    guard.over = true;
                    break;
                }
            } else {
                // The runner doesn't hold it any more: it restarted, and its containers were cleaned up with it.
                let since = *missing_since.get_or_insert_with(tokio::time::Instant::now);
                if since.elapsed() > RECONCILE_EVERY * 2 {
                    let _ = reports.send(Report::Failed {
                        code: code::RUNNER_FAILED.to_owned(),
                        message: "the job runner restarted and lost the job".to_owned(),
                        exit_code: None,
                    });
                    guard.over = true;
                    break;
                }
            }
        }
    }
    if guard.over {
        // The end is told; the runner can drop what it kept for us.
        link.notify(Request::Forget { session, attempt });
    }
}

fn phase_state(phase: Phase) -> SessionState {
    state_of(phase).unwrap_or(SessionState::Failed)
}

impl SessionRunner for ContainerRunner {
    fn run(&self, job: Job, reports: mpsc::UnboundedSender<Report>) -> Pin<Box<dyn Future<Output = ()> + Send>> {
        let this = self.clone();
        Box::pin(async move {
            let start = match this.start_of(&job) {
                Ok(start) => start,
                Err(failure) => {
                    let _ =
                        reports.send(Report::Failed { code: failure.code, message: failure.message, exit_code: None });
                    return;
                }
            };
            let (session, attempt) = (job.session_id.to_string(), job.attempt);
            // Listening before the start: no event of the attempt can be missed. And if this future is dropped while the
            // start is in flight, the attempt is stopped as well.
            let events = this.link.events();
            let mut guard = StopOnDrop { link: this.link.clone(), session: session.clone(), attempt, over: false };
            if let Err(failure) = this.link.request(Request::Start(Box::new(start))).await {
                // A start that never reached the runner started nothing; one that did and lost its answer is stopped.
                guard.over = failure.code != jobcore::link::LINK_LOST;
                let _ = reports.send(Report::Failed { code: failure.code, message: failure.message, exit_code: None });
                return;
            }
            guard.over = true;
            follow(this.link.clone(), events, session, attempt, reports, job.stop_requested.clone(), job.fence.clone())
                .await;
        })
    }

    fn owns_logs(&self) -> bool {
        true
    }

    fn warm(&self) {
        // The runner's first answer after a cold start comes when its distribution is up; asking is what starts it. Once
        // it answers, a newer blocklist than its own is handed over, before the job starts.
        let link = self.link.clone();
        let held = self.blocklist.lock().unwrap_or_else(std::sync::PoisonError::into_inner).clone();
        tokio::spawn(async move {
            if let Ok(Answer::Hello(hello)) = link.request(Request::Hello).await
                && let Some((file, signature)) = held
            {
                hand_over_blocklist(&link, &hello, file, signature).await;
            }
        });
        // And nothing is downloaded while a session starts.
        self.link.notify(Request::CancelPulls);
    }

    fn blocklist(&self, file: String, signature: Vec<u8>) {
        *self.blocklist.lock().unwrap_or_else(std::sync::PoisonError::into_inner) =
            Some((file.clone(), signature.clone()));
        // The distribution is only woken for a session; one that is up takes the list now.
        if self.is_awake() {
            let link = self.link.clone();
            tokio::spawn(async move {
                if let Ok(Answer::Hello(hello)) = link.request(Request::Hello).await {
                    hand_over_blocklist(&link, &hello, file, signature).await;
                }
            });
        }
    }

    fn stages_inputs(&self) -> bool {
        true
    }

    fn renew(&self, session_id: Uuid, attempt: i32, ttl: Duration) {
        self.link.notify(Request::Lease {
            session: session_id.to_string(),
            attempt,
            ttl_secs: u32::try_from(ttl.as_secs()).unwrap_or(u32::MAX),
        });
    }

    fn refresh(&self, session_id: Uuid, attempt: i32, credentials: &Credentials) {
        let Some(attempt_grant) = credentials.attempt_grant.as_ref() else { return };
        self.link.notify(Request::Refresh(Box::new(jobcore::protocol::Refresh {
            session: session_id.to_string(),
            attempt,
            grants: Grants {
                attempt: GrantWire::from_message(attempt_grant),
                read: credentials.read_grant.as_ref().map(GrantWire::from_message),
            },
        })));
    }

    fn adopt(&self) -> Pin<Box<dyn Future<Output = Vec<Adopted>> + Send + '_>> {
        let link = self.link.clone();
        let awake = self.is_awake();
        Box::pin(async move {
            // A stopped distribution holds no jobs, and isn't started just to ask.
            if !awake {
                return Vec::new();
            }
            let Ok(Answer::List { sessions }) = link.request(Request::List).await else { return Vec::new() };
            sessions
                .into_iter()
                .filter_map(|info| {
                    let session_id = Uuid::parse_str(&info.session).ok()?;
                    let (session, attempt) = (info.session.clone(), info.attempt);
                    let link = link.clone();
                    let events = link.events();
                    let watching: Watching = Box::new(move |reports, stop_requested, fence| {
                        Box::pin(follow(link, events, session, attempt, reports, stop_requested, fence))
                    });
                    Some(Adopted {
                        session_id,
                        attempt: info.attempt,
                        state: phase_state(info.phase),
                        latest_checkpoint_seq: info.latest_checkpoint_seq,
                        latest_log_seq: info.latest_log_seq,
                        running_for: Duration::from_secs(info.running_for_secs),
                        lease_remaining: Duration::from_secs(info.lease_remaining_secs),
                        runtime_remaining: Duration::from_secs(info.runtime_remaining_secs),
                        minute_index_base: info.agent.minute_index_base,
                        gpus: info.agent.gpus.clone(),
                        watching,
                    })
                })
                .collect()
        })
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;
    use std::time::Duration;

    use agent_proto::v1::{SessionSpec, StorageGrant, StoragePermission};
    use jobcore::link::Connection;
    use jobcore::logs::LogWriter;
    use jobcore::protocol::{Body, Frame, Outcome};
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, duplex};

    use super::*;

    const SESSION: &str = "0190f3a2-0000-7000-8000-000000000000";

    /// A scripted runner on the other end of the link: it records every request, answers each (`list` from what the test
    /// set), and says whatever the test pushes.
    struct Scripted {
        requests: Arc<Mutex<Vec<Request>>>,
        sessions: Arc<Mutex<Vec<SessionInfo>>>,
        say: mpsc::UnboundedSender<String>,
        refuse_start: Arc<Mutex<Option<Failure>>>,
        /// The abuse blocklist version the scripted runner says it is using.
        blocklist_version: Arc<Mutex<String>>,
    }

    impl Scripted {
        fn runner() -> (Self, ContainerRunner) {
            let requests: Arc<Mutex<Vec<Request>>> = Arc::default();
            let sessions: Arc<Mutex<Vec<SessionInfo>>> = Arc::default();
            let refuse_start: Arc<Mutex<Option<Failure>>> = Arc::default();
            let blocklist_version: Arc<Mutex<String>> = Arc::new(Mutex::new("2026-10-22".to_owned()));
            let (say, speech) = mpsc::unbounded_channel::<String>();
            let speech = Arc::new(tokio::sync::Mutex::new(Some(speech)));
            let (seen, listed, refusing) = (Arc::clone(&requests), Arc::clone(&sessions), Arc::clone(&refuse_start));
            let using = Arc::clone(&blocklist_version);
            let link = Link::new(Arc::new(move || {
                let (agent_read, mut runner_write) = duplex(1 << 20);
                let (runner_read, agent_write) = duplex(1 << 20);
                let (out, mut outgoing) = mpsc::unbounded_channel::<String>();
                tokio::spawn(async move {
                    while let Some(line) = outgoing.recv().await {
                        if runner_write.write_all(line.as_bytes()).await.is_err() {
                            return;
                        }
                    }
                });
                let talk = out.clone();
                let speech = Arc::clone(&speech);
                tokio::spawn(async move {
                    if let Some(mut speech) = speech.lock().await.take() {
                        while let Some(line) = speech.recv().await {
                            let _ = talk.send(line);
                        }
                    }
                });
                let (seen, listed, refusing) = (Arc::clone(&seen), Arc::clone(&listed), Arc::clone(&refusing));
                let using = Arc::clone(&using);
                tokio::spawn(async move {
                    let mut lines = BufReader::new(runner_read).lines();
                    while let Ok(Some(line)) = lines.next_line().await {
                        let Ok(Frame { body: Body::Request { id, req }, .. }) = Frame::decode(&(line + "\n")) else {
                            continue;
                        };
                        seen.lock().unwrap().push(req.clone());
                        let reply = match req {
                            Request::List => Outcome::Ok(Answer::List { sessions: listed.lock().unwrap().clone() }),
                            Request::Hello => Outcome::Ok(Answer::Hello(HelloAnswer {
                                runner_version: "test".to_owned(),
                                distro_version: "test".to_owned(),
                                gpu: false,
                                rules_loaded: true,
                                scratch_free_bytes: 0,
                                development: true,
                                blocklist_version: using.lock().unwrap().clone(),
                            })),
                            Request::Start(_) => match refusing.lock().unwrap().clone() {
                                Some(failure) => Outcome::Err(failure),
                                None => Outcome::Ok(Answer::Done),
                            },
                            _ => Outcome::Ok(Answer::Done),
                        };
                        let _ = out.send(Frame::reply(id, reply));
                    }
                });
                Ok(Connection { reader: Box::pin(agent_read), writer: Box::pin(agent_write), keep: Box::new(()) })
            }));
            (Self { requests, sessions, say, refuse_start, blocklist_version }, ContainerRunner::new(link, true))
        }

        fn event(&self, event: Event) {
            let _ = self.say.send(Frame::event(SESSION, 1, event));
        }

        fn requests(&self) -> Vec<Request> {
            self.requests.lock().unwrap().clone()
        }

        async fn wait_for(&self, what: &str, found: impl Fn(&[Request]) -> bool) {
            for _ in 0..400 {
                if found(&self.requests()) {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            panic!("never saw {what}: {:?}", self.requests());
        }
    }

    fn grant(prefixes: &[&str], permission: StoragePermission) -> StorageGrant {
        StorageGrant {
            endpoint: "http://storage.invalid".to_owned(),
            bucket: "idleo".to_owned(),
            access_key_id: "AKIA-never-logged".to_owned(),
            secret_access_key: "secret-never-logged".to_owned(),
            session_token: "token-never-logged".to_owned(),
            prefixes: prefixes.iter().map(|p| (*p).to_owned()).collect(),
            permission: permission.into(),
            expires_in: Some(prost_types::Duration { seconds: 3600, nanos: 0 }),
        }
    }

    fn job() -> (Job, watch::Sender<Option<Duration>>, Fence) {
        let (stop, stop_requested) = watch::channel(None);
        let fence = Fence::new();
        let job = Job {
            session_id: Uuid::parse_str(SESSION).unwrap(),
            attempt: 1,
            spec: SessionSpec {
                image: format!("registry.example/job@sha256:{}", "ab".repeat(32)),
                args: vec!["/opt/idleo/run.sh".to_owned()],
                env: [("SESSION_PARAM_EPOCHS".to_owned(), "3".to_owned())].into_iter().collect(),
                memory_mb: 8192,
                cpu_millicores: 4000,
                pids_limit: 512,
                scratch_mb: 4096,
                max_runtime: Some(prost_types::Duration { seconds: 600, nanos: 0 }),
                ..SessionSpec::default()
            },
            attempt_prefix: format!("sessions/{SESSION}/attempt-1/"),
            attempt_store: Arc::new(jobcore::storage::testing::MemoryStore::new(
                &Arc::default(),
                &["sessions/x/"],
                true,
            )),
            read_store: None,
            resume_from: Some(agent_proto::v1::CheckpointRef {
                prefix: "sessions/x/attempt-0/checkpoints/".to_owned(),
                seq: 3,
            }),
            fence: fence.clone(),
            stop_requested,
            folder: std::env::temp_dir(),
            inputs: std::collections::BTreeMap::default(),
            logs: LogWriter::discard(),
            input_refs: vec![agent_proto::v1::InputRef {
                prefix: "uploads/u/files/".to_owned(),
                name: "train.jsonl".to_owned(),
                size_bytes: 8,
                slot: "data".to_owned(),
            }],
            credentials: Some(Credentials {
                attempt_grant: Some(grant(&[&format!("sessions/{SESSION}/attempt-1/")], StoragePermission::ReadWrite)),
                read_grant: Some(grant(&["uploads/u/files/"], StoragePermission::ReadOnly)),
                ..Credentials::default()
            }),
            lease_ttl: Duration::from_secs(120),
            minute_index_base: 2,
            gpus: vec!["GPU-1".to_owned()],
        };
        (job, stop, fence)
    }

    async fn drain(mut reports: mpsc::UnboundedReceiver<Report>, until: impl Fn(&Report) -> bool) -> Vec<Report> {
        let mut all = Vec::new();
        loop {
            let report = tokio::time::timeout(Duration::from_secs(5), reports.recv())
                .await
                .expect("a report in time")
                .expect("open");
            let done = until(&report);
            all.push(report);
            if done {
                return all;
            }
        }
    }

    #[tokio::test]
    async fn the_start_carries_everything_the_runner_needs_and_nothing_prints_a_secret() {
        let (runner_side, runner) = Scripted::runner();
        let (job, _stop, _fence) = job();
        let (reports, received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;

        let Some(Request::Start(start)) = runner_side.requests().into_iter().find(|r| matches!(r, Request::Start(_)))
        else {
            panic!()
        };
        assert_eq!((start.session.as_str(), start.attempt, start.lease_ttl_secs), (SESSION, 1, 120));
        assert_eq!(start.spec.image, format!("registry.example/job@sha256:{}", "ab".repeat(32)));
        assert_eq!(
            (start.spec.memory_mb, start.spec.cpu_millicores, start.spec.pids_limit, start.spec.scratch_mb),
            (8192, 4000, 512, 4096)
        );
        assert_eq!(start.spec.max_runtime_secs, 600);
        assert!(start.spec.gpu, "a real host's jobs get the GPU");
        assert_eq!(start.spec.env["SESSION_PARAM_EPOCHS"], "3");
        assert_eq!(start.attempt_prefix, format!("sessions/{SESSION}/attempt-1/"));
        assert!(start.grants.attempt.writable && start.grants.read.as_ref().is_some_and(|read| !read.writable));
        assert_eq!(start.grants.attempt.secret_access_key, "secret-never-logged");
        assert_eq!(
            start.resume_from,
            Some(ResumeFrom { prefix: "sessions/x/attempt-0/checkpoints/".to_owned(), seq: 3 })
        );
        assert_eq!(start.inputs.len(), 1);
        assert_eq!(start.inputs[0].slot, "data");
        assert_eq!(start.agent, AgentNotes { minute_index_base: 2, gpus: vec!["GPU-1".to_owned()] });
        let printed = format!("{start:?} {:?}", runner_side.requests());
        assert!(!printed.contains("never-logged"), "{printed}");

        runner_side.event(Event::Exited { ended: Ended::Succeeded });
        drain(received, |report| *report == Report::Succeeded).await;
        running.await.unwrap();
    }

    #[tokio::test]
    async fn the_runners_events_become_the_supervisors_reports_in_order_and_the_end_is_forgotten() {
        let (runner_side, runner) = Scripted::runner();
        let (job, _stop, _fence) = job();
        let (reports, received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;

        runner_side.event(Event::State { phase: Phase::Preparing });
        runner_side.event(Event::State { phase: Phase::Running });
        runner_side.event(Event::Logs { latest_seq: 1, flushed: false });
        runner_side.event(Event::Checkpoint { seq: 1 });
        runner_side.event(Event::Checkpoint { seq: 1 });
        runner_side.event(Event::State { phase: Phase::Uploading });
        runner_side.event(Event::Logs { latest_seq: 2, flushed: true });
        runner_side.event(Event::Exited { ended: Ended::Succeeded });

        let reports = drain(received, |report| *report == Report::Succeeded).await;
        assert_eq!(
            reports,
            vec![
                Report::State(SessionState::Preparing),
                Report::State(SessionState::Running),
                Report::Logs(LogEvent::Uploaded(1)),
                Report::Checkpoint(1),
                Report::State(SessionState::Uploading),
                Report::Logs(LogEvent::Uploaded(2)),
                Report::Logs(LogEvent::Flushed(2)),
                Report::Succeeded,
            ],
            "a repeated fact is told once"
        );
        running.await.unwrap();
        runner_side
            .wait_for("forget", |seen| seen.iter().any(|r| matches!(r, Request::Forget { attempt: 1, .. })))
            .await;
    }

    #[tokio::test]
    async fn what_the_runner_saw_in_the_session_reaches_the_supervisor_as_it_was_said() {
        let (runner_side, runner) = Scripted::runner();
        let (job, _stop, _fence) = job();
        let (reports, received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;
        runner_side.event(Event::Behavior { net_out_bytes: 10, net_in_bytes: 20, printed_lines: 3, conn_drops: 4 });
        runner_side.event(Event::Abuse {
            kind: jobcore::protocol::AbuseKind::ProcessHash,
            detail: "a listed build".to_owned(),
            sha256: "a".repeat(64),
            count: 0,
            stopped: true,
        });
        runner_side.event(Event::Exited {
            ended: Ended::Failed {
                code: code::ABUSE_STOPPED.to_owned(),
                message: "the job ran software that isn't allowed on IdleO".to_owned(),
                exit_code: None,
            },
        });

        let reports = drain(received, |report| matches!(report, Report::Failed { .. })).await;
        assert!(reports.contains(&Report::Behavior {
            net_out_bytes: 10,
            net_in_bytes: 20,
            printed_lines: 3,
            conn_drops: 4
        }));
        assert!(reports.contains(&Report::Abuse {
            kind: jobcore::protocol::AbuseKind::ProcessHash,
            detail: "a listed build".to_owned(),
            sha256: "a".repeat(64),
            count: 0,
            stopped: true,
        }));
        assert!(matches!(reports.last(), Some(Report::Failed { code, .. }) if code == "abuse_stopped"));
        running.await.unwrap();
    }

    #[tokio::test]
    async fn a_failure_keeps_its_code_and_exit_code() {
        let (runner_side, runner) = Scripted::runner();
        let (job, _stop, _fence) = job();
        let (reports, received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;
        runner_side.event(Event::Exited {
            ended: Ended::Failed {
                code: "out_of_memory".to_owned(),
                message: "ran out".to_owned(),
                exit_code: Some(137),
            },
        });

        let reports = drain(received, |report| matches!(report, Report::Failed { .. })).await;
        assert_eq!(
            reports.last(),
            Some(&Report::Failed {
                code: "out_of_memory".to_owned(),
                message: "ran out".to_owned(),
                exit_code: Some(137)
            })
        );
        running.await.unwrap();
    }

    #[tokio::test]
    async fn a_refused_start_is_a_failed_attempt_and_nothing_is_left_to_stop() {
        let (runner_side, runner) = Scripted::runner();
        *runner_side.refuse_start.lock().unwrap() = Some(Failure::new(code::BAD_REQUEST, "no"));
        let (job, _stop, _fence) = job();
        let (reports, mut received) = mpsc::unbounded_channel();

        runner.run(job, reports).await;

        assert!(matches!(received.recv().await, Some(Report::Failed { code, .. }) if code == code::BAD_REQUEST));
        tokio::time::sleep(Duration::from_millis(100)).await;
        assert!(!runner_side.requests().iter().any(|r| matches!(r, Request::Stop { .. })));
    }

    #[tokio::test]
    async fn the_supervisors_stop_becomes_a_stop_with_its_grace() {
        let (runner_side, runner) = Scripted::runner();
        let (job, stop, _fence) = job();
        let (reports, received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;

        stop.send(Some(Duration::from_secs(120))).unwrap();
        runner_side
            .wait_for("the stop", |seen| {
                seen.iter().any(|r| matches!(r, Request::Stop { grace_secs: 120, attempt: 1, .. }))
            })
            .await;
        runner_side.event(Event::Exited { ended: Ended::Stopped });

        drain(received, |report| *report == Report::Stopped).await;
        running.await.unwrap();
    }

    #[tokio::test]
    async fn a_future_dropped_before_the_end_stops_the_job_with_no_grace() {
        let (runner_side, runner) = Scripted::runner();
        let (job, _stop, _fence) = job();
        let (reports, _received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;

        running.abort();

        runner_side
            .wait_for("the stop", |seen| seen.iter().any(|r| matches!(r, Request::Stop { grace_secs: 0, .. })))
            .await;
    }

    #[tokio::test]
    async fn an_ending_the_events_missed_is_found_in_the_runners_list() {
        let (runner_side, runner) = Scripted::runner();
        let (job, _stop, _fence) = job();
        let (reports, received) = mpsc::unbounded_channel();
        let running = tokio::spawn(runner.run(job, reports));
        runner_side.wait_for("the start", |seen| seen.iter().any(|r| matches!(r, Request::Start(_)))).await;
        // No event is ever sent; the runner's list says it ended, which the next check finds.
        runner_side.sessions.lock().unwrap().push(SessionInfo {
            session: SESSION.to_owned(),
            attempt: 1,
            phase: Phase::Ended,
            ended: Some(Ended::Succeeded),
            latest_checkpoint_seq: 4,
            latest_log_seq: 9,
            running_for_secs: 90,
            lease_remaining_secs: 60,
            runtime_remaining_secs: 500,
            agent: AgentNotes::default(),
        });

        tokio::time::pause();
        tokio::time::advance(RECONCILE_EVERY + Duration::from_millis(10)).await;
        tokio::time::resume();
        let reports = drain(received, |report| *report == Report::Succeeded).await;

        assert!(
            reports.contains(&Report::Checkpoint(4)) && reports.contains(&Report::Logs(LogEvent::Flushed(9))),
            "{reports:?}"
        );
        running.await.unwrap();
    }

    #[tokio::test]
    async fn a_lease_renewal_and_fresh_grants_are_passed_on() {
        let (runner_side, runner) = Scripted::runner();
        runner.renew(Uuid::parse_str(SESSION).unwrap(), 1, Duration::from_secs(120));
        runner.refresh(
            Uuid::parse_str(SESSION).unwrap(),
            1,
            &Credentials {
                attempt_grant: Some(grant(&["sessions/x/"], StoragePermission::ReadWrite)),
                ..Credentials::default()
            },
        );

        runner_side
            .wait_for("the lease", |seen| seen.iter().any(|r| matches!(r, Request::Lease { ttl_secs: 120, .. })))
            .await;
        runner_side.wait_for("the refresh", |seen| seen.iter().any(|r| matches!(r, Request::Refresh(_)))).await;
    }

    #[tokio::test]
    async fn attempts_that_outlived_the_agent_are_picked_up_and_a_sleeping_distribution_is_left_asleep() {
        let (runner_side, runner) = Scripted::runner();
        runner_side.sessions.lock().unwrap().push(SessionInfo {
            session: SESSION.to_owned(),
            attempt: 2,
            phase: Phase::Running,
            ended: None,
            latest_checkpoint_seq: 3,
            latest_log_seq: 7,
            running_for_secs: 200,
            lease_remaining_secs: 50,
            runtime_remaining_secs: 400,
            agent: AgentNotes { minute_index_base: 5, gpus: vec!["GPU-1".to_owned()] },
        });

        let adopted = runner.adopt().await;
        assert_eq!(adopted.len(), 1);
        let found = &adopted[0];
        assert_eq!(
            (found.attempt, found.state, found.latest_checkpoint_seq, found.latest_log_seq),
            (2, SessionState::Running, 3, 7)
        );
        assert_eq!(
            (found.running_for, found.lease_remaining, found.runtime_remaining),
            (Duration::from_secs(200), Duration::from_secs(50), Duration::from_secs(400))
        );
        assert_eq!((found.minute_index_base, found.gpus.clone()), (5, vec!["GPU-1".to_owned()]));

        let asleep = ContainerRunner::new(Link::new(Arc::new(|| Err(std::io::Error::other("would wake it")))), true)
            .with_awake(|| false);
        assert!(asleep.adopt().await.is_empty(), "never starts a stopped distribution just to look");
    }

    #[test]
    fn only_our_own_distributions_are_ever_talked_to() {
        assert!(ContainerRunner::wsl("Ubuntu").is_err());
        assert!(ContainerRunner::wsl("IdleO-").is_err());
        assert!(ContainerRunner::wsl("IdleO-0.1.0").is_ok());
    }

    fn blocklist_file(version: &str) -> String {
        format!(r#"{{"version":"{version}","names":[],"hashes":[],"pools":[]}}"#)
    }

    #[tokio::test]
    async fn a_newer_blocklist_is_given_to_a_runner_that_is_up_and_an_equal_or_older_one_is_not() {
        let (scripted, runner) = Scripted::runner();
        let newer = blocklist_file("2026-10-25T09:30:00Z");

        runner.blocklist(newer.clone(), vec![7; 64]);
        scripted
            .wait_for("the blocklist", |seen| {
                seen.iter().any(|r| matches!(r, Request::Blocklist { file, signature } if *file == newer && *signature == vec![7; 64]))
            })
            .await;

        // The runner says it already uses that one: a second hand-over sends nothing.
        *scripted.blocklist_version.lock().unwrap() = "2026-10-25T09:30:00Z".to_owned();
        let before = scripted.requests().iter().filter(|r| matches!(r, Request::Blocklist { .. })).count();
        runner.blocklist(newer, vec![7; 64]);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(scripted.requests().iter().filter(|r| matches!(r, Request::Blocklist { .. })).count(), before);

        // And one older than the runner's own is never sent.
        runner.blocklist(blocklist_file("2026-10-24"), vec![7; 64]);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert_eq!(scripted.requests().iter().filter(|r| matches!(r, Request::Blocklist { .. })).count(), before);
    }

    #[tokio::test]
    async fn a_runner_that_reports_no_blocklist_is_given_any_list_the_agent_holds() {
        let (scripted, runner) = Scripted::runner();
        *scripted.blocklist_version.lock().unwrap() = String::new();
        let list = blocklist_file("2026-10-22");

        runner.blocklist(list.clone(), vec![7; 64]);
        scripted
            .wait_for("the blocklist", |seen| {
                seen.iter().any(|r| matches!(r, Request::Blocklist { file, .. } if *file == list))
            })
            .await;
    }

    #[tokio::test]
    async fn a_sleeping_distribution_is_not_woken_for_a_blocklist_and_takes_it_when_a_session_wakes_it() {
        let (scripted, runner) = Scripted::runner();
        let runner = runner.with_awake(|| false);
        let newer = blocklist_file("2026-10-25T09:30:00Z");

        runner.blocklist(newer.clone(), vec![7; 64]);
        tokio::time::sleep(Duration::from_millis(200)).await;
        assert!(
            scripted.requests().is_empty(),
            "nothing is sent to a distribution that is stopped: {:?}",
            scripted.requests()
        );

        runner.warm();
        scripted
            .wait_for("the blocklist", |seen| {
                seen.iter().any(|r| matches!(r, Request::Blocklist { file, .. } if *file == newer))
            })
            .await;
        let hello = scripted.requests().iter().position(|r| matches!(r, Request::Hello)).unwrap();
        let list = scripted.requests().iter().position(|r| matches!(r, Request::Blocklist { .. })).unwrap();
        assert!(hello < list, "the runner is asked what it has before it is given anything");
    }
}
