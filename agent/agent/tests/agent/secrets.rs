use std::io::Write;
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use agent_proto::v1::SessionState;
use agent_proto::v1::open_stream_response::Body as ServerBody;
use idleo_agent::connection::Event;
use idleo_agent::enrol::{self, EnrolmentCode};
use idleo_agent::runner::dev::DevRunner;
use idleo_agent::state::KEY_FILE;
use tracing_subscriber::fmt::MakeWriter;

use crate::sessions::{start_session, statuses};
use crate::support::{DAY, Stream, TestServer, VALID_CODE, next_event, spawn_agent, timing};

#[derive(Clone, Default)]
struct Captured(Arc<Mutex<Vec<u8>>>);

impl Write for Captured {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(bytes);
        Ok(bytes.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl<'a> MakeWriter<'a> for Captured {
    type Writer = Self;

    fn make_writer(&'a self) -> Self::Writer {
        self.clone()
    }
}

/// The base64 lines of a PEM body, the parts of a secret that could leak into a log.
fn pem_body_lines(pem: &str) -> Vec<String> {
    pem.lines().filter(|line| !line.starts_with("-----") && line.len() >= 16).map(str::to_owned).collect()
}

/// Captures every log line in this test process at trace level. Process-wide, because a
/// thread-scoped subscriber misses events while other tests run in parallel; it also means the
/// other tests' logs are checked too.
fn capture() -> &'static Captured {
    static CAPTURED: OnceLock<Captured> = OnceLock::new();
    CAPTURED.get_or_init(|| {
        let captured = Captured::default();
        let subscriber = tracing_subscriber::fmt()
            .with_writer(captured.clone())
            .with_max_level(tracing::Level::TRACE)
            .with_ansi(false)
            .finish();
        tracing::subscriber::set_global_default(subscriber).unwrap();
        captured
    })
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;
    bytes.iter().fold(String::new(), |mut out, b| {
        let _ = write!(out, "{b:02x}");
        out
    })
}

#[tokio::test]
async fn no_key_csr_or_code_appears_in_the_logs() {
    let captured = capture();

    let server = TestServer::tcp().await;
    server.behave(|b| b.enrol_valid_for = 9 * DAY);
    let dir = tempfile::tempdir().unwrap();
    let roots = server.pki().agent_roots();
    let wrong = EnrolmentCode::parse("WRONG-C0DE0-00000-00000").unwrap();
    assert!(enrol::enrol(&server.target, &roots, dir.path(), &wrong, false).await.is_err());
    server.enrol(dir.path()).await;
    let first_key = std::fs::read_to_string(dir.path().join(KEY_FILE)).unwrap();

    // Running renews the 9-day certificate: a second key and CSR.
    let (options, mut events) = server.run_options(dir.path(), timing());
    let (stop, agent) = spawn_agent(options);
    next_event(&mut events, |e| matches!(e, Event::Renewed { .. })).await;
    next_event(&mut events, |e| matches!(e, Event::Welcomed { .. })).await;
    stop.send(true).unwrap();
    tokio::time::timeout(Duration::from_secs(5), agent).await.unwrap().unwrap().unwrap();
    let second_key = std::fs::read_to_string(dir.path().join(KEY_FILE)).unwrap();
    assert_ne!(first_key, second_key);

    let logs = String::from_utf8(captured.0.lock().unwrap().clone()).unwrap();
    assert!(logs.contains("enrolled") && logs.contains("renewed"), "the capture works");
    let mut secrets = vec![
        VALID_CODE.to_owned(),
        VALID_CODE.replace('-', ""),
        "WRONG-C0DE0-00000-00000".to_owned(),
        "WRONGC0DE0".to_owned(),
    ];
    secrets.extend(pem_body_lines(&first_key));
    secrets.extend(pem_body_lines(&second_key));
    let csrs = server.recorded().csrs.clone();
    assert_eq!(csrs.len(), 3, "two enrolments and one renewal");
    for csr in &csrs {
        secrets.extend(pem_body_lines(&pem::encode(&pem::Pem::new("CERTIFICATE REQUEST", csr.clone()))));
        secrets.push(hex(csr));
        secrets.push(format!("{:?}", &csr[..24]));
    }
    for secret in &secrets {
        assert!(!logs.contains(secret.as_str()), "a secret appears in the logs: {secret}");
    }
    println!("checked {} secret fragments against {} bytes of trace-level logs", secrets.len(), logs.len());
}

#[tokio::test]
async fn no_storage_credential_appears_in_the_logs() {
    let captured = capture();
    let server = TestServer::tcp().await;
    server.behave(|b| {
        b.default_stream = Stream::Scripted;
        b.ack_statuses = true;
    });
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let (mut options, _events) = server.run_options(dir.path(), timing());
    options.runner = Some(std::sync::Arc::new(DevRunner));
    let (_stop, _agent) = spawn_agent(options);
    server.wait_until(Duration::from_secs(10), |r| r.connections.first().is_some_and(|c| c.welcomed.is_some())).await;
    // Storage that refuses every connection, so each upload fails and is logged.
    let mut start = start_session(uuid::Uuid::now_v7(), "command-secrets", 120);
    let Some(ServerBody::StartSession(message)) = start.body.as_mut() else { unreachable!() };
    message.spec.as_mut().unwrap().args = vec!["--duration".to_owned(), "100ms".to_owned()];
    let grant = message.credentials.as_mut().unwrap().attempt_grant.as_mut().unwrap();
    grant.endpoint = "http://127.0.0.1:9".to_owned();
    let secrets = [grant.access_key_id.clone(), grant.secret_access_key.clone(), grant.session_token.clone()];

    server.send(start).await;

    server
        .wait_until(Duration::from_secs(20), |r| statuses(r, 0).iter().any(|(_, s)| s.state() == SessionState::Failed))
        .await;
    let failed = statuses(&server.recorded(), 0).pop().unwrap().1;
    assert_eq!(failed.error.unwrap().code, "outputs_upload_failed");
    let logs = String::from_utf8(captured.0.lock().unwrap().clone()).unwrap();
    assert!(logs.contains("an output upload failed"), "the capture works");
    for secret in &secrets {
        assert!(!logs.contains(secret.as_str()), "a storage secret appears in the logs: {secret}");
    }
}
