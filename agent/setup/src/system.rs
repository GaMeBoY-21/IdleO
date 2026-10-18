//! What the setup steps need from the machine (ADR 0033), behind [`System`]: the real Windows implementation in the
//! program, and [`fake::FakeSystem`] for the tests on the Mac. Installing and removing go through
//! [`idleo_release::install::Machine`], which `System` extends, so setup and the uninstaller remove the same things.

use std::path::Path;

use idleo_agent::link::{LinkError, Linked, Progress};
use idleo_agent::self_test::Report;
use idleo_release::channel::LinkState;
use idleo_release::download::DownloadError;
use idleo_release::install::Machine;
use idleo_release::manifest::Artifact;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::scrub::Identity;

/// A GPU, as NVML sees it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Gpu {
    /// Marketing name, for example `NVIDIA GeForce RTX 4080 Laptop GPU`.
    pub name: String,
    /// Total memory, in MB.
    pub vram_mb: i32,
    /// The NVIDIA driver, for example "572.16".
    pub driver: String,
}

/// The latest release, as `GET /v1/agent-releases/latest` gives it: the manifest exactly as signed, its signature,
/// and links valid for an hour.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Latest {
    /// The manifest's bytes.
    pub manifest: Vec<u8>,
    /// The raw signature.
    pub signature: Vec<u8>,
    /// The app zip's link.
    pub app_url: String,
    /// The distro tarball's link.
    pub distro_url: String,
}

/// One measurement, as `POST /v1/host-installs/{id}/events` takes it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallEvent {
    /// The step, `click` or `setup_opened` / `listed`.
    pub step: String,
    /// `started`, `passed`, `failed` or `fixed`.
    pub outcome: String,
    /// Why it failed, or which button a click was.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    /// How long the step took.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    /// When, on this machine's clock.
    #[serde(with = "time::serde::rfc3339")]
    pub at: time::OffsetDateTime,
    /// The setup app's version.
    pub setup_version: String,
    /// The Windows build, once known.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub windows_build: Option<u32>,
    /// The GPU's model, once known.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gpu_model: Option<String>,
    /// Why it failed, in words, already scrubbed ([`crate::scrub::detail`]) and at most 2,000 characters.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
}

/// IdleO didn't take a batch of measurements.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rejected {
    /// What to say in a few characters: the HTTP status such as `422`, or `no answer`.
    pub status: String,
    /// What it answered, for the setup log.
    pub answer: String,
}

/// What a development build asks the self-test to find out besides its checks (`idleo-agent self-test --facts`).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct FactsRequest {
    /// Our storage's `host:port`, for the reachability fact.
    pub storage: Option<String>,
}

/// The machine, as setup sees it.
#[allow(async_fn_in_trait)] // Used through generics only; the futures never cross threads.
pub trait System: Machine {
    /// The Windows build, for example 26100.
    ///
    /// # Errors
    /// If it can't be read.
    fn windows_build(&self) -> Result<u32, String>;

    /// The NVIDIA GPUs, through NVML; empty if there are none.
    ///
    /// # Errors
    /// If NVML can't be loaded (no NVIDIA driver at all).
    fn gpus(&self) -> Result<Vec<Gpu>, String>;

    /// Whether firmware virtualization is on (or a hypervisor already runs).
    fn virtualization_enabled(&self) -> bool;

    /// Free bytes on the drive the app installs to.
    ///
    /// # Errors
    /// If it can't be read.
    fn free_disk_bytes(&self) -> Result<u64, String>;

    /// Whether the laptop runs on battery now.
    fn on_battery(&self) -> bool;

    /// Who made the laptop, lowercase, for the help page: `asus`, `msi`, `lenovo`, `hp`, `acer`, `dell` or `other`.
    fn maker(&self) -> String;

    /// The latest release.
    ///
    /// # Errors
    /// If IdleO can't be reached, or there's no release.
    async fn latest(&self) -> Result<Latest, String>;

    /// Downloads one file of a release, resuming, checked against its size and SHA-256.
    ///
    /// # Errors
    /// See [`DownloadError`].
    async fn download(
        &self,
        url: &str,
        dest: &Path,
        artifact: &Artifact,
        progress: &mut (dyn FnMut(u64, u64) + Send),
    ) -> Result<(), DownloadError>;

    /// Runs the installed agent's `self-test --json` against a distro version, with `--facts` when asked.
    ///
    /// # Errors
    /// If it can't be run or its output read.
    fn self_test(&self, agent: &Path, distro_version: &str, facts: Option<&FactsRequest>) -> Result<Report, String>;

    /// Whether this machine already has its identity (it's linked).
    fn linked(&self) -> bool;

    /// Links this machine by approval (`idleo_agent::link`), with the install's id.
    ///
    /// # Errors
    /// See [`LinkError`].
    async fn link(
        &self,
        install_id: Uuid,
        gpus: &[Gpu],
        progress: &mut dyn FnMut(Progress),
    ) -> Result<Linked, LinkError>;

    /// Starts the launcher, which starts the agent and the tray.
    ///
    /// # Errors
    /// If it can't be started.
    fn start_launcher(&self) -> Result<(), String>;

    /// Waits for the agent to tell the tray where the host stands (its first `HostStatus`).
    ///
    /// # Errors
    /// If it doesn't in time.
    async fn wait_listed(&self) -> Result<LinkState, String>;

    /// Opens an address in the browser.
    fn open_url(&self, url: &str);

    /// Restarts Windows now.
    ///
    /// # Errors
    /// If it can't.
    fn restart_windows(&self) -> Result<(), String>;

    /// Copies this setup program to `to`, so it survives the restart even if the download is deleted.
    ///
    /// # Errors
    /// If it can't be copied.
    fn copy_self(&self, to: &Path) -> Result<(), String>;

    /// Who and what a report must not name: the profile folder, the user and the computer.
    fn identity(&self) -> Identity;

    /// Sends measurements once, with a short wait. They're best effort: setup carries on whatever the answer, but
    /// says in the log (and in a development build on the last screen) when IdleO didn't take them.
    ///
    /// # Errors
    /// If IdleO answered with anything but success, or didn't answer.
    async fn send_events(&self, install_id: Uuid, events: Vec<InstallEvent>) -> Result<(), Rejected>;
}

/// A machine for tests: the fake disk, registry and `wsl.exe` of [`idleo_release::install::fake`], and every other
/// answer scripted.
#[cfg(test)]
pub mod fake {
    use std::collections::VecDeque;
    use std::path::{Path, PathBuf};

    use idleo_agent::link::{LinkError, Linked, Progress};
    use idleo_agent::self_test::{Check, Report};
    use idleo_release::channel::LinkState;
    use idleo_release::download::DownloadError;
    use idleo_release::install::Machine;
    use idleo_release::install::fake::FakeMachine;
    use idleo_release::manifest::Artifact;
    use idleo_release::paths::Layout;
    use idleo_release::registry::Registry;
    use idleo_release::wsl::Runner;
    use uuid::Uuid;

    use super::{FactsRequest, Gpu, Identity, InstallEvent, Latest, Rejected, System};

    /// A value the fake changes behind `&self`, shareable between threads as `Machine` requires.
    #[derive(Debug, Default)]
    pub struct Shared<T>(std::sync::Mutex<T>);

    impl<T> Shared<T> {
        /// A shared value.
        pub fn new(value: T) -> Self {
            Self(std::sync::Mutex::new(value))
        }

        /// Reads it.
        pub fn borrow(&self) -> std::sync::MutexGuard<'_, T> {
            self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
        }

        /// Changes it.
        pub fn borrow_mut(&self) -> std::sync::MutexGuard<'_, T> {
            self.borrow()
        }
    }

    /// How a link attempt goes.
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum LinkGoes {
        /// Approved.
        Approved,
        /// "This isn't my laptop."
        Denied,
        /// Nobody approved in time.
        Expired,
    }

    /// The fake machine.
    pub struct FakeSystem {
        /// The disk, registry and `wsl.exe`.
        pub machine: FakeMachine,
        /// The Windows build.
        pub windows_build: u32,
        /// The GPUs; each check reads the front, and the last one stays.
        pub gpus: Shared<VecDeque<Vec<Gpu>>>,
        /// Firmware virtualization.
        pub virtualization: bool,
        /// Free disk.
        pub free_bytes: u64,
        /// On battery.
        pub on_battery: bool,
        /// The release, or why it can't be had.
        pub latest: Result<Latest, String>,
        /// The files the release's links serve, by link.
        pub files: Vec<(String, Vec<u8>)>,
        /// Whether the self-test passes.
        pub self_test_passes: bool,
        /// The self-test's checks when a test wants its own; else one, `gpu_in_container`.
        pub self_test_checks: Option<Vec<Check>>,
        /// The facts the self-test finds when it is asked for them.
        pub facts: Vec<idleo_agent::facts::Fact>,
        /// Whether, and how, each self-test was asked for facts.
        pub facts_asked: Shared<Vec<Option<FactsRequest>>>,
        /// Who the fake laptop's owner is.
        pub identity: Identity,
        /// When set, IdleO refuses every batch of measurements with this status.
        pub reject_events: Option<Rejected>,
        /// How many batches were offered, taken or not.
        pub batches_offered: Shared<usize>,
        /// The size in bytes of each batch, as it goes on the wire.
        pub batch_bytes: Shared<Vec<usize>>,
        /// How each link attempt goes, in turn.
        pub links: Shared<VecDeque<LinkGoes>>,
        /// What the host's first status says.
        pub listed: LinkState,
        /// Addresses opened.
        pub opened: Shared<Vec<String>>,
        /// Measurements sent.
        pub events: Shared<Vec<InstallEvent>>,
        /// Whether Windows was restarted.
        pub restarted: Shared<bool>,
        /// Whether the launcher was started.
        pub started: Shared<bool>,
    }

    /// The RTX laptop every test starts with.
    #[must_use]
    pub fn rtx() -> Gpu {
        Gpu { name: "NVIDIA GeForce RTX 4080 Laptop GPU".into(), vram_mb: 12_282, driver: "572.16".into() }
    }

    impl FakeSystem {
        /// A laptop that passes everything, with WSL ready; `latest` and `files` are a release.
        #[must_use]
        pub fn new(latest: Latest, files: Vec<(String, Vec<u8>)>) -> Self {
            Self {
                machine: FakeMachine::new(),
                windows_build: 26_100,
                gpus: Shared::new(VecDeque::from([vec![rtx()]])),
                virtualization: true,
                free_bytes: 200_000_000_000,
                on_battery: false,
                latest: Ok(latest),
                files,
                self_test_passes: true,
                self_test_checks: None,
                facts: Vec::new(),
                facts_asked: Shared::new(Vec::new()),
                identity: Identity {
                    profile: r"C:\Users\Asha Rao".into(),
                    user: "Asha".into(),
                    machine: "TESTRIG-7".into(),
                },
                reject_events: None,
                batches_offered: Shared::new(0),
                batch_bytes: Shared::new(Vec::new()),
                links: Shared::new(VecDeque::from([LinkGoes::Approved])),
                listed: LinkState::PendingApproval,
                opened: Shared::new(Vec::new()),
                events: Shared::new(Vec::new()),
                restarted: Shared::new(false),
                started: Shared::new(false),
            }
        }
    }

    impl Machine for FakeSystem {
        fn layout(&self) -> &Layout {
            self.machine.layout()
        }

        fn registry(&self) -> &dyn Registry {
            self.machine.registry()
        }

        fn wsl_runner(&self) -> &dyn Runner {
            self.machine.wsl_runner()
        }

        fn shortcut_path(&self) -> PathBuf {
            self.machine.shortcut_path()
        }

        fn create_shortcut(&self, link: &Path, target: &Path, arguments: &str) -> std::io::Result<()> {
            self.machine.create_shortcut(link, target, arguments)
        }
    }

    // The fake answers at once; the trait is async for the real machine.
    #[allow(clippy::unused_async_trait_impl)]
    impl System for FakeSystem {
        fn windows_build(&self) -> Result<u32, String> {
            Ok(self.windows_build)
        }

        fn gpus(&self) -> Result<Vec<Gpu>, String> {
            let mut gpus = self.gpus.borrow_mut();
            let front = gpus.front().cloned().unwrap_or_default();
            if gpus.len() > 1 {
                gpus.pop_front();
            }
            Ok(front)
        }

        fn virtualization_enabled(&self) -> bool {
            self.virtualization
        }

        fn free_disk_bytes(&self) -> Result<u64, String> {
            Ok(self.free_bytes)
        }

        fn on_battery(&self) -> bool {
            self.on_battery
        }

        fn maker(&self) -> String {
            "asus".into()
        }

        async fn latest(&self) -> Result<Latest, String> {
            self.latest.clone()
        }

        async fn download(
            &self,
            url: &str,
            dest: &Path,
            artifact: &Artifact,
            progress: &mut (dyn FnMut(u64, u64) + Send),
        ) -> Result<(), DownloadError> {
            let bytes = self
                .files
                .iter()
                .find(|(link, _)| link == url)
                .map(|(_, bytes)| bytes.clone())
                .ok_or_else(|| DownloadError::Download("404".into()))?;
            std::fs::create_dir_all(dest.parent().expect("a folder")).expect("writable");
            std::fs::write(dest, &bytes).expect("writable");
            progress(bytes.len() as u64, artifact.size);
            let matches = idleo_release::download::matches(dest, artifact).await.unwrap_or(false);
            if matches {
                Ok(())
            } else {
                let _ = std::fs::remove_file(dest);
                Err(DownloadError::Verify("not the release's file".into()))
            }
        }

        fn self_test(
            &self,
            agent: &Path,
            distro_version: &str,
            facts: Option<&FactsRequest>,
        ) -> Result<Report, String> {
            assert!(agent.exists(), "the installed agent runs the self-test");
            self.facts_asked.borrow_mut().push(facts.cloned());
            let checks = self.self_test_checks.clone().unwrap_or_else(|| {
                vec![Check {
                    name: "gpu_in_container".into(),
                    passed: self.self_test_passes,
                    detail: format!("distro {distro_version}"),
                    took_ms: 900,
                }]
            });
            let passed = checks.iter().all(|check| check.passed);
            let facts = if facts.is_some() { self.facts.clone() } else { Vec::new() };
            Ok(Report { passed, checks, distro_version: distro_version.into(), facts })
        }

        fn linked(&self) -> bool {
            self.layout().agent_dir().join("host.json").exists()
        }

        async fn link(
            &self,
            _install_id: Uuid,
            gpus: &[Gpu],
            progress: &mut dyn FnMut(Progress),
        ) -> Result<Linked, LinkError> {
            assert!(!gpus.is_empty(), "the approval page shows the GPU");
            progress(Progress::Asking);
            progress(Progress::ApproveAt {
                url: "https://app.idleo.test/link/42/".into(),
                expires_in: std::time::Duration::from_secs(600),
            });
            progress(Progress::Waiting);
            let goes = self.links.borrow_mut().pop_front().unwrap_or(LinkGoes::Approved);
            match goes {
                LinkGoes::Approved => {
                    progress(Progress::Enrolling);
                    let dir = self.layout().agent_dir();
                    std::fs::create_dir_all(&dir).expect("writable");
                    std::fs::write(dir.join("host.json"), "{}").expect("writable");
                    Ok(Linked {
                        host_id: Uuid::nil(),
                        host_name: "Nikki's laptop".into(),
                        owner_hint: Some("n".into()),
                        not_after: time::OffsetDateTime::now_utc(),
                    })
                }
                LinkGoes::Denied => Err(LinkError::Denied),
                LinkGoes::Expired => Err(LinkError::Expired),
            }
        }

        fn start_launcher(&self) -> Result<(), String> {
            *self.started.borrow_mut() = true;
            Ok(())
        }

        async fn wait_listed(&self) -> Result<LinkState, String> {
            Ok(self.listed)
        }

        fn open_url(&self, url: &str) {
            self.opened.borrow_mut().push(url.to_owned());
        }

        fn restart_windows(&self) -> Result<(), String> {
            *self.restarted.borrow_mut() = true;
            Ok(())
        }

        fn copy_self(&self, to: &Path) -> Result<(), String> {
            std::fs::create_dir_all(to.parent().expect("a folder")).map_err(|e| e.to_string())?;
            std::fs::write(to, "IdleO-Setup").map_err(|e| e.to_string())
        }

        fn identity(&self) -> Identity {
            self.identity.clone()
        }

        async fn send_events(&self, _install_id: Uuid, events: Vec<InstallEvent>) -> Result<(), Rejected> {
            *self.batches_offered.borrow_mut() += 1;
            let wire = serde_json::json!({ "events": events }).to_string();
            self.batch_bytes.borrow_mut().push(wire.len());
            if let Some(rejected) = &self.reject_events {
                return Err(rejected.clone());
            }
            self.events.borrow_mut().extend(events);
            Ok(())
        }
    }
}
