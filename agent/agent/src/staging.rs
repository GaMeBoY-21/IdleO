//! Staging a session's inputs (ADR 0019, ADR 0022): before the runner starts, while the attempt is PREPARING, the
//! supervisor downloads every uploaded file named in `StartSession.inputs` through the read grant into the attempt's
//! scratch folder, `input/{slot}/{name}`, keeping each file's path inside its input's folder, and checks each size.
//! Nothing is billed before minute 0, so a failure here costs the renter nothing: a file that can't be downloaded
//! after [`TRIES`] tries fails the attempt with `input_unavailable`. It lives in the supervisor, not a runner, so every
//! runner gets it.
//!
//! Only uploads are staged. A link input is fetched by the job itself, inside its sandbox where private networks
//! are blocked: the agent runs outside the sandbox and could reach the host's home network, so it never fetches a
//! renter's link.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use agent_proto::v1::InputRef;
use tokio::task::JoinSet;

use crate::logs::LogWriter;
use crate::storage::{Grant, ObjectStore};

/// How many times a file's download is tried before the attempt fails.
pub const TRIES: u32 = 3;
/// How many files download at once: a model folder of many small files shouldn't wait on each one's round trip.
const PARALLEL: usize = 4;
/// The longest path a file may have inside its input's folder, in bytes.
const MAX_PATH_BYTES: usize = 1_024;

/// One segment of a file's path: what the control plane stores upload paths as.
#[must_use]
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && name != "."
        && name != ".."
        && name.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// A file's path inside its input's folder: one or more safe segments joined by `/`.
#[must_use]
pub fn valid_path(path: &str) -> bool {
    path.len() <= MAX_PATH_BYTES && path.split('/').all(valid_name)
}

/// The template's name for an input, as the job's folder and variables use it.
#[must_use]
pub fn valid_slot(slot: &str) -> bool {
    let mut bytes = slot.bytes();
    slot.len() <= 32
        && bytes.next().is_some_and(|first| first.is_ascii_lowercase())
        && bytes.all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
}

/// Checks the inputs against the proto's rules: each one valid, no path given twice in a slot, and no file where
/// another file's folder would be.
///
/// # Errors
/// Why they're invalid.
pub fn check(inputs: &[InputRef]) -> Result<(), String> {
    let mut paths: BTreeMap<&str, BTreeSet<&str>> = BTreeMap::new();
    for input in inputs {
        if input.prefix.is_empty() || !input.prefix.ends_with('/') || input.prefix.split('/').any(|part| part == "..") {
            return Err("an input's prefix must end in /".to_owned());
        }
        if !valid_slot(&input.slot) {
            return Err("an input's slot isn't a valid name".to_owned());
        }
        if !valid_path(&input.name) {
            return Err("an input's name isn't a safe path".to_owned());
        }
        if input.size_bytes < 0 {
            return Err("an input's size can't be below zero".to_owned());
        }
        if !paths.entry(&input.slot).or_default().insert(&input.name) {
            return Err(format!("{}/{} is given twice", input.slot, input.name));
        }
    }
    for (slot, files) in &paths {
        for file in files {
            let folder = format!("{file}/");
            if files.range::<&str, _>(folder.as_str()..).next().is_some_and(|next| next.starts_with(&folder)) {
                return Err(format!("{slot}/{file} is both a file and a folder"));
            }
        }
    }
    Ok(())
}

/// Checks the inputs ([`check`]), and that the read grant covers every one.
///
/// # Errors
/// Why they're invalid or out of reach.
pub fn check_covered(inputs: &[InputRef], grant: Option<&Grant>) -> Result<(), String> {
    check(inputs)?;
    if inputs.is_empty() {
        return Ok(());
    }
    let grant = grant.ok_or("an input without a read grant")?;
    inputs.iter().try_for_each(|input| {
        grant.check(&key(input), false).map_err(|_| "the read grant doesn't cover an input".to_owned())
    })
}

/// An input's object key.
#[must_use]
pub fn key(input: &InputRef) -> String {
    format!("{}{}", input.prefix, input.name)
}

/// Downloads every input into `{folder}/input/{slot}/{name}`, a few at a time, and returns each slot's folder.
///
/// # Errors
/// Why an input couldn't be staged, after every try. Never renter data.
pub async fn stage(
    store: Arc<dyn ObjectStore>,
    inputs: &[InputRef],
    folder: &Path,
    logs: &LogWriter,
) -> Result<BTreeMap<String, PathBuf>, String> {
    let mut folders = BTreeMap::new();
    let mut totals: BTreeMap<String, (usize, u64)> = BTreeMap::new();
    for input in inputs {
        folders.insert(input.slot.clone(), folder.join("input").join(&input.slot));
        let total = totals.entry(input.slot.clone()).or_default();
        total.0 += 1;
        total.1 += u64::try_from(input.size_bytes).unwrap_or(0);
    }
    for path in folders.values() {
        tokio::fs::create_dir_all(path).await.map_err(|e| format!("the input folder couldn't be made: {e}"))?;
    }
    let mut waiting = inputs.iter().cloned();
    let mut running = JoinSet::new();
    loop {
        while running.len() < PARALLEL {
            let Some(input) = waiting.next() else { break };
            let path = folder.join("input").join(&input.slot).join(&input.name);
            running.spawn(file(Arc::clone(&store), input, path));
        }
        match running.join_next().await {
            None => break,
            Some(Ok(Ok(()))) => {}
            Some(Ok(Err(error))) => {
                running.abort_all();
                logs.line(format!("an input couldn't be downloaded after {TRIES} tries"));
                return Err(error);
            }
            Some(Err(error)) => {
                running.abort_all();
                return Err(format!("staging an input stopped: {error}"));
            }
        }
    }
    for (slot, (files, bytes)) in &totals {
        logs.line(format!("input {slot} staged: {files} file{}, {bytes} bytes", if *files == 1 { "" } else { "s" }));
    }
    Ok(folders)
}

/// One file, downloaded and checked, with its folders made; an empty one is written, not downloaded.
async fn file(store: Arc<dyn ObjectStore>, input: InputRef, path: PathBuf) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| format!("the input folder couldn't be made: {e}"))?;
    }
    let expected = u64::try_from(input.size_bytes).unwrap_or(0);
    if expected == 0 {
        return tokio::fs::write(&path, b"").await.map_err(|e| format!("an empty input file couldn't be written: {e}"));
    }
    let mut last = String::new();
    for tried in 1..=TRIES {
        match store.download(&key(&input), &path).await {
            Ok(size) if size == expected => return Ok(()),
            Ok(size) => last = format!("{}/{} is {size} bytes, not the {expected} expected", input.slot, input.name),
            Err(error) => last = error.to_string(),
        }
        let _ = tokio::fs::remove_file(&path).await;
        tracing::warn!(tried, reason = %last, "staging an input file failed");
        if tried < TRIES {
            tokio::time::sleep(Duration::from_secs(u64::from(tried))).await;
        }
    }
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::testing::{MemoryStore, Objects};

    const UPLOAD: &str = "uploads/0190f3a2-0000-7000-8000-00000000000a/files/";

    fn input(slot: &str, name: &str, size: i64) -> InputRef {
        InputRef { prefix: UPLOAD.to_owned(), name: name.to_owned(), size_bytes: size, slot: slot.to_owned() }
    }

    fn store(objects: &Objects) -> Arc<dyn ObjectStore> {
        Arc::new(MemoryStore::new(objects, &[UPLOAD], false))
    }

    #[tokio::test(start_paused = true)]
    async fn every_input_is_downloaded_into_its_slot_with_its_folders_kept() {
        let objects = Objects::default();
        for (name, body) in
            [("config.json", &b"{}"[..]), ("weights/model-00001.safetensors", b"abcd"), ("train.jsonl", b"{\"x\":1}\n")]
        {
            objects.lock().unwrap().insert(format!("{UPLOAD}{name}"), body.to_vec());
        }
        let folder = tempfile::tempdir().unwrap();
        let inputs = [
            input("model", "config.json", 2),
            input("model", "weights/model-00001.safetensors", 4),
            input("model", "empty/__init__.py", 0),
            input("data", "train.jsonl", 8),
        ];

        let staged = stage(store(&objects), &inputs, folder.path(), &LogWriter::discard()).await.unwrap();

        assert_eq!(staged["model"], folder.path().join("input/model"));
        assert_eq!(staged["data"], folder.path().join("input/data"));
        assert_eq!(std::fs::read(folder.path().join("input/model/weights/model-00001.safetensors")).unwrap(), b"abcd");
        assert_eq!(std::fs::read(folder.path().join("input/model/empty/__init__.py")).unwrap(), b"");
        assert_eq!(std::fs::read(folder.path().join("input/data/train.jsonl")).unwrap(), b"{\"x\":1}\n");
    }

    #[tokio::test(start_paused = true)]
    async fn a_missing_object_fails_after_every_try() {
        let objects = Objects::default();
        let folder = tempfile::tempdir().unwrap();

        let error =
            stage(store(&objects), &[input("data", "train.jsonl", 8)], folder.path(), &LogWriter::discard()).await;

        assert!(error.unwrap_err().contains("doesn't exist"));
        assert!(!folder.path().join("input/data/train.jsonl").exists());
    }

    #[tokio::test(start_paused = true)]
    async fn the_wrong_size_is_refused() {
        let objects = Objects::default();
        objects.lock().unwrap().insert(format!("{UPLOAD}train.jsonl"), b"short".to_vec());
        let folder = tempfile::tempdir().unwrap();

        let error =
            stage(store(&objects), &[input("data", "train.jsonl", 8)], folder.path(), &LogWriter::discard()).await;

        assert_eq!(error.unwrap_err(), "data/train.jsonl is 5 bytes, not the 8 expected");
    }

    #[tokio::test(start_paused = true)]
    async fn a_folder_of_many_files_arrives_whole() {
        let objects = Objects::default();
        let inputs: Vec<InputRef> = (1..=25)
            .map(|i| {
                let name = format!("shards/part-{i:03}.bin");
                objects.lock().unwrap().insert(format!("{UPLOAD}{name}"), vec![u8::try_from(i).unwrap(); i]);
                input("model", &name, i64::try_from(i).unwrap())
            })
            .collect();
        let folder = tempfile::tempdir().unwrap();

        stage(store(&objects), &inputs, folder.path(), &LogWriter::discard()).await.unwrap();

        let staged = std::fs::read_dir(folder.path().join("input/model/shards")).unwrap().count();
        assert_eq!(staged, 25);
        assert_eq!(std::fs::read(folder.path().join("input/model/shards/part-025.bin")).unwrap(), vec![25; 25]);
    }

    #[test]
    fn every_input_must_be_inside_the_read_grant() {
        let objects = Objects::default();
        let grant = MemoryStore::new(&objects, &[UPLOAD], false).grant();
        let elsewhere = InputRef {
            prefix: "uploads/0190f3a2-0000-7000-8000-00000000000b/files/".to_owned(),
            ..input("data", "a", 1)
        };

        assert!(check_covered(&[input("model", "a", 1)], Some(&grant)).is_ok());
        assert!(check_covered(&[input("model", "a", 1), elsewhere], Some(&grant)).is_err());
        assert!(check_covered(&[input("model", "a", 1)], None).is_err(), "no read grant");
        assert!(check_covered(&[], None).is_ok(), "nothing to stage");
    }

    #[test]
    fn only_safe_slots_paths_and_whole_prefixes_pass() {
        assert!(check(&[input("model", "a/b/config.json", 1), input("data", "a", 0)]).is_ok());
        for bad in [
            input("model", "../etc/passwd", 1),
            input("model", "a/../b", 1),
            input("model", "/etc/passwd", 1),
            input("model", "a//b", 1),
            input("model", "a b", 1),
            input("Model", "a", 1),
            input("", "a", 1),
            input("model", "a", -1),
            InputRef { prefix: "uploads/x".to_owned(), ..input("model", "a", 1) },
        ] {
            assert!(check(std::slice::from_ref(&bad)).is_err(), "{bad:?}");
        }
        assert!(check(&[input("model", "a", 1), input("model", "a", 1)]).is_err(), "twice");
        assert!(check(&[input("model", "a", 1), input("model", "a/b", 1)]).is_err(), "a file and a folder");
        assert!(check(&[input("model", "a", 1), input("data", "a/b", 1)]).is_ok(), "different slots");
    }
}
