//! `IdleO-Setup-Demo.exe`: setup's window with its screens and pretend progress, for showing people the app. It changes nothing
//! on the PC: no WSL, no downloads, no network, no files, no registry (ADR 0061, `idleo_setup::demo`).
#![forbid(unsafe_code)]
#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
#[path = "ui.rs"]
mod ui;
#[cfg(windows)]
#[path = "windows_system.rs"]
mod windows_system;

fn main() -> std::process::ExitCode {
    #[cfg(windows)]
    {
        ui::run(ui::Mode { demo: true, ..ui::Mode::from_args() })
    }
    #[cfg(not(windows))]
    {
        eprintln!(
            "IdleO-Setup-Demo runs only on Windows; its screens are in the browser preview (cargo run -p setup --example preview)"
        );
        std::process::ExitCode::FAILURE
    }
}
