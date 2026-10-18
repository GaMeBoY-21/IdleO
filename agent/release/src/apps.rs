//! The app's installed versions (ADR 0034): each in its own `app-<version>` folder, `current` naming the one that
//! runs, written by a rename so it's never half there, and at most two kept, the current one and the one before.

use std::collections::BTreeSet;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

use crate::paths::{self, Layout};

/// The installed versions under a [`Layout`].
#[derive(Debug, Clone)]
pub struct Apps {
    layout: Layout,
}

impl Apps {
    /// The versions under `layout`.
    #[must_use]
    pub fn new(layout: Layout) -> Self {
        Self { layout }
    }

    /// The version that runs, if one is installed.
    #[must_use]
    pub fn current(&self) -> Option<String> {
        fs::read_to_string(self.layout.current_file())
            .ok()
            .map(|text| text.trim().to_owned())
            .filter(|version| !version.is_empty())
    }

    /// Makes `version` the one that runs: a temporary file, then a rename.
    ///
    /// # Errors
    /// If the version isn't installed, or the file can't be written.
    pub fn set_current(&self, version: &str) -> io::Result<()> {
        if !self.layout.app_dir(version).is_dir() {
            return Err(io::Error::new(io::ErrorKind::NotFound, format!("version {version} isn't installed")));
        }
        let temporary = self.layout.programs().join("current.tmp");
        fs::write(&temporary, format!("{version}\n"))?;
        fs::rename(&temporary, self.layout.current_file())
    }

    /// A program of an installed version.
    #[must_use]
    pub fn program(&self, version: &str, name: &str) -> PathBuf {
        self.layout.app_dir(version).join(paths::exe(name))
    }

    /// Every installed version, oldest name first.
    #[must_use]
    pub fn installed(&self) -> BTreeSet<String> {
        let Ok(entries) = fs::read_dir(self.layout.programs()) else { return BTreeSet::new() };
        entries
            .filter_map(Result::ok)
            .filter(|entry| entry.path().is_dir())
            .filter_map(|entry| entry.file_name().to_str()?.strip_prefix("app-").map(str::to_owned))
            .filter(|version| !Path::new(version).extension().is_some_and(|e| e.eq_ignore_ascii_case("tmp")))
            .collect()
    }

    /// Unpacks an app zip into `app-<version>`: into a temporary folder first, renamed into place once whole. Only the
    /// five programs (and their licence notices) are taken, flat, so nothing can be written outside the folder. An
    /// existing folder for that version is replaced.
    ///
    /// # Errors
    /// If the zip can't be read, holds a path, or lacks one of the programs.
    pub fn install_zip(&self, zip: &Path, version: &str) -> io::Result<PathBuf> {
        let target = self.layout.app_dir(version);
        let temporary = self.layout.programs().join(format!("app-{version}.tmp"));
        let _ = fs::remove_dir_all(&temporary);
        fs::create_dir_all(&temporary)?;
        let mut archive = zip::ZipArchive::new(fs::File::open(zip)?).map_err(io::Error::other)?;
        for index in 0..archive.len() {
            let mut entry = archive.by_index(index).map_err(io::Error::other)?;
            if entry.is_dir() {
                continue;
            }
            let name = entry.name().map_err(io::Error::other)?.into_owned();
            if name.contains(['/', '\\']) || name.contains("..") || name.starts_with('.') {
                return Err(io::Error::new(io::ErrorKind::InvalidData, format!("the zip holds a path: {name}")));
            }
            let mut bytes = Vec::new();
            entry.read_to_end(&mut bytes)?;
            fs::write(temporary.join(&name), bytes)?;
        }
        for program in paths::PROGRAMS {
            if !temporary.join(paths::exe(program)).is_file() {
                let _ = fs::remove_dir_all(&temporary);
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("the zip has no {}", paths::exe(program)),
                ));
            }
        }
        let _ = fs::remove_dir_all(&target);
        fs::rename(&temporary, &target)?;
        Ok(target)
    }

    /// Removes every installed version but `keep`.
    ///
    /// # Errors
    /// If a folder can't be removed.
    pub fn keep_only(&self, keep: &[&str]) -> io::Result<()> {
        for version in self.installed() {
            if !keep.contains(&version.as_str()) {
                fs::remove_dir_all(self.layout.app_dir(&version))?;
            }
        }
        Ok(())
    }

    /// Stages `version`'s launcher as `idleo-launcher.new.exe` if it differs from the one in place, for the launcher
    /// to swap itself at its next start. Returns whether it staged one.
    ///
    /// # Errors
    /// If a file can't be read or copied.
    pub fn stage_launcher(&self, version: &str) -> io::Result<bool> {
        let shipped = self.program(version, paths::LAUNCHER_EXE);
        let in_place = self.layout.launcher();
        if fs::read(&shipped)? == fs::read(&in_place).unwrap_or_default() {
            return Ok(false);
        }
        fs::copy(&shipped, self.layout.launcher_staged())?;
        Ok(true)
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use std::io::Write;

    use super::*;

    /// An app zip holding the five programs, each file's content naming it and the version.
    pub(crate) fn app_zip(dir: &Path, version: &str) -> PathBuf {
        let path = dir.join(format!("idleo-app-{version}.zip"));
        let mut zip = zip::ZipWriter::new(fs::File::create(&path).unwrap());
        for program in paths::PROGRAMS {
            zip.start_file(paths::exe(program), zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(format!("{program} {version}").as_bytes()).unwrap();
        }
        zip.finish().unwrap();
        path
    }

    #[test]
    fn installs_switches_and_keeps_two() {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::under(root.path());
        fs::create_dir_all(layout.programs()).unwrap();
        let apps = Apps::new(layout.clone());
        assert_eq!(apps.current(), None);
        assert!(apps.set_current("0.1.0").is_err(), "not installed");

        for version in ["0.1.0", "0.2.0", "0.3.0"] {
            apps.install_zip(&app_zip(root.path(), version), version).unwrap();
        }
        apps.set_current("0.3.0").unwrap();
        assert_eq!(apps.current().as_deref(), Some("0.3.0"));
        assert_eq!(fs::read_to_string(apps.program("0.2.0", paths::AGENT_EXE)).unwrap(), "idleo-agent 0.2.0");
        apps.keep_only(&["0.3.0", "0.2.0"]).unwrap();
        assert_eq!(apps.installed().into_iter().collect::<Vec<_>>(), ["0.2.0", "0.3.0"]);
        assert!(!layout.programs().join("current.tmp").exists());
    }

    #[test]
    fn a_zip_with_a_path_or_a_missing_program_is_refused() {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::under(root.path());
        fs::create_dir_all(layout.programs()).unwrap();
        let apps = Apps::new(layout.clone());

        let evil = root.path().join("evil.zip");
        let mut zip = zip::ZipWriter::new(fs::File::create(&evil).unwrap());
        zip.start_file("../../Startup/evil.exe", zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(b"x").unwrap();
        zip.finish().unwrap();
        assert!(apps.install_zip(&evil, "0.9.0").is_err());

        let short = root.path().join("short.zip");
        let mut zip = zip::ZipWriter::new(fs::File::create(&short).unwrap());
        zip.start_file(paths::exe(paths::AGENT_EXE), zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(b"x").unwrap();
        zip.finish().unwrap();
        assert!(apps.install_zip(&short, "0.9.0").is_err());
        assert!(apps.installed().is_empty());
    }

    #[test]
    fn a_launcher_is_staged_only_when_it_changed() {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::under(root.path());
        fs::create_dir_all(layout.programs()).unwrap();
        let apps = Apps::new(layout.clone());
        apps.install_zip(&app_zip(root.path(), "0.1.0"), "0.1.0").unwrap();
        fs::copy(apps.program("0.1.0", paths::LAUNCHER_EXE), layout.launcher()).unwrap();
        assert!(!apps.stage_launcher("0.1.0").unwrap());
        apps.install_zip(&app_zip(root.path(), "0.2.0"), "0.2.0").unwrap();
        assert!(apps.stage_launcher("0.2.0").unwrap());
        assert_eq!(fs::read_to_string(layout.launcher_staged()).unwrap(), "idleo-launcher 0.2.0");
    }
}
