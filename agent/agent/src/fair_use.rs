//! Fair use of the owner's home internet (ADR 0038): how much of the upload a job may use.
//!
//! A job's upload is capped at half of what this laptop measured its own upload to be (the self-test's `storage_speed`,
//! which writes 32 MB to our storage), so the owner's own internet stays usable, and never under a floor that keeps
//! checkpoints moving (`min_upload_kbps` in `agent.toml`, 5 Mbit/s by default). Until the first measurement it is the floor.
//! The runner enforces it (`runner/src/fair.rs`); the agent only works it out, remembers it across restarts, tells the
//! runner at every start and tells the control plane, whose machine page says "Jobs use at most 25 Mbit/s of your upload".

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};

/// The floor of the cap, in kbit/s, when `agent.toml` doesn't say.
pub const DEFAULT_FLOOR_KBPS: u32 = 5_000;
/// The file in `data_dir` that keeps the last measured upload speed.
const FILE: &str = "upload-speed.txt";

/// The cap for a measured upload speed: half of it, never under `floor`; the floor when nothing was measured.
#[must_use]
pub fn cap_kbps(measured_kbps: u32, floor_kbps: u32) -> u32 {
    (measured_kbps / 2).max(floor_kbps)
}

/// This laptop's upload cap for jobs, shared by the self-test (which measures) and the runner link (which tells each session).
#[derive(Debug)]
pub struct UploadCap {
    floor_kbps: u32,
    measured_kbps: AtomicU32,
    file: Option<PathBuf>,
}

impl UploadCap {
    /// The cap with nothing measured and nothing kept: the floor.
    #[must_use]
    pub fn floor_only(floor_kbps: u32) -> Arc<Self> {
        Arc::new(Self { floor_kbps, measured_kbps: AtomicU32::new(0), file: None })
    }

    /// The cap, with the last measurement read from `data_dir` (none yet, or an unreadable file, is the floor).
    #[must_use]
    pub fn load(data_dir: &Path, floor_kbps: u32) -> Arc<Self> {
        let file = data_dir.join(FILE);
        let measured =
            std::fs::read_to_string(&file).ok().and_then(|text| text.trim().parse::<u32>().ok()).unwrap_or(0);
        Arc::new(Self { floor_kbps, measured_kbps: AtomicU32::new(measured), file: Some(file) })
    }

    /// The upload a job may use, in kbit/s.
    #[must_use]
    pub fn kbps(&self) -> u32 {
        cap_kbps(self.measured_kbps.load(Ordering::SeqCst), self.floor_kbps)
    }

    /// The floor, in kbit/s.
    #[must_use]
    pub fn floor_kbps(&self) -> u32 {
        self.floor_kbps
    }

    /// The last measured upload, in kbit/s; 0 before any.
    #[must_use]
    pub fn measured_kbps(&self) -> u32 {
        self.measured_kbps.load(Ordering::SeqCst)
    }

    /// A new measurement: used from the next session, and kept for the next start of the agent.
    pub fn measured(&self, kbps: u32) {
        self.measured_kbps.store(kbps, Ordering::SeqCst);
        if let Some(file) = &self.file
            && let Err(why) = std::fs::write(file, format!("{kbps}\n"))
        {
            tracing::warn!(%why, "the measured upload speed couldn't be kept");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_job_may_use_half_of_the_upload_and_never_less_than_the_floor() {
        assert_eq!(cap_kbps(50_000, 5_000), 25_000);
        assert_eq!(cap_kbps(8_000, 5_000), 5_000, "half of a slow line is under the floor");
        assert_eq!(cap_kbps(0, 5_000), 5_000, "nothing measured yet");
        assert_eq!(cap_kbps(1_000_000, 5_000), 500_000);
    }

    #[test]
    fn a_measurement_is_kept_for_the_next_start_and_a_bad_file_is_the_floor() {
        let folder = tempfile::tempdir().unwrap();
        let first = UploadCap::load(folder.path(), 5_000);
        assert_eq!(first.kbps(), 5_000);
        first.measured(60_000);
        assert_eq!((first.kbps(), first.measured_kbps()), (30_000, 60_000));
        assert_eq!(UploadCap::load(folder.path(), 5_000).kbps(), 30_000, "read back by the next start");
        std::fs::write(folder.path().join("upload-speed.txt"), "fast\n").unwrap();
        assert_eq!(UploadCap::load(folder.path(), 5_000).kbps(), 5_000);
        assert_eq!(UploadCap::floor_only(7_000).kbps(), 7_000);
    }
}
