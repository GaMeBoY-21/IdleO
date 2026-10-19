//! A small S3 server on a local port, for testing what talks to storage without MinIO: single puts and gets, listings,
//! and the multipart calls (which it can be told to fail). It checks no signatures; the grants' own scope checks are what's
//! under test. Objects are by key, without the bucket.

use std::collections::BTreeMap;
use std::fmt::Write as _;
use std::sync::{Arc, Mutex};

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

/// What the server holds, and what it has been asked.
#[derive(Default)]
pub struct State {
    /// Every finished object, by key.
    pub objects: BTreeMap<String, Vec<u8>>,
    /// How many multipart uploads were started.
    pub started: u32,
    /// The parts of the latest upload, by number.
    pub parts: BTreeMap<u16, Vec<u8>>,
    /// Every part request that arrived, refused or not.
    pub part_requests: Vec<u16>,
    /// Part number -> how many of its requests are refused before one is taken.
    pub refuse: BTreeMap<u16, u32>,
    /// The body of the last completion request.
    pub completed_with: Option<String>,
    /// Whether an upload was aborted.
    pub aborted: bool,
    upload_key: Option<String>,
}

/// A running server.
pub struct FakeS3 {
    /// `http://127.0.0.1:<port>`.
    pub endpoint: String,
    /// The port, for composing another address for it.
    pub port: u16,
    /// Its state.
    pub state: Arc<Mutex<State>>,
}

fn decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if let (b'%', true, Ok(value)) =
            (bytes[i], i + 2 < bytes.len(), u8::from_str_radix(text.get(i + 1..i + 3).unwrap_or("zz"), 16))
        {
            out.push(value);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

impl FakeS3 {
    /// Starts a server on every interface, on a free port.
    ///
    /// # Panics
    /// If no port can be bound.
    pub async fn start() -> Self {
        let listener = TcpListener::bind("0.0.0.0:0").await.expect("a free port");
        let port = listener.local_addr().expect("an address").port();
        let state = Arc::new(Mutex::new(State::default()));
        let shared = Arc::clone(&state);
        tokio::spawn(async move {
            loop {
                let Ok((socket, _)) = listener.accept().await else { return };
                tokio::spawn(handle(socket, Arc::clone(&shared)));
            }
        });
        Self { endpoint: format!("http://127.0.0.1:{port}"), port, state }
    }
}

#[allow(clippy::too_many_lines)] // One request/response loop, clearer whole than split.
async fn handle(mut socket: tokio::net::TcpStream, state: Arc<Mutex<State>>) {
    let mut raw = Vec::new();
    let mut chunk = [0_u8; 16 * 1024];
    let (head_end, length) = loop {
        let read = socket.read(&mut chunk).await.unwrap_or(0);
        if read == 0 {
            return;
        }
        raw.extend_from_slice(&chunk[..read]);
        if let Some(end) = raw.windows(4).position(|w| w == b"\r\n\r\n") {
            let head = String::from_utf8_lossy(&raw[..end]).to_ascii_lowercase();
            let length = head
                .lines()
                .find_map(|line| line.strip_prefix("content-length: "))
                .and_then(|value| value.trim().parse::<usize>().ok())
                .unwrap_or(0);
            break (end + 4, length);
        }
    };
    while raw.len() < head_end + length {
        let read = socket.read(&mut chunk).await.unwrap_or(0);
        if read == 0 {
            return;
        }
        raw.extend_from_slice(&chunk[..read]);
    }
    let head = String::from_utf8_lossy(&raw[..head_end]).into_owned();
    let body = raw[head_end..head_end + length].to_vec();
    let method = head.split_whitespace().next().unwrap_or("").to_owned();
    let target = head.split_whitespace().nth(1).unwrap_or("/").to_owned();
    let (path, query) = target.split_once('?').unwrap_or((&target, ""));
    let key = decode(path.trim_start_matches('/').split_once('/').map_or("", |(_, key)| key));
    let param = |name: &str| query.split('&').find_map(|pair| pair.strip_prefix(&format!("{name}=")).map(decode));
    let (status, extra, content) = {
        let mut state = state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match method.as_str() {
            "POST" if query.contains("uploads") => {
                state.started += 1;
                state.parts.clear();
                state.upload_key = Some(key.clone());
                (
                    200,
                    String::new(),
                    b"<InitiateMultipartUploadResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Bucket>b</Bucket><Key>k</Key><UploadId>up-1</UploadId></InitiateMultipartUploadResult>".to_vec(),
                )
            }
            "PUT" if param("partNumber").is_some() => {
                let number: u16 = param("partNumber").and_then(|n| n.parse().ok()).unwrap_or(0);
                state.part_requests.push(number);
                match state.refuse.get_mut(&number) {
                    Some(left) if *left > 0 => {
                        *left -= 1;
                        (500, String::new(), Vec::new())
                    }
                    _ => {
                        state.parts.insert(number, body);
                        (200, format!("ETag: \"etag-{number}\"\r\n"), Vec::new())
                    }
                }
            }
            "POST" if query.contains("uploadId=") => {
                state.completed_with = Some(String::from_utf8_lossy(&body).into_owned());
                let assembled: Vec<u8> = state.parts.values().flatten().copied().collect();
                if let Some(target) = state.upload_key.clone() {
                    state.objects.insert(target, assembled);
                }
                (200, String::new(), b"<CompleteMultipartUploadResult/>".to_vec())
            }
            "DELETE" if query.contains("uploadId=") => {
                state.aborted = true;
                (204, String::new(), Vec::new())
            }
            "PUT" => {
                state.objects.insert(key, body);
                (200, String::new(), Vec::new())
            }
            "GET" if query.contains("list-type=2") => {
                let prefix = param("prefix").unwrap_or_default();
                let mut xml = String::from(
                    "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>false</IsTruncated>",
                );
                for (name, object) in state.objects.iter().filter(|(name, _)| name.starts_with(&prefix)) {
                    let _ = write!(
                        xml,
                        "<Contents><Key>{name}</Key><LastModified>2026-01-01T00:00:00.000Z</LastModified><ETag>\"e\"</ETag><Size>{}</Size></Contents>",
                        object.len()
                    );
                }
                xml.push_str("</ListBucketResult>");
                (200, String::new(), xml.into_bytes())
            }
            "GET" => match state.objects.get(&key) {
                Some(object) => (200, String::new(), object.clone()),
                None => (404, String::new(), b"<Error><Code>NoSuchKey</Code></Error>".to_vec()),
            },
            _ => (400, String::new(), Vec::new()),
        }
    };
    let reply = format!("HTTP/1.1 {status} X\r\n{extra}Content-Length: {}\r\nConnection: close\r\n\r\n", content.len());
    let _ = socket.write_all(reply.as_bytes()).await;
    let _ = socket.write_all(&content).await;
    let _ = socket.shutdown().await;
}
