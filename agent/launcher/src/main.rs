//! `idleo-launcher`: the Windows app's one entry point (ADR 0033).
//!
//! - `idleo-launcher start` (the Run entry, at sign-in): swaps in a staged launcher if there is one, then starts and
//!   watches `idleo-hostd` and `idleo-tray`.
//! - `idleo-launcher open [idleo:open/<tab>]` (the Start-menu shortcut, and a toast's click): starts the app if it isn't running,
//!   and has the tray show the IdleO window, on a tab if one is named.
//! - `idleo-launcher uninstall` (the Uninstall entry): the uninstaller.
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
        eprintln!("idleo-launcher runs only on Windows; its rules are tested on any system (cargo test -p launcher)");
        std::process::ExitCode::FAILURE
    }
}
