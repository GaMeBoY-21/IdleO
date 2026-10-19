//! Moving a job's files to and from storage (ADR 0035, proto/README.md "Storage layout"): its checkpoints and outputs go
//! up, a checkpoint to resume from comes down.
//!
//! **Checkpoints.** A job writes a folder `checkpoints/<n>/` (n counts up) and, when it's complete, an empty `_DONE` file
//! in it. [`Checkpoints::sweep`] uploads each finished folder to `{attempt}/checkpoints/{n:08}/` with the real `_COMPLETE`
//! manifest (every file, its size and SHA-256) written last, reports it, and deletes older local checkpoints, keeping the
//! last. **Outputs.** [`upload_outputs`] uploads everything in `outputs/` and then `outputs/_COMPLETE`.
//!
//! The folders are the job's, so everything is read through [`Tree`], which never follows a symlink; a link, a device or
//! anything but a regular file in them is skipped and said so in a line for the renter's log. Files go up by
//! [`ObjectStore::put_file`], a part at a time.

use std::collections::BTreeSet;
use std::path::Path;

use jobcore::manifest::{COMPLETE, Manifest, ManifestFile};
use jobcore::protocol::ResumeFrom;
use jobcore::staging;
use jobcore::storage::{ObjectStore, StorageError};

use crate::safefs::{Kind, Tree};

/// The empty file a job makes in a checkpoint folder once it's complete.
pub const DONE: &str = "_DONE";

/// Why a folder couldn't be uploaded.
#[derive(Debug, PartialEq, Eq)]
pub enum UploadError {
    /// Storage refused or failed.
    Storage(String),
    /// The folder couldn't be read.
    Local(String),
}

impl std::fmt::Display for UploadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Storage(why) | Self::Local(why) => f.write_str(why),
        }
    }
}

impl From<StorageError> for UploadError {
    fn from(error: StorageError) -> Self {
        Self::Storage(error.to_string())
    }
}

/// What uploading a folder did.
#[derive(Debug)]
pub struct Uploaded {
    /// What was uploaded, as the `_COMPLETE` marker lists it.
    pub manifest: Manifest,
    /// Entries left out because they aren't regular files, by path.
    pub skipped: Vec<String>,
}

/// Uploads every regular file under `folder` (a path inside `tree`) to `{dest}{path inside folder}`, then `{dest}_COMPLETE`.
/// The marker's name and the checkpoint's `_DONE` are never uploaded as files.
///
/// # Errors
/// If a file can't be read or storage fails.
pub async fn upload_folder(
    store: &dyn ObjectStore,
    tree: &Tree,
    folder: &str,
    dest: &str,
) -> Result<Uploaded, UploadError> {
    let local = |e: std::io::Error| UploadError::Local(format!("reading {folder} failed: {e}"));
    let mut files = Vec::new();
    let mut skipped = Vec::new();
    for entry in tree.walk(folder).map_err(local)? {
        let inside = entry.rel.strip_prefix(&format!("{folder}/")).unwrap_or(&entry.rel).to_owned();
        match entry.kind {
            Kind::Dir => {}
            Kind::Other => skipped.push(inside),
            Kind::File if inside == DONE || inside == COMPLETE => {}
            Kind::File if !staging::valid_path(&inside) => skipped.push(inside),
            Kind::File => {
                let file = tree.open_file(&entry.rel).map_err(local)?;
                let uploaded = store.put_file(&format!("{dest}{inside}"), file).await?;
                files.push(ManifestFile { path: inside, size: uploaded.size, sha256: Some(uploaded.sha256) });
            }
        }
    }
    let manifest = Manifest { files };
    store.put(&format!("{dest}{COMPLETE}"), manifest.to_bytes()).await?;
    Ok(Uploaded { manifest, skipped })
}

/// Uploads the job's outputs: everything in `outputs/`, then `{attempt}outputs/_COMPLETE`.
///
/// # Errors
/// If a file can't be read or storage fails.
pub async fn upload_outputs(
    store: &dyn ObjectStore,
    work: &Path,
    attempt_prefix: &str,
) -> Result<Uploaded, UploadError> {
    let tree = Tree::open(work).map_err(|e| UploadError::Local(format!("the work folder can't be read: {e}")))?;
    if tree.list("outputs").is_err() {
        // A job that wrote no outputs has an empty folder, and that's still a complete (empty) set.
        let manifest = Manifest { files: Vec::new() };
        store.put(&format!("{attempt_prefix}outputs/{COMPLETE}"), manifest.to_bytes()).await?;
        return Ok(Uploaded { manifest, skipped: Vec::new() });
    }
    upload_folder(store, &tree, "outputs", &format!("{attempt_prefix}outputs/")).await
}

/// The checkpoints a job has finished and the runner has uploaded.
#[derive(Debug, Default)]
pub struct Checkpoints {
    uploaded: BTreeSet<i32>,
}

impl Checkpoints {
    /// The newest checkpoint uploaded; 0 for none.
    #[must_use]
    pub fn latest(&self) -> i32 {
        self.uploaded.last().copied().unwrap_or(0)
    }

    /// Uploads every checkpoint the job has finished and the runner hasn't uploaded, oldest first, calling `reported` with
    /// each one's number once its marker is up, then deletes every older local checkpoint but the newest. Returns the lines
    /// to write to the renter's log.
    ///
    /// # Errors
    /// If a checkpoint can't be uploaded. The ones before it stay uploaded, and the next sweep tries it again.
    pub async fn sweep(
        &mut self,
        store: &dyn ObjectStore,
        work: &Path,
        attempt_prefix: &str,
        mut reported: impl FnMut(i32) + Send,
    ) -> Result<Vec<String>, UploadError> {
        let mut log = Vec::new();
        let Ok(tree) = Tree::open(work) else { return Ok(log) };
        let Ok(entries) = tree.list("checkpoints") else { return Ok(log) };
        let mut finished: Vec<i32> = entries
            .iter()
            .filter(|entry| entry.kind == Kind::Dir)
            .filter_map(|entry| entry.rel.rsplit('/').next()?.parse::<i32>().ok().filter(|n| *n > 0))
            .filter(|n| !self.uploaded.contains(n))
            .filter(|n| {
                tree.list(&format!("checkpoints/{n}")).is_ok_and(|inside| {
                    inside.iter().any(|entry| entry.kind == Kind::File && entry.rel.ends_with(&format!("/{DONE}")))
                })
            })
            .collect();
        finished.sort_unstable();
        for n in finished {
            let result = upload_folder(
                store,
                &tree,
                &format!("checkpoints/{n}"),
                &format!("{attempt_prefix}checkpoints/{n:08}/"),
            )
            .await?;
            for path in &result.skipped {
                log.push(format!("checkpoint {n}: {path} isn't a regular file, so it isn't saved"));
            }
            self.uploaded.insert(n);
            reported(n);
            log.push(format!("checkpoint {n} saved ({} files)", result.manifest.files.len()));
        }
        let newest = self.latest();
        for entry in tree.list("checkpoints").unwrap_or_default() {
            let older = entry.rel.rsplit('/').next().and_then(|name| name.parse::<i32>().ok());
            if entry.kind == Kind::Dir && older.is_some_and(|n| n < newest && self.uploaded.contains(&n)) {
                let _ = tree.remove_dir_all(&entry.rel);
            }
        }
        Ok(log)
    }
}

/// Downloads the checkpoint to resume from into `dest`, only if its `_COMPLETE` marker exists and every file it lists is
/// there with the size (and SHA-256, if listed) it says.
///
/// # Errors
/// What's wrong with the checkpoint, in a line.
pub async fn fetch_checkpoint(store: &dyn ObjectStore, from: &ResumeFrom, dest: &Path) -> Result<u64, String> {
    let folder = format!("{}{:08}/", from.prefix, from.seq);
    let marker = store
        .get(&format!("{folder}{COMPLETE}"))
        .await
        .map_err(|e| format!("the checkpoint to resume from has no completion marker: {e}"))?;
    let manifest = Manifest::from_slice(&marker).map_err(|e| format!("the checkpoint's {e}"))?;
    let mut total = 0;
    for file in &manifest.files {
        if !staging::valid_path(&file.path) {
            return Err("the checkpoint lists a file with an unsafe path".to_owned());
        }
        let path = dest.join(&file.path);
        if let Some(parent) = path.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|e| format!("the checkpoint's folder couldn't be made: {e}"))?;
        }
        let size = store
            .download(&format!("{folder}{}", file.path), &path)
            .await
            .map_err(|e| format!("a file of the checkpoint couldn't be downloaded: {e}"))?;
        if size != file.size {
            return Err(format!("{} is {size} bytes, not the {} its checkpoint lists", file.path, file.size));
        }
        if let Some(expected) = &file.sha256 {
            let actual = sha256_of(&path).await.map_err(|e| format!("a checkpoint file couldn't be read back: {e}"))?;
            if &actual != expected {
                return Err(format!("{} doesn't match the SHA-256 its checkpoint lists", file.path));
            }
        }
        total += size;
    }
    Ok(total)
}

async fn sha256_of(path: &Path) -> std::io::Result<String> {
    use sha2::{Digest, Sha256};
    use tokio::io::AsyncReadExt;
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0_u8; 1024 * 1024];
    loop {
        let read = file.read(&mut buffer).await?;
        if read == 0 {
            return Ok(jobcore::storage::hex(&hasher.finalize()));
        }
        hasher.update(&buffer[..read]);
    }
}

#[cfg(test)]
mod tests {
    use std::sync::{Arc, Mutex};

    use jobcore::storage::testing::{MemoryStore, Objects};
    use sha2::{Digest, Sha256};

    use super::*;

    const ATTEMPT: &str = "sessions/0190f3a2-0000-7000-8000-000000000000/attempt-1/";

    fn store(objects: &Objects) -> MemoryStore {
        MemoryStore::new(objects, &[ATTEMPT], true)
    }

    fn write(work: &Path, rel: &str, body: &[u8]) {
        let path = work.join(rel);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, body).unwrap();
    }

    fn sha(body: &[u8]) -> String {
        jobcore::storage::hex(&Sha256::digest(body))
    }

    #[tokio::test]
    async fn a_finished_checkpoint_goes_up_with_its_marker_last_and_hashes_in_it() {
        let work = tempfile::tempdir().unwrap();
        write(work.path(), "checkpoints/1/state.json", b"{\"step\":10}");
        write(work.path(), "checkpoints/1/weights/shard-0.bin", &[7; 100]);
        write(work.path(), "checkpoints/1/_DONE", b"");
        let objects = Objects::default();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let mut checkpoints = Checkpoints::default();

        let recorded = Arc::clone(&seen);
        let log = checkpoints
            .sweep(&store(&objects), work.path(), ATTEMPT, move |n| recorded.lock().unwrap().push(n))
            .await
            .unwrap();

        assert_eq!(*seen.lock().unwrap(), [1]);
        assert_eq!(checkpoints.latest(), 1);
        assert_eq!(log, ["checkpoint 1 saved (2 files)"]);
        let objects = objects.lock().unwrap();
        let folder = format!("{ATTEMPT}checkpoints/00000001/");
        assert_eq!(objects[&format!("{folder}state.json")], b"{\"step\":10}");
        assert!(!objects.contains_key(&format!("{folder}_DONE")), "the job's own marker isn't uploaded");
        let manifest = Manifest::from_slice(&objects[&format!("{folder}{COMPLETE}")]).unwrap();
        assert_eq!(
            manifest.files,
            [
                ManifestFile { path: "state.json".to_owned(), size: 11, sha256: Some(sha(b"{\"step\":10}")) },
                ManifestFile { path: "weights/shard-0.bin".to_owned(), size: 100, sha256: Some(sha(&[7; 100])) },
            ]
        );
    }

    #[tokio::test]
    async fn a_checkpoint_without_its_done_file_isnt_touched_and_one_is_never_uploaded_twice() {
        let work = tempfile::tempdir().unwrap();
        write(work.path(), "checkpoints/1/state.json", b"half written");
        let objects = Objects::default();
        let mut checkpoints = Checkpoints::default();
        let uploads = store(&objects);

        checkpoints.sweep(&uploads, work.path(), ATTEMPT, |_| panic!("not finished")).await.unwrap();
        assert!(objects.lock().unwrap().is_empty());

        write(work.path(), "checkpoints/1/_DONE", b"");
        let mut count = 0;
        checkpoints.sweep(&uploads, work.path(), ATTEMPT, |_| count += 1).await.unwrap();
        checkpoints.sweep(&uploads, work.path(), ATTEMPT, |_| count += 1).await.unwrap();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    async fn older_local_checkpoints_are_deleted_and_the_last_is_kept() {
        let work = tempfile::tempdir().unwrap();
        for n in 1..=3 {
            write(work.path(), &format!("checkpoints/{n}/state.json"), b"x");
            write(work.path(), &format!("checkpoints/{n}/_DONE"), b"");
        }
        let objects = Objects::default();
        let mut checkpoints = Checkpoints::default();

        checkpoints.sweep(&store(&objects), work.path(), ATTEMPT, |_| {}).await.unwrap();

        assert!(!work.path().join("checkpoints/1").exists() && !work.path().join("checkpoints/2").exists());
        assert!(work.path().join("checkpoints/3/state.json").exists());
        assert_eq!(checkpoints.latest(), 3);
    }

    #[tokio::test]
    async fn a_checkpoint_that_fails_to_upload_is_tried_again_and_the_earlier_ones_stay_up() {
        let work = tempfile::tempdir().unwrap();
        for n in 1..=2 {
            write(work.path(), &format!("checkpoints/{n}/state.json"), b"x");
            write(work.path(), &format!("checkpoints/{n}/_DONE"), b"");
        }
        let objects = Objects::default();
        let uploads = store(&objects);
        let mut checkpoints = Checkpoints::default();
        // Checkpoint 1 needs a file and the marker; let those through, then fail checkpoint 2's first write.
        let mut up = Vec::new();
        uploads.fail_puts(0);
        checkpoints.sweep(&uploads, work.path(), ATTEMPT, |n| up.push(n)).await.unwrap();
        assert_eq!(up, [1, 2]);

        write(work.path(), "checkpoints/3/state.json", b"x");
        write(work.path(), "checkpoints/3/_DONE", b"");
        uploads.fail_puts(1);
        let error = checkpoints.sweep(&uploads, work.path(), ATTEMPT, |n| up.push(n)).await.unwrap_err();
        assert!(matches!(error, UploadError::Storage(_)));
        assert_eq!(checkpoints.latest(), 2);
        checkpoints.sweep(&uploads, work.path(), ATTEMPT, |n| up.push(n)).await.unwrap();
        assert_eq!(up, [1, 2, 3]);
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn a_planted_link_is_never_uploaded_and_the_renters_log_says_so() {
        let work = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        std::fs::write(outside.path().join("shadow"), b"root:secret").unwrap();
        write(work.path(), "checkpoints/1/state.json", b"ok");
        std::os::unix::fs::symlink(outside.path().join("shadow"), work.path().join("checkpoints/1/innocent.txt"))
            .unwrap();
        std::os::unix::fs::symlink(outside.path(), work.path().join("checkpoints/1/folder")).unwrap();
        write(work.path(), "checkpoints/1/_DONE", b"");
        let objects = Objects::default();
        let mut checkpoints = Checkpoints::default();

        let log = checkpoints.sweep(&store(&objects), work.path(), ATTEMPT, |_| {}).await.unwrap();

        let objects = objects.lock().unwrap();
        assert!(objects.values().all(|body| !String::from_utf8_lossy(body).contains("root:secret")));
        assert!(!objects.keys().any(|key| key.contains("innocent") || key.contains("shadow")), "{:?}", objects.keys());
        assert!(log.iter().any(|line| line.contains("innocent.txt isn't a regular file")), "{log:?}");
    }

    #[tokio::test]
    async fn outputs_go_up_in_their_folders_with_the_marker_last_and_no_outputs_is_still_complete() {
        let work = tempfile::tempdir().unwrap();
        write(work.path(), "outputs/result.json", b"{}");
        write(work.path(), "outputs/logs/train.log", b"line\n");
        let objects = Objects::default();

        let result = upload_outputs(&store(&objects), work.path(), ATTEMPT).await.unwrap();

        assert_eq!(result.manifest.files.len(), 2);
        let keys: Vec<String> = objects.lock().unwrap().keys().cloned().collect();
        assert_eq!(
            keys,
            [
                format!("{ATTEMPT}outputs/_COMPLETE"),
                format!("{ATTEMPT}outputs/logs/train.log"),
                format!("{ATTEMPT}outputs/result.json")
            ]
        );
        let empty = tempfile::tempdir().unwrap();
        let other = Objects::default();
        let none = upload_outputs(&store(&other), empty.path(), ATTEMPT).await.unwrap();
        assert_eq!(none.manifest.files.len(), 0);
        assert!(other.lock().unwrap().contains_key(&format!("{ATTEMPT}outputs/_COMPLETE")));
    }

    #[tokio::test]
    async fn a_checkpoint_comes_down_only_if_it_is_whole() {
        let objects = Objects::default();
        let uploads = store(&objects);
        let prefix = format!("{ATTEMPT}checkpoints/");
        let folder = format!("{prefix}00000002/");
        objects.lock().unwrap().insert(format!("{folder}state.json"), b"{\"step\":5}".to_vec());
        let from = ResumeFrom { prefix: prefix.clone(), seq: 2 };
        let dest = tempfile::tempdir().unwrap();

        let missing = fetch_checkpoint(&uploads, &from, dest.path()).await.unwrap_err();
        assert!(missing.contains("no completion marker"), "{missing}");

        let manifest = Manifest {
            files: vec![ManifestFile { path: "state.json".to_owned(), size: 10, sha256: Some(sha(b"{\"step\":5}")) }],
        };
        objects.lock().unwrap().insert(format!("{folder}{COMPLETE}"), manifest.to_bytes());
        assert_eq!(fetch_checkpoint(&uploads, &from, dest.path()).await, Ok(10));
        assert_eq!(std::fs::read(dest.path().join("state.json")).unwrap(), b"{\"step\":5}");

        let wrong =
            Manifest { files: vec![ManifestFile { sha256: Some("0".repeat(64)), ..manifest.files[0].clone() }] };
        objects.lock().unwrap().insert(format!("{folder}{COMPLETE}"), wrong.to_bytes());
        assert!(fetch_checkpoint(&uploads, &from, dest.path()).await.unwrap_err().contains("SHA-256"));

        let unsafe_path =
            Manifest { files: vec![ManifestFile { path: "../escape".to_owned(), size: 1, sha256: None }] };
        objects.lock().unwrap().insert(format!("{folder}{COMPLETE}"), unsafe_path.to_bytes());
        assert!(fetch_checkpoint(&uploads, &from, dest.path()).await.unwrap_err().contains("unsafe"));
    }
}
