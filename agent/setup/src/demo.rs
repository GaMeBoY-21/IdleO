//! `IdleO-Setup-Demo.exe`, or `IdleO-Setup.exe --demo` (ADR 0061): the whole of setup, for showing people the app. It walks
//! through every screen with pretend progress, and **changes nothing on the PC**: no WSL, no downloads, no network, no files and
//! no registry. That is true by construction: this module is handed only a way to show a screen and a way to hear the page, and
//! nothing else: it has no [`crate::system::System`], so there is nothing it could touch. A test reads this file's source to keep
//! it so.
//!
//! The tour: the welcome, each of the eight steps with pretend progress (the download with its MB and the time left), the
//! approval, and then every screen setup can end on, one after another, each moved on from by its own button. The window's title
//! bar says "Demo", and so does a note on every screen.

use std::time::Duration;

use tokio::sync::mpsc;

use crate::ipc::{Facts, FromPage, Problem, StepView, ToPage};
use crate::steps::{Ending, Step, overall_percent, time_left};

/// How fast the pretend progress goes.
#[derive(Debug, Clone, Copy)]
pub struct Pace {
    /// How long a step takes.
    pub step: Duration,
    /// How often the download's count moves.
    pub tick: Duration,
}

impl Default for Pace {
    fn default() -> Self {
        Self { step: Duration::from_millis(1_600), tick: Duration::from_millis(250) }
    }
}

/// A pretend download's size, in MB.
const PRETEND_MB: u64 = 310;
/// A pretend path for the diagnostics, so the screen can be shown; no file is made.
const PRETEND_ZIP: &str = r"C:\Users\you\Downloads\IdleO-diagnostics-demo.zip";

/// The facts the demo's screens name: invented, never read from the PC.
fn facts() -> Facts {
    Facts {
        free_up_gb: 23,
        drive: "C:".to_owned(),
        free_gb: 37,
        needed_gb: 60,
        file: "idleo-distro.tar.gz".to_owned(),
        proxy: false,
        driver: "511.65".to_owned(),
    }
}

fn steps(done: usize, detail: Option<String>, percent: Option<u32>) -> Vec<StepView> {
    Step::ALL
        .iter()
        .enumerate()
        .map(|(index, step)| StepView {
            label: step.label().to_owned(),
            state: if index < done {
                "done"
            } else if index == done {
                "current"
            } else {
                "waiting"
            }
            .to_owned(),
            detail: (index == done).then(|| detail.clone()).flatten(),
            percent: (index == done).then_some(percent).flatten(),
        })
        .collect()
}

/// The demo's engine: what to show, and what the page said.
struct Tour<'a> {
    show: &'a mut dyn FnMut(ToPage),
    events: &'a mut mpsc::UnboundedReceiver<FromPage>,
    pace: Pace,
}

impl Tour<'_> {
    /// The next thing the page says, other than what is answered here by itself. `None` when the window was closed.
    async fn next(&mut self) -> Option<FromPage> {
        loop {
            match self.events.recv().await? {
                FromPage::ConfirmClose => return None,
                FromPage::Diagnostics => (self.show)(ToPage::DiagnosticsSaved { path: PRETEND_ZIP.to_owned() }),
                other => return Some(other),
            }
        }
    }

    /// Waits for `wanted` (or, for a screen with two ways on, one of several). `None` when the window was closed.
    async fn wait_for(&mut self, wanted: &[FromPage]) -> Option<FromPage> {
        loop {
            let event = self.next().await?;
            if wanted.contains(&event) {
                return Some(event);
            }
        }
    }

    /// Lets `time` pass, ending early (`false`) if the window is closed.
    async fn pause(&mut self, time: Duration) -> bool {
        tokio::select! {
            () = tokio::time::sleep(time) => true,
            event = self.events.recv() => {
                // Anything but closing the window is ignored while the pretend progress runs.
                !matches!(event, Some(FromPage::ConfirmClose) | None)
            }
        }
    }

    async fn progress(&mut self, index: usize, detail: &str) -> bool {
        let completed = &Step::ALL[..index];
        (self.show)(ToPage::Progress {
            steps: steps(index, Some(detail.to_owned()), None),
            note: None,
            overall: overall_percent(completed, 0),
        });
        self.pause(self.pace.step).await
    }

    /// The pretend download: the count climbs, with the time left.
    async fn download(&mut self) -> bool {
        let completed = &Step::ALL[..2];
        let ticks = 12_u64;
        for tick in 1..=ticks {
            let mb = PRETEND_MB * tick / ticks;
            let done = mb * 1_000_000;
            let total = PRETEND_MB * 1_000_000;
            let left = time_left(done, total, self.pace.tick * u32::try_from(tick).unwrap_or(1) * 12)
                .map(|left| format!(" · {left}"))
                .unwrap_or_default();
            let percent = u32::try_from(mb * 100 / PRETEND_MB).unwrap_or(100);
            (self.show)(ToPage::Progress {
                steps: steps(2, Some(format!("Downloading · {mb} of {PRETEND_MB} MB{left}")), Some(percent)),
                note: None,
                overall: overall_percent(completed, percent),
            });
            if !self.pause(self.pace.tick).await {
                return false;
            }
        }
        true
    }

    /// A screen with one button, and wait for it. `false` when the window was closed.
    async fn screen(&mut self, screen: ToPage, wanted: &[FromPage]) -> bool {
        (self.show)(screen);
        self.wait_for(wanted).await.is_some()
    }
}

/// Walks through every screen, with pretend progress, and returns how it ended: [`Ending::Done`] after the last screen,
/// [`Ending::Undo`] if the window was closed (there is nothing to undo).
pub async fn run(show: &mut dyn FnMut(ToPage), events: &mut mpsc::UnboundedReceiver<FromPage>, pace: Pace) -> Ending {
    let mut tour = Tour { show, events, pace };
    if !tour
        .screen(
            ToPage::Welcome { already_set_up: false, dev_build: false, unfinished: false, demo: true },
            &[FromPage::SetUp],
        )
        .await
    {
        return Ending::Undo;
    }
    let details = [
        "Checking Windows, the graphics card and the space",
        "Windows will ask for permission",
        "",
        "Putting the programs in place",
        "Setting up the sealed Linux space",
        "12 checks passed",
        "Waiting for your approval",
        "Starting IdleO",
    ];
    for (index, detail) in details.iter().enumerate() {
        let go_on = if index == 2 { tour.download().await } else { tour.progress(index, detail).await };
        if !go_on {
            return Ending::Undo;
        }
        if index == 5 {
            let approve = ToPage::Approve {
                machine_name: "Your laptop".to_owned(),
                url: "https://app.idleo.in/link/demo/".to_owned(),
                short_url: "app.idleo.in/link/demo/".to_owned(),
            };
            if !tour.screen(approve, &[FromPage::OpenAgain, FromPage::TryAgain, FromPage::Later, FromPage::Fix]).await {
                return Ending::Undo;
            }
        }
    }
    // Every other screen, one after another: each moves on with its own button.
    let every = Problem::ALL.map(|problem| {
        let (title, text) = problem.words(&facts());
        ToPage::Fix {
            problem,
            title,
            text,
            button: problem.button().to_owned(),
            rechecking: problem.rechecks(),
            keep: false,
            help: problem.help().map(str::to_owned),
        }
    });
    let more = [
        ToPage::Restart,
        ToPage::LinkFailed { message: "The link ran out after 10 minutes. Try again to get a new one.".to_owned() },
        ToPage::Welcome { already_set_up: true, dev_build: false, unfinished: false, demo: true },
        ToPage::Welcome { already_set_up: false, dev_build: false, unfinished: true, demo: true },
    ];
    let moves_on =
        [FromPage::Fix, FromPage::TryAgain, FromPage::Later, FromPage::SetUp, FromPage::OpenIdleo, FromPage::Close];
    for screen in every.into_iter().chain(more) {
        if !tour.screen(screen, &moves_on).await {
            return Ending::Undo;
        }
    }
    if !tour.screen(ToPage::Done { pending: true, report_problem: None }, &[FromPage::Close]).await {
        return Ending::Undo;
    }
    (tour.show)(ToPage::Done { pending: false, report_problem: None });
    let _ = tour.wait_for(&[FromPage::Close]).await;
    Ending::Done { pending: false }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The demo's source above its tests: what it could touch is whatever it names.
    fn source() -> &'static str {
        let all = include_str!("demo.rs");
        &all[..all.find("#[cfg(test)]").unwrap()]
    }

    #[test]
    fn the_demo_has_nothing_to_touch_the_pc_with() {
        let code = source();
        for forbidden in [
            "std::fs",
            "std::process",
            "std::net",
            "std::env",
            "tokio::fs",
            "tokio::net",
            "tokio::process",
            "reqwest",
            "System",
            "registry",
            "Registry",
            "wsl",
            "Wsl",
            "Layout",
            "Machine",
            "idleo_release",
            "agent_windows",
        ] {
            // Only the words in this list's own line and the doc comment mention them.
            let uses = code
                .lines()
                .filter(|line| !line.trim_start().starts_with("//"))
                .filter(|line| line.contains(forbidden))
                .count();
            assert_eq!(uses, 0, "the demo uses {forbidden}");
        }
    }

    /// Runs the whole tour, pressing the right button on every screen. Returns every screen shown, in order, and how it ended.
    async fn tour(answers: impl Fn(&ToPage) -> Option<FromPage>) -> (Vec<ToPage>, Ending) {
        let (to_demo, mut from_page) = mpsc::unbounded_channel::<FromPage>();
        let (shown, mut seen) = mpsc::unbounded_channel::<ToPage>();
        let runner = async move {
            let mut show = move |screen: ToPage| {
                let _ = shown.send(screen);
            };
            run(&mut show, &mut from_page, Pace::default()).await
        };
        let driver = async {
            let mut screens = Vec::new();
            while let Some(screen) = seen.recv().await {
                if let Some(answer) = answers(&screen) {
                    let _ = to_demo.send(answer);
                }
                screens.push(screen);
            }
            screens
        };
        let (ending, screens) = tokio::join!(runner, driver);
        (screens, ending)
    }

    fn presses(screen: &ToPage) -> Option<FromPage> {
        match screen {
            ToPage::Welcome { .. } => Some(FromPage::SetUp),
            ToPage::Approve { .. } => Some(FromPage::OpenAgain),
            ToPage::Fix { .. } | ToPage::LinkFailed { .. } => Some(FromPage::Fix),
            ToPage::Restart => Some(FromPage::Later),
            ToPage::Done { .. } => Some(FromPage::Close),
            _ => None,
        }
    }

    #[tokio::test(start_paused = true)]
    async fn the_tour_walks_every_step_every_failure_and_both_endings() {
        let (screens, ending) = tour(presses).await;
        assert_eq!(ending, Ending::Done { pending: false });
        // Every step shown as the one under way, in order.
        let current: Vec<String> = screens
            .iter()
            .filter_map(|screen| match screen {
                ToPage::Progress { steps, .. } => {
                    steps.iter().find(|step| step.state == "current").map(|step| step.label.clone())
                }
                _ => None,
            })
            .collect();
        for step in Step::ALL {
            assert!(current.iter().any(|label| label == step.label()), "{:?} never shown", step);
        }
        // Every problem's screen, with its own words.
        for problem in Problem::ALL {
            assert!(
                screens.iter().any(|screen| matches!(screen, ToPage::Fix { problem: shown, .. } if *shown == problem)),
                "{problem:?} never shown"
            );
        }
        for wanted in ["restart", "link_failed", "approve"] {
            let json: Vec<String> = screens.iter().map(|screen| serde_json::to_string(screen).unwrap()).collect();
            assert!(
                json.iter().any(|screen| screen.contains(&format!("\"screen\":\"{wanted}\""))),
                "{wanted} never shown"
            );
        }
        assert!(screens.iter().any(|screen| matches!(screen, ToPage::Done { pending: true, .. })));
        assert!(matches!(screens.last(), Some(ToPage::Done { pending: false, .. })));
        // The welcome says it's a demo, and nothing in the tour is a real laptop's.
        assert!(matches!(screens.first(), Some(ToPage::Welcome { demo: true, .. })));
        let all = serde_json::to_string(&screens).unwrap();
        assert!(!all.contains("Users\\\\") || all.contains("you"), "only the invented path");
    }

    #[tokio::test(start_paused = true)]
    async fn the_download_counts_up_in_mb_with_the_time_left_and_the_overall_climbs() {
        let (screens, _) = tour(presses).await;
        let downloads: Vec<(&str, u32, u32)> = screens
            .iter()
            .filter_map(|screen| match screen {
                ToPage::Progress { steps, overall, .. } => steps
                    .iter()
                    .find(|step| step.state == "current" && step.label == Step::Download.label())
                    .and_then(|step| Some((step.detail.as_deref()?, step.percent?, *overall))),
                _ => None,
            })
            .collect();
        assert!(downloads.len() >= 10);
        assert!(downloads[0].0.starts_with("Downloading · 25 of 310 MB"));
        assert!(downloads.last().unwrap().0.starts_with("Downloading · 310 of 310 MB"));
        assert_eq!(downloads.last().unwrap().1, 100);
        assert!(
            downloads.windows(2).all(|pair| pair[0].1 <= pair[1].1 && pair[0].2 <= pair[1].2),
            "never goes backwards"
        );
        assert!(downloads.iter().any(|(detail, ..)| detail.contains("left")));
    }

    #[tokio::test(start_paused = true)]
    async fn closing_the_window_ends_the_tour_wherever_it_is_with_nothing_to_undo() {
        let (_screens, ending) = tour(|screen| match screen {
            ToPage::Welcome { .. } => Some(FromPage::SetUp),
            ToPage::Progress { overall, .. } if *overall > 30 => Some(FromPage::ConfirmClose),
            _ => None,
        })
        .await;
        assert_eq!(ending, Ending::Undo);
    }

    #[tokio::test(start_paused = true)]
    async fn diagnostics_on_a_demo_screen_names_a_pretend_file_and_makes_none() {
        let asked = std::cell::Cell::new(false);
        let (screens, _) = tour(|screen| match screen {
            ToPage::Welcome { .. } if !asked.replace(true) => Some(FromPage::Diagnostics),
            ToPage::DiagnosticsSaved { .. } => Some(FromPage::ConfirmClose),
            _ => None,
        })
        .await;
        assert_eq!(screens.len(), 2);
        assert_eq!(screens[1], ToPage::DiagnosticsSaved { path: PRETEND_ZIP.to_owned() });
    }
}
