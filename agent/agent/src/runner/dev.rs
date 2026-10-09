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
//! It proves what the job saw (ADR 0019): `result.json` has the staged input's name, size and SHA-256,
//! every `SESSION_PARAM_*` variable from the spec, and, when there's a `greeting` parameter (the
//! `echo-input` template), the greeting repeated `repeat` times. It writes real log lines: the start, the
//! attempt, the checkpoint it resumed from, the parameters, the input, each checkpoint, and the finish.
//!
//! With `--simulate <kind>` in its arguments (fine-tune, run-code, generate-images or transcribe), it plays
//! one of the four services instead ([`simulate`]): that service's log lines, streamed as it runs, and its
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

use self::simulate::{Given, Kind, Simulation};
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

/// A folder's completion marker: every other file in it, with its size.
#[derive(Debug, Serialize, Deserialize)]
struct Manifest {
    files: Vec<ManifestFile>,
}

#[derive(Debug, Serialize, Deserialize)]
struct ManifestFile {
    path: String,
    size: u64,
}

/// The job's output.
#[derive(Debug, Serialize, Deserialize, PartialEq, Eq)]
struct Output {
    session_id: String,
    attempt: i32,
    resumed_from: Option<ResumedFrom>,
    ran_for_ms: u64,
    checkpoints_written: i32,
    /// The staged input, as the job read it.
    input: Option<InputSeen>,
    /// `SESSION_INPUT_URL`, which a real job would fetch inside its sandbox; this one only records it.
    input_url: Option<String>,
    /// Every `SESSION_PARAM_*` variable the job saw.
    params: BTreeMap<String, String>,
    /// For a `greeting` parameter: the greeting, `repeat` times.
    echo: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
struct InputSeen {
    name: String,
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
        let asked_to_stop = *stop_requested.borrow_and_update()
            || tokio::select! {
                () = tokio::time::sleep(step) => false,
                // A closed channel disables this branch: with nobody left to ask, no stop is coming.
                Ok(_) = stop_requested.wait_for(|stop| *stop) => true,
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
        input: seen.input.clone(),
        input_url: seen.input_url.clone(),
        params: seen.params.clone(),
        echo: seen.echo.clone(),
    };
    serde_json::to_vec_pretty(&output).expect("the output serialises")
}

fn simulation(job: &Job, kind: Kind, seen: &Seen) -> Simulation {
    let given = Given {
        params: seen.params.clone(),
        input_name: seen.input.as_ref().map(|input| input.name.clone()),
        input_bytes: seen.input.as_ref().map_or(0, |input| input.size_bytes),
        input_lines: seen.input_lines,
    };
    Simulation::new(kind, given, &job.session_id.to_string())
}

/// What the job was given; written to its log as it's read, unless a simulation writes its own.
struct Seen {
    params: BTreeMap<String, String>,
    input: Option<InputSeen>,
    /// Lines in the input file: a simulation's training examples or prompts.
    input_lines: u64,
    input_url: Option<String>,
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
    let (input, input_lines) = match &job.input_file {
        Some(path) => {
            let (seen, lines) = read_input(path).await?;
            line(format!("input {} {} bytes sha256 {}", seen.name, seen.size_bytes, seen.sha256));
            (Some(seen), lines)
        }
        None => (None, 0),
    };
    let input_url = job.spec.env.get("SESSION_INPUT_URL").cloned();
    if let Some(url) = &input_url {
        line(format!("input url {url} (a real job fetches it inside its sandbox)"));
    }
    let echo = params.get("SESSION_PARAM_GREETING").map(|greeting| {
        let repeat = params.get("SESSION_PARAM_REPEAT").and_then(|repeat| repeat.parse::<usize>().ok()).unwrap_or(1);
        vec![greeting.clone(); repeat]
    });
    for text in echo.iter().flatten() {
        line(text.clone());
    }
    Ok(Seen { params, input, input_lines, input_url, echo })
}

/// The staged input's name, size and SHA-256, and how many lines it has, read in pieces.
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
    Ok((InputSeen { name, size_bytes: size, sha256 }, lines))
}

fn fenced(job: &Job) -> Result<(), Stop> {
    if job.fence.is_up() { Ok(()) } else { Err(Stop::Fenced) }
}

/// Reads a checkpoint, only if its completion marker exists and lists what's there.
async fn resume(store: &dyn ObjectStore, checkpoint: &CheckpointRef) -> Result<Duration, Stop> {
    let folder = format!("{}{:08}/", checkpoint.prefix, checkpoint.seq);
    let missing = |message: String| Stop::Failed { code: "resume_checkpoint_missing", message };
    let marker = store.get(&format!("{folder}_COMPLETE")).await.map_err(|e| missing(e.to_string()))?;
    let manifest: Manifest =
        serde_json::from_slice(&marker).map_err(|e| missing(format!("the checkpoint's manifest is invalid: {e}")))?;
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
    let files = files.iter().map(|(path, body)| ManifestFile { path: path.clone(), size: body.len() as u64 }).collect();
    serde_json::to_vec(&Manifest { files }).expect("the manifest serialises")
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
            stop_requested: tokio::sync::watch::channel(false).1,
            folder: std::env::temp_dir(),
            input_file: None,
            logs: LogWriter::discard(),
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
        let (stop, stop_requested) = tokio::sync::watch::channel(false);
        let job = Job { stop_requested, ..job(session, 1, &objects, None) };
        let (reports, mut received) = mpsc::unbounded_channel();
        let running = tokio::spawn(DevRunner.run(job, reports));
        tokio::time::sleep(Duration::from_secs(7)).await;
        stop.send(true).unwrap();
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
        let input = folder.path().join("input/train.jsonl");
        std::fs::create_dir_all(input.parent().unwrap()).unwrap();
        std::fs::write(&input, b"hello world").unwrap();
        let mut job = job(session, 1, &objects, None);
        job.input_file = Some(input);
        job.spec.env = [("SESSION_PARAM_GREETING", "hello"), ("SESSION_PARAM_REPEAT", "3"), ("PATH", "/bin")]
            .into_iter()
            .map(|(name, value)| (name.to_owned(), value.to_owned()))
            .collect();

        run_to_end(job).await;

        let objects = objects.lock().unwrap();
        let output: Output =
            serde_json::from_slice(&objects[&format!("sessions/{session}/attempt-1/outputs/result.json")]).unwrap();
        assert_eq!(
            output.input,
            Some(InputSeen {
                name: "train.jsonl".to_owned(),
                size_bytes: 11,
                // sha256("hello world")
                sha256: "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9".to_owned(),
            })
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

    /// A simulated service's job: its outputs, listed in their marker, and its log, for `kind`.
    async fn simulate(kind: &str, input: &[u8], params: &[(&str, &str)]) -> (BTreeMap<String, Vec<u8>>, String) {
        let objects = Arc::default();
        let session = Uuid::now_v7();
        let folder = tempfile::tempdir().unwrap();
        let path = folder.path().join("input/given");
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, input).unwrap();
        let (finish, finished) = tokio::sync::watch::channel(false);
        let prefix = format!("sessions/{session}/attempt-1/");
        let (logs, sink) =
            crate::logs::spawn(store(&objects, &prefix, true), format!("{prefix}logs/"), finished, |_| {});
        let mut job = job(session, 1, &objects, None);
        job.spec = spec(&["--duration", "90s", "--simulate", kind], Duration::from_secs(5));
        job.spec.env =
            params.iter().map(|&(name, value)| (format!("SESSION_PARAM_{name}"), value.to_owned())).collect();
        job.input_file = Some(path);
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
        let manifest: Manifest = serde_json::from_slice(&objects[&format!("{outputs}_COMPLETE")]).unwrap();
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
    async fn simulating_a_fine_tune_writes_an_adapter_and_a_training_log() {
        let examples = "{\"prompt\": \"a\", \"completion\": \"b\"}\n".repeat(40);
        let (files, log) = simulate(
            "fine-tune",
            examples.as_bytes(),
            &[("BASE_MODEL", "Llama 3.2 1B Instruct"), ("EPOCHS", "3"), ("LORA_RANK", "8")],
        )
        .await;

        assert_eq!(files.keys().collect::<Vec<_>>(), ["adapter_model.safetensors", "training_log.json"]);
        let training: serde_json::Value = serde_json::from_slice(&files["training_log.json"]).unwrap();
        assert_eq!(training["training_examples"], 40);
        assert_eq!(training["steps"].as_array().unwrap().len(), 15, "5 steps an epoch for 40 examples, 3 epochs");
        assert!(log.contains("loading base model Llama 3.2 1B Instruct (4-bit)"), "{log}");
        assert!(log.contains("epoch 3/3 step 15/15 loss "), "{log}");
        assert!(log.contains("saving the adapter: adapter_model.safetensors"), "{log}");
        assert!(!log.contains("SESSION_PARAM_"), "a simulation prints its own lines, not the runner's");
    }

    #[tokio::test(start_paused = true)]
    async fn simulating_run_your_code_writes_its_result() {
        let (files, log) =
            simulate("run-code", b"PK\x03\x04 not really a zip", &[("COMMAND", "python train.py --fast")]).await;

        assert_eq!(files.keys().collect::<Vec<_>>(), ["result.txt"]);
        let result = String::from_utf8(files["result.txt"].clone()).unwrap();
        assert!(result.starts_with("$ python train.py --fast\n") && result.contains("exit code 0"), "{result}");
        assert!(
            log.contains("$ python train.py --fast") && log.contains("batch 16/16: ") && log.contains("Done."),
            "{log}"
        );
    }

    #[tokio::test(start_paused = true)]
    async fn simulating_image_generation_writes_one_png_an_image() {
        let (files, log) =
            simulate("generate-images", b"a red fox\na lighthouse at dusk\n", &[("COUNT", "3"), ("SIZE", "512")]).await;

        assert_eq!(files.keys().collect::<Vec<_>>(), ["image-1.png", "image-2.png", "image-3.png"]);
        assert!(files.values().all(|png| png.starts_with(b"\x89PNG\r\n\x1a\n")));
        assert!(log.contains("2 prompts; 3 images at 512x512") && log.contains("image 3 of 3: done"), "{log}");
    }

    #[tokio::test(start_paused = true)]
    async fn simulating_a_transcription_writes_text_and_subtitles() {
        let (files, log) = simulate("transcribe", &[0_u8; 4096], &[("LANGUAGE", "Hindi")]).await;

        assert_eq!(files.keys().collect::<Vec<_>>(), ["transcript.srt", "transcript.txt"]);
        let text = String::from_utf8(files["transcript.txt"].clone()).unwrap();
        let srt = String::from_utf8(files["transcript.srt"].clone()).unwrap();
        assert_eq!(text.lines().count(), 12);
        assert!(text.starts_with("नमस्ते"), "{text}");
        assert!(srt.starts_with("1\n00:00:00,400 --> 00:00:0"), "{srt}");
        assert!(log.contains("language: Hindi") && log.contains("[00:00:00.400 --> "), "{log}");
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
