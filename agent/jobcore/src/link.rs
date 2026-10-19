//! The agent's end of the runner transport (ADR 0035): a [`Link`] spawns `idleo-runner attach` (through `wsl.exe` on
//! Windows, `docker exec -i` in development, whatever [`Spawner`] says), speaks [`protocol`](crate::protocol) over its stdin
//! and stdout, answers each request with its reply, and hands out the runner's events. One implementation, used by the agent,
//! the hostile tests and the end-to-end scripts' tools.
//!
//! A link that loses its process fails the requests waiting on it with `link_lost` and connects again for the next request
//! (after a short pause if the last try failed), so a runner that restarts, or a `wsl --terminate` of an idle distribution,
//! is only a slower request.

use std::collections::HashMap;
use std::io;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::sync::{broadcast, mpsc, oneshot, watch};

use crate::protocol::{Answer, Body, DecodeError, Event, Failure, Frame, MAX_LINE_BYTES, Outcome, Request, code};

/// The failure code of a request the link couldn't carry.
pub const LINK_LOST: &str = "link_lost";

/// How long a request waits for its reply, unless it says otherwise.
pub const REPLY_WITHIN: Duration = Duration::from_secs(60);
/// The pause before connecting again after a connection that failed or ended at once.
const RECONNECT_PAUSE: Duration = Duration::from_secs(1);

type Reader = Pin<Box<dyn AsyncRead + Send>>;
type Writer = Pin<Box<dyn AsyncWrite + Send>>;

/// A started connection: what to read from, what to write to, and the process behind it (kept so it's killed when the link
/// drops it).
pub struct Connection {
    /// The runner's lines.
    pub reader: Reader,
    /// The agent's lines.
    pub writer: Writer,
    /// Held until the connection ends; dropping it ends the process.
    pub keep: Box<dyn Send>,
}

/// Starts a connection to the runner.
pub type Spawner = Arc<dyn Fn() -> io::Result<Connection> + Send + Sync>;

/// A runner's event, with who it's about.
#[derive(Debug, Clone, PartialEq)]
pub struct RunnerEvent {
    /// The session; empty for a pull's progress.
    pub session: String,
    /// Its attempt.
    pub attempt: i32,
    /// What happened.
    pub event: Event,
}

type Pending = Arc<Mutex<HashMap<u64, oneshot::Sender<Outcome>>>>;

struct Live {
    lines: mpsc::Sender<String>,
    pending: Pending,
    /// Becomes true when the connection has ended.
    ended: watch::Receiver<bool>,
}

struct Inner {
    spawner: Spawner,
    live: tokio::sync::Mutex<Option<Live>>,
    last_failure: Mutex<Option<tokio::time::Instant>>,
    events: broadcast::Sender<RunnerEvent>,
    next_id: AtomicU64,
    /// How many connections have been made: a change tells whoever's listening that the runner may have been missed.
    generation: watch::Sender<u64>,
}

/// The agent's link to its runner.
#[derive(Clone)]
pub struct Link {
    inner: Arc<Inner>,
}

impl Link {
    /// A link that connects through `spawner` when it's first used.
    #[must_use]
    pub fn new(spawner: Spawner) -> Self {
        Self {
            inner: Arc::new(Inner {
                spawner,
                live: tokio::sync::Mutex::new(None),
                last_failure: Mutex::new(None),
                events: broadcast::channel(4096).0,
                next_id: AtomicU64::new(1),
                generation: watch::channel(0).0,
            }),
        }
    }

    /// A link over a process started by `command` each time it connects, with `kill_on_drop` set.
    #[must_use]
    pub fn process(command: impl Fn() -> tokio::process::Command + Send + Sync + 'static) -> Self {
        Self::new(Arc::new(move || {
            let mut command = command();
            command
                .stdin(std::process::Stdio::piped())
                .stdout(std::process::Stdio::piped())
                .stderr(std::process::Stdio::null())
                .kill_on_drop(true);
            let mut child = command.spawn()?;
            let (stdin, stdout) = (
                child.stdin.take().ok_or_else(|| io::Error::other("no stdin"))?,
                child.stdout.take().ok_or_else(|| io::Error::other("no stdout"))?,
            );
            Ok(Connection { reader: Box::pin(stdout), writer: Box::pin(stdin), keep: Box::new(child) })
        }))
    }

    /// Every event from now on.
    #[must_use]
    pub fn events(&self) -> broadcast::Receiver<RunnerEvent> {
        self.inner.events.subscribe()
    }

    /// A counter that goes up each time the link connects: events may have been missed across the change.
    #[must_use]
    pub fn connections(&self) -> watch::Receiver<u64> {
        self.inner.generation.subscribe()
    }

    async fn connected(&self) -> Result<(mpsc::Sender<String>, Pending), Failure> {
        let mut live = self.inner.live.lock().await;
        if let Some(current) = live.as_ref().filter(|current| !*current.ended.borrow()) {
            return Ok((current.lines.clone(), Arc::clone(&current.pending)));
        }
        *live = None;
        let pause = self
            .inner
            .last_failure
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .map(|at| RECONNECT_PAUSE.saturating_sub(at.elapsed()));
        if let Some(pause) = pause.filter(|pause| !pause.is_zero()) {
            tokio::time::sleep(pause).await;
        }
        let connection = (self.inner.spawner)().map_err(|e| {
            *self.inner.last_failure.lock().unwrap_or_else(PoisonError::into_inner) = Some(tokio::time::Instant::now());
            Failure::new(LINK_LOST, format!("the runner can't be reached: {e}"))
        })?;
        let pending: Pending = Arc::default();
        let (lines, outgoing) = mpsc::channel(256);
        let (ended_tx, ended) = watch::channel(false);
        tokio::spawn(run(connection, outgoing, Arc::clone(&pending), self.inner.events.clone(), ended_tx));
        self.inner.generation.send_modify(|generation| *generation += 1);
        let attached = (lines.clone(), Arc::clone(&pending));
        *live = Some(Live { lines, pending, ended });
        Ok(attached)
    }

    /// Sends a request and waits for its reply, up to [`REPLY_WITHIN`].
    ///
    /// # Errors
    /// The runner's refusal, or [`LINK_LOST`] if it couldn't be reached or the connection ended first.
    pub async fn request(&self, request: Request) -> Result<Answer, Failure> {
        self.request_within(request, REPLY_WITHIN).await
    }

    /// The same, waiting up to `within`.
    ///
    /// # Errors
    /// As [`Link::request`].
    pub async fn request_within(&self, request: Request, within: Duration) -> Result<Answer, Failure> {
        let (lines, pending) = self.connected().await?;
        let id = self.inner.next_id.fetch_add(1, Ordering::SeqCst);
        let (reply, answer) = oneshot::channel();
        pending.lock().unwrap_or_else(PoisonError::into_inner).insert(id, reply);
        if lines.send(Frame::request(id, request)).await.is_err() {
            pending.lock().unwrap_or_else(PoisonError::into_inner).remove(&id);
            return Err(Failure::new(LINK_LOST, "the runner's connection ended"));
        }
        let outcome = match tokio::time::timeout(within, answer).await {
            Ok(Ok(outcome)) => outcome,
            Ok(Err(_)) => return Err(Failure::new(LINK_LOST, "the runner's connection ended")),
            Err(_) => {
                pending.lock().unwrap_or_else(PoisonError::into_inner).remove(&id);
                return Err(Failure::new(LINK_LOST, "the runner didn't answer in time"));
            }
        };
        match outcome {
            Outcome::Ok(answer) => Ok(answer),
            Outcome::Err(failure) => Err(failure),
        }
    }

    /// Sends a request nobody waits for (a lease renewal, a stop from a dropped future): it's tried once, in the background.
    pub fn notify(&self, request: Request) {
        let link = self.clone();
        tokio::spawn(async move {
            if let Err(failure) = link.request(request).await {
                tracing::debug!(%failure, "a request to the runner failed");
            }
        });
    }
}

async fn run(
    connection: Connection,
    mut outgoing: mpsc::Receiver<String>,
    pending: Pending,
    events: broadcast::Sender<RunnerEvent>,
    ended: watch::Sender<bool>,
) {
    let Connection { reader, mut writer, keep } = connection;
    let writing = async {
        while let Some(line) = outgoing.recv().await {
            if writer.write_all(line.as_bytes()).await.is_err() || writer.flush().await.is_err() {
                return;
            }
        }
    };
    let reading = async {
        let mut lines = BufReader::new(reader);
        let mut line = String::new();
        loop {
            line.clear();
            match lines.read_line(&mut line).await {
                Ok(0) | Err(_) => return,
                Ok(_) if line.len() > MAX_LINE_BYTES => return,
                Ok(_) => {}
            }
            match Frame::decode(&line) {
                Ok(Frame { body: Body::Reply { id, result }, .. }) => {
                    if let Some(reply) = pending.lock().unwrap_or_else(PoisonError::into_inner).remove(&id) {
                        let _ = reply.send(result);
                    } else if let (0, Outcome::Err(failure)) = (id, &result) {
                        // The runner refused a line it couldn't read, with no request to answer: nothing more will work.
                        tracing::warn!(%failure, "the runner refused a line");
                        if failure.code == code::UNSUPPORTED_VERSION {
                            return;
                        }
                    }
                }
                Ok(Frame { body: Body::Event { session, attempt, event }, .. }) => {
                    let _ = events.send(RunnerEvent { session, attempt, event });
                }
                Ok(_) => {}
                Err(DecodeError::Version(version)) => {
                    tracing::warn!(version, "the runner speaks another protocol version");
                    return;
                }
                Err(error) => tracing::debug!(%error, "a line from the runner couldn't be read"),
            }
        }
    };
    tokio::select! {
        () = writing => {}
        () = reading => {}
    }
    drop(keep);
    // Whoever is still waiting is told the connection is gone: dropping their senders does it.
    pending.lock().unwrap_or_else(PoisonError::into_inner).clear();
    let _ = ended.send(true);
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::AtomicUsize;

    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, duplex};

    use super::*;
    use crate::protocol::Phase;

    /// A scripted runner on the other end of an in-memory pipe: it answers `list`, refuses everything else, takes 30 s over a
    /// `pull`, and says something unasked before each reply. With `die_first`, its first connection ends on its first line.
    fn spawner(connections: Arc<AtomicUsize>, die_first: bool) -> Spawner {
        Arc::new(move || {
            let number = connections.fetch_add(1, Ordering::SeqCst) + 1;
            let (agent_read, mut runner_write) = duplex(64 * 1024);
            let (runner_read, agent_write) = duplex(64 * 1024);
            let (out, mut outgoing) = mpsc::channel::<String>(64);
            tokio::spawn(async move {
                while let Some(line) = outgoing.recv().await {
                    if runner_write.write_all(line.as_bytes()).await.is_err() {
                        return;
                    }
                }
            });
            tokio::spawn(async move {
                let mut lines = BufReader::new(runner_read).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    let Ok(Frame { body: Body::Request { id, req }, .. }) = Frame::decode(&(line + "\n")) else {
                        continue;
                    };
                    if die_first && number == 1 {
                        return;
                    }
                    let out = out.clone();
                    tokio::spawn(async move {
                        let reply = match req {
                            Request::List => Outcome::Ok(Answer::List { sessions: Vec::new() }),
                            Request::Pull { .. } => {
                                tokio::time::sleep(Duration::from_secs(30)).await;
                                Outcome::Ok(Answer::Done)
                            }
                            _ => Outcome::Err(Failure::new(code::BAD_REQUEST, "no")),
                        };
                        let _ = out.send(Frame::event("s", 1, Event::State { phase: Phase::Running })).await;
                        let _ = out.send(Frame::reply(id, reply)).await;
                    });
                }
            });
            Ok(Connection { reader: Box::pin(agent_read), writer: Box::pin(agent_write), keep: Box::new(()) })
        })
    }

    #[tokio::test]
    async fn a_request_gets_its_reply_and_the_runners_events_are_handed_out() {
        let link = Link::new(spawner(Arc::default(), false));
        let mut events = link.events();

        assert_eq!(link.request(Request::List).await, Ok(Answer::List { sessions: Vec::new() }));
        assert_eq!(
            link.request(Request::Hello).await.unwrap_err().code,
            code::BAD_REQUEST,
            "a refusal comes back as one"
        );

        let event = tokio::time::timeout(Duration::from_secs(2), events.recv()).await.unwrap().unwrap();
        assert_eq!(
            event,
            RunnerEvent { session: "s".to_owned(), attempt: 1, event: Event::State { phase: Phase::Running } }
        );
    }

    #[tokio::test]
    async fn requests_are_answered_whatever_order_the_replies_come_in() {
        let link = Link::new(spawner(Arc::default(), false));
        let slow = {
            let link = link.clone();
            tokio::spawn(async move {
                link.request_within(Request::Pull { image: "x".to_owned() }, Duration::from_secs(60)).await
            })
        };
        tokio::time::sleep(Duration::from_millis(50)).await;
        assert!(link.request(Request::List).await.is_ok(), "a long pull doesn't hold the next request up");
        slow.abort();
    }

    #[tokio::test]
    async fn a_request_the_runner_never_answers_gives_up() {
        let link = Link::new(spawner(Arc::default(), false));
        let failure =
            link.request_within(Request::Pull { image: "x".to_owned() }, Duration::from_millis(100)).await.unwrap_err();
        assert_eq!(failure.code, LINK_LOST);
    }

    #[tokio::test]
    async fn a_lost_connection_fails_what_waited_on_it_and_the_next_request_connects_again() {
        let connections = Arc::new(AtomicUsize::new(0));
        let link = Link::new(spawner(Arc::clone(&connections), true));
        let mut generations = link.connections();

        let failure = link.request(Request::List).await.unwrap_err();
        assert_eq!(failure.code, LINK_LOST);
        assert!(generations.has_changed().unwrap(), "the first connection was announced");
        generations.borrow_and_update();

        assert_eq!(link.request(Request::List).await, Ok(Answer::List { sessions: Vec::new() }));
        assert_eq!(connections.load(Ordering::SeqCst), 2, "it connected again");
        assert!(generations.has_changed().unwrap(), "and said so");
    }

    #[tokio::test]
    async fn a_spawner_that_fails_is_a_failed_request_not_a_panic() {
        let link = Link::new(Arc::new(|| Err(io::Error::other("no such distribution"))));
        let failure = link.request(Request::Hello).await.unwrap_err();
        assert_eq!(failure.code, LINK_LOST);
        assert!(failure.message.contains("no such distribution"));
    }

    #[tokio::test]
    async fn another_protocol_version_ends_the_connection() {
        let link = Link::new(Arc::new(|| {
            let (agent_read, mut runner_write) = duplex(1024);
            let (runner_read, agent_write) = duplex(1024);
            tokio::spawn(async move {
                let mut lines = BufReader::new(runner_read).lines();
                let _ = lines.next_line().await;
                let _ = runner_write.write_all(b"{\"v\":9,\"reply\":{\"id\":1}}\n").await;
                tokio::time::sleep(Duration::from_secs(5)).await;
            });
            Ok(Connection { reader: Box::pin(agent_read), writer: Box::pin(agent_write), keep: Box::new(()) })
        }));
        let failure = link.request(Request::Hello).await.unwrap_err();
        assert_eq!(failure.code, LINK_LOST);
    }
}
