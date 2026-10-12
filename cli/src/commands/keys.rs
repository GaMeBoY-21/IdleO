//! `keys`, `keys create` and `keys revoke` (docs/plan/product-surface.md section 4.2). Listing and revoking work
//! with this computer's key. Creating one never does: it goes through the same approval in the web app as a
//! sign-in, so a stolen key can never make another (ADR 0025).

use chrono::Utc;

use crate::api::{ApiKeyList, CreatedApiKey};
use crate::brand::BIN_NAME;
use crate::cli::KeysCommand;
use crate::commands::login::device_login;
use crate::context::Context;
use crate::error::{CliError, Result};
use crate::table::table;
use crate::time::ago;

pub async fn keys(ctx: &Context, command: Option<&KeysCommand>) -> Result<()> {
    match command {
        None => list(ctx).await,
        Some(KeysCommand::Create { name }) => create(ctx, name).await,
        Some(KeysCommand::Revoke { id }) => revoke(ctx, id).await,
    }
}

async fn list(ctx: &Context) -> Result<()> {
    let list: ApiKeyList = ctx.client.get("/v1/api-keys", &[]).await?;
    ctx.result(&list, || {
        if list.keys.is_empty() {
            return "You have no API keys.".to_owned();
        }
        let now = Utc::now();
        let rows: Vec<Vec<String>> = list
            .keys
            .iter()
            .map(|key| {
                vec![
                    key.name.clone(),
                    key.prefix.clone(),
                    format!("{:?}", key.scope).to_lowercase(),
                    ago(key.created_at, now),
                    key.last_used_at.map_or_else(|| "never".to_owned(), |used| ago(used, now)),
                    key.id.to_string(),
                ]
            })
            .collect();
        table(&["NAME", "KEY", "SCOPE", "CREATED", "LAST USED", "ID"], &rows)
    });
    Ok(())
}

async fn create(ctx: &Context, name: &str) -> Result<()> {
    ctx.client.require_key()?;
    if name.trim().is_empty() {
        return Err(CliError::usage("give the key a name with --name"));
    }
    let created: CreatedApiKey = device_login(ctx, name.trim(), true).await?;
    ctx.result(&created, || {
        format!(
            "Made \"{}\" (a full key). Copy it now: it's shown only this once.\n\n  {}\n\nRevoke it any time with `{BIN_NAME} keys revoke {}`.",
            created.name, created.key, created.id
        )
    });
    Ok(())
}

async fn revoke(ctx: &Context, id: &str) -> Result<()> {
    let id: uuid::Uuid =
        id.parse().map_err(|_| CliError::usage(format!("{id:?} isn't a key id; see `{BIN_NAME} keys`")))?;
    ctx.client.delete(&format!("/v1/api-keys/{id}")).await?;
    let mine = ctx.keys.load().and_then(|(stored, _)| stored.id) == Some(id);
    if mine {
        ctx.keys.delete();
        ctx.say("Revoked. That was this computer's own key, so you're signed out.");
    } else {
        ctx.say("Revoked.");
    }
    Ok(())
}
