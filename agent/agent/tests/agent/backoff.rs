//! Reconnects on tokio's paused clock: delays are virtual, so minutes of backoff take
//! milliseconds.

use std::time::Duration;

use idleo_agent::connection::{EndReason, Event};
use idleo_agent::pki::{IssuedIdentity, NewKey};
use idleo_agent::state;
use tokio::sync::mpsc::UnboundedReceiver;
use tonic::Code;

use crate::support::{DAY, Stream, TestServer, next_event, spawn_agent, timing};

const SECOND: Duration = Duration::from_secs(1);

async fn retries(events: &mut UnboundedReceiver<Event>, count: usize) -> Vec<(Duration, Option<Duration>, EndReason)> {
    let mut retries = Vec::new();
    while retries.len() < count {
        if let Event::Retrying { delay, ceiling, reason } =
            next_event(events, |e| matches!(e, Event::Retrying { .. })).await
        {
            retries.push((delay, ceiling, reason));
        }
    }
    retries
}

#[tokio::test(start_paused = true)]
async fn reconnect_delays_double_to_the_cap_with_jitter() {
    let server = TestServer::in_memory();
    server.behave(|b| b.default_stream = Stream::Fail(Code::Unavailable));
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    let retries = retries(&mut events, 10).await;
    server.wait_until(Duration::from_secs(3600), |r| r.connections.len() == 11).await;

    let ceilings: Vec<u64> = retries.iter().map(|(_, ceiling, _)| ceiling.unwrap().as_secs()).collect();
    assert_eq!(ceilings, [1, 2, 4, 8, 16, 32, 60, 60, 60, 60]);
    for (delay, ceiling, reason) in &retries {
        assert!(delay <= &ceiling.unwrap());
        assert!(matches!(reason, EndReason::Failed(_)), "{reason:?}");
    }
    let delays: Vec<Duration> = retries.iter().map(|(delay, _, _)| *delay).collect();
    let distinct: std::collections::HashSet<_> = delays.iter().collect();
    assert!(distinct.len() > 5, "full jitter gives different delays: {delays:?}");

    // The server sees each reconnect after exactly the chosen delay, on the virtual clock.
    let recorded = server.recorded();
    let gaps: Vec<Duration> = recorded.connections.windows(2).map(|w| w[1].opened - w[0].opened).collect();
    for (gap, delay) in gaps.iter().zip(&delays) {
        assert!(gap.abs_diff(*delay) < Duration::from_millis(50), "gap {gap:?}, delay {delay:?}");
    }
    println!("observed reconnect delays (ceiling → delay):");
    for ((_, ceiling, _), gap) in retries.iter().zip(&gaps) {
        println!("  {:>2} s → {:.3} s", ceiling.unwrap().as_secs(), gap.as_secs_f64());
    }
}

#[tokio::test(start_paused = true)]
async fn the_backoff_resets_only_after_a_minute_healthy() {
    let server = TestServer::in_memory();
    let healthy =
        |after| Stream::Welcome { interval: Duration::from_secs(5), end_after: Some((after, Code::Unavailable)) };
    server.behave(|b| {
        b.streams.extend([
            Stream::Fail(Code::Unavailable),
            Stream::Fail(Code::Unavailable),
            Stream::Fail(Code::Unavailable),
            healthy(Duration::from_secs(30)),
            healthy(Duration::from_secs(61)),
        ]);
        b.default_stream = Stream::Fail(Code::Unavailable);
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    let ceilings: Vec<u64> =
        retries(&mut events, 6).await.iter().map(|(_, ceiling, _)| ceiling.unwrap().as_secs()).collect();

    // 30 s up after Welcome isn't healthy enough: 8 follows 4. After 61 s up it starts again at 1.
    assert_eq!(ceilings, [1, 2, 4, 8, 1, 2]);
}

#[tokio::test(start_paused = true)]
async fn a_refused_certificate_switches_to_the_slow_retry() {
    let server = TestServer::in_memory();
    server.behave(|b| {
        b.streams.push_back(Stream::Fail(Code::Unauthenticated));
        b.streams.push_back(Stream::Fail(Code::PermissionDenied));
        b.streams.push_back(Stream::Fail(Code::Aborted));
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    let retries = retries(&mut events, 3).await;

    for (delay, ceiling, reason) in &retries[..2] {
        assert!(matches!(reason, EndReason::Refused(_)), "{reason:?}");
        assert_eq!(*ceiling, None);
        assert!((600 * SECOND..=660 * SECOND).contains(delay), "{delay:?}");
    }
    // ABORTED (replaced by a newer connection) is normal backoff, continuing the count.
    assert_eq!(retries[2].2, EndReason::Replaced);
    assert_eq!(retries[2].1, Some(SECOND));
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
}

#[tokio::test(start_paused = true)]
async fn an_expired_certificate_retries_slowly_without_connecting() {
    let server = TestServer::in_memory();
    let dir = tempfile::tempdir().unwrap();
    let key = NewKey::generate().unwrap();
    let chain =
        server.pki().host_chain(&rcgen::KeyPair::from_pem(&key.private_key_pem()).unwrap(), server.host_id(), -DAY);
    let identity = IssuedIdentity {
        chain: chain.into_iter().map(Into::into).collect(),
        host_id: server.host_id(),
        not_after: time::OffsetDateTime::now_utc() - DAY,
    };
    state::save(dir.path(), &key.private_key_pem(), &identity, false).unwrap();
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    let retries = retries(&mut events, 2).await;

    for (delay, ceiling, reason) in retries {
        assert_eq!(reason, EndReason::Expired);
        assert_eq!(ceiling, None);
        assert!(delay >= 600 * SECOND);
    }
    assert!(server.recorded().connections.is_empty());
}
