//! The IdleO window's model (ADR 0061): what Rust shows the page ([`WindowState`], as JSON) and what the page asks back
//! ([`FromWindow`]), and what each ask means. The page (`assets/`) draws and nothing else: every word, number and money
//! amount in it is worked out here, so the figures are tested and the same as the tray's.
//!
//! Five tabs: **Now** (the state, one pause switch, earnings, the next payout, and the GPU while a job runs), **Schedule**,
//! **Limits**, **Earnings** and **Settings**. The settings travel whole, both ways, and the agent stores them.

use idleo_release::channel::{Earnings, Problem, Request, Status};
use idleo_release::settings::{HostSettings, Theme};
use serde::{Deserialize, Serialize};
use time::{OffsetDateTime, UtcOffset};

use crate::menu::{Action, PauseFor, paused_by_host};
use crate::status::{self, TrayState, clock, rupees};

/// The window's tabs, by the names the page and `idleo:open/<tab>` use.
pub const TABS: [&str; 5] = ["now", "schedule", "limits", "earnings", "settings"];
/// Where the NVIDIA driver is had, for "Update your NVIDIA driver".
pub const NVIDIA_DRIVERS: &str = "https://www.nvidia.com/en-us/software/nvidia-app/";
/// Windows' own storage page, for "Your disk is nearly full".
pub const STORAGE_SETTINGS: &str = "ms-settings:storagesense";

/// One finished job in the list: when, how long, what it paid.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct JobView {
    /// "Today 3:20 pm", "Yesterday 9:05 pm", "12 Nov".
    pub when: String,
    /// "23 min".
    pub length: String,
    /// "₹4.20".
    pub earned: String,
}

/// The earnings, in words.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Default)]
pub struct EarningsView {
    /// Today, week and month; none until IdleO has said.
    pub today: Option<String>,
    /// This week.
    pub week: Option<String>,
    /// This month.
    pub month: Option<String>,
    /// "Monday 23 November": the next payout.
    pub next_payout: Option<String>,
    /// "₹999.00 on 16 November": the latest payout's share of this laptop's earnings, before deductions.
    pub last_payout: Option<String>,
    /// The last few jobs.
    pub recent: Vec<JobView>,
    /// "Last updated 12 min ago", while IdleO can't be reached or it's been a while.
    pub last_updated: Option<String>,
}

/// The GPU while a job runs: nothing of the renter's.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct LiveView {
    /// "71 °C".
    pub temp: Option<String>,
    /// "98%".
    pub util: Option<String>,
}

/// A problem, and the one button that fixes it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ProblemView {
    /// The big words.
    pub title: String,
    /// What to do.
    pub fix: String,
    /// The button, if something can be opened: `driver`, `storage` or `diagnostics`.
    pub action: Option<&'static str>,
    /// The button's words.
    pub button: Option<&'static str>,
}

/// A pause choice for the Now tab's switch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ChoiceView {
    /// What the page sends back.
    pub id: &'static str,
    /// Its words.
    pub label: &'static str,
}

/// Everything the page draws.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[allow(clippy::struct_excessive_bools)] // Facts the page shows, each its own.
pub struct WindowState {
    /// `ready`, `running`, `paused`, `waiting`, `offline`, `problem` or `update`.
    pub state: &'static str,
    /// The state's colour name.
    pub colour: &'static str,
    /// The big words.
    pub headline: String,
    /// The line under them.
    pub detail: Option<String>,
    /// The host has paused hosting: the switch is on.
    pub paused: bool,
    /// How to pause.
    pub pause_choices: Vec<ChoiceView>,
    /// The earnings.
    pub earnings: EarningsView,
    /// The GPU, while a job runs.
    pub live: Option<LiveView>,
    /// A problem, if there is one.
    pub problem: Option<ProblemView>,
    /// What the host chose.
    pub settings: HostSettings,
    /// What "50% of memory" comes to, in GB, if the laptop's memory is known.
    pub memory_gb: Option<u64>,
    /// The agent is reachable (false: the settings can't be changed now).
    pub connected: bool,
    /// The app's version.
    pub version: String,
    /// "Updated to v0.2.0", for a week after an update.
    pub updated_to: Option<String>,
    /// The update channel, in words.
    pub channel: String,
    /// A development build.
    pub dev_build: bool,
    /// The host site can be opened.
    pub has_host_site: bool,
    /// The window's theme, for the page's `data-theme`.
    pub theme: &'static str,
}

fn date_words(date: &str) -> Option<String> {
    let parsed = time::Date::parse(date, &time::macros::format_description!("[year]-[month]-[day]")).ok()?;
    Some(format!("{} {} {}", parsed.weekday(), parsed.day(), parsed.month()))
}

fn job_when(at: OffsetDateTime, now: OffsetDateTime, offset: UtcOffset) -> String {
    let (local, today) = (at.to_offset(offset), now.to_offset(offset));
    let days = (today.date() - local.date()).whole_days();
    match days {
        0 => format!("Today {}", clock(at, offset)),
        1 => format!("Yesterday {}", clock(at, offset)),
        _ => format!("{} {}", local.day(), local.month()),
    }
}

fn job_length(seconds: u32) -> String {
    match seconds {
        0..=59 => format!("{seconds} s"),
        60..=3599 => format!("{} min", seconds / 60),
        _ => format!("{} h {} min", seconds / 3600, seconds % 3600 / 60),
    }
}

fn earnings_view(status: &Status, now: OffsetDateTime, offset: UtcOffset) -> EarningsView {
    let Some(earnings) = status.earnings.as_ref() else {
        return EarningsView { today: status.earned_today_paise.map(rupees), ..EarningsView::default() };
    };
    let Earnings { today_paise, week_paise, month_paise, recent, next_payout_on, last_payout, updated_at } = earnings;
    let stale = !status.server_reachable || now - *updated_at > time::Duration::minutes(30);
    EarningsView {
        today: Some(rupees(*today_paise)),
        week: week_paise.map(rupees),
        month: month_paise.map(rupees),
        next_payout: next_payout_on.as_deref().and_then(date_words),
        last_payout: last_payout.map(|payout| {
            let local = payout.sent_at.to_offset(offset);
            format!("{} on {} {}", rupees(payout.amount_paise), local.day(), local.month())
        }),
        recent: recent
            .iter()
            .map(|job| JobView {
                when: job_when(job.ended_at, now, offset),
                length: job_length(job.seconds),
                earned: rupees(job.earned_paise),
            })
            .collect(),
        last_updated: stale.then(|| format!("Last updated {}", status::ago(now - *updated_at))),
    }
}

fn problem_view(problem: Problem) -> ProblemView {
    let (title, fix) = status::problem_words(problem);
    let (action, button) = match problem {
        Problem::DriverTooOld => (Some("driver"), Some("Get the driver")),
        Problem::DiskNearlyFull => (Some("storage"), Some("Open Storage settings")),
        Problem::Crashing => (Some("diagnostics"), Some("Send diagnostics")),
        Problem::TooHot | Problem::OfflineTooLong => (None, None),
    };
    ProblemView { title: title.to_owned(), fix: fix.to_owned(), action, button }
}

/// What the window shows for `status` at `now`. `ram_mb` is the laptop's memory, if known; `crashing` the launcher's word.
#[must_use]
pub fn view(
    status: Option<&Status>,
    now: OffsetDateTime,
    offset: UtcOffset,
    crashing: bool,
    ram_mb: Option<u64>,
) -> WindowState {
    let state = status::state(status, crashing);
    let settings = status.map_or_else(HostSettings::default, |status| status.settings);
    let problem = if crashing { Some(Problem::Crashing) } else { status.and_then(|status| status.problem) };
    WindowState {
        state: state.name(),
        colour: state.colour().name(),
        headline: status::headline(status, now, offset, crashing),
        detail: status::detail(status, now, offset, crashing),
        paused: status.is_some_and(paused_by_host),
        pause_choices: [PauseFor::Hour, PauseFor::Tomorrow, PauseFor::UntilResumed]
            .into_iter()
            .map(|choice| ChoiceView { id: choice.id(), label: choice.label() })
            .collect(),
        earnings: status.map(|status| earnings_view(status, now, offset)).unwrap_or_default(),
        live: status.filter(|status| status.session_running).and_then(|status| status.live).map(|live| LiveView {
            temp: live.gpu_temp_c.map(|temp| format!("{temp} °C")),
            util: live.gpu_util_pct.map(|util| format!("{util}%")),
        }),
        problem: problem.map(problem_view),
        settings,
        memory_gb: ram_mb.map(|mb| (mb * u64::from(settings.limits.max_memory_percent) / 100).div_ceil(1024)),
        connected: status.is_some(),
        version: status
            .map(|status| status.version.clone())
            .filter(|version| !version.is_empty())
            .unwrap_or_else(|| env!("CARGO_PKG_VERSION").to_owned()),
        updated_to: status.and_then(|status| status.updated_to.clone()),
        channel: "Stable: updates install by themselves, only while your laptop is idle and never during a job."
            .to_owned(),
        dev_build: status.map_or(idleo_release::keys::DEV_BUILD, |status| status.dev_build),
        has_host_site: status.is_some_and(|status| status.dashboard_url().is_some()),
        theme: match settings.theme {
            Theme::System => "system",
            Theme::Light => "light",
            Theme::Dark => "dark",
        },
    }
}

/// What the page asks.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum FromWindow {
    /// The page has loaded.
    Ready,
    /// The switch: pause, for one of the choices.
    Pause {
        /// `hour`, `tomorrow` or `until_resumed`.
        choice: String,
    },
    /// The switch turned off.
    Resume,
    /// Save these settings.
    SetSettings {
        /// All of them.
        settings: HostSettings,
    },
    /// The button of a problem: `driver`, `storage` or `diagnostics`.
    Fix {
        /// Which.
        action: String,
    },
    /// "See everything on the host site".
    OpenHostSite,
    /// Send diagnostics.
    SendDiagnostics,
    /// Remove IdleO.
    RemoveIdleo,
}

impl FromWindow {
    /// Reads a message from the page; `None` for anything else.
    #[must_use]
    pub fn parse(text: &str) -> Option<Self> {
        serde_json::from_str(text).ok()
    }
}

/// What a page's ask comes to, as something the tray does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Do {
    /// Ask the agent.
    Send(Request),
    /// Open an address.
    Open(String),
    /// Open the host site.
    HostSite,
    /// Save diagnostics and show where.
    Diagnostics,
    /// Run the uninstaller.
    Remove,
    /// Nothing to do.
    Nothing,
}

/// What `ask` means at `now`.
#[must_use]
pub fn meaning(ask: &FromWindow, now: OffsetDateTime, offset: UtcOffset) -> Do {
    match ask {
        FromWindow::Ready => Do::Nothing,
        FromWindow::Pause { choice } => {
            PauseFor::from_id(choice).map_or(Do::Nothing, |choice| Do::Send(choice.request(now, offset)))
        }
        FromWindow::Resume => Do::Send(Request::Resume),
        FromWindow::SetSettings { settings } => Do::Send(Request::SetSettings { settings: *settings }),
        FromWindow::Fix { action } => match action.as_str() {
            "driver" => Do::Open(NVIDIA_DRIVERS.to_owned()),
            "storage" => Do::Open(STORAGE_SETTINGS.to_owned()),
            "diagnostics" => Do::Diagnostics,
            _ => Do::Nothing,
        },
        FromWindow::OpenHostSite => Do::HostSite,
        FromWindow::SendDiagnostics => Do::Diagnostics,
        FromWindow::RemoveIdleo => Do::Remove,
    }
}

/// The script that hands the page its state.
///
/// # Panics
/// Never: the state always serialises.
#[must_use]
pub fn script(state: &WindowState) -> String {
    format!("window.idleo.receive({});", serde_json::to_string(state).expect("the window's state serialises"))
}

/// The script that shows a tab.
#[must_use]
pub fn show_tab_script(tab: &str) -> String {
    let tab = if TABS.contains(&tab) { tab } else { "now" };
    format!("window.idleo.showTab({tab:?});")
}

/// The tray action for a menu click that opens the window.
#[must_use]
pub fn is_window_action(action: &Action) -> Option<Option<&'static str>> {
    match action {
        Action::OpenWindow(tab) => Some(*tab),
        _ => None,
    }
}

/// The state's name for a tray state, as the page and the icon pictures use.
#[must_use]
pub fn state_name(state: TrayState) -> &'static str {
    state.name()
}

#[cfg(test)]
#[allow(
    clippy::assert_is_empty,
    clippy::field_reassign_with_default,
    clippy::duration_suboptimal_units,
    reason = "tests state the empty case and a settings change as plainly as they can"
)]
mod tests {
    use idleo_release::channel::{Live, PayoutNote, RecentJob, State};
    use idleo_release::settings::ScheduleMode;

    use super::*;
    use crate::status::tests::{at, ready};

    fn ist() -> UtcOffset {
        UtcOffset::from_hms(5, 30, 0).unwrap()
    }

    fn earnings(updated_ago_minutes: i64) -> Earnings {
        let job = |hours_ago: i64, seconds, paise| RecentJob {
            ended_at: at() - time::Duration::hours(hours_ago),
            seconds,
            earned_paise: paise,
        };
        Earnings {
            today_paise: 1240,
            week_paise: Some(9_900),
            month_paise: Some(4_100_000),
            recent: vec![job(1, 1_380, 420), job(20, 4_000, 1_050), job(100, 45, 3)],
            next_payout_on: Some("2026-11-23".to_owned()),
            last_payout: Some(PayoutNote { sent_at: at(), amount_paise: 99_900 }),
            updated_at: at() - time::Duration::minutes(updated_ago_minutes),
        }
    }

    #[test]
    fn the_now_tab_has_the_state_the_switch_the_choices_and_the_figures() {
        let status = Status { earnings: Some(earnings(1)), ..ready() };
        let state = view(Some(&status), at(), ist(), false, None);
        assert_eq!((state.state, state.colour), ("ready", "volt"));
        assert_eq!(state.headline, "Ready for renters");
        assert!(!state.paused);
        assert_eq!(state.pause_choices.iter().map(|c| c.id).collect::<Vec<_>>(), ["hour", "tomorrow", "until_resumed"]);
        assert_eq!(state.pause_choices[1].label, "Until tomorrow, 9 am");
        assert_eq!(
            (state.earnings.today.as_deref(), state.earnings.week.as_deref(), state.earnings.month.as_deref()),
            (Some("₹12.40"), Some("₹99.00"), Some("₹41,000.00"))
        );
        assert_eq!(state.earnings.next_payout.as_deref(), Some("Monday 23 November"));
        assert_eq!(state.earnings.last_payout.as_deref(), Some("₹999.00 on 9 October"));
        assert_eq!(state.earnings.last_updated, None, "heard a minute ago");
        assert!(state.live.is_none(), "no job, no GPU numbers");
        assert!(state.connected && state.has_host_site);
    }

    #[test]
    fn the_recent_jobs_are_length_and_earnings_and_never_who_or_what() {
        let status = Status { earnings: Some(earnings(1)), ..ready() };
        let jobs = view(Some(&status), at(), ist(), false, None).earnings.recent;
        assert_eq!(
            jobs,
            [
                JobView { when: "Today 1:23 pm".into(), length: "23 min".into(), earned: "₹4.20".into() },
                JobView { when: "Yesterday 6:23 pm".into(), length: "1 h 6 min".into(), earned: "₹10.50".into() },
                JobView { when: "5 October".into(), length: "45 s".into(), earned: "₹0.03".into() },
            ]
        );
    }

    #[test]
    fn the_gpu_shows_only_while_a_job_runs() {
        let running = Status {
            session_running: true,
            live: Some(Live { gpu_temp_c: Some(71), gpu_util_pct: Some(98), job_started_at: None }),
            ..ready()
        };
        let state = view(Some(&running), at(), ist(), false, None);
        assert_eq!(state.live, Some(LiveView { temp: Some("71 °C".into()), util: Some("98%".into()) }));
        let idle_with_stale_live = Status { live: running.live, ..ready() };
        assert!(view(Some(&idle_with_stale_live), at(), ist(), false, None).live.is_none());
    }

    #[test]
    fn offline_says_when_the_figures_are_from_and_never_hides_them() {
        let offline = Status { server_reachable: false, earnings: Some(earnings(95)), ..ready() };
        let state = view(Some(&offline), at(), ist(), false, None);
        assert_eq!(state.state, "offline");
        assert_eq!(state.earnings.last_updated.as_deref(), Some("Last updated 1 hour ago"));
        assert_eq!(state.earnings.week.as_deref(), Some("₹99.00"), "the last known figures are still there");
        let stale_but_online = Status { earnings: Some(earnings(45)), ..ready() };
        assert_eq!(
            view(Some(&stale_but_online), at(), ist(), false, None).earnings.last_updated.as_deref(),
            Some("Last updated 45 min ago")
        );
    }

    #[test]
    fn with_no_agent_there_is_a_page_with_nothing_to_change() {
        let state = view(None, at(), ist(), false, None);
        assert_eq!((state.state, state.headline.as_str(), state.connected), ("offline", "Starting…", false));
        assert_eq!(state.settings, HostSettings::default());
        assert!(!state.has_host_site && state.earnings == EarningsView::default());
    }

    #[test]
    fn the_switch_is_on_while_the_host_has_paused() {
        for (state, paused) in
            [(State::Available, false), (State::AutoPaused, false), (State::HostingOff, true), (State::Paused, true)]
        {
            assert_eq!(view(Some(&Status { state, ..ready() }), at(), ist(), false, None).paused, paused, "{state:?}");
        }
    }

    #[test]
    fn each_problem_has_its_one_button_and_a_crash_loop_has_one_with_no_agent() {
        let button = |problem| {
            let state = view(Some(&Status { problem: Some(problem), ..ready() }), at(), ist(), false, None);
            let problem = state.problem.unwrap();
            (problem.action, problem.button)
        };
        assert_eq!(button(Problem::DriverTooOld), (Some("driver"), Some("Get the driver")));
        assert_eq!(button(Problem::DiskNearlyFull), (Some("storage"), Some("Open Storage settings")));
        assert_eq!(button(Problem::TooHot), (None, None));
        assert_eq!(button(Problem::Crashing), (Some("diagnostics"), Some("Send diagnostics")));
        let crashing = view(None, at(), ist(), true, None);
        assert_eq!(crashing.problem.unwrap().action, Some("diagnostics"));
        assert_eq!(crashing.state, "problem");
    }

    #[test]
    fn memory_is_shown_as_a_share_of_the_laptops() {
        let state = view(Some(&ready()), at(), ist(), false, Some(16_384));
        assert_eq!(state.memory_gb, Some(8), "the default 50% of 16 GB");
        assert_eq!(view(Some(&ready()), at(), ist(), false, None).memory_gb, None);
    }

    #[test]
    fn the_settings_and_theme_go_to_the_page_as_they_are() {
        let mut settings = HostSettings::default();
        settings.theme = Theme::Dark;
        settings.schedule.mode = ScheduleMode::Hours;
        let state = view(Some(&Status { settings, ..ready() }), at(), ist(), false, None);
        assert_eq!((state.theme, state.settings), ("dark", settings));
        let text = script(&state);
        assert!(text.starts_with("window.idleo.receive({") && text.ends_with(");"));
        assert!(text.contains("\"schedule\"") && text.contains("\"mode\":\"hours\""));
    }

    #[test]
    fn a_version_updated_to_and_the_channel_are_shown() {
        let state = view(Some(&Status { updated_to: Some("0.2.0".into()), ..ready() }), at(), ist(), false, None);
        assert_eq!((state.version.as_str(), state.updated_to.as_deref()), ("0.2.0", Some("0.2.0")));
        assert!(state.channel.contains("only while your laptop is idle"));
    }

    #[test]
    fn what_the_page_asks_becomes_something_the_tray_does() {
        let parse = |text: &str| FromWindow::parse(text).unwrap();
        let meant = |text: &str| meaning(&parse(text), at(), UtcOffset::UTC);
        assert_eq!(
            meant(r#"{"type":"pause","choice":"hour"}"#),
            Do::Send(Request::Pause { until: at() + time::Duration::hours(1) })
        );
        assert_eq!(meant(r#"{"type":"pause","choice":"until_resumed"}"#), Do::Send(Request::SetHosting { on: false }));
        assert_eq!(meant(r#"{"type":"pause","choice":"for ever"}"#), Do::Nothing);
        assert_eq!(meant(r#"{"type":"resume"}"#), Do::Send(Request::Resume));
        assert_eq!(meant(r#"{"type":"fix","action":"driver"}"#), Do::Open(NVIDIA_DRIVERS.to_owned()));
        assert_eq!(meant(r#"{"type":"fix","action":"storage"}"#), Do::Open("ms-settings:storagesense".to_owned()));
        assert_eq!(meant(r#"{"type":"fix","action":"diagnostics"}"#), Do::Diagnostics);
        assert_eq!(meant(r#"{"type":"fix","action":"format_disk"}"#), Do::Nothing, "only the buttons we drew");
        assert_eq!(meant(r#"{"type":"open_host_site"}"#), Do::HostSite);
        assert_eq!(meant(r#"{"type":"send_diagnostics"}"#), Do::Diagnostics);
        assert_eq!(meant(r#"{"type":"remove_idleo"}"#), Do::Remove);
        assert_eq!(meant(r#"{"type":"ready"}"#), Do::Nothing);
        assert_eq!(FromWindow::parse("nonsense"), None);
        assert_eq!(FromWindow::parse(r#"{"type":"format_disk"}"#), None);
    }

    #[test]
    fn saved_settings_are_sent_to_the_agent_whole() {
        let mut settings = HostSettings::default();
        settings.limits.max_gpu_temp_c = 75;
        let text = serde_json::json!({ "type": "set_settings", "settings": settings }).to_string();
        assert_eq!(
            meaning(&FromWindow::parse(&text).unwrap(), at(), UtcOffset::UTC),
            Do::Send(Request::SetSettings { settings })
        );
    }

    #[test]
    fn only_the_five_tabs_can_be_shown() {
        assert_eq!(show_tab_script("earnings"), "window.idleo.showTab(\"earnings\");");
        assert_eq!(show_tab_script("<img onerror=x>"), "window.idleo.showTab(\"now\");");
        assert_eq!(TABS.len(), 5);
    }
}
