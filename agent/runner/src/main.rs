//! `idleo-runner`: runs each renter's job in a container inside our sealed Linux distribution (ADR 0035). `serve` (the systemd
//! service), `attach` (the agent's transport), `load-rules` (what the sandbox's unit runs), `rules` (prints the rules as
//! loaded) and `version`. It runs only on Linux; the program exists on other systems only so the workspace builds there.

#[cfg(unix)]
mod unix_main;

#[cfg(unix)]
fn main() -> std::process::ExitCode {
    unix_main::run()
}

#[cfg(not(unix))]
fn main() -> std::process::ExitCode {
    eprintln!("idleo-runner runs only in our Linux distribution");
    std::process::ExitCode::from(2)
}
