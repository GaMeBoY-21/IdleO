//! Everything the runner does to the machine it runs on, behind two traits, so the lifecycle of an attempt (session.rs)
//! is tested without a container runtime: [`Machine`] is the distribution (the sandbox's rules, images, scratch disks,
//! starting containers), [`Container`] is one running job.
//!
//! The real implementation is `nerdctl::Nerdctl`; the tests' is a scripted machine that runs "containers" as tasks.

use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;

use jobcore::protocol::{Check, HelloAnswer};
use tokio::sync::mpsc;

use crate::container::Plan;

/// A job's output: chunks, and which stream each came from.
pub type Output = mpsc::Receiver<(Stream, Vec<u8>)>;

/// A boxed future.
pub type Fut<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// A job's scratch disk, mounted: its folders on the distribution's own disk.
#[derive(Debug, Clone)]
pub struct Scratch {
    /// What names it, `<session>-<attempt>`.
    pub key: String,
    /// The mounted disk's root.
    pub root: PathBuf,
    /// `<root>/work`, which the job sees as `/work`.
    pub work: PathBuf,
    /// `<root>/input`, where inputs are staged under `input/<slot>/`: mounted read-only in the job at `/work/input`.
    pub input: PathBuf,
    /// `<root>/resume`, the checkpoint to resume from, mounted read-only at `/work/resume`.
    pub resume: PathBuf,
    /// The device-mapper name of its encrypted volume (ADR 0038); `None` only for a development distribution whose kernel
    /// has no dm-crypt, where the disk is plain and the runner has said so.
    pub mapper: Option<String>,
}

impl Scratch {
    /// `<root>/logs`: where the container's own output file is kept, on this disk and out of the job's sight, so what a job
    /// prints is never on the distribution's disk in the clear.
    #[must_use]
    pub fn logs(&self) -> PathBuf {
        self.root.join("logs")
    }
}

/// Which of a job's output streams a chunk came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stream {
    /// Standard output.
    Stdout,
    /// Standard error.
    Stderr,
}

/// How a container ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Exit {
    /// The process's exit code; 128 plus the signal for one killed by a signal.
    pub code: i32,
}

/// What a session's network has carried and what the fair-use limits dropped, since its container started (ADR 0038).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Traffic {
    /// Bytes the job sent out.
    pub out_bytes: u64,
    /// Bytes it received.
    pub in_bytes: u64,
    /// What the fair-use rules dropped.
    pub drops: crate::fair::Drops,
}

/// A running job.
pub trait Container: Send + Sync {
    /// The processes in the container now. Their executables' SHA-256 are worked out only when `with_hashes` (the blocklist
    /// lists any), and only once for each executable.
    fn processes(&self, _with_hashes: bool) -> Fut<'_, Vec<crate::abuse::ProcessSeen>> {
        Box::pin(async { Vec::new() })
    }
    /// The SHA-256 of one process's executable, worked out when asked: a process whose name is listed carries it, so an
    /// admin who confirms the session can add the build to the list. `None` when it can't be read or is too large.
    fn hash_of(&self, _pid: u32) -> Fut<'_, Option<String>> {
        Box::pin(async { None })
    }
    /// The mining-pool lookups the session's DNS forwarder has refused, one message each. Only the first call has them.
    fn take_lookups(&self) -> Option<mpsc::UnboundedReceiver<crate::dns::PoolLookup>> {
        None
    }
    /// What its network has carried and what the fair-use limits have dropped, in all.
    fn traffic(&self) -> Fut<'_, Traffic> {
        Box::pin(async { Traffic::default() })
    }
    /// Its output, a chunk at a time, ending when the job's output does. Only the first call has it.
    fn take_output(&self) -> Option<Output>;
    /// Resolves when the job's process has exited.
    fn wait(&self) -> Fut<'_, Exit>;
    /// Sends the job's main process a signal: `TERM` or `KILL`.
    fn signal(&self, signal: &'static str) -> Fut<'_, ()>;
    /// Whether the kernel has killed something in it for using too much memory.
    fn oom_killed(&self) -> Fut<'_, bool>;
    /// Kills everything in it and removes it, and frees its network. Safe to call again.
    fn remove(&self) -> Fut<'_, ()>;
}

/// Why a container couldn't be started. A sandbox that isn't ready (its limits can't be put in place) is told apart from a
/// runner that failed, because the first is the host's and the renter is not charged for it (`sandbox_not_ready`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StartError {
    /// The limits a session must run under (the network rules, the fair-use limits) aren't in place.
    SandboxNotReady(String),
    /// Anything else.
    Failed(String),
}

impl From<String> for StartError {
    fn from(why: String) -> Self {
        Self::Failed(why)
    }
}

impl From<&str> for StartError {
    fn from(why: &str) -> Self {
        Self::Failed(why.to_owned())
    }
}

impl std::fmt::Display for StartError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::SandboxNotReady(why) | Self::Failed(why) => f.write_str(why),
        }
    }
}

/// The distribution.
pub trait Machine: Send + Sync + 'static {
    /// What the self-test and the agent ask first.
    fn hello(&self) -> Fut<'_, HelloAnswer>;
    /// Whether the sandbox's rules are loaded, as this runner's version of them, with the addresses that depend on the
    /// network refreshed. A job never starts without it.
    fn check_sandbox(&self) -> Fut<'_, Result<(), String>>;
    /// Makes sure the image is in containerd's store, pulling it if it isn't, reporting `(done, total)` bytes.
    fn ensure_image<'a>(&'a self, image: &'a str, progress: Progress) -> Fut<'a, Result<(), String>>;
    /// Makes and mounts a scratch disk of `scratch_mb`, owned by the job's user, with its folders in place.
    fn make_scratch<'a>(&'a self, key: &'a str, scratch_mb: u32) -> Fut<'a, Result<Scratch, String>>;
    /// Bytes free on the scratch disk.
    fn scratch_free(&self, scratch: &Scratch) -> u64;
    /// Unmounts and deletes a scratch disk. Safe to call again.
    fn remove_scratch<'a>(&'a self, scratch: &'a Scratch) -> Fut<'a, ()>;
    /// Gives the plan its session network and starts the container.
    fn start_container(&self, plan: Plan) -> Fut<'_, Result<Arc<dyn Container>, StartError>>;
    /// The checks of a self-check.
    fn self_check<'a>(&'a self, cuda_image: Option<&'a str>, quick: bool) -> Fut<'a, Vec<Check>>;
    /// What finished sessions have left behind in this distribution, one phrase for each kind of thing ("1 process", "2 mounts"):
    /// containers, processes, mounts, loop devices, scratch files and what a session attached to its network (interfaces, address
    /// leases) that no session running now holds. Empty
    /// when nothing is. A runner that found its own leftovers is a runner with a leak.
    fn leftovers(&self) -> Fut<'_, Vec<String>> {
        Box::pin(async { Vec::new() })
    }
    /// Whether this is a development distribution, where the runner tells each session's log what was left from the ones before.
    fn development(&self) -> bool {
        false
    }
    /// The list of software and pools to look for in a session (ADR 0038): the newest the runner holds, and, in a development
    /// distribution, the test entries its marker file names.
    fn abuse_blocklist(&self) -> Arc<crate::abuse::Blocklist>;
    /// Takes a newer list (the signed file's exact text and its signature): refused if the signature doesn't verify against the
    /// release key, it can't be matched safely or isn't newer than the one in use, kept as the last good copy otherwise.
    /// Returns the version now in use.
    ///
    /// # Errors
    /// Why it was refused, in a line.
    fn set_blocklist(&self, _file: &str, _signature: &[u8]) -> Result<String, String> {
        Err("this runner can't take a blocklist".to_owned())
    }
    /// Sessions' leftovers from a runner that died: containers and scratch disks nobody holds. Run once at start-up.
    fn clean_up_leftovers(&self) -> Fut<'_, ()>;
}

/// Reports a pull's progress as `(done_bytes, total_bytes)`.
pub type Progress = Arc<dyn Fn(u64, u64) + Send + Sync>;
