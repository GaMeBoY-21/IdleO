//! The real machine (ADR 0035): containerd driven through `nerdctl`, scratch disks from `truncate`, `mkfs.ext4` and `mount`,
//! the sandbox's rules in `nft`. Everything here shells out to a pinned tool of the distribution; nothing is reached over a
//! network except the registry an image is pulled from.
//!
//! **Networks.** Each session gets its own bridge, taken from a pool of 32: slot `N` is the bridge `idlN` on
//! `172.30.N.0/24`, described by a CNI config written at start-up (`idleo-slot-N`), so no network is made or deleted per
//! session and a crash leaves nothing to clean up but the slot. A slot is held from a container's start until it's removed.
//! **Scratch disks.** `<state>/scratch/<key>.img`, a sparse ext4 image of `scratch_mb`, loop-mounted at `<state>/mnt/<key>`
//! with `nosuid,nodev`; deleted at the end. A job can't fill the machine's disk, only its own.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use jobcore::protocol::{Check, HelloAnswer};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::process::Command;
use tokio::sync::{mpsc, watch};

use crate::container::{self, NAMESPACE, Plan};
use crate::machine::{Container, Exit, Fut, Machine, Output, Progress, Scratch, Stream};
use crate::sandbox;

mod selfcheck;

/// How many session networks there are: the number of sessions that can run at once on one machine.
pub const SLOTS: u8 = 32;
/// Marks a development distribution: a plain-HTTP registry and a job without a GPU are allowed. Only root can make it, and
/// the release distribution doesn't have it.
pub const DEVELOPMENT_MARKER: &str = "/etc/idleo/development";
/// The development registry's logical name, which images in `templates/development.yaml` use. A development distribution maps
/// it to the real address named by the `registry=` line of its marker, so one catalog works on a Mac and on a laptop on the
/// LAN. A release distribution has no marker, so never resolves it.
pub const DEV_REGISTRY: &str = "registry.idleo.test:5000";
/// The image baked into the distribution that the self-test runs.
pub const SELF_CHECK_IMAGE: &str = "docker.io/idleo/gpu-check:local";
/// The job's user, which owns `/work`.
const JOB_UID: u32 = 10001;
/// A command that takes longer than this fails.
const COMMAND_TIMEOUT: Duration = Duration::from_secs(120);
/// Pulling an image has longer: a few GB over a home connection.
const PULL_TIMEOUT: Duration = Duration::from_hours(3);

/// Paths the runner uses, so tests can point them elsewhere.
#[derive(Debug, Clone)]
pub struct Paths {
    /// Scratch images and their mount points.
    pub state: PathBuf,
    /// CNI network configs.
    pub cni_config: PathBuf,
    /// containerd's content store, whose growth is a pull's progress.
    pub content: PathBuf,
}

impl Default for Paths {
    fn default() -> Self {
        Self {
            state: PathBuf::from("/var/lib/idleo"),
            cni_config: PathBuf::from("/etc/cni/net.d"),
            content: PathBuf::from("/var/lib/containerd/io.containerd.content.v1.content"),
        }
    }
}

/// The distribution this runner is in.
pub struct Nerdctl {
    paths: Paths,
    slots: Arc<Mutex<BTreeSet<u8>>>,
    /// The containers containerd has said the kernel killed for memory (its `/tasks/oom` events), by id: see
    /// [`Nerdctl::watch_oom_events`].
    ooms: Arc<Mutex<std::collections::HashSet<String>>>,
}

struct Finished {
    ok: bool,
    code: i32,
    stdout: String,
    stderr: String,
}

impl Finished {
    /// Why it failed, in a line: what it printed to stderr, or its exit code.
    fn why(&self, what: &str) -> String {
        let text = if self.stderr.trim().is_empty() { self.stdout.trim() } else { self.stderr.trim() };
        // nerdctl and containerd log `level=fatal msg="..."`: the message is the reason, the rest is noise.
        let fatal = text
            .lines()
            .rev()
            .find_map(|line| line.split_once("level=fatal msg=").map(|(_, message)| message.trim_matches('"')));
        let text: String = match fatal {
            Some(message) => message.replace("\\\"", "\"").replace("\\\\", "\\"),
            None => text.lines().rev().take(3).collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join("; "),
        };
        let text: String = text.chars().take(700).collect();
        if text.is_empty() {
            format!("{what} failed with exit code {}", self.code)
        } else {
            format!("{what} failed: {text}")
        }
    }
}

async fn run(program: &str, args: &[&str], stdin: Option<&str>, limit: Duration) -> Result<Finished, String> {
    let mut command = Command::new(program);
    command
        .args(args)
        .env("PATH", "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin")
        .env("CNI_PATH", "/opt/cni/bin")
        .stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = command.spawn().map_err(|e| format!("{program} couldn't be started: {e}"))?;
    if let (Some(text), Some(mut pipe)) = (stdin, child.stdin.take()) {
        pipe.write_all(text.as_bytes()).await.map_err(|e| format!("{program} didn't take its input: {e}"))?;
    }
    let result = tokio::time::timeout(limit, child.wait_with_output())
        .await
        .map_err(|_| format!("{program} took longer than {} s", limit.as_secs()))?
        .map_err(|e| format!("{program} failed: {e}"))?;
    Ok(Finished {
        ok: result.status.success(),
        code: result.status.code().unwrap_or(-1),
        stdout: String::from_utf8_lossy(&result.stdout).into_owned(),
        stderr: String::from_utf8_lossy(&result.stderr).into_owned(),
    })
}

async fn nerdctl(args: &[&str]) -> Result<Finished, String> {
    let mut all = vec!["--namespace", NAMESPACE];
    all.extend_from_slice(args);
    run("nerdctl", &all, None, COMMAND_TIMEOUT).await
}

impl Nerdctl {
    /// A runner's machine over `paths`.
    #[must_use]
    pub fn new(paths: Paths) -> Self {
        Self { paths, slots: Arc::default(), ooms: Arc::default() }
    }

    /// Whether this is a development distribution.
    #[must_use]
    pub fn development() -> bool {
        Path::new(DEVELOPMENT_MARKER).exists()
    }

    /// Points the development registry's logical name at its real address, in containerd's registry config (`hosts.toml`):
    /// plain HTTP, and only in a development distribution.
    fn map_development_registry(image: &str) -> Result<(), String> {
        if !Self::development() || !image.starts_with(&format!("{DEV_REGISTRY}/")) {
            return Ok(());
        }
        let marker = std::fs::read_to_string(DEVELOPMENT_MARKER).unwrap_or_default();
        let real = marker
            .lines()
            .find_map(|line| line.strip_prefix("registry="))
            .map(str::trim)
            .filter(|real| !real.is_empty() && real.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b':')))
            .ok_or("this development distribution doesn't say where its registry is (a registry= line in /etc/idleo/development)")?;
        let folder = PathBuf::from("/etc/containerd/certs.d").join(DEV_REGISTRY);
        std::fs::create_dir_all(&folder).map_err(|e| format!("the registry's config folder couldn't be made: {e}"))?;
        let config = format!(
            "server = \"http://{real}\"\n\n[host.\"http://{real}\"]\n  capabilities = [\"pull\", \"resolve\"]\n"
        );
        std::fs::write(folder.join("hosts.toml"), config)
            .map_err(|e| format!("the registry's config couldn't be written: {e}"))
    }

    fn gpu_present() -> bool {
        Path::new("/dev/dxg").exists() || Path::new("/dev/nvidiactl").exists()
    }

    fn scratch_paths(&self, key: &str) -> (PathBuf, PathBuf) {
        (self.paths.state.join("scratch").join(format!("{key}.img")), self.paths.state.join("mnt").join(key))
    }

    /// Follows containerd's events for the runner's namespace for as long as the process lives, and remembers every container
    /// the kernel killed for memory. This is the kernel's own verdict, kept after the container (and its cgroup, and its
    /// counters) are gone, which is why the runner can say `out_of_memory` rather than a bare exit code 137.
    pub fn watch_oom_events(&self) {
        let ooms = Arc::clone(&self.ooms);
        tokio::spawn(async move {
            loop {
                let spawned = Command::new("ctr")
                    .args(["--namespace", NAMESPACE, "events"])
                    .stdin(Stdio::null())
                    .stdout(Stdio::piped())
                    .stderr(Stdio::null())
                    .kill_on_drop(true)
                    .spawn();
                if let Ok(mut child) = spawned {
                    if let Some(out) = child.stdout.take() {
                        let mut lines = tokio::io::BufReader::new(out).lines();
                        while let Ok(Some(line)) = lines.next_line().await {
                            if let Some(id) = oom_event_container(&line) {
                                ooms.lock().unwrap_or_else(PoisonError::into_inner).insert(id);
                            }
                        }
                    }
                    let _ = child.wait().await;
                }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        });
    }

    /// Whether containerd has the image, by exactly this reference. (`nerdctl image inspect` doesn't find a reference with a
    /// digest, though the image is listed under it; and an image that's here is never looked for on the network.)
    async fn image_present(image: &str) -> bool {
        nerdctl(&["images", "--format", "{{.Name}}"])
            .await
            .is_ok_and(|out| out.ok && out.stdout.lines().any(|name| name.trim() == image))
    }

    /// Writes each session network's CNI config and turns on forwarding. Run once, when the runner starts.
    ///
    /// # Panics
    /// Never: the configs always serialise.
    ///
    /// # Errors
    /// If a config can't be written.
    pub fn prepare_networks(&self) -> Result<(), String> {
        std::fs::create_dir_all(&self.paths.cni_config).map_err(|e| format!("the CNI folder couldn't be made: {e}"))?;
        // The last is nerdctl's own default network, `bridge`: it insists one exists (and would make its own, which needs
        // plugins we don't carry), though nothing here ever runs a container on it without naming a session network.
        for slot in 0..=SLOTS {
            let name = if slot == SLOTS { "bridge".to_owned() } else { format!("idleo-slot-{slot}") };
            let config = serde_json::json!({
                "cniVersion": "1.0.0",
                "name": name,
                "plugins": [{
                    "type": "bridge",
                    "bridge": format!("{}{slot}", sandbox::BRIDGE_PREFIX),
                    "isGateway": true,
                    "ipMasq": false,
                    "hairpinMode": false,
                    "ipam": {
                        "type": "host-local",
                        "ranges": [[{ "subnet": format!("172.30.{slot}.0/24") }]],
                        "routes": [{ "dst": "0.0.0.0/0" }],
                    },
                }],
            });
            let path = self.paths.cni_config.join(format!("{name}.conflist"));
            std::fs::write(&path, serde_json::to_vec_pretty(&config).expect("a config serialises"))
                .map_err(|e| format!("{} couldn't be written: {e}", path.display()))?;
        }
        std::fs::write("/proc/sys/net/ipv4/ip_forward", "1\n")
            .map_err(|e| format!("forwarding couldn't be turned on: {e}"))
    }

    fn take_slot(&self) -> Option<u8> {
        let mut taken = self.slots.lock().unwrap_or_else(PoisonError::into_inner);
        let slot = (0..SLOTS).find(|slot| !taken.contains(slot))?;
        taken.insert(slot);
        Some(slot)
    }

    fn release_slot(&self, slot: u8) {
        self.slots.lock().unwrap_or_else(PoisonError::into_inner).remove(&slot);
    }

    /// Starts a container from its `nerdctl run` arguments, and the tasks that follow it. On failure nothing is left running.
    async fn launch(plan: &Plan, args: &[String]) -> Result<Running, String> {
        let arguments: Vec<&str> = args.iter().map(String::as_str).collect();
        let out = run("nerdctl", &arguments, None, COMMAND_TIMEOUT).await?;
        if !out.ok {
            let _ = nerdctl(&["rm", "--force", &plan.name]).await;
            return Err(out.why("starting the container"));
        }
        let id = out.stdout.lines().last().unwrap_or_default().trim().to_owned();
        if id.is_empty() {
            return Err("nerdctl started a container but gave no id".to_owned());
        }
        let cgroup_events = async {
            let pid = nerdctl(&["inspect", "--format", "{{.State.Pid}}", &id])
                .await
                .ok()?
                .stdout
                .trim()
                .parse::<u32>()
                .ok()?;
            let cgroup = std::fs::read_to_string(format!("/proc/{pid}/cgroup")).ok()?;
            let path = cgroup.lines().find_map(|line| line.strip_prefix("0::"))?.to_owned();
            Some(PathBuf::from(format!("/sys/fs/cgroup{path}/memory.events")))
        }
        .await;

        let (lines, output) = mpsc::channel(256);
        let followed = Command::new("nerdctl")
            .args(["--namespace", NAMESPACE, "logs", "--follow", &id])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true)
            .spawn();
        if let Ok(mut child) = followed {
            for (stream, pipe) in
                [(Stream::Stdout, child.stdout.take().map(boxed)), (Stream::Stderr, child.stderr.take().map(boxed))]
            {
                if let Some(mut pipe) = pipe {
                    let lines = lines.clone();
                    tokio::spawn(async move {
                        let mut buffer = vec![0_u8; 16 * 1024];
                        while let Ok(read) = pipe.read(&mut buffer).await {
                            if read == 0 || lines.send((stream, buffer[..read].to_vec())).await.is_err() {
                                break;
                            }
                        }
                    });
                }
            }
            tokio::spawn(async move {
                let _ = child.wait().await;
            });
        }
        drop(lines);

        // The kernel's verdict is read while the job runs, every 10 ms: the container's cgroup is gone within a few tens of
        // milliseconds of its exit, with its counters.
        let oom = Arc::new(AtomicBool::new(false));
        if let Some(path) = cgroup_events.clone() {
            let oom = Arc::clone(&oom);
            tokio::spawn(async move {
                while Arc::strong_count(&oom) > 1 {
                    match std::fs::read_to_string(&path) {
                        Ok(text) => {
                            if oom_kills(&text) > 0 {
                                oom.store(true, Ordering::SeqCst);
                            }
                        }
                        Err(_) => return,
                    }
                    tokio::time::sleep(Duration::from_millis(10)).await;
                }
            });
        }
        let (exit_tx, exit) = watch::channel(None);
        let waited = id.clone();
        tokio::spawn(async move {
            let code = match nerdctl(&["wait", &waited]).await {
                Ok(out) if out.ok => out.stdout.trim().parse::<i32>().unwrap_or(-1),
                _ => -1,
            };
            exit_tx.send_replace(Some(Exit { code }));
        });
        Ok(Running { id, cgroup_events, oom, output: Mutex::new(Some(output)), exit, removed: AtomicBool::new(false) })
    }

    /// Loads the sandbox's rules: what `idleo-sandbox.service` runs.
    ///
    /// # Errors
    /// If `nft` refuses them.
    pub async fn load_rules() -> Result<(), String> {
        let out = run("nft", &["-f", "-"], Some(&sandbox::rendered()), COMMAND_TIMEOUT).await?;
        if out.ok { Ok(()) } else { Err(out.why("loading the network rules")) }
    }

    /// The rules as loaded, checked against this runner's, with the addresses that depend on the network refreshed.
    async fn sandbox_ready(&self) -> Result<(), String> {
        let inet = run("nft", &["list", "table", "inet", "idleo"], None, COMMAND_TIMEOUT).await?;
        let nat = run("nft", &["list", "table", "ip", "idleo_nat"], None, COMMAND_TIMEOUT).await?;
        sandbox::verify(if inet.ok { &inet.stdout } else { "" }, if nat.ok { &nat.stdout } else { "" })?;
        let mut blocked = sandbox::own_addresses(&std::fs::read_to_string("/proc/net/fib_trie").unwrap_or_default());
        blocked.extend(sandbox::default_gateway(&std::fs::read_to_string("/proc/net/route").unwrap_or_default()));
        let update = run("nft", &["-f", "-"], Some(&sandbox::extra_blocked_script(&blocked)), COMMAND_TIMEOUT).await?;
        if !update.ok {
            return Err(update.why("refreshing the blocked addresses"));
        }
        if std::fs::read_to_string("/proc/sys/net/ipv4/ip_forward").map_or(true, |text| text.trim() != "1") {
            return Err("forwarding is off, so a session network can't reach out".to_owned());
        }
        Ok(())
    }

    fn free_bytes(path: &Path) -> u64 {
        rustix::fs::statvfs(path).map_or(0, |stat| stat.f_bavail.saturating_mul(stat.f_frsize))
    }

    /// How much containerd has downloaded so far, for a pull's progress.
    fn downloaded(&self) -> u64 {
        fn size(folder: &Path) -> u64 {
            std::fs::read_dir(folder)
                .into_iter()
                .flatten()
                .flatten()
                .map(|entry| {
                    let path = entry.path();
                    match entry.metadata() {
                        Ok(meta) if meta.is_dir() => size(&path),
                        Ok(meta) => meta.len(),
                        Err(_) => 0,
                    }
                })
                .sum()
        }
        size(&self.paths.content.join("blobs")) + size(&self.paths.content.join("ingest"))
    }

    fn insecure(image: &str) -> bool {
        // A registry with a port or an address as its name is a development one; the marker decides whether it may be
        // reached without TLS. The logical development registry is mapped by its hosts.toml instead (plain HTTP there too).
        Self::development()
            && !image.starts_with(&format!("{DEV_REGISTRY}/"))
            && image
                .split('/')
                .next()
                .is_some_and(|host| host.contains(':') || host.parse::<std::net::Ipv4Addr>().is_ok())
    }

    async fn remove_leftover_mounts(&self) {
        let mounts = std::fs::read_to_string("/proc/mounts").unwrap_or_default();
        let prefix = self.paths.state.join("mnt");
        for line in mounts.lines() {
            if let Some(point) =
                line.split_whitespace().nth(1).map(str::to_owned).filter(|p| Path::new(p).starts_with(&prefix))
            {
                let _ = run("umount", &["-l", &point], None, COMMAND_TIMEOUT).await;
            }
        }
    }
}

impl Machine for Nerdctl {
    fn hello(&self) -> Fut<'_, HelloAnswer> {
        Box::pin(async move {
            HelloAnswer {
                runner_version: env!("CARGO_PKG_VERSION").to_owned(),
                distro_version: std::fs::read_to_string("/etc/idleo-distro-version")
                    .unwrap_or_default()
                    .trim()
                    .to_owned(),
                gpu: Self::gpu_present(),
                rules_loaded: self.sandbox_ready().await.is_ok(),
                scratch_free_bytes: Self::free_bytes(&self.paths.state),
                development: Self::development(),
            }
        })
    }

    fn check_sandbox(&self) -> Fut<'_, Result<(), String>> {
        Box::pin(self.sandbox_ready())
    }

    fn ensure_image<'a>(&'a self, image: &'a str, progress: Progress) -> Fut<'a, Result<(), String>> {
        Box::pin(async move {
            if Self::image_present(image).await {
                return Ok(());
            }
            Self::map_development_registry(image)?;
            let before = self.downloaded();
            let mut args = vec!["--namespace", NAMESPACE, "pull", "--quiet"];
            if Self::insecure(image) {
                args.push("--insecure-registry");
            }
            args.push(image);
            tracing::info!(image, ?args, "pulling an image");
            let pulling = run("nerdctl", &args, None, PULL_TIMEOUT);
            tokio::pin!(pulling);
            let mut ticks = tokio::time::interval(Duration::from_secs(1));
            let result = loop {
                tokio::select! {
                    result = &mut pulling => break result,
                    _ = ticks.tick() => progress(self.downloaded().saturating_sub(before), 0),
                }
            };
            let out = result?;
            if out.ok { Ok(()) } else { Err(out.why("pulling the image")) }
        })
    }

    fn make_scratch<'a>(&'a self, key: &'a str, scratch_mb: u32) -> Fut<'a, Result<Scratch, String>> {
        Box::pin(async move {
            let (image, root) = self.scratch_paths(key);
            let made = async {
                for folder in [image.parent().unwrap_or(Path::new("/")), root.as_path()] {
                    tokio::fs::create_dir_all(folder)
                        .await
                        .map_err(|e| format!("{} couldn't be made: {e}", folder.display()))?;
                }
                let image_text = image.to_string_lossy().into_owned();
                let size = format!("{scratch_mb}M");
                let step = |out: Finished, what: &str| if out.ok { Ok(()) } else { Err(out.why(what)) };
                step(
                    run("truncate", &["-s", &size, &image_text], None, COMMAND_TIMEOUT).await?,
                    "making the scratch disk",
                )?;
                step(
                    run(
                        "mkfs.ext4",
                        &[
                            "-q",
                            "-F",
                            "-m",
                            "0",
                            "-L",
                            "idleo-scratch",
                            "-E",
                            "lazy_itable_init=1,lazy_journal_init=1,nodiscard",
                            &image_text,
                        ],
                        None,
                        COMMAND_TIMEOUT,
                    )
                    .await?,
                    "formatting the scratch disk",
                )?;
                step(
                    run(
                        "mount",
                        &["-o", "loop,nosuid,nodev", &image_text, &root.to_string_lossy()],
                        None,
                        COMMAND_TIMEOUT,
                    )
                    .await?,
                    "mounting the scratch disk",
                )?;
                let scratch = Scratch {
                    key: key.to_owned(),
                    work: root.join("work"),
                    input: root.join("input"),
                    resume: root.join("resume"),
                    root: root.clone(),
                };
                for (folder, mode) in [(&scratch.work, 0o755), (&scratch.input, 0o755), (&scratch.resume, 0o755)] {
                    std::fs::create_dir(folder).map_err(|e| format!("{} couldn't be made: {e}", folder.display()))?;
                    set_mode(folder, mode)?;
                }
                // Inside /work: the job's own folders, and the mount points of the read-only inputs, which it can't remove.
                for name in ["outputs", "checkpoints", "tmp"] {
                    let folder = scratch.work.join(name);
                    std::fs::create_dir(&folder).map_err(|e| format!("{} couldn't be made: {e}", folder.display()))?;
                    std::os::unix::fs::chown(&folder, Some(JOB_UID), Some(JOB_UID))
                        .map_err(|e| format!("chown failed: {e}"))?;
                }
                for name in ["input", "resume"] {
                    let folder = scratch.work.join(name);
                    std::fs::create_dir(&folder).map_err(|e| format!("{} couldn't be made: {e}", folder.display()))?;
                    set_mode(&folder, 0o555)?;
                }
                std::os::unix::fs::chown(&scratch.work, Some(JOB_UID), Some(JOB_UID))
                    .map_err(|e| format!("chown failed: {e}"))?;
                Ok::<_, String>(scratch)
            }
            .await;
            match made {
                Ok(scratch) => Ok(scratch),
                Err(why) => {
                    let (image, root) = self.scratch_paths(key);
                    teardown_scratch(&image, &root).await;
                    Err(why)
                }
            }
        })
    }

    fn scratch_free(&self, scratch: &Scratch) -> u64 {
        Self::free_bytes(&scratch.root)
    }

    fn remove_scratch<'a>(&'a self, scratch: &'a Scratch) -> Fut<'a, ()> {
        Box::pin(async move {
            let (image, root) = self.scratch_paths(&scratch.key);
            teardown_scratch(&image, &root).await;
        })
    }

    fn start_container(&self, mut plan: Plan) -> Fut<'_, Result<Arc<dyn Container>, String>> {
        Box::pin(async move {
            let slot = self.take_slot().ok_or("every session network is in use")?;
            plan.network = format!("idleo-slot-{slot}");
            let args = if plan.entrypoint.is_some() {
                container::run_arguments_trusted(&plan)
            } else {
                container::run_arguments(&plan)
            }
            .map_err(|refused| refused.0)?;
            let started = Self::launch(&plan, &args).await;
            match started {
                Ok(running) => Ok(Arc::new(NerdctlContainer {
                    running,
                    slot,
                    slots: Arc::clone(&self.slots),
                    ooms: Arc::clone(&self.ooms),
                }) as Arc<dyn Container>),
                Err(why) => {
                    self.release_slot(slot);
                    Err(why)
                }
            }
        })
    }

    fn self_check<'a>(&'a self, cuda_image: Option<&'a str>, quick: bool) -> Fut<'a, Vec<Check>> {
        Box::pin(self.checks(cuda_image, quick))
    }

    fn clean_up_leftovers(&self) -> Fut<'_, ()> {
        Box::pin(async move {
            if let Ok(out) = nerdctl(&["ps", "--all", "--quiet", "--filter", "label=idleo.session"]).await {
                for id in out.stdout.split_whitespace() {
                    let _ = nerdctl(&["rm", "--force", id]).await;
                }
            }
            self.remove_leftover_mounts().await;
            let _ = tokio::fs::remove_dir_all(self.paths.state.join("scratch")).await;
            let _ = tokio::fs::remove_dir_all(self.paths.state.join("mnt")).await;
        })
    }
}

fn set_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
        .map_err(|e| format!("{} couldn't be set up: {e}", path.display()))
}

/// Unmounts a scratch disk and deletes its image and folder, whatever state it's in.
async fn teardown_scratch(image: &Path, root: &Path) {
    let point = root.to_string_lossy().into_owned();
    for attempt in 0..5 {
        match run("umount", &[&point], None, COMMAND_TIMEOUT).await {
            Ok(out) if out.ok || out.stderr.contains("not mounted") => break,
            _ if attempt == 4 => {
                let _ = run("umount", &["-l", &point], None, COMMAND_TIMEOUT).await;
            }
            _ => tokio::time::sleep(Duration::from_millis(300)).await,
        }
    }
    let _ = tokio::fs::remove_file(image).await;
    let _ = tokio::fs::remove_dir_all(root).await;
}

fn boxed(pipe: impl AsyncRead + Unpin + Send + 'static) -> Pin<Box<dyn AsyncRead + Send>> {
    Box::pin(pipe)
}

/// A started container: its id, its cgroup (for the kernel's memory verdict), its output and its exit.
struct Running {
    id: String,
    cgroup_events: Option<PathBuf>,
    /// Set once `memory.events` has shown the kernel killing something in the container for memory.
    oom: Arc<AtomicBool>,
    output: Mutex<Option<Output>>,
    exit: watch::Receiver<Option<Exit>>,
    removed: AtomicBool,
}

struct NerdctlContainer {
    running: Running,
    slot: u8,
    slots: Arc<Mutex<BTreeSet<u8>>>,
    ooms: Arc<Mutex<std::collections::HashSet<String>>>,
}

impl Container for NerdctlContainer {
    fn take_output(&self) -> Option<Output> {
        self.running.output.lock().unwrap_or_else(PoisonError::into_inner).take()
    }

    fn wait(&self) -> Fut<'_, Exit> {
        let mut exit = self.running.exit.clone();
        Box::pin(async move {
            loop {
                if let Some(exit) = *exit.borrow_and_update() {
                    return exit;
                }
                if exit.changed().await.is_err() {
                    return Exit { code: -1 };
                }
            }
        })
    }

    fn signal(&self, signal: &'static str) -> Fut<'_, ()> {
        Box::pin(async move {
            if let Err(why) = nerdctl(&["kill", "--signal", signal, &self.running.id]).await {
                tracing::warn!(%why, signal, "a signal couldn't be sent");
            }
        })
    }

    fn oom_killed(&self) -> Fut<'_, bool> {
        Box::pin(async move {
            let now = self
                .running
                .cgroup_events
                .as_ref()
                .and_then(|path| std::fs::read_to_string(path).ok())
                .is_some_and(|text| oom_kills(&text) > 0);
            now || self.running.oom.load(Ordering::SeqCst)
                || self.ooms.lock().unwrap_or_else(PoisonError::into_inner).contains(&self.running.id)
        })
    }

    fn remove(&self) -> Fut<'_, ()> {
        Box::pin(async move {
            if self.running.removed.swap(true, Ordering::SeqCst) {
                return;
            }
            let _ = nerdctl(&["rm", "--force", &self.running.id]).await;
            self.slots.lock().unwrap_or_else(PoisonError::into_inner).remove(&self.slot);
        })
    }
}

/// The container id in a `ctr events` line about an out-of-memory kill:
/// `2026-10-19 19:22:12 +0000 UTC idleo /tasks/oom {"container_id":"..."}`.
fn oom_event_container(line: &str) -> Option<String> {
    let (_, rest) = line.split_once(" /tasks/oom ")?;
    let id = rest.split_once("\"container_id\":\"")?.1.split('"').next()?;
    Some(id.to_owned())
}

/// The `oom_kill` counter of a cgroup's `memory.events`: how many times the kernel killed something in it for memory.
fn oom_kills(events: &str) -> u64 {
    events.lines().find_map(|line| line.strip_prefix("oom_kill ")?.trim().parse().ok()).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_oom_event_names_its_container() {
        let line = r#"2026-10-19 19:22:12.481491666 +0000 UTC idleo /tasks/oom {"container_id":"4f3dbfc6"}"#;
        assert_eq!(oom_event_container(line).as_deref(), Some("4f3dbfc6"));
        assert_eq!(oom_event_container(r#"2026-10-19 idleo /tasks/exit {"container_id":"4f3dbfc6"}"#), None);
    }

    #[test]
    fn the_kernels_memory_events_are_read_for_oom_kills() {
        assert_eq!(oom_kills("low 0\nhigh 0\nmax 12\noom 1\noom_kill 3\noom_group_kill 0\n"), 3);
        assert_eq!(oom_kills("low 0\noom_kill 0\n"), 0);
        assert_eq!(oom_kills(""), 0);
    }
}
