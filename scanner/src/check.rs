//! One check, start to end (ADR 0042): resolve the reference to one platform's manifest, read its config, say what it is
//! (the control plane decides the limits, or answers from a result it already has for that digest), download the blobs as
//! they are into an OCI layout on the scratch disk, read every layer as a stream ([`layers`](crate::layers)), run Trivy on
//! the layout, and report. The scratch folder is deleted whatever happens. A step that can't finish, a crash or a check past
//! its deadline is reported as a failure, never as a pass: the image stays not checked and can't be booked.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use serde::Deserialize;
use tokio::sync::Mutex;

use crate::control::{ExecutableWire, ImageConfig, Next, Phase, Refused, Resolution, Result_};
use crate::guard::Allowed;
use crate::layers::{Compression, Image, Limits, Refusal};
use crate::reference::Reference;
use crate::registry::{self, Login, Registry, Resolved};
use crate::trivy;

/// Where a check's facts go: the control plane, or the terminal for `idleo-scanner check`.
pub trait Sink: Send + Sync {
    /// What the reference resolved to.
    fn resolved(&self, resolution: &Resolution) -> impl Future<Output = Result<Next, String>> + Send;
    /// Progress.
    fn progress(&self, phase: Phase, done: u64, total: u64) -> impl Future<Output = Result<Next, String>> + Send;
    /// The facts.
    fn result(&self, result: &Result_) -> impl Future<Output = Result<(), String>> + Send;
    /// A refusal.
    fn refused(&self, refused: &Refused) -> impl Future<Output = Result<(), String>> + Send;
    /// A failure: not checked.
    fn failed(&self, detail: &str) -> impl Future<Output = Result<(), String>> + Send;
}

/// What one check is given.
#[derive(Debug, Clone)]
pub struct Job {
    /// Its id, which names its scratch folder.
    pub id: String,
    /// The reference.
    pub reference: String,
    /// A private registry's credentials.
    pub login: Option<Login>,
    /// `linux/amd64`.
    pub platform: String,
    /// What it may unpack to.
    pub limits: Limits,
    /// How long it may take in all.
    pub deadline: Duration,
}

/// What every check shares.
#[derive(Debug, Clone)]
pub struct Settings {
    /// The scratch disk: each check gets a folder of its own, deleted after.
    pub scratch: PathBuf,
    /// Trivy.
    pub trivy: trivy::Settings,
    /// Development hosts that may be private.
    pub allowed: Allowed,
    /// Room kept free on the scratch disk beyond what a check downloads.
    pub keep_free_bytes: u64,
}

/// How a check ended, for the log.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ending {
    /// Facts reported.
    Checked,
    /// The control plane stopped it (a cached result, a limit, a lost claim).
    Stopped,
    /// Refused: blocked.
    Refused(&'static str),
    /// Not checked.
    Failed,
}

enum Stop {
    Refuse(Refused),
    Fail(String),
    Told,
}

fn refuse(reason: &'static str, detail: impl Into<String>) -> Stop {
    Stop::Refuse(Refused { reason, detail: detail.into() })
}

/// Progress shared with the heartbeat.
#[derive(Default)]
struct Shared {
    phase: Mutex<Option<Phase>>,
    done: AtomicU64,
    total: AtomicU64,
}

/// Runs `job`, reporting to `sink`, and deletes its scratch folder.
pub async fn run(job: &Job, settings: &Settings, sink: &impl Sink) -> Ending {
    let folder = settings
        .scratch
        .join(format!("check-{}", job.id.replace(|c: char| !c.is_ascii_alphanumeric() && c != '-', "")));
    let _ = tokio::fs::remove_dir_all(&folder).await;
    let shared = Arc::new(Shared::default());
    let work = check(job, settings, sink, &folder, &shared);
    let beat = heartbeat(sink, &shared);
    let outcome = tokio::select! {
        outcome = tokio::time::timeout(job.deadline, work) => outcome,
        () = beat => Ok(Err(Stop::Told)),
    };
    if let Err(e) = tokio::fs::remove_dir_all(&folder).await
        && e.kind() != std::io::ErrorKind::NotFound
    {
        tracing::warn!(check = %job.id, error = %e, "a check's scratch folder couldn't be deleted");
    }
    let report = match outcome {
        Ok(Ok(result)) => sink.result(&result).await.map(|()| Ending::Checked),
        Ok(Err(Stop::Told)) => Ok(Ending::Stopped),
        Ok(Err(Stop::Refuse(refused))) => sink.refused(&refused).await.map(|()| Ending::Refused(refused.reason)),
        Ok(Err(Stop::Fail(detail))) => sink.failed(&detail).await.map(|()| Ending::Failed),
        Err(_) => sink
            .failed(&format!("The check ran past its {} minutes.", job.deadline.as_secs() / 60))
            .await
            .map(|()| Ending::Failed),
    };
    report.unwrap_or_else(|e| {
        // The control plane's claim lapses and the check is queued again (or, after its tries, failed): never a pass.
        tracing::warn!(check = %job.id, error = %e, "a check's ending couldn't be reported");
        Ending::Failed
    })
}

/// Reports progress every 20 seconds, which keeps the claim; ends when the control plane says to stop.
async fn heartbeat(sink: &impl Sink, shared: &Shared) {
    let mut ticks = tokio::time::interval(Duration::from_secs(20));
    ticks.tick().await;
    loop {
        ticks.tick().await;
        let Some(phase) = *shared.phase.lock().await else { continue };
        match sink.progress(phase, shared.done.load(Ordering::Relaxed), shared.total.load(Ordering::Relaxed)).await {
            Ok(Next::Stop) => return,
            Ok(Next::Proceed) => {}
            Err(e) => tracing::warn!(error = %e, "progress couldn't be reported; the check goes on"),
        }
    }
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "PascalCase")]
struct ConfigBody {
    #[serde(default)]
    env: Option<Vec<String>>,
    #[serde(default)]
    entrypoint: Option<Vec<String>>,
    #[serde(default)]
    cmd: Option<Vec<String>>,
    #[serde(default)]
    working_dir: Option<String>,
    #[serde(default)]
    user: Option<String>,
}

#[derive(Deserialize)]
struct ConfigFile {
    #[serde(default)]
    architecture: String,
    #[serde(default)]
    os: String,
    #[serde(default)]
    config: Option<ConfigBody>,
}

/// The config's facts: only the two CUDA variables of its environment are kept, since the rest may hold anything.
fn image_config(bytes: &[u8]) -> Result<(String, ImageConfig), String> {
    let file: ConfigFile = serde_json::from_slice(bytes).map_err(|_| "the image's config isn't JSON".to_owned())?;
    let body = file.config.unwrap_or_default();
    let env = |name: &str| {
        body.env
            .iter()
            .flatten()
            .find_map(|entry| entry.strip_prefix(&format!("{name}=")).map(|v| v.chars().take(500).collect()))
    };
    let config = ImageConfig {
        cuda_require: env("NVIDIA_REQUIRE_CUDA"),
        cuda_version: env("CUDA_VERSION"),
        entrypoint: body.entrypoint.clone().unwrap_or_default(),
        cmd: body.cmd.clone().unwrap_or_default(),
        working_dir: body.working_dir.clone().filter(|dir| !dir.is_empty()),
        user: body.user.clone().filter(|user| !user.is_empty()),
    };
    Ok((format!("{}/{}", file.os, file.architecture), config))
}

async fn check(
    job: &Job,
    settings: &Settings,
    sink: &impl Sink,
    folder: &Path,
    shared: &Shared,
) -> Result<Result_, Stop> {
    let reference = Reference::parse(&job.reference)
        .map_err(|e| refuse("pull_failed", format!("That isn't an image reference: {e}.")))?;
    let registry = Registry::new(reference, job.login.clone(), settings.allowed.clone()).map_err(|e| pull_stop(&e))?;
    *shared.phase.lock().await = Some(Phase::Downloading);
    let manifest = match registry.resolve(&job.platform).await.map_err(|e| pull_stop(&e))? {
        Resolved::Found(manifest) => manifest,
        Resolved::WrongPlatform(platforms) => {
            sink.resolved(&Resolution { digest: None, platforms, compressed_bytes: 0, layers: 0 })
                .await
                .map_err(Stop::Fail)?;
            return Err(Stop::Told);
        }
    };
    let config_bytes = registry.config(&manifest.config).await.map_err(|e| pull_stop(&e))?;
    let (platform, config) = image_config(&config_bytes).map_err(|e| refuse("pull_failed", e))?;
    let compressed: u64 = manifest.layers.iter().map(|layer| layer.size).sum();
    let layer_count = u32::try_from(manifest.layers.len()).unwrap_or(u32::MAX);
    let resolution = if platform == job.platform {
        Resolution {
            digest: Some(manifest.digest.clone()),
            platforms: Vec::new(),
            compressed_bytes: compressed,
            layers: layer_count,
        }
    } else {
        Resolution { digest: None, platforms: vec![platform], compressed_bytes: compressed, layers: layer_count }
    };
    if sink.resolved(&resolution).await.map_err(Stop::Fail)? == Next::Stop || resolution.digest.is_none() {
        return Err(Stop::Told);
    }
    for layer in &manifest.layers {
        if Compression::of(&layer.media_type).is_none() {
            return Err(refuse(
                "unpack_failed",
                format!("The image has a layer of a kind we can't read ({}).", layer.media_type),
            ));
        }
    }

    // The blobs as they are, in an OCI layout: what Trivy reads, and what the layers are read from.
    let blobs = folder.join("blobs").join("sha256");
    tokio::fs::create_dir_all(&blobs)
        .await
        .map_err(|e| Stop::Fail(format!("The scanner's disk refused a folder: {e}.")))?;
    let free = free_bytes(&settings.scratch);
    if free < compressed.saturating_add(settings.keep_free_bytes) {
        return Err(Stop::Fail(format!(
            "The scanner's disk has {} free; this image's {} (and {} kept free) needs more, so it's checked again later.",
            crate::gigabytes(free),
            crate::gigabytes(compressed),
            crate::gigabytes(settings.keep_free_bytes)
        )));
    }
    let blob = |digest: &str| blobs.join(digest.trim_start_matches("sha256:"));
    write(&blob(&manifest.config.digest), &config_bytes).await?;
    write(&blob(&manifest.digest), &manifest.bytes).await?;
    let index = serde_json::json!({
        "schemaVersion": 2,
        "manifests": [{ "mediaType": manifest.media_type, "digest": manifest.digest, "size": manifest.bytes.len() }],
    });
    write(&folder.join("index.json"), index.to_string().as_bytes()).await?;
    write(&folder.join("oci-layout"), br#"{"imageLayoutVersion":"1.0.0"}"#).await?;
    shared.total.store(compressed, Ordering::Relaxed);
    let mut before = 0;
    for layer in &manifest.layers {
        let done = &shared.done;
        registry
            .download(layer, &blob(&layer.digest), |received| done.store(before + received, Ordering::Relaxed))
            .await
            .map_err(|e| pull_stop(&e))?;
        before += layer.size;
    }

    let image = read_layers(&manifest.layers, job.limits, &blobs, shared).await?;

    *shared.phase.lock().await = Some(Phase::Scanning);
    let tmp = folder.join("tmp");
    tokio::fs::create_dir_all(&tmp)
        .await
        .map_err(|e| Stop::Fail(format!("The scanner's disk refused a folder: {e}.")))?;
    let findings = trivy::scan(&settings.trivy, folder, &tmp).await.map_err(Stop::Fail)?;
    Ok(Result_ {
        digest: manifest.digest,
        compressed_bytes: compressed,
        layers: layer_count,
        config,
        unpacked_bytes: image.unpacked_bytes,
        has_shell: image.has_shell(),
        executables: image.executables.iter().map(ExecutableWire::from).collect(),
        findings,
    })
}

/// Reads every layer from its downloaded blob, as a stream, off the async threads.
async fn read_layers(
    layers: &[registry::Descriptor],
    limits: Limits,
    blobs: &Path,
    shared: &Shared,
) -> Result<Image, Stop> {
    *shared.phase.lock().await = Some(Phase::Reading);
    shared.done.store(0, Ordering::Relaxed);
    let mut image = Image::default();
    for (index, layer) in layers.iter().enumerate() {
        let path = blobs.join(layer.digest.trim_start_matches("sha256:"));
        let compression = Compression::of(&layer.media_type).unwrap_or(Compression::None);
        let read = tokio::task::spawn_blocking(move || {
            let file = std::fs::File::open(&path)
                .map_err(|e| Refusal::Unreadable(format!("A layer couldn't be opened: {e}.")))?;
            image
                .read_layer(index, std::io::BufReader::with_capacity(1 << 20, file), compression, limits)
                .map(|()| image)
        })
        .await
        .map_err(|e| Stop::Fail(format!("Reading a layer stopped: {e}.")))?;
        image = read.map_err(|refusal| match refusal {
            Refusal::TooLargeUnpacked(why) => refuse("too_large_unpacked", why),
            Refusal::UnsafePath(why) => refuse("unsafe_path", why),
            Refusal::Unreadable(why) => refuse("unpack_failed", why),
        })?;
        shared.done.fetch_add(layer.size, Ordering::Relaxed);
    }
    Ok(image)
}

/// A registry's refusal blocks the image (it can't be pulled); a network failure leaves it not checked, to try again.
fn pull_stop(error: &registry::Error) -> Stop {
    match error {
        registry::Error::Refused(why) | registry::Error::Bad(why) => {
            refuse("pull_failed", format!("The image couldn't be pulled: {why}."))
        }
        registry::Error::Network(why) => Stop::Fail(format!("The image couldn't be downloaded: {why}.")),
    }
}

async fn write(path: &Path, bytes: &[u8]) -> Result<(), Stop> {
    tokio::fs::write(path, bytes).await.map_err(|e| Stop::Fail(format!("The scanner's disk refused a write: {e}.")))
}

fn free_bytes(path: &Path) -> u64 {
    fs2_free(path).unwrap_or(0)
}

#[cfg(unix)]
fn fs2_free(path: &Path) -> Option<u64> {
    let stat = rustix::fs::statvfs(path).ok()?;
    Some(stat.f_bavail.saturating_mul(stat.f_frsize))
}

#[cfg(not(unix))]
fn fs2_free(_path: &Path) -> Option<u64> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_cuda_variables_of_the_environment_are_kept() {
        let config = br#"{"architecture":"amd64","os":"linux","config":{
            "Env":["PATH=/usr/bin","CUDA_VERSION=12.4.1","NVIDIA_REQUIRE_CUDA=cuda>=12.4 brand=tesla,driver>=470","API_KEY=hunter2"],
            "Entrypoint":["/opt/nvidia/nvidia_entrypoint.sh"],"Cmd":["python","train.py"],"WorkingDir":"/app","User":""}}"#;
        let (platform, config) = image_config(config).unwrap();
        assert_eq!(platform, "linux/amd64");
        assert_eq!(config.cuda_version.as_deref(), Some("12.4.1"));
        assert_eq!(config.cuda_require.as_deref(), Some("cuda>=12.4 brand=tesla,driver>=470"));
        assert_eq!(config.cmd, ["python", "train.py"]);
        assert_eq!(config.working_dir.as_deref(), Some("/app"));
        assert_eq!(config.user, None, "an empty user is root, said as none");
        assert!(!serde_json::to_string(&config).unwrap().contains("hunter2"));
    }
}
