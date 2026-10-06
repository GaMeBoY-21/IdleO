//! The host agent's only `unsafe` code: a few Windows API calls that have no safe wrapper.
//!
//! - [`idle_time`]: time since the last keyboard or mouse input (`GetLastInputInfo`), for
//!   owner activity.
//! - [`windows_build`]: the Windows build number, from the registry, for `Hello`.
//! - [`create_private_file`]: creates a file whose access list allows only SYSTEM and
//!   Administrators, applied atomically at creation, for `host.key`.
//! - [`file_access_sddl`]: reads a file's access list back, to check it.
//! - [`performance_counter`]: time since Windows started, including sleep, for session leases.
//!
//! Every `unsafe` block is in the `windows` module below and says why it is sound. On other
//! systems this crate is empty.
#![deny(unsafe_code)]

#[cfg(windows)]
#[allow(unsafe_code)] // The one place unsafe is allowed in the agent; see the crate docs.
mod windows;

#[cfg(windows)]
pub use crate::windows::{create_private_file, file_access_sddl, idle_time, performance_counter, windows_build};

/// The access list `host.key` gets: protected from inheritance, full access for SYSTEM (`SY`) and
/// the Administrators group (`BA`), nobody else.
pub const PRIVATE_FILE_SDDL: &str = "D:P(A;;FA;;;SY)(A;;FA;;;BA)";
