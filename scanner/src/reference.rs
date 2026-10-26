//! An image reference as a renter writes it (`pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime`, `ghcr.io/me/job:v3`,
//! `nvcr.io/nvidia/pytorch:24.10-py3`, `quay.io/org/img@sha256:…`), read by the distribution spec's grammar, with Docker Hub's
//! defaults: no registry is `docker.io`, reached at `registry-1.docker.io`, and a one-part name there is `library/<name>`.
//! The control plane reads references with the same rules (`ImageReference`); the scanner reads them again rather than trust
//! what it's handed.

use std::fmt;

/// A parsed reference.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reference {
    /// The registry as named, for example `docker.io` or `localhost:5000`.
    pub registry: String,
    /// The repository, for example `library/ubuntu`.
    pub repository: String,
    /// The tag, when the reference names one.
    pub tag: Option<String>,
    /// The digest, `sha256:<64 hex>`, when the reference pins one.
    pub digest: Option<String>,
}

/// Why a reference isn't one.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Invalid(pub String);

impl fmt::Display for Invalid {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl Reference {
    /// Reads `text`.
    ///
    /// # Errors
    /// If it isn't a reference to an image.
    pub fn parse(text: &str) -> Result<Self, Invalid> {
        let text = text.trim();
        if text.is_empty() || text.len() > 512 {
            return Err(Invalid("a reference is 1 to 512 characters".to_owned()));
        }
        let (rest, digest) = match text.split_once('@') {
            Some((rest, digest)) => (rest, Some(digest.to_owned())),
            None => (text, None),
        };
        if let Some(digest) = &digest
            && !is_sha256(digest)
        {
            return Err(Invalid("a digest is sha256: and 64 lower-case hex digits".to_owned()));
        }
        // A tag is after the last ':' that comes after the last '/', so a registry's port isn't taken for one.
        let slash = rest.rfind('/').map_or(0, |i| i + 1);
        let (name, tag) = match rest[slash..].rfind(':') {
            Some(i) => (&rest[..slash + i], Some(rest[slash + i + 1..].to_owned())),
            None => (rest, None),
        };
        if let Some(tag) = &tag
            && !valid_tag(tag)
        {
            return Err(Invalid(format!("{tag:?} isn't a tag: letters, digits, '_', '.' and '-', at most 128")));
        }
        let (registry, repository) = match name.split_once('/') {
            Some((first, rest)) if first.contains(['.', ':']) || first == "localhost" => {
                (first.to_owned(), rest.to_owned())
            }
            _ => ("docker.io".to_owned(), name.to_owned()),
        };
        let registry = registry.to_ascii_lowercase();
        let repository = if registry == "docker.io" && !repository.contains('/') {
            format!("library/{repository}")
        } else {
            repository
        };
        if !valid_registry(&registry) {
            return Err(Invalid(format!("{registry:?} isn't a registry's name")));
        }
        if !repository.split('/').all(valid_component) {
            return Err(Invalid(format!(
                "{repository:?} isn't a repository: lower-case letters, digits and separators ('.', '_', '-'), in parts split by '/'"
            )));
        }
        let tag = if tag.is_none() && digest.is_none() { Some("latest".to_owned()) } else { tag };
        Ok(Self { registry, repository, tag, digest })
    }

    /// Where the registry's API is: Docker Hub's API isn't at `docker.io`.
    #[must_use]
    pub fn api_host(&self) -> &str {
        if self.registry == "docker.io" { "registry-1.docker.io" } else { &self.registry }
    }

    /// The registry's host name, without a port.
    #[must_use]
    pub fn host(&self) -> &str {
        let host = self.api_host();
        if host.starts_with('[') {
            return host.split(']').next().map_or(host, |h| h.trim_start_matches('['));
        }
        host.rsplit_once(':').map_or(host, |(host, _)| host)
    }

    /// What to ask the registry for: the digest if pinned, else the tag.
    #[must_use]
    pub fn manifest_ref(&self) -> &str {
        self.digest.as_deref().or(self.tag.as_deref()).unwrap_or("latest")
    }

    /// The reference pinned to `digest`: what runs.
    #[must_use]
    pub fn pinned(&self, digest: &str) -> String {
        format!("{}/{}@{digest}", self.registry, self.repository)
    }
}

/// `sha256:` and 64 lower-case hex digits.
#[must_use]
pub fn is_sha256(digest: &str) -> bool {
    digest
        .strip_prefix("sha256:")
        .is_some_and(|hex| hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)))
}

fn valid_tag(tag: &str) -> bool {
    !tag.is_empty()
        && tag.len() <= 128
        && tag.bytes().next().is_some_and(|b| b.is_ascii_alphanumeric() || b == b'_')
        && tag.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b'-'))
}

fn valid_registry(registry: &str) -> bool {
    let (host, port) = registry.rsplit_once(':').map_or((registry, None), |(h, p)| (h, Some(p)));
    port.is_none_or(|p| !p.is_empty() && p.len() <= 5 && p.bytes().all(|b| b.is_ascii_digit()))
        && !host.is_empty()
        && host.len() <= 253
        && host.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && !label.starts_with('-')
                && !label.ends_with('-')
                && label.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
}

fn valid_component(part: &str) -> bool {
    let bytes = part.as_bytes();
    !bytes.is_empty()
        && bytes.first().is_some_and(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        && bytes.last().is_some_and(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        && bytes.iter().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'_' | b'-'))
        && !part.contains("..")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(text: &str) -> Reference {
        Reference::parse(text).unwrap_or_else(|e| panic!("{text}: {e}"))
    }

    #[test]
    fn docker_hubs_defaults_apply() {
        let r = parse("ubuntu");
        assert_eq!(
            (r.registry.as_str(), r.repository.as_str(), r.tag.as_deref()),
            ("docker.io", "library/ubuntu", Some("latest"))
        );
        assert_eq!(r.api_host(), "registry-1.docker.io");
        let r = parse("pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime");
        assert_eq!(
            (r.repository.as_str(), r.tag.as_deref()),
            ("pytorch/pytorch", Some("2.5.1-cuda12.4-cudnn9-runtime"))
        );
    }

    #[test]
    fn other_registries_ports_and_digests() {
        let r = parse("nvcr.io/nvidia/pytorch:24.10-py3");
        assert_eq!((r.registry.as_str(), r.repository.as_str()), ("nvcr.io", "nvidia/pytorch"));
        let r = parse("localhost:5000/me/job");
        assert_eq!((r.registry.as_str(), r.host(), r.tag.as_deref()), ("localhost:5000", "localhost", Some("latest")));
        let digest = format!("sha256:{}", "ab".repeat(32));
        let r = parse(&format!("ghcr.io/me/job:v3@{digest}"));
        assert_eq!(
            (r.tag.as_deref(), r.digest.as_deref(), r.manifest_ref()),
            (Some("v3"), Some(digest.as_str()), digest.as_str())
        );
        assert_eq!(r.pinned(&digest), format!("ghcr.io/me/job@{digest}"));
    }

    #[test]
    fn anything_else_is_refused() {
        for bad in [
            "",
            "Ubuntu",
            "ubuntu:",
            "ubuntu:-x",
            "ubuntu@sha256:abc",
            "-x/y",
            "registry..io/x/y",
            "a/../b",
            "ghcr.io/me/job:v3 --privileged",
            "ghcr.io:99999999/x",
        ] {
            assert!(Reference::parse(bad).is_err(), "{bad}");
        }
    }
}
