//! The setup log: `%LOCALAPPDATA%\IdleO\logs\setup-<date-time>.log` (UTC), one per run of setup. Every step's start and
//! end with its duration, every command with its exit code and its output (4 KB at most each), the self-test's JSON and
//! the answer to any report setup couldn't make. It stays on the laptop with the words as they were; what is sent to
//! IdleO is scrubbed first ([`crate::scrub`]). A release build's undo deletes it with the rest; a development build's
//! keeps the `logs` folder.

use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Instant;

use idleo_release::wsl::{Output, Runner};

/// The most of one command's output the log keeps.
pub const MAX_OUTPUT: usize = 4 * 1024;

/// The log file, shared with the runner that logs commands. Written as it goes, so a crash leaves what happened.
#[derive(Debug, Clone)]
pub struct SetupLog {
    inner: Arc<Mutex<Inner>>,
}

#[derive(Debug)]
struct Inner {
    path: PathBuf,
    file: Option<std::fs::File>,
}

impl SetupLog {
    /// A log for the run starting at `at`, in `logs`. The file is made with the first line.
    #[must_use]
    pub fn new(logs: &Path, at: time::OffsetDateTime) -> Self {
        let name = format!(
            "setup-{:04}{:02}{:02}-{:02}{:02}{:02}.log",
            at.year(),
            u8::from(at.month()),
            at.day(),
            at.hour(),
            at.minute(),
            at.second()
        );
        Self { inner: Arc::new(Mutex::new(Inner { path: logs.join(name), file: None })) }
    }

    /// Where it is.
    #[must_use]
    pub fn path(&self) -> PathBuf {
        self.lock().path.clone()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// One line, stamped with the time (UTC). Never fails: a log that can't be written is not worth stopping for.
    pub fn line(&self, text: &str) {
        let stamp =
            time::OffsetDateTime::now_utc().format(&time::format_description::well_known::Rfc3339).unwrap_or_default();
        self.write(&format!("{stamp} {text}\n"));
    }

    /// A titled block: a self-test's JSON, a command's output.
    pub fn block(&self, title: &str, body: &str) {
        self.line(title);
        let body = trimmed(body, MAX_OUTPUT);
        for line in body.lines() {
            self.write(&format!("    {line}\n"));
        }
    }

    /// A step began.
    pub fn step_started(&self, step: &str) {
        self.line(&format!("step {step}: started"));
    }

    /// A step ended, `how` being `passed` or `failed`, with its reason and detail if it failed.
    pub fn step_ended(&self, step: &str, how: &str, took: std::time::Duration, why: Option<&str>) {
        let why = why.map(|why| format!(" ({})", trimmed(why, MAX_OUTPUT))).unwrap_or_default();
        self.line(&format!("step {step}: {how} after {} ms{why}", took.as_millis()));
    }

    /// A command and what it did.
    pub fn command(&self, program: &str, args: &[&str], result: &std::io::Result<Output>, took: std::time::Duration) {
        let command = std::iter::once(program).chain(args.iter().copied()).collect::<Vec<_>>().join(" ");
        match result {
            Ok(output) => {
                self.line(&format!("ran `{command}`: exit {} after {} ms", output.code, took.as_millis()));
                if !output.stdout.trim().is_empty() {
                    self.block("  stdout:", &output.stdout);
                }
                if !output.stderr.trim().is_empty() {
                    self.block("  stderr:", &output.stderr);
                }
            }
            Err(e) => self.line(&format!("ran `{command}`: couldn't be started: {e}")),
        }
    }

    fn write(&self, text: &str) {
        let mut inner = self.lock();
        if inner.file.is_none() {
            if let Some(parent) = inner.path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            inner.file = std::fs::OpenOptions::new().create(true).append(true).open(&inner.path).ok();
        }
        if let Some(file) = inner.file.as_mut() {
            let _ = file.write_all(text.as_bytes());
        }
    }
}

/// `text` cut to `max` bytes at a character boundary, saying how much was left out.
#[must_use]
pub fn trimmed(text: &str, max: usize) -> String {
    if text.len() <= max {
        return text.trim_end().to_owned();
    }
    let mut end = max;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}\n… ({} more bytes)", text[..end].trim_end(), text.len() - end)
}

/// Runs everything through `inner`, logging each command.
pub struct LoggingRunner<'a> {
    inner: &'a dyn Runner,
    log: SetupLog,
}

impl<'a> LoggingRunner<'a> {
    /// `inner`, logged to `log`.
    #[must_use]
    pub fn new(inner: &'a dyn Runner, log: SetupLog) -> Self {
        Self { inner, log }
    }
}

impl Runner for LoggingRunner<'_> {
    fn run(&self, program: &str, args: &[&str]) -> std::io::Result<Output> {
        let started = Instant::now();
        let result = self.inner.run(program, args);
        self.log.command(program, args, &result, started.elapsed());
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn output_is_cut_to_four_kilobytes_on_a_character_boundary() {
        let text = "é".repeat(5_000);
        let out = trimmed(&text, MAX_OUTPUT);
        assert!(out.len() < MAX_OUTPUT + 40, "{}", out.len());
        assert!(out.ends_with("more bytes)"));
        assert_eq!(trimmed("short  \n", MAX_OUTPUT), "short");
    }

    #[test]
    fn the_file_is_named_by_the_time_and_made_with_the_first_line() {
        let dir = tempfile::tempdir().unwrap();
        let at = time::OffsetDateTime::from_unix_timestamp(1_760_790_000).unwrap();
        let log = SetupLog::new(&dir.path().join("logs"), at);
        assert!(!log.path().exists());
        log.step_started("checks");
        let name = log.path().file_name().unwrap().to_string_lossy().into_owned();
        assert_eq!(name, "setup-20251018-122000.log");
        assert!(std::fs::read_to_string(log.path()).unwrap().contains("step checks: started"));
    }
}
