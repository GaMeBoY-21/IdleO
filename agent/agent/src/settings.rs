//! `settings.toml` in the agent's folder: auto-pause's settings (ADR 0033). The agent owns the file; the tray changes
//! it only through the local channel. A missing or unreadable file means the defaults: every trigger on, starting
//! again after 10 quiet minutes.

use std::path::{Path, PathBuf};

use idleo_release::channel::AutoPause;
use serde::{Deserialize, Serialize};

/// The file's name in the agent's folder.
pub const SETTINGS_FILE: &str = "settings.toml";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
struct File {
    auto_pause: AutoPauseFile,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AutoPauseFile {
    keyboard_mouse: bool,
    games: bool,
    unplugged: bool,
    start_again_after_minutes: u32,
}

/// The settings file in `dir`.
#[must_use]
pub fn path(dir: &Path) -> PathBuf {
    dir.join(SETTINGS_FILE)
}

/// Auto-pause's settings from `dir`, or the defaults.
#[must_use]
pub fn load(dir: &Path) -> AutoPause {
    let Ok(text) = std::fs::read_to_string(path(dir)) else { return AutoPause::default() };
    match toml::from_str::<File>(&text) {
        Ok(file) => {
            let settings = AutoPause {
                keyboard_mouse: file.auto_pause.keyboard_mouse,
                games: file.auto_pause.games,
                unplugged: file.auto_pause.unplugged,
                start_again_after_minutes: file.auto_pause.start_again_after_minutes,
            };
            if settings.is_valid() { settings } else { AutoPause::default() }
        }
        Err(e) => {
            tracing::warn!(error = %e.message(), "settings.toml isn't valid; using the defaults");
            AutoPause::default()
        }
    }
}

/// Saves auto-pause's settings to `dir`: a temporary file, then a rename.
///
/// # Errors
/// If the settings aren't allowed or the file can't be written.
pub fn save(dir: &Path, settings: AutoPause) -> Result<(), String> {
    if !settings.is_valid() {
        return Err("start again after must be 5, 10, 20, 30 or 60 minutes".to_owned());
    }
    let file = File {
        auto_pause: AutoPauseFile {
            keyboard_mouse: settings.keyboard_mouse,
            games: settings.games,
            unplugged: settings.unplugged,
            start_again_after_minutes: settings.start_again_after_minutes,
        },
    };
    let text = toml::to_string(&file).map_err(|e| e.to_string())?;
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let temporary = dir.join(format!("{SETTINGS_FILE}.tmp"));
    std::fs::write(&temporary, format!("# Auto-pause, changed from the tray. The agent owns this file.\n{text}"))
        .map_err(|e| e.to_string())?;
    std::fs::rename(&temporary, path(dir)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_round_trip_and_bad_ones_fall_back() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(load(dir.path()), AutoPause::default());
        let changed = AutoPause { games: false, start_again_after_minutes: 30, ..AutoPause::default() };
        save(dir.path(), changed).unwrap();
        assert_eq!(load(dir.path()), changed);
        assert!(save(dir.path(), AutoPause { start_again_after_minutes: 7, ..changed }).is_err());
        std::fs::write(path(dir.path()), "[auto_pause]\nkeyboard_mouse = true\n").unwrap();
        assert_eq!(load(dir.path()), AutoPause::default());
    }
}
