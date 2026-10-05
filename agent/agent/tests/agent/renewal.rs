use std::fs;
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body;
use idleo_agent::connection::Event;
use idleo_agent::state::{self, CERT_FILE, KEY_FILE};
use rustls::pki_types::CertificateDer;
use rustls::pki_types::pem::PemObject;

use crate::support::{DAY, Renew, Stream, TestServer, messages, next_event, spawn_agent, timing};

fn leaf(dir: &std::path::Path) -> Vec<u8> {
    CertificateDer::pem_file_iter(dir.join(CERT_FILE)).unwrap().next().unwrap().unwrap().to_vec()
}

#[tokio::test]
async fn with_nine_days_left_the_agent_renews_and_reconnects_with_the_new_certificate() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.enrol_valid_for = 9 * DAY);
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let old_key = fs::read(dir.path().join(KEY_FILE)).unwrap();
    let old_leaf = leaf(dir.path());
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    next_event(&mut events, |e| matches!(e, Event::Renewed { .. })).await;
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    server.wait_until(Duration::from_secs(5), |r| r.connections.len() == 2 && r.connections[0].ended_by_agent).await;

    let new_leaf = leaf(dir.path());
    assert_ne!(fs::read(dir.path().join(KEY_FILE)).unwrap(), old_key);
    assert_ne!(new_leaf, old_leaf);
    let recorded = server.recorded();
    assert_eq!(recorded.renewals, 1);
    assert_eq!(recorded.connections[0].client_certificate.as_deref(), Some(old_leaf.as_slice()));
    assert_eq!(recorded.connections[1].client_certificate.as_deref(), Some(new_leaf.as_slice()));
    let identity = state::load(dir.path()).unwrap();
    assert_eq!(identity.host_id, server.host_id());
    assert!(identity.not_after > time::OffsetDateTime::now_utc() + 29 * DAY);
    assert!(fs::read_dir(dir.path()).unwrap().all(|e| !e.unwrap().file_name().to_string_lossy().ends_with(".new")));
}

#[tokio::test]
async fn with_twenty_days_left_the_agent_does_not_renew() {
    let server = TestServer::tcp().await;
    server.behave(|b| {
        b.enrol_valid_for = 20 * DAY;
        b.default_stream = Stream::welcome(Duration::from_millis(100));
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (options, _events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    server
        .wait_until(Duration::from_secs(10), |r| {
            r.connections.first().is_some_and(|c| {
                c.messages.iter().filter(|(_, m)| matches!(m.body, Some(Body::Heartbeat(_)))).count() >= 5
            })
        })
        .await;

    let recorded = server.recorded();
    assert_eq!(recorded.renewals, 0);
    assert_eq!(recorded.connections.len(), 1);
}

#[tokio::test]
async fn a_declined_renewal_waits_for_the_next_check_and_stays_connected() {
    let server = TestServer::tcp().await;
    server.behave(|b| {
        b.enrol_valid_for = 9 * DAY;
        b.renew = Renew::FailedPrecondition;
        b.default_stream = Stream::welcome(Duration::from_millis(100));
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let key = fs::read(dir.path().join(KEY_FILE)).unwrap();
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    next_event(&mut events, |e| matches!(e, Event::RenewalDeclined)).await;
    server
        .wait_until(Duration::from_secs(10), |r| {
            messages(r, 0).iter().filter(|m| matches!(m.body, Some(Body::Heartbeat(_)))).count() >= 5
        })
        .await;

    let recorded = server.recorded();
    assert_eq!(recorded.renewals, 1);
    assert_eq!(recorded.connections.len(), 1);
    assert_eq!(fs::read(dir.path().join(KEY_FILE)).unwrap(), key);
}
