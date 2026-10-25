//! The abuse blocklist the agent holds and hands to its job runner (ADR 0038).
//!
//! The control plane sends a newer list over the stream as an `UpdateBlocklist`: a file signed with the release key, with a
//! version of its own. This agent uses it only if the signature verifies against the key built into it and its version is
//! newer than the one it holds, keeps the last good one in its data folder, and gives it to the runner, which judges
//! sessions by the newest it has ([`crate::runner::SessionRunner::blocklist`]). A list that is tampered with, signed by
//! another key, or older (a replay) changes nothing.

use std::path::{Path, PathBuf};
use std::sync::{Mutex, PoisonError};

use agent_proto::v1::UpdateBlocklist;
use idleo_release::blocklist;

/// Checks a list against its signature, and says its version.
pub type Verify = Box<dyn Fn(&[u8], &[u8]) -> Result<String, blocklist::BlocklistError> + Send + Sync>;

const FILE: &str = "blocklist.json";
const SIGNATURE: &str = "blocklist.json.sig";

/// What the agent holds.
struct Held {
    version: String,
    file: String,
    signature: Vec<u8>,
}

/// The newest verified list, kept across restarts.
pub struct Keeper {
    dir: PathBuf,
    held: Mutex<Option<Held>>,
    /// Verifies a list: against the release key built into this build, or, in a test, against another.
    verify: Verify,
}

impl Keeper {
    /// The keeper for the agent whose data is in `dir`, holding the last good list it kept, if it still verifies.
    #[must_use]
    pub fn open(dir: &Path) -> Self {
        Self::with_verifier(dir, Box::new(blocklist::verify_with_baked_key))
    }

    /// As [`Self::open`], verifying with `verify`.
    #[must_use]
    pub fn with_verifier(dir: &Path, verify: Verify) -> Self {
        let keeper = Self { dir: dir.to_owned(), held: Mutex::new(None), verify };
        let held = std::fs::read(dir.join(FILE)).ok().zip(std::fs::read(dir.join(SIGNATURE)).ok()).and_then(
            |(file, signature)| {
                let version = (keeper.verify)(&file, &signature).ok()?;
                Some(Held { version, file: String::from_utf8(file).ok()?, signature })
            },
        );
        *keeper.held.lock().unwrap_or_else(PoisonError::into_inner) = held;
        keeper
    }

    /// The version held, or empty if none: what Hello tells the control plane.
    #[must_use]
    pub fn version(&self) -> String {
        self.held
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .as_ref()
            .map_or_else(String::new, |held| held.version.clone())
    }

    /// The list held, as signed, and its signature: what the runner is given, which verifies it again itself.
    #[must_use]
    pub fn signed(&self) -> Option<(String, Vec<u8>)> {
        self.held
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .as_ref()
            .map(|held| (held.file.clone(), held.signature.clone()))
    }

    /// Takes the control plane's list if it verifies, says what it is, and is newer than the one held; keeps it. Returns the
    /// list and its signature to hand to the runner, or `None` when it changes nothing (one already held, or a resend).
    ///
    /// # Errors
    /// Why it was refused: its signature, its version, or that it is older than the one held.
    pub fn offer(&self, update: &UpdateBlocklist) -> Result<Option<(String, Vec<u8>)>, String> {
        let version = (self.verify)(&update.blocklist, &update.signature).map_err(|e| e.to_string())?;
        if version != update.version {
            return Err(format!("the message says {} but the list is {version}", update.version));
        }
        let file = String::from_utf8(update.blocklist.clone()).map_err(|_| "the blocklist isn't text".to_owned())?;
        let mut held = self.held.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some(current) = held.as_ref() {
            if current.version == version {
                return Ok(None);
            }
            if !blocklist::is_newer(&version, &current.version) {
                return Err(format!("{version} is older than {}, which is held", current.version));
            }
        }
        self.keep(&update.signature, &update.blocklist)?;
        *held = Some(Held { version, file: file.clone(), signature: update.signature.clone() });
        Ok(Some((file, update.signature.clone())))
    }

    /// The signature first and the list second, each whole or not at all; a pair that doesn't match is not read back.
    fn keep(&self, signature: &[u8], file: &[u8]) -> Result<(), String> {
        for (name, bytes) in [(SIGNATURE, signature), (FILE, file)] {
            let partial = self.dir.join(format!("{name}.partial"));
            std::fs::write(&partial, bytes)
                .and_then(|()| std::fs::rename(&partial, self.dir.join(name)))
                .map_err(|e| format!("the blocklist couldn't be kept: {e}"))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::{Signer, SigningKey};

    use super::*;

    fn keys() -> (SigningKey, [u8; 32]) {
        let signing = SigningKey::from_bytes(&[3; 32]);
        let public = signing.verifying_key().to_bytes();
        (signing, public)
    }

    fn keeper(dir: &Path, public: [u8; 32]) -> Keeper {
        Keeper::with_verifier(dir, Box::new(move |bytes, signature| blocklist::verify(bytes, signature, &public)))
    }

    fn list(version: &str) -> Vec<u8> {
        format!(r#"{{"version":"{version}","names":[],"hashes":[],"pools":[]}}"#).into_bytes()
    }

    fn update(signing: &SigningKey, version: &str, file: &[u8]) -> UpdateBlocklist {
        UpdateBlocklist {
            version: version.to_owned(),
            blocklist: file.to_vec(),
            signature: signing.sign(file).to_bytes().to_vec(),
        }
    }

    #[test]
    fn a_newer_signed_list_is_taken_kept_and_handed_on() {
        let dir = tempfile::tempdir().unwrap();
        let (signing, public) = keys();
        let keeper = keeper(dir.path(), public);
        assert_eq!(keeper.version(), "");

        let file = list("2026-10-25T09:30:00Z");
        let handed = keeper.offer(&update(&signing, "2026-10-25T09:30:00Z", &file)).unwrap();

        let signature = signing.sign(&file).to_bytes().to_vec();
        assert_eq!(handed, Some((String::from_utf8(file).unwrap(), signature)));
        assert_eq!(keeper.version(), "2026-10-25T09:30:00Z");
        // A restarted agent finds it, verified again.
        assert_eq!(super::tests::keeper(dir.path(), public).version(), "2026-10-25T09:30:00Z");
    }

    #[test]
    fn a_tampered_list_or_one_signed_by_another_key_changes_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let (signing, public) = keys();
        let keeper = keeper(dir.path(), public);
        let mut tampered = update(&signing, "2026-10-25T09:30:00Z", &list("2026-10-25T09:30:00Z"));
        tampered.blocklist = list("2026-10-26T00:00:00Z");
        assert!(keeper.offer(&tampered).unwrap_err().contains("signature"));

        let other = SigningKey::from_bytes(&[4; 32]);
        assert!(keeper.offer(&update(&other, "2026-10-25T09:30:00Z", &list("2026-10-25T09:30:00Z"))).is_err());

        assert_eq!(keeper.version(), "");
        assert!(!dir.path().join(FILE).exists(), "nothing refused is ever kept");
    }

    #[test]
    fn an_older_list_is_refused_and_a_resend_changes_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let (signing, public) = keys();
        let keeper = keeper(dir.path(), public);
        keeper.offer(&update(&signing, "2026-10-25T09:30:00Z", &list("2026-10-25T09:30:00Z"))).unwrap();

        let older = update(&signing, "2026-10-24T00:00:00Z", &list("2026-10-24T00:00:00Z"));
        assert!(keeper.offer(&older).unwrap_err().contains("older"));
        let again = update(&signing, "2026-10-25T09:30:00Z", &list("2026-10-25T09:30:00Z"));
        assert_eq!(keeper.offer(&again).unwrap(), None);
        assert_eq!(keeper.version(), "2026-10-25T09:30:00Z");
    }

    #[test]
    fn a_message_whose_version_is_not_the_lists_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let (signing, public) = keys();
        let keeper = keeper(dir.path(), public);
        let lying = update(&signing, "2027-01-01", &list("2026-10-25T09:30:00Z"));
        assert!(keeper.offer(&lying).unwrap_err().contains("the message says"));
    }

    #[test]
    fn a_list_kept_on_disk_that_no_longer_verifies_is_not_trusted() {
        let dir = tempfile::tempdir().unwrap();
        let (signing, public) = keys();
        keeper(dir.path(), public)
            .offer(&update(&signing, "2026-10-25T09:30:00Z", &list("2026-10-25T09:30:00Z")))
            .unwrap();
        std::fs::write(dir.path().join(FILE), list("2026-12-31")).unwrap();

        assert_eq!(keeper(dir.path(), public).version(), "", "edited on disk, so not believed");
    }
}
