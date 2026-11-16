//! The host agent's only `unsafe` code: the Windows API calls that have no safe wrapper. Every `unsafe` block is in a
//! module below and says why it is sound. On other systems only the pure helpers ([`private_file_sddl`],
//! [`pipe_sddl`]) exist.
//!
//! - `windows`: time since the last keyboard or mouse input (`GetLastInputInfo`), the Windows build, free disk space,
//!   the lease clock (`QueryPerformanceCounter`), and creating a file whose access list allows only SYSTEM and the
//!   signed-in owner, applied atomically at creation, for `host.key`.
//! - `session`: the owner's SID, whether a game or full-screen app is open (`SHQueryUserNotificationState`), the power
//!   line (`GetSystemPowerStatus`), and whether firmware virtualization is on.
//! - `network`: whether the internet connection is metered (the `WinRT` `NetworkInformation` API, which needs no `unsafe`).
//! - `toast`: Windows toasts (the `WinRT` `ToastNotification` API, which needs no `unsafe` either).
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
mod network;

#[cfg(windows)]
#[allow(unsafe_code)] // See the crate docs.
pub mod registry;

#[cfg(windows)]
mod toast;

#[cfg(windows)]
#[allow(unsafe_code)] // See the crate docs.
mod shell;

#[cfg(windows)]
pub use crate::network::connection_is_metered;
#[cfg(windows)]
pub use crate::session::{ac_line_status, current_user_sid, user_notification_state, virtualization_enabled};
#[cfg(windows)]
pub use crate::shell::{Progress, ask, create_pipe_server, create_shortcut, message_box, progress};
#[cfg(windows)]
pub use crate::toast::toast;
#[cfg(windows)]
pub use crate::windows::{
    create_private_file, file_access_sddl, free_disk_space, idle_time, is_arm, is_s_mode, performance_counter,
    system_manufacturer, total_memory_mb, windows_build,
};

/// `text` made safe to put inside an XML element or attribute (a toast's words come from the agent's status, which carries
/// nothing a person typed, but the XML is built here and must never be breakable).
#[must_use]
pub fn xml_escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            c if c.is_control() && c != '\n' => {}
            c => out.push(c),
        }
    }
    out
}

/// A toast's XML: two lines of text, and `launch` as the address a click opens (by protocol activation).
#[must_use]
pub fn toast_xml(title: &str, text: &str, launch: &str) -> String {
    format!(
        "<toast activationType=\"protocol\" launch=\"{}\"><visual><binding template=\"ToastGeneric\"><text>{}</text><text>{}</text></binding></visual></toast>",
        xml_escape(launch),
        xml_escape(title),
        xml_escape(text)
    )
}

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

    #[test]
    fn what_goes_in_a_toasts_xml_cannot_break_out_of_it() {
        assert_eq!(
            xml_escape(r#"<b>"Tom" & 'Jerry'</b>"#),
            "&lt;b&gt;&quot;Tom&quot; &amp; &apos;Jerry&apos;&lt;/b&gt;"
        );
        assert_eq!(xml_escape("a\u{0}b"), "ab");
        let xml = toast_xml("Payout sent", "₹999.00 is on its way", "idleo:open/earnings");
        assert!(xml.contains("launch=\"idleo:open/earnings\"") && xml.contains("<text>₹999.00 is on its way</text>"));
        assert!(!toast_xml("\"><evil/>", "x", "y").contains("<evil/>"));
    }
}
