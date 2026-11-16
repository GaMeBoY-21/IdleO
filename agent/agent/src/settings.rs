//! `settings.toml` in the agent's folder: everything the host chooses (ADR 0033, ADR 0061). The agent owns the file; the tray
//! and the IdleO window change it only through the local channel, so a setting is never only in a window and survives a
//! restart. A missing or unreadable file means the defaults: hosting as it has always worked (available whenever the owner
//! is away), every pause trigger on, heat limit 83 °C, and every notification on.
//!
//! An older file, which holds only `[auto_pause]`, still reads: what it doesn't say is the default.

use std::path::{Path, PathBuf};

use idleo_release::settings::HostSettings;

/// The file's name in the agent's folder.
pub const SETTINGS_FILE: &str = "settings.toml";

/// The settings file in `dir`.
#[must_use]
pub fn path(dir: &Path) -> PathBuf {
    dir.join(SETTINGS_FILE)
}

/// The settings from `dir`, or the defaults. Settings that aren't allowed (a hand-edited file) are the defaults too, so the
/// agent never runs on a value its own window couldn't have set.
#[must_use]
pub fn load(dir: &Path) -> HostSettings {
    let Ok(text) = std::fs::read_to_string(path(dir)) else { return HostSettings::default() };
    match toml::from_str::<HostSettings>(&text) {
        Ok(settings) => {
            let settings = settings.normalized();
            match settings.validate() {
                Ok(()) => settings,
                Err(e) => {
                    tracing::warn!(error = %e, "settings.toml has a value that isn't allowed; using the defaults");
                    HostSettings::default()
                }
            }
        }
        Err(e) => {
            tracing::warn!(error = %e.message(), "settings.toml isn't valid; using the defaults");
            HostSettings::default()
        }
    }
}

/// Saves the settings to `dir`: a temporary file, then a rename.
///
/// # Errors
/// If the settings aren't allowed or the file can't be written.
pub fn save(dir: &Path, settings: &HostSettings) -> Result<(), String> {
    settings.validate()?;
    let text = toml::to_string(&settings.normalized()).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let temporary = dir.join(format!("{SETTINGS_FILE}.tmp"));
    std::fs::write(&temporary, format!("# What the host chose in the IdleO window. The agent owns this file.\n{text}"))
        .map_err(|e| e.to_string())?;
    std::fs::rename(&temporary, path(dir)).map_err(|e| e.to_string())
}

#[cfg(test)]
#[allow(clippy::field_reassign_with_default, reason = "a test changes the one setting it is about")]
mod tests {
    use idleo_release::channel::AutoPause;
    use idleo_release::settings::{DayHours, ScheduleMode, Theme};

    use super::*;

    #[test]
    fn settings_round_trip_and_bad_ones_fall_back() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(load(dir.path()), HostSettings::default());
        let mut changed = HostSettings::default();
        changed.auto_pause.games = false;
        changed.auto_pause.start_again_after_minutes = 30;
        changed.schedule.mode = ScheduleMode::Hours;
        changed.schedule.days[5] = DayHours { on: false, from: 0, to: 60 };
        changed.limits.max_gpu_temp_c = 78;
        changed.limits.max_disk_gb = 80;
        changed.limits.max_memory_percent = 75;
        changed.not_on_metered = false;
        changed.start_with_windows = false;
        changed.notifications.payout = false;
        changed.theme = Theme::Dark;
        save(dir.path(), &changed).unwrap();
        assert_eq!(load(dir.path()), changed, "everything survives a restart");

        let mut bad = changed;
        bad.auto_pause.start_again_after_minutes = 7;
        assert!(save(dir.path(), &bad).is_err());
        assert_eq!(load(dir.path()), changed, "a refused save leaves the file as it was");
        std::fs::write(path(dir.path()), "[auto_pause]\nkeyboard_mouse = true\n").unwrap();
        assert_eq!(load(dir.path()), HostSettings::default(), "half a table is not a table");
        std::fs::write(path(dir.path()), "[limits]\nmax_gpu_temp_c = 150\n").unwrap();
        assert_eq!(load(dir.path()), HostSettings::default(), "a value the window couldn't set");
    }

    #[test]
    fn a_file_from_the_version_before_still_reads_and_nothing_else_changes() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            path(dir.path()),
            "# Auto-pause, changed from the tray. The agent owns this file.\n[auto_pause]\nkeyboard_mouse = true\ngames = false\nunplugged = true\nstart_again_after_minutes = 20\n",
        )
        .unwrap();
        let read = load(dir.path());
        assert_eq!(read.auto_pause, AutoPause { games: false, start_again_after_minutes: 20, ..AutoPause::default() });
        assert_eq!(read.schedule, HostSettings::default().schedule);
        assert_eq!(read.limits, HostSettings::default().limits);
    }

    #[test]
    fn the_owner_activity_trigger_and_the_schedule_mode_are_saved_in_step() {
        let dir = tempfile::tempdir().unwrap();
        let mut settings = HostSettings::default();
        settings.schedule.mode = ScheduleMode::Always;
        save(dir.path(), &settings).unwrap();
        assert!(!load(dir.path()).auto_pause.keyboard_mouse);
    }
}
