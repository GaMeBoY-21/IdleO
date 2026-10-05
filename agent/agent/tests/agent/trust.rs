use idleo_agent::connection::{EndReason, Event};
use idleo_agent::enrol::{self, EnrolError, EnrolmentCode};
use idleo_agent::pki::{IssuedIdentity, NewKey};
use idleo_agent::state;

use crate::support::{DAY, ServerTls, TLS12_ONLY, TestServer, VALID_CODE, next_event, spawn_agent, timing};

#[tokio::test]
async fn enrolment_refuses_a_server_certificate_from_another_ca() {
    let server = TestServer::tcp_with(ServerTls { certificate_from_another_ca: true, ..ServerTls::default() }).await;
    let dir = tempfile::tempdir().unwrap();
    let code = EnrolmentCode::parse(VALID_CODE).unwrap();

    let error = enrol::enrol(&server.target, &server.pki().agent_roots(), dir.path(), &code, false).await.unwrap_err();

    let EnrolError::Connect(message) = &error else { panic!("{error}") };
    assert!(message.contains("UnknownIssuer"), "{message}");
    assert_eq!(server.recorded().csrs, Vec::<Vec<u8>>::new(), "the code never reached the impostor");
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 0);
}

#[tokio::test]
async fn the_stream_refuses_a_server_certificate_from_another_ca() {
    let server = TestServer::tcp_with(ServerTls { certificate_from_another_ca: true, ..ServerTls::default() }).await;
    let dir = tempfile::tempdir().unwrap();
    let key = NewKey::generate().unwrap();
    let public_key = rcgen::KeyPair::from_pem(&key.private_key_pem()).unwrap();
    let identity = IssuedIdentity {
        chain: server.pki().host_chain(&public_key, server.host_id(), 30 * DAY).into_iter().map(Into::into).collect(),
        host_id: server.host_id(),
        not_after: time::OffsetDateTime::now_utc() + 30 * DAY,
    };
    state::save(dir.path(), &key.private_key_pem(), &identity, false).unwrap();
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (_stop, _agent) = spawn_agent(options);

    let Event::Retrying { reason, .. } = next_event(&mut events, |e| matches!(e, Event::Retrying { .. })).await else {
        unreachable!()
    };

    assert!(matches!(&reason, EndReason::Failed(e) if e.contains("UnknownIssuer")), "{reason:?}");
    assert!(server.recorded().connections.is_empty());
}

#[tokio::test]
async fn a_tls_1_2_server_is_refused() {
    let server = TestServer::tcp_with(ServerTls { versions: TLS12_ONLY, ..ServerTls::default() }).await;
    let dir = tempfile::tempdir().unwrap();
    let code = EnrolmentCode::parse(VALID_CODE).unwrap();

    let error = enrol::enrol(&server.target, &server.pki().agent_roots(), dir.path(), &code, false).await.unwrap_err();

    assert!(matches!(error, EnrolError::Connect(_)), "{error}");
    assert_eq!(server.recorded().csrs, Vec::<Vec<u8>>::new());
}
