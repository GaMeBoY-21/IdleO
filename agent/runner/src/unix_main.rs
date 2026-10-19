//! `idleo-runner`: `serve` (the systemd service), `attach` (the agent's transport), `load-rules` (what the sandbox's unit
//! runs), `rules` (prints the rules as loaded) and `version`.

use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;

use idleo_runner::nerdctl::{Nerdctl, Paths};
use idleo_runner::{attach, sandbox, server, session, storage};
use jobcore::lease_clock::{LeaseClock, SystemLeaseClock};

/// Where the runner listens.
const SOCKET: &str = "/run/idleo/runner.sock";

fn socket() -> PathBuf {
    std::env::var_os("IDLEO_RUNNER_SOCKET").map_or_else(|| PathBuf::from(SOCKET), PathBuf::from)
}

#[tokio::main]
pub async fn run() -> ExitCode {
    let command = std::env::args().nth(1).unwrap_or_default();
    match command.as_str() {
        "serve" => report(serve().await),
        "attach" => report(attach::attach(&socket()).await.map_err(|e| format!("the runner can't be reached: {e}"))),
        "load-rules" => report(Nerdctl::load_rules().await),
        "rules" => {
            print!("{}", sandbox::rendered());
            ExitCode::SUCCESS
        }
        "version" | "--version" => {
            println!("idleo-runner {}", env!("CARGO_PKG_VERSION"));
            ExitCode::SUCCESS
        }
        _ => {
            eprintln!("usage: idleo-runner serve | attach | load-rules | rules | version");
            ExitCode::from(2)
        }
    }
}

fn report(result: Result<(), String>) -> ExitCode {
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(why) => {
            eprintln!("idleo-runner: {why}");
            ExitCode::FAILURE
        }
    }
}

async fn serve() -> Result<(), String> {
    tracing_subscriber::fmt().with_writer(std::io::stderr).with_ansi(false).init();
    let machine = Arc::new(Nerdctl::new(Paths::default()));
    machine.prepare_networks()?;
    machine.watch_oom_events();
    idleo_runner::machine::Machine::clean_up_leftovers(machine.as_ref()).await;
    let clock: Arc<dyn LeaseClock> = Arc::new(SystemLeaseClock);
    let http = jobcore::storage::http_client()?;
    let storage = Arc::new(storage::S3Storage::new(http, Arc::clone(&clock)));
    let registry = session::Registry::new(machine, storage, clock);
    tracing::info!(version = env!("CARGO_PKG_VERSION"), rules = %&sandbox::hash()[..12], "idleo-runner is serving");
    server::serve(registry, &socket()).await.map_err(|e| format!("the socket failed: {e}"))
}
