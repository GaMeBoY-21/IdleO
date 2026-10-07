//! Staging a session's input (ADR 0019): before the runner starts, while the attempt is PREPARING, the
//! supervisor downloads the uploaded file named in `StartSession.input` through the read grant into the
//! attempt's scratch folder, `input/{name}`, and checks its size. Nothing is billed before minute 0, so a failure
//! here costs the renter nothing: after [`TRIES`] tries the attempt fails with `input_unavailable`. It lives in the
//! supervisor, not a runner, so every runner gets it.
//!
//! Only uploads are staged. A URL input is fetched by the job itself, inside its sandbox where private networks
//! are blocked: the agent runs outside the sandbox and could reach the host's home network, so it never fetches a
//! renter's URL.

use std::path::{Path, PathBuf};
use std::time::Duration;

use agent_proto::v1::InputRef;

use crate::logs::LogWriter;
use crate::storage::ObjectStore;

/// How many times the download is tried before the attempt fails.
pub const TRIES: u32 = 3;

/// The file name an input may have: what the control plane stores uploads under.
#[must_use]
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && name != "."
        && name != ".."
        && name.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// Checks an `InputRef` against the proto's rules.
///
/// # Errors
/// Why it's invalid.
pub fn check(input: &InputRef) -> Result<(), String> {
    if input.prefix.is_empty() || !input.prefix.ends_with('/') || input.prefix.split('/').any(|part| part == "..") {
        return Err("the input's prefix must end in /".to_owned());
    }
    if !valid_name(&input.name) {
        return Err("the input's name isn't a safe file name".to_owned());
    }
    if input.size_bytes <= 0 {
        return Err("the input's size must be above zero".to_owned());
    }
    Ok(())
}

/// The input's object key.
#[must_use]
pub fn key(input: &InputRef) -> String {
    format!("{}{}", input.prefix, input.name)
}

/// Downloads the input into `{folder}/input/{name}`, retrying, and returns the file's path.
///
/// # Errors
/// Why the input couldn't be staged, after every try. Never renter data.
pub async fn stage(
    store: &dyn ObjectStore,
    input: &InputRef,
    folder: &Path,
    logs: &LogWriter,
) -> Result<PathBuf, String> {
    let directory = folder.join("input");
    tokio::fs::create_dir_all(&directory).await.map_err(|e| format!("the input folder couldn't be made: {e}"))?;
    let path = directory.join(&input.name);
    let expected = u64::try_from(input.size_bytes).unwrap_or(0);
    let mut last = String::new();
    for tried in 1..=TRIES {
        match store.download(&key(input), &path).await {
            Ok(size) if size == expected => {
                logs.line(format!("input staged: {} ({size} bytes)", input.name));
                return Ok(path);
            }
            Ok(size) => last = format!("the input is {size} bytes, not the {expected} expected"),
            Err(error) => last = error.to_string(),
        }
        let _ = tokio::fs::remove_file(&path).await;
        tracing::warn!(tried, reason = %last, "staging the input failed");
        if tried < TRIES {
            tokio::time::sleep(Duration::from_secs(u64::from(tried))).await;
        }
    }
    logs.line(format!("the input couldn't be downloaded after {TRIES} tries"));
    Err(last)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::testing::{MemoryStore, Objects};

    const UPLOAD: &str = "uploads/0190f3a2-0000-7000-8000-00000000000a/";

    fn input(size: i64) -> InputRef {
        InputRef { prefix: UPLOAD.to_owned(), name: "train.jsonl".to_owned(), size_bytes: size }
    }

    #[tokio::test(start_paused = true)]
    async fn the_input_is_downloaded_into_the_scratch_folder() {
        let objects = Objects::default();
        objects.lock().unwrap().insert(format!("{UPLOAD}train.jsonl"), b"{\"x\":1}\n".to_vec());
        let store = MemoryStore::new(&objects, &[UPLOAD], false);
        let folder = tempfile::tempdir().unwrap();

        let path = stage(&store, &input(8), folder.path(), &LogWriter::discard()).await.unwrap();

        assert_eq!(path, folder.path().join("input/train.jsonl"));
        assert_eq!(std::fs::read(path).unwrap(), b"{\"x\":1}\n");
    }

    #[tokio::test(start_paused = true)]
    async fn a_missing_object_fails_after_every_try() {
        let objects = Objects::default();
        let store = MemoryStore::new(&objects, &[UPLOAD], false);
        let folder = tempfile::tempdir().unwrap();

        let error = stage(&store, &input(8), folder.path(), &LogWriter::discard()).await.unwrap_err();

        assert!(error.contains("doesn't exist"), "{error}");
        assert!(!folder.path().join("input/train.jsonl").exists());
    }

    #[tokio::test(start_paused = true)]
    async fn the_wrong_size_is_refused() {
        let objects = Objects::default();
        objects.lock().unwrap().insert(format!("{UPLOAD}train.jsonl"), b"short".to_vec());
        let store = MemoryStore::new(&objects, &[UPLOAD], false);
        let folder = tempfile::tempdir().unwrap();

        let error = stage(&store, &input(8), folder.path(), &LogWriter::discard()).await.unwrap_err();

        assert_eq!(error, "the input is 5 bytes, not the 8 expected");
    }

    #[test]
    fn only_safe_names_and_whole_prefixes_pass() {
        assert!(check(&input(1)).is_ok());
        assert!(check(&InputRef { name: "../etc/passwd".to_owned(), ..input(1) }).is_err());
        assert!(check(&InputRef { name: "..".to_owned(), ..input(1) }).is_err());
        assert!(check(&InputRef { name: "a b".to_owned(), ..input(1) }).is_err());
        assert!(check(&InputRef { prefix: "uploads/x".to_owned(), ..input(1) }).is_err());
        assert!(check(&input(0)).is_err());
    }
}
