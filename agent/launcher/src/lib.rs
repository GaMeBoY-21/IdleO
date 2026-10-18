//! The Windows app's launcher (ADR 0033, ADR 0034): the one program Windows knows about. The Run entry starts it at
//! sign-in, the Start-menu shortcut opens it, and the Uninstall entry runs it. It is small and rarely changes.
//!
//! - [`supervise`]: starts `idleo-hostd` and `idleo-tray` from the version `current` names and restarts them when they
//!   stop; runs an update's trial and rolls it back if the new version crashes three times or isn't confirmed in five
//!   minutes.
//! - [`self_update`]: replaces itself only by rename, at its next start.
//! - [`uninstall`]: stops a renter's job after its checkpoint, tells IdleO, and removes everything of ours.
#![forbid(unsafe_code)]

pub mod self_update;
pub mod supervise;
pub mod uninstall;
