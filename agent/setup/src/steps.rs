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
//! Every step's start and end, and every click, are measured (host experience §6 row 11).

use std::path::PathBuf;
use std::time::Duration;

use idleo_agent::link::{LinkError, Progress};
use idleo_release::apps::Apps;
use idleo_release::channel::LinkState;
use idleo_release::download::DownloadError;
use idleo_release::install::{self, RUNONCE_KEY, RUNONCE_VALUE};
use idleo_release::manifest::{self, Manifest};
use idleo_release::paths;
use idleo_release::registry::Value;
use idleo_release::wsl::{Wsl, WslState};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio::time::Instant;
use uuid::Uuid;

use crate::ipc::{FromPage, Problem, StepView, ToPage};
use crate::system::{Gpu, InstallEvent, Latest, System};

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
        }
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
        self.pending_events.push(InstallEvent {
            step: step.to_owned(),
            outcome: outcome.to_owned(),
            reason,
            duration_ms: took.map(|took| u64::try_from(took.as_millis()).unwrap_or(u64::MAX)),
            at: time::OffsetDateTime::now_utc(),
            setup_version: idleo_agent::AGENT_VERSION.to_owned(),
            windows_build: self.windows_build,
            gpu_model: self.gpus.first().map(|gpu| gpu.name.clone()),
        });
    }

    fn click(&mut self, which: &str) {
        self.measure("click", "passed", Some(which.to_owned()), None);
    }

    async fn flush(&mut self) {
        let events = std::mem::take(&mut self.pending_events);
        for batch in events.chunks(20) {
            self.system.send_events(self.state.install_id, batch.to_vec()).await;
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
        if ending == Ending::Undo {
            self.measure("setup_closed", "failed", self.failed.map(|step| step.name().to_owned()), None);
        }
        self.flush().await;
        ending
    }

    async fn steps(&mut self, resume: bool) -> Result<Ending, Stop> {
        let installed = install::is_installed(self.system);
        let linked = self.system.linked();
        let mut repair = false;
        let first = if resume && !self.state.completed.is_empty() {
            self.first_incomplete()
        } else if installed && linked {
            self.show(ToPage::Welcome { already_set_up: true, dev_build: idleo_release::keys::DEV_BUILD });
            self.wait_for(&FromPage::SetUp).await?;
            self.click("repair");
            repair = true;
            self.state.completed.clear();
            Step::Checks
        } else if installed {
            Step::Link
        } else {
            self.show(ToPage::Welcome { already_set_up: false, dev_build: idleo_release::keys::DEV_BUILD });
            self.wait_for(&FromPage::SetUp).await?;
            self.click("set_up");
            self.first_incomplete()
        };
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
        self.show(ToPage::Done { pending: self.pending_listing() });
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
        self.show_progress();
        self.measure(step.name(), "started", None, None);
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
                self.measure(step.name(), "passed", None, Some(started.elapsed()));
                if !self.state.completed.contains(&step) {
                    self.state.completed.push(step);
                }
                self.save();
            }
            Err(Stop::Ending(Ending::Restarting | Ending::Later)) => {}
            Err(Stop::Ending(_)) => {
                self.failed = Some(step);
                let reason = self.reason.take();
                self.measure(step.name(), "failed", reason, Some(started.elapsed()));
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
            self.reason = Some(format!("windows_build_{build}"));
            let system = self.system;
            self.fix(Problem::WindowsTooOld, 0, Some("ms-settings:windowsupdate".to_owned()), move || {
                system.windows_build().unwrap_or(0) >= minimum
            })
            .await?;
        }
        self.gpus = rtx(&self.system.gpus().unwrap_or_default());
        if self.gpus.is_empty() {
            self.reason = Some("no_rtx_gpu".to_owned());
            self.show(ToPage::Fix {
                problem: Problem::NoRtxGpu,
                text: Problem::NoRtxGpu.text(0),
                button: Problem::NoRtxGpu.button().to_owned(),
                rechecking: false,
            });
            self.wait_for(&FromPage::Fix).await?;
            self.click("fix:no_rtx_gpu");
            return Err(Ending::Undo.into());
        }
        if !self.gpus.iter().any(|gpu| idleo_release::drivers::is_supported(&gpu.driver)) {
            self.reason = Some(format!("driver_too_old:{}", self.gpus[0].driver));
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
            self.reason = Some("virtualization_off".to_owned());
            let help =
                format!("{}/help/virtualization/{}/", self.config.site_url.trim_end_matches('/'), self.system.maker());
            let system = self.system;
            self.fix(Problem::VirtualizationOff, 0, Some(help), move || system.virtualization_enabled()).await?;
        }
        let free_gb = self.system.free_disk_bytes().unwrap_or(0) / 1_000_000_000;
        if free_gb < NEEDED_FREE_GB {
            self.reason = Some(format!("disk_{free_gb}_gb"));
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
            self.measure("plugged_in", "failed", Some("on_battery".to_owned()), None);
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
                            self.reason = Some(format!("manifest:{e}"));
                            self.something_didnt_pass().await
                        }
                    };
                }
                Err(e) => {
                    self.reason = Some(format!("unreachable:{e}"));
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
        });
        self.wait_for(&FromPage::Fix).await?;
        self.click("try_again");
        self.show_progress();
        Ok(())
    }

    /// "Something didn't pass": the report reaches us, the laptop stays unlisted, and setup undoes what it did.
    async fn something_didnt_pass(&mut self) -> Result<(), Stop> {
        self.show(ToPage::Fix {
            problem: Problem::SomethingDidntPass,
            text: Problem::SomethingDidntPass.text(0),
            button: Problem::SomethingDidntPass.button().to_owned(),
            rechecking: false,
        });
        self.flush().await;
        self.wait_for(&FromPage::Fix).await?;
        Err(Ending::Undo.into())
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
        });
        self.flush().await;
        loop {
            tokio::select! {
                event = self.events.recv() => match event {
                    Some(FromPage::Fix) => {
                        self.click(&format!("fix:{}", problem_name(problem)));
                        if let Some(url) = &url {
                            self.system.open_url(url);
                        }
                    }
                    Some(FromPage::ConfirmClose) | None => return Err(Ending::Undo.into()),
                    Some(_) => {}
                },
                () = tokio::time::sleep(RECHECK_EVERY) => {
                    if recheck() {
                        self.measure(Step::Checks.name(), "fixed", Some(problem_name(problem).to_owned()), None);
                        self.reason = None;
                        self.show_progress();
                        return Ok(());
                    }
                }
            }
        }
    }

    async fn wsl(&mut self) -> Result<(), Stop> {
        let wsl = Wsl::new(self.system.wsl_runner());
        match wsl.state() {
            WslState::Ready(_) => Ok(()),
            WslState::TooOld(found) => {
                self.detail = Some("Updating WSL".to_owned());
                self.show_progress();
                wsl.update().map_err(|e| {
                    self.reason = Some(format!("wsl_update:{found}:{e}"));
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
                        self.reason = Some(format!("wsl_install:{e}"));
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
                    self.click("restart_now");
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
                    self.reason = Some(format!("download_verify:{e}"));
                    return self.something_didnt_pass().await;
                }
                Err(DownloadError::Download(e)) => {
                    self.reason = Some(format!("download:{e}"));
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
            self.reason = Some(format!("install:{e}"));
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
        let wsl = Wsl::new(self.system.wsl_runner());
        let tarball = layout.updates().join(file_name(&manifest.distro.file));
        let registered = wsl.ours().is_ok_and(|ours| ours.contains(&name));
        if !registered && let Err(e) = wsl.import(&name, &layout.wsl_dir(&manifest.distro.version), &tarball) {
            self.reason = Some(format!("distro:{e}"));
            return self.something_didnt_pass().await;
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
        match self.system.self_test(&apps.program(&version, paths::AGENT_EXE), &distro) {
            Ok(report) if report.passed => {
                self.detail = Some(format!("{} checks passed", report.checks.len()));
                self.show_progress();
                Ok(())
            }
            Ok(report) => {
                let failed: Vec<&str> =
                    report.checks.iter().filter(|check| !check.passed).map(|check| check.name.as_str()).collect();
                self.reason = Some(format!("self_test:{}", failed.join(",")));
                self.something_didnt_pass().await
            }
            Err(e) => {
                self.reason = Some(format!("self_test:{e}"));
                self.something_didnt_pass().await
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
                    self.click("approve_seen");
                    self.show_progress();
                    return Ok(());
                }
                Err(LinkError::Denied) => {
                    self.reason = Some("link_denied".to_owned());
                    self.link_failed("This laptop wasn't approved. If that was a mistake, try again.").await?;
                }
                Err(LinkError::Expired) => {
                    self.reason = Some("link_expired".to_owned());
                    self.link_failed("The link ran out after 10 minutes. Try again to get a new one.").await?;
                }
                Err(LinkError::Network(e)) => {
                    self.reason = Some(format!("link_network:{e}"));
                    self.cant_reach().await?;
                }
                Err(e) => {
                    self.reason = Some(format!("link:{e}"));
                    return self.something_didnt_pass().await;
                }
            }
        }
    }

    async fn link_failed(&mut self, message: &str) -> Result<(), Stop> {
        self.measure(Step::Link.name(), "failed", self.reason.clone(), None);
        self.show(ToPage::LinkFailed { message: message.to_owned() });
        self.flush().await;
        self.wait_for(&FromPage::TryAgain).await?;
        self.click("try_again");
        self.show_progress();
        Ok(())
    }

    async fn start(&mut self) -> Result<(), Stop> {
        loop {
            if let Err(e) = self.system.start_launcher() {
                self.reason = Some(format!("start:{e}"));
                return self.something_didnt_pass().await;
            }
            match self.system.wait_listed().await {
                Ok(state) => {
                    self.listed = Some(state);
                    self.measure("listed", "passed", Some(format!("{state:?}").to_lowercase()), None);
                    return Ok(());
                }
                Err(e) => {
                    self.reason = Some(format!("start:{e}"));
                    self.cant_reach().await?;
                }
            }
        }
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

/// Where setup keeps itself across the restart.
#[must_use]
pub fn resume_copy(layout: &paths::Layout) -> PathBuf {
    layout.setup_dir().join(paths::exe(paths::SETUP_EXE))
}
