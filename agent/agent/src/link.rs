//! `idleo-agent link`: links this machine to its owner's account by approval, with no code to copy (ADR 0032).
//!
//! The agent asks the control plane for a link, hands the person its address (the caller opens the browser, or the
//! setup app shows its own screen), and polls until the owner approves in the web app. The ticket it gets back is the
//! one-time enrolment code, which it trades for its certificate exactly as `enrol` does, so the private key is made
//! here and never leaves. Progress goes through a callback and nothing is printed from in here, so the command line
//! and the Windows setup app drive the same function.

use std::fmt;
use std::sync::Arc;
use std::time::Duration;

use reqwest::StatusCode;
use rustls::RootCertStore;
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use uuid::Uuid;

use crate::enrol::{self, EnrolError, EnrolmentCode};
use crate::state;
use crate::tls::Target;

/// How many polls in a row may fail to reach the control plane before the link gives up (a few seconds each).
const MAX_FAILED_POLLS: u32 = 5;
/// Longer than the control plane's 10 minutes, so its own "expired" answer is what usually ends a link.
const DEADLINE_SLACK: Duration = Duration::from_secs(30);

/// A GPU, as the approval page shows it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkGpu {
    /// Marketing name.
    pub model: String,
    /// Total memory, in MB.
    pub vram_mb: i32,
}

/// What the agent says of its machine: only what the owner sees on the approval page.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Machine {
    /// The name offered to the owner, who may change it.
    #[serde(rename = "machineName")]
    pub name: String,
    /// `windows`, `linux` or `macos`.
    pub os: String,
    /// The agent's own version.
    pub agent_version: String,
    /// Its GPUs, at most eight.
    pub gpus: Vec<LinkGpu>,
}

/// Where to link and what to link.
pub struct Settings {
    /// The control plane's REST address, such as `https://api.idleo.in`.
    pub api_url: String,
    /// The machine, as it asks to be linked.
    pub machine: Machine,
    /// The agent endpoint the enrolment that follows dials.
    pub target: Target,
    /// The root the control plane's agent endpoint must chain to.
    pub roots: Arc<RootCertStore>,
    /// Where the identity is saved.
    pub data_dir: std::path::PathBuf,
    /// Replace an identity that exists, keeping a backup.
    pub force: bool,
    /// Poll at this interval instead of the one the control plane gives (tests).
    pub poll_interval: Option<Duration>,
}

/// Where a link has got to, for the caller to show.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Progress {
    /// Asking the control plane for a link.
    Asking,
    /// The owner must open this address and approve.
    ApproveAt {
        /// The web app's approval page for this link.
        url: String,
        /// How long the link can be approved.
        expires_in: Duration,
    },
    /// Waiting for the owner to approve.
    Waiting,
    /// Approved: trading the ticket for a certificate.
    Enrolling,
}

/// A finished link.
#[derive(Debug, Clone)]
pub struct Linked {
    /// The host this machine now is.
    pub host_id: Uuid,
    /// The name the owner confirmed.
    pub host_name: String,
    /// The first letter of the owner's address, if the control plane said it.
    pub owner_hint: Option<String>,
    /// When the first certificate expires.
    pub not_after: OffsetDateTime,
}

/// Why linking failed. Nothing is saved in any of these cases.
#[derive(Debug)]
pub enum LinkError {
    /// An identity already exists and `force` wasn't given.
    AlreadyEnrolled(std::path::PathBuf),
    /// The owner said this isn't their laptop.
    Denied,
    /// Nobody approved within the link's ten minutes, or it was already used.
    Expired,
    /// The control plane couldn't be reached.
    Network(String),
    /// The control plane refused the request for another reason.
    Refused(String),
    /// Approved, but trading the ticket for a certificate failed.
    Enrol(EnrolError),
}

impl LinkError {
    /// The process exit code of `idleo-agent link`: 2 denied, 3 expired, 4 network, 5 already linked, 6 the identity
    /// couldn't be saved, 1 anything else.
    #[must_use]
    pub fn exit_code(&self) -> u8 {
        match self {
            Self::Denied => 2,
            Self::Expired => 3,
            Self::Network(_) | Self::Enrol(EnrolError::Connect(_)) => 4,
            Self::AlreadyEnrolled(_) => 5,
            Self::Enrol(EnrolError::State(_)) => 6,
            Self::Refused(_) | Self::Enrol(_) => 1,
        }
    }
}

impl fmt::Display for LinkError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::AlreadyEnrolled(dir) => write!(
                f,
                "this machine is already linked (identity in {}); to replace it, run link again with --force, which keeps a backup",
                dir.display()
            ),
            Self::Denied => {
                f.write_str("the link was denied: it isn't your laptop. Run link again if that was a mistake")
            }
            Self::Expired => {
                f.write_str("the link expired before it was approved (links last 10 minutes). Run link again")
            }
            Self::Network(e) => write!(f, "couldn't reach the control plane: {e}"),
            Self::Refused(e) => write!(f, "the control plane refused the link: {e}"),
            Self::Enrol(e) => write!(f, "approved, but this machine couldn't finish linking: {e}"),
        }
    }
}

impl std::error::Error for LinkError {}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Started {
    id: Uuid,
    approve_url: String,
    poll_token: String,
    interval_seconds: u64,
    expires_in_seconds: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Ticket {
    host_name: String,
    enrolment_code: String,
    owner_hint: Option<String>,
}

#[derive(Deserialize)]
struct Problem {
    #[serde(rename = "type", default)]
    kind: String,
}

/// What one poll said.
enum Polled {
    Ticket(Ticket),
    Pending,
    Denied,
    Expired,
}

/// Links this machine: asks, waits for the owner's approval, and enrols with the ticket.
///
/// # Errors
/// See [`LinkError`]; nothing is saved on any error.
pub async fn link(
    settings: &Settings,
    client: &reqwest::Client,
    progress: &mut dyn FnMut(Progress),
) -> Result<Linked, LinkError> {
    if !settings.force && state::is_enrolled(&settings.data_dir).map_err(|e| LinkError::Enrol(EnrolError::State(e)))? {
        return Err(LinkError::AlreadyEnrolled(settings.data_dir.clone()));
    }
    progress(Progress::Asking);
    let base = settings.api_url.trim_end_matches('/');
    let started = start(client, base, &settings.machine).await?;
    let expires_in = Duration::from_secs(started.expires_in_seconds);
    progress(Progress::ApproveAt { url: started.approve_url.clone(), expires_in });
    progress(Progress::Waiting);

    let interval = settings.poll_interval.unwrap_or(Duration::from_secs(started.interval_seconds.max(1)));
    let deadline = tokio::time::Instant::now() + expires_in + DEADLINE_SLACK;
    let mut failed = 0;
    let ticket = loop {
        tokio::time::sleep(interval).await;
        if tokio::time::Instant::now() > deadline {
            return Err(LinkError::Expired);
        }
        match poll(client, base, &started).await {
            Ok(Polled::Ticket(ticket)) => break ticket,
            Ok(Polled::Pending) => failed = 0,
            Ok(Polled::Denied) => return Err(LinkError::Denied),
            Ok(Polled::Expired) => return Err(LinkError::Expired),
            Err(LinkError::Network(e)) => {
                failed += 1;
                if failed >= MAX_FAILED_POLLS {
                    return Err(LinkError::Network(e));
                }
            }
            Err(e) => return Err(e),
        }
    };

    progress(Progress::Enrolling);
    let code = EnrolmentCode::parse(&ticket.enrolment_code)
        .map_err(|_| LinkError::Refused("the ticket's code isn't in the enrolment code format".to_owned()))?;
    let enrolled = enrol::enrol(&settings.target, &settings.roots, &settings.data_dir, &code, settings.force)
        .await
        .map_err(LinkError::Enrol)?;
    Ok(Linked {
        host_id: enrolled.host_id,
        host_name: ticket.host_name,
        owner_hint: ticket.owner_hint.filter(|hint| !hint.is_empty()),
        not_after: enrolled.not_after,
    })
}

async fn start(client: &reqwest::Client, base: &str, machine: &Machine) -> Result<Started, LinkError> {
    let body = serde_json::to_string(machine).map_err(|e| LinkError::Refused(e.to_string()))?;
    let response = client
        .post(format!("{base}/v1/host-links"))
        .header("Content-Type", "application/json")
        .body(body)
        .send()
        .await
        .map_err(|e| LinkError::Network(describe(&e)))?;
    if response.status() == StatusCode::TOO_MANY_REQUESTS {
        return Err(LinkError::Refused("too many link requests from this address; try again in a while".to_owned()));
    }
    if !response.status().is_success() {
        return Err(LinkError::Refused(format!("it answered {}", response.status())));
    }
    response.json_str().await
}

async fn poll(client: &reqwest::Client, base: &str, started: &Started) -> Result<Polled, LinkError> {
    let body = format!("{{\"pollToken\":{}}}", serde_json::Value::String(started.poll_token.clone()));
    let response = client
        .post(format!("{base}/v1/host-links/{}/ticket", started.id))
        .header("Content-Type", "application/json")
        .body(body)
        .send()
        .await
        .map_err(|e| LinkError::Network(describe(&e)))?;
    let status = response.status();
    if status.is_success() {
        return response.json_str::<Ticket>().await.map(Polled::Ticket);
    }
    if status == StatusCode::NOT_FOUND {
        // A link the control plane no longer knows: the same as one that ran out.
        return Ok(Polled::Expired);
    }
    if status == StatusCode::TOO_MANY_REQUESTS || status.is_server_error() {
        return Err(LinkError::Network(format!("it answered {status}")));
    }
    let text = response.text().await.map_err(|e| LinkError::Network(describe(&e)))?;
    let kind = serde_json::from_str::<Problem>(&text).map(|p| p.kind).unwrap_or_default();
    match kind.as_str() {
        "/problems/authorization-pending" => Ok(Polled::Pending),
        "/problems/host-link-denied" => Ok(Polled::Denied),
        "/problems/host-link-expired" => Ok(Polled::Expired),
        _ => Err(LinkError::Refused(format!("it answered {status}"))),
    }
}

/// Reads a JSON body without reqwest's `json` feature (which this crate doesn't use).
trait JsonStr {
    async fn json_str<T: for<'de> Deserialize<'de>>(self) -> Result<T, LinkError>;
}

impl JsonStr for reqwest::Response {
    async fn json_str<T: for<'de> Deserialize<'de>>(self) -> Result<T, LinkError> {
        let text = self.text().await.map_err(|e| LinkError::Network(describe(&e)))?;
        serde_json::from_str(&text)
            .map_err(|_| LinkError::Refused("its answer wasn't what a link looks like".to_owned()))
    }
}

fn describe(error: &reqwest::Error) -> String {
    if error.is_timeout() {
        "no answer in time".to_owned()
    } else if error.is_connect() {
        "couldn't connect".to_owned()
    } else {
        // Never the URL: it holds nothing secret, but the error text of a failed request may carry a token's path.
        "the request failed".to_owned()
    }
}

/// How to open `url` in the person's default browser on this system, as a program and its arguments.
#[must_use]
pub fn browser_command(os: &str, url: &str) -> (&'static str, Vec<String>) {
    match os {
        "macos" => ("open", vec![url.to_owned()]),
        "windows" => ("explorer.exe", vec![url.to_owned()]),
        _ => ("xdg-open", vec![url.to_owned()]),
    }
}

/// Opens `url` in the default browser; false if there is none to open (a headless machine), in which case the caller
/// prints the address for the person to open anywhere.
#[must_use]
pub fn open_browser(url: &str) -> bool {
    let os = if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    };
    let (program, args) = browser_command(os, url);
    std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .is_ok()
}

/// This machine's name, for the approval page: the computer's own, which the owner may change there.
#[must_use]
pub fn local_machine_name() -> String {
    let from_env = ["COMPUTERNAME", "HOSTNAME"]
        .iter()
        .find_map(|key| std::env::var(key).ok().filter(|value| !value.trim().is_empty()));
    let name = from_env
        .or_else(|| {
            ["/proc/sys/kernel/hostname", "/etc/hostname"]
                .iter()
                .find_map(|path| std::fs::read_to_string(path).ok().filter(|value| !value.trim().is_empty()))
        })
        .or_else(|| {
            std::process::Command::new("hostname")
                .output()
                .ok()
                .and_then(|output| String::from_utf8(output.stdout).ok())
                .filter(|value| !value.trim().is_empty())
        })
        .unwrap_or_else(|| "My computer".to_owned());
    name.trim().chars().take(100).collect()
}

/// The `os` the control plane is told: `windows`, `linux` or `macos`.
#[must_use]
pub fn os_name() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_browser_is_opened_the_way_each_system_does_it() {
        assert_eq!(browser_command("macos", "http://x/").0, "open");
        assert_eq!(browser_command("linux", "http://x/").0, "xdg-open");
        assert_eq!(browser_command("windows", "http://x/").0, "explorer.exe");
        assert_eq!(browser_command("linux", "http://x/?a=1&b=2").1, ["http://x/?a=1&b=2"]);
    }

    #[test]
    fn each_way_it_ends_has_its_own_exit_code() {
        assert_eq!(LinkError::Denied.exit_code(), 2);
        assert_eq!(LinkError::Expired.exit_code(), 3);
        assert_eq!(LinkError::Network(String::new()).exit_code(), 4);
        assert_eq!(LinkError::AlreadyEnrolled(std::path::PathBuf::new()).exit_code(), 5);
        assert_eq!(LinkError::Refused(String::new()).exit_code(), 1);
    }

    #[test]
    fn the_machine_is_sent_in_the_shape_the_control_plane_reads() {
        let machine = Machine {
            name: "ASUS ROG Strix".to_owned(),
            os: "windows".to_owned(),
            agent_version: "0.1.0".to_owned(),
            gpus: vec![LinkGpu { model: "RTX 4080 Laptop".to_owned(), vram_mb: 12282 }],
        };
        let json: serde_json::Value = serde_json::from_str(&serde_json::to_string(&machine).unwrap()).unwrap();
        assert_eq!(json["machineName"], "ASUS ROG Strix");
        assert_eq!(json["agentVersion"], "0.1.0");
        assert_eq!(json["gpus"][0]["vramMb"], 12282);
    }

    #[test]
    fn the_machine_name_is_never_empty_and_never_long() {
        let name = local_machine_name();
        assert_ne!(name, "");
        assert!(name.chars().count() <= 100);
    }
}
