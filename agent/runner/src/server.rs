//! The runner's socket (ADR 0035): a Unix socket only root can open, speaking [`jobcore::protocol`]. The agent doesn't
//! connect to it directly; `idleo-runner attach` (attach.rs) bridges a process's stdin and stdout to it, so the one
//! transport works the same through `wsl.exe` on Windows and `docker exec` in development.
//!
//! Each connection gets every event, and the reply to each of its own requests, which are answered concurrently: a long
//! pull never holds up a stop. A line with another protocol version is refused and the connection closed.

use std::io;
use std::path::Path;
use std::sync::Arc;

use jobcore::protocol::{Body, DecodeError, Failure, Frame, MAX_LINE_BYTES, Outcome, code};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{broadcast, mpsc};

use crate::session::Registry;

/// Binds the socket (root only) and serves until the process ends.
///
/// # Errors
/// If the socket can't be made.
pub async fn serve(registry: Arc<Registry>, socket: &Path) -> io::Result<()> {
    let listener = bind(socket)?;
    loop {
        let (stream, _) = listener.accept().await?;
        let registry = Arc::clone(&registry);
        tokio::spawn(async move {
            if let Err(error) = connection(registry, stream).await {
                tracing::debug!(%error, "a connection ended");
            }
        });
    }
}

/// The socket, in a folder only root can enter, itself only root's to open.
///
/// # Errors
/// If it can't be made.
pub fn bind(socket: &Path) -> io::Result<UnixListener> {
    use std::os::unix::fs::PermissionsExt;
    if let Some(folder) = socket.parent() {
        std::fs::create_dir_all(folder)?;
        std::fs::set_permissions(folder, std::fs::Permissions::from_mode(0o700))?;
    }
    match std::fs::remove_file(socket) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(e) => return Err(e),
    }
    let listener = UnixListener::bind(socket)?;
    std::fs::set_permissions(socket, std::fs::Permissions::from_mode(0o600))?;
    Ok(listener)
}

/// One line, read without ever holding more than [`MAX_LINE_BYTES`] of it.
async fn read_line<R: AsyncBufReadExt + Unpin>(reader: &mut R) -> io::Result<Option<String>> {
    let mut line = Vec::new();
    let read = reader.take(u64::try_from(MAX_LINE_BYTES).unwrap_or(u64::MAX) + 1).read_until(b'\n', &mut line).await?;
    if read == 0 {
        return Ok(None);
    }
    if line.len() > MAX_LINE_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "a line is longer than the protocol allows"));
    }
    Ok(Some(String::from_utf8_lossy(&line).into_owned()))
}

async fn connection(registry: Arc<Registry>, stream: UnixStream) -> io::Result<()> {
    let (read, mut write) = stream.into_split();
    let (lines, mut outgoing) = mpsc::channel::<String>(1024);
    let writer = tokio::spawn(async move {
        while let Some(line) = outgoing.recv().await {
            if write.write_all(line.as_bytes()).await.is_err() || write.flush().await.is_err() {
                break;
            }
        }
    });
    let mut events = registry.subscribe();
    let forward = lines.clone();
    let forwarder = tokio::spawn(async move {
        loop {
            match events.recv().await {
                Ok(line) => {
                    if forward.send(line).await.is_err() {
                        return;
                    }
                }
                // Events are hints; a connection that fell behind carries on, and the agent's next list is the truth.
                Err(broadcast::error::RecvError::Lagged(missed)) => {
                    tracing::warn!(missed, "a connection missed some events");
                }
                Err(broadcast::error::RecvError::Closed) => return,
            }
        }
    });

    let mut reader = BufReader::new(read);
    let result = loop {
        let line = match read_line(&mut reader).await {
            Ok(Some(line)) => line,
            Ok(None) => break Ok(()),
            Err(error) => break Err(error),
        };
        match Frame::decode(&line) {
            Ok(Frame { body: Body::Request { id, req }, .. }) => {
                let (registry, lines) = (Arc::clone(&registry), lines.clone());
                tokio::spawn(async move {
                    let outcome = registry.answer(req).await;
                    let _ = lines.send(Frame::reply(id, outcome)).await;
                });
            }
            Ok(_) => {
                let _ = lines
                    .send(Frame::reply(0, Outcome::Err(Failure::new(code::BAD_REQUEST, "only requests are accepted"))))
                    .await;
            }
            Err(DecodeError::Version(version)) => {
                let message = format!("protocol version {version} isn't {}", jobcore::protocol::VERSION);
                let _ =
                    lines.send(Frame::reply(0, Outcome::Err(Failure::new(code::UNSUPPORTED_VERSION, message)))).await;
                break Ok(());
            }
            Err(error) => {
                let _ =
                    lines.send(Frame::reply(0, Outcome::Err(Failure::new(code::BAD_REQUEST, error.to_string())))).await;
            }
        }
    };
    forwarder.abort();
    drop(lines);
    let _ = tokio::time::timeout(std::time::Duration::from_secs(2), writer).await;
    result
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::time::Duration;

    use jobcore::protocol::{Answer, Event, Request};
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

    use super::*;
    use crate::testing::{FakeMachine, MemoryStorage, TokioClock};

    struct Client {
        lines: tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
        write: tokio::net::unix::OwnedWriteHalf,
        _folder: tempfile::TempDir,
    }

    async fn connect() -> (Client, Arc<FakeMachine>) {
        let folder = tempfile::tempdir().unwrap();
        let socket = folder.path().join("runner.sock");
        let machine = FakeMachine::new();
        let registry = Registry::with_tick(
            Arc::clone(&machine) as Arc<dyn crate::machine::Machine>,
            MemoryStorage::new() as Arc<dyn crate::storage::Storage>,
            TokioClock::new(),
            Duration::from_millis(10),
        );
        let listener = bind(&socket).unwrap();
        tokio::spawn(async move {
            loop {
                let (stream, _) = listener.accept().await.unwrap();
                tokio::spawn(connection(Arc::clone(&registry), stream));
            }
        });
        let (read, write) = UnixStream::connect(&socket).await.unwrap().into_split();
        (Client { lines: BufReader::new(read).lines(), write, _folder: folder }, machine)
    }

    impl Client {
        async fn send(&mut self, line: &str) {
            self.write.write_all(line.as_bytes()).await.unwrap();
        }

        async fn next(&mut self) -> Option<Frame> {
            let line = tokio::time::timeout(Duration::from_secs(5), self.lines.next_line())
                .await
                .expect("a line in time")
                .unwrap()?;
            Some(Frame::decode(&line).unwrap())
        }
    }

    #[tokio::test]
    async fn a_request_gets_its_reply_with_the_same_id() {
        let (mut client, _machine) = connect().await;
        client.send(&Frame::request(7, Request::Hello)).await;
        client.send(&Frame::request(8, Request::List)).await;
        let mut answers = Vec::new();
        for _ in 0..2 {
            let Some(Frame { body: Body::Reply { id, result: Outcome::Ok(answer) }, .. }) = client.next().await else {
                panic!("a reply")
            };
            answers.push((id, answer));
        }
        answers.sort_by_key(|(id, _)| *id);
        assert!(matches!(&answers[0], (7, Answer::Hello(hello)) if hello.rules_loaded && !hello.development));
        assert_eq!(answers[1], (8, Answer::List { sessions: Vec::new() }));
    }

    #[tokio::test]
    async fn another_version_is_refused_and_the_connection_closed() {
        let (mut client, _machine) = connect().await;
        client.send("{\"v\":2,\"request\":{\"id\":1,\"req\":{\"type\":\"hello\"}}}\n").await;
        let Some(Frame { body: Body::Reply { result: Outcome::Err(failure), .. }, .. }) = client.next().await else {
            panic!("a refusal")
        };
        assert_eq!(failure.code, code::UNSUPPORTED_VERSION);
        assert!(client.next().await.is_none(), "closed");
    }

    #[tokio::test]
    async fn a_line_that_isnt_a_request_is_answered_and_the_connection_carries_on() {
        let (mut client, _machine) = connect().await;
        client.send("garbage\n").await;
        let Some(Frame { body: Body::Reply { result: Outcome::Err(failure), .. }, .. }) = client.next().await else {
            panic!("a refusal")
        };
        assert_eq!(failure.code, code::BAD_REQUEST);
        client.send(&Frame::request(1, Request::List)).await;
        assert!(matches!(client.next().await, Some(Frame { body: Body::Reply { id: 1, .. }, .. })));
    }

    #[tokio::test]
    async fn every_connection_hears_the_events_of_a_session() {
        let (mut client, machine) = connect().await;
        let start = crate::session_tests::sample_start(1);
        client.send(&Frame::request(1, Request::Start(Box::new(start)))).await;
        let mut phases = Vec::new();
        let mut replied = false;
        while phases.len() < 2 || !replied {
            match client.next().await.unwrap().body {
                Body::Reply { id: 1, result: Outcome::Ok(Answer::Done) } => replied = true,
                Body::Event { event: Event::State { phase }, .. } => phases.push(phase),
                other => panic!("unexpected {other:?}"),
            }
        }
        assert_eq!(phases, [jobcore::protocol::Phase::Preparing, jobcore::protocol::Phase::Running]);
        machine.container().await.finish(0);
        loop {
            if let Body::Event { event: Event::Exited { ended }, .. } = client.next().await.unwrap().body {
                assert_eq!(ended, jobcore::protocol::Ended::Succeeded);
                break;
            }
        }
    }

    #[tokio::test]
    async fn the_socket_is_only_roots_to_open() {
        use std::os::unix::fs::PermissionsExt;
        let folder = tempfile::tempdir().unwrap();
        let socket = folder.path().join("run/runner.sock");
        let _listener = bind(&socket).unwrap();
        assert_eq!(std::fs::metadata(&socket).unwrap().permissions().mode() & 0o777, 0o600);
        assert_eq!(std::fs::metadata(socket.parent().unwrap()).unwrap().permissions().mode() & 0o777, 0o700);
    }
}
