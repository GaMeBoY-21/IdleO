//! `outputs`: a completed session's files, and downloading them; a file that was part-way carries on from where it
//! stopped, through a range request, so a dropped connection never means starting a model over.

use std::path::{Component, Path, PathBuf};
use std::time::Duration;

use futures_util::StreamExt;
use reqwest::StatusCode;
use tokio::io::AsyncWriteExt;

use crate::api::{Output, Outputs};
use crate::bytes_fmt::format_bytes;
use crate::cli::OutputsArgs;
use crate::context::Context;
use crate::error::{CliError, Result};
use crate::sessions::parse_id;
use crate::table::table;

const RETRIES: u32 = 3;
/// A file being downloaded is `NAME.idleo-part` until it's whole.
const PART_SUFFIX: &str = ".idleo-part";

pub async fn outputs(ctx: &Context, args: &OutputsArgs) -> Result<()> {
    let id = parse_id(&args.id)?;
    let outputs: Outputs = ctx.client.get(&format!("/v1/sessions/{id}/outputs"), &[]).await?;
    match &args.download {
        None => ctx.result(&outputs, || {
            if outputs.files.is_empty() {
                return "There are no output files.".to_owned();
            }
            let rows: Vec<Vec<String>> = outputs
                .files
                .iter()
                .map(|file| vec![file.path.clone(), format_bytes(u64::try_from(file.size_bytes).unwrap_or(0))])
                .collect();
            table(&["FILE", "SIZE"], &rows)
        }),
        Some(dir) => {
            let mut done = Vec::new();
            for file in &outputs.files {
                let path = download(ctx, file, dir).await?;
                done.push(path);
            }
            ctx.result(&done.iter().map(|path| path.display().to_string()).collect::<Vec<_>>(), || {
                format!("Downloaded {} file(s) to {}.", done.len(), dir.display())
            });
        }
    }
    Ok(())
}

/// Where an output goes under `dir`: its path, which must stay inside it.
///
/// # Errors
/// If the path would leave the folder.
pub fn destination(dir: &Path, relative: &str) -> Result<PathBuf> {
    let path = Path::new(relative);
    let safe = !relative.is_empty()
        && path.components().all(|part| matches!(part, Component::Normal(_)))
        && !relative.contains('\\');
    if !safe {
        return Err(CliError::failed(format!(
            "The output {relative:?} has a path that would leave the download folder."
        )));
    }
    Ok(dir.join(path))
}

async fn download(ctx: &Context, file: &Output, dir: &Path) -> Result<PathBuf> {
    let target = destination(dir, &file.path)?;
    let size = u64::try_from(file.size_bytes).unwrap_or(0);
    if let Some(parent) = target.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|e| CliError::failed(format!("couldn't make {}: {e}", parent.display())))?;
    }
    if tokio::fs::metadata(&target).await.is_ok_and(|meta| meta.len() == size) {
        ctx.note(format!("{}  already downloaded", file.path));
        return Ok(target);
    }
    let part = PathBuf::from(format!("{}{PART_SUFFIX}", target.display()));
    let mut failures = 0;
    loop {
        match fetch(ctx, file, &part, size).await {
            Ok(()) => break,
            Err(error) if failures < RETRIES => {
                failures += 1;
                ctx.note(format!("{}  {error}; trying again", file.path));
                tokio::time::sleep(Duration::from_secs(u64::from(failures) * 2)).await;
            }
            Err(error) => return Err(error),
        }
    }
    tokio::fs::rename(&part, &target)
        .await
        .map_err(|e| CliError::failed(format!("couldn't finish {}: {e}", target.display())))?;
    ctx.note(format!("{}  {}", file.path, format_bytes(size)));
    Ok(target)
}

/// Gets the rest of a file into its part file: from where the part file ends when storage can give a range.
async fn fetch(ctx: &Context, file: &Output, part: &Path, size: u64) -> Result<()> {
    let mut have = tokio::fs::metadata(part).await.map_or(0, |meta| meta.len());
    if have > size {
        have = 0;
    }
    if have == size && size > 0 {
        return Ok(());
    }
    let mut request = ctx.client.transfers().get(&file.url);
    if have > 0 {
        request = request.header(reqwest::header::RANGE, format!("bytes={have}-"));
    }
    let response =
        request.send().await.map_err(|e| CliError::network(format!("the download failed: {}", e.without_url())))?;
    let append = match response.status() {
        StatusCode::PARTIAL_CONTENT => true,
        StatusCode::OK => false,
        status => {
            return Err(CliError::failed(format!(
                "storage answered {status}; the download link may have expired, run `outputs` again"
            )));
        }
    };
    let mut out = tokio::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .append(append)
        .truncate(!append)
        .open(part)
        .await
        .map_err(|e| CliError::failed(format!("couldn't write {}: {e}", part.display())))?;
    let mut body = response.bytes_stream();
    while let Some(chunk) = body.next().await {
        let chunk = chunk.map_err(|e| CliError::network(format!("the download dropped: {e}")))?;
        out.write_all(&chunk).await.map_err(|e| CliError::failed(format!("couldn't write {}: {e}", part.display())))?;
    }
    out.flush().await.map_err(|e| CliError::failed(e.to_string()))?;
    let written = tokio::fs::metadata(part).await.map_or(0, |meta| meta.len());
    if written == size { Ok(()) } else { Err(CliError::network(format!("only {written} of {size} bytes arrived"))) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_output_stays_inside_the_download_folder() {
        let dir = Path::new("/tmp/out");
        assert_eq!(destination(dir, "adapter_model.safetensors").unwrap(), dir.join("adapter_model.safetensors"));
        assert_eq!(destination(dir, "logs/train.txt").unwrap(), dir.join("logs/train.txt"));
    }

    #[test]
    fn a_path_that_would_leave_it_is_refused() {
        let dir = Path::new("/tmp/out");
        for bad in ["../x", "a/../../x", "/etc/passwd", "", "a\\b", "./a"] {
            assert!(destination(dir, bad).is_err(), "{bad}");
        }
    }
}
