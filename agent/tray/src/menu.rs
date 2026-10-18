//! The tray's menu as data (ADR 0033), built from the agent's status, and what each item asks the agent. The native
//! menu is drawn from this, so what it holds and what a click sends are tested without a desktop.
//!
//! - Status (disabled), "Earned today ₹12.40" (disabled), a separator.
//! - **Hosting** (a check item).
//! - **Pause** ▸ For 1 hour · Until this evening (6 pm) · Until tomorrow morning (9 am): hidden while hosting is off.
//! - **Resume now**: only while paused by the owner.
//! - **Auto-pause** ▸ three check items, then **Start again after** ▸ 5 · 10 · 20 · 30 · 60 min.
//! - **Open dashboard**.
//!
//! Nothing else: there's no Quit. Turning hosting off is how a host stops.

use idleo_release::channel::{AutoPause, Request, State, Status};
use time::{Duration, OffsetDateTime, Time, UtcOffset};

use crate::status;

/// What clicking an item does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Ask the agent.
    Send(Request),
    /// Open the web app's Hosting page.
    OpenDashboard,
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
    /// An item with a tick.
    Check {
        /// Its words.
        text: String,
        /// Ticked.
        checked: bool,
        /// What a click does.
        action: Action,
    },
    /// A submenu.
    Submenu {
        /// Its words.
        text: String,
        /// What's in it.
        items: Vec<Item>,
    },
}

/// The pause choices at `now`, local time: an hour from now; this evening at 6 pm, while it's still before 6; and
/// tomorrow at 9 am. Every one is within the control plane's 7 days.
#[must_use]
pub fn pause_choices(now: OffsetDateTime, offset: UtcOffset) -> Vec<(String, OffsetDateTime)> {
    let local = now.to_offset(offset);
    let mut choices = vec![("For 1 hour".to_owned(), (now + Duration::hours(1)).replace_nanosecond(0).unwrap_or(now))];
    let evening = local.replace_time(Time::from_hms(18, 0, 0).unwrap_or(Time::MIDNIGHT));
    if local < evening {
        choices.push(("Until this evening (6 pm)".to_owned(), evening.to_offset(UtcOffset::UTC)));
    }
    let morning = (local + Duration::days(1)).replace_time(Time::from_hms(9, 0, 0).unwrap_or(Time::MIDNIGHT));
    choices.push(("Until tomorrow morning (9 am)".to_owned(), morning.to_offset(UtcOffset::UTC)));
    choices
}

/// The menu for `status` at `now`. With no status (the agent is starting, or the channel dropped), only the line and
/// Open dashboard.
#[must_use]
pub fn build(status: Option<&Status>, now: OffsetDateTime, offset: UtcOffset) -> Vec<Item> {
    let mut items = vec![Item::Label(status::line(status, offset))];
    let Some(status) = status else {
        items.push(Item::Separator);
        items.push(Item::Button { text: "Open dashboard".to_owned(), action: Action::OpenDashboard });
        return items;
    };
    if let Some(earned) = status::earned_today(Some(status)) {
        items.push(Item::Label(earned));
    }
    items.push(Item::Separator);
    let hosting = status.state != State::HostingOff;
    items.push(Item::Check {
        text: "Hosting".to_owned(),
        checked: hosting,
        action: Action::Send(Request::SetHosting { on: !hosting }),
    });
    if hosting {
        items.push(Item::Submenu {
            text: "Pause".to_owned(),
            items: pause_choices(now, offset)
                .into_iter()
                .map(|(text, until)| Item::Button { text, action: Action::Send(Request::Pause { until }) })
                .collect(),
        });
    }
    if status.state == State::Paused {
        items.push(Item::Button { text: "Resume now".to_owned(), action: Action::Send(Request::Resume) });
    }
    items.push(auto_pause(status.auto_pause));
    items.push(Item::Separator);
    items.push(Item::Button { text: "Open dashboard".to_owned(), action: Action::OpenDashboard });
    items
}

fn auto_pause(settings: AutoPause) -> Item {
    let set = |settings: AutoPause| Action::Send(Request::SetAutoPause { settings });
    Item::Submenu {
        text: "Auto-pause".to_owned(),
        items: vec![
            Item::Check {
                text: "When I use the keyboard or mouse".to_owned(),
                checked: settings.keyboard_mouse,
                action: set(AutoPause { keyboard_mouse: !settings.keyboard_mouse, ..settings }),
            },
            Item::Check {
                text: "When a game or full-screen app is open".to_owned(),
                checked: settings.games,
                action: set(AutoPause { games: !settings.games, ..settings }),
            },
            Item::Check {
                text: "When unplugged".to_owned(),
                checked: settings.unplugged,
                action: set(AutoPause { unplugged: !settings.unplugged, ..settings }),
            },
            Item::Separator,
            Item::Submenu {
                text: "Start again after".to_owned(),
                items: AutoPause::START_AGAIN_CHOICES
                    .iter()
                    .map(|minutes| Item::Check {
                        text: format!("{minutes} min"),
                        checked: settings.start_again_after_minutes == *minutes,
                        action: set(AutoPause { start_again_after_minutes: *minutes, ..settings }),
                    })
                    .collect(),
            },
        ],
    }
}

/// The dashboard's address: the web app's Hosting page.
#[must_use]
pub fn dashboard_url(app_url: &str) -> String {
    format!("{}/hosting/", app_url.trim_end_matches('/'))
}

#[cfg(test)]
mod tests {
    use idleo_release::channel::Reason;

    use super::*;
    use crate::status::tests::ready;

    const IST: UtcOffset = match UtcOffset::from_hms(5, 30, 0) {
        Ok(offset) => offset,
        Err(_) => UtcOffset::UTC,
    };

    /// 2026-10-18 at the given IST time.
    fn at(hour: u8, minute: u8) -> OffsetDateTime {
        OffsetDateTime::new_in_offset(
            time::Date::from_calendar_date(2026, time::Month::October, 18).unwrap(),
            Time::from_hms(hour, minute, 0).unwrap(),
            IST,
        )
    }

    fn texts(items: &[Item]) -> Vec<String> {
        items
            .iter()
            .map(|item| match item {
                Item::Label(text)
                | Item::Button { text, .. }
                | Item::Check { text, .. }
                | Item::Submenu { text, .. } => text.clone(),
                Item::Separator => "—".to_owned(),
            })
            .collect()
    }

    fn find<'a>(items: &'a [Item], wanted: &str) -> &'a Item {
        items
            .iter()
            .find(|item| texts(std::slice::from_ref(*item))[0] == wanted)
            .unwrap_or_else(|| panic!("no {wanted}"))
    }

    #[test]
    fn the_menu_has_what_the_tray_promises_and_nothing_else() {
        let items = build(Some(&ready()), at(14, 0), IST);
        assert_eq!(
            texts(&items),
            ["Ready for renters", "Earned today ₹12.40", "—", "Hosting", "Pause", "Auto-pause", "—", "Open dashboard"]
        );
        assert!(!texts(&items).iter().any(|text| text.contains("Quit")));
        let Item::Submenu { items: pause, .. } = find(&items, "Pause") else { panic!() };
        assert_eq!(texts(pause), ["For 1 hour", "Until this evening (6 pm)", "Until tomorrow morning (9 am)"]);
    }

    #[test]
    fn hosting_off_hides_pause_and_turning_it_on_resumes() {
        let off = Status { state: State::HostingOff, ..ready() };
        let items = build(Some(&off), at(14, 0), IST);
        assert!(!texts(&items).contains(&"Pause".to_owned()));
        match find(&items, "Hosting") {
            Item::Check { checked, action, .. } => {
                assert!(!checked);
                assert_eq!(action, &Action::Send(Request::SetHosting { on: true }));
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn resume_shows_only_while_paused_by_the_owner() {
        let paused = Status { state: State::Paused, paused_until: Some(at(18, 0)), ..ready() };
        assert!(texts(&build(Some(&paused), at(14, 0), IST)).contains(&"Resume now".to_owned()));
        let auto = Status { state: State::AutoPaused, reason: Some(Reason::Game), ..ready() };
        assert!(!texts(&build(Some(&auto), at(14, 0), IST)).contains(&"Resume now".to_owned()));
    }

    #[test]
    fn pause_times_are_local_and_this_evening_goes_after_six() {
        let choices = pause_choices(at(14, 10), IST);
        assert_eq!(choices[0].1, at(15, 10));
        assert_eq!(choices[1].1, at(18, 0));
        assert_eq!(choices[2].1, at(9, 0) + Duration::days(1));
        let late = pause_choices(at(19, 0), IST);
        assert_eq!(late.len(), 2, "no 'this evening' after 6 pm");
        assert!(late.iter().all(|(_, until)| *until - at(19, 0) <= Duration::days(7)));
    }

    #[test]
    fn auto_pause_items_toggle_one_setting_each() {
        let items = build(Some(&ready()), at(14, 0), IST);
        let Item::Submenu { items: auto, .. } = find(&items, "Auto-pause") else { panic!() };
        match find(auto, "When unplugged") {
            Item::Check { checked: true, action: Action::Send(Request::SetAutoPause { settings }), .. } => {
                assert_eq!(*settings, AutoPause { unplugged: false, ..AutoPause::default() });
            }
            other => panic!("{other:?}"),
        }
        let Item::Submenu { items: waits, .. } = find(auto, "Start again after") else { panic!() };
        assert_eq!(texts(waits), ["5 min", "10 min", "20 min", "30 min", "60 min"]);
        assert!(matches!(find(waits, "10 min"), Item::Check { checked: true, .. }));
    }

    #[test]
    fn without_the_agent_only_the_line_and_the_dashboard() {
        assert_eq!(texts(&build(None, at(14, 0), IST)), ["Starting…", "—", "Open dashboard"]);
        assert_eq!(dashboard_url("http://192.168.1.20:3000/"), "http://192.168.1.20:3000/hosting/");
    }
}
