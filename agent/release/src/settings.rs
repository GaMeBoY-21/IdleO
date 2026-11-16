//! What the host chooses in the IdleO window (ADR 0061): one type for the agent (which stores and enforces it), the tray
//! and the window (which show and change it), and the rules that need nothing but the settings and a moment: whether the
//! schedule is open, whether the GPU is too hot, and how much disk may be used.
//!
//! The settings are stored by the agent in `settings.toml`, never only in the window, and travel on the local channel as
//! JSON. Every field has a default, so a file or a message from an older version still reads.
//!
//! **Schedule.** [`ScheduleMode::WhenIdle`] (the default): renters get the laptop whenever its owner is away, which is how
//! hosting has always worked. [`ScheduleMode::Always`]: no time or owner activity pauses it (games, heat, battery and the
//! metered-connection rule still do). [`ScheduleMode::Hours`]: only inside the hours set for each weekday, and only while
//! the owner is away. The owner-activity trigger of [`crate::channel::AutoPause`] follows the mode ([`HostSettings::normalized`]),
//! so the two never disagree.

use serde::{Deserialize, Serialize};

use crate::channel::AutoPause;

/// Minutes in a day: the end of a window that runs to midnight.
pub const DAY_MINUTES: u16 = 24 * 60;

/// When the laptop is available.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScheduleMode {
    /// Around the clock, even while the owner is at the laptop.
    Always,
    /// Whenever the owner is away.
    #[default]
    WhenIdle,
    /// Only inside the hours set for each weekday, and only while the owner is away.
    Hours,
}

/// One weekday's hours. Times are minutes after midnight, local. `to` before `from` means the window runs past midnight
/// into the next day; `to` of [`DAY_MINUTES`] is the end of the day.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct DayHours {
    /// Whether this weekday has any window.
    pub on: bool,
    /// When the window opens.
    pub from: u16,
    /// When it closes.
    pub to: u16,
}

impl DayHours {
    /// 10 pm to 7 am: the night, when most laptops sit idle.
    pub const NIGHT: Self = Self { on: true, from: 22 * 60, to: 7 * 60 };

    /// Whether the window is allowed: both times in the day, and not the same time (which would mean nothing).
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.from < DAY_MINUTES && self.to <= DAY_MINUTES && self.to > 0 && self.from != self.to
    }
}

/// A moment in the host's own week, local time.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Moment {
    /// 0 is Monday, 6 is Sunday.
    pub weekday: u8,
    /// Minutes after local midnight.
    pub minute: u16,
}

impl Moment {
    /// The moment `at` is, in the host's UTC offset.
    #[must_use]
    pub fn of(at: time::OffsetDateTime, offset: time::UtcOffset) -> Self {
        let local = at.to_offset(offset);
        Self {
            weekday: local.weekday().number_days_from_monday(),
            minute: u16::from(local.hour()) * 60 + u16::from(local.minute()),
        }
    }

    fn after(self, minutes: u32) -> Self {
        let total = u32::from(self.weekday) * u32::from(DAY_MINUTES) + u32::from(self.minute) + minutes;
        let week = 7 * u32::from(DAY_MINUTES);
        let total = total % week;
        Self {
            weekday: u8::try_from(total / u32::from(DAY_MINUTES)).unwrap_or(0),
            minute: u16::try_from(total % u32::from(DAY_MINUTES)).unwrap_or(0),
        }
    }
}

/// The hours of each weekday, Monday first.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Schedule {
    /// How the laptop is made available.
    pub mode: ScheduleMode,
    /// Used in [`ScheduleMode::Hours`], Monday first.
    pub days: [DayHours; 7],
}

impl Default for Schedule {
    fn default() -> Self {
        Self { mode: ScheduleMode::default(), days: [DayHours::NIGHT; 7] }
    }
}

impl Schedule {
    /// Whether the schedule lets the laptop be available at `at`. Only [`ScheduleMode::Hours`] ever says no.
    #[must_use]
    pub fn open_at(&self, at: Moment) -> bool {
        if self.mode != ScheduleMode::Hours {
            return true;
        }
        let today = self.days[usize::from(at.weekday % 7)];
        let yesterday = self.days[usize::from((at.weekday + 6) % 7)];
        let within_today = today.on && today.from < today.to && (today.from..today.to).contains(&at.minute);
        // A window that began today and runs past midnight covers the rest of today…
        let starts_today = today.on && today.from > today.to && at.minute >= today.from;
        // …and one that began yesterday covers the start of today.
        let from_yesterday = yesterday.on && yesterday.from > yesterday.to && at.minute < yesterday.to;
        within_today || starts_today || from_yesterday
    }

    /// Minutes from `at` until the schedule next opens, if it is closed now and ever opens; `None` when it's open or
    /// never opens.
    #[must_use]
    pub fn opens_in_minutes(&self, at: Moment) -> Option<u32> {
        if self.open_at(at) {
            return None;
        }
        (1..=7 * u32::from(DAY_MINUTES)).find(|minutes| self.open_at(at.after(*minutes)))
    }

    /// Whether every day's window is allowed.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        self.days.iter().all(|day| !day.on || day.is_valid())
    }
}

/// What the laptop may use, and the heat it may reach.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Limits {
    /// The highest GPU temperature allowed, in °C: above it hosting pauses.
    pub max_gpu_temp_c: u32,
    /// The most disk the Linux space may use (the sealed distribution's disk and the job images in it), in GB.
    pub max_disk_gb: u32,
    /// The share of the laptop's memory a job may be given, in percent.
    pub max_memory_percent: u32,
}

impl Limits {
    /// The temperatures the window offers, lowest to highest.
    pub const TEMP_RANGE: (u32, u32) = (60, 90);
    /// The disk limits it offers.
    pub const DISK_RANGE: (u32, u32) = (20, 500);
    /// The memory shares it offers.
    pub const MEMORY_CHOICES: [u32; 4] = [25, 50, 75, 90];
    /// How far below the limit the GPU must cool before hosting carries on.
    pub const COOL_BY_C: u32 = 5;

    /// Whether the limits are allowed.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        (Self::TEMP_RANGE.0..=Self::TEMP_RANGE.1).contains(&self.max_gpu_temp_c)
            && (Self::DISK_RANGE.0..=Self::DISK_RANGE.1).contains(&self.max_disk_gb)
            && Self::MEMORY_CHOICES.contains(&self.max_memory_percent)
    }

    /// Whether a GPU at `temp_c` is too hot now. Once too hot it stays so until it has cooled [`Self::COOL_BY_C`] degrees
    /// below the limit (`was_hot`), so a laptop on the edge doesn't flap on and off every second.
    #[must_use]
    pub fn too_hot(&self, temp_c: Option<u32>, was_hot: bool) -> bool {
        let Some(temp) = temp_c else { return false };
        if was_hot { temp > self.max_gpu_temp_c.saturating_sub(Self::COOL_BY_C) } else { temp > self.max_gpu_temp_c }
    }

    /// The disk the scheduler may count on: what the laptop has free, held to what the Linux space may still grow into
    /// (`used_mb` of its [`Self::max_disk_gb`] already). Never below zero.
    #[must_use]
    pub fn disk_to_offer_mb(&self, free_mb: i32, used_mb: u64) -> i32 {
        let cap_mb = u64::from(self.max_disk_gb) * 1024;
        let room = i32::try_from(cap_mb.saturating_sub(used_mb)).unwrap_or(i32::MAX);
        free_mb.min(room).max(0)
    }

    /// The most memory a job may ask for, in MB, on a laptop with `total_mb`.
    #[must_use]
    pub fn memory_to_offer_mb(&self, total_mb: u64) -> u64 {
        total_mb * u64::from(self.max_memory_percent) / 100
    }
}

impl Default for Limits {
    fn default() -> Self {
        Self { max_gpu_temp_c: 83, max_disk_gb: 40, max_memory_percent: 50 }
    }
}

/// What may make a toast, by kind. All on by default; a problem that needs the host is never quiet unless turned off.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
#[allow(clippy::struct_excessive_bools)] // One switch per kind of notification.
pub struct Notifications {
    /// "You're live": linked and approved.
    pub live: bool,
    /// The first job.
    pub first_job: bool,
    /// A job paused because the owner came back, at most once a day.
    pub paused_when_back: bool,
    /// A payout sent.
    pub payout: bool,
    /// An update installed.
    pub update: bool,
    /// A problem that needs the host.
    pub problems: bool,
}

impl Default for Notifications {
    fn default() -> Self {
        Self { live: true, first_job: true, paused_when_back: true, payout: true, update: true, problems: true }
    }
}

/// The window's colours: Windows' own setting, or one the host picked.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Theme {
    /// Follow Windows' light or dark setting.
    #[default]
    System,
    /// Light.
    Light,
    /// Dark.
    Dark,
}

/// Everything the host chooses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
#[allow(clippy::struct_excessive_bools)] // One switch per choice.
pub struct HostSettings {
    /// What pauses hosting: the owner's activity, a game, unplugging, and how long to wait before starting again.
    pub auto_pause: AutoPause,
    /// When the laptop is available.
    pub schedule: Schedule,
    /// No jobs and no job image downloads on a metered connection.
    pub not_on_metered: bool,
    /// The heat, disk and memory limits.
    pub limits: Limits,
    /// Start IdleO when Windows starts.
    pub start_with_windows: bool,
    /// Which toasts to show.
    pub notifications: Notifications,
    /// The window's theme.
    pub theme: Theme,
}

impl Default for HostSettings {
    fn default() -> Self {
        Self {
            auto_pause: AutoPause::default(),
            schedule: Schedule::default(),
            not_on_metered: true,
            limits: Limits::default(),
            start_with_windows: true,
            notifications: Notifications::default(),
            theme: Theme::default(),
        }
    }
}

impl HostSettings {
    /// The same settings with what must agree made to: the owner-activity trigger follows the schedule's mode.
    #[must_use]
    pub fn normalized(mut self) -> Self {
        self.auto_pause.keyboard_mouse = self.schedule.mode != ScheduleMode::Always;
        self
    }

    /// Changes auto-pause the way an older tray did, keeping the schedule's mode in step: turning the keyboard-and-mouse
    /// trigger off is "Always", turning it back on is "when idle".
    #[must_use]
    pub fn with_auto_pause(mut self, auto_pause: AutoPause) -> Self {
        self.schedule.mode = match (auto_pause.keyboard_mouse, self.schedule.mode) {
            (false, _) => ScheduleMode::Always,
            (true, ScheduleMode::Always) => ScheduleMode::WhenIdle,
            (true, mode) => mode,
        };
        self.auto_pause = auto_pause;
        self.normalized()
    }

    /// Whether the settings are allowed.
    ///
    /// # Errors
    /// Which one isn't, in plain words.
    pub fn validate(&self) -> Result<(), String> {
        if !self.auto_pause.is_valid() {
            return Err("start again after must be 5, 10, 20, 30 or 60 minutes".to_owned());
        }
        if !self.schedule.is_valid() {
            return Err("every day's hours must start and end at different times within the day".to_owned());
        }
        if !self.limits.is_valid() {
            return Err("a limit is outside what the window offers".to_owned());
        }
        Ok(())
    }
}

#[cfg(test)]
#[allow(clippy::field_reassign_with_default, reason = "a test changes the one setting it is about")]
mod tests {
    use super::*;

    fn at(weekday: u8, hour: u16, minute: u16) -> Moment {
        Moment { weekday, minute: hour * 60 + minute }
    }

    fn hours(from: u16, to: u16) -> Schedule {
        Schedule { mode: ScheduleMode::Hours, days: [DayHours { on: true, from, to }; 7] }
    }

    #[test]
    fn always_and_when_idle_never_close_on_the_clock() {
        for mode in [ScheduleMode::Always, ScheduleMode::WhenIdle] {
            let schedule = Schedule { mode, days: [DayHours { on: false, from: 0, to: 60 }; 7] };
            assert!((0..7).all(|day| (0..24).all(|hour| schedule.open_at(at(day, hour, 0)))), "{mode:?}");
        }
    }

    #[test]
    fn a_daytime_window_is_open_from_its_start_up_to_its_end() {
        let schedule = hours(9 * 60, 17 * 60);
        assert!(!schedule.open_at(at(2, 8, 59)));
        assert!(schedule.open_at(at(2, 9, 0)));
        assert!(schedule.open_at(at(2, 16, 59)));
        assert!(!schedule.open_at(at(2, 17, 0)), "the end is not inside");
    }

    #[test]
    fn a_window_past_midnight_carries_into_the_next_weekday() {
        let mut schedule = hours(22 * 60, 7 * 60);
        // Only Friday has a window: 10 pm Friday to 7 am Saturday.
        schedule.days = [DayHours { on: false, from: 0, to: 0 }; 7];
        schedule.days[4] = DayHours { on: true, from: 22 * 60, to: 7 * 60 };
        assert!(!schedule.open_at(at(4, 21, 59)));
        assert!(schedule.open_at(at(4, 22, 0)));
        assert!(schedule.open_at(at(4, 23, 59)));
        assert!(schedule.open_at(at(5, 0, 0)), "Saturday's small hours belong to Friday's window");
        assert!(schedule.open_at(at(5, 6, 59)));
        assert!(!schedule.open_at(at(5, 7, 0)));
        assert!(!schedule.open_at(at(5, 22, 0)), "Saturday has no window of its own");
        assert!(!schedule.open_at(at(4, 3, 0)), "and Friday's own small hours are Thursday's, which is off");
    }

    #[test]
    fn sunday_night_runs_into_monday_morning() {
        let mut schedule = hours(23 * 60, 6 * 60);
        schedule.days = [DayHours { on: false, from: 0, to: 0 }; 7];
        schedule.days[6] = DayHours { on: true, from: 23 * 60, to: 6 * 60 };
        assert!(schedule.open_at(at(0, 5, 0)), "Monday 5 am, from Sunday's window");
        assert!(!schedule.open_at(at(0, 6, 0)));
    }

    #[test]
    fn a_window_to_midnight_ends_with_the_day() {
        let schedule = hours(18 * 60, DAY_MINUTES);
        assert!(schedule.open_at(at(1, 23, 59)));
        assert!(!schedule.open_at(at(2, 0, 0)));
    }

    #[test]
    fn it_says_how_long_until_the_schedule_opens() {
        let schedule = hours(22 * 60, 7 * 60);
        assert_eq!(schedule.opens_in_minutes(at(1, 21, 0)), Some(60));
        assert_eq!(schedule.opens_in_minutes(at(1, 23, 0)), None, "open now");
        let mut never = schedule;
        never.days = [DayHours { on: false, from: 0, to: 0 }; 7];
        assert_eq!(never.opens_in_minutes(at(1, 12, 0)), None, "never opens");
        let mut monday = never;
        monday.days[0] = DayHours { on: true, from: 9 * 60, to: 10 * 60 };
        assert_eq!(monday.opens_in_minutes(at(6, 9, 0)), Some(24 * 60), "Sunday 9 am to Monday 9 am");
    }

    #[test]
    fn a_moment_is_the_hosts_own_local_time() {
        let utc = time::OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap(); // a Thursday, 08:53 UTC
        let ist = time::UtcOffset::from_hms(5, 30, 0).unwrap();
        assert_eq!(Moment::of(utc, time::UtcOffset::UTC), Moment { weekday: 3, minute: 8 * 60 + 53 });
        assert_eq!(Moment::of(utc, ist), Moment { weekday: 3, minute: 14 * 60 + 23 });
        let late = time::OffsetDateTime::from_unix_timestamp(1_760_000_000 + 11 * 3600).unwrap(); // 19:53 UTC
        assert_eq!(Moment::of(late, ist), Moment { weekday: 4, minute: 60 + 23 }, "past midnight in India: Friday");
    }

    #[test]
    fn heat_pauses_above_the_limit_and_waits_to_cool_before_carrying_on() {
        let limits = Limits { max_gpu_temp_c: 83, ..Limits::default() };
        assert!(!limits.too_hot(Some(83), false), "at the limit is fine");
        assert!(limits.too_hot(Some(84), false));
        assert!(limits.too_hot(Some(80), true), "still above 78: held");
        assert!(limits.too_hot(Some(79), true));
        assert!(!limits.too_hot(Some(78), true), "cooled five degrees: carries on");
        assert!(!limits.too_hot(None, true), "no reading, no pause");
        assert!(!limits.too_hot(None, false));
    }

    #[test]
    fn the_disk_to_offer_is_held_to_what_the_linux_space_may_still_use() {
        let limits = Limits { max_disk_gb: 40, ..Limits::default() };
        assert_eq!(limits.disk_to_offer_mb(300_000, 10 * 1024), 30 * 1024, "30 GB of the 40 are left");
        assert_eq!(limits.disk_to_offer_mb(5_000, 10 * 1024), 5_000, "the laptop's own free space is less");
        assert_eq!(limits.disk_to_offer_mb(300_000, 50 * 1024), 0, "over the limit: nothing");
        assert_eq!(limits.disk_to_offer_mb(-5, 0), 0);
    }

    #[test]
    fn memory_is_a_share_of_the_laptops() {
        let limits = Limits { max_memory_percent: 50, ..Limits::default() };
        assert_eq!(limits.memory_to_offer_mb(16_384), 8_192);
        assert_eq!(Limits { max_memory_percent: 90, ..limits }.memory_to_offer_mb(32_000), 28_800);
    }

    #[test]
    fn the_defaults_are_valid_and_keep_hosting_as_it_was() {
        let settings = HostSettings::default();
        assert_eq!(settings.validate(), Ok(()));
        assert_eq!(settings.schedule.mode, ScheduleMode::WhenIdle);
        assert!(settings.auto_pause.keyboard_mouse && settings.auto_pause.games && settings.auto_pause.unplugged);
        assert_eq!(settings.normalized(), settings);
    }

    #[test]
    fn bad_settings_are_named() {
        let mut settings = HostSettings::default();
        settings.limits.max_gpu_temp_c = 120;
        assert!(settings.validate().unwrap_err().contains("limit"));
        let mut settings = HostSettings::default();
        settings.schedule.days[2] = DayHours { on: true, from: 600, to: 600 };
        assert!(settings.validate().unwrap_err().contains("hours"));
        // A day that is off isn't checked.
        settings.schedule.days[2].on = false;
        assert_eq!(settings.validate(), Ok(()));
        let mut settings = HostSettings::default();
        settings.auto_pause.start_again_after_minutes = 7;
        assert!(settings.validate().is_err());
    }

    #[test]
    fn the_owner_activity_trigger_follows_the_schedule_mode() {
        let mut settings = HostSettings::default();
        settings.schedule.mode = ScheduleMode::Always;
        assert!(!settings.normalized().auto_pause.keyboard_mouse);
        settings.schedule.mode = ScheduleMode::Hours;
        assert!(settings.normalized().auto_pause.keyboard_mouse);
    }

    #[test]
    fn an_older_trays_auto_pause_change_keeps_the_mode_in_step() {
        let settings = HostSettings::default();
        let off = settings.with_auto_pause(AutoPause { keyboard_mouse: false, ..settings.auto_pause });
        assert_eq!(off.schedule.mode, ScheduleMode::Always);
        let on = off.with_auto_pause(AutoPause { keyboard_mouse: true, ..off.auto_pause });
        assert_eq!(on.schedule.mode, ScheduleMode::WhenIdle);
        // In Hours mode, leaving the trigger on changes nothing.
        let hours = HostSettings { schedule: Schedule { mode: ScheduleMode::Hours, ..settings.schedule }, ..settings };
        assert_eq!(hours.with_auto_pause(hours.auto_pause).schedule.mode, ScheduleMode::Hours);
    }

    #[test]
    fn an_older_settings_file_or_message_still_reads_with_the_new_choices_at_their_defaults() {
        let older =
            r#"{"auto_pause":{"keyboard_mouse":true,"games":false,"unplugged":true,"start_again_after_minutes":30}}"#;
        let read: HostSettings = serde_json::from_str(older).unwrap();
        assert!(!read.auto_pause.games);
        assert_eq!(read.auto_pause.start_again_after_minutes, 30);
        assert_eq!(read.limits, Limits::default());
        assert_eq!(read.schedule, Schedule::default());
        assert!(read.notifications.problems && read.not_on_metered && read.start_with_windows);
        let round: HostSettings = serde_json::from_str(&serde_json::to_string(&read).unwrap()).unwrap();
        assert_eq!(round, read);
    }
}
