//! The agent's side of the Windows app (ADR 0033): one place that knows where the machine stands, for the stream,
//! the tray and the launcher.
//!
//! - **Availability**: auto-pause's state machine ([`crate::availability`]), stepped with a fresh sample whenever a
//!   heartbeat or an offer needs `owner_active`, and every second by [`watch`], which also stops a running job after
//!   its checkpoint when the owner comes back.
//! - **What the control plane said**: the latest `HostStatus` (the host's state, the owner's pause, the earnings the IdleO
//!   window shows, kept on disk so they can still be shown, with their age, while offline).
//! - **What the host chose** ([`Host::set_settings`]): stored in `settings.toml` and applied at once.
//! - **The tray's status**: pushed to every local-channel client on every change ([`Host::subscribe`]).
//! - **The outbox**: `SetPause`, `Leave`, `UpdateResult` and `SelfTestReport`, kept until the control plane acks them
//!   and sent again on every new stream, like the supervisor's statuses.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body as AgentBody;
use agent_proto::v1::{HostState, HostStatus, OpenStreamRequest};
use idleo_release::channel::{
    self, AutoPause, Earnings, LinkState, Live, Prefetch, Problem, RecentJob, Status, UpdatePhase,
};
use idleo_release::settings::HostSettings;
use time::OffsetDateTime;
use tokio::sync::{Notify, broadcast, watch};
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

/// Free disk below which the host is told the laptop's disk is nearly full.
pub const DISK_NEARLY_FULL_MB: i32 = 10 * 1024;
/// The share of the disk limit at which old job images are cleaned up.
pub const DISK_PRESSURE_PERCENT: u32 = 80;
/// How long the GPU must stay over the host's limit before it is a problem for the host rather than a pause.
pub const HOT_PROBLEM_AFTER: Duration = Duration::from_secs(120);
/// How long the agent may be unreachable while it should be available before the host is told.
pub const OFFLINE_PROBLEM_AFTER: Duration = Duration::from_hours(1);
/// How long after an update a version is shown as "Updated to".
pub const UPDATED_FOR: Duration = Duration::from_hours(7 * 24);

/// What is the machine's own, which the hub is given before it is shared.
pub struct Machine {
    /// Where the earnings are kept for the window while IdleO can't be reached.
    pub earnings_dir: PathBuf,
    /// What turns "Start with Windows" on and off.
    pub autostart: Arc<dyn Autostart>,
    /// The version an update installed lately, for "Updated to".
    pub updated_to: Option<String>,
    /// The public website, where Help goes.
    pub site_url: Option<String>,
}

/// What "Start with Windows" turns on and off: the Run entry. The real one writes the registry; tests count.
pub trait Autostart: Send + Sync {
    /// Starts IdleO when Windows starts (or stops it).
    ///
    /// # Errors
    /// If it can't be changed, in plain words.
    fn set(&self, on: bool) -> Result<(), String>;
}

/// Where there is no Windows: nothing to start.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoAutostart;

impl Autostart for NoAutostart {
    fn set(&self, _on: bool) -> Result<(), String> {
        Ok(())
    }
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

#[allow(clippy::struct_excessive_bools)] // Facts about the machine, each its own, not mode flags.
struct Inner {
    availability: Availability,
    link: LinkState,
    reachable: bool,
    earned_today: Option<i64>,
    earning_now: Option<i64>,
    session_running: bool,
    gpu_util: Option<u32>,
    /// The busiest GPU's latest temperature, for the host's heat limit and the window.
    gpu_temp: Option<u32>,
    /// Whether the GPU's use and heat count for auto-pause: real GPUs on Windows only (see [`Host::ignore_gpu_use`]).
    gpu_watched: bool,
    /// When the running job began running.
    job_started_at: Option<OffsetDateTime>,
    /// The earnings IdleO last said, and when.
    earnings: Option<Earnings>,
    /// When IdleO was last heard from.
    server_seen_at: Option<OffsetDateTime>,
    /// Since when IdleO hasn't been reachable, while it hasn't.
    offline_since: Option<OffsetDateTime>,
    /// Since when the GPU has been over the host's limit.
    hot_since: Option<OffsetDateTime>,
    /// The NVIDIA driver is new enough for the job runner.
    driver_ok: bool,
    /// The free space on the laptop's disk, in MB, when it could be read.
    disk_free_mb: Option<i32>,
    /// The Linux space has used the disk the host allowed it.
    disk_full: bool,
    /// How much of the host's disk limit the Linux space uses, in percent.
    disk_used_percent: Option<u32>,
    /// The version an update installed lately, for "Updated to".
    updated_to: Option<String>,
    update: UpdatePhase,
    outbox: Vec<Pending>,
    leaving: Leaving,
    /// When an offer was last accepted, for "not mid-offer".
    accepted_at: Option<Instant>,
    /// The job images the control plane said are worth having (its latest Welcome), with the GPU memory each needs.
    prefetch_images: Vec<(String, i32)>,
    /// Where downloading them stands, for the tray.
    prefetch: Option<Prefetch>,
    /// The digests (`sha256:...`) of the job images already on the machine, which every heartbeat tells the scheduler.
    cached_images: Vec<String>,
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
    /// Whether the job runner answers with the sandbox's rules loaded: set when it first does ([`Host::set_runner_installed`]).
    runner_installed: std::sync::atomic::AtomicBool,
    app_url: Option<String>,
    /// The host site (ADR 0058), where Open dashboard goes.
    hosts_site: Option<String>,
    /// Where the earnings are kept for the window while IdleO can't be reached.
    earnings_dir: PathBuf,
    /// The public website, where Help goes.
    site: Option<String>,
    /// Somebody asked to show the IdleO window: the local channel hands it to the tray.
    window: broadcast::Sender<Option<String>>,
    /// Turns "Start with Windows" on and off.
    autostart: Arc<dyn Autostart>,
    /// Counts the times the laptop woke from sleep or hibernation, so the connection (dead by then) is made again at once.
    resumes: watch::Sender<u64>,
    /// The owner chose Quit IdleO: the agent shuts down and exits with [`channel::QUIT_BY_OWNER_EXIT_CODE`].
    quit: watch::Sender<bool>,
}

impl Host {
    /// A hub for the agent whose settings live in `data_dir`; `runner_installed` says whether it can run jobs.
    #[must_use]
    pub fn new(inputs: Arc<dyn MachineInputs>, data_dir: PathBuf, runner_installed: bool) -> Arc<Self> {
        Self::with_web_urls(inputs, data_dir, runner_installed, None, None)
    }

    /// The same, telling the tray where the host site is (ADR 0058), or on an older install the renter app.
    #[must_use]
    pub fn with_web_urls(
        inputs: Arc<dyn MachineInputs>,
        data_dir: PathBuf,
        runner_installed: bool,
        app_url: Option<String>,
        host_url: Option<String>,
    ) -> Arc<Self> {
        let host_settings = settings::load(&data_dir);
        let earnings_dir = data_dir.join("earnings");
        let earnings = load_earnings(&earnings_dir);
        let inner = Inner {
            availability: Availability::new(host_settings),
            link: LinkState::Unknown,
            reachable: false,
            earned_today: None,
            earning_now: None,
            session_running: false,
            gpu_util: None,
            gpu_temp: None,
            gpu_watched: true,
            job_started_at: None,
            earnings,
            server_seen_at: None,
            offline_since: Some(OffsetDateTime::now_utc()),
            hot_since: None,
            driver_ok: true,
            disk_free_mb: None,
            disk_full: false,
            disk_used_percent: None,
            updated_to: None,
            update: UpdatePhase::Idle,
            outbox: Vec::new(),
            leaving: Leaving::No,
            accepted_at: None,
            speed: None,
            prefetch_images: Vec::new(),
            prefetch: None,
            cached_images: Vec::new(),
        };
        let (status, _) = watch::channel(Self::status_of(
            &inner,
            runner_installed,
            [&app_url, &host_url, &None],
            OffsetDateTime::now_utc(),
        ));
        Arc::new(Self {
            inner: Mutex::new(inner),
            status,
            changed: Notify::new(),
            inputs,
            data_dir,
            started: Instant::now(),
            runner_installed: std::sync::atomic::AtomicBool::new(runner_installed),
            app_url,
            hosts_site: host_url,
            earnings_dir,
            site: None,
            window: broadcast::channel(8).0,
            autostart: Arc::new(NoAutostart),
            resumes: watch::channel(0).0,
            quit: watch::channel(false).0,
        })
    }

    /// The same hub with the machine's own things set (before it is shared).
    ///
    /// # Panics
    /// If the hub is already shared: it is set up before anything else holds it.
    #[must_use]
    pub fn with_machine(mut self: Arc<Self>, machine: Machine) -> Arc<Self> {
        let this = Arc::get_mut(&mut self).expect("the hub isn't shared yet");
        {
            let mut inner = this.inner.lock().unwrap_or_else(PoisonError::into_inner);
            inner.earnings = load_earnings(&machine.earnings_dir);
            inner.updated_to = machine.updated_to;
        }
        this.earnings_dir = machine.earnings_dir;
        this.autostart = machine.autostart;
        this.site = machine.site_url;
        {
            let inner = this.inner.lock().unwrap_or_else(PoisonError::into_inner);
            this.publish(&inner);
        }
        self
    }

    /// The job images the control plane's Welcome said are worth having, as (image, GPU memory in MB the least demanding
    /// template needs).
    pub fn set_prefetch_images(&self, images: Vec<(String, i32)>) {
        self.lock().prefetch_images = images;
    }

    /// The images to consider downloading, as last told.
    #[must_use]
    pub fn prefetch_images(&self) -> Vec<(String, i32)> {
        self.lock().prefetch_images.clone()
    }

    /// The job images now on the machine, by the image references the control plane named: the heartbeat reports their
    /// digests, so a renter whose template uses one is matched with this host first.
    pub fn set_cached_images<'a>(&self, images: impl IntoIterator<Item = &'a String>) {
        let mut digests: Vec<String> =
            images.into_iter().filter_map(|image| image.split_once('@').map(|(_, digest)| digest.to_owned())).collect();
        digests.sort();
        digests.dedup();
        self.lock().cached_images = digests;
    }

    /// The digests of the job images on the machine.
    #[must_use]
    pub fn cached_images(&self) -> Vec<String> {
        self.lock().cached_images.clone()
    }

    /// Where downloading job images stands, for the tray.
    pub fn set_prefetch(&self, prefetch: Option<Prefetch>) {
        let mut inner = self.lock();
        inner.prefetch = prefetch;
        self.publish(&inner);
    }

    /// Whether the job runner is there and ready: the tray's "jobs start when the job runner arrives" line goes when it is.
    pub fn set_runner_installed(&self, installed: bool) {
        self.runner_installed.store(installed, std::sync::atomic::Ordering::SeqCst);
        let inner = self.lock();
        self.publish(&inner);
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
        let next = Self::status_of(
            inner,
            self.runner_installed.load(std::sync::atomic::Ordering::SeqCst),
            [&self.app_url, &self.hosts_site, &self.site],
            OffsetDateTime::now_utc(),
        );
        self.status.send_if_modified(|current| {
            if *current == next {
                false
            } else {
                *current = next;
                true
            }
        });
    }

    /// `web`: the renter app's address and the host site's, as the config has them.
    fn status_of(inner: &Inner, runner_installed: bool, web: [&Option<String>; 3], wall: OffsetDateTime) -> Status {
        let [app_url, host_url, site_url] = web;
        let (state, reason, paused_until) = match inner.availability.state() {
            State::Available => (channel::State::Available, None, None),
            State::AutoPaused { reason, .. } => (channel::State::AutoPaused, Some(reason), None),
            State::HostingOff => (channel::State::HostingOff, None, None),
            State::Paused { until } => (channel::State::Paused, None, Some(until)),
        };
        let settings = inner.availability.settings();
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
            auto_pause: settings.auto_pause,
            app_url: app_url.clone(),
            host_url: host_url.clone(),
            prefetch: inner.prefetch,
            site_url: site_url.clone(),
            settings,
            live: inner.session_running.then_some(Live {
                gpu_temp_c: inner.gpu_temp,
                gpu_util_pct: inner.gpu_util,
                job_started_at: inner.job_started_at,
            }),
            earnings: inner.earnings.clone(),
            problem: problem(inner, wall),
            server_seen_at: inner.server_seen_at,
            version: crate::AGENT_VERSION.to_owned(),
            updated_to: inner.updated_to.clone(),
        }
    }

    /// Samples the machine and steps availability. Returns whether the owner is active now, and whether this step
    /// moved into an auto-pause (the owner just came back).
    pub fn step(&self, session_running: bool) -> (bool, bool) {
        let sample = self.inputs.sample();
        let mut inner = self.lock();
        inner.session_running = session_running;
        let wall = OffsetDateTime::now_utc();
        if session_running && inner.job_started_at.is_none() {
            inner.job_started_at = Some(wall);
        } else if !session_running {
            inner.job_started_at = None;
        }
        let inputs = Inputs {
            idle: sample.idle,
            fullscreen: sample.fullscreen,
            on_battery: sample.on_battery,
            gpu_util_pct: inner.gpu_util,
            session_running,
            gpu_temp_c: inner.gpu_temp,
            metered: sample.metered,
            disk_full: inner.disk_full,
            utc_offset: time::UtcOffset::current_local_offset().unwrap_or(time::UtcOffset::UTC),
        };
        let (before, after) = inner.availability.step(self.started.elapsed(), wall, &inputs);
        inner.hot_since = match (inner.availability.too_hot(), inner.hot_since) {
            (true, since) => since.or(Some(wall)),
            (false, _) => None,
        };
        let came_back = matches!(after, State::AutoPaused { .. }) && !matches!(before, State::AutoPaused { .. });
        let active = inner.availability.owner_active();
        self.publish(&inner);
        (active, came_back)
    }

    /// The busiest GPU's latest use, for the GPU-busy rule.
    pub fn set_gpu_util(&self, util: Option<u32>) {
        self.set_gpu_reading(util, None);
    }

    /// The busiest GPU's latest use and the hottest one's temperature, for the GPU-busy rule, the host's heat limit and the
    /// window's live numbers.
    pub fn set_gpu_reading(&self, util: Option<u32>, temp_c: Option<u32>) {
        let mut inner = self.lock();
        if inner.gpu_watched {
            inner.gpu_util = util;
            inner.gpu_temp = temp_c;
        }
    }

    /// Never counts the GPU's use for auto-pause: on Linux and macOS, where nobody is watched (`crate::owner`), and
    /// with fake GPUs, whose use is invented and must never pause a host or keep it from being offered work.
    pub fn ignore_gpu_use(&self) {
        let mut inner = self.lock();
        inner.gpu_watched = false;
        inner.gpu_util = None;
        inner.gpu_temp = None;
    }

    /// Whether the control plane can be reached: true once welcomed, false when the stream ends.
    pub fn set_reachable(&self, reachable: bool) {
        let mut inner = self.lock();
        let now = OffsetDateTime::now_utc();
        if reachable {
            inner.server_seen_at = Some(now);
            inner.offline_since = None;
        } else if inner.reachable || inner.offline_since.is_none() {
            // The moment it went away (or the agent started and hasn't reached it yet).
            inner.offline_since = Some(now);
        }
        inner.reachable = reachable;
        self.publish(&inner);
    }

    /// Whether the NVIDIA driver is new enough for the job runner: the window tells the host when it isn't.
    pub fn set_driver_ok(&self, ok: bool) {
        let mut inner = self.lock();
        inner.driver_ok = ok;
        self.publish(&inner);
    }

    /// The laptop's free disk, and whether the Linux space has used all the disk the host allowed it.
    pub fn set_disk(&self, free_mb: Option<i32>, full: bool) {
        let mut inner = self.lock();
        inner.disk_free_mb = free_mb;
        inner.disk_full = full;
        self.publish(&inner);
    }

    /// How much of the disk limit the Linux space uses, in percent.
    pub fn set_disk_use(&self, percent: u32) {
        self.lock().disk_used_percent = Some(percent);
    }

    /// Whether the Linux space is close enough to its limit (80%) that old job images should go.
    #[must_use]
    pub fn disk_pressure(&self) -> bool {
        let inner = self.lock();
        inner.disk_full || inner.disk_used_percent.is_some_and(|percent| percent >= DISK_PRESSURE_PERCENT)
    }

    /// The version an update installed lately, for "Updated to".
    pub fn set_updated_to(&self, version: Option<String>) {
        let mut inner = self.lock();
        inner.updated_to = version;
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
        let now = OffsetDateTime::now_utc();
        inner.server_seen_at = Some(now);
        let earnings = Earnings {
            today_paise: status.earned_today_paise,
            week_paise: status.earned_week_paise,
            month_paise: status.earned_month_paise,
            recent: status
                .recent_jobs
                .iter()
                .filter_map(|job| {
                    Some(RecentJob {
                        ended_at: OffsetDateTime::from_unix_timestamp(job.ended_at.as_ref()?.seconds).ok()?,
                        seconds: u32::try_from(job.seconds).unwrap_or(0),
                        earned_paise: job.earned_paise,
                    })
                })
                .collect(),
            next_payout_on: status.next_payout_on.clone().filter(|date| valid_date(date)),
            last_payout: status.last_payout.as_ref().and_then(|payout| {
                Some(channel::PayoutNote {
                    sent_at: OffsetDateTime::from_unix_timestamp(payout.sent_at.as_ref()?.seconds).ok()?,
                    amount_paise: payout.amount_paise,
                })
            }),
            updated_at: now,
        };
        if inner.earnings.as_ref().is_none_or(|kept| kept.without_age() != earnings.without_age()) {
            save_earnings(&self.earnings_dir, &earnings);
        }
        inner.earnings = Some(earnings);
        drop(inner);
        let running = self.lock().session_running;
        self.step(running);
    }

    /// Changes what the host chose, saves it and applies it at once.
    ///
    /// # Errors
    /// If the settings aren't allowed, or can't be saved.
    pub fn set_settings(&self, settings: HostSettings) -> Result<(), String> {
        let settings = settings.normalized();
        settings::save(&self.data_dir, &settings)?;
        let (running, start_changed) = {
            let mut inner = self.lock();
            let before = inner.availability.settings();
            inner.availability.set_settings(settings);
            (inner.session_running, before.start_with_windows != settings.start_with_windows)
        };
        if start_changed && let Err(e) = self.autostart.set(settings.start_with_windows) {
            // The settings are kept either way: the file is what the window shows, and the next change tries again.
            tracing::warn!(error = %e, "Start with Windows couldn't be changed");
        }
        self.step(running);
        Ok(())
    }

    /// The settings as stored.
    #[must_use]
    pub fn settings(&self) -> HostSettings {
        self.lock().availability.settings()
    }

    /// Changes auto-pause the way an older tray did.
    ///
    /// # Errors
    /// If the settings aren't allowed or can't be saved.
    pub fn set_auto_pause(&self, settings: AutoPause) -> Result<(), String> {
        self.set_settings(self.settings().with_auto_pause(settings))
    }

    /// Asks every client of the local channel to show the IdleO window, on `tab` if one is named.
    pub fn open_window(&self, tab: Option<String>) {
        let _ = self.window.send(tab);
    }

    /// The laptop woke up: whoever holds a connection makes it again.
    pub fn note_resume(&self) {
        self.resumes.send_modify(|count| *count += 1);
    }

    /// The count of wake-ups, changing each time the laptop wakes.
    pub fn resumes(&self) -> watch::Receiver<u64> {
        self.resumes.subscribe()
    }

    /// The owner chose Quit IdleO. Whoever runs the agent shuts it down and exits with
    /// [`channel::QUIT_BY_OWNER_EXIT_CODE`].
    pub fn request_quit(&self) {
        let _ = self.quit.send(true);
    }

    /// Whether Quit IdleO has been chosen, and when it is.
    pub fn quit_requests(&self) -> watch::Receiver<bool> {
        self.quit.subscribe()
    }

    /// Requests to show the window, as they are made.
    pub fn window_requests(&self) -> broadcast::Receiver<Option<String>> {
        self.window.subscribe()
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

/// Whether `date` is a `YYYY-MM-DD`: all the window needs to be able to say it.
fn valid_date(date: &str) -> bool {
    let bytes = date.as_bytes();
    bytes.len() == 10
        && bytes.iter().enumerate().all(|(i, b)| if matches!(i, 4 | 7) { *b == b'-' } else { b.is_ascii_digit() })
}

/// The problem the host should hear about, if there is one: the driver, the disk, the heat or a long time offline.
fn problem(inner: &Inner, wall: OffsetDateTime) -> Option<Problem> {
    if !inner.driver_ok {
        return Some(Problem::DriverTooOld);
    }
    if inner.disk_free_mb.is_some_and(|free| free < DISK_NEARLY_FULL_MB) {
        return Some(Problem::DiskNearlyFull);
    }
    let hot_long = inner.hot_since.is_some_and(|since| wall - since >= HOT_PROBLEM_AFTER);
    if hot_long {
        return Some(Problem::TooHot);
    }
    // Offline while it should be available: the owner's own pause and an auto-pause aren't a reason to worry.
    let should_be_up = matches!(inner.availability.state(), State::Available) && inner.link == LinkState::Active;
    let offline_long = inner.offline_since.is_some_and(|since| wall - since >= OFFLINE_PROBLEM_AFTER);
    (should_be_up && !inner.reachable && offline_long).then_some(Problem::OfflineTooLong)
}

/// The earnings kept from last time, if there are any.
fn load_earnings(dir: &std::path::Path) -> Option<Earnings> {
    serde_json::from_slice(&std::fs::read(dir.join("history.json")).ok()?).ok()
}

/// Keeps the earnings for the window while IdleO can't be reached, written whole and renamed into place. Best effort: the
/// window shows what it has.
fn save_earnings(dir: &std::path::Path, earnings: &Earnings) {
    let write = || -> std::io::Result<()> {
        std::fs::create_dir_all(dir)?;
        let temporary = dir.join("history.json.tmp");
        std::fs::write(&temporary, serde_json::to_vec_pretty(earnings).map_err(std::io::Error::other)?)?;
        std::fs::rename(temporary, dir.join("history.json"))
    };
    if let Err(e) = write() {
        tracing::debug!(error = %e, "the earnings couldn't be kept");
    }
}

/// How much longer than its interval a tick of [`watch`] must take for the laptop to have been asleep.
pub const WAKE_GAP: Duration = Duration::from_secs(15);

/// Whether `elapsed` on the wall clock since the last tick, when `every` was expected, means the machine slept.
#[must_use]
pub fn slept(elapsed: Duration, every: Duration) -> bool {
    elapsed > every + WAKE_GAP
}

/// Steps availability every `every`, publishing changes, and stops running jobs after their checkpoint when the owner
/// comes back. Runs for the life of the agent, stream or no stream.
pub async fn watch(host: Arc<Host>, sessions: crate::supervisor::Shared, every: Duration) {
    let mut ticks = tokio::time::interval(every);
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last = std::time::SystemTime::now();
    loop {
        ticks.tick().await;
        let now = std::time::SystemTime::now();
        if slept(now.duration_since(last).unwrap_or_default(), every) {
            tracing::info!("the laptop woke up: connecting again");
            host.note_resume();
        }
        last = now;
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

/// Hosts for the tests of what's built on one.
#[cfg(test)]
#[allow(clippy::missing_panics_doc, clippy::must_use_candidate)] // Test doubles.
pub mod testing {
    use super::*;
    use crate::owner::Sample;

    struct Quiet;

    impl MachineInputs for Quiet {
        fn sample(&self) -> Sample {
            Sample::QUIET
        }
    }

    /// A host that's linked, listed, reachable, available and has its job runner: ready for work.
    pub fn ready_host() -> Arc<Host> {
        let dir = Box::leak(Box::new(tempfile::tempdir().unwrap()));
        let host = Host::new(Arc::new(Quiet), dir.path().to_owned(), true);
        host.set_reachable(true);
        set_available(&host, true);
        host
    }

    /// The owner resumes hosting, or pauses it.
    pub fn set_available(host: &Host, available: bool) {
        host.on_host_status(&HostStatus {
            state: if available { HostState::Active } else { HostState::Paused }.into(),
            paused_until: None,
            earned_today_paise: 0,
            earning_now_paise: None,
            ..HostStatus::default()
        });
    }
}

#[cfg(test)]
#[allow(clippy::field_reassign_with_default, reason = "a test changes the one setting it is about")]
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
            ..HostStatus::default()
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
            ..HostStatus::default()
        });
        assert_eq!(host.status().state, channel::State::Paused);
        assert_eq!(host.status().paused_until.unwrap().unix_timestamp(), 4_102_444_800);

        host.on_host_status(&HostStatus { state: HostState::PendingApproval.into(), ..HostStatus::default() });
        assert_eq!(host.status().link_state, LinkState::PendingApproval);
        assert_eq!(host.status().state, channel::State::Available);
    }

    #[test]
    fn the_heartbeat_names_cached_images_by_digest_only() {
        let (host, _dir) = host();
        assert_eq!(host.cached_images(), Vec::<String>::new(), "nothing is cached before anything is downloaded");

        let a = format!("registry.example/pytorch@sha256:{}", "ab".repeat(32));
        let b = format!("other.example/cuda@sha256:{}", "cd".repeat(32));
        let same_as_a_elsewhere = format!("mirror.example/pytorch@sha256:{}", "ab".repeat(32));
        host.set_cached_images(&[b.clone(), a.clone(), same_as_a_elsewhere, "not-pinned".to_owned()]);

        assert_eq!(
            host.cached_images(),
            [format!("sha256:{}", "ab".repeat(32)), format!("sha256:{}", "cd".repeat(32))]
        );
        host.set_cached_images(&[]);
        assert_eq!(host.cached_images(), Vec::<String>::new());
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
        assert_eq!(settings::load(dir.path()).auto_pause, settings);
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

    use idleo_release::channel::{Problem, Reason};
    use idleo_release::settings::ScheduleMode;

    fn quiet_host_in(dir: &std::path::Path) -> Arc<Host> {
        let host = Host::new(Arc::new(Quiet), dir.to_owned(), true);
        host.set_reachable(true);
        host.on_host_status(&HostStatus { state: HostState::Active.into(), ..HostStatus::default() });
        host
    }

    #[test]
    fn the_windows_earnings_come_from_the_control_plane_and_are_kept_for_when_it_cant_be_reached() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        let at = |seconds| Some(prost_types::Timestamp { seconds, nanos: 0 });
        host.on_host_status(&HostStatus {
            state: HostState::Active.into(),
            earned_today_paise: 1240,
            earned_week_paise: Some(9_900),
            earned_month_paise: Some(41_000),
            recent_jobs: vec![
                agent_proto::v1::RecentJob { ended_at: at(1_760_000_000), seconds: 1_380, earned_paise: 420 },
                agent_proto::v1::RecentJob { ended_at: None, seconds: 5, earned_paise: 1 },
            ],
            next_payout_on: Some("2026-11-23".to_owned()),
            last_payout: Some(agent_proto::v1::PayoutSent { sent_at: at(1_760_100_000), amount_paise: 12_500 }),
            ..HostStatus::default()
        });
        let earnings = host.status().earnings.unwrap();
        assert_eq!(
            (earnings.today_paise, earnings.week_paise, earnings.month_paise),
            (1240, Some(9_900), Some(41_000))
        );
        assert_eq!(earnings.recent.len(), 1, "a job with no end time is dropped, not guessed");
        assert_eq!((earnings.recent[0].seconds, earnings.recent[0].earned_paise), (1_380, 420));
        assert_eq!(earnings.next_payout_on.as_deref(), Some("2026-11-23"));
        assert_eq!(earnings.last_payout.unwrap().amount_paise, 12_500);

        // The agent restarts with no connection: the window still has the last figures, with the time they were heard.
        let restarted = Host::new(Arc::new(Quiet), dir.path().to_owned(), true);
        let kept = restarted.status().earnings.unwrap();
        assert_eq!(kept.week_paise, Some(9_900));
        assert_eq!(kept.updated_at, earnings.updated_at);
        assert!(!restarted.status().server_reachable);
    }

    #[test]
    fn a_control_plane_that_says_nothing_of_the_week_leaves_it_unknown_and_a_bad_date_is_dropped() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        host.on_host_status(&HostStatus {
            state: HostState::Active.into(),
            earned_today_paise: 5,
            next_payout_on: Some("next monday".to_owned()),
            ..HostStatus::default()
        });
        let earnings = host.status().earnings.unwrap();
        assert_eq!((earnings.week_paise, earnings.month_paise, earnings.next_payout_on), (None, None, None));
    }

    #[test]
    fn the_window_sees_the_gpu_only_while_a_job_runs() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        host.set_gpu_reading(Some(97), Some(71));
        assert!(host.status().live.is_none(), "nothing to show while idle");
        host.step(true);
        let live = host.status().live.unwrap();
        assert_eq!((live.gpu_temp_c, live.gpu_util_pct), (Some(71), Some(97)));
        assert!(live.job_started_at.is_some());
        host.step(false);
        assert!(host.status().live.is_none());
    }

    #[test]
    fn a_gpu_over_the_hosts_limit_pauses_with_the_reason_and_carries_on_cooler() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        host.set_gpu_reading(Some(0), Some(90));
        host.step(false);
        let status = host.status();
        assert_eq!((status.state, status.reason), (channel::State::AutoPaused, Some(Reason::TooHot)));
        host.set_gpu_reading(Some(0), Some(70));
        host.step(false);
        assert_eq!(host.status().state, channel::State::Available);
    }

    #[test]
    fn the_hosts_settings_are_stored_applied_and_start_with_windows_is_told() {
        struct Counting(Mutex<Vec<bool>>);
        impl Autostart for Counting {
            fn set(&self, on: bool) -> Result<(), String> {
                self.0.lock().unwrap().push(on);
                Ok(())
            }
        }
        let dir = tempfile::tempdir().unwrap();
        let counting = Arc::new(Counting(Mutex::new(Vec::new())));
        let host = Host::new(Arc::new(Quiet), dir.path().to_owned(), true).with_machine(Machine {
            earnings_dir: dir.path().join("earnings"),
            autostart: Arc::clone(&counting) as Arc<dyn Autostart>,
            updated_to: None,
            site_url: Some("https://idleo.in".to_owned()),
        });
        assert_eq!(host.status().site_url.as_deref(), Some("https://idleo.in"));
        let mut settings = host.settings();
        settings.schedule.mode = ScheduleMode::Always;
        settings.limits.max_disk_gb = 80;
        host.set_settings(settings).unwrap();
        assert_eq!(*counting.0.lock().unwrap(), Vec::<bool>::new(), "start with Windows didn't change");
        settings.start_with_windows = false;
        host.set_settings(settings).unwrap();
        host.set_settings(settings).unwrap();
        assert_eq!(*counting.0.lock().unwrap(), [false], "told once, when it changed");
        assert_eq!(settings::load(dir.path()).limits.max_disk_gb, 80);
        assert_eq!(Host::new(Arc::new(Quiet), dir.path().to_owned(), true).settings(), host.settings().normalized());
    }

    #[test]
    fn the_host_is_told_of_the_problems_that_are_theirs_to_fix_in_order() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        assert_eq!(host.status().problem, None);
        host.set_disk(Some(5_000), false);
        assert_eq!(host.status().problem, Some(Problem::DiskNearlyFull));
        host.set_driver_ok(false);
        assert_eq!(host.status().problem, Some(Problem::DriverTooOld), "the driver comes first");
        host.set_driver_ok(true);
        host.set_disk(Some(50_000), false);
        assert_eq!(host.status().problem, None);
    }

    #[test]
    fn offline_for_an_hour_while_it_should_be_up_is_a_problem_and_a_pause_is_not() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        host.set_reachable(false);
        {
            let mut inner = host.lock();
            inner.offline_since = Some(OffsetDateTime::now_utc() - Duration::from_mins(61));
            host.publish(&inner);
        }
        assert_eq!(host.status().problem, Some(Problem::OfflineTooLong));
        // The owner's own pause: nothing to worry about.
        host.on_host_status(&HostStatus { state: HostState::Paused.into(), ..HostStatus::default() });
        assert_eq!(host.status().problem, None);
    }

    #[test]
    fn an_update_is_shown_as_updated_to_and_reaches_the_status() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        assert_eq!(host.status().updated_to, None);
        host.set_updated_to(Some("0.2.0".to_owned()));
        assert_eq!(host.status().updated_to.as_deref(), Some("0.2.0"));
        assert_eq!(host.status().version, crate::AGENT_VERSION);
    }

    #[tokio::test]
    async fn quit_and_open_window_are_signalled_to_whoever_listens() {
        let dir = tempfile::tempdir().unwrap();
        let host = quiet_host_in(dir.path());
        let mut windows = host.window_requests();
        host.open_window(Some("now".to_owned()));
        assert_eq!(windows.recv().await.unwrap().as_deref(), Some("now"));
        let mut quit = host.quit_requests();
        assert!(!*quit.borrow());
        host.request_quit();
        quit.wait_for(|quit| *quit).await.unwrap();
    }

    #[test]
    fn a_long_gap_between_ticks_means_the_laptop_slept_and_a_wake_is_signalled() {
        assert!(!slept(Duration::from_secs(1), Duration::from_secs(1)));
        assert!(!slept(Duration::from_secs(10), Duration::from_secs(1)), "a busy moment is not sleep");
        assert!(slept(Duration::from_secs(3_600), Duration::from_secs(1)));
        let (host, _dir) = host();
        let mut woke = host.resumes();
        assert!(!woke.has_changed().unwrap());
        host.note_resume();
        assert!(woke.has_changed().unwrap());
        assert_eq!(*woke.borrow_and_update(), 1);
    }
}
