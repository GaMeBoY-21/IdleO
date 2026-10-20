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

/// In a development distribution on a Mac, the stack's `localhost` is the Mac's, not the distribution's: a `localhost=NAME` line in
/// the marker (`/etc/idleo/development`) sends storage requests for `localhost` to NAME (`host.docker.internal`). A release
/// distribution has no marker, so never does this.
fn development_localhost() -> Option<std::net::SocketAddr> {
    use std::net::ToSocketAddrs;
    let marker = std::fs::read_to_string(idleo_runner::nerdctl::DEVELOPMENT_MARKER).ok()?;
    let name = marker.lines().find_map(|line| line.strip_prefix("localhost="))?.trim();
    if name.is_empty() || !name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-')) {
        return None;
    }
    (name, 0).to_socket_addrs().ok()?.find(std::net::SocketAddr::is_ipv4)
}

async fn serve() -> Result<(), String> {
    tracing_subscriber::fmt().with_writer(std::io::stderr).with_ansi(false).init();
    let machine = Arc::new(Nerdctl::new(Paths::default()));
    machine.prepare_networks()?;
    machine.watch_oom_events();
    idleo_runner::machine::Machine::clean_up_leftovers(machine.as_ref()).await;
    let clock: Arc<dyn LeaseClock> = Arc::new(SystemLeaseClock);
    let local = development_localhost();
    let names: Vec<(&str, std::net::SocketAddr)> = local.iter().map(|address| ("localhost", *address)).collect();
    let http = jobcore::storage::http_client_resolving(&names)?;
    let storage = Arc::new(storage::S3Storage::new(http, Arc::clone(&clock)));
    let registry = session::Registry::new(machine, storage, clock);
    tracing::info!(version = env!("CARGO_PKG_VERSION"), rules = %&sandbox::hash()[..12], "idleo-runner is serving");
    server::serve(registry, &socket()).await.map_err(|e| format!("the socket failed: {e}"))
}
