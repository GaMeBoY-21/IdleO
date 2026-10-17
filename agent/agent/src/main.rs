//! `idleo-agent`: the host agent's command line.
#![forbid(unsafe_code)]

use std::io::IsTerminal;
use std::path::PathBuf;
use std::process::ExitCode;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

use clap::{Parser, Subcommand};
use idleo_agent::config::{self, Config, LogFormat};
use idleo_agent::connection::{self, RunOptions, Timing};
use idleo_agent::disk::{FixedDisk, FreeDisk, SystemDisk};
use idleo_agent::enrol::{self, EnrolmentCode};
use idleo_agent::lease_clock::SystemLeaseClock;
use idleo_agent::link;
use idleo_agent::owner::SystemOwner;
use idleo_agent::runner::SessionRunner;
use idleo_agent::runner::dev::DevRunner;
use idleo_agent::telemetry::{self, FakeGpus, FakeModel, GpuSource};
use idleo_agent::{pki, state, tls};
use tokio::sync::watch;
use tracing_subscriber::filter::Targets;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

/// Exit codes, also listed in the README.
mod exit {
    pub const FAILURE: u8 = 1;
    pub const MALFORMED_CODE: u8 = 2;
    pub const CONFIG: u8 = 6;
    pub const GPU: u8 = 7;
}

/// The hard limit on shutdown after Ctrl-C or a stop request.
const SHUTDOWN_LIMIT: Duration = Duration::from_secs(5);

#[derive(Parser)]
#[command(name = "idleo-agent", version, about = "The IdleO host agent")]
struct Cli {
    /// The config file [default: agent.toml in the agent's directory for this OS]
    #[arg(long, global = true, value_name = "PATH")]
    config: Option<PathBuf>,
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Link this machine to your account: open the link it shows, and approve it in the browser.
    Link {
        /// The name to offer on the approval page [default: this computer's name].
        #[arg(long, value_name = "NAME")]
        name: Option<String>,
        /// The control plane's REST address [default: the address set in the config file].
        #[arg(long, value_name = "URL")]
        api: Option<String>,
        /// Don't open a browser; only print the link.
        #[arg(long)]
        no_browser: bool,
        /// Replace an existing link, keeping a backup of it.
        #[arg(long)]
        force: bool,
        /// Use N invented GPUs instead of NVML, for development and tests [default: 1].
        #[arg(long, value_name = "N", num_args = 0..=1, default_missing_value = "1")]
        fake_gpu: Option<u8>,
        /// Only with --fake-gpu: the model name the fake GPUs report [default: IdleO Fake GPU].
        #[arg(long, value_name = "NAME", requires = "fake_gpu")]
        fake_gpu_model: Option<String>,
        /// Only with --fake-gpu: each fake GPU's memory in MB, at least 1024 [default: 12282].
        #[arg(long, value_name = "MB", requires = "fake_gpu", value_parser = clap::value_parser!(i32).range(i64::from(FakeModel::MIN_VRAM_MB)..))]
        fake_gpu_vram_mb: Option<i32>,
    },
    /// For operators and tests: enrol this machine with a one-time code from the web app or an operator command.
    Enrol {
        /// The code, XXXXX-XXXXX-XXXXX-XXXXX.
        #[arg(long, value_name = "CODE")]
        code: String,
        /// Replace an existing enrolment, keeping a backup of it.
        #[arg(long)]
        force: bool,
    },
    /// Connect to the control plane and stay connected.
    Run {
        /// Use N invented GPUs instead of NVML, for development and tests [default: 1].
        #[arg(long, value_name = "N", num_args = 0..=1, default_missing_value = "1")]
        fake_gpu: Option<u8>,
        /// Only with --fake-gpu: the model name the fake GPUs report [default: IdleO Fake GPU].
        #[arg(long, value_name = "NAME", requires = "fake_gpu")]
        fake_gpu_model: Option<String>,
        /// Only with --fake-gpu: each fake GPU's memory in MB, at least 1024 [default: 12282].
        #[arg(long, value_name = "MB", requires = "fake_gpu", value_parser = clap::value_parser!(i32).range(i64::from(FakeModel::MIN_VRAM_MB)..))]
        fake_gpu_vram_mb: Option<i32>,
        /// Only with --fake-gpu: report this much free disk in MB instead of the data folder's real free space, so a
        /// local stack can show a host without room for a session's files.
        #[arg(long, value_name = "MB", requires = "fake_gpu", value_parser = clap::value_parser!(i32).range(0..))]
        fake_free_disk_mb: Option<i32>,
        /// Run sessions with the development runner, which simulates jobs against real object storage.
        /// Only with --fake-gpu: a real host never runs it. Without a runner the agent declines offers.
        #[arg(long, requires = "fake_gpu")]
        dev_runner: bool,
    },
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    let config_path = cli.config.unwrap_or_else(config::default_path);
    let config = match Config::load(&config_path) {
        Ok(config) => config,
        Err(e) => return fail(exit::CONFIG, &e),
    };
    init_logging(config.log_format);
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(e) => return fail(exit::FAILURE, &e),
    };
    runtime.block_on(async move {
        match cli.command {
            Command::Link { name, api, no_browser, force, fake_gpu, fake_gpu_model, fake_gpu_vram_mb } => {
                let defaults = FakeModel::default();
                let model = FakeModel {
                    name: fake_gpu_model.unwrap_or(defaults.name),
                    vram_mb: fake_gpu_vram_mb.unwrap_or(defaults.vram_mb),
                };
                link_command(&config, name, api, no_browser, force, fake_gpu.map(|count| (count, model))).await
            }
            Command::Enrol { code, force } => enrol_command(&config, &code, force).await,
            Command::Run { fake_gpu, fake_gpu_model, fake_gpu_vram_mb, fake_free_disk_mb, dev_runner } => {
                let defaults = FakeModel::default();
                let model = FakeModel {
                    name: fake_gpu_model.unwrap_or(defaults.name),
                    vram_mb: fake_gpu_vram_mb.unwrap_or(defaults.vram_mb),
                };
                run_command(config, fake_gpu.map(|count| (count, model)), fake_free_disk_mb, dev_runner).await
            }
        }
    })
}

/// `idleo-agent link`: a thin wrapper over `idleo_agent::link::link` that prints its progress and maps how it ended to
/// an exit code (0 linked, 2 denied, 3 expired, 4 network; see the README).
async fn link_command(
    config: &Config,
    name: Option<String>,
    api: Option<String>,
    no_browser: bool,
    force: bool,
    fake_gpu: Option<(u8, FakeModel)>,
) -> ExitCode {
    let Some(api_url) = api.or_else(|| config.api_url.clone()) else {
        return fail(exit::CONFIG, &"no control plane address: set api_url in the config, or pass --api");
    };
    let gpus: Box<dyn GpuSource> = match fake_gpu {
        Some((count, model)) => {
            tracing::warn!(count, model = %model.name, vram_mb = model.vram_mb, "using fake GPUs, as asked by --fake-gpu");
            Box::new(FakeGpus::new(count, &model))
        }
        None => match telemetry::system() {
            Ok(source) => source,
            Err(e) => return fail(exit::GPU, &e),
        },
    };
    let gpus = match gpus.gpus() {
        Ok(gpus) => gpus
            .into_iter()
            .take(8)
            .map(|gpu| link::LinkGpu { model: gpu.name, vram_mb: gpu.vram_mb })
            .collect::<Vec<_>>(),
        Err(e) => return fail(exit::GPU, &e),
    };
    let (target, roots) = match connection_settings(config) {
        Ok(settings) => settings,
        Err(e) => return fail(exit::CONFIG, &e),
    };
    let client = match idleo_agent::storage::http_client() {
        Ok(client) => client,
        Err(e) => return fail(exit::FAILURE, &e),
    };
    let settings = link::Settings {
        api_url,
        machine: link::Machine {
            name: name.unwrap_or_else(link::local_machine_name),
            os: link::os_name().to_owned(),
            agent_version: idleo_agent::AGENT_VERSION.to_owned(),
            gpus,
        },
        target,
        roots,
        data_dir: config.data_dir.clone(),
        force,
        poll_interval: None,
    };
    let mut progress = |step: link::Progress| match step {
        link::Progress::Asking => {}
        link::Progress::ApproveAt { url, .. } => {
            if no_browser || !link::open_browser(&url) {
                println!("Open this link on any device to approve: {url}");
            }
        }
        link::Progress::Waiting => println!("Waiting for you to approve in the browser…"),
        link::Progress::Enrolling => println!("Approved. Linking this laptop…"),
    };
    match link::link(&settings, &client, &mut progress).await {
        Ok(linked) => {
            let who = linked.owner_hint.map_or_else(String::new, |hint| format!(" to {hint}…"));
            println!("Linked{who} as '{}'. You can close the browser.", linked.host_name);
            ExitCode::SUCCESS
        }
        Err(e) => fail(e.exit_code(), &e),
    }
}

async fn enrol_command(config: &Config, code: &str, force: bool) -> ExitCode {
    // Checked before anything else, and the input is never echoed: it's a secret.
    let code = match EnrolmentCode::parse(code) {
        Ok(code) => code,
        Err(e) => return fail(exit::MALFORMED_CODE, &e),
    };
    let (target, roots) = match connection_settings(config) {
        Ok(settings) => settings,
        Err(e) => return fail(exit::CONFIG, &e),
    };
    match enrol::enrol(&target, &roots, &config.data_dir, &code, force).await {
        Ok(enrolled) => {
            println!(
                "Enrolled as host {}. The certificate is valid until {}.",
                enrolled.host_id,
                state::format_time(enrolled.not_after)
            );
            if let Some(backup) = enrolled.backup {
                println!("The previous enrolment was moved to {}.", backup.display());
            }
            ExitCode::SUCCESS
        }
        Err(e) => fail(e.exit_code(), &e),
    }
}

async fn run_command(
    config: Config,
    fake_gpu: Option<(u8, FakeModel)>,
    fake_free_disk_mb: Option<i32>,
    dev_runner: bool,
) -> ExitCode {
    // clap already refuses --dev-runner alone; this keeps the rule even if the parser changes.
    if dev_runner && fake_gpu.is_none() {
        return fail(exit::CONFIG, &"--dev-runner needs --fake-gpu");
    }
    let runner: Option<Arc<dyn SessionRunner>> = if dev_runner {
        tracing::warn!("running sessions with the development runner, as asked by --dev-runner");
        Some(Arc::new(DevRunner))
    } else {
        None
    };
    let gpus: Arc<dyn GpuSource> = match fake_gpu {
        Some((count, model)) => {
            tracing::warn!(count, model = %model.name, vram_mb = model.vram_mb, "using fake GPUs, as asked by --fake-gpu");
            Arc::new(FakeGpus::new(count, &model))
        }
        None => match telemetry::system() {
            Ok(source) => Arc::from(source),
            Err(e) => return fail(exit::GPU, &e),
        },
    };
    let disk: Arc<dyn FreeDisk> = match fake_free_disk_mb {
        Some(mb) => {
            tracing::warn!(mb, "reporting fake free disk space, as asked by --fake-free-disk-mb");
            Arc::new(FixedDisk(mb))
        }
        None => Arc::new(SystemDisk::new(config.data_dir.clone())),
    };
    let (target, roots) = match connection_settings(&config) {
        Ok(settings) => settings,
        Err(e) => return fail(exit::CONFIG, &e),
    };
    let (stop, shutdown) = watch::channel(false);
    tokio::spawn(async move {
        wait_for_stop_request().await;
        tracing::info!("stopping");
        let _ = stop.send(true);
        tokio::time::sleep(SHUTDOWN_LIMIT).await;
        tracing::warn!("the stream didn't close within 5 seconds; exiting anyway");
        std::process::exit(0);
    });
    let options = RunOptions {
        target,
        roots,
        data_dir: config.data_dir,
        gpus,
        disk,
        owner: Arc::new(SystemOwner),
        lease_clock: Arc::new(SystemLeaseClock),
        runner,
        timing: Timing { welcome_timeout: config.welcome_timeout, ..Timing::default() },
        events: None,
    };
    match connection::run(options, shutdown).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => fail(e.exit_code(), &e),
    }
}

fn connection_settings(config: &Config) -> Result<(tls::Target, Arc<rustls::RootCertStore>), String> {
    let roots = pki::load_roots(&config.ca_root)?;
    let target = tls::Target::tcp(&config.control_plane.host, config.control_plane.port)?;
    Ok((target, roots))
}

#[cfg(unix)]
async fn wait_for_stop_request() {
    use tokio::signal::unix::{SignalKind, signal};
    match signal(SignalKind::terminate()) {
        Ok(mut terminate) => tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = terminate.recv() => {}
        },
        Err(_) => {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
}

#[cfg(windows)]
async fn wait_for_stop_request() {
    use tokio::signal::windows::{ctrl_close, ctrl_shutdown};
    match (ctrl_close(), ctrl_shutdown()) {
        (Ok(mut close), Ok(mut shutdown)) => tokio::select! {
            _ = tokio::signal::ctrl_c() => {}
            _ = close.recv() => {}
            _ = shutdown.recv() => {}
        },
        _ => {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
}

/// Logs go to stderr: the agent at info, libraries at warn, unless `IDLEO_LOG` says otherwise
/// (for example `IDLEO_LOG=debug` or `IDLEO_LOG=idleo_agent=debug,h2=info`).
fn init_logging(format: LogFormat) {
    let filter =
        std::env::var("IDLEO_LOG").ok().and_then(|value| Targets::from_str(&value).ok()).unwrap_or_else(|| {
            Targets::new().with_default(tracing::Level::WARN).with_target("idleo_agent", tracing::Level::INFO)
        });
    let registry = tracing_subscriber::registry().with(filter);
    let result = match format {
        LogFormat::Json => {
            registry.with(tracing_subscriber::fmt::layer().json().with_writer(std::io::stderr)).try_init()
        }
        // Colours only on a terminal, never in log files or a service's journal.
        LogFormat::Text => registry
            .with(
                tracing_subscriber::fmt::layer()
                    .with_ansi(std::io::stderr().is_terminal())
                    .with_writer(std::io::stderr),
            )
            .try_init(),
    };
    if let Err(e) = result {
        eprintln!("idleo-agent: logging couldn't start: {e}");
    }
}

fn fail(code: u8, error: &dyn std::fmt::Display) -> ExitCode {
    eprintln!("idleo-agent: {error}");
    ExitCode::from(code)
}
