//! Object storage through a session's storage grants (ADR 0016, proto/README.md "Storage layout").
//!
//! A grant is short-lived S3 credentials scoped to a few prefixes, enforced by storage itself. The
//! agent checks the scope too, before any request, so a bug can't even try to write outside it, and
//! it stops using a grant once its `expires_in` has run out on the lease clock.
//!
//! Requests are signed by `rusty-s3`, which does no I/O and never looks for credentials of its own,
//! and sent with `reqwest` over our own rustls config: the ring provider, and the operating system's
//! trust store, since storage presents a public certificate.

use std::fmt;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use agent_proto::v1::{StorageGrant, StoragePermission};
use rustls_platform_verifier::BuilderVerifierExt;
use rusty_s3::actions::{ListObjectsV2, S3Action};
use rusty_s3::{Bucket, Credentials, UrlStyle};

use crate::lease_clock::LeaseClock;

/// How long each signed request stays valid.
const SIGNED_FOR: Duration = Duration::from_mins(15);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);
/// R2 signs as `auto`; `MinIO`, configured without a region, accepts any.
const REGION: &str = "auto";

/// A boxed future, for a trait the runner can hold as `dyn`.
pub type StorageFuture<'a, T> = Pin<Box<dyn Future<Output = Result<T, StorageError>> + Send + 'a>>;

/// What went wrong with storage.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StorageError {
    /// The key is outside the grant's prefixes, or a write through a read-only grant. Never sent.
    OutOfScope(String),
    /// The grant's lifetime has run out on the lease clock.
    Expired,
    /// Storage answered with an error, or couldn't be reached.
    Failed(String),
    /// The object doesn't exist.
    NotFound(String),
}

impl fmt::Display for StorageError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::OutOfScope(what) => write!(f, "outside the storage grant: {what}"),
            Self::Expired => f.write_str("the storage grant has expired"),
            Self::Failed(e) => write!(f, "storage failed: {e}"),
            Self::NotFound(key) => write!(f, "{key} doesn't exist"),
        }
    }
}

impl std::error::Error for StorageError {}

/// The objects a job reads and writes, through one grant.
pub trait ObjectStore: Send + Sync {
    /// Writes `body` at `key`.
    fn put<'a>(&'a self, key: &'a str, body: Vec<u8>) -> StorageFuture<'a, ()>;
    /// Reads the object at `key`.
    fn get<'a>(&'a self, key: &'a str) -> StorageFuture<'a, Vec<u8>>;
    /// Every key under `prefix`.
    fn list<'a>(&'a self, prefix: &'a str) -> StorageFuture<'a, Vec<String>>;
}

/// A storage grant as the agent holds it. Its secrets never reach a log: [`fmt::Debug`] leaves them
/// out.
#[derive(Clone)]
pub struct Grant {
    endpoint: reqwest::Url,
    bucket: String,
    access_key_id: String,
    secret_access_key: String,
    session_token: String,
    prefixes: Vec<String>,
    writable: bool,
    /// When it stops being usable, on the lease clock.
    expires_at: Duration,
}

impl fmt::Debug for Grant {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Grant")
            .field("bucket", &self.bucket)
            .field("prefixes", &self.prefixes)
            .field("writable", &self.writable)
            .finish_non_exhaustive()
    }
}

impl Grant {
    /// Checks a grant from a `StartSession` against the proto's rules; `received_at` is the lease
    /// clock when it arrived, from which `expires_in` counts.
    ///
    /// # Errors
    /// Why the grant is invalid. The message never contains a secret.
    pub fn from_message(grant: &StorageGrant, received_at: Duration) -> Result<Self, String> {
        let endpoint = reqwest::Url::parse(&grant.endpoint).map_err(|_| "the grant's endpoint isn't a URL")?;
        if !matches!(endpoint.scheme(), "http" | "https") || endpoint.host_str().is_none() {
            return Err("the grant's endpoint must be an http or https URL with a host".to_owned());
        }
        if grant.bucket.is_empty() || grant.access_key_id.is_empty() || grant.secret_access_key.is_empty() {
            return Err("the grant lacks a bucket or keys".to_owned());
        }
        if grant.session_token.is_empty() {
            return Err("the grant lacks a session token".to_owned());
        }
        if grant.prefixes.is_empty() || grant.prefixes.iter().any(|prefix| !valid_prefix(prefix)) {
            return Err("the grant's prefixes must be non-empty and each end in /".to_owned());
        }
        let writable = match grant.permission() {
            StoragePermission::ReadWrite => true,
            StoragePermission::ReadOnly => false,
            StoragePermission::Unspecified => return Err("the grant has no permission".to_owned()),
        };
        let expires_in = grant
            .expires_in
            .and_then(|value| Duration::try_from(value).ok())
            .filter(|value| !value.is_zero())
            .ok_or("the grant has no expiry")?;
        Ok(Self {
            endpoint,
            bucket: grant.bucket.clone(),
            access_key_id: grant.access_key_id.clone(),
            secret_access_key: grant.secret_access_key.clone(),
            session_token: grant.session_token.clone(),
            prefixes: grant.prefixes.clone(),
            writable,
            expires_at: received_at + expires_in,
        })
    }

    /// The prefixes it covers.
    #[must_use]
    pub fn prefixes(&self) -> &[String] {
        &self.prefixes
    }

    /// Whether `key` (an object, or a prefix to list) is inside the grant, for a write or a read.
    ///
    /// # Errors
    /// [`StorageError::OutOfScope`] if not.
    pub fn check(&self, key: &str, write: bool) -> Result<(), StorageError> {
        if write && !self.writable {
            return Err(StorageError::OutOfScope(format!("{key} (the grant is read-only)")));
        }
        if key.split('/').any(|part| part == ".." || part == ".") {
            return Err(StorageError::OutOfScope(key.to_owned()));
        }
        if self.prefixes.iter().any(|prefix| key.starts_with(prefix.as_str()) && key.len() > prefix.len()) {
            Ok(())
        } else {
            Err(StorageError::OutOfScope(key.to_owned()))
        }
    }

    fn live(&self, clock: &dyn LeaseClock) -> Result<(), StorageError> {
        if clock.now() >= self.expires_at { Err(StorageError::Expired) } else { Ok(()) }
    }
}

fn valid_prefix(prefix: &str) -> bool {
    prefix.ends_with('/') && prefix.len() > 1 && !prefix.starts_with('/') && !prefix.split('/').any(|p| p == "..")
}

/// The HTTP client for storage.
///
/// # Errors
/// If the operating system's trust store can't be loaded.
pub fn http_client() -> Result<reqwest::Client, String> {
    let tls = rustls::ClientConfig::builder_with_provider(crate::tls::provider())
        .with_safe_default_protocol_versions()
        .and_then(BuilderVerifierExt::with_platform_verifier)
        .map_err(|e| format!("storage TLS couldn't be set up: {e}"))?
        .with_no_client_auth();
    reqwest::Client::builder()
        .use_preconfigured_tls(tls)
        .connect_timeout(CONNECT_TIMEOUT)
        .timeout(REQUEST_TIMEOUT)
        .build()
        .map_err(|e| format!("the storage client couldn't be built: {e}"))
}

/// S3-compatible storage through one grant.
pub struct S3Store {
    grant: Grant,
    bucket: Bucket,
    credentials: Credentials,
    http: reqwest::Client,
    clock: Arc<dyn LeaseClock>,
}

impl S3Store {
    /// A store for `grant`.
    ///
    /// # Errors
    /// If the grant's endpoint and bucket don't make a bucket URL.
    pub fn new(grant: Grant, http: reqwest::Client, clock: Arc<dyn LeaseClock>) -> Result<Self, String> {
        let bucket = Bucket::new(grant.endpoint.clone(), UrlStyle::Path, grant.bucket.clone(), REGION)
            .map_err(|e| format!("the grant's bucket isn't usable: {e}"))?;
        let credentials = Credentials::new_with_token(
            grant.access_key_id.clone(),
            grant.secret_access_key.clone(),
            grant.session_token.clone(),
        );
        Ok(Self { grant, bucket, credentials, http, clock })
    }

    async fn send(&self, request: reqwest::RequestBuilder, key: &str) -> Result<reqwest::Response, StorageError> {
        let response = request.send().await.map_err(|e| StorageError::Failed(describe(&e)))?;
        match response.status() {
            status if status.is_success() => Ok(response),
            reqwest::StatusCode::NOT_FOUND => Err(StorageError::NotFound(key.to_owned())),
            status => Err(StorageError::Failed(format!("{status} for {key}"))),
        }
    }
}

/// A transport error without its URL, which carries a signature and session token: what kind of
/// failure it was, then its causes.
fn describe(error: &reqwest::Error) -> String {
    let mut text = if error.is_timeout() {
        "timed out"
    } else if error.is_connect() {
        "couldn't connect"
    } else if error.is_body() || error.is_decode() {
        "the response body failed"
    } else {
        "the request failed"
    }
    .to_owned();
    let mut source = std::error::Error::source(error);
    while let Some(cause) = source {
        text.push_str(": ");
        text.push_str(&cause.to_string());
        source = cause.source();
    }
    text
}

impl ObjectStore for S3Store {
    fn put<'a>(&'a self, key: &'a str, body: Vec<u8>) -> StorageFuture<'a, ()> {
        Box::pin(async move {
            self.grant.check(key, true)?;
            self.grant.live(self.clock.as_ref())?;
            let url = self.bucket.put_object(Some(&self.credentials), key).sign(SIGNED_FOR);
            self.send(self.http.put(url).body(body), key).await.map(|_| ())
        })
    }

    fn get<'a>(&'a self, key: &'a str) -> StorageFuture<'a, Vec<u8>> {
        Box::pin(async move {
            self.grant.check(key, false)?;
            self.grant.live(self.clock.as_ref())?;
            let url = self.bucket.get_object(Some(&self.credentials), key).sign(SIGNED_FOR);
            let response = self.send(self.http.get(url), key).await?;
            let body = response.bytes().await.map_err(|e| StorageError::Failed(describe(&e)))?;
            Ok(body.to_vec())
        })
    }

    fn list<'a>(&'a self, prefix: &'a str) -> StorageFuture<'a, Vec<String>> {
        Box::pin(async move {
            self.grant.check(prefix, false)?;
            let mut keys = Vec::new();
            let mut token: Option<String> = None;
            loop {
                self.grant.live(self.clock.as_ref())?;
                let mut action = self.bucket.list_objects_v2(Some(&self.credentials));
                action.with_prefix(prefix);
                if let Some(token) = &token {
                    action.with_continuation_token(token.clone());
                }
                let url = action.sign(SIGNED_FOR);
                let response = self.send(self.http.get(url), prefix).await?;
                let text = response.text().await.map_err(|e| StorageError::Failed(describe(&e)))?;
                let page = ListObjectsV2::parse_response(&text)
                    .map_err(|e| StorageError::Failed(format!("the listing didn't parse: {e}")))?;
                keys.extend(page.contents.into_iter().map(|object| object.key));
                match page.next_continuation_token {
                    Some(next) => token = Some(next),
                    None => return Ok(keys),
                }
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lease_clock::FakeLeaseClock;

    fn message(permission: StoragePermission, prefixes: &[&str]) -> StorageGrant {
        StorageGrant {
            endpoint: "http://localhost:9000".to_owned(),
            bucket: "idleo-test".to_owned(),
            access_key_id: "TESTACCESSKEY".to_owned(),
            secret_access_key: "test-secret-never-logged".to_owned(),
            session_token: "test-token-never-logged".to_owned(),
            prefixes: prefixes.iter().map(|&p| p.to_owned()).collect(),
            permission: permission.into(),
            expires_in: Some(prost_types::Duration { seconds: 3600, nanos: 0 }),
        }
    }

    const ATTEMPT: &str = "sessions/0190f3a2-0000-7000-8000-000000000000/attempt-2/";
    const INPUT: &str = "sessions/0190f3a2-0000-7000-8000-000000000000/input/";

    #[test]
    fn a_read_write_grant_writes_only_under_its_prefixes() {
        let grant = Grant::from_message(&message(StoragePermission::ReadWrite, &[ATTEMPT]), Duration::ZERO).unwrap();
        assert_eq!(grant.check(&format!("{ATTEMPT}checkpoints/00000001/state.json"), true), Ok(()));
        assert!(grant.check(&format!("{INPUT}data.txt"), true).is_err(), "the input");
        assert!(
            grant.check("sessions/0190f3a2-0000-7000-8000-000000000000/attempt-1/outputs/x", true).is_err(),
            "another attempt"
        );
        assert!(grant.check(&format!("{ATTEMPT}../attempt-1/x"), true).is_err(), "climbing out");
        assert!(grant.check(ATTEMPT.trim_end_matches('/'), true).is_err(), "the prefix itself");
    }

    #[test]
    fn a_read_only_grant_never_writes() {
        let grant = Grant::from_message(&message(StoragePermission::ReadOnly, &[INPUT]), Duration::ZERO).unwrap();
        assert_eq!(grant.check(&format!("{INPUT}data.txt"), false), Ok(()));
        assert!(matches!(grant.check(&format!("{INPUT}data.txt"), true), Err(StorageError::OutOfScope(_))));
    }

    #[test]
    fn a_grant_expires_on_the_lease_clock() {
        let clock = FakeLeaseClock::default();
        clock.advance(Duration::from_secs(100));
        let grant = Grant::from_message(&message(StoragePermission::ReadWrite, &[ATTEMPT]), clock.now()).unwrap();
        assert_eq!(grant.live(&clock), Ok(()));
        clock.advance(Duration::from_secs(3600));
        assert_eq!(grant.live(&clock), Err(StorageError::Expired));
    }

    #[test]
    fn invalid_grants_are_refused_without_naming_secrets() {
        let cases = [
            StorageGrant { endpoint: "ftp://x".to_owned(), ..message(StoragePermission::ReadWrite, &[ATTEMPT]) },
            StorageGrant { session_token: String::new(), ..message(StoragePermission::ReadWrite, &[ATTEMPT]) },
            message(StoragePermission::Unspecified, &[ATTEMPT]),
            message(StoragePermission::ReadWrite, &[]),
            message(StoragePermission::ReadWrite, &["sessions/no-slash"]),
            StorageGrant { expires_in: None, ..message(StoragePermission::ReadWrite, &[ATTEMPT]) },
        ];
        for case in cases {
            let error = Grant::from_message(&case, Duration::ZERO).unwrap_err();
            assert!(!error.contains("never-logged"), "{error}");
        }
    }

    #[test]
    fn debug_output_leaves_the_secrets_out() {
        let grant = Grant::from_message(&message(StoragePermission::ReadWrite, &[ATTEMPT]), Duration::ZERO).unwrap();
        let printed = format!("{grant:?}");
        assert!(printed.contains(ATTEMPT));
        assert!(!printed.contains("never-logged") && !printed.contains("TESTACCESSKEY"), "{printed}");
    }

    #[test]
    fn the_http_client_builds() {
        assert!(http_client().is_ok());
    }
}
