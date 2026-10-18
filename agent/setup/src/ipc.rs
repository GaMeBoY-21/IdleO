//! The setup window's messages (ADR 0033): one typed module for what Rust tells the page and what the page tells Rust,
//! as JSON. Rust sends with `evaluate_script("window.idleo.receive(…)")`; the page sends with `window.ipc.postMessage`.

use serde::{Deserialize, Serialize};

/// A problem with its one screen and one button (host experience §4).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Problem {
    /// Windows is older than 10 22H2.
    WindowsTooOld,
    /// The NVIDIA driver is older than we support.
    DriverTooOld,
    /// Virtualization is off in the firmware.
    VirtualizationOff,
    /// No NVIDIA RTX GPU with 8 GB.
    NoRtxGpu,
    /// Not enough free disk.
    NotEnoughDisk,
    /// The self-test didn't pass, or the download didn't match the release.
    SomethingDidntPass,
    /// IdleO can't be reached.
    CantReachIdleo,
}

impl Problem {
    /// What the screen says.
    #[must_use]
    pub fn text(self, free_up_gb: u64) -> String {
        match self {
            Self::WindowsTooOld => "This laptop needs Windows 10 22H2 or Windows 11 first.".to_owned(),
            Self::DriverTooOld => "Your graphics driver needs an update (2 min).".to_owned(),
            Self::VirtualizationOff => "One setting is off in your laptop's startup menu.".to_owned(),
            Self::NoRtxGpu => "This laptop's graphics card can't run renters' jobs yet.".to_owned(),
            Self::NotEnoughDisk => format!("Free up {free_up_gb} GB to host."),
            Self::SomethingDidntPass => {
                "Something didn't pass. We've been told; you don't need to do anything.".to_owned()
            }
            Self::CantReachIdleo => "We can't reach IdleO. Check you're online.".to_owned(),
        }
    }

    /// The one button.
    #[must_use]
    pub fn button(self) -> &'static str {
        match self {
            Self::WindowsTooOld => "Open Windows Update",
            Self::DriverTooOld => "Update driver",
            Self::VirtualizationOff => "Show me how",
            Self::NoRtxGpu | Self::SomethingDidntPass => "OK",
            Self::NotEnoughDisk => "Open Storage settings",
            Self::CantReachIdleo => "Try again",
        }
    }
}

/// One step on the progress screen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StepView {
    /// Its plain words, for example "Checking your laptop".
    pub label: String,
    /// `done`, `current`, `waiting` or `failed`.
    pub state: String,
    /// A detail under it, for example "Downloading · 120 of 310 MB".
    pub detail: Option<String>,
}

/// What Rust tells the page.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "screen", rename_all = "snake_case")]
pub enum ToPage {
    /// The first screen, or "already set up" with Repair.
    Welcome {
        /// IdleO is already installed and linked here.
        already_set_up: bool,
        /// A development build says so in its title.
        dev_build: bool,
        /// An earlier setup stopped part-way and was kept: Repair carries on from the step that didn't pass.
        unfinished: bool,
    },
    /// The steps, ticked as they go.
    Progress {
        /// Every step, in order.
        steps: Vec<StepView>,
        /// A warning shown under the list (unplugged), if any.
        note: Option<String>,
    },
    /// A problem with one button.
    Fix {
        /// Which.
        problem: Problem,
        /// What it says.
        text: String,
        /// The button.
        button: String,
        /// For "Update driver": checking again by itself.
        rechecking: bool,
        /// A development build offers "Keep it for troubleshooting" beside the button.
        keep: bool,
    },
    /// Approve in the browser.
    Approve {
        /// The machine's name, shown big.
        machine_name: String,
        /// The approval page.
        url: String,
        /// The same, without `https://`, to type on a phone.
        short_url: String,
    },
    /// The link was denied or ran out.
    LinkFailed {
        /// What happened, in plain words.
        message: String,
    },
    /// Windows needs one restart.
    Restart,
    /// Set up.
    Done {
        /// The host waits for IdleO's check (the pilot); false when it's live.
        pending: bool,
        /// A development build says when IdleO didn't take setup's measurements: `422`, `no answer`.
        report_problem: Option<String>,
    },
    /// A development build's last screen after a setup that didn't finish: where the log is.
    Stopped {
        /// Kept for troubleshooting, not undone.
        kept: bool,
        /// The setup log's file.
        log: String,
        /// When IdleO didn't take setup's measurements: `422`, `no answer`.
        report_problem: Option<String>,
    },
}

/// What the page tells Rust.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum FromPage {
    /// The page has loaded.
    Ready,
    /// Set up (click 2), or Repair.
    SetUp,
    /// A fix screen's button.
    Fix,
    /// Try again (a link denied or expired, or IdleO unreachable).
    TryAgain,
    /// Open the approval page again.
    OpenAgain,
    /// Restart now.
    RestartNow,
    /// Restart later.
    Later,
    /// Close: the window asks "Stop setting up?" itself.
    Close,
    /// Keep it for troubleshooting (development builds): leave what's installed and close.
    Keep,
    /// Yes, stop setting up: undo everything and close.
    ConfirmClose,
}

impl FromPage {
    /// Reads a message from the page; `None` for anything else.
    #[must_use]
    pub fn parse(text: &str) -> Option<Self> {
        serde_json::from_str(text).ok()
    }
}

/// The script that hands a message to the page.
///
/// # Panics
/// Never: the types always serialise.
#[must_use]
pub fn script(message: &ToPage) -> String {
    format!("window.idleo.receive({});", serde_json::to_string(message).expect("the page's messages serialise"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_round_trip_as_json() {
        let messages = [
            ToPage::Welcome { already_set_up: false, dev_build: true, unfinished: false },
            ToPage::Fix {
                problem: Problem::DriverTooOld,
                text: Problem::DriverTooOld.text(0),
                button: "Update driver".into(),
                rechecking: true,
                keep: false,
            },
            ToPage::Approve {
                machine_name: "ASUS ROG Strix".into(),
                url: "https://app.idleo.in/link/1/".into(),
                short_url: "app.idleo.in/link/1/".into(),
            },
            ToPage::Done { pending: true, report_problem: Some("422".into()) },
            ToPage::Stopped { kept: true, log: r"C:\x\setup.log".into(), report_problem: None },
        ];
        for message in messages {
            let text = serde_json::to_string(&message).unwrap();
            assert_eq!(serde_json::from_str::<ToPage>(&text).unwrap(), message);
        }
        assert!(script(&ToPage::Restart).starts_with("window.idleo.receive({\"screen\":\"restart\"}"));
    }

    #[test]
    fn the_page_messages_parse_and_anything_else_is_ignored() {
        assert_eq!(FromPage::parse(r#"{"type":"set_up"}"#), Some(FromPage::SetUp));
        assert_eq!(FromPage::parse(r#"{"type":"confirm_close"}"#), Some(FromPage::ConfirmClose));
        assert_eq!(FromPage::parse(r#"{"type":"keep"}"#), Some(FromPage::Keep));
        assert_eq!(FromPage::parse(r#"{"type":"format_disk"}"#), None);
        assert_eq!(FromPage::parse("nonsense"), None);
    }

    #[test]
    fn each_problem_has_the_words_and_button_the_spec_gives() {
        assert_eq!(Problem::DriverTooOld.text(0), "Your graphics driver needs an update (2 min).");
        assert_eq!(Problem::VirtualizationOff.button(), "Show me how");
        assert_eq!(Problem::NotEnoughDisk.text(23), "Free up 23 GB to host.");
        assert_eq!(Problem::NoRtxGpu.button(), "OK");
        assert_eq!(
            Problem::SomethingDidntPass.text(0),
            "Something didn't pass. We've been told; you don't need to do anything."
        );
    }
}
