//! The agent answers offers (ADR 0015).

use std::time::Duration;

use agent_proto::v1::OfferDeclineReason;
use agent_proto::v1::open_stream_request::Body;
use idleo_agent::connection::Event;

use crate::support::{Owner, Stream, TestServer, next_event, spawn_agent, timing};

fn offer_stream(temp_cap_c: i32, vram_needed_mb: i32, expires_in: Duration) -> Stream {
    Stream::Offer { temp_cap_c, vram_needed_mb, expires_in, ack_from: 1 }
}

async fn answer(server: &TestServer, owner_active: bool) -> (String, Option<OfferDeclineReason>) {
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, mut events) = server.run_options(dir.path(), timing());
    options.host = idleo_agent::host::Host::new(std::sync::Arc::new(Owner(owner_active)), dir.path().to_owned(), true);
    let (_stop, _agent) = spawn_agent(options);
    let Event::OfferAnswered { offer_id, declined } =
        next_event(&mut events, |e| matches!(e, Event::OfferAnswered { .. })).await
    else {
        unreachable!()
    };
    server
        .wait_until(Duration::from_secs(5), |r| {
            r.connections
                .first()
                .is_some_and(|c| c.messages.iter().any(|(_, m)| matches!(m.body, Some(Body::OfferReply(_)))))
        })
        .await;
    let reply = server.recorded().connections[0]
        .messages
        .iter()
        .find_map(|(_, m)| match &m.body {
            Some(Body::OfferReply(reply)) => Some(reply.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(reply.offer_id, offer_id);
    assert_eq!(reply.accepted, declined.is_none());
    assert_eq!(reply.decline_reason(), declined.unwrap_or(OfferDeclineReason::Unspecified));
    (offer_id, declined)
}

#[tokio::test]
async fn an_offer_that_fits_is_accepted() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = offer_stream(85, 1024, Duration::from_secs(10)));

    assert_eq!(answer(&server, false).await.1, None);
}

#[tokio::test]
async fn the_owner_at_the_machine_declines() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = offer_stream(85, 1024, Duration::from_secs(10)));

    assert_eq!(answer(&server, true).await.1, Some(OfferDeclineReason::OwnerActive));
}

#[tokio::test]
async fn too_little_gpu_memory_declines() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = offer_stream(85, 99_999, Duration::from_secs(10)));

    assert_eq!(answer(&server, false).await.1, Some(OfferDeclineReason::InsufficientResources));
}

#[tokio::test]
async fn a_gpu_at_the_cap_declines() {
    let server = TestServer::tcp().await;
    // The fake GPU reads 35 °C or more, so a 30 °C cap is always exceeded.
    server.behave(|b| b.default_stream = offer_stream(30, 1024, Duration::from_secs(10)));

    assert_eq!(answer(&server, false).await.1, Some(OfferDeclineReason::TooHot));
}

#[tokio::test]
async fn an_offer_expired_on_arrival_declines_as_expired() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.default_stream = offer_stream(85, 1024, Duration::from_nanos(1)));

    assert_eq!(answer(&server, false).await.1, Some(OfferDeclineReason::Expired));
}

#[tokio::test(start_paused = true)]
async fn an_unacked_reply_is_resent_with_the_same_id_until_acked() {
    let server = TestServer::in_memory();
    server.behave(|b| {
        b.default_stream =
            Stream::Offer { temp_cap_c: 85, vram_needed_mb: 1024, expires_in: Duration::from_secs(10), ack_from: 2 };
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::OfferAnswered { .. })).await;

    tokio::time::sleep(Duration::from_secs(20)).await;

    let recorded = server.recorded();
    let replies: Vec<_> =
        recorded.connections[0].messages.iter().filter(|(_, m)| matches!(m.body, Some(Body::OfferReply(_)))).collect();
    assert_eq!(replies.len(), 2, "sent, resent once, then acked");
    assert_eq!(replies[0].1.message_id, replies[1].1.message_id, "the same message id");
    let gap = replies[1].0 - replies[0].0;
    assert!(gap >= Duration::from_secs(3) && gap < Duration::from_secs(5), "resent after {gap:?}");
}
