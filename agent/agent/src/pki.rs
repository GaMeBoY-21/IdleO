//! Keys, certificate signing requests, and checking a certificate the control plane issued before
//! anything is saved.

use std::fmt;
use std::path::Path;
use std::sync::Arc;

use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
use rustls::RootCertStore;
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, UnixTime};
use rustls::server::WebPkiClientVerifier;
use time::OffsetDateTime;
use uuid::Uuid;
use x509_parser::extensions::GeneralName;
use x509_parser::prelude::{FromDer, X509Certificate};

/// The URI prefix of a host certificate's single subject alternative name (ADR 0010).
pub const HOST_URN_PREFIX: &str = "urn:idleo:host:";

/// A freshly generated ECDSA P-256 key pair. The private key never leaves the machine; it is only
/// ever written to `host.key`.
pub struct NewKey(KeyPair);

impl NewKey {
    /// Generates a key pair.
    ///
    /// # Errors
    /// If the system's random number generator fails.
    pub fn generate() -> Result<Self, rcgen::Error> {
        KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).map(Self)
    }

    /// A DER-encoded PKCS#10 request for this key. Its subject doesn't matter: the CA takes only
    /// the public key and writes the host's identity itself.
    ///
    /// # Errors
    /// If signing the request fails.
    pub fn csr_der(&self) -> Result<Vec<u8>, rcgen::Error> {
        let request = CertificateParams::default().serialize_request(&self.0)?;
        Ok(request.der().to_vec())
    }

    /// The public key as an uncompressed EC point, as it appears in a certificate.
    #[must_use]
    pub fn public_key_raw(&self) -> &[u8] {
        self.0.public_key_raw()
    }

    /// The private key as PKCS#8 PEM, for `host.key`.
    #[must_use]
    pub fn private_key_pem(&self) -> String {
        self.0.serialize_pem()
    }
}

impl fmt::Debug for NewKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("NewKey(..)")
    }
}

/// The public key of a PKCS#8 PEM private key, as an uncompressed EC point.
///
/// # Errors
/// If the PEM isn't a P-256 PKCS#8 key.
pub fn public_key_of(private_key_pem: &str) -> Result<Vec<u8>, rcgen::Error> {
    Ok(KeyPair::from_pem(private_key_pem)?.public_key_raw().to_vec())
}

/// Loads our root certificate(s), the only trust anchors the agent uses. System and webpki roots
/// are never consulted.
///
/// # Errors
/// If the file can't be read, holds no certificate, or a certificate isn't a valid anchor.
pub fn load_roots(path: &Path) -> Result<Arc<RootCertStore>, String> {
    let certificates = CertificateDer::pem_file_iter(path)
        .and_then(Iterator::collect::<Result<Vec<_>, _>>)
        .map_err(|e| format!("can't read the root certificate {}: {e}", path.display()))?;
    roots_from(certificates).map_err(|e| format!("{}: {e}", path.display()))
}

/// Builds a trust store from root certificates.
///
/// # Errors
/// If there are none, or one isn't a valid trust anchor.
pub fn roots_from(certificates: Vec<CertificateDer<'static>>) -> Result<Arc<RootCertStore>, String> {
    if certificates.is_empty() {
        return Err("no root certificate found".to_owned());
    }
    let mut roots = RootCertStore::empty();
    for certificate in certificates {
        roots.add(certificate).map_err(|e| format!("not a usable root certificate: {e}"))?;
    }
    Ok(Arc::new(roots))
}

/// A certificate that passed every check, ready to save.
#[derive(Debug, Clone)]
pub struct IssuedIdentity {
    /// The host's certificate first, then the intermediate.
    pub chain: Vec<CertificateDer<'static>>,
    /// From the certificate's `urn:idleo:host:` name.
    pub host_id: Uuid,
    /// When the host certificate expires.
    pub not_after: OffsetDateTime,
}

/// Why an issued certificate was refused. Nothing is saved when this happens.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReplyRejected {
    /// The reply had no certificate.
    Empty,
    /// The certificate doesn't chain to our root through the returned intermediate, isn't valid
    /// now, or isn't for client authentication.
    Untrusted(String),
    /// The certificate couldn't be parsed.
    Unparseable,
    /// The certificate is for a public key other than the one we generated.
    NotOurKey,
    /// The certificate's subject alternative names aren't exactly one host URN.
    BadIdentity(String),
}

impl fmt::Display for ReplyRejected {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Empty => f.write_str("the reply holds no certificate"),
            Self::Untrusted(e) => write!(f, "the certificate doesn't chain to our root: {e}"),
            Self::Unparseable => f.write_str("the certificate can't be parsed"),
            Self::NotOurKey => f.write_str("the certificate isn't for the key this agent generated"),
            Self::BadIdentity(e) => write!(f, "the certificate's identity is wrong: {e}"),
        }
    }
}

impl std::error::Error for ReplyRejected {}

/// Checks a certificate chain from `Enroll` or `RenewCertificate`:
/// - the leaf chains to one of `roots` through the returned intermediates, is valid now, and is
///   for client authentication;
/// - the leaf's public key is `our_public_key`;
/// - its subject alternative names are exactly one `urn:idleo:host:<uuid>`.
///
/// # Errors
/// The first check that fails.
pub fn verify_issued(
    chain_der: &[Vec<u8>],
    roots: &Arc<RootCertStore>,
    our_public_key: &[u8],
) -> Result<IssuedIdentity, ReplyRejected> {
    let chain: Vec<CertificateDer<'static>> = chain_der.iter().map(|der| CertificateDer::from(der.clone())).collect();
    let (leaf, intermediates) = chain.split_first().ok_or(ReplyRejected::Empty)?;
    let verifier = WebPkiClientVerifier::builder_with_provider(Arc::clone(roots), crate::tls::provider())
        .build()
        .map_err(|e| ReplyRejected::Untrusted(e.to_string()))?;
    verifier
        .verify_client_cert(leaf, intermediates, UnixTime::now())
        .map_err(|e| ReplyRejected::Untrusted(e.to_string()))?;
    let identity = read_identity(leaf)?;
    if identity.public_key != our_public_key {
        return Err(ReplyRejected::NotOurKey);
    }
    Ok(IssuedIdentity { chain, host_id: identity.host_id, not_after: identity.not_after })
}

/// What a host certificate says about itself.
#[derive(Debug, Clone)]
pub struct CertificateIdentity {
    /// From the single `urn:idleo:host:` name.
    pub host_id: Uuid,
    /// When it expires.
    pub not_after: OffsetDateTime,
    /// The subject public key, as an uncompressed EC point.
    pub public_key: Vec<u8>,
}

/// Reads the host id, expiry and public key from a host certificate.
///
/// # Errors
/// If it can't be parsed, or its names aren't exactly one host URN.
pub fn read_identity(leaf: &CertificateDer<'_>) -> Result<CertificateIdentity, ReplyRejected> {
    let (_, certificate) = X509Certificate::from_der(leaf).map_err(|_| ReplyRejected::Unparseable)?;
    let names = certificate
        .subject_alternative_name()
        .map_err(|_| ReplyRejected::Unparseable)?
        .ok_or_else(|| ReplyRejected::BadIdentity("no subject alternative name".to_owned()))?;
    let host_id = match names.value.general_names.as_slice() {
        [GeneralName::URI(uri)] => uri
            .strip_prefix(HOST_URN_PREFIX)
            .and_then(|id| Uuid::try_parse(id).ok())
            .ok_or_else(|| ReplyRejected::BadIdentity(format!("{uri} isn't a host URN")))?,
        [_] => return Err(ReplyRejected::BadIdentity("the name isn't a URI".to_owned())),
        names => return Err(ReplyRejected::BadIdentity(format!("{} names, not one", names.len()))),
    };
    Ok(CertificateIdentity {
        host_id,
        not_after: certificate.validity().not_after.to_datetime(),
        public_key: certificate.public_key().subject_public_key.data.to_vec(),
    })
}
