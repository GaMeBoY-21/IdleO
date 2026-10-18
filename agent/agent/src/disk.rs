//! Free disk space, for heartbeats (ADR 0022): sessions' scratch folders and staged inputs go under the agent's data
//! folder, so the scheduler offers a session only to a host with room for its inputs and its scratch disk. Read at
//! each heartbeat from the volume that holds the data folder. `--fake-free-disk-mb` reports a fixed amount instead,
//! and only with `--fake-gpu`: a real host always reports what it has.

use std::io;
use std::path::PathBuf;

/// Where free disk space comes from.
pub trait FreeDisk: Send + Sync {
    /// Free space in MiB; `None` when it can't be read, so the scheduler offers nothing rather than guessing.
    fn free_mb(&self) -> Option<i32>;
}

/// The free space this user can write on the volume holding `path`.
#[derive(Debug, Clone)]
pub struct SystemDisk {
    path: PathBuf,
}

impl SystemDisk {
    #[must_use]
    pub fn new(path: PathBuf) -> Self {
        Self { path }
    }
}

impl FreeDisk for SystemDisk {
    fn free_mb(&self) -> Option<i32> {
        match available_bytes(&self.path) {
            Ok(bytes) => Some(mib(bytes)),
            Err(error) => {
                tracing::warn!(%error, path = %self.path.display(), "couldn't read the free disk space");
                None
            }
        }
    }
}

/// A fixed amount, for `--fake-free-disk-mb` and tests.
#[derive(Debug, Clone, Copy)]
pub struct FixedDisk(pub i32);

impl FreeDisk for FixedDisk {
    fn free_mb(&self) -> Option<i32> {
        Some(self.0)
    }
}

/// Bytes as whole MiB, at most what an `int32` holds (2 PiB).
fn mib(bytes: u64) -> i32 {
    i32::try_from(bytes / (1024 * 1024)).unwrap_or(i32::MAX)
}

#[cfg(unix)]
/// Bytes this user may still write on the volume holding `path`.
///
/// # Errors
/// If it can't be read.
pub fn available_bytes(path: &std::path::Path) -> io::Result<u64> {
    let stats = rustix::fs::statvfs(path)?;
    Ok(stats.f_bavail.saturating_mul(stats.f_frsize))
}

#[cfg(windows)]
/// Bytes this user may still write on the volume holding `path`.
///
/// # Errors
/// If it can't be read.
pub fn available_bytes(path: &std::path::Path) -> io::Result<u64> {
    agent_windows::free_disk_space(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_data_folders_volume_has_some_free_space() {
        let folder = tempfile::tempdir().unwrap();
        let free = SystemDisk::new(folder.path().to_path_buf()).free_mb();
        assert!(free.is_some_and(|mb| mb > 0), "{free:?}");
    }

    #[test]
    fn a_missing_folder_reads_as_unknown_not_zero() {
        assert_eq!(SystemDisk::new(PathBuf::from("/no/such/folder/anywhere")).free_mb(), None);
    }

    #[test]
    fn bytes_round_down_to_whole_mib_and_never_overflow() {
        assert_eq!(mib(3 * 1024 * 1024 - 1), 2);
        assert_eq!(mib(u64::MAX), i32::MAX);
        assert_eq!(FixedDisk(40_960).free_mb(), Some(40_960));
    }
}
