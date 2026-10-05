//! `idleo-agent enrol --code XXXXX-XXXXX-XXXXX-XXXXX`: trades a one-time code and a CSR for the
//! host's first certificate.

use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use agent_proto::v1::EnrollRequest;
use agent_proto::v1::enrollment_service_client::EnrollmentServiceClient;
use rustls::RootCertStore;
use time::OffsetDateTime;
use tonic::Code;
use uuid::Uuid;

use crate::pki::{self, NewKey, ReplyRejected};
use crate::state::{self, StateError};
use crate::tls::{self, Target};

const ENROL_TIMEOUT: Duration = Duration::from_secs(30);
/// Crockford base32, as the control plane issues codes (ADR 0010).
const ALPHABET: &str = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_LENGTH: usize = 20;

/// A well-formed enrolment code. It is a secret: it is never logged, and `Debug` hides it.
#[derive(Clone, PartialEq, Eq)]
pub struct EnrolmentCode(String);

impl EnrolmentCode {
    /// Parses a code as a person might type it: case, hyphens and spaces don't matter, and O, I
    /// and L read as 0, 1 and 1, as the control plane decodes them.
    ///
    /// # Errors
    /// If it isn't 20 Crockford base32 symbols. The error never contains the input.
    pub fn parse(input: &str) -> Result<Self, MalformedCode> {
        let mut symbols = String::with_capacity(CODE_LENGTH);
        for c in input.chars().filter(|c| *c != '-' && *c != ' ') {
            let c = match c.to_ascii_uppercase() {
                'O' => '0',
                'I' | 'L' => '1',
                c if ALPHABET.contains(c) => c,
                _ => return Err(MalformedCode),
            };
            symbols.push(c);
        }
        if symbols.len() == CODE_LENGTH { Ok(Self(symbols)) } else { Err(MalformedCode) }
    }

    /// The code as shown in the web app, `XXXXX-XXXXX-XXXXX-XXXXX`.
    fn grouped(&self) -> String {
        let groups: Vec<&str> = (0..4).map(|i| &self.0[i * 5..i * 5 + 5]).collect();
        groups.join("-")
    }
}

impl fmt::Debug for EnrolmentCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("EnrolmentCode(..)")
    }
}

/// The code isn't in the enrolment code format.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MalformedCode;

impl fmt::Display for MalformedCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("that isn't an enrolment code: it should look like XXXXX-XXXXX-XXXXX-XXXXX (20 letters and digits)")
    }
}

impl std::error::Error for MalformedCode {}

/// A finished enrolment.
#[derive(Debug, Clone)]
pub struct Enrolled {
    /// The host this machine now is.
    pub host_id: Uuid,
    /// When the first certificate expires.
    pub not_after: OffsetDateTime,
    /// Where the previous identity went, with `--force`.
    pub backup: Option<PathBuf>,
}

/// Why enrolment failed. Nothing is saved in any of these cases.
#[derive(Debug)]
pub enum EnrolError {
    /// An identity already exists and `--force` wasn't given.
    AlreadyEnrolled(PathBuf),
    /// The control plane refused the code: invalid, used or expired.
    CodeRefused,
    /// The control plane refused the request for another reason.
    Refused(Code, String),
    /// The control plane couldn't be reached, or its certificate isn't from our CA.
    Connect(String),
    /// The issued certificate failed a check.
    BadReply(ReplyRejected),
    /// Generating the key or CSR failed.
    Key(String),
    /// Reading or saving the identity failed.
    State(StateError),
}

impl EnrolError {
    /// The process exit code for this failure (documented in the README).
    #[must_use]
    pub fn exit_code(&self) -> u8 {
        match self {
            Self::CodeRefused => 3,
            Self::BadReply(_) => 4,
            Self::AlreadyEnrolled(_) => 5,
            Self::State(_) => 6,
            Self::Connect(_) => 10,
            Self::Refused(..) | Self::Key(_) => 1,
        }
    }
}

impl fmt::Display for EnrolError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::AlreadyEnrolled(dir) => write!(
                f,
                "this machine is already enrolled (identity in {}); to replace it, run enrol again with --force, which keeps a backup",
                dir.display()
            ),
            Self::CodeRefused => f.write_str(
                "the control plane refused the code: it's wrong, already used, or expired (codes last an hour); get a new one from the web app",
            ),
            Self::Refused(code, message) => write!(f, "the control plane refused enrolment ({code:?}): {message}"),
            Self::Connect(e) => write!(f, "couldn't reach the control plane: {e}"),
            Self::BadReply(e) => write!(f, "the certificate the control plane returned failed a check, so nothing was saved: {e}"),
            Self::Key(e) => write!(f, "couldn't generate the key: {e}"),
            Self::State(e) => e.fmt(f),
        }
    }
}

impl std::error::Error for EnrolError {}

impl From<StateError> for EnrolError {
    fn from(e: StateError) -> Self {
        Self::State(e)
    }
}

/// Enrols this machine: generates a key pair, sends the code and a CSR over TLS 1.3 trusting only
/// `roots`, checks the certificate it gets back, and only then saves the identity in `data_dir`.
///
/// # Errors
/// See [`EnrolError`]; nothing is saved on any error.
pub async fn enrol(
    target: &Target,
    roots: &Arc<RootCertStore>,
    data_dir: &Path,
    code: &EnrolmentCode,
    force: bool,
) -> Result<Enrolled, EnrolError> {
    if !force && state::is_enrolled(data_dir)? {
        return Err(EnrolError::AlreadyEnrolled(data_dir.to_owned()));
    }
    let key = NewKey::generate().map_err(|e| EnrolError::Key(e.to_string()))?;
    let csr_der = key.csr_der().map_err(|e| EnrolError::Key(e.to_string()))?;

    let config = tls::client_config(roots, None).map_err(|e| EnrolError::Connect(e.to_string()))?;
    let call = async {
        let channel = target.connect(config).await.map_err(|e| EnrolError::Connect(tls::describe(&e)))?;
        let request = EnrollRequest { enrollment_code: code.grouped(), csr_der };
        EnrollmentServiceClient::new(channel).enroll(request).await.map_err(|status| match status.code() {
            Code::Unauthenticated => EnrolError::CodeRefused,
            Code::Unavailable => EnrolError::Connect(tls::describe(&status)),
            code => EnrolError::Refused(code, status.message().to_owned()),
        })
    };
    let response = tokio::time::timeout(ENROL_TIMEOUT, call)
        .await
        .map_err(|_| EnrolError::Connect("no answer within 30 seconds".to_owned()))??;

    let certificate = response.into_inner().certificate.ok_or(EnrolError::BadReply(ReplyRejected::Empty))?;
    let issued = pki::verify_issued(&certificate.certificate_chain_der, roots, key.public_key_raw())
        .map_err(EnrolError::BadReply)?;
    let backup = state::save(data_dir, &key.private_key_pem(), &issued, force)?;
    tracing::info!(host_id = %issued.host_id, not_after = %state::format_time(issued.not_after), "enrolled");
    Ok(Enrolled { host_id: issued.host_id, not_after: issued.not_after, backup })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_parse_as_people_type_them() {
        let code = EnrolmentCode::parse("abcde-fghjk-mnpqr-stvwx").unwrap();
        assert_eq!(code.grouped(), "ABCDE-FGHJK-MNPQR-STVWX");
        assert_eq!(EnrolmentCode::parse("OIL00 11111 22222 33333").unwrap().grouped(), "01100-11111-22222-33333");
        assert_eq!(EnrolmentCode::parse("0123456789ABCDEFGHJK").unwrap().grouped(), "01234-56789-ABCDE-FGHJK");
    }

    #[test]
    fn malformed_codes_are_refused() {
        for input in [
            "",
            "ABCDE-FGHJK-MNPQR-STVW",
            "ABCDE-FGHJK-MNPQR-STVWXY",
            "ABCDE-FGHJK-MNPQR-STVWU",
            "ABCDE_FGHJK_MNPQR_STVWX",
        ] {
            assert_eq!(EnrolmentCode::parse(input), Err(MalformedCode), "{input}");
        }
    }

    #[test]
    fn a_code_never_shows_in_debug_output_or_errors() {
        let code = EnrolmentCode::parse("ABCDE-FGHJK-MNPQR-STVWX").unwrap();
        assert_eq!(format!("{code:?}"), "EnrolmentCode(..)");
        assert!(!MalformedCode.to_string().contains("ABCDE"));
    }
}
