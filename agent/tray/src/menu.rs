//! The tray's right-click menu as data (ADR 0033, ADR 0061), built from the agent's status, and what each item does. The
//! native menu is drawn from this, so what it holds and what a click does are tested without a desktop.
//!
//! - The state, in words (disabled), and a separator.
//! - **Pause** (until the host resumes) and **Pause for 1 hour**; or, while paused by the host, **Resume**.
//! - **Open IdleO** (the window), **Host site**, **Help**.
//! - **Quit IdleO**: stops it, and asks first when a renter's job is running.
//!
//! A left click opens the window instead of the menu.

use idleo_release::channel::{Request, State, Status};
use time::{Duration, OffsetDateTime, Time, UtcOffset};

use crate::status;

/// What clicking an item does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Ask the agent.
    Send(Request),
    /// Show the IdleO window, on a tab if one is named.
    OpenWindow(Option<&'static str>),
    /// Open the host site.
    OpenHostSite,
    /// Open the help pages.
    OpenHelp,
    /// Quit IdleO: the tray asks first if a job is running, then stops the agent and itself.
    Quit,
}

/// One item of the menu.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Item {
    /// Text that can't be clicked.
    Label(String),
    /// A line between groups.
    Separator,
    /// A clickable item.
    Button {
        /// Its words.
        text: String,
        /// What a click does.
        action: Action,
    },
}

/// How long a pause may be, as the window offers it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PauseFor {
    /// An hour from now.
    Hour,
    /// Until 9 am tomorrow.
    Tomorrow,
    /// Until the host resumes.
    UntilResumed,
}

impl PauseFor {
    /// What the window's page calls it.
    #[must_use]
    pub fn id(self) -> &'static str {
        match self {
            Self::Hour => "hour",
            Self::Tomorrow => "tomorrow",
            Self::UntilResumed => "until_resumed",
        }
    }

    /// The choice the page named.
    #[must_use]
    pub fn from_id(id: &str) -> Option<Self> {
        [Self::Hour, Self::Tomorrow, Self::UntilResumed].into_iter().find(|choice| choice.id() == id)
    }

    /// Its words.
    #[must_use]
    pub fn label(self) -> &'static str {
        match self {
            Self::Hour => "For 1 hour",
            Self::Tomorrow => "Until tomorrow, 9 am",
            Self::UntilResumed => "Until I resume",
        }
    }

    /// What it asks the agent at `now`, local time `offset`. Every pause with an end is within the control plane's 7 days.
    #[must_use]
    pub fn request(self, now: OffsetDateTime, offset: UtcOffset) -> Request {
        match self {
            Self::Hour => Request::Pause { until: (now + Duration::hours(1)).replace_nanosecond(0).unwrap_or(now) },
            Self::Tomorrow => {
                let local = now.to_offset(offset);
                let morning =
                    (local + Duration::days(1)).replace_time(Time::from_hms(9, 0, 0).unwrap_or(Time::MIDNIGHT));
                Request::Pause { until: morning.to_offset(UtcOffset::UTC) }
            }
            Self::UntilResumed => Request::SetHosting { on: false },
        }
    }
}

/// Whether the host has paused hosting, for good or until a time: the switch in the window and Resume in the menu.
#[must_use]
pub fn paused_by_host(status: &Status) -> bool {
    matches!(status.state, State::HostingOff | State::Paused)
}

/// The menu for `status` at `now`. With no status (the agent is starting, or the channel dropped) there is nothing to pause or
/// open a site for, only the words, the window and Quit.
#[must_use]
pub fn build(status: Option<&Status>, now: OffsetDateTime, offset: UtcOffset, crashing: bool) -> Vec<Item> {
    let mut items = vec![Item::Label(status::headline(status, now, offset, crashing)), Item::Separator];
    if let Some(status) = status {
        if paused_by_host(status) {
            items.push(Item::Button { text: "Resume".to_owned(), action: Action::Send(Request::Resume) });
        } else {
            items.push(Item::Button {
                text: "Pause".to_owned(),
                action: Action::Send(PauseFor::UntilResumed.request(now, offset)),
            });
            items.push(Item::Button {
                text: "Pause for 1 hour".to_owned(),
                action: Action::Send(PauseFor::Hour.request(now, offset)),
            });
        }
        items.push(Item::Separator);
    }
    items.push(Item::Button { text: "Open IdleO".to_owned(), action: Action::OpenWindow(None) });
    if status.is_some_and(|status| status.dashboard_url().is_some()) {
        items.push(Item::Button { text: "Host site".to_owned(), action: Action::OpenHostSite });
    }
    if status.is_some_and(|status| status.help_url().is_some()) {
        items.push(Item::Button { text: "Help".to_owned(), action: Action::OpenHelp });
    }
    items.push(Item::Separator);
    items.push(Item::Button { text: "Quit IdleO".to_owned(), action: Action::Quit });
    items
}

#[cfg(test)]
#[allow(
    clippy::assert_is_empty,
    clippy::field_reassign_with_default,
    clippy::duration_suboptimal_units,
    reason = "tests state the empty case and a settings change as plainly as they can"
)]
mod tests {
    use super::*;
    use crate::status::tests::{at, ready};

    fn texts(items: &[Item]) -> Vec<String> {
        items
            .iter()
            .map(|item| match item {
                Item::Label(text) => format!("· {text}"),
                Item::Separator => "---".to_owned(),
                Item::Button { text, .. } => text.clone(),
            })
            .collect()
    }

    #[test]
    fn a_ready_laptop_offers_the_pauses_the_window_the_sites_and_quit() {
        let items = build(Some(&ready()), at(), UtcOffset::UTC, false);
        assert_eq!(
            texts(&items),
            [
                "· Ready for renters",
                "---",
                "Pause",
                "Pause for 1 hour",
                "---",
                "Open IdleO",
                "Host site",
                "Help",
                "---",
                "Quit IdleO"
            ]
        );
    }

    #[test]
    fn a_host_pause_offers_resume_and_nothing_to_pause_again() {
        for state in [State::HostingOff, State::Paused] {
            let items = build(Some(&Status { state, ..ready() }), at(), UtcOffset::UTC, false);
            let words = texts(&items);
            assert_eq!(words[2], "Resume");
            assert!(!words.iter().any(|word| word.starts_with("Pause")), "{words:?}");
            assert!(items.contains(&Item::Button { text: "Resume".to_owned(), action: Action::Send(Request::Resume) }));
        }
        // An auto-pause is not the host's: the host can still pause for longer.
        let auto =
            Status { state: State::AutoPaused, reason: Some(idleo_release::channel::Reason::Schedule), ..ready() };
        assert_eq!(texts(&build(Some(&auto), at(), UtcOffset::UTC, false))[2], "Pause");
    }

    #[test]
    fn each_pause_asks_the_agent_for_the_right_time() {
        let items = build(Some(&ready()), at(), UtcOffset::UTC, false);
        let sent: Vec<&Request> = items
            .iter()
            .filter_map(|item| match item {
                Item::Button { action: Action::Send(request), .. } => Some(request),
                _ => None,
            })
            .collect();
        assert_eq!(sent[0], &Request::SetHosting { on: false });
        assert_eq!(sent[1], &Request::Pause { until: at() + Duration::hours(1) });
    }

    #[test]
    fn tomorrow_is_nine_in_the_morning_where_the_host_is_and_within_seven_days() {
        let ist = UtcOffset::from_hms(5, 30, 0).unwrap();
        // 2025-10-09 14:23 in India: tomorrow 9 am there is 2025-10-10 03:30 UTC.
        let Request::Pause { until } = PauseFor::Tomorrow.request(at(), ist) else { panic!() };
        assert_eq!(until, time::macros::datetime!(2025-10-10 03:30 UTC));
        assert!(until - at() < Duration::days(7));
        // Just after midnight: tomorrow is still the next calendar day.
        let late = time::macros::datetime!(2025-10-09 20:00 UTC); // 01:30 on the 10th in India
        let Request::Pause { until } = PauseFor::Tomorrow.request(late, ist) else { panic!() };
        assert_eq!(until, time::macros::datetime!(2025-10-11 03:30 UTC));
    }

    #[test]
    fn the_choices_have_names_the_page_can_send_back() {
        for choice in [PauseFor::Hour, PauseFor::Tomorrow, PauseFor::UntilResumed] {
            assert_eq!(PauseFor::from_id(choice.id()), Some(choice));
            assert!(!choice.label().is_empty());
        }
        assert_eq!(PauseFor::from_id("forever"), None);
    }

    #[test]
    fn with_no_agent_there_is_the_window_and_quit_and_nothing_to_pause() {
        let items = build(None, at(), UtcOffset::UTC, false);
        assert_eq!(texts(&items), ["· Starting…", "---", "Open IdleO", "---", "Quit IdleO"]);
        assert!(items.contains(&Item::Button { text: "Quit IdleO".to_owned(), action: Action::Quit }));
    }

    #[test]
    fn a_site_that_isnt_known_has_no_link() {
        let status = Status { host_url: None, app_url: None, site_url: None, ..ready() };
        let words = texts(&build(Some(&status), at(), UtcOffset::UTC, false));
        assert!(!words.contains(&"Host site".to_owned()) && !words.contains(&"Help".to_owned()), "{words:?}");
    }

    #[test]
    fn a_crash_loop_says_so_in_the_first_line() {
        assert_eq!(texts(&build(None, at(), UtcOffset::UTC, true))[0], "· IdleO keeps stopping");
    }
}
