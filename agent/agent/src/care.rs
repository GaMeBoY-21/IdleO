//! Keeping the job runner's distribution ready for renters and light for the owner (ADR 0035).
//!
//! **Ready.** The control plane's Welcome lists the job images worth having ([`crate::host::Host::prefetch_images`]).
//! Once the laptop is listed and idle (available, not auto-paused or paused, no session running), the agent asks the
//! runner to download, one at a time, each that fits its largest GPU and isn't here yet, so a renter's first job doesn't
//! wait for several gigabytes. **Never on a metered connection** (the tray says it's waiting for an unmetered one), and
//! **never while a session runs**: a session starting, the owner coming back or the connection turning metered stops the
//! download at once, and the next one carries on from the layers already there. What's been fetched is remembered for the
//! distribution's version, so a laptop that has everything never wakes its distribution to look.
//!
//! **Light.** After [`Timing::stop_after`] with no session and no download, the agent stops our distribution
//! (`wsl --terminate IdleO-…`, ours and nobody else's), so an idle host holds no virtual-machine memory. The next
//! offer or download starts it again; [`SessionRunner::warm`](crate::runner::SessionRunner::warm) does that as an offer is
//! accepted, so the job's first line isn't waiting on the boot.

use std::collections::BTreeSet;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use idleo_release::channel::{LinkState, Prefetch, State};
use serde::{Deserialize, Serialize};
use tokio::time::Instant;

use crate::host::Host;
use crate::supervisor::Shared;

type Fut<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Whether the connection to the internet is one the owner pays for by the byte.
pub trait Metered: Send + Sync {
    /// True if it is, or if that can't be told: a download waits rather than guesses.
    fn is_metered(&self) -> bool;
}

/// Windows' own answer (`agent-windows`); everywhere else, never metered.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemMetered;

impl Metered for SystemMetered {
    #[cfg(windows)]
    fn is_metered(&self) -> bool {
        agent_windows::connection_is_metered().unwrap_or(true)
    }

    #[cfg(not(windows))]
    fn is_metered(&self) -> bool {
        false
    }
}

/// What care does to the distribution.
pub trait Distribution: Send + Sync {
    /// Whether it's running now. A stopped one is never started to look at it.
    fn awake(&self) -> bool;
    /// Downloads an image, reporting `(downloaded bytes, total bytes or 0)`.
    fn pull<'a>(
        &'a self,
        image: &'a str,
        progress: &'a (dyn Fn(u64, u64) + Send + Sync),
    ) -> Fut<'a, Result<(), String>>;
    /// Stops every download under way.
    fn cancel_pulls(&self);
    /// Stops the distribution: `wsl --terminate`, ours only.
    fn stop(&self);
}

/// Intervals.
#[derive(Debug, Clone, Copy)]
pub struct Timing {
    /// How often care looks at the host.
    pub tick: Duration,
    /// How long with no session and no download before the distribution is stopped.
    pub stop_after: Duration,
    /// How long to wait after a failed download before trying again.
    pub retry_after: Duration,
}

impl Default for Timing {
    fn default() -> Self {
        Self { tick: Duration::from_secs(5), stop_after: Duration::from_mins(5), retry_after: Duration::from_mins(2) }
    }
}

/// Everything care works with.
pub struct Care {
    /// The distribution.
    pub distribution: Arc<dyn Distribution>,
    /// Whether the connection is metered.
    pub metered: Arc<dyn Metered>,
    /// Where what's been downloaded is remembered.
    pub data_dir: PathBuf,
    /// The distribution's version, which a remembered download belongs to.
    pub distro_version: String,
    /// The largest GPU's memory in MB: an image whose templates need more isn't worth having.
    pub largest_gpu_vram_mb: i32,
    /// Intervals.
    pub timing: Timing,
}

/// What's been downloaded, for one distribution version.
#[derive(Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
struct Done {
    distro: String,
    images: BTreeSet<String>,
}

fn done_path(dir: &Path) -> PathBuf {
    dir.join("prefetched.json")
}

fn load(dir: &Path, distro: &str) -> BTreeSet<String> {
    std::fs::read_to_string(done_path(dir))
        .ok()
        .and_then(|text| serde_json::from_str::<Done>(&text).ok())
        .filter(|done| done.distro == distro)
        .map(|done| done.images)
        .unwrap_or_default()
}

fn save(dir: &Path, distro: &str, images: &BTreeSet<String>) {
    let done = Done { distro: distro.to_owned(), images: images.clone() };
    if let Ok(text) = serde_json::to_string(&done)
        && let Err(error) = std::fs::write(done_path(dir), text)
    {
        tracing::warn!(%error, "what's been downloaded couldn't be remembered");
    }
}

/// Whether the host may spend the owner's bandwidth and disk on getting ready: linked, listed, available.
fn ready_for_work(host: &Host) -> bool {
    let status = host.status();
    status.link_state == LinkState::Active
        && status.state == State::Available
        && status.runner_installed
        && status.server_reachable
}

impl Care {
    /// Runs until the process ends.
    pub async fn run(self, host: Arc<Host>, sessions: Shared) {
        let mut done = load(&self.data_dir, &self.distro_version);
        host.set_cached_images(&done);
        let mut idle_since: Option<Instant> = None;
        let mut retry_at: Option<Instant> = None;
        let mut ticks = tokio::time::interval(self.timing.tick);
        ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticks.tick().await;
            let busy = !sessions.lock().running().is_empty();
            if busy {
                idle_since = None;
                host.set_prefetch(None);
                continue;
            }
            let pending = self.pending(&host, &done);
            let mut downloading = false;
            if !pending.is_empty() && ready_for_work(&host) && retry_at.is_none_or(|at| Instant::now() >= at) {
                if self.metered.is_metered() {
                    host.set_prefetch(Some(Prefetch::WaitingForUnmetered));
                } else {
                    downloading = true;
                    let count = u32::try_from(pending.len()).unwrap_or(u32::MAX);
                    let image = pending[0].clone();
                    match self.fetch(&host, &sessions, &image, 1, count).await {
                        Fetched::Done => {
                            done.insert(image);
                            save(&self.data_dir, &self.distro_version, &done);
                            host.set_cached_images(&done);
                        }
                        Fetched::Stopped => {}
                        Fetched::Failed(why) => {
                            tracing::warn!(%image, %why, "a job image couldn't be downloaded; trying again later");
                            retry_at = Some(Instant::now() + self.timing.retry_after);
                        }
                    }
                    host.set_prefetch(None);
                }
            } else if pending.is_empty() {
                host.set_prefetch(None);
            }
            if downloading {
                idle_since = Some(Instant::now());
                continue;
            }
            // Light: nothing is running and nothing is being downloaded.
            let since = *idle_since.get_or_insert_with(Instant::now);
            if since.elapsed() >= self.timing.stop_after && self.distribution.awake() {
                tracing::info!("nothing has run for a while: stopping the distribution");
                self.distribution.stop();
                idle_since = Some(Instant::now());
            }
        }
    }

    /// The images still to download: the Welcome's, those that fit the GPU, not here yet.
    fn pending(&self, host: &Host, done: &BTreeSet<String>) -> Vec<String> {
        host.prefetch_images()
            .into_iter()
            .filter(|(image, min_vram_mb)| *min_vram_mb <= self.largest_gpu_vram_mb && !done.contains(image))
            .map(|(image, _)| image)
            .collect()
    }

    /// Downloads one image, stopping it the moment a session starts, the host stops being available or the connection turns
    /// metered.
    async fn fetch(&self, host: &Arc<Host>, sessions: &Shared, image: &str, number: u32, of: u32) -> Fetched {
        let progress_host = Arc::clone(host);
        let report = move |done_bytes: u64, total_bytes: u64| {
            progress_host.set_prefetch(Some(Prefetch::Downloading {
                done_bytes,
                total_bytes,
                image: number,
                images: of,
            }));
        };
        host.set_prefetch(Some(Prefetch::Downloading { done_bytes: 0, total_bytes: 0, image: number, images: of }));
        let pulling = self.distribution.pull(image, &report);
        tokio::pin!(pulling);
        let mut watch = tokio::time::interval(self.timing.tick.min(Duration::from_secs(2)));
        watch.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tokio::select! {
                result = &mut pulling => return match result {
                    Ok(()) => Fetched::Done,
                    // A stop for a session is the runner's own answer to being cancelled.
                    Err(_) if !sessions.lock().running().is_empty() => Fetched::Stopped,
                    Err(why) => Fetched::Failed(why),
                },
                _ = watch.tick() => {
                    if !sessions.lock().running().is_empty() || !ready_for_work(host) || self.metered.is_metered() {
                        self.distribution.cancel_pulls();
                        let _ = (&mut pulling).await;
                        return Fetched::Stopped;
                    }
                }
            }
        }
    }
}

/// How one download went.
enum Fetched {
    Done,
    Stopped,
    Failed(String),
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    use idleo_release::channel::Prefetch;

    use super::*;
    use crate::host::testing::{ready_host, set_available};

    #[derive(Default)]
    struct FakeDistribution {
        awake: AtomicBool,
        pulled: Mutex<Vec<String>>,
        stops: AtomicUsize,
        cancels: AtomicUsize,
        /// How long a pull takes.
        takes: Mutex<Duration>,
        fail: AtomicBool,
    }

    impl Distribution for FakeDistribution {
        fn awake(&self) -> bool {
            self.awake.load(Ordering::SeqCst)
        }

        fn pull<'a>(
            &'a self,
            image: &'a str,
            progress: &'a (dyn Fn(u64, u64) + Send + Sync),
        ) -> Fut<'a, Result<(), String>> {
            Box::pin(async move {
                self.awake.store(true, Ordering::SeqCst);
                progress(1_000_000, 0);
                let takes = *self.takes.lock().unwrap();
                tokio::time::sleep(takes).await;
                if self.fail.load(Ordering::SeqCst) {
                    return Err("the registry isn't reachable".to_owned());
                }
                self.pulled.lock().unwrap().push(image.to_owned());
                Ok(())
            })
        }

        fn cancel_pulls(&self) {
            self.cancels.fetch_add(1, Ordering::SeqCst);
        }

        fn stop(&self) {
            self.stops.fetch_add(1, Ordering::SeqCst);
            self.awake.store(false, Ordering::SeqCst);
        }
    }

    struct Connection(AtomicBool);

    impl Metered for Connection {
        fn is_metered(&self) -> bool {
            self.0.load(Ordering::SeqCst)
        }
    }

    fn image(n: u8) -> String {
        format!("registry.example/job{n}@sha256:{}", format!("{n:02x}").repeat(32))
    }

    struct Rig {
        distribution: Arc<FakeDistribution>,
        connection: Arc<Connection>,
        host: Arc<Host>,
        sessions: Shared,
        dir: tempfile::TempDir,
    }

    fn rig(vram_mb: i32, images: &[(u8, i32)]) -> (Rig, Care) {
        let distribution = Arc::new(FakeDistribution::default());
        *distribution.takes.lock().unwrap() = Duration::from_secs(20);
        let connection = Arc::new(Connection(AtomicBool::new(false)));
        let host = ready_host();
        host.set_prefetch_images(images.iter().map(|(n, vram)| (image(*n), *vram)).collect());
        let (sessions, _reports) = crate::supervisor::testing::idle_supervisor();
        let dir = tempfile::tempdir().unwrap();
        let care = Care {
            distribution: Arc::clone(&distribution) as Arc<dyn Distribution>,
            metered: Arc::clone(&connection) as Arc<dyn Metered>,
            data_dir: dir.path().to_path_buf(),
            distro_version: "0.1.0".to_owned(),
            largest_gpu_vram_mb: vram_mb,
            timing: Timing {
                tick: Duration::from_secs(1),
                stop_after: Duration::from_secs(300),
                retry_after: Duration::from_secs(60),
            },
        };
        (Rig { distribution, connection, host, sessions, dir }, care)
    }

    #[tokio::test(start_paused = true)]
    async fn images_that_fit_are_downloaded_one_at_a_time_and_remembered() {
        let (rig, care) = rig(12_000, &[(1, 8_000), (2, 24_000), (3, 4_000)]);
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));

        tokio::time::sleep(Duration::from_secs(70)).await;

        assert_eq!(
            *rig.distribution.pulled.lock().unwrap(),
            [image(1), image(3)],
            "the 24 GB one doesn't fit a 12 GB GPU"
        );
        assert_eq!(rig.host.status().prefetch, None, "nothing left to download");
        let remembered: Done =
            serde_json::from_str(&std::fs::read_to_string(done_path(rig.dir.path())).unwrap()).unwrap();
        assert_eq!(remembered.images, BTreeSet::from([image(1), image(3)]));
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn what_is_downloaded_is_what_the_heartbeat_says_is_cached() {
        let (rig, care) = rig(12_000, &[(1, 8_000), (2, 24_000)]);
        let digest = |n: u8| format!("sha256:{}", format!("{n:02x}").repeat(32));
        assert_eq!(rig.host.cached_images(), Vec::<String>::new());
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));

        tokio::time::sleep(Duration::from_secs(70)).await;

        assert_eq!(rig.host.cached_images(), [digest(1)], "only what's here, not the image that doesn't fit");
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn images_remembered_from_before_are_cached_from_the_start() {
        let (rig, care) = rig(12_000, &[(1, 8_000)]);
        save(rig.dir.path(), "0.1.0", &BTreeSet::from([image(1)]));
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));

        tokio::time::sleep(Duration::from_secs(2)).await;

        assert_eq!(rig.host.cached_images(), [format!("sha256:{}", "01".repeat(32))]);
        assert!(rig.distribution.pulled.lock().unwrap().is_empty(), "nothing is downloaded twice");
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn the_tray_sees_the_download_while_it_runs() {
        let (rig, care) = rig(12_000, &[(1, 8_000)]);
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));

        tokio::time::sleep(Duration::from_secs(5)).await;

        assert_eq!(
            rig.host.status().prefetch,
            Some(Prefetch::Downloading { done_bytes: 1_000_000, total_bytes: 0, image: 1, images: 1 })
        );
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn nothing_is_downloaded_on_a_metered_connection_and_it_carries_on_when_it_isnt() {
        let (rig, care) = rig(12_000, &[(1, 8_000)]);
        rig.connection.0.store(true, Ordering::SeqCst);
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));

        tokio::time::sleep(Duration::from_secs(30)).await;
        assert!(rig.distribution.pulled.lock().unwrap().is_empty());
        assert_eq!(rig.host.status().prefetch, Some(Prefetch::WaitingForUnmetered));

        rig.connection.0.store(false, Ordering::SeqCst);
        tokio::time::sleep(Duration::from_secs(40)).await;
        assert_eq!(*rig.distribution.pulled.lock().unwrap(), [image(1)]);
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn the_connection_turning_metered_stops_a_download_under_way() {
        let (rig, care) = rig(12_000, &[(1, 8_000)]);
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));
        tokio::time::sleep(Duration::from_secs(5)).await;

        rig.connection.0.store(true, Ordering::SeqCst);
        tokio::time::sleep(Duration::from_secs(5)).await;

        assert!(rig.distribution.cancels.load(Ordering::SeqCst) >= 1, "the runner was told to stop downloading");
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn nothing_is_downloaded_while_the_host_is_paused_and_a_failed_download_waits_before_trying_again() {
        let (rig, care) = rig(12_000, &[(1, 8_000)]);
        set_available(&rig.host, false);
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));
        tokio::time::sleep(Duration::from_secs(30)).await;
        assert!(rig.distribution.pulled.lock().unwrap().is_empty() && !rig.distribution.awake());

        set_available(&rig.host, true);
        rig.distribution.fail.store(true, Ordering::SeqCst);
        *rig.distribution.takes.lock().unwrap() = Duration::from_secs(1);
        tokio::time::sleep(Duration::from_secs(10)).await;
        rig.distribution.fail.store(false, Ordering::SeqCst);
        tokio::time::sleep(Duration::from_secs(20)).await;
        assert!(rig.distribution.pulled.lock().unwrap().is_empty(), "it waits a minute after a failure");
        tokio::time::sleep(Duration::from_secs(60)).await;
        assert_eq!(*rig.distribution.pulled.lock().unwrap(), [image(1)]);
        running.abort();
    }

    #[tokio::test(start_paused = true)]
    async fn an_idle_distribution_is_stopped_after_five_minutes_and_a_laptop_with_everything_never_starts_it() {
        let (rig, care) = rig(12_000, &[(1, 8_000)]);
        let mut done = BTreeSet::new();
        done.insert(image(1));
        save(rig.dir.path(), "0.1.0", &done);
        rig.distribution.awake.store(true, Ordering::SeqCst);
        let (host, sessions) = (Arc::clone(&rig.host), rig.sessions.clone());
        let running = tokio::spawn(care.run(host, sessions));

        tokio::time::sleep(Duration::from_secs(290)).await;
        assert_eq!(rig.distribution.stops.load(Ordering::SeqCst), 0, "not yet");
        tokio::time::sleep(Duration::from_secs(20)).await;
        assert_eq!(rig.distribution.stops.load(Ordering::SeqCst), 1);
        tokio::time::sleep(Duration::from_secs(600)).await;
        assert!(!rig.distribution.awake(), "and it isn't started again to look");
        assert!(rig.distribution.pulled.lock().unwrap().is_empty(), "everything was here already");
        running.abort();
    }

    #[test]
    fn what_was_downloaded_belongs_to_one_distribution_version() {
        let dir = tempfile::tempdir().unwrap();
        save(dir.path(), "0.1.0", &BTreeSet::from(["a@sha256:1".to_owned()]));
        assert_eq!(load(dir.path(), "0.1.0").len(), 1);
        assert!(load(dir.path(), "0.2.0").is_empty(), "a new distribution has none of them");
        assert!(load(&dir.path().join("missing"), "0.1.0").is_empty());
    }
}
