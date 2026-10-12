//! Where the API key is kept (docs/plan/product-surface.md section 4.1): `IDLEO_API_KEY` wins; otherwise the
//! operating system's keychain; otherwise a file in the config folder only its owner can read. A key is kept per API
//! address, so the local stack's key never goes to production.

use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::brand::BIN_NAME;
use crate::error::{CliError, Result};

/// A signed-in key and its id, which `logout` needs to revoke it. A key from the environment has no id.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Stored {
    pub key: String,
    pub id: Option<Uuid>,
}

/// Where a key was found.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Source {
    Environment,
    Keychain,
    File,
}

pub struct Keystore {
    api: String,
    dir: PathBuf,
    env_key: Option<String>,
    use_keychain: bool,
}

const FILE: &str = "credentials.json";

impl Keystore {
    /// The store for one API address. `env_key` is `IDLEO_API_KEY`; `use_keychain` is false when
    /// `IDLEO_NO_KEYCHAIN` is set (tests, CI and machines with no keychain).
    #[must_use]
    pub fn new(api: &str, dir: &Path, env_key: Option<String>, use_keychain: bool) -> Self {
        Self {
            api: api.trim_end_matches('/').to_owned(),
            dir: dir.to_owned(),
            env_key: env_key.filter(|key| !key.trim().is_empty()).map(|key| key.trim().to_owned()),
            use_keychain,
        }
    }

    /// The key to use, and where it came from.
    #[must_use]
    pub fn load(&self) -> Option<(Stored, Source)> {
        if let Some(key) = &self.env_key {
            return Some((Stored { key: key.clone(), id: None }, Source::Environment));
        }
        if let Some(stored) = self.keychain().and_then(|entry| entry.get_password().ok()).and_then(|json| parse(&json))
        {
            return Some((stored, Source::Keychain));
        }
        self.read_file().remove(&self.api).map(|stored| (stored, Source::File))
    }

    /// Keeps a key: in the keychain if there is one, else in the file. Says where it went.
    ///
    /// # Errors
    /// If neither the keychain nor the file can be written.
    pub fn save(&self, stored: &Stored) -> Result<Source> {
        let json = serde_json::to_string(stored).map_err(|e| CliError::failed(e.to_string()))?;
        if let Some(entry) = self.keychain()
            && entry.set_password(&json).is_ok()
        {
            // A copy left in the file from before would outlive a logout.
            self.forget_file();
            return Ok(Source::Keychain);
        }
        let mut all = self.read_file();
        all.insert(self.api.clone(), stored.clone());
        self.write_file(&all)?;
        Ok(Source::File)
    }

    /// Forgets the key everywhere it was kept; nothing to forget is fine.
    pub fn delete(&self) {
        if let Some(entry) = self.keychain() {
            let _ = entry.delete_credential();
        }
        self.forget_file();
    }

    fn keychain(&self) -> Option<keyring::Entry> {
        if !self.use_keychain {
            return None;
        }
        keyring::Entry::new(BIN_NAME, &self.api).ok()
    }

    fn path(&self) -> PathBuf {
        self.dir.join(FILE)
    }

    fn read_file(&self) -> BTreeMap<String, Stored> {
        fs::read_to_string(self.path()).ok().and_then(|text| serde_json::from_str(&text).ok()).unwrap_or_default()
    }

    fn forget_file(&self) {
        let mut all = self.read_file();
        if all.remove(&self.api).is_some() {
            if all.is_empty() {
                let _ = fs::remove_file(self.path());
            } else {
                let _ = self.write_file(&all);
            }
        }
    }

    fn write_file(&self, all: &BTreeMap<String, Stored>) -> Result<()> {
        let fail =
            |e: std::io::Error| CliError::failed(format!("couldn't save the key in {}: {e}", self.path().display()));
        fs::create_dir_all(&self.dir).map_err(fail)?;
        let text = serde_json::to_string_pretty(all).map_err(|e| CliError::failed(e.to_string()))?;
        write_private(&self.path(), text.as_bytes()).map_err(fail)
    }
}

fn parse(json: &str) -> Option<Stored> {
    serde_json::from_str(json).ok()
}

/// Writes a file only its owner can read: created with mode 0600, never briefly wider.
#[cfg(unix)]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
    let mut file = fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(path)?;
    file.set_permissions(fs::Permissions::from_mode(0o600))?;
    file.write_all(bytes)
}

/// Windows keeps a user's files in their own profile, which other users can't read.
#[cfg(not(unix))]
fn write_private(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    fs::write(path, bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store(dir: &Path, env: Option<&str>) -> Keystore {
        Keystore::new("http://localhost:8080/", dir, env.map(str::to_owned), false)
    }

    fn key(secret: &str) -> Stored {
        Stored { key: secret.to_owned(), id: Some(Uuid::nil()) }
    }

    #[test]
    fn nothing_is_stored_until_a_key_is_saved() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(store(dir.path(), None).load(), None);
    }

    #[test]
    fn a_saved_key_comes_back_from_the_file() {
        let dir = tempfile::tempdir().unwrap();
        let keys = store(dir.path(), None);
        assert_eq!(keys.save(&key("idleo_a")).unwrap(), Source::File);
        assert_eq!(keys.load(), Some((key("idleo_a"), Source::File)));
    }

    #[test]
    fn the_environment_wins_and_has_no_id() {
        let dir = tempfile::tempdir().unwrap();
        store(dir.path(), None).save(&key("idleo_stored")).unwrap();
        let (found, source) = store(dir.path(), Some(" idleo_env ")).load().unwrap();
        assert_eq!((found.key.as_str(), found.id, source), ("idleo_env", None, Source::Environment));
    }

    #[test]
    fn a_key_is_kept_for_its_own_api_address_only() {
        let dir = tempfile::tempdir().unwrap();
        store(dir.path(), None).save(&key("idleo_local")).unwrap();
        let production = Keystore::new("https://api.example", dir.path(), None, false);
        assert_eq!(production.load(), None);
        production.save(&key("idleo_production")).unwrap();
        assert_eq!(store(dir.path(), None).load().unwrap().0.key, "idleo_local");
    }

    #[test]
    fn deleting_forgets_the_key_and_only_that_one() {
        let dir = tempfile::tempdir().unwrap();
        store(dir.path(), None).save(&key("idleo_local")).unwrap();
        let production = Keystore::new("https://api.example", dir.path(), None, false);
        production.save(&key("idleo_production")).unwrap();

        store(dir.path(), None).delete();

        assert_eq!(store(dir.path(), None).load(), None);
        assert_eq!(production.load().unwrap().0.key, "idleo_production");
        production.delete();
        assert!(!dir.path().join(FILE).exists());
        store(dir.path(), None).delete();
    }

    #[cfg(unix)]
    #[test]
    fn the_file_is_readable_by_its_owner_alone() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        store(dir.path(), None).save(&key("idleo_a")).unwrap();
        let mode = fs::metadata(dir.path().join(FILE)).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o600);
    }
}
