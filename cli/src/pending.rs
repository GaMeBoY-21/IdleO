//! The Idempotency-Key of a booking, kept in a file until the API answers,
//! so a dropped connection never books twice: running the same command again sends the same key, and the API returns
//! the session the first try made, or makes it once.

use std::fs;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use uuid::Uuid;

pub struct PendingKeys {
    dir: PathBuf,
}

impl PendingKeys {
    #[must_use]
    pub fn new(config_dir: &Path) -> Self {
        Self { dir: config_dir.join("pending") }
    }

    fn file(&self, request: &str) -> PathBuf {
        let hash = crate::hex::hex(&Sha256::digest(request.as_bytes()));
        self.dir.join(format!("{hash}.key"))
    }

    /// The key for this request: the one a first try left behind, else a new one, kept from now on.
    #[must_use]
    pub fn key_for(&self, request: &str) -> String {
        let file = self.file(request);
        if let Some(key) =
            fs::read_to_string(&file).ok().map(|text| text.trim().to_owned()).filter(|key| !key.is_empty())
        {
            return key;
        }
        let key = Uuid::now_v7().to_string();
        if fs::create_dir_all(&self.dir).is_ok() {
            let _ = fs::write(&file, &key);
        }
        key
    }

    /// The API has answered, so the key has done its job.
    pub fn answered(&self, request: &str) {
        let _ = fs::remove_file(self.file(request));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_same_request_keeps_its_key_until_it_is_answered() {
        let dir = tempfile::tempdir().unwrap();
        let keys = PendingKeys::new(dir.path());
        let first = keys.key_for("{\"a\":1}");
        assert_eq!(keys.key_for("{\"a\":1}"), first);
        assert_ne!(keys.key_for("{\"a\":2}"), first);
        keys.answered("{\"a\":1}");
        assert_ne!(keys.key_for("{\"a\":1}"), first);
    }

    #[test]
    fn a_key_lasts_across_runs() {
        let dir = tempfile::tempdir().unwrap();
        let first = PendingKeys::new(dir.path()).key_for("body");
        assert_eq!(PendingKeys::new(dir.path()).key_for("body"), first);
    }
}
