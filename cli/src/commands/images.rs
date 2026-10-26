//! `images`, `images submit`, `images show` and `images remove` (ADR 0042): a renter's own container images and the check
//! each must pass on our server before it can run. A private registry's token is read from `IDLEO_REGISTRY_TOKEN` or, with
//! `--token-stdin`, standard input: never from the command line, where it would land in the shell's history.

use std::io::Read as _;
use std::time::Duration;

use chrono::Utc;

use crate::api::{ImageList, ImageResponse, ImageResponseState, NewImage};
use crate::brand::BIN_NAME;
use crate::bytes_fmt::format_bytes;
use crate::cli::ImagesCommand;
use crate::context::Context;
use crate::error::{CliError, Exit, Result};
use crate::table::table;
use crate::time::ago;

/// How often a check's progress is asked for while waiting.
const POLL: Duration = Duration::from_secs(3);

pub async fn images(ctx: &Context, command: Option<&ImagesCommand>) -> Result<()> {
    match command {
        None => list(ctx).await,
        Some(ImagesCommand::Submit { reference, username, token_stdin, wait }) => {
            submit(ctx, reference, username.as_deref(), *token_stdin, *wait).await
        }
        Some(ImagesCommand::Show { id, wait }) => show(ctx, id, *wait).await,
        Some(ImagesCommand::Remove { id }) => remove(ctx, id).await,
    }
}

async fn list(ctx: &Context) -> Result<()> {
    let list: ImageList = ctx.client.get("/v1/images", &[]).await?;
    ctx.result(&list, || {
        if !list.can_use {
            return list.why_not.clone().unwrap_or_else(|| "You can't use your own images yet.".to_owned());
        }
        if list.images.is_empty() {
            return format!("You have no images. Check one with `{BIN_NAME} images submit REFERENCE`.");
        }
        let now = Utc::now();
        let rows: Vec<Vec<String>> = list
            .images
            .iter()
            .map(|image| {
                vec![
                    image.reference.clone(),
                    image.state.to_string(),
                    image
                        .result
                        .as_ref()
                        .map_or_else(String::new, |r| format_bytes(u64::try_from(r.compressed_bytes).unwrap_or(0))),
                    ago(image.created_at, now),
                    image.id.to_string(),
                ]
            })
            .collect();
        table(&["IMAGE", "STATE", "SIZE", "CHECKED", "ID"], &rows)
    });
    Ok(())
}

/// The token as given: read from standard input with `--token-stdin`, else `IDLEO_REGISTRY_TOKEN`'s value.
fn given_token(from_stdin: bool) -> Result<Option<String>> {
    if from_stdin {
        let mut text = String::new();
        std::io::stdin()
            .read_to_string(&mut text)
            .map_err(|e| CliError::usage(format!("couldn't read the token: {e}")))?;
        return Ok(Some(text));
    }
    Ok(std::env::var("IDLEO_REGISTRY_TOKEN").ok())
}

/// A user name and a token go together, or neither does.
fn token(username: Option<&str>, given: Option<String>) -> Result<Option<String>> {
    let token = given.map(|t| t.trim().to_owned()).filter(|t| !t.is_empty());
    match (username, &token) {
        (Some(_), None) => Err(CliError::usage(
            "a private registry needs a read-only token too: set IDLEO_REGISTRY_TOKEN, or pass --token-stdin",
        )),
        (None, Some(_)) => Err(CliError::usage("give the registry's user name with --username")),
        _ => Ok(token),
    }
}

async fn submit(ctx: &Context, reference: &str, username: Option<&str>, token_stdin: bool, wait: bool) -> Result<()> {
    ctx.client.require_key()?;
    let token = token(username, given_token(token_stdin)?)?;
    let bad = |what: &str| CliError::usage(format!("that {what} is too long"));
    let request = NewImage {
        reference: reference.trim().to_owned().try_into().map_err(|_| bad("reference"))?,
        registry_username: username.map(|u| u.to_owned().try_into()).transpose().map_err(|_| bad("user name"))?,
        registry_token: token.map(TryInto::try_into).transpose().map_err(|_| bad("token"))?,
    };
    let key = uuid::Uuid::now_v7().to_string();
    let image: ImageResponse = ctx.client.post("/v1/images", &request, Some(&key)).await?;
    ctx.say(format!("Checking {} ({}).", image.reference, image.id));
    if wait { follow(ctx, image).await } else { print(ctx, &image) }
}

async fn show(ctx: &Context, id: &str, wait: bool) -> Result<()> {
    let image = get(ctx, id).await?;
    if wait { follow(ctx, image).await } else { print(ctx, &image) }
}

async fn get(ctx: &Context, id: &str) -> Result<ImageResponse> {
    let id: uuid::Uuid =
        id.trim().parse().map_err(|_| CliError::usage(format!("{id:?} isn't an image id; see `{BIN_NAME} images`")))?;
    ctx.client.get(&format!("/v1/images/{id}"), &[]).await
}

/// Waits until the check is done, saying its progress as it changes, then prints the result.
async fn follow(ctx: &Context, mut image: ImageResponse) -> Result<()> {
    let mut said = String::new();
    while matches!(image.state, ImageResponseState::Queued | ImageResponseState::Checking) {
        let now = progress(&image);
        if now != said {
            ctx.say(&now);
            said = now;
        }
        tokio::time::sleep(POLL).await;
        image = ctx.client.get(&format!("/v1/images/{}", image.id), &[]).await?;
    }
    print(ctx, &image)
}

fn progress(image: &ImageResponse) -> String {
    let Some(progress) = &image.progress else { return "Waiting for its check…".to_owned() };
    let bytes = |n: i64| format_bytes(u64::try_from(n).unwrap_or(0));
    match progress.phase.to_string().as_str() {
        "downloading" if progress.total_bytes > 0 => {
            format!("Downloading · {} of {}", bytes(progress.done_bytes), bytes(progress.total_bytes))
        }
        "downloading" => "Downloading…".to_owned(),
        "reading" => "Reading its layers…".to_owned(),
        "scanning" => "Looking for known vulnerabilities and secrets…".to_owned(),
        _ => "Waiting for the blocklist to judge it…".to_owned(),
    }
}

/// The image and its check; a blocked or unchecked image ends the command with an error (exit 1).
fn print(ctx: &Context, image: &ImageResponse) -> Result<()> {
    ctx.result(image, || describe(image));
    match image.state {
        ImageResponseState::Blocked | ImageResponseState::Failed => Err(CliError::new(Exit::Failed, String::new())),
        _ => Ok(()),
    }
}

fn describe(image: &ImageResponse) -> String {
    let mut lines = vec![format!("{}: {}", image.reference, image.state)];
    if let Some(detail) = &image.detail {
        lines.push(detail.clone());
    }
    if let Some(result) = &image.result {
        lines.push(format!("Runs as checked: {}", result.pinned_reference));
        lines.push(format!(
            "{} compressed, {} unpacked, {} layers{}.",
            format_bytes(u64::try_from(result.compressed_bytes).unwrap_or(0)),
            format_bytes(u64::try_from(result.unpacked_bytes).unwrap_or(0)),
            result.layers,
            result.cuda_needed.as_ref().map_or_else(String::new, |cuda| format!(", CUDA {cuda}"))
        ));
        for warning in &result.warnings {
            lines.push(format!("Warning: {}", warning.message));
        }
        if image.state == ImageResponseState::Ready {
            lines.push(format!("Run it: {BIN_NAME} run custom-image --image {} --param command=\"…\"", image.id));
        }
    }
    lines.join("\n")
}

async fn remove(ctx: &Context, id: &str) -> Result<()> {
    let image = get(ctx, id).await?;
    ctx.client.delete(&format!("/v1/images/{}", image.id)).await?;
    ctx.say(format!("Removed {}.", image.reference));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_user_name_and_a_token_go_together() {
        assert_eq!(token(Some("me"), Some(" ghp_x\n".to_owned())).unwrap().as_deref(), Some("ghp_x"));
        assert!(token(Some("me"), None).is_err(), "a user name needs a token");
        assert!(token(Some("me"), Some("  ".to_owned())).is_err(), "a blank token is none");
        assert!(token(None, Some("ghp_x".to_owned())).is_err(), "a token needs a user name");
        assert!(token(None, None).unwrap().is_none(), "a public image needs neither");
    }
}
