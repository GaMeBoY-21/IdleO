// Compiles proto/gpu/agent/v1 with protox, a pure-Rust protobuf compiler, so a protoc on PATH is
// never used.
use std::path::PathBuf;

const FILES: [&str; 4] = [
    "gpu/agent/v1/agent_gateway.proto",
    "gpu/agent/v1/enrollment.proto",
    "gpu/agent/v1/session.proto",
    "gpu/agent/v1/telemetry.proto",
];

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let proto_root = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?).join("../../proto");
    println!("cargo:rerun-if-changed={}", proto_root.display());
    let descriptors = protox::compile(FILES, [&proto_root])?;
    // No transport helpers: the agent dials with its own TLS 1.3 connector.
    tonic_prost_build::configure().build_transport(false).compile_fds(descriptors)?;
    Ok(())
}
