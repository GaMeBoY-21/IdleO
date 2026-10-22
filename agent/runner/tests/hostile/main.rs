//! The hostile tests (ADR 0035, §7, and ADR 0038): a job that tries to break out of its sandbox, or whose data a host tries to
//! read, 16 ways, each a named test run against
//! the development distribution (`scripts/dev-distro.sh`), where `idleo-runner` runs real containers under real nftables
//! rules. **Every attempt must fail.** A single FAIL blocks the work.
//!
//! They need a running development distribution and the hostile image in the development registry, so they're `#[ignore]`d
//! for a plain `cargo test`; `agent/runner/tests/hostile/run.sh` makes both and runs them, printing a table.

#![cfg(unix)]

use std::collections::BTreeMap;
use std::process::Command as Process;
use std::sync::Arc;
use std::time::{Duration, Instant};

use idleo_runner::{fair, sandbox};
use jobcore::fakes3::FakeS3;
use jobcore::link::{Link, RunnerEvent};
use jobcore::protocol::{
    AgentNotes, Answer, CheckStatus, Ended, Event, GrantWire, Grants, InputFile, Phase, Request, Spec, Start, code,
};
use tokio::sync::broadcast::Receiver;

/// What the attack ended with, and what it printed.
struct Run {
    ended: Ended,
    log: String,
}

impl Run {
    /// The attack's own verdict line.
    fn verdict(&self, attack: &str) -> Option<(bool, String)> {
        self.log.lines().find_map(|line| {
            let rest = line.strip_prefix(&format!("RESULT {attack} "))?;
            let (verdict, detail) = rest.split_once(' ').unwrap_or((rest, ""));
            Some((verdict == "HELD", detail.to_owned()))
        })
    }
}

/// A job to start: how it differs from the plain one.
struct Job {
    attack: &'static str,
    env: BTreeMap<String, String>,
    memory_mb: u32,
    pids_limit: u32,
    scratch_mb: u32,
    max_runtime_secs: u64,
    inputs: Vec<(String, Vec<u8>)>,
    /// The upload cap the agent would have given, in kbit/s; 0 is the runner's default.
    upload_cap_kbps: u32,
}

impl Job {
    fn new(attack: &'static str) -> Self {
        Self {
            attack,
            env: BTreeMap::new(),
            memory_mb: 512,
            pids_limit: 64,
            scratch_mb: 64,
            max_runtime_secs: 120,
            inputs: Vec::new(),
            upload_cap_kbps: 0,
        }
    }
}

struct Harness {
    distro: String,
    image: String,
    s3: FakeS3,
    link: Link,
}

fn distro() -> String {
    std::env::var("IDLEO_DEV_DISTRO").unwrap_or_else(|_| "idleo-dev-distro".to_owned())
}

fn inside(distro: &str, args: &[&str]) -> String {
    let out = Process::new("docker").args(["exec", distro]).args(args).output().expect("docker runs");
    format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr))
}

impl Harness {
    async fn new() -> Self {
        let image = std::env::var("IDLEO_HOSTILE_IMAGE").expect(
            "IDLEO_HOSTILE_IMAGE names the hostile image in the dev registry: run agent/runner/tests/hostile/run.sh",
        );
        let distro = distro();
        let name = distro.clone();
        let link = Link::process(move || {
            let mut command = tokio::process::Command::new("docker");
            command.args(["exec", "-i", &name, "/usr/local/bin/idleo-runner", "attach"]);
            command
        });
        assert!(
            matches!(link.request(Request::Hello).await, Ok(Answer::Hello(hello)) if hello.rules_loaded && hello.development),
            "the development distribution isn't up with its rules loaded"
        );
        Self { distro, image, s3: FakeS3::start().await, link }
    }

    fn start(&self, job: &Job, session: &str, attempt: i32) -> Start {
        let prefix = format!("sessions/{session}/attempt-{attempt}/");
        let endpoint = format!("http://host.docker.internal:{}", self.s3.port);
        let grant = |prefixes: Vec<String>, writable| GrantWire {
            endpoint: endpoint.clone(),
            bucket: "hostile".to_owned(),
            access_key_id: "HOSTILE-ACCESS-KEY".to_owned(),
            secret_access_key: "HOSTILE-SECRET-KEY".to_owned(),
            session_token: "HOSTILE-SESSION-TOKEN".to_owned(),
            prefixes,
            writable,
            expires_in_secs: 3600,
        };
        let upload = format!("uploads/{session}/files/");
        let inputs: Vec<InputFile> = job
            .inputs
            .iter()
            .map(|(name, body)| {
                self.s3.state.lock().unwrap().objects.insert(format!("{upload}{name}"), body.clone());
                InputFile {
                    prefix: upload.clone(),
                    name: name.clone(),
                    size_bytes: i64::try_from(body.len()).unwrap(),
                    slot: "data".to_owned(),
                }
            })
            .collect();
        Start {
            session: session.to_owned(),
            attempt,
            spec: Spec {
                image: self.image.clone(),
                args: vec![job.attack.to_owned()],
                env: job.env.clone(),
                memory_mb: job.memory_mb,
                cpu_millicores: 1000,
                pids_limit: job.pids_limit,
                scratch_mb: job.scratch_mb,
                max_runtime_secs: job.max_runtime_secs,
                checkpoint_interval_secs: 0,
                gpu: false,
            },
            attempt_prefix: prefix.clone(),
            grants: Grants {
                attempt: grant(vec![prefix], true),
                read: (!inputs.is_empty()).then(|| grant(vec![upload], false)),
            },
            resume_from: None,
            inputs,
            lease_ttl_secs: 600,
            agent: AgentNotes { minute_index_base: 0, gpus: vec![] },
            upload_cap_kbps: job.upload_cap_kbps,
        }
    }

    /// Starts the job and returns once the container is running (or has failed to).
    async fn begin(&self, job: &Job) -> (String, Receiver<RunnerEvent>) {
        let session = uuid::Uuid::now_v7().to_string();
        let events = self.link.events();
        let start = self.start(job, &session, 1);
        self.link.request(Request::Start(Box::new(start))).await.expect("the runner takes the start");
        (session, events)
    }

    async fn finish(&self, session: &str, mut events: Receiver<RunnerEvent>, within: Duration) -> Run {
        let deadline = Instant::now() + within;
        let ended = loop {
            let left = deadline.saturating_duration_since(Instant::now());
            assert!(!left.is_zero(), "the job didn't end in {within:?}");
            match tokio::time::timeout(left, events.recv()).await {
                Ok(Ok(RunnerEvent { session: who, event: Event::Exited { ended }, .. })) if who == session => {
                    break ended;
                }
                Ok(_) | Err(_) => {}
            }
        };
        let log = self.log(session);
        Run { ended, log }
    }

    fn log(&self, session: &str) -> String {
        let prefix = format!("sessions/{session}/attempt-1/logs/");
        let state = self.s3.state.lock().unwrap();
        state
            .objects
            .iter()
            .filter(|(key, _)| key.starts_with(&prefix))
            .map(|(_, body)| String::from_utf8_lossy(body).into_owned())
            .collect()
    }

    async fn run(&self, job: &Job) -> Run {
        let (session, events) = self.begin(job).await;
        self.finish(&session, events, Duration::from_secs(180)).await
    }

    /// Runs a job that tries to reach things, and checks the connection-rate limit dropped none of what it tried: a connection
    /// the limit dropped looks "blocked" whatever the sandbox's rules say, so an attack that outran the limit proves nothing
    /// about the rules (ADR 0038). The attacks pace themselves under it.
    async fn run_unthrottled(&self, job: &Job) -> Run {
        let (session, events) = self.begin(job).await;
        let slot = self.network_of(&session).await;
        let run = self.finish(&session, events, Duration::from_secs(180)).await;
        let dropped = self.fair_drops(slot).connections;
        assert_eq!(
            dropped, 0,
            "the connection limit dropped {dropped} of the attack's connections: it proves nothing about the rules"
        );
        run
    }

    fn inside(&self, args: &[&str]) -> String {
        inside(&self.distro, args)
    }

    /// A command inside the distribution as the unprivileged `idleo` user, not root: a stand-in for any process on the
    /// laptop that isn't the owner acting as administrator.
    fn inside_as_idleo(&self, args: &[&str]) -> (bool, String) {
        let out = Process::new("docker")
            .args(["exec", "-u", "idleo", &self.distro])
            .args(args)
            .output()
            .expect("docker runs");
        (
            out.status.success(),
            format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr)),
        )
    }

    /// How many times `marker` is in the parts of the stand-in virtual disk that hold data (its sparse holes hold nothing):
    /// the owner copying the laptop's `ext4.vhdx` would see exactly these bytes. Reads only the allocated extents, so it's
    /// quick however large the disk is.
    fn scan_state_disk(&self, marker: &str) -> u64 {
        let script = "img=/var/lib/idleo-disk/state.img; total=0; \
            while read -r start length; do \
              c=$(dd if=\"$img\" bs=1024 skip=\"$start\" count=\"$length\" 2>/dev/null | grep -a -c -F -- \"$1\"); \
              total=$((total + c)); \
            done < <(filefrag -e -b1024 \"$img\" 2>/dev/null | awk 'NR>3 { line=$0; gsub(/\\.\\./, \" \", line); gsub(/:/, \" \", line); n=split(line, f, \" \"); if (n>=6) print f[2], f[6] }'); \
            echo \"$total\"";
        self.inside(&["bash", "-c", script, "scan", marker]).trim().parse().unwrap_or(u64::MAX)
    }

    /// The runner and the distribution are as healthy as before: it answers, its rules are loaded, containerd lists, and it's
    /// the same process.
    async fn assert_healthy(&self, runner_pid: &str) {
        let Ok(Answer::Hello(hello)) = self.link.request(Request::Hello).await else {
            panic!("the runner stopped answering")
        };
        assert!(hello.rules_loaded, "the rules are gone");
        assert_eq!(self.inside(&["pgrep", "-x", "idleo-runner"]).trim(), runner_pid, "the runner restarted");
        let containers = self.inside(&["nerdctl", "--namespace", "idleo", "ps", "--all", "--quiet"]);
        assert!(containers.trim().is_empty(), "containers left behind: {containers}");
        let mounts = self.inside(&["sh", "-c", "mount | grep /var/lib/idleo/mnt || true"]);
        assert!(mounts.trim().is_empty(), "scratch disks left mounted: {mounts}");
    }

    /// The number of a running session's network, from its container's address (`172.30.<N>.x`), waiting for it to have one.
    async fn network_of(&self, session: &str) -> u8 {
        for _ in 0..120 {
            let address = self.container_address(session);
            if let Some(slot) = address.strip_prefix("172.30.").and_then(|rest| rest.split('.').next()) {
                return slot.parse().expect("a network number");
            }
            tokio::time::sleep(Duration::from_millis(250)).await;
        }
        panic!("the session never got a network address");
    }

    /// What the fair-use rules of network `slot` have dropped since its session began.
    fn fair_drops(&self, slot: u8) -> fair::Drops {
        let listing = self.inside(&["nft", "-j", "list", "counters", "table", "inet", fair::TABLE]);
        fair::drops_of(&fair::parse_counters(&listing).expect("nft's listing of the counters"), slot)
    }

    /// Adds test entries to the development distribution's marker (`abuse-name=`, `abuse-pool=`, `abuse-sha256=` lines), which
    /// the runner reads when a session starts; a release distribution has no marker, so no test entries.
    fn list_for_tests(&self, lines: &[&str]) {
        let text = lines.join("\n");
        self.inside(&["sh", "-c", &format!("grep -v '^abuse-' /etc/idleo/development > /tmp/marker; printf '%s\\n' '{text}' >> /tmp/marker; cp /tmp/marker /etc/idleo/development")]);
    }

    /// Waits for a job to end, collecting what the runner said about abuse on the way: `(kind, detail, stopped)`.
    async fn finish_collecting(
        &self,
        session: &str,
        mut events: Receiver<RunnerEvent>,
        within: Duration,
    ) -> (Run, Vec<(jobcore::protocol::AbuseKind, String, bool)>) {
        let deadline = Instant::now() + within;
        let mut told = Vec::new();
        let ended = loop {
            let left = deadline.saturating_duration_since(Instant::now());
            assert!(!left.is_zero(), "the job didn't end in {within:?}");
            match tokio::time::timeout(left, events.recv()).await {
                Ok(Ok(RunnerEvent { session: who, event: Event::Abuse { kind, detail, stopped, .. }, .. }))
                    if who == session =>
                {
                    told.push((kind, detail, stopped));
                }
                Ok(Ok(RunnerEvent { session: who, event: Event::Exited { ended }, .. })) if who == session => {
                    break ended;
                }
                Ok(_) | Err(_) => {}
            }
        };
        (Run { ended, log: self.log(session) }, told)
    }

    /// Waits for a job to end, saying how long its outputs took to upload (from the runner's `uploading` to its end).
    async fn finish_timed(
        &self,
        session: &str,
        mut events: Receiver<RunnerEvent>,
        within: Duration,
    ) -> (Run, Duration) {
        let deadline = Instant::now() + within;
        let mut uploading = None;
        let (ended, took) = loop {
            let left = deadline.saturating_duration_since(Instant::now());
            assert!(!left.is_zero(), "the job didn't end in {within:?}");
            match tokio::time::timeout(left, events.recv()).await {
                Ok(Ok(RunnerEvent { session: who, event: Event::State { phase: Phase::Uploading }, .. }))
                    if who == session =>
                {
                    uploading = Some(Instant::now());
                }
                Ok(Ok(RunnerEvent { session: who, event: Event::Exited { ended }, .. })) if who == session => {
                    break (ended, uploading.map(|began| began.elapsed()).unwrap_or_default());
                }
                Ok(_) | Err(_) => {}
            }
        };
        (Run { ended, log: self.log(session) }, took)
    }

    fn runner_pid(&self) -> String {
        self.inside(&["pgrep", "-x", "idleo-runner"]).trim().to_owned()
    }

    fn container_address(&self, session: &str) -> String {
        let name = format!("idleo-{session}-1");
        self.inside(&[
            "nerdctl",
            "--namespace",
            "idleo",
            "inspect",
            "--format",
            "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
            &name,
        ])
        .trim()
        .to_owned()
    }
}

/// Prints the row the table is made from, then fails the test if the attack got through.
fn report(number: u32, name: &str, held: bool, detail: &str) {
    println!("HOSTILE {number:02} {name}: {} {detail}", if held { "PASS" } else { "FAIL" });
    assert!(held, "{name}: {detail}");
}

/// Checks the attack ran to its verdict and the sandbox held.
fn expect_held(number: u32, name: &str, attack: &str, run: &Run, ended_ok: bool) {
    let verdict = run.verdict(attack);
    let (held, detail) = match (&verdict, ended_ok) {
        (Some((true, detail)), true) => (true, detail.clone()),
        (Some((true, detail)), false) => (false, format!("held, but the job ended {:?}: {detail}", run.ended)),
        (Some((false, detail)), _) => (false, format!("BREACH: {detail}")),
        (None, _) => (false, format!("the attack gave no verdict (ended {:?}); log:\n{}", run.ended, run.log)),
    };
    report(number, name, held, &detail);
}

fn succeeded(run: &Run) -> bool {
    run.ended == Ended::Succeeded
}

macro_rules! hostile {
    ($(#[$meta:meta])* $name:ident, $body:expr) => {
        $(#[$meta])*
        #[tokio::test]
        #[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
        async fn $name() {
            let harness = Harness::new().await;
            let run: fn(Harness) -> std::pin::Pin<Box<dyn std::future::Future<Output = ()>>> = $body;
            run(harness).await;
        }
    };
}

hostile!(
    /// 1. Write to `/`, `/usr` or `/etc`.
    h01_write_to_the_root_filesystem,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("write_root")).await;
        expect_held(1, "write to /, /usr or /etc", "write_root", &run, succeeded(&run));
    })
);

hostile!(
    /// 2. Become root: setuid binaries, `su`, `unshare -r`, `mount`, loading a module.
    h02_become_root,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("become_root")).await;
        expect_held(2, "become root", "become_root", &run, succeeded(&run));
    })
);

hostile!(
    /// 3. Use raw sockets or change network settings (`ip link`, `iptables`).
    h03_raw_sockets_and_network_settings,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("raw_net")).await;
        expect_held(3, "raw sockets and network settings", "raw_net", &run, succeeded(&run));
    })
);

hostile!(
    /// 4. Reach each private range, link-local, the gateway, the distro's own addresses, and other sessions' networks.
    h04_reach_the_home_network,
    |h| Box::pin(async move {
        // What a job must not reach and could only know by being on this machine: the distribution's own addresses and its
        // gateway, and every session network's bridge and first container address.
        let trie = h.inside(&["cat", "/proc/net/fib_trie"]);
        let route = h.inside(&["cat", "/proc/net/route"]);
        let mut targets: Vec<String> =
            sandbox::own_addresses(&trie).iter().filter(|address| !address.is_loopback()).map(ToString::to_string).collect();
        targets.extend(sandbox::default_gateway(&route).map(|gateway| gateway.to_string()));
        for slot in 0..32 {
            targets.push(format!("172.30.{slot}.1"));
            targets.push(format!("172.30.{slot}.2"));
        }
        let mut job = Job::new("reach_private");
        job.env.insert("SESSION_PARAM_TARGETS".to_owned(), targets.join(","));
        job.max_runtime_secs = 170;
        let run = h.run_unthrottled(&job).await;
        expect_held(4, "reach private ranges, this machine, other sessions' networks and every port but DNS on the gateway", "reach_private", &run, succeeded(&run));
    })
);

hostile!(
    /// 5. Reach anything except TCP 80/443 and DNS to the named resolvers (SMTP 25, SSH 22 to a public address).
    h05_reach_anything_but_the_web_and_dns,
    |h| Box::pin(async move {
        let run = h.run_unthrottled(&Job::new("reach_ports")).await;
        expect_held(5, "reach anything but TCP 80/443 and DNS through the runner's forwarder", "reach_ports", &run, succeeded(&run));
        assert!(run.log.contains("CONTROL https open"), "the positive control: HTTPS to a public address works\n{}", run.log);
    })
);

hostile!(
    /// 6. IPv6 anything.
    h06_ipv6,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("ipv6")).await;
        expect_held(6, "IPv6", "ipv6", &run, succeeded(&run));
    })
);

hostile!(
    /// 7. A fork bomb: PIDs stop at the limit, and the runner and the distro stay healthy.
    h07_fork_bomb,
    |h| Box::pin(async move {
        let pid = h.runner_pid();
        let mut job = Job::new("fork_bomb");
        job.pids_limit = 64;
        job.env.insert("SESSION_PARAM_PIDS_LIMIT".to_owned(), "64".to_owned());
        let run = h.run(&job).await;
        expect_held(7, "fork bomb", "fork_bomb", &run, succeeded(&run));
        h.assert_healthy(&pid).await;
    })
);

hostile!(
    /// 8. A memory bomb: only the container dies, reported as `out_of_memory`.
    h08_memory_bomb,
    |h| Box::pin(async move {
        let pid = h.runner_pid();
        let mut job = Job::new("memory_bomb");
        job.memory_mb = 256;
        let run = h.run(&job).await;
        let reported = matches!(&run.ended, Ended::Failed { code, .. } if code == code::OUT_OF_MEMORY);
        report(8, "memory bomb", reported, &format!("ended {:?}", run.ended));
        h.assert_healthy(&pid).await;
    })
);

hostile!(
    /// 9. Fill the disk: stops at `scratch_mb` with `scratch_full`, and the distro's free space is unchanged.
    h09_fill_the_disk,
    |h| Box::pin(async move {
        let free = |h: &Harness| h.inside(&["df", "--output=avail", "-B1", "/var/lib/idleo"]).lines().last().unwrap_or("0").trim().parse::<u64>().unwrap_or(0);
        let before = free(&h);
        let pid = h.runner_pid();
        let mut job = Job::new("fill_disk");
        job.scratch_mb = 64;
        let run = h.run(&job).await;
        let reported = matches!(&run.ended, Ended::Failed { code, .. } if code == code::SCRATCH_FULL);
        let wrote = run.log.lines().find(|l| l.starts_with("RESULT fill_disk HELD wrote ")).unwrap_or("");
        h.assert_healthy(&pid).await;
        let after = free(&h);
        let kept = before.saturating_sub(after) < 4 * 1024 * 1024;
        report(9, "fill the disk", reported && kept && !wrote.is_empty(), &format!("ended {:?}; {wrote}; distro free space {before} -> {after}", run.ended));
    })
);

hostile!(
    /// 10. See the grants: the environment, `/proc`, every readable file, and the runner's socket.
    h10_see_the_storage_grants,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("see_grants")).await;
        expect_held(10, "see the storage grants or the runner's socket", "see_grants", &run, succeeded(&run));
    })
);

hostile!(
    /// 11. See another session's scratch, processes or network: two sessions at once.
    h11_see_another_session,
    |h| Box::pin(async move {
        let marker = "VICTIM-MARKER-7f3a9c";
        let mut victim = Job::new("see_other_session");
        victim.env.insert("SESSION_PARAM_ROLE".to_owned(), "victim".to_owned());
        victim.env.insert("SESSION_PARAM_OTHER_MARKER".to_owned(), marker.to_owned());
        victim.env.insert("SESSION_PARAM_HOLD".to_owned(), "60".to_owned());
        let (victim_session, victim_events) = h.begin(&victim).await;
        // Wait until the victim has written its secret into its own scratch disk, as seen from inside the distribution.
        for _ in 0..120 {
            if !h.inside(&["sh", "-c", "grep -rl VICTIM-MARKER /var/lib/idleo/mnt 2>/dev/null | head -1"]).trim().is_empty() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
        }
        let address = h.container_address(&victim_session);
        let mut attacker = Job::new("see_other_session");
        let (first, second) = marker.split_at(6);
        attacker.env.insert("SESSION_PARAM_MARKER_A".to_owned(), first.to_owned());
        attacker.env.insert("SESSION_PARAM_MARKER_B".to_owned(), second.to_owned());
        attacker.env.insert("SESSION_PARAM_VICTIM_ADDRESS".to_owned(), address.clone());
        let run = h.run(&attacker).await;
        // The victim's own scratch holds the marker the whole time: it was alive when the attacker looked.
        let alive = h.inside(&["sh", "-c", "grep -rl VICTIM-MARKER /var/lib/idleo/mnt 2>/dev/null | head -1"]);
        if alive.trim().is_empty() {
            println!("the victim's state: {:?}; its log: {}", h.link.request(Request::List).await, h.log(&victim_session));
        }
        let _ = h.link.request(Request::Stop { session: victim_session.clone(), attempt: 1, grace_secs: 0 }).await;
        let _ = h.finish(&victim_session, victim_events, Duration::from_secs(60)).await;
        assert!(!address.is_empty() && !alive.trim().is_empty(), "the victim wasn't there to be attacked (address {address:?}, marker file {alive:?})");
        expect_held(11, "see another session's scratch, processes or network", "see_other_session", &run, succeeded(&run));
    })
);

hostile!(
    /// 12. Outlive the session: a background process, and the scratch disk, are gone after the session ends.
    h12_outlive_the_session,
    |h| Box::pin(async move {
        let pid = h.runner_pid();
        let run = h.run(&Job::new("outlive_session")).await;
        tokio::time::sleep(Duration::from_secs(2)).await;
        let survivors = h.inside(&["sh", "-c", "pgrep -af idleo-hostile-sleeper | grep -v pgrep || true"]);
        let scratch = h.inside(&["sh", "-c", "find /var/lib/idleo/mnt /var/lib/idleo/scratch -mindepth 1 2>/dev/null; losetup -a | grep /var/lib/idleo/scratch; ls /dev/mapper | grep '^idleo-' || true"]);
        let started = run.log.contains("STARTED-BACKGROUND");
        h.assert_healthy(&pid).await;
        // And the runner's own check, which the laptop's self-test runs: nothing is left that no running session holds.
        let leftovers = match h.link.request(Request::SelfCheck { cuda_image: None, quick: true }).await {
            Ok(Answer::SelfCheck { checks }) => checks.into_iter().find(|check| check.name == "leftovers"),
            other => panic!("the self-check didn't answer: {other:?}"),
        };
        let clean = leftovers.as_ref().is_some_and(|check| check.status == CheckStatus::Passed);
        report(
            12,
            "outlive the session",
            started && survivors.trim().is_empty() && scratch.trim().is_empty() && clean,
            &format!(
                "started: {started}; processes left: {:?}; scratch left: {:?}; the runner's leftovers check: {}",
                survivors.trim(),
                scratch.trim(),
                leftovers.map_or_else(|| "missing".to_owned(), |check| format!("{:?} {}", check.status, check.detail))
            ),
        );
    })
);

hostile!(
    /// 13. Write into the read-only inputs.
    h13_write_into_the_inputs,
    |h| Box::pin(async move {
        let mut job = Job::new("write_inputs");
        job.inputs = vec![("hello.txt".to_owned(), b"hello from the renter\n".to_vec())];
        let run = h.run(&job).await;
        assert!(run.log.contains("CONTROL read 'hello from the renter\\n'"), "the input wasn't staged: {}", run.log);
        expect_held(13, "write into the read-only inputs", "write_inputs", &run, succeeded(&run));
    })
);

hostile!(
    /// 14. Run past `max_runtime`: stopped with `timed_out`.
    h14_run_past_the_maximum_runtime,
    |h| Box::pin(async move {
        let pid = h.runner_pid();
        let mut job = Job::new("run_forever");
        job.max_runtime_secs = 8;
        let started = Instant::now();
        let run = h.run(&job).await;
        let timed_out = matches!(&run.ended, Ended::Failed { code, .. } if code == code::TIMED_OUT);
        h.assert_healthy(&pid).await;
        report(14, "run past max_runtime", timed_out, &format!("ended {:?} after {:?}", run.ended, started.elapsed()));
    })
);

hostile!(
    /// 15. A renter's data, once the session has ended, is nowhere in the clear on the distribution's disk: its scratch disk
    /// was encrypted (ADR 0038), and closed and erased at the end. The job writes a marker it makes itself into its files and
    /// prints it; the test learns it from the job's log, and finds it nowhere in the stand-in virtual disk.
    h15_the_scratch_disk_is_encrypted_on_disk,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("leave_marker")).await;
        let marker = run
            .log
            .lines()
            .find_map(|line| line.strip_prefix("RESULT leave_marker HELD marker="))
            .map(str::trim)
            .unwrap_or_default()
            .to_owned();
        assert!(marker.starts_with("IDLEO-SCRATCH-MARKER-"), "the job didn't write a marker; log:\n{}", run.log);
        // The marker really was on the scratch disk while it was open: the test's own control. (It can't check that now; the
        // disk is gone. It checks the opposite: that it isn't in the backing disk after the session.)
        let hits = h.scan_state_disk(&marker);
        // The scan's own control: a marker left in the clear on that disk is found, so a 0 above means something.
        let control = "IDLEO-SCAN-CONTROL-".to_owned() + &uuid::Uuid::now_v7().simple().to_string();
        h.inside(&["sh", "-c", &format!("echo {control} > /var/lib/idleo/scan-control.txt; sync")]);
        let found = h.scan_state_disk(&control);
        h.inside(&["rm", "-f", "/var/lib/idleo/scan-control.txt"]);
        assert!(found >= 1, "the scan can't find a marker that is there ({found} hits)");
        report(
            15,
            "a renter's data is encrypted on the distribution's disk",
            hits == 0 && matches!(run.ended, Ended::Succeeded),
            &format!("the marker the job wrote is in the backing disk {hits} times after the session (ended {:?})", run.ended),
        );
    })
);

hostile!(
    /// 16. While a session runs, an unprivileged process in the distribution can't open its scratch volume or read its image,
    /// and the container's memory is never swapped to the Windows disk (ADR 0038).
    h16_a_running_scratch_disk_is_protected,
    |h| Box::pin(async move {
        let mut job = Job::new("leave_marker");
        job.env.insert("SESSION_PARAM_HOLD".to_owned(), "60".to_owned());
        let (session, events) = h.begin(&job).await;
        // Wait until the job has written its marker, so the scratch volume is open and holding the renter's data.
        let mut ready = false;
        for _ in 0..120 {
            if h.inside(&["sh", "-c", "find /var/lib/idleo/mnt -name MARKER-WRITTEN 2>/dev/null | head -1"]).trim().is_empty() {
                tokio::time::sleep(Duration::from_millis(500)).await;
            } else {
                ready = true;
                break;
            }
        }
        let mapper = format!("/dev/mapper/idleo-{session}-1");
        let image = format!("/var/lib/idleo/scratch/{session}-1.img");
        // The unprivileged user can't open the decrypted mapping, and can't read the image (and even root reading the image
        // sees only ciphertext, which test 15 proves).
        let (read_mapping, mapping_out) = h.inside_as_idleo(&["dd", &format!("if={mapper}"), "bs=1024", "count=1", "of=/dev/null"]);
        let (read_image, image_out) = h.inside_as_idleo(&["dd", &format!("if={image}"), "bs=1024", "count=1", "of=/dev/null"]);
        // The container's own memory cgroup never allows swap, so its memory can't be written to the unencrypted Windows swap.
        let cgroup = h.inside(&[
            "sh",
            "-c",
            &format!(
                "pid=$(nerdctl --namespace idleo inspect --format '{{{{.State.Pid}}}}' idleo-{session}-1); \
                 cg=$(sed -n 's/^0:://p' /proc/$pid/cgroup); cat /sys/fs/cgroup$cg/memory.swap.max /sys/fs/cgroup$cg/memory.swap.current"
            ),
        ]);
        // memory.swap.max is 0 (no swap allowed at all) and memory.swap.current is 0 (none used).
        let swap_off = cgroup.lines().map(str::trim).collect::<Vec<_>>() == ["0", "0"];
        let _ = h.link.request(Request::Stop { session: session.clone(), attempt: 1, grace_secs: 0 }).await;
        let _ = h.finish(&session, events, Duration::from_secs(60)).await;
        report(
            16,
            "a running session's scratch disk and memory are protected",
            ready && !read_mapping && !read_image && swap_off,
            &format!(
                "marker written: {ready}; idleo could read the mapping: {read_mapping} ({}); the image: {read_image} ({}); memory.swap.max and .current: {:?}",
                mapping_out.trim(),
                image_out.trim(),
                cgroup.replace('\n', " ")
            ),
        );
    })
);

/// Not an attack: while a session runs and writes data, nothing of it is written to the distribution's own disk in the clear
/// (ADR 0038). The distribution is watched for new files (everywhere a laptop's virtual disk holds: its root, containerd's,
/// nerdctl's and CNI's state, and the state disk), each is checked against the few places the runner and containerd write
/// metadata that names no data, and the marker the job wrote (also printed, so also in its output) is searched for in every one
/// of them, in memory-backed `/run` and `/dev/shm`, and in the whole backing disk. nerdctl's file of what the job prints must be
/// a link onto the encrypted disk, never a file of its own.
#[tokio::test]
#[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
async fn nothing_of_a_session_is_written_outside_its_encrypted_disk() {
    let h = Harness::new().await;
    h.inside(&["sh", "-c", "touch /tmp/before-session && sleep 1"]);
    let mut job = Job::new("leave_marker");
    job.env.insert("SESSION_PARAM_HOLD".to_owned(), "45".to_owned());
    let (session, events) = h.begin(&job).await;
    for _ in 0..120 {
        if !h
            .inside(&["sh", "-c", "find /var/lib/idleo/mnt -name MARKER-WRITTEN 2>/dev/null | head -1"])
            .trim()
            .is_empty()
        {
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    // The marker, as the job printed it (the runner uploads its output as it comes).
    let mut marker = String::new();
    for _ in 0..60 {
        marker = h
            .log(&session)
            .lines()
            .find_map(|line| line.strip_prefix("RESULT leave_marker HELD marker="))
            .unwrap_or_default()
            .trim()
            .to_owned();
        if !marker.is_empty() {
            break;
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    assert!(marker.starts_with("IDLEO-SCRATCH-MARKER-"), "the job's marker never arrived");
    let script = r#"
        for d in / /var/lib/containerd /var/lib/nerdctl /var/lib/cni /var/lib/idleo; do
          if [ "$d" = / ]; then opt=-xdev; else opt=; fi
          find "$d" $opt \( -path /proc -o -path /sys -o -path /dev -o -path /run -o -path /tmp -o -path /var/lib/idleo/mnt \) -prune -o -type f -newer /tmp/before-session -print 2>/dev/null
        done | sort -u > /tmp/new-files
        sed -E 's/[0-9a-f]{64}/<id>/g; s/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/<uuid>/g; s#/nerdctl/[0-9a-f]{8}/#/nerdctl/<h>/#' /tmp/new-files | sort -u | sed 's/^/NEW /'
        # The marker in every new file, and in memory-backed places.
        { cat /tmp/new-files; find /run /dev/shm -type f 2>/dev/null; } | while read -r f; do grep -aqF -- "$1" "$f" 2>/dev/null && echo "MARKER $f"; done
        # A file of what a job prints that is a file of its own, not a link, is a renter's output in the clear.
        find /var/lib/nerdctl -name '*-json.log' -type f 2>/dev/null | sed 's/^/CLEARLOG /'
        find /var/lib/nerdctl -name '*-json.log' -type l -exec readlink {} \; 2>/dev/null | sed 's/^/LINK /'
    "#;
    let seen = h.inside(&["bash", "-c", script, "watch", &marker]);
    let _ = h.link.request(Request::Stop { session: session.clone(), attempt: 1, grace_secs: 0 }).await;
    let _ = h.finish(&session, events, Duration::from_secs(60)).await;
    let allowed = [
        "/var/lib/containerd/io.containerd.metadata.v1.bolt/meta.db",
        "/var/lib/containerd/io.containerd.snapshotter.v1.overlayfs/metadata.db",
        "/var/lib/idleo/scratch/<uuid>-1.img",
        "/var/log/journal/",
    ];
    let allowed_prefixes = ["/var/lib/nerdctl/", "/var/lib/cni/"];
    let mut unexpected = Vec::new();
    for line in seen.lines() {
        if let Some(path) = line.strip_prefix("NEW ")
            && !allowed.iter().any(|ok| path == *ok || (ok.ends_with('/') && path.starts_with(ok)))
            && !allowed_prefixes.iter().any(|prefix| path.starts_with(prefix))
        {
            unexpected.push(path.to_owned());
        }
    }
    let in_clear: Vec<&str> =
        seen.lines().filter(|line| line.starts_with("MARKER ") || line.starts_with("CLEARLOG ")).collect();
    let links: Vec<&str> = seen.lines().filter_map(|line| line.strip_prefix("LINK ")).collect();
    // The raw disk too, once the session is over and its scratch disk gone.
    let on_disk = h.scan_state_disk(&marker);
    println!("WATCH new files: {}", seen.lines().filter(|line| line.starts_with("NEW ")).count());
    assert!(
        unexpected.is_empty(),
        "files written outside the encrypted disk that the runner doesn't account for: {unexpected:#?}\n{seen}"
    );
    assert!(in_clear.is_empty(), "the renter's data is in the clear outside the encrypted disk: {in_clear:#?}");
    assert!(
        links.iter().any(|link| link.starts_with("/var/lib/idleo/mnt/") && link.contains("/logs/")),
        "nerdctl's file of what the job prints isn't a link onto the encrypted disk: {links:?}"
    );
    assert_eq!(on_disk, 0, "the marker is in the backing disk {on_disk} times");
    println!("WATCH nothing of the session was written outside its encrypted disk; links: {links:?}");
}

/// Not an attack: the runner's DNS (ADR 0038). A job looks up a name nobody should mind and then two mining pools: the first
/// pool lookup is refused and told to an admin, the second stops the session, and the name nobody should mind is written down
/// nowhere (not in the runner's log, not in a counter): lookups are the renter's private business.
#[tokio::test]
#[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
async fn a_second_lookup_of_a_mining_pool_stops_the_session_and_no_other_name_is_ever_written_down() {
    use jobcore::protocol::AbuseKind;
    let h = Harness::new().await;
    h.list_for_tests(&["abuse-pool=pool.abuse-test.invalid"]);
    let private = format!("idleo-private-{}.example.com", uuid::Uuid::now_v7().simple());
    let mut job = Job::new("lookup_pools");
    job.env.insert(
        "SESSION_PARAM_DOMAINS".to_owned(),
        format!("{private},a.pool.abuse-test.invalid,b.pool.abuse-test.invalid"),
    );
    let (session, events) = h.begin(&job).await;
    let (run, told) = h.finish_collecting(&session, events, Duration::from_secs(90)).await;
    assert!(
        matches!(&run.ended, Ended::Failed { code, .. } if code == code::ABUSE_STOPPED),
        "{:?}\n{}",
        run.ended,
        run.log
    );
    assert_eq!(
        told,
        [
            (AbuseKind::PoolLookup, "pool.abuse-test.invalid".to_owned(), false),
            (AbuseKind::PoolLookup, "pool.abuse-test.invalid".to_owned(), true)
        ]
    );
    assert!(run.log.contains(&format!("LOOKUP {private} ")), "the ordinary name was looked up: {}", run.log);
    assert!(run.log.contains("LOOKUP a.pool.abuse-test.invalid gaierror"), "the pool was refused: {}", run.log);
    assert!(run.log.contains("stopped because it ran software that isn't allowed on IdleO"), "{}", run.log);
    assert!(!run.log.contains("RESULT lookup_pools"), "the second pool lookup ended it before the end");
    // Nobody wrote the ordinary name down: not the runner's log, not anything on the distribution.
    let journal = h.inside(&["sh", "-c", "journalctl -u idleo-runner --no-pager -o cat"]);
    assert!(!journal.contains(&private), "the runner's log holds a name the renter looked up");
    let anywhere =
        h.inside(&["sh", "-c", &format!("grep -rlaF '{private}' /var/log /var/lib/idleo /run 2>/dev/null | head -3")]);
    assert_eq!(anywhere.trim(), "", "a name the renter looked up is written down at {anywhere}");
    println!("WATCH dns: stopped on the second pool lookup; {private} written down nowhere");
}

/// Not an attack: a program called by a listed name is flagged and the job goes on; a program whose executable is a listed
/// build is stopped whatever it is called.
#[tokio::test]
#[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
async fn a_listed_name_flags_and_a_listed_build_stops_whatever_it_is_called() {
    use jobcore::protocol::AbuseKind;
    let h = Harness::new().await;
    // The image is in the distribution once a session has used it.
    h.run(&Job::new("write_root")).await;
    let sleep_hash = h
        .inside(&[
            "nerdctl",
            "--namespace",
            "idleo",
            "run",
            "--rm",
            "--pull",
            "never",
            "--network",
            "none",
            "--entrypoint",
            "sha256sum",
            &h.image,
            "/bin/sleep",
        ])
        .split_whitespace()
        .find(|word| word.len() == 64 && word.bytes().all(|b| b.is_ascii_hexdigit()))
        .expect("the image's sleep has a hash")
        .to_owned();
    h.list_for_tests(&["abuse-name=idleo-testminer", &format!("abuse-sha256={sleep_hash}")]);

    // By name: told, not stopped. (Its executable is the same `sleep`, so the listed build would stop it: the name is listed
    // alone in the first run, with the build's hash held back, to show a name alone stops no one.)
    h.list_for_tests(&["abuse-name=idleo-testminer"]);
    let mut named = Job::new("run_named");
    named.env.insert("SESSION_PARAM_NAME".to_owned(), "idleo-testminer".to_owned());
    let (session, mut events) = h.begin(&named).await;
    let mut told = Vec::new();
    let waited = Instant::now();
    while told.is_empty() && waited.elapsed() < Duration::from_secs(40) {
        if let Ok(Ok(RunnerEvent { session: who, event: Event::Abuse { kind, detail, stopped, .. }, .. })) =
            tokio::time::timeout(Duration::from_millis(500), events.recv()).await
            && who == session
        {
            told.push((kind, detail, stopped));
        }
    }
    assert_eq!(told, [(AbuseKind::ProcessName, "idleo-testminer".to_owned(), false)]);
    let still_running = h.link.request(Request::List).await;
    assert!(
        matches!(&still_running, Ok(Answer::List { sessions }) if sessions.iter().any(|s| s.session == session && s.ended.is_none())),
        "a name alone stops no one: {still_running:?}"
    );
    let _ = h.link.request(Request::Stop { session: session.clone(), attempt: 1, grace_secs: 0 }).await;
    let _ = h.finish(&session, events, Duration::from_secs(60)).await;

    // By build: the same program under an innocent name, and its hash listed.
    h.list_for_tests(&[&format!("abuse-sha256={sleep_hash}")]);
    let mut innocent = Job::new("run_named");
    innocent.env.insert("SESSION_PARAM_NAME".to_owned(), "train-model".to_owned());
    let (session, events) = h.begin(&innocent).await;
    let (run, told) = h.finish_collecting(&session, events, Duration::from_secs(60)).await;
    assert!(
        matches!(&run.ended, Ended::Failed { code, .. } if code == code::ABUSE_STOPPED),
        "{:?}\n{}",
        run.ended,
        run.log
    );
    assert_eq!(told, [(AbuseKind::ProcessHash, "a test build".to_owned(), true)]);
    println!(
        "WATCH abuse: a listed name told and stopped no one; a listed build under another name stopped the session"
    );
}

/// What a flood job printed: the number after `name=` in its verdict line.
fn number_after(run: &Run, attack: &str, name: &str) -> f64 {
    let line = run.verdict(attack).map(|(_, detail)| detail).unwrap_or_default();
    line.split_whitespace()
        .find_map(|word| word.strip_prefix(&format!("{name}=")))
        .and_then(|number| number.trim_end_matches('s').parse().ok())
        .unwrap_or_else(|| panic!("no {name} in the verdict of {attack}: {:?}\n{}", run.verdict(attack), run.log))
}

/// Not an attack: fair use of the owner's home internet (ADR 0038). A job that tries to open 1,000 connections a second for
/// five seconds, as a scan or a flood from someone's home would, is held to 50 a second with a burst of 100, and the rules
/// count what they dropped.
#[tokio::test]
#[ignore = "needs the development distribution and the internet: agent/runner/tests/hostile/run.sh"]
async fn a_job_cant_open_connections_faster_than_the_limit() {
    let h = Harness::new().await;
    let mut job = Job::new("connection_flood");
    job.max_runtime_secs = 90;
    let (session, events) = h.begin(&job).await;
    let slot = h.network_of(&session).await;
    let run = h.finish(&session, events, Duration::from_secs(90)).await;
    let (attempted, completed, seconds) = (
        number_after(&run, "connection_flood", "attempted"),
        number_after(&run, "connection_flood", "completed"),
        number_after(&run, "connection_flood", "in"),
    );
    let drops = h.fair_drops(slot);
    // 50 a second for the time it ran, and the burst of 100 ahead of that, with a little room for the second it takes the last
    // connections to finish.
    let allowed = 50.0 * (seconds + 1.0) + 100.0;
    println!(
        "FAIR connections: attempted {attempted}, completed {completed} (at most {allowed:.0}), the rules dropped {} new-connection packets",
        drops.connections
    );
    assert!(attempted >= 4000.0, "the job didn't try hard enough to mean anything: {attempted}");
    assert!(completed <= allowed, "{completed} connections completed, more than 50 a second allows ({allowed:.0})");
    assert!(completed > 0.0, "an honest connection must still get through");
    assert!(drops.connections >= 1000, "the rules counted only {} dropped connections", drops.connections);
}

/// Not an attack: a job that uploads as fast as it can for 20 seconds is held to its cap (10 Mbit/s here), within 30%: the
/// token bucket lets a quarter of a second through ahead of the rate, and TCP slows to fit what it drops.
#[tokio::test]
#[ignore = "needs the development distribution and the internet: agent/runner/tests/hostile/run.sh"]
async fn a_job_cant_use_more_of_the_upload_than_its_cap() {
    let h = Harness::new().await;
    let mut job = Job::new("upload_flood");
    job.max_runtime_secs = 120;
    job.upload_cap_kbps = 10_000;
    let (session, events) = h.begin(&job).await;
    let slot = h.network_of(&session).await;
    let run = h.finish(&session, events, Duration::from_secs(120)).await;
    let kbps = number_after(&run, "upload_flood", "kbps");
    let drops = h.fair_drops(slot);
    println!(
        "FAIR upload: {kbps:.0} kbit/s achieved of a cap of 10000 ({:.0}%), the rules dropped {} packets ({} bytes)",
        kbps / 100.0,
        drops.upload_packets,
        drops.upload_bytes
    );
    assert!(kbps <= 13_000.0, "the job uploaded at {kbps} kbit/s, over its cap of 10000");
    assert!(kbps >= 7_000.0, "the job got only {kbps} kbit/s of its 10000: the cap is too hard on honest uploads");
    assert!(drops.upload_packets > 0, "nothing was dropped, so the cap wasn't what held it");
}

/// Not an attack: the runner's own uploads of a session's outputs start in the distribution, never from a session's network,
/// so a session's cap does not hold them. While one job floods its upload under a cap of 5 Mbit/s (which would take 160 s to
/// move 100 MB), another job's 100 MB of outputs reach storage as fast as without the flood.
#[tokio::test]
#[ignore = "needs the development distribution and the internet: agent/runner/tests/hostile/run.sh"]
async fn the_runners_own_uploads_are_not_held_by_a_sessions_cap() {
    let h = Harness::new().await;
    let output = |h: &Harness| {
        let mut job = Job::new("write_output");
        job.scratch_mb = 400;
        job.upload_cap_kbps = 5_000;
        job.env.insert("SESSION_PARAM_MB".to_owned(), "100".to_owned());
        let _ = h;
        job
    };
    let (session, events) = h.begin(&output(&h)).await;
    let (alone_run, alone) = h.finish_timed(&session, events, Duration::from_secs(180)).await;
    assert_eq!(alone_run.ended, Ended::Succeeded, "{}", alone_run.log);

    let mut flood = Job::new("upload_flood");
    flood.max_runtime_secs = 120;
    flood.upload_cap_kbps = 5_000;
    let (flooding, flood_events) = h.begin(&flood).await;
    let slot = h.network_of(&flooding).await;
    tokio::time::sleep(Duration::from_secs(4)).await;
    let (session, events) = h.begin(&output(&h)).await;
    let (busy_run, busy) = h.finish_timed(&session, events, Duration::from_secs(180)).await;
    assert_eq!(busy_run.ended, Ended::Succeeded, "{}", busy_run.log);
    let still_flooding = h.fair_drops(slot).upload_packets;
    let _ = h.link.request(Request::Stop { session: flooding.clone(), attempt: 1, grace_secs: 0 }).await;
    let _ = h.finish(&flooding, flood_events, Duration::from_secs(60)).await;
    println!(
        "FAIR runner upload: 100 MB of outputs took {alone:?} alone and {busy:?} beside a job flooding its own upload (capped at 5 Mbit/s, which has dropped {still_flooding} packets)"
    );
    assert!(still_flooding > 0, "the flood wasn't being held while the outputs went up");
    assert!(
        busy < Duration::from_secs(20),
        "100 MB took {busy:?}: the runner's upload looks held by the session's cap (160 s)"
    );
    assert!(busy < alone * 3 + Duration::from_secs(2), "{busy:?} beside the flood against {alone:?} alone");
}

/// Not an attack: the number that matters, how long from "Start" to the job's first line, with the image already here.
#[tokio::test]
#[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
async fn timing_start_to_first_log_line() {
    let h = Harness::new().await;
    let _ = Arc::new(());
    // Once to make sure the image is here, then the measured ones.
    h.run(&Job::new("write_root")).await;
    for round in 1..=3 {
        let job = Job::new("write_root");
        let started = Instant::now();
        let (session, events) = h.begin(&job).await;
        let mut first_chunk = None;
        let mut events = events;
        let deadline = started + Duration::from_secs(120);
        let ended = loop {
            if first_chunk.is_none() && !h.log(&session).is_empty() {
                first_chunk = Some(started.elapsed());
            }
            match tokio::time::timeout(Duration::from_millis(50), events.recv()).await {
                Ok(Ok(RunnerEvent { session: who, event: Event::Exited { ended }, .. })) if who == session => {
                    break ended;
                }
                _ => assert!(Instant::now() < deadline, "the job didn't end"),
            }
        };
        let log_line = h.inside(&[
            "sh",
            "-c",
            &format!("journalctl -u idleo-runner --no-pager -o cat | grep {session} | tail -2"),
        ]);
        println!(
            "TIMING round {round}: ended {ended:?}; first log chunk in storage after {:?}\n{log_line}",
            first_chunk.unwrap_or_default()
        );
    }
}

async fn rules_loaded(h: &Harness) -> bool {
    matches!(h.link.request(Request::Hello).await, Ok(Answer::Hello(hello)) if hello.rules_loaded)
}

/// Not an attack: someone with a shell in the distribution changes the sandbox's rules by hand, leaving the comment that names
/// their version as it was. The runner reads the loaded tables back and compares them with what its own rules make, so each of
/// these is found, the rules are loaded again once, and the runner answers that they're loaded; and the rules are what the file
/// says again, not what was left.
#[tokio::test]
#[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
async fn rules_changed_by_hand_are_found_and_loaded_again() {
    let h = Harness::new().await;
    // The rules that matter, as they're loaded: the session chain and the set of blocked ranges.
    let chain = || {
        format!(
            "{}{}",
            h.inside(&["nft", "list", "chain", "inet", "idleo", "session"]),
            h.inside(&["nft", "list", "set", "inet", "idleo", "blocked_v4"])
        )
    };
    let original = chain();
    assert!(original.contains("tcp dport { 80, 443 } accept"), "the rules aren't the ones this test knows: {original}");
    // Each change is made, the runner is asked, and the rules must be the file's again.
    let tamperings: [(&str, Vec<String>); 4] = [
        (
            "a rule's port added",
            vec!["sh".into(), "-c".into(), "h=$(nft -a list chain inet idleo session | sed -n 's/.*tcp dport { 80, 443 } accept # handle //p'); nft replace rule inet idleo session handle $h tcp dport { 80, 443, 25 } accept".into()],
        ),
        (
            "the final drop deleted",
            vec!["sh".into(), "-c".into(), "h=$(nft -a list chain inet idleo session | awk '/^\\t\\tdrop # handle/ {print $NF}' | tail -1); nft delete rule inet idleo session handle $h".into()],
        ),
        ("a blocked range removed", vec!["nft".into(), "delete".into(), "element".into(), "inet".into(), "idleo".into(), "blocked_v4".into(), "{ 192.168.0.0/16 }".into()]),
        ("the whole table deleted", vec!["nft".into(), "delete".into(), "table".into(), "inet".into(), "idleo".into()]),
    ];
    for (what, command) in tamperings {
        let args: Vec<&str> = command.iter().map(String::as_str).collect();
        let done = h.inside(&args);
        let changed = chain();
        assert_ne!(changed, original, "{what}: the change wasn't made ({done})");
        assert!(rules_loaded(&h).await, "{what}: the runner didn't load the rules again and say they're loaded");
        assert_eq!(chain(), original, "{what}: the rules aren't the file's again");
        println!("RULES-CHECK {what}: found, the rules loaded again, and they're the file's");
    }
}

async fn leftovers_check(h: &Harness) -> jobcore::protocol::Check {
    match h.link.request(Request::SelfCheck { cuda_image: None, quick: true }).await {
        Ok(Answer::SelfCheck { checks }) => {
            checks.into_iter().find(|check| check.name == "leftovers").expect("the self-check has a leftovers check")
        }
        other => panic!("the self-check didn't answer: {other:?}"),
    }
}

/// Not an attack: the runner's `leftovers` check, which the laptop's self-test runs and a development runner says in each session's
/// log, finds what a session could leave (a scratch file, a mount, a network address lease) and passes once it's gone.
#[tokio::test]
#[ignore = "needs the development distribution: agent/runner/tests/hostile/run.sh"]
async fn the_leftovers_check_finds_what_is_left_and_passes_once_it_is_gone() {
    let h = Harness::new().await;
    let clean = leftovers_check(&h).await;
    assert_eq!(clean.status, CheckStatus::Passed, "{}", clean.detail);

    h.inside(&[
        "sh",
        "-c",
        "mkdir -p /var/lib/idleo/scratch /var/lib/idleo/mnt/stray && touch /var/lib/idleo/scratch/stray.img \
         && mount -t tmpfs tmpfs /var/lib/idleo/mnt/stray && mkdir -p /var/lib/cni/networks/idleo-slot-30 \
         && touch /var/lib/cni/networks/idleo-slot-30/10.30.30.30",
    ]);
    let left = leftovers_check(&h).await;
    h.inside(&[
        "sh",
        "-c",
        "rm -f /var/lib/cni/networks/idleo-slot-30/10.30.30.30; umount /var/lib/idleo/mnt/stray; \
         rm -rf /var/lib/idleo/mnt/stray /var/lib/idleo/scratch/stray.img",
    ]);
    assert_eq!(left.status, CheckStatus::Failed, "{}", left.detail);
    for found in ["1 mount", "2 scratch files", "1 network address lease"] {
        assert!(left.detail.contains(found), "{found} not in: {}", left.detail);
    }
    let again = leftovers_check(&h).await;
    assert_eq!(again.status, CheckStatus::Passed, "{}", again.detail);
    println!("LEFTOVERS-CHECK found [{}]; clean again: {}", left.detail, again.detail);
}
