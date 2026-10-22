//! The IdleO host agent: links the host by its owner's approval (or enrols it with a code), then keeps one mutual-TLS
//! stream open to the control plane, sending heartbeats and renewing its certificate before it expires. On Windows it
//! also pauses itself while the owner uses the machine, talks to the tray over a local channel, and updates itself
//! (ADR 0033, ADR 0034).
//!
//! Every module here is free of `unsafe`. The agent's only unsafe code is the Windows API calls in
//! the `agent-windows` crate.
#![forbid(unsafe_code)]

pub mod app;
pub mod availability;
pub mod backoff;
pub mod care;
pub mod config;
pub mod connection;
pub mod disk;
pub mod enrol;
pub mod facts;
pub mod fair_use;
pub mod host;
pub mod link;
pub mod local;
pub mod offers;
pub mod owner;
pub mod pki;
pub mod runner;
pub mod self_test;
pub mod settings;
pub mod speed;
pub mod state;
pub mod supervisor;
pub mod system;
pub mod telemetry;
pub mod tls;
pub mod updater;
pub use idleo_release::version;
pub use jobcore::{lease_clock, logs, staging, storage};

/// The agent's own version, sent in `Hello`: the crate's, unless a release build sets `IDLEO_APP_VERSION`
/// (scripts/release/publish.sh), which every program of the Windows app is built with.
pub const AGENT_VERSION: &str = match option_env!("IDLEO_APP_VERSION") {
    Some(version) => version,
    None => env!("CARGO_PKG_VERSION"),
};

/// A new message id: a UUIDv7 in canonical text form (ADR 0007).
#[must_use]
pub fn new_message_id() -> String {
    uuid::Uuid::now_v7().to_string()
}
