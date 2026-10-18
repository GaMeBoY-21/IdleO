//! What an installed IdleO is, written in one place and removed in one place (ADR 0033): the setup app writes it,
//! and both the setup app's "Stop setting up?" and the launcher's uninstall remove it.
//!
//! - `HKCU\…\Run\IdleO`: `"…\idleo-launcher.exe" start`, so it starts at sign-in.
//! - `HKCU\…\RunOnce\IdleO Setup`: `"…\IdleO-Setup.exe" --resume`, only across the one restart WSL may need.
//! - `HKCU\…\Uninstall\IdleO`: what Settings → Apps shows, and its `UninstallString`.
//! - The Start-menu shortcut "IdleO", with our AppUserModelID.
//! - Our WSL distributions (`IdleO-…`), the data folder and the programs folder.
//!
//! The owner's own distributions, `.wslconfig` and anything else of theirs are never touched.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use crate::paths::{self, Layout};
use crate::registry::{Registry, Value};
use crate::wsl::{Runner, Wsl};

/// Starts the launcher at sign-in.
pub const RUN_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Run";
/// The Run entry's name.
pub const RUN_VALUE: &str = crate::PRODUCT;
/// Carries setup on after the restart WSL may need.
pub const RUNONCE_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\RunOnce";
/// The RunOnce entry's name.
pub const RUNONCE_VALUE: &str = "IdleO Setup";
/// The Uninstall entry, per user.
pub const UNINSTALL_KEY: &str = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\IdleO";
/// Groups the shortcut, the tray and notifications under one app in Windows.
pub const APP_USER_MODEL_ID: &str = "IdleO.Host";
/// The Uninstall entry's publisher.
pub const PUBLISHER: &str = "IdleO";

/// What installing and removing touch on a machine: the folders, the registry, WSL and the Start menu.
pub trait Machine: Send + Sync {
    /// The app's folders.
    fn layout(&self) -> &Layout;
    /// `HKEY_CURRENT_USER`.
    fn registry(&self) -> &dyn Registry;
    /// What runs `wsl.exe`.
    fn wsl_runner(&self) -> &dyn Runner;
    /// Where the Start-menu shortcut goes.
    fn shortcut_path(&self) -> PathBuf;
    /// Makes the Start-menu shortcut.
    ///
    /// # Errors
    /// If it can't be made.
    fn create_shortcut(&self, link: &Path, target: &Path, arguments: &str) -> io::Result<()>;
}

/// The Run entry's command: the launcher, started at sign-in.
#[must_use]
pub fn run_command(layout: &Layout) -> String {
    format!("\"{}\" start", layout.launcher().display())
}

/// The RunOnce entry's command: setup, carrying on after the restart.
#[must_use]
pub fn resume_command(setup_copy: &Path) -> String {
    format!("\"{}\" --resume", setup_copy.display())
}

/// Writes the Run entry, the Start-menu shortcut and the Uninstall entry for `version`, whose files take `size_kb`.
///
/// # Errors
/// If one can't be written.
pub fn register(machine: &dyn Machine, version: &str, size_kb: u32) -> io::Result<()> {
    let layout = machine.layout();
    let registry = machine.registry();
    let launcher = layout.launcher();
    registry.set(RUN_KEY, RUN_VALUE, &Value::Text(run_command(layout)))?;
    machine.create_shortcut(&machine.shortcut_path(), &launcher, "open")?;
    let text = |value: String| Value::Text(value);
    for (name, value) in [
        ("DisplayName", text(crate::PRODUCT.to_owned())),
        ("Publisher", text(PUBLISHER.to_owned())),
        ("DisplayVersion", text(version.to_owned())),
        ("DisplayIcon", text(format!("{},0", launcher.display()))),
        ("InstallLocation", text(layout.programs().display().to_string())),
        ("UninstallString", text(format!("\"{}\" uninstall", launcher.display()))),
        ("EstimatedSize", Value::Number(size_kb)),
        ("NoModify", Value::Number(1)),
        ("NoRepair", Value::Number(1)),
    ] {
        registry.set(UNINSTALL_KEY, name, &value)?;
    }
    Ok(())
}

/// Updates the Uninstall entry's version after an update.
///
/// # Errors
/// If it can't be written.
pub fn set_version(machine: &dyn Machine, version: &str) -> io::Result<()> {
    machine.registry().set(UNINSTALL_KEY, "DisplayVersion", &Value::Text(version.to_owned()))
}

/// Whether IdleO is installed on this machine: the Uninstall entry and a current version.
#[must_use]
pub fn is_installed(machine: &dyn Machine) -> bool {
    machine.registry().get(UNINSTALL_KEY, "UninstallString").is_some()
        && crate::apps::Apps::new(machine.layout().clone()).current().is_some()
}

/// What a removal did.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct Removal {
    /// Our distributions unregistered (their disks with them).
    pub distros: Vec<String>,
    /// A file that couldn't be deleted because it's running (the launcher itself): delete it once it exits.
    pub leftover: Option<PathBuf>,
    /// What couldn't be removed, in plain words; empty when everything went.
    pub problems: Vec<String>,
}

/// Removes everything of ours: our distributions, the registry entries, the shortcut, the data folder and the
/// programs folder. `running` is a program that can't be deleted yet because it is the one running this; it's left,
/// with the folder holding it, and named in [`Removal::leftover`]. Keeps going past a failure, and says what failed.
#[must_use]
pub fn remove_all(machine: &dyn Machine, running: Option<&Path>) -> Removal {
    let mut removal = Removal::default();
    let wsl = Wsl::new(machine.wsl_runner());
    match wsl.ours() {
        Ok(ours) => {
            for name in ours {
                match wsl.unregister(&name) {
                    Ok(()) => removal.distros.push(name),
                    Err(e) => removal.problems.push(e.to_string()),
                }
            }
        }
        // No WSL means nothing of ours registered in it.
        Err(crate::wsl::WslError::NotFound(_)) => {}
        Err(e) => removal.problems.push(e.to_string()),
    }
    let registry = machine.registry();
    for result in [
        registry.delete_value(RUN_KEY, RUN_VALUE),
        registry.delete_value(RUNONCE_KEY, RUNONCE_VALUE),
        registry.delete_key(UNINSTALL_KEY),
    ] {
        if let Err(e) = result {
            removal.problems.push(format!("a registry entry: {e}"));
        }
    }
    if let Err(e) = remove_file(&machine.shortcut_path()) {
        removal.problems.push(format!("the Start-menu shortcut: {e}"));
    }
    let layout = machine.layout();
    if let Err(e) = remove_dir(layout.data()) {
        removal.problems.push(format!("{}: {e}", layout.data().display()));
    }
    match running.filter(|program| program.starts_with(layout.programs())) {
        Some(program) => {
            if let Ok(entries) = fs::read_dir(layout.programs()) {
                for entry in entries.filter_map(Result::ok) {
                    let path = entry.path();
                    if program.starts_with(&path) {
                        continue;
                    }
                    let result = if path.is_dir() { remove_dir(&path) } else { remove_file(&path) };
                    if let Err(e) = result {
                        removal.problems.push(format!("{}: {e}", path.display()));
                    }
                }
            }
            removal.leftover = Some(program.to_owned());
        }
        None => {
            if let Err(e) = remove_dir(layout.programs()) {
                removal.problems.push(format!("{}: {e}", layout.programs().display()));
            }
        }
    }
    removal
}

/// Whether anything of ours is left: the folders, the registry entries, the shortcut or a distribution.
#[must_use]
pub fn leftovers(machine: &dyn Machine) -> Vec<String> {
    let mut left = Vec::new();
    let layout = machine.layout();
    for folder in [layout.data(), layout.programs()] {
        if folder.exists() {
            left.push(folder.display().to_string());
        }
    }
    let registry = machine.registry();
    if registry.get(RUN_KEY, RUN_VALUE).is_some() {
        left.push("the Run entry".to_owned());
    }
    if registry.get(RUNONCE_KEY, RUNONCE_VALUE).is_some() {
        left.push("the RunOnce entry".to_owned());
    }
    if registry.get(UNINSTALL_KEY, "UninstallString").is_some() {
        left.push("the Uninstall entry".to_owned());
    }
    if machine.shortcut_path().exists() {
        left.push("the Start-menu shortcut".to_owned());
    }
    if let Ok(ours) = Wsl::new(machine.wsl_runner()).ours() {
        left.extend(ours);
    }
    left
}

fn remove_file(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

fn remove_dir(path: &Path) -> io::Result<()> {
    match fs::remove_dir_all(path) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

/// A machine for tests: a temporary folder as `%LOCALAPPDATA%` (and the Start menu), an in-memory registry and a
/// fake `wsl.exe`.
#[cfg(any(test, feature = "fakes"))]
pub mod fake {
    use std::path::{Path, PathBuf};

    use super::Machine;
    use crate::paths::Layout;
    use crate::registry::{MemoryRegistry, Registry};
    use crate::wsl::Runner;
    use crate::wsl::fake::FakeWsl;

    /// The fake machine.
    pub struct FakeMachine {
        /// The fake `%LOCALAPPDATA%` and Start menu.
        pub root: tempfile::TempDir,
        /// The app's folders under it.
        pub layout: Layout,
        /// The registry.
        pub registry: MemoryRegistry,
        /// `wsl.exe`.
        pub wsl: FakeWsl,
    }

    impl FakeMachine {
        /// A machine with WSL ready and nothing installed.
        ///
        /// # Panics
        /// If a temporary folder can't be made.
        #[must_use]
        pub fn new() -> Self {
            let root = tempfile::tempdir().expect("a temporary folder");
            let layout = Layout::under(&root.path().join("Local"));
            Self { root, layout, registry: MemoryRegistry::default(), wsl: FakeWsl::ready() }
        }
    }

    impl Default for FakeMachine {
        fn default() -> Self {
            Self::new()
        }
    }

    impl Machine for FakeMachine {
        fn layout(&self) -> &Layout {
            &self.layout
        }

        fn registry(&self) -> &dyn Registry {
            &self.registry
        }

        fn wsl_runner(&self) -> &dyn Runner {
            &self.wsl
        }

        fn shortcut_path(&self) -> PathBuf {
            self.root.path().join("Start Menu").join("Programs").join("IdleO.lnk")
        }

        fn create_shortcut(&self, link: &Path, target: &Path, arguments: &str) -> std::io::Result<()> {
            std::fs::create_dir_all(link.parent().expect("a folder"))?;
            std::fs::write(link, format!("{} {arguments}", target.display()))
        }
    }
}

/// The real machine: the signed-in user's folders and registry, `wsl.exe`, and the Start menu.
#[cfg(windows)]
pub struct SystemMachine {
    layout: Layout,
    registry: crate::registry::UserRegistry,
    runner: crate::wsl::SystemRunner,
}

#[cfg(windows)]
impl SystemMachine {
    /// This user's machine.
    #[must_use]
    pub fn new() -> Self {
        Self { layout: Layout::for_user(), registry: crate::registry::UserRegistry, runner: crate::wsl::SystemRunner }
    }
}

#[cfg(windows)]
impl Default for SystemMachine {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(windows)]
impl Machine for SystemMachine {
    fn layout(&self) -> &Layout {
        &self.layout
    }

    fn registry(&self) -> &dyn Registry {
        &self.registry
    }

    fn wsl_runner(&self) -> &dyn Runner {
        &self.runner
    }

    fn shortcut_path(&self) -> PathBuf {
        let roaming = std::env::var_os("APPDATA")
            .map_or_else(|| self.layout.data().join("..").join("..").join("Roaming"), PathBuf::from);
        roaming.join("Microsoft").join("Windows").join("Start Menu").join("Programs").join("IdleO.lnk")
    }

    fn create_shortcut(&self, link: &Path, target: &Path, arguments: &str) -> io::Result<()> {
        agent_windows::create_shortcut(link, target, arguments, APP_USER_MODEL_ID)
    }
}

/// Every program of the app, as file names, for building and checking zips.
#[must_use]
pub fn program_files() -> Vec<String> {
    paths::PROGRAMS.iter().map(|name| paths::exe(name)).collect()
}

#[cfg(test)]
mod tests {
    use super::fake::FakeMachine;
    use super::*;
    use crate::apps::Apps;

    fn installed(machine: &FakeMachine) {
        let layout = machine.layout();
        fs::create_dir_all(layout.programs()).unwrap();
        let apps = Apps::new(layout.clone());
        apps.install_zip(&crate::apps::tests::app_zip(machine.root.path(), "0.2.0"), "0.2.0").unwrap();
        apps.set_current("0.2.0").unwrap();
        fs::copy(apps.program("0.2.0", paths::LAUNCHER_EXE), layout.launcher()).unwrap();
        fs::create_dir_all(layout.agent_dir()).unwrap();
        fs::write(layout.agent_config(), "[control_plane]\n").unwrap();
        register(machine, "0.2.0", 12_000).unwrap();
        machine.registry().set(RUNONCE_KEY, RUNONCE_VALUE, &Value::Text("x".into())).unwrap();
        Wsl::new(machine.wsl_runner()).import("IdleO-0.1.0", Path::new("/disk"), Path::new("/t")).unwrap();
    }

    #[test]
    fn registering_writes_what_settings_shows() {
        let machine = FakeMachine::new();
        installed(&machine);
        assert!(is_installed(&machine));
        let registry = machine.registry();
        assert_eq!(registry.get(RUN_KEY, RUN_VALUE).unwrap(), run_command(machine.layout()));
        assert_eq!(registry.get(UNINSTALL_KEY, "DisplayName").as_deref(), Some("IdleO"));
        assert!(registry.get(UNINSTALL_KEY, "UninstallString").unwrap().ends_with("\" uninstall"));
        assert_eq!(registry.get(UNINSTALL_KEY, "NoModify").as_deref(), Some("1"));
        assert!(machine.shortcut_path().exists());
    }

    #[test]
    fn removing_leaves_nothing_of_ours_and_never_touches_the_owners_distribution() {
        let machine = FakeMachine::new();
        machine.wsl.add_foreign("Ubuntu");
        installed(&machine);
        let removal = remove_all(&machine, None);
        assert_eq!(removal.problems, Vec::<String>::new());
        assert_eq!(removal.distros, ["IdleO-0.1.0"]);
        assert_eq!(leftovers(&machine), Vec::<String>::new());
        assert!(machine.registry.is_empty());
        assert_eq!(Wsl::new(machine.wsl_runner()).list().unwrap(), ["Ubuntu"]);
    }

    #[test]
    fn the_running_launcher_is_left_for_later() {
        let machine = FakeMachine::new();
        installed(&machine);
        let launcher = machine.layout().launcher();
        let removal = remove_all(&machine, Some(&launcher));
        assert_eq!(removal.leftover.as_deref(), Some(launcher.as_path()));
        assert!(launcher.exists());
        assert!(!machine.layout().app_dir("0.2.0").exists());
        assert!(!machine.layout().data().exists());
        fs::remove_dir_all(machine.layout().programs()).unwrap();
        assert_eq!(leftovers(&machine), Vec::<String>::new());
    }

    #[test]
    fn removing_with_nothing_installed_is_fine() {
        let machine = FakeMachine::new();
        machine.wsl.state().version = None;
        let removal = remove_all(&machine, None);
        assert!(removal.problems.is_empty(), "{:?}", removal.problems);
    }
}
