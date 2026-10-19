//! The sessions the runner holds, and the life of one attempt (ADR 0035).
//!
//! An attempt goes: check the sandbox's rules, pull the image if it isn't there, make the scratch disk, stage the inputs
//! and the checkpoint to resume from into it, start the container, and then watch it: a log pump turns its output into
//! the renter's live log, a sweeper uploads each checkpoint the job finishes, and a loop enforces the stop, the lease and
//! `max_runtime`. When the job exits its outputs go up, the logs are flushed, the container and the scratch disk are
//! deleted whatever happened, and only then is the end reported, so an `Exited` event always comes after everything the
//! attempt will ever upload.
//!
//! **Stopping.** SIGTERM, then the job has the grace the agent gave (0 kills it at once) to save a checkpoint and exit;
//! the sweeper uploads what it finishes meanwhile; then SIGKILL, a last sweep of what's finished, and `Stopped`.
//! **The lease.** The agent renews it every 30 s ([`Registry::lease`]); with nobody renewing, the attempt is killed when
//! it lapses, even if the agent is gone, and ends `lease_expired`. **`max_runtime`** is enforced here too.
//!
//! The events are hints: the truth is [`Registry::list`], which also holds how an attempt ended until the agent says
//! [`Registry::forget`], so an agent that restarts finds what happened while it was away.

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use agent_proto::v1::InputRef;
use jobcore::lease_clock::LeaseClock;
use jobcore::logs::{self, LogEvent, LogWriter};
use jobcore::protocol::{
    AgentNotes, Answer, Check, Ended, Event, Failure, Frame, HelloAnswer, Outcome, Phase, Refresh, SessionInfo, Start,
    code,
};
use jobcore::staging;
use jobcore::storage::ObjectStore;
use tokio::sync::{broadcast, watch};

use crate::container::{self, Plan};
use crate::machine::{Container, Machine, Progress, Scratch, Stream};
use crate::storage::{Opened, Refresher, Storage};
use crate::uploads::{self, Checkpoints};

/// How often an attempt looks at its stop, its lease and its time.
pub const TICK: Duration = Duration::from_secs(1);
/// The longest line of a job's output kept whole; a longer one is cut into lines of this size.
const MAX_LINE: usize = 16 * 1024;
/// How long the log pump has to drain a job's last output once it has exited.
const DRAIN: Duration = Duration::from_secs(3);
/// Below this much free space on the scratch disk, a job that failed is said to have filled it.
const FULL_BELOW: u64 = 1024 * 1024;

/// Everything the runner holds.
pub struct Registry {
    machine: Arc<dyn Machine>,
    storage: Arc<dyn Storage>,
    clock: Arc<dyn LeaseClock>,
    tick: Duration,
    entries: Mutex<BTreeMap<(String, i32), Arc<Entry>>>,
    events: broadcast::Sender<String>,
    /// Goes up when downloads are to stop.
    pulls: watch::Sender<u64>,
}

/// One attempt as the runner holds it.
pub struct Entry {
    session: String,
    attempt: i32,
    state: Mutex<State>,
    stop: watch::Sender<Option<u32>>,
    refresh: Mutex<Option<Refresher>>,
}

struct State {
    phase: Phase,
    ended: Option<Ended>,
    latest_checkpoint_seq: i32,
    latest_log_seq: i32,
    running_since: Option<Duration>,
    lease_deadline: Duration,
    runtime_deadline: Duration,
    agent: AgentNotes,
}

impl Entry {
    fn state(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// Reports an attempt's events: records them in its entry, then tells whoever's listening.
#[derive(Clone)]
struct Emitter {
    entry: Arc<Entry>,
    events: broadcast::Sender<String>,
}

impl Emitter {
    fn send(&self, event: Event) {
        let _ = self.events.send(Frame::event(&self.entry.session, self.entry.attempt, event));
    }

    fn phase(&self, phase: Phase) {
        self.entry.state().phase = phase;
        self.send(Event::State { phase });
    }

    fn checkpoint(&self, seq: i32) {
        {
            let mut state = self.entry.state();
            state.latest_checkpoint_seq = state.latest_checkpoint_seq.max(seq);
        }
        self.send(Event::Checkpoint { seq });
    }

    fn logs(&self, event: LogEvent) {
        let (seq, flushed) = match event {
            LogEvent::Uploaded(seq) => (seq, false),
            LogEvent::Flushed(seq) => (seq, true),
        };
        {
            let mut state = self.entry.state();
            state.latest_log_seq = state.latest_log_seq.max(seq);
        }
        self.send(Event::Logs { latest_seq: seq, flushed });
    }
}

/// Why an attempt was interrupted.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Interrupt {
    Stop(u32),
    LeaseLapsed,
    TimedOut,
}

/// Watches an attempt's stop, lease and time.
struct Watch {
    entry: Arc<Entry>,
    clock: Arc<dyn LeaseClock>,
    stop: watch::Receiver<Option<u32>>,
    tick: Duration,
}

impl Watch {
    /// What's wrong now, if anything: the lease and the time first, since a stop only asks nicely.
    fn check(&self) -> Option<Interrupt> {
        let now = self.clock.now();
        let state = self.entry.state();
        if now >= state.lease_deadline {
            return Some(Interrupt::LeaseLapsed);
        }
        if now >= state.runtime_deadline {
            return Some(Interrupt::TimedOut);
        }
        drop(state);
        (*self.stop.borrow()).map(Interrupt::Stop)
    }

    async fn next(&mut self) -> Interrupt {
        loop {
            if let Some(interrupt) = self.check() {
                return interrupt;
            }
            tokio::select! {
                _ = self.stop.changed() => {}
                () = tokio::time::sleep(self.tick) => {}
            }
        }
    }

    /// The next lease lapse or time-out, ignoring a stop: for an attempt that's already stopping.
    async fn next_lapse(&mut self) -> Interrupt {
        loop {
            if let Some(lapse @ (Interrupt::LeaseLapsed | Interrupt::TimedOut)) = self.check() {
                return lapse;
            }
            tokio::time::sleep(self.tick).await;
        }
    }

    /// Runs `step`, unless an interrupt comes first.
    async fn guard<T>(&mut self, step: impl std::future::Future<Output = T>) -> Result<T, Interrupt> {
        tokio::select! {
            value = step => Ok(value),
            interrupt = self.next() => Err(interrupt),
        }
    }
}

fn failed(code: &str, message: impl Into<String>) -> Ended {
    Ended::Failed { code: code.to_owned(), message: message.into(), exit_code: None }
}

fn interrupted(interrupt: Interrupt) -> Ended {
    match interrupt {
        Interrupt::Stop(_) => Ended::Stopped,
        Interrupt::LeaseLapsed => failed(code::LEASE_EXPIRED, "the lease ran out before it was renewed"),
        Interrupt::TimedOut => failed(code::TIMED_OUT, "the job ran past its maximum runtime"),
    }
}

impl Registry {
    /// A registry over `machine`, with storage from `storage`.
    #[must_use]
    pub fn new(machine: Arc<dyn Machine>, storage: Arc<dyn Storage>, clock: Arc<dyn LeaseClock>) -> Arc<Self> {
        Self::with_tick(machine, storage, clock, TICK)
    }

    /// The same, looking at stops, leases and times every `tick`.
    #[must_use]
    pub fn with_tick(
        machine: Arc<dyn Machine>,
        storage: Arc<dyn Storage>,
        clock: Arc<dyn LeaseClock>,
        tick: Duration,
    ) -> Arc<Self> {
        let (events, _) = broadcast::channel(4096);
        Arc::new(Self {
            machine,
            storage,
            clock,
            tick,
            entries: Mutex::new(BTreeMap::new()),
            events,
            pulls: watch::channel(0).0,
        })
    }

    /// Every event from now on, as lines, for a connection to forward.
    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<String> {
        self.events.subscribe()
    }

    fn entries(&self) -> MutexGuard<'_, BTreeMap<(String, i32), Arc<Entry>>> {
        self.entries.lock().unwrap_or_else(PoisonError::into_inner)
    }

    fn entry(&self, session: &str, attempt: i32) -> Result<Arc<Entry>, Failure> {
        self.entries()
            .get(&(session.to_owned(), attempt))
            .cloned()
            .ok_or_else(|| Failure::new(code::BAD_REQUEST, "no such session here"))
    }

    /// Who the runner is.
    pub async fn hello(&self) -> HelloAnswer {
        self.machine.hello().await
    }

    /// Every attempt held, and where each stands.
    #[must_use]
    pub fn list(&self) -> Vec<SessionInfo> {
        let now = self.clock.now();
        self.entries()
            .values()
            .map(|entry| {
                let state = entry.state();
                SessionInfo {
                    session: entry.session.clone(),
                    attempt: entry.attempt,
                    phase: state.phase,
                    ended: state.ended.clone(),
                    latest_checkpoint_seq: state.latest_checkpoint_seq,
                    latest_log_seq: state.latest_log_seq,
                    running_for_secs: state.running_since.map_or(0, |since| now.saturating_sub(since).as_secs()),
                    lease_remaining_secs: state.lease_deadline.saturating_sub(now).as_secs(),
                    runtime_remaining_secs: state.runtime_deadline.saturating_sub(now).as_secs(),
                    agent: state.agent.clone(),
                }
            })
            .collect()
    }

    /// Starts an attempt. Answered at once; how it goes comes as events. A start for an attempt already held is answered
    /// the same and does nothing.
    ///
    /// # Errors
    /// If the request can't be acted on at all.
    pub fn start(self: &Arc<Self>, start: Start) -> Result<(), Failure> {
        if start.attempt <= 0
            || start.session.is_empty()
            || start.spec.max_runtime_secs == 0
            || start.lease_ttl_secs == 0
        {
            return Err(Failure::new(code::BAD_REQUEST, "the start lacks a session, an attempt, a runtime or a lease"));
        }
        let now = self.clock.now();
        let (stop, stop_rx) = watch::channel(None);
        let entry = Arc::new(Entry {
            session: start.session.clone(),
            attempt: start.attempt,
            state: Mutex::new(State {
                phase: Phase::Preparing,
                ended: None,
                latest_checkpoint_seq: 0,
                latest_log_seq: 0,
                running_since: None,
                lease_deadline: now + Duration::from_secs(u64::from(start.lease_ttl_secs)),
                runtime_deadline: now + Duration::from_secs(start.spec.max_runtime_secs),
                agent: start.agent.clone(),
            }),
            stop,
            refresh: Mutex::new(None),
        });
        {
            let mut entries = self.entries();
            if entries.contains_key(&(start.session.clone(), start.attempt)) {
                return Ok(());
            }
            entries.insert((start.session.clone(), start.attempt), Arc::clone(&entry));
        }
        let registry = Arc::clone(self);
        tokio::spawn(async move {
            let emitter = Emitter { entry: Arc::clone(&entry), events: registry.events.clone() };
            let watch = Watch {
                entry: Arc::clone(&entry),
                clock: Arc::clone(&registry.clock),
                stop: stop_rx,
                tick: registry.tick,
            };
            let ended = registry.attempt(&entry, &emitter, watch, start).await;
            {
                let mut state = entry.state();
                state.phase = Phase::Ended;
                state.ended = Some(ended.clone());
            }
            emitter.send(Event::Exited { ended });
        });
        Ok(())
    }

    /// Asks an attempt to stop: SIGTERM, then `grace_secs` to save and exit, then SIGKILL. A repeated stop keeps the first's
    /// grace.
    ///
    /// # Errors
    /// If there's no such attempt.
    pub fn stop(&self, session: &str, attempt: i32, grace_secs: u32) -> Result<(), Failure> {
        let entry = self.entry(session, attempt)?;
        entry.stop.send_if_modified(|stop| {
            let first = stop.is_none();
            if first {
                *stop = Some(grace_secs);
            }
            first
        });
        Ok(())
    }

    /// The lease was renewed: the attempt may run `ttl_secs` more. It never shortens.
    ///
    /// # Errors
    /// If there's no such attempt.
    pub fn lease(&self, session: &str, attempt: i32, ttl_secs: u32) -> Result<(), Failure> {
        let entry = self.entry(session, attempt)?;
        let until = self.clock.now() + Duration::from_secs(u64::from(ttl_secs));
        let mut state = entry.state();
        state.lease_deadline = state.lease_deadline.max(until);
        Ok(())
    }

    /// Fresh grants for a running attempt.
    ///
    /// # Errors
    /// If there's no such attempt, or the grants aren't for the same prefixes with the same permissions.
    pub fn refresh(&self, refresh: &Refresh) -> Result<(), Failure> {
        let entry = self.entry(&refresh.session, refresh.attempt)?;
        let guard = entry.refresh.lock().unwrap_or_else(PoisonError::into_inner);
        let Some(replace) = guard.as_ref() else {
            return Err(Failure::new(code::BAD_REQUEST, "that attempt isn't running"));
        };
        replace(&refresh.grants).map_err(|message| Failure::new(code::BAD_REQUEST, message))
    }

    /// Deletes the record of an ended attempt.
    ///
    /// # Errors
    /// If it's still running, or there's no such attempt.
    pub fn forget(&self, session: &str, attempt: i32) -> Result<(), Failure> {
        let entry = self.entry(session, attempt)?;
        if entry.state().ended.is_none() {
            return Err(Failure::new(code::BAD_REQUEST, "that attempt is still running"));
        }
        self.entries().remove(&(session.to_owned(), attempt));
        Ok(())
    }

    /// Stops every download under way.
    pub fn cancel_pulls(&self) {
        self.pulls.send_modify(|generation| *generation += 1);
    }

    /// Downloads an image, reporting progress as events with no session. A [`Registry::cancel_pulls`] ends it.
    ///
    /// # Errors
    /// If the image isn't pinned by digest or can't be pulled.
    pub async fn pull(&self, image: &str) -> Result<(), Failure> {
        container::check_image(image).map_err(|r| Failure::new(code::IMAGE_PULL_FAILED, r.0))?;
        let events = self.events.clone();
        let named = image.to_owned();
        let progress: Progress = Arc::new(move |done, total| {
            let _ = events.send(Frame::event(
                "",
                0,
                Event::Pull { image: named.clone(), done_bytes: done, total_bytes: total },
            ));
        });
        let mut cancelled = self.pulls.subscribe();
        cancelled.mark_unchanged();
        tokio::select! {
            result = self.machine.ensure_image(image, progress) => {
                result.map_err(|why| Failure::new(code::IMAGE_PULL_FAILED, why))
            }
            _ = cancelled.changed() => Err(Failure::new(code::IMAGE_PULL_FAILED, "the download was stopped for a session that is starting")),
        }
    }

    /// The self-test's checks.
    pub async fn self_check(&self, cuda_image: Option<&str>) -> Vec<Check> {
        self.machine.self_check(cuda_image).await
    }

    /// The answer to one request, as the reply the agent reads.
    pub async fn answer(self: &Arc<Self>, request: jobcore::protocol::Request) -> Outcome {
        use jobcore::protocol::Request;
        let done = |result: Result<(), Failure>| match result {
            Ok(()) => Outcome::Ok(Answer::Done),
            Err(failure) => Outcome::Err(failure),
        };
        match request {
            Request::Hello => Outcome::Ok(Answer::Hello(self.hello().await)),
            Request::List => Outcome::Ok(Answer::List { sessions: self.list() }),
            Request::Start(start) => done(self.start(*start)),
            Request::Stop { session, attempt, grace_secs } => done(self.stop(&session, attempt, grace_secs)),
            Request::Lease { session, attempt, ttl_secs } => done(self.lease(&session, attempt, ttl_secs)),
            Request::Refresh(refresh) => done(self.refresh(&refresh)),
            Request::Pull { image } => done(self.pull(&image).await),
            Request::CancelPulls => {
                self.cancel_pulls();
                Outcome::Ok(Answer::Done)
            }
            Request::Forget { session, attempt } => done(self.forget(&session, attempt)),
            Request::SelfCheck { cuda_image } => {
                Outcome::Ok(Answer::SelfCheck { checks: self.self_check(cuda_image.as_deref()).await })
            }
        }
    }

    /// One attempt, start to end, cleaning up after itself whatever happens.
    async fn attempt(&self, entry: &Arc<Entry>, emitter: &Emitter, mut watch: Watch, start: Start) -> Ended {
        let opened = match self.storage.open(&start.grants) {
            Ok(opened) => opened,
            Err(why) => return failed(code::INVALID_INPUT, why),
        };
        let Opened { attempt: attempt_store, read: read_store, refresh } = opened;
        *entry.refresh.lock().unwrap_or_else(PoisonError::into_inner) = Some(refresh);
        let (finish, finished) = watch::channel(false);
        let sink_emitter = emitter.clone();
        let (log, sink) =
            logs::spawn(Arc::clone(&attempt_store), format!("{}logs/", start.attempt_prefix), finished, move |event| {
                sink_emitter.logs(event);
            });
        let mut held = Held::default();
        let ended =
            self.phases(emitter, &mut watch, &start, &attempt_store, read_store.as_ref(), &log, &mut held).await;
        if let Some(container) = held.container.take() {
            container.remove().await;
        }
        let _ = finish.send(true);
        let _ = sink.await;
        if let Some(scratch) = held.scratch.take() {
            self.machine.remove_scratch(&scratch).await;
        }
        *entry.refresh.lock().unwrap_or_else(PoisonError::into_inner) = None;
        ended
    }

    #[allow(clippy::too_many_arguments, clippy::too_many_lines)] // One attempt's whole context, and its phases in a straight line, each with its guard and timing in view.
    async fn phases(
        &self,
        emitter: &Emitter,
        watch: &mut Watch,
        start: &Start,
        attempt_store: &Arc<dyn ObjectStore>,
        read_store: Option<&Arc<dyn ObjectStore>>,
        log: &LogWriter,
        held: &mut Held,
    ) -> Ended {
        macro_rules! step {
            ($future:expr) => {
                match watch.guard($future).await {
                    Ok(value) => value,
                    Err(interrupt) => return interrupted(interrupt),
                }
            };
        }
        emitter.phase(Phase::Preparing);
        let began = std::time::Instant::now();
        let mut timings = Timings::default();
        if let Err(why) = self.machine.check_sandbox().await {
            return failed(code::SANDBOX_NOT_READY, why);
        }
        if let Err(refused) = container::check_image(&start.spec.image) {
            return failed(code::IMAGE_PULL_FAILED, refused.0);
        }
        let input_refs: Vec<InputRef> = start
            .inputs
            .iter()
            .map(|file| InputRef {
                prefix: file.prefix.clone(),
                name: file.name.clone(),
                size_bytes: file.size_bytes,
                slot: file.slot.clone(),
            })
            .collect();
        let read_grant = start
            .grants
            .read
            .as_ref()
            .and_then(|wire| jobcore::storage::Grant::from_message(&wire.to_message(), self.clock.now()).ok());
        if let Err(why) = staging::check_covered(&input_refs, read_grant.as_ref()) {
            return failed(code::INVALID_INPUT, why);
        }
        if start.resume_from.as_ref().is_some_and(|from| from.prefix.is_empty() || from.seq <= 0)
            || (start.resume_from.is_some() && read_store.is_none())
        {
            return failed(code::INVALID_INPUT, "the checkpoint to resume from is invalid or has no read grant");
        }

        timings.sandbox = began.elapsed();
        let mark = std::time::Instant::now();
        if let Err(why) = step!(self.machine.ensure_image(&start.spec.image, Arc::new(|_, _| {}))) {
            return failed(code::IMAGE_PULL_FAILED, why);
        }
        timings.image = mark.elapsed();
        let mark = std::time::Instant::now();
        let key = format!("{}-{}", start.session, start.attempt);
        let scratch = match step!(self.machine.make_scratch(&key, start.spec.scratch_mb)) {
            Ok(scratch) => held.scratch.insert(scratch).clone(),
            Err(why) => return failed(code::RUNNER_FAILED, why),
        };
        timings.scratch = mark.elapsed();
        let mark = std::time::Instant::now();

        let mut slots = Vec::new();
        if let (false, Some(store)) = (input_refs.is_empty(), read_store) {
            match step!(staging::stage(Arc::clone(store), &input_refs, &scratch.root, log)) {
                Ok(folders) => slots = folders.into_keys().collect(),
                Err(why) => return failed("input_unavailable", why),
            }
        }
        timings.staging = mark.elapsed();
        let mark = std::time::Instant::now();
        let mut resume = None;
        if let (Some(from), Some(store)) = (&start.resume_from, read_store) {
            match step!(uploads::fetch_checkpoint(store.as_ref(), from, &scratch.resume)) {
                Ok(bytes) => {
                    log.line(format!("resuming from checkpoint {} ({bytes} bytes)", from.seq));
                    resume = Some(scratch.resume.clone());
                }
                Err(why) => return failed("resume_checkpoint_missing", why),
            }
        }

        let plan = Plan {
            // The whole id: the first characters of a UUIDv7 are its time, the same for sessions made in one moment.
            name: format!("idleo-{}-{}", start.session, start.attempt),
            session: start.session.clone(),
            attempt: start.attempt,
            network: String::new(),
            spec: start.spec.clone(),
            work: scratch.work.clone(),
            input: scratch.input.clone(),
            resume,
            slots,
            development: self.machine.hello().await.development,
            entrypoint: None,
        };
        timings.resume = mark.elapsed();
        let mark = std::time::Instant::now();
        let container = match step!(self.machine.start_container(plan)) {
            Ok(container) => Arc::clone(held.container.insert(container)),
            Err(why) => return failed(code::RUNNER_FAILED, why),
        };
        timings.container = mark.elapsed();
        timings.started = Some(std::time::Instant::now());
        timings.since_start = began.elapsed();
        tracing::info!(session = %start.session, attempt = start.attempt, ?timings, "an attempt's container is running");
        *held.timings.lock().unwrap_or_else(PoisonError::into_inner) = Some(timings);

        emitter.entry.state().running_since = Some(self.clock.now());
        emitter.phase(Phase::Running);
        self.watch_job(emitter, watch, start, attempt_store, &scratch, &container, log).await
    }

    /// The job runs: its output becomes the log, its checkpoints go up, and the stop, the lease and the time are enforced,
    /// until it exits; then its end is decided.
    #[allow(clippy::too_many_arguments)] // One attempt's whole context, threaded down from `phases`.
    async fn watch_job(
        &self,
        emitter: &Emitter,
        watch: &mut Watch,
        start: &Start,
        attempt_store: &Arc<dyn ObjectStore>,
        scratch: &Scratch,
        container: &Arc<dyn Container>,
        log: &LogWriter,
    ) -> Ended {
        let begun = std::time::Instant::now();
        let session = start.session.clone();
        let pump = container.take_output().map(|mut output| {
            let log = log.clone();
            tokio::spawn(async move {
                let mut lines = Lines::default();
                let mut first = true;
                while let Some((stream, bytes)) = output.recv().await {
                    if std::mem::take(&mut first) {
                        tracing::info!(%session, first_output_ms = begun.elapsed().as_millis(), "the job's first output");
                    }
                    lines.push(stream, &bytes, &log);
                }
                lines.finish(&log);
            })
        });
        let checkpoints = Arc::new(tokio::sync::Mutex::new(Checkpoints::default()));
        let (sweeping, sweep_stop) = watch::channel(false);
        let sweeper = tokio::spawn(sweep_loop(
            Arc::clone(&checkpoints),
            Arc::clone(attempt_store),
            scratch.work.clone(),
            start.attempt_prefix.clone(),
            emitter.clone(),
            log.clone(),
            sweep_stop,
            self.tick,
        ));

        let exit = container.wait();
        tokio::pin!(exit);
        let mut cause: Option<Interrupt> = None;
        let mut kill_at: Option<Duration> = None;
        let mut killed = false;
        let exited = loop {
            tokio::select! {
                exited = &mut exit => break exited,
                interrupt = async {
                    match cause {
                        None => watch.next().await,
                        Some(Interrupt::Stop(_)) => watch.next_lapse().await,
                        Some(_) => std::future::pending().await,
                    }
                } => {
                    match interrupt {
                        Interrupt::Stop(grace) => {
                            cause = Some(interrupt);
                            log.line("the job was asked to stop: saving its progress");
                            if grace == 0 {
                                container.signal("KILL").await;
                                killed = true;
                            } else {
                                container.signal("TERM").await;
                                kill_at = Some(self.clock.now() + Duration::from_secs(u64::from(grace)));
                            }
                        }
                        Interrupt::LeaseLapsed | Interrupt::TimedOut => {
                            cause = Some(interrupt);
                            container.signal("KILL").await;
                            killed = true;
                        }
                    }
                }
                () = tokio::time::sleep(self.tick) => {
                    if !killed && kill_at.is_some_and(|at| self.clock.now() >= at) {
                        log.line("the job didn't stop in time and was killed");
                        container.signal("KILL").await;
                        killed = true;
                    }
                }
            }
        };
        let oom = container.oom_killed().await;
        container.remove().await;
        if let Some(pump) = pump {
            let _ = tokio::time::timeout(DRAIN, pump).await;
        }
        let _ = sweeping.send(true);
        let _ = sweeper.await;

        self.conclude(emitter, start, attempt_store, scratch, log, &checkpoints, cause, exited.code, oom).await
    }

    /// Decides how a job that has exited and been removed ended: what it finished is uploaded (the job is dead, so nothing
    /// changes under the upload), then, with no stop, lapse or time limit to blame, its exit code and the kernel's say.
    #[allow(clippy::too_many_arguments)] // The facts of one ended job, gathered by `watch_job`.
    async fn conclude(
        &self,
        emitter: &Emitter,
        start: &Start,
        attempt_store: &Arc<dyn ObjectStore>,
        scratch: &Scratch,
        log: &LogWriter,
        checkpoints: &tokio::sync::Mutex<Checkpoints>,
        cause: Option<Interrupt>,
        exit_code: i32,
        oom: bool,
    ) -> Ended {
        let lapsed = matches!(cause, Some(Interrupt::LeaseLapsed));
        if !lapsed {
            // The job is dead, so what it finished can no longer change under the upload.
            let mut checkpoints = checkpoints.lock().await;
            let emitter = emitter.clone();
            match checkpoints
                .sweep(attempt_store.as_ref(), &scratch.work, &start.attempt_prefix, move |seq| emitter.checkpoint(seq))
                .await
            {
                Ok(lines) => lines.iter().for_each(|line| log.line(line)),
                Err(why) => log.line(format!("a checkpoint couldn't be saved: {why}")),
            }
        }
        match cause {
            Some(Interrupt::LeaseLapsed) => return interrupted(Interrupt::LeaseLapsed),
            Some(Interrupt::TimedOut) => return interrupted(Interrupt::TimedOut),
            Some(Interrupt::Stop(_)) => return Ended::Stopped,
            None => {}
        }
        if exit_code == 0 {
            emitter.phase(Phase::Uploading);
            return match uploads::upload_outputs(attempt_store.as_ref(), &scratch.work, &start.attempt_prefix).await {
                Ok(uploaded) => {
                    for path in &uploaded.skipped {
                        log.line(format!("outputs/{path} isn't a regular file, so it isn't uploaded"));
                    }
                    log.line(format!("outputs uploaded ({} files)", uploaded.manifest.files.len()));
                    Ended::Succeeded
                }
                Err(why) => failed(code::OUTPUTS_UPLOAD_FAILED, why.to_string()),
            };
        }
        if oom {
            return Ended::Failed {
                code: code::OUT_OF_MEMORY.to_owned(),
                message: "the job used more memory than it was given".to_owned(),
                exit_code: Some(exit_code),
            };
        }
        if let Some(message) = invalid_input_said(&scratch.work) {
            return Ended::Failed { code: code::INVALID_INPUT.to_owned(), message, exit_code: Some(exit_code) };
        }
        if self.machine.scratch_free(scratch) < FULL_BELOW {
            return Ended::Failed {
                code: code::SCRATCH_FULL.to_owned(),
                message: "the job filled its scratch disk".to_owned(),
                exit_code: Some(exit_code),
            };
        }
        Ended::Failed {
            code: code::JOB_FAILED.to_owned(),
            message: format!("the job exited with code {exit_code}"),
            exit_code: Some(exit_code),
        }
    }
}

/// What the job said about its input when it failed: its `idleo-failure.json`, read without following a link, at most 4 KiB,
/// and believed only for the code `invalid_input` (a job can say its renter's input is wrong, and nothing else about itself).
/// The line is the renter's to read, so it's one plain line of at most 200 characters.
fn invalid_input_said(work: &std::path::Path) -> Option<String> {
    use std::io::Read as _;
    let file = crate::safefs::Tree::open(work).ok()?.open_file(crate::container::FAILURE_FILE).ok()?;
    let mut text = String::new();
    file.take(4096).read_to_string(&mut text).ok()?;
    let said: serde_json::Value = serde_json::from_str(&text).ok()?;
    if said.get("code")?.as_str()? != code::INVALID_INPUT {
        return None;
    }
    let line: String = said.get("message")?.as_str()?.chars().filter(|c| !c.is_control()).take(200).collect();
    let line = line.trim();
    Some(if line.is_empty() {
        "The job says what you gave it isn't what it needs.".to_owned()
    } else {
        line.to_owned()
    })
}

/// What an attempt has made, to unmake at its end.
#[derive(Default)]
struct Held {
    scratch: Option<Scratch>,
    container: Option<Arc<dyn Container>>,
    timings: Arc<Mutex<Option<Timings>>>,
}

/// Where the time went before the job ran, logged for each attempt: the number a renter feels is the sum, then the job's
/// first line.
#[derive(Debug, Default, Clone, Copy)]
#[allow(dead_code)] // Read through `Debug` in the log, which is the point.
struct Timings {
    sandbox: Duration,
    image: Duration,
    scratch: Duration,
    staging: Duration,
    resume: Duration,
    container: Duration,
    since_start: Duration,
    started: Option<std::time::Instant>,
}

#[allow(clippy::too_many_arguments)] // Everything one sweeper task needs, moved in once.
async fn sweep_loop(
    checkpoints: Arc<tokio::sync::Mutex<Checkpoints>>,
    store: Arc<dyn ObjectStore>,
    work: std::path::PathBuf,
    attempt_prefix: String,
    emitter: Emitter,
    log: LogWriter,
    mut stop: watch::Receiver<bool>,
    every: Duration,
) {
    loop {
        tokio::select! {
            () = tokio::time::sleep(every) => {}
            _ = stop.changed() => return,
        }
        if *stop.borrow() {
            return;
        }
        let mut guard = checkpoints.lock().await;
        let reporter = emitter.clone();
        match guard.sweep(store.as_ref(), &work, &attempt_prefix, move |seq| reporter.checkpoint(seq)).await {
            Ok(lines) => lines.iter().for_each(|line| log.line(line)),
            Err(why) => tracing::warn!(%why, "a checkpoint couldn't be saved yet; trying again"),
        }
    }
}

/// Turns a job's output chunks into whole lines, per stream, so stdout and stderr never mix inside a line.
#[derive(Default)]
struct Lines {
    stdout: Vec<u8>,
    stderr: Vec<u8>,
}

impl Lines {
    fn push(&mut self, stream: Stream, bytes: &[u8], log: &LogWriter) {
        let pending = match stream {
            Stream::Stdout => &mut self.stdout,
            Stream::Stderr => &mut self.stderr,
        };
        pending.extend_from_slice(bytes);
        while let Some(end) =
            pending.iter().position(|b| *b == b'\n').or_else(|| (pending.len() >= MAX_LINE).then_some(MAX_LINE - 1))
        {
            let rest = pending.split_off(end + 1);
            let line = std::mem::replace(pending, rest);
            log.write(line_with_newline(line));
        }
    }

    fn finish(&mut self, log: &LogWriter) {
        for pending in [&mut self.stdout, &mut self.stderr] {
            if !pending.is_empty() {
                log.write(line_with_newline(std::mem::take(pending)));
            }
        }
    }
}

fn line_with_newline(mut line: Vec<u8>) -> Vec<u8> {
    if line.last() != Some(&b'\n') {
        line.push(b'\n');
    }
    line
}
