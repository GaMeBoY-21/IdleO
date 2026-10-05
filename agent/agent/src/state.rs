//! The host's identity on disk, in `data_dir`:
//! - `host.key`: the private key, PKCS#8 PEM, readable only by the agent;
//! - `host.crt`: the host certificate, then the intermediate, PEM;
//! - `host.json`: the host id and the certificate's expiry, for people and scripts.
//!
//! The three files are replaced as one: new files are staged as `*.new`, `host.json.new` is
//! written last as the commit point, then each is renamed into place. A crash before the commit
//! point leaves the old identity; a crash after it is rolled forward on the next load. Nothing is
//! ever half-written in place.

use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;
use uuid::Uuid;

use crate::pki::{self, IssuedIdentity};

/// The private key's file name.
pub const KEY_FILE: &str = "host.key";
/// The certificate chain's file name.
pub const CERT_FILE: &str = "host.crt";
/// The host id and expiry file's name.
pub const INFO_FILE: &str = "host.json";
const FILES: [&str; 3] = [KEY_FILE, CERT_FILE, INFO_FILE];
const STAGED: &str = ".new";

/// A loaded, consistent identity.
pub struct Identity {
    /// From the certificate's `urn:idleo:host:` name.
    pub host_id: Uuid,
    /// When the host certificate expires.
    pub not_after: OffsetDateTime,
    /// The host certificate first, then the intermediate.
    pub chain: Vec<CertificateDer<'static>>,
    /// The private key.
    pub key: PrivateKeyDer<'static>,
}

impl fmt::Debug for Identity {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Identity")
            .field("host_id", &self.host_id)
            .field("not_after", &self.not_after)
            .finish_non_exhaustive()
    }
}

#[derive(Serialize, Deserialize)]
struct Info {
    host_id: Uuid,
    #[serde(with = "time::serde::rfc3339")]
    not_after: OffsetDateTime,
}

/// Why the identity couldn't be loaded or saved.
#[derive(Debug)]
pub enum StateError {
    /// No identity yet.
    NotEnrolled(PathBuf),
    /// The key file can be read by others (Unix mode shown in octal).
    KeyTooOpen(PathBuf, u32),
    /// A file is missing, unreadable or doesn't match the others.
    Corrupt(PathBuf, String),
    /// Reading or writing failed.
    Io(PathBuf, io::Error),
}

impl fmt::Display for StateError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotEnrolled(dir) => write!(
                f,
                "this host isn't enrolled yet (no identity in {}): run `idleo-agent enrol --code <code>` with the code from the web app",
                dir.display()
            ),
            Self::KeyTooOpen(path, mode) => write!(
                f,
                "{} is readable by other users (mode {mode:o}); it must be 600: run `chmod 600 {}`",
                path.display(),
                path.display()
            ),
            Self::Corrupt(path, why) => {
                write!(f, "{}: {why}; re-enrol with a new code (`enrol --force`)", path.display())
            }
            Self::Io(path, e) => write!(f, "{}: {e}", path.display()),
        }
    }
}

impl std::error::Error for StateError {}

fn io_error(path: &Path) -> impl FnOnce(io::Error) -> StateError + '_ {
    move |e| StateError::Io(path.to_owned(), e)
}

/// Whether any identity file exists in `dir`, after finishing or discarding an interrupted save.
///
/// # Errors
/// If recovering an interrupted save fails.
pub fn is_enrolled(dir: &Path) -> Result<bool, StateError> {
    recover(dir)?;
    Ok(FILES.iter().any(|name| dir.join(name).exists()))
}

/// Saves a new identity in place of any existing one. With `backup`, the existing files are first
/// moved to `backup-<UTC time>/` in `dir`, and that folder is returned.
///
/// # Errors
/// If a file can't be written or renamed.
pub fn save(dir: &Path, key_pem: &str, issued: &IssuedIdentity, backup: bool) -> Result<Option<PathBuf>, StateError> {
    create_dir(dir)?;
    recover(dir)?;
    let chain: Vec<_> = issued.chain.iter().map(|der| pem::Pem::new("CERTIFICATE", der.to_vec())).collect();
    let chain_pem = pem::encode_many_config(&chain, pem::EncodeConfig::new().set_line_ending(pem::LineEnding::LF));
    let info = serde_json::to_vec_pretty(&Info { host_id: issued.host_id, not_after: issued.not_after })
        .map_err(|e| StateError::Io(dir.join(INFO_FILE), io::Error::other(e)))?;
    write_new(&staged(dir, KEY_FILE), key_pem.as_bytes(), true)?;
    write_new(&staged(dir, CERT_FILE), chain_pem.as_bytes(), false)?;
    // The commit point: once host.json.new exists in full, the new identity wins.
    let temporary = dir.join(format!("{INFO_FILE}.tmp"));
    write_new(&temporary, &info, false)?;
    fs::rename(&temporary, staged(dir, INFO_FILE)).map_err(io_error(&temporary))?;
    sync_dir(dir)?;
    let backup_dir = if backup { back_up(dir)? } else { None };
    roll_forward(dir)?;
    Ok(backup_dir)
}

/// Loads the identity, checking the key file's permissions and that the files agree.
///
/// # Errors
/// If not enrolled, the key is readable by others, or a file is missing or inconsistent.
pub fn load(dir: &Path) -> Result<Identity, StateError> {
    if !is_enrolled(dir)? {
        return Err(StateError::NotEnrolled(dir.to_owned()));
    }
    let key_path = dir.join(KEY_FILE);
    check_private(&key_path)?;
    let key_pem = fs::read_to_string(&key_path).map_err(io_error(&key_path))?;
    let key = PrivatePkcs8KeyDer::from_pem_slice(key_pem.as_bytes())
        .map_err(|_| StateError::Corrupt(key_path.clone(), "not a PKCS#8 PEM private key".to_owned()))?;
    let public_key = pki::public_key_of(&key_pem)
        .map_err(|_| StateError::Corrupt(key_path.clone(), "not an ECDSA P-256 key".to_owned()))?;

    let cert_path = dir.join(CERT_FILE);
    let chain = CertificateDer::pem_file_iter(&cert_path)
        .and_then(Iterator::collect::<Result<Vec<_>, _>>)
        .map_err(|e| StateError::Corrupt(cert_path.clone(), e.to_string()))?;
    let leaf = chain.first().ok_or_else(|| StateError::Corrupt(cert_path.clone(), "no certificate".to_owned()))?;
    let identity = pki::read_identity(leaf).map_err(|e| StateError::Corrupt(cert_path.clone(), e.to_string()))?;
    if identity.public_key != public_key {
        return Err(StateError::Corrupt(cert_path, "the certificate isn't for host.key".to_owned()));
    }

    let info_path = dir.join(INFO_FILE);
    let info: Info = fs::read(&info_path).map_err(io_error(&info_path)).and_then(|bytes| {
        serde_json::from_slice(&bytes).map_err(|e| StateError::Corrupt(info_path.clone(), e.to_string()))
    })?;
    if info.host_id != identity.host_id {
        return Err(StateError::Corrupt(info_path, "the host id doesn't match the certificate".to_owned()));
    }
    Ok(Identity { host_id: identity.host_id, not_after: identity.not_after, chain, key: key.into() })
}

fn staged(dir: &Path, name: &str) -> PathBuf {
    dir.join(format!("{name}{STAGED}"))
}

/// Finishes a save that reached its commit point, or discards one that didn't.
fn recover(dir: &Path) -> Result<(), StateError> {
    if staged(dir, INFO_FILE).exists() {
        roll_forward(dir)
    } else {
        for name in [KEY_FILE, CERT_FILE] {
            let path = staged(dir, name);
            match fs::remove_file(&path) {
                Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(StateError::Io(path, e)),
                _ => {}
            }
        }
        Ok(())
    }
}

/// Renames the staged files into place, `host.json` last, so it can resume after a crash.
fn roll_forward(dir: &Path) -> Result<(), StateError> {
    for name in FILES {
        let from = staged(dir, name);
        if from.exists() {
            fs::rename(&from, dir.join(name)).map_err(io_error(&from))?;
        }
    }
    sync_dir(dir)
}

fn back_up(dir: &Path) -> Result<Option<PathBuf>, StateError> {
    let existing: Vec<_> = FILES.iter().filter(|name| dir.join(name).exists()).collect();
    if existing.is_empty() {
        return Ok(None);
    }
    let stamp = OffsetDateTime::now_utc().unix_timestamp_nanos();
    let backup = dir.join(format!("backup-{stamp}"));
    create_dir(&backup)?;
    for name in existing {
        fs::rename(dir.join(name), backup.join(name)).map_err(io_error(&backup))?;
    }
    sync_dir(dir)?;
    Ok(Some(backup))
}

/// Formats an expiry for logs and `host.json`.
#[must_use]
pub fn format_time(time: OffsetDateTime) -> String {
    time.format(&Rfc3339).unwrap_or_else(|_| time.to_string())
}

fn create_dir(dir: &Path) -> Result<(), StateError> {
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    std::os::unix::fs::DirBuilderExt::mode(&mut builder, 0o700);
    builder.create(dir).map_err(io_error(dir))
}

/// Writes a new file and flushes it to disk. A private file is created readable only by the
/// agent: mode 0600 on Unix, SYSTEM and Administrators only on Windows.
fn write_new(path: &Path, contents: &[u8], private: bool) -> Result<(), StateError> {
    match fs::remove_file(path) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(StateError::Io(path.to_owned(), e)),
        _ => {}
    }
    let mut file =
        if private { create_private(path) } else { OpenOptions::new().write(true).create_new(true).open(path) }
            .map_err(io_error(path))?;
    file.write_all(contents).and_then(|()| file.sync_all()).map_err(io_error(path))
}

#[cfg(unix)]
fn create_private(path: &Path) -> io::Result<File> {
    use std::os::unix::fs::OpenOptionsExt;
    OpenOptions::new().write(true).create_new(true).mode(0o600).open(path)
}

#[cfg(windows)]
fn create_private(path: &Path) -> io::Result<File> {
    agent_windows::create_private_file(path)
}

#[cfg(not(any(unix, windows)))]
fn create_private(path: &Path) -> io::Result<File> {
    OpenOptions::new().write(true).create_new(true).open(path)
}

/// Refuses a key file other users can read or write.
#[cfg(unix)]
fn check_private(path: &Path) -> Result<(), StateError> {
    use std::os::unix::fs::PermissionsExt;
    let mode = fs::metadata(path).map_err(io_error(path))?.permissions().mode() & 0o777;
    if mode & 0o077 != 0 { Err(StateError::KeyTooOpen(path.to_owned(), mode)) } else { Ok(()) }
}

/// On Windows the key file is created with its access list already restricted (see
/// `agent-windows`), so there's nothing to check here.
#[cfg(not(unix))]
#[allow(clippy::unnecessary_wraps)] // Same signature as the Unix check.
fn check_private(_path: &Path) -> Result<(), StateError> {
    Ok(())
}

#[cfg(unix)]
fn sync_dir(dir: &Path) -> Result<(), StateError> {
    File::open(dir).and_then(|d| d.sync_all()).map_err(io_error(dir))
}

/// Windows can't open a directory as a file to flush it; NTFS journals renames itself.
#[cfg(not(unix))]
#[allow(clippy::unnecessary_wraps)] // Same signature as the Unix version.
fn sync_dir(_dir: &Path) -> Result<(), StateError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_interrupted_save_before_the_commit_point_is_discarded() {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join(KEY_FILE), "old key").unwrap();
        fs::write(staged(dir.path(), KEY_FILE), "new key").unwrap();
        fs::write(staged(dir.path(), CERT_FILE), "new cert").unwrap();

        recover(dir.path()).unwrap();

        assert_eq!(fs::read_to_string(dir.path().join(KEY_FILE)).unwrap(), "old key");
        assert!(!staged(dir.path(), KEY_FILE).exists());
        assert!(!staged(dir.path(), CERT_FILE).exists());
    }

    #[test]
    fn an_interrupted_save_after_the_commit_point_is_finished() {
        let dir = tempfile::tempdir().unwrap();
        // The crash came after host.key was renamed into place, before host.crt.
        fs::write(dir.path().join(KEY_FILE), "new key").unwrap();
        fs::write(dir.path().join(CERT_FILE), "old cert").unwrap();
        fs::write(staged(dir.path(), CERT_FILE), "new cert").unwrap();
        fs::write(staged(dir.path(), INFO_FILE), "new info").unwrap();

        recover(dir.path()).unwrap();

        assert_eq!(fs::read_to_string(dir.path().join(KEY_FILE)).unwrap(), "new key");
        assert_eq!(fs::read_to_string(dir.path().join(CERT_FILE)).unwrap(), "new cert");
        assert_eq!(fs::read_to_string(dir.path().join(INFO_FILE)).unwrap(), "new info");
        assert!(FILES.iter().all(|name| !staged(dir.path(), name).exists()));
    }
}
