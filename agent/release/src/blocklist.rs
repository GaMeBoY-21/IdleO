//! The abuse blocklist as a signed file of its own (ADR 0034's flow, ADR 0038's list): `stable/blocklist.json` and
//! `stable/blocklist.json.sig` in the release bucket, signed with the same Ed25519 key as the app's manifest and with a
//! version of its own, so a wrong entry can be pulled within a day without a new distribution.
//!
//! The control plane serves the file only if its signature verifies, and the agent trusts it only if it does against the
//! key baked into the build. What is in the list is the runner's to judge (`agent/runner/src/abuse.rs`); this module only
//! answers "is this file ours?", and which of two is newer.
//!
//! A list's **version** is when it was issued: `2026-10-25T09:30:00Z` (the list built into a distribution carries a plain
//! date, `2026-10-22`). Both sort as text in the order they happened, so "newer" is a string comparison everywhere, in the
//! agent, the runner and the control plane, with nothing to parse.

use std::fmt;

use ed25519_dalek::{Signature, Verifier, VerifyingKey};

/// The most a list may be, in bytes: it is names, hashes and domains, a few kilobytes.
pub const MAX_BYTES: usize = 1024 * 1024;

/// Whether `text` is a version: a date, or an issue time in UTC to the second.
#[must_use]
pub fn is_version(text: &str) -> bool {
    let bytes = text.as_bytes();
    let digits =
        |range: std::ops::Range<usize>| bytes.get(range).is_some_and(|part| part.iter().all(u8::is_ascii_digit));
    let date = bytes.len() >= 10
        && digits(0..4)
        && bytes[4] == b'-'
        && digits(5..7)
        && bytes[7] == b'-'
        && digits(8..10)
        && (1..=12).contains(&text[5..7].parse::<u8>().unwrap_or(0))
        && (1..=31).contains(&text[8..10].parse::<u8>().unwrap_or(0));
    match bytes.len() {
        10 => date,
        20 => {
            date && bytes[10] == b'T'
                && digits(11..13)
                && bytes[13] == b':'
                && digits(14..16)
                && bytes[16] == b':'
                && digits(17..19)
                && bytes[19] == b'Z'
                && text[11..13].parse::<u8>().unwrap_or(99) < 24
                && text[14..16].parse::<u8>().unwrap_or(99) < 60
                && text[17..19].parse::<u8>().unwrap_or(99) < 61
        }
        _ => false,
    }
}

/// Whether version `candidate` is newer than `current`; false if either isn't a version.
#[must_use]
pub fn is_newer(candidate: &str, current: &str) -> bool {
    is_version(candidate) && is_version(current) && candidate > current
}

/// Why a list was refused. Nothing from a refused list is used.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BlocklistError {
    /// This build has no release key.
    NoKey,
    /// The signature isn't 64 bytes, or doesn't verify against the release key.
    BadSignature,
    /// It verified, but it isn't a list: no version, or too large.
    Invalid(String),
}

impl fmt::Display for BlocklistError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NoKey => f.write_str("this development build has no release key"),
            Self::BadSignature => f.write_str("the blocklist's signature doesn't verify against the release key"),
            Self::Invalid(why) => write!(f, "the blocklist isn't valid: {why}"),
        }
    }
}

impl std::error::Error for BlocklistError {}

/// Verifies `signature` (the raw 64 bytes) over `bytes` with the key baked into this build, and returns the list's version.
///
/// # Errors
/// See [`BlocklistError`].
pub fn verify_with_baked_key(bytes: &[u8], signature: &[u8]) -> Result<String, BlocklistError> {
    let key = crate::keys::RELEASE_KEY.ok_or(BlocklistError::NoKey)?;
    verify(bytes, signature, &key)
}

/// Verifies `signature` over `bytes` with the raw Ed25519 public key `key`, and returns the list's version.
///
/// # Errors
/// See [`BlocklistError`].
pub fn verify(bytes: &[u8], signature: &[u8], key: &[u8; 32]) -> Result<String, BlocklistError> {
    let key = VerifyingKey::from_bytes(key).map_err(|_| BlocklistError::BadSignature)?;
    let signature = Signature::from_slice(signature).map_err(|_| BlocklistError::BadSignature)?;
    key.verify(bytes, &signature).map_err(|_| BlocklistError::BadSignature)?;
    if bytes.len() > MAX_BYTES {
        return Err(BlocklistError::Invalid(format!("more than {MAX_BYTES} bytes")));
    }
    version_of(bytes)
}

/// The list to publish: `base` (the distribution's list) with its `version` replaced by `version` and the `additions` (a JSON
/// array of `{sha256, name, kind, item}`: builds an admin confirmed as abuse) as `learned` entries. An entry already in the
/// base's `learned` stays; the same build twice is one. Refused when `version` isn't a version or isn't newer than the base's.
///
/// # Errors
/// What is wrong with an input, in a line.
pub fn merge(base: &[u8], additions: Option<&[u8]>, version: &str) -> Result<String, String> {
    if !is_version(version) {
        return Err(format!(
            "{version:?} isn't a version (a date, or the time it was issued, like 2026-10-25T09:30:00Z)"
        ));
    }
    let mut list: serde_json::Value =
        serde_json::from_slice(base).map_err(|e| format!("the base list isn't JSON: {e}"))?;
    let object = list.as_object_mut().ok_or("the base list isn't a JSON object")?;
    if let Some(current) = object.get("version").and_then(serde_json::Value::as_str)
        && !is_newer(version, current)
    {
        return Err(format!("{version} isn't newer than the base list's {current}"));
    }
    object.insert("version".to_owned(), serde_json::Value::String(version.to_owned()));
    if let Some(additions) = additions {
        let added: Vec<serde_json::Value> = serde_json::from_slice(additions)
            .map_err(|e| format!("the additions aren't a JSON array of builds: {e}"))?;
        let learned = object.entry("learned").or_insert_with(|| serde_json::Value::Array(Vec::new()));
        let learned = learned.as_array_mut().ok_or("the base list's learned isn't an array")?;
        for entry in added {
            let sha256 = entry.get("sha256").and_then(serde_json::Value::as_str).unwrap_or_default().to_owned();
            if !learned.iter().any(|known| known.get("sha256").and_then(serde_json::Value::as_str) == Some(&sha256)) {
                learned.push(entry);
            }
        }
    }
    serde_json::to_string_pretty(&list).map(|text| format!("{text}\n")).map_err(|e| e.to_string())
}

/// The `version` a list's JSON carries.
///
/// # Errors
/// If it isn't JSON with a `version` that is a version.
pub fn version_of(bytes: &[u8]) -> Result<String, BlocklistError> {
    let value: serde_json::Value =
        serde_json::from_slice(bytes).map_err(|e| BlocklistError::Invalid(format!("not JSON: {e}")))?;
    value
        .get("version")
        .and_then(serde_json::Value::as_str)
        .filter(|version| is_version(version))
        .map(str::to_owned)
        .ok_or_else(|| BlocklistError::Invalid("it has no version like 2026-10-25 or 2026-10-25T09:30:00Z".to_owned()))
}

#[cfg(test)]
mod tests {
    use ed25519_dalek::{Signer, SigningKey};

    use super::*;

    fn keys() -> (SigningKey, [u8; 32]) {
        let signing = SigningKey::from_bytes(&[7; 32]);
        let public = signing.verifying_key().to_bytes();
        (signing, public)
    }

    const LIST: &[u8] = br#"{"version":"2026-10-23T09:30:00Z","names":[],"hashes":[],"pools":[]}"#;

    #[test]
    fn versions_sort_in_the_order_they_were_issued() {
        let order = [
            "2026-10-22",
            "2026-10-22T00:00:00Z",
            "2026-10-22T09:30:00Z",
            "2026-10-22T09:30:01Z",
            "2026-10-23",
            "2027-01-01T00:00:00Z",
        ];
        for pair in order.windows(2) {
            assert!(is_newer(pair[1], pair[0]), "{} is newer than {}", pair[1], pair[0]);
            assert!(!is_newer(pair[0], pair[1]), "{} isn't newer than {}", pair[0], pair[1]);
        }
        assert!(!is_newer("2026-10-22T09:30:00Z", "2026-10-22T09:30:00Z"), "the same version isn't newer");
    }

    #[test]
    fn things_that_are_not_versions_are_not_versions() {
        for text in [
            "",
            "2026-10",
            "26-10-22",
            "2026-13-01",
            "2026-10-00",
            "2026-10-22T25:00:00Z",
            "2026-10-22T09:30:00",
            "2026-10-22 09:30:00Z",
            "2026-10-22.1",
            "v1",
        ] {
            assert!(!is_version(text), "{text:?}");
        }
        assert!(!is_newer("nonsense", "2026-10-22"));
        assert!(!is_newer("2026-10-23", "nonsense"));
        assert!(is_version("2026-10-22") && is_version("2026-10-22T09:30:00Z"));
    }

    #[test]
    fn a_list_signed_with_the_key_verifies_and_says_its_version() {
        let (signing, public) = keys();
        let signature = signing.sign(LIST).to_bytes();
        assert_eq!(verify(LIST, &signature, &public).unwrap(), "2026-10-23T09:30:00Z");
    }

    #[test]
    fn a_tampered_list_or_another_key_is_refused() {
        let (signing, public) = keys();
        let signature = signing.sign(LIST).to_bytes();
        let tampered = br#"{"version":"2026-10-23T09:30:00Z","names":[],"hashes":[],"pools":["example.org"]}"#;
        assert_eq!(verify(tampered, &signature, &public), Err(BlocklistError::BadSignature));
        let other = SigningKey::from_bytes(&[9; 32]).verifying_key().to_bytes();
        assert_eq!(verify(LIST, &signature, &other), Err(BlocklistError::BadSignature));
        assert_eq!(verify(LIST, &signature[..63], &public), Err(BlocklistError::BadSignature));
    }

    const BASE: &str = r#"{"version":"2026-10-22","names":[],"hashes":[],"pools":[]}"#;
    const BUILD: &str = r#"{"sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","name":"a miner","kind":"miner","item":"01a1-0000"}"#;

    #[test]
    fn a_published_list_is_the_base_with_a_new_version_and_the_builds_admins_confirmed() {
        let additions = format!("[{BUILD}]");
        let merged = merge(BASE.as_bytes(), Some(additions.as_bytes()), "2026-10-25T09:30:00Z").unwrap();
        let value: serde_json::Value = serde_json::from_str(&merged).unwrap();
        assert_eq!(value["version"], "2026-10-25T09:30:00Z");
        assert_eq!(value["learned"].as_array().unwrap().len(), 1);
        assert_eq!(version_of(merged.as_bytes()).unwrap(), "2026-10-25T09:30:00Z");

        // Published again with the same build and a later version: still one entry, and the base's list is untouched.
        let again = merge(merged.as_bytes(), Some(additions.as_bytes()), "2026-10-26T00:00:00Z").unwrap();
        let value: serde_json::Value = serde_json::from_str(&again).unwrap();
        assert_eq!(value["learned"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn a_list_without_additions_only_gets_a_new_version() {
        let merged = merge(BASE.as_bytes(), None, "2026-10-23").unwrap();
        let value: serde_json::Value = serde_json::from_str(&merged).unwrap();
        assert_eq!(value["version"], "2026-10-23");
        assert!(value.get("learned").is_none());
    }

    #[test]
    fn a_version_that_is_not_newer_or_not_a_version_is_not_published() {
        assert!(merge(BASE.as_bytes(), None, "2026-10-22").unwrap_err().contains("isn't newer"));
        assert!(merge(BASE.as_bytes(), None, "2026-10-21").unwrap_err().contains("isn't newer"));
        assert!(merge(BASE.as_bytes(), None, "tomorrow").unwrap_err().contains("isn't a version"));
        assert!(merge(b"[]", None, "2026-10-23").is_err());
        assert!(merge(BASE.as_bytes(), Some(b"{}"), "2026-10-23").is_err());
    }

    #[test]
    fn a_signed_file_that_is_not_a_list_is_refused() {
        let (signing, public) = keys();
        for bytes in [&b"[]"[..], b"{\"version\":\"soon\"}", b"not json", b"{}"] {
            let signature = signing.sign(bytes).to_bytes();
            assert!(matches!(verify(bytes, &signature, &public), Err(BlocklistError::Invalid(_))), "{bytes:?}");
        }
    }
}
