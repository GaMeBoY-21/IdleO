//! Every reason code setup can send in an install event (`POST /v1/host-installs/{id}/events`), in one place.
//!
//! A reason is a short code, never prose: the server accepts only [`PATTERN`] up to [`MAX_LEN`] characters
//! (`HostInstallController`), so a failure's words travel in the event's `detail` instead. [`examples`] holds one of
//! every code, with the codes that carry a value filled in; `api/install-reasons.json` is its committed copy, which the
//! control plane's tests read and check against its own pattern, so the two can't drift apart. After adding a code,
//! rewrite the copy with `IDLEO_REASONS_WRITE=1 cargo test -p setup reasons`.

/// What the server accepts, as a regular expression (the control plane's `REASON`).
pub const PATTERN: &str = r"[a-z0-9_:.\-]+";
/// The longest reason the server accepts.
pub const MAX_LEN: usize = 120;

/// The buttons a person presses (`click` events).
pub const CLICK_SET_UP: &str = "set_up";
/// Repair, on the first screen.
pub const CLICK_REPAIR: &str = "repair";
/// Try again, after something couldn't be reached or a link didn't go through.
pub const CLICK_TRY_AGAIN: &str = "try_again";
/// The approval was seen.
pub const CLICK_APPROVE_SEEN: &str = "approve_seen";
/// Restart now.
pub const CLICK_RESTART_NOW: &str = "restart_now";
/// Keep it for troubleshooting (development builds).
pub const CLICK_KEEP: &str = "keep";

/// Plugged-in warning.
pub const ON_BATTERY: &str = "on_battery";
/// No NVIDIA RTX GPU with 8 GB.
pub const NO_RTX_GPU: &str = "no_rtx_gpu";
/// Virtualization is off.
pub const VIRTUALIZATION_OFF: &str = "virtualization_off";
/// The manifest didn't verify.
pub const MANIFEST_INVALID: &str = "manifest_invalid";
/// IdleO couldn't be reached.
pub const UNREACHABLE: &str = "unreachable";
/// Updating WSL failed.
pub const WSL_UPDATE: &str = "wsl_update";
/// Turning WSL on failed.
pub const WSL_INSTALL: &str = "wsl_install";
/// A download didn't match the release.
pub const DOWNLOAD_VERIFY: &str = "download_verify";
/// A download failed.
pub const DOWNLOAD: &str = "download";
/// Installing the programs failed.
pub const INSTALL: &str = "install";
/// Importing the distribution failed.
pub const DISTRO_IMPORT: &str = "distro_import";
/// The self-test couldn't be run or its answer read.
pub const SELF_TEST_RUN: &str = "self_test_run";
/// The owner didn't approve the link.
pub const LINK_DENIED: &str = "link_denied";
/// The link ran out.
pub const LINK_EXPIRED: &str = "link_expired";
/// The link couldn't reach IdleO.
pub const LINK_NETWORK: &str = "link_network";
/// The link failed some other way.
pub const LINK: &str = "link";
/// The launcher couldn't be started.
pub const START_LAUNCHER: &str = "start_launcher";
/// The agent never said where the host stands.
pub const START_LISTED: &str = "start_listed";

/// A development build's environment facts about the laptop (WSL, kernel, driver, memory).
pub const FACTS_SYSTEM: &str = "facts_system";
/// A development build's facts about what the distribution can reach.
pub const FACTS_NETWORK: &str = "facts_network";

/// `windows_build_19044`.
#[must_use]
pub fn windows_build(build: u32) -> String {
    format!("windows_build_{build}")
}

/// `driver_too_old:572.16`.
#[must_use]
pub fn driver_too_old(driver: &str) -> String {
    format!("driver_too_old:{}", code(driver))
}

/// `disk_23_gb`: the free disk found.
#[must_use]
pub fn disk(free_gb: u64) -> String {
    format!("disk_{free_gb}_gb")
}

/// A fix screen was shown or fixed: `fix:no_rtx_gpu`.
#[must_use]
pub fn fix(problem: &str) -> String {
    format!("fix:{problem}")
}

/// A self-test check that failed, by its name: `gpu_in_container`.
#[must_use]
pub fn check(name: &str) -> String {
    code(name)
}

/// Where the host stood when listed: `pendingapproval`, `active`.
#[must_use]
pub fn listed(state: &str) -> String {
    code(state)
}

/// Anything made into a code: lowercase letters, digits and `_:.-`, the rest as `_`, at most [`MAX_LEN`] long.
#[must_use]
pub fn code(text: &str) -> String {
    let mut out: String =
        text.to_lowercase()
            .chars()
            .map(|c| {
                if c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | ':' | '.' | '-') { c } else { '_' }
            })
            .take(MAX_LEN)
            .collect();
    if out.is_empty() {
        out.push('_');
    }
    out
}

/// Whether the server would accept `reason`: [`PATTERN`] in full, at most [`MAX_LEN`] long.
#[must_use]
pub fn is_valid(reason: &str) -> bool {
    !reason.is_empty()
        && reason.len() <= MAX_LEN
        && reason.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | ':' | '.' | '-'))
}

/// One of every code setup sends.
#[must_use]
pub fn examples() -> Vec<String> {
    let mut all: Vec<String> = [
        CLICK_SET_UP,
        CLICK_REPAIR,
        CLICK_TRY_AGAIN,
        CLICK_APPROVE_SEEN,
        CLICK_RESTART_NOW,
        CLICK_KEEP,
        ON_BATTERY,
        NO_RTX_GPU,
        VIRTUALIZATION_OFF,
        MANIFEST_INVALID,
        UNREACHABLE,
        WSL_UPDATE,
        WSL_INSTALL,
        DOWNLOAD_VERIFY,
        DOWNLOAD,
        INSTALL,
        DISTRO_IMPORT,
        SELF_TEST_RUN,
        LINK_DENIED,
        LINK_EXPIRED,
        LINK_NETWORK,
        LINK,
        START_LAUNCHER,
        START_LISTED,
        FACTS_SYSTEM,
        FACTS_NETWORK,
    ]
    .map(str::to_owned)
    .to_vec();
    all.extend([
        windows_build(19_044),
        driver_too_old("572.16"),
        driver_too_old("A driver, 5 & more!"),
        disk(23),
        fix("windows_too_old"),
        fix("driver_too_old"),
        fix("virtualization_off"),
        fix("no_rtx_gpu"),
        fix("not_enough_disk"),
        check("gpu_in_container"),
        check("Lockdown"),
        listed("PendingApproval"),
        listed("Active"),
    ]);
    all
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `api/install-reasons.json`: the pattern, the limit and every example, for the control plane's tests.
    fn fixture_path() -> std::path::PathBuf {
        std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../api/install-reasons.json")
    }

    fn generated() -> String {
        let value = serde_json::json!({ "pattern": PATTERN, "maxLength": MAX_LEN, "reasons": examples() });
        serde_json::to_string_pretty(&value).unwrap() + "\n"
    }

    #[test]
    fn every_example_is_a_reason_the_server_accepts() {
        for reason in examples() {
            assert!(is_valid(&reason), "{reason:?} would be refused");
        }
    }

    #[test]
    fn anything_made_into_a_code_is_valid() {
        for text in ["", "Has Spaces & Symbols!", "ÜBER", &"x".repeat(500), "C:\\Users\\nikki"] {
            let made = code(text);
            assert!(is_valid(&made), "{made:?}");
        }
    }

    #[test]
    fn the_committed_list_is_this_one() {
        if std::env::var_os("IDLEO_REASONS_WRITE").is_some() {
            std::fs::write(fixture_path(), generated()).unwrap();
        }
        assert_eq!(
            std::fs::read_to_string(fixture_path()).unwrap(),
            generated(),
            "api/install-reasons.json is out of date; run IDLEO_REASONS_WRITE=1 cargo test -p setup reasons"
        );
    }
}
