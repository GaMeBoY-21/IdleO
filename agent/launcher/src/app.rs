//! The launcher on Windows: the three commands, a log file, one running launcher at a time, and the uninstaller's
//! task dialogs.

use std::ffi::OsString;
use std::path::Path;
use std::process::ExitCode;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use idleo_launcher::self_update::{self, SelfUpdate};
use idleo_launcher::supervise::{self, Supervisor, SystemProcesses};
use idleo_launcher::uninstall::{self, Shown, SystemPrograms};
use idleo_release::install::SystemMachine;
use idleo_release::paths::Layout;
use time::OffsetDateTime;

const STOP: i32 = 101;
const CANCEL: i32 = 102;

pub fn run() -> ExitCode {
    let args: Vec<OsString> = std::env::args_os().collect();
    let command = args.get(1).and_then(|a| a.to_str()).unwrap_or("start").to_owned();
    let layout = Layout::for_user();
    init_logging(&supervise::log_file(&layout));
    match command.as_str() {
        "start" => start(&layout, &args),
        "open" => open(&layout),
        "uninstall" => uninstall_command(&layout, args.get(2).and_then(|a| a.to_str()) == Some("--from-temp")),
        other => {
            tracing::error!(command = other, "unknown command");
            ExitCode::from(2)
        }
    }
}

fn init_logging(path: &Path) {
    if let Some(parent) = path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    if let Ok(file) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = tracing_subscriber::fmt().with_ansi(false).with_writer(Mutex::new(file)).try_init();
    }
}

/// Holds the lock that says a launcher is running; `None` if another one already holds it.
fn running_lock(layout: &Layout) -> Option<std::fs::File> {
    use std::os::windows::fs::OpenOptionsExt;
    let _ = std::fs::create_dir_all(layout.data());
    std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .share_mode(0)
        .open(layout.data().join("launcher.lock"))
        .ok()
}

fn start(layout: &Layout, args: &[OsString]) -> ExitCode {
    let running = std::env::current_exe().unwrap_or_else(|_| layout.launcher());
    match self_update::at_start(layout, &running) {
        Ok(SelfUpdate::Swapped) => {
            tracing::info!("a new launcher is in place; starting it");
            let _ = std::process::Command::new(layout.launcher()).args(&args[1..]).spawn();
            return ExitCode::SUCCESS;
        }
        Ok(SelfUpdate::None) => {}
        Err(e) => tracing::warn!(error = %e, "the staged launcher couldn't be swapped in; carrying on"),
    }
    let Some(_lock) = running_lock(layout) else {
        tracing::info!("a launcher is already running");
        return ExitCode::SUCCESS;
    };
    tracing::info!("starting IdleO");
    let mut supervisor = Supervisor::new(layout.clone(), SystemProcesses, OffsetDateTime::now_utc());
    loop {
        for event in supervisor.tick(OffsetDateTime::now_utc()) {
            tracing::info!(?event, "supervision");
        }
        std::thread::sleep(Duration::from_secs(1));
    }
}

fn open(layout: &Layout) -> ExitCode {
    if running_lock(layout).is_some() {
        let _ = std::process::Command::new(layout.launcher()).arg("start").spawn();
    }
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else {
        return ExitCode::FAILURE;
    };
    let url = runtime.block_on(async {
        for _ in 0..20 {
            if let Ok(mut client) = idleo_release::channel::connect()
                && let Ok(Ok(idleo_release::channel::Message::Status(status))) =
                    tokio::time::timeout(Duration::from_secs(2), client.next()).await
            {
                return status.app_url;
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        None
    });
    if let Some(url) = url {
        let _ = idleo_release::browser::open(&format!("{}/hosting/", url.trim_end_matches('/')));
    }
    ExitCode::SUCCESS
}

fn uninstall_command(layout: &Layout, from_temp: bool) -> ExitCode {
    let exe = std::env::current_exe().unwrap_or_else(|_| layout.launcher());
    if !from_temp {
        // Run from a copy in %TEMP%, so the programs folder can go entirely.
        let copy = std::env::temp_dir().join(format!("idleo-uninstall-{}.exe", std::process::id()));
        if std::fs::copy(&exe, &copy).is_ok()
            && std::process::Command::new(&copy).args(["uninstall", "--from-temp"]).spawn().is_ok()
        {
            return ExitCode::SUCCESS;
        }
        tracing::warn!("couldn't run the uninstaller from a copy; the launcher itself will be left");
    }
    let (decided, decision) = std::sync::mpsc::channel::<bool>();
    let view = Arc::new(Mutex::new(agent_windows::Progress {
        heading: "Uninstalling IdleO".to_owned(),
        text: "Getting ready.".to_owned(),
        percent: None,
        done: false,
    }));
    let worker_view = Arc::clone(&view);
    let worker = std::thread::spawn(move || {
        let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else { return };
        runtime.block_on(async move {
            let mut agent = idleo_release::channel::connect().ok();
            let running = match agent.as_mut() {
                Some(agent) => uninstall::session_running(agent).await,
                None => false,
            };
            if running {
                let answer = agent_windows::ask(
                    "Uninstall IdleO",
                    uninstall::ASK_HEADING,
                    uninstall::ASK_TEXT,
                    &[(STOP, uninstall::STOP_AND_UNINSTALL), (CANCEL, "Cancel")],
                );
                if !matches!(answer, Ok(Some(STOP))) {
                    let _ = decided.send(false);
                    return;
                }
            }
            let _ = decided.send(true);
            let machine = SystemMachine::new();
            let mut show = |shown: Shown| {
                let mut view = worker_view.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                view.heading = shown.heading;
                view.text = shown.text;
                view.percent = Some(shown.percent);
            };
            let finished = uninstall::uninstall(&machine, agent.as_mut(), &SystemPrograms, &mut show).await;
            tracing::info!(told = finished.told, problems = ?finished.problems, "uninstalled");
            worker_view.lock().unwrap_or_else(std::sync::PoisonError::into_inner).done = true;
        });
    });
    if decision.recv() == Ok(true)
        && let Err(e) = agent_windows::progress("Uninstall IdleO", "Close", &view)
    {
        tracing::error!(error = %e, "the progress dialog couldn't be shown");
    }
    let _ = worker.join();
    if from_temp {
        delete_after_exit(&exe);
    }
    ExitCode::SUCCESS
}

/// Deletes this copy of the uninstaller a moment after it exits.
fn delete_after_exit(exe: &Path) {
    let command = format!("ping 127.0.0.1 -n 3 > nul & del /f /q \"{}\"", exe.display());
    let mut cmd = std::process::Command::new("cmd");
    cmd.args(["/c", &command]);
    {
        use std::os::windows::process::CommandExt;
        /// `CREATE_NO_WINDOW`.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let _ = cmd.spawn();
}
