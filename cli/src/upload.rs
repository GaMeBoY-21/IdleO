//! A folder of files, or one file, uploaded straight to storage (ADR 0019, ADR 0022), as home internet allows: the
//! same as the web app's upload (web/src/features/book/upload.ts), in Rust.
//!
//! - `POST /v1/uploads` opens it with each file's path and size; each file's parts are signed on demand
//!   (`POST /v1/uploads/{id}/part-urls`), a hundred at a time, so a 4,000-part model never asks for 4,000 links at once;
//! - each part is a slice of its file, never the whole file in memory, PUT three at a time;
//! - each `ETag` is remembered in a file as it arrives (`src/resume.rs`): running the same command again opens the same
//!   upload with the same Idempotency-Key and sends only the parts storage hasn't taken;
//! - every file is then completed (`POST /v1/uploads/{id}/complete`), a hundred to a call;
//! - a failed part is retried three times with backoff; an expired link (403) gets a fresh one.

use std::collections::BTreeMap;
use std::io::SeekFrom;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use futures_util::stream::{self, StreamExt};
use reqwest::Method;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use uuid::Uuid;

use crate::api::{
    CompleteUploadRequest, CompletedFile, CompletedFilePath, CreateUploadRequest, PartLinksRequest, UploadDetail,
    UploadFileRequest, UploadPartLinks, UploadResponseStatus, UploadedPart, UploadedPartEtag,
};
use crate::bytes_fmt::format_bytes;
use crate::client::Client;
use crate::context::Context;
use crate::error::{CliError, Result};
use crate::files::{self, Local, fingerprint, part_range};
use crate::resume::{ResumeStore, Unfinished};

const CONCURRENCY: usize = 3;
/// The most part links, and the most files to complete, one request takes, as the API allows.
const PER_REQUEST: usize = 100;
const RETRIES: u32 = 3;

/// Uploads what's at `root`, or carries on with it; the upload's id once it's ready.
///
/// # Errors
/// If the files can't be read, the API refuses, or a part fails for good.
pub async fn upload(ctx: &Context, resume: &ResumeStore, root: &std::path::Path, label: &str) -> Result<Uuid> {
    let (name, files) = files::collect(root)?;
    let fp = fingerprint(&name, &files);
    let total: u64 = files.iter().map(|file| file.size).sum();
    let specs = CreateUploadRequest {
        name: name
            .as_str()
            .try_into()
            .map_err(|_| CliError::usage(format!("{} has a name the API can't take", root.display())))?,
        files: files
            .iter()
            .map(|file| {
                Ok(UploadFileRequest {
                    path: file.path.as_str().try_into().map_err(|_| {
                        CliError::usage(format!("{} can't be uploaded: its path is empty or too long", file.path))
                    })?,
                    size_bytes: i64::try_from(file.size).map_err(|_| CliError::usage("a file is too large"))?,
                })
            })
            .collect::<Result<_>>()?,
    };
    ctx.note(format!("{label}: {} file(s), {}", files.len(), format_bytes(total)));
    let (opened, mut record) = open(ctx, resume, &fp, &specs).await?;
    let id = opened.upload.id;
    record.upload_id = Some(id);
    if opened.upload.status == UploadResponseStatus::Ready {
        resume.forget(&fp);
        ctx.note(format!("{label}: already uploaded"));
        return Ok(id);
    }
    let part_size = u64::try_from(opened.upload.part_size_bytes)
        .map_err(|_| CliError::failed("the API sent a part size this version can't use"))?;
    let by_path: BTreeMap<&str, &Local> = files.iter().map(|file| (file.path.as_str(), file)).collect();

    // What storage still needs: every part of every unfinished file that the record doesn't have.
    let mut todo: Vec<(String, u32)> = Vec::new();
    let mut base = 0_u64;
    let mut left = Vec::new();
    for file in &opened.files {
        if file.done {
            base += u64::try_from(file.size_bytes).unwrap_or(0);
            continue;
        }
        left.push(file.path.clone());
        let taken = record.parts.entry(file.path.clone()).or_default();
        for number in 1..=u32::try_from(file.part_count).unwrap_or(0) {
            if taken.contains_key(&number) {
                let size = u64::try_from(file.size_bytes).unwrap_or(0);
                let (from, to) = part_range(number, part_size, size);
                base += to - from;
            } else {
                todo.push((file.path.clone(), number));
            }
        }
    }
    let state = Arc::new(Mutex::new(record));
    let progress = Arc::new(Progress::new(base, total));
    let links = Links::default();
    let fp_ref = fp.as_str();
    let sent = stream::iter(todo.clone()).map(|(path, number)| {
        let file = by_path[path.as_str()];
        let (state, progress, links) = (state.clone(), progress.clone(), links.clone());
        let todo = &todo;
        async move {
            let etag = send_part(ctx, id, file, number, part_size, &links, todo, &progress, label).await?;
            let mut record = state.lock().unwrap_or_else(PoisonError::into_inner);
            record.parts.entry(path).or_default().insert(number, etag);
            resume.write(fp_ref, &record);
            Ok::<(), CliError>(())
        }
    });
    let mut running = sent.buffer_unordered(CONCURRENCY);
    while let Some(done) = running.next().await {
        done?;
    }
    drop(running);

    let record = state.lock().unwrap_or_else(PoisonError::into_inner).clone();
    complete(&ctx.client, id, &left, &record).await?;
    resume.forget(&fp);
    ctx.note(format!("{label}: uploaded {}", format_bytes(total)));
    Ok(id)
}

/// The upload these files went to before, if one is remembered and still wanted, opened again with its key; else a
/// new one.
async fn open(
    ctx: &Context,
    resume: &ResumeStore,
    fp: &str,
    specs: &CreateUploadRequest,
) -> Result<(UploadDetail, Unfinished)> {
    if let Some(saved) = resume.read(fp) {
        match ctx.client.post::<_, UploadDetail>("/v1/uploads", specs, Some(&saved.key)).await {
            Ok(again)
                if Some(again.upload.id) == saved.upload_id
                    && matches!(again.upload.status, UploadResponseStatus::Pending | UploadResponseStatus::Ready) =>
            {
                ctx.note("carrying on from where it stopped");
                return Ok((again, saved));
            }
            // Gone, failed or refused: start over.
            Ok(_) | Err(_) => resume.forget(fp),
        }
    }
    let key = Uuid::now_v7().to_string();
    let opened: UploadDetail = ctx.client.post("/v1/uploads", specs, Some(&key)).await?;
    let record = Unfinished { upload_id: Some(opened.upload.id), key, parts: BTreeMap::new() };
    resume.write(fp, &record);
    Ok((opened, record))
}

/// Part links already asked for, by file path and part number.
#[derive(Clone, Default)]
struct Links(Arc<tokio::sync::Mutex<BTreeMap<(String, u32), String>>>);

impl Links {
    /// The link for one part, asking for it and the file's next parts still to send, a hundred at most.
    async fn url(&self, client: &Client, id: Uuid, path: &str, number: u32, todo: &[(String, u32)]) -> Result<String> {
        let mut known = self.0.lock().await;
        if let Some(url) = known.get(&(path.to_owned(), number)) {
            return Ok(url.clone());
        }
        let mut wanted: Vec<u32> = vec![number];
        wanted.extend(
            todo.iter()
                .filter(|(p, n)| p == path && *n != number && !known.contains_key(&(p.clone(), *n)))
                .map(|(_, n)| *n),
        );
        wanted.truncate(PER_REQUEST);
        let body = PartLinksRequest {
            path: path.try_into().map_err(|_| CliError::failed("a file path the API can't take"))?,
            part_numbers: wanted.iter().filter_map(|n| std::num::NonZeroU32::new(*n)).collect(),
        };
        let links: UploadPartLinks = client.post(&format!("/v1/uploads/{id}/part-urls"), &body, None).await?;
        for link in links.parts {
            known.insert((path.to_owned(), u32::try_from(link.number).unwrap_or(0)), link.url);
        }
        known
            .get(&(path.to_owned(), number))
            .cloned()
            .ok_or_else(|| CliError::failed(format!("the API sent no link for part {number} of {path}")))
    }

    async fn forget(&self, path: &str, number: u32) {
        self.0.lock().await.remove(&(path.to_owned(), number));
    }
}

#[allow(clippy::too_many_arguments)] // One part's whole story; splitting it only moves the arguments around.
async fn send_part(
    ctx: &Context,
    id: Uuid,
    file: &Local,
    number: u32,
    part_size: u64,
    links: &Links,
    todo: &[(String, u32)],
    progress: &Progress,
    label: &str,
) -> Result<String> {
    let (from, to) = part_range(number, part_size, file.size);
    let mut failures = 0;
    let mut refreshes = 0;
    loop {
        let url = links.url(&ctx.client, id, &file.path, number, todo).await?;
        let body = read_slice(&file.from, from, to - from).await?;
        match put(ctx.client.transfers(), &url, body).await {
            Ok(etag) => {
                progress.add(to - from, ctx, label);
                return Ok(etag);
            }
            Err(Failure::Status(403)) if refreshes < RETRIES => {
                // The part's link has expired: forget it, so the next try asks for a fresh one.
                refreshes += 1;
                links.forget(&file.path, number).await;
            }
            Err(failure) => {
                failures += 1;
                if failures > RETRIES {
                    return Err(CliError::network(format!(
                        "part {number} of {} couldn't be sent: {failure}",
                        file.path
                    )));
                }
                tokio::time::sleep(Duration::from_secs(2_u64.pow(failures - 1))).await;
            }
        }
    }
}

async fn read_slice(path: &PathBuf, from: u64, len: u64) -> Result<Vec<u8>> {
    let fail = |e: std::io::Error| CliError::failed(format!("couldn't read {}: {e}", path.display()));
    let mut file = tokio::fs::File::open(path).await.map_err(fail)?;
    file.seek(SeekFrom::Start(from)).await.map_err(fail)?;
    let mut bytes =
        vec![0; usize::try_from(len).map_err(|_| CliError::failed("a part is too large for this computer"))?];
    file.read_exact(&mut bytes).await.map_err(fail)?;
    Ok(bytes)
}

enum Failure {
    Status(u16),
    Other(String),
}

impl std::fmt::Display for Failure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Status(status) => write!(f, "storage answered {status}"),
            Self::Other(why) => f.write_str(why),
        }
    }
}

/// One part to its signed link; storage's answer carries the part's `ETag`.
async fn put(http: &reqwest::Client, url: &str, body: Vec<u8>) -> std::result::Result<String, Failure> {
    let response = http.put(url).body(body).send().await.map_err(|e| Failure::Other(e.to_string()))?;
    if !response.status().is_success() {
        return Err(Failure::Status(response.status().as_u16()));
    }
    response
        .headers()
        .get(reqwest::header::ETAG)
        .and_then(|value| value.to_str().ok())
        .map(str::to_owned)
        .ok_or_else(|| Failure::Other("storage didn't say it had the part (no ETag)".to_owned()))
}

async fn complete(client: &Client, id: Uuid, left: &[String], record: &Unfinished) -> Result<()> {
    let finished: Vec<CompletedFile> = left
        .iter()
        .map(|path| {
            Ok(CompletedFile {
                path: CompletedFilePath::try_from(path.as_str())
                    .map_err(|_| CliError::failed("a file path the API can't take"))?,
                parts: record
                    .parts
                    .get(path)
                    .into_iter()
                    .flatten()
                    .map(|(number, etag)| {
                        Ok(UploadedPart {
                            number: std::num::NonZeroU32::new(*number)
                                .ok_or_else(|| CliError::failed("a part number of 0"))?,
                            etag: UploadedPartEtag::try_from(etag.as_str())
                                .map_err(|_| CliError::failed("an ETag the API can't take"))?,
                        })
                    })
                    .collect::<Result<_>>()?,
            })
        })
        .collect::<Result<_>>()?;
    for batch in finished.chunks(PER_REQUEST) {
        let body = CompleteUploadRequest { files: batch.to_vec() };
        client.send(client.authed(Method::POST, &format!("/v1/uploads/{id}/complete"))?.json(&body)).await?;
    }
    Ok(())
}

/// Bytes storage has taken, shown as a percentage and a speed about once a second.
struct Progress {
    total: u64,
    inner: Mutex<(u64, Instant, Instant, u64)>,
}

impl Progress {
    fn new(base: u64, total: u64) -> Self {
        let now = Instant::now();
        Self { total, inner: Mutex::new((base, now, now, base)) }
    }

    fn add(&self, bytes: u64, ctx: &Context, label: &str) {
        let mut inner = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        inner.0 += bytes;
        let (sent, started, last, base) = *inner;
        if last.elapsed() >= Duration::from_secs(1) || sent >= self.total {
            inner.1 = started;
            inner.2 = Instant::now();
            let speed =
                u64::try_from(u128::from(sent - base) * 1000 / started.elapsed().as_millis().max(1)).unwrap_or(0);
            let percent = sent.saturating_mul(100).checked_div(self.total).unwrap_or(100);
            ctx.note(format!(
                "{label}: {percent}%  {} of {}  {}/s",
                format_bytes(sent),
                format_bytes(self.total),
                format_bytes(speed)
            ));
        }
    }
}
