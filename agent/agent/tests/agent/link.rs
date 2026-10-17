//! `link` against a scripted control plane: asking, waiting, approval, denial, expiry, the network, and the enrolment
//! that follows the ticket (which the in-process mutual-TLS server answers, as in `enrolment.rs`).

use std::collections::VecDeque;
use std::fs;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use idleo_agent::enrol::EnrolError;
use idleo_agent::link::{self, LinkError, LinkGpu, Machine, Progress, Settings};
use idleo_agent::state::{CERT_FILE, INFO_FILE, KEY_FILE};
use idleo_agent::storage;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use crate::support::{TestServer, VALID_CODE};

const LINK_ID: &str = "01a14b00-0000-7000-8000-000000000001";
const POLL_TOKEN: &str = "qwertyuiopasdfghjklzxcvbnm234567qwertyuiopasdfghjklz";

/// A control plane's REST side, scripted: it answers a link request, then each poll with the next scripted answer
/// (the last one repeats), and records every request it saw.
struct FakeApi {
    addr: SocketAddr,
    seen: Arc<Mutex<Vec<(String, String)>>>,
}

impl FakeApi {
    async fn start(polls: Vec<(u16, String)>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let script = Arc::new(Mutex::new(VecDeque::from(polls)));
        let record = Arc::clone(&seen);
        tokio::spawn(async move {
            loop {
                let Ok((mut stream, _)) = listener.accept().await else { return };
                let (record, script) = (Arc::clone(&record), Arc::clone(&script));
                tokio::spawn(async move {
                    let mut buffer = Vec::new();
                    let mut chunk = [0u8; 4096];
                    let (head, body) = loop {
                        let n = stream.read(&mut chunk).await.unwrap_or(0);
                        if n == 0 {
                            return;
                        }
                        buffer.extend_from_slice(&chunk[..n]);
                        let text = String::from_utf8_lossy(&buffer).into_owned();
                        if let Some(split) = text.find("\r\n\r\n") {
                            let head = text[..split].to_owned();
                            let length = head
                                .lines()
                                .find_map(|line| {
                                    line.to_ascii_lowercase()
                                        .strip_prefix("content-length:")
                                        .map(|v| v.trim().parse::<usize>().unwrap())
                                })
                                .unwrap_or(0);
                            if text.len() >= split + 4 + length {
                                break (head, text[split + 4..split + 4 + length].to_owned());
                            }
                        }
                    };
                    let path = head.lines().next().unwrap().split(' ').nth(1).unwrap().to_owned();
                    record.lock().unwrap().push((path.clone(), body));
                    let (status, json) = if path == "/v1/host-links" {
                        (
                            201,
                            format!(
                                "{{\"id\":\"{LINK_ID}\",\"approveUrl\":\"http://localhost:3000/link/{LINK_ID}/\",\"pollToken\":\"{POLL_TOKEN}\",\"intervalSeconds\":1,\"expiresInSeconds\":600}}"
                            ),
                        )
                    } else {
                        let mut script = script.lock().unwrap();
                        let next = if script.len() > 1 { script.pop_front() } else { script.front().cloned() };
                        next.unwrap_or((500, "{}".to_owned()))
                    };
                    let reply = format!(
                        "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{json}",
                        json.len()
                    );
                    let _ = stream.write_all(reply.as_bytes()).await;
                });
            }
        });
        Self { addr, seen }
    }

    fn url(&self) -> String {
        format!("http://{}", self.addr)
    }

    fn requests(&self) -> Vec<(String, String)> {
        self.seen.lock().unwrap().clone()
    }
}

fn pending() -> (u16, String) {
    (400, "{\"type\":\"/problems/authorization-pending\"}".to_owned())
}

fn ticket(code: &str) -> (u16, String) {
    (
        200,
        format!(
            "{{\"hostId\":\"00000000-0000-0000-0000-000000000000\",\"hostName\":\"Nikki's laptop\",\"enrolmentCode\":\"{code}\",\"codeExpiresAt\":\"2026-10-17T12:00:00Z\",\"ownerHint\":\"n\"}}"
        ),
    )
}

fn machine() -> Machine {
    Machine {
        name: "ASUS ROG Strix".to_owned(),
        os: "windows".to_owned(),
        agent_version: "0.1.0".to_owned(),
        gpus: vec![LinkGpu { model: "NVIDIA GeForce RTX 4080 Laptop GPU".to_owned(), vram_mb: 12282 }],
    }
}

fn settings(server: &TestServer, api: &FakeApi, dir: &std::path::Path, force: bool) -> Settings {
    Settings {
        api_url: api.url(),
        machine: machine(),
        target: server.target.clone(),
        roots: server.pki().agent_roots(),
        data_dir: dir.to_owned(),
        force,
        poll_interval: Some(Duration::from_millis(10)),
    }
}

async fn run(settings: &Settings) -> (Result<link::Linked, LinkError>, Vec<Progress>) {
    let client = storage::http_client().unwrap();
    let mut steps = Vec::new();
    let result = link::link(settings, &client, &mut |step| steps.push(step)).await;
    (result, steps)
}

fn saved(dir: &std::path::Path) -> Vec<String> {
    let mut names: Vec<String> = fs::read_dir(dir)
        .map(|entries| entries.map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect())
        .unwrap_or_default();
    names.sort();
    names
}

#[tokio::test]
async fn an_approved_link_waits_then_enrols_with_the_ticket_and_saves_the_identity() {
    let server = TestServer::tcp().await;
    let api = FakeApi::start(vec![pending(), pending(), ticket(VALID_CODE)]).await;
    let dir = tempfile::tempdir().unwrap();

    let (result, steps) = run(&settings(&server, &api, dir.path(), false)).await;

    let linked = result.unwrap();
    assert_eq!(linked.host_id, server.host_id());
    assert_eq!(linked.host_name, "Nikki's laptop");
    assert_eq!(linked.owner_hint.as_deref(), Some("n"));
    assert_eq!(saved(dir.path()), [CERT_FILE, INFO_FILE, KEY_FILE]);
    // The caller is told the address to open, then that it's waiting, then that it's linking.
    assert_eq!(steps[0], Progress::Asking);
    assert_eq!(
        steps[1],
        Progress::ApproveAt {
            url: format!("http://localhost:3000/link/{LINK_ID}/"),
            expires_in: Duration::from_secs(600)
        }
    );
    assert_eq!(steps[2], Progress::Waiting);
    assert_eq!(steps.last(), Some(&Progress::Enrolling));
    // What was asked: the machine's own words, then the poll token on each poll.
    let requests = api.requests();
    assert_eq!(requests[0].0, "/v1/host-links");
    let body: serde_json::Value = serde_json::from_str(&requests[0].1).unwrap();
    assert_eq!(body["machineName"], "ASUS ROG Strix");
    assert_eq!(body["gpus"][0]["vramMb"], 12282);
    assert_eq!(requests.len(), 4);
    for (path, body) in &requests[1..] {
        assert_eq!(path, &format!("/v1/host-links/{LINK_ID}/ticket"));
        assert!(body.contains(POLL_TOKEN));
    }
}

#[tokio::test]
async fn a_denied_link_saves_nothing_and_exits_2() {
    let server = TestServer::tcp().await;
    let api = FakeApi::start(vec![pending(), (400, "{\"type\":\"/problems/host-link-denied\"}".to_owned())]).await;
    let dir = tempfile::tempdir().unwrap();

    let (result, _) = run(&settings(&server, &api, dir.path(), false)).await;

    let error = result.unwrap_err();
    assert!(matches!(error, LinkError::Denied));
    assert_eq!(error.exit_code(), 2);
    assert_eq!(saved(dir.path()), Vec::<String>::new());
}

#[tokio::test]
async fn an_expired_link_saves_nothing_and_exits_3() {
    let server = TestServer::tcp().await;
    let api = FakeApi::start(vec![(400, "{\"type\":\"/problems/host-link-expired\"}".to_owned())]).await;
    let dir = tempfile::tempdir().unwrap();

    let (result, _) = run(&settings(&server, &api, dir.path(), false)).await;

    let error = result.unwrap_err();
    assert!(matches!(error, LinkError::Expired));
    assert_eq!(error.exit_code(), 3);
    assert_eq!(saved(dir.path()), Vec::<String>::new());
}

#[tokio::test]
async fn a_control_plane_that_forgot_the_link_is_the_same_as_one_that_ran_out() {
    let server = TestServer::tcp().await;
    let api = FakeApi::start(vec![(404, "{\"type\":\"/problems/not-found\"}".to_owned())]).await;
    let dir = tempfile::tempdir().unwrap();

    let (result, _) = run(&settings(&server, &api, dir.path(), false)).await;

    assert!(matches!(result.unwrap_err(), LinkError::Expired));
}

#[tokio::test]
async fn an_unreachable_control_plane_exits_4_before_anything_is_saved() {
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();
    // A port nothing listens on: bind one, note it, and let it go.
    let closed = TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap();
    let mut settings = settings(&server, &FakeApi::start(vec![]).await, dir.path(), false);
    settings.api_url = format!("http://{closed}");

    let (result, _) = run(&settings).await;

    let error = result.unwrap_err();
    assert!(matches!(error, LinkError::Network(_)), "{error}");
    assert_eq!(error.exit_code(), 4);
    assert_eq!(saved(dir.path()), Vec::<String>::new());
}

#[tokio::test]
async fn polls_that_fail_for_a_while_end_in_a_network_error() {
    let server = TestServer::tcp().await;
    let api = FakeApi::start(vec![(503, "{}".to_owned())]).await;
    let dir = tempfile::tempdir().unwrap();

    let (result, _) = run(&settings(&server, &api, dir.path(), false)).await;

    assert!(matches!(result.unwrap_err(), LinkError::Network(_)));
}

#[tokio::test]
async fn a_machine_that_is_already_linked_isnt_asked_again_unless_forced() {
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    let api = FakeApi::start(vec![ticket(VALID_CODE)]).await;

    let (result, steps) = run(&settings(&server, &api, dir.path(), false)).await;

    let error = result.unwrap_err();
    assert!(matches!(error, LinkError::AlreadyEnrolled(_)));
    assert_eq!(error.exit_code(), 5);
    assert_eq!(steps, Vec::<Progress>::new());
    assert_eq!(api.requests(), Vec::<(String, String)>::new(), "it didn't even ask");

    let (result, _) = run(&settings(&server, &api, dir.path(), true)).await;
    result.unwrap();
}

#[tokio::test]
async fn a_ticket_the_enrolment_refuses_is_an_error_and_saves_nothing() {
    let server = TestServer::tcp().await;
    let api = FakeApi::start(vec![ticket("ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ".replace('Z', "9").as_str())]).await;
    let dir = tempfile::tempdir().unwrap();

    let (result, _) = run(&settings(&server, &api, dir.path(), false)).await;

    assert!(matches!(result.unwrap_err(), LinkError::Enrol(EnrolError::CodeRefused)));
    assert_eq!(saved(dir.path()), Vec::<String>::new());
}
