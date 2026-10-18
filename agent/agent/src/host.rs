//! The agent's side of the Windows app (ADR 0033): one place that knows where the machine stands, for the stream,
//! the tray and the launcher.
//!
//! - **Availability**: auto-pause's state machine ([`crate::availability`]), stepped with a fresh sample whenever a
//!   heartbeat or an offer needs `owner_active`, and every second by [`watch`], which also stops a running job after
//!   its checkpoint when the owner comes back.
//! - **What the control plane said**: the latest `HostStatus` (the host's state, the owner's pause, today's earnings).
//! - **The tray's status**: pushed to every local-channel client on every change ([`Host::subscribe`]).
//! - **The outbox**: `SetPause`, `Leave`, `UpdateResult` and `SelfTestReport`, kept until the control plane acks them
//!   and sent again on every new stream, like the supervisor's statuses.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body as AgentBody;
use agent_proto::v1::{HostState, HostStatus, OpenStreamRequest};
use idleo_release::channel::{self, AutoPause, LinkState, Status, UpdatePhase};
use time::OffsetDateTime;
use tokio::sync::{Notify, watch};
use tokio::time::Instant;

use crate::availability::{Availability, Inputs, OwnerPause, State};
use crate::new_message_id;
use crate::owner::MachineInputs;
use crate::settings;

/// What a queued message is, so its ack can be acted on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Kind {
    /// The owner paused or resumed from the tray.
    SetPause,
    /// The app is being uninstalled.
    Leave,
    /// How an update went.
    UpdateResult,
    /// The self-test's results.
    SelfTest,
    /// Asking for a storage grant for the self-test's speed check.
    SpeedTest,
}

struct Pending {
    request: OpenStreamRequest,
    kind: Kind,
    sent_at: Option<Instant>,
}

/// Where an uninstall's goodbye stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Leaving {
    /// Not asked.
    #[default]
    No,
    /// `Leave` is queued or sent, not yet answered.
    Asked,
    /// The control plane acked it, or ended the stream because the host was removed.
    Done,
}

struct Inner {
    availability: Availability,
    link: LinkState,
    reachable: bool,
    earned_today: Option<i64>,
    earning_now: Option<i64>,
    session_running: bool,
    gpu_util: Option<u32>,
    /// Whether the GPU's use counts for auto-pause: real GPUs on Windows only (see [`Host::ignore_gpu_use`]).
    gpu_watched: bool,
    update: UpdatePhase,
    outbox: Vec<Pending>,
    leaving: Leaving,
    /// When an offer was last accepted, for "not mid-offer".
    accepted_at: Option<Instant>,
    /// Waiting for the control plane's speed-check grant.
    speed: Option<tokio::sync::oneshot::Sender<agent_proto::v1::SpeedTestGrant>>,
}

/// The hub. Shared by the connection, the local channel and the updater.
pub struct Host {
    inner: Mutex<Inner>,
    status: watch::Sender<Status>,
    changed: Notify,
    inputs: Arc<dyn MachineInputs>,
    data_dir: PathBuf,
    started: Instant,
    runner_installed: bool,
    app_url: Option<String>,
}

impl Host {
    /// A hub for the agent whose settings live in `data_dir`; `runner_installed` says whether it can run jobs.
    #[must_use]
    pub fn new(inputs: Arc<dyn MachineInputs>, data_dir: PathBuf, runner_installed: bool) -> Arc<Self> {
        Self::with_app_url(inputs, data_dir, runner_installed, None)
    }

    /// The same, telling the tray where the web app is.
    #[must_use]
    pub fn with_app_url(
        inputs: Arc<dyn MachineInputs>,
        data_dir: PathBuf,
        runner_installed: bool,
        app_url: Option<String>,
    ) -> Arc<Self> {
        let auto_pause = settings::load(&data_dir);
        let inner = Inner {
            availability: Availability::new(auto_pause),
            link: LinkState::Unknown,
            reachable: false,
            earned_today: None,
            earning_now: None,
            session_running: false,
            gpu_util: None,
            gpu_watched: true,
            update: UpdatePhase::Idle,
            outbox: Vec::new(),
            leaving: Leaving::No,
            accepted_at: None,
            speed: None,
        };
        let (status, _) =
            watch::channel(Self::status_of(&inner, runner_installed, app_url.as_deref(), OffsetDateTime::now_utc()));
        Arc::new(Self {
            inner: Mutex::new(inner),
            status,
            changed: Notify::new(),
            inputs,
            data_dir,
            started: Instant::now(),
            runner_installed,
            app_url,
        })
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// The tray's status, now and on every change.
    pub fn subscribe(&self) -> watch::Receiver<Status> {
        self.status.subscribe()
    }

    /// The status now.
    #[must_use]
    pub fn status(&self) -> Status {
        self.status.borrow().clone()
    }

    fn publish(&self, inner: &Inner) {
        let next = Self::status_of(inner, self.runner_installed, self.app_url.as_deref(), OffsetDateTime::now_utc());
        self.status.send_if_modified(|current| {
            if *current == next {
                false
            } else {
                *current = next;
                true
            }
        });
    }

    fn status_of(inner: &Inner, runner_installed: bool, app_url: Option<&str>, wall: OffsetDateTime) -> Status {
        let (state, reason, paused_until) = match inner.availability.state() {
            State::Available => (channel::State::Available, None, None),
            State::AutoPaused { reason, .. } => (channel::State::AutoPaused, Some(reason), None),
            State::HostingOff => (channel::State::HostingOff, None, None),
            State::Paused { until } => (channel::State::Paused, None, Some(until)),
        };
        Status {
            state,
            reason,
            resumes_at: inner.availability.resumes_at(wall).map(|at| at.replace_nanosecond(0).unwrap_or(at)),
            paused_until,
            earned_today_paise: inner.earned_today,
            earning_now_paise: inner.earning_now.filter(|_| inner.session_running),
            session_running: inner.session_running,
            link_state: inner.link,
            server_reachable: inner.reachable,
            update: inner.update,
            runner_installed,
            dev_build: idleo_release::keys::DEV_BUILD,
            auto_pause: inner.availability.settings(),
            app_url: app_url.map(str::to_owned),
        }
    }

    /// Samples the machine and steps availability. Returns whether the owner is active now, and whether this step
    /// moved into an auto-pause (the owner just came back).
    pub fn step(&self, session_running: bool) -> (bool, bool) {
        let sample = self.inputs.sample();
        let mut inner = self.lock();
        inner.session_running = session_running;
        let inputs = Inputs {
            idle: sample.idle,
            fullscreen: sample.fullscreen,
            on_battery: sample.on_battery,
            gpu_util_pct: inner.gpu_util,
            session_running,
        };
        let (before, after) = inner.availability.step(self.started.elapsed(), OffsetDateTime::now_utc(), &inputs);
        let came_back = matches!(after, State::AutoPaused { .. }) && !matches!(before, State::AutoPaused { .. });
        let active = inner.availability.owner_active();
        self.publish(&inner);
        (active, came_back)
    }

    /// The busiest GPU's latest use, for the GPU-busy rule.
    pub fn set_gpu_util(&self, util: Option<u32>) {
        let mut inner = self.lock();
        if inner.gpu_watched {
            inner.gpu_util = util;
        }
    }

    /// Never counts the GPU's use for auto-pause: on Linux and macOS, where nobody is watched (`crate::owner`), and
    /// with fake GPUs, whose use is invented and must never pause a host or keep it from being offered work.
    pub fn ignore_gpu_use(&self) {
        let mut inner = self.lock();
        inner.gpu_watched = false;
        inner.gpu_util = None;
    }

    /// Whether the control plane can be reached: true once welcomed, false when the stream ends.
    pub fn set_reachable(&self, reachable: bool) {
        let mut inner = self.lock();
        inner.reachable = reachable;
        self.publish(&inner);
    }

    /// Where an update stands.
    pub fn set_update(&self, phase: UpdatePhase) {
        let mut inner = self.lock();
        inner.update = phase;
        self.publish(&inner);
    }

    /// Where the update stands now.
    #[must_use]
    pub fn update_phase(&self) -> UpdatePhase {
        self.lock().update
    }

    /// The control plane said where the host stands.
    pub fn on_host_status(&self, status: &HostStatus) {
        let mut inner = self.lock();
        inner.link = match status.state() {
            HostState::PendingApproval => LinkState::PendingApproval,
            HostState::Active | HostState::Paused => LinkState::Active,
            HostState::Removed => LinkState::Removed,
            HostState::Suspended => LinkState::Suspended,
            HostState::Unspecified => inner.link,
        };
        let until = status.paused_until.and_then(|at| OffsetDateTime::from_unix_timestamp(at.seconds).ok()).map(|at| {
            at + Duration::from_nanos(u64::try_from(status.paused_until.map_or(0, |t| t.nanos)).unwrap_or(0))
        });
        let pause = match (status.state(), until) {
            (HostState::Paused, Some(until)) => OwnerPause::Until(until),
            (HostState::Paused, None) => OwnerPause::Indefinite,
            _ => OwnerPause::None,
        };
        inner.availability.set_pause(pause);
        inner.earned_today = Some(status.earned_today_paise);
        inner.earning_now = status.earning_now_paise;
        drop(inner);
        let running = self.lock().session_running;
        self.step(running);
    }

    /// Changes auto-pause and saves it.
    ///
    /// # Errors
    /// If the settings aren't allowed or can't be saved.
    pub fn set_auto_pause(&self, settings: AutoPause) -> Result<(), String> {
        settings::save(&self.data_dir, settings)?;
        let running = {
            let mut inner = self.lock();
            inner.availability.set_settings(settings);
            inner.session_running
        };
        self.step(running);
        Ok(())
    }

    /// Queues a message for the control plane, kept until acked. Returns its message id.
    pub fn queue(&self, body: AgentBody, kind: Kind) -> String {
        let request = OpenStreamRequest { message_id: new_message_id(), body: Some(body) };
        let id = request.message_id.clone();
        let mut inner = self.lock();
        if kind == Kind::Leave {
            inner.leaving = Leaving::Asked;
        }
        // Only the latest pause matters: an older one not yet acked is dropped.
        if kind == Kind::SetPause {
            inner.outbox.retain(|pending| pending.kind != Kind::SetPause);
        }
        inner.outbox.push(Pending { request, kind, sent_at: None });
        drop(inner);
        self.changed.notify_one();
        id
    }

    /// Resolves when a message has been queued.
    pub async fn changed(&self) {
        self.changed.notified().await;
    }

    /// Messages to send now: those never sent on this stream, and those unacked for `resend_after`.
    pub fn due(&self, now: Instant, resend_after: Duration) -> Vec<OpenStreamRequest> {
        self.lock()
            .outbox
            .iter_mut()
            .filter(|pending| pending.sent_at.is_none_or(|sent| now.duration_since(sent) >= resend_after))
            .map(|pending| {
                pending.sent_at = Some(now);
                pending.request.clone()
            })
            .collect()
    }

    /// A new stream: everything unacked goes out again.
    pub fn unsend_all(&self) {
        for pending in &mut self.lock().outbox {
            pending.sent_at = None;
        }
    }

    /// The control plane acked a message; returns what it was, if it was one of ours.
    pub fn acked(&self, message_id: &str) -> Option<Kind> {
        let mut inner = self.lock();
        let index = inner.outbox.iter().position(|pending| pending.request.message_id == message_id)?;
        let kind = inner.outbox.remove(index).kind;
        if kind == Kind::Leave {
            inner.leaving = Leaving::Done;
        }
        Some(kind)
    }

    /// Whether a message of this kind is still waiting for its ack.
    #[must_use]
    pub fn waiting(&self, kind: &Kind) -> bool {
        self.lock().outbox.iter().any(|pending| &pending.kind == kind)
    }

    /// The stream ended because the host was removed or refused: after a `Leave`, that's the goodbye answered.
    pub fn refused(&self) {
        let mut inner = self.lock();
        if inner.leaving == Leaving::Asked {
            inner.leaving = Leaving::Done;
            inner.outbox.retain(|pending| pending.kind != Kind::Leave);
            inner.link = LinkState::Removed;
            self.publish(&inner);
        }
    }

    /// Where an uninstall's goodbye stands.
    #[must_use]
    pub fn leaving(&self) -> Leaving {
        self.lock().leaving
    }

    /// An offer was answered; an accepted one keeps an update from installing until its session has had time to start.
    pub fn offer_answered(&self, accepted: bool) {
        if accepted {
            self.lock().accepted_at = Some(Instant::now());
        }
    }

    /// Whether an offer was accepted within `within`.
    #[must_use]
    pub fn accepted_within(&self, within: Duration) -> bool {
        self.lock().accepted_at.is_some_and(|at| at.elapsed() < within)
    }

    /// Asks the control plane for a speed-check grant; the receiver gets it when it comes.
    pub fn request_speed_grant(&self) -> tokio::sync::oneshot::Receiver<agent_proto::v1::SpeedTestGrant> {
        let (sender, receiver) = tokio::sync::oneshot::channel();
        self.lock().speed = Some(sender);
        self.queue(AgentBody::SpeedTestRequest(agent_proto::v1::SpeedTestRequest {}), Kind::SpeedTest);
        receiver
    }

    /// The control plane's speed-check grant arrived.
    pub fn speed_grant(&self, grant: agent_proto::v1::SpeedTestGrant) {
        let mut inner = self.lock();
        inner.outbox.retain(|pending| pending.kind != Kind::SpeedTest);
        if let Some(waiting) = inner.speed.take() {
            let _ = waiting.send(grant);
        }
    }

    /// Where the host stands with the control plane.
    #[must_use]
    pub fn link_state(&self) -> LinkState {
        self.lock().link
    }

    /// The owner is at the machine, as of the last step.
    #[must_use]
    pub fn owner_active(&self) -> bool {
        self.lock().availability.owner_active()
    }
}

/// Steps availability every `every`, publishing changes, and stops running jobs after their checkpoint when the owner
/// comes back. Runs for the life of the agent, stream or no stream.
pub async fn watch(host: Arc<Host>, sessions: crate::supervisor::Shared, every: Duration) {
    let mut ticks = tokio::time::interval(every);
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticks.tick().await;
        let running = !sessions.lock().running().is_empty();
        let (_, came_back) = host.step(running);
        if came_back && running {
            let stopping = sessions.lock().owner_stop(crate::supervisor::OWNER_STOP_GRACE);
            if stopping > 0 {
                tracing::info!(stopping, "the owner is using the machine: renters' jobs save their progress and stop");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::owner::Sample;

    struct Quiet;

    impl MachineInputs for Quiet {
        fn sample(&self) -> Sample {
            Sample::QUIET
        }
    }

    fn host() -> (Arc<Host>, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        (Host::new(Arc::new(Quiet), dir.path().to_owned(), false), dir)
    }

    fn pause_body() -> AgentBody {
        AgentBody::SetPause(agent_proto::v1::SetPause { until: None, resume: false })
    }

    #[test]
    fn host_status_sets_the_link_the_pause_and_the_earnings() {
        let (host, _dir) = host();
        assert_eq!(host.status().link_state, LinkState::Unknown);
        host.on_host_status(&HostStatus {
            state: HostState::Paused.into(),
            paused_until: None,
            earned_today_paise: 1240,
            earning_now_paise: None,
        });
        let status = host.status();
        assert_eq!(status.link_state, LinkState::Active);
        assert_eq!(status.state, channel::State::HostingOff);
        assert_eq!(status.earned_today_paise, Some(1240));
        assert!(!host.owner_active(), "the owner's pause isn't the owner being at the machine");

        host.on_host_status(&HostStatus {
            state: HostState::Paused.into(),
            paused_until: Some(prost_types::Timestamp { seconds: 4_102_444_800, nanos: 0 }),
            earned_today_paise: 1240,
            earning_now_paise: None,
        });
        assert_eq!(host.status().state, channel::State::Paused);
        assert_eq!(host.status().paused_until.unwrap().unix_timestamp(), 4_102_444_800);

        host.on_host_status(&HostStatus { state: HostState::PendingApproval.into(), ..HostStatus::default() });
        assert_eq!(host.status().link_state, LinkState::PendingApproval);
        assert_eq!(host.status().state, channel::State::Available);
    }

    #[test]
    fn the_outbox_keeps_the_latest_pause_until_acked_and_resends_on_a_new_stream() {
        let (host, _dir) = host();
        host.queue(pause_body(), Kind::SetPause);
        let latest = host.queue(pause_body(), Kind::SetPause);
        let now = Instant::now();
        let sent = host.due(now, Duration::from_secs(5));
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].message_id, latest);
        assert_eq!(host.due(now, Duration::from_secs(5)), Vec::new());
        host.unsend_all();
        assert_eq!(host.due(now, Duration::from_secs(5)).len(), 1);
        assert_eq!(host.acked(&latest), Some(Kind::SetPause));
        assert_eq!(host.acked(&latest), None);
        assert_eq!(host.due(now, Duration::ZERO), Vec::new());
    }

    #[test]
    fn leaving_is_answered_by_an_ack_or_by_the_host_being_removed() {
        let (host, _dir) = host();
        let leave = AgentBody::Leave(agent_proto::v1::Leave {});
        let id = host.queue(leave.clone(), Kind::Leave);
        assert_eq!(host.leaving(), Leaving::Asked);
        host.acked(&id);
        assert_eq!(host.leaving(), Leaving::Done);

        let (other, _dir) = self::host();
        other.queue(leave, Kind::Leave);
        other.refused();
        assert_eq!(other.leaving(), Leaving::Done);
        assert!(!other.waiting(&Kind::Leave));
        assert_eq!(other.status().link_state, LinkState::Removed);
    }

    #[test]
    fn auto_pause_settings_are_saved_and_pushed() {
        let (host, dir) = host();
        let mut changes = host.subscribe();
        let settings = AutoPause { unplugged: false, start_again_after_minutes: 20, ..AutoPause::default() };
        host.set_auto_pause(settings).unwrap();
        assert!(changes.has_changed().unwrap());
        assert_eq!(changes.borrow_and_update().auto_pause, settings);
        assert_eq!(settings::load(dir.path()), settings);
        assert!(host.set_auto_pause(AutoPause { start_again_after_minutes: 3, ..settings }).is_err());
    }

    #[test]
    fn ignored_gpu_use_never_pauses() {
        let dir = tempfile::tempdir().unwrap();
        let host = Host::new(Arc::new(Quiet), dir.path().to_path_buf(), true);
        host.ignore_gpu_use();
        host.set_gpu_util(Some(100));
        assert_eq!(host.lock().gpu_util, None);
    }
}
