//! The real machine for setup: NVML, the registry, `wsl.exe`, the network and the processes it starts.

use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use base64::Engine as _;
use idleo_agent::link::{self, LinkError, LinkGpu, Linked, Progress};
use idleo_agent::self_test::Report;
use idleo_release::channel::{LinkState, Message};
use idleo_release::download::DownloadError;
use idleo_release::install::{Machine, SystemMachine};
use idleo_release::manifest::Artifact;
use idleo_release::paths::Layout;
use idleo_release::registry::Registry;
use idleo_release::wsl::Runner;
use idleo_setup::steps::Config;
use idleo_setup::system::{Gpu, InstallEvent, Latest, System};
use uuid::Uuid;

/// `CREATE_NO_WINDOW`: no console flashes up.
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
/// How long to wait for the agent's first word from IdleO after starting it.
const LISTED_WAITS: Duration = Duration::from_secs(180);

pub struct WindowsSystem {
    machine: SystemMachine,
    client: reqwest::Client,
    config: Config,
}

impl WindowsSystem {
    pub fn new(config: Config) -> Result<Self, String> {
        Ok(Self { machine: SystemMachine::new(), client: idleo_release::download::http_client()?, config })
    }
}

impl Machine for WindowsSystem {
    fn layout(&self) -> &Layout {
        self.machine.layout()
    }

    fn registry(&self) -> &dyn Registry {
        self.machine.registry()
    }

    fn wsl_runner(&self) -> &dyn Runner {
        self.machine.wsl_runner()
    }

    fn shortcut_path(&self) -> std::path::PathBuf {
        self.machine.shortcut_path()
    }

    fn create_shortcut(&self, link: &Path, target: &Path, arguments: &str) -> std::io::Result<()> {
        self.machine.create_shortcut(link, target, arguments)
    }
}

#[derive(serde::Deserialize)]
struct LatestResponse {
    manifest: String,
    signature: String,
    urls: Urls,
}

#[derive(serde::Deserialize)]
struct Urls {
    app: String,
    distro: String,
}

impl System for WindowsSystem {
    fn windows_build(&self) -> Result<u32, String> {
        agent_windows::windows_build().map_err(|e| e.to_string())
    }

    fn gpus(&self) -> Result<Vec<Gpu>, String> {
        let source = idleo_agent::telemetry::system().map_err(|e| e.to_string())?;
        Ok(source
            .gpus()
            .map_err(|e| e.to_string())?
            .into_iter()
            .map(|gpu| Gpu { name: gpu.name, vram_mb: gpu.vram_mb, driver: gpu.driver_version })
            .collect())
    }

    fn virtualization_enabled(&self) -> bool {
        agent_windows::virtualization_enabled()
    }

    fn free_disk_bytes(&self) -> Result<u64, String> {
        let mut folder = self.layout().data().to_path_buf();
        while !folder.exists() {
            if !folder.pop() {
                break;
            }
        }
        agent_windows::free_disk_space(&folder).map_err(|e| e.to_string())
    }

    fn on_battery(&self) -> bool {
        agent_windows::ac_line_status().is_ok_and(|status| status == 0)
    }

    fn maker(&self) -> String {
        let maker = agent_windows::system_manufacturer().unwrap_or_default().to_lowercase();
        [
            ("asus", "asus"),
            ("micro-star", "msi"),
            ("msi", "msi"),
            ("lenovo", "lenovo"),
            ("hewlett", "hp"),
            ("hp", "hp"),
            ("acer", "acer"),
            ("dell", "dell"),
        ]
        .iter()
        .find(|(found, _)| maker.contains(found))
        .map_or("other", |(_, page)| page)
        .to_owned()
    }

    async fn latest(&self) -> Result<Latest, String> {
        let url = format!("{}/v1/agent-releases/latest", self.config.api_url.trim_end_matches('/'));
        let response = self.client.get(url).send().await.map_err(|e| e.to_string())?;
        if !response.status().is_success() {
            return Err(format!("IdleO answered {}", response.status()));
        }
        let body: LatestResponse = response.json().await.map_err(|e| e.to_string())?;
        let signature = base64::engine::general_purpose::STANDARD.decode(body.signature).map_err(|e| e.to_string())?;
        Ok(Latest {
            manifest: body.manifest.into_bytes(),
            signature,
            app_url: body.urls.app,
            distro_url: body.urls.distro,
        })
    }

    async fn download(
        &self,
        url: &str,
        dest: &Path,
        artifact: &Artifact,
        progress: &mut (dyn FnMut(u64, u64) + Send),
    ) -> Result<(), DownloadError> {
        idleo_release::download::download(&self.client, url, dest, artifact, progress).await.map(|_| ())
    }

    fn self_test(&self, agent: &Path, distro_version: &str) -> Result<Report, String> {
        use std::os::windows::process::CommandExt;
        let output = std::process::Command::new(agent)
            .args(["self-test", "--json", "--distro", distro_version])
            .creation_flags(CREATE_NO_WINDOW)
            .output()
            .map_err(|e| e.to_string())?;
        let text = String::from_utf8_lossy(&output.stdout);
        serde_json::from_str(text.lines().last().unwrap_or("")).map_err(|e| format!("the self-test's answer: {e}"))
    }

    fn linked(&self) -> bool {
        idleo_agent::state::is_enrolled(&self.layout().agent_dir()).unwrap_or(false)
    }

    async fn link(
        &self,
        install_id: Uuid,
        gpus: &[Gpu],
        progress: &mut dyn FnMut(Progress),
    ) -> Result<Linked, LinkError> {
        let roots =
            idleo_agent::pki::load_roots(&self.layout().agent_dir().join("ca-root.pem")).map_err(LinkError::Refused)?;
        let target = idleo_agent::tls::Target::tcp(&self.config.control_plane_host, self.config.control_plane_port)
            .map_err(LinkError::Refused)?;
        let settings = link::Settings {
            api_url: self.config.api_url.clone(),
            machine: link::Machine {
                name: link::local_machine_name(),
                os: link::os_name().to_owned(),
                agent_version: idleo_agent::AGENT_VERSION.to_owned(),
                gpus: gpus
                    .iter()
                    .take(8)
                    .map(|gpu| LinkGpu { model: gpu.name.clone(), vram_mb: gpu.vram_mb })
                    .collect(),
                install_id: Some(install_id),
            },
            target,
            roots,
            data_dir: self.layout().agent_dir(),
            force: false,
            poll_interval: None,
        };
        let client = idleo_agent::storage::http_client().map_err(LinkError::Refused)?;
        link::link(&settings, &client, progress).await
    }

    fn start_launcher(&self) -> Result<(), String> {
        use std::os::windows::process::CommandExt;
        std::process::Command::new(self.layout().launcher())
            .arg("start")
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map(|_| ())
            .map_err(|e| e.to_string())
    }

    async fn wait_listed(&self) -> Result<LinkState, String> {
        tokio::time::timeout(LISTED_WAITS, async {
            loop {
                if let Ok(mut client) = idleo_release::channel::connect() {
                    while let Ok(message) = client.next().await {
                        if let Message::Status(status) = message
                            && matches!(status.link_state, LinkState::PendingApproval | LinkState::Active)
                        {
                            return status.link_state;
                        }
                    }
                }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        })
        .await
        .map_err(|_| "the app didn't hear from IdleO in time".to_owned())
    }

    fn open_url(&self, url: &str) {
        let _ = idleo_release::browser::open(url);
    }

    fn restart_windows(&self) -> Result<(), String> {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("shutdown")
            .args(["/r", "/t", "0"])
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
            .map(|_| ())
            .map_err(|e| e.to_string())
    }

    fn copy_self(&self, to: &Path) -> Result<(), String> {
        let me = std::env::current_exe().map_err(|e| e.to_string())?;
        if me == to {
            return Ok(());
        }
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        std::fs::copy(me, to).map(|_| ()).map_err(|e| e.to_string())
    }

    async fn send_events(&self, install_id: Uuid, events: Vec<InstallEvent>) {
        // Best effort, and never in the way: sent on its own task.
        let client = self.client.clone();
        let url = format!("{}/v1/host-installs/{install_id}/events", self.config.api_url.trim_end_matches('/'));
        let body = Arc::new(serde_json::json!({ "events": events }));
        tokio::spawn(async move {
            let _ = client.post(url).json(&*body).timeout(Duration::from_secs(10)).send().await;
        });
    }
}
