//! `IdleO-Setup.exe`, the one download (ADR 0033): a small window (WebView2, with its page built into the program)
//! that checks the laptop, turns on WSL, downloads and installs the app and its sealed Linux space, self-tests, links
//! the laptop by approval in the browser and starts hosting. The setup itself carries no payload: it downloads the
//! signed release.
//!
//! The steps are [`steps`], against [`system::System`]; the window's messages are [`ipc`]. The window itself is the
//! Windows program's.
#![forbid(unsafe_code)]

pub mod ipc;
pub mod steps;
pub mod system;

/// Where this build points: the addresses and root `build.rs` baked in.
pub mod config {
    include!(concat!(env!("OUT_DIR"), "/setup_config.rs"));
}

#[cfg(test)]
mod tests;
