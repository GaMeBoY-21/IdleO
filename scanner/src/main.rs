//! `idleo-scanner`: `work` takes checks from the control plane until stopped (what the container runs); `check REFERENCE`
//! checks one image here and prints what the control plane would be told, with how long each step took (for measuring).
#![forbid(unsafe_code)]

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use clap::{Parser, Subcommand};
use idleo_scanner::check::{self, Ending, Job, Settings, Sink};
use idleo_scanner::control::{ControlPlane, Next, Phase, Refused, Resolution, Result_};
use idleo_scanner::guard::Allowed;
use idleo_scanner::layers::Limits;
use idleo_scanner::trivy;

#[derive(Parser)]
#[command(name = "idleo-scanner", version, about = "Checks renters' own container images before they reach a host")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Take checks from the control plane's internal API until stopped.
    Work,
    /// Check one image here and print the result.
    Check {
        /// The image reference.
        reference: String,
        /// The platform to check.
        #[arg(long, default_value = "linux/amd64")]
        platform: String,
    },
}

fn env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|value| !value.trim().is_empty())
}

fn env_u64(name: &str, default: u64) -> u64 {
    env(name).and_then(|value| value.trim().parse().ok()).unwrap_or(default)
}

/// Everything the scanner reads from its environment (scanner/README.md lists it).
fn settings() -> Settings {
    Settings {
        scratch: PathBuf::from(env("SCANNER_SCRATCH").unwrap_or_else(|| "/scratch".to_owned())),
        trivy: trivy::Settings {
            program: env("SCANNER_TRIVY").unwrap_or_else(|| "trivy".to_owned()),
            cache: env("SCANNER_TRIVY_CACHE").unwrap_or_else(|| "/cache/trivy".to_owned()),
            offline: env("SCANNER_TRIVY_OFFLINE").is_some_and(|v| v == "1" || v == "true"),
            timeout: Duration::from_secs(env_u64("SCANNER_TRIVY_TIMEOUT_SECONDS", 1800)),
        },
        allowed: Allowed::from_list(&env("SCANNER_ALLOW_PRIVATE").unwrap_or_default()),
        keep_free_bytes: env_u64("SCANNER_KEEP_FREE_BYTES", 2_000_000_000),
    }
}

#[tokio::main]
async fn main() -> std::process::ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();
    match Cli::parse().command {
        Command::Work => work().await,
        Command::Check { reference, platform } => one(reference, platform).await,
    }
}

async fn work() -> std::process::ExitCode {
    let Some(base) = env("SCANNER_CONTROL_PLANE").and_then(|url| url.parse().ok()) else {
        eprintln!("idleo-scanner: SCANNER_CONTROL_PLANE (the control plane's internal address) isn't set");
        return std::process::ExitCode::from(2);
    };
    let token = match env("SCANNER_TOKEN_FILE") {
        Some(path) => std::fs::read_to_string(path).ok().map(|t| t.trim().to_owned()),
        None => env("SCANNER_TOKEN"),
    };
    let Some(token) = token.filter(|t| t.len() >= 32) else {
        eprintln!(
            "idleo-scanner: SCANNER_TOKEN_FILE or SCANNER_TOKEN must hold the scanner's token (32 characters or more)"
        );
        return std::process::ExitCode::from(2);
    };
    let control = match ControlPlane::new(base, token) {
        Ok(control) => control,
        Err(e) => {
            eprintln!("idleo-scanner: {e}");
            return std::process::ExitCode::from(1);
        }
    };
    let settings = settings();
    let name = env("SCANNER_NAME").or_else(|| env("HOSTNAME")).unwrap_or_else(|| "scanner".to_owned());
    let poll = Duration::from_secs(env_u64("SCANNER_POLL_SECONDS", 5));
    tracing::info!(scanner = %name, scratch = %settings.scratch.display(), "taking checks");
    let mut backoff = poll;
    loop {
        let claim = tokio::select! {
            claim = control.claim(&name) => claim,
            _ = tokio::signal::ctrl_c() => return std::process::ExitCode::SUCCESS,
        };
        match claim {
            Ok(Some(claim)) => {
                backoff = poll;
                let job = Job {
                    id: claim.check_id.clone(),
                    reference: claim.reference.clone(),
                    login: claim.login.clone(),
                    platform: claim.platform.clone(),
                    limits: Limits {
                        layer_bytes: claim.max_layer_unpacked_bytes,
                        image_bytes: claim.max_image_unpacked_bytes,
                    },
                    deadline: Duration::from_secs(claim.deadline_seconds),
                };
                let started = Instant::now();
                let sink =
                    Remote { control: control.clone(), check: claim.check_id.clone(), claim: claim.claim.clone() };
                let ending = check::run(&job, &settings, &sink).await;
                tracing::info!(check = %claim.check_id, ?ending, seconds = started.elapsed().as_secs(), "a check ended");
            }
            Ok(None) => tokio::time::sleep(poll).await,
            Err(e) => {
                tracing::warn!(error = %e, "no claim; trying again");
                tokio::time::sleep(backoff).await;
                backoff = (backoff * 2).min(Duration::from_secs(120));
            }
        }
    }
}

/// The control plane, as a check's sink.
struct Remote {
    control: ControlPlane,
    check: String,
    claim: String,
}

impl Sink for Remote {
    async fn resolved(&self, resolution: &Resolution) -> Result<Next, String> {
        self.control.resolved(&self.check, &self.claim, resolution).await
    }
    async fn progress(&self, phase: Phase, done: u64, total: u64) -> Result<Next, String> {
        self.control.progress(&self.check, &self.claim, phase, done, total).await
    }
    async fn result(&self, result: &Result_) -> Result<(), String> {
        self.control.result(&self.check, &self.claim, result).await
    }
    async fn refused(&self, refused: &Refused) -> Result<(), String> {
        self.control.refused(&self.check, &self.claim, refused).await
    }
    async fn failed(&self, detail: &str) -> Result<(), String> {
        self.control.failed(&self.check, &self.claim, detail).await
    }
}

/// The terminal, as a check's sink: steps with their times, then the result. Its methods are async only because the trait's are.
struct Local {
    started: Instant,
    steps: Mutex<Vec<String>>,
}

impl Local {
    fn note(&self, what: &str) {
        let line = format!("{:>7.1} s  {what}", self.started.elapsed().as_secs_f64());
        eprintln!("{line}");
        self.steps.lock().unwrap_or_else(std::sync::PoisonError::into_inner).push(line);
    }
}

impl Sink for Local {
    fn resolved(&self, resolution: &Resolution) -> impl Future<Output = Result<Next, String>> + Send {
        self.note(&format!("resolved: {}", serde_json::to_string(resolution).unwrap_or_default()));
        std::future::ready(Ok(Next::Proceed))
    }
    fn progress(&self, phase: Phase, done: u64, total: u64) -> impl Future<Output = Result<Next, String>> + Send {
        self.note(&format!("{phase:?}: {} of {}", idleo_scanner::gigabytes(done), idleo_scanner::gigabytes(total)));
        std::future::ready(Ok(Next::Proceed))
    }
    fn result(&self, result: &Result_) -> impl Future<Output = Result<(), String>> + Send {
        self.note(&format!(
            "checked: {} ELF files, unpacked {}, shell {}, vulnerabilities {} critical / {} high, {} secret(s)",
            result.executables.len(),
            idleo_scanner::gigabytes(result.unpacked_bytes),
            result.has_shell,
            result.findings.critical,
            result.findings.high,
            result.findings.secrets.len()
        ));
        println!(
            "{}",
            serde_json::to_string_pretty(&serde_json::json!({
                "digest": result.digest,
                "config": result.config,
                "unpackedBytes": result.unpacked_bytes,
                "hasShell": result.has_shell,
                "executables": result.executables.len(),
                "critical": result.findings.critical,
                "high": result.findings.high,
                "secrets": result.findings.secrets,
            }))
            .unwrap_or_default()
        );
        std::future::ready(Ok(()))
    }
    fn refused(&self, refused: &Refused) -> impl Future<Output = Result<(), String>> + Send {
        self.note(&format!("refused ({}): {}", refused.reason, refused.detail));
        std::future::ready(Ok(()))
    }
    fn failed(&self, detail: &str) -> impl Future<Output = Result<(), String>> + Send {
        self.note(&format!("not checked: {detail}"));
        std::future::ready(Ok(()))
    }
}

async fn one(reference: String, platform: String) -> std::process::ExitCode {
    let settings = settings();
    let login = match (env("SCANNER_CHECK_USERNAME"), env("SCANNER_CHECK_TOKEN")) {
        (Some(username), Some(token)) => Some(idleo_scanner::registry::Login { username, token }),
        _ => None,
    };
    let job = Job {
        id: format!("local-{}", std::process::id()),
        reference,
        login,
        platform,
        limits: Limits {
            layer_bytes: env_u64("SCANNER_MAX_LAYER_UNPACKED_BYTES", 40_000_000_000),
            image_bytes: env_u64("SCANNER_MAX_IMAGE_UNPACKED_BYTES", 60_000_000_000),
        },
        deadline: Duration::from_secs(env_u64("SCANNER_DEADLINE_SECONDS", 3600)),
    };
    let sink = Local { started: Instant::now(), steps: Mutex::new(Vec::new()) };
    match check::run(&job, &settings, &sink).await {
        Ending::Checked => std::process::ExitCode::SUCCESS,
        Ending::Refused(_) => std::process::ExitCode::from(3),
        Ending::Stopped | Ending::Failed => std::process::ExitCode::from(1),
    }
}
