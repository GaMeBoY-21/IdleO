//! `idleo-hostd`: `idleo-agent run` as the Windows app's background host, started by the launcher. It is a Windows GUI
//! program, so no console window ever flashes up, and its logs go to the app's logs folder. Everything else is the
//! agent's own command line (ADR 0033).
#![forbid(unsafe_code)]
#![cfg_attr(windows, windows_subsystem = "windows")]

use std::ffi::OsString;

fn main() -> std::process::ExitCode {
    let log = idleo_release::paths::Layout::for_user().logs().join("hostd.log");
    let mut args: Vec<OsString> = vec!["idleo-hostd".into(), "--log-file".into(), log.into_os_string(), "run".into()];
    args.extend(std::env::args_os().skip(1));
    idleo_agent::app::main(args)
}
