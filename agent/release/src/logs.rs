//! The programs' log files (ADR 0061): each one a few megabytes at most, and scrubbed of the person and the machine as it
//! is written, so a log the host sends to support never names them.
//!
//! A log is `name.log`, which grows to [`DEFAULT_MAX_BYTES`]; then it becomes `name.log.1`, `.1` becomes `.2`, and the
//! oldest beyond [`DEFAULT_KEEP`] is deleted. So the most a program ever keeps is `(DEFAULT_KEEP + 1)` times the limit:
//! 3 MiB.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, PoisonError};

/// How big a log file grows before it is rotated.
pub const DEFAULT_MAX_BYTES: u64 = 1024 * 1024;
/// How many older files are kept beside the current one.
pub const DEFAULT_KEEP: usize = 2;

type Scrubber = Box<dyn Fn(&str) -> String + Send + Sync>;

struct State {
    file: Option<File>,
    size: u64,
}

/// A rotating, scrubbing log file. Usable as a `tracing` writer through `Arc<RotatingLog>`.
pub struct RotatingLog {
    path: PathBuf,
    max_bytes: u64,
    keep: usize,
    scrub: Option<Scrubber>,
    state: Mutex<State>,
}

impl RotatingLog {
    /// A log at `path` (its folder is made when it is first written), rotated at `max_bytes`, keeping `keep` older files.
    #[must_use]
    pub fn new(path: &Path, max_bytes: u64, keep: usize) -> Self {
        Self { path: path.to_owned(), max_bytes, keep, scrub: None, state: Mutex::new(State { file: None, size: 0 }) }
    }

    /// The same, with the standard limits, scrubbing everything written with `scrub` (a closure over the person's
    /// [`crate::scrub::Identity`]).
    #[must_use]
    pub fn standard(path: &Path, scrub: impl Fn(&str) -> String + Send + Sync + 'static) -> Self {
        let mut log = Self::new(path, DEFAULT_MAX_BYTES, DEFAULT_KEEP);
        log.scrub = Some(Box::new(scrub));
        log
    }

    /// The file `n` generations back: 0 is the current one.
    fn generation(&self, n: usize) -> PathBuf {
        if n == 0 {
            return self.path.clone();
        }
        let mut name = self.path.file_name().map(std::ffi::OsString::from).unwrap_or_default();
        name.push(format!(".{n}"));
        self.path.with_file_name(name)
    }

    /// Every file this log may have on disk, newest first.
    #[must_use]
    pub fn files(&self) -> Vec<PathBuf> {
        (0..=self.keep).map(|n| self.generation(n)).filter(|path| path.exists()).collect()
    }

    fn open(&self, state: &mut State) -> io::Result<()> {
        if state.file.is_some() {
            return Ok(());
        }
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new().create(true).append(true).open(&self.path)?;
        state.size = file.metadata().map_or(0, |meta| meta.len());
        state.file = Some(file);
        Ok(())
    }

    fn rotate(&self, state: &mut State) {
        state.file = None;
        let _ = fs::remove_file(self.generation(self.keep));
        for n in (0..self.keep).rev() {
            let _ = fs::rename(self.generation(n), self.generation(n + 1));
        }
        if self.keep == 0 {
            let _ = fs::remove_file(&self.path);
        }
        state.size = 0;
    }

    fn append(&self, bytes: &[u8]) -> io::Result<()> {
        let scrubbed;
        let bytes = match &self.scrub {
            Some(scrub) => {
                scrubbed = scrub(&String::from_utf8_lossy(bytes));
                scrubbed.as_bytes()
            }
            None => bytes,
        };
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        self.open(&mut state)?;
        if state.size > 0 && state.size + bytes.len() as u64 > self.max_bytes {
            self.rotate(&mut state);
            self.open(&mut state)?;
        }
        if let Some(file) = state.file.as_mut() {
            file.write_all(bytes)?;
            state.size += bytes.len() as u64;
        }
        Ok(())
    }
}

impl Write for &RotatingLog {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.append(buf)?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        state.file.as_mut().map_or(Ok(()), Write::flush)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scrub::{Identity, scrub};

    fn size(dir: &Path) -> u64 {
        fs::read_dir(dir).unwrap().filter_map(Result::ok).map(|entry| entry.metadata().unwrap().len()).sum()
    }

    #[test]
    fn a_log_never_grows_past_its_limit_times_its_generations() {
        let dir = tempfile::tempdir().unwrap();
        let log = RotatingLog::new(&dir.path().join("hostd.log"), 1_000, 2);
        let line = format!("{}\n", "x".repeat(99));
        for _ in 0..500 {
            (&log).write_all(line.as_bytes()).unwrap();
        }
        let names: Vec<String> = {
            let mut names: Vec<_> = fs::read_dir(dir.path())
                .unwrap()
                .filter_map(Result::ok)
                .map(|entry| entry.file_name().to_string_lossy().into_owned())
                .collect();
            names.sort();
            names
        };
        assert_eq!(names, ["hostd.log", "hostd.log.1", "hostd.log.2"]);
        assert!(size(dir.path()) <= 3 * 1_000, "{}", size(dir.path()));
        assert_eq!(log.files().len(), 3);
    }

    #[test]
    fn the_newest_lines_are_in_the_current_file_and_the_oldest_are_dropped() {
        let dir = tempfile::tempdir().unwrap();
        let log = RotatingLog::new(&dir.path().join("a.log"), 100, 1);
        for n in 0..40 {
            (&log).write_all(format!("line {n:02}\n").as_bytes()).unwrap();
        }
        let current = fs::read_to_string(dir.path().join("a.log")).unwrap();
        let older = fs::read_to_string(dir.path().join("a.log.1")).unwrap();
        assert!(current.contains("line 39") && !current.contains("line 00"));
        assert!(older.contains("line 2") && !older.contains("line 00"), "{older}");
        assert!(!dir.path().join("a.log.2").exists());
    }

    #[test]
    fn a_log_picks_up_where_the_last_run_stopped() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("a.log");
        fs::write(&path, "x".repeat(90)).unwrap();
        let log = RotatingLog::new(&path, 100, 1);
        (&log).write_all(b"0123456789abc").unwrap();
        assert!(dir.path().join("a.log.1").exists(), "the old run's 90 bytes count toward the limit");
        assert_eq!(fs::read_to_string(&path).unwrap(), "0123456789abc");
    }

    #[test]
    fn what_is_written_never_names_the_person_or_the_machine() {
        let dir = tempfile::tempdir().unwrap();
        let who = Identity { profile: r"C:\Users\Asha Rao".into(), user: "Asha".into(), machine: "TESTRIG-7".into() };
        let log = RotatingLog::standard(&dir.path().join("tray.log"), move |text| scrub(text, &who));
        (&log).write_all(br"opened C:\Users\Asha Rao\AppData\Local\IdleO\agent on TESTRIG-7 for Asha").unwrap();
        let written = fs::read_to_string(dir.path().join("tray.log")).unwrap();
        assert_eq!(written, r"opened %USERPROFILE%\AppData\Local\IdleO\agent on <machine> for <user>");
    }
}
