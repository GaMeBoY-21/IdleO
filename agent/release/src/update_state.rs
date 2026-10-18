//! An update's trial (ADR 0034), in `update-state.json`, shared by the agent and the launcher.
//!
//! 1. The old agent, once idle, unpacks the new version, passes its quick self-test there, writes `{trial: new,
//!    previous: old, outcome: trial}`, switches `current` and exits for the launcher to restart it.
//! 2. The new agent marks it `confirmed` once it has connected, been welcomed and passed `self-test --quick`, then
//!    reports `INSTALLED`.
//! 3. If the new agent crashes three times or isn't confirmed within five minutes, the launcher switches `current`
//!    back, marks it `rolled_back` with the reason, and starts the old one, which reports `ROLLED_BACK`.
//!
//! The file is deleted once the agent's report is acked. Every write is a temporary file and a rename.

use std::fs;
use std::io;
use std::path::Path;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use time::OffsetDateTime;

/// How long the new version has to confirm itself.
pub const TRIAL_LIMIT: Duration = Duration::from_mins(5);
/// How many crashes of the new version end its trial.
pub const TRIAL_CRASHES: u32 = 3;

/// Where a trial has got to.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "outcome", rename_all = "snake_case")]
pub enum Outcome {
    /// Running the new version, not confirmed yet.
    Trial,
    /// The new version confirmed itself.
    Confirmed,
    /// The new version failed its trial, and the old one is back.
    RolledBack {
        /// Why, for the admin console.
        reason: String,
    },
}

/// A distro change riding along with an update: the new one is imported beside the old, and the old unregistered only
/// once the trial is confirmed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DistroSwitch {
    /// The distro version before the update.
    pub previous: String,
    /// The distro version the update brought.
    pub trial: String,
}

/// An update's trial.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdateState {
    /// The new version.
    pub trial: String,
    /// The version it replaced, which a rollback goes back to.
    pub previous: String,
    /// When the trial started.
    #[serde(with = "time::serde::rfc3339")]
    pub started_at: OffsetDateTime,
    /// A distro change, if the update brought one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub distro: Option<DistroSwitch>,
    /// How many times the new version has crashed during the trial.
    #[serde(default)]
    pub crashes: u32,
    /// Where it has got to.
    #[serde(flatten)]
    pub outcome: Outcome,
}

impl UpdateState {
    /// Reads the trial, if there is one. A file that can't be read as one is treated as none.
    #[must_use]
    pub fn load(path: &Path) -> Option<Self> {
        let text = fs::read_to_string(path).ok()?;
        serde_json::from_str(&text).ok()
    }

    /// Writes the trial: a temporary file, then a rename.
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

    /// Deletes the trial, once its result has been reported.
    ///
    /// # Errors
    /// If it exists and can't be deleted.
    pub fn clear(path: &Path) -> io::Result<()> {
        match fs::remove_file(path) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }

    /// Whether the trial has run past [`TRIAL_LIMIT`] at `now`.
    #[must_use]
    pub fn timed_out(&self, now: OffsetDateTime) -> bool {
        self.outcome == Outcome::Trial && now - self.started_at >= TRIAL_LIMIT
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_trial_round_trips_and_times_out() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("update-state.json");
        assert_eq!(UpdateState::load(&path), None);
        let started = OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap();
        let state = UpdateState {
            trial: "0.2.0".into(),
            previous: "0.1.0".into(),
            started_at: started,
            distro: Some(DistroSwitch { previous: "0.1.0".into(), trial: "0.2.0".into() }),
            crashes: 0,
            outcome: Outcome::Trial,
        };
        state.save(&path).unwrap();
        assert_eq!(UpdateState::load(&path).as_ref(), Some(&state));
        assert!(!state.timed_out(started + Duration::from_secs(299)));
        assert!(state.timed_out(started + Duration::from_secs(300)));
        let rolled = UpdateState { outcome: Outcome::RolledBack { reason: "crashed 3 times".into() }, ..state };
        rolled.save(&path).unwrap();
        assert!(fs::read_to_string(&path).unwrap().contains("\"outcome\": \"rolled_back\""));
        assert_eq!(UpdateState::load(&path), Some(rolled));
        UpdateState::clear(&path).unwrap();
        UpdateState::clear(&path).unwrap();
        assert_eq!(UpdateState::load(&path), None);
    }
}
