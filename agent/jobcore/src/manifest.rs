//! A folder's completion marker (proto/README.md "Storage layout"): `_COMPLETE`, a JSON manifest listing every other
//! file in the folder with its size, written last. A folder without it doesn't exist as far as resume or outputs are
//! concerned. Files uploaded from disk also record their SHA-256.

use serde::{Deserialize, Serialize};

/// The marker's name.
pub const COMPLETE: &str = "_COMPLETE";

/// What `_COMPLETE` holds.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    /// Every file in the folder.
    pub files: Vec<ManifestFile>,
}

/// One file in a manifest.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ManifestFile {
    /// Its path inside the folder.
    pub path: String,
    /// Its size in bytes.
    pub size: u64,
    /// Its SHA-256 in lower-case hex, for files uploaded from disk.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sha256: Option<String>,
}

impl Manifest {
    /// The marker's body.
    ///
    /// # Panics
    /// Never: the type always serialises.
    #[must_use]
    pub fn to_bytes(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("a manifest serialises")
    }

    /// Reads a marker.
    ///
    /// # Errors
    /// If it isn't a manifest.
    pub fn from_slice(bytes: &[u8]) -> Result<Self, String> {
        serde_json::from_slice(bytes).map_err(|e| format!("the manifest is invalid: {e}"))
    }

    /// The manifest of files held in memory, without hashes.
    #[must_use]
    pub fn of_bodies(files: &[(String, Vec<u8>)]) -> Self {
        Self {
            files: files
                .iter()
                .map(|(path, body)| ManifestFile { path: path.clone(), size: body.len() as u64, sha256: None })
                .collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_manifest_without_hashes_is_the_old_shape() {
        let manifest = Manifest::of_bodies(&[("state.json".to_owned(), b"{}".to_vec())]);
        assert_eq!(String::from_utf8(manifest.to_bytes()).unwrap(), r#"{"files":[{"path":"state.json","size":2}]}"#);
    }

    #[test]
    fn hashes_are_kept_and_an_old_manifest_still_reads() {
        let manifest =
            Manifest { files: vec![ManifestFile { path: "a".to_owned(), size: 1, sha256: Some("ab".repeat(32)) }] };
        assert_eq!(Manifest::from_slice(&manifest.to_bytes()).unwrap(), manifest);
        let old = Manifest::from_slice(br#"{"files":[{"path":"a","size":1}]}"#).unwrap();
        assert_eq!(old.files[0].sha256, None);
        assert!(Manifest::from_slice(b"nope").is_err());
    }
}
