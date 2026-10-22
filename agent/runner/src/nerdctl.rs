//! The real machine (ADR 0035): containerd driven through `nerdctl`, scratch disks from `truncate`, `mkfs.ext4` and `mount`,
//! the sandbox's rules in `nft`. Everything here shells out to a pinned tool of the distribution; nothing is reached over a
//! network except the registry an image is pulled from.
//!
//! **Networks.** Each session gets its own bridge, taken from a pool of 32: slot `N` is the bridge `idlN` on
//! `172.30.N.0/24`, described by a CNI config written at start-up (`idleo-slot-N`), so no network is made or deleted per
//! session and a crash leaves nothing to clean up but the slot. A slot is held from a container's start until it's removed.
//! **Scratch disks.** `<state>/scratch/<key>.img`, a sparse image of `scratch_mb` holding a LUKS2 volume with a key of its
//! own (ADR 0038, `crypto.rs`), with ext4 inside, mounted at `<state>/mnt/<key>` with `nosuid,nodev`; closed, its header
//! erased and deleted at the end. A job can't fill the machine's disk, only its own. What the job prints, which nerdctl
//! keeps in a file of the container's, is kept on that disk too (`<root>/logs`), through a link put in place before the
//! container starts, so nothing of the renter's is on the distribution's disk in the clear. A development distribution
//! whose kernel has no dm-crypt makes plain disks and says so; a release one runs no job without it.

use std::collections::{BTreeSet, HashSet};
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
use crate::crypto;
use crate::machine::{Container, Exit, Fut, Machine, Output, Progress, Scratch, Stream};
use crate::sandbox;

mod leftovers;
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
/// Room on every scratch disk that only root can use, beyond the job's `scratch_mb`: what the job prints is kept in a file on
/// the disk (ADR 0038), written by nerdctl as root, so a job that fills its share of the disk still has its last lines
/// recorded. The runner keeps at most 10 MiB of a job's output (`jobcore::logs::CAP`); nerdctl's file of it, a JSON object
/// a line, is larger. A small disk keeps a quarter of its size instead (ext4 refuses to reserve half of one).
const OUTPUT_RESERVE_MIB: u32 = 64;

/// The room kept for what the job prints on a disk of `scratch_mb` for the job: [`OUTPUT_RESERVE_MIB`], or a quarter of a
/// small one, and never under 4 MiB.
fn output_reserve_mib(scratch_mb: u32) -> u32 {
    OUTPUT_RESERVE_MIB.min(scratch_mb / 4).max(4)
}
/// The scratch disk's block size, so the reserve is exactly [`OUTPUT_RESERVE_MIB`] (a small ext4 would use 1 KiB blocks).
const SCRATCH_BLOCK: u32 = 4096;
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
    /// Where the CNI plugins keep what they've handed out: host-local IPAM's address leases, a folder for each network.
    pub cni_state: PathBuf,
    /// containerd's content store, whose growth is a pull's progress.
    pub content: PathBuf,
    /// nerdctl's own data: each container's folder, with the file of what it prints.
    pub nerdctl_data: PathBuf,
    /// Where device-mapper's devices are, to find an encrypted volume a crashed runner left open.
    pub mapper: PathBuf,
}

impl Default for Paths {
    fn default() -> Self {
        Self {
            state: PathBuf::from("/var/lib/idleo"),
            cni_config: PathBuf::from("/etc/cni/net.d"),
            cni_state: PathBuf::from("/var/lib/cni/networks"),
            content: PathBuf::from("/var/lib/containerd/io.containerd.content.v1.content"),
            nerdctl_data: PathBuf::from("/var/lib/nerdctl"),
            mapper: PathBuf::from("/dev/mapper"),
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
    /// The containers whose exit containerd has reported, read from the same stream, in order, as the OOM events: once a
    /// container is in here, every OOM event about it has been read already.
    exits: Arc<Mutex<std::collections::HashSet<String>>>,
    /// What sessions running now hold, so what finished ones left can be told from it ([`Nerdctl::what_is_left`]): the ids of
    /// their containers and the keys of their scratch disks. (Session networks are `slots`.)
    held_containers: Arc<Mutex<HashSet<String>>>,
    held_scratch: Arc<Mutex<HashSet<String>>>,
    /// Whether this kernel can encrypt a scratch disk, found once by making a small encrypted volume (ADR 0038).
    encryption: tokio::sync::OnceCell<Result<(), String>>,
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

/// Runs `program` with `args`, writing `stdin` to its standard input (then closing it) when there is one: the only way a
/// scratch disk's key ever reaches `cryptsetup`.
async fn run(program: &str, args: &[&str], stdin: Option<&[u8]>, limit: Duration) -> Result<Finished, String> {
    let mut command = Command::new(program);
    command
        .args(args)
        .env("PATH", "/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin")
        .env("CNI_PATH", "/opt/cni/bin")
        // There's no udev in the distribution: device-mapper makes its own device nodes instead of waiting for it.
        .env("DM_DISABLE_UDEV", "1")
        .stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    let mut child = command.spawn().map_err(|e| format!("{program} couldn't be started: {e}"))?;
    if let (Some(bytes), Some(mut pipe)) = (stdin, child.stdin.take()) {
        pipe.write_all(bytes).await.map_err(|e| format!("{program} didn't take its input: {e}"))?;
        drop(pipe);
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
        Self {
            paths,
            slots: Arc::default(),
            ooms: Arc::default(),
            exits: Arc::default(),
            held_containers: Arc::default(),
            held_scratch: Arc::default(),
            encryption: tokio::sync::OnceCell::new(),
        }
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
        let exits = Arc::clone(&self.exits);
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
                            } else if let Some(id) = exit_event_container(&line) {
                                exits.lock().unwrap_or_else(PoisonError::into_inner).insert(id);
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

    /// Moves the file nerdctl keeps of what container `id` prints (`<data>/<address hash>/containers/<namespace>/<id>/
    /// <id>-json.log`, made empty by `nerdctl create`) onto the scratch disk, leaving a link where it was: nerdctl and
    /// `nerdctl logs` go on using it, and what the job prints is written only to the encrypted disk. A container whose file
    /// isn't where nerdctl keeps it isn't started, so a nerdctl that changed where it keeps it can't put a renter's output
    /// on the distribution's disk unnoticed.
    fn keep_output_on_scratch(&self, id: &str, logs: &Path) -> Result<(), String> {
        let name = format!("{id}-json.log");
        let file = std::fs::read_dir(&self.paths.nerdctl_data)
            .map_err(|e| format!("nerdctl's data couldn't be read: {e}"))?
            .flatten()
            .map(|entry| entry.path().join("containers").join(NAMESPACE).join(id).join(&name))
            .find(|path| path.symlink_metadata().is_ok_and(|meta| meta.is_file()))
            .ok_or("nerdctl's file of the container's output isn't where nerdctl keeps it")?;
        let kept = logs.join(&name);
        std::fs::File::create(&kept).map_err(|e| format!("the container's output file couldn't be made: {e}"))?;
        set_mode(&kept, 0o600)?;
        std::fs::remove_file(&file).map_err(|e| format!("nerdctl's output file couldn't be moved: {e}"))?;
        std::os::unix::fs::symlink(&kept, &file).map_err(|e| format!("nerdctl's output file couldn't be linked: {e}"))
    }

    /// Makes a container from its `nerdctl create` arguments, keeps what it will print on its scratch disk, starts it, and
    /// starts the tasks that follow it. On failure nothing is left running.
    async fn launch(&self, plan: &Plan, args: &[String]) -> Result<Running, String> {
        let arguments: Vec<&str> = args.iter().map(String::as_str).collect();
        let out = run("nerdctl", &arguments, None, COMMAND_TIMEOUT).await?;
        if !out.ok {
            let _ = nerdctl(&["rm", "--force", &plan.name]).await;
            return Err(out.why("making the container"));
        }
        let id = out.stdout.lines().last().unwrap_or_default().trim().to_owned();
        if id.is_empty() {
            let _ = nerdctl(&["rm", "--force", &plan.name]).await;
            return Err("nerdctl made a container but gave no id".to_owned());
        }
        let started = match self.keep_output_on_scratch(&id, &plan.logs) {
            Ok(()) => nerdctl(&["start", &id])
                .await
                .and_then(|out| if out.ok { Ok(()) } else { Err(out.why("starting the container")) }),
            Err(why) => Err(why),
        };
        if let Err(why) = started {
            let _ = nerdctl(&["rm", "--force", &id]).await;
            return Err(why);
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
        let out = run("nft", &["-f", "-"], Some(sandbox::rendered().as_bytes()), COMMAND_TIMEOUT).await?;
        if out.ok { Ok(()) } else { Err(out.why("loading the network rules")) }
    }

    /// The tables this runner's rules make, worked out once: the rules file is loaded in a network namespace of its own,
    /// where it changes nothing, and read back the way the loaded ones are, so the two are in the same form whatever
    /// `nft` does to what it's given.
    async fn expected_rules() -> Result<&'static sandbox::Expected, String> {
        static EXPECTED: tokio::sync::OnceCell<Result<sandbox::Expected, String>> = tokio::sync::OnceCell::const_new();
        EXPECTED
            .get_or_init(|| async {
                let listing = |family: &'static str, table: &'static str| async move {
                    let script = format!("nft -f - && nft -j list table {family} {table}");
                    let out = run(
                        "unshare",
                        &["--net", "sh", "-c", &script],
                        Some(sandbox::rendered().as_bytes()),
                        COMMAND_TIMEOUT,
                    )
                    .await?;
                    if out.ok {
                        sandbox::normalize(&out.stdout)
                    } else {
                        Err(out.why("working out what the network rules should be"))
                    }
                };
                Ok(sandbox::Expected { inet: listing("inet", "idleo").await?, nat: listing("ip", "idleo_nat").await? })
            })
            .await
            .as_ref()
            .map_err(Clone::clone)
    }

    /// The loaded rules, by what they contain, against this runner's.
    async fn rules_match() -> Result<(), String> {
        let expected = Self::expected_rules().await?;
        let inet = run("nft", &["-j", "list", "table", "inet", "idleo"], None, COMMAND_TIMEOUT).await?;
        let nat = run("nft", &["-j", "list", "table", "ip", "idleo_nat"], None, COMMAND_TIMEOUT).await?;
        sandbox::verify(expected, if inet.ok { &inet.stdout } else { "" }, if nat.ok { &nat.stdout } else { "" })
    }

    /// The rules as loaded, checked against this runner's, with the addresses that depend on the network refreshed. Rules that
    /// aren't exactly this runner's (changed by hand, half loaded, an older version) are loaded again once, which is one
    /// `nft` transaction with no moment between them, and checked again; a job starts only if they match then.
    async fn sandbox_ready(&self) -> Result<(), String> {
        if let Err(first) = Self::rules_match().await {
            tracing::warn!(%first, "the network rules aren't this runner's; loading them again");
            Self::load_rules().await?;
            Self::rules_match().await.map_err(|second| format!("{first}; after loading them again: {second}"))?;
        }
        let mut blocked = sandbox::own_addresses(&std::fs::read_to_string("/proc/net/fib_trie").unwrap_or_default());
        blocked.extend(sandbox::default_gateway(&std::fs::read_to_string("/proc/net/route").unwrap_or_default()));
        let update =
            run("nft", &["-f", "-"], Some(sandbox::extra_blocked_script(&blocked).as_bytes()), COMMAND_TIMEOUT).await?;
        if !update.ok {
            return Err(update.why("refreshing the blocked addresses"));
        }
        if std::fs::read_to_string("/proc/sys/net/ipv4/ip_forward").map_or(true, |text| text.trim() != "1") {
            return Err("forwarding is off, so a session network can't reach out".to_owned());
        }
        // A renter's files are never on a plain disk here (ADR 0038); only a development distribution may go without.
        if let Err(why) = self.encryption_ready().await
            && !Self::development()
        {
            return Err(format!("scratch disks can't be encrypted on this kernel: {why}"));
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

    /// Whether this kernel can encrypt a scratch disk, found once by making a small encrypted volume and seeing that what is
    /// written through it isn't on its image in the clear. A release distribution runs no job without it; a development one
    /// says so in the runner's log and makes plain disks.
    async fn encryption_ready(&self) -> Result<(), String> {
        self.encryption
            .get_or_init(|| async {
                let result = self.encryption_round_trip().await.map(|_| ());
                if let Err(why) = &result {
                    if Self::development() {
                        tracing::warn!(%why, "this kernel can't encrypt scratch disks; a development distribution makes plain ones");
                    } else {
                        tracing::error!(%why, "this kernel can't encrypt scratch disks, so no job will run");
                    }
                }
                result
            })
            .await
            .clone()
    }

    /// Makes a small encrypted volume with a key of its own, writes a marker through it and reads it back, closes it and
    /// erases its header, and checks the image never held the marker in the clear. What it took, in a line.
    pub(super) async fn encryption_round_trip(&self) -> Result<String, String> {
        // Microsoft's WSL kernel has dm-crypt as a module (CONFIG_DM_CRYPT=m); a kernel with it built in has nothing to load.
        let _ = run("modprobe", &["dm_crypt"], None, COMMAND_TIMEOUT).await;
        let key = "encryption-check";
        let mapper = crypto::mapper_name(key)?;
        let (image, _) = self.scratch_paths(key);
        let image_text = image.to_string_lossy().into_owned();
        let device = self.paths.mapper.join(&mapper);
        let started = std::time::Instant::now();
        let checked = async {
            tokio::fs::create_dir_all(image.parent().unwrap_or(Path::new("/")))
                .await
                .map_err(|e| format!("the scratch folder couldn't be made: {e}"))?;
            let out = run("truncate", &["-s", "32M", &image_text], None, COMMAND_TIMEOUT).await?;
            if !out.ok {
                return Err(out.why("making the test volume"));
            }
            open_encrypted(&image_text, &mapper).await?;
            let opened = started.elapsed();
            let marker: Vec<u8> = b"IDLEO-ENCRYPTION-CHECK-".iter().copied().cycle().take(64 * 1024).collect();
            let device_for_io = device.clone();
            let probe = marker.clone();
            let read = tokio::task::spawn_blocking(move || -> std::io::Result<Vec<u8>> {
                use std::io::{Read, Seek, SeekFrom, Write};
                let mut volume = std::fs::OpenOptions::new().read(true).write(true).open(&device_for_io)?;
                volume.write_all(&probe)?;
                volume.sync_all()?;
                volume.seek(SeekFrom::Start(0))?;
                let mut back = vec![0_u8; probe.len()];
                volume.read_exact(&mut back)?;
                Ok(back)
            })
            .await
            .map_err(|e| format!("the test volume's check didn't finish: {e}"))?
            .map_err(|e| format!("the test volume couldn't be written and read: {e}"));
            close_encrypted(&image_text, &mapper).await;
            if read? != marker {
                return Err("what was read back from the test volume isn't what was written".to_owned());
            }
            let raw =
                tokio::fs::read(&image).await.map_err(|e| format!("the test volume's image couldn't be read: {e}"))?;
            if raw.windows(23).any(|window| window == &marker[..23]) {
                return Err("the test volume's image holds what was written through it in the clear".to_owned());
            }
            Ok(format!(
                "opened in {} ms, written, read, closed and erased in {} ms; its image holds nothing in the clear",
                opened.as_millis(),
                started.elapsed().as_millis()
            ))
        }
        .await;
        if checked.is_err() {
            close_encrypted(&image_text, &mapper).await;
        }
        let _ = tokio::fs::remove_file(&image).await;
        checked
    }

    /// Makes the scratch disk's image of `scratch_mb` and its reserve, opens it as an encrypted volume when there's a mapper
    /// name for it, puts ext4 on it with the reserve kept for root, and mounts it at `root`.
    async fn format_and_mount(
        &self,
        image: &Path,
        root: &Path,
        scratch_mb: u32,
        mapper: Option<&str>,
    ) -> Result<(), String> {
        let image_text = image.to_string_lossy().into_owned();
        let reserve_mib = output_reserve_mib(scratch_mb);
        let size = format!("{}M", scratch_mb + reserve_mib);
        let step = |out: Finished, what: &str| if out.ok { Ok(()) } else { Err(out.why(what)) };
        step(run("truncate", &["-s", &size, &image_text], None, COMMAND_TIMEOUT).await?, "making the scratch disk")?;
        // Only root reads it: not the `idleo` user or any other account in the distribution (hostile test 16).
        set_mode(image, 0o600)?;
        // The filesystem goes on the opened volume, or on the image itself when it's a plain disk.
        let (device, mount_options) = match mapper {
            Some(mapper) => {
                open_encrypted(&image_text, mapper).await?;
                (self.paths.mapper.join(mapper).to_string_lossy().into_owned(), "nosuid,nodev")
            }
            None => (image_text.clone(), "loop,nosuid,nodev"),
        };
        let block = SCRATCH_BLOCK.to_string();
        let format = [
            "-q",
            "-F",
            "-m",
            "0",
            "-b",
            &block,
            "-L",
            "idleo-scratch",
            "-E",
            "lazy_itable_init=1,lazy_journal_init=1,nodiscard",
            &device,
        ];
        step(run("mkfs.ext4", &format, None, COMMAND_TIMEOUT).await?, "formatting the scratch disk")?;
        let reserved = (reserve_mib * 1024 * 1024 / SCRATCH_BLOCK).to_string();
        step(
            run("tune2fs", &["-r", &reserved, &device], None, COMMAND_TIMEOUT).await?,
            "keeping room on the scratch disk for what the job prints",
        )?;
        step(
            run("mount", &["-o", mount_options, &device, &root.to_string_lossy()], None, COMMAND_TIMEOUT).await?,
            "mounting the scratch disk",
        )
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
            // A release distribution never makes a plain disk; `check_sandbox` has already refused its jobs.
            let encrypted = match self.encryption_ready().await {
                Ok(()) => Some(crypto::mapper_name(key)?),
                Err(_) if Self::development() => None,
                Err(why) => return Err(format!("this distribution can't encrypt a scratch disk: {why}")),
            };
            self.held_scratch.lock().unwrap_or_else(PoisonError::into_inner).insert(key.to_owned());
            let (image, root) = self.scratch_paths(key);
            let made = async {
                for folder in [image.parent().unwrap_or(Path::new("/")), root.as_path()] {
                    tokio::fs::create_dir_all(folder)
                        .await
                        .map_err(|e| format!("{} couldn't be made: {e}", folder.display()))?;
                }
                set_mode(image.parent().unwrap_or(Path::new("/")), 0o700)?;
                self.format_and_mount(&image, &root, scratch_mb, encrypted.as_deref()).await?;
                let scratch = Scratch {
                    key: key.to_owned(),
                    work: root.join("work"),
                    input: root.join("input"),
                    resume: root.join("resume"),
                    root: root.clone(),
                    mapper: encrypted.clone(),
                };
                // The container's own output file, on this disk and readable only by root: nothing of the job's sees it.
                std::fs::create_dir(scratch.logs())
                    .map_err(|e| format!("the scratch disk's logs folder couldn't be made: {e}"))?;
                set_mode(&scratch.logs(), 0o700)?;
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
                    teardown_scratch(&image, &root, encrypted.as_deref()).await;
                    self.held_scratch.lock().unwrap_or_else(PoisonError::into_inner).remove(key);
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
            teardown_scratch(&image, &root, scratch.mapper.as_deref()).await;
            self.held_scratch.lock().unwrap_or_else(PoisonError::into_inner).remove(&scratch.key);
        })
    }

    fn start_container(&self, mut plan: Plan) -> Fut<'_, Result<Arc<dyn Container>, String>> {
        Box::pin(async move {
            let slot = self.take_slot().ok_or("every session network is in use")?;
            plan.network = format!("idleo-slot-{slot}");
            let args = if plan.entrypoint.is_some() {
                container::create_arguments_trusted(&plan)
            } else {
                container::create_arguments(&plan)
            }
            .map_err(|refused| refused.0)?;
            let started = self.launch(&plan, &args).await;
            match started {
                Ok(running) => {
                    self.held_containers.lock().unwrap_or_else(PoisonError::into_inner).insert(running.id.clone());
                    Ok(Arc::new(NerdctlContainer {
                        running,
                        slot,
                        slots: Arc::clone(&self.slots),
                        ooms: Arc::clone(&self.ooms),
                        exits: Arc::clone(&self.exits),
                        held: Arc::clone(&self.held_containers),
                    }) as Arc<dyn Container>)
                }
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

    fn leftovers(&self) -> Fut<'_, Vec<String>> {
        Box::pin(self.what_is_left())
    }

    fn development(&self) -> bool {
        Self::development()
    }

    fn clean_up_leftovers(&self) -> Fut<'_, ()> {
        Box::pin(async move {
            if let Ok(out) = nerdctl(&["ps", "--all", "--quiet", "--filter", "label=idleo.session"]).await {
                for id in out.stdout.split_whitespace() {
                    let _ = nerdctl(&["rm", "--force", id]).await;
                }
            }
            self.remove_leftover_mounts().await;
            // An encrypted volume a runner that died left open: its key died with it, so what's in it can't be read again;
            // the mapping and its loop device go, and the header is erased before the image is deleted.
            for mapper in leftovers::open_mappers(&self.paths.mapper) {
                let key = mapper.strip_prefix(crypto::MAPPER_PREFIX).unwrap_or(&mapper).to_owned();
                let (image, _) = self.scratch_paths(&key);
                close_encrypted(&image.to_string_lossy(), &mapper).await;
            }
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

fn strs(args: &[String]) -> Vec<&str> {
    args.iter().map(String::as_str).collect()
}

/// Makes `image` a LUKS2 volume with a fresh key and opens it as `/dev/mapper/<mapper>`. The key goes to `cryptsetup`
/// through its standard input and nowhere else, and is zeroed when this returns: from then on only the kernel holds what
/// unlocks the volume, and only while it's open.
async fn open_encrypted(image: &str, mapper: &str) -> Result<(), String> {
    let key = crypto::ScratchKey::random()?;
    let out = run("cryptsetup", &strs(&crypto::format_arguments(image)), Some(key.bytes()), COMMAND_TIMEOUT).await?;
    if !out.ok {
        return Err(out.why("encrypting the scratch disk"));
    }
    let out =
        run("cryptsetup", &strs(&crypto::open_arguments(image, mapper)), Some(key.bytes()), COMMAND_TIMEOUT).await?;
    if !out.ok {
        return Err(out.why("opening the encrypted scratch disk"));
    }
    Ok(())
}

/// Closes an encrypted volume (which also lets go of the loop device `cryptsetup open` made) and erases every key slot in
/// its image's header, so nothing can open it again. Safe to call on one that's closed already.
async fn close_encrypted(image: &str, mapper: &str) {
    for attempt in 0..5 {
        match run("cryptsetup", &strs(&crypto::close_arguments(mapper)), None, COMMAND_TIMEOUT).await {
            Ok(out) if out.ok || out.stderr.contains("doesn't exist") || out.stderr.contains("is not active") => break,
            Ok(out) if attempt == 4 => {
                tracing::warn!(why = %out.why("closing an encrypted scratch disk"), "it stays open");
            }
            _ => tokio::time::sleep(Duration::from_millis(300)).await,
        }
    }
    if Path::new(image).exists() {
        match run("cryptsetup", &strs(&crypto::erase_arguments(image)), None, COMMAND_TIMEOUT).await {
            Ok(out) if out.ok => {}
            Ok(out) => tracing::warn!(why = %out.why("erasing a scratch disk's header"), "the image is deleted anyway"),
            Err(why) => tracing::warn!(%why, "the image is deleted anyway"),
        }
    }
}

/// Unmounts a scratch disk, closes and erases its encrypted volume if it has one, and deletes its image and folder,
/// whatever state it's in.
async fn teardown_scratch(image: &Path, root: &Path, mapper: Option<&str>) {
    let point = root.to_string_lossy().into_owned();
    for attempt in 0..5 {
        match run("umount", &[&point], None, COMMAND_TIMEOUT).await {
            Ok(out) if out.ok || out.stderr.contains("not mounted") || out.stderr.contains("no mount point") => break,
            _ if attempt == 4 => {
                let _ = run("umount", &["-l", &point], None, COMMAND_TIMEOUT).await;
            }
            _ => tokio::time::sleep(Duration::from_millis(300)).await,
        }
    }
    if let Some(mapper) = mapper {
        close_encrypted(&image.to_string_lossy(), mapper).await;
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
    exits: Arc<Mutex<std::collections::HashSet<String>>>,
    held: Arc<Mutex<HashSet<String>>>,
}

/// How long to wait for containerd's own report of an exit before deciding without it: it comes within milliseconds when
/// its event stream is up, and only a stream that isn't up makes this wait.
const EXIT_EVENT_LIMIT: Duration = Duration::from_millis(500);

/// How long a job that was killed (exit code 137) waits for containerd's OOM event, which the kernel's count has already made
/// certain: milliseconds when it comes, and only a kill that wasn't for memory ever waits all of it.
const OOM_EVENT_LIMIT: Duration = Duration::from_millis(250);

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

    /// Whether the kernel killed the container for memory. The cgroup's own `memory.events` is gone the instant the container
    /// exits (measured: absent at the first look after the process ends), so it can't be read after the exit. The verdict is
    /// kept three ways: the counter read every 10 ms while the job runs, containerd's `/tasks/oom` event, and, to settle the race
    /// between the exit and the event, containerd's own `/tasks/exit` event for the container, read from the same stream: the
    /// kernel counts the kill before it makes it, so the OOM event comes first, a millisecond or so ahead of the exit, though
    /// not always in the order the stream delivers them (measured: one run in ten). Once the exit event has been read, a job that
    /// didn't die of SIGKILL (exit code 137) is known not to have been killed for memory; one that did waits up to
    /// [`OOM_EVENT_LIMIT`] for the OOM event, which is what an ordinary kill of a container (not ours: those are known) hardly ever
    /// costs. A stream that isn't delivering costs up to [`EXIT_EVENT_LIMIT`], and then the counter and what's been seen decide.
    fn oom_killed(&self) -> Fut<'_, bool> {
        Box::pin(async move {
            let id = &self.running.id;
            let seen_exit = || self.exits.lock().unwrap_or_else(PoisonError::into_inner).contains(id);
            let known = || {
                self.running.oom.load(Ordering::SeqCst)
                    || self.ooms.lock().unwrap_or_else(PoisonError::into_inner).contains(id)
                    || self
                        .running
                        .cgroup_events
                        .as_ref()
                        .and_then(|path| std::fs::read_to_string(path).ok())
                        .is_some_and(|text| oom_kills(&text) > 0)
            };
            let deadline = tokio::time::Instant::now() + EXIT_EVENT_LIMIT;
            while !seen_exit() && tokio::time::Instant::now() < deadline {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            // SIGKILL is what the kernel's OOM killer sends, and the only exit that can have been one.
            let killed = self.running.exit.borrow().as_ref().is_some_and(|exit| exit.code == 137);
            let deadline = tokio::time::Instant::now() + OOM_EVENT_LIMIT;
            while killed && !known() && tokio::time::Instant::now() < deadline {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            known()
        })
    }

    fn remove(&self) -> Fut<'_, ()> {
        Box::pin(async move {
            if self.running.removed.swap(true, Ordering::SeqCst) {
                return;
            }
            let _ = nerdctl(&["rm", "--force", &self.running.id]).await;
            // The verdict was read before this; what was kept for it isn't needed any more.
            self.held.lock().unwrap_or_else(PoisonError::into_inner).remove(&self.running.id);
            self.exits.lock().unwrap_or_else(PoisonError::into_inner).remove(&self.running.id);
            self.ooms.lock().unwrap_or_else(PoisonError::into_inner).remove(&self.running.id);
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

/// The container id in a `ctr events` line about a task's exit: `… /tasks/exit {"container_id":"…","id":"…","pid":…}`.
fn exit_event_container(line: &str) -> Option<String> {
    let (_, rest) = line.split_once(" /tasks/exit ")?;
    let id = rest.split_once("\"container_id\":\"")?.1.split('"').next()?;
    (!id.is_empty()).then(|| id.to_owned())
}

/// The `oom_kill` counter of a cgroup's `memory.events`: how many times the kernel killed something in it for memory.
fn oom_kills(events: &str) -> u64 {
    events.lines().find_map(|line| line.strip_prefix("oom_kill ")?.trim().parse().ok()).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_scratch_disk_keeps_room_for_what_the_job_prints() {
        assert_eq!(output_reserve_mib(40960), 64, "a real job's disk keeps 64 MiB");
        assert_eq!(output_reserve_mib(256), 64);
        assert_eq!(output_reserve_mib(64), 16, "a small one a quarter of it, which ext4 allows");
        assert_eq!(output_reserve_mib(8), 4, "never under 4 MiB");
    }

    #[test]
    fn an_exit_event_names_its_container() {
        let line = r#"2026-10-19 19:22:12.481491666 +0000 UTC idleo /tasks/exit {"container_id":"4f3dbfc6","id":"4f3dbfc6","pid":812,"exit_status":137}"#;
        assert_eq!(exit_event_container(line).as_deref(), Some("4f3dbfc6"));
        assert_eq!(exit_event_container(r#"2026-10-19 idleo /tasks/oom {"container_id":"4f3dbfc6"}"#), None);
        assert_eq!(exit_event_container(r#"2026-10-19 idleo /tasks/exit {"container_id":""}"#), None);
    }

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
