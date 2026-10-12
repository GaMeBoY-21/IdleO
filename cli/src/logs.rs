//! A session's logs: read in pages from `GET /v1/sessions/{id}/logs`, each page after the cursor the last one gave,
//! so a log is never read twice and a reader that starts late sees the whole of it. Reads with a cursor don't count
//! against the request limit; the first read does (ADR 0025).

use crate::api::SessionLogPage;
use crate::client::Client;
use crate::context::Context;
use crate::error::Result;

const PAGE: u32 = 100;

pub struct LogReader<'a> {
    client: &'a Client,
    path: String,
    cursor: Option<String>,
    complete: bool,
}

impl<'a> LogReader<'a> {
    #[must_use]
    pub fn new(client: &'a Client, session_id: &str) -> Self {
        Self { client, path: format!("/v1/sessions/{session_id}/logs"), cursor: None, complete: false }
    }

    /// Prints every chunk there is now, then says whether the log is complete (the session is settled, and it's all
    /// been read).
    ///
    /// # Errors
    /// If the API can't be reached or refuses.
    pub async fn read(&mut self, ctx: &Context) -> Result<bool> {
        while !self.complete {
            let mut query = vec![("limit", PAGE.to_string())];
            if let Some(cursor) = &self.cursor {
                query.push(("cursor", cursor.clone()));
            }
            let page: SessionLogPage = self.client.get(&self.path, &query).await?;
            for chunk in &page.chunks {
                ctx.log(&chunk.text);
            }
            self.complete = page.complete;
            let more = !page.chunks.is_empty();
            if page.next_cursor.is_some() {
                self.cursor = page.next_cursor;
            }
            if !more {
                break;
            }
        }
        Ok(self.complete)
    }
}
