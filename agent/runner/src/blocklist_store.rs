//! The blocklist the runner judges sessions by (ADR 0038), and keeping it up to date.
//!
//! Two copies can exist: the one that ships in the distribution (`/usr/local/share/idleo/blocklist.json`), and the newest
//! the agent has handed over, which the runner keeps as its **last good copy** (`blocklist/current.json` in its state
//! folder) so it survives a restart. At start the newer of the two is used; with neither the runner has no list and says
//! so (an empty version, and the self-test's `abuse_blocklist` check fails) rather than judge sessions by nothing.
//!
//! The agent has already checked the file's signature against the release key; the runner reads it again with
//! [`Blocklist::parse`] (refusing what can't be matched safely or isn't sourced) and refuses one that is not newer than the
//! one in use. A version is a date, or the time the list was issued, and sorts as text ([`crate::abuse`]).

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};

use crate::abuse::Blocklist;

/// Where the distribution keeps its copy.
pub const DISTRO_COPY: &str = "/usr/local/share/idleo/blocklist.json";

/// The list in use.
pub struct BlocklistStore {
    current: Mutex<Arc<Blocklist>>,
    saved: PathBuf,
    distro: PathBuf,
}

impl BlocklistStore {
    /// A store that has loaded nothing yet: [`Self::load`] does.
    #[must_use]
    pub fn new(saved: PathBuf, distro: PathBuf) -> Self {
        Self { current: Mutex::new(Arc::new(Blocklist::empty())), saved, distro }
    }

    /// Takes the newer of the distribution's copy and the last good copy. A saved copy that no longer parses is ignored (and
    /// said so): the distribution's copy is always there to fall back on.
    ///
    /// # Errors
    /// If neither can be read, the reason for the distribution's copy.
    pub fn load(&self) -> Result<String, String> {
        let distro = read(&self.distro);
        let saved = read(&self.saved).map_err(|why| {
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

    /// Takes a newer list: refused if it can't be matched safely or isn't newer than the one in use; otherwise written as
    /// the last good copy (a whole file or none, readable only by the runner) and put in use. A list with the version in use
    /// is not an error, so a resend changes nothing. Returns the version in use.
    ///
    /// # Errors
    /// Why it was refused, or couldn't be kept.
    pub fn update(&self, file: &str) -> Result<String, String> {
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
        keep(&self.saved, file)?;
        let version = incoming.version().to_owned();
        *self.current.lock().unwrap_or_else(PoisonError::into_inner) = Arc::new(incoming);
        tracing::info!(%version, "a newer abuse blocklist is in use");
        Ok(version)
    }
}

fn read(path: &Path) -> Result<Blocklist, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{} can't be read: {e}", path.display()))?;
    Blocklist::parse(&text).map_err(|e| format!("{}: {e}", path.display()))
}

/// Writes `file` beside `path` and renames it into place, so a crash leaves the old copy or the new one, never half of one.
fn keep(path: &Path, file: &str) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let folder = path.parent().ok_or_else(|| "the blocklist has nowhere to be kept".to_owned())?;
    std::fs::create_dir_all(folder).map_err(|e| format!("{} can't be made: {e}", folder.display()))?;
    let partial = path.with_extension("json.partial");
    let mut out = std::fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(&partial)
        .map_err(|e| format!("{} can't be written: {e}", partial.display()))?;
    out.write_all(file.as_bytes())
        .and_then(|()| out.sync_all())
        .map_err(|e| format!("the blocklist can't be written: {e}"))?;
    std::fs::rename(&partial, path).map_err(|e| format!("the blocklist can't be put in place: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn list(version: &str, extra_name: &str) -> String {
        let names = if extra_name.is_empty() {
            String::new()
        } else {
            format!(r#"{{"name":"{extra_name}","kind":"miner","source":"https://example.org/{extra_name}"}}"#)
        };
        format!(r#"{{"version":"{version}","names":[{names}],"hashes":[],"pools":[]}}"#)
    }

    struct Rig {
        dir: tempfile::TempDir,
    }

    impl Rig {
        fn new(distro: &str) -> Self {
            let dir = tempfile::tempdir().unwrap();
            std::fs::write(dir.path().join("distro.json"), distro).unwrap();
            Self { dir }
        }

        fn store(&self) -> BlocklistStore {
            BlocklistStore::new(self.dir.path().join("blocklist/current.json"), self.dir.path().join("distro.json"))
        }
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
    fn a_newer_list_is_used_at_once_and_kept_for_the_next_start() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();

        assert_eq!(store.update(&list("2026-10-25T09:30:00Z", "xmrig")).unwrap(), "2026-10-25T09:30:00Z");
        assert_eq!(store.get().version(), "2026-10-25T09:30:00Z");

        // A restarted runner finds the last good copy.
        let again = rig.store();
        assert_eq!(again.load().unwrap(), "2026-10-25T09:30:00Z");
        let mode = std::os::unix::fs::PermissionsExt::mode(
            &std::fs::metadata(rig.dir.path().join("blocklist/current.json")).unwrap().permissions(),
        );
        assert_eq!(mode & 0o077, 0, "only the runner can read it");
    }

    #[test]
    fn an_older_list_is_refused_and_a_resend_changes_nothing() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();
        store.update(&list("2026-10-25T09:30:00Z", "xmrig")).unwrap();

        let refused = store.update(&list("2026-10-23", "xmrig")).unwrap_err();
        assert!(refused.contains("older than the one in use"), "{refused}");
        assert_eq!(store.get().version(), "2026-10-25T09:30:00Z");
        assert_eq!(store.update(&list("2026-10-25T09:30:00Z", "xmrig")).unwrap(), "2026-10-25T09:30:00Z");
    }

    #[test]
    fn a_list_that_cannot_be_matched_safely_is_refused_and_leaves_the_one_in_use() {
        let rig = Rig::new(&list("2026-10-22", "xmrig"));
        let store = rig.store();
        store.load().unwrap();
        let unsourced = r#"{"version":"2026-10-26","names":[{"name":"x","kind":"miner","source":"http://no"}],"hashes":[],"pools":[]}"#;

        assert!(store.update(unsourced).is_err());
        assert!(store.update("not json").is_err());
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

        store.update(&list("2026-10-25T09:30:00Z", "newminer")).unwrap();

        assert!(store.get().check(&process).is_some());
    }

    #[test]
    fn a_saved_copy_that_is_broken_or_older_than_the_distributions_is_not_used() {
        let rig = Rig::new(&list("2026-11-01", "xmrig"));
        let saved = rig.dir.path().join("blocklist/current.json");
        std::fs::create_dir_all(saved.parent().unwrap()).unwrap();
        std::fs::write(&saved, list("2026-10-25T09:30:00Z", "newminer")).unwrap();
        assert_eq!(
            rig.store().load().unwrap(),
            "2026-11-01",
            "a new distribution's copy is newer than an old download"
        );

        std::fs::write(&saved, "garbage").unwrap();
        assert_eq!(rig.store().load().unwrap(), "2026-11-01");
    }

    #[test]
    fn with_no_list_at_all_the_runner_says_so() {
        let dir = tempfile::tempdir().unwrap();
        let store = BlocklistStore::new(dir.path().join("current.json"), dir.path().join("missing.json"));

        let refused = store.load().unwrap_err();
        assert!(refused.starts_with("no blocklist"), "{refused}");
        assert_eq!(store.get().version(), "");
    }
}
