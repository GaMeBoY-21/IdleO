//! Where an unfinished upload is remembered, so running the same command again carries on from the last part storage
//! took instead of starting over (ADR 0022): after Ctrl-C, a dropped connection or a closed laptop. One small file
//! for each set of files, in the config folder, named by the files' fingerprint; forgotten once the upload is ready.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Unfinished {
    pub upload_id: Option<Uuid>,
    /// The Idempotency-Key the upload was opened with: opening it again with the same key returns it as it stands.
    pub key: String,
    /// Each file's parts storage took, by path: part number to `ETag`.
    pub parts: BTreeMap<String, BTreeMap<u32, String>>,
}

pub struct ResumeStore {
    dir: PathBuf,
}

impl ResumeStore {
    #[must_use]
    pub fn new(config_dir: &Path) -> Self {
        Self { dir: config_dir.join("uploads") }
    }

    fn path(&self, fingerprint: &str) -> PathBuf {
        self.dir.join(format!("{fingerprint}.json"))
    }

    /// What's remembered for these files, if anything readable.
    #[must_use]
    pub fn read(&self, fingerprint: &str) -> Option<Unfinished> {
        serde_json::from_str(&fs::read_to_string(self.path(fingerprint)).ok()?).ok()
    }

    /// Remembers; every failure is swallowed, as remembering is a convenience: the next run starts over.
    pub fn write(&self, fingerprint: &str, unfinished: &Unfinished) {
        if fs::create_dir_all(&self.dir).is_ok()
            && let Ok(text) = serde_json::to_string(unfinished)
        {
            // Written to a side file and moved into place, so a crash never leaves half a record.
            let side = self.path(&format!("{fingerprint}.tmp"));
            if fs::write(&side, text).is_ok() {
                let _ = fs::rename(side, self.path(fingerprint));
            }
        }
    }

    pub fn forget(&self, fingerprint: &str) {
        let _ = fs::remove_file(self.path(fingerprint));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn what_was_remembered_comes_back() {
        let dir = tempfile::tempdir().unwrap();
        let store = ResumeStore::new(dir.path());
        assert_eq!(store.read("abc"), None);
        let mut unfinished = Unfinished { upload_id: Some(Uuid::nil()), key: "k".to_owned(), ..Unfinished::default() };
        unfinished.parts.entry("a.bin".to_owned()).or_default().insert(2, "\"etag-2\"".to_owned());
        store.write("abc", &unfinished);
        assert_eq!(store.read("abc"), Some(unfinished));
        store.forget("abc");
        assert_eq!(store.read("abc"), None);
        store.forget("abc");
    }

    #[test]
    fn a_damaged_record_is_as_good_as_none() {
        let dir = tempfile::tempdir().unwrap();
        let store = ResumeStore::new(dir.path());
        store.write("abc", &Unfinished::default());
        fs::write(dir.path().join("uploads/abc.json"), "{ not json").unwrap();
        assert_eq!(store.read("abc"), None);
    }
}
