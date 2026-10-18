//! The Windows app's release manifest (ADR 0034): `stable/latest.json`, signed with Ed25519 over its exact bytes.
//!
//! Only a manifest whose signature verifies against the release key is ever read; its fields are then checked the
//! same way the control plane checks them. The download links are not signed: each file's size and SHA-256 are, and
//! [`crate::download`] checks them.

use std::fmt;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;

/// The only channel there is.
pub const CHANNEL: &str = "stable";

/// One downloadable file of a release.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Artifact {
    /// Its object key under the channel's folder, for example `0.2.0/idleo-app-0.2.0.zip`.
    pub file: String,
    /// Its SHA-256, lowercase hex.
    pub sha256: String,
    /// Its size in bytes.
    pub size: u64,
}

/// The sealed Linux image a release carries.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Distro {
    /// Its own version (`agent/distro/VERSION`), which names the WSL distribution: `IdleO-<version>`.
    pub version: String,
    /// The tarball's object key under the channel's folder.
    pub file: String,
    /// The tarball's SHA-256, lowercase hex.
    pub sha256: String,
    /// The tarball's size in bytes.
    pub size: u64,
}

impl Distro {
    /// The tarball as a downloadable file.
    #[must_use]
    pub fn artifact(&self) -> Artifact {
        Artifact { file: self.file.clone(), sha256: self.sha256.clone(), size: self.size }
    }
}

/// A verified manifest.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    /// The app's version, semantic versioning.
    pub version: String,
    /// Always [`CHANNEL`].
    pub channel: String,
    /// When it was released.
    #[serde(with = "time::serde::rfc3339")]
    pub released_at: OffsetDateTime,
    /// The share of hosts offered it, 0 to 100.
    pub rollout_percent: u8,
    /// The oldest Windows build it runs on, for example 19045 (Windows 10 22H2).
    pub min_windows_build: u32,
    /// The zip of the five programs.
    pub app: Artifact,
    /// The sealed Linux image.
    pub distro: Distro,
    /// The bare setup program, for the website's Download button.
    pub setup: Artifact,
}

/// Why a manifest was refused. Nothing from a refused manifest is used.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ManifestError {
    /// This build has no release key (a development build without `scripts/release/dev-key.sh`).
    NoKey,
    /// The signature isn't 64 bytes of base64, or doesn't verify against the release key.
    BadSignature,
    /// It verified, but a field is missing or out of range.
    Invalid(String),
}

impl fmt::Display for ManifestError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NoKey => {
                f.write_str("this development build has no release key; run scripts/release/dev-key.sh and build again")
            }
            Self::BadSignature => f.write_str("the release's signature doesn't verify against the release key"),
            Self::Invalid(why) => write!(f, "the release's manifest isn't valid: {why}"),
        }
    }
}

impl std::error::Error for ManifestError {}

/// Verifies `signature` over `bytes` with the key baked into this build, then reads the manifest.
///
/// # Errors
/// See [`ManifestError`].
pub fn verify_with_baked_key(bytes: &[u8], signature: &[u8]) -> Result<Manifest, ManifestError> {
    let key = crate::keys::RELEASE_KEY.ok_or(ManifestError::NoKey)?;
    verify(bytes, signature, &key)
}

/// Verifies `signature` (the raw 64 bytes) over `bytes` with the raw Ed25519 public key `key`, then reads and checks
/// the manifest.
///
/// # Errors
/// See [`ManifestError`].
pub fn verify(bytes: &[u8], signature: &[u8], key: &[u8; 32]) -> Result<Manifest, ManifestError> {
    let key = VerifyingKey::from_bytes(key).map_err(|_| ManifestError::BadSignature)?;
    let signature = Signature::from_slice(signature).map_err(|_| ManifestError::BadSignature)?;
    key.verify(bytes, &signature).map_err(|_| ManifestError::BadSignature)?;
    let manifest: Manifest =
        serde_json::from_slice(bytes).map_err(|e| ManifestError::Invalid(format!("not the manifest's JSON: {e}")))?;
    manifest.check()?;
    Ok(manifest)
}

/// A signature as text (base64, as `latest.json.sig` and the API carry it), decoded.
///
/// # Errors
/// [`ManifestError::BadSignature`] if it isn't base64 of 64 bytes.
pub fn decode_signature(text: &str) -> Result<Vec<u8>, ManifestError> {
    let bytes = STANDARD.decode(text.trim()).map_err(|_| ManifestError::BadSignature)?;
    if bytes.len() == 64 { Ok(bytes) } else { Err(ManifestError::BadSignature) }
}

impl Manifest {
    fn check(&self) -> Result<(), ManifestError> {
        let invalid = |why: &str| Err(ManifestError::Invalid(why.to_owned()));
        if crate::version::is_newer(&self.version, "0.0.0").is_none() {
            return invalid("version isn't semver");
        }
        if crate::version::is_newer(&self.distro.version, "0.0.0").is_none() {
            return invalid("distro.version isn't semver");
        }
        if self.channel != CHANNEL {
            return invalid("the channel isn't stable");
        }
        if self.rollout_percent > 100 {
            return invalid("rollout_percent is over 100");
        }
        for (name, artifact) in
            [("app", self.app.clone()), ("distro", self.distro.artifact()), ("setup", self.setup.clone())]
        {
            if !is_plain_key(&artifact.file) {
                return Err(ManifestError::Invalid(format!(
                    "{name}.file isn't a plain key under the channel's folder"
                )));
            }
            if artifact.sha256.len() != 64
                || !artifact.sha256.bytes().all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
            {
                return Err(ManifestError::Invalid(format!("{name}.sha256 isn't 64 lowercase hex digits")));
            }
            if artifact.size == 0 {
                return Err(ManifestError::Invalid(format!("{name}.size is zero")));
            }
        }
        Ok(())
    }

    /// The WSL distribution this release's image is imported as: `IdleO-<distro version>`.
    #[must_use]
    pub fn distro_name(&self) -> String {
        distro_name(&self.distro.version)
    }
}

/// The name a distro version is registered under in WSL. Every distribution we make starts with `IdleO-`, and only
/// those are ever touched.
#[must_use]
pub fn distro_name(version: &str) -> String {
    format!("{}{version}", crate::wsl::DISTRO_PREFIX)
}

/// A key of `[A-Za-z0-9._-]` segments joined by `/`, with no `..`: nothing outside the channel's folder.
fn is_plain_key(file: &str) -> bool {
    !file.is_empty()
        && !file.contains("..")
        && file.split('/').all(|segment| {
            !segment.is_empty() && segment.bytes().all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
        })
}

#[cfg(test)]
pub(crate) mod tests {
    use ed25519_dalek::{Signer, SigningKey};

    use super::*;

    /// A manifest as `idleo-release manifest` writes it.
    pub(crate) fn text(version: &str, rollout: u8) -> String {
        format!(
            r#"{{"version":"{version}","channel":"stable","released_at":"2026-10-18T12:00:00Z","rollout_percent":{rollout},
 "min_windows_build":19045,
 "app":{{"file":"{version}/idleo-app-{version}.zip","sha256":"{a}","size":9800000}},
 "distro":{{"version":"0.1.0","file":"distro/idleo-distro-0.1.0.tar.gz","sha256":"{b}","size":98000000}},
 "setup":{{"file":"{version}/IdleO-Setup.exe","sha256":"{c}","size":9700000}}}}"#,
            a = "a".repeat(64),
            b = "b".repeat(64),
            c = "c".repeat(64),
        )
    }

    pub(crate) fn key(seed: u8) -> SigningKey {
        SigningKey::from_bytes(&[seed; 32])
    }

    #[test]
    fn a_good_manifest_verifies_and_reads() {
        let signer = key(7);
        let bytes = text("0.2.0", 10);
        let signature = signer.sign(bytes.as_bytes()).to_bytes();
        let manifest = verify(bytes.as_bytes(), &signature, &signer.verifying_key().to_bytes()).unwrap();
        assert_eq!(manifest.version, "0.2.0");
        assert_eq!(manifest.rollout_percent, 10);
        assert_eq!(manifest.distro_name(), "IdleO-0.1.0");
        assert_eq!(manifest.setup.size, 9_700_000);
    }

    #[test]
    fn a_tampered_manifest_or_the_wrong_key_is_refused() {
        let signer = key(7);
        let bytes = text("0.2.0", 10);
        let signature = signer.sign(bytes.as_bytes()).to_bytes();
        let tampered = text("0.2.0", 100);
        assert_eq!(
            verify(tampered.as_bytes(), &signature, &signer.verifying_key().to_bytes()),
            Err(ManifestError::BadSignature)
        );
        assert_eq!(
            verify(bytes.as_bytes(), &signature, &key(8).verifying_key().to_bytes()),
            Err(ManifestError::BadSignature)
        );
        assert_eq!(
            verify(bytes.as_bytes(), &[0; 10], &signer.verifying_key().to_bytes()),
            Err(ManifestError::BadSignature)
        );
    }

    #[test]
    fn a_signed_manifest_with_bad_fields_is_refused() {
        let signer = key(7);
        let public = signer.verifying_key().to_bytes();
        for (bad, why) in [
            (text("latest", 10), "semver"),
            (text("0.2.0", 10).replace("\"stable\"", "\"beta\""), "channel"),
            (text("0.2.0", 10).replace("0.2.0/IdleO", "../IdleO"), "setup.file"),
            (text("0.2.0", 10).replace(&"a".repeat(64), &"A".repeat(64)), "app.sha256"),
            (text("0.2.0", 101), "rollout"),
        ] {
            let signature = signer.sign(bad.as_bytes()).to_bytes();
            match verify(bad.as_bytes(), &signature, &public) {
                Err(ManifestError::Invalid(message)) => assert!(message.contains(why), "{message}"),
                other => panic!("{why}: {other:?}"),
            }
        }
    }

    #[test]
    fn signatures_are_read_from_base64() {
        assert_eq!(decode_signature(&STANDARD.encode([1u8; 64])).unwrap(), vec![1u8; 64]);
        assert!(decode_signature("not base64!").is_err());
        assert!(decode_signature(&STANDARD.encode([1u8; 63])).is_err());
    }
}
