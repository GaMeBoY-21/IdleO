//! The agent protocol, generated at build time from `proto/gpu/agent/v1`. Nothing here is written
//! by hand, and nothing generated is committed.
#![forbid(unsafe_code)]

/// Package `gpu.agent.v1`.
#[allow(
    // Generated code: its style is prost's and tonic's, not ours.
    clippy::all,
    clippy::pedantic
)]
pub mod v1 {
    tonic::include_proto!("gpu.agent.v1");
}
