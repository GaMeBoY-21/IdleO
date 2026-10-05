//! Whether the machine's owner is using it right now, behind [`OwnerActivity`].

use std::time::Duration;

/// Input within this long counts as the owner being active.
pub const ACTIVE_WITHIN: Duration = Duration::from_secs(120);

/// Reports whether the owner is at the machine.
pub trait OwnerActivity: Send + Sync {
    /// True if the owner is using the machine right now.
    fn is_active(&self) -> bool;
}

/// The operating system's view of the owner:
/// - Windows: keyboard or mouse input within the last 2 minutes (`GetLastInputInfo`).
/// - Linux and macOS: always false for now.
#[derive(Debug, Default, Clone, Copy)]
pub struct SystemOwner;

impl OwnerActivity for SystemOwner {
    #[cfg(windows)]
    fn is_active(&self) -> bool {
        match agent_windows::idle_time() {
            Ok(idle) => idle < ACTIVE_WITHIN,
            Err(e) => {
                tracing::warn!(error = %e, "couldn't read the last input time; reporting the owner as active");
                true
            }
        }
    }

    #[cfg(not(windows))]
    fn is_active(&self) -> bool {
        false
    }
}
