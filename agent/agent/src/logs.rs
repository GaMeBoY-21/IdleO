//! A session attempt's logs (ADR 0019): one sink per attempt, independent of any runner. The runner writes
//! bytes into a [`LogWriter`]; the sink uploads them in numbered chunks through the attempt grant, to
//! `sessions/{id}/attempt-{n}/logs/{seq}.log` (8 digits, from 1), and tells the supervisor the highest seq that
//! has finished uploading, which goes to the control plane as `latest_log_seq`.
//!
//! - A chunk is cut every [`CHUNK_EVERY`] or every [`CHUNK_BYTES`], whichever comes first.
//! - At most [`CAP`] bytes per attempt are kept. Past it, one last line, [`LIMIT_LINE`], and the rest is dropped:
//!   the writer refuses it, so memory stays bounded however much a job prints.
//! - Chunks wait in order; a failed upload is retried on the next tick. Writing never waits on storage, so a log
//!   upload never blocks, slows or fails the job.
//! - When the attempt ends, the sink cuts what's left and keeps trying for up to [`FLUSH_FOR`], then reports
//!   [`LogEvent::Flushed`]; the supervisor sends the terminal status only after that.

use std::collections::VecDeque;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::time::Duration;

use tokio::sync::{mpsc, watch};
use tokio::task::JoinHandle;

use crate::storage::ObjectStore;

/// A chunk is cut at least this often while there's output.
pub const CHUNK_EVERY: Duration = Duration::from_secs(5);
/// A chunk is cut once this much output is waiting.
pub const CHUNK_BYTES: usize = 64 * 1024;
/// The most output kept per attempt.
pub const CAP: usize = 10 * 1024 * 1024;
/// Written once, after the cap.
pub const LIMIT_LINE: &str = "[log limit reached; later output isn't kept]\n";
/// How long the last chunks may take to upload once the attempt ends.
pub const FLUSH_FOR: Duration = Duration::from_secs(10);

/// What the sink tells the supervisor.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogEvent {
    /// Chunk `seq` and every one before it are uploaded.
    Uploaded(i32),
    /// The attempt ended and the sink is done: the highest seq uploaded, 0 for none.
    Flushed(i32),
}

/// Where a runner writes its output. Cheap to clone; writing never blocks.
#[derive(Clone)]
pub struct LogWriter {
    bytes: mpsc::UnboundedSender<Vec<u8>>,
    accepted: Arc<AtomicUsize>,
    limited: Arc<AtomicBool>,
}

impl LogWriter {
    /// Keeps `bytes`, or as much as fits under the cap; the first write past the cap adds [`LIMIT_LINE`].
    pub fn write(&self, bytes: impl Into<Vec<u8>>) {
        let mut bytes = bytes.into();
        if bytes.is_empty() || self.limited.load(Ordering::SeqCst) {
            return;
        }
        let before = self.accepted.fetch_add(bytes.len(), Ordering::SeqCst);
        if before + bytes.len() > CAP {
            bytes.truncate(CAP.saturating_sub(before));
            if !self.limited.swap(true, Ordering::SeqCst) {
                if !bytes.is_empty() && !bytes.ends_with(b"\n") {
                    bytes.push(b'\n');
                }
                bytes.extend_from_slice(LIMIT_LINE.as_bytes());
            }
        }
        if !bytes.is_empty() {
            let _ = self.bytes.send(bytes);
        }
    }

    /// One line of text, with its newline.
    pub fn line(&self, text: impl AsRef<str>) {
        let mut bytes = text.as_ref().as_bytes().to_vec();
        bytes.push(b'\n');
        self.write(bytes);
    }

    /// A writer whose output goes nowhere, for code that runs without a sink.
    #[must_use]
    pub fn discard() -> Self {
        let (bytes, _) = mpsc::unbounded_channel();
        Self { bytes, accepted: Arc::default(), limited: Arc::default() }
    }
}

/// Starts the sink for one attempt: it uploads under `logs_prefix` (`.../attempt-{n}/logs/`) through `store`,
/// reports through `events`, and finishes once `finish` becomes true.
pub fn spawn(
    store: Arc<dyn ObjectStore>,
    logs_prefix: String,
    finish: watch::Receiver<bool>,
    events: impl Fn(LogEvent) + Send + 'static,
) -> (LogWriter, JoinHandle<()>) {
    let (bytes, received) = mpsc::unbounded_channel();
    let writer = LogWriter { bytes, accepted: Arc::default(), limited: Arc::default() };
    let sink = Sink {
        store,
        prefix: logs_prefix,
        buffer: Vec::new(),
        pending: VecDeque::new(),
        next_seq: 1,
        uploaded: 0,
        events: Box::new(events),
    };
    (writer, tokio::spawn(sink.run(received, finish)))
}

struct Sink {
    store: Arc<dyn ObjectStore>,
    prefix: String,
    buffer: Vec<u8>,
    /// Cut chunks not yet uploaded, oldest first.
    pending: VecDeque<(i32, Vec<u8>)>,
    next_seq: i32,
    uploaded: i32,
    events: Box<dyn Fn(LogEvent) + Send>,
}

impl Sink {
    async fn run(mut self, mut received: mpsc::UnboundedReceiver<Vec<u8>>, mut finish: watch::Receiver<bool>) {
        let mut ticks = tokio::time::interval_at(tokio::time::Instant::now() + CHUNK_EVERY, CHUNK_EVERY);
        ticks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        let mut writers_gone = false;
        loop {
            if *finish.borrow_and_update() {
                break;
            }
            tokio::select! {
                bytes = received.recv(), if !writers_gone => match bytes {
                    Some(bytes) => {
                        self.buffer.extend_from_slice(&bytes);
                        if self.buffer.len() >= CHUNK_BYTES {
                            self.cut();
                            self.upload().await;
                        }
                    }
                    None => writers_gone = true,
                },
                _ = ticks.tick() => {
                    self.cut();
                    self.upload().await;
                }
                changed = finish.changed() => {
                    if changed.is_err() {
                        break;
                    }
                }
            }
        }
        while let Ok(bytes) = received.try_recv() {
            self.buffer.extend_from_slice(&bytes);
        }
        self.cut();
        let deadline = tokio::time::Instant::now() + FLUSH_FOR;
        while !self.pending.is_empty() && tokio::time::Instant::now() < deadline {
            self.upload().await;
            if !self.pending.is_empty() {
                tokio::time::sleep(Duration::from_secs(1).min(deadline - tokio::time::Instant::now())).await;
            }
        }
        if !self.pending.is_empty() {
            tracing::warn!(chunks = self.pending.len(), "the last log chunks didn't upload in time; they're dropped");
        }
        (self.events)(LogEvent::Flushed(self.uploaded));
    }

    /// Cuts everything waiting into chunks of at most [`CHUNK_BYTES`].
    fn cut(&mut self) {
        while !self.buffer.is_empty() {
            let rest = self.buffer.split_off(self.buffer.len().min(CHUNK_BYTES));
            let chunk = std::mem::replace(&mut self.buffer, rest);
            self.pending.push_back((self.next_seq, chunk));
            self.next_seq += 1;
        }
    }

    /// Uploads waiting chunks in order, stopping at the first failure; the rest wait for the next try.
    async fn upload(&mut self) {
        while let Some((seq, chunk)) = self.pending.front() {
            let key = format!("{}{seq:08}.log", self.prefix);
            match self.store.put(&key, chunk.clone()).await {
                Ok(()) => {
                    self.uploaded = *seq;
                    self.pending.pop_front();
                    (self.events)(LogEvent::Uploaded(self.uploaded));
                }
                Err(error) => {
                    tracing::debug!(%error, seq, "a log chunk didn't upload; it's tried again");
                    return;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;
    use crate::storage::testing::{MemoryStore, Objects};

    const PREFIX: &str = "sessions/0190f3a2-0000-7000-8000-000000000000/attempt-1/";

    struct Setup {
        objects: Objects,
        store: Arc<MemoryStore>,
        events: Arc<Mutex<Vec<LogEvent>>>,
        finish: watch::Sender<bool>,
        writer: LogWriter,
        task: JoinHandle<()>,
    }

    fn setup() -> Setup {
        let objects = Objects::default();
        let store = Arc::new(MemoryStore::new(&objects, &[PREFIX], true));
        let events = Arc::new(Mutex::new(Vec::new()));
        let (finish, finished) = watch::channel(false);
        let recorded = Arc::clone(&events);
        let (writer, task) =
            spawn(Arc::clone(&store) as Arc<dyn ObjectStore>, format!("{PREFIX}logs/"), finished, move |event| {
                recorded.lock().unwrap().push(event);
            });
        Setup { objects, store, events, finish, writer, task }
    }

    fn chunks(objects: &Objects) -> Vec<(String, String)> {
        objects
            .lock()
            .unwrap()
            .iter()
            .map(|(key, body)| (key.trim_start_matches(PREFIX).to_owned(), String::from_utf8_lossy(body).into_owned()))
            .collect()
    }

    async fn settle() {
        for _ in 0..10 {
            tokio::task::yield_now().await;
        }
    }

    #[tokio::test(start_paused = true)]
    async fn output_is_uploaded_every_five_seconds() {
        let setup = setup();
        setup.writer.line("starting");
        settle().await;
        assert!(chunks(&setup.objects).is_empty(), "nothing before the first tick");

        tokio::time::sleep(CHUNK_EVERY).await;
        settle().await;
        assert_eq!(chunks(&setup.objects), vec![("logs/00000001.log".to_owned(), "starting\n".to_owned())]);
        assert_eq!(*setup.events.lock().unwrap(), vec![LogEvent::Uploaded(1)]);

        tokio::time::sleep(CHUNK_EVERY).await;
        settle().await;
        assert_eq!(chunks(&setup.objects).len(), 1, "no empty chunks");
    }

    #[tokio::test(start_paused = true)]
    async fn a_chunk_is_cut_as_soon_as_64_kib_wait() {
        let setup = setup();
        setup.writer.write(vec![b'a'; CHUNK_BYTES + 10]);
        settle().await;

        let uploaded = chunks(&setup.objects);
        assert_eq!(uploaded.len(), 2, "the full chunk and the rest, without waiting for a tick");
        assert_eq!(uploaded[0].1.len(), CHUNK_BYTES);
        assert_eq!(uploaded[1].1.len(), 10);
    }

    #[tokio::test(start_paused = true)]
    async fn past_the_cap_one_line_says_so_and_the_rest_is_dropped() {
        let setup = setup();
        setup.writer.write(vec![b'x'; CAP - 3]);
        setup.writer.write(b"abcdef".to_vec());
        setup.writer.line("never kept");
        setup.finish.send(true).unwrap();
        setup.task.await.unwrap();

        let all: String = chunks(&setup.objects).into_iter().map(|(_, body)| body).collect();
        assert_eq!(all.len(), CAP + 1 + LIMIT_LINE.len());
        assert!(all.ends_with(&format!("xxxabc\n{LIMIT_LINE}")), "the cap, then the limit line");
        assert!(!all.contains("never kept"));
    }

    #[tokio::test(start_paused = true)]
    async fn chunks_keep_their_order_and_a_failed_upload_is_retried_next_tick() {
        let setup = setup();
        setup.store.fail_puts(1);
        setup.writer.line("one");
        tokio::time::sleep(CHUNK_EVERY).await;
        settle().await;
        assert!(chunks(&setup.objects).is_empty(), "the first try failed");

        setup.writer.line("two");
        tokio::time::sleep(CHUNK_EVERY).await;
        settle().await;
        assert_eq!(
            chunks(&setup.objects),
            vec![
                ("logs/00000001.log".to_owned(), "one\n".to_owned()),
                ("logs/00000002.log".to_owned(), "two\n".to_owned())
            ]
        );
        assert_eq!(*setup.events.lock().unwrap(), vec![LogEvent::Uploaded(1), LogEvent::Uploaded(2)]);
    }

    #[tokio::test(start_paused = true)]
    async fn the_end_flushes_what_is_left_before_saying_so() {
        let setup = setup();
        setup.writer.line("last words");
        setup.finish.send(true).unwrap();
        setup.task.await.unwrap();

        assert_eq!(chunks(&setup.objects), vec![("logs/00000001.log".to_owned(), "last words\n".to_owned())]);
        assert_eq!(*setup.events.lock().unwrap(), vec![LogEvent::Uploaded(1), LogEvent::Flushed(1)]);
    }

    #[tokio::test(start_paused = true)]
    async fn storage_that_stays_down_never_holds_the_end_past_the_flush_limit() {
        let setup = setup();
        setup.store.fail_puts(u32::MAX);
        setup.writer.line("lost");
        let started = tokio::time::Instant::now();
        setup.finish.send(true).unwrap();
        setup.task.await.unwrap();

        assert!(started.elapsed() <= FLUSH_FOR + Duration::from_secs(1));
        assert_eq!(*setup.events.lock().unwrap(), vec![LogEvent::Flushed(0)]);
    }
}
