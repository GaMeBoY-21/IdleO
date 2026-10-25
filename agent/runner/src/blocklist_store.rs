//! The blocklist the runner judges sessions by (ADR 0038), and keeping it up to date.
//!
//! Two copies can exist, each a file with its signature beside it (`<file>.sig`, base64 text as `idleo-release sign` writes it): the one that ships in the distribution
//! (`/usr/local/share/idleo/blocklist.json`), and the newest the agent has handed over, which the runner keeps as its **last
//! good copy** (`blocklist/current.json` in its state folder) so it survives a restart. At start the newer of the two that
//! verify is used.
//!
//! **A list is used only if its signature verifies** against the release key in the distribution
//! (`/usr/local/share/idleo/release.pub`), whoever handed it over: the agent has checked it too, and the runner does not
//! take its word. With no list that verifies the runner has none and says so (an empty version, the self-test's
//! `abuse_blocklist` check fails), and it starts no job (`sandbox_not_ready`) rather than judge sessions by nothing.
//!
//! A verified file is then read with [`Blocklist::parse`] (refusing what can't be matched safely or isn't sourced), and one
//! that is not newer than the one in use is refused. A version is a date, or the time the list was issued, and sorts as text
//! ([`crate::abuse`]).

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};

use base64::Engine;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};

use crate::abuse::Blocklist;

/// Where the distribution keeps its copy.
pub const DISTRO_COPY: &str = "/usr/local/share/idleo/blocklist.json";

/// Where the distribution keeps the release key (base64 of the raw Ed25519 public key, as `release.pub` is).
pub const RELEASE_KEY: &str = "/usr/local/share/idleo/release.pub";

/// The list in use.
pub struct BlocklistStore {
    current: Mutex<Arc<Blocklist>>,
    saved: PathBuf,
    distro: PathBuf,
    key: PathBuf,
}

/// The signature file beside `path`: `blocklist.json.sig`.
fn signature_of(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(".sig");
    PathBuf::from(name)
}

impl BlocklistStore {
    /// A store that has loaded nothing yet: [`Self::load`] does. Lists are verified against the key in the file `key`.
    #[must_use]
    pub fn new(saved: PathBuf, distro: PathBuf, key: PathBuf) -> Self {
        Self { current: Mutex::new(Arc::new(Blocklist::empty())), saved, distro, key }
    }

    /// The release key, read each time so that a missing or unreadable key refuses every list with its reason.
    fn release_key(&self) -> Result<VerifyingKey, String> {
        let text = std::fs::read_to_string(&self.key)
            .map_err(|e| format!("the release key {} can't be read: {e}", self.key.display()))?;
        let raw = base64::engine::general_purpose::STANDARD
            .decode(text.trim())
            .map_err(|_| format!("the release key {} isn't base64", self.key.display()))?;
        let raw: [u8; 32] =
            raw.try_into().map_err(|_| format!("the release key {} isn't 32 bytes", self.key.display()))?;
        VerifyingKey::from_bytes(&raw).map_err(|_| format!("the release key {} isn't a key", self.key.display()))
    }

    /// Whether `signature` is the release key's over `file`.
    fn verify(&self, file: &str, signature: &[u8]) -> Result<(), String> {
        let key = self.release_key()?;
        let signature =
            Signature::from_slice(signature).map_err(|_| "the blocklist has no valid signature".to_owned())?;
        key.verify(file.as_bytes(), &signature)
            .map_err(|_| "the blocklist's signature doesn't verify against the release key".to_owned())
    }

    /// Reads a file and its signature, and returns the list only if the signature verifies.
    fn read_verified(&self, path: &Path) -> Result<Blocklist, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("{} can't be read: {e}", path.display()))?;
        let signature = std::fs::read_to_string(signature_of(path))
            .map_err(|e| format!("{} can't be read: {e}", signature_of(path).display()))?;
        let signature = base64::engine::general_purpose::STANDARD
            .decode(signature.trim())
            .map_err(|_| format!("{} isn't base64", signature_of(path).display()))?;
        self.verify(&text, &signature).map_err(|e| format!("{}: {e}", path.display()))?;
        Blocklist::parse(&text).map_err(|e| format!("{}: {e}", path.display()))
    }

    /// Takes the newer of the distribution's copy and the last good copy, each only if its signature verifies. A saved copy
    /// that no longer does is ignored (and said so): the distribution's copy is there to fall back on.
    ///
    /// # Errors
    /// If neither verifies, the reason for the distribution's copy. The runner then holds no list.
    pub fn load(&self) -> Result<String, String> {
        let distro = self.read_verified(&self.distro);
        let saved = self.read_verified(&self.saved).map_err(|why| {
            if self.saved.exists() {
                tracing::warn!(path = %self.saved.display(), %why, "the last good blocklist can't be used; using the distribution's copy");
            }
            why
        });
        let chosen = match (distro, saved) {
            (Ok(distro), Ok(saved)) if saved.version() > distro.version() => saved,
            (Ok(distro), _) => distro,
            (Err(_), Ok(saved)) => saved,
            (Err(why), Err(_)) => return Err(format!("no blocklist: {why}")),
        };
        let version = chosen.version().to_owned();
        *self.current.lock().unwrap_or_else(PoisonError::into_inner) = Arc::new(chosen);
        Ok(version)
    }

    /// The list in use.
    #[must_use]
    pub fn get(&self) -> Arc<Blocklist> {
        Arc::clone(&self.current.lock().unwrap_or_else(PoisonError::into_inner))
    }

    /// Takes a newer list: refused if its signature doesn't verify, it can't be matched safely or it isn't newer than the one
    /// in use; otherwise written, with its signature, as the last good copy (a whole file or none, readable only by the
    /// runner) and put in use. A list with the version in use is not an error, so a resend changes nothing. Returns the
    /// version in use.
    ///
    /// # Errors
    /// Why it was refused, or couldn't be kept.
    pub fn update(&self, file: &str, signature: &[u8]) -> Result<String, String> {
        self.verify(file, signature)?;
        let incoming = Blocklist::parse(file)?;
        let in_use = self.get();
        if incoming.version() == in_use.version() {
            return Ok(in_use.version().to_owned());
        }
        if incoming.version() < in_use.version() {
            return Err(format!(
                "the blocklist {} is older than the one in use, {}",
                incoming.version(),
                in_use.version()
            ));
        }
        // The signature first: a crash between the two leaves a pair that doesn't verify, which the next start ignores.
        let encoded = format!("{}\n", base64::engine::general_purpose::STANDARD.encode(signature));
        keep(&signature_of(&self.saved), encoded.as_bytes())?;
        keep(&self.saved, file.as_bytes())?;
        let version = incoming.version().to_owned();
        *self.current.lock().unwrap_or_else(PoisonError::into_inner) = Arc::new(incoming);
        tracing::info!(%version, "a newer abuse blocklist is in use");
        Ok(version)
    }
}

/// Writes `bytes` beside `path` and renames it into place, so a crash leaves the old copy or the new one, never half of one.
fn keep(path: &Path, bytes: &[u8]) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let folder = path.parent().ok_or_else(|| "the blocklist has nowhere to be kept".to_owned())?;
    std::fs::create_dir_all(folder).map_err(|e| format!("{} can't be made: {e}", folder.display()))?;
    let partial = path.with_extension("partial");
    let mut out = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&partial)
        .map_err(|e| format!("{} can't be written: {e}", partial.display()))?;
    out.write_all(bytes).and_then(|()| out.sync_all()).map_err(|e| format!("the blocklist can't be written: {e}"))?;
    std::fs::rename(&partial, path).map_err(|e| format!("the blocklist can't be put in place: {e}"))
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::{Signer, SigningKey};

    use super::*;

    fn list(version: &str, extra_name: &str) -> String {
        let names = if extra_name.is_empty() {
            String::new()
        } else {
            format!(r#"{{"name":"{extra_name}","kind":"miner","source":"https://example.org/{extra_name}"}}"#)
        };
        format!(r#"{{"version":"{version}","names":[{names}],"hashes":[],"pools":[]}}"#)
    }

    fn signing() -> SigningKey {
        SigningKey::from_bytes(&[5; 32])
    }

    fn sign(file: &str) -> Vec<u8> {
        signing().sign(file.as_bytes()).to_bytes().to_vec()
    }

    /// A signature as `idleo-release sign` writes it to a `.sig` file.
    fn sig_file(signature: &[u8]) -> String {
        format!("{}\n", base64::engine::general_purpose::STANDARD.encode(signature))
    }

    struct Rig {
        dir: tempfile::TempDir,
    }

    impl Rig {
        /// A distribution with `distro` as its list, signed by the release key.
        fn new(distro: &str) -> Self {
            let rig = Self::unsigned(distro);
            std::fs::write(rig.dir.path().join("distro.json.sig"), sig_file(&sign(distro))).unwrap();
            rig
        }

        /// A distribution whose list has no signature.
        fn unsigned(distro: &str) -> Self {
            let dir = tempfile::tempdir().unwrap();
            std::fs::write(dir.path().join("distro.json"), distro).unwrap();
            let public = base64::engine::general_purpose::STANDARD.encode(signing().verifying_key().to_bytes());
            std::fs::write(dir.path().join("release.pub"), format!("{public}\n")).unwrap();
            Self { dir }
        }

        fn store(&self) -> BlocklistStore {
            BlocklistStore::new(
                self.dir.path().join("blocklist/current.json"),
                self.dir.path().join("distro.json"),
                self.dir.path().join("release.pub"),
            )
        }
    }

    fn update(store: &BlocklistStore, file: &str) -> Result<String, String> {
        store.update(file, &sign(file))
    }

    #[test]
    fn with_nothing_newer_the_distributions_own_copy_is_used() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        assert_eq!(store.get().version(), "", "nothing is loaded before load()");

        assert_eq!(store.load().unwrap(), "2026-10-22");
        assert_eq!(store.get().counts(), (1, 0, 0));
    }

    #[test]
    fn a_newer_list_is_used_at_once_and_kept_with_its_signature_for_the_next_start() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();

        assert_eq!(update(&store, &list("2026-10-25T09:30:00Z", "xmrig")).unwrap(), "2026-10-25T09:30:00Z");
        assert_eq!(store.get().version(), "2026-10-25T09:30:00Z");

        // A restarted runner finds the last good copy, verified again.
        let again = rig.store();
        assert_eq!(again.load().unwrap(), "2026-10-25T09:30:00Z");
        for name in ["current.json", "current.json.sig"] {
            let mode = std::os::unix::fs::PermissionsExt::mode(
                &std::fs::metadata(rig.dir.path().join("blocklist").join(name)).unwrap().permissions(),
            );
            assert_eq!(mode & 0o077, 0, "only the runner can read {name}");
        }
    }

    #[test]
    fn an_older_list_is_refused_and_a_resend_changes_nothing() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();
        update(&store, &list("2026-10-25T09:30:00Z", "xmrig")).unwrap();

        let refused = update(&store, &list("2026-10-23", "xmrig")).unwrap_err();
        assert!(refused.contains("older than the one in use"), "{refused}");
        assert_eq!(store.get().version(), "2026-10-25T09:30:00Z");
        assert_eq!(update(&store, &list("2026-10-25T09:30:00Z", "xmrig")).unwrap(), "2026-10-25T09:30:00Z");
    }

    #[test]
    fn a_list_without_a_signature_that_verifies_is_refused_and_leaves_the_one_in_use() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();
        let newer = list("2026-10-26", "newminer");

        let unsigned = store.update(&newer, &[]).unwrap_err();
        assert!(unsigned.contains("no valid signature"), "{unsigned}");
        let foreign = SigningKey::from_bytes(&[6; 32]).sign(newer.as_bytes()).to_bytes();
        assert!(store.update(&newer, &foreign).unwrap_err().contains("doesn't verify"));
        let tampered = list("2026-10-26", "othername");
        assert!(store.update(&tampered, &sign(&newer)).unwrap_err().contains("doesn't verify"));

        assert_eq!(store.get().version(), "2026-10-22");
        assert!(!rig.dir.path().join("blocklist/current.json").exists(), "a refused list is never kept");
    }

    #[test]
    fn a_list_that_cannot_be_matched_safely_is_refused_even_when_signed() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();
        let unsourced = r#"{"version":"2026-10-26","names":[{"name":"x","kind":"miner","source":"http://no"}],"hashes":[],"pools":[]}"#;

        assert!(update(&store, unsourced).is_err());
        assert!(update(&store, "not json").is_err());
        assert_eq!(store.get().version(), "2026-10-22");
        assert!(!rig.dir.path().join("blocklist/current.json").exists(), "a refused list is never kept");
    }

    #[test]
    fn a_new_entry_changes_what_a_process_is_judged_by() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();
        let process = crate::abuse::ProcessSeen {
            pid: 1,
            started: 1,
            comm: "newminer".to_owned(),
            exe_name: "newminer".to_owned(),
            sha256: None,
        };
        assert!(store.get().check(&process).is_none());

        update(&store, &list("2026-10-25T09:30:00Z", "newminer")).unwrap();

        assert!(store.get().check(&process).is_some());
    }

    #[test]
    fn a_saved_copy_that_is_broken_unsigned_or_older_than_the_distributions_is_not_used() {
        let rig = Rig::new(&list("2026-11-01", "xmrig"));
        let saved = rig.dir.path().join("blocklist/current.json");
        std::fs::create_dir_all(saved.parent().unwrap()).unwrap();
        let old = list("2026-10-25T09:30:00Z", "newminer");
        std::fs::write(&saved, &old).unwrap();
        std::fs::write(signature_of(&saved), sig_file(&sign(&old))).unwrap();
        assert_eq!(
            rig.store().load().unwrap(),
            "2026-11-01",
            "a new distribution's copy is newer than an old download"
        );

        // A newer one, but edited on disk after it was signed.
        let newer = list("2027-01-01", "newminer");
        std::fs::write(&saved, list("2027-01-01", "othername")).unwrap();
        std::fs::write(signature_of(&saved), sig_file(&sign(&newer))).unwrap();
        assert_eq!(rig.store().load().unwrap(), "2026-11-01", "edited on disk, so not believed");

        std::fs::write(&saved, "garbage").unwrap();
        assert_eq!(rig.store().load().unwrap(), "2026-11-01");
    }

    #[test]
    fn a_distribution_copy_that_is_unsigned_or_signed_by_another_key_is_not_used() {
        let rig = Rig::unsigned(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        let refused = store.load().unwrap_err();
        assert!(refused.contains("distro.json.sig"), "{refused}");
        assert_eq!(store.get().version(), "", "an unverified list is no list");

        let foreign = SigningKey::from_bytes(&[6; 32]).sign(list("2026-10-22", "xmrig").as_bytes()).to_bytes();
        std::fs::write(rig.dir.path().join("distro.json.sig"), sig_file(&foreign)).unwrap();
        assert!(rig.store().load().unwrap_err().contains("doesn't verify"));
    }

    #[test]
    fn with_no_list_at_all_or_no_key_the_runner_says_so() {
        let dir = tempfile::tempdir().unwrap();
        let store = BlocklistStore::new(
            dir.path().join("current.json"),
            dir.path().join("missing.json"),
            dir.path().join("none.pub"),
        );

        let refused = store.load().unwrap_err();
        assert!(refused.contains("missing.json"), "{refused}");
        assert_eq!(store.get().version(), "");
        assert!(store.update("{}", &[0; 64]).unwrap_err().contains("release key"));
    }
}
