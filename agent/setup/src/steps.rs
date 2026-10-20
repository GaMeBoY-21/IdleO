//! The setup steps (ADR 0033), in order, each idempotent, with progress saved to `IdleO\setup\state.json` so a re-run
//! or the restart WSL may need picks up where it stopped.
//!
//! 1. `checks`: the release (its signature checked), Windows 10 22H2 or later, an NVIDIA RTX GPU with 8 GB, a driver
//!    at or above `MIN_NVIDIA_DRIVER`, virtualization on, 40 GB free plus the 20 GB a host keeps, and plugged in
//!    (a warning only). Each problem gets its one screen and one button; most check again every 10 seconds and carry
//!    on by themselves.
//! 2. `wsl`: WSL ready, updated, or turned on, with the one restart it may need.
//! 3. `download`: the app zip and the distro tarball, resumed, checked against the signed manifest.
//! 4. `install`: the programs, `current`, the Run entry, the shortcut, the Uninstall entry and `agent.toml`.
//! 5. `distro`: our distribution imported.
//! 6. `self_test`: the installed agent's self-test.
//! 7. `link`: approval in the browser (click 3).
//! 8. `start`: the launcher, then the host's first status.
//!
//! Every step's start and end, and every click, are measured (host experience §6 row 11); when a step or a self-test
//! check fails, the words go with it (`detail`, scrubbed of the person and the machine), and the whole run is written to
//! the setup log. A development build can keep a failed install for troubleshooting instead of undoing it.

use std::path::PathBuf;
use std::time::Duration;

use idleo_agent::link::{LinkError, Progress};
use idleo_release::apps::Apps;
use idleo_release::channel::LinkState;
use idleo_release::download::DownloadError;
use idleo_release::install::{self, RUN_KEY, RUN_VALUE, RUNONCE_KEY, RUNONCE_VALUE};
use idleo_release::manifest::{self, Manifest};
use idleo_release::paths;
use idleo_release::registry::Value;
use idleo_release::wsl::{Wsl, WslState};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio::time::Instant;
use uuid::Uuid;

use crate::ipc::{FromPage, Problem, StepView, ToPage};
use crate::log::{LoggingRunner, SetupLog};
use crate::reasons;
use crate::scrub::{self, Identity};
use crate::system::{FactsRequest, Gpu, InstallEvent, Latest, System};

/// The steps, in order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Step {
    /// The laptop and the release.
    Checks,
    /// WSL.
    Wsl,
    /// The release's files.
    Download,
    /// The programs and the registry.
    Install,
    /// Our distribution.
    Distro,
    /// The installed agent's self-test.
    SelfTest,
    /// Approval in the browser.
    Link,
    /// The launcher, and the host's first status.
    Start,
}

impl Step {
    /// Every step, in order.
    pub const ALL: [Self; 8] =
        [Self::Checks, Self::Wsl, Self::Download, Self::Install, Self::Distro, Self::SelfTest, Self::Link, Self::Start];

    /// Its name in measurements.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::Checks => "checks",
            Self::Wsl => "wsl",
            Self::Download => "download",
            Self::Install => "install",
            Self::Distro => "distro",
            Self::SelfTest => "self_test",
            Self::Link => "link",
            Self::Start => "start",
        }
    }

    /// Its plain words on the progress screen.
    #[must_use]
    pub fn label(self) -> &'static str {
        match self {
            Self::Checks => "Checking your laptop",
            Self::Wsl => "Turning on Windows' Linux support",
            Self::Download => "Downloading IdleO",
            Self::Install => "Installing IdleO",
            Self::Distro => "Setting up the safe space for renters' jobs",
            Self::SelfTest => "Testing the safe space",
            Self::Link => "Linking to your account",
            Self::Start => "Starting IdleO",
        }
    }
}

/// Where the app talks to, baked into the build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    /// The control plane's REST address.
    pub api_url: String,
    /// The web app.
    pub app_url: String,
    /// The public website, for the help pages.
    pub site_url: String,
    /// The agent endpoint's host.
    pub control_plane_host: String,
    /// The agent endpoint's port.
    pub control_plane_port: u16,
    /// Our root certificate, PEM.
    pub ca_root_pem: String,
    /// A development build's image registry, `host:port` (ADR 0035): written into the distribution so its runner can pull
    /// the development catalog's images over plain HTTP. Always `None` in a release build.
    pub registry: Option<String>,
}

/// Setup's saved progress.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedState {
    /// The install's id, for its measurements.
    pub install_id: Uuid,
    /// The steps done.
    pub completed: Vec<Step>,
    /// `setup_opened` has been sent.
    pub opened: bool,
}

/// How setup ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ending {
    /// Set up; the host is pending (the pilot) or live.
    Done {
        /// Waiting for IdleO's check.
        pending: bool,
    },
    /// Windows is restarting; setup carries on after.
    Restarting,
    /// The owner chose to restart later; setup carries on after the restart.
    Later,
    /// A development build was told to keep what was installed so far, for troubleshooting. Nothing is linked or
    /// started, and the Run entry is gone; running setup again offers Repair.
    Kept,
    /// Stopped: by the owner ("Stop setting up?"), or because the laptop can't host, or something didn't pass. Whatever
    /// was done is undone.
    Undo,
}

enum Stop {
    Ending(Ending),
}

impl From<Ending> for Stop {
    fn from(ending: Ending) -> Self {
        Self::Ending(ending)
    }
}

/// Free disk a host needs: 40 GB for the app, its Linux space and a job's files, plus the 20 GB a host keeps free.
pub const NEEDED_FREE_GB: u64 = 40 + 20;
/// GPU memory that counts as 8 GB (laptops report a little under).
pub const MIN_VRAM_MB: i32 = 7_900;
/// How often a fix screen checks again by itself.
pub const RECHECK_EVERY: Duration = Duration::from_secs(10);

/// The setup run.
pub struct Engine<'a, S: System> {
    system: &'a S,
    config: Config,
    key: Option<[u8; 32]>,
    page: &'a mut dyn FnMut(ToPage),
    events: &'a mut mpsc::UnboundedReceiver<FromPage>,
    state: SavedState,
    current: Option<Step>,
    failed: Option<Step>,
    detail: Option<String>,
    note: Option<String>,
    latest: Option<(Latest, Manifest)>,
    gpus: Vec<Gpu>,
    windows_build: Option<u32>,
    pending_events: Vec<InstallEvent>,
    /// Why the step under way failed, for its measurement.
    reason: Option<String>,
    /// Where the host stood when it was first listed.
    listed: Option<LinkState>,
    /// A development build: it offers to keep a failed install, and says where the log is.
    dev_build: bool,
    /// Whom the reports must not name.
    identity: Identity,
    /// This run's log.
    log: SetupLog,
    /// Why the step under way failed, in words as they were (scrubbed when sent).
    failure: Option<String>,
    /// The failure was reported already, check by check.
    reported: bool,
    /// What IdleO answered when it didn't take a batch, until the end.
    report_problem: Option<String>,
    /// Batches refused or unanswered in a row; after [`GIVE_UP_REPORTING`] setup stops trying.
    report_failures: u8,
}

/// Refused or unanswered batches in a row after which setup stops reporting, so a server that is down costs a few
/// seconds, never the install.
pub const GIVE_UP_REPORTING: u8 = 3;
/// Most bytes of events in one request; the control plane takes 16 KiB.
const MAX_BATCH_BYTES: usize = 12 * 1024;

/// How a run ended, for the window's last screen.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Summary {
    /// A step didn't pass.
    pub failed: bool,
    /// The log's file.
    pub log: std::path::PathBuf,
    /// What IdleO answered when it didn't take setup's measurements.
    pub report_problem: Option<String>,
}

impl<'a, S: System> Engine<'a, S> {
    /// A run against `system`, telling the page through `page` and hearing it through `events`. `key` is the release
    /// key the manifest must verify against.
    pub fn new(
        system: &'a S,
        config: Config,
        key: Option<[u8; 32]>,
        page: &'a mut dyn FnMut(ToPage),
        events: &'a mut mpsc::UnboundedReceiver<FromPage>,
    ) -> Self {
        let path = system.layout().setup_state();
        let state = std::fs::read_to_string(&path)
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or(SavedState { install_id: Uuid::new_v4(), completed: Vec::new(), opened: false });
        Self {
            system,
            config,
            key,
            page,
            events,
            state,
            current: None,
            failed: None,
            detail: None,
            note: None,
            latest: None,
            gpus: Vec::new(),
            windows_build: None,
            pending_events: Vec::new(),
            reason: None,
            listed: None,
            dev_build: false,
            identity: system.identity(),
            log: SetupLog::new(&system.layout().logs(), time::OffsetDateTime::now_utc()),
            failure: None,
            reported: false,
            report_problem: None,
            report_failures: 0,
        }
    }

    /// A development build: failed installs can be kept, and the last screen says where the log is.
    #[must_use]
    pub fn dev_build(mut self, dev_build: bool) -> Self {
        self.dev_build = dev_build;
        self
    }

    /// How the run went, for the last screen.
    #[must_use]
    pub fn summary(&self) -> Summary {
        Summary { failed: self.failed.is_some(), log: self.log.path(), report_problem: self.report_problem.clone() }
    }

    /// `wsl.exe`, every command of it written to the log.
    fn wsl_tool(&self) -> Wsl<LoggingRunner<'a>> {
        Wsl::new(LoggingRunner::new(self.system.wsl_runner(), self.log.clone()))
    }

    /// What a development build's screens say about measurements IdleO didn't take.
    fn dev_report_problem(&self) -> Option<String> {
        self.dev_build.then(|| self.report_problem.clone()).flatten()
    }

    /// The install's id.
    #[must_use]
    pub fn install_id(&self) -> Uuid {
        self.state.install_id
    }

    fn save(&self) {
        let path = self.system.layout().setup_state();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Ok(text) = serde_json::to_vec_pretty(&self.state) {
            let _ = std::fs::write(path, text);
        }
    }

    fn measure(&mut self, step: &str, outcome: &str, reason: Option<String>, took: Option<Duration>) {
        self.measure_detail(step, outcome, reason, None, took);
    }

    /// A measurement with the words behind it: scrubbed of the person and the machine, and at most 2,000 characters.
    fn measure_detail(
        &mut self,
        step: &str,
        outcome: &str,
        reason: Option<String>,
        detail: Option<&str>,
        took: Option<Duration>,
    ) {
        debug_assert!(reason.as_deref().is_none_or(reasons::is_valid), "{reason:?} is not a reason code");
        let reason = reason.map(|reason| if reasons::is_valid(&reason) { reason } else { reasons::code(&reason) });
        let detail = detail.map(|detail| scrub::detail(detail, &self.identity)).filter(|detail| !detail.is_empty());
        self.pending_events.push(InstallEvent {
            step: step.to_owned(),
            outcome: outcome.to_owned(),
            reason,
            duration_ms: took.map(|took| u64::try_from(took.as_millis()).unwrap_or(u64::MAX)),
            at: time::OffsetDateTime::now_utc(),
            setup_version: idleo_agent::AGENT_VERSION.to_owned(),
            windows_build: self.windows_build,
            gpu_model: self.gpus.first().map(|gpu| gpu.name.clone()),
            detail,
        });
    }

    /// Notes why the step under way is failing: a short code for the measurement, the words for its detail.
    fn fail(&mut self, reason: impl Into<String>, words: impl std::fmt::Display) {
        self.reason = Some(reason.into());
        self.failure = Some(words.to_string());
    }

    fn click(&mut self, which: &str) {
        self.measure("click", "passed", Some(which.to_owned()), None);
    }

    /// Sends what's measured, in requests of at most 20 events and 12 KiB, each tried once. A batch IdleO doesn't take
    /// is written to the log and remembered for the last screen; after a few in a row, setup stops trying.
    async fn flush(&mut self) {
        let mut events = std::mem::take(&mut self.pending_events).into_iter().peekable();
        while events.peek().is_some() {
            let mut batch = Vec::new();
            let mut bytes = 0;
            while let Some(event) = events.peek() {
                let size = serde_json::to_string(event).map_or(0, |text| text.len() + 1);
                if !batch.is_empty() && (batch.len() >= 20 || bytes + size > MAX_BATCH_BYTES) {
                    break;
                }
                bytes += size;
                batch.extend(events.next());
            }
            if self.report_failures >= GIVE_UP_REPORTING {
                self.log.line(&format!(
                    "not reported to IdleO: {} measurements, after {GIVE_UP_REPORTING} failures",
                    batch.len()
                ));
                continue;
            }
            match self.system.send_events(self.state.install_id, batch.clone()).await {
                Ok(()) => self.report_failures = 0,
                Err(rejected) => {
                    self.report_failures += 1;
                    self.log.line(&format!(
                        "IdleO didn't take {} measurements: {} {}",
                        batch.len(),
                        rejected.status,
                        crate::log::trimmed(&rejected.answer, 500)
                    ));
                    self.report_problem = Some(rejected.status);
                }
            }
        }
    }

    fn show(&mut self, message: ToPage) {
        (self.page)(message);
    }

    fn show_progress(&mut self) {
        let steps = Step::ALL
            .iter()
            .map(|step| {
                let state = if Some(*step) == self.failed {
                    "failed"
                } else if self.state.completed.contains(step) {
                    "done"
                } else if Some(*step) == self.current {
                    "current"
                } else {
                    "waiting"
                };
                StepView {
                    label: step.label().to_owned(),
                    state: state.to_owned(),
                    detail: (Some(*step) == self.current).then(|| self.detail.clone()).flatten(),
                }
            })
            .collect();
        let note = self.note.clone();
        self.show(ToPage::Progress { steps, note });
    }

    /// The next message from the page. "Yes, stop setting up" ends the run.
    async fn next_event(&mut self) -> Result<FromPage, Stop> {
        match self.events.recv().await {
            Some(FromPage::ConfirmClose) | None => Err(Ending::Undo.into()),
            Some(event) => Ok(event),
        }
    }

    /// Runs setup to its end. `resume` is set after the restart WSL needed.
    pub async fn run(&mut self, resume: bool) -> Ending {
        if !self.state.opened {
            self.state.opened = true;
            self.measure("setup_opened", "passed", None, None);
            self.save();
        }
        let ending = match self.steps(resume).await {
            Ok(ending) | Err(Stop::Ending(ending)) => ending,
        };
        if matches!(ending, Ending::Undo | Ending::Kept) {
            self.measure("setup_closed", "failed", self.failed.map(|step| step.name().to_owned()), None);
        }
        if ending == Ending::Kept {
            // Nothing starts by itself at sign-in while it is kept; Repair puts the entry back.
            let _ = self.system.registry().delete_value(RUN_KEY, RUN_VALUE);
        }
        self.log.line(&format!("setup ended: {ending:?}"));
        self.flush().await;
        ending
    }

    async fn steps(&mut self, resume: bool) -> Result<Ending, Stop> {
        let installed = install::is_installed(self.system);
        let linked = self.system.linked();
        let mut repair = false;
        // Stopped part-way and kept, or interrupted: some steps done, the last one never was.
        let unfinished = installed && !self.state.completed.is_empty() && !self.state.completed.contains(&Step::Start);
        let first = if resume && !self.state.completed.is_empty() {
            self.first_incomplete()
        } else if unfinished {
            self.show(ToPage::Welcome {
                already_set_up: false,
                dev_build: idleo_release::keys::DEV_BUILD,
                unfinished: true,
            });
            self.wait_for(&FromPage::SetUp).await?;
            self.click(reasons::CLICK_REPAIR);
            self.first_incomplete()
        } else if installed && linked {
            self.show(ToPage::Welcome {
                already_set_up: true,
                dev_build: idleo_release::keys::DEV_BUILD,
                unfinished: false,
            });
            self.wait_for(&FromPage::SetUp).await?;
            self.click(reasons::CLICK_REPAIR);
            repair = true;
            self.state.completed.clear();
            Step::Checks
        } else if installed {
            Step::Link
        } else {
            self.show(ToPage::Welcome {
                already_set_up: false,
                dev_build: idleo_release::keys::DEV_BUILD,
                unfinished: false,
            });
            self.wait_for(&FromPage::SetUp).await?;
            self.click(reasons::CLICK_SET_UP);
            self.first_incomplete()
        };
        // Repairing from the middle needs the release's manifest, which only the first step fetches.
        if matches!(first, Step::Install | Step::Distro) && self.latest.is_none() {
            self.fetch_release().await?;
        }
        let mut started = false;
        for step in Step::ALL {
            if step == first {
                started = true;
            }
            if !started || (repair && step == Step::Link) {
                continue;
            }
            self.run_step(step).await?;
        }
        self.show(ToPage::Done { pending: self.pending_listing(), report_problem: self.dev_report_problem() });
        Ok(Ending::Done { pending: self.pending_listing() })
    }

    fn first_incomplete(&self) -> Step {
        Step::ALL.into_iter().find(|step| !self.state.completed.contains(step)).unwrap_or(Step::Start)
    }

    fn pending_listing(&self) -> bool {
        self.listed != Some(LinkState::Active)
    }

    async fn wait_for(&mut self, wanted: &FromPage) -> Result<(), Stop> {
        loop {
            if &self.next_event().await? == wanted {
                return Ok(());
            }
        }
    }

    async fn run_step(&mut self, step: Step) -> Result<(), Stop> {
        self.current = Some(step);
        self.detail = None;
        self.reason = None;
        self.failure = None;
        self.reported = false;
        self.show_progress();
        self.measure(step.name(), "started", None, None);
        self.log.step_started(step.name());
        let started = Instant::now();
        let result = match step {
            Step::Checks => self.checks().await,
            Step::Wsl => self.wsl().await,
            Step::Download => self.download().await,
            Step::Install => self.install(),
            Step::Distro => self.distro().await,
            Step::SelfTest => self.self_test().await,
            Step::Link => self.link().await,
            Step::Start => self.start().await,
        };
        match &result {
            Ok(()) => {
                self.log.step_ended(step.name(), "passed", started.elapsed(), None);
                self.measure(step.name(), "passed", None, Some(started.elapsed()));
                if !self.state.completed.contains(&step) {
                    self.state.completed.push(step);
                }
                self.save();
            }
            Err(Stop::Ending(Ending::Restarting | Ending::Later)) => {
                self.log.step_ended(step.name(), "paused", started.elapsed(), None);
            }
            Err(Stop::Ending(_)) => {
                self.failed = Some(step);
                let reason = self.reason.take();
                let words = self.failure.take();
                let why = [reason.clone(), words.clone()].into_iter().flatten().collect::<Vec<_>>().join(": ");
                self.log.step_ended(
                    step.name(),
                    "failed",
                    started.elapsed(),
                    Some(&why).filter(|why| !why.is_empty()).map(String::as_str),
                );
                // A self-test reports each failed check on its own; the step's own line would say the same twice.
                if !std::mem::take(&mut self.reported) {
                    self.measure_detail(step.name(), "failed", reason, words.as_deref(), Some(started.elapsed()));
                }
            }
        }
        self.flush().await;
        result
    }
}

/// The checks, WSL and the download.
impl<S: System> Engine<'_, S> {
    async fn checks(&mut self) -> Result<(), Stop> {
        self.fetch_release().await?;
        let minimum = self.latest.as_ref().map_or(19_045, |(_, manifest)| manifest.min_windows_build);
        let build = self.system.windows_build().unwrap_or(0);
        self.windows_build = Some(build);
        if build < minimum {
            self.fail(reasons::windows_build(build), format!("Windows build {build}; this needs {minimum} or later"));
            let system = self.system;
            self.fix(Problem::WindowsTooOld, 0, Some("ms-settings:windowsupdate".to_owned()), move || {
                system.windows_build().unwrap_or(0) >= minimum
            })
            .await?;
        }
        self.gpus = rtx(&self.system.gpus().unwrap_or_default());
        if self.gpus.is_empty() {
            let seen = self.system.gpus().unwrap_or_default();
            let names = seen.iter().map(|gpu| format!("{} ({} MB)", gpu.name, gpu.vram_mb)).collect::<Vec<_>>();
            self.fail(
                reasons::NO_RTX_GPU,
                if names.is_empty() { "no NVIDIA GPU found".to_owned() } else { format!("found {}", names.join(", ")) },
            );
            self.show(ToPage::Fix {
                problem: Problem::NoRtxGpu,
                text: Problem::NoRtxGpu.text(0),
                button: Problem::NoRtxGpu.button().to_owned(),
                rechecking: false,
                keep: false,
            });
            self.wait_for(&FromPage::Fix).await?;
            self.click(&reasons::fix(problem_name(Problem::NoRtxGpu)));
            return Err(Ending::Undo.into());
        }
        if !self.gpus.iter().any(|gpu| idleo_release::drivers::is_supported(&gpu.driver)) {
            let driver = self.gpus[0].driver.clone();
            self.fail(reasons::driver_too_old(&driver), format!("NVIDIA driver {driver}; this needs a newer one"));
            let system = self.system;
            self.fix(
                Problem::DriverTooOld,
                0,
                Some("https://www.nvidia.com/en-us/software/nvidia-app/".to_owned()),
                move || {
                    rtx(&system.gpus().unwrap_or_default())
                        .iter()
                        .any(|gpu| idleo_release::drivers::is_supported(&gpu.driver))
                },
            )
            .await?;
            self.gpus = rtx(&self.system.gpus().unwrap_or_default());
        }
        if !self.system.virtualization_enabled() {
            self.fail(reasons::VIRTUALIZATION_OFF, "virtualization is off in the firmware");
            let help =
                format!("{}/help/virtualization/{}/", self.config.site_url.trim_end_matches('/'), self.system.maker());
            let system = self.system;
            self.fix(Problem::VirtualizationOff, 0, Some(help), move || system.virtualization_enabled()).await?;
        }
        let free_gb = self.system.free_disk_bytes().unwrap_or(0) / 1_000_000_000;
        if free_gb < NEEDED_FREE_GB {
            self.fail(reasons::disk(free_gb), format!("{free_gb} GB free; {NEEDED_FREE_GB} GB needed"));
            let system = self.system;
            self.fix(
                Problem::NotEnoughDisk,
                NEEDED_FREE_GB - free_gb,
                Some("ms-settings:storagesense".to_owned()),
                move || system.free_disk_bytes().unwrap_or(0) / 1_000_000_000 >= NEEDED_FREE_GB,
            )
            .await?;
        }
        if self.system.on_battery() {
            self.note = Some("Plug your laptop in: IdleO pauses while it runs on battery.".to_owned());
            self.measure("plugged_in", "failed", Some(reasons::ON_BATTERY.to_owned()), None);
        }
        Ok(())
    }

    /// The latest release, its manifest checked against the release key. IdleO unreachable: a screen with Try again.
    async fn fetch_release(&mut self) -> Result<(), Stop> {
        loop {
            match self.system.latest().await {
                Ok(latest) => {
                    let verified = match self.key {
                        Some(key) => manifest::verify(&latest.manifest, &latest.signature, &key),
                        None => Err(manifest::ManifestError::NoKey),
                    };
                    return match verified {
                        Ok(manifest) => {
                            self.latest = Some((latest, manifest));
                            Ok(())
                        }
                        Err(e) => {
                            self.fail(reasons::MANIFEST_INVALID, e);
                            self.something_didnt_pass().await
                        }
                    };
                }
                Err(e) => {
                    self.fail(reasons::UNREACHABLE, e);
                    self.cant_reach().await?;
                }
            }
        }
    }

    async fn cant_reach(&mut self) -> Result<(), Stop> {
        self.show(ToPage::Fix {
            problem: Problem::CantReachIdleo,
            text: Problem::CantReachIdleo.text(0),
            button: Problem::CantReachIdleo.button().to_owned(),
            rechecking: false,
            keep: false,
        });
        self.wait_for(&FromPage::Fix).await?;
        self.click(reasons::CLICK_TRY_AGAIN);
        self.show_progress();
        Ok(())
    }

    /// "Something didn't pass": the report reaches us, the laptop stays unlisted, and setup undoes what it did. A
    /// development build also offers to keep what's installed, so the failure can be looked at on the laptop.
    async fn something_didnt_pass(&mut self) -> Result<(), Stop> {
        let keep = self.dev_build;
        self.show(ToPage::Fix {
            problem: Problem::SomethingDidntPass,
            text: Problem::SomethingDidntPass.text(0),
            button: Problem::SomethingDidntPass.button().to_owned(),
            rechecking: false,
            keep,
        });
        self.flush().await;
        loop {
            match self.next_event().await? {
                FromPage::Fix => return Err(Ending::Undo.into()),
                // A release build never offers it, and ignores a page that asks anyway.
                FromPage::Keep if keep => {
                    self.click(reasons::CLICK_KEEP);
                    return Err(Ending::Kept.into());
                }
                _ => {}
            }
        }
    }

    /// A fix screen: its button opens `url`, and every 10 seconds it checks again, carrying on the moment it passes.
    async fn fix(
        &mut self,
        problem: Problem,
        free_up_gb: u64,
        url: Option<String>,
        recheck: impl Fn() -> bool,
    ) -> Result<(), Stop> {
        self.show(ToPage::Fix {
            problem,
            text: problem.text(free_up_gb),
            button: problem.button().to_owned(),
            rechecking: true,
            keep: false,
        });
        self.flush().await;
        loop {
            tokio::select! {
                event = self.events.recv() => match event {
                    Some(FromPage::Fix) => {
                        self.click(&reasons::fix(problem_name(problem)));
                        if let Some(url) = &url {
                            self.system.open_url(url);
                        }
                    }
                    Some(FromPage::ConfirmClose) | None => return Err(Ending::Undo.into()),
                    Some(_) => {}
                },
                () = tokio::time::sleep(RECHECK_EVERY) => {
                    if recheck() {
                        self.measure(Step::Checks.name(), "fixed", Some(reasons::fix(problem_name(problem))), None);
                        self.reason = None;
                        self.failure = None;
                        self.show_progress();
                        return Ok(());
                    }
                }
            }
        }
    }

    async fn wsl(&mut self) -> Result<(), Stop> {
        let wsl = self.wsl_tool();
        match wsl.state() {
            WslState::Ready(_) => Ok(()),
            WslState::TooOld(found) => {
                self.detail = Some("Updating WSL".to_owned());
                self.show_progress();
                wsl.update().map_err(|e| {
                    self.fail(reasons::WSL_UPDATE, format!("WSL {found}: {e}"));
                    Stop::from(Ending::Undo)
                })
            }
            WslState::Missing => {
                self.detail = Some("Windows will ask for permission".to_owned());
                self.show_progress();
                match wsl.install() {
                    Ok(false) => Ok(()),
                    Ok(true) => self.restart().await,
                    Err(e) => {
                        self.fail(reasons::WSL_INSTALL, e);
                        self.something_didnt_pass().await
                    }
                }
            }
            WslState::NeedsRestart => self.restart().await,
        }
    }

    /// Windows needs one restart to finish turning WSL on: a copy of setup and a RunOnce entry carry it on after.
    async fn restart(&mut self) -> Result<(), Stop> {
        let copy = self.system.layout().setup_dir().join(paths::exe(paths::SETUP_EXE));
        if let Err(e) = self.system.copy_self(&copy) {
            tracing::warn!(error = %e, "setup couldn't copy itself; the Downloads copy carries on after the restart");
        }
        let _ = self.system.registry().set(RUNONCE_KEY, RUNONCE_VALUE, &Value::Text(install::resume_command(&copy)));
        self.show(ToPage::Restart);
        self.flush().await;
        loop {
            match self.next_event().await? {
                FromPage::RestartNow => {
                    self.click(reasons::CLICK_RESTART_NOW);
                    self.flush().await;
                    if let Err(e) = self.system.restart_windows() {
                        tracing::warn!(error = %e, "Windows couldn't be restarted from setup");
                    }
                    return Err(Ending::Restarting.into());
                }
                FromPage::Later => return Err(Ending::Later.into()),
                _ => {}
            }
        }
    }

    async fn download(&mut self) -> Result<(), Stop> {
        if self.latest.is_none() {
            self.fetch_release().await?;
        }
        let Some((latest, manifest)) = self.latest.clone() else { return Err(Ending::Undo.into()) };
        let updates = self.system.layout().updates();
        let app = updates.join(file_name(&manifest.app.file));
        let tarball = updates.join(file_name(&manifest.distro.file));
        let total = manifest.app.size + manifest.distro.size;
        loop {
            let mut shown_mb = u64::MAX;
            let mut done_before = 0;
            let mut result = Ok(());
            for (url, dest, artifact) in [
                (latest.app_url.clone(), app.clone(), manifest.app.clone()),
                (latest.distro_url.clone(), tarball.clone(), manifest.distro.artifact()),
            ] {
                // The download reports its bytes through a channel; the page is told here, in MB.
                let (sender, mut received) = mpsc::unbounded_channel::<u64>();
                let mut progress = move |have: u64, _: u64| {
                    let _ = sender.send(have);
                };
                let downloading = self.system.download(&url, &dest, &artifact, &mut progress);
                tokio::pin!(downloading);
                let completed = self.state.completed.clone();
                let outcome = loop {
                    tokio::select! {
                        biased;
                        outcome = &mut downloading => break outcome,
                        Some(have) = received.recv() => {
                            let mb = (done_before + have) / 1_000_000;
                            if mb != shown_mb {
                                shown_mb = mb;
                                (self.page)(progress_screen(
                                    &completed,
                                    Step::Download,
                                    &format!("Downloading · {mb} of {} MB", total / 1_000_000),
                                ));
                            }
                        }
                    }
                };
                done_before += artifact.size;
                if let Err(e) = outcome {
                    result = Err(e);
                    break;
                }
            }
            match result {
                Ok(()) => return Ok(()),
                Err(DownloadError::Verify(e)) => {
                    let _ = std::fs::remove_dir_all(&updates);
                    self.fail(reasons::DOWNLOAD_VERIFY, e);
                    return self.something_didnt_pass().await;
                }
                Err(DownloadError::Download(e)) => {
                    self.fail(reasons::DOWNLOAD, e);
                    self.cant_reach().await?;
                }
            }
        }
    }
}

/// Installing, the distro, the self-test, the link and the start.
impl<S: System> Engine<'_, S> {
    fn install(&mut self) -> Result<(), Stop> {
        let Some((_, manifest)) = self.latest.clone() else { return Err(Ending::Undo.into()) };
        let layout = self.system.layout().clone();
        let apps = Apps::new(layout.clone());
        let zip = layout.updates().join(file_name(&manifest.app.file));
        let result = (|| -> Result<(), String> {
            std::fs::create_dir_all(layout.programs()).map_err(|e| e.to_string())?;
            let installed = apps.current().as_deref() == Some(manifest.version.as_str())
                && paths::PROGRAMS.iter().all(|program| apps.program(&manifest.version, program).exists());
            if !installed {
                apps.install_zip(&zip, &manifest.version).map_err(|e| e.to_string())?;
            }
            // The launcher may be running already (a repair): then it stays as it is.
            let launcher = apps.program(&manifest.version, paths::LAUNCHER_EXE);
            if !layout.launcher().exists() {
                std::fs::copy(&launcher, layout.launcher()).map_err(|e| e.to_string())?;
            }
            apps.set_current(&manifest.version).map_err(|e| e.to_string())?;
            let size_kb = folder_size(&layout.app_dir(&manifest.version)) / 1024;
            install::register(self.system, &manifest.version, u32::try_from(size_kb).unwrap_or(u32::MAX))
                .map_err(|e| e.to_string())?;
            self.write_agent_files().map_err(|e| e.to_string())?;
            let _ = std::fs::remove_file(&zip);
            Ok(())
        })();
        result.map_err(|e| {
            self.fail(reasons::INSTALL, e);
            Stop::from(Ending::Undo)
        })
    }

    /// `agent.toml` and the root it trusts.
    fn write_agent_files(&self) -> std::io::Result<()> {
        let dir = self.system.layout().agent_dir();
        std::fs::create_dir_all(&dir)?;
        std::fs::write(dir.join("ca-root.pem"), &self.config.ca_root_pem)?;
        let text = format!(
            "# Written by IdleO's setup. The agent's own settings are in settings.toml.\n\
             api_url = \"{}\"\napp_url = \"{}\"\nca_root = \"ca-root.pem\"\ndata_dir = \".\"\n\n[control_plane]\nhost = \"{}\"\nport = {}\n",
            self.config.api_url, self.config.app_url, self.config.control_plane_host, self.config.control_plane_port
        );
        std::fs::write(dir.join("agent.toml"), text)
    }

    async fn distro(&mut self) -> Result<(), Stop> {
        let Some((_, manifest)) = self.latest.clone() else { return Err(Ending::Undo.into()) };
        let layout = self.system.layout().clone();
        let name = manifest.distro_name();
        let wsl = self.wsl_tool();
        let tarball = layout.updates().join(file_name(&manifest.distro.file));
        let registered = wsl.ours().is_ok_and(|ours| ours.contains(&name));
        if !registered && let Err(e) = wsl.import(&name, &layout.wsl_dir(&manifest.distro.version), &tarball) {
            self.fail(reasons::DISTRO_IMPORT, e);
            return self.something_didnt_pass().await;
        }
        // A development build tells its runner where the development registry is; a release distribution carries no such file.
        if self.dev_build
            && let Some(registry) = self.config.registry.clone()
        {
            let script =
                format!("mkdir -p /etc/idleo && printf 'registry=%s\\n' '{registry}' > /etc/idleo/development");
            match wsl.exec(&name, "sh", &["-c", &script]) {
                Ok(output) if output.ok() => {
                    self.log.line(&format!("told the runner the development registry is {registry}"));
                }
                Ok(output) => {
                    self.log.line(&format!("the development registry couldn't be written: {}", output.text().trim()));
                }
                Err(e) => self.log.line(&format!("the development registry couldn't be written: {e}")),
            }
        }
        let distro_file = layout.data().join("distro");
        let _ = std::fs::write(distro_file, format!("{}\n", manifest.distro.version));
        let _ = std::fs::remove_file(&tarball);
        Ok(())
    }

    async fn self_test(&mut self) -> Result<(), Stop> {
        let layout = self.system.layout().clone();
        let apps = Apps::new(layout.clone());
        let Some(version) = apps.current() else { return Err(Ending::Undo.into()) };
        let distro = std::fs::read_to_string(layout.data().join("distro")).unwrap_or_default().trim().to_owned();
        let agent = apps.program(&version, paths::AGENT_EXE);
        // A development build also asks for facts about the laptop, which never make the test fail.
        let request = self.dev_build.then(|| FactsRequest { storage: self.storage_address() });
        let facts_flag = if request.is_some() { " --facts" } else { "" };
        self.log.line(&format!("ran `{} self-test --json --distro {distro}{facts_flag}`", agent.display()));
        match self.system.self_test(&agent, &distro, request.as_ref()) {
            Ok(report) => {
                self.log.block("self-test answered:", &serde_json::to_string_pretty(&report).unwrap_or_default());
                self.report_facts(&report.facts);
                if report.passed {
                    self.detail = Some(format!("{} checks passed", report.checks.len()));
                    self.show_progress();
                    return Ok(());
                }
                // Each failed check is its own measurement, with what it found and how long it took.
                let mut first = None;
                for check in report.checks.iter().filter(|check| !check.passed) {
                    let reason = reasons::check(&check.name);
                    let words = format!("{} (took {} ms)", check.detail, check.took_ms);
                    self.measure_detail(
                        Step::SelfTest.name(),
                        "failed",
                        Some(reason.clone()),
                        Some(&words),
                        Some(Duration::from_millis(check.took_ms)),
                    );
                    first.get_or_insert((reason, words));
                }
                self.reported = true;
                let (reason, words) = first.unwrap_or_else(|| (reasons::SELF_TEST_RUN.to_owned(), "failed".to_owned()));
                self.fail(reason, words);
                self.something_didnt_pass().await
            }
            Err(e) => {
                self.fail(reasons::SELF_TEST_RUN, e);
                self.something_didnt_pass().await
            }
        }
    }

    /// Where our storage is, as `host:port`, from the release's download link: the self-test checks the distribution
    /// can reach it.
    fn storage_address(&self) -> Option<String> {
        self.latest.as_ref().and_then(|(latest, _)| host_and_port(&latest.app_url))
    }

    /// The facts a development build's self-test found, as two measurements (the laptop; what the distribution can
    /// reach), each `name: value` a line, scrubbed like every detail. Facts, not results: a blocked LAN is expected.
    fn report_facts(&mut self, facts: &[idleo_agent::facts::Fact]) {
        for (reason, network) in [(reasons::FACTS_SYSTEM, false), (reasons::FACTS_NETWORK, true)] {
            let text = facts
                .iter()
                .filter(|fact| fact.name.starts_with("reach_") == network)
                .map(|fact| format!("{}: {}", fact.name, fact.value.chars().take(280).collect::<String>()))
                .collect::<Vec<_>>()
                .join("\n");
            if !text.is_empty() {
                self.measure_detail(FACTS_STEP, "passed", Some(reason.to_owned()), Some(&text), None);
            }
        }
    }

    async fn link(&mut self) -> Result<(), Stop> {
        if self.system.linked() {
            return Ok(());
        }
        if self.gpus.is_empty() {
            self.gpus = rtx(&self.system.gpus().unwrap_or_default());
        }
        let machine_name = idleo_agent::link::local_machine_name();
        loop {
            let approve_url: std::cell::RefCell<Option<String>> = std::cell::RefCell::new(None);
            let outcome = {
                let system = self.system;
                let gpus = self.gpus.clone();
                let page = &mut *self.page;
                let events = &mut *self.events;
                let install_id = self.state.install_id;
                let mut progress = |step: Progress| {
                    if let Progress::ApproveAt { url, .. } = step {
                        system.open_url(&url);
                        page(ToPage::Approve {
                            machine_name: machine_name.clone(),
                            short_url: url.trim_start_matches("https://").trim_start_matches("http://").to_owned(),
                            url: url.clone(),
                        });
                        *approve_url.borrow_mut() = Some(url);
                    }
                };
                let linking = system.link(install_id, &gpus, &mut progress);
                tokio::pin!(linking);
                loop {
                    tokio::select! {
                        // The link first: its answer, once there, wins over anything the page says at the same moment.
                        biased;
                        outcome = &mut linking => break Some(outcome),
                        event = events.recv() => match event {
                            Some(FromPage::OpenAgain) => {
                                if let Some(url) = approve_url.borrow().as_deref() {
                                    system.open_url(url);
                                }
                            }
                            Some(FromPage::ConfirmClose) | None => break None,
                            Some(_) => {}
                        },
                    }
                }
            };
            let Some(outcome) = outcome else { return Err(Ending::Undo.into()) };
            match outcome {
                Ok(_) | Err(LinkError::AlreadyEnrolled(_)) => {
                    self.click(reasons::CLICK_APPROVE_SEEN);
                    self.show_progress();
                    return Ok(());
                }
                Err(LinkError::Denied) => {
                    self.fail(reasons::LINK_DENIED, "the owner said this isn't their laptop");
                    self.link_failed("This laptop wasn't approved. If that was a mistake, try again.").await?;
                }
                Err(LinkError::Expired) => {
                    self.fail(reasons::LINK_EXPIRED, "the link ran out after 10 minutes");
                    self.link_failed("The link ran out after 10 minutes. Try again to get a new one.").await?;
                }
                Err(LinkError::Network(e)) => {
                    self.fail(reasons::LINK_NETWORK, e);
                    self.cant_reach().await?;
                }
                Err(e) => {
                    self.fail(reasons::LINK, e);
                    return self.something_didnt_pass().await;
                }
            }
        }
    }

    async fn link_failed(&mut self, message: &str) -> Result<(), Stop> {
        let (reason, words) = (self.reason.clone(), self.failure.clone());
        self.measure_detail(Step::Link.name(), "failed", reason, words.as_deref(), None);
        self.show(ToPage::LinkFailed { message: message.to_owned() });
        self.flush().await;
        self.wait_for(&FromPage::TryAgain).await?;
        self.click(reasons::CLICK_TRY_AGAIN);
        self.show_progress();
        Ok(())
    }

    async fn start(&mut self) -> Result<(), Stop> {
        loop {
            // The entry that starts it at sign-in; a kept install has none until Repair gets here.
            if let Err(e) = install::enable_at_sign_in(self.system)
                .map_err(|e| e.to_string())
                .and_then(|()| self.system.start_launcher())
            {
                self.fail(reasons::START_LAUNCHER, e);
                return self.something_didnt_pass().await;
            }
            match self.system.wait_listed().await {
                Ok(state) => {
                    self.listed = Some(state);
                    self.measure("listed", "passed", Some(reasons::listed(&format!("{state:?}"))), None);
                    return Ok(());
                }
                Err(e) => {
                    self.fail(reasons::START_LISTED, e);
                    self.cant_reach().await?;
                }
            }
        }
    }
}

/// The step name facts are measured under: `install-report.sh` shows them under their own heading.
pub const FACTS_STEP: &str = "facts";

/// `host:port` of a link, with the scheme's usual port when it names none: `https://s.example/x` is `s.example:443`.
#[must_use]
pub fn host_and_port(url: &str) -> Option<String> {
    let (scheme, rest) = url.split_once("://")?;
    let authority = rest.split(['/', '?', '#']).next()?.rsplit('@').next()?;
    let has_port = authority
        .rsplit_once(':')
        .is_some_and(|(_, port)| port.chars().all(|c| c.is_ascii_digit()) && !port.is_empty());
    match (authority.is_empty(), has_port, scheme) {
        (true, _, _) => None,
        (false, true, _) => Some(authority.to_owned()),
        (false, false, "https") => Some(format!("{authority}:443")),
        (false, false, _) => Some(format!("{authority}:80")),
    }
}

/// The RTX GPUs with 8 GB or more.
fn rtx(gpus: &[Gpu]) -> Vec<Gpu> {
    gpus.iter().filter(|gpu| gpu.name.contains("RTX") && gpu.vram_mb >= MIN_VRAM_MB).cloned().collect()
}

fn problem_name(problem: Problem) -> &'static str {
    match problem {
        Problem::WindowsTooOld => "windows_too_old",
        Problem::DriverTooOld => "driver_too_old",
        Problem::VirtualizationOff => "virtualization_off",
        Problem::NoRtxGpu => "no_rtx_gpu",
        Problem::NotEnoughDisk => "not_enough_disk",
        Problem::SomethingDidntPass => "something_didnt_pass",
        Problem::CantReachIdleo => "cant_reach_idleo",
    }
}

fn file_name(key: &str) -> String {
    std::path::Path::new(key)
        .file_name()
        .map_or_else(|| key.replace('/', "_"), |name| name.to_string_lossy().into_owned())
}

fn folder_size(dir: &std::path::Path) -> u64 {
    std::fs::read_dir(dir).map_or(0, |entries| {
        entries.filter_map(Result::ok).filter_map(|entry| entry.metadata().ok()).map(|meta| meta.len()).sum()
    })
}

/// The progress screen with one step current and its detail, for the download's running count.
fn progress_screen(completed: &[Step], current: Step, detail: &str) -> ToPage {
    ToPage::Progress {
        steps: Step::ALL
            .iter()
            .map(|step| StepView {
                label: step.label().to_owned(),
                state: if completed.contains(step) {
                    "done"
                } else if *step == current {
                    "current"
                } else {
                    "waiting"
                }
                .to_owned(),
                detail: (*step == current).then(|| detail.to_owned()),
            })
            .collect(),
        note: None,
    }
}

/// Undoes setup: our distribution, the registry entries, the shortcut and both folders. `running` is the setup program
/// itself when it runs from inside them (after the restart).
#[must_use]
pub fn undo<S: System>(system: &S, running: Option<&std::path::Path>) -> install::Removal {
    install::remove_all(system, running)
}

/// [`undo`], leaving the `logs` folder: a development build's failed setup, whose log is how it gets looked at.
#[must_use]
pub fn undo_keeping_logs<S: System>(system: &S, running: Option<&std::path::Path>) -> install::Removal {
    install::remove_all_keeping(system, running, install::Keep::Logs)
}

/// Where setup keeps itself across the restart.
#[must_use]
pub fn resume_copy(layout: &paths::Layout) -> PathBuf {
    layout.setup_dir().join(paths::exe(paths::SETUP_EXE))
}
