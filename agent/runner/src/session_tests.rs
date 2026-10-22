//! The life of an attempt (session.rs), against a machine whose containers the test controls.

use std::collections::BTreeMap;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use jobcore::manifest::Manifest;
use jobcore::protocol::{
    AbuseKind, AgentNotes, Body, Ended, Event, Frame, GrantWire, Grants, InputFile, Phase, ResumeFrom, Spec, Start,
    code,
};
use tokio::sync::broadcast;

use crate::abuse::Blocklist;
use crate::machine::{StartError, Stream};
use crate::session::Registry;
use crate::testing::{FakeMachine, MemoryStorage, OnTerm, TokioClock};

const SESSION: &str = "0190f3a2-0000-7000-8000-000000000000";
const DIGEST: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const TICK: Duration = Duration::from_millis(10);

fn grant(prefixes: &[&str], writable: bool) -> GrantWire {
    GrantWire {
        endpoint: "http://storage.invalid".to_owned(),
        bucket: "idleo-test".to_owned(),
        access_key_id: "TESTKEY".to_owned(),
        secret_access_key: "test-secret".to_owned(),
        session_token: "test-token".to_owned(),
        prefixes: prefixes.iter().map(|p| (*p).to_owned()).collect(),
        writable,
        expires_in_secs: 3600,
    }
}

fn prefix(attempt: i32) -> String {
    format!("sessions/{SESSION}/attempt-{attempt}/")
}

pub(crate) fn sample_start(attempt: i32) -> Start {
    start(attempt)
}

fn start(attempt: i32) -> Start {
    Start {
        session: SESSION.to_owned(),
        attempt,
        spec: Spec {
            image: format!("registry.example/job@sha256:{DIGEST}"),
            args: vec![],
            env: BTreeMap::new(),
            memory_mb: 1024,
            cpu_millicores: 1000,
            pids_limit: 64,
            scratch_mb: 64,
            max_runtime_secs: 60,
            checkpoint_interval_secs: 0,
            gpu: false,
        },
        attempt_prefix: prefix(attempt),
        grants: Grants { attempt: grant(&[&prefix(attempt)], true), read: None },
        resume_from: None,
        inputs: vec![],
        lease_ttl_secs: 60,
        agent: AgentNotes { minute_index_base: 0, gpus: vec!["GPU-1".to_owned()] },
        upload_cap_kbps: 0,
    }
}

struct Rig {
    registry: Arc<Registry>,
    machine: Arc<FakeMachine>,
    storage: Arc<MemoryStorage>,
    events: broadcast::Receiver<String>,
}

fn rig() -> Rig {
    let machine = FakeMachine::new();
    let storage = MemoryStorage::new();
    let registry = Registry::with_tick(
        Arc::clone(&machine) as Arc<dyn crate::machine::Machine>,
        Arc::clone(&storage) as Arc<dyn crate::storage::Storage>,
        TokioClock::new(),
        TICK,
    );
    let events = registry.subscribe();
    Rig { registry, machine, storage, events }
}

impl Rig {
    async fn ended(&self, attempt: i32) -> Ended {
        for _ in 0..1500 {
            if let Some(ended) = self.registry.list().into_iter().find(|s| s.attempt == attempt).and_then(|s| s.ended) {
                return ended;
            }
            tokio::time::sleep(TICK).await;
        }
        panic!("the attempt never ended: {:?}", self.registry.list());
    }

    fn events(&mut self) -> Vec<Event> {
        let mut all = Vec::new();
        while let Ok(line) = self.events.try_recv() {
            if let Body::Event { event, .. } = Frame::decode(&line).unwrap().body {
                all.push(event);
            }
        }
        all
    }

    fn log_text(&self, attempt: i32) -> String {
        self.storage
            .under(&format!("{}logs/", prefix(attempt)))
            .values()
            .map(|b| String::from_utf8_lossy(b).into_owned())
            .collect()
    }
}

fn failed_code(ended: &Ended) -> &str {
    match ended {
        Ended::Failed { code, .. } => code,
        other => panic!("not a failure: {other:?}"),
    }
}

#[tokio::test]
async fn a_job_that_exits_zero_uploads_its_outputs_flushes_its_logs_and_leaves_nothing_behind() {
    let mut rig = rig();
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;
    let work = rig.machine.scratch_of(&format!("{SESSION}-1")).join("work");
    std::fs::create_dir_all(work.join("outputs/sub")).unwrap();
    std::fs::write(work.join("outputs/result.json"), b"{\"ok\":true}").unwrap();
    std::fs::write(work.join("outputs/sub/log.txt"), b"hello").unwrap();
    container.say(Stream::Stdout, "epoch 1 loss 2.3\nepoch 2 lo").await;
    container.say(Stream::Stderr, "a warning\n").await;
    container.say(Stream::Stdout, "ss 1.9\n").await;
    container.finish(0);

    assert_eq!(rig.ended(1).await, Ended::Succeeded);

    let outputs = rig.storage.under(&format!("{}outputs/", prefix(1)));
    assert_eq!(outputs[&format!("{}outputs/result.json", prefix(1))], b"{\"ok\":true}");
    let manifest = Manifest::from_slice(&outputs[&format!("{}outputs/_COMPLETE", prefix(1))]).unwrap();
    assert_eq!(manifest.files.len(), 2);
    assert!(manifest.files.iter().all(|file| file.sha256.is_some()));
    let log = rig.log_text(1);
    assert!(
        log.contains("epoch 1 loss 2.3\n") && log.contains("a warning\n") && log.contains("epoch 2 loss 1.9\n"),
        "{log}"
    );
    assert!(container.removed.load(Ordering::SeqCst), "the container is deleted");
    assert_eq!(*rig.machine.scratches_removed.lock().unwrap(), [format!("{SESSION}-1")], "and so is the scratch disk");
    let events = rig.events();
    let phases: Vec<Phase> =
        events.iter().filter_map(|e| if let Event::State { phase } = e { Some(*phase) } else { None }).collect();
    assert_eq!(phases, [Phase::Preparing, Phase::Running, Phase::Uploading]);
    let exited = events.iter().position(|e| matches!(e, Event::Exited { .. })).unwrap();
    let flushed = events.iter().position(|e| matches!(e, Event::Logs { flushed: true, .. })).unwrap();
    assert!(flushed < exited, "the end comes after the logs are flushed: {events:?}");
    assert_eq!(rig.registry.list()[0].latest_log_seq, 1);
}

#[tokio::test]
async fn a_stop_gives_the_job_its_grace_uploads_what_it_saved_and_ends_stopped() {
    let mut rig = rig();
    *rig.machine.on_term.lock().unwrap() = OnTerm::ExitAfter(Duration::from_millis(300));
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;
    let work = rig.machine.scratch_of(&format!("{SESSION}-1")).join("work");

    rig.registry.stop(SESSION, 1, 30).unwrap();
    std::fs::create_dir_all(work.join("checkpoints/1")).unwrap();
    std::fs::write(work.join("checkpoints/1/state.json"), b"{\"step\":7}").unwrap();
    std::fs::write(work.join("checkpoints/1/_DONE"), b"").unwrap();

    assert_eq!(rig.ended(1).await, Ended::Stopped);
    assert_eq!(container.signals(), ["TERM"], "asked nicely, and it left in time");
    let folder = format!("{}checkpoints/00000001/", prefix(1));
    assert_eq!(rig.storage.get(&format!("{folder}state.json")).unwrap(), b"{\"step\":7}");
    assert!(rig.storage.get(&format!("{folder}_COMPLETE")).is_some(), "the marker is what makes it count");
    assert!(rig.events().iter().any(|e| matches!(e, Event::Checkpoint { seq: 1 })));
    assert_eq!(rig.registry.list()[0].latest_checkpoint_seq, 1);
    assert!(container.removed.load(Ordering::SeqCst));
}

#[tokio::test]
async fn a_job_that_ignores_sigterm_is_killed_when_its_grace_runs_out_and_a_grace_of_zero_kills_at_once() {
    let rig = rig();
    *rig.machine.on_term.lock().unwrap() = OnTerm::Ignore;
    rig.registry.start(start(1)).unwrap();
    let slow = rig.machine.container().await;
    rig.registry.stop(SESSION, 1, 1).unwrap();
    assert_eq!(rig.ended(1).await, Ended::Stopped);
    assert_eq!(slow.signals(), ["TERM", "KILL"]);

    let mut second = start(2);
    second.attempt = 2;
    rig.registry.start(second).unwrap();
    for _ in 0..500 {
        if rig.machine.containers.lock().unwrap().len() == 2 {
            break;
        }
        tokio::time::sleep(TICK).await;
    }
    let quick = Arc::clone(&rig.machine.containers.lock().unwrap()[1]);
    rig.registry.stop(SESSION, 2, 0).unwrap();
    assert_eq!(rig.ended(2).await, Ended::Stopped);
    assert_eq!(quick.signals(), ["KILL"], "no grace, no SIGTERM");
}

#[tokio::test]
async fn how_a_job_failed_is_told_apart_by_the_kernel_and_the_disk() {
    for (name, oom, free, expected_code, expected_exit) in [
        ("an ordinary failure", false, 10 << 30, code::JOB_FAILED, 3),
        ("memory", true, 10 << 30, code::OUT_OF_MEMORY, 137),
        ("the scratch disk", false, 100, code::SCRATCH_FULL, 1),
    ] {
        let rig = rig();
        rig.machine.scratch_free.store(free, Ordering::SeqCst);
        rig.registry.start(start(1)).unwrap();
        let container = rig.machine.container().await;
        container.oom.store(oom, Ordering::SeqCst);
        container.finish(expected_exit);

        let ended = rig.ended(1).await;

        assert_eq!(failed_code(&ended), expected_code, "{name}");
        assert!(
            matches!(&ended, Ended::Failed { exit_code: Some(code), .. } if *code == expected_exit),
            "{name}: {ended:?}"
        );
        assert!(container.removed.load(Ordering::SeqCst), "{name}: cleaned up");
        assert!(rig.storage.keys().iter().all(|k| !k.contains("outputs/")), "{name}: a failed job uploads no outputs");
    }
}

#[tokio::test]
async fn a_job_past_its_maximum_runtime_is_killed_and_ends_timed_out() {
    let rig = rig();
    let mut short = start(1);
    short.spec.max_runtime_secs = 1;
    rig.registry.start(short).unwrap();
    let container = rig.machine.container().await;

    let ended = rig.ended(1).await;

    assert_eq!(failed_code(&ended), code::TIMED_OUT);
    assert_eq!(container.signals(), ["KILL"]);
}

#[tokio::test]
async fn a_lease_nobody_renews_kills_the_job_and_one_that_is_renewed_keeps_it_running() {
    let rig = rig();
    let mut brief = start(1);
    brief.lease_ttl_secs = 1;
    rig.registry.start(brief).unwrap();
    let container = rig.machine.container().await;
    for _ in 0..4 {
        tokio::time::sleep(Duration::from_millis(400)).await;
        rig.registry.lease(SESSION, 1, 1).unwrap();
    }
    assert!(rig.registry.list()[0].ended.is_none(), "renewed, so still running");

    let ended = rig.ended(1).await;

    assert_eq!(failed_code(&ended), code::LEASE_EXPIRED, "the agent stopped renewing");
    assert_eq!(container.signals(), ["KILL"]);
    assert!(rig.storage.keys().iter().all(|k| !k.contains("checkpoints/")), "nothing is uploaded after a lease lapses");
}

/// A list with one listed build, for the tests of what the runner does about software no renter may run (ADR 0038).
fn list_with_a_build(sha256: &str) -> Arc<Blocklist> {
    Arc::new(
        Blocklist::parse(&format!(
            r#"{{"version":"t","names":[{{"name":"xmrig","kind":"miner","source":"https://example.invalid/x"}}],
               "hashes":[{{"sha256":"{sha256}","name":"a listed build","kind":"miner","source":"https://example.invalid/x"}}],
               "pools":[{{"domain":"nanopool.org","source":"https://example.invalid/x"}}]}}"#
        ))
        .unwrap(),
    )
}

fn abuse_events(events: &[Event]) -> Vec<(AbuseKind, String, bool)> {
    events
        .iter()
        .filter_map(|event| match event {
            Event::Abuse { kind, detail, stopped, .. } => Some((*kind, detail.clone(), *stopped)),
            _ => None,
        })
        .collect()
}

#[tokio::test]
async fn a_listed_build_stops_the_session_tells_the_renter_and_saves_nothing() {
    let mut rig = rig();
    let build = "e".repeat(64);
    *rig.machine.blocklist.lock().unwrap() = Some(list_with_a_build(&build));
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;
    let work = rig.machine.scratch_of(&format!("{SESSION}-1")).join("work");
    std::fs::create_dir_all(work.join("outputs")).unwrap();
    std::fs::write(work.join("outputs/result.json"), b"{}").unwrap();

    // Renamed to look innocent, but it is the listed build.
    container.show_process(900, "totally-innocent", "totally-innocent", Some(&build));
    let ended = rig.ended(1).await;

    assert_eq!(failed_code(&ended), code::ABUSE_STOPPED, "{ended:?}");
    assert_eq!(container.signals(), ["KILL"], "killed at once, no grace");
    assert_eq!(abuse_events(&rig.events()), [(AbuseKind::ProcessHash, "a listed build".to_owned(), true)]);
    assert!(rig.log_text(1).contains("stopped because it ran software that isn't allowed on IdleO"));
    assert!(rig.storage.keys().iter().all(|k| !k.contains("outputs/")), "nothing of a stopped session is saved");
    assert!(container.removed.load(Ordering::SeqCst));
}

#[tokio::test]
async fn a_listed_name_only_tells_an_admin_and_the_job_goes_on() {
    let mut rig = rig();
    *rig.machine.blocklist.lock().unwrap() = Some(list_with_a_build(&"e".repeat(64)));
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;

    container.show_process(901, "xmrig", "xmrig", None);
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(rig.registry.list()[0].ended.is_none(), "a name alone stops no one");
    // The same name again, a new process of it, is not told twice.
    container.show_process(902, "xmrig", "xmrig", None);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(abuse_events(&rig.events()), [(AbuseKind::ProcessName, "xmrig".to_owned(), false)]);

    container.finish(0);
    assert_eq!(rig.ended(1).await, Ended::Succeeded, "and it finishes like any job");
}

#[tokio::test]
async fn the_first_lookup_of_a_pool_tells_and_the_second_stops_the_session() {
    let mut rig = rig();
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;

    container.refuse_lookup("nanopool.org");
    tokio::time::sleep(Duration::from_millis(200)).await;
    assert!(rig.registry.list()[0].ended.is_none(), "one lookup stops no one");
    container.refuse_lookup("2miners.com");
    let ended = rig.ended(1).await;

    assert_eq!(failed_code(&ended), code::ABUSE_STOPPED);
    assert_eq!(
        abuse_events(&rig.events()),
        [
            (AbuseKind::PoolLookup, "nanopool.org".to_owned(), false),
            (AbuseKind::PoolLookup, "2miners.com".to_owned(), true)
        ]
    );
}

#[tokio::test]
async fn every_minute_the_runner_says_what_the_job_sent_received_and_printed_and_five_minutes_of_dropped_connections_tell()
 {
    let mut rig = rig();
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;
    // A minute is 60 ticks here: 600 ms.
    container.say(Stream::Stdout, "one\ntwo\nthree\n").await;
    container.carry(5_000, 90_000, 0);
    tokio::time::sleep(Duration::from_millis(750)).await;
    let behaviours = |events: Vec<Event>| -> Vec<(u64, u64, u64, u64)> {
        events
            .into_iter()
            .filter_map(|event| match event {
                Event::Behavior { net_out_bytes, net_in_bytes, printed_lines, conn_drops } => {
                    Some((net_out_bytes, net_in_bytes, printed_lines, conn_drops))
                }
                _ => None,
            })
            .collect()
    };
    assert_eq!(behaviours(rig.events()), [(5_000, 90_000, 3, 0)], "what the first minute carried and printed");

    // Then the minute after it, counted from the one before, with connections dropped in it and in each one that follows.
    for minute in 1..=5_u64 {
        container.carry(5_000 + 100 * minute, 90_000 + 200 * minute, 400 * minute);
        tokio::time::sleep(Duration::from_millis(600)).await;
    }
    let events = rig.events();
    let flags: Vec<_> = abuse_events(&events);
    assert_eq!(flags, [(AbuseKind::ConnectionDrops, String::new(), false)], "told once, after five minutes in a row");
    assert!(rig.registry.list()[0].ended.is_none(), "dropped connections stop no one");
    let counts = behaviours(events);
    assert!(counts.iter().all(|(out, inn, lines, _)| (*out, *inn, *lines) == (100, 200, 0)), "{counts:?}");
    container.finish(0);
    rig.ended(1).await;
}

#[tokio::test]
async fn a_start_refused_for_its_limits_is_the_hosts_and_any_other_is_the_runners() {
    let rig = rig();
    *rig.machine.start.lock().unwrap() =
        Some(StartError::SandboxNotReady("the fair-use limits of network 3 are not in place".to_owned()));
    rig.registry.start(start(1)).unwrap();
    let ended = rig.ended(1).await;
    assert_eq!(failed_code(&ended), code::SANDBOX_NOT_READY, "{ended:?}");
    assert_eq!(rig.machine.scratches_removed.lock().unwrap().len(), 1, "and its scratch disk is erased");

    *rig.machine.start.lock().unwrap() = Some(StartError::Failed("nerdctl died".to_owned()));
    rig.registry.start(start(2)).unwrap();
    assert_eq!(failed_code(&rig.ended(2).await), code::RUNNER_FAILED);
}

#[tokio::test]
async fn nothing_starts_without_the_sandbox_rules_and_a_failed_pull_is_its_own_code() {
    let rig = rig();
    *rig.machine.sandbox.lock().unwrap() = Err("the network rules aren't loaded".to_owned());
    rig.registry.start(start(1)).unwrap();
    let ended = rig.ended(1).await;
    assert_eq!(failed_code(&ended), code::SANDBOX_NOT_READY);
    assert!(rig.machine.containers.lock().unwrap().is_empty() && rig.machine.scratches_made.lock().unwrap().is_empty());

    *rig.machine.sandbox.lock().unwrap() = Ok(());
    *rig.machine.image.lock().unwrap() = Err("the registry isn't reachable".to_owned());
    rig.registry.start(start(2)).unwrap();
    assert_eq!(failed_code(&rig.ended(2).await), code::IMAGE_PULL_FAILED);

    *rig.machine.image.lock().unwrap() = Ok(());
    let mut unpinned = start(3);
    unpinned.spec.image = "ubuntu:24.04".to_owned();
    rig.registry.start(unpinned).unwrap();
    assert_eq!(failed_code(&rig.ended(3).await), code::IMAGE_PULL_FAILED);
    assert!(rig.machine.containers.lock().unwrap().is_empty());
}

#[tokio::test]
async fn inputs_are_staged_into_the_scratch_disk_and_a_checkpoint_comes_down_to_resume_from() {
    let rig = rig();
    let upload = "uploads/0190f3a2-0000-7000-8000-00000000000a/files/";
    rig.storage.put(&format!("{upload}config.json"), b"{}");
    rig.storage.put(&format!("{upload}weights/w.bin"), b"abcd");
    let checkpoints = format!("sessions/{SESSION}/attempt-1/checkpoints/");
    let folder = format!("{checkpoints}00000003/");
    rig.storage.put(&format!("{folder}state.json"), b"{\"step\":9}");
    rig.storage.put(
        &format!("{folder}_COMPLETE"),
        &Manifest::of_bodies(&[("state.json".to_owned(), b"{\"step\":9}".to_vec())]).to_bytes(),
    );
    let mut resumed = start(2);
    resumed.grants.read = Some(grant(&[upload, &folder], false));
    resumed.inputs = vec![
        InputFile {
            prefix: upload.to_owned(),
            name: "config.json".to_owned(),
            size_bytes: 2,
            slot: "model".to_owned(),
        },
        InputFile {
            prefix: upload.to_owned(),
            name: "weights/w.bin".to_owned(),
            size_bytes: 4,
            slot: "model".to_owned(),
        },
    ];
    resumed.resume_from = Some(ResumeFrom { prefix: checkpoints, seq: 3 });
    resumed.spec.env = BTreeMap::from([("SESSION_PARAM_EPOCHS".to_owned(), "3".to_owned())]);
    rig.registry.start(resumed).unwrap();

    let container = rig.machine.container().await;
    let root = rig.machine.scratch_of(&format!("{SESSION}-2"));
    assert_eq!(std::fs::read(root.join("input/model/weights/w.bin")).unwrap(), b"abcd");
    assert_eq!(std::fs::read(root.join("resume/state.json")).unwrap(), b"{\"step\":9}");
    assert_eq!(container.plan.slots, ["model"]);
    assert_eq!(container.plan.resume.as_deref(), Some(root.join("resume").as_path()));
    assert_eq!(container.plan.spec.env["SESSION_PARAM_EPOCHS"], "3");
    container.finish(0);
    assert_eq!(rig.ended(2).await, Ended::Succeeded);
}

#[tokio::test]
async fn an_input_that_cant_be_staged_or_a_checkpoint_that_isnt_whole_fails_the_attempt_before_the_job_starts() {
    let rig = rig();
    let upload = "uploads/0190f3a2-0000-7000-8000-00000000000a/files/";
    let mut missing = start(1);
    missing.grants.read = Some(grant(&[upload], false));
    missing.inputs = vec![InputFile {
        prefix: upload.to_owned(),
        name: "gone.bin".to_owned(),
        size_bytes: 4,
        slot: "data".to_owned(),
    }];
    rig.registry.start(missing).unwrap();
    assert_eq!(failed_code(&rig.ended(1).await), "input_unavailable");

    let checkpoints = format!("sessions/{SESSION}/attempt-1/checkpoints/");
    let mut resume = start(2);
    resume.grants.read = Some(grant(&[&checkpoints], false));
    resume.resume_from = Some(ResumeFrom { prefix: checkpoints, seq: 5 });
    rig.registry.start(resume).unwrap();
    assert_eq!(failed_code(&rig.ended(2).await), "resume_checkpoint_missing");

    let mut outside = start(3);
    outside.grants.read = Some(grant(&[upload], false));
    outside.inputs = vec![InputFile {
        prefix: "uploads/someone-elses/files/".to_owned(),
        name: "a".to_owned(),
        size_bytes: 1,
        slot: "data".to_owned(),
    }];
    rig.registry.start(outside).unwrap();
    assert_eq!(failed_code(&rig.ended(3).await), code::INVALID_INPUT, "an input outside the read grant");

    assert!(rig.machine.containers.lock().unwrap().is_empty());
    assert_eq!(rig.machine.scratches_removed.lock().unwrap().len(), 2, "and the scratch disks made were deleted");
}

#[tokio::test]
async fn the_ended_attempt_is_held_until_the_agent_forgets_it_and_a_repeated_start_does_nothing() {
    let rig = rig();
    rig.registry.start(start(1)).unwrap();
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;
    assert_eq!(rig.registry.forget(SESSION, 1).unwrap_err().code, code::BAD_REQUEST, "still running");
    container.finish(0);
    assert_eq!(rig.ended(1).await, Ended::Succeeded);

    assert_eq!(rig.machine.containers.lock().unwrap().len(), 1, "one container for two starts");
    let info = &rig.registry.list()[0];
    assert_eq!(
        (info.phase, info.ended.clone(), info.agent.minute_index_base),
        (Phase::Ended, Some(Ended::Succeeded), 0)
    );
    rig.registry.forget(SESSION, 1).unwrap();
    assert_eq!(rig.registry.list().len(), 0);
    assert!(rig.registry.stop(SESSION, 1, 5).is_err());
}

#[tokio::test]
async fn fresh_grants_replace_the_old_ones_while_the_job_runs() {
    let rig = rig();
    rig.registry.start(start(1)).unwrap();
    let container = rig.machine.container().await;
    let refresh = jobcore::protocol::Refresh { session: SESSION.to_owned(), attempt: 1, grants: start(1).grants };

    rig.registry.refresh(&refresh).unwrap();

    assert_eq!(rig.storage.refreshed.load(Ordering::SeqCst), 1);
    container.finish(0);
    rig.ended(1).await;
    assert!(rig.registry.refresh(&refresh).is_err(), "nothing to refresh once it has ended");
}

#[tokio::test]
async fn a_start_without_a_runtime_or_a_lease_is_refused_outright() {
    let rig = rig();
    let mut bad = start(1);
    bad.lease_ttl_secs = 0;
    assert_eq!(rig.registry.start(bad).unwrap_err().code, code::BAD_REQUEST);
    let mut bad = start(1);
    bad.spec.max_runtime_secs = 0;
    assert!(rig.registry.start(bad).is_err());
}

#[tokio::test]
async fn a_job_that_says_its_input_is_wrong_ends_invalid_input_with_its_line_and_nothing_else_it_says_is_believed() {
    for (written, expected) in [
        (
            r#"{"code":"invalid_input","message":"This folder has no config.json, so it isn't a Hugging Face model."}"#,
            Some("This folder has no config.json, so it isn't a Hugging Face model."),
        ),
        (r#"{"code":"out_of_memory","message":"pretending"}"#, None),
        (r#"{"code":"invalid_input","message":"line one\nline\u0007 two"}"#, Some("line oneline two")),
        ("not json", None),
    ] {
        let rig = rig();
        rig.registry.start(start(1)).unwrap();
        let container = rig.machine.container().await;
        let work = rig.machine.scratch_of(&format!("{SESSION}-1")).join("work");
        std::fs::write(work.join("idleo-failure.json"), written).unwrap();
        container.finish(2);

        let ended = rig.ended(1).await;

        match expected {
            Some(line) => assert_eq!(
                ended,
                Ended::Failed { code: code::INVALID_INPUT.to_owned(), message: line.to_owned(), exit_code: Some(2) }
            ),
            None => assert_eq!(failed_code(&ended), code::JOB_FAILED, "{written}"),
        }
    }
}

/// What finished sessions left behind is said in the next session's log in a development distribution (hostile test 12 reads it
/// through the real path), and in no other.
#[tokio::test]
async fn a_development_distribution_tells_each_sessions_log_what_the_sessions_before_it_left() {
    for (development, left, wanted) in [
        (true, Vec::new(), Some("idleo-runner (development): nothing is left from finished sessions")),
        (
            true,
            vec!["1 process".to_owned(), "2 mounts".to_owned()],
            Some("idleo-runner (development): left from finished sessions: 1 process, 2 mounts"),
        ),
        (false, vec!["1 process".to_owned()], None),
    ] {
        let rig = rig();
        rig.machine.development.store(development, Ordering::SeqCst);
        *rig.machine.leftover.lock().unwrap() = left;
        rig.registry.start(start(1)).unwrap();
        rig.machine.container().await.finish(0);
        assert_eq!(rig.ended(1).await, Ended::Succeeded);
        let log = rig.log_text(1);
        match wanted {
            Some(line) => assert!(log.contains(line), "{log}"),
            None => assert!(!log.contains("finished sessions"), "a release distribution says nothing: {log}"),
        }
    }
}
