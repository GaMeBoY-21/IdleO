//! `idleo-tray`: the Windows app's tray icon, started and watched by the launcher (ADR 0033).
#![forbid(unsafe_code)]
#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
mod app;

fn main() -> std::process::ExitCode {
    #[cfg(windows)]
    {
        app::run()
    }
    #[cfg(not(windows))]
    {
        eprintln!("idleo-tray runs only on Windows; its menu and words are tested on any system (cargo test -p tray)");
        std::process::ExitCode::FAILURE
    }
}
