//! `login`, `logout` and `whoami`: signing in through the web app.
//!
//! A sign-in is a device login shaped like RFC 8628: the CLI asks the API for a short code and a link, the person
//! opens the link in a browser where they're signed in and approves the same code, and the CLI, which has been
//! polling, is handed a new API key, once.

use std::time::Duration;

use serde::Serialize;
use uuid::Uuid;

use crate::api::{CreatedApiKey, MeResponse, PollCliLoginRequest, StartCliLoginRequest, StartedCliLogin};
use crate::brand::BIN_NAME;
use crate::cli::LoginArgs;
use crate::client::not_signed_in;
use crate::context::Context;
use crate::error::{CliError, Exit, Result};
use crate::keystore::{Source, Stored};

/// What the person is asked to do, and how long the CLI waits for them to do it.
pub async fn device_login(ctx: &Context, device_name: &str, open_browser: bool) -> Result<CreatedApiKey> {
    let name = device_name.chars().take(60).collect::<String>();
    let request = StartCliLoginRequest {
        device_name: name
            .as_str()
            .try_into()
            .map_err(|_| CliError::usage("give the device a name of 1 to 60 characters"))?,
    };
    let started: StartedCliLogin = ctx.client.post_open("/v1/cli-logins", &request).await?;
    ctx.say(format!(
        "To approve this sign-in, open:\n\n  {}\n\nand check that the code is {}",
        started.verification_url, started.user_code
    ));
    if open_browser {
        open(&started.verification_url);
    }
    ctx.say(format!(
        "Waiting for you to approve it (it expires in {} minutes)…",
        started.expires_in_seconds.max(60) / 60
    ));
    poll(ctx, &started).await
}

async fn poll(ctx: &Context, started: &StartedCliLogin) -> Result<CreatedApiKey> {
    let interval = Duration::from_secs(u64::try_from(started.interval_seconds).unwrap_or(3).max(1));
    let deadline =
        tokio::time::Instant::now() + Duration::from_secs(u64::try_from(started.expires_in_seconds).unwrap_or(600));
    let body = PollCliLoginRequest {
        poll_token: started
            .poll_token
            .as_str()
            .try_into()
            .map_err(|_| CliError::failed("the API sent a poll token this version can't use"))?,
    };
    let path = format!("/v1/cli-logins/{}/token", started.id);
    loop {
        tokio::select! {
            () = tokio::time::sleep(interval) => {}
            _ = tokio::signal::ctrl_c() => return Err(CliError::failed("Cancelled. Nothing was signed in.")),
        }
        match ctx.client.post_open::<_, CreatedApiKey>(&path, &body).await {
            Ok(key) => return Ok(key),
            Err(error) if error.problem.as_deref() == Some("authorization-pending") => {}
            Err(error) if error.problem.as_deref() == Some("cli-login-expired") => {
                return Err(CliError::failed(format!(
                    "That sign-in expired. Run `{BIN_NAME} login` to start another."
                )));
            }
            Err(error) if error.exit == Exit::Network => {}
            Err(error) => return Err(error),
        }
        if tokio::time::Instant::now() >= deadline {
            return Err(CliError::failed(format!("That sign-in expired. Run `{BIN_NAME} login` to start another.")));
        }
    }
}

/// Opens a link in the person's browser; if that can't be done, the printed link is all there is.
fn open(url: &str) {
    let command = if cfg!(target_os = "macos") {
        ("open", vec![url])
    } else if cfg!(windows) {
        ("explorer", vec![url])
    } else {
        ("xdg-open", vec![url])
    };
    let _ = std::process::Command::new(command.0)
        .args(command.1)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
}

/// This computer's name for the approval page: its host name, else a plain "this computer".
#[must_use]
pub fn this_device() -> String {
    ["HOSTNAME", "COMPUTERNAME"]
        .iter()
        .find_map(|name| std::env::var(name).ok())
        .or_else(|| {
            std::process::Command::new("hostname").output().ok().and_then(|out| String::from_utf8(out.stdout).ok())
        })
        .map(|name| name.trim().to_owned())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "this computer".to_owned())
}

#[derive(Serialize)]
struct SignedIn<'a> {
    #[serde(rename = "keyId")]
    key_id: Uuid,
    #[serde(rename = "keyName")]
    key_name: &'a str,
    account: &'a MeResponse,
}

pub async fn login(ctx: &Context, args: &LoginArgs) -> Result<()> {
    let device = args.device_name.clone().unwrap_or_else(this_device);
    let created = device_login(ctx, &device, !args.no_browser).await?;
    let stored = Stored { key: created.key.clone(), id: Some(created.id) };
    let source = ctx.keys.save(&stored)?;
    // From here the new key is the one in use, so ask who it is.
    let me: MeResponse = ctx.client.with_key(created.key.clone()).get("/v1/me", &[]).await?;
    let where_ = match source {
        Source::Keychain => "in your keychain",
        Source::File | Source::Environment => "in a file only you can read",
    };
    ctx.result(&SignedIn { key_id: created.id, key_name: &created.name, account: &me }, || {
        format!(
            "Signed in as {}. The key \"{}\" is kept {where_}.",
            me.email.as_deref().unwrap_or("your account"),
            created.name
        )
    });
    Ok(())
}

pub async fn whoami(ctx: &Context) -> Result<()> {
    let me: MeResponse = ctx.client.get("/v1/me", &[]).await?;
    ctx.result(&me, || {
        let name = me.display_name.as_deref().unwrap_or("(no name)");
        format!("{name}\n{}", me.email.as_deref().unwrap_or("(no email)"))
    });
    Ok(())
}

/// Revokes this computer's key on the server, then forgets it here. Signed out already is fine.
pub async fn logout(ctx: &Context) -> Result<()> {
    let Some((stored, source)) = ctx.keys.load() else {
        ctx.say("You're not signed in.");
        return Ok(());
    };
    let id = match stored.id {
        Some(id) => Some(id),
        None => find_key_id(ctx, &stored.key).await?,
    };
    if let Some(id) = id {
        match ctx.client.delete(&format!("/v1/api-keys/{id}")).await {
            Ok(()) => {}
            // Already revoked, or never ours: the key is useless either way.
            Err(error) if error.exit == Exit::NotSignedIn || error.problem.as_deref() == Some("not-found") => {}
            Err(error) => return Err(error),
        }
    }
    ctx.keys.delete();
    if source == Source::Environment {
        ctx.say("Revoked the key in IDLEO_API_KEY. Unset it in your environment.");
    } else {
        ctx.say("Signed out. This computer's key is revoked.");
    }
    Ok(())
}

/// A key from the environment has no stored id; its id is the one in the list whose prefix starts the key.
async fn find_key_id(ctx: &Context, key: &str) -> Result<Option<Uuid>> {
    let list: crate::api::ApiKeyList = ctx
        .client
        .get("/v1/api-keys", &[])
        .await
        .map_err(|error| if error.exit == Exit::NotSignedIn { not_signed_in() } else { error })?;
    Ok(list.keys.iter().find(|k| key.starts_with(&k.prefix)).map(|k| k.id))
}
