//! The agent's command line, shared by `idleo-agent` (a console program) and `idleo-hostd` (the same `run` with no
//! console window, which the Windows app's launcher starts): every line of logic is here, once (ADR 0033).

use std::ffi::OsString;
use std::io::IsTerminal;
use std::path::PathBuf;
use std::process::ExitCode;
use std::str::FromStr;
use std::sync::Arc;
use std::time::Duration;

use clap::{Parser, Subcommand};
use idleo_release::paths::Layout;
use idleo_release::wsl::SystemRunner;

use crate::config::{self, Config, LogFormat};
use crate::connection::{self, RunOptions, Timing};
use crate::disk::{FixedDisk, FreeDisk, SystemDisk};
use crate::enrol::{self, EnrolmentCode};
use crate::host::Host;
use crate::lease_clock::{LeaseClock, SystemLeaseClock};
use crate::local::SelfTestFn;
use crate::owner::SystemOwner;
use crate::runner::SessionRunner;
use crate::runner::container::{ContainerRunner, RunnerDistribution};
use crate::runner::dev::DevRunner;
use crate::self_test::{self, Environment};
use crate::speed::StorageSpeed;
use crate::telemetry::{self, FakeGpus, FakeModel, GpuSource};
use crate::updater::{self, Updater};
use crate::{link, pki, state, tls};
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
    /// `self-test` ran, and a check failed.
    pub const SELF_TEST_FAILED: u8 = 9;
}

/// The hard limit on shutdown after Ctrl-C or a stop request.
const SHUTDOWN_LIMIT: Duration = Duration::from_secs(5);

#[derive(Parser)]
#[command(name = "idleo-agent", version, about = "The IdleO host agent")]
struct Cli {
    /// Write logs to this file instead of stderr (`idleo-hostd`, which has no console, writes to the app's logs folder).
    #[arg(long, global = true, value_name = "PATH")]
    log_file: Option<PathBuf>,
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
        /// Run sessions in real containers, through the job runner of a development distribution: `docker:<container>`
        /// (see scripts/dev-distro.sh). Only with --fake-gpu: a real host reaches its own distribution through `wsl.exe`.
        #[arg(long, value_name = "docker:CONTAINER", requires = "fake_gpu", conflicts_with = "dev_runner")]
        container_runner: Option<String>,
    },
    /// Check this machine can host: our distribution, its lockdown, the GPU inside it and in a container, and the
    /// disk. Exits 0 if every check passed, 9 if one failed.
    SelfTest {
        /// Leave out the slow checks (the GPU in a container and the lockdown), as an update's trial does.
        #[arg(long)]
        quick: bool,
        /// Print the results as JSON, for the setup app and the updater.
        #[arg(long)]
        json: bool,
        /// Check this distro version instead of the one in use.
        #[arg(long, value_name = "VERSION")]
        distro: Option<String>,
        /// Skip every check, as on a machine with invented GPUs.
        #[arg(long)]
        fake_gpu: bool,
        /// Also collect environment facts (WSL, kernel, driver, memory, what the distribution can reach), for setup to
        /// send with its report. Development builds only: a release build collects nothing.
        #[arg(long)]
        facts: bool,
        /// With --facts: our storage's HOST:PORT, for the reachability fact.
        #[arg(long, value_name = "HOST:PORT", requires = "facts")]
        storage: Option<String>,
    },
}

/// Runs the command line with these arguments (the first is the program's name).
pub fn main(args: impl IntoIterator<Item = OsString>) -> ExitCode {
    let cli = Cli::parse_from(args);
    let config_path = cli.config.unwrap_or_else(config::default_path);
    let config = match Config::load(&config_path) {
        Ok(config) => config,
        Err(e) => return fail(exit::CONFIG, &e),
    };
    init_logging(config.log_format, cli.log_file.as_deref());
    if let Err(e) = idleo_release::keys::check_control_plane(&config.control_plane.host) {
        return fail(exit::CONFIG, &e);
    }
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
            Command::Run {
                fake_gpu,
                fake_gpu_model,
                fake_gpu_vram_mb,
                fake_free_disk_mb,
                dev_runner,
                container_runner,
            } => {
                let defaults = FakeModel::default();
                let model = FakeModel {
                    name: fake_gpu_model.unwrap_or(defaults.name),
                    vram_mb: fake_gpu_vram_mb.unwrap_or(defaults.vram_mb),
                };
                run_command(
                    config,
                    fake_gpu.map(|count| (count, model)),
                    fake_free_disk_mb,
                    dev_runner,
                    container_runner,
                )
                .await
            }
            Command::SelfTest { quick, json, distro, fake_gpu, facts, storage } => {
                self_test_command(&config, SelfTestOptions { quick, json, distro, fake: fake_gpu, facts, storage })
                    .await
            }
        }
    })
}

/// What the self-test looks at on this machine. `skip` says why every check is skipped (Linux, macOS, fake GPUs).
fn environment(config: &Config, gpus: Arc<dyn GpuSource>, distro: Option<String>, fake: bool) -> Environment {
    let layout = Layout::for_user();
    let skip = if fake {
        Some("this machine has invented GPUs".to_owned())
    } else if !cfg!(windows) {
        Some("not a Windows host".to_owned())
    } else {
        None
    };
    let distro_version = distro.or_else(|| Updater::current_distro(&layout));
    let disk_dir = distro_version.as_deref().map_or_else(|| config.data_dir.clone(), |version| layout.wsl_dir(version));
    Environment {
        wsl: Arc::new(SystemRunner),
        distro_version,
        gpus,
        disk_dir,
        free_bytes: crate::disk::available_bytes,
        skip,
    }
}

/// What `idleo-agent self-test` was asked.
#[allow(clippy::struct_excessive_bools)] // The command line's own switches, each its own.
struct SelfTestOptions {
    quick: bool,
    json: bool,
    distro: Option<String>,
    fake: bool,
    /// Collect environment facts too (a development build only).
    facts: bool,
    /// Our storage's `host:port`, for the reachability fact.
    storage: Option<String>,
}

/// `idleo-agent self-test`: prints each check (or JSON) and exits 0 if every one passed, 9 if not.
async fn self_test_command(config: &Config, options: SelfTestOptions) -> ExitCode {
    let SelfTestOptions { quick, json, distro, fake, facts, storage } = options;
    let gpus: Arc<dyn GpuSource> = if fake || !cfg!(windows) {
        Arc::new(FakeGpus::new(1, &FakeModel::default()))
    } else {
        match telemetry::system() {
            Ok(source) => Arc::from(source),
            Err(e) => return fail(exit::GPU, &e),
        }
    };
    let environment = environment(config, gpus, distro, fake);
    let mut report = self_test::run(&environment, quick, None).await;
    if facts {
        // Facts, not checks: they never change whether the test passed.
        report.facts = crate::facts::collect(idleo_release::keys::DEV_BUILD, &environment, storage.as_deref());
    }
    if json {
        match serde_json::to_string(&report) {
            Ok(text) => println!("{text}"),
            Err(e) => return fail(exit::FAILURE, &e),
        }
    } else {
        for check in &report.checks {
            println!(
                "{} {:<18} {} ({} ms)",
                if check.passed { "PASS" } else { "FAIL" },
                check.name,
                check.detail,
                check.took_ms
            );
        }
        for fact in &report.facts {
            println!("FACT {:<18} {}", fact.name, fact.value);
        }
    }
    if report.passed { ExitCode::SUCCESS } else { ExitCode::from(exit::SELF_TEST_FAILED) }
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
    let client = match crate::storage::http_client() {
        Ok(client) => client,
        Err(e) => return fail(exit::FAILURE, &e),
    };
    let settings = link::Settings {
        api_url,
        machine: link::Machine {
            name: name.unwrap_or_else(link::local_machine_name),
            os: link::os_name().to_owned(),
            agent_version: crate::AGENT_VERSION.to_owned(),
            gpus,
            install_id: None,
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

/// The hub for `run`, watching the signed-in owner. The GPU's use counts for auto-pause only with real GPUs on Windows.
fn owner_host(config: &Config, runner_installed: bool, fake: bool) -> Arc<Host> {
    let host =
        Host::with_app_url(Arc::new(SystemOwner), config.data_dir.clone(), runner_installed, config.app_url.clone());
    if fake || !cfg!(windows) {
        host.ignore_gpu_use();
    }
    host
}

#[allow(clippy::too_many_lines)] // The agent's start-up wiring, in the order things must come up.
async fn run_command(
    config: Config,
    fake_gpu: Option<(u8, FakeModel)>,
    fake_free_disk_mb: Option<i32>,
    dev_runner: bool,
    container_runner: Option<String>,
) -> ExitCode {
    let fake_gpu_given = fake_gpu.is_some();
    // clap already refuses --dev-runner alone; this keeps the rule even if the parser changes.
    if dev_runner && fake_gpu.is_none() {
        return fail(exit::CONFIG, &"--dev-runner needs --fake-gpu");
    }
    let containers: Option<Arc<ContainerRunner>> = match container_runner {
        Some(spec) => {
            let Some(container) = spec.strip_prefix("docker:").filter(|name| !name.is_empty()) else {
                return fail(exit::CONFIG, &"--container-runner takes docker:<container>");
            };
            if !fake_gpu_given {
                return fail(exit::CONFIG, &"--container-runner needs --fake-gpu");
            }
            tracing::warn!(
                container,
                "running sessions in containers of a development distribution, as asked by --container-runner"
            );
            Some(Arc::new(ContainerRunner::docker(container)))
        }
        // A real host on Windows: its own distribution, through wsl.exe (ADR 0035).
        None if !fake_gpu_given && !dev_runner && cfg!(windows) => Updater::current_distro(&Layout::for_user())
            .and_then(|version| ContainerRunner::wsl(&idleo_release::manifest::distro_name(&version)).ok())
            .map(Arc::new),
        None => None,
    };
    let runner: Option<Arc<dyn SessionRunner>> = if dev_runner {
        tracing::warn!("running sessions with the development runner, as asked by --dev-runner");
        Some(Arc::new(DevRunner))
    } else {
        containers.clone().map(|runner| runner as Arc<dyn SessionRunner>)
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
    let stopping = stop.clone();
    tokio::spawn(async move {
        wait_for_stop_request().await;
        tracing::info!("stopping");
        let _ = stopping.send(true);
        tokio::time::sleep(SHUTDOWN_LIMIT).await;
        tracing::warn!("the stream didn't close within 5 seconds; exiting anyway");
        std::process::exit(0);
    });
    let fake = fake_gpu_given;
    // A container runner counts as installed once it has answered with the sandbox's rules loaded, not before.
    let host = owner_host(&config, runner.is_some() && containers.is_none(), fake);
    if let Some(containers) = containers.clone() {
        let host = Arc::clone(&host);
        tokio::spawn(async move {
            for attempt in 1..=6_u64 {
                match containers.hello().await {
                    Ok(hello) if hello.rules_loaded => {
                        tracing::info!(runner = %hello.runner_version, distro = %hello.distro_version, gpu = hello.gpu, "the job runner answers with the sandbox's rules loaded");
                        host.set_runner_installed(true);
                        return;
                    }
                    Ok(_) => tracing::warn!("the job runner answers, but the sandbox's network rules aren't loaded"),
                    Err(failure) => tracing::warn!(%failure, "the job runner doesn't answer yet"),
                }
                tokio::time::sleep(Duration::from_secs(5 * attempt)).await;
            }
            host.set_runner_installed(false);
        });
    }
    let lease_clock: Arc<dyn LeaseClock> = Arc::new(SystemLeaseClock);
    let http = match crate::storage::http_client() {
        Ok(client) => client,
        Err(e) => return fail(exit::FAILURE, &e),
    };
    let environment = Arc::new(environment(&config, Arc::clone(&gpus), None, fake));
    let speed = Arc::new(StorageSpeed::new(Arc::clone(&host), http.clone(), Arc::clone(&lease_clock)));
    let self_test: SelfTestFn = Arc::new(move |quick| {
        let environment = Arc::clone(&environment);
        let speed = Arc::clone(&speed);
        Box::pin(async move { self_test::run(&environment, quick, Some(speed.as_ref())).await })
    });
    // Updates only where the Windows app installed the agent: its launcher runs the trial.
    let layout = Layout::for_user();
    let (restart, mut restarting) = watch::channel(false);
    let updater = (cfg!(windows) && idleo_release::apps::Apps::new(layout.clone()).current().is_some()).then(|| {
        Updater::new(
            layout,
            Arc::clone(&host),
            http,
            Arc::new(SystemRunner),
            idleo_release::keys::RELEASE_KEY,
            crate::AGENT_VERSION,
            restart,
            Updater::fail_trial_built_in(),
        )
    });
    tokio::spawn(async move {
        if restarting.wait_for(|restart| *restart).await.is_ok() {
            tracing::info!("restarting into the new version");
            let _ = stop.send(true);
        }
    });
    // Keeping the distribution ready and light (ADR 0035): job images downloaded before they're needed, the distribution
    // stopped when it has been idle for a while. On a laptop `wsl --terminate` stops it; the Mac's stand-in is left running.
    let care = containers.clone().map(|runner| {
        let distro = if cfg!(windows) { Updater::current_distro(&Layout::for_user()) } else { None };
        let stop = distro
            .as_deref()
            .and_then(|version| RunnerDistribution::terminate(&idleo_release::manifest::distro_name(version)).ok());
        crate::care::Care {
            distribution: Arc::new(RunnerDistribution::new(runner, stop)),
            metered: Arc::new(crate::care::SystemMetered),
            data_dir: config.data_dir.clone(),
            distro_version: distro.unwrap_or_else(|| "development".to_owned()),
            largest_gpu_vram_mb: 0,
            timing: crate::care::Timing::default(),
        }
    });
    let options = RunOptions {
        target,
        roots,
        data_dir: config.data_dir,
        gpus,
        disk,
        host,
        updater: updater.clone(),
        self_test: Some(self_test),
        lease_clock,
        runner,
        care,
        timing: Timing { welcome_timeout: config.welcome_timeout, ..Timing::default() },
        events: None,
    };
    let restarting = updater.map(|updater| updater.restarting());
    match connection::run(options, shutdown).await {
        Ok(()) if restarting.is_some_and(|flag| *flag.borrow()) => ExitCode::from(updater::RESTART_FOR_UPDATE),
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

/// Logs go to stderr, or to `log_file` (appended to): the agent at info, libraries at warn, unless `IDLEO_LOG` says
/// otherwise (for example `IDLEO_LOG=debug` or `IDLEO_LOG=idleo_agent=debug,h2=info`).
fn init_logging(format: LogFormat, log_file: Option<&std::path::Path>) {
    let filter =
        std::env::var("IDLEO_LOG").ok().and_then(|value| Targets::from_str(&value).ok()).unwrap_or_else(|| {
            Targets::new().with_default(tracing::Level::WARN).with_target("idleo_agent", tracing::Level::INFO)
        });
    let registry = tracing_subscriber::registry().with(filter);
    if let Some(path) = log_file {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        match std::fs::OpenOptions::new().create(true).append(true).open(path) {
            Ok(file) => {
                let file = std::sync::Mutex::new(file);
                let result =
                    registry.with(tracing_subscriber::fmt::layer().with_ansi(false).with_writer(file)).try_init();
                if let Err(e) = result {
                    eprintln!("idleo-agent: logging couldn't start: {e}");
                }
                return;
            }
            Err(e) => eprintln!("idleo-agent: {}: {e}; logging to stderr", path.display()),
        }
    }
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
