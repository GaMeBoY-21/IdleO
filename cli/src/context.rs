//! What every command is given: the API client, the key store, and how to talk to the person. In `--json` mode
//! nothing but the JSON goes to stdout; every other word goes to stderr.

use std::io::Write;

use serde::Serialize;

use crate::client::Client;
use crate::keystore::Keystore;

pub struct Context {
    pub client: Client,
    pub keys: Keystore,
    pub json: bool,
}

impl Context {
    /// A line for the person: stdout, or stderr when stdout is for JSON.
    pub fn say(&self, line: impl AsRef<str>) {
        if self.json {
            let _ = writeln!(std::io::stderr(), "{}", line.as_ref());
        } else {
            let _ = writeln!(std::io::stdout(), "{}", line.as_ref());
        }
    }

    /// Progress the person watches (a live log, a percentage): always stderr, so it never mixes with output.
    pub fn note(&self, line: impl AsRef<str>) {
        let _ = writeln!(std::io::stderr(), "{}", line.as_ref());
    }

    /// A session's log text, as it came, with no line added: stdout, or stderr when stdout is for JSON.
    pub fn log(&self, text: &str) {
        if self.json {
            let _ = write!(std::io::stderr(), "{text}");
        } else {
            let _ = write!(std::io::stdout(), "{text}");
            let _ = std::io::stdout().flush();
        }
    }

    /// The command's result: as JSON on stdout in `--json` mode, otherwise `human` is printed.
    ///
    /// # Panics
    /// Never: the API's own types always serialise.
    pub fn result<T: Serialize>(&self, value: &T, human: impl FnOnce() -> String) {
        let text = if self.json { serde_json::to_string_pretty(value).expect("API types are JSON") } else { human() };
        let _ = writeln!(std::io::stdout(), "{text}");
    }
}
