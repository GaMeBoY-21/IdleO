use std::time::Duration;

use agent_proto::v1::OperatingSystem;
use agent_proto::v1::open_stream_request::Body;
use idleo_agent::connection::{EndReason, Event};
use tokio::time::Instant;
use uuid::Uuid;

use crate::support::{Stream, TestServer, messages, next_event, spawn_agent, timing};

#[tokio::test]
async fn hello_is_the_first_message_and_shutdown_closes_the_stream() {
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());

    let started = Instant::now();
    let (stop, agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    let to_welcome = started.elapsed();
    server
        .wait_until(Duration::from_secs(5), |r| !r.connections.is_empty() && r.connections[0].welcomed.is_some())
        .await;

    let first = messages(&server.recorded(), 0).remove(0);
    println!("time from run start to Welcome: {to_welcome:?}");
    println!("first Hello the test server received:\n{first:#?}");
    assert_eq!(Uuid::parse_str(&first.message_id).unwrap().get_version_num(), 7);
    let Some(Body::Hello(hello)) = first.body else { panic!("the first message isn't Hello: {first:?}") };
    assert_eq!(hello.agent_version, env!("CARGO_PKG_VERSION"));
    let os = if cfg!(windows) {
        OperatingSystem::Windows
    } else if cfg!(target_os = "linux") {
        OperatingSystem::Linux
    } else {
        OperatingSystem::Macos
    };
    assert_eq!(hello.os(), os);
    assert_ne!(hello.os_version, "");
    assert_eq!(hello.gpus.len(), 1);
    assert!(hello.gpus[0].uuid.starts_with("GPU-00000000-fake-"));
    assert!(hello.gpus[0].vram_mb > 0);
    assert_eq!(hello.running_sessions, []);

    let stopping = Instant::now();
    stop.send(true).unwrap();
    tokio::time::timeout(Duration::from_secs(5), agent).await.expect("stopped within 5 seconds").unwrap().unwrap();
    println!("shutdown took {:?}", stopping.elapsed());
    server.wait_until(Duration::from_secs(5), |r| r.connections[0].ended_by_agent).await;
}

#[tokio::test(start_paused = true)]
async fn heartbeats_follow_at_the_interval_welcome_sets() {
    let server = TestServer::in_memory();
    server.behave(|b| b.default_stream = Stream::welcome(Duration::from_millis(250)));
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    tokio::time::sleep(Duration::from_millis(5_100)).await;

    let all = messages(&server.recorded(), 0);
    let heartbeats: Vec<_> = all
        .iter()
        .filter_map(|m| match &m.body {
            Some(Body::Heartbeat(h)) => Some(h.clone()),
            _ => None,
        })
        .collect();
    assert_eq!(heartbeats.len(), 20, "5.1 s at 250 ms");
    for heartbeat in &heartbeats {
        assert_eq!(heartbeat.gpus.len(), 1);
        assert!(heartbeat.gpus[0].uuid.starts_with("GPU-00000000-fake-"));
        assert!(heartbeat.gpus[0].power_w.is_some());
        assert_eq!(heartbeat.net_up_kbps, 0);
        assert!(!heartbeat.owner_active);
        assert_eq!(heartbeat.sessions, []);
    }
    let mut ids: Vec<_> = all.iter().map(|m| m.message_id.clone()).collect();
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), all.len(), "every message has its own id");
}

#[tokio::test]
async fn a_zero_heartbeat_interval_drops_the_stream_and_reconnects() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.streams.push_back(Stream::ZeroInterval));
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    let retry = next_event(&mut events, |e| matches!(e, Event::Retrying { .. })).await;
    let Event::Retrying { reason, ceiling, .. } = retry else { unreachable!() };
    assert!(matches!(reason, EndReason::BadWelcome(ref e) if e.contains("zero")), "{reason:?}");
    assert_eq!(ceiling, Some(Duration::from_secs(1)));

    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    server.wait_until(Duration::from_secs(5), |r| r.connections.len() == 2 && r.connections[0].ended_by_agent).await;
}
