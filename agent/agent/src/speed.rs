//! The self-test's storage speed check (ADR 0034): the running agent asks the control plane for a grant covering only
//! its own speed-check object, writes 32 MB there and reads it back, timing both. The next check overwrites it.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, Instant};

use idleo_release::channel::LinkState;

use crate::fair_use::UploadCap;
use crate::host::Host;
use crate::lease_clock::LeaseClock;
use crate::self_test::{SPEED_TEST_BYTES, Speed, SpeedCheck, kbit_per_second, mbit_per_second};
use crate::storage::{Grant, ObjectStore, S3Store};

/// How long to wait for the control plane's grant.
const GRANT_WAITS: Duration = Duration::from_secs(15);

/// The check, through the running agent's stream.
pub struct StorageSpeed {
    host: Arc<Host>,
    http: reqwest::Client,
    clock: Arc<dyn LeaseClock>,
    /// What jobs may upload: the upload measured here is what it's half of (ADR 0038).
    upload_cap: Arc<UploadCap>,
}

impl StorageSpeed {
    /// The check for this agent.
    #[must_use]
    pub fn new(host: Arc<Host>, http: reqwest::Client, clock: Arc<dyn LeaseClock>, upload_cap: Arc<UploadCap>) -> Self {
        Self { host, http, clock, upload_cap }
    }
}

impl SpeedCheck for StorageSpeed {
    fn measure(&self) -> Pin<Box<dyn Future<Output = Result<Speed, String>> + Send + '_>> {
        Box::pin(async move {
            if !matches!(self.host.link_state(), LinkState::Active | LinkState::PendingApproval) {
                return Err("not linked to IdleO yet".to_owned());
            }
            let grant = tokio::time::timeout(GRANT_WAITS, self.host.request_speed_grant())
                .await
                .map_err(|_| "IdleO didn't send a storage grant in time".to_owned())?
                .map_err(|_| "the request for a storage grant was dropped".to_owned())?;
            let message = grant.grant.ok_or("the grant was empty")?;
            let store = S3Store::new(
                Grant::from_message(&message, self.clock.now())?,
                self.http.clone(),
                Arc::clone(&self.clock),
            )?;
            let body = vec![0u8; SPEED_TEST_BYTES];
            let started = Instant::now();
            store.put(&grant.key, body).await.map_err(|e| format!("upload failed: {e}"))?;
            let up = started.elapsed();
            let started = Instant::now();
            let back = store.get(&grant.key).await.map_err(|e| format!("download failed: {e}"))?;
            let down = started.elapsed();
            if back.len() != SPEED_TEST_BYTES {
                return Err(format!("{} bytes came back, not {SPEED_TEST_BYTES}", back.len()));
            }
            let up_kbps = kbit_per_second(SPEED_TEST_BYTES, up);
            self.upload_cap.measured(up_kbps);
            Ok(Speed {
                detail: format!(
                    "up {} Mbit/s, down {} Mbit/s",
                    mbit_per_second(SPEED_TEST_BYTES, up),
                    mbit_per_second(SPEED_TEST_BYTES, down)
                ),
                up_kbps,
            })
        })
    }
}
