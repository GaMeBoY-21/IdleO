//! The agent runs sessions (ADR 0016): `StartSession` acked and deduplicated, statuses kept until
//! acked and replayed after a reconnect, leases kept on the lease clock, and stops obeyed.

use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body;
use agent_proto::v1::open_stream_response::Body as ServerBody;
use agent_proto::v1::{
    Credentials, LeaseRenew, OpenStreamResponse, SessionSpec, SessionState, SessionStatus, StartSession, StopReason,
    StopSession, StorageGrant, StoragePermission,
};
use idleo_agent::lease_clock::{FakeLeaseClock, LeaseClock};
use tonic::Code;
use uuid::Uuid;

use crate::support::{Recorded, Stream, TestRunner, TestServer, spawn_agent, timing};

const WAIT: Duration = Duration::from_secs(10);
const GPU: &str = "GPU-00000000-fake-0000-000000000000";

fn seconds(seconds: i64) -> prost_types::Duration {
    prost_types::Duration { seconds, nanos: 0 }
}

pub(crate) fn start_session(session_id: Uuid, command_id: &str, lease_ttl: i64) -> OpenStreamResponse {
    OpenStreamResponse {
        message_id: Uuid::now_v7().to_string(),
        body: Some(ServerBody::StartSession(StartSession {
            session_id: session_id.to_string(),
            command_id: command_id.to_owned(),
            attempt: 1,
            spec: Some(SessionSpec {
                image: format!("registry.example/smoke@sha256:{}", "1".repeat(64)),
                args: vec!["--duration".to_owned(), "20s".to_owned()],
                vram_needed_mb: 1024,
                memory_mb: 2048,
                cpu_millicores: 2000,
                pids_limit: 256,
                scratch_mb: 1024,
                checkpoint_interval: Some(seconds(5)),
                max_runtime: Some(seconds(300)),
                ..SessionSpec::default()
            }),
            credentials: Some(Credentials {
                attempt_grant: Some(StorageGrant {
                    endpoint: "http://storage.invalid:9000".to_owned(),
                    bucket: "idleo-test".to_owned(),
                    access_key_id: "TESTKEYNEVERLOGGED".to_owned(),
                    secret_access_key: "test-secret-never-logged".to_owned(),
                    session_token: "test-token-never-logged".to_owned(),
                    prefixes: vec![format!("sessions/{session_id}/attempt-1/")],
                    permission: StoragePermission::ReadWrite.into(),
                    expires_in: Some(seconds(3600)),
                }),
                ..Credentials::default()
            }),
            lease_ttl: Some(seconds(lease_ttl)),
            minute_index_base: Some(0),
            gpu_uuids: vec![GPU.to_owned()],
            resume_from: None,
        })),
    }
}

pub(crate) fn statuses(recorded: &Recorded, connection: usize) -> Vec<(String, SessionStatus)> {
    recorded.connections[connection]
        .messages
        .iter()
        .filter_map(|(_, message)| match &message.body {
            Some(Body::SessionStatus(status)) => Some((message.message_id.clone(), status.clone())),
            _ => None,
        })
        .collect()
}

fn acks_of(recorded: &Recorded, message_id: &str) -> usize {
    recorded
        .connections
        .iter()
        .flat_map(|c| c.messages.iter())
        .filter(|(_, m)| matches!(&m.body, Some(Body::Ack(ack)) if ack.acked_message_id == message_id))
        .count()
}

/// An enrolled agent on a scripted stream, with a fake lease clock; returns what the test needs.
async fn agent(
    server: &TestServer,
) -> (Arc<FakeLeaseClock>, Arc<std::sync::atomic::AtomicUsize>, tokio::sync::watch::Sender<bool>, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    server.behave(|b| b.default_stream = Stream::Scripted);
    let (mut options, _events) = server.run_options(dir.path(), timing());
    let clock = Arc::new(FakeLeaseClock::default());
    options.lease_clock = Arc::clone(&clock) as Arc<dyn LeaseClock>;
    let runner = TestRunner::default();
    let started = Arc::clone(&runner.started);
    options.runner = Some(Arc::new(runner));
    let (stop, _agent) = spawn_agent(options);
    server.wait_until(WAIT, |r| r.connections.first().is_some_and(|c| c.welcomed.is_some())).await;
    (clock, started, stop, dir)
}

#[tokio::test]
async fn a_start_is_acked_and_a_repeat_with_the_same_command_id_never_runs_twice() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.ack_statuses = true);
    let (_clock, started, _stop, _dir) = agent(&server).await;
    let session = Uuid::now_v7();
    let first = start_session(session, "command-1", 120);
    let again = start_session(session, "command-1", 120);

    server.send(first.clone()).await;
    server.send(again.clone()).await;

    server
        .wait_until(WAIT, |r| {
            acks_of(r, &first.message_id) == 1
                && acks_of(r, &again.message_id) == 1
                && statuses(r, 0).iter().any(|(_, s)| s.latest_checkpoint_seq == 1)
        })
        .await;
    assert_eq!(started.load(Ordering::SeqCst), 1);
    let states: Vec<_> = statuses(&server.recorded(), 0).iter().map(|(_, s)| s.state()).collect();
    assert_eq!(states, vec![SessionState::Preparing, SessionState::Running, SessionState::Running]);
}

#[tokio::test]
async fn unacked_statuses_are_resent_on_the_next_stream_which_lists_the_running_session() {
    let server = TestServer::tcp().await;
    let (_clock, _started, _stop, _dir) = agent(&server).await;
    let session = Uuid::now_v7();
    server.send(start_session(session, "command-1", 120)).await;
    server.wait_until(WAIT, |r| statuses(r, 0).len() == 3).await;
    let first_ids: Vec<_> = statuses(&server.recorded(), 0).into_iter().map(|(id, _)| id).collect();

    server.behave(|b| b.ack_statuses = true);
    server.end_stream(Code::Unavailable).await;

    server.wait_until(WAIT, |r| r.connections.len() == 2 && statuses(r, 1).len() == 3).await;
    let recorded = server.recorded();
    let again: Vec<_> = statuses(&recorded, 1).into_iter().map(|(id, _)| id).collect();
    assert_eq!(again, first_ids, "the same messages, with their ids");
    let Some(Body::Hello(hello)) = &recorded.connections[1].messages[0].1.body else { panic!("Hello first") };
    assert_eq!(hello.running_sessions.len(), 1);
    assert_eq!(hello.running_sessions[0].session_id, session.to_string());
    assert_eq!(hello.running_sessions[0].attempt, 1);
}

#[tokio::test]
async fn without_renewals_the_lease_lapses_and_the_attempt_is_reported_expired() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.ack_statuses = true);
    let (clock, _started, _stop, _dir) = agent(&server).await;
    let session = Uuid::now_v7();
    server.send(start_session(session, "command-1", 120)).await;
    server.wait_until(WAIT, |r| statuses(r, 0).len() == 3).await;

    clock.advance(Duration::from_secs(100));
    let renew = OpenStreamResponse {
        message_id: Uuid::now_v7().to_string(),
        body: Some(ServerBody::LeaseRenew(LeaseRenew {
            session_id: session.to_string(),
            attempt: 1,
            lease_ttl: Some(seconds(120)),
        })),
    };
    server.send(renew).await;
    tokio::time::sleep(Duration::from_millis(300)).await;
    clock.advance(Duration::from_secs(100));
    tokio::time::sleep(Duration::from_millis(600)).await;
    assert!(
        statuses(&server.recorded(), 0).iter().all(|(_, s)| s.state() != SessionState::LeaseExpired),
        "renewed at 100 s, so alive at 200 s"
    );

    clock.advance(Duration::from_secs(30));

    server.wait_until(WAIT, |r| statuses(r, 0).iter().any(|(_, s)| s.state() == SessionState::LeaseExpired)).await;
    let expired = statuses(&server.recorded(), 0).pop().unwrap().1;
    assert_eq!(expired.error.unwrap().code, "lease_expired");
    assert_eq!(expired.latest_checkpoint_seq, 1);
}

#[tokio::test]
async fn a_stop_is_acked_and_the_attempt_reported_stopped() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.ack_statuses = true);
    let (_clock, _started, _stop, _dir) = agent(&server).await;
    let session = Uuid::now_v7();
    server.send(start_session(session, "command-1", 120)).await;
    server.wait_until(WAIT, |r| statuses(r, 0).len() == 3).await;
    let stop = OpenStreamResponse {
        message_id: Uuid::now_v7().to_string(),
        body: Some(ServerBody::StopSession(StopSession {
            session_id: session.to_string(),
            attempt: 1,
            command_id: "stop-1".to_owned(),
            reason: StopReason::Superseded.into(),
            ..StopSession::default()
        })),
    };

    server.send(stop.clone()).await;

    server
        .wait_until(WAIT, |r| {
            acks_of(r, &stop.message_id) == 1 && statuses(r, 0).iter().any(|(_, s)| s.state() == SessionState::Stopped)
        })
        .await;
}

#[tokio::test]
async fn an_agent_without_a_runner_declines_offers() {
    let server = TestServer::tcp().await;
    server.behave(|b| {
        b.default_stream =
            Stream::Offer { temp_cap_c: 85, vram_needed_mb: 1024, expires_in: Duration::from_secs(10), ack_from: 1 };
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, _events) = server.run_options(dir.path(), timing());
    options.runner = None;
    let (_stop, _agent) = spawn_agent(options);

    server
        .wait_until(WAIT, |r| {
            r.connections.first().is_some_and(|c| {
                c.messages.iter().any(|(_, m)| {
                    matches!(&m.body, Some(Body::OfferReply(reply))
                        if !reply.accepted
                            && reply.decline_reason() == agent_proto::v1::OfferDeclineReason::InsufficientResources)
                })
            })
        })
        .await;
}
