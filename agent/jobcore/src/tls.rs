//! The one crypto provider the agent and the runner use, for TLS and for checking issued certificates: `ring`.

use std::sync::Arc;

use rustls::crypto::CryptoProvider;

/// The provider.
#[must_use]
pub fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}
