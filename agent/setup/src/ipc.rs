//! The setup window's messages (ADR 0033, ADR 0061): one typed module for what Rust tells the page and what the page tells Rust,
//! as JSON. Rust sends with `evaluate_script("window.idleo.receive(…)")`; the page sends with `window.ipc.postMessage`.
//!
//! Every way setup can go wrong is a [`Problem`]: it has plain words ([`Problem::words`]), one button ([`Problem::button`]) and,
//! where there is somewhere to go to fix it, one address ([`Problem::address`]). `reasons.rs` is the other half: the short
//! codes the same problems are measured under.

use serde::{Deserialize, Serialize};

/// A way setup can go wrong, with its one screen and one button.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Problem {
    /// Windows is older than 10 22H2.
    WindowsTooOld,
    /// Windows runs in S mode, which only runs apps from the Microsoft Store.
    WindowsSMode,
    /// The processor is ARM, which IdleO doesn't support yet.
    ArmLaptop,
    /// No NVIDIA graphics card is visible: none, or no NVIDIA driver.
    NoNvidiaGpu,
    /// An NVIDIA graphics card, but not an RTX with 8 GB.
    NoRtxGpu,
    /// The NVIDIA driver is older than we support.
    DriverTooOld,
    /// Virtualization is off in the firmware.
    VirtualizationOff,
    /// Not enough free disk.
    NotEnoughDisk,
    /// Running on battery.
    OnBattery,
    /// An antivirus took a file setup downloaded or wrote.
    AntivirusBlocked,
    /// The self-test didn't pass, or the download didn't match the release.
    SomethingDidntPass,
    /// IdleO can't be reached: no internet, or a proxy in the way.
    CantReachIdleo,
}

/// What the words on a problem's screen may mention.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Facts {
    /// How many GB to free up.
    pub free_up_gb: u64,
    /// The drive setup installs to, for example `C:`.
    pub drive: String,
    /// How many GB are free there now.
    pub free_gb: u64,
    /// How many GB IdleO needs.
    pub needed_gb: u64,
    /// The file an antivirus blocked.
    pub file: String,
    /// The PC is set to go through a proxy.
    pub proxy: bool,
    /// The graphics driver found.
    pub driver: String,
}

impl Problem {
    /// Every problem, for the preview and the tests.
    pub const ALL: [Self; 12] = [
        Self::WindowsTooOld,
        Self::WindowsSMode,
        Self::ArmLaptop,
        Self::NoNvidiaGpu,
        Self::NoRtxGpu,
        Self::DriverTooOld,
        Self::VirtualizationOff,
        Self::NotEnoughDisk,
        Self::OnBattery,
        Self::AntivirusBlocked,
        Self::SomethingDidntPass,
        Self::CantReachIdleo,
    ];

    /// Its name, as the page and the measurements know it.
    #[must_use]
    pub fn name(self) -> &'static str {
        match self {
            Self::WindowsTooOld => "windows_too_old",
            Self::WindowsSMode => "windows_s_mode",
            Self::ArmLaptop => "arm_laptop",
            Self::NoNvidiaGpu => "no_nvidia_gpu",
            Self::NoRtxGpu => "no_rtx_gpu",
            Self::DriverTooOld => "driver_too_old",
            Self::VirtualizationOff => "virtualization_off",
            Self::NotEnoughDisk => "not_enough_disk",
            Self::OnBattery => "on_battery",
            Self::AntivirusBlocked => "antivirus_blocked",
            Self::SomethingDidntPass => "something_didnt_pass",
            Self::CantReachIdleo => "cant_reach_idleo",
        }
    }

    /// The big words and the plain ones under them.
    #[must_use]
    pub fn words(self, facts: &Facts) -> (String, String) {
        let (title, text) = match self {
            Self::WindowsTooOld => (
                "Windows needs an update".to_owned(),
                "IdleO needs Windows 10 version 22H2, or Windows 11. Update Windows and we'll carry on by ourselves.".to_owned(),
            ),
            Self::WindowsSMode => (
                "Windows is in S mode".to_owned(),
                "S mode only runs apps from the Microsoft Store, so IdleO can't run on this laptop. It isn't supported.".to_owned(),
            ),
            Self::ArmLaptop => (
                "This laptop isn't supported yet".to_owned(),
                "It has an ARM processor. IdleO works on laptops with an Intel or AMD processor for now.".to_owned(),
            ),
            Self::NoNvidiaGpu => (
                "We can't see an NVIDIA graphics card".to_owned(),
                "IdleO needs a laptop with an NVIDIA RTX card. If this one has an NVIDIA card, install NVIDIA's driver and we'll carry on by ourselves.".to_owned(),
            ),
            Self::NoRtxGpu => (
                "This graphics card can't run jobs yet".to_owned(),
                "IdleO needs an NVIDIA RTX card with 8 GB of memory or more.".to_owned(),
            ),
            Self::DriverTooOld => (
                "Your graphics driver is too old".to_owned(),
                "Update it (about 2 minutes) and we'll carry on by ourselves.".to_owned(),
            ),
            Self::VirtualizationOff => (
                "Virtualization is off".to_owned(),
                "It's one setting in your laptop's startup menu (the BIOS). Turn on Virtualization (it may be called Intel VT-x, AMD-V or SVM), restart, then open IdleO setup again. If you've done it already, check again.".to_owned(),
            ),
            Self::NotEnoughDisk => {
                let drive = if facts.drive.is_empty() { "your drive" } else { facts.drive.as_str() };
                (
                    "Not enough free space".to_owned(),
                    format!(
                        "IdleO needs {} GB free on {drive}, and there are {}. Free up {} GB and we'll carry on by ourselves.",
                        facts.needed_gb, facts.free_gb, facts.free_up_gb
                    ),
                )
            }
            Self::OnBattery => (
                "Plug in your laptop".to_owned(),
                "Setup is quicker and safer plugged in, and IdleO only works on mains power. We'll carry on the moment it is.".to_owned(),
            ),
            Self::AntivirusBlocked => {
                let file = if facts.file.is_empty() { "a file" } else { facts.file.as_str() };
                (
                    "Your antivirus blocked a file".to_owned(),
                    format!("It blocked {file}. Allow it in your antivirus, or add IdleO's folder to what it leaves alone, then try again."),
                )
            }
            Self::SomethingDidntPass => (
                "Something didn't pass".to_owned(),
                "We've been told; you don't need to do anything.".to_owned(),
            ),
            Self::CantReachIdleo => (
                "Check your connection".to_owned(),
                if facts.proxy {
                    "We can't reach IdleO. This PC is set to use a proxy, which IdleO can't go through yet: turn it off, or use another network, then try again.".to_owned()
                } else {
                    "We can't reach IdleO. Check this laptop is online, then try again.".to_owned()
                },
            ),
        };
        (title, text)
    }

    /// The one button.
    #[must_use]
    pub fn button(self) -> &'static str {
        match self {
            Self::WindowsTooOld => "Open Windows Update",
            Self::NoNvidiaGpu | Self::DriverTooOld => "Get NVIDIA's driver",
            Self::VirtualizationOff | Self::OnBattery => "Check again",
            Self::NotEnoughDisk => "Open Storage settings",
            Self::AntivirusBlocked | Self::CantReachIdleo => "Try again",
            Self::WindowsSMode | Self::ArmLaptop | Self::NoRtxGpu | Self::SomethingDidntPass => "OK",
        }
    }

    /// Where the button goes, if it opens something: the page that fixes it.
    #[must_use]
    pub fn address(self) -> Option<&'static str> {
        match self {
            Self::WindowsTooOld => Some("ms-settings:windowsupdate"),
            Self::NoNvidiaGpu | Self::DriverTooOld => Some(NVIDIA_DRIVERS),
            Self::NotEnoughDisk => Some("ms-settings:storagesense"),
            _ => None,
        }
    }

    /// Whether setup checks again by itself every few seconds and carries on the moment it's fixed.
    #[must_use]
    pub fn rechecks(self) -> bool {
        matches!(
            self,
            Self::WindowsTooOld
                | Self::NoNvidiaGpu
                | Self::DriverTooOld
                | Self::VirtualizationOff
                | Self::NotEnoughDisk
                | Self::OnBattery
        )
    }

    /// Whether the person has nothing to fix: the laptop can't host, and the button closes setup.
    #[must_use]
    pub fn is_final(self) -> bool {
        matches!(self, Self::WindowsSMode | Self::ArmLaptop | Self::NoRtxGpu)
    }

    /// A second, quiet link under the button, if there is one: "Show me how for my laptop".
    #[must_use]
    pub fn help(self) -> Option<&'static str> {
        (self == Self::VirtualizationOff).then_some("Show me how for my laptop")
    }
}

/// Where NVIDIA's driver is had.
pub const NVIDIA_DRIVERS: &str = "https://www.nvidia.com/en-us/software/nvidia-app/";

/// One step on the progress screen.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StepView {
    /// Its plain words, for example "Checking your laptop".
    pub label: String,
    /// `done`, `current`, `waiting` or `failed`.
    pub state: String,
    /// A detail under it, for example "Downloading · 120 of 310 MB".
    pub detail: Option<String>,
    /// How far this step is, 0 to 100, when it can say (the download).
    #[serde(default)]
    pub percent: Option<u32>,
}

/// What Rust tells the page.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "screen", rename_all = "snake_case")]
pub enum ToPage {
    /// The first screen, or "already set up" with Open IdleO and Repair.
    Welcome {
        /// IdleO is already installed and linked here.
        already_set_up: bool,
        /// A development build says so in its title.
        dev_build: bool,
        /// An earlier setup stopped part-way and was kept: Repair carries on from the step that didn't pass.
        unfinished: bool,
        /// The demo: nothing is installed, and the page says so.
        #[serde(default)]
        demo: bool,
    },
    /// The steps, ticked as they go.
    Progress {
        /// Every step, in order.
        steps: Vec<StepView>,
        /// A message shown under the list, if any.
        note: Option<String>,
        /// How far setup has got in all, 0 to 100.
        #[serde(default)]
        overall: u32,
    },
    /// A problem with one button.
    Fix {
        /// Which.
        problem: Problem,
        /// The big words.
        title: String,
        /// What it says.
        text: String,
        /// The button.
        button: String,
        /// Checking again by itself.
        rechecking: bool,
        /// A development build offers "Keep it for troubleshooting" beside the button.
        keep: bool,
        /// A quiet link under the button, for the way to fix it.
        #[serde(default)]
        help: Option<String>,
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
    /// Diagnostics were saved, and where: shown on whatever screen asked.
    DiagnosticsSaved {
        /// The zip's file.
        path: String,
    },
    /// Diagnostics couldn't be saved.
    DiagnosticsFailed {
        /// Why, in plain words.
        message: String,
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
    /// A fix screen's quiet link: show me how.
    Help,
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
    /// "Open IdleO" on the already-installed screen.
    OpenIdleo,
    /// "Send diagnostics" on any failure.
    Diagnostics,
    /// Show the saved diagnostics in Explorer.
    ShowDiagnostics,
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
            ToPage::Welcome { already_set_up: false, dev_build: true, unfinished: false, demo: false },
            ToPage::Fix {
                problem: Problem::DriverTooOld,
                title: "Your graphics driver is too old".into(),
                text: "Update it".into(),
                button: "Get NVIDIA's driver".into(),
                rechecking: true,
                keep: false,
                help: None,
            },
            ToPage::Approve {
                machine_name: "ASUS ROG Strix".into(),
                url: "https://app.idleo.in/link/1/".into(),
                short_url: "app.idleo.in/link/1/".into(),
            },
            ToPage::Done { pending: true, report_problem: Some("422".into()) },
            ToPage::Stopped { kept: true, log: r"C:\x\setup.log".into(), report_problem: None },
            ToPage::DiagnosticsSaved { path: r"C:\Users\x\Downloads\IdleO-diagnostics.zip".into() },
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
        assert_eq!(FromPage::parse(r#"{"type":"open_idleo"}"#), Some(FromPage::OpenIdleo));
        assert_eq!(FromPage::parse(r#"{"type":"diagnostics"}"#), Some(FromPage::Diagnostics));
        assert_eq!(FromPage::parse(r#"{"type":"format_disk"}"#), None);
        assert_eq!(FromPage::parse("nonsense"), None);
    }

    fn facts() -> Facts {
        Facts {
            free_up_gb: 29,
            drive: "C:".into(),
            free_gb: 31,
            needed_gb: 60,
            file: "idleo-distro.tar.gz".into(),
            proxy: false,
            driver: "511.00".into(),
        }
    }

    #[test]
    fn every_problem_has_a_title_plain_words_and_exactly_one_button() {
        for problem in Problem::ALL {
            let (title, text) = problem.words(&facts());
            assert!(!title.is_empty() && !title.ends_with('.'), "{problem:?}");
            assert!(text.ends_with('.') && text.chars().count() < 260, "{problem:?}: {text}");
            assert!(!problem.button().is_empty() && problem.button().chars().count() <= 24, "{problem:?}");
            assert!(
                !text.contains("BIOS") || problem == Problem::VirtualizationOff,
                "jargon only where it can't be helped"
            );
        }
    }

    #[test]
    fn each_problem_says_what_the_table_in_the_brief_says() {
        let words = |problem: Problem| problem.words(&facts()).1;
        assert!(words(Problem::WindowsTooOld).contains("22H2") && words(Problem::WindowsTooOld).contains("Windows 11"));
        assert!(
            words(Problem::WindowsSMode).contains("S mode") && words(Problem::WindowsSMode).contains("isn't supported")
        );
        assert!(
            words(Problem::ArmLaptop).contains("ARM") && words(Problem::ArmLaptop).contains("yet")
                || Problem::ArmLaptop.words(&facts()).0.contains("yet")
        );
        assert_eq!(Problem::NoNvidiaGpu.address(), Some(NVIDIA_DRIVERS));
        assert_eq!(Problem::DriverTooOld.address(), Some(NVIDIA_DRIVERS));
        assert_eq!(
            Problem::NotEnoughDisk.words(&facts()).1,
            "IdleO needs 60 GB free on C:, and there are 31. Free up 29 GB and we'll carry on by ourselves."
        );
        assert!(
            words(Problem::OnBattery).contains("Plug in") || Problem::OnBattery.words(&facts()).0.contains("Plug in")
        );
        assert_eq!(Problem::OnBattery.button(), "Check again");
        assert_eq!(Problem::VirtualizationOff.button(), "Check again");
        assert_eq!(Problem::VirtualizationOff.help(), Some("Show me how for my laptop"));
        assert!(words(Problem::AntivirusBlocked).contains("idleo-distro.tar.gz"));
        assert!(words(Problem::CantReachIdleo).contains("online"));
    }

    #[test]
    fn a_proxy_is_named_when_one_is_set() {
        let behind = Facts { proxy: true, ..facts() };
        assert!(Problem::CantReachIdleo.words(&behind).1.contains("proxy"));
        assert!(!Problem::CantReachIdleo.words(&facts()).1.contains("proxy"));
    }

    #[test]
    fn a_laptop_that_cant_host_has_a_final_screen_and_a_fixable_one_carries_on_by_itself() {
        for problem in Problem::ALL {
            assert!(!(problem.is_final() && problem.rechecks()), "{problem:?} can't be both");
        }
        assert!(Problem::ArmLaptop.is_final() && Problem::WindowsSMode.is_final() && Problem::NoRtxGpu.is_final());
        assert!(
            Problem::VirtualizationOff.rechecks() && Problem::OnBattery.rechecks() && Problem::NotEnoughDisk.rechecks()
        );
        assert!(!Problem::CantReachIdleo.rechecks(), "a connection is retried by the button");
    }

    #[test]
    fn the_names_are_unique_and_the_page_can_use_them() {
        let names: std::collections::HashSet<_> = Problem::ALL.iter().map(|problem| problem.name()).collect();
        assert_eq!(names.len(), Problem::ALL.len());
        for problem in Problem::ALL {
            assert_eq!(serde_json::to_string(&problem).unwrap(), format!("\"{}\"", problem.name()));
        }
    }
}
