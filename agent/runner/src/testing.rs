//! A machine that isn't one, for testing the lifecycle of an attempt: "containers" are controlled by the test, scratch
//! disks are folders in a temporary directory, and storage is in memory.

use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use jobcore::lease_clock::LeaseClock;
use jobcore::protocol::{Check, Grants, HelloAnswer};
use jobcore::storage::ObjectStore;
use jobcore::storage::testing::{MemoryStore, Objects};
use tokio::sync::{mpsc, watch};

use crate::abuse::{Blocklist, ProcessSeen};
use crate::container::Plan;
use crate::dns::PoolLookup;
use crate::machine::{Container, Exit, Fut, Machine, Output, Progress, Scratch, StartError, Stream, Traffic};
use crate::storage::{Opened, Storage};

/// A clock that follows tokio's, so a test with paused time moves both together.
pub struct TokioClock(tokio::time::Instant);

impl TokioClock {
    pub fn new() -> Arc<Self> {
        Arc::new(Self(tokio::time::Instant::now()))
    }
}

impl LeaseClock for TokioClock {
    fn now(&self) -> Duration {
        self.0.elapsed()
    }
}

/// What a fake container does when it's signalled.
#[derive(Clone, Copy)]
pub enum OnTerm {
    /// Exits with 143 after this long, as a job that saves a checkpoint first.
    ExitAfter(Duration),
    /// Carries on, as a job that has no handler for SIGTERM.
    Ignore,
}

/// A job the test controls.
pub struct FakeContainer {
    pub plan: Plan,
    pub signals: Mutex<Vec<&'static str>>,
    pub removed: AtomicBool,
    pub oom: AtomicBool,
    exit: watch::Sender<Option<Exit>>,
    output: Mutex<Option<Output>>,
    say: mpsc::Sender<(Stream, Vec<u8>)>,
    on_term: Mutex<OnTerm>,
    /// The processes the container shows (ADR 0038), for the abuse watch.
    processes: Mutex<Vec<ProcessSeen>>,
    /// The mining-pool lookups its DNS refuses: send one here, as the forwarder would.
    refuse: mpsc::UnboundedSender<PoolLookup>,
    refused: Mutex<Option<mpsc::UnboundedReceiver<PoolLookup>>>,
    /// What its network has carried and the fair-use limits have dropped, in all.
    traffic: Mutex<Traffic>,
    /// The executables' hashes the container can work out when asked, by pid.
    hashes: Mutex<std::collections::HashMap<u32, String>>,
}

impl FakeContainer {
    /// A process appears in the container.
    pub fn show_process(&self, pid: u32, comm: &str, exe_name: &str, sha256: Option<&str>) {
        self.processes.lock().unwrap().push(ProcessSeen {
            pid,
            started: u64::from(pid),
            comm: comm.to_owned(),
            exe_name: exe_name.to_owned(),
            sha256: sha256.map(str::to_owned),
        });
    }

    /// What the container can say an executable's SHA-256 is when asked for it, as `hash_of` does for a process whose name is
    /// listed.
    pub fn can_hash(&self, pid: u32, sha256: &str) {
        self.hashes.lock().unwrap().insert(pid, sha256.to_owned());
    }

    /// The job looks up a mining pool's domain and the forwarder refuses it.
    pub fn refuse_lookup(&self, domain: &str) {
        let _ = self.refuse.send(PoolLookup { domain: domain.to_owned() });
    }

    /// The network has carried this much in all, and the limits have dropped this many new connections.
    pub fn carry(&self, out_bytes: u64, in_bytes: u64, dropped_connections: u64) {
        *self.traffic.lock().unwrap() = Traffic {
            out_bytes,
            in_bytes,
            drops: crate::fair::Drops { connections: dropped_connections, ..crate::fair::Drops::default() },
        };
    }

    /// The job exits with `code`.
    pub fn finish(&self, code: i32) {
        self.exit.send_replace(Some(Exit { code }));
    }

    /// The job prints.
    pub async fn say(&self, stream: Stream, text: &str) {
        let _ = self.say.send((stream, text.as_bytes().to_vec())).await;
    }

    pub fn signals(&self) -> Vec<&'static str> {
        self.signals.lock().unwrap().clone()
    }
}

impl Container for FakeContainer {
    fn processes(&self, _with_hashes: bool) -> Fut<'_, Vec<ProcessSeen>> {
        let processes = self.processes.lock().unwrap().clone();
        Box::pin(async move { processes })
    }

    fn hash_of(&self, pid: u32) -> Fut<'_, Option<String>> {
        let hash = self.hashes.lock().unwrap().get(&pid).cloned();
        Box::pin(async move { hash })
    }

    fn take_lookups(&self) -> Option<mpsc::UnboundedReceiver<PoolLookup>> {
        self.refused.lock().unwrap().take()
    }

    fn traffic(&self) -> Fut<'_, Traffic> {
        let traffic = *self.traffic.lock().unwrap();
        Box::pin(async move { traffic })
    }

    fn take_output(&self) -> Option<Output> {
        self.output.lock().unwrap().take()
    }

    fn wait(&self) -> Fut<'_, Exit> {
        let mut exit = self.exit.subscribe();
        Box::pin(async move {
            loop {
                if let Some(exit) = *exit.borrow_and_update() {
                    return exit;
                }
                exit.changed().await.expect("the container lives as long as its test");
            }
        })
    }

    fn signal(&self, signal: &'static str) -> Fut<'_, ()> {
        self.signals.lock().unwrap().push(signal);
        let behaviour = *self.on_term.lock().unwrap();
        let exit = self.exit.clone();
        Box::pin(async move {
            match (signal, behaviour) {
                ("KILL", _) => {
                    exit.send_replace(Some(Exit { code: 137 }));
                }
                ("TERM", OnTerm::ExitAfter(after)) => {
                    tokio::spawn(async move {
                        tokio::time::sleep(after).await;
                        exit.send_if_modified(|current| {
                            let first = current.is_none();
                            if first {
                                *current = Some(Exit { code: 143 });
                            }
                            first
                        });
                    });
                }
                _ => {}
            }
        })
    }

    fn oom_killed(&self) -> Fut<'_, bool> {
        let oom = self.oom.load(Ordering::SeqCst);
        Box::pin(async move { oom })
    }

    fn remove(&self) -> Fut<'_, ()> {
        self.removed.store(true, Ordering::SeqCst);
        self.exit.send_if_modified(|current| {
            let first = current.is_none();
            if first {
                *current = Some(Exit { code: 137 });
            }
            first
        });
        Box::pin(async {})
    }
}

/// Called with each plan a container is started from.
pub type StartHook = Box<dyn Fn(&Plan) + Send>;

/// The distribution, as a test sets it up.
pub struct FakeMachine {
    pub sandbox: Mutex<Result<(), String>>,
    pub image: Mutex<Result<(), String>>,
    /// How long a pull takes.
    pub pull_takes: Mutex<Duration>,
    /// The logins images were pulled with, and the images removed.
    pub pulled_with: Mutex<Vec<(String, Option<String>)>>,
    pub images_removed: Mutex<Vec<String>>,
    /// What starting a container does: nothing wrong, or this failure.
    pub start: Mutex<Option<StartError>>,
    /// The list of software and pools to look for; the one built into the runner unless a test sets its own.
    pub blocklist: Mutex<Option<Arc<Blocklist>>>,
    pub scratch_free: AtomicU64,
    pub development: AtomicBool,
    /// What `leftovers` says is left from finished sessions.
    pub leftover: Mutex<Vec<String>>,
    pub containers: Mutex<Vec<Arc<FakeContainer>>>,
    pub scratches_made: Mutex<Vec<Scratch>>,
    pub scratches_removed: Mutex<Vec<String>>,
    pub on_start: Mutex<Option<StartHook>>,
    pub on_term: Mutex<OnTerm>,
    folder: tempfile::TempDir,
}

impl FakeMachine {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            sandbox: Mutex::new(Ok(())),
            image: Mutex::new(Ok(())),
            pull_takes: Mutex::new(Duration::ZERO),
            pulled_with: Mutex::new(Vec::new()),
            images_removed: Mutex::new(Vec::new()),
            start: Mutex::new(None),
            blocklist: Mutex::new(None),
            scratch_free: AtomicU64::new(10 * 1024 * 1024 * 1024),
            development: AtomicBool::new(false),
            leftover: Mutex::new(Vec::new()),
            containers: Mutex::new(Vec::new()),
            scratches_made: Mutex::new(Vec::new()),
            scratches_removed: Mutex::new(Vec::new()),
            on_start: Mutex::new(None),
            on_term: Mutex::new(OnTerm::ExitAfter(Duration::from_millis(100))),
            folder: tempfile::tempdir().unwrap(),
        })
    }

    /// The first (and usually only) container started, once it has been.
    pub async fn container(&self) -> Arc<FakeContainer> {
        for _ in 0..2000 {
            if let Some(container) = self.containers.lock().unwrap().first() {
                return Arc::clone(container);
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("no container was started");
    }

    pub fn scratch_of(&self, key: &str) -> PathBuf {
        self.folder.path().join(key)
    }
}

impl Machine for FakeMachine {
    fn hello(&self) -> Fut<'_, HelloAnswer> {
        Box::pin(async move {
            HelloAnswer {
                runner_version: "test".to_owned(),
                distro_version: "test".to_owned(),
                gpu: false,
                rules_loaded: self.sandbox.lock().unwrap().is_ok(),
                scratch_free_bytes: self.scratch_free.load(Ordering::SeqCst),
                development: self.development.load(Ordering::SeqCst),
                blocklist_version: self.abuse_blocklist().version().to_owned(),
            }
        })
    }

    fn check_sandbox(&self) -> Fut<'_, Result<(), String>> {
        let result = self.sandbox.lock().unwrap().clone();
        Box::pin(async move { result })
    }

    fn ensure_image<'a>(
        &'a self,
        image: &'a str,
        progress: Progress,
        login: Option<&'a jobcore::protocol::RegistryLogin>,
    ) -> Fut<'a, Result<(), String>> {
        let result = self.image.lock().unwrap().clone();
        let takes = *self.pull_takes.lock().unwrap();
        self.pulled_with.lock().unwrap().push((image.to_owned(), login.map(|l| format!("{}:{}", l.username, l.token))));
        Box::pin(async move {
            if !takes.is_zero() {
                progress(1, 0);
                tokio::time::sleep(takes).await;
            }
            result
        })
    }

    fn remove_image<'a>(&'a self, image: &'a str) -> Fut<'a, ()> {
        self.images_removed.lock().unwrap().push(image.to_owned());
        Box::pin(async {})
    }

    fn make_scratch<'a>(&'a self, key: &'a str, _scratch_mb: u32) -> Fut<'a, Result<Scratch, String>> {
        Box::pin(async move {
            let root = self.scratch_of(key);
            let scratch = Scratch {
                key: key.to_owned(),
                work: root.join("work"),
                input: root.join("input"),
                resume: root.join("resume"),
                root,
                mapper: None,
            };
            for folder in [&scratch.work, &scratch.input, &scratch.resume] {
                std::fs::create_dir_all(folder).map_err(|e| e.to_string())?;
            }
            self.scratches_made.lock().unwrap().push(scratch.clone());
            Ok(scratch)
        })
    }

    fn scratch_free(&self, _scratch: &Scratch) -> u64 {
        self.scratch_free.load(Ordering::SeqCst)
    }

    fn remove_scratch<'a>(&'a self, scratch: &'a Scratch) -> Fut<'a, ()> {
        Box::pin(async move {
            let _ = std::fs::remove_dir_all(&scratch.root);
            self.scratches_removed.lock().unwrap().push(scratch.key.clone());
        })
    }

    fn start_container(&self, plan: Plan) -> Fut<'_, Result<Arc<dyn Container>, StartError>> {
        Box::pin(async move {
            if let Some(failure) = self.start.lock().unwrap().clone() {
                return Err(failure);
            }
            if let Some(hook) = self.on_start.lock().unwrap().as_ref() {
                hook(&plan);
            }
            let (say, output) = mpsc::channel(64);
            let (refuse, refused) = mpsc::unbounded_channel();
            let container = Arc::new(FakeContainer {
                processes: Mutex::new(Vec::new()),
                refuse,
                refused: Mutex::new(Some(refused)),
                traffic: Mutex::new(Traffic::default()),
                hashes: Mutex::new(std::collections::HashMap::new()),
                plan,
                signals: Mutex::new(Vec::new()),
                removed: AtomicBool::new(false),
                oom: AtomicBool::new(false),
                exit: watch::channel(None).0,
                output: Mutex::new(Some(output)),
                say,
                on_term: Mutex::new(*self.on_term.lock().unwrap()),
            });
            self.containers.lock().unwrap().push(Arc::clone(&container));
            Ok(container as Arc<dyn Container>)
        })
    }

    fn abuse_blocklist(&self) -> Arc<Blocklist> {
        self.blocklist.lock().unwrap().clone().unwrap_or_else(|| Arc::new(Blocklist::built_in()))
    }

    fn set_blocklist(&self, file: &str, signature: &[u8]) -> Result<String, String> {
        // The fake machine has no key; it refuses an empty signature as the real one refuses an unsigned list.
        if signature.is_empty() {
            return Err("the blocklist has no valid signature".to_owned());
        }
        let incoming = Blocklist::parse(file)?;
        if incoming.version() < self.abuse_blocklist().version() {
            return Err("older than the one in use".to_owned());
        }
        let version = incoming.version().to_owned();
        *self.blocklist.lock().unwrap() = Some(Arc::new(incoming));
        Ok(version)
    }

    fn self_check<'a>(&'a self, _cuda_image: Option<&'a str>, _quick: bool) -> Fut<'a, Vec<Check>> {
        Box::pin(async { Vec::new() })
    }

    fn leftovers(&self) -> Fut<'_, Vec<String>> {
        let left = self.leftover.lock().unwrap().clone();
        Box::pin(async move { left })
    }

    fn development(&self) -> bool {
        self.development.load(Ordering::SeqCst)
    }

    fn clean_up_leftovers(&self) -> Fut<'_, ()> {
        Box::pin(async {})
    }
}

/// Storage in memory: every grant's prefixes over one set of objects.
pub struct MemoryStorage {
    pub objects: Objects,
    /// How many times fresh grants have replaced the first.
    pub refreshed: Arc<AtomicU64>,
}

impl MemoryStorage {
    pub fn new() -> Arc<Self> {
        Arc::new(Self { objects: Objects::default(), refreshed: Arc::default() })
    }

    pub fn put(&self, key: &str, body: &[u8]) {
        self.objects.lock().unwrap().insert(key.to_owned(), body.to_vec());
    }

    pub fn keys(&self) -> Vec<String> {
        self.objects.lock().unwrap().keys().cloned().collect()
    }

    pub fn get(&self, key: &str) -> Option<Vec<u8>> {
        self.objects.lock().unwrap().get(key).cloned()
    }

    /// Everything under `prefix`, by key.
    pub fn under(&self, prefix: &str) -> BTreeMap<String, Vec<u8>> {
        self.objects
            .lock()
            .unwrap()
            .iter()
            .filter(|(k, _)| k.starts_with(prefix))
            .map(|(k, v)| (k.clone(), v.clone()))
            .collect()
    }
}

impl Storage for MemoryStorage {
    fn open(&self, grants: &Grants) -> Result<Opened, String> {
        let attempt = MemoryStore::new(
            &self.objects,
            &grants.attempt.prefixes.iter().map(String::as_str).collect::<Vec<_>>(),
            true,
        );
        let read = grants.read.as_ref().map(|wire| {
            MemoryStore::new(&self.objects, &wire.prefixes.iter().map(String::as_str).collect::<Vec<_>>(), false)
        });
        let refreshed = Arc::clone(&self.refreshed);
        Ok(Opened {
            attempt: Arc::new(attempt) as Arc<dyn ObjectStore>,
            read: read.map(|store| Arc::new(store) as Arc<dyn ObjectStore>),
            refresh: Box::new(move |_| {
                refreshed.fetch_add(1, Ordering::SeqCst);
                Ok(())
            }),
        })
    }
}
