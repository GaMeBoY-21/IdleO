//! The development runner (`--dev-runner`, only with `--fake-gpu`): simulates a job so the whole
//! session flow runs on a machine without a GPU, against real object storage through the job's
//! grants.
//!
//! It runs for the duration in the spec's arguments (`--duration 20s`), writing a checkpoint every
//! `checkpoint_interval`: a numbered folder holding `state.json`, then its `_COMPLETE` manifest,
//! written last. On resume it reads the `resume_from` checkpoint through the read grant and carries
//! on from the time it records. At the end it writes `outputs/result.json`, naming the attempt and
//! the checkpoint it resumed from, then `outputs/_COMPLETE`, and succeeds with exit code 0. Asked to stop,
//! it writes one more checkpoint with the time so far and stops.
//!
//! It proves what the job saw (ADR 0019, ADR 0022): `result.json` has every staged input file's path, size and
//! SHA-256, by input, each link input, every `SESSION_PARAM_*` variable from the spec, and, when there's a
//! `greeting` parameter (the `echo-input` template), the greeting repeated `repeat` times. It writes real log lines:
//! the start, the attempt, the checkpoint it resumed from, the parameters, the inputs, each checkpoint, and the
//! finish.
//!
//! With `--simulate <kind>` in its arguments (fine-tune or run-code), it plays one of the services instead
//! ([`simulate`]): that service's log lines, streamed as it runs, and its
//! outputs in place of `result.json`, all generated.

mod simulate;

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::future::Future;
use std::path::Path;
use std::pin::Pin;
use std::time::Duration;

use agent_proto::v1::{CheckpointRef, SessionState};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;
use tokio::sync::mpsc;

use self::simulate::{Given, GivenFile, Kind, Simulation};
use jobcore::manifest::Manifest;

use super::{Job, Report, SessionRunner};
use crate::storage::{ObjectStore, StorageError};

/// How many times an output upload is tried before the job fails.
const UPLOAD_TRIES: u32 = 3;
/// The most the job sleeps between looking at the time: a simulation's lines are written as they fall due.
const TICK: Duration = Duration::from_secs(1);

/// The development runner.
#[derive(Debug, Clone, Copy, Default)]
pub struct DevRunner;

/// What a checkpoint records.
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
struct State {
    /// Simulated work done so far, across every attempt.
    elapsed_ms: u64,
}

/// The job's output.
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
struct Output {
    session_id: String,
    attempt: i32,
    resumed_from: Option<ResumedFrom>,
    ran_for_ms: u64,
    checkpoints_written: i32,
    /// Each uploaded input's files, as the job read them, by input name.
    inputs: BTreeMap<String, Vec<InputSeen>>,
    /// Each link input, `SESSION_INPUT_<NAME>_URL`, by input name: a real job fetches it inside its sandbox; this one
    /// only records it.
    links: BTreeMap<String, String>,
    /// Every `SESSION_PARAM_*` variable the job saw.
    params: BTreeMap<String, String>,
    /// For a `greeting` parameter: the greeting, `repeat` times.
    echo: Option<Vec<String>>,
}

/// One staged file, as the job read it: its path inside its input's folder.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct InputSeen {
    path: String,
    size_bytes: u64,
    sha256: String,
}

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
struct ResumedFrom {
    prefix: String,
    seq: i32,
}

/// Why the job stopped early.
enum Stop {
    /// The fence went down: stop at once, report nothing.
    Fenced,
    /// The control plane asked for a stop; the last checkpoint is written.
    Requested,
    Failed {
        code: &'static str,
        message: String,
    },
}

impl From<StorageError> for Stop {
    fn from(error: StorageError) -> Self {
        Self::Failed { code: "storage_failed", message: error.to_string() }
    }
}

impl SessionRunner for DevRunner {
    fn run(&self, job: Job, reports: mpsc::UnboundedSender<Report>) -> Pin<Box<dyn Future<Output = ()> + Send>> {
        Box::pin(async move {
            match run(&job, &reports).await {
                Ok(()) => {
                    let _ = reports.send(Report::Succeeded);
                }
                Err(Stop::Requested) => {
                    let _ = reports.send(Report::Stopped);
                }
                Err(Stop::Fenced) => {}
                Err(Stop::Failed { code, message }) => {
                    tracing::warn!(session_id = %job.session_id, attempt = job.attempt, code, %message, "the job failed");
                    let _ = reports.send(Report::Failed { code: code.to_owned(), message, exit_code: None });
                }
            }
        })
    }
}

async fn run(job: &Job, reports: &mpsc::UnboundedSender<Report>) -> Result<(), Stop> {
    let _ = reports.send(Report::State(SessionState::Preparing));
    job.logs.line(format!("starting session {} attempt {}", job.session_id, job.attempt));
    let invalid_args = |message| Stop::Failed { code: "invalid_args", message };
    let duration = duration_from_args(&job.spec.args).map_err(invalid_args)?;
    let kind = arg_value(&job.spec.args, "--simulate").map(str::parse::<Kind>).transpose().map_err(invalid_args)?;
    let seen = observe(job, kind.is_none()).await?;
    let simulation = kind.map(|kind| simulation(job, kind, &seen));
    for line in simulation.iter().flat_map(Simulation::opening) {
        job.logs.line(line);
    }
    let interval = job
        .spec
        .checkpoint_interval
        .and_then(|value| Duration::try_from(value).ok())
        .filter(|value| !value.is_zero())
        .ok_or(Stop::Failed { code: "invalid_spec", message: "no checkpoint interval".to_owned() })?;
    let mut elapsed = Duration::ZERO;
    if let Some(checkpoint) = &job.resume_from {
        let store = job.read_store.as_ref().ok_or(Stop::Failed {
            code: "resume_checkpoint_missing",
            message: "a checkpoint to resume from, but no read grant".to_owned(),
        })?;
        elapsed = resume(store.as_ref(), checkpoint).await?;
        tracing::info!(session_id = %job.session_id, attempt = job.attempt, seq = checkpoint.seq, ?elapsed, "resumed from a checkpoint");
        job.logs.line(format!(
            "resumed from checkpoint {} of {} at {} ms",
            checkpoint.seq,
            checkpoint.prefix,
            millis(elapsed)
        ));
    }
    fenced(job)?;
    let _ = reports.send(Report::State(SessionState::Running));
    tracing::info!(session_id = %job.session_id, attempt = job.attempt, ?duration, "the job is running");

    let mut seq = 0;
    let mut next_checkpoint = elapsed + interval;
    let mut stop_requested = job.stop_requested.clone();
    while elapsed < duration {
        let step = TICK.min(duration.saturating_sub(elapsed)).min(next_checkpoint.saturating_sub(elapsed));
        let started = tokio::time::Instant::now();
        let asked_to_stop = stop_requested.borrow_and_update().is_some()
            || tokio::select! {
                () = tokio::time::sleep(step) => false,
                // A closed channel disables this branch: with nobody left to ask, no stop is coming.
                Ok(_) = stop_requested.wait_for(Option::is_some) => true,
            };
        let before = elapsed;
        elapsed += started.elapsed().min(step);
        fenced(job)?;
        for line in simulation.iter().flat_map(|simulation| simulation.lines_between(before, elapsed, duration)) {
            job.logs.line(line);
        }
        if asked_to_stop {
            // The last checkpoint, if storage takes it; the stop happens either way.
            match write_checkpoint(job, seq + 1, elapsed).await {
                Ok(()) => {
                    let _ = reports.send(Report::Checkpoint(seq + 1));
                    job.logs.line(format!("checkpoint {} at {} ms, then stopped as asked", seq + 1, millis(elapsed)));
                    tracing::info!(session_id = %job.session_id, attempt = job.attempt, seq = seq + 1, "stopped after a last checkpoint");
                }
                Err(Stop::Failed { message, .. }) => {
                    tracing::warn!(session_id = %job.session_id, %message, "the last checkpoint failed to upload; stopping anyway");
                }
                Err(ended) => return Err(ended),
            }
            return Err(Stop::Requested);
        }
        if elapsed >= next_checkpoint && elapsed < duration {
            next_checkpoint += interval;
            seq += 1;
            match write_checkpoint(job, seq, elapsed).await {
                Ok(()) => {
                    let _ = reports.send(Report::Checkpoint(seq));
                    job.logs.line(format!("checkpoint {seq} at {} ms", millis(elapsed)));
                }
                Err(ended @ (Stop::Fenced | Stop::Requested)) => return Err(ended),
                // The job goes on; the next checkpoint may work, and resume uses the last complete one.
                Err(Stop::Failed { message, .. }) => {
                    tracing::warn!(session_id = %job.session_id, seq, %message, "a checkpoint failed to upload");
                    seq -= 1;
                }
            }
        }
    }

    let _ = reports.send(Report::State(SessionState::Uploading));
    let outputs = format!("{}outputs/", job.attempt_prefix);
    let files = match &simulation {
        Some(simulation) => simulation.outputs(),
        None => vec![("result.json".to_owned(), result(job, &seen, elapsed, seq))],
    };
    for (path, body) in &files {
        upload(job, &format!("{outputs}{path}"), body.clone()).await?;
    }
    upload(job, &format!("{outputs}_COMPLETE"), manifest(&files)).await?;
    tracing::info!(session_id = %job.session_id, attempt = job.attempt, "the job finished and its outputs are uploaded");
    job.logs.line(format!("finished after {} ms; outputs uploaded", millis(elapsed)));
    Ok(())
}

/// `result.json`: what the job saw, and how it ran.
fn result(job: &Job, seen: &Seen, elapsed: Duration, checkpoints_written: i32) -> Vec<u8> {
    let output = Output {
        session_id: job.session_id.to_string(),
        attempt: job.attempt,
        resumed_from: job.resume_from.as_ref().map(|from| ResumedFrom { prefix: from.prefix.clone(), seq: from.seq }),
        ran_for_ms: millis(elapsed),
        checkpoints_written,
        inputs: seen
            .inputs
            .iter()
            .map(|(name, files)| (name.clone(), files.iter().map(|(file, _)| file.clone()).collect()))
            .collect(),
        links: seen.links.clone(),
        params: seen.params.clone(),
        echo: seen.echo.clone(),
    };
    serde_json::to_vec_pretty(&output).expect("the output serialises")
}

fn simulation(job: &Job, kind: Kind, seen: &Seen) -> Simulation {
    let given = Given {
        params: seen.params.clone(),
        inputs: seen
            .inputs
            .iter()
            .map(|(name, files)| {
                let files = files
                    .iter()
                    .map(|(file, lines)| GivenFile {
                        path: file.path.clone(),
                        size_bytes: file.size_bytes,
                        lines: *lines,
                    })
                    .collect();
                (name.clone(), files)
            })
            .collect(),
        links: seen.links.clone(),
    };
    Simulation::new(kind, given, &job.session_id.to_string())
}

/// What the job was given; written to its log as it's read, unless a simulation writes its own.
struct Seen {
    params: BTreeMap<String, String>,
    /// Each uploaded input's files, with how many lines each has: a simulation's training examples.
    inputs: BTreeMap<String, Vec<(InputSeen, u64)>>,
    links: BTreeMap<String, String>,
    echo: Option<Vec<String>>,
}

async fn observe(job: &Job, log: bool) -> Result<Seen, Stop> {
    let line = |text: String| {
        if log {
            job.logs.line(text);
        }
    };
    let params: BTreeMap<String, String> = job
        .spec
        .env
        .iter()
        .filter(|(name, _)| name.starts_with("SESSION_PARAM_"))
        .map(|(name, value)| (name.clone(), value.clone()))
        .collect();
    for (name, value) in &params {
        line(format!("parameter {name}={value}"));
    }
    let mut inputs = BTreeMap::new();
    for (name, folder) in &job.inputs {
        let files = read_folder(folder).await?;
        for (file, _) in &files {
            line(format!("input {name}/{} {} bytes sha256 {}", file.path, file.size_bytes, file.sha256));
        }
        inputs.insert(name.clone(), files);
    }
    let links: BTreeMap<String, String> = job
        .spec
        .env
        .iter()
        .filter_map(|(variable, url)| {
            let name = variable.strip_prefix("SESSION_INPUT_")?.strip_suffix("_URL")?;
            Some((name.to_ascii_lowercase(), url.clone()))
        })
        .collect();
    for (name, url) in &links {
        line(format!("input {name} link {url} (a real job fetches it inside its sandbox)"));
    }
    let echo = params.get("SESSION_PARAM_GREETING").map(|greeting| {
        let repeat = params.get("SESSION_PARAM_REPEAT").and_then(|repeat| repeat.parse::<usize>().ok()).unwrap_or(1);
        vec![greeting.clone(); repeat]
    });
    for text in echo.iter().flatten() {
        line(text.clone());
    }
    Ok(Seen { params, inputs, links, echo })
}

/// Every file in a staged input's folder, by path inside it, sorted, each with its line count.
async fn read_folder(folder: &Path) -> Result<Vec<(InputSeen, u64)>, Stop> {
    let unreadable = |e: std::io::Error| Stop::Failed { code: "input_unreadable", message: e.to_string() };
    let mut files = Vec::new();
    let mut folders = vec![folder.to_path_buf()];
    while let Some(next) = folders.pop() {
        let mut entries = tokio::fs::read_dir(&next).await.map_err(unreadable)?;
        while let Some(entry) = entries.next_entry().await.map_err(unreadable)? {
            if entry.file_type().await.map_err(unreadable)?.is_dir() {
                folders.push(entry.path());
            } else {
                let (mut seen, lines) = read_input(&entry.path()).await?;
                let relative = entry.path().strip_prefix(folder).map(Path::to_path_buf).unwrap_or_default();
                seen.path =
                    relative.components().map(|part| part.as_os_str().to_string_lossy()).collect::<Vec<_>>().join("/");
                files.push((seen, lines));
            }
        }
    }
    files.sort_by(|a, b| a.0.path.cmp(&b.0.path));
    Ok(files)
}

/// A staged file's size and SHA-256, and how many lines it has, read in pieces.
async fn read_input(path: &Path) -> Result<(InputSeen, u64), Stop> {
    let unreadable = |e: std::io::Error| Stop::Failed { code: "input_unreadable", message: e.to_string() };
    let mut file = tokio::fs::File::open(path).await.map_err(unreadable)?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0_u8; 64 * 1024];
    let mut size = 0_u64;
    let mut lines = 0_u64;
    let mut ends_in_newline = true;
    loop {
        let read = file.read(&mut buffer).await.map_err(unreadable)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
        size += read as u64;
        lines += buffer[..read].iter().fold(0, |count, &byte| count + u64::from(byte == b'\n'));
        ends_in_newline = buffer[read - 1] == b'\n';
    }
    // A last line without a newline still counts.
    if !ends_in_newline {
        lines += 1;
    }
    let mut sha256 = String::with_capacity(64);
    for byte in hasher.finalize() {
        let _ = write!(sha256, "{byte:02x}");
    }
    let name = path.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    Ok((InputSeen { path: name, size_bytes: size, sha256 }, lines))
}

fn fenced(job: &Job) -> Result<(), Stop> {
    if job.fence.is_up() { Ok(()) } else { Err(Stop::Fenced) }
}

/// Reads a checkpoint, only if its completion marker exists and lists what's there.
async fn resume(store: &dyn ObjectStore, checkpoint: &CheckpointRef) -> Result<Duration, Stop> {
    let folder = format!("{}{:08}/", checkpoint.prefix, checkpoint.seq);
    let missing = |message: String| Stop::Failed { code: "resume_checkpoint_missing", message };
    let marker = store.get(&format!("{folder}_COMPLETE")).await.map_err(|e| missing(e.to_string()))?;
    let manifest = Manifest::from_slice(&marker).map_err(|e| missing(format!("the checkpoint's {e}")))?;
    let state = store.get(&format!("{folder}state.json")).await.map_err(|e| missing(e.to_string()))?;
    let listed = manifest.files.iter().find(|file| file.path == "state.json");
    if listed.map(|file| file.size) != Some(state.len() as u64) {
        return Err(missing("the checkpoint's state doesn't match its manifest".to_owned()));
    }
    let state: State =
        serde_json::from_slice(&state).map_err(|e| missing(format!("the checkpoint's state is invalid: {e}")))?;
    Ok(Duration::from_millis(state.elapsed_ms))
}

/// A checkpoint folder: the state first, then the marker that makes it count.
async fn write_checkpoint(job: &Job, seq: i32, elapsed: Duration) -> Result<(), Stop> {
    let folder = format!("{}checkpoints/{seq:08}/", job.attempt_prefix);
    let state = serde_json::to_vec(&State { elapsed_ms: millis(elapsed) }).expect("the state serialises");
    let manifest = manifest(&[("state.json".to_owned(), state.clone())]);
    fenced(job)?;
    job.attempt_store.put(&format!("{folder}state.json"), state).await?;
    fenced(job)?;
    job.attempt_store.put(&format!("{folder}_COMPLETE"), manifest).await?;
    Ok(())
}

async fn upload(job: &Job, key: &str, body: Vec<u8>) -> Result<(), Stop> {
    let mut tries = 0;
    loop {
        fenced(job)?;
        tries += 1;
        match job.attempt_store.put(key, body.clone()).await {
            Ok(()) => return Ok(()),
            Err(error @ (StorageError::OutOfScope(_) | StorageError::Expired)) => return Err(error.into()),
            Err(error) if tries >= UPLOAD_TRIES => {
                return Err(Stop::Failed { code: "outputs_upload_failed", message: error.to_string() });
            }
            Err(error) => {
                tracing::warn!(key, %error, "an output upload failed; trying again");
                tokio::time::sleep(Duration::from_secs(1) * tries).await;
            }
        }
    }
}

/// A folder's `_COMPLETE` marker, listing every file in it.
fn manifest(files: &[(String, Vec<u8>)]) -> Vec<u8> {
    Manifest::of_bodies(files).to_bytes()
}

fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

/// The value of `--name value` or `--name=value` in the spec's arguments; the last one given.
fn arg_value<'a>(args: &'a [String], name: &str) -> Option<&'a str> {
    let mut value = None;
    let mut args = args.iter();
    while let Some(arg) = args.next() {
        if arg == name {
            value = args.next().map(String::as_str);
        } else if let Some(rest) = arg.strip_prefix(name).and_then(|rest| rest.strip_prefix('=')) {
            value = Some(rest);
        }
    }
    value
}

/// The job's length from `--duration 20s` or `--duration=20s`, in `ms`, `s` or `m`.
fn duration_from_args(args: &[String]) -> Result<Duration, String> {
    let value =
        arg_value(args, "--duration").ok_or("the development runner needs --duration, for example --duration 20s")?;
    let (number, unit) = value.split_at(value.find(|c: char| !c.is_ascii_digit()).unwrap_or(value.len()));
    let number: u64 = number.parse().map_err(|_| format!("--duration {value} isn't a number with a unit"))?;
    let duration = match unit {
        "ms" => Duration::from_millis(number),
        "s" => Duration::from_secs(number),
        "m" => Duration::from_secs(number.saturating_mul(60)),
        _ => return Err(format!("--duration {value} needs a unit: ms, s or m")),
    };
    if duration.is_zero() {
        return Err("--duration must be above zero".to_owned());
    }
    Ok(duration)
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;
    use std::sync::{Arc, Mutex};

    use agent_proto::v1::SessionSpec;
    use uuid::Uuid;

    use super::*;
    use crate::logs::LogWriter;
    use crate::runner::Fence;
    use crate::storage::testing::MemoryStore;

    fn store(objects: &Arc<Mutex<BTreeMap<String, Vec<u8>>>>, prefix: &str, writable: bool) -> Arc<dyn ObjectStore> {
        Arc::new(MemoryStore::new(objects, &[prefix], writable))
    }

    fn spec(args: &[&str], checkpoint_every: Duration) -> SessionSpec {
        SessionSpec {
            args: args.iter().map(|&arg| arg.to_owned()).collect(),
            checkpoint_interval: Some(prost_types::Duration::try_from(checkpoint_every).unwrap()),
            ..SessionSpec::default()
        }
    }

    fn job(
        session: Uuid,
        attempt: i32,
        objects: &Arc<Mutex<BTreeMap<String, Vec<u8>>>>,
        resume_from: Option<CheckpointRef>,
    ) -> Job {
        let attempt_prefix = format!("sessions/{session}/attempt-{attempt}/");
        let read_store =
            resume_from.as_ref().map(|from| store(objects, &format!("{}{:08}/", from.prefix, from.seq), false));
        Job {
            session_id: session,
            attempt,
            spec: spec(&["--duration", "20s"], Duration::from_secs(5)),
            attempt_store: store(objects, &attempt_prefix, true),
            attempt_prefix,
            read_store,
            resume_from,
            fence: Fence::new(),
            stop_requested: tokio::sync::watch::channel(None).1,
            folder: std::env::temp_dir(),
            inputs: BTreeMap::new(),
            logs: LogWriter::discard(),
            input_refs: Vec::new(),
            credentials: None,
            lease_ttl: Duration::from_secs(120),
            minute_index_base: 0,
            gpus: Vec::new(),
        }
    }

    async fn run_to_end(job: Job) -> Vec<Report> {
        let (reports, mut received) = mpsc::unbounded_channel();
        DevRunner.run(job, reports).await;
        let mut all = Vec::new();
        while let Ok(report) = received.try_recv() {
            all.push(report);
        }
        all
    }

    #[tokio::test(start_paused = true)]
    async fn a_job_checkpoints_then_writes_its_outputs_last() {
        let objects = Arc::default();
        let session = Uuid::now_v7();

        let reports = run_to_end(job(session, 1, &objects, None)).await;

        assert_eq!(
            reports,
            vec![
                Report::State(SessionState::Preparing),
                Report::State(SessionState::Running),
                Report::Checkpoint(1),
                Report::Checkpoint(2),
                Report::Checkpoint(3),
                Report::State(SessionState::Uploading),
                Report::Succeeded,
            ]
        );
        let objects = objects.lock().unwrap();
        let prefix = format!("sessions/{session}/attempt-1/");
        assert!(objects.contains_key(&format!("{prefix}checkpoints/00000003/_COMPLETE")));
        assert!(objects.contains_key(&format!("{prefix}outputs/_COMPLETE")));
        let output: Output = serde_json::from_slice(&objects[&format!("{prefix}outputs/result.json")]).unwrap();
        assert_eq!(output.attempt, 1);
        assert_eq!(output.resumed_from, None);
        assert_eq!(output.ran_for_ms, 20_000);
        assert!(objects.keys().all(|key| key.starts_with(&prefix)), "nothing outside its grant");
    }

    #[tokio::test(start_paused = true)]
    async fn a_resumed_job_carries_on_from_its_checkpoint() {
        let objects = Arc::default();
        let session = Uuid::now_v7();
        run_to_end(job(session, 1, &objects, None)).await;
        let from = CheckpointRef { prefix: format!("sessions/{session}/attempt-1/checkpoints/"), seq: 2 };

        let reports = run_to_end(job(session, 2, &objects, Some(from.clone()))).await;

        assert_eq!(reports.iter().filter(|report| matches!(report, Report::Checkpoint(_))).count(), 1);
        let objects = objects.lock().unwrap();
        let output: Output =
            serde_json::from_slice(&objects[&format!("sessions/{session}/attempt-2/outputs/result.json")]).unwrap();
        assert_eq!(output.attempt, 2);
        assert_eq!(output.resumed_from, Some(ResumedFrom { prefix: from.prefix, seq: 2 }));
        assert_eq!(output.ran_for_ms, 20_000, "10 s from the checkpoint plus 10 s more");
    }

    #[tokio::test(start_paused = true)]
    async fn a_checkpoint_without_its_marker_is_never_resumed_from() {
        let objects: Arc<Mutex<BTreeMap<String, Vec<u8>>>> = Arc::default();
        let session = Uuid::now_v7();
        let prefix = format!("sessions/{session}/attempt-1/checkpoints/");
        objects.lock().unwrap().insert(format!("{prefix}00000001/state.json"), br#"{"elapsed_ms":5000}"#.to_vec());

        let reports = run_to_end(job(session, 2, &objects, Some(CheckpointRef { prefix, seq: 1 }))).await;

        assert!(matches!(reports.last(), Some(Report::Failed { code, .. }) if code == "resume_checkpoint_missing"));
    }

    #[tokio::test(start_paused = true)]
    async fn a_fenced_job_stops_and_reports_nothing_more() {
        let objects = Arc::default();
        let job = job(Uuid::now_v7(), 1, &objects, None);
        let fence = job.fence.clone();
        let (reports, mut received) = mpsc::unbounded_channel();
        let running = tokio::spawn(DevRunner.run(job, reports));
        tokio::time::sleep(Duration::from_secs(7)).await;
        fence.take_down();
        running.await.unwrap();

        let mut all = Vec::new();
        while let Ok(report) = received.try_recv() {
            all.push(report);
        }
        assert_eq!(all.last(), Some(&Report::Checkpoint(1)));
        assert!(!objects.lock().unwrap().keys().any(|key| key.contains("outputs/")));
    }

    #[tokio::test(start_paused = true)]
    async fn asked_to_stop_it_writes_a_last_checkpoint_with_the_time_so_far_and_stops() {
        let objects = Arc::default();
        let session = Uuid::now_v7();
        let (stop, stop_requested) = tokio::sync::watch::channel(None);
        let job = Job { stop_requested, ..job(session, 1, &objects, None) };
        let (reports, mut received) = mpsc::unbounded_channel();
        let running = tokio::spawn(DevRunner.run(job, reports));
        tokio::time::sleep(Duration::from_secs(7)).await;
        stop.send(Some(Duration::from_secs(60))).unwrap();
        running.await.unwrap();

        let mut all = Vec::new();
        while let Ok(report) = received.try_recv() {
            all.push(report);
        }
        assert_eq!(all[all.len() - 3..], [Report::Checkpoint(1), Report::Checkpoint(2), Report::Stopped]);
        let objects = objects.lock().unwrap();
        let folder = format!("sessions/{session}/attempt-1/checkpoints/00000002/");
        assert!(objects.contains_key(&format!("{folder}_COMPLETE")));
        let state: State = serde_json::from_slice(&objects[&format!("{folder}state.json")]).unwrap();
        assert_eq!(state.elapsed_ms, 7_000, "the time actually run, not a whole interval");
        assert!(!objects.keys().any(|key| key.contains("outputs/")));
    }

    #[tokio::test(start_paused = true)]
    async fn the_output_proves_the_job_saw_the_exact_input_and_parameters() {
        let objects = Arc::default();
        let session = Uuid::now_v7();
        let folder = tempfile::tempdir().unwrap();
        let input = folder.path().join("input/file");
        std::fs::create_dir_all(input.join("nested")).unwrap();
        std::fs::write(input.join("train.jsonl"), b"hello world").unwrap();
        std::fs::write(input.join("nested/empty.txt"), b"").unwrap();
        let mut job = job(session, 1, &objects, None);
        job.inputs = BTreeMap::from([("file".to_owned(), input)]);
        job.spec.env = [
            ("SESSION_PARAM_GREETING", "hello"),
            ("SESSION_PARAM_REPEAT", "3"),
            ("SESSION_INPUT_FILE_DIR", "/work/input/file/"),
            ("SESSION_INPUT_DATA_URL", "https://example.com/data.jsonl"),
            ("PATH", "/bin"),
        ]
        .into_iter()
        .map(|(name, value)| (name.to_owned(), value.to_owned()))
        .collect();

        run_to_end(job).await;

        let objects = objects.lock().unwrap();
        let output: Output =
            serde_json::from_slice(&objects[&format!("sessions/{session}/attempt-1/outputs/result.json")]).unwrap();
        assert_eq!(
            output.inputs,
            BTreeMap::from([(
                "file".to_owned(),
                vec![
                    InputSeen {
                        path: "nested/empty.txt".to_owned(),
                        size_bytes: 0,
                        // sha256 of nothing
                        sha256: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".to_owned(),
                    },
                    InputSeen {
                        path: "train.jsonl".to_owned(),
                        size_bytes: 11,
                        // sha256("hello world")
                        sha256: "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9".to_owned(),
                    },
                ]
            )])
        );
        assert_eq!(
            output.links,
            BTreeMap::from([("data".to_owned(), "https://example.com/data.jsonl".to_owned())]),
            "a link is recorded, never fetched"
        );
        assert_eq!(
            output.params,
            BTreeMap::from([
                ("SESSION_PARAM_GREETING".to_owned(), "hello".to_owned()),
                ("SESSION_PARAM_REPEAT".to_owned(), "3".to_owned())
            ]),
            "only the parameters, not the rest of the environment"
        );
        assert_eq!(output.echo, Some(vec!["hello".to_owned(); 3]));
    }

    #[tokio::test(start_paused = true)]
    async fn the_job_writes_its_progress_to_the_log() {
        let objects = Arc::default();
        let session = Uuid::now_v7();
        let (finish, finished) = tokio::sync::watch::channel(false);
        let log_store = store(&objects, &format!("sessions/{session}/attempt-1/"), true);
        let (logs, sink) =
            crate::logs::spawn(log_store, format!("sessions/{session}/attempt-1/logs/"), finished, |_| {});
        let mut job = job(session, 1, &objects, None);
        job.logs = logs;
        job.spec.env = BTreeMap::from([("SESSION_PARAM_GREETING".to_owned(), "hi".to_owned())]).into_iter().collect();

        run_to_end(job).await;
        finish.send(true).unwrap();
        sink.await.unwrap();

        let objects = objects.lock().unwrap();
        let text: String = objects
            .iter()
            .filter(|(key, _)| key.contains("/logs/"))
            .map(|(_, body)| String::from_utf8_lossy(body).into_owned())
            .collect();
        for expected in [
            format!("starting session {session} attempt 1"),
            "parameter SESSION_PARAM_GREETING=hi".to_owned(),
            "checkpoint 1 at 5000 ms".to_owned(),
            "finished after 20000 ms; outputs uploaded".to_owned(),
        ] {
            assert!(text.contains(&expected), "{expected:?} not in the log:\n{text}");
        }
    }

    /// A simulated service's job given `files` (input, path, contents): its outputs, listed in their marker, and its
    /// log, for `kind`.
    async fn simulate(
        kind: &str,
        files: &[(&str, &str, &[u8])],
        params: &[(&str, &str)],
    ) -> (BTreeMap<String, Vec<u8>>, String) {
        let objects = Arc::default();
        let session = Uuid::now_v7();
        let folder = tempfile::tempdir().unwrap();
        let mut inputs = BTreeMap::new();
        for (input, path, contents) in files {
            let staged = folder.path().join("input").join(input);
            std::fs::create_dir_all(staged.join(path).parent().unwrap()).unwrap();
            std::fs::write(staged.join(path), contents).unwrap();
            inputs.insert((*input).to_owned(), staged);
        }
        let (finish, finished) = tokio::sync::watch::channel(false);
        let prefix = format!("sessions/{session}/attempt-1/");
        let (logs, sink) =
            crate::logs::spawn(store(&objects, &prefix, true), format!("{prefix}logs/"), finished, |_| {});
        let mut job = job(session, 1, &objects, None);
        job.spec = spec(&["--duration", "90s", "--simulate", kind], Duration::from_secs(5));
        job.spec.env =
            params.iter().map(|&(name, value)| (format!("SESSION_PARAM_{name}"), value.to_owned())).collect();
        job.inputs = inputs;
        job.logs = logs;

        let reports = run_to_end(job).await;
        finish.send(true).unwrap();
        sink.await.unwrap();

        assert_eq!(reports.last(), Some(&Report::Succeeded), "{reports:?}");
        assert_eq!(
            reports.iter().filter(|report| matches!(report, Report::Checkpoint(_))).count(),
            17,
            "every 5 s, as now"
        );
        let objects = objects.lock().unwrap();
        let outputs = format!("{prefix}outputs/");
        let files: BTreeMap<String, Vec<u8>> = objects
            .iter()
            .filter_map(|(key, body)| key.strip_prefix(&outputs).map(|path| (path.to_owned(), body.clone())))
            .filter(|(path, _)| path != "_COMPLETE")
            .collect();
        let manifest = Manifest::from_slice(&objects[&format!("{outputs}_COMPLETE")]).unwrap();
        let listed: BTreeMap<String, u64> = manifest.files.into_iter().map(|file| (file.path, file.size)).collect();
        assert_eq!(
            listed,
            files.iter().map(|(path, body)| (path.clone(), body.len() as u64)).collect(),
            "the marker lists every output"
        );
        let log = objects
            .iter()
            .filter(|(key, _)| key.contains("/logs/"))
            .map(|(_, body)| String::from_utf8_lossy(body).into_owned())
            .collect();
        (files, log)
    }

    #[tokio::test(start_paused = true)]
    async fn simulating_a_fine_tune_lists_the_renters_model_and_writes_an_adapter_and_a_training_log() {
        let examples = "{\"prompt\": \"a\", \"completion\": \"b\"}\n".repeat(40);
        let (files, log) = simulate(
            "fine-tune",
            &[
                ("model", "config.json", b"{}"),
                ("model", "tokenizer.json", b"{\"v\":1}"),
                ("model", "model-00001-of-00001.safetensors", &[7; 64]),
                ("data", "train.jsonl", examples.as_bytes()),
            ],
            &[("EPOCHS", "3"), ("LORA_RANK", "8")],
        )
        .await;

        assert_eq!(files.keys().collect::<Vec<_>>(), ["adapter_model.safetensors", "training_log.json"]);
        let training: serde_json::Value = serde_json::from_slice(&files["training_log.json"]).unwrap();
        assert_eq!(training["training_examples"], 40);
        assert_eq!(training["base_model"], "your uploaded model (3 files)");
        assert_eq!(training["steps"].as_array().unwrap().len(), 15, "5 steps an epoch for 40 examples, 3 epochs");
        for line in [
            "your model: 3 files, 73 bytes in /work/input/model/",
            "  model-00001-of-00001.safetensors (64 bytes)",
            "  tokenizer.json (7 bytes)",
            "your training data: 1 file, 1400 bytes in /work/input/data/",
            "40 training examples, 5 steps an epoch at batch size 8",
        ] {
            assert!(log.contains(line), "{line:?} not in the log:\n{log}");
        }
        assert!(log.contains("epoch 3/3 step 15/15 loss "), "{log}");
        assert!(log.contains("saving the adapter: adapter_model.safetensors"), "{log}");
        assert!(!log.contains("SESSION_PARAM_"), "a simulation prints its own lines, not the runner's");
    }

    #[tokio::test(start_paused = true)]
    async fn simulating_run_your_code_writes_its_result() {
        let (files, log) = simulate(
            "run-code",
            &[("code", "main.py", b"print('hi')\n"), ("code", "lib/util.py", b"")],
            &[("COMMAND", "python train.py --fast")],
        )
        .await;

        assert_eq!(files.keys().collect::<Vec<_>>(), ["result.txt"]);
        let result = String::from_utf8(files["result.txt"].clone()).unwrap();
        assert!(result.starts_with("$ python train.py --fast\n") && result.contains("exit code 0"), "{result}");
        assert!(
            log.contains("$ python train.py --fast") && log.contains("batch 16/16: ") && log.contains("Done."),
            "{log}"
        );
        assert!(
            log.contains("your code: 2 files, 12 bytes in /work/input/code/")
                && log.contains("  lib/util.py (0 bytes)")
        );
        assert!(log.contains("your data: none given"), "{log}");
    }

    #[tokio::test(start_paused = true)]
    async fn an_unknown_simulation_is_refused() {
        let objects = Arc::default();
        let mut job = job(Uuid::now_v7(), 1, &objects, None);
        job.spec = spec(&["--duration", "20s", "--simulate", "mine-bitcoin"], Duration::from_secs(5));

        let reports = run_to_end(job).await;

        assert!(matches!(reports.last(), Some(Report::Failed { code, .. }) if code == "invalid_args"), "{reports:?}");
    }

    #[test]
    fn durations_come_from_the_args() {
        let parse = |args: &[&str]| duration_from_args(&args.iter().map(|&a| a.to_owned()).collect::<Vec<_>>());
        assert_eq!(parse(&["--duration", "20s"]), Ok(Duration::from_secs(20)));
        assert_eq!(parse(&["--duration=2m"]), Ok(Duration::from_secs(120)));
        assert_eq!(parse(&["x", "--duration", "500ms"]), Ok(Duration::from_millis(500)));
        assert!(parse(&["nvidia-smi"]).is_err());
        assert!(parse(&["--duration", "20"]).is_err());
        assert!(parse(&["--duration", "0s"]).is_err());
        let args = ["--duration", "90s", "--simulate=fine-tune"].map(str::to_owned);
        assert_eq!(arg_value(&args, "--simulate"), Some("fine-tune"));
        assert_eq!(arg_value(&args, "--sim"), None, "a prefix isn't the argument");
    }
}
