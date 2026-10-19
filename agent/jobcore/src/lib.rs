//! What every runner of a session's job shares, so no rule is written twice: object storage through a session's
//! grants ([`storage`]), staging a job's inputs ([`staging`]), the log sink ([`logs`]), completion markers
//! ([`manifest`]), the lease clock ([`lease_clock`]) and the conversation between the agent and `idleo-runner`
//! ([`protocol`]). The agent, the development runner and `idleo-runner` all use it.
//!
//! Free of `unsafe`: the only unsafe code is in the `agent-windows` crate.
#![forbid(unsafe_code)]

#[cfg(any(test, feature = "testing"))]
pub mod fakes3;
pub mod lease_clock;
pub mod link;
pub mod logs;
pub mod manifest;
pub mod protocol;
pub mod staging;
pub mod storage;
pub mod tls;
