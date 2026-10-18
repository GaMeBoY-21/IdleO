//! `IdleO-Setup.exe`: the one download (ADR 0033).
#![forbid(unsafe_code)]
#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
mod ui;
#[cfg(windows)]
mod windows_system;

fn main() -> std::process::ExitCode {
    #[cfg(windows)]
    {
        ui::run()
    }
    #[cfg(not(windows))]
    {
        eprintln!("IdleO-Setup runs only on Windows; its steps are tested on any system (cargo test -p setup)");
        std::process::ExitCode::FAILURE
    }
}
