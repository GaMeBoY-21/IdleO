//! What the tray says, as pure functions of the agent's status (ADR 0033): the first line of the menu, the tooltip,
//! the icon's colour and "Earned today". Each is tested for every state.

use idleo_release::channel::{LinkState, Prefetch, Reason, State, Status, UpdatePhase};
use time::{OffsetDateTime, UtcOffset};

/// The icon's colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Colour {
    /// Available or earning: #65A30D.
    Green,
    /// Paused: #A1A1AA.
    Grey,
    /// Waiting for approval, or can't reach IdleO: #FFB86B.
    Apricot,
    /// A problem: #DC2626.
    Red,
}

impl Colour {
    /// Red, green and blue.
    #[must_use]
    pub fn rgb(self) -> [u8; 3] {
        match self {
            Self::Green => [0x65, 0xA3, 0x0D],
            Self::Grey => [0xA1, 0xA1, 0xAA],
            Self::Apricot => [0xFF, 0xB8, 0x6B],
            Self::Red => [0xDC, 0x26, 0x26],
        }
    }
}

/// The first line of the menu, also the tooltip's text. `None` means the agent isn't running or the channel dropped.
#[must_use]
pub fn line(status: Option<&Status>, offset: UtcOffset) -> String {
    let Some(status) = status else { return "Starting…".to_owned() };
    if status.link_state == LinkState::Removed {
        return "This laptop was removed from IdleO · uninstall the app".to_owned();
    }
    if status.link_state == LinkState::Suspended {
        return "Hosting is suspended · contact IdleO".to_owned();
    }
    if status.session_running {
        return match status.earning_now_paise {
            Some(paise) => format!("Earning {} on a renter's job", rupees(paise)),
            None => "Earning on a renter's job".to_owned(),
        };
    }
    if !status.server_reachable {
        return "Can't reach IdleO · trying again".to_owned();
    }
    if status.link_state == LinkState::PendingApproval {
        return "Waiting for IdleO to check this laptop".to_owned();
    }
    match status.state {
        State::HostingOff => return "Hosting is off".to_owned(),
        State::Paused => {
            if let Some(until) = status.paused_until {
                return format!("Paused until {}", clock(until, offset));
            }
            return "Paused".to_owned();
        }
        State::AutoPaused => {
            return match status.reason {
                Some(Reason::KeyboardMouse) | None => format!(
                    "Paused while you use your laptop · starts again {} min after you stop",
                    status.auto_pause.start_again_after_minutes
                ),
                Some(Reason::Game | Reason::GpuBusy) => "Paused while a game is open".to_owned(),
                Some(Reason::Unplugged) => "Paused while unplugged".to_owned(),
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

/// Bytes as gigabytes with one decimal, decimal gigabytes like the disk sizes the owner sees ("2.1", "7.8").
fn gigabytes(bytes: u64) -> String {
    let tenths = (bytes + 50_000_000) / 100_000_000;
    format!("{}.{}", tenths / 10, tenths % 10)
}

/// The tooltip: the same line, starting "Dev · " in a development build.
#[must_use]
pub fn tooltip(status: Option<&Status>, offset: UtcOffset) -> String {
    let dev = status.map_or(idleo_release::keys::DEV_BUILD, |status| status.dev_build);
    let line = line(status, offset);
    if dev { format!("{}{line}", idleo_release::keys::DEV_PREFIX) } else { line }
}

/// The icon's colour for a status.
#[must_use]
pub fn colour(status: Option<&Status>) -> Colour {
    let Some(status) = status else { return Colour::Apricot };
    match status.link_state {
        LinkState::Removed | LinkState::Suspended => return Colour::Red,
        LinkState::PendingApproval if !status.session_running => return Colour::Apricot,
        _ => {}
    }
    if status.session_running {
        return Colour::Green;
    }
    if !status.server_reachable {
        return Colour::Apricot;
    }
    match status.state {
        State::Available => Colour::Green,
        State::AutoPaused | State::HostingOff | State::Paused => Colour::Grey,
    }
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
pub(crate) mod tests {
    use idleo_release::channel::AutoPause;

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
            app_url: Some("http://192.168.1.20:3000".to_owned()),
            prefetch: None,
        }
    }

    const IST: UtcOffset = match UtcOffset::from_hms(5, 30, 0) {
        Ok(offset) => offset,
        Err(_) => UtcOffset::UTC,
    };

    #[test]
    fn every_state_has_its_line_and_colour() {
        let cases: Vec<(Status, &str, Colour)> = vec![
            (
                Status { session_running: true, earning_now_paise: Some(273), ..ready() },
                "Earning ₹2.73 on a renter's job",
                Colour::Green,
            ),
            (Status { session_running: true, ..ready() }, "Earning on a renter's job", Colour::Green),
            (ready(), "Ready for renters", Colour::Green),
            (
                Status { state: State::AutoPaused, reason: Some(Reason::KeyboardMouse), ..ready() },
                "Paused while you use your laptop · starts again 10 min after you stop",
                Colour::Grey,
            ),
            (
                Status { state: State::AutoPaused, reason: Some(Reason::Game), ..ready() },
                "Paused while a game is open",
                Colour::Grey,
            ),
            (
                Status { state: State::AutoPaused, reason: Some(Reason::GpuBusy), ..ready() },
                "Paused while a game is open",
                Colour::Grey,
            ),
            (
                Status { state: State::AutoPaused, reason: Some(Reason::Unplugged), ..ready() },
                "Paused while unplugged",
                Colour::Grey,
            ),
            (
                Status {
                    state: State::Paused,
                    paused_until: Some(OffsetDateTime::from_unix_timestamp(1_760_790_600).unwrap()),
                    ..ready()
                },
                "Paused until 6:00 pm",
                Colour::Grey,
            ),
            (Status { state: State::HostingOff, ..ready() }, "Hosting is off", Colour::Grey),
            (
                Status { link_state: LinkState::PendingApproval, ..ready() },
                "Waiting for IdleO to check this laptop",
                Colour::Apricot,
            ),
            (Status { server_reachable: false, ..ready() }, "Can't reach IdleO · trying again", Colour::Apricot),
            (
                Status {
                    prefetch: Some(Prefetch::Downloading {
                        done_bytes: 2_100_000_000,
                        total_bytes: 7_800_000_000,
                        image: 1,
                        images: 2,
                    }),
                    ..ready()
                },
                "Getting ready for renters · 2.1 of 7.8 GB",
                Colour::Green,
            ),
            (
                Status {
                    prefetch: Some(Prefetch::Downloading {
                        done_bytes: 2_140_000_000,
                        total_bytes: 0,
                        image: 1,
                        images: 1,
                    }),
                    ..ready()
                },
                "Getting ready for renters · 2.1 GB downloaded",
                Colour::Green,
            ),
            (
                Status { prefetch: Some(Prefetch::WaitingForUnmetered), ..ready() },
                "Waiting for an unmetered connection to download",
                Colour::Green,
            ),
            (Status { runner_installed: false, ..ready() }, "Linked · getting the job runner ready", Colour::Green),
            (
                Status { link_state: LinkState::Removed, ..ready() },
                "This laptop was removed from IdleO · uninstall the app",
                Colour::Red,
            ),
        ];
        for (status, text, expected) in cases {
            assert_eq!(line(Some(&status), IST), text);
            assert_eq!(colour(Some(&status)), expected, "{text}");
        }
        assert_eq!(line(None, IST), "Starting…");
        assert_eq!(colour(None), Colour::Apricot);
        let wait = Status {
            state: State::AutoPaused,
            reason: Some(Reason::KeyboardMouse),
            auto_pause: AutoPause { start_again_after_minutes: 30, ..AutoPause::default() },
            ..ready()
        };
        assert!(line(Some(&wait), IST).ends_with("starts again 30 min after you stop"));
    }

    #[test]
    fn a_development_build_says_so_in_the_tooltip() {
        assert_eq!(tooltip(Some(&ready()), IST), "Ready for renters");
        assert_eq!(tooltip(Some(&Status { dev_build: true, ..ready() }), IST), "Dev · Ready for renters");
    }

    #[test]
    fn earned_today_and_money_are_written_as_the_web_app_writes_them() {
        assert_eq!(earned_today(Some(&ready())).as_deref(), Some("Earned today ₹12.40"));
        assert_eq!(earned_today(Some(&Status { earned_today_paise: None, ..ready() })), None);
        let cases: serde_json::Value =
            serde_json::from_str(include_str!("../../../api/money-format.json")).expect("the shared money cases");
        for case in cases["cases"].as_array().expect("cases") {
            assert_eq!(rupees(case["paise"].as_i64().unwrap()), case["text"].as_str().unwrap());
        }
    }

    #[test]
    fn times_are_written_in_twelve_hours() {
        let at = |hour: u8, minute: u8| {
            OffsetDateTime::new_in_offset(
                time::Date::from_calendar_date(2026, time::Month::October, 18).unwrap(),
                time::Time::from_hms(hour, minute, 0).unwrap(),
                IST,
            )
        };
        assert_eq!(clock(at(18, 0), IST), "6:00 pm");
        assert_eq!(clock(at(0, 5), IST), "12:05 am");
        assert_eq!(clock(at(12, 30), IST), "12:30 pm");
        assert_eq!(clock(at(9, 0), IST), "9:00 am");
    }

    #[test]
    fn an_update_under_way_has_its_line_and_none_when_idle() {
        let mut status = ready();
        assert_eq!(update_line(Some(&status)), None);
        assert_eq!(update_line(None), None);
        for (phase, line) in [
            (UpdatePhase::Downloading, "Downloading an update"),
            (UpdatePhase::Ready, "Update ready · installs when idle"),
            (UpdatePhase::Installing, "Installing an update"),
        ] {
            status.update = phase;
            assert_eq!(update_line(Some(&status)).as_deref(), Some(line));
        }
    }
}
