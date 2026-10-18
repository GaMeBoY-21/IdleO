//! Uninstalling (ADR 0033): Settings → Apps → IdleO → Uninstall runs `idleo-launcher uninstall`, which shows a small
//! native dialog, no web view.
//!
//! 1. If a renter's job is running, it asks first: "A renter's job is running. It'll save its progress and stop within
//!    2 minutes." On "Stop it and uninstall", the agent stops the job the way it does when its owner comes back.
//! 2. The agent tells IdleO (`Leave`), which removes the host and revokes its certificate. If IdleO can't be reached
//!    in 15 seconds, the uninstall carries on, and says the machine shows offline on the web, where its owner can
//!    remove it.
//! 3. The tray and the agent stop.
//! 4. Our WSL distributions are unregistered, only ours; then the registry entries, the shortcut and both folders
//!    go ([`idleo_release::install::remove_all`]).
//!
//! The steps are here, against traits; the dialogs are the Windows program's.

use std::time::Duration;

use idleo_release::channel::{Client, Message, Request, UninstallStep};
use idleo_release::install::{self, Machine};
use tokio::io::{AsyncBufRead, AsyncWrite};

/// The question while a renter's job runs.
pub const ASK_HEADING: &str = "A renter's job is running";
/// What it says.
pub const ASK_TEXT: &str = "It'll save its progress and stop within 2 minutes.";
/// The button that goes ahead.
pub const STOP_AND_UNINSTALL: &str = "Stop it and uninstall";
/// The last screen's heading.
pub const REMOVED: &str = "IdleO is removed.";
/// The last screen's text.
pub const NOTHING_LEFT: &str = "Nothing is left on your laptop. (WSL stays: it's part of Windows.)";
/// Added when IdleO couldn't be told.
pub const NOT_TOLD: &str =
    "IdleO couldn't be reached, so this laptop shows offline on the web. You can remove it there.";

/// How long to wait for the agent's first status.
const STATUS_WAITS: Duration = Duration::from_secs(5);
/// How long the agent may take to stop a job and say goodbye (2 minutes to stop, 15 seconds to leave, and slack).
const GOODBYE_WAITS: Duration = Duration::from_secs(180);

/// What the dialog shows as it goes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shown {
    /// The bold line.
    pub heading: String,
    /// The line under it.
    pub text: String,
    /// How far, 0 to 100.
    pub percent: u32,
}

fn shown(heading: &str, text: &str, percent: u32) -> Shown {
    Shown { heading: heading.to_owned(), text: text.to_owned(), percent }
}

/// Stops the app's running programs (not the uninstaller itself).
pub trait Programs {
    /// Stops the tray, the agent and any launcher.
    fn stop_all(&self);
}

/// How the uninstall ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Finished {
    /// Whether IdleO removed the host.
    pub told: bool,
    /// Our distributions it removed.
    pub distros: Vec<String>,
    /// What couldn't be removed; empty when everything went.
    pub problems: Vec<String>,
    /// The last screen.
    pub screen: Shown,
}

/// Whether a renter's job is running, from the agent's first status; false if it doesn't say in time.
pub async fn session_running<R, W>(agent: &mut Client<R, W>) -> bool
where
    R: AsyncBufRead + Unpin,
    W: AsyncWrite + Unpin,
{
    tokio::time::timeout(STATUS_WAITS, async {
        loop {
            match agent.next().await {
                Ok(Message::Status(status)) => return status.session_running,
                Ok(_) => {}
                Err(_) => return false,
            }
        }
    })
    .await
    .unwrap_or(false)
}

/// Says goodbye through the agent, if it's running: it stops a job after its checkpoint, then tells IdleO. Returns
/// whether IdleO removed the host.
async fn goodbye<R, W>(agent: Option<&mut Client<R, W>>, progress: &mut dyn FnMut(Shown)) -> bool
where
    R: AsyncBufRead + Unpin,
    W: AsyncWrite + Unpin,
{
    let Some(agent) = agent else { return false };
    let Ok(id) = agent.send(&Request::PrepareUninstall).await else { return false };
    let waited = tokio::time::timeout(GOODBYE_WAITS, async {
        loop {
            match agent.next().await {
                Ok(Message::Uninstall { id: got, step }) if got == id => match step {
                    UninstallStep::StoppingSession => {
                        progress(shown("Stopping the renter's job", "It's saving its progress first.", 10));
                    }
                    UninstallStep::Leaving => {
                        progress(shown("Telling IdleO", "Removing this laptop from your account.", 40));
                    }
                    UninstallStep::Left => return true,
                    UninstallStep::Unreachable => return false,
                },
                Ok(_) => {}
                Err(_) => return false,
            }
        }
    })
    .await;
    waited.unwrap_or(false)
}

/// The uninstall after any question: the goodbye, stopping the programs, and removing everything of ours.
pub async fn uninstall<R, W>(
    machine: &dyn Machine,
    agent: Option<&mut Client<R, W>>,
    programs: &dyn Programs,
    progress: &mut dyn FnMut(Shown),
) -> Finished
where
    R: AsyncBufRead + Unpin,
    W: AsyncWrite + Unpin,
{
    progress(shown("Uninstalling IdleO", "Getting ready.", 5));
    let told = goodbye(agent, progress).await;
    progress(shown("Uninstalling IdleO", "Stopping IdleO.", 60));
    programs.stop_all();
    progress(shown("Uninstalling IdleO", "Removing IdleO's Linux space and files.", 75));
    let removal = install::remove_all(machine, None);
    let mut text = NOTHING_LEFT.to_owned();
    if !told {
        text = format!("{text} {NOT_TOLD}");
    }
    if !removal.problems.is_empty() {
        text = format!(
            "Some of IdleO couldn't be removed: {}. {}",
            removal.problems.join("; "),
            if told { "" } else { NOT_TOLD }
        );
    }
    let screen = Shown { heading: REMOVED.to_owned(), text: text.trim().to_owned(), percent: 100 };
    progress(screen.clone());
    Finished { told, distros: removal.distros, problems: removal.problems, screen }
}

/// Stops the app's programs with `taskkill`, leaving the uninstaller (which runs as a copy with another name) alone.
#[derive(Debug, Default)]
pub struct SystemPrograms;

impl Programs for SystemPrograms {
    fn stop_all(&self) {
        for program in [
            idleo_release::paths::LAUNCHER_EXE,
            idleo_release::paths::TRAY_EXE,
            idleo_release::paths::HOSTD_EXE,
            idleo_release::paths::AGENT_EXE,
        ] {
            let image = idleo_release::paths::exe(program);
            let _ = idleo_release::wsl::Runner::run(
                &idleo_release::wsl::SystemRunner,
                "taskkill",
                &["/F", "/T", "/IM", &image],
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use std::cell::RefCell;
    use std::path::Path;

    use idleo_release::apps::Apps;
    use idleo_release::channel::{
        AutoPause, LinkState, State, Status, UpdatePhase, decode_request, encode_message, read_line,
    };
    use idleo_release::install::fake::FakeMachine;
    use idleo_release::install::{leftovers, register};
    use idleo_release::paths;
    use idleo_release::update_state::{Outcome, UpdateState};
    use idleo_release::wsl::Wsl;
    use tokio::io::{AsyncWriteExt, BufReader, DuplexStream, ReadHalf, WriteHalf};

    use super::*;

    #[derive(Default)]
    struct FakePrograms(RefCell<u32>);

    impl Programs for FakePrograms {
        fn stop_all(&self) {
            *self.0.borrow_mut() += 1;
        }
    }

    fn status(session_running: bool) -> Status {
        Status {
            state: State::Available,
            reason: None,
            resumes_at: None,
            paused_until: None,
            earned_today_paise: Some(0),
            earning_now_paise: None,
            session_running,
            link_state: LinkState::Active,
            server_reachable: true,
            update: UpdatePhase::Idle,
            runner_installed: false,
            dev_build: true,
            auto_pause: AutoPause::default(),
            app_url: None,
        }
    }

    type FakeClient = Client<BufReader<ReadHalf<DuplexStream>>, WriteHalf<DuplexStream>>;

    /// A fake agent that pushes its status, then answers an uninstall with these steps.
    fn agent(session_running: bool, steps: Vec<UninstallStep>) -> FakeClient {
        let (ours, theirs) = tokio::io::duplex(1 << 16);
        let (their_read, mut their_write) = tokio::io::split(theirs);
        tokio::spawn(async move {
            their_write
                .write_all(encode_message(&Message::Status(status(session_running))).unwrap().as_bytes())
                .await
                .unwrap();
            let mut their_read = BufReader::new(their_read);
            let Ok(line) = read_line(&mut their_read).await else { return };
            let (id, request) = decode_request(&line).unwrap();
            assert_eq!(request, Request::PrepareUninstall);
            for step in steps {
                their_write
                    .write_all(encode_message(&Message::Uninstall { id, step }).unwrap().as_bytes())
                    .await
                    .unwrap();
            }
            // Stay open, as the agent does until the launcher stops it.
            let _ = read_line(&mut their_read).await;
        });
        let (read, write) = tokio::io::split(ours);
        Client::new(BufReader::new(read), write)
    }

    /// An installed app: two versions, the registry, the shortcut, our distribution, and an owner's own Ubuntu.
    fn installed(linked: bool, mid_update: bool) -> FakeMachine {
        let machine = FakeMachine::new();
        machine.wsl.add_foreign("Ubuntu");
        let layout = machine.layout.clone();
        std::fs::create_dir_all(layout.programs()).unwrap();
        let apps = Apps::new(layout.clone());
        for version in ["0.1.0", "0.2.0"] {
            std::fs::create_dir_all(layout.app_dir(version)).unwrap();
            std::fs::write(apps.program(version, paths::AGENT_EXE), version).unwrap();
        }
        apps.set_current("0.2.0").unwrap();
        std::fs::write(layout.launcher(), "launcher").unwrap();
        std::fs::create_dir_all(layout.agent_dir()).unwrap();
        if linked {
            std::fs::write(layout.agent_dir().join("host.key"), "key").unwrap();
        }
        if mid_update {
            UpdateState {
                trial: "0.2.0".into(),
                previous: "0.1.0".into(),
                started_at: time::OffsetDateTime::now_utc(),
                distro: None,
                crashes: 1,
                outcome: Outcome::Trial,
            }
            .save(&layout.update_state())
            .unwrap();
            Wsl::new(machine.wsl_runner()).import("IdleO-0.2.0", Path::new("/d2"), Path::new("/t")).unwrap();
        }
        register(&machine, "0.2.0", 12_000).unwrap();
        Wsl::new(machine.wsl_runner()).import("IdleO-0.1.0", Path::new("/d"), Path::new("/t")).unwrap();
        machine
    }

    async fn uninstall_with(machine: &FakeMachine, agent: Option<&mut FakeClient>) -> (Finished, Vec<Shown>, u32) {
        let programs = FakePrograms::default();
        let mut screens = Vec::new();
        let finished = uninstall(machine, agent, &programs, &mut |shown| screens.push(shown)).await;
        let stopped = *programs.0.borrow();
        (finished, screens, stopped)
    }

    fn nothing_of_ours_left(machine: &FakeMachine) {
        assert_eq!(leftovers(machine), Vec::<String>::new());
        assert!(machine.registry.is_empty());
        assert_eq!(Wsl::new(machine.wsl_runner()).list().unwrap(), ["Ubuntu"], "the owner's distribution is untouched");
    }

    #[tokio::test]
    async fn a_linked_laptop_says_goodbye_and_leaves_nothing() {
        let machine = installed(true, false);
        let mut agent = agent(false, vec![UninstallStep::Leaving, UninstallStep::Left]);
        assert!(!session_running(&mut agent).await);
        let (finished, screens, stopped) = uninstall_with(&machine, Some(&mut agent)).await;
        assert!(finished.told);
        assert_eq!(finished.screen.text, NOTHING_LEFT);
        assert_eq!(finished.screen.heading, REMOVED);
        assert_eq!(stopped, 1);
        assert!(screens.iter().any(|s| s.heading == "Telling IdleO"));
        nothing_of_ours_left(&machine);
    }

    #[tokio::test]
    async fn a_running_job_is_stopped_first() {
        let machine = installed(true, false);
        let mut agent = agent(true, vec![UninstallStep::StoppingSession, UninstallStep::Leaving, UninstallStep::Left]);
        assert!(session_running(&mut agent).await, "the dialog asks before going on");
        let (finished, screens, _) = uninstall_with(&machine, Some(&mut agent)).await;
        assert!(finished.told);
        assert!(screens.iter().any(|s| s.heading == "Stopping the renter's job"));
        nothing_of_ours_left(&machine);
    }

    #[tokio::test]
    async fn with_the_server_down_it_carries_on_and_says_so() {
        let machine = installed(true, false);
        let mut agent = agent(false, vec![UninstallStep::Leaving, UninstallStep::Unreachable]);
        let (finished, _, _) = uninstall_with(&machine, Some(&mut agent)).await;
        assert!(!finished.told);
        assert!(finished.screen.text.contains("shows offline on the web"));
        nothing_of_ours_left(&machine);
    }

    #[tokio::test]
    async fn unlinked_mid_update_or_without_a_running_agent_it_still_removes_everything() {
        for (linked, mid_update) in [(false, false), (true, true), (false, true)] {
            let machine = installed(linked, mid_update);
            let (finished, _, _) = uninstall_with(&machine, None::<&mut FakeClient>).await;
            assert!(!finished.told);
            assert!(finished.problems.is_empty(), "{:?}", finished.problems);
            nothing_of_ours_left(&machine);
        }
    }

    #[test]
    fn the_words_are_the_ones_the_spec_gives() {
        assert_eq!(
            format!("{ASK_HEADING}. {ASK_TEXT}"),
            "A renter's job is running. It'll save its progress and stop within 2 minutes."
        );
        assert_eq!(
            format!("{REMOVED} {NOTHING_LEFT}"),
            "IdleO is removed. Nothing is left on your laptop. (WSL stays: it's part of Windows.)"
        );
    }
}
