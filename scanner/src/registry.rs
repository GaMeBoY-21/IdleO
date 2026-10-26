//! A registry, spoken to by the OCI distribution spec: the manifest a reference names (one platform's, out of an index), its
//! config and its layers, each checked against its digest and its declared size as it arrives. Docker Hub's, GHCR's, quay.io's
//! and NGC's token services and a private registry's basic auth are the same two challenges. Every request goes through the
//! [`guard`](crate::guard): public addresses only, also on redirects, and a blob's redirect to a CDN never carries our
//! credentials (reqwest drops them when the host changes).

use std::path::Path;
use std::time::Duration;

use reqwest::header::{ACCEPT, AUTHORIZATION, CONTENT_TYPE, HeaderValue, WWW_AUTHENTICATE};
use reqwest::{Client, StatusCode, Url};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;

use crate::guard::{self, Allowed, PublicOnly};
use crate::reference::{Reference, is_sha256};

/// The most a manifest, an index or a config may be; real ones are a few kilobytes.
const MAX_DOCUMENT_BYTES: usize = 4 * 1024 * 1024;
/// The most a token service's answer may be.
const MAX_TOKEN_BYTES: usize = 64 * 1024;

const OCI_INDEX: &str = "application/vnd.oci.image.index.v1+json";
const OCI_MANIFEST: &str = "application/vnd.oci.image.manifest.v1+json";
const DOCKER_LIST: &str = "application/vnd.docker.distribution.manifest.list.v2+json";
const DOCKER_MANIFEST: &str = "application/vnd.docker.distribution.manifest.v2+json";
const ACCEPTED: &str = "application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, \
                        application/vnd.docker.distribution.manifest.list.v2+json, \
                        application/vnd.docker.distribution.manifest.v2+json";

/// A pull's credentials: a user name and a read-only token. Never printed.
#[derive(Clone, Deserialize)]
pub struct Login {
    /// The user name.
    pub username: String,
    /// The token.
    pub token: String,
}

impl std::fmt::Debug for Login {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Login(..)")
    }
}

/// A content descriptor.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Descriptor {
    /// What it is.
    pub media_type: String,
    /// `sha256:…`.
    pub digest: String,
    /// Its size in bytes.
    pub size: u64,
    /// Where an index says it runs.
    #[serde(default)]
    pub platform: Option<Platform>,
    /// URLs a foreign layer is fetched from: never followed.
    #[serde(default)]
    pub urls: Vec<String>,
}

/// A platform, as an index names one.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Platform {
    /// `amd64`, `arm64`, …
    pub architecture: String,
    /// `linux`, `windows`, …
    pub os: String,
    /// `v8` and the like.
    #[serde(default)]
    pub variant: Option<String>,
}

impl Platform {
    fn name(&self) -> String {
        match &self.variant {
            Some(variant) => format!("{}/{}/{variant}", self.os, self.architecture),
            None => format!("{}/{}", self.os, self.architecture),
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Document {
    #[serde(default)]
    schema_version: u32,
    #[serde(default)]
    media_type: Option<String>,
    #[serde(default)]
    manifests: Vec<Descriptor>,
    #[serde(default)]
    config: Option<Descriptor>,
    #[serde(default)]
    layers: Vec<Descriptor>,
}

/// One platform's image manifest, as fetched.
#[derive(Debug, Clone)]
pub struct Manifest {
    /// Its digest: what the renter's sessions will run.
    pub digest: String,
    /// Its media type.
    pub media_type: String,
    /// Its exact bytes, which the digest is of.
    pub bytes: Vec<u8>,
    /// The config's descriptor.
    pub config: Descriptor,
    /// The layers, bottom first.
    pub layers: Vec<Descriptor>,
}

/// What a reference resolved to.
#[derive(Debug)]
pub enum Resolved {
    /// The manifest for the platform asked for.
    Found(Box<Manifest>),
    /// An index without that platform: the ones it has.
    WrongPlatform(Vec<String>),
}

/// Why a registry couldn't give what was asked. Plain words, never a credential.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// The registry refused us, or there's no such image.
    Refused(String),
    /// What it sent isn't what it should be.
    Bad(String),
    /// The network failed.
    Network(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Refused(why) | Self::Bad(why) | Self::Network(why) => f.write_str(why),
        }
    }
}

fn network(e: &reqwest::Error) -> Error {
    // reqwest's text can name the URL, never a header; the URL holds no credential.
    Error::Network(format!("the registry couldn't be reached: {e}"))
}

/// A registry for one reference.
pub struct Registry {
    http: Client,
    allowed: Allowed,
    reference: Reference,
    base: Url,
    login: Option<Login>,
    auth: Mutex<Option<HeaderValue>>,
}

impl Registry {
    /// A client for `reference`'s registry.
    ///
    /// # Errors
    /// If the registry may not be reached at all (a private address, or plain HTTP to a host that isn't allowed).
    pub fn new(reference: Reference, login: Option<Login>, allowed: Allowed) -> Result<Self, Error> {
        let host = reference.api_host().to_owned();
        let scheme = if allowed.allows(reference.host()) { "http" } else { "https" };
        let base: Url = format!("{scheme}://{host}/")
            .parse()
            .map_err(|_| Error::Bad(format!("{host} isn't a registry's address")))?;
        if !guard::url_allowed(&base, &allowed) {
            return Err(Error::Refused(format!("{host} isn't a public registry")));
        }
        let redirects = allowed.clone();
        let http = Client::builder()
            .use_preconfigured_tls(crate::tls_config())
            .dns_resolver(std::sync::Arc::new(PublicOnly(allowed.clone())))
            .redirect(reqwest::redirect::Policy::custom(move |attempt| {
                if attempt.previous().len() >= 5 {
                    attempt.error("too many redirects")
                } else if guard::url_allowed(attempt.url(), &redirects) {
                    attempt.follow()
                } else {
                    attempt.error("a redirect to an address that isn't public")
                }
            }))
            .connect_timeout(Duration::from_secs(15))
            .read_timeout(Duration::from_secs(60))
            .user_agent(concat!("idleo-scanner/", env!("CARGO_PKG_VERSION")))
            .build()
            .map_err(|e| Error::Network(format!("no HTTP client: {e}")))?;
        Ok(Self { http, allowed, reference, base, login, auth: Mutex::new(None) })
    }

    /// The manifest the reference names, for `platform` (`linux/amd64`).
    ///
    /// # Errors
    /// If the registry refuses, or what it sends isn't a manifest we can use.
    pub async fn resolve(&self, platform: &str) -> Result<Resolved, Error> {
        let (os, arch) = platform.split_once('/').unwrap_or(("linux", platform));
        let (bytes, media_type) = self.manifest(self.reference.manifest_ref()).await?;
        let digest = sha256(&bytes);
        if let Some(pinned) = &self.reference.digest
            && pinned != &digest
        {
            return Err(Error::Bad("the registry sent a manifest whose digest isn't the one asked for".to_owned()));
        }
        let document = parse(&bytes)?;
        let kind = document.media_type.clone().unwrap_or(media_type);
        if kind == OCI_INDEX || kind == DOCKER_LIST || (!document.manifests.is_empty() && document.config.is_none()) {
            let wanted = document.manifests.iter().find(|m| {
                m.platform.as_ref().is_some_and(|p| p.os == os && p.architecture == arch)
                    && is_sha256(&m.digest)
                    && (m.media_type == OCI_MANIFEST || m.media_type == DOCKER_MANIFEST)
            });
            let Some(wanted) = wanted else {
                let mut platforms: Vec<String> = document
                    .manifests
                    .iter()
                    .filter_map(|m| m.platform.as_ref())
                    .filter(|p| p.os != "unknown")
                    .map(Platform::name)
                    .collect();
                platforms.dedup();
                return Ok(Resolved::WrongPlatform(platforms));
            };
            let (child, child_type) = self.manifest(&wanted.digest).await?;
            if sha256(&child) != wanted.digest {
                return Err(Error::Bad(
                    "the registry sent a manifest whose digest isn't the one its index names".to_owned(),
                ));
            }
            return manifest(wanted.digest.clone(), child_type, child).map(|m| Resolved::Found(Box::new(m)));
        }
        manifest(digest, kind, bytes).map(|m| Resolved::Found(Box::new(m)))
    }

    /// The config blob, checked.
    ///
    /// # Errors
    /// If it can't be fetched or isn't what the manifest says.
    pub async fn config(&self, descriptor: &Descriptor) -> Result<Vec<u8>, Error> {
        if descriptor.size > MAX_DOCUMENT_BYTES as u64 {
            return Err(Error::Bad("the image's config is larger than a config is".to_owned()));
        }
        let response = self.get(&format!("blobs/{}", descriptor.digest), None).await?;
        let bytes = limited(response, MAX_DOCUMENT_BYTES).await?;
        if bytes.len() as u64 != descriptor.size || sha256(&bytes) != descriptor.digest {
            return Err(Error::Bad("the image's config isn't the one its manifest names".to_owned()));
        }
        Ok(bytes)
    }

    /// Downloads a blob to `to`, checking its size and digest as it comes; `progress` hears the bytes so far. A blob that runs
    /// past its declared size is cut off there and refused.
    ///
    /// # Errors
    /// If it can't be fetched, or isn't what was declared.
    pub async fn download(
        &self,
        descriptor: &Descriptor,
        to: &Path,
        mut progress: impl FnMut(u64),
    ) -> Result<(), Error> {
        if !descriptor.urls.is_empty() {
            return Err(Error::Bad(
                "the image has a layer fetched from elsewhere (a foreign layer), which isn't supported".to_owned(),
            ));
        }
        let mut response = self.get(&format!("blobs/{}", descriptor.digest), None).await?;
        let mut file = tokio::fs::File::create(to)
            .await
            .map_err(|e| Error::Network(format!("the scratch disk refused a write: {e}")))?;
        let mut hasher = Sha256::new();
        let mut received: u64 = 0;
        while let Some(chunk) = response.chunk().await.map_err(|e| network(&e))? {
            received += chunk.len() as u64;
            if received > descriptor.size {
                return Err(Error::Bad("a layer is larger than its manifest says".to_owned()));
            }
            hasher.update(&chunk);
            file.write_all(&chunk)
                .await
                .map_err(|e| Error::Network(format!("the scratch disk refused a write: {e}")))?;
            progress(received);
        }
        file.flush().await.map_err(|e| Error::Network(format!("the scratch disk refused a write: {e}")))?;
        if received != descriptor.size
            || crate::hex(&hasher.finalize()) != descriptor.digest.trim_start_matches("sha256:")
        {
            return Err(Error::Bad("a layer isn't the one its manifest names".to_owned()));
        }
        Ok(())
    }

    async fn manifest(&self, reference: &str) -> Result<(Vec<u8>, String), Error> {
        let response = self.get(&format!("manifests/{reference}"), Some(ACCEPTED)).await?;
        let media_type = response
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(|v| v.split(';').next().unwrap_or(v).trim().to_owned())
            .unwrap_or_default();
        Ok((limited(response, MAX_DOCUMENT_BYTES).await?, media_type))
    }

    /// A GET under the repository, answering the registry's challenge once if it sends one.
    async fn get(&self, path: &str, accept: Option<&str>) -> Result<reqwest::Response, Error> {
        let url = self
            .base
            .join(&format!("v2/{}/{path}", self.reference.repository))
            .map_err(|_| Error::Bad("the reference doesn't make a URL".to_owned()))?;
        for answered in [false, true] {
            let mut request = self.http.get(url.clone());
            if let Some(accept) = accept {
                request = request.header(ACCEPT, accept);
            }
            if let Some(auth) = self.auth.lock().await.clone() {
                request = request.header(AUTHORIZATION, auth);
            }
            let response = request.send().await.map_err(|e| network(&e))?;
            match response.status() {
                StatusCode::UNAUTHORIZED if !answered => {
                    let challenge = response
                        .headers()
                        .get(WWW_AUTHENTICATE)
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or_default()
                        .to_owned();
                    let auth = self.answer(&challenge).await?;
                    *self.auth.lock().await = Some(auth);
                }
                status if status.is_success() => return Ok(response),
                StatusCode::UNAUTHORIZED | StatusCode::FORBIDDEN => {
                    return Err(Error::Refused(if self.login.is_some() {
                        "the registry refused the user name and token you gave".to_owned()
                    } else {
                        "the registry needs a sign-in for this image: it may be private, so give a read-only token"
                            .to_owned()
                    }));
                }
                StatusCode::NOT_FOUND => {
                    return Err(Error::Refused("the registry has no such image or tag".to_owned()));
                }
                StatusCode::TOO_MANY_REQUESTS => {
                    return Err(Error::Network("the registry is limiting requests; try again later".to_owned()));
                }
                status => return Err(Error::Network(format!("the registry answered {status}"))),
            }
        }
        Err(Error::Refused("the registry refused us after its sign-in".to_owned()))
    }

    /// The Authorization a challenge asks for: a bearer token from its realm, or basic auth.
    async fn answer(&self, challenge: &str) -> Result<HeaderValue, Error> {
        let (scheme, params) = challenge.split_once(' ').unwrap_or((challenge, ""));
        if scheme.eq_ignore_ascii_case("basic") {
            let login = self.login.as_ref().ok_or_else(|| {
                Error::Refused(
                    "the registry needs a sign-in for this image: give a user name and a read-only token".to_owned(),
                )
            })?;
            return secret_header(&format!("Basic {}", basic(login)));
        }
        if !scheme.eq_ignore_ascii_case("bearer") {
            return Err(Error::Bad(format!("the registry asks for a sign-in we don't know ({scheme})")));
        }
        let params = challenge_params(params);
        let realm = params.iter().find(|(k, _)| k == "realm").map(|(_, v)| v.as_str()).unwrap_or_default();
        let mut url: Url =
            realm.parse().map_err(|_| Error::Bad("the registry's sign-in address isn't a URL".to_owned()))?;
        if !guard::url_allowed(&url, &self.allowed) {
            return Err(Error::Refused("the registry's sign-in address isn't public".to_owned()));
        }
        {
            let mut query = url.query_pairs_mut();
            if let Some((_, service)) = params.iter().find(|(k, _)| k == "service") {
                query.append_pair("service", service);
            }
            query.append_pair("scope", &format!("repository:{}:pull", self.reference.repository));
        }
        let mut request = self.http.get(url);
        if let Some(login) = &self.login {
            request = request.header(AUTHORIZATION, secret_header(&format!("Basic {}", basic(login)))?);
        }
        let response = request.send().await.map_err(|e| network(&e))?;
        if !response.status().is_success() {
            return Err(Error::Refused(if self.login.is_some() {
                "the registry refused the user name and token you gave".to_owned()
            } else {
                "the registry wouldn't give access to this image: if it's private, give a user name and a read-only token"
                    .to_owned()
            }));
        }
        let body = limited(response, MAX_TOKEN_BYTES).await?;
        let token: Token =
            serde_json::from_slice(&body).map_err(|_| Error::Bad("the registry's token isn't JSON".to_owned()))?;
        let value = if token.token.is_empty() { token.access_token } else { token.token };
        if value.is_empty() {
            return Err(Error::Bad("the registry sent no token".to_owned()));
        }
        secret_header(&format!("Bearer {value}"))
    }
}

/// A token service's answer: Docker's `token`, or OAuth's `access_token`.
#[derive(Deserialize)]
struct Token {
    #[serde(default)]
    token: String,
    #[serde(default)]
    access_token: String,
}

fn secret_header(value: &str) -> Result<HeaderValue, Error> {
    let mut header =
        HeaderValue::from_str(value).map_err(|_| Error::Bad("the sign-in isn't a valid header".to_owned()))?;
    header.set_sensitive(true);
    Ok(header)
}

fn basic(login: &Login) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(format!("{}:{}", login.username, login.token))
}

/// `realm="…",service="…",scope="…"`, quoted values with commas inside kept whole.
fn challenge_params(text: &str) -> Vec<(String, String)> {
    let mut params = Vec::new();
    let mut rest = text.trim();
    while let Some((key, after)) = rest.split_once('=') {
        let key = key.trim().trim_start_matches(',').trim().to_ascii_lowercase();
        let (value, remainder) = if let Some(quoted) = after.strip_prefix('"') {
            match quoted.split_once('"') {
                Some((value, remainder)) => (value.to_owned(), remainder),
                None => (quoted.to_owned(), ""),
            }
        } else {
            match after.split_once(',') {
                Some((value, remainder)) => (value.trim().to_owned(), remainder),
                None => (after.trim().to_owned(), ""),
            }
        };
        params.push((key, value));
        rest = remainder.trim_start_matches(',').trim();
    }
    params
}

fn parse(bytes: &[u8]) -> Result<Document, Error> {
    let document: Document =
        serde_json::from_slice(bytes).map_err(|_| Error::Bad("the registry's manifest isn't JSON".to_owned()))?;
    if document.schema_version != 2 {
        return Err(Error::Bad(
            "the image uses an old manifest format (schema 1); push it again with a current Docker".to_owned(),
        ));
    }
    Ok(document)
}

fn manifest(digest: String, media_type: String, bytes: Vec<u8>) -> Result<Manifest, Error> {
    let document = parse(&bytes)?;
    let media_type = document.media_type.clone().unwrap_or(media_type);
    if media_type != OCI_MANIFEST && media_type != DOCKER_MANIFEST {
        return Err(Error::Bad(format!("{media_type:?} isn't a container image")));
    }
    let config = document.config.ok_or_else(|| Error::Bad("the manifest names no config".to_owned()))?;
    if !is_sha256(&config.digest) || document.layers.iter().any(|layer| !is_sha256(&layer.digest)) {
        return Err(Error::Bad("the manifest names content by something other than SHA-256".to_owned()));
    }
    Ok(Manifest { digest, media_type, bytes, config, layers: document.layers })
}

async fn limited(mut response: reqwest::Response, max: usize) -> Result<Vec<u8>, Error> {
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|e| network(&e))? {
        if body.len() + chunk.len() > max {
            return Err(Error::Bad("the registry sent more than a document holds".to_owned()));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

/// `sha256:<hex>` of `bytes`.
#[must_use]
pub fn sha256(bytes: &[u8]) -> String {
    format!("sha256:{}", crate::hex(&Sha256::digest(bytes)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_challenge_is_read_with_its_quoted_commas() {
        let params = challenge_params(
            r#"realm="https://auth.docker.io/token",service="registry.docker.io",scope="repository:a/b:pull,push""#,
        );
        assert_eq!(params[0], ("realm".to_owned(), "https://auth.docker.io/token".to_owned()));
        assert_eq!(params[1], ("service".to_owned(), "registry.docker.io".to_owned()));
        assert_eq!(params[2], ("scope".to_owned(), "repository:a/b:pull,push".to_owned()));
    }

    #[test]
    fn a_login_never_prints() {
        let login = Login { username: "me".to_owned(), token: "ghp_never_printed".to_owned() };
        assert!(!format!("{login:?}").contains("never"));
    }
}
