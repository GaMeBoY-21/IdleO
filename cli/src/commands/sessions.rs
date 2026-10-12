//! `ls`, `status`, `logs`, `watch`, `stop` and `cancel`: your sessions, and what to do with one.

use chrono::Utc;
use serde::Serialize;

use crate::api::{SessionList, SessionResponse, StopRequest};
use crate::brand::BIN_NAME;
use crate::cli::{LogsArgs, LsArgs, SessionId, StopArgs};
use crate::context::Context;
use crate::error::Result;
use crate::follow::{Outcome, Show, fetch, follow};
use crate::logs::LogReader;
use crate::money::format_paise;
use crate::sessions::{cost, describe, exit_for, parse_id, state_words, why_ended};
use crate::table::table;
use crate::time::ago;

pub async fn ls(ctx: &Context, args: &LsArgs) -> Result<()> {
    let mut query = vec![("limit", args.limit.to_string())];
    if !args.all {
        query.push(("active", "true".to_owned()));
    }
    let list: SessionList = ctx.client.get("/v1/sessions", &query).await?;
    let wanted = args.state.as_deref().map(str::to_lowercase);
    let sessions: Vec<&SessionResponse> = list
        .sessions
        .iter()
        .filter(|session| wanted.as_deref().is_none_or(|state| session.state.to_lowercase() == state.replace(' ', "_")))
        .collect();
    ctx.result(&sessions, || {
        if sessions.is_empty() {
            return if args.all {
                "No sessions yet.".to_owned()
            } else {
                format!("Nothing is running. `{BIN_NAME} ls --all` shows the ones that ended.")
            };
        }
        let now = Utc::now();
        let rows: Vec<Vec<String>> = sessions
            .iter()
            .map(|session| {
                vec![
                    session.id.to_string(),
                    state_words(&session.state),
                    session.template_id.clone(),
                    format_paise(cost(session)),
                    ago(session.created_at, now),
                ]
            })
            .collect();
        table(&["ID", "STATE", "TEMPLATE", "COST", "BOOKED"], &rows)
    });
    Ok(())
}

pub async fn status(ctx: &Context, args: &SessionId) -> Result<()> {
    let id = parse_id(&args.id)?;
    let session = fetch(&ctx.client, &id.to_string()).await?;
    ctx.result(&session, || describe(&session, Utc::now()));
    Ok(())
}

pub async fn logs(ctx: &Context, args: &LogsArgs) -> Result<()> {
    let id = parse_id(&args.id)?.to_string();
    if args.follow {
        return match follow(ctx, &id, Show { logs: true }).await? {
            Outcome::Settled(session) => finish(ctx, &session),
            Outcome::Detached => Ok(()),
        };
    }
    LogReader::new(&ctx.client, &id).read(ctx).await?;
    Ok(())
}

pub async fn watch(ctx: &Context, args: &SessionId) -> Result<()> {
    let id = parse_id(&args.id)?.to_string();
    match follow(ctx, &id, Show { logs: false }).await? {
        Outcome::Settled(session) => finish(ctx, &session),
        Outcome::Detached => Ok(()),
    }
}

/// The last line of following a settled session: what it cost, and why it ended if it didn't complete.
fn finish(ctx: &Context, session: &SessionResponse) -> Result<()> {
    ctx.say(format!("Finished: {}. Charged {}.", state_words(&session.state), format_paise(session.charged_paise)));
    if let Some(why) = why_ended(session) {
        ctx.say(why);
    }
    match exit_for(session) {
        crate::error::Exit::Success => Ok(()),
        exit => Err(crate::error::CliError::new(exit, String::new())),
    }
}

#[derive(Serialize)]
struct Nothing {}

pub async fn stop(ctx: &Context, args: &StopArgs) -> Result<()> {
    let id = parse_id(&args.id)?;
    let body = StopRequest { checkpoint_first: Some(!args.no_checkpoint) };
    let session: SessionResponse = ctx.client.post(&format!("/v1/sessions/{id}/stop"), &body, None).await?;
    ctx.result(&session, || {
        format!(
            "Stopping. It {}saves a last checkpoint first, then ends; you're charged only for the minutes it ran.\n`{BIN_NAME} status {id}` shows where it is.",
            if args.no_checkpoint { "doesn't " } else { "" }
        )
    });
    Ok(())
}

pub async fn cancel(ctx: &Context, args: &SessionId) -> Result<()> {
    let id = parse_id(&args.id)?;
    let session: SessionResponse = ctx.client.post(&format!("/v1/sessions/{id}/cancel"), &Nothing {}, None).await?;
    ctx.result(&session, || "Cancelled. Nothing was charged.".to_owned());
    Ok(())
}
