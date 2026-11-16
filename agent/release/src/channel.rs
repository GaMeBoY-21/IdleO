//! The local channel between `idleo-hostd` and the tray and launcher (ADR 0033): JSON lines over a named pipe,
//! `\\.\pipe\idleo-<owner SID>`, whose access list allows only the owner and SYSTEM. Every line carries `"v": 1`; a
//! line of any other version is refused, never guessed at.
//!
//! - A client sends [`Request`]s, each with an `id`; the agent answers each with [`Message::Done`], [`Message::Failed`]
//!   or, for a self-test or an uninstall, a message of its own with the same `id`.
//! - The agent pushes [`Message::Status`] to every client when it connects and on every change.
//!
//! This module is the protocol only, one implementation for the agent, the tray and the launcher; the transport is
//! any `AsyncBufRead`/`AsyncWrite` pair, so tests run over an in-memory pipe.

use std::fmt;

use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};

use crate::settings::HostSettings;

/// The protocol's version, on every line.
pub const VERSION: u32 = 1;
/// What `idleo-hostd` exits with when its owner chose Quit IdleO: the launcher stops everything and doesn't start it again
/// until the next sign-in.
pub const QUIT_BY_OWNER_EXIT_CODE: u8 = 11;
/// The longest line accepted, so a broken peer can't fill memory.
pub const MAX_LINE: usize = 64 * 1024;

/// The pipe's name for a user, by their SID.
#[must_use]
pub fn pipe_name(sid: &str) -> String {
    format!(r"\\.\pipe\idleo-{sid}")
}

/// What auto-pause watches, and how long it waits after the owner stops (ADR 0033).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct AutoPause {
    /// Pause when the keyboard or mouse is used.
    pub keyboard_mouse: bool,
    /// Pause while a game or a full-screen app is open, or another program is using the GPU.
    pub games: bool,
    /// Pause while unplugged.
    pub unplugged: bool,
    /// Start again this many quiet minutes after the owner stops: 5, 10, 20, 30 or 60.
    pub start_again_after_minutes: u32,
}

impl AutoPause {
    /// The choices for "start again after".
    pub const START_AGAIN_CHOICES: [u32; 5] = [5, 10, 20, 30, 60];

    /// Whether the settings are allowed.
    #[must_use]
    pub fn is_valid(&self) -> bool {
        Self::START_AGAIN_CHOICES.contains(&self.start_again_after_minutes)
    }
}

impl Default for AutoPause {
    /// Everything on, starting again after 10 quiet minutes.
    fn default() -> Self {
        Self { keyboard_mouse: true, games: true, unplugged: true, start_again_after_minutes: 10 }
    }
}

/// What a client asks.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Request {
    /// Send the status now.
    Status,
    /// Hosting on (resume) or off (a pause with no end).
    SetHosting {
        /// On or off.
        on: bool,
    },
    /// Pause until a time, at most 7 days away.
    Pause {
        /// When it ends.
        #[serde(with = "time::serde::rfc3339")]
        until: OffsetDateTime,
    },
    /// End the owner's pause.
    Resume,
    /// Change auto-pause.
    SetAutoPause {
        /// The new settings.
        settings: AutoPause,
    },
    /// Stop a running session after a checkpoint, then tell IdleO the host is leaving. Answered by
    /// [`Message::Uninstall`] steps.
    PrepareUninstall,
    /// Run the self-test now. Answered by [`Message::SelfTest`].
    SelfTest,
    /// Change the host's settings (the IdleO window's tabs). Checked and stored by the agent, and applied at once; the new
    /// settings come back in the next status.
    SetSettings {
        /// All of them.
        settings: HostSettings,
    },
    /// Ask the tray to show the IdleO window (the Start menu, a toast, the launcher): the agent hands it on to every
    /// client as [`Message::OpenWindow`].
    OpenWindow {
        /// The tab to open: `now`, `schedule`, `limits`, `earnings` or `settings`; none opens the one last used.
        tab: Option<String>,
    },
    /// Stop IdleO: a running job saves its progress and stops first, then the agent leaves and the launcher doesn't
    /// start it again until the next sign-in. Answered by [`Message::Done`] just before the agent exits.
    Quit,
}

/// The agent's own state, as auto-pause and the owner's pause decide it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum State {
    /// Ready for renters.
    Available,
    /// Paused by auto-pause.
    AutoPaused,
    /// Hosting is off.
    HostingOff,
    /// Paused by the owner until a time.
    Paused,
}

/// Why auto-pause paused.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Reason {
    /// The keyboard or mouse was used.
    KeyboardMouse,
    /// A game or a full-screen app is open.
    Game,
    /// Another program is using the GPU.
    GpuBusy,
    /// Running on battery.
    Unplugged,
    /// Outside the hours the host set.
    Schedule,
    /// The GPU is hotter than the host's limit.
    TooHot,
    /// The connection is metered and the host said not to use those.
    Metered,
    /// The Linux space has reached the disk limit the host set.
    DiskFull,
}

/// Where the host stands with IdleO.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LinkState {
    /// Not heard from the control plane yet since starting.
    Unknown,
    /// Linked, waiting to be checked before it gets work.
    PendingApproval,
    /// Live.
    Active,
    /// Removed; the app should be uninstalled.
    Removed,
    /// Suspended by an operator.
    Suspended,
}

/// Where an update stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdatePhase {
    /// Nothing to do.
    Idle,
    /// Downloading.
    Downloading,
    /// Downloaded and checked; installs at the next idle moment.
    Ready,
    /// Installing now.
    Installing,
}

/// Where the download of job images stands, while there's one to do (ADR 0035).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "state", rename_all = "snake_case")]
pub enum Prefetch {
    /// Downloading the images renters' jobs run in, one at a time.
    Downloading {
        /// Bytes downloaded so far of the image being fetched.
        done_bytes: u64,
        /// Its size in bytes; 0 while that isn't known.
        total_bytes: u64,
        /// Which image this is, from 1.
        image: u32,
        /// How many there are to fetch in all.
        images: u32,
    },
    /// Not downloading, because the connection is metered: the owner's data allowance is theirs.
    WaitingForUnmetered,
}

/// Everything the tray shows, pushed on every change.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[allow(clippy::struct_excessive_bools)] // Facts the tray reads, each its own, not mode flags.
pub struct Status {
    /// Available, auto-paused, hosting off or paused.
    pub state: State,
    /// Why auto-pause paused, while it has.
    pub reason: Option<Reason>,
    /// When auto-pause will start again if nothing more happens; none while a trigger is still active.
    #[serde(with = "time::serde::rfc3339::option")]
    pub resumes_at: Option<OffsetDateTime>,
    /// When the owner's pause ends.
    #[serde(with = "time::serde::rfc3339::option")]
    pub paused_until: Option<OffsetDateTime>,
    /// Earned today, from the control plane; none until it has said.
    pub earned_today_paise: Option<i64>,
    /// What the running session has earned so far; none when it hasn't said yet.
    pub earning_now_paise: Option<i64>,
    /// A renter's job is running.
    pub session_running: bool,
    /// Where the host stands with IdleO.
    pub link_state: LinkState,
    /// The control plane can be reached.
    pub server_reachable: bool,
    /// Where an update stands.
    pub update: UpdatePhase,
    /// The real job runner is installed; until it is, the agent declines offers and the tray says so.
    pub runner_installed: bool,
    /// A development build.
    pub dev_build: bool,
    /// Auto-pause's settings.
    pub auto_pause: AutoPause,
    /// The renter app, from an install older than the host site's address (ADR 0058): Open dashboard goes to its
    /// `/hosting/`, which redirects to the host site. None if the agent's config doesn't say.
    pub app_url: Option<String>,
    /// The host site (ADR 0058), where the tray's Open dashboard goes; none from an older install, or a newer agent's
    /// status read by an older tray.
    #[serde(default)]
    pub host_url: Option<String>,
    /// The job images being downloaded in the background, if any.
    #[serde(default)]
    pub prefetch: Option<Prefetch>,
    /// The public website, where Help goes; none from an older install.
    #[serde(default)]
    pub site_url: Option<String>,
    /// Everything the host chose, as the agent stores it.
    #[serde(default)]
    pub settings: HostSettings,
    /// The GPU's heat and use, while a renter's job runs: never anything of the renter's.
    #[serde(default)]
    pub live: Option<Live>,
    /// Earnings from IdleO, as last heard, and when.
    #[serde(default)]
    pub earnings: Option<Earnings>,
    /// A problem that needs the host, if there is one.
    #[serde(default)]
    pub problem: Option<Problem>,
    /// The last time IdleO was heard from, for "last updated" while offline.
    #[serde(default, with = "time::serde::rfc3339::option")]
    pub server_seen_at: Option<OffsetDateTime>,
    /// This agent's version.
    #[serde(default)]
    pub version: String,
    /// The version an update installed lately (within a week), for "Updated to v…".
    #[serde(default)]
    pub updated_to: Option<String>,
}

impl Status {
    /// Where Help goes: the website's help pages.
    #[must_use]
    pub fn help_url(&self) -> Option<String> {
        self.site_url.as_ref().map(|site| format!("{}/help/", site.trim_end_matches('/')))
    }

    /// Where Open dashboard goes: the host site's home (ADR 0058), or on an older install the renter app's old Hosting
    /// address, which redirects there.
    #[must_use]
    pub fn dashboard_url(&self) -> Option<String> {
        match (&self.host_url, &self.app_url) {
            (Some(host), _) => Some(format!("{}/", host.trim_end_matches('/'))),
            (None, Some(app)) => Some(format!("{}/hosting/", app.trim_end_matches('/'))),
            (None, None) => None,
        }
    }
}

/// What a running job's laptop is doing: the GPU's heat and use, and how long the job has run. Nothing of the renter's.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Live {
    /// The hottest GPU, in °C.
    pub gpu_temp_c: Option<u32>,
    /// The busiest GPU's use, in percent.
    pub gpu_util_pct: Option<u32>,
    /// When the job started running, if the agent knows.
    #[serde(default, with = "time::serde::rfc3339::option")]
    pub job_started_at: Option<OffsetDateTime>,
}

/// One finished job, for the Earnings tab: how long it ran and what it paid the host. No renter, no service.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecentJob {
    /// When it ended.
    #[serde(with = "time::serde::rfc3339")]
    pub ended_at: OffsetDateTime,
    /// How long it ran, in seconds.
    pub seconds: u32,
    /// What it paid the host, in paise.
    pub earned_paise: i64,
}

/// What IdleO says the host has earned (billing's own figures, from the ledger), and the next payout.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Earnings {
    /// Today, India's calendar day.
    pub today_paise: i64,
    /// This week, Monday to now, India's calendar.
    pub week_paise: Option<i64>,
    /// This month, India's calendar.
    pub month_paise: Option<i64>,
    /// The last few jobs, newest first.
    #[serde(default)]
    pub recent: Vec<RecentJob>,
    /// The next payout's date, `YYYY-MM-DD`, India's calendar; none when IdleO doesn't say.
    #[serde(default)]
    pub next_payout_on: Option<String>,
    /// The latest payout sent to the host.
    #[serde(default)]
    pub last_payout: Option<PayoutNote>,
    /// When IdleO last said, on this laptop's clock.
    #[serde(with = "time::serde::rfc3339")]
    pub updated_at: OffsetDateTime,
}

impl Earnings {
    /// The same figures with the time they were heard set aside, to tell whether anything changed.
    #[must_use]
    pub fn without_age(&self) -> Self {
        Self { updated_at: OffsetDateTime::UNIX_EPOCH, ..self.clone() }
    }
}

/// A payout sent to the host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PayoutNote {
    /// When it was sent.
    #[serde(with = "time::serde::rfc3339")]
    pub sent_at: OffsetDateTime,
    /// How much, in paise.
    pub amount_paise: i64,
}

/// A problem that needs the host to do something.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Problem {
    /// The NVIDIA driver is older than the job runner needs.
    DriverTooOld,
    /// The laptop's disk is nearly full.
    DiskNearlyFull,
    /// The GPU is hotter than the host's limit.
    TooHot,
    /// Offline for more than an hour while it should be available.
    OfflineTooLong,
    /// The agent keeps crashing; sending diagnostics helps.
    Crashing,
}

/// One self-test check, for the tray.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Check {
    /// Its name.
    pub name: String,
    /// Whether it passed.
    pub passed: bool,
    /// What it found.
    pub detail: String,
}

/// Where an uninstall's goodbye has got to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UninstallStep {
    /// A renter's job is saving its progress and stopping.
    StoppingSession,
    /// Telling IdleO.
    Leaving,
    /// IdleO removed the host.
    Left,
    /// IdleO couldn't be reached in time; the machine shows offline, and its owner can remove it on the web.
    Unreachable,
}

/// What the agent sends.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
// A status is pushed a few times a minute and held by one reader; boxing it would only cost every pattern that reads it.
#[allow(clippy::large_enum_variant)]
pub enum Message {
    /// The status, on connecting and on every change.
    Status(Status),
    /// A request was done.
    Done {
        /// The request's id.
        id: u64,
    },
    /// A request failed.
    Failed {
        /// The request's id.
        id: u64,
        /// Why, in plain words.
        message: String,
    },
    /// A self-test's results.
    SelfTest {
        /// The request's id.
        id: u64,
        /// Whether every check passed.
        passed: bool,
        /// Each check.
        checks: Vec<Check>,
    },
    /// An uninstall's progress.
    Uninstall {
        /// The request's id.
        id: u64,
        /// Where it has got to.
        step: UninstallStep,
    },
    /// Somebody asked to show the IdleO window ([`Request::OpenWindow`]); the tray does.
    OpenWindow {
        /// The tab to open, if one was named.
        tab: Option<String>,
    },
}

/// A request's line: the version, the request's id, and the request.
#[derive(Serialize, Deserialize)]
struct RequestLine<T> {
    v: u32,
    id: u64,
    #[serde(flatten)]
    body: T,
}

/// A message's line: the version and the message, which carries its own request id where it answers one.
#[derive(Serialize, Deserialize)]
struct MessageLine<T> {
    v: u32,
    #[serde(flatten)]
    body: T,
}

/// Why a line couldn't be read.
#[derive(Debug)]
pub enum ChannelError {
    /// The other side closed the channel.
    Closed,
    /// A line of another protocol version.
    Version(u32),
    /// A line that isn't this protocol, or is too long.
    Malformed(String),
    /// Reading or writing failed.
    Io(std::io::Error),
}

impl fmt::Display for ChannelError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Closed => f.write_str("the channel closed"),
            Self::Version(v) => write!(f, "the other side speaks version {v} of the local channel, not {VERSION}"),
            Self::Malformed(e) => write!(f, "a line that isn't the local channel's: {e}"),
            Self::Io(e) => write!(f, "the local channel failed: {e}"),
        }
    }
}

impl std::error::Error for ChannelError {}

/// One line for a request with its id.
///
/// # Errors
/// Never in practice: the types always serialise.
pub fn encode_request(id: u64, request: &Request) -> Result<String, ChannelError> {
    line(&RequestLine { v: VERSION, id, body: request })
}

/// One line for a message from the agent.
///
/// # Errors
/// Never in practice: the types always serialise.
pub fn encode_message(message: &Message) -> Result<String, ChannelError> {
    line(&MessageLine { v: VERSION, body: message })
}

fn line<T: Serialize>(envelope: &T) -> Result<String, ChannelError> {
    let mut text = serde_json::to_string(envelope).map_err(|e| ChannelError::Malformed(e.to_string()))?;
    text.push('\n');
    Ok(text)
}

/// A request line: its id and the request.
///
/// # Errors
/// Another version, or not a request.
pub fn decode_request(line: &str) -> Result<(u64, Request), ChannelError> {
    check_version(line)?;
    let envelope: RequestLine<Request> =
        serde_json::from_str(line).map_err(|e| ChannelError::Malformed(e.to_string()))?;
    Ok((envelope.id, envelope.body))
}

/// A message line from the agent.
///
/// # Errors
/// Another version, or not a message.
pub fn decode_message(line: &str) -> Result<Message, ChannelError> {
    check_version(line)?;
    serde_json::from_str::<MessageLine<Message>>(line)
        .map(|envelope| envelope.body)
        .map_err(|e| ChannelError::Malformed(e.to_string()))
}

fn check_version(line: &str) -> Result<(), ChannelError> {
    #[derive(Deserialize)]
    struct Versioned {
        v: Option<u32>,
    }
    let versioned: Versioned = serde_json::from_str(line).map_err(|e| ChannelError::Malformed(e.to_string()))?;
    match versioned.v {
        Some(VERSION) => Ok(()),
        Some(other) => Err(ChannelError::Version(other)),
        None => Err(ChannelError::Malformed("no \"v\" field".to_owned())),
    }
}

/// Reads one line, at most [`MAX_LINE`] bytes.
///
/// # Errors
/// [`ChannelError::Closed`] at the end, or if it's too long or unreadable.
pub async fn read_line<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<String, ChannelError> {
    let mut bytes = Vec::new();
    loop {
        let buffer = reader.fill_buf().await.map_err(ChannelError::Io)?;
        if buffer.is_empty() {
            return Err(ChannelError::Closed);
        }
        if let Some(end) = buffer.iter().position(|b| *b == b'\n') {
            bytes.extend_from_slice(&buffer[..end]);
            reader.consume(end + 1);
            break;
        }
        let taken = buffer.len();
        bytes.extend_from_slice(buffer);
        reader.consume(taken);
        if bytes.len() > MAX_LINE {
            return Err(ChannelError::Malformed("a line over 64 KiB".to_owned()));
        }
    }
    if bytes.len() > MAX_LINE {
        return Err(ChannelError::Malformed("a line over 64 KiB".to_owned()));
    }
    String::from_utf8(bytes).map_err(|_| ChannelError::Malformed("not UTF-8".to_owned()))
}

/// The client's side: the tray and the launcher.
pub struct Client<R, W> {
    reader: R,
    writer: W,
    next_id: u64,
}

impl<R: AsyncBufRead + Unpin, W: AsyncWrite + Unpin> Client<R, W> {
    /// A client over a connected pipe.
    pub fn new(reader: R, writer: W) -> Self {
        Self { reader, writer, next_id: 1 }
    }

    /// Sends a request and returns its id.
    ///
    /// # Errors
    /// If it can't be written.
    pub async fn send(&mut self, request: &Request) -> Result<u64, ChannelError> {
        let id = self.next_id;
        self.next_id += 1;
        self.writer.write_all(encode_request(id, request)?.as_bytes()).await.map_err(ChannelError::Io)?;
        self.writer.flush().await.map_err(ChannelError::Io)?;
        Ok(id)
    }

    /// The next message from the agent.
    ///
    /// # Errors
    /// [`ChannelError::Closed`] when the agent has gone, or a line that isn't version 1 of this protocol.
    pub async fn next(&mut self) -> Result<Message, ChannelError> {
        decode_message(&read_line(&mut self.reader).await?)
    }
}

/// A client over the agent's named pipe.
#[cfg(windows)]
pub type PipeClient = Client<
    tokio::io::BufReader<tokio::io::ReadHalf<tokio::net::windows::named_pipe::NamedPipeClient>>,
    tokio::io::WriteHalf<tokio::net::windows::named_pipe::NamedPipeClient>,
>;

/// Connects to the signed-in user's agent. Needs a Tokio runtime.
///
/// # Errors
/// If the agent isn't running, or its pipe can't be opened.
#[cfg(windows)]
pub fn connect() -> std::io::Result<PipeClient> {
    let sid = agent_windows::current_user_sid()?;
    let pipe = tokio::net::windows::named_pipe::ClientOptions::new().open(pipe_name(&sid))?;
    let (reader, writer) = tokio::io::split(pipe);
    Ok(Client::new(tokio::io::BufReader::new(reader), writer))
}

#[cfg(test)]
mod tests {
    use tokio::io::BufReader;

    use super::*;

    fn status() -> Status {
        Status {
            state: State::AutoPaused,
            reason: Some(Reason::KeyboardMouse),
            resumes_at: Some(OffsetDateTime::from_unix_timestamp(1_760_000_600).unwrap()),
            paused_until: None,
            earned_today_paise: Some(1240),
            earning_now_paise: None,
            session_running: false,
            link_state: LinkState::Active,
            server_reachable: true,
            update: UpdatePhase::Ready,
            runner_installed: false,
            dev_build: true,
            auto_pause: AutoPause::default(),
            app_url: Some("http://192.168.1.20:3000".to_owned()),
            host_url: None,
            prefetch: None,
            site_url: Some("https://idleo.in".to_owned()),
            settings: HostSettings::default(),
            live: None,
            earnings: None,
            problem: None,
            server_seen_at: None,
            version: "0.1.0".to_owned(),
            updated_to: None,
        }
    }

    #[test]
    fn the_dashboard_is_the_host_site_or_an_older_installs_old_hosting_address() {
        let host = Status { host_url: Some("https://host.idleo.in".to_owned()), ..status() };
        assert_eq!(host.dashboard_url().as_deref(), Some("https://host.idleo.in/"));
        assert_eq!(status().dashboard_url().as_deref(), Some("http://192.168.1.20:3000/hosting/"));
        assert_eq!(Status { app_url: None, ..status() }.dashboard_url(), None);
        assert_eq!(status().help_url().as_deref(), Some("https://idleo.in/help/"));
        assert_eq!(Status { site_url: None, ..status() }.help_url(), None);
        // An older agent's status has no host_url, and still reads.
        let older = serde_json::to_string(&status()).unwrap().replace(",\"host_url\":null", "");
        assert!(!older.contains("host_url"));
        assert_eq!(serde_json::from_str::<Status>(&older).unwrap(), status());
    }

    #[test]
    fn requests_and_messages_round_trip_as_version_1_lines() {
        let requests = [
            Request::Status,
            Request::SetHosting { on: false },
            Request::Pause { until: OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap() },
            Request::Resume,
            Request::SetAutoPause { settings: AutoPause { start_again_after_minutes: 30, ..AutoPause::default() } },
            Request::PrepareUninstall,
            Request::SelfTest,
            Request::SetSettings { settings: HostSettings::default() },
            Request::OpenWindow { tab: Some("earnings".to_owned()) },
            Request::OpenWindow { tab: None },
            Request::Quit,
        ];
        for (id, request) in (1..).zip(requests) {
            let line = encode_request(id, &request).unwrap();
            assert!(line.starts_with("{\"v\":1,\"id\":"), "{line}");
            assert!(line.ends_with('\n'));
            assert_eq!(decode_request(line.trim_end()).unwrap(), (id, request));
        }
        let messages = [
            Message::Status(status()),
            Message::Done { id: 3 },
            Message::Failed { id: 4, message: "A pause must end within 7 days.".into() },
            Message::SelfTest {
                id: 5,
                passed: true,
                checks: vec![Check { name: "disk".into(), passed: true, detail: "120 GB".into() }],
            },
            Message::Uninstall { id: 6, step: UninstallStep::Unreachable },
            Message::OpenWindow { tab: Some("now".to_owned()) },
        ];
        for message in messages {
            let line = encode_message(&message).unwrap();
            assert_eq!(decode_message(line.trim_end()).unwrap(), message);
        }
        assert!(encode_message(&Message::Status(status())).unwrap().contains("\"reason\":\"keyboard_mouse\""));
    }

    #[test]
    fn a_status_from_an_older_agent_reads_with_the_new_fields_empty() {
        let full = serde_json::to_value(status()).unwrap();
        let mut older = full.as_object().unwrap().clone();
        for key in ["site_url", "settings", "live", "earnings", "problem", "server_seen_at", "version", "updated_to"] {
            older.remove(key);
        }
        let read: Status = serde_json::from_value(older.into()).unwrap();
        assert_eq!(read.settings, HostSettings::default());
        assert!(read.live.is_none() && read.earnings.is_none() && read.problem.is_none());
        assert_eq!(read.version, "");
    }

    #[test]
    fn live_earnings_and_problems_travel_on_the_status() {
        let at = OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap();
        let rich = Status {
            live: Some(Live { gpu_temp_c: Some(71), gpu_util_pct: Some(98), job_started_at: Some(at) }),
            earnings: Some(Earnings {
                today_paise: 1240,
                week_paise: Some(9_900),
                month_paise: None,
                recent: vec![RecentJob { ended_at: at, seconds: 1_380, earned_paise: 420 }],
                next_payout_on: Some("2026-11-23".to_owned()),
                last_payout: Some(PayoutNote { sent_at: at, amount_paise: 12_500 }),
                updated_at: at,
            }),
            problem: Some(Problem::DiskNearlyFull),
            server_seen_at: Some(at),
            updated_to: Some("0.2.0".to_owned()),
            ..status()
        };
        let line = encode_message(&Message::Status(rich.clone())).unwrap();
        assert!(line.contains("\"problem\":\"disk_nearly_full\""), "{line}");
        assert_eq!(decode_message(line.trim_end()).unwrap(), Message::Status(rich));
    }

    #[test]
    fn other_versions_and_other_shapes_are_refused() {
        assert!(matches!(decode_request(r#"{"v":2,"id":1,"type":"status"}"#), Err(ChannelError::Version(2))));
        assert!(matches!(decode_message(r#"{"type":"done","id":1}"#), Err(ChannelError::Malformed(_))));
        assert!(matches!(decode_request(r#"{"v":1,"id":1,"type":"format_disk"}"#), Err(ChannelError::Malformed(_))));
        assert!(matches!(decode_request("not json"), Err(ChannelError::Malformed(_))));
    }

    #[tokio::test]
    async fn a_client_talks_over_any_pipe_and_long_lines_are_refused() {
        let (client_side, agent_side) = tokio::io::duplex(1 << 16);
        let (client_read, client_write) = tokio::io::split(client_side);
        let (agent_read, mut agent_write) = tokio::io::split(agent_side);
        let mut client = Client::new(BufReader::new(client_read), client_write);
        let id = client.send(&Request::SetHosting { on: false }).await.unwrap();
        let mut agent_read = BufReader::new(agent_read);
        assert_eq!(
            decode_request(&read_line(&mut agent_read).await.unwrap()).unwrap(),
            (id, Request::SetHosting { on: false })
        );
        agent_write.write_all(encode_message(&Message::Done { id }).unwrap().as_bytes()).await.unwrap();
        assert_eq!(client.next().await.unwrap(), Message::Done { id });

        // Written while the client reads, as a real pipe would carry it: the line is bigger than the pipe's buffer.
        let writer = tokio::spawn(async move {
            agent_write.write_all(&vec![b'x'; MAX_LINE + 10]).await.unwrap();
            agent_write.write_all(b"\n").await.unwrap();
            agent_write
        });
        assert!(matches!(client.next().await, Err(ChannelError::Malformed(_))));
        drop(writer.await.unwrap());
        drop(agent_read);
        while let Ok(_) | Err(ChannelError::Malformed(_)) = client.next().await {}
        assert!(matches!(client.next().await, Err(ChannelError::Closed)));
    }

    #[test]
    fn only_the_offered_waits_are_valid() {
        assert!(AutoPause::default().is_valid());
        assert!(!AutoPause { start_again_after_minutes: 15, ..AutoPause::default() }.is_valid());
        assert_eq!(pipe_name("S-1-5-21-1"), r"\\.\pipe\idleo-S-1-5-21-1");
    }
}
