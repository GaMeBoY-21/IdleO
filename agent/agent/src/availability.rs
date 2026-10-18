//! Whether this machine is free for renters right now (ADR 0033): one rule, in one place, as a pure state machine
//! driven by samples and a clock, so every transition is tested with a fake clock.
//!
//! **Inputs**, sampled every second on Windows ([`crate::owner`]): time since the last keyboard or mouse input; whether
//! a game or full-screen app is open; whether the machine runs on battery; and the GPU's use, which counts when no
//! session of ours is running and it stays over 30% for 10 seconds (a game in a borderless window, or the owner's own
//! CUDA work). On Linux and macOS the inputs never say the owner is there, so the local stack is unchanged.
//!
//! **States**: [`State::Available`]; [`State::AutoPaused`]; [`State::HostingOff`] and [`State::Paused`], which
//! mirror the owner's pause from the control plane (`HostStatus`).
//!
//! **Rules**: any enabled trigger auto-pauses at once. Leaving an auto-pause needs every trigger clear **and** no input
//! for the "start again after" time. The owner's pause wins over everything. `owner_active`, in heartbeats and offer
//! replies, is true only while the owner is at the machine ([`State::AutoPaused`]). The owner's pause is not part of
//! it: the control plane keeps that in its database, where a pause and a resume count at once, and never offers a paused
//! host a session. Said again here, it lagged a resume by up to 5 s (until the agent heard `HostStatus` and sent its next
//! heartbeat), and a session booked in that time went to another laptop.

use std::time::Duration;

use idleo_release::channel::{AutoPause, Reason};
use time::OffsetDateTime;

/// Input within this long of a sample counts as the owner using the machine now.
pub const INPUT_NOW: Duration = Duration::from_secs(2);
/// GPU use above this, while no session of ours runs, counts as another program using it…
pub const GPU_BUSY_PERCENT: u32 = 30;
/// …once it has lasted this long.
pub const GPU_BUSY_FOR: Duration = Duration::from_secs(10);

/// One sample of the machine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(clippy::struct_excessive_bools)] // Facts sampled from the machine, each its own.
pub struct Inputs {
    /// Time since the last keyboard or mouse input.
    pub idle: Duration,
    /// A game or a full-screen app is open.
    pub fullscreen: bool,
    /// Running on battery. An unknown power state (a desktop) is not.
    pub on_battery: bool,
    /// The busiest GPU's use, in percent, if it could be read.
    pub gpu_util_pct: Option<u32>,
    /// A session of ours is running, so the GPU's use is ours.
    pub session_running: bool,
}

impl Inputs {
    /// A machine nobody is using: what Linux and macOS always report.
    pub const QUIET: Self =
        Self { idle: Duration::MAX, fullscreen: false, on_battery: false, gpu_util_pct: None, session_running: false };
}

/// The owner's pause, as the control plane last said.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OwnerPause {
    /// Not paused.
    #[default]
    None,
    /// Hosting is off: a pause with no end.
    Indefinite,
    /// Paused until this time.
    Until(OffsetDateTime),
}

/// Where the machine stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    /// Ready for renters.
    Available,
    /// Paused because the owner is using it.
    AutoPaused {
        /// The trigger that last fired.
        reason: Reason,
        /// When the wait began, on the monotonic clock: the last input, or when the other triggers last became clear;
        /// `None` while a game, the GPU or the battery still holds it.
        quiet_since: Option<Duration>,
    },
    /// Hosting is off.
    HostingOff,
    /// Paused by the owner until a time.
    Paused {
        /// When it ends.
        until: OffsetDateTime,
    },
}

/// The state machine.
#[derive(Debug, Clone)]
pub struct Availability {
    settings: AutoPause,
    pause: OwnerPause,
    state: State,
    /// When the GPU went over the busy line with no session of ours, on the monotonic clock.
    gpu_busy_since: Option<Duration>,
    /// The last sample's time and its time since input, for `resumes_at`.
    last: Option<(Duration, Inputs)>,
}

impl Availability {
    /// Starts available, with these settings.
    #[must_use]
    pub fn new(settings: AutoPause) -> Self {
        Self { settings, pause: OwnerPause::None, state: State::Available, gpu_busy_since: None, last: None }
    }

    /// The state after the last step.
    #[must_use]
    pub fn state(&self) -> State {
        self.state
    }

    /// The settings.
    #[must_use]
    pub fn settings(&self) -> AutoPause {
        self.settings
    }

    /// Changes the settings; the next step applies them.
    pub fn set_settings(&mut self, settings: AutoPause) {
        self.settings = settings;
    }

    /// The owner's pause changed at the control plane.
    pub fn set_pause(&mut self, pause: OwnerPause) {
        self.pause = pause;
    }

    /// True while the owner is at the machine (auto-pause): heartbeats and offer replies say so. Not true for the
    /// owner's own pause or hosting switch: those are the control plane's to enforce, as soon as they are made.
    #[must_use]
    pub fn owner_active(&self) -> bool {
        matches!(self.state, State::AutoPaused { .. })
    }

    /// Applies one sample taken at `now` (monotonic) and `wall` (UTC). Returns the state before and after.
    pub fn step(&mut self, now: Duration, wall: OffsetDateTime, inputs: &Inputs) -> (State, State) {
        let before = self.state;
        self.last = Some((now, *inputs));
        let gpu_busy = !inputs.session_running && inputs.gpu_util_pct.is_some_and(|pct| pct > GPU_BUSY_PERCENT);
        if gpu_busy {
            self.gpu_busy_since.get_or_insert(now);
        } else {
            self.gpu_busy_since = None;
        }
        self.state = match self.pause {
            OwnerPause::Indefinite => State::HostingOff,
            OwnerPause::Until(until) if wall < until => State::Paused { until },
            OwnerPause::Until(_) | OwnerPause::None => self.auto(now, inputs),
        };
        (before, self.state)
    }

    /// A trigger other than input that holds an auto-pause while it lasts.
    fn held_by(&self, now: Duration, inputs: &Inputs) -> Option<Reason> {
        let settings = self.settings;
        if settings.games && inputs.fullscreen {
            Some(Reason::Game)
        } else if settings.games && self.gpu_busy_since.is_some_and(|since| now.saturating_sub(since) >= GPU_BUSY_FOR) {
            Some(Reason::GpuBusy)
        } else if settings.unplugged && inputs.on_battery {
            Some(Reason::Unplugged)
        } else {
            None
        }
    }

    fn auto(&self, now: Duration, inputs: &Inputs) -> State {
        if let Some(reason) = self.held_by(now, inputs) {
            return State::AutoPaused { reason, quiet_since: None };
        }
        if self.settings.keyboard_mouse && inputs.idle < INPUT_NOW {
            // The wait runs from the input itself: "starts again 10 min after you stop".
            return State::AutoPaused {
                reason: Reason::KeyboardMouse,
                quiet_since: Some(now.saturating_sub(inputs.idle)),
            };
        }
        let State::AutoPaused { reason, quiet_since } = self.state else {
            return State::Available;
        };
        let quiet_since = quiet_since.unwrap_or(now);
        if now.saturating_sub(self.quiet_from(quiet_since, now, inputs)) >= self.wait() {
            State::Available
        } else {
            State::AutoPaused { reason, quiet_since: Some(quiet_since) }
        }
    }

    /// The later of when the triggers cleared and the last input (when input counts).
    fn quiet_from(&self, quiet_since: Duration, now: Duration, inputs: &Inputs) -> Duration {
        if self.settings.keyboard_mouse { quiet_since.max(now.saturating_sub(inputs.idle)) } else { quiet_since }
    }

    fn wait(&self) -> Duration {
        Duration::from_secs(u64::from(self.settings.start_again_after_minutes) * 60)
    }

    /// When an auto-pause will end if nothing more happens; `None` while a game, the GPU or the battery still holds it,
    /// or when not auto-paused.
    #[must_use]
    pub fn resumes_at(&self, wall: OffsetDateTime) -> Option<OffsetDateTime> {
        let State::AutoPaused { quiet_since: Some(quiet_since), .. } = self.state else { return None };
        let (now, inputs) = self.last?;
        let ends = self.quiet_from(quiet_since, now, &inputs) + self.wait();
        Some(wall + ends.saturating_sub(now))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECOND: Duration = Duration::from_secs(1);
    const MINUTE: Duration = Duration::from_secs(60);

    fn wall(at: Duration) -> OffsetDateTime {
        OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap() + at
    }

    /// A clock that moves only when told, and the last input's time on it.
    struct Fake {
        machine: Availability,
        now: Duration,
        last_input: Option<Duration>,
        inputs: Inputs,
    }

    impl Fake {
        fn new() -> Self {
            Self {
                machine: Availability::new(AutoPause::default()),
                now: Duration::from_hours(1),
                last_input: None,
                inputs: Inputs::QUIET,
            }
        }

        fn touch(&mut self) -> State {
            self.last_input = Some(self.now);
            self.step()
        }

        fn wait(&mut self, by: Duration) -> State {
            // Sampled every second, as the agent does.
            let end = self.now + by;
            let mut state = self.machine.state();
            while self.now < end {
                self.now = (self.now + SECOND).min(end);
                state = self.step();
            }
            state
        }

        fn step(&mut self) -> State {
            self.inputs.idle = self.last_input.map_or(Duration::MAX, |at| self.now.saturating_sub(at));
            self.machine.step(self.now, wall(self.now), &self.inputs).1
        }
    }

    fn paused(state: State, reason: Reason) -> bool {
        matches!(state, State::AutoPaused { reason: r, .. } if r == reason)
    }

    #[test]
    fn input_pauses_at_once_and_ten_quiet_minutes_start_it_again() {
        let mut fake = Fake::new();
        assert_eq!(fake.step(), State::Available);
        assert!(paused(fake.touch(), Reason::KeyboardMouse));
        assert!(fake.machine.owner_active());
        assert!(paused(fake.wait(9 * MINUTE + 58 * SECOND), Reason::KeyboardMouse));
        assert_eq!(fake.wait(2 * SECOND), State::Available);
        assert!(!fake.machine.owner_active());
    }

    #[test]
    fn input_resets_the_quiet_timer() {
        let mut fake = Fake::new();
        fake.touch();
        fake.wait(8 * MINUTE);
        assert!(paused(fake.touch(), Reason::KeyboardMouse));
        assert!(paused(fake.wait(8 * MINUTE), Reason::KeyboardMouse), "eight minutes after the second touch");
        assert_eq!(fake.wait(2 * MINUTE + SECOND), State::Available);
    }

    #[test]
    fn a_game_pauses_and_the_wait_starts_when_it_closes() {
        let mut fake = Fake::new();
        fake.inputs.fullscreen = true;
        assert!(paused(fake.step(), Reason::Game));
        assert!(paused(fake.wait(30 * MINUTE), Reason::Game), "held while it stays open");
        assert_eq!(fake.machine.resumes_at(wall(fake.now)), None);
        fake.inputs.fullscreen = false;
        assert!(paused(fake.wait(SECOND), Reason::Game));
        let resumes = fake.machine.resumes_at(wall(fake.now)).unwrap();
        assert_eq!(resumes, wall(fake.now) + 10 * MINUTE);
        fake.wait(10 * MINUTE);
        assert_eq!(fake.machine.state(), State::Available);
    }

    #[test]
    fn unplugging_pauses_and_plugging_in_waits() {
        let mut fake = Fake::new();
        fake.inputs.on_battery = true;
        assert!(paused(fake.step(), Reason::Unplugged));
        fake.inputs.on_battery = false;
        assert!(paused(fake.wait(9 * MINUTE), Reason::Unplugged));
        assert_eq!(fake.wait(MINUTE + SECOND), State::Available);
    }

    #[test]
    fn the_gpu_counts_after_ten_seconds_and_never_for_our_own_session() {
        let mut fake = Fake::new();
        fake.inputs.gpu_util_pct = Some(80);
        // First seen busy at the first sample: ten seconds after that, it counts.
        assert_eq!(fake.wait(10 * SECOND), State::Available);
        assert!(paused(fake.wait(SECOND), Reason::GpuBusy));

        let mut ours = Fake::new();
        ours.inputs.gpu_util_pct = Some(99);
        ours.inputs.session_running = true;
        assert_eq!(ours.wait(5 * MINUTE), State::Available);

        let mut light = Fake::new();
        light.inputs.gpu_util_pct = Some(30);
        assert_eq!(light.wait(MINUTE), State::Available, "30% isn't over 30%");
    }

    #[test]
    fn a_trigger_turned_off_doesnt_pause() {
        let mut fake = Fake::new();
        fake.machine.set_settings(AutoPause {
            keyboard_mouse: false,
            games: false,
            unplugged: false,
            start_again_after_minutes: 5,
        });
        fake.inputs.fullscreen = true;
        fake.inputs.on_battery = true;
        fake.inputs.gpu_util_pct = Some(100);
        assert_eq!(fake.touch(), State::Available);
        assert_eq!(fake.wait(MINUTE), State::Available);

        // With input not counted, the wait runs from the moment the game closed, whatever the mouse does.
        let mut games = Fake::new();
        games.machine.set_settings(AutoPause {
            keyboard_mouse: false,
            start_again_after_minutes: 5,
            ..AutoPause::default()
        });
        games.inputs.fullscreen = true;
        games.step();
        games.inputs.fullscreen = false;
        games.wait(4 * MINUTE);
        games.touch();
        assert_eq!(games.wait(MINUTE + SECOND), State::Available);
    }

    #[test]
    fn only_the_owner_being_at_the_machine_is_owner_active() {
        let mut fake = Fake::new();
        assert!(!fake.machine.owner_active());
        fake.machine.set_pause(OwnerPause::Until(wall(fake.now + 30 * MINUTE)));
        fake.step();
        assert!(!fake.machine.owner_active(), "paused by the owner, nobody at the machine");
        // Resumed: the machine is offered work in the very next breath, with no wait for anything to be heard.
        fake.machine.set_pause(OwnerPause::None);
        assert_eq!(fake.step(), State::Available);
        assert!(!fake.machine.owner_active());
        assert!(paused(fake.touch(), Reason::KeyboardMouse));
        assert!(fake.machine.owner_active(), "someone at the machine");
    }

    #[test]
    fn the_owners_pause_wins_and_ends_by_itself() {
        let mut fake = Fake::new();
        fake.machine.set_pause(OwnerPause::Indefinite);
        assert_eq!(fake.touch(), State::HostingOff);
        assert!(
            !fake.machine.owner_active(),
            "the owner's pause is the control plane's to enforce, not the machine's to report"
        );
        let until = wall(fake.now + 30 * MINUTE);
        fake.machine.set_pause(OwnerPause::Until(until));
        assert_eq!(fake.step(), State::Paused { until });
        // Ends at its time; the owner isn't using the machine then, so it's available at once.
        fake.last_input = None;
        assert_eq!(fake.wait(30 * MINUTE), State::Available);
        fake.machine.set_pause(OwnerPause::None);
        assert_eq!(fake.step(), State::Available);
    }

    #[test]
    fn linux_and_macos_never_pause() {
        let mut machine = Availability::new(AutoPause::default());
        for second in 0..600 {
            machine.step(Duration::from_secs(second), wall(Duration::from_secs(second)), &Inputs::QUIET);
            assert_eq!(machine.state(), State::Available);
        }
    }
}
