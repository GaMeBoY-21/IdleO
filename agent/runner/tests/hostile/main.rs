//! The hostile tests (ADR 0035, §7): a job that tries to break out of its sandbox, 14 ways, each a named test run against
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

use idleo_runner::sandbox;
use jobcore::fakes3::FakeS3;
use jobcore::link::{Link, RunnerEvent};
use jobcore::protocol::{AgentNotes, Answer, Ended, Event, GrantWire, Grants, InputFile, Request, Spec, Start, code};
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

    fn inside(&self, args: &[&str]) -> String {
        inside(&self.distro, args)
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
        let run = h.run(&job).await;
        expect_held(4, "reach private ranges, the gateway, this machine and other sessions' networks", "reach_private", &run, succeeded(&run));
    })
);

hostile!(
    /// 5. Reach anything except TCP 80/443 and DNS to the named resolvers (SMTP 25, SSH 22 to a public address).
    h05_reach_anything_but_the_web_and_dns,
    |h| Box::pin(async move {
        let run = h.run(&Job::new("reach_ports")).await;
        expect_held(5, "reach anything but TCP 80/443 and DNS to the named resolvers", "reach_ports", &run, succeeded(&run));
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
        let scratch = h.inside(&["sh", "-c", "find /var/lib/idleo/mnt /var/lib/idleo/scratch -mindepth 1 2>/dev/null; losetup -a | grep idleo || true"]);
        let started = run.log.contains("STARTED-BACKGROUND");
        h.assert_healthy(&pid).await;
        report(
            12,
            "outlive the session",
            started && survivors.trim().is_empty() && scratch.trim().is_empty(),
            &format!("started: {started}; processes left: {:?}; scratch left: {:?}", survivors.trim(), scratch.trim()),
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
