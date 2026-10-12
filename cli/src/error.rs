//! What can go wrong, and the exit code each thing gets (docs/plan/product-surface.md section 4.3): fixed per error
//! type, from the API's one problem catalog, so a script can branch on `$?`.

use std::fmt;

/// The CLI's exit codes. A command that ends well, or whose session completed, is 0.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Exit {
    Success = 0,
    /// The session failed, or the API refused something no other code names.
    Failed = 1,
    /// A command line that doesn't make sense.
    Usage = 2,
    NotEnoughCredit = 3,
    NoHostFree = 4,
    NotSignedIn = 5,
    RateLimited = 6,
    Network = 7,
}

impl Exit {
    #[must_use]
    pub fn code(self) -> i32 {
        self as i32
    }

    /// The exit code for an API problem type (its slug, `insufficient-credit` for `/problems/insufficient-credit`).
    #[must_use]
    pub fn for_problem(slug: &str) -> Self {
        match slug {
            "unauthenticated" | "invite-required" | "browser-sign-in-required" => Self::NotSignedIn,
            "insufficient-credit" => Self::NotEnoughCredit,
            "no-gpu-free" => Self::NoHostFree,
            "rate-limited" | "too-many-new-sessions" => Self::RateLimited,
            "validation-failed" | "malformed-request" => Self::Usage,
            _ => Self::Failed,
        }
    }
}

/// A failed command: what to tell the person, and how to exit.
#[derive(Debug)]
pub struct CliError {
    pub exit: Exit,
    pub message: String,
    /// The API's problem type, when the API said what was wrong; for `--json` output.
    pub problem: Option<String>,
}

impl CliError {
    #[must_use]
    pub fn new(exit: Exit, message: impl Into<String>) -> Self {
        Self { exit, message: message.into(), problem: None }
    }

    #[must_use]
    pub fn usage(message: impl Into<String>) -> Self {
        Self::new(Exit::Usage, message)
    }

    #[must_use]
    pub fn failed(message: impl Into<String>) -> Self {
        Self::new(Exit::Failed, message)
    }

    #[must_use]
    pub fn network(message: impl Into<String>) -> Self {
        Self::new(Exit::Network, message)
    }

    #[must_use]
    pub fn not_signed_in(message: impl Into<String>) -> Self {
        Self::new(Exit::NotSignedIn, message)
    }
}

impl fmt::Display for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for CliError {}

pub type Result<T> = std::result::Result<T, CliError>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_codes_are_the_tables() {
        let table = [
            (Exit::Success, 0),
            (Exit::Failed, 1),
            (Exit::Usage, 2),
            (Exit::NotEnoughCredit, 3),
            (Exit::NoHostFree, 4),
            (Exit::NotSignedIn, 5),
            (Exit::RateLimited, 6),
            (Exit::Network, 7),
        ];
        for (exit, code) in table {
            assert_eq!(exit.code(), code);
        }
    }

    #[test]
    fn each_problem_type_has_its_code() {
        assert_eq!(Exit::for_problem("insufficient-credit"), Exit::NotEnoughCredit);
        assert_eq!(Exit::for_problem("no-gpu-free"), Exit::NoHostFree);
        for slug in ["unauthenticated", "invite-required", "browser-sign-in-required"] {
            assert_eq!(Exit::for_problem(slug), Exit::NotSignedIn, "{slug}");
        }
        for slug in ["rate-limited", "too-many-new-sessions"] {
            assert_eq!(Exit::for_problem(slug), Exit::RateLimited, "{slug}");
        }
        for slug in ["validation-failed", "malformed-request"] {
            assert_eq!(Exit::for_problem(slug), Exit::Usage, "{slug}");
        }
    }

    #[test]
    fn anything_the_table_doesnt_name_is_a_plain_failure() {
        for slug in ["too-many-sessions", "input-not-accepted", "upload-in-use", "not-found", "something-new"] {
            assert_eq!(Exit::for_problem(slug), Exit::Failed, "{slug}");
        }
    }
}
