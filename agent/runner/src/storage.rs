//! A session's storage in the runner: the two grants of a [`Start`](jobcore::protocol::Start) made into stores, and the
//! way fresh grants replace them while the job runs. The grants live here, in memory, and nowhere else: never in the
//! container, its environment, a file or a log.

use std::sync::Arc;

use jobcore::lease_clock::LeaseClock;
use jobcore::protocol::Grants;
use jobcore::storage::{Grant, HttpClient, ObjectStore, S3Store};

/// Swaps fresh grants in for the same prefixes and permissions.
pub type Refresher = Box<dyn Fn(&Grants) -> Result<(), String> + Send + Sync>;

/// An attempt's stores, and what replaces their grants.
pub struct Opened {
    /// Read and write on the attempt's own folder.
    pub attempt: Arc<dyn ObjectStore>,
    /// Read-only on the inputs and the resume checkpoint.
    pub read: Option<Arc<dyn ObjectStore>>,
    /// Swaps in fresh grants for the same prefixes and permissions.
    pub refresh: Refresher,
}

/// Makes an attempt's stores from its grants.
pub trait Storage: Send + Sync {
    /// Checks the grants and opens the stores.
    ///
    /// # Errors
    /// Why the grants can't be used. Never a secret.
    fn open(&self, grants: &Grants) -> Result<Opened, String>;
}

/// S3-compatible storage (`MinIO` locally, Cloudflare R2 in production) through the grants.
pub struct S3Storage {
    http: HttpClient,
    clock: Arc<dyn LeaseClock>,
}

impl S3Storage {
    /// Storage over `http`, counting the grants' lifetimes on `clock`.
    #[must_use]
    pub fn new(http: HttpClient, clock: Arc<dyn LeaseClock>) -> Self {
        Self { http, clock }
    }
}

impl Storage for S3Storage {
    fn open(&self, grants: &Grants) -> Result<Opened, String> {
        if !grants.attempt.writable {
            return Err("the attempt's grant can't write".to_owned());
        }
        if grants.read.as_ref().is_some_and(|read| read.writable) {
            return Err("the read grant can write".to_owned());
        }
        let now = self.clock.now();
        let attempt = Arc::new(S3Store::new(
            Grant::from_message(&grants.attempt.to_message(), now)?,
            self.http.clone(),
            Arc::clone(&self.clock),
        )?);
        let read = grants
            .read
            .as_ref()
            .map(|wire| {
                Ok::<_, String>(Arc::new(S3Store::new(
                    Grant::from_message(&wire.to_message(), now)?,
                    self.http.clone(),
                    Arc::clone(&self.clock),
                )?))
            })
            .transpose()?;
        let (attempt_store, read_store, clock) = (Arc::clone(&attempt), read.clone(), Arc::clone(&self.clock));
        Ok(Opened {
            attempt: attempt as Arc<dyn ObjectStore>,
            read: read.map(|store| store as Arc<dyn ObjectStore>),
            refresh: Box::new(move |fresh: &Grants| {
                let now = clock.now();
                attempt_store.replace(Grant::from_message(&fresh.attempt.to_message(), now)?)?;
                if let (Some(store), Some(wire)) = (&read_store, &fresh.read) {
                    store.replace(Grant::from_message(&wire.to_message(), now)?)?;
                }
                Ok(())
            }),
        })
    }
}
