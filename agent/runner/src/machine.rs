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

/// A running job.
pub trait Container: Send + Sync {
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
    fn start_container(&self, plan: Plan) -> Fut<'_, Result<Arc<dyn Container>, String>>;
    /// The checks of a self-check.
    fn self_check<'a>(&'a self, cuda_image: Option<&'a str>, quick: bool) -> Fut<'a, Vec<Check>>;
    /// Sessions' leftovers from a runner that died: containers and scratch disks nobody holds. Run once at start-up.
    fn clean_up_leftovers(&self) -> Fut<'_, ()>;
}

/// Reports a pull's progress as `(done_bytes, total_bytes)`.
pub type Progress = Arc<dyn Fn(u64, u64) + Send + Sync>;
