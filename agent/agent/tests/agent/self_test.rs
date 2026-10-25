//! The self-test the web app asks for (`RunSelfTest`), and the cache the heartbeat tells the scheduler about.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body;
use agent_proto::v1::open_stream_response::Body as ServerBody;
use agent_proto::v1::{OfferDeclineReason, OpenStreamRequest, OpenStreamResponse, RunSelfTest};
use idleo_agent::connection::Event;
use idleo_agent::local::RequestedSelfTestFn;
use idleo_agent::self_test::{Check, Report};
use uuid::Uuid;

use crate::support::{Stream, TestServer, messages, next_event, offer, spawn_agent, timing};

fn check(name: &str, passed: bool) -> Check {
    Check { name: name.to_owned(), passed, detail: format!("{name} found"), took_ms: 250 }
}

fn report(passed: bool) -> Report {
    Report {
        checks: vec![check("gpu_visible", true), check("lockdown", true), check("storage_speed", passed)],
        passed,
        distro_version: "0.1.0".to_owned(),
        facts: Vec::new(),
        upload_kbps: Some(40_000),
        upload_cap_kbps: Some(20_000),
        download_kbps: Some(90_000),
    }
}

/// A requested self-test that tells the sink of each check, then waits for `release` before returning its report.
fn requested(release: Arc<AtomicBool>) -> RequestedSelfTestFn {
    Arc::new(move |sink| {
        let release = Arc::clone(&release);
        Box::pin(async move {
            let report = report(true);
            for check in &report.checks {
                sink(check);
            }
            while !release.load(Ordering::SeqCst) {
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            report
        })
    })
}

fn run_self_test(run_id: &str) -> OpenStreamResponse {
    OpenStreamResponse {
        message_id: Uuid::now_v7().to_string(),
        body: Some(ServerBody::RunSelfTest(RunSelfTest { run_id: run_id.to_owned() })),
    }
}

fn sent(server: &TestServer) -> Vec<OpenStreamRequest> {
    messages(&server.recorded(), 0)
}

#[tokio::test]
async fn a_requested_self_test_sends_each_check_as_it_ends_and_then_the_report_with_its_run_id() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, mut events) = server.run_options(dir.path(), timing());
    options.requested_self_test = Some(requested(Arc::new(AtomicBool::new(true))));
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;

    let run_id = Uuid::now_v7().to_string();
    let command = run_self_test(&run_id);
    let command_id = command.message_id.clone();
    server.send(command).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0].messages.iter().any(|(_, m)| matches!(m.body, Some(Body::SelfTestReport(_))))
        })
        .await;

    let all = sent(&server);
    assert!(
        all.iter().any(|m| matches!(&m.body, Some(Body::Ack(ack)) if ack.acked_message_id == command_id)),
        "the command is acked"
    );
    let progress: Vec<_> = all
        .iter()
        .filter_map(|m| match &m.body {
            Some(Body::SelfTestProgress(progress)) => Some(progress.clone()),
            _ => None,
        })
        .collect();
    let names: Vec<_> =
        progress.iter().map(|p| p.check.as_ref().expect("a check, not a refusal").name.as_str()).collect();
    assert_eq!(names, ["gpu_visible", "lockdown", "storage_speed"], "one message a check, in the order they ran");
    assert!(progress.iter().all(|p| p.run_id == run_id && p.refused.is_empty()));
    assert_eq!(progress[0].check.as_ref().unwrap().took.as_ref().unwrap().nanos, 250_000_000);

    let reports: Vec<_> = all
        .iter()
        .filter_map(|m| match &m.body {
            Some(Body::SelfTestReport(report)) => Some(report.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(reports.len(), 1);
    assert_eq!(reports[0].run_id, run_id);
    assert!(reports[0].passed);
    assert_eq!((reports[0].upload_kbps, reports[0].download_kbps), (40_000, 90_000));
    let last_progress = all.iter().rposition(|m| matches!(m.body, Some(Body::SelfTestProgress(_)))).unwrap();
    let report_at = all.iter().position(|m| matches!(m.body, Some(Body::SelfTestReport(_)))).unwrap();
    assert!(last_progress < report_at, "every check is sent before the report");
}

#[tokio::test]
async fn a_resent_command_for_a_run_already_started_changes_nothing() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, mut events) = server.run_options(dir.path(), timing());
    options.requested_self_test = Some(requested(Arc::new(AtomicBool::new(true))));
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;

    let run_id = Uuid::now_v7().to_string();
    server.send(run_self_test(&run_id)).await;
    server.send(run_self_test(&run_id)).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0].messages.iter().any(|(_, m)| matches!(m.body, Some(Body::SelfTestReport(_))))
        })
        .await;
    tokio::time::sleep(Duration::from_millis(300)).await;

    let all = sent(&server);
    let acks = all.iter().filter(|m| matches!(&m.body, Some(Body::Ack(_)))).count();
    assert!(acks >= 2, "both copies are acked");
    assert_eq!(all.iter().filter(|m| matches!(m.body, Some(Body::SelfTestReport(_)))).count(), 1);
    assert_eq!(all.iter().filter(|m| matches!(m.body, Some(Body::SelfTestProgress(_)))).count(), 3);
}

#[tokio::test]
async fn a_second_run_while_one_is_going_is_refused() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, mut events) = server.run_options(dir.path(), timing());
    let release = Arc::new(AtomicBool::new(false));
    options.requested_self_test = Some(requested(Arc::clone(&release)));
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;

    let first = Uuid::now_v7().to_string();
    let second = Uuid::now_v7().to_string();
    server.send(run_self_test(&first)).await;
    server.send(run_self_test(&second)).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0]
                .messages
                .iter()
                .any(|(_, m)| matches!(&m.body, Some(Body::SelfTestProgress(p)) if p.refused == "already_running"))
        })
        .await;
    let refusal = sent(&server)
        .into_iter()
        .find_map(|m| match m.body {
            Some(Body::SelfTestProgress(p)) if !p.refused.is_empty() => Some(p),
            _ => None,
        })
        .unwrap();
    assert_eq!(refusal.run_id, second);
    assert!(refusal.check.is_none());
    release.store(true, Ordering::SeqCst);
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0]
                .messages
                .iter()
                .any(|(_, m)| matches!(&m.body, Some(Body::SelfTestReport(r)) if r.run_id == first))
        })
        .await;
}

#[tokio::test]
async fn a_machine_running_a_session_refuses_it() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, mut events) = server.run_options(dir.path(), timing());
    options.requested_self_test = Some(requested(Arc::new(AtomicBool::new(true))));
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    server.send(crate::sessions::start_session(Uuid::now_v7(), "command-1", 120)).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            crate::sessions::statuses(r, 0).iter().any(|(_, s)| s.latest_checkpoint_seq == 1)
        })
        .await;

    let run_id = Uuid::now_v7().to_string();
    server.send(run_self_test(&run_id)).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0]
                .messages
                .iter()
                .any(|(_, m)| matches!(&m.body, Some(Body::SelfTestProgress(p)) if p.refused == "session_running"))
        })
        .await;

    assert!(!sent(&server).iter().any(|m| matches!(m.body, Some(Body::SelfTestReport(_)))), "nothing was run");
}

/// The reply to the offer with this id, once it has arrived.
async fn reply_to(server: &TestServer, offer_id: &str) -> agent_proto::v1::OfferReply {
    let has = |r: &crate::support::Recorded| {
        r.connections[0]
            .messages
            .iter()
            .any(|(_, m)| matches!(&m.body, Some(Body::OfferReply(reply)) if reply.offer_id == offer_id))
    };
    server.wait_until(Duration::from_secs(5), has).await;
    sent(server)
        .into_iter()
        .find_map(|m| match m.body {
            Some(Body::OfferReply(reply)) if reply.offer_id == offer_id => Some(reply),
            _ => None,
        })
        .unwrap()
}

fn offer_with_id(server_offer: OpenStreamResponse) -> (String, OpenStreamResponse) {
    let Some(ServerBody::Offer(inner)) = &server_offer.body else { unreachable!() };
    (inner.offer_id.clone(), server_offer)
}

#[tokio::test]
async fn offers_are_declined_while_a_requested_self_test_runs_and_taken_again_after() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, mut events) = server.run_options(dir.path(), timing());
    let release = Arc::new(AtomicBool::new(false));
    options.requested_self_test = Some(requested(Arc::clone(&release)));
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    let run_id = Uuid::now_v7().to_string();
    server.send(run_self_test(&run_id)).await;
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0].messages.iter().any(|(_, m)| matches!(&m.body, Some(Body::SelfTestProgress(_))))
        })
        .await;

    let (during, message) = offer_with_id(offer(1024, Duration::from_secs(10)));
    server.send(message).await;
    let declined = reply_to(&server, &during).await;
    assert!(!declined.accepted);
    assert_eq!(declined.decline_reason(), OfferDeclineReason::SelfTestRunning);

    release.store(true, Ordering::SeqCst);
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections[0].messages.iter().any(|(_, m)| matches!(&m.body, Some(Body::SelfTestReport(_))))
        })
        .await;
    let (after, message) = offer_with_id(offer(1024, Duration::from_secs(10)));
    server.send(message).await;
    let taken = reply_to(&server, &after).await;
    assert!(taken.accepted, "the test is over, so the machine takes work again");
}

#[tokio::test(start_paused = true)]
async fn heartbeats_tell_the_scheduler_which_job_images_are_already_here() {
    let server = TestServer::in_memory();
    server.behave(|b| b.default_stream = Stream::welcome(Duration::from_millis(250)));
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let digest = format!("sha256:{}", "ab".repeat(32));
    options.host.set_cached_images(&[format!("registry.example/job@{digest}"), "not-pinned".to_owned()]);
    let (_stop, _agent) = spawn_agent(options);

    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    tokio::time::sleep(Duration::from_millis(600)).await;

    let heartbeats: Vec<_> = messages(&server.recorded(), 0)
        .into_iter()
        .filter_map(|m| match m.body {
            Some(Body::Heartbeat(h)) => Some(h),
            _ => None,
        })
        .collect();
    assert_ne!(heartbeats.len(), 0);
    for heartbeat in &heartbeats {
        assert_eq!(heartbeat.cached_images, std::slice::from_ref(&digest), "digests only, and only of pinned images");
    }
}
