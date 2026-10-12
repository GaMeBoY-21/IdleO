//! A session's live view: Server-Sent Events from `GET /v1/sessions/{id}/events` (ADR 0019), the one stream the web
//! app, this CLI and the SDK follow. `event: session` carries the whole session; `event: logs` says a new log chunk
//! exists. A comment line keeps a quiet stream open, and the stream ends once the session is settled.

use futures_util::StreamExt;
use reqwest::Method;

use crate::api::{SessionLogsEvent, SessionResponse};
use crate::client::Client;
use crate::error::{CliError, Result};

#[derive(Debug)]
pub enum Event {
    Session(Box<SessionResponse>),
    /// A new log chunk exists; the logs themselves are read with `GET …/logs`.
    Logs(SessionLogsEvent),
}

/// Splits a stream of bytes into events, a chunk at a time, however the network cut it.
#[derive(Default)]
pub struct Parser {
    buffer: String,
    name: Option<String>,
    data: Vec<String>,
}

impl Parser {
    /// Takes the next bytes and returns the events they completed.
    pub fn push(&mut self, bytes: &[u8]) -> Vec<Event> {
        self.buffer.push_str(&String::from_utf8_lossy(bytes));
        let mut events = Vec::new();
        while let Some(end) = self.buffer.find('\n') {
            let line: String = self.buffer.drain(..=end).collect();
            let line = line.trim_end_matches(['\n', '\r']);
            if line.is_empty() {
                events.extend(self.dispatch());
            } else if let Some(name) = line.strip_prefix("event:") {
                self.name = Some(name.trim().to_owned());
            } else if let Some(data) = line.strip_prefix("data:") {
                self.data.push(data.strip_prefix(' ').unwrap_or(data).to_owned());
            }
            // A line starting with a colon is a comment (the keep-alive), and anything else is ignored.
        }
        events
    }

    fn dispatch(&mut self) -> Option<Event> {
        let name = self.name.take();
        let data = std::mem::take(&mut self.data).join("\n");
        match name.as_deref() {
            Some("session") => serde_json::from_str(&data).ok().map(|s| Event::Session(Box::new(s))),
            Some("logs") => serde_json::from_str(&data).ok().map(Event::Logs),
            _ => None,
        }
    }
}

/// An open event stream.
pub struct Stream {
    bytes: futures_util::stream::BoxStream<'static, reqwest::Result<bytes::Bytes>>,
    parser: Parser,
    pending: std::collections::VecDeque<Event>,
}

/// Opens a session's event stream.
///
/// # Errors
/// If the API refuses it, or can't be reached.
pub async fn open(client: &Client, session_id: &str) -> Result<Stream> {
    let request = client
        .authed(Method::GET, &format!("/v1/sessions/{session_id}/events"))?
        .header(reqwest::header::ACCEPT, "text/event-stream");
    // Long-lived: it must not carry the short request timeout.
    let request = request.timeout(std::time::Duration::from_mins(35));
    let response = client.send(request).await?;
    Ok(Stream {
        bytes: response.bytes_stream().boxed(),
        parser: Parser::default(),
        pending: std::collections::VecDeque::new(),
    })
}

impl Stream {
    /// The next event; `None` when the stream has ended (the session is settled, or the connection closed).
    ///
    /// # Errors
    /// If the connection breaks.
    pub async fn next(&mut self) -> Result<Option<Event>> {
        loop {
            if let Some(event) = self.pending.pop_front() {
                return Ok(Some(event));
            }
            match self.bytes.next().await {
                None => return Ok(None),
                Some(Err(e)) => return Err(CliError::network(format!("The live view dropped: {e}"))),
                Some(Ok(chunk)) => self.pending.extend(self.parser.push(&chunk)),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SESSION: &str = r#"{"accruedPaise":0,"attempt":1,"chargedPaise":0,"checkpoints":[],"createdAt":"2026-10-12T10:00:00Z","events":[],"id":"01a10e8e-9c65-7789-a526-473491c82218","inputs":{},"lowCredit":false,"maxPricePaisePerMin":5,"maxRuntimeSeconds":300,"maxSpendPaise":1000,"params":{},"presets":{},"reservedPaise":60,"settled":false,"state":"RUNNING","templateId":"t","vramNeededMb":1024}"#;

    fn session_frame() -> String {
        format!("event: session\ndata: {SESSION}\n\n")
    }

    #[test]
    fn a_session_event_is_the_whole_session() {
        let events = Parser::default().push(session_frame().as_bytes());
        assert!(matches!(events.as_slice(), [Event::Session(s)] if s.state == "RUNNING" && s.max_spend_paise == 1000));
    }

    #[test]
    fn an_event_cut_anywhere_is_put_back_together() {
        let frame = session_frame();
        let (a, b) = frame.as_bytes().split_at(37);
        let mut parser = Parser::default();
        assert!(parser.push(a).is_empty());
        assert_eq!(parser.push(b).len(), 1);
    }

    #[test]
    fn keep_alives_and_unknown_events_are_ignored() {
        let text = format!(
            ": keep-alive\n\nevent: weather\ndata: {{}}\n\nevent: logs\ndata: {{\"attempt\":1,\"latestSeq\":3}}\n\n{}",
            session_frame()
        );
        let events = Parser::default().push(text.as_bytes());
        assert!(matches!(events.as_slice(), [Event::Logs(l), Event::Session(_)] if l.latest_seq == 3));
    }

    #[test]
    fn windows_line_endings_are_fine() {
        let text = session_frame().replace('\n', "\r\n");
        assert_eq!(Parser::default().push(text.as_bytes()).len(), 1);
    }
}
