//! Which Windows toasts to show, and when (ADR 0061): a pure function of the agent's statuses and what was shown before.
//!
//! A toast is made only for: linked and approved ("You're live"); the first job; a job paused because the host came back
//! (once a day at most); a payout sent; an update installed; and a problem that needs the host (driver too old, disk nearly
//! full, too hot, offline for over an hour while it should be up, the agent crashing). Each opens the tab that helps. Each
//! kind obeys its own switch in the window's Settings, and Windows' Focus mode, which holds toasts back by itself.
//!
//! The first status after the tray starts only sets what is already so (the laptop was live long ago), so an upgrade never
//! greets the host with old news. What has been shown is kept in `notified.json`, so a restart never shows it twice.

use std::fs;
use std::path::Path;

use idleo_release::channel::{LinkState, Problem, Reason, State, Status};
use idleo_release::settings::Notifications;
use serde::{Deserialize, Serialize};
use time::{OffsetDateTime, UtcOffset};

use crate::status::problem_words;

/// How long before the same problem is shown again, if it is still there.
pub const PROBLEM_AGAIN_AFTER: time::Duration = time::Duration::hours(24);

/// The kinds of toast, one switch each.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Linked and approved.
    Live,
    /// The first job.
    FirstJob,
    /// A job paused because the host came back.
    PausedWhenBack,
    /// A payout sent.
    Payout,
    /// An update installed.
    Update,
    /// A problem that needs the host.
    Problem,
}

impl Kind {
    /// Whether the host has this kind switched on.
    #[must_use]
    pub fn enabled(self, switches: &Notifications) -> bool {
        match self {
            Self::Live => switches.live,
            Self::FirstJob => switches.first_job,
            Self::PausedWhenBack => switches.paused_when_back,
            Self::Payout => switches.payout,
            Self::Update => switches.update,
            Self::Problem => switches.problems,
        }
    }
}

/// One toast.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Toast {
    /// Which kind.
    pub kind: Kind,
    /// The bold line.
    pub title: String,
    /// The line under it.
    pub text: String,
    /// The tab of the IdleO window a click opens.
    pub tab: &'static str,
}

impl Toast {
    /// The address a click opens: `idleo:open/<tab>`, which the launcher turns into the window on that tab.
    #[must_use]
    pub fn address(&self) -> String {
        format!("idleo:open/{}", self.tab)
    }
}

/// A problem that was shown, and when.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
struct ProblemShown {
    problem: Problem,
    #[serde(with = "time::serde::rfc3339")]
    at: OffsetDateTime,
}

/// What was shown before, kept between runs.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Memory {
    /// "You're live" is behind us.
    live_announced: bool,
    /// The first job is behind us.
    first_job_done: bool,
    /// The latest payout shown.
    #[serde(with = "time::serde::rfc3339::option")]
    payout_seen: Option<OffsetDateTime>,
    /// The version an update's toast was shown for.
    update_seen: Option<String>,
    /// The day a "paused because you came back" toast was last shown, `YYYY-MM-DD`, local.
    back_on: Option<String>,
    /// The problem last shown.
    problem: Option<ProblemShown>,
}

impl Memory {
    /// Reads what was kept; nothing if there's nothing readable.
    #[must_use]
    pub fn load(path: &Path) -> Self {
        fs::read(path).ok().and_then(|bytes| serde_json::from_slice(&bytes).ok()).unwrap_or_default()
    }

    /// Keeps it: a temporary file, then a rename. Best effort.
    pub fn save(&self, path: &Path) {
        let write = || -> std::io::Result<()> {
            if let Some(parent) = path.parent() {
                fs::create_dir_all(parent)?;
            }
            let temporary = path.with_extension("json.tmp");
            fs::write(&temporary, serde_json::to_vec(self).map_err(std::io::Error::other)?)?;
            fs::rename(temporary, path)
        };
        if let Err(e) = write() {
            tracing::debug!(error = %e, "what was shown couldn't be kept");
        }
    }
}

/// What the last status said that the next one is compared with.
#[derive(Debug, Clone, Copy)]
struct Before {
    link: LinkState,
    session_running: bool,
}

/// Decides toasts from statuses.
#[derive(Debug, Default)]
pub struct Notifier {
    memory: Memory,
    before: Option<Before>,
}

fn day(at: OffsetDateTime, offset: UtcOffset) -> String {
    let local = at.to_offset(offset);
    format!("{:04}-{:02}-{:02}", local.year(), u8::from(local.month()), local.day())
}

impl Notifier {
    /// A notifier that remembers what `memory` says was shown.
    #[must_use]
    pub fn new(memory: Memory) -> Self {
        Self { memory, before: None }
    }

    /// What has been shown, to keep.
    #[must_use]
    pub fn memory(&self) -> &Memory {
        &self.memory
    }

    /// Hears one status, with `crashing` the launcher's word that something keeps crashing, and returns the toasts to show
    /// now. Memory advances whether or not a kind is switched on, so turning one on later never dumps old news.
    pub fn observe(
        &mut self,
        status: Option<&Status>,
        crashing: bool,
        now: OffsetDateTime,
        offset: UtcOffset,
    ) -> Vec<Toast> {
        let mut toasts = Vec::new();
        let Some(status) = status else {
            self.problem(None, crashing, now, Notifications::default(), &mut toasts);
            return toasts;
        };
        let switches = status.settings.notifications;
        let first = self.before.is_none();
        let before = self.before.unwrap_or(Before { link: status.link_state, session_running: status.session_running });
        let shown = |toasts: &mut Vec<Toast>, toast: Toast| {
            if toast.kind.enabled(&switches) {
                toasts.push(toast);
            }
        };

        // You're live: the laptop passed IdleO's checks while the tray watched.
        if status.link_state == LinkState::Active {
            if before.link == LinkState::PendingApproval && !self.memory.live_announced && !first {
                shown(
                    &mut toasts,
                    Toast {
                        kind: Kind::Live,
                        title: "You're live".into(),
                        text: "Renters can book this laptop now.".into(),
                        tab: "now",
                    },
                );
            }
            self.memory.live_announced = true;
        }

        // The first job.
        let had_jobs = status.earnings.as_ref().is_some_and(|earnings| !earnings.recent.is_empty());
        if self.memory.first_job_done || had_jobs || (first && status.session_running) {
            self.memory.first_job_done = true;
        } else if status.session_running && !before.session_running {
            self.memory.first_job_done = true;
            shown(
                &mut toasts,
                Toast {
                    kind: Kind::FirstJob,
                    title: "Your first job is running".into(),
                    text: "Your laptop is earning.".into(),
                    tab: "now",
                },
            );
        }

        // A job paused because the host came back, once a day.
        let came_back = before.session_running
            && !status.session_running
            && status.state == State::AutoPaused
            && matches!(status.reason, Some(Reason::KeyboardMouse | Reason::Game | Reason::GpuBusy));
        if came_back {
            let today = day(now, offset);
            if self.memory.back_on.as_deref() != Some(today.as_str()) {
                self.memory.back_on = Some(today);
                shown(
                    &mut toasts,
                    Toast {
                        kind: Kind::PausedWhenBack,
                        title: "IdleO paused a job".into(),
                        text: "You're back, so the renter's job saved its progress and stopped.".into(),
                        tab: "now",
                    },
                );
            }
        }

        // A payout sent.
        if let Some(payout) = status.earnings.as_ref().and_then(|earnings| earnings.last_payout) {
            let new = self.memory.payout_seen.is_none_or(|seen| payout.sent_at > seen);
            if new {
                let announce = self.memory.payout_seen.is_some() || !first;
                self.memory.payout_seen = Some(payout.sent_at);
                if announce {
                    shown(
                        &mut toasts,
                        Toast {
                            kind: Kind::Payout,
                            title: "Payout sent".into(),
                            // The amount a payout takes from one laptop isn't what reaches the bank (tax is deducted): the
                            // toast says it was sent, and the Earnings tab shows the rest.
                            text: "Your payout is on its way to your UPI ID.".into(),
                            tab: "earnings",
                        },
                    );
                }
            }
        }

        // An update installed.
        if let Some(version) = &status.updated_to
            && self.memory.update_seen.as_ref() != Some(version)
        {
            self.memory.update_seen = Some(version.clone());
            shown(
                &mut toasts,
                Toast {
                    kind: Kind::Update,
                    title: "IdleO updated".into(),
                    text: format!("You're on version {version}."),
                    tab: "settings",
                },
            );
        }

        self.problem(status.problem, crashing, now, switches, &mut toasts);
        self.before = Some(Before { link: status.link_state, session_running: status.session_running });
        toasts
    }

    /// A problem that needs the host: shown when it appears, and again a day later if it is still there.
    fn problem(
        &mut self,
        found: Option<Problem>,
        crashing: bool,
        now: OffsetDateTime,
        switches: Notifications,
        toasts: &mut Vec<Toast>,
    ) {
        let found = if crashing { Some(Problem::Crashing) } else { found };
        let Some(problem) = found else {
            self.memory.problem = None;
            return;
        };
        let due =
            self.memory.problem.is_none_or(|shown| shown.problem != problem || now - shown.at >= PROBLEM_AGAIN_AFTER);
        if !due {
            return;
        }
        self.memory.problem = Some(ProblemShown { problem, at: now });
        if switches.problems {
            let (title, fix) = problem_words(problem);
            let tab = match problem {
                Problem::DiskNearlyFull | Problem::TooHot => "limits",
                Problem::Crashing => "settings",
                Problem::DriverTooOld | Problem::OfflineTooLong => "now",
            };
            toasts.push(Toast { kind: Kind::Problem, title: title.to_owned(), text: fix.to_owned(), tab });
        }
    }
}

#[cfg(test)]
#[allow(
    clippy::assert_is_empty,
    clippy::field_reassign_with_default,
    clippy::duration_suboptimal_units,
    reason = "tests state the empty case and a settings change as plainly as they can"
)]
mod tests {
    use idleo_release::channel::{Earnings, PayoutNote, RecentJob};
    use idleo_release::settings::HostSettings;

    use super::*;
    use crate::status::tests::{at, ready};

    fn watch(statuses: &[Status]) -> Vec<Vec<Toast>> {
        let mut notifier = Notifier::default();
        statuses.iter().map(|status| notifier.observe(Some(status), false, at(), UtcOffset::UTC)).collect()
    }

    fn kinds(toasts: &[Toast]) -> Vec<Kind> {
        toasts.iter().map(|toast| toast.kind).collect()
    }

    fn pending() -> Status {
        Status { link_state: LinkState::PendingApproval, ..ready() }
    }

    fn running() -> Status {
        Status { session_running: true, ..ready() }
    }

    fn back() -> Status {
        Status { state: State::AutoPaused, reason: Some(Reason::KeyboardMouse), ..ready() }
    }

    fn with_earnings(status: Status, recent: usize, payout: Option<PayoutNote>) -> Status {
        let job = RecentJob { ended_at: at(), seconds: 60, earned_paise: 10 };
        let earnings = Earnings {
            today_paise: 0,
            week_paise: None,
            month_paise: None,
            recent: vec![job; recent],
            next_payout_on: None,
            last_payout: payout,
            updated_at: at(),
        };
        Status { earnings: Some(earnings), ..status }
    }

    #[test]
    fn passing_the_checks_while_the_tray_watches_is_a_toast_to_the_now_tab_and_only_once() {
        let toasts = watch(&[pending(), ready(), ready(), pending(), ready()]);
        assert_eq!(kinds(&toasts[1]), [Kind::Live]);
        assert_eq!(toasts[1][0].title, "You're live");
        assert_eq!(toasts[1][0].address(), "idleo:open/now");
        assert!(toasts[0].is_empty() && toasts[2].is_empty());
        assert!(toasts[4].is_empty(), "never twice");
    }

    #[test]
    fn a_laptop_that_was_live_before_the_tray_started_says_nothing() {
        assert!(watch(&[ready(), ready()]).iter().all(Vec::is_empty));
    }

    #[test]
    fn the_first_job_is_a_toast_once_and_a_laptop_that_has_run_jobs_never_gets_it() {
        let toasts = watch(&[ready(), running(), ready(), running()]);
        assert_eq!(kinds(&toasts[1]), [Kind::FirstJob]);
        assert!(toasts[3].is_empty());
        let old_hand = watch(&[with_earnings(ready(), 3, None), with_earnings(running(), 3, None)]);
        assert!(old_hand.iter().all(Vec::is_empty), "its earnings list says it has been here before");
        let already_running = watch(&[running(), ready(), running()]);
        assert!(already_running.iter().all(Vec::is_empty), "a job already under way when the tray started isn't news");
    }

    #[test]
    fn a_job_stopped_because_the_host_came_back_is_a_toast_once_a_day() {
        let mut notifier = Notifier::default();
        let _ = notifier.observe(Some(&running()), false, at(), UtcOffset::UTC);
        let first = notifier.observe(Some(&back()), false, at(), UtcOffset::UTC);
        assert_eq!(kinds(&first), [Kind::PausedWhenBack]);
        // Later the same day: another job, stopped again.
        let _ = notifier.observe(Some(&running()), false, at() + time::Duration::hours(2), UtcOffset::UTC);
        let again = notifier.observe(Some(&back()), false, at() + time::Duration::hours(3), UtcOffset::UTC);
        assert!(again.is_empty(), "once a day at most");
        let _ = notifier.observe(Some(&running()), false, at() + time::Duration::hours(30), UtcOffset::UTC);
        let next_day = notifier.observe(Some(&back()), false, at() + time::Duration::hours(31), UtcOffset::UTC);
        assert_eq!(kinds(&next_day), [Kind::PausedWhenBack]);
    }

    #[test]
    fn only_the_host_coming_back_counts_not_a_schedule_or_the_heat() {
        for reason in [Reason::Schedule, Reason::TooHot, Reason::Unplugged, Reason::Metered, Reason::DiskFull] {
            let mut notifier = Notifier::default();
            let _ = notifier.observe(Some(&running()), false, at(), UtcOffset::UTC);
            let stopped = Status { state: State::AutoPaused, reason: Some(reason), ..ready() };
            assert!(notifier.observe(Some(&stopped), false, at(), UtcOffset::UTC).is_empty(), "{reason:?}");
        }
    }

    #[test]
    fn a_payout_sent_is_a_toast_to_earnings_and_an_old_one_is_not() {
        let paid =
            |seconds: i64, paise| PayoutNote { sent_at: at() + time::Duration::seconds(seconds), amount_paise: paise };
        let toasts = watch(&[
            with_earnings(ready(), 1, Some(paid(0, 12_500))),
            with_earnings(ready(), 1, Some(paid(0, 12_500))),
            with_earnings(ready(), 1, Some(paid(604_800, 99_900))),
        ]);
        assert!(toasts[0].is_empty() && toasts[1].is_empty(), "the one the tray found on starting is old news");
        assert_eq!(kinds(&toasts[2]), [Kind::Payout]);
        assert_eq!(toasts[2][0].text, "Your payout is on its way to your UPI ID.");
        assert_eq!(toasts[2][0].tab, "earnings");
    }

    #[test]
    fn an_installed_update_is_a_toast_once_per_version() {
        let updated = |version: &str| Status { updated_to: Some(version.to_owned()), ..ready() };
        let toasts = watch(&[ready(), updated("0.2.0"), updated("0.2.0"), updated("0.3.0")]);
        assert_eq!(kinds(&toasts[1]), [Kind::Update]);
        assert_eq!(toasts[1][0].text, "You're on version 0.2.0.");
        assert_eq!(toasts[1][0].tab, "settings");
        assert!(toasts[2].is_empty());
        assert_eq!(kinds(&toasts[3]), [Kind::Update]);
    }

    #[test]
    fn a_problem_is_a_toast_on_the_tab_that_helps_and_repeats_only_after_a_day() {
        let mut notifier = Notifier::default();
        let hot = Status { problem: Some(Problem::TooHot), ..ready() };
        let first = notifier.observe(Some(&hot), false, at(), UtcOffset::UTC);
        assert_eq!(kinds(&first), [Kind::Problem]);
        assert_eq!((first[0].title.as_str(), first[0].tab), ("Your GPU is too hot", "limits"));
        assert!(notifier.observe(Some(&hot), false, at() + time::Duration::hours(23), UtcOffset::UTC).is_empty());
        let a_day_on = notifier.observe(Some(&hot), false, at() + time::Duration::hours(25), UtcOffset::UTC);
        assert_eq!(kinds(&a_day_on), [Kind::Problem]);
        // Gone, then back: shown again at once.
        let _ = notifier.observe(Some(&ready()), false, at() + time::Duration::hours(26), UtcOffset::UTC);
        let back_again = notifier.observe(Some(&hot), false, at() + time::Duration::hours(27), UtcOffset::UTC);
        assert_eq!(kinds(&back_again), [Kind::Problem]);
        // A different problem is its own.
        let disk = Status { problem: Some(Problem::DiskNearlyFull), ..ready() };
        assert_eq!(
            notifier.observe(Some(&disk), false, at() + time::Duration::hours(28), UtcOffset::UTC)[0].tab,
            "limits"
        );
    }

    #[test]
    fn each_problem_opens_the_right_tab() {
        let tab = |problem| {
            let mut notifier = Notifier::default();
            notifier.observe(Some(&Status { problem: Some(problem), ..ready() }), false, at(), UtcOffset::UTC)[0].tab
        };
        assert_eq!(
            [
                tab(Problem::DriverTooOld),
                tab(Problem::DiskNearlyFull),
                tab(Problem::TooHot),
                tab(Problem::OfflineTooLong),
                tab(Problem::Crashing)
            ],
            ["now", "limits", "limits", "now", "settings"]
        );
    }

    #[test]
    fn a_crash_loop_is_a_problem_even_with_no_agent_to_ask() {
        let mut notifier = Notifier::default();
        let toasts = notifier.observe(None, true, at(), UtcOffset::UTC);
        assert_eq!(toasts.len(), 1);
        assert_eq!(toasts[0].title, "IdleO keeps stopping");
        assert!(notifier.observe(None, true, at() + time::Duration::minutes(5), UtcOffset::UTC).is_empty());
        assert!(notifier.observe(None, false, at() + time::Duration::minutes(6), UtcOffset::UTC).is_empty());
    }

    #[test]
    fn each_kind_obeys_its_own_switch_and_turning_one_on_later_shows_nothing_old() {
        let mut off = HostSettings::default();
        off.notifications = Notifications {
            live: false,
            first_job: false,
            paused_when_back: false,
            payout: false,
            update: false,
            problems: false,
        };
        let quiet = |status: Status| Status { settings: off, ..status };
        let toasts = watch(&[
            quiet(pending()),
            quiet(ready()),
            quiet(running()),
            quiet(Status { problem: Some(Problem::TooHot), updated_to: Some("0.2.0".into()), ..ready() }),
        ]);
        assert!(toasts.iter().all(Vec::is_empty), "{toasts:?}");
        // Switched on afterwards: what already happened stays quiet.
        let mut notifier = Notifier::default();
        for status in [quiet(pending()), quiet(ready()), quiet(running())] {
            let _ = notifier.observe(Some(&status), false, at(), UtcOffset::UTC);
        }
        assert!(notifier.observe(Some(&ready()), false, at(), UtcOffset::UTC).is_empty());
        assert!(
            notifier.observe(Some(&running()), false, at(), UtcOffset::UTC).is_empty(),
            "the first job was already used up"
        );
    }

    #[test]
    fn what_was_shown_survives_a_restart() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("notified.json");
        let mut notifier = Notifier::new(Memory::load(&path));
        let _ = notifier.observe(Some(&pending()), false, at(), UtcOffset::UTC);
        assert_eq!(kinds(&notifier.observe(Some(&ready()), false, at(), UtcOffset::UTC)), [Kind::Live]);
        let _ = notifier.observe(Some(&running()), false, at(), UtcOffset::UTC);
        notifier.memory().save(&path);

        let mut restarted = Notifier::new(Memory::load(&path));
        let _ = restarted.observe(Some(&pending()), false, at(), UtcOffset::UTC);
        assert!(restarted.observe(Some(&ready()), false, at(), UtcOffset::UTC).is_empty(), "live was announced");
        assert!(restarted.observe(Some(&running()), false, at(), UtcOffset::UTC).is_empty(), "the first job was too");
        assert_eq!(Memory::load(&dir.path().join("missing.json")), Memory::default());
    }
}
