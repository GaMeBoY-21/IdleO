//! The development runner (`--dev-runner`, only with `--fake-gpu`): simulates a job so the whole
//! session flow runs on a machine without a GPU, against real object storage through the job's
//! grants.
//!
//! It runs for the duration in the spec's arguments (`--duration 20s`), writing a checkpoint every
//! `checkpoint_interval`: a numbered folder holding `state.json`, then its `_COMPLETE` manifest,
//! written last. On resume it reads the `resume_from` checkpoint through the read grant and carries
//! on from the time it records. At the end it writes `outputs/result.json`, naming the attempt and
//! the checkpoint it resumed from, then `outputs/_COMPLETE`, and succeeds with exit code 0.

use std::future::Future;
use std::pin::Pin;
use std::time::Duration;

use agent_proto::v1::{CheckpointRef, SessionState};
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;

use super::{Job, Report, SessionRunner};
use crate::storage::{ObjectStore, StorageError};

/// How many times an output upload is tried before the job fails.
const UPLOAD_TRIES: u32 = 3;

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
    let duration =
        duration_from_args(&job.spec.args).map_err(|message| Stop::Failed { code: "invalid_args", message })?;
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
    }
    fenced(job)?;
    let _ = reports.send(Report::State(SessionState::Running));
    tracing::info!(session_id = %job.session_id, attempt = job.attempt, ?duration, "the job is running");

    let mut seq = 0;
    while elapsed < duration {
        let step = interval.min(duration.saturating_sub(elapsed));
        tokio::time::sleep(step).await;
        elapsed += step;
        fenced(job)?;
        if elapsed < duration {
            seq += 1;
            match write_checkpoint(job, seq, elapsed).await {
                Ok(()) => {
                    let _ = reports.send(Report::Checkpoint(seq));
                }
                Err(Stop::Fenced) => return Err(Stop::Fenced),
                // The job goes on; the next checkpoint may work, and resume uses the last complete one.
                Err(Stop::Failed { message, .. }) => {
                    tracing::warn!(session_id = %job.session_id, seq, %message, "a checkpoint failed to upload");
                    seq -= 1;
                }
            }
        }
    }

    let _ = reports.send(Report::State(SessionState::Uploading));
    let output = Output {
        session_id: job.session_id.to_string(),
        attempt: job.attempt,
        resumed_from: job.resume_from.as_ref().map(|from| ResumedFrom { prefix: from.prefix.clone(), seq: from.seq }),
        ran_for_ms: millis(elapsed),
        checkpoints_written: seq,
    };
    let outputs = format!("{}outputs/", job.attempt_prefix);
    let body = serde_json::to_vec_pretty(&output).expect("the output serialises");
    let manifest = manifest("result.json", &body);
    upload(job, &format!("{outputs}result.json"), body).await?;
    upload(job, &format!("{outputs}_COMPLETE"), manifest).await?;
    tracing::info!(session_id = %job.session_id, attempt = job.attempt, "the job finished and its outputs are uploaded");
    Ok(())
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
    let manifest = manifest("state.json", &state);
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

fn manifest(path: &str, body: &[u8]) -> Vec<u8> {
    serde_json::to_vec(&Manifest { files: vec![ManifestFile { path: path.to_owned(), size: body.len() as u64 }] })
        .expect("the manifest serialises")
}

fn millis(duration: Duration) -> u64 {
    u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
}

/// The job's length from `--duration 20s` or `--duration=20s`, in `ms`, `s` or `m`.
fn duration_from_args(args: &[String]) -> Result<Duration, String> {
    let mut value = None;
    let mut args = args.iter();
    while let Some(arg) = args.next() {
        if arg == "--duration" {
            value = args.next().map(String::as_str);
        } else if let Some(rest) = arg.strip_prefix("--duration=") {
            value = Some(rest);
        }
    }
    let value = value.ok_or("the development runner needs --duration, for example --duration 20s")?;
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

    use agent_proto::v1::{SessionSpec, StorageGrant, StoragePermission};
    use uuid::Uuid;

    use super::*;
    use crate::runner::Fence;
    use crate::storage::{Grant, StorageFuture};

    /// Storage in memory, with a grant's scope checks, so the runner can be tested without a server.
    struct MemoryStore {
        grant: Grant,
        objects: Arc<Mutex<BTreeMap<String, Vec<u8>>>>,
    }

    impl ObjectStore for MemoryStore {
        fn put<'a>(&'a self, key: &'a str, body: Vec<u8>) -> StorageFuture<'a, ()> {
            Box::pin(async move {
                self.grant.check(key, true)?;
                self.objects.lock().unwrap().insert(key.to_owned(), body);
                Ok(())
            })
        }

        fn get<'a>(&'a self, key: &'a str) -> StorageFuture<'a, Vec<u8>> {
            Box::pin(async move {
                self.grant.check(key, false)?;
                self.objects.lock().unwrap().get(key).cloned().ok_or_else(|| StorageError::NotFound(key.to_owned()))
            })
        }

        fn list<'a>(&'a self, prefix: &'a str) -> StorageFuture<'a, Vec<String>> {
            Box::pin(async move {
                self.grant.check(prefix, false)?;
                Ok(self.objects.lock().unwrap().keys().filter(|key| key.starts_with(prefix)).cloned().collect())
            })
        }
    }

    fn store(objects: &Arc<Mutex<BTreeMap<String, Vec<u8>>>>, prefix: &str, writable: bool) -> Arc<dyn ObjectStore> {
        let message = StorageGrant {
            endpoint: "http://storage.invalid".to_owned(),
            bucket: "idleo-test".to_owned(),
            access_key_id: "TESTKEY".to_owned(),
            secret_access_key: "test-secret".to_owned(),
            session_token: "test-token".to_owned(),
            prefixes: vec![prefix.to_owned()],
            permission: if writable { StoragePermission::ReadWrite } else { StoragePermission::ReadOnly }.into(),
            expires_in: Some(prost_types::Duration { seconds: 3600, nanos: 0 }),
        };
        Arc::new(MemoryStore {
            grant: Grant::from_message(&message, Duration::ZERO).unwrap(),
            objects: Arc::clone(objects),
        })
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

    #[test]
    fn durations_come_from_the_args() {
        let parse = |args: &[&str]| duration_from_args(&args.iter().map(|&a| a.to_owned()).collect::<Vec<_>>());
        assert_eq!(parse(&["--duration", "20s"]), Ok(Duration::from_secs(20)));
        assert_eq!(parse(&["--duration=2m"]), Ok(Duration::from_secs(120)));
        assert_eq!(parse(&["x", "--duration", "500ms"]), Ok(Duration::from_millis(500)));
        assert!(parse(&["nvidia-smi"]).is_err());
        assert!(parse(&["--duration", "20"]).is_err());
        assert!(parse(&["--duration", "0s"]).is_err());
    }
}
