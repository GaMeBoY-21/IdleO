//! The disk the Linux space may use (ADR 0061): the host's limit, held to what the scheduler hears and what the window
//! shows.
//!
//! The control plane offers a session only to a host with room for its files (`free_disk_mb` in every heartbeat). With a
//! limit, the room it hears of is the laptop's own free space held to what the Linux space may still grow into: the limit
//! less what it already uses. At the limit the number is zero, no session is offered, and the host is told (auto-pause's
//! "disk limit" reason) rather than left wondering. Old job images the host no longer needs are removed by care before the
//! limit is reached.

use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use crate::disk::FreeDisk;
use crate::host::Host;

/// How long a measurement of what the Linux space uses is kept: walking its folder is cheap, not free.
const MEASURED_FOR: Duration = Duration::from_secs(30);

/// How much disk IdleO's Linux space uses now.
pub trait UsedSpace: Send + Sync {
    /// In MB.
    fn used_mb(&self) -> u64;
}

/// The size of a folder's files, measured at most every [`MEASURED_FOR`].
pub struct FolderUsed {
    folder: PathBuf,
    measured: Mutex<Option<(Instant, u64)>>,
}

impl FolderUsed {
    /// Measures `folder` (the Linux space's: `wsl\<distro version>` and its siblings).
    #[must_use]
    pub fn new(folder: PathBuf) -> Self {
        Self { folder, measured: Mutex::new(None) }
    }
}

impl UsedSpace for FolderUsed {
    fn used_mb(&self) -> u64 {
        let mut measured = self.measured.lock().unwrap_or_else(PoisonError::into_inner);
        if let Some((at, mb)) = *measured
            && at.elapsed() < MEASURED_FOR
        {
            return mb;
        }
        let mb = folder_bytes(&self.folder) / (1024 * 1024);
        *measured = Some((Instant::now(), mb));
        mb
    }
}

/// The bytes in every file under `folder`; a folder that isn't there holds none.
#[must_use]
pub fn folder_bytes(folder: &Path) -> u64 {
    let Ok(entries) = std::fs::read_dir(folder) else { return 0 };
    entries
        .filter_map(Result::ok)
        .map(|entry| match entry.metadata() {
            Ok(meta) if meta.is_dir() => folder_bytes(&entry.path()),
            Ok(meta) => meta.len(),
            Err(_) => 0,
        })
        .sum()
}

/// The free disk the scheduler hears of: the laptop's own, held to the host's limit.
pub struct LimitedDisk {
    real: Arc<dyn FreeDisk>,
    used: Arc<dyn UsedSpace>,
    host: Arc<Host>,
}

impl LimitedDisk {
    /// `real` is the laptop's free space; `used` what the Linux space holds; the limit is the host's setting.
    #[must_use]
    pub fn new(real: Arc<dyn FreeDisk>, used: Arc<dyn UsedSpace>, host: Arc<Host>) -> Self {
        Self { real, used, host }
    }
}

impl FreeDisk for LimitedDisk {
    fn free_mb(&self) -> Option<i32> {
        let free = self.real.free_mb()?;
        Some(self.host.settings().limits.disk_to_offer_mb(free, self.used.used_mb()))
    }
}

/// Tells the host, every `every`, how full the laptop's disk is and whether the Linux space is at its limit, for the
/// window's problem line and the "disk limit" pause. Runs for the life of the agent.
pub async fn watch(host: Arc<Host>, real: Arc<dyn FreeDisk>, used: Arc<dyn UsedSpace>, every: Duration) {
    let mut ticks = tokio::time::interval(every);
    ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        ticks.tick().await;
        let free = real.free_mb();
        let cap_mb = u64::from(host.settings().limits.max_disk_gb) * 1024;
        host.set_disk(free, used.used_mb() >= cap_mb);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::disk::FixedDisk;
    use crate::host::testing::ready_host;

    struct Used(u64);

    impl UsedSpace for Used {
        fn used_mb(&self) -> u64 {
            self.0
        }
    }

    #[test]
    fn a_folders_files_are_added_up_and_a_missing_folder_holds_none() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("wsl/0.1.0")).unwrap();
        std::fs::write(dir.path().join("wsl/0.1.0/ext4.vhdx"), vec![0u8; 3 * 1024 * 1024]).unwrap();
        std::fs::write(dir.path().join("wsl/other"), vec![0u8; 1024 * 1024]).unwrap();
        assert_eq!(folder_bytes(&dir.path().join("wsl")), 4 * 1024 * 1024);
        assert_eq!(FolderUsed::new(dir.path().join("wsl")).used_mb(), 4);
        assert_eq!(folder_bytes(&dir.path().join("nothing")), 0);
    }

    #[test]
    fn the_scheduler_hears_the_laptops_room_held_to_what_the_linux_space_may_still_use() {
        let host = ready_host();
        let mut settings = host.settings();
        settings.limits.max_disk_gb = 40;
        host.set_settings(settings).unwrap();
        let disk = LimitedDisk::new(Arc::new(FixedDisk(300_000)), Arc::new(Used(10 * 1024)), Arc::clone(&host));
        assert_eq!(disk.free_mb(), Some(30 * 1024));
        let at_limit = LimitedDisk::new(Arc::new(FixedDisk(300_000)), Arc::new(Used(40 * 1024)), Arc::clone(&host));
        assert_eq!(at_limit.free_mb(), Some(0), "no session is offered at the limit");
        let tight = LimitedDisk::new(Arc::new(FixedDisk(5_000)), Arc::new(Used(0)), host);
        assert_eq!(tight.free_mb(), Some(5_000), "the laptop's own free space is less than the limit");
    }

    #[tokio::test(start_paused = true)]
    async fn the_host_is_told_when_the_limit_is_reached_and_when_the_laptop_is_nearly_full() {
        let host = ready_host();
        let watching = tokio::spawn(watch(
            Arc::clone(&host),
            Arc::new(FixedDisk(8 * 1024)),
            Arc::new(Used(41 * 1024)),
            Duration::from_secs(1),
        ));
        tokio::time::sleep(Duration::from_secs(2)).await;
        let status = host.status();
        assert_eq!(status.problem, Some(idleo_release::channel::Problem::DiskNearlyFull), "8 GB free is under 10");
        watching.abort();
    }
}
