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
use std::path::Path;
use std::pin::Pin;
use std::sync::{Arc, PoisonError, RwLock};
use std::time::Duration;

use agent_proto::v1::{StorageGrant, StoragePermission};
use rustls_platform_verifier::BuilderVerifierExt;
use rusty_s3::actions::{CreateMultipartUpload, ListObjectsV2, S3Action};
use rusty_s3::{Bucket, Credentials, UrlStyle};
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;

use crate::lease_clock::LeaseClock;

/// How long each signed request stays valid.
const SIGNED_FOR: Duration = Duration::from_mins(15);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(120);
/// R2 signs as `auto`; `MinIO`, configured without a region, accepts any.
const REGION: &str = "auto";
/// A file is uploaded in parts of this size, so a 2 GiB checkpoint never sits in memory whole. Storage's minimum for
/// every part but the last is 5 MiB.
pub const PART_BYTES: usize = 16 * 1024 * 1024;
/// How many times a request of a file upload is tried (each part, and the two calls around them) before it fails.
const UPLOAD_TRIES: u32 = 3;
/// The pause before a retry is this times the number of tries so far.
const RETRY_DELAY: Duration = Duration::from_secs(1);

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
    /// Streams the object at `key` into a new file at `path`, never holding it all in memory, and returns how
    /// many bytes were written. A file already at `path` is replaced.
    fn download<'a>(&'a self, key: &'a str, path: &'a Path) -> StorageFuture<'a, u64>;
    /// Uploads the file at `path` to `key` a part at a time, never holding more than one part in memory, retrying each
    /// part, and returns the file's size and SHA-256 as read.
    fn put_file<'a>(&'a self, key: &'a str, path: &'a Path) -> StorageFuture<'a, Uploaded>;
}

/// A file as it was uploaded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Uploaded {
    /// Its size in bytes.
    pub size: u64,
    /// Its SHA-256, in lower-case hex.
    pub sha256: String,
}

/// Lower-case hex of `bytes`.
#[must_use]
pub fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut text, byte| {
        let _ = write!(text, "{byte:02x}");
        text
    })
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

/// S3-compatible storage through one grant, which `RefreshCredentials` can replace while the job runs.
pub struct S3Store {
    signed: RwLock<Signed>,
    http: reqwest::Client,
    clock: Arc<dyn LeaseClock>,
    part_bytes: usize,
    retry_delay: Duration,
}

/// A grant and what it signs with.
#[derive(Clone)]
struct Signed {
    grant: Grant,
    bucket: Bucket,
    credentials: Credentials,
}

impl Signed {
    fn new(grant: Grant) -> Result<Self, String> {
        let bucket = Bucket::new(grant.endpoint.clone(), UrlStyle::Path, grant.bucket.clone(), REGION)
            .map_err(|e| format!("the grant's bucket isn't usable: {e}"))?;
        let credentials = Credentials::new_with_token(
            grant.access_key_id.clone(),
            grant.secret_access_key.clone(),
            grant.session_token.clone(),
        );
        Ok(Self { grant, bucket, credentials })
    }
}

impl S3Store {
    /// A store for `grant`.
    ///
    /// # Errors
    /// If the grant's endpoint and bucket don't make a bucket URL.
    pub fn new(grant: Grant, http: reqwest::Client, clock: Arc<dyn LeaseClock>) -> Result<Self, String> {
        Ok(Self {
            signed: RwLock::new(Signed::new(grant)?),
            http,
            clock,
            part_bytes: PART_BYTES,
            retry_delay: RETRY_DELAY,
        })
    }

    /// Uploads files in parts of `part_bytes` and pauses `retry_delay` times the tries so far between retries, for
    /// tests against a server that takes any size of part.
    #[must_use]
    pub fn tuned(mut self, part_bytes: usize, retry_delay: Duration) -> Self {
        self.part_bytes = part_bytes.max(1);
        self.retry_delay = retry_delay;
        self
    }

    /// Swaps in fresh credentials for the same scope. Requests already under way finish with the old ones.
    ///
    /// # Errors
    /// If the new grant covers different prefixes or a different permission, or isn't usable.
    pub fn replace(&self, grant: Grant) -> Result<(), String> {
        let mut signed = self.signed.write().unwrap_or_else(PoisonError::into_inner);
        if grant.prefixes != signed.grant.prefixes || grant.writable != signed.grant.writable {
            return Err("the new grant doesn't cover the same prefixes with the same permission".to_owned());
        }
        *signed = Signed::new(grant)?;
        Ok(())
    }

    /// The current grant, for one request.
    fn current(&self) -> Signed {
        self.signed.read().unwrap_or_else(PoisonError::into_inner).clone()
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

impl S3Store {
    /// Runs one request of a file upload up to [`UPLOAD_TRIES`] times. A grant problem is final at once.
    async fn retrying<T, F, Fut>(&self, mut attempt: F) -> Result<T, StorageError>
    where
        F: FnMut() -> Fut,
        Fut: Future<Output = Result<T, StorageError>>,
    {
        let mut tried = 0;
        loop {
            tried += 1;
            match attempt().await {
                Err(StorageError::Failed(reason)) if tried < UPLOAD_TRIES => {
                    tracing::warn!(tried, %reason, "a request of an upload failed; trying again");
                    tokio::time::sleep(self.retry_delay * tried).await;
                }
                other => return other,
            }
        }
    }

    /// One whole object in one request, for a file that's no bigger than a part.
    async fn put_small(&self, key: &str, body: Vec<u8>) -> Result<(), StorageError> {
        self.retrying(|| async {
            let signed = self.current();
            signed.grant.check(key, true)?;
            signed.grant.live(self.clock.as_ref())?;
            let url = signed.bucket.put_object(Some(&signed.credentials), key).sign(SIGNED_FOR);
            self.send(self.http.put(url).body(body.clone()), key).await.map(|_| ())
        })
        .await
    }

    async fn start_multipart(&self, key: &str) -> Result<String, StorageError> {
        self.retrying(|| async {
            let signed = self.current();
            signed.grant.check(key, true)?;
            signed.grant.live(self.clock.as_ref())?;
            let url = signed.bucket.create_multipart_upload(Some(&signed.credentials), key).sign(SIGNED_FOR);
            let response = self.send(self.http.post(url), key).await?;
            let text = response.text().await.map_err(|e| StorageError::Failed(describe(&e)))?;
            CreateMultipartUpload::parse_response(&text)
                .map(|parsed| parsed.upload_id().to_owned())
                .map_err(|e| StorageError::Failed(format!("the upload's start didn't parse: {e}")))
        })
        .await
    }

    async fn put_part(&self, key: &str, upload_id: &str, number: u16, body: &[u8]) -> Result<String, StorageError> {
        self.retrying(|| async {
            let signed = self.current();
            signed.grant.check(key, true)?;
            signed.grant.live(self.clock.as_ref())?;
            let url = signed.bucket.upload_part(Some(&signed.credentials), key, number, upload_id).sign(SIGNED_FOR);
            let response = self.send(self.http.put(url).body(body.to_vec()), key).await?;
            response
                .headers()
                .get(reqwest::header::ETAG)
                .and_then(|etag| etag.to_str().ok())
                .map(str::to_owned)
                .ok_or_else(|| StorageError::Failed("storage took a part but gave no ETag".to_owned()))
        })
        .await
    }

    async fn finish_multipart(&self, key: &str, upload_id: &str, etags: &[String]) -> Result<(), StorageError> {
        self.retrying(|| async {
            let signed = self.current();
            signed.grant.check(key, true)?;
            signed.grant.live(self.clock.as_ref())?;
            let action = signed.bucket.complete_multipart_upload(
                Some(&signed.credentials),
                key,
                upload_id,
                etags.iter().map(String::as_str),
            );
            let body = action.clone().body();
            let url = action.sign(SIGNED_FOR);
            self.send(self.http.post(url).body(body), key).await.map(|_| ())
        })
        .await
    }

    /// Gives up an upload so storage doesn't keep its parts. Best effort: a grant that has run out can't.
    async fn abort_multipart(&self, key: &str, upload_id: &str) {
        let signed = self.current();
        let url = signed.bucket.abort_multipart_upload(Some(&signed.credentials), key, upload_id).sign(SIGNED_FOR);
        if let Err(error) = self.send(self.http.delete(url), key).await {
            tracing::debug!(%error, "an unfinished upload couldn't be aborted");
        }
    }

    async fn upload_file(&self, key: &str, path: &Path) -> Result<Uploaded, StorageError> {
        let local = |e: std::io::Error| StorageError::Failed(format!("reading {} failed: {e}", path.display()));
        {
            let signed = self.current();
            signed.grant.check(key, true)?;
            signed.grant.live(self.clock.as_ref())?;
        }
        let mut file = tokio::fs::File::open(path).await.map_err(local)?;
        let small = file.metadata().await.map_err(local)?.len() <= self.part_bytes as u64;
        let mut hasher = Sha256::new();
        let mut total = 0_u64;
        let mut upload: Option<(String, Vec<String>)> = None;
        let outcome: Result<(), StorageError> = async {
            loop {
                let mut part = vec![0_u8; self.part_bytes];
                let mut filled = 0;
                while filled < part.len() {
                    let read = file.read(&mut part[filled..]).await.map_err(local)?;
                    if read == 0 {
                        break;
                    }
                    filled += read;
                }
                part.truncate(filled);
                hasher.update(&part);
                total += filled as u64;
                let last = filled < self.part_bytes;
                match &mut upload {
                    None if last || small => return self.put_small(key, part).await,
                    None => {
                        let id = self.start_multipart(key).await?;
                        upload = Some((id, Vec::new()));
                    }
                    Some(_) => {}
                }
                let (id, etags) = upload.as_mut().expect("a multipart upload was started above");
                if !part.is_empty() {
                    let number = u16::try_from(etags.len() + 1)
                        .map_err(|_| StorageError::Failed("the file needs more than 65,535 parts".to_owned()))?;
                    etags.push(self.put_part(key, id, number, &part).await?);
                }
                if last {
                    return self.finish_multipart(key, id, etags).await;
                }
            }
        }
        .await;
        match outcome {
            Ok(()) => Ok(Uploaded { size: total, sha256: hex(&hasher.finalize()) }),
            Err(error) => {
                if let Some((id, _)) = &upload {
                    self.abort_multipart(key, id).await;
                }
                Err(error)
            }
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
            let signed = self.current();
            signed.grant.check(key, true)?;
            signed.grant.live(self.clock.as_ref())?;
            let url = signed.bucket.put_object(Some(&signed.credentials), key).sign(SIGNED_FOR);
            self.send(self.http.put(url).body(body), key).await.map(|_| ())
        })
    }

    fn get<'a>(&'a self, key: &'a str) -> StorageFuture<'a, Vec<u8>> {
        Box::pin(async move {
            let signed = self.current();
            signed.grant.check(key, false)?;
            signed.grant.live(self.clock.as_ref())?;
            let url = signed.bucket.get_object(Some(&signed.credentials), key).sign(SIGNED_FOR);
            let response = self.send(self.http.get(url), key).await?;
            let body = response.bytes().await.map_err(|e| StorageError::Failed(describe(&e)))?;
            Ok(body.to_vec())
        })
    }

    fn put_file<'a>(&'a self, key: &'a str, path: &'a Path) -> StorageFuture<'a, Uploaded> {
        Box::pin(self.upload_file(key, path))
    }

    fn download<'a>(&'a self, key: &'a str, path: &'a Path) -> StorageFuture<'a, u64> {
        Box::pin(async move {
            use tokio::io::AsyncWriteExt;
            let signed = self.current();
            signed.grant.check(key, false)?;
            signed.grant.live(self.clock.as_ref())?;
            let url = signed.bucket.get_object(Some(&signed.credentials), key).sign(SIGNED_FOR);
            let mut response = self.send(self.http.get(url), key).await?;
            let local = |e: std::io::Error| StorageError::Failed(format!("writing the download failed: {e}"));
            let mut file = tokio::fs::File::create(path).await.map_err(local)?;
            let mut written = 0_u64;
            while let Some(chunk) = response.chunk().await.map_err(|e| StorageError::Failed(describe(&e)))? {
                file.write_all(&chunk).await.map_err(local)?;
                written += chunk.len() as u64;
            }
            file.flush().await.map_err(local)?;
            Ok(written)
        })
    }

    fn list<'a>(&'a self, prefix: &'a str) -> StorageFuture<'a, Vec<String>> {
        Box::pin(async move {
            let mut keys = Vec::new();
            let mut token: Option<String> = None;
            loop {
                let signed = self.current();
                signed.grant.check(prefix, false)?;
                signed.grant.live(self.clock.as_ref())?;
                let mut action = signed.bucket.list_objects_v2(Some(&signed.credentials));
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

/// Storage in memory with a grant's scope checks, for testing what reads and writes it without a server.
#[cfg(any(test, feature = "testing"))]
#[allow(clippy::missing_panics_doc)] // Test doubles: a poisoned lock or an invalid fixture is a bug in the test.
pub mod testing {
    use std::collections::BTreeMap;
    use std::path::Path;
    use std::sync::atomic::{AtomicU32, Ordering};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    use agent_proto::v1::{StorageGrant, StoragePermission};

    use super::{Grant, ObjectStore, StorageError, StorageFuture};

    /// Every object, by key.
    pub type Objects = Arc<Mutex<BTreeMap<String, Vec<u8>>>>;

    pub struct MemoryStore {
        grant: Grant,
        objects: Objects,
        /// Writes refused before the next one is let through.
        failing_puts: AtomicU32,
    }

    impl MemoryStore {
        pub fn new(objects: &Objects, prefixes: &[&str], writable: bool) -> Self {
            let message = StorageGrant {
                endpoint: "http://storage.invalid".to_owned(),
                bucket: "idleo-test".to_owned(),
                access_key_id: "TESTKEY".to_owned(),
                secret_access_key: "test-secret".to_owned(),
                session_token: "test-token".to_owned(),
                prefixes: prefixes.iter().map(|&prefix| prefix.to_owned()).collect(),
                permission: if writable { StoragePermission::ReadWrite } else { StoragePermission::ReadOnly }.into(),
                expires_in: Some(prost_types::Duration { seconds: 3600, nanos: 0 }),
            };
            Self {
                grant: Grant::from_message(&message, Duration::ZERO).expect("a valid test grant"),
                objects: Arc::clone(objects),
                failing_puts: AtomicU32::new(0),
            }
        }

        /// The grant it checks keys against.
        pub fn grant(&self) -> Grant {
            self.grant.clone()
        }

        /// Refuses the next `count` writes, as storage that's briefly down would.
        pub fn fail_puts(&self, count: u32) {
            self.failing_puts.store(count, Ordering::SeqCst);
        }
    }

    impl ObjectStore for MemoryStore {
        fn put<'a>(&'a self, key: &'a str, body: Vec<u8>) -> StorageFuture<'a, ()> {
            Box::pin(async move {
                self.grant.check(key, true)?;
                if self.failing_puts.try_update(Ordering::SeqCst, Ordering::SeqCst, |left| left.checked_sub(1)).is_ok()
                {
                    return Err(StorageError::Failed("storage is down".to_owned()));
                }
                self.objects.lock().unwrap().insert(key.to_owned(), body);
                Ok(())
            })
        }

        fn get<'a>(&'a self, key: &'a str) -> StorageFuture<'a, Vec<u8>> {
            Box::pin(async move {
                self.grant.check(key, false)?;
                self.objects.lock().unwrap().get(key).cloned().ok_or_else(|| StorageError::NotFound(key.to_owned()))
            })
        }

        fn list<'a>(&'a self, prefix: &'a str) -> StorageFuture<'a, Vec<String>> {
            Box::pin(async move {
                self.grant.check(prefix, false)?;
                Ok(self.objects.lock().unwrap().keys().filter(|key| key.starts_with(prefix)).cloned().collect())
            })
        }

        fn put_file<'a>(&'a self, key: &'a str, path: &'a Path) -> StorageFuture<'a, super::Uploaded> {
            Box::pin(async move {
                let body = tokio::fs::read(path).await.map_err(|e| StorageError::Failed(e.to_string()))?;
                let uploaded = super::Uploaded {
                    size: body.len() as u64,
                    sha256: super::hex(&<sha2::Sha256 as sha2::Digest>::digest(&body)),
                };
                self.put(key, body).await?;
                Ok(uploaded)
            })
        }

        fn download<'a>(&'a self, key: &'a str, path: &'a Path) -> StorageFuture<'a, u64> {
            Box::pin(async move {
                let body = self.get(key).await?;
                tokio::fs::write(path, &body).await.map_err(|e| StorageError::Failed(e.to_string()))?;
                Ok(body.len() as u64)
            })
        }
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

    #[tokio::test]
    async fn refreshed_credentials_replace_an_expired_grant_in_place() {
        let clock = Arc::new(FakeLeaseClock::default());
        let with_token = |token: &str| StorageGrant {
            endpoint: "http://127.0.0.1:9".to_owned(),
            session_token: token.to_owned(),
            expires_in: Some(prost_types::Duration { seconds: 60, nanos: 0 }),
            ..message(StoragePermission::ReadWrite, &[ATTEMPT])
        };
        let first = Grant::from_message(&with_token("first-token"), clock.now()).unwrap();
        let store = S3Store::new(first, http_client().unwrap(), Arc::clone(&clock) as Arc<dyn LeaseClock>).unwrap();
        let key = format!("{ATTEMPT}checkpoints/00000001/state.json");

        clock.advance(Duration::from_secs(61));
        assert_eq!(store.put(&key, b"x".to_vec()).await, Err(StorageError::Expired));

        store.replace(Grant::from_message(&with_token("second-token"), clock.now()).unwrap()).unwrap();
        // Past the grant check now; the closed port refuses the connection.
        assert!(matches!(store.put(&key, b"x".to_vec()).await, Err(StorageError::Failed(_))));
        let other = Grant::from_message(&message(StoragePermission::ReadOnly, &[ATTEMPT]), clock.now()).unwrap();
        assert!(store.replace(other).is_err(), "a different permission");
    }

    #[test]
    fn the_http_client_builds() {
        assert!(http_client().is_ok());
    }

    /// A small S3 server on a local port: single puts, and the multipart calls, which it can be told to fail.
    mod server {
        use std::collections::BTreeMap;
        use std::sync::{Arc, Mutex};

        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tokio::net::TcpListener;

        #[derive(Default)]
        pub struct State {
            pub single: Option<Vec<u8>>,
            pub started: u32,
            pub parts: BTreeMap<u16, Vec<u8>>,
            /// Every part request that arrived, failed or not.
            pub part_requests: Vec<u16>,
            /// Part number -> how many of its requests are refused before one is taken.
            pub refuse: BTreeMap<u16, u32>,
            pub completed_with: Option<String>,
            pub aborted: bool,
        }

        pub async fn start() -> (String, Arc<Mutex<State>>) {
            let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
            let endpoint = format!("http://{}", listener.local_addr().unwrap());
            let state = Arc::new(Mutex::new(State::default()));
            let shared = Arc::clone(&state);
            tokio::spawn(async move {
                loop {
                    let Ok((mut socket, _)) = listener.accept().await else { return };
                    let state = Arc::clone(&shared);
                    tokio::spawn(async move {
                        let mut raw = Vec::new();
                        let mut chunk = [0_u8; 8192];
                        let (head_end, length) = loop {
                            let read = socket.read(&mut chunk).await.unwrap_or(0);
                            if read == 0 {
                                return;
                            }
                            raw.extend_from_slice(&chunk[..read]);
                            if let Some(end) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
                                let head = String::from_utf8_lossy(&raw[..end]).to_ascii_lowercase();
                                let length = head
                                    .lines()
                                    .find_map(|line| line.strip_prefix("content-length: "))
                                    .and_then(|value| value.trim().parse::<usize>().ok())
                                    .unwrap_or(0);
                                break (end + 4, length);
                            }
                        };
                        while raw.len() < head_end + length {
                            let read = socket.read(&mut chunk).await.unwrap_or(0);
                            if read == 0 {
                                return;
                            }
                            raw.extend_from_slice(&chunk[..read]);
                        }
                        let head = String::from_utf8_lossy(&raw[..head_end]).into_owned();
                        let body = raw[head_end..head_end + length].to_vec();
                        let target = head.split_whitespace().nth(1).unwrap_or("/").to_owned();
                        let method = head.split_whitespace().next().unwrap_or("").to_owned();
                        let query = target.split_once('?').map_or("", |(_, query)| query);
                        let param = |name: &str| {
                            query.split('&').find_map(|pair| pair.strip_prefix(&format!("{name}=")).map(str::to_owned))
                        };
                        let (status, extra, text) = {
                            let mut state = state.lock().unwrap();
                            match method.as_str() {
                                "POST" if query.contains("uploads") => {
                                    state.started += 1;
                                    (
                                        200,
                                        String::new(),
                                        "<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Bucket>b</Bucket><Key>k</Key><UploadId>up-1</UploadId></InitiateMultipartUploadResult>".to_owned(),
                                    )
                                }
                                "PUT" if param("partNumber").is_some() => {
                                    let number: u16 = param("partNumber").unwrap().parse().unwrap();
                                    state.part_requests.push(number);
                                    match state.refuse.get_mut(&number) {
                                        Some(left) if *left > 0 => {
                                            *left -= 1;
                                            (500, String::new(), String::new())
                                        }
                                        _ => {
                                            state.parts.insert(number, body);
                                            (200, format!("ETag: \"etag-{number}\"\r\n"), String::new())
                                        }
                                    }
                                }
                                "POST" if query.contains("uploadId=") => {
                                    state.completed_with = Some(String::from_utf8_lossy(&body).into_owned());
                                    (200, String::new(), "<CompleteMultipartUploadResult/>".to_owned())
                                }
                                "DELETE" if query.contains("uploadId=") => {
                                    state.aborted = true;
                                    (204, String::new(), String::new())
                                }
                                "PUT" => {
                                    state.single = Some(body);
                                    (200, String::new(), String::new())
                                }
                                _ => (400, String::new(), String::new()),
                            }
                        };
                        let reply = format!(
                            "HTTP/1.1 {status} X\r\n{extra}Content-Length: {}\r\nConnection: close\r\n\r\n{text}",
                            text.len()
                        );
                        let _ = socket.write_all(reply.as_bytes()).await;
                        let _ = socket.shutdown().await;
                    });
                }
            });
            (endpoint, state)
        }
    }

    fn store_at(endpoint: &str, part_bytes: usize) -> S3Store {
        let grant = StorageGrant { endpoint: endpoint.to_owned(), ..message(StoragePermission::ReadWrite, &[ATTEMPT]) };
        let clock = Arc::new(FakeLeaseClock::default());
        S3Store::new(Grant::from_message(&grant, clock.now()).unwrap(), http_client().unwrap(), clock)
            .unwrap()
            .tuned(part_bytes, Duration::from_millis(5))
    }

    fn file_of(size: usize) -> (tempfile::TempDir, std::path::PathBuf, Vec<u8>) {
        let folder = tempfile::tempdir().unwrap();
        let path = folder.path().join("big.bin");
        let bytes: Vec<u8> = (0..size).map(|i| u8::try_from((i * 31 + i / 251) % 256).unwrap()).collect();
        std::fs::write(&path, &bytes).unwrap();
        (folder, path, bytes)
    }

    fn sha256_of(bytes: &[u8]) -> String {
        hex(&Sha256::digest(bytes))
    }

    #[tokio::test]
    async fn a_file_bigger_than_a_part_goes_up_in_parts_with_a_part_retried_and_its_hash_recorded() {
        let (endpoint, state) = server::start().await;
        let store = store_at(&endpoint, 1_000_000);
        let (_folder, path, bytes) = file_of(2_500_000);
        state.lock().unwrap().refuse.insert(2, 1);
        let key = format!("{ATTEMPT}checkpoints/00000001/model.bin");

        let uploaded = store.put_file(&key, &path).await.unwrap();

        assert_eq!(uploaded, Uploaded { size: 2_500_000, sha256: sha256_of(&bytes) });
        let state = state.lock().unwrap();
        assert_eq!(state.started, 1);
        assert_eq!(state.part_requests, [1, 2, 2, 3], "part 2 was refused once and tried again");
        let assembled: Vec<u8> = state.parts.values().flatten().copied().collect();
        assert_eq!(assembled, bytes, "the parts, in order, are the file");
        assert_eq!(state.parts[&1].len(), 1_000_000);
        assert_eq!(state.parts[&3].len(), 500_000);
        let complete = state.completed_with.as_deref().unwrap();
        assert!(
            complete.find("etag-1").unwrap() < complete.find("etag-2").unwrap()
                && complete.find("etag-2").unwrap() < complete.find("etag-3").unwrap(),
            "{complete}"
        );
        assert!(state.single.is_none() && !state.aborted);
    }

    #[tokio::test]
    async fn a_file_that_is_a_whole_number_of_parts_has_no_empty_last_part() {
        let (endpoint, state) = server::start().await;
        let store = store_at(&endpoint, 1_000_000);
        let (_folder, path, bytes) = file_of(2_000_000);

        let uploaded = store.put_file(&format!("{ATTEMPT}outputs/a.bin"), &path).await.unwrap();

        assert_eq!(uploaded.sha256, sha256_of(&bytes));
        assert_eq!(state.lock().unwrap().part_requests, [1, 2]);
    }

    #[tokio::test]
    async fn a_file_no_bigger_than_a_part_is_one_request_and_an_empty_file_is_an_empty_object() {
        let (endpoint, state) = server::start().await;
        let store = store_at(&endpoint, 1_000_000);
        let (_folder, path, bytes) = file_of(1_000_000);
        let uploaded = store.put_file(&format!("{ATTEMPT}outputs/a.bin"), &path).await.unwrap();
        assert_eq!(uploaded, Uploaded { size: 1_000_000, sha256: sha256_of(&bytes) });
        assert_eq!(state.lock().unwrap().single.as_deref(), Some(&bytes[..]));
        assert_eq!(state.lock().unwrap().started, 0, "no multipart upload for one part");

        let (_folder, empty, _) = file_of(0);
        let uploaded = store.put_file(&format!("{ATTEMPT}outputs/empty"), &empty).await.unwrap();
        assert_eq!(uploaded, Uploaded { size: 0, sha256: sha256_of(b"") });
        assert_eq!(state.lock().unwrap().single.as_deref(), Some(&b""[..]));
    }

    #[tokio::test]
    async fn a_part_that_keeps_failing_fails_the_upload_and_gives_up_the_parts() {
        let (endpoint, state) = server::start().await;
        let store = store_at(&endpoint, 1_000_000);
        let (_folder, path, _) = file_of(2_500_000);
        state.lock().unwrap().refuse.insert(2, u32::MAX);

        let error = store.put_file(&format!("{ATTEMPT}outputs/a.bin"), &path).await.unwrap_err();

        assert!(matches!(error, StorageError::Failed(_)), "{error}");
        let state = state.lock().unwrap();
        assert_eq!(state.part_requests, [1, 2, 2, 2], "three tries, then it stops");
        assert!(state.aborted && state.completed_with.is_none());
    }

    #[tokio::test]
    async fn a_file_is_never_uploaded_outside_the_grant() {
        let (endpoint, state) = server::start().await;
        let store = store_at(&endpoint, 1_000_000);
        let (_folder, path, _) = file_of(10);

        let error = store.put_file(&format!("{INPUT}stolen.bin"), &path).await.unwrap_err();

        assert!(matches!(error, StorageError::OutOfScope(_)));
        assert!(state.lock().unwrap().single.is_none());
    }
}
