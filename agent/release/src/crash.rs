//! Telling a program that crashes now and then from one that keeps crashing (ADR 0061).
//!
//! The launcher restarts a crashed agent or tray with backoff, as it always has. After [`CRASHES`] crashes of the same
//! program within [`WITHIN`], it also writes `crash-loop.json`, which the tray reads to show a problem state with "Send
//! diagnostics" (and which the diagnostics zip includes). The file is deleted once the program has stayed up for
//! [`HEALTHY_FOR`]. The watch is pure: it is given the times.

use std::collections::VecDeque;
use std::fs;
use std::io;
use std::path::Path;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use time::OffsetDateTime;

/// How many crashes are a loop.
pub const CRASHES: usize = 3;
/// How short a time they must happen in.
pub const WITHIN: Duration = Duration::from_mins(10);
/// How long a program must stay up for the report to be withdrawn.
pub const HEALTHY_FOR: Duration = Duration::from_mins(10);

/// What the launcher saw.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CrashReport {
    /// Which program keeps crashing: `idleo-hostd` or `idleo-tray`.
    pub program: String,
    /// How many times it crashed in the last ten minutes.
    pub crashes: u32,
    /// When the latest crash was.
    #[serde(with = "time::serde::rfc3339")]
    pub last_at: OffsetDateTime,
}

impl CrashReport {
    /// Keeps the report: a temporary file, then a rename.
    ///
    /// # Errors
    /// If it can't be written.
    pub fn save(&self, path: &Path) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let temporary = path.with_extension("json.tmp");
        fs::write(&temporary, serde_json::to_vec_pretty(self).map_err(io::Error::other)?)?;
        fs::rename(&temporary, path)
    }

    /// The report, if the launcher wrote one and it still stands.
    #[must_use]
    pub fn load(path: &Path) -> Option<Self> {
        serde_json::from_slice(&fs::read(path).ok()?).ok()
    }

    /// Withdraws the report.
    ///
    /// # Errors
    /// If it exists and can't be deleted.
    pub fn clear(path: &Path) -> io::Result<()> {
        match fs::remove_file(path) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}

/// The crashes of one program, remembered for [`WITHIN`].
#[derive(Debug, Default, Clone)]
pub struct CrashWatch {
    crashes: VecDeque<OffsetDateTime>,
    up_since: Option<OffsetDateTime>,
}

impl CrashWatch {
    /// The program crashed at `now`. Returns how many crashes it has had within the last ten minutes, if that is a loop
    /// ([`CRASHES`] or more).
    pub fn crashed(&mut self, now: OffsetDateTime) -> Option<u32> {
        self.up_since = None;
        self.crashes.push_back(now);
        while self.crashes.front().is_some_and(|at| now - *at > WITHIN) {
            self.crashes.pop_front();
        }
        (self.crashes.len() >= CRASHES).then(|| u32::try_from(self.crashes.len()).unwrap_or(u32::MAX))
    }

    /// The program started at `now`.
    pub fn started(&mut self, now: OffsetDateTime) {
        self.up_since = Some(now);
    }

    /// Whether the program has been up long enough at `now` for a report of a loop to be withdrawn.
    #[must_use]
    pub fn healthy(&self, now: OffsetDateTime) -> bool {
        self.up_since.is_some_and(|since| now - since >= HEALTHY_FOR)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(minutes: u64, seconds: u64) -> OffsetDateTime {
        OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap() + Duration::from_secs(minutes * 60 + seconds)
    }

    #[test]
    fn three_crashes_in_ten_minutes_are_a_loop_and_two_are_not() {
        let mut watch = CrashWatch::default();
        assert_eq!(watch.crashed(at(0, 0)), None);
        assert_eq!(watch.crashed(at(2, 0)), None);
        assert_eq!(watch.crashed(at(9, 59)), Some(3));
        assert_eq!(watch.crashed(at(10, 30)), Some(3), "the first has gone, a fourth has come");
    }

    #[test]
    fn crashes_spread_over_more_than_ten_minutes_never_make_a_loop() {
        let mut watch = CrashWatch::default();
        for minute in [0, 6, 12, 18, 24, 30] {
            assert_eq!(watch.crashed(at(minute, 0)), None, "one every six minutes");
        }
    }

    #[test]
    fn a_program_that_stays_up_ten_minutes_is_healthy_again() {
        let mut watch = CrashWatch::default();
        watch.crashed(at(0, 0));
        assert!(!watch.healthy(at(1, 0)), "not started yet");
        watch.started(at(1, 0));
        assert!(!watch.healthy(at(10, 59)));
        assert!(watch.healthy(at(11, 0)));
        watch.crashed(at(11, 30));
        assert!(!watch.healthy(at(30, 0)), "a crash starts it over until it is started again");
    }

    #[test]
    fn the_report_is_kept_read_and_withdrawn() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("crash-loop.json");
        assert_eq!(CrashReport::load(&path), None);
        let report = CrashReport { program: "idleo-hostd".into(), crashes: 3, last_at: at(5, 0) };
        report.save(&path).unwrap();
        assert_eq!(CrashReport::load(&path), Some(report));
        CrashReport::clear(&path).unwrap();
        CrashReport::clear(&path).unwrap();
        assert_eq!(CrashReport::load(&path), None);
    }
}
