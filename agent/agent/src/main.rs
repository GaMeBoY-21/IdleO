//! `idleo-agent`: the host agent's command line.
#![forbid(unsafe_code)]

fn main() -> std::process::ExitCode {
    idleo_agent::app::main(std::env::args_os())
}
