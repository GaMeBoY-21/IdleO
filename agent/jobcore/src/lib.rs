//! What every runner of a session's job shares, so no rule is written twice: object storage through a session's
//! grants ([`storage`]), staging a job's inputs ([`staging`]), the log sink ([`logs`]) and the lease clock
//! ([`lease_clock`]). The agent, the development runner and `idleo-runner` all use it.
//!
//! Free of `unsafe`: the only unsafe code is in the `agent-windows` crate.
#![forbid(unsafe_code)]

pub mod lease_clock;
pub mod logs;
pub mod staging;
pub mod storage;
pub mod tls;
