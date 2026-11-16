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
//! for the "start again after" time, except for the host's own limits (ADR 0061): outside the schedule's hours, a metered
//! connection the host said not to use, a GPU hotter than the host's limit (it carries on once five degrees cooler) and
//! the Linux space's disk limit. Those clear the moment their cause does, because nothing about the owner's being at the
//! machine is in them; only the owner's last input still has to be old enough. The owner's pause wins over everything. `owner_active`, in heartbeats and offer
//! replies, is true only while the owner is at the machine ([`State::AutoPaused`]). The owner's pause is not part of
//! it: the control plane keeps that in its database, where a pause and a resume count at once, and never offers a paused
//! host a session. Said again here, it lagged a resume by up to 5 s (until the agent heard `HostStatus` and sent its next
//! heartbeat), and a session booked in that time went to another laptop.

use std::time::Duration;

use idleo_release::channel::Reason;
use idleo_release::settings::{HostSettings, Moment};
use time::{OffsetDateTime, UtcOffset};

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
    /// The hottest GPU's temperature, in °C, if it could be read.
    pub gpu_temp_c: Option<u32>,
    /// The connection is metered.
    pub metered: bool,
    /// The Linux space has used the disk the host allowed it.
    pub disk_full: bool,
    /// The host's local time zone, for the schedule.
    pub utc_offset: UtcOffset,
}

impl Inputs {
    /// A machine nobody is using: what Linux and macOS always report.
    pub const QUIET: Self = Self {
        idle: Duration::MAX,
        fullscreen: false,
        on_battery: false,
        gpu_util_pct: None,
        session_running: false,
        gpu_temp_c: None,
        metered: false,
        disk_full: false,
        utc_offset: UtcOffset::UTC,
    };
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
    settings: HostSettings,
    pause: OwnerPause,
    state: State,
    /// When the GPU went over the busy line with no session of ours, on the monotonic clock.
    gpu_busy_since: Option<Duration>,
    /// The GPU is over the host's temperature limit, and stays so until it has cooled a little.
    hot: bool,
    /// The last sample's time, wall time and inputs, for `resumes_at`.
    last: Option<(Duration, OffsetDateTime, Inputs)>,
}

impl Availability {
    /// Starts available, with these settings.
    #[must_use]
    pub fn new(settings: HostSettings) -> Self {
        Self {
            settings: settings.normalized(),
            pause: OwnerPause::None,
            state: State::Available,
            gpu_busy_since: None,
            hot: false,
            last: None,
        }
    }

    /// The state after the last step.
    #[must_use]
    pub fn state(&self) -> State {
        self.state
    }

    /// The settings.
    #[must_use]
    pub fn settings(&self) -> HostSettings {
        self.settings
    }

    /// Changes the settings; the next step applies them.
    pub fn set_settings(&mut self, settings: HostSettings) {
        self.settings = settings.normalized();
    }

    /// Whether the GPU is held over the host's temperature limit now.
    #[must_use]
    pub fn too_hot(&self) -> bool {
        self.hot
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
        self.last = Some((now, wall, *inputs));
        self.hot = self.settings.limits.too_hot(inputs.gpu_temp_c, self.hot);
        let gpu_busy = !inputs.session_running && inputs.gpu_util_pct.is_some_and(|pct| pct > GPU_BUSY_PERCENT);
        if gpu_busy {
            self.gpu_busy_since.get_or_insert(now);
        } else {
            self.gpu_busy_since = None;
        }
        self.state = match self.pause {
            OwnerPause::Indefinite => State::HostingOff,
            OwnerPause::Until(until) if wall < until => State::Paused { until },
            OwnerPause::Until(_) | OwnerPause::None => self.auto(now, wall, inputs),
        };
        (before, self.state)
    }

    /// A limit the host set that closes hosting while it holds, and lets go the moment it doesn't.
    fn closed_by(&self, wall: OffsetDateTime, inputs: &Inputs) -> Option<Reason> {
        if !self.settings.schedule.open_at(Moment::of(wall, inputs.utc_offset)) {
            Some(Reason::Schedule)
        } else if self.settings.not_on_metered && inputs.metered {
            Some(Reason::Metered)
        } else if self.hot {
            Some(Reason::TooHot)
        } else if inputs.disk_full {
            Some(Reason::DiskFull)
        } else {
            None
        }
    }

    /// A trigger other than input that holds an auto-pause while it lasts, and then waits out the quiet time.
    fn held_by(&self, now: Duration, inputs: &Inputs) -> Option<Reason> {
        let settings = self.settings.auto_pause;
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

    fn auto(&self, now: Duration, wall: OffsetDateTime, inputs: &Inputs) -> State {
        if let Some(reason) = self.closed_by(wall, inputs) {
            return State::AutoPaused { reason, quiet_since: None };
        }
        if let Some(reason) = self.held_by(now, inputs) {
            return State::AutoPaused { reason, quiet_since: None };
        }
        if self.settings.auto_pause.keyboard_mouse && inputs.idle < INPUT_NOW {
            // The wait runs from the input itself: "starts again 10 min after you stop".
            return State::AutoPaused {
                reason: Reason::KeyboardMouse,
                quiet_since: Some(now.saturating_sub(inputs.idle)),
            };
        }
        let State::AutoPaused { reason, quiet_since } = self.state else {
            return State::Available;
        };
        if reason.clears_at_once() {
            // The host's own limit let go. Nothing about the owner needs waiting out, except that they mustn't have been at
            // the machine within the quiet time (the schedule opening on someone still typing).
            let quiet = self.settings.auto_pause.keyboard_mouse && inputs.idle < self.wait();
            return if quiet {
                State::AutoPaused { reason: Reason::KeyboardMouse, quiet_since: Some(now.saturating_sub(inputs.idle)) }
            } else {
                State::Available
            };
        }
        let quiet_since = quiet_since.unwrap_or(now);
        if now.saturating_sub(self.quiet_from(quiet_since, now, inputs)) >= self.wait() {
            State::Available
        } else {
            State::AutoPaused { reason, quiet_since: Some(quiet_since) }
        }
    }

    /// The later of when the triggers cleared and the last input (when input counts).
    fn quiet_from(&self, quiet_since: Duration, now: Duration, inputs: &Inputs) -> Duration {
        if self.settings.auto_pause.keyboard_mouse {
            quiet_since.max(now.saturating_sub(inputs.idle))
        } else {
            quiet_since
        }
    }

    fn wait(&self) -> Duration {
        Duration::from_secs(u64::from(self.settings.auto_pause.start_again_after_minutes) * 60)
    }

    /// When an auto-pause will end if nothing more happens; `None` while a game, the GPU or the battery still holds it,
    /// or when not auto-paused. Outside the schedule's hours it is when they next open.
    #[must_use]
    pub fn resumes_at(&self, wall: OffsetDateTime) -> Option<OffsetDateTime> {
        let State::AutoPaused { reason, quiet_since } = self.state else { return None };
        let (now, sampled, inputs) = self.last?;
        if reason == Reason::Schedule {
            let minutes = self.settings.schedule.opens_in_minutes(Moment::of(sampled, inputs.utc_offset))?;
            let opens = sampled + Duration::from_secs(u64::from(minutes) * 60);
            // The minute it opens, rounded down to the start of it.
            return Some((opens - Duration::from_secs(u64::from(sampled.second()))).max(wall));
        }
        let quiet_since = quiet_since?;
        let ends = self.quiet_from(quiet_since, now, &inputs) + self.wait();
        Some(wall + ends.saturating_sub(now))
    }
}

/// What a [`Reason`] needs to end.
trait ClearsAtOnce {
    /// The reason is one of the host's own limits, which ends the moment its cause does.
    fn clears_at_once(self) -> bool;
}

impl ClearsAtOnce for Reason {
    fn clears_at_once(self) -> bool {
        matches!(self, Self::Schedule | Self::Metered | Self::TooHot | Self::DiskFull)
    }
}

#[cfg(test)]
#[allow(clippy::field_reassign_with_default, reason = "a test changes the one setting it is about")]
mod tests {
    use idleo_release::channel::AutoPause;
    use idleo_release::settings::{DayHours, Schedule, ScheduleMode};

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
                machine: Availability::new(HostSettings::default()),
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
        fake.machine.set_settings(HostSettings::default().with_auto_pause(AutoPause {
            keyboard_mouse: false,
            games: false,
            unplugged: false,
            start_again_after_minutes: 5,
        }));
        fake.inputs.fullscreen = true;
        fake.inputs.on_battery = true;
        fake.inputs.gpu_util_pct = Some(100);
        assert_eq!(fake.touch(), State::Available);
        assert_eq!(fake.wait(MINUTE), State::Available);

        // With input not counted, the wait runs from the moment the game closed, whatever the mouse does.
        let mut games = Fake::new();
        games.machine.set_settings(HostSettings::default().with_auto_pause(AutoPause {
            keyboard_mouse: false,
            start_again_after_minutes: 5,
            ..AutoPause::default()
        }));
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
        let mut machine = Availability::new(HostSettings::default());
        for second in 0..600 {
            machine.step(Duration::from_secs(second), wall(Duration::from_secs(second)), &Inputs::QUIET);
            assert_eq!(machine.state(), State::Available);
        }
    }

    /// A machine for the host's own limits, in UTC. Its clock starts at Thursday 2025-10-09 09:53:20 UTC.
    fn in_utc() -> Fake {
        let mut fake = Fake::new();
        fake.inputs.utc_offset = UtcOffset::UTC;
        fake
    }

    fn open_only(from_h: u16, to_h: u16) -> HostSettings {
        let mut settings = HostSettings::default();
        settings.schedule =
            Schedule { mode: ScheduleMode::Hours, days: [DayHours { on: true, from: from_h * 60, to: to_h * 60 }; 7] };
        settings
    }

    #[test]
    fn outside_the_hours_it_pauses_and_inside_them_it_carries_on_at_once() {
        // The fake's wall clock is 09:53 UTC on its first step.
        let mut fake = in_utc();
        fake.machine.set_settings(open_only(22, 7));
        assert!(paused(fake.step(), Reason::Schedule), "09:53 is outside 22:00-07:00");
        assert!(fake.machine.owner_active(), "not for renters now");
        let resumes = fake.machine.resumes_at(wall(fake.now)).unwrap();
        // The window opens at 22:00 UTC, on the minute.
        assert_eq!((resumes.hour(), resumes.minute(), resumes.second()), (22, 0, 0));
        // 13 hours later the hours are open: no quiet time to wait out.
        let state = fake.wait(13 * 60 * MINUTE);
        assert_eq!(state, State::Available, "{state:?}");
    }

    #[test]
    fn the_schedule_uses_the_hosts_own_time_zone() {
        let mut fake = in_utc();
        // 09:53 UTC is 15:23 in India, so 14:00-16:00 is open there and closed in UTC.
        fake.machine.set_settings(open_only(14, 16));
        fake.inputs.utc_offset = UtcOffset::from_hms(5, 30, 0).unwrap();
        assert_eq!(fake.step(), State::Available, "15:23 in India");
        fake.inputs.utc_offset = UtcOffset::UTC;
        assert!(paused(fake.step(), Reason::Schedule), "09:53 in UTC");
    }

    #[test]
    fn the_schedule_opening_on_an_owner_still_typing_waits_out_the_quiet_time() {
        let mut fake = in_utc();
        // Closed now (09:53), opens at 10:00 UTC.
        fake.machine.set_settings(open_only(10, 11));
        assert!(paused(fake.step(), Reason::Schedule));
        fake.wait(6 * MINUTE + 30 * SECOND);
        fake.touch();
        let at_open = fake.wait(MINUTE);
        assert!(paused(at_open, Reason::KeyboardMouse), "the owner typed a minute ago: {at_open:?}");
        assert_eq!(fake.wait(10 * MINUTE), State::Available, "ten quiet minutes after the last key");
    }

    #[test]
    fn always_ignores_the_owner_and_the_clock() {
        let mut fake = in_utc();
        let mut settings = open_only(22, 7);
        settings.schedule.mode = ScheduleMode::Always;
        fake.machine.set_settings(settings);
        assert_eq!(fake.touch(), State::Available, "at the keyboard, outside the hours: still available");
    }

    #[test]
    fn heat_pauses_above_the_limit_and_carries_on_five_degrees_cooler_with_no_quiet_time() {
        let mut fake = in_utc();
        fake.inputs.gpu_temp_c = Some(83);
        assert_eq!(fake.step(), State::Available, "at the limit");
        fake.inputs.gpu_temp_c = Some(84);
        assert!(paused(fake.step(), Reason::TooHot));
        fake.inputs.gpu_temp_c = Some(80);
        assert!(paused(fake.wait(SECOND), Reason::TooHot), "still hot enough to stay held");
        fake.inputs.gpu_temp_c = Some(78);
        assert_eq!(fake.wait(SECOND), State::Available, "cooled: back at once, not 10 minutes later");
        assert!(!fake.machine.too_hot());
    }

    #[test]
    fn a_lower_limit_from_the_host_applies_at_once() {
        let mut fake = in_utc();
        fake.inputs.gpu_temp_c = Some(75);
        assert_eq!(fake.step(), State::Available);
        let mut settings = HostSettings::default();
        settings.limits.max_gpu_temp_c = 70;
        fake.machine.set_settings(settings);
        assert!(paused(fake.step(), Reason::TooHot));
    }

    #[test]
    fn a_metered_connection_pauses_only_if_the_host_said_not_to_use_those() {
        let mut fake = in_utc();
        fake.inputs.metered = true;
        assert!(paused(fake.step(), Reason::Metered), "on by default");
        fake.inputs.metered = false;
        assert_eq!(fake.wait(SECOND), State::Available, "back the moment it isn't");
        let mut allowed = in_utc();
        let mut settings = HostSettings::default();
        settings.not_on_metered = false;
        allowed.machine.set_settings(settings);
        allowed.inputs.metered = true;
        assert_eq!(allowed.step(), State::Available);
    }

    #[test]
    fn a_full_disk_limit_pauses_and_clears_when_there_is_room() {
        let mut fake = in_utc();
        fake.inputs.disk_full = true;
        assert!(paused(fake.step(), Reason::DiskFull));
        fake.inputs.disk_full = false;
        assert_eq!(fake.wait(SECOND), State::Available);
    }

    #[test]
    fn plugging_in_still_waits_but_the_hosts_limits_never_do_and_the_first_reason_shown_is_the_hosts() {
        let mut fake = in_utc();
        fake.inputs.on_battery = true;
        fake.inputs.gpu_temp_c = Some(95);
        assert!(paused(fake.step(), Reason::TooHot), "the host's own limit is named before the battery");
        fake.inputs.gpu_temp_c = Some(50);
        assert!(paused(fake.wait(SECOND), Reason::Unplugged));
    }

    #[test]
    fn the_owners_pause_still_wins_over_the_limits() {
        let mut fake = in_utc();
        fake.inputs.gpu_temp_c = Some(99);
        fake.machine.set_pause(OwnerPause::Indefinite);
        assert_eq!(fake.step(), State::HostingOff);
    }
}
