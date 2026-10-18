//! The `wsl.exe` wrapper (ADR 0033). Every command runs with `WSL_UTF8=1` (otherwise `wsl.exe` writes UTF-16) and,
//! on Windows, without a console window. Only distributions named `IdleO-…` are ever imported, run, terminated or
//! unregistered; the owner's own distributions and their `%USERPROFILE%\.wslconfig` are never touched.
//!
//! Commands go through a [`Runner`], so the setup app, the launcher and the self-test are tested on the Mac with a
//! fake one.

use std::fmt;
use std::io;
use std::path::Path;

/// Every distribution we make starts with this, and nothing else is ever touched.
pub const DISTRO_PREFIX: &str = "IdleO-";
/// The oldest WSL we use: the Store's WSL 2 with systemd, `--import --version 2` and `--no-distribution`.
pub const MIN_WSL_VERSION: &str = "2.0.0";

/// What a command printed, and how it ended.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Output {
    /// The exit code; -1 when it was killed.
    pub code: i32,
    /// Standard output, as text.
    pub stdout: String,
    /// Standard error, as text.
    pub stderr: String,
}

impl Output {
    /// Whether it exited 0.
    #[must_use]
    pub fn ok(&self) -> bool {
        self.code == 0
    }

    /// Both streams together, for messages.
    #[must_use]
    pub fn text(&self) -> String {
        format!("{}{}", self.stdout, self.stderr)
    }
}

/// Runs a program and waits for it.
pub trait Runner: Send + Sync {
    /// Runs `program` with `args` and returns what it printed.
    ///
    /// # Errors
    /// If it can't be started at all (for example it isn't installed).
    fn run(&self, program: &str, args: &[&str]) -> io::Result<Output>;
}

impl<T: Runner + ?Sized> Runner for &T {
    fn run(&self, program: &str, args: &[&str]) -> io::Result<Output> {
        (**self).run(program, args)
    }
}

impl<T: Runner + ?Sized> Runner for std::sync::Arc<T> {
    fn run(&self, program: &str, args: &[&str]) -> io::Result<Output> {
        (**self).run(program, args)
    }
}

/// The real runner: no console window, `WSL_UTF8=1`, and UTF-16 output decoded if `wsl.exe` wrote it anyway.
#[derive(Debug, Default, Clone, Copy)]
pub struct SystemRunner;

impl Runner for SystemRunner {
    fn run(&self, program: &str, args: &[&str]) -> io::Result<Output> {
        let mut command = std::process::Command::new(program);
        command.args(args).env("WSL_UTF8", "1").stdin(std::process::Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            /// `CREATE_NO_WINDOW`: no console flashes up from a program with none.
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        let output = command.output()?;
        Ok(Output {
            code: output.status.code().unwrap_or(-1),
            stdout: text(&output.stdout),
            stderr: text(&output.stderr),
        })
    }
}

/// Bytes as text: UTF-16LE when they look like it (every other byte NUL), UTF-8 otherwise.
#[must_use]
pub fn text(bytes: &[u8]) -> String {
    let looks_utf16 = bytes.len() >= 2
        && bytes.len().is_multiple_of(2)
        && bytes.iter().skip(1).step_by(2).filter(|b| **b == 0).count() * 2 >= bytes.len() / 2;
    if looks_utf16 {
        let units: Vec<u16> = bytes.as_chunks::<2>().0.iter().map(|pair| u16::from_le_bytes(*pair)).collect();
        String::from_utf16_lossy(&units).trim_start_matches('\u{feff}').to_owned()
    } else {
        String::from_utf8_lossy(bytes).into_owned()
    }
}

/// Where WSL stands on this machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WslState {
    /// Not installed, or the Windows feature is off: `wsl --install --no-distribution` turns it on.
    Missing,
    /// Turned on, but Windows needs a restart to finish.
    NeedsRestart,
    /// Installed, but older than [`MIN_WSL_VERSION`] (or the old inbox WSL, which has no `--version`): `wsl --update`.
    TooOld(String),
    /// Ready, at this version.
    Ready(String),
}

/// Why a WSL command failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WslError {
    /// `wsl.exe` couldn't be started.
    NotFound(String),
    /// A distribution that isn't ours was asked for.
    NotOurs(String),
    /// The command ran and failed.
    Failed {
        /// What was asked.
        what: String,
        /// What it printed.
        output: String,
    },
}

impl fmt::Display for WslError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotFound(e) => write!(f, "wsl.exe couldn't be started: {e}"),
            Self::NotOurs(name) => write!(f, "{name} isn't one of IdleO's distributions, so it's left alone"),
            Self::Failed { what, output } => write!(f, "{what} failed: {}", output.trim()),
        }
    }
}

impl std::error::Error for WslError {}

/// `wsl.exe`, through a [`Runner`].
pub struct Wsl<R> {
    runner: R,
    program: String,
}

impl<R: Runner> Wsl<R> {
    /// `wsl.exe` through `runner`.
    pub fn new(runner: R) -> Self {
        Self { runner, program: "wsl.exe".to_owned() }
    }

    fn run(&self, what: &str, args: &[&str]) -> Result<Output, WslError> {
        let output = self.runner.run(&self.program, args).map_err(|e| WslError::NotFound(e.to_string()))?;
        if output.ok() { Ok(output) } else { Err(WslError::Failed { what: what.to_owned(), output: output.text() }) }
    }

    /// Where WSL stands: from `wsl --version`, and `wsl --status` when that fails.
    #[must_use]
    pub fn state(&self) -> WslState {
        match self.runner.run(&self.program, &["--version"]) {
            Err(_) => WslState::Missing,
            Ok(output) if output.ok() => match parse_version(&output.stdout) {
                Some(version) if crate::version::is_newer(MIN_WSL_VERSION, &version) == Some(false) => {
                    WslState::Ready(version)
                }
                Some(version) => WslState::TooOld(version),
                None => WslState::TooOld("unknown".to_owned()),
            },
            Ok(_) => match self.runner.run(&self.program, &["--status"]) {
                Err(_) => WslState::Missing,
                Ok(status) => {
                    let text = status.text().to_lowercase();
                    if text.contains("restart") || text.contains("reboot") {
                        WslState::NeedsRestart
                    } else if status.ok() {
                        // `--status` works but `--version` doesn't: the old inbox WSL.
                        WslState::TooOld("inbox".to_owned())
                    } else {
                        WslState::Missing
                    }
                }
            },
        }
    }

    /// Turns WSL on without installing a distribution; Windows raises its own prompt for admin rights. Returns
    /// whether Windows needs a restart to finish.
    ///
    /// # Errors
    /// If it fails.
    pub fn install(&self) -> Result<bool, WslError> {
        let output = self.run("turning on WSL", &["--install", "--no-distribution"])?;
        let text = output.text().to_lowercase();
        Ok(text.contains("restart") || text.contains("reboot"))
    }

    /// Updates WSL to the Store's latest; no restart.
    ///
    /// # Errors
    /// If it fails.
    pub fn update(&self) -> Result<(), WslError> {
        self.run("updating WSL", &["--update"]).map(|_| ())
    }

    /// Every registered distribution's name.
    ///
    /// # Errors
    /// If they can't be listed.
    pub fn list(&self) -> Result<Vec<String>, WslError> {
        match self.runner.run(&self.program, &["--list", "--quiet"]) {
            Err(e) => Err(WslError::NotFound(e.to_string())),
            Ok(output) if output.ok() => {
                Ok(output.stdout.lines().map(|line| line.trim().to_owned()).filter(|name| !name.is_empty()).collect())
            }
            // With no distribution registered, wsl.exe exits non-zero and says so.
            Ok(_) => Ok(Vec::new()),
        }
    }

    /// Our distributions only.
    ///
    /// # Errors
    /// If they can't be listed.
    pub fn ours(&self) -> Result<Vec<String>, WslError> {
        Ok(self.list()?.into_iter().filter(|name| name.starts_with(DISTRO_PREFIX)).collect())
    }

    /// Imports a tarball as a WSL 2 distribution whose disk lives in `dir`.
    ///
    /// # Errors
    /// If `name` isn't ours, or the import fails.
    pub fn import(&self, name: &str, dir: &Path, tarball: &Path) -> Result<(), WslError> {
        ours(name)?;
        let dir = dir.to_string_lossy();
        let tarball = tarball.to_string_lossy();
        self.run(&format!("importing {name}"), &["--import", name, &dir, &tarball, "--version", "2"]).map(|_| ())
    }

    /// Unregisters one of our distributions, which deletes its disk.
    ///
    /// # Errors
    /// If `name` isn't ours, or it fails.
    pub fn unregister(&self, name: &str) -> Result<(), WslError> {
        ours(name)?;
        self.run(&format!("removing {name}"), &["--unregister", name]).map(|_| ())
    }

    /// Stops one of our distributions.
    ///
    /// # Errors
    /// If `name` isn't ours, or it fails.
    pub fn terminate(&self, name: &str) -> Result<(), WslError> {
        ours(name)?;
        self.run(&format!("stopping {name}"), &["--terminate", name]).map(|_| ())
    }

    /// Runs a program inside one of our distributions as root, returning what it printed however it ended.
    ///
    /// # Errors
    /// If `name` isn't ours, or `wsl.exe` can't be started.
    pub fn exec(&self, name: &str, program: &str, args: &[&str]) -> Result<Output, WslError> {
        ours(name)?;
        let mut all = vec!["--distribution", name, "--user", "root", "--exec", program];
        all.extend_from_slice(args);
        self.runner.run(&self.program, &all).map_err(|e| WslError::NotFound(e.to_string()))
    }

    /// The WSL version of one of our distributions (1 or 2), from `wsl --list --verbose`.
    ///
    /// # Errors
    /// If they can't be listed.
    pub fn wsl_version_of(&self, name: &str) -> Result<Option<u8>, WslError> {
        let output = self.run("listing distributions", &["--list", "--verbose"])?;
        Ok(output.stdout.lines().find_map(|line| {
            let mut fields = line.trim_start_matches('*').split_whitespace();
            (fields.next()? == name).then(|| fields.last()?.parse().ok()).flatten()
        }))
    }
}

fn ours(name: &str) -> Result<(), WslError> {
    if name.starts_with(DISTRO_PREFIX) { Ok(()) } else { Err(WslError::NotOurs(name.to_owned())) }
}

/// `WSL version: 2.3.26.0` → `2.3.26`, from the first line that has one.
fn parse_version(text: &str) -> Option<String> {
    text.lines().find_map(|line| {
        let value = line.split(':').nth(1)?.trim();
        let parts: Vec<&str> = value.split('.').take(3).collect();
        (parts.len() == 3 && parts.iter().all(|part| part.parse::<u32>().is_ok())).then(|| parts.join("."))
    })
}

/// A fake `wsl.exe` for tests: distributions in memory, answers scripted, every call recorded.
#[cfg(any(test, feature = "fakes"))]
pub mod fake {
    use std::collections::BTreeMap;
    use std::io;
    use std::sync::{Arc, Mutex, PoisonError};

    use super::{Output, Runner};

    /// What the fake knows.
    #[derive(Debug, Default)]
    pub struct State {
        /// `None`: wsl.exe isn't there. Otherwise what `--version` says.
        pub version: Option<String>,
        /// True: `--install` asks for a restart, and `--version` fails until [`FakeWsl::restart`].
        pub install_needs_restart: bool,
        /// Waiting for a restart.
        pub restart_pending: bool,
        /// Registered distributions, with their WSL version and disk folder.
        pub distros: BTreeMap<String, String>,
        /// Programs run inside distributions, with what each prints and its exit code.
        pub exec: BTreeMap<String, (i32, String)>,
        /// Every call, as its arguments joined by spaces.
        pub calls: Vec<String>,
        /// When set, `--import` fails with this on stderr.
        pub import_error: Option<String>,
    }

    /// A fake `wsl.exe`. Clones share the same state.
    #[derive(Debug, Clone, Default)]
    pub struct FakeWsl(pub Arc<Mutex<State>>);

    impl FakeWsl {
        /// A machine with WSL ready at 2.3.26 and nothing registered.
        #[must_use]
        pub fn ready() -> Self {
            let fake = Self::default();
            fake.state().version = Some("2.3.26.0".to_owned());
            fake
        }

        /// The fake's state.
        pub fn state(&self) -> std::sync::MutexGuard<'_, State> {
            self.0.lock().unwrap_or_else(PoisonError::into_inner)
        }

        /// Windows restarted: a pending install finishes.
        pub fn restart(&self) {
            let mut state = self.state();
            if state.restart_pending {
                state.restart_pending = false;
                state.version = Some("2.3.26.0".to_owned());
            }
        }

        /// Registers a distribution that isn't ours, as the owner's own Ubuntu.
        pub fn add_foreign(&self, name: &str) {
            self.state().distros.insert(name.to_owned(), "elsewhere".to_owned());
        }
    }

    impl Runner for FakeWsl {
        fn run(&self, _program: &str, args: &[&str]) -> io::Result<Output> {
            let mut state = self.state();
            state.calls.push(args.join(" "));
            let ok = |stdout: String| Ok(Output { code: 0, stdout, stderr: String::new() });
            let fail = |stderr: &str| Ok(Output { code: 1, stdout: String::new(), stderr: stderr.to_owned() });
            match args {
                ["--version"] => match (&state.version, state.restart_pending) {
                    (Some(version), false) => ok(format!("WSL version: {version}\nKernel version: 5.15\n")),
                    _ => fail("not installed"),
                },
                ["--status"] if state.restart_pending => fail("Please restart your computer to finish."),
                ["--status"] => match state.version {
                    Some(_) => ok("Default Version: 2".to_owned()),
                    None => fail("WSL is not installed"),
                },
                ["--install", "--no-distribution"] => {
                    if state.install_needs_restart {
                        state.restart_pending = true;
                        ok("The requested operation is successful. Changes will not be effective until the system is rebooted.".to_owned())
                    } else {
                        state.version = Some("2.3.26.0".to_owned());
                        ok("The operation completed successfully.".to_owned())
                    }
                }
                ["--update"] => {
                    state.version = Some("2.3.26.0".to_owned());
                    ok(String::new())
                }
                ["--list", "--quiet"] if state.distros.is_empty() => fail("no installed distributions"),
                ["--list", "--quiet"] => ok(state.distros.keys().fold(String::new(), |out, name| {
                    out + name
                        + "
"
                })),
                ["--list", "--verbose"] => ok(state.distros.keys().fold(String::new(), |out, name| {
                    out + "  "
                        + name
                        + "  Stopped  2
"
                })),
                ["--import", ..] if state.import_error.is_some() => {
                    fail(&state.import_error.clone().unwrap_or_default())
                }
                ["--import", name, dir, _tarball, "--version", "2"] => {
                    state.distros.insert((*name).to_owned(), (*dir).to_owned());
                    ok(String::new())
                }
                ["--unregister", name] => match state.distros.remove(*name) {
                    Some(_) => ok(String::new()),
                    None => fail("there is no distribution with the supplied name"),
                },
                ["--terminate", _] => ok(String::new()),
                ["--distribution", name, "--user", "root", "--exec", program, ..] => {
                    if !state.distros.contains_key(*name) {
                        return fail("there is no distribution with the supplied name");
                    }
                    let (code, stdout) = state.exec.get(*program).cloned().unwrap_or((0, String::new()));
                    Ok(Output { code, stdout, stderr: String::new() })
                }
                _ => fail("unknown command"),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fake::FakeWsl;
    use super::*;

    #[test]
    fn utf16_output_is_decoded() {
        let utf16: Vec<u8> = "\u{feff}IdleO-0.1.0\r\n".encode_utf16().flat_map(u16::to_le_bytes).collect();
        assert_eq!(text(&utf16), "IdleO-0.1.0\r\n");
        assert_eq!(text(b"Ubuntu\n"), "Ubuntu\n");
    }

    #[test]
    fn versions_are_read_and_compared() {
        assert_eq!(parse_version("WSL version: 2.3.26.0\nKernel version: 5.15.167.4-1"), Some("2.3.26".to_owned()));
        assert_eq!(parse_version("nothing"), None);
        let wsl = Wsl::new(FakeWsl::ready());
        assert_eq!(wsl.state(), WslState::Ready("2.3.26".to_owned()));
        let old = FakeWsl::ready();
        old.state().version = Some("1.2.5.0".to_owned());
        assert_eq!(Wsl::new(old).state(), WslState::TooOld("1.2.5".to_owned()));
    }

    #[test]
    fn a_missing_wsl_is_installed_and_may_need_a_restart() {
        let fake = FakeWsl::default();
        fake.state().install_needs_restart = true;
        let wsl = Wsl::new(fake.clone());
        assert_eq!(wsl.state(), WslState::Missing);
        assert!(wsl.install().unwrap());
        assert_eq!(wsl.state(), WslState::NeedsRestart);
        fake.restart();
        assert_eq!(wsl.state(), WslState::Ready("2.3.26".to_owned()));
    }

    #[test]
    fn only_our_distributions_are_ever_touched() {
        let fake = FakeWsl::ready();
        fake.add_foreign("Ubuntu");
        let wsl = Wsl::new(fake.clone());
        wsl.import("IdleO-0.1.0", Path::new("/disk"), Path::new("/tar.gz")).unwrap();
        assert_eq!(wsl.ours().unwrap(), ["IdleO-0.1.0"]);
        assert_eq!(wsl.wsl_version_of("IdleO-0.1.0").unwrap(), Some(2));
        for refused in
            [wsl.unregister("Ubuntu"), wsl.terminate("Ubuntu"), wsl.import("Ubuntu", Path::new("/d"), Path::new("/t"))]
        {
            assert_eq!(refused, Err(WslError::NotOurs("Ubuntu".to_owned())));
        }
        assert!(wsl.exec("Ubuntu", "ls", &[]).is_err());
        wsl.unregister("IdleO-0.1.0").unwrap();
        assert_eq!(wsl.list().unwrap(), ["Ubuntu"]);
        assert!(fake.state().calls.iter().all(|call| !call.contains("Ubuntu") || call.starts_with("--list")));
    }
}
