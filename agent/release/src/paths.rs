//! Where the Windows app lives (ADR 0033): everything per user, so nothing needs admin rights after setup.
//!
//! - Programs: `%LOCALAPPDATA%\Programs\IdleO\`: `idleo-launcher.exe`, `current` (the version that runs), and one
//!   `app-<version>\` folder per installed version (two at most).
//! - Data: `%LOCALAPPDATA%\IdleO\`: `agent\` (the agent's config, identity and settings), `updates\` (downloads),
//!   `setup\` (setup's progress), `wsl\<distro version>\` (our distribution's disk), `update-state.json`, `logs\`.
//!
//! [`Layout::under`] puts the same tree under any folder, which is how the tests on the Mac run the setup, the launcher
//! and the updater against a fake disk.

use std::path::{Path, PathBuf};

/// The agent's console program.
pub const AGENT_EXE: &str = "idleo-agent";
/// The agent as the background host, with no console window.
pub const HOSTD_EXE: &str = "idleo-hostd";
/// The tray icon.
pub const TRAY_EXE: &str = "idleo-tray";
/// The launcher: starts and watches the others, runs update trials and uninstalls.
pub const LAUNCHER_EXE: &str = "idleo-launcher";
/// The one download.
pub const SETUP_EXE: &str = "IdleO-Setup";
/// The five programs in an app zip.
pub const PROGRAMS: [&str; 5] = [AGENT_EXE, HOSTD_EXE, TRAY_EXE, LAUNCHER_EXE, SETUP_EXE];

/// A program's file name on this system: `idleo-agent.exe` on Windows.
#[must_use]
pub fn exe(name: &str) -> String {
    format!("{name}{}", std::env::consts::EXE_SUFFIX)
}

/// The app's folders.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    programs: PathBuf,
    data: PathBuf,
}

impl Layout {
    /// The signed-in user's folders: under `%LOCALAPPDATA%` on Windows. Elsewhere (development only) under
    /// `~/Library/Application Support/IdleO-app` on macOS and `~/.local/share/idleo-app` on Linux.
    #[must_use]
    pub fn for_user() -> Self {
        if cfg!(windows) {
            let local = std::env::var_os("LOCALAPPDATA").map_or_else(
                || {
                    let profile = std::env::var_os("USERPROFILE").unwrap_or_else(|| r"C:\Users\Default".into());
                    PathBuf::from(profile).join("AppData").join("Local")
                },
                PathBuf::from,
            );
            Self::under(&local)
        } else {
            let home = PathBuf::from(std::env::var_os("HOME").unwrap_or_else(|| "/".into()));
            let base = if cfg!(target_os = "macos") {
                home.join("Library/Application Support/IdleO-app")
            } else {
                home.join(".local/share/idleo-app")
            };
            Self::under(&base)
        }
    }

    /// The same tree under `root`, as `%LOCALAPPDATA%` would hold it.
    #[must_use]
    pub fn under(root: &Path) -> Self {
        Self { programs: root.join("Programs").join(crate::PRODUCT), data: root.join(crate::PRODUCT) }
    }

    /// `…\Programs\IdleO`.
    #[must_use]
    pub fn programs(&self) -> &Path {
        &self.programs
    }

    /// `…\IdleO`.
    #[must_use]
    pub fn data(&self) -> &Path {
        &self.data
    }

    /// The launcher, outside the version folders, so the Run entry and shortcuts never change.
    #[must_use]
    pub fn launcher(&self) -> PathBuf {
        self.programs.join(exe(LAUNCHER_EXE))
    }

    /// A newer launcher staged by an update, moved into place at the launcher's next start.
    #[must_use]
    pub fn launcher_staged(&self) -> PathBuf {
        self.programs.join(exe(&format!("{LAUNCHER_EXE}.new")))
    }

    /// The launcher a self-update replaced, deleted at the start after.
    #[must_use]
    pub fn launcher_old(&self) -> PathBuf {
        self.programs.join(exe(&format!("{LAUNCHER_EXE}.old")))
    }

    /// The file naming the version that runs.
    #[must_use]
    pub fn current_file(&self) -> PathBuf {
        self.programs.join("current")
    }

    /// One installed version's folder.
    #[must_use]
    pub fn app_dir(&self, version: &str) -> PathBuf {
        self.programs.join(format!("app-{version}"))
    }

    /// The agent's folder: `agent.toml`, `ca-root.pem`, its identity and `settings.toml`.
    #[must_use]
    pub fn agent_dir(&self) -> PathBuf {
        self.data.join("agent")
    }

    /// The agent's config file.
    #[must_use]
    pub fn agent_config(&self) -> PathBuf {
        self.agent_dir().join("agent.toml")
    }

    /// Downloads, kept until installed.
    #[must_use]
    pub fn updates(&self) -> PathBuf {
        self.data.join("updates")
    }

    /// Setup's own folder: its progress, and its copy of itself across a restart.
    #[must_use]
    pub fn setup_dir(&self) -> PathBuf {
        self.data.join("setup")
    }

    /// Setup's progress, so a re-run or a restart picks up where it stopped.
    #[must_use]
    pub fn setup_state(&self) -> PathBuf {
        self.setup_dir().join("state.json")
    }

    /// Where a distro version's disk lives.
    #[must_use]
    pub fn wsl_dir(&self, distro_version: &str) -> PathBuf {
        self.data.join("wsl").join(distro_version)
    }

    /// An update's trial (see [`crate::update_state`]).
    #[must_use]
    pub fn update_state(&self) -> PathBuf {
        self.data.join("update-state.json")
    }

    /// The programs' log files.
    #[must_use]
    pub fn logs(&self) -> PathBuf {
        self.data.join("logs")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_tree_is_the_same_under_any_root() {
        let layout = Layout::under(Path::new("/fake/Local"));
        assert_eq!(layout.programs(), Path::new("/fake/Local/Programs/IdleO"));
        assert_eq!(layout.data(), Path::new("/fake/Local/IdleO"));
        assert_eq!(layout.app_dir("0.2.0"), Path::new("/fake/Local/Programs/IdleO/app-0.2.0"));
        assert_eq!(layout.agent_config(), Path::new("/fake/Local/IdleO/agent/agent.toml"));
        assert_eq!(layout.wsl_dir("0.1.0"), Path::new("/fake/Local/IdleO/wsl/0.1.0"));
    }
}
