//! The conversation between the agent and `idleo-runner` (ADR 0035): JSON lines, one message a line, over a process's
//! stdin and stdout (`idleo-runner attach`, which bridges them to the runner's Unix socket). One module, with serde
//! types and a version field, shared by both ends, so neither can drift from the other.
//!
//! Every line is a [`Frame`] carrying [`VERSION`]. A line with another version is refused ([`Frame::decode`]) and the
//! runner closes the connection, so a newer agent never talks to an older runner by guesswork.
//!
//! The agent sends [`Request`]s, each with an `id`; the runner answers each with a [`Reply`] of the same `id`, and,
//! unasked, sends [`Event`]s about its sessions. Storage grants travel in a [`Start`], a [`Refresh`] or a
//! [`Pull`]-less request only, and are printed without their secrets ([`GrantWire`]'s `Debug`).

use std::collections::BTreeMap;
use std::fmt;

use agent_proto::v1::{StorageGrant, StoragePermission};
use serde::{Deserialize, Serialize};

/// The protocol's version. Bumped only by a change an older end can't read; fields are added with defaults.
pub const VERSION: u32 = 1;

/// The most a line may hold, in bytes: a start with a few hundred inputs fits easily, nothing larger is real.
pub const MAX_LINE_BYTES: usize = 4 * 1024 * 1024;

/// The codes a session fails or ends with, as the runner says them. Stable: the web app's words are keyed by them.
pub mod code {
    /// The image couldn't be pulled or isn't pinned by digest.
    pub const IMAGE_PULL_FAILED: &str = "image_pull_failed";
    /// A renter's own image didn't finish downloading within its time limit (ADR 0042): before minute 0, so uncharged.
    pub const IMAGE_PULL_TIMED_OUT: &str = "image_pull_timed_out";
    /// An input or the resume checkpoint couldn't be staged, or isn't valid.
    pub const INVALID_INPUT: &str = "invalid_input";
    /// The sandbox's network rules aren't loaded, so no job runs.
    pub const SANDBOX_NOT_READY: &str = "sandbox_not_ready";
    /// The container was killed for using more memory than its limit.
    pub const OUT_OF_MEMORY: &str = "out_of_memory";
    /// The job filled its scratch disk.
    pub const SCRATCH_FULL: &str = "scratch_full";
    /// The job ran past `max_runtime`.
    pub const TIMED_OUT: &str = "timed_out";
    /// The job exited with a code other than 0.
    pub const JOB_FAILED: &str = "job_failed";
    /// The lease lapsed with nobody to renew it: the work was stopped.
    pub const LEASE_EXPIRED: &str = "lease_expired";
    /// The outputs couldn't be uploaded.
    pub const OUTPUTS_UPLOAD_FAILED: &str = "outputs_upload_failed";
    /// The runner failed in a way that isn't the job's.
    pub const RUNNER_FAILED: &str = "runner_failed";
    /// A request the runner can't act on.
    pub const BAD_REQUEST: &str = "bad_request";
    /// The line's version isn't the runner's.
    pub const UNSUPPORTED_VERSION: &str = "unsupported_version";
    /// The runner stopped the job because it ran software no renter may run on a host's GPU (ADR 0038): a listed build, or a
    /// second lookup of a mining pool.
    pub const ABUSE_STOPPED: &str = "abuse_stopped";
}

/// One line on the wire.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Frame {
    /// [`VERSION`].
    pub v: u32,
    /// What the line says.
    #[serde(flatten)]
    pub body: Body,
}

/// What a line says: who it's from decides which it is.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Body {
    /// Agent to runner.
    Request {
        /// Echoed in the reply.
        id: u64,
        /// What to do.
        req: Request,
    },
    /// Runner to agent, once for each request.
    Reply {
        /// The request's id.
        id: u64,
        /// What came of it.
        #[serde(flatten)]
        result: Outcome,
    },
    /// Runner to agent, unasked.
    Event {
        /// The session it's about.
        session: String,
        /// Which attempt.
        attempt: i32,
        /// What happened.
        event: Event,
    },
}

/// A reply: it worked, or here's why not.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    /// It worked.
    Ok(Answer),
    /// It didn't.
    Err(Failure),
}

/// Why a request failed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Failure {
    /// A stable code, one of [`code`].
    pub code: String,
    /// One short line, never renter data or a secret.
    pub message: String,
}

impl Failure {
    /// A failure with `code`.
    pub fn new(code: &str, message: impl Into<String>) -> Self {
        Self { code: code.to_owned(), message: message.into() }
    }
}

impl fmt::Display for Failure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}: {}", self.code, self.message)
    }
}

impl std::error::Error for Failure {}

/// What the agent asks of the runner.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Request {
    /// Who are you, and what can you do?
    Hello,
    /// Every session you hold, and where each stands.
    List,
    /// Run an attempt.
    Start(Box<Start>),
    /// Stop an attempt: SIGTERM, wait up to `grace_secs` for it to save and exit, then SIGKILL.
    Stop {
        /// The session.
        session: String,
        /// Its attempt.
        attempt: i32,
        /// How long the job has to save progress; 0 kills it at once.
        grace_secs: u32,
    },
    /// The lease was renewed: the attempt may run `ttl_secs` more from now. With nobody to renew it, the runner stops it.
    Lease {
        /// The session.
        session: String,
        /// Its attempt.
        attempt: i32,
        /// Seconds from now.
        ttl_secs: u32,
    },
    /// Fresh storage grants for a running attempt, in place of ones about to expire.
    Refresh(Box<Refresh>),
    /// Download an image into containerd's store, reporting progress as [`Event::Pull`]. Answered when it's done.
    Pull {
        /// The image, pinned by digest.
        image: String,
    },
    /// Stop every download under way: a session is starting, and nothing is downloaded while one runs. What finished stays;
    /// the next pull carries on from there.
    CancelPulls,
    /// Delete everything left of an ended attempt (its record, scratch disk and container), once the agent has what it
    /// reported.
    Forget {
        /// The session.
        session: String,
        /// Its attempt.
        attempt: i32,
    },
    /// The checks the self-test runs: the sandbox's rules, a scratch disk, a throwaway container on a session network and,
    /// with an image that's on the laptop, a real CUDA calculation in it.
    SelfCheck {
        /// The job image to run the CUDA calculation in, when it has been downloaded.
        #[serde(default)]
        cuda_image: Option<String>,
        /// Only the quick checks (the rules and a scratch disk), as an update's trial does.
        #[serde(default)]
        quick: bool,
    },
    /// A newer abuse blocklist (ADR 0038): the signed file's exact bytes as text and its signature. The agent has verified
    /// it against the release key, and the runner does so again with the key in its own image before anything else: a list
    /// without a signature that verifies is refused. It also refuses what it can't match safely or what isn't newer than the
    /// one it holds, and keeps the last good copy.
    Blocklist {
        /// The list's JSON.
        file: String,
        /// The raw Ed25519 signature over `file`'s bytes (64 bytes). Absent from an older agent, which the runner refuses.
        #[serde(default)]
        signature: Vec<u8>,
    },
}

/// A grant of storage access, as the runner holds it: in memory only, never in the container, a file or a log.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GrantWire {
    /// Storage's address.
    pub endpoint: String,
    /// The bucket.
    pub bucket: String,
    /// The temporary access key.
    pub access_key_id: String,
    /// Its secret.
    pub secret_access_key: String,
    /// Its session token.
    pub session_token: String,
    /// The prefixes it covers, each ending in `/`.
    pub prefixes: Vec<String>,
    /// Whether it may write.
    pub writable: bool,
    /// Seconds it stays valid, counted from receipt.
    pub expires_in_secs: u64,
}

impl fmt::Debug for GrantWire {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("GrantWire")
            .field("bucket", &self.bucket)
            .field("prefixes", &self.prefixes)
            .field("writable", &self.writable)
            .finish_non_exhaustive()
    }
}

impl GrantWire {
    /// The agent's grant message, for the wire.
    #[must_use]
    pub fn from_message(grant: &StorageGrant) -> Self {
        Self {
            endpoint: grant.endpoint.clone(),
            bucket: grant.bucket.clone(),
            access_key_id: grant.access_key_id.clone(),
            secret_access_key: grant.secret_access_key.clone(),
            session_token: grant.session_token.clone(),
            prefixes: grant.prefixes.clone(),
            writable: grant.permission() == StoragePermission::ReadWrite,
            expires_in_secs: grant.expires_in.map_or(0, |value| u64::try_from(value.seconds).unwrap_or(0)),
        }
    }

    /// The grant message storage's code checks and signs with.
    #[must_use]
    pub fn to_message(&self) -> StorageGrant {
        StorageGrant {
            endpoint: self.endpoint.clone(),
            bucket: self.bucket.clone(),
            access_key_id: self.access_key_id.clone(),
            secret_access_key: self.secret_access_key.clone(),
            session_token: self.session_token.clone(),
            prefixes: self.prefixes.clone(),
            permission: if self.writable { StoragePermission::ReadWrite } else { StoragePermission::ReadOnly }.into(),
            expires_in: Some(prost_types::Duration {
                seconds: i64::try_from(self.expires_in_secs).unwrap_or(i64::MAX),
                nanos: 0,
            }),
        }
    }
}

/// The grants an attempt runs with.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Grants {
    /// Read and write on the attempt's own folder.
    pub attempt: GrantWire,
    /// Read-only on the inputs and the resume checkpoint, when there are either.
    #[serde(default)]
    pub read: Option<GrantWire>,
}

/// One uploaded input file, as the agent was told to stage it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct InputFile {
    /// The folder of the upload in storage, ending in `/`.
    pub prefix: String,
    /// The file's path inside it, and inside its slot's folder.
    pub name: String,
    /// Its size, which the staged copy must match.
    pub size_bytes: i64,
    /// The template's name for the input.
    pub slot: String,
}

/// The checkpoint to resume from.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResumeFrom {
    /// The folder holding the numbered checkpoints, ending in `/`.
    pub prefix: String,
    /// The checkpoint's number.
    pub seq: i32,
}

/// What to run, and within what.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Spec {
    /// The image, pinned by digest: `registry/name@sha256:...`.
    pub image: String,
    /// The command's arguments, after the image's entrypoint.
    #[serde(default)]
    pub args: Vec<String>,
    /// `SESSION_PARAM_*` and `SESSION_INPUT_*` only; the runner adds its own.
    #[serde(default)]
    pub env: BTreeMap<String, String>,
    /// Memory limit in MiB, with no swap beyond it.
    pub memory_mb: u32,
    /// CPUs in thousandths.
    pub cpu_millicores: u32,
    /// The most processes the container may have.
    pub pids_limit: u32,
    /// The scratch disk's size in MiB.
    pub scratch_mb: u32,
    /// How long the job may run, from the start.
    pub max_runtime_secs: u64,
    /// How often the job should save a checkpoint, in seconds, told to it as `SESSION_CHECKPOINT_INTERVAL`; 0 for not told.
    #[serde(default)]
    pub checkpoint_interval_secs: u64,
    /// Whether the job has a GPU (`--gpus all`). False only in a development build, which the runner checks.
    pub gpu: bool,
    /// In place of the image's entrypoint when not empty: `["/bin/sh", "-c"]` for a renter's own image run with the command
    /// they typed (ADR 0042). Empty keeps the image's own.
    #[serde(default)]
    pub entrypoint: Vec<String>,
    /// The job's working directory; empty is `/work`. A renter's own image runs in its own.
    #[serde(default)]
    pub working_dir: String,
    /// A renter's own image (ADR 0042): pulled within `pull_timeout_secs` with the start's registry login, and deleted from
    /// the machine when the attempt ends, since it may hold the renter's code.
    #[serde(default)]
    pub own_image: bool,
    /// How long pulling a renter's own image may take; 0 for the runner's own limit.
    #[serde(default)]
    pub pull_timeout_secs: u64,
    /// The image's compressed size, what its download counts up to; 0 when not known.
    #[serde(default)]
    pub image_size_bytes: u64,
}

/// A private registry's read-only login for one pull (ADR 0042): in the runner's memory only, written for the pull into a
/// file only root can read on a memory-backed folder and deleted after it, never in an environment or a log.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RegistryLogin {
    /// The user name.
    pub username: String,
    /// The read-only token.
    pub token: String,
}

impl fmt::Debug for RegistryLogin {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RegistryLogin").finish_non_exhaustive()
    }
}

/// An attempt to run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Start {
    /// The session.
    pub session: String,
    /// Its attempt.
    pub attempt: i32,
    /// What to run.
    pub spec: Spec,
    /// Where it writes: `sessions/{id}/attempt-{n}/`.
    pub attempt_prefix: String,
    /// Its storage access.
    pub grants: Grants,
    /// The checkpoint to resume from, if any.
    #[serde(default)]
    pub resume_from: Option<ResumeFrom>,
    /// Every uploaded input file.
    #[serde(default)]
    pub inputs: Vec<InputFile>,
    /// The first lease: the attempt may run this long from now, until the agent renews it.
    pub lease_ttl_secs: u32,
    /// What the agent needs back when it reattaches after a restart.
    pub agent: AgentNotes,
    /// The most the session may upload, in kbit/s: half of what the laptop measured its own upload to be, never under the
    /// agent's floor (ADR 0038). Zero is "not said": the runner uses its default.
    #[serde(default)]
    pub upload_cap_kbps: u32,
    /// The login a renter's own private image is pulled with (ADR 0042); none for a public one and for our images.
    #[serde(default)]
    pub registry: Option<RegistryLogin>,
}

/// What the agent keeps in the runner, to pick an attempt up again after it restarts.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AgentNotes {
    /// The attempt's first minute index (ADR 0017).
    pub minute_index_base: i32,
    /// The GPUs it runs on.
    #[serde(default)]
    pub gpus: Vec<String>,
}

/// Fresh grants for a running attempt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Refresh {
    /// The session.
    pub session: String,
    /// Its attempt.
    pub attempt: i32,
    /// The new grants, for the same prefixes with the same permissions.
    pub grants: Grants,
}

/// What a successful request answers.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Answer {
    /// Nothing more to say.
    Done,
    /// Answer to [`Request::Hello`].
    Hello(HelloAnswer),
    /// Answer to [`Request::List`].
    List {
        /// Every session the runner holds.
        sessions: Vec<SessionInfo>,
    },
    /// Answer to [`Request::SelfCheck`].
    SelfCheck {
        /// Each check and how it went.
        checks: Vec<Check>,
    },
    /// Answer to [`Request::Blocklist`]: the version now in use.
    Blocklist {
        /// The list's version.
        version: String,
    },
}

/// Who the runner is.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HelloAnswer {
    /// The runner's own version.
    pub runner_version: String,
    /// The distribution's version, from `/etc/idleo-distro-version`.
    pub distro_version: String,
    /// Whether a GPU is visible to containers.
    pub gpu: bool,
    /// Whether the sandbox's network rules are loaded.
    pub rules_loaded: bool,
    /// Free space where scratch disks are made, in bytes.
    pub scratch_free_bytes: u64,
    /// Whether this is a development distribution: a plain-HTTP registry and CPU-only jobs are allowed.
    pub development: bool,
    /// The version of the abuse blocklist in use, so the agent hands over a newer one only (empty from an older runner).
    #[serde(default)]
    pub blocklist_version: String,
}

/// One check of a self-check.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Check {
    /// Its stable name.
    pub name: String,
    /// How it went.
    pub status: CheckStatus,
    /// One line about it.
    pub detail: String,
}

/// How a check went.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CheckStatus {
    /// It held.
    Passed,
    /// It didn't.
    Failed,
    /// It couldn't be tried here, for a reason that isn't a failure (offline, image not downloaded yet).
    Skipped,
}

/// Where an attempt is.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    /// Staging, making the scratch disk, starting the container.
    Preparing,
    /// The job runs.
    Running,
    /// The job exited 0 and its outputs are going up.
    Uploading,
    /// It's over: see [`SessionInfo::ended`].
    Ended,
}

/// How an attempt ended.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "result", rename_all = "snake_case")]
pub enum Ended {
    /// The job exited 0 and its outputs, with their marker, are uploaded.
    Succeeded,
    /// Asked to stop, it saved what it could and stopped.
    Stopped,
    /// The job or its setup failed.
    Failed {
        /// One of [`code`].
        code: String,
        /// One short line, never renter data.
        message: String,
        /// The job's exit code, when it exited.
        #[serde(default)]
        exit_code: Option<i32>,
    },
}

/// A session as the runner holds it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionInfo {
    /// The session.
    pub session: String,
    /// Its attempt.
    pub attempt: i32,
    /// Where it is.
    pub phase: Phase,
    /// How it ended, once it has.
    #[serde(default)]
    pub ended: Option<Ended>,
    /// The newest checkpoint whose marker is uploaded; 0 for none.
    pub latest_checkpoint_seq: i32,
    /// The newest log chunk uploaded; 0 for none.
    pub latest_log_seq: i32,
    /// Seconds since it first ran, on the runner's clock; 0 before.
    pub running_for_secs: u64,
    /// Seconds its lease has left.
    pub lease_remaining_secs: u64,
    /// Seconds before `max_runtime` stops it.
    pub runtime_remaining_secs: u64,
    /// What the agent left with [`Start::agent`].
    pub agent: AgentNotes,
}

/// What happened to an attempt.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    /// The attempt moved to a new phase. [`Phase::Ended`] comes with [`Event::Exited`] instead.
    State {
        /// The phase.
        phase: Phase,
    },
    /// Log chunks are uploaded up to `latest_seq`; `flushed` once nothing more will come.
    Logs {
        /// The newest chunk uploaded.
        latest_seq: i32,
        /// The log is finished: the end of the attempt follows.
        flushed: bool,
    },
    /// Checkpoint `seq` is complete: its marker is uploaded.
    Checkpoint {
        /// Its number.
        seq: i32,
    },
    /// The attempt is over, its logs flushed first.
    Exited {
        /// How it ended.
        ended: Ended,
    },
    /// What the runner saw in the session that an admin should look at, or that it stopped the session for (ADR 0038).
    Abuse {
        /// Which signal.
        kind: AbuseKind,
        /// The process's name, or the pool's domain from the blocklist; never a path, an address or anything the job typed.
        #[serde(default)]
        detail: String,
        /// The executable's SHA-256, for [`AbuseKind::ProcessHash`].
        #[serde(default)]
        sha256: String,
        /// For [`AbuseKind::ConnectionDrops`]: how many new connections the fair-use limit has dropped in all.
        #[serde(default)]
        count: u64,
        /// The runner stopped the session because of this; else it only tells.
        stopped: bool,
    },
    /// What the session's job did in the last minute, for the review of abuse (ADR 0038): never billed on, never shown to a
    /// host, and kept 90 days by the control plane.
    Behavior {
        /// Bytes the job sent out in the minute.
        net_out_bytes: u64,
        /// Bytes it received.
        net_in_bytes: u64,
        /// How many lines it printed.
        printed_lines: u64,
        /// New connections the fair-use limit dropped in the minute.
        conn_drops: u64,
    },
    /// Progress of a pull: of a [`Request::Pull`], with no session (`session` empty), or of a renter's own image at its
    /// attempt's start, with the session.
    Pull {
        /// The image.
        image: String,
        /// Bytes downloaded so far.
        done_bytes: u64,
        /// Bytes in all, when known.
        total_bytes: u64,
    },
}

/// What an [`Event::Abuse`] is about.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AbuseKind {
    /// A process with a listed name (flagged).
    ProcessName,
    /// A process whose executable is a listed build (stops the session).
    ProcessHash,
    /// A lookup of a mining pool's domain (flagged; a second stops the session).
    PoolLookup,
    /// The fair-use limit has dropped a job's new connections for 5 minutes running (flagged).
    ConnectionDrops,
}

/// Why a line couldn't be read.
#[derive(Debug, PartialEq, Eq)]
pub enum DecodeError {
    /// Not JSON of this protocol.
    Malformed(String),
    /// A version other than [`VERSION`].
    Version(u32),
}

impl fmt::Display for DecodeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Malformed(why) => write!(f, "the line isn't a protocol message: {why}"),
            Self::Version(v) => write!(f, "protocol version {v} isn't {VERSION}"),
        }
    }
}

impl std::error::Error for DecodeError {}

#[derive(Deserialize)]
struct Versioned {
    v: u32,
}

impl Frame {
    /// A line of this version.
    #[must_use]
    pub fn new(body: Body) -> Self {
        Self { v: VERSION, body }
    }

    /// A request, as a line (with its newline).
    ///
    /// # Panics
    /// Never: the types always serialise.
    #[must_use]
    pub fn request(id: u64, req: Request) -> String {
        Self::new(Body::Request { id, req }).encode()
    }

    /// A reply, as a line.
    #[must_use]
    pub fn reply(id: u64, result: Outcome) -> String {
        Self::new(Body::Reply { id, result }).encode()
    }

    /// An event, as a line.
    #[must_use]
    pub fn event(session: &str, attempt: i32, event: Event) -> String {
        Self::new(Body::Event { session: session.to_owned(), attempt, event }).encode()
    }

    /// The line, with its newline.
    ///
    /// # Panics
    /// Never: the types always serialise.
    #[must_use]
    pub fn encode(&self) -> String {
        let mut line = serde_json::to_string(self).expect("a frame serialises");
        line.push('\n');
        line
    }

    /// Reads a line, refusing any version but ours before looking at the rest.
    ///
    /// # Errors
    /// [`DecodeError`] if it isn't JSON, isn't this protocol or has another version.
    pub fn decode(line: &str) -> Result<Self, DecodeError> {
        if line.len() > MAX_LINE_BYTES {
            return Err(DecodeError::Malformed("the line is too long".to_owned()));
        }
        let versioned: Versioned = serde_json::from_str(line).map_err(|e| DecodeError::Malformed(e.to_string()))?;
        if versioned.v != VERSION {
            return Err(DecodeError::Version(versioned.v));
        }
        serde_json::from_str(line).map_err(|e| DecodeError::Malformed(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grant() -> GrantWire {
        GrantWire {
            endpoint: "https://storage.example".to_owned(),
            bucket: "idleo".to_owned(),
            access_key_id: "AKIA-never-logged".to_owned(),
            secret_access_key: "secret-never-logged".to_owned(),
            session_token: "token-never-logged".to_owned(),
            prefixes: vec!["sessions/s/attempt-1/".to_owned()],
            writable: true,
            expires_in_secs: 3600,
        }
    }

    fn start() -> Start {
        Start {
            session: "0190f3a2-0000-7000-8000-000000000000".to_owned(),
            attempt: 2,
            spec: Spec {
                image: "registry.example/job@sha256:abc".to_owned(),
                args: vec!["python".to_owned(), "main.py".to_owned()],
                env: BTreeMap::from([("SESSION_PARAM_EPOCHS".to_owned(), "3".to_owned())]),
                memory_mb: 8192,
                cpu_millicores: 4000,
                pids_limit: 512,
                scratch_mb: 4096,
                max_runtime_secs: 600,
                checkpoint_interval_secs: 300,
                gpu: true,
                entrypoint: vec!["/bin/sh".to_owned(), "-c".to_owned()],
                working_dir: "/app".to_owned(),
                own_image: true,
                pull_timeout_secs: 1200,
                image_size_bytes: 8_400_000_000,
            },
            attempt_prefix: "sessions/s/attempt-2/".to_owned(),
            grants: Grants { attempt: grant(), read: None },
            resume_from: Some(ResumeFrom { prefix: "sessions/s/attempt-1/checkpoints/".to_owned(), seq: 4 }),
            inputs: vec![InputFile {
                prefix: "uploads/u/files/".to_owned(),
                name: "train.jsonl".to_owned(),
                size_bytes: 8,
                slot: "data".to_owned(),
            }],
            lease_ttl_secs: 120,
            agent: AgentNotes { minute_index_base: 3, gpus: vec!["GPU-1".to_owned()] },
            upload_cap_kbps: 12_500,
            registry: Some(RegistryLogin { username: "renter".to_owned(), token: "ghp_token-never-logged".to_owned() }),
        }
    }

    #[test]
    fn every_message_survives_the_wire() {
        let lines = [
            Frame::request(1, Request::Hello),
            Frame::request(2, Request::List),
            Frame::request(3, Request::Start(Box::new(start()))),
            Frame::request(4, Request::Stop { session: "s".to_owned(), attempt: 1, grace_secs: 120 }),
            Frame::request(5, Request::Lease { session: "s".to_owned(), attempt: 1, ttl_secs: 120 }),
            Frame::request(6, Request::Pull { image: "r/i@sha256:1".to_owned() }),
            Frame::request(9, Request::CancelPulls),
            Frame::request(7, Request::Forget { session: "s".to_owned(), attempt: 1 }),
            Frame::request(8, Request::SelfCheck { cuda_image: None, quick: false }),
            Frame::reply(1, Outcome::Ok(Answer::Done)),
            Frame::reply(2, Outcome::Err(Failure::new(code::SANDBOX_NOT_READY, "the rules aren't loaded"))),
            Frame::event("s", 1, Event::State { phase: Phase::Running }),
            Frame::event("s", 1, Event::Logs { latest_seq: 3, flushed: false }),
            Frame::event("s", 1, Event::Checkpoint { seq: 2 }),
            Frame::event(
                "s",
                1,
                Event::Abuse {
                    kind: AbuseKind::PoolLookup,
                    detail: "nanopool.org".to_owned(),
                    sha256: String::new(),
                    count: 0,
                    stopped: false,
                },
            ),
            Frame::event(
                "s",
                1,
                Event::Behavior { net_out_bytes: 1200, net_in_bytes: 3400, printed_lines: 7, conn_drops: 0 },
            ),
            Frame::event(
                "s",
                1,
                Event::Exited {
                    ended: Ended::Failed {
                        code: code::OUT_OF_MEMORY.to_owned(),
                        message: "ran out of memory".to_owned(),
                        exit_code: Some(137),
                    },
                },
            ),
        ];
        for line in lines {
            assert!(line.ends_with('\n') && !line[..line.len() - 1].contains('\n'), "one line: {line}");
            let frame = Frame::decode(&line).unwrap_or_else(|e| panic!("{e}: {line}"));
            assert_eq!(frame.encode(), line);
        }
    }

    #[test]
    fn a_start_keeps_every_field() {
        let line = Frame::request(9, Request::Start(Box::new(start())));
        let Body::Request { id: 9, req: Request::Start(decoded) } = Frame::decode(&line).unwrap().body else {
            panic!("not a start");
        };
        assert_eq!(*decoded, start());
    }

    #[test]
    fn another_version_is_refused_before_anything_else_is_read() {
        assert_eq!(Frame::decode(r#"{"v":2,"request":{"id":1,"req":{"type":"hello"}}}"#), Err(DecodeError::Version(2)));
        assert_eq!(Frame::decode(r#"{"v":2,"something":"we can't read"}"#), Err(DecodeError::Version(2)));
        assert!(matches!(Frame::decode("not json"), Err(DecodeError::Malformed(_))));
        assert!(matches!(Frame::decode(r#"{"request":{"id":1}}"#), Err(DecodeError::Malformed(_))), "no version");
        assert!(matches!(
            Frame::decode(r#"{"v":1,"request":{"id":1,"req":{"type":"mine_bitcoin"}}}"#),
            Err(DecodeError::Malformed(_))
        ));
    }

    #[test]
    fn a_line_over_the_limit_is_refused() {
        let line = format!(r#"{{"v":1,"pad":"{}"}}"#, "x".repeat(MAX_LINE_BYTES));
        assert!(matches!(Frame::decode(&line), Err(DecodeError::Malformed(_))));
    }

    #[test]
    fn grants_never_print_their_secrets() {
        let frame = Frame::decode(&Frame::request(3, Request::Start(Box::new(start())))).unwrap();
        let printed = format!("{:?} {frame:?}", grant());
        assert!(!printed.contains("never-logged"), "{printed}");
        let start = format!("{:?}", start());
        assert!(!start.contains("never-logged"), "{start}");
    }

    #[test]
    fn an_older_agents_start_reads_as_our_own_image_with_no_login() {
        let line = r#"{"v":1,"request":{"id":1,"req":{"type":"start","session":"s","attempt":1,"spec":{"image":"r/i@sha256:1","memory_mb":1,"cpu_millicores":1,"pids_limit":1,"scratch_mb":1,"max_runtime_secs":1,"gpu":true},"attempt_prefix":"p/","grants":{"attempt":{"endpoint":"e","bucket":"b","access_key_id":"a","secret_access_key":"s","session_token":"t","prefixes":[],"writable":true,"expires_in_secs":1}},"lease_ttl_secs":1,"agent":{"minute_index_base":0}}}}"#;
        let Body::Request { req: Request::Start(start), .. } = Frame::decode(line).unwrap().body else {
            panic!("not a start")
        };
        assert!(!start.spec.own_image && start.spec.entrypoint.is_empty() && start.spec.working_dir.is_empty());
        assert!(start.registry.is_none());
    }

    #[test]
    fn a_grant_goes_to_the_storage_code_and_back() {
        let message = grant().to_message();
        assert_eq!(message.permission(), StoragePermission::ReadWrite);
        assert_eq!(GrantWire::from_message(&message), grant());
        let read = GrantWire { writable: false, ..grant() };
        assert_eq!(read.to_message().permission(), StoragePermission::ReadOnly);
    }

    #[test]
    fn a_newer_agent_may_add_fields_an_older_runner_ignores() {
        let line = r#"{"v":1,"request":{"id":4,"req":{"type":"stop","session":"s","attempt":1,"grace_secs":5,"reason":"new"}}}"#;
        assert!(matches!(
            Frame::decode(line).unwrap().body,
            Body::Request { req: Request::Stop { grace_secs: 5, .. }, .. }
        ));
    }
}
