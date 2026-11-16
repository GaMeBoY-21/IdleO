//! What the tray says, as pure functions of the agent's status (ADR 0033, ADR 0061): the state (and so the icon), the big
//! words, the line under them, the tooltip and the earnings. Each is tested for every state.
//!
//! There are seven states, each with its own icon: ready, running a job, paused, waiting for IdleO's checks, offline, a
//! problem, and an update ready to install.

use idleo_brand::Glyph;
use idleo_release::channel::{LinkState, Prefetch, Problem, Reason, State, Status, UpdatePhase};
use time::{OffsetDateTime, UtcOffset};

/// The icon's colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Colour {
    /// Available or earning: volt, #C8FF3D.
    Volt,
    /// Paused or offline: grey, #8A8F98.
    Grey,
    /// Waiting for approval: the warning colour, #D9A400.
    Amber,
    /// A problem: red, #E5484D.
    Red,
}

impl Colour {
    /// Red, green and blue.
    #[must_use]
    pub fn rgb(self) -> [u8; 3] {
        match self {
            Self::Volt => [0xC8, 0xFF, 0x3D],
            Self::Grey => [0x8A, 0x8F, 0x98],
            Self::Amber => [0xD9, 0xA4, 0x00],
            Self::Red => [0xE5, 0x48, 0x4D],
        }
    }

    /// Its name, for the window's page.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::Volt => "volt",
            Self::Grey => "grey",
            Self::Amber => "amber",
            Self::Red => "red",
        }
    }
}

/// Where the laptop stands, in the tray's seven words.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayState {
    /// Ready for renters.
    Ready,
    /// Running a renter's job.
    Running,
    /// Paused: by the host, by the schedule or a limit, or because the host is using the laptop.
    Paused,
    /// Linked, waiting for IdleO's checks.
    WaitingForChecks,
    /// The agent isn't running yet, or IdleO can't be reached.
    Offline,
    /// Something needs the host.
    Problem,
    /// An update is ready, and installs at the next idle moment.
    UpdateReady,
}

impl TrayState {
    /// Every state.
    pub const ALL: [Self; 7] = [
        Self::Ready,
        Self::Running,
        Self::Paused,
        Self::WaitingForChecks,
        Self::Offline,
        Self::Problem,
        Self::UpdateReady,
    ];

    /// The icon's colour.
    #[must_use]
    pub fn colour(self) -> Colour {
        match self {
            Self::Ready | Self::Running | Self::UpdateReady => Colour::Volt,
            Self::Paused | Self::Offline => Colour::Grey,
            Self::WaitingForChecks => Colour::Amber,
            Self::Problem => Colour::Red,
        }
    }

    /// The shape inside the icon.
    #[must_use]
    pub fn glyph(self) -> Glyph {
        match self {
            Self::Ready => Glyph::Ring,
            Self::Running => Glyph::Play,
            Self::Paused => Glyph::Pause,
            Self::WaitingForChecks => Glyph::Clock,
            Self::Offline => Glyph::Cross,
            Self::Problem => Glyph::Exclaim,
            Self::UpdateReady => Glyph::Up,
        }
    }

    /// Its name, for the window's page and the icon pictures.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::Ready => "ready",
            Self::Running => "running",
            Self::Paused => "paused",
            Self::WaitingForChecks => "waiting",
            Self::Offline => "offline",
            Self::Problem => "problem",
            Self::UpdateReady => "update",
        }
    }
}

/// The state for a status. `crashing` is the launcher's word that the agent or the tray keeps crashing. `None` is the agent
/// not running yet, or its channel dropped.
#[must_use]
pub fn state(status: Option<&Status>, crashing: bool) -> TrayState {
    let Some(status) = status else { return if crashing { TrayState::Problem } else { TrayState::Offline } };
    if crashing || status.problem.is_some() || matches!(status.link_state, LinkState::Removed | LinkState::Suspended) {
        return TrayState::Problem;
    }
    if status.session_running {
        return TrayState::Running;
    }
    if status.link_state == LinkState::PendingApproval && status.server_reachable {
        return TrayState::WaitingForChecks;
    }
    if !status.server_reachable {
        return TrayState::Offline;
    }
    if status.state != State::Available {
        return TrayState::Paused;
    }
    if status.update == UpdatePhase::Ready { TrayState::UpdateReady } else { TrayState::Ready }
}

/// What a problem is, and what to do about it: the big words and the one sentence under them.
#[must_use]
pub fn problem_words(problem: Problem) -> (&'static str, &'static str) {
    match problem {
        Problem::DriverTooOld => {
            ("Update your NVIDIA driver", "IdleO needs a newer graphics driver to run renters' jobs.")
        }
        Problem::DiskNearlyFull => ("Your disk is nearly full", "Free up some space so IdleO can keep working."),
        Problem::TooHot => (
            "Your GPU is too hot",
            "IdleO paused hosting. Check the laptop's vents are clear and it isn't on a soft surface.",
        ),
        Problem::OfflineTooLong => ("Offline for over an hour", "Check this laptop's internet connection."),
        Problem::Crashing => ("IdleO keeps stopping", "Send diagnostics, so we can find out why."),
    }
}

/// The big words: what the laptop is doing now. `None` means the agent isn't running or the channel dropped.
#[must_use]
pub fn headline(status: Option<&Status>, now: OffsetDateTime, offset: UtcOffset, crashing: bool) -> String {
    let Some(status) = status else {
        return if crashing { problem_words(Problem::Crashing).0.to_owned() } else { "Starting…".to_owned() };
    };
    match status.link_state {
        LinkState::Removed => return "Removed from your account · uninstall the app".to_owned(),
        LinkState::Suspended => return "Hosting is suspended · contact IdleO".to_owned(),
        _ => {}
    }
    if crashing {
        return problem_words(Problem::Crashing).0.to_owned();
    }
    if let Some(problem) = status.problem {
        return problem_words(problem).0.to_owned();
    }
    if status.session_running {
        let minutes = status.live.and_then(|live| live.job_started_at).map(|at| (now - at).whole_minutes());
        return match minutes {
            Some(minutes) if minutes >= 1 => format!("Running a job · {minutes} min"),
            _ => "Running a job".to_owned(),
        };
    }
    if !status.server_reachable {
        return "Offline · reconnecting".to_owned();
    }
    if status.link_state == LinkState::PendingApproval {
        return "Waiting for our checks".to_owned();
    }
    match status.state {
        State::HostingOff => return "Paused · until you resume".to_owned(),
        State::Paused => {
            return status
                .paused_until
                .map_or_else(|| "Paused".to_owned(), |until| format!("Paused until {}", clock(until, offset)));
        }
        State::AutoPaused => {
            return match status.reason {
                Some(Reason::KeyboardMouse) | None => "Paused · you're using the laptop".to_owned(),
                Some(Reason::Game) => "Paused · a game or full-screen app is open".to_owned(),
                Some(Reason::GpuBusy) => "Paused · another program is using the GPU".to_owned(),
                Some(Reason::Unplugged) => "Paused · on battery".to_owned(),
                Some(Reason::Schedule) => "Paused · outside your hours".to_owned(),
                Some(Reason::TooHot) => "Paused · the GPU is too hot".to_owned(),
                Some(Reason::Metered) => "Paused · on a metered connection".to_owned(),
                Some(Reason::DiskFull) => "Paused · the disk limit is reached".to_owned(),
            };
        }
        State::Available => {}
    }
    if !status.runner_installed {
        return "Linked · getting the job runner ready".to_owned();
    }
    match status.prefetch {
        Some(Prefetch::Downloading { done_bytes, total_bytes, .. }) => {
            let done = gigabytes(done_bytes);
            if total_bytes > 0 {
                format!("Getting ready for renters · {done} of {} GB", gigabytes(total_bytes))
            } else {
                format!("Getting ready for renters · {done} GB downloaded")
            }
        }
        Some(Prefetch::WaitingForUnmetered) => "Waiting for an unmetered connection to download".to_owned(),
        None => "Ready for renters".to_owned(),
    }
}

/// The line under the big words, where there is more to say.
#[must_use]
pub fn detail(status: Option<&Status>, now: OffsetDateTime, offset: UtcOffset, crashing: bool) -> Option<String> {
    if crashing {
        return Some(problem_words(Problem::Crashing).1.to_owned());
    }
    let status = status?;
    if let Some(problem) = status.problem {
        return Some(problem_words(problem).1.to_owned());
    }
    if status.session_running {
        return status.earning_now_paise.map(|paise| format!("Earning {} on this job so far", rupees(paise)));
    }
    if !status.server_reachable {
        return last_updated(status, now);
    }
    match (status.state, status.reason) {
        (State::AutoPaused, Some(Reason::KeyboardMouse) | None) => {
            Some(format!("Starts again {} min after you stop", status.auto_pause.start_again_after_minutes))
        }
        (State::AutoPaused, Some(Reason::Schedule)) => {
            status.resumes_at.map(|at| format!("Back at {}", clock(at, offset)))
        }
        (State::AutoPaused, Some(Reason::Game | Reason::GpuBusy)) => Some("Starts again after it closes".to_owned()),
        (State::AutoPaused, Some(Reason::TooHot)) => Some("Carries on by itself once it has cooled".to_owned()),
        (State::AutoPaused, Some(Reason::Metered)) => Some("Carries on by itself on another connection".to_owned()),
        (State::AutoPaused, Some(Reason::DiskFull)) => Some("Raise the limit in Limits, or free up space".to_owned()),
        (State::AutoPaused, Some(Reason::Unplugged)) => Some("Plug in to carry on".to_owned()),
        (State::HostingOff, _) => Some("Turn the switch off to carry on".to_owned()),
        (State::Available, _) if status.link_state == LinkState::PendingApproval => {
            Some("We check every new laptop before it goes live. You'll get an email.".to_owned())
        }
        _ => None,
    }
}

/// "Last updated 12 min ago", for what was last heard from IdleO, once it's been a while or IdleO can't be reached.
#[must_use]
pub fn last_updated(status: &Status, now: OffsetDateTime) -> Option<String> {
    let at =
        status.earnings.as_ref().map(|earnings| earnings.updated_at).into_iter().chain(status.server_seen_at).max()?;
    Some(format!("Last updated {}", ago(now - at)))
}

/// A while ago, in words.
#[must_use]
pub fn ago(elapsed: time::Duration) -> String {
    let minutes = elapsed.whole_minutes();
    match minutes {
        i64::MIN..=0 => "just now".to_owned(),
        1 => "1 min ago".to_owned(),
        2..=59 => format!("{minutes} min ago"),
        60..=119 => "1 hour ago".to_owned(),
        120..=1439 => format!("{} hours ago", minutes / 60),
        1440..=2879 => "yesterday".to_owned(),
        _ => format!("{} days ago", minutes / 1440),
    }
}

/// Bytes as gigabytes with one decimal, decimal gigabytes like the disk sizes the owner sees ("2.1", "7.8").
fn gigabytes(bytes: u64) -> String {
    let tenths = (bytes + 50_000_000) / 100_000_000;
    format!("{}.{}", tenths / 10, tenths % 10)
}

/// The tooltip: the big words, starting "Dev · " in a development build.
#[must_use]
pub fn tooltip(status: Option<&Status>, now: OffsetDateTime, offset: UtcOffset, crashing: bool) -> String {
    let dev = status.map_or(idleo_release::keys::DEV_BUILD, |status| status.dev_build);
    let line = headline(status, now, offset, crashing);
    if dev { format!("{}{line}", idleo_release::keys::DEV_PREFIX) } else { line }
}

/// "Earned today ₹12.40", once the control plane has said.
#[must_use]
pub fn earned_today(status: Option<&Status>) -> Option<String> {
    status.and_then(|status| status.earned_today_paise).map(|paise| format!("Earned today {}", rupees(paise)))
}

/// Where an update stands, while one is under way: none when there's nothing to do. An update never interrupts a
/// renter's job, so "ready" says it waits for an idle moment.
#[must_use]
pub fn update_line(status: Option<&Status>) -> Option<String> {
    match status?.update {
        UpdatePhase::Idle => None,
        UpdatePhase::Downloading => Some("Downloading an update".to_owned()),
        UpdatePhase::Ready => Some("Update ready · installs when idle".to_owned()),
        UpdatePhase::Installing => Some("Installing an update".to_owned()),
    }
}

/// Integer paise as rupees, the way the web app and the control plane write them (`api/money-format.json`): Indian
/// grouping, two decimals, a real minus sign.
#[must_use]
pub fn rupees(paise: i64) -> String {
    let sign = if paise < 0 { "−" } else { "" };
    let paise = paise.unsigned_abs();
    let whole = (paise / 100).to_string();
    let grouped = if whole.len() <= 3 {
        whole
    } else {
        let (head, last3) = whole.split_at(whole.len() - 3);
        let mut groups = Vec::new();
        let mut rest = head;
        while rest.len() > 2 {
            let (left, right) = rest.split_at(rest.len() - 2);
            groups.push(right);
            rest = left;
        }
        groups.push(rest);
        groups.reverse();
        format!("{},{last3}", groups.join(","))
    };
    format!("{sign}₹{grouped}.{:02}", paise % 100)
}

/// A time as the tray shows it: "6:00 pm", in local time.
#[must_use]
pub fn clock(at: OffsetDateTime, offset: UtcOffset) -> String {
    let local = at.to_offset(offset);
    let (hour, suffix) = match local.hour() {
        0 => (12, "am"),
        hour @ 1..=11 => (hour, "am"),
        12 => (12, "pm"),
        hour => (hour - 12, "pm"),
    };
    format!("{hour}:{:02} {suffix}", local.minute())
}

#[cfg(test)]
#[allow(
    clippy::assert_is_empty,
    clippy::field_reassign_with_default,
    clippy::duration_suboptimal_units,
    reason = "tests state the empty case and a settings change as plainly as they can"
)]
pub(crate) mod tests {
    use idleo_release::channel::{AutoPause, Earnings, Live};
    use idleo_release::settings::HostSettings;

    use super::*;

    pub(crate) fn ready() -> Status {
        Status {
            state: State::Available,
            reason: None,
            resumes_at: None,
            paused_until: None,
            earned_today_paise: Some(1240),
            earning_now_paise: None,
            session_running: false,
            link_state: LinkState::Active,
            server_reachable: true,
            update: UpdatePhase::Idle,
            runner_installed: true,
            dev_build: false,
            auto_pause: AutoPause::default(),
            app_url: None,
            host_url: Some("https://host.idleo.in".to_owned()),
            prefetch: None,
            site_url: Some("https://idleo.in".to_owned()),
            settings: HostSettings::default(),
            live: None,
            earnings: None,
            problem: None,
            server_seen_at: None,
            version: "0.2.0".to_owned(),
            updated_to: None,
        }
    }

    pub(crate) fn at() -> OffsetDateTime {
        OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap()
    }

    fn words(status: &Status) -> String {
        headline(Some(status), at(), UtcOffset::UTC, false)
    }

    fn paused_by(reason: Reason) -> Status {
        Status { state: State::AutoPaused, reason: Some(reason), ..ready() }
    }

    #[test]
    fn the_seven_states_are_told_apart() {
        assert_eq!(state(Some(&ready()), false), TrayState::Ready);
        assert_eq!(state(Some(&Status { session_running: true, ..ready() }), false), TrayState::Running);
        assert_eq!(state(Some(&paused_by(Reason::KeyboardMouse)), false), TrayState::Paused);
        assert_eq!(state(Some(&Status { state: State::HostingOff, ..ready() }), false), TrayState::Paused);
        assert_eq!(
            state(Some(&Status { link_state: LinkState::PendingApproval, ..ready() }), false),
            TrayState::WaitingForChecks
        );
        assert_eq!(state(Some(&Status { server_reachable: false, ..ready() }), false), TrayState::Offline);
        assert_eq!(state(None, false), TrayState::Offline);
        assert_eq!(state(Some(&Status { problem: Some(Problem::TooHot), ..ready() }), false), TrayState::Problem);
        assert_eq!(state(Some(&Status { link_state: LinkState::Removed, ..ready() }), false), TrayState::Problem);
        assert_eq!(state(Some(&Status { update: UpdatePhase::Ready, ..ready() }), false), TrayState::UpdateReady);
        // An update waiting never hides what the laptop is doing.
        let busy = Status { update: UpdatePhase::Ready, session_running: true, ..ready() };
        assert_eq!(state(Some(&busy), false), TrayState::Running);
        // A crash loop is a problem whatever else is true, even with no agent to ask.
        assert_eq!(state(None, true), TrayState::Problem);
        assert_eq!(state(Some(&ready()), true), TrayState::Problem);
    }

    #[test]
    fn a_job_waits_for_checks_only_while_reachable_and_offline_comes_before_waiting() {
        let pending_offline = Status { link_state: LinkState::PendingApproval, server_reachable: false, ..ready() };
        assert_eq!(state(Some(&pending_offline), false), TrayState::Offline);
    }

    #[test]
    fn every_state_has_a_colour_a_glyph_and_a_name_of_its_own() {
        let glyphs: std::collections::HashSet<_> = TrayState::ALL.iter().map(|state| state.name()).collect();
        assert_eq!(glyphs.len(), 7);
        for (i, a) in TrayState::ALL.iter().enumerate() {
            for b in &TrayState::ALL[i + 1..] {
                assert_ne!(a.glyph(), b.glyph(), "{a:?} and {b:?} share a shape");
            }
        }
        assert_eq!(TrayState::Problem.colour(), Colour::Red);
        assert_eq!(TrayState::WaitingForChecks.colour(), Colour::Amber);
        assert_eq!(TrayState::Ready.colour(), Colour::Volt);
    }

    #[test]
    fn the_big_words_say_what_the_laptop_is_doing() {
        assert_eq!(words(&ready()), "Ready for renters");
        assert_eq!(words(&Status { link_state: LinkState::PendingApproval, ..ready() }), "Waiting for our checks");
        assert_eq!(words(&Status { server_reachable: false, ..ready() }), "Offline · reconnecting");
        assert_eq!(words(&Status { state: State::HostingOff, ..ready() }), "Paused · until you resume");
        assert_eq!(headline(None, at(), UtcOffset::UTC, false), "Starting…");
        assert_eq!(headline(None, at(), UtcOffset::UTC, true), "IdleO keeps stopping");
        assert_eq!(words(&paused_by(Reason::KeyboardMouse)), "Paused · you're using the laptop");
        assert_eq!(words(&paused_by(Reason::Game)), "Paused · a game or full-screen app is open");
        assert_eq!(words(&paused_by(Reason::Unplugged)), "Paused · on battery");
        assert_eq!(words(&paused_by(Reason::Schedule)), "Paused · outside your hours");
        assert_eq!(words(&paused_by(Reason::TooHot)), "Paused · the GPU is too hot");
        assert_eq!(words(&paused_by(Reason::Metered)), "Paused · on a metered connection");
        assert_eq!(words(&paused_by(Reason::DiskFull)), "Paused · the disk limit is reached");
        let until = at() + std::time::Duration::from_secs(3600);
        let paused = Status { state: State::Paused, paused_until: Some(until), ..ready() };
        assert_eq!(words(&paused), "Paused until 9:53 am");
        assert_eq!(
            words(&Status { link_state: LinkState::Suspended, ..ready() }),
            "Hosting is suspended · contact IdleO"
        );
        assert_eq!(
            words(&Status { link_state: LinkState::Removed, ..ready() }),
            "Removed from your account · uninstall the app"
        );
    }

    #[test]
    fn a_running_job_says_how_long_and_never_what_it_is() {
        let started = at() - std::time::Duration::from_secs(23 * 60 + 10);
        let running = Status {
            session_running: true,
            earning_now_paise: Some(4_200),
            live: Some(Live { gpu_temp_c: Some(70), gpu_util_pct: Some(99), job_started_at: Some(started) }),
            ..ready()
        };
        assert_eq!(words(&running), "Running a job · 23 min");
        assert_eq!(
            detail(Some(&running), at(), UtcOffset::UTC, false).as_deref(),
            Some("Earning ₹42.00 on this job so far")
        );
        let just_now =
            Status { live: Some(Live { job_started_at: Some(at()), gpu_temp_c: None, gpu_util_pct: None }), ..running };
        assert_eq!(words(&just_now), "Running a job");
    }

    #[test]
    fn every_problem_has_words_and_a_fix_and_outranks_the_rest() {
        for problem in [
            Problem::DriverTooOld,
            Problem::DiskNearlyFull,
            Problem::TooHot,
            Problem::OfflineTooLong,
            Problem::Crashing,
        ] {
            let (title, fix) = problem_words(problem);
            assert!(!title.is_empty() && fix.ends_with('.'), "{problem:?}");
            let status = Status { problem: Some(problem), session_running: true, ..ready() };
            assert_eq!(words(&status), title);
            assert_eq!(detail(Some(&status), at(), UtcOffset::UTC, false).as_deref(), Some(fix));
        }
    }

    #[test]
    fn the_line_under_the_words_helps_where_there_is_something_to_say() {
        let detail_of = |status: &Status| detail(Some(status), at(), UtcOffset::UTC, false);
        assert_eq!(detail_of(&ready()), None);
        assert_eq!(detail_of(&paused_by(Reason::KeyboardMouse)).as_deref(), Some("Starts again 10 min after you stop"));
        let opens =
            Status { resumes_at: Some(at() + std::time::Duration::from_secs(3600)), ..paused_by(Reason::Schedule) };
        assert_eq!(detail_of(&opens).as_deref(), Some("Back at 9:53 am"));
        assert_eq!(detail_of(&paused_by(Reason::TooHot)).as_deref(), Some("Carries on by itself once it has cooled"));
        assert_eq!(detail_of(&paused_by(Reason::Unplugged)).as_deref(), Some("Plug in to carry on"));
        assert!(detail_of(&Status { link_state: LinkState::PendingApproval, ..ready() }).unwrap().contains("email"));
    }

    #[test]
    fn offline_says_when_it_last_heard_from_idleo() {
        let seen = at() - std::time::Duration::from_secs(12 * 60);
        let offline = Status { server_reachable: false, server_seen_at: Some(seen), ..ready() };
        assert_eq!(detail(Some(&offline), at(), UtcOffset::UTC, false).as_deref(), Some("Last updated 12 min ago"));
        let kept = Earnings {
            today_paise: 1,
            week_paise: None,
            month_paise: None,
            recent: Vec::new(),
            next_payout_on: None,
            last_payout: None,
            updated_at: at() - std::time::Duration::from_secs(3 * 3600),
        };
        let only_earnings = Status { server_reachable: false, earnings: Some(kept), ..ready() };
        assert_eq!(
            detail(Some(&only_earnings), at(), UtcOffset::UTC, false).as_deref(),
            Some("Last updated 3 hours ago")
        );
        assert_eq!(detail(Some(&Status { server_reachable: false, ..ready() }), at(), UtcOffset::UTC, false), None);
    }

    #[test]
    fn ages_are_in_plain_words() {
        let age = |minutes| ago(time::Duration::minutes(minutes));
        assert_eq!(
            [age(0), age(1), age(45), age(60), age(125), age(1500), age(3000)],
            ["just now", "1 min ago", "45 min ago", "1 hour ago", "2 hours ago", "yesterday", "2 days ago"]
        );
    }

    #[test]
    fn the_download_is_told_in_gigabytes() {
        let downloading = Status {
            prefetch: Some(Prefetch::Downloading {
                done_bytes: 2_100_000_000,
                total_bytes: 7_800_000_000,
                image: 1,
                images: 2,
            }),
            ..ready()
        };
        assert_eq!(words(&downloading), "Getting ready for renters · 2.1 of 7.8 GB");
        assert_eq!(
            words(&Status { prefetch: Some(Prefetch::WaitingForUnmetered), ..ready() }),
            "Waiting for an unmetered connection to download"
        );
        assert_eq!(words(&Status { runner_installed: false, ..ready() }), "Linked · getting the job runner ready");
    }

    #[test]
    fn the_tooltip_is_the_words_and_a_development_build_says_so() {
        assert_eq!(tooltip(Some(&ready()), at(), UtcOffset::UTC, false), "Ready for renters");
        assert!(
            tooltip(Some(&Status { dev_build: true, ..ready() }), at(), UtcOffset::UTC, false)
                .starts_with(idleo_release::keys::DEV_PREFIX)
        );
        for state in [ready(), paused_by(Reason::Schedule), Status { problem: Some(Problem::TooHot), ..ready() }] {
            assert!(
                tooltip(Some(&state), at(), UtcOffset::UTC, false).chars().count() < 127,
                "Windows cuts a tooltip at 127"
            );
        }
    }

    #[test]
    fn rupees_are_written_the_way_the_web_app_writes_them() {
        assert_eq!(rupees(1240), "₹12.40");
        assert_eq!(rupees(0), "₹0.00");
        assert_eq!(rupees(123_456_789), "₹12,34,567.89");
        assert_eq!(rupees(-5), "−₹0.05");
        assert_eq!(earned_today(Some(&ready())).as_deref(), Some("Earned today ₹12.40"));
        assert_eq!(earned_today(None), None);
    }

    #[test]
    fn times_are_in_the_hosts_own_clock() {
        let ist = UtcOffset::from_hms(5, 30, 0).unwrap();
        assert_eq!(clock(at(), ist), "2:23 pm");
        assert_eq!(clock(at(), UtcOffset::UTC), "8:53 am");
        assert_eq!(clock(at() + std::time::Duration::from_secs(3 * 3600 + 7 * 60), UtcOffset::UTC), "12:00 pm");
    }

    #[test]
    fn an_update_under_way_is_said() {
        assert_eq!(update_line(Some(&ready())), None);
        assert_eq!(
            update_line(Some(&Status { update: UpdatePhase::Ready, ..ready() })).as_deref(),
            Some("Update ready · installs when idle")
        );
        assert_eq!(
            update_line(Some(&Status { update: UpdatePhase::Installing, ..ready() })).as_deref(),
            Some("Installing an update")
        );
    }
}
