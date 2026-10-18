//! Starting and watching the app's two background programs (ADR 0033), and running an update's trial (ADR 0034).
//!
//! Every second, [`Supervisor::tick`]:
//! - starts `idleo-hostd` and `idleo-tray` from the version `current` names, if they aren't running;
//! - restarts one that stopped, waiting longer after each crash (1 second doubling to a minute, reset after a minute
//!   of health);
//! - on `idleo-hostd`'s exit code 3 (an update switched `current`), restarts both at once from the new version;
//! - during a trial, counts the new version's crashes, and rolls back after three or after five minutes without the
//!   new agent confirming: `current` goes back, the trial is marked rolled back with the reason, and the old version
//!   starts, which reports `ROLLED_BACK` to IdleO.
//!
//! Processes and the clock are behind traits, so every rule is tested on the Mac.

use std::io;
use std::path::{Path, PathBuf};
use std::time::Duration;

use idleo_release::apps::Apps;
use idleo_release::paths::{self, Layout};
use idleo_release::update_state::{self, TRIAL_CRASHES, UpdateState};
use time::OffsetDateTime;

/// `idleo-hostd`'s exit code when an update has switched `current` (the agent's `RESTART_FOR_UPDATE`).
pub const RESTART_FOR_UPDATE: i32 = 3;
/// A program that stays up this long resets its crash backoff.
pub const HEALTHY_AFTER: Duration = Duration::from_mins(1);

/// A program the launcher started.
pub trait Child: Send {
    /// Its exit code if it has stopped (`-1` when killed), `None` while it runs.
    ///
    /// # Errors
    /// If Windows can't say.
    fn exit_code(&mut self) -> io::Result<Option<i32>>;
    /// Stops it, and waits.
    fn stop(&mut self);
}

/// Starts programs.
pub trait Processes: Send {
    /// What it starts.
    type Child: Child;
    /// Starts `program` with `args`, with no console window.
    ///
    /// # Errors
    /// If it can't be started.
    fn start(&mut self, program: &Path, args: &[&str]) -> io::Result<Self::Child>;
}

/// What a tick did, for the log.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// A program started from a version.
    Started {
        /// `idleo-hostd` or `idleo-tray`.
        program: &'static str,
        /// The version it came from.
        version: String,
    },
    /// A program stopped by itself.
    Stopped {
        /// Which.
        program: &'static str,
        /// Its exit code.
        code: i32,
    },
    /// An update switched `current`: both programs restart from it.
    Switched {
        /// The new version.
        version: String,
    },
    /// The trial failed and the old version is back.
    RolledBack {
        /// The version that failed.
        from: String,
        /// The version running again.
        to: String,
        /// Why.
        reason: String,
    },
    /// A program couldn't be started.
    Failed {
        /// Which.
        program: &'static str,
        /// Why.
        error: String,
    },
}

struct Watched<C> {
    child: Option<C>,
    started_at: Option<OffsetDateTime>,
    failures: u32,
    next_start: OffsetDateTime,
}

impl<C> Watched<C> {
    fn new(now: OffsetDateTime) -> Self {
        Self { child: None, started_at: None, failures: 0, next_start: now }
    }
}

/// The launcher's supervision.
pub struct Supervisor<P: Processes> {
    layout: Layout,
    apps: Apps,
    processes: P,
    hostd: Watched<P::Child>,
    tray: Watched<P::Child>,
}

/// The wait after `failures` crashes in a row: 1 second doubling to a minute.
#[must_use]
pub fn backoff(failures: u32) -> Duration {
    Duration::from_secs((1u64 << failures.min(6)).min(60))
}

impl<P: Processes> Supervisor<P> {
    /// Supervision of the app under `layout`, starting programs with `processes`, from `now`.
    pub fn new(layout: Layout, processes: P, now: OffsetDateTime) -> Self {
        Self { apps: Apps::new(layout.clone()), layout, processes, hostd: Watched::new(now), tray: Watched::new(now) }
    }

    /// The processes, for tests.
    pub fn processes(&mut self) -> &mut P {
        &mut self.processes
    }

    /// One round: checks the trial, then each program. Returns what happened.
    pub fn tick(&mut self, now: OffsetDateTime) -> Vec<Event> {
        let mut events = Vec::new();
        let Some(current) = self.apps.current() else {
            return events;
        };
        if let Some(state) = self.trial_of(&current)
            && state.timed_out(now)
        {
            self.roll_back(state, "the new version wasn't confirmed within 5 minutes", now, &mut events);
            return events;
        }
        // The agent first.
        if let Some(code) = exited(&mut self.hostd) {
            events.push(Event::Stopped { program: paths::HOSTD_EXE, code });
            if code == RESTART_FOR_UPDATE {
                let switched = self.apps.current().unwrap_or_else(|| current.clone());
                events.push(Event::Switched { version: switched });
                stop(&mut self.tray);
                self.hostd.failures = 0;
                self.hostd.next_start = now;
                self.tray.next_start = now;
            } else if let Some(mut state) = self.trial_of(&current) {
                state.crashes += 1;
                if state.crashes >= TRIAL_CRASHES {
                    self.roll_back(state, &format!("the new version crashed {TRIAL_CRASHES} times"), now, &mut events);
                    return events;
                }
                let _ = state.save(&self.layout.update_state());
                crashed(&mut self.hostd, now);
            } else {
                crashed(&mut self.hostd, now);
            }
        }
        if let Some(code) = exited(&mut self.tray) {
            events.push(Event::Stopped { program: paths::TRAY_EXE, code });
            crashed(&mut self.tray, now);
        }
        let current = self.apps.current().unwrap_or(current);
        for (program, which) in [(paths::HOSTD_EXE, true), (paths::TRAY_EXE, false)] {
            let path = self.apps.program(&current, program);
            let watched = if which { &mut self.hostd } else { &mut self.tray };
            healthy(watched, now);
            if watched.child.is_none() && now >= watched.next_start {
                match self.processes.start(&path, &[]) {
                    Ok(child) => {
                        watched.child = Some(child);
                        watched.started_at = Some(now);
                        events.push(Event::Started { program, version: current.clone() });
                    }
                    Err(e) => {
                        crashed(watched, now);
                        events.push(Event::Failed { program, error: e.to_string() });
                    }
                }
            }
        }
        events
    }

    /// Stops both programs, for an uninstall or Windows signing out.
    pub fn stop_all(&mut self) {
        stop(&mut self.hostd);
        stop(&mut self.tray);
    }

    /// The trial under way for `current`, if it is the version on trial.
    fn trial_of(&self, current: &str) -> Option<UpdateState> {
        UpdateState::load(&self.layout.update_state())
            .filter(|state| state.outcome == update_state::Outcome::Trial && state.trial == current)
    }

    fn roll_back(&mut self, mut state: UpdateState, reason: &str, now: OffsetDateTime, events: &mut Vec<Event>) {
        stop(&mut self.hostd);
        stop(&mut self.tray);
        if let Err(e) = self.apps.set_current(&state.previous) {
            events.push(Event::Failed { program: paths::LAUNCHER_EXE, error: format!("rolling back: {e}") });
            return;
        }
        state.outcome = update_state::Outcome::RolledBack { reason: reason.to_owned() };
        let _ = state.save(&self.layout.update_state());
        events.push(Event::RolledBack {
            from: state.trial.clone(),
            to: state.previous.clone(),
            reason: reason.to_owned(),
        });
        self.hostd = Watched::new(now);
        self.tray = Watched::new(now);
    }
}

fn exited<C: Child>(watched: &mut Watched<C>) -> Option<i32> {
    let child = watched.child.as_mut()?;
    match child.exit_code() {
        Ok(Some(code)) => {
            watched.child = None;
            Some(code)
        }
        Ok(None) => None,
        Err(_) => {
            watched.child = None;
            Some(-1)
        }
    }
}

fn crashed<C>(watched: &mut Watched<C>, now: OffsetDateTime) {
    watched.next_start = now + backoff(watched.failures);
    watched.failures = watched.failures.saturating_add(1);
    watched.started_at = None;
}

fn healthy<C>(watched: &mut Watched<C>, now: OffsetDateTime) {
    if watched.child.is_some() && watched.started_at.is_some_and(|at| now - at >= HEALTHY_AFTER) {
        watched.failures = 0;
    }
}

fn stop<C: Child>(watched: &mut Watched<C>) {
    if let Some(mut child) = watched.child.take() {
        child.stop();
    }
}

/// Real processes, with no console window.
#[derive(Debug, Default)]
pub struct SystemProcesses;

/// A real child process.
pub struct SystemChild(std::process::Child);

impl Child for SystemChild {
    fn exit_code(&mut self) -> io::Result<Option<i32>> {
        Ok(self.0.try_wait()?.map(|status| status.code().unwrap_or(-1)))
    }

    fn stop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

impl Processes for SystemProcesses {
    type Child = SystemChild;

    fn start(&mut self, program: &Path, args: &[&str]) -> io::Result<SystemChild> {
        let mut command = std::process::Command::new(program);
        command.args(args).stdin(std::process::Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            /// `CREATE_NO_WINDOW`.
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        command.spawn().map(SystemChild)
    }
}

/// Where the launcher writes its own log.
#[must_use]
pub fn log_file(layout: &Layout) -> PathBuf {
    layout.logs().join("launcher.log")
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex};

    use super::*;

    /// A started program whose exits are scripted: each start takes the next scripted life (`None` runs forever).
    struct FakeChild {
        exit: Arc<Mutex<Option<i32>>>,
    }

    impl Child for FakeChild {
        fn exit_code(&mut self) -> io::Result<Option<i32>> {
            Ok(*self.exit.lock().unwrap())
        }

        fn stop(&mut self) {
            *self.exit.lock().unwrap() = Some(-1);
        }
    }

    #[derive(Default)]
    struct FakeProcesses {
        started: Vec<String>,
        running: Vec<(String, Arc<Mutex<Option<i32>>>)>,
    }

    impl FakeProcesses {
        /// Makes the newest `program` exit with `code`.
        fn exit(&mut self, program: &str, code: i32) {
            let (_, exit) = self.running.iter().rev().find(|(name, _)| name.contains(program)).expect("it started");
            *exit.lock().unwrap() = Some(code);
        }
    }

    impl Processes for FakeProcesses {
        type Child = FakeChild;

        fn start(&mut self, program: &Path, _args: &[&str]) -> io::Result<FakeChild> {
            let name = program.to_string_lossy().into_owned();
            let exit = Arc::new(Mutex::new(None));
            self.started.push(name.clone());
            self.running.push((name, Arc::clone(&exit)));
            Ok(FakeChild { exit })
        }
    }

    const START: i64 = 1_760_000_000;

    fn at(seconds: u64) -> OffsetDateTime {
        OffsetDateTime::from_unix_timestamp(START).unwrap() + Duration::from_secs(seconds)
    }

    /// 0.1.0 and 0.2.0 installed, 0.1.0 current.
    fn installed() -> (tempfile::TempDir, Layout, Supervisor<FakeProcesses>) {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::under(root.path());
        std::fs::create_dir_all(layout.programs()).unwrap();
        for version in ["0.1.0", "0.2.0"] {
            let dir = layout.app_dir(version);
            std::fs::create_dir_all(&dir).unwrap();
        }
        Apps::new(layout.clone()).set_current("0.1.0").unwrap();
        let supervisor = Supervisor::new(layout.clone(), FakeProcesses::default(), at(0));
        (root, layout, supervisor)
    }

    fn started(supervisor: &mut Supervisor<FakeProcesses>) -> VecDeque<String> {
        supervisor
            .processes()
            .started
            .drain(..)
            .map(|path| {
                let path = PathBuf::from(path);
                let version = path.parent().unwrap().file_name().unwrap().to_string_lossy().into_owned();
                format!("{version}/{}", path.file_name().unwrap().to_string_lossy())
            })
            .collect()
    }

    fn start_trial(layout: &Layout) {
        UpdateState {
            trial: "0.2.0".into(),
            previous: "0.1.0".into(),
            started_at: at(10),
            distro: None,
            crashes: 0,
            outcome: update_state::Outcome::Trial,
        }
        .save(&layout.update_state())
        .unwrap();
        Apps::new(layout.clone()).set_current("0.2.0").unwrap();
    }

    #[test]
    fn both_programs_start_from_current_and_a_crash_restarts_with_backoff() {
        let (_root, _layout, mut supervisor) = installed();
        supervisor.tick(at(0));
        assert_eq!(
            started(&mut supervisor),
            [
                format!("app-0.1.0/{}", paths::exe(paths::HOSTD_EXE)),
                format!("app-0.1.0/{}", paths::exe(paths::TRAY_EXE))
            ]
        );
        supervisor.processes().exit("hostd", 1);
        supervisor.tick(at(1));
        assert_eq!(started(&mut supervisor), VecDeque::<String>::new(), "it waits a second first");
        supervisor.tick(at(2));
        assert_eq!(started(&mut supervisor).len(), 1);
        supervisor.processes().exit("hostd", 1);
        supervisor.tick(at(3));
        supervisor.tick(at(4));
        assert_eq!(started(&mut supervisor), VecDeque::<String>::new(), "then two seconds");
        supervisor.tick(at(5));
        assert_eq!(started(&mut supervisor).len(), 1);
        assert_eq!(backoff(10), Duration::from_mins(1));
    }

    #[test]
    fn an_update_switch_restarts_both_from_the_new_version_at_once() {
        let (_root, layout, mut supervisor) = installed();
        supervisor.tick(at(0));
        started(&mut supervisor);
        start_trial(&layout);
        supervisor.processes().exit("hostd", RESTART_FOR_UPDATE);
        let events = supervisor.tick(at(11));
        assert!(events.contains(&Event::Switched { version: "0.2.0".into() }));
        assert_eq!(
            started(&mut supervisor),
            [
                format!("app-0.2.0/{}", paths::exe(paths::HOSTD_EXE)),
                format!("app-0.2.0/{}", paths::exe(paths::TRAY_EXE))
            ]
        );
    }

    #[test]
    fn three_crashes_of_the_new_version_roll_back() {
        let (_root, layout, mut supervisor) = installed();
        start_trial(&layout);
        supervisor.tick(at(10));
        started(&mut supervisor);
        let mut now = 11;
        for crash in 1..=3 {
            supervisor.processes().exit("hostd", 101);
            let events = supervisor.tick(at(now));
            if crash < 3 {
                assert_eq!(UpdateState::load(&layout.update_state()).unwrap().crashes, crash);
                now += 120;
                supervisor.tick(at(now));
                now += 1;
            } else {
                assert!(
                    matches!(&events[..], [.., Event::RolledBack { from, to, reason }] if from == "0.2.0" && to == "0.1.0" && reason.contains("crashed 3 times"))
                );
            }
        }
        assert_eq!(Apps::new(layout.clone()).current().as_deref(), Some("0.1.0"));
        let state = UpdateState::load(&layout.update_state()).unwrap();
        assert_eq!(
            state.outcome,
            update_state::Outcome::RolledBack { reason: "the new version crashed 3 times".into() }
        );
        started(&mut supervisor);
        supervisor.tick(at(now + 1));
        assert!(
            started(&mut supervisor).iter().all(|program| program.starts_with("app-0.1.0/")),
            "the old version runs again"
        );
    }

    #[test]
    fn a_trial_not_confirmed_in_five_minutes_rolls_back() {
        let (_root, layout, mut supervisor) = installed();
        start_trial(&layout);
        supervisor.tick(at(10));
        assert_eq!(supervisor.tick(at(10 + 299)), Vec::new());
        let events = supervisor.tick(at(10 + 300));
        assert!(matches!(&events[..], [Event::RolledBack { reason, .. }] if reason.contains("5 minutes")));
        assert_eq!(Apps::new(layout.clone()).current().as_deref(), Some("0.1.0"));
    }

    #[test]
    fn a_confirmed_trial_is_left_alone() {
        let (_root, layout, mut supervisor) = installed();
        start_trial(&layout);
        let mut state = UpdateState::load(&layout.update_state()).unwrap();
        state.outcome = update_state::Outcome::Confirmed;
        state.save(&layout.update_state()).unwrap();
        supervisor.tick(at(10));
        supervisor.processes().exit("hostd", 101);
        let events = supervisor.tick(at(10 + 400));
        assert!(!events.iter().any(|event| matches!(event, Event::RolledBack { .. })));
        assert_eq!(Apps::new(layout.clone()).current().as_deref(), Some("0.2.0"));
    }
}
