//! The IdleO host agent: enrols the host with a one-time code, then keeps one mutual-TLS stream
//! open to the control plane, sending heartbeats and renewing its certificate before it expires.
//!
//! Every module here is free of `unsafe`. The agent's only unsafe code is the Windows API calls in
//! the `agent-windows` crate.
#![forbid(unsafe_code)]

pub mod backoff;
pub mod config;
pub mod connection;
pub mod enrol;
pub mod lease_clock;
pub mod logs;
pub mod offers;
pub mod owner;
pub mod pki;
pub mod runner;
pub mod staging;
pub mod state;
pub mod storage;
pub mod supervisor;
pub mod system;
pub mod telemetry;
pub mod tls;
pub mod version;

/// The agent's own version, from the crate, sent in `Hello`.
pub const AGENT_VERSION: &str = env!("CARGO_PKG_VERSION");

/// A new message id: a UUIDv7 in canonical text form (ADR 0007).
#[must_use]
pub fn new_message_id() -> String {
    uuid::Uuid::now_v7().to_string()
}
