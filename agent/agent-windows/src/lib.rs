//! The host agent's only `unsafe` code: the Windows API calls that have no safe wrapper. Every `unsafe` block is in a
//! module below and says why it is sound. On other systems only the pure helpers ([`private_file_sddl`],
//! [`pipe_sddl`]) exist.
//!
//! - `windows`: time since the last keyboard or mouse input (`GetLastInputInfo`), the Windows build, free disk space,
//!   the lease clock (`QueryPerformanceCounter`), and creating a file whose access list allows only SYSTEM and the
//!   signed-in owner, applied atomically at creation, for `host.key`.
//! - `session`: the owner's SID, whether a game or full-screen app is open (`SHQueryUserNotificationState`), the power
//!   line (`GetSystemPowerStatus`), and whether firmware virtualization is on.
//! - [`registry`]: `HKEY_CURRENT_USER` only: the Run, RunOnce and Uninstall entries.
//! - `shell`: the Start-menu shortcut, a message box, the uninstaller's task dialogs, and the local channel's named pipe
//!   whose access list allows only the owner and SYSTEM.
#![deny(unsafe_code)]

#[cfg(windows)]
#[allow(unsafe_code)] // One of the few places unsafe is allowed in the agent; see the crate docs.
mod windows;

#[cfg(windows)]
#[allow(unsafe_code)] // See the crate docs.
mod session;

#[cfg(windows)]
#[allow(unsafe_code)] // See the crate docs.
pub mod registry;

#[cfg(windows)]
#[allow(unsafe_code)] // See the crate docs.
mod shell;

#[cfg(windows)]
pub use crate::session::{ac_line_status, current_user_sid, user_notification_state, virtualization_enabled};
#[cfg(windows)]
pub use crate::shell::{Progress, ask, create_pipe_server, create_shortcut, message_box, progress};
#[cfg(windows)]
pub use crate::windows::{
    create_private_file, file_access_sddl, free_disk_space, idle_time, performance_counter, windows_build,
};

/// The access list `host.key` gets: protected from inheritance, full access for SYSTEM (`SY`) and the signed-in owner
/// whose agent made it, nobody else; not even Administrators, so the agent never needs to run elevated (ADR 0033).
#[must_use]
pub fn private_file_sddl(owner_sid: &str) -> String {
    format!("D:P(A;;FA;;;SY)(A;;FA;;;{owner_sid})")
}

/// The access list of the local channel's named pipe: SYSTEM and the signed-in owner, nobody else (ADR 0033).
#[must_use]
pub fn pipe_sddl(owner_sid: &str) -> String {
    format!("D:P(A;;GA;;;SY)(A;;GA;;;{owner_sid})")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_access_lists_name_only_system_and_the_owner() {
        assert_eq!(private_file_sddl("S-1-5-21-1-2-3-1001"), "D:P(A;;FA;;;SY)(A;;FA;;;S-1-5-21-1-2-3-1001)");
        assert_eq!(pipe_sddl("S-1-5-21-1-2-3-1001"), "D:P(A;;GA;;;SY)(A;;GA;;;S-1-5-21-1-2-3-1001)");
        assert!(!private_file_sddl("S-1-5-21-1").contains(";BA)"));
    }
}
