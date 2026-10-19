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

use crate::container::Plan;
use crate::machine::{Container, Exit, Fut, Machine, Output, Progress, Scratch, Stream};
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
}

impl FakeContainer {
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
    pub scratch_free: AtomicU64,
    pub development: AtomicBool,
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
            scratch_free: AtomicU64::new(10 * 1024 * 1024 * 1024),
            development: AtomicBool::new(false),
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
            }
        })
    }

    fn check_sandbox(&self) -> Fut<'_, Result<(), String>> {
        let result = self.sandbox.lock().unwrap().clone();
        Box::pin(async move { result })
    }

    fn ensure_image<'a>(&'a self, _image: &'a str, _progress: Progress) -> Fut<'a, Result<(), String>> {
        let result = self.image.lock().unwrap().clone();
        Box::pin(async move { result })
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

    fn start_container(&self, plan: Plan) -> Fut<'_, Result<Arc<dyn Container>, String>> {
        Box::pin(async move {
            if let Some(hook) = self.on_start.lock().unwrap().as_ref() {
                hook(&plan);
            }
            let (say, output) = mpsc::channel(64);
            let container = Arc::new(FakeContainer {
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

    fn self_check<'a>(&'a self, _cuda_image: Option<&'a str>) -> Fut<'a, Vec<Check>> {
        Box::pin(async { Vec::new() })
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
