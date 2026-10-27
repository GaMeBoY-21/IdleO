//! Following a session until it ends: its state from the event stream,
//! its logs read as they appear, a dropped connection picked up again, and Ctrl-C leaving it running.

use std::time::Duration;

use crate::api::SessionResponse;
use crate::brand::BIN_NAME;
use crate::client::Client;
use crate::context::Context;
use crate::error::{CliError, Exit, Result};
use crate::events::{self, Event};
use crate::logs::LogReader;
use crate::money::format_paise;
use crate::sessions::{cost, state_words};

/// How often logs are read even when the stream says nothing, and how long to wait before reconnecting.
const TICK: Duration = Duration::from_secs(2);
/// Failures in a row (a stream that won't open, or keeps dropping) before giving up.
const GIVE_UP_AFTER: u32 = 6;
/// How long after a session settles its last log chunks may still be coming.
const LOGS_GRACE: u32 = 30;

pub enum Outcome {
    /// It ended and settled: its final figures.
    Settled(Box<SessionResponse>),
    /// The person pressed Ctrl-C: it's still running.
    Detached,
}

/// What to show while following.
#[derive(Clone, Copy)]
pub struct Show {
    pub logs: bool,
}

/// Follows a session to its end.
///
/// # Errors
/// If the API can't be reached for a while, or refuses.
pub async fn follow(ctx: &Context, id: &str, show: Show) -> Result<Outcome> {
    let mut logs = LogReader::new(&ctx.client, id);
    let mut last_state = String::new();
    let mut failures = 0;
    loop {
        match events::open(&ctx.client, id).await {
            Ok(mut stream) => {
                failures = 0;
                loop {
                    tokio::select! {
                        event = stream.next() => match event {
                            Ok(Some(Event::Session(session))) => {
                                announce(ctx, &session, &mut last_state);
                                if session.settled {
                                    finish_logs(ctx, &mut logs, show).await?;
                                    return Ok(Outcome::Settled(session));
                                }
                            }
                            Ok(Some(Event::Logs(_))) => read_logs(ctx, &mut logs, show).await?,
                            Ok(None) | Err(_) => break,
                        },
                        () = tokio::time::sleep(TICK) => read_logs(ctx, &mut logs, show).await?,
                        _ = tokio::signal::ctrl_c() => return Ok(detach(ctx, id)),
                    }
                }
            }
            // A stream that can't open, or a network that's down: try again a few times, then say so.
            Err(error)
                if error.exit == Exit::Network
                    || error.problem.as_deref() == Some("server-error")
                    || error.problem.as_deref() == Some("too-many-streams") =>
            {
                failures += 1;
                if failures >= GIVE_UP_AFTER {
                    return Err(error);
                }
            }
            Err(error) => return Err(error),
        }
        // The stream ended without the session settling: ask once, and carry on from there.
        match ctx.client.get::<SessionResponse>(&format!("/v1/sessions/{id}"), &[]).await {
            Ok(session) => {
                announce(ctx, &session, &mut last_state);
                if session.settled {
                    finish_logs(ctx, &mut logs, show).await?;
                    return Ok(Outcome::Settled(Box::new(session)));
                }
            }
            Err(error) if error.exit == Exit::Network => {
                failures += 1;
                if failures >= GIVE_UP_AFTER {
                    return Err(error);
                }
            }
            Err(error) => return Err(error),
        }
        tokio::select! {
            () = tokio::time::sleep(TICK) => {}
            _ = tokio::signal::ctrl_c() => return Ok(detach(ctx, id)),
        }
    }
}

fn detach(ctx: &Context, id: &str) -> Outcome {
    ctx.say(format!(
        "\nDetached. The session keeps running.\n  See it:   {BIN_NAME} status {id}\n  Follow:   {BIN_NAME} logs {id} -f\n  Stop it:  {BIN_NAME} stop {id}"
    ));
    Outcome::Detached
}

fn announce(ctx: &Context, session: &SessionResponse, last: &mut String) {
    if session.state == *last {
        return;
    }
    last.clone_from(&session.state);
    let gpu = session.gpu.as_ref().map(|gpu| format!(" on {}", gpu.model)).unwrap_or_default();
    ctx.say(format!("· {}{gpu}  ({} so far)", state_words(&session.state), format_paise(cost(session))));
}

async fn read_logs(ctx: &Context, logs: &mut LogReader<'_>, show: Show) -> Result<()> {
    if show.logs {
        // A failed read isn't the end of the session; the next tick tries again.
        match logs.read(ctx).await {
            Ok(_) => {}
            // A read refused for the log reads' limit is tried again on a later tick, like a network blip.
            Err(error)
                if error.exit == Exit::Network
                    || error.exit == Exit::RateLimited
                    || error.problem.as_deref() == Some("server-error") => {}
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

/// Once settled, reads until the log says it's complete, giving the last chunks a moment to arrive.
async fn finish_logs(ctx: &Context, logs: &mut LogReader<'_>, show: Show) -> Result<()> {
    if !show.logs {
        return Ok(());
    }
    for _ in 0..LOGS_GRACE {
        if logs.read(ctx).await? {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
    Err(CliError::failed("The session has ended, but its last log lines didn't arrive. Try `logs` again in a moment."))
}

/// The session, once, for commands that follow nothing.
///
/// # Errors
/// As [`Client::get`].
pub async fn fetch(client: &Client, id: &str) -> Result<SessionResponse> {
    client.get(&format!("/v1/sessions/{id}"), &[]).await
}
