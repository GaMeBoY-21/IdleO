//! What the Windows app's programs share (ADR 0033, ADR 0034), one implementation each: the setup app, the launcher
//! and the agent's updater all use these.
//!
//! - [`keys`]: the release key baked into this build, and what a development build refuses.
//! - [`manifest`]: the signed release manifest, verified with Ed25519.
//! - [`version`]: comparing semantic versions.
//! - [`download`]: downloading a release's file with resume, checked against its size and SHA-256.
//! - [`paths`]: where the app's programs and data live (per user, no admin rights).
//! - [`registry`]: the user's registry, and a stand-in for tests.
//! - [`install`]: what an installed IdleO is, written and removed in one place.
//! - [`apps`]: the installed versions of the app, `current`, and keeping two.
//! - [`update_state`]: an update's trial, shared by the agent and the launcher.
//! - [`wsl`]: the `wsl.exe` wrapper, and only our own distributions.
//! - [`channel`]: the local channel between `idleo-hostd`, the tray and the launcher.
//! - [`drivers`]: the NVIDIA driver the distro's CUDA needs.
#![forbid(unsafe_code)]

pub mod apps;
pub mod channel;
pub mod download;
pub mod drivers;
pub mod install;
pub mod keys;
pub mod manifest;
pub mod paths;
pub mod registry;
pub mod update_state;
pub mod version;
pub mod wsl;

/// The product's name, as Windows shows it (the Uninstall entry, the shortcut, the window titles).
pub const PRODUCT: &str = "IdleO";
