//! The image scanner (ADR 0042): checks a renter's own container image on our server before it can reach a host. It runs
//! in a container of its own (scanner/Dockerfile), with no database access, no storage keys and nothing on its root it can
//! write, takes work from the control plane's internal API, and reports facts; the control plane decides the verdict.
#![forbid(unsafe_code)]

pub mod check;
pub mod control;
pub mod guard;
pub mod layers;
pub mod reference;
pub mod registry;
pub mod tar;
pub mod trivy;

use std::sync::Arc;

/// Lower-case hex.
#[must_use]
pub fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}

/// A size as people read it: "8.4 GB" (10^9 bytes, as registries and the web app show them).
#[must_use]
pub fn gigabytes(bytes: u64) -> String {
    let tenths = (bytes + 50_000_000) / 100_000_000;
    if tenths >= 10 || bytes == 0 {
        format!("{}.{} GB", tenths / 10, tenths % 10)
    } else {
        format!("{} MB", bytes.div_ceil(1_000_000))
    }
}

/// TLS with the operating system's trust store (the scanner image's CA certificates), as the CLI and the agent do.
///
/// # Panics
/// If the platform's verifier can't be built, which leaves the scanner nothing to talk over.
#[must_use]
pub fn tls_config() -> rustls::ClientConfig {
    use rustls_platform_verifier::BuilderVerifierExt;
    rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .and_then(BuilderVerifierExt::with_platform_verifier)
        .expect("the platform's certificate verifier")
        .with_no_client_auth()
}

#[cfg(test)]
mod tests {
    #[test]
    fn sizes_read_as_people_say_them() {
        assert_eq!(super::gigabytes(8_400_000_000), "8.4 GB");
        assert_eq!(super::gigabytes(15_000_000_000), "15.0 GB");
        assert_eq!(super::gigabytes(312_000_000), "312 MB");
    }
}
