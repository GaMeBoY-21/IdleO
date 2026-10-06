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
use idleo_agent::enrol::{self, EnrolmentCode};
use idleo_agent::owner::SystemOwner;
use idleo_agent::telemetry::{self, FakeGpus, GpuSource};
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
    /// Enrol this machine with the one-time code from the web app.
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
            Command::Enrol { code, force } => enrol_command(&config, &code, force).await,
            Command::Run { fake_gpu } => run_command(config, fake_gpu).await,
        }
    })
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

async fn run_command(config: Config, fake_gpu: Option<u8>) -> ExitCode {
    let gpus: Arc<dyn GpuSource> = match fake_gpu {
        Some(count) => {
            tracing::warn!(count, "using fake GPUs, as asked by --fake-gpu");
            Arc::new(FakeGpus::new(count))
        }
        None => match telemetry::system() {
            Ok(source) => Arc::from(source),
            Err(e) => return fail(exit::GPU, &e),
        },
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
        owner: Arc::new(SystemOwner),
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
