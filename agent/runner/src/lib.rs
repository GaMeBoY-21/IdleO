//! `idleo-runner` (ADR 0035): runs each renter's job in a container inside our sealed Linux distribution, and does
//! everything that touches the renter's job and data (staging inputs, the scratch disk, the container, the network rules,
//! logs, uploading checkpoints and outputs), so none of it ever touches the owner's Windows disk.
//!
//! The agent talks to it over one transport, a process whose stdin and stdout carry the JSON lines of
//! [`jobcore::protocol`] to the runner's Unix socket (`idleo-runner attach`).
#![forbid(unsafe_code)]
// The runner lives in our Linux distribution (Unix sockets, mounts, nftables). On Windows the crate is empty, so the workspace
// still builds and lints there.
#![cfg(unix)]

pub mod attach;
pub mod container;
pub mod crypto;
pub mod fair;
pub mod machine;
pub mod nerdctl;
pub mod safefs;
pub mod sandbox;
pub mod server;
pub mod session;
pub mod storage;
pub mod uploads;

#[cfg(test)]
pub(crate) mod session_tests;
#[cfg(test)]
mod testing;
