//! Downloading a release's file (ADR 0034): resumed with an HTTP Range request where a part was left, then checked
//! against the manifest's size and SHA-256 before it is moved into place. The link itself is never trusted; the
//! manifest's numbers are.
//!
//! The partial file is `<name>.part` beside the destination. A finished file whose size or hash doesn't match is
//! deleted, so the next try starts clean.

use std::fmt;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_stream::StreamExt;

use crate::manifest::Artifact;

/// Why a download failed.
#[derive(Debug)]
pub enum DownloadError {
    /// The download couldn't be finished: the network, the server, or the disk.
    Download(String),
    /// It finished, but its size or SHA-256 isn't the manifest's.
    Verify(String),
}

impl fmt::Display for DownloadError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Download(e) => write!(f, "the download couldn't be finished: {e}"),
            Self::Verify(e) => write!(f, "the download didn't match the release: {e}"),
        }
    }
}

impl std::error::Error for DownloadError {}

/// Downloads `url` to `dest`, resuming a part left from before, and checks it is `expected`. `progress` hears the
/// bytes there so far and the total. An existing `dest` that already matches is kept without downloading.
///
/// # Errors
/// [`DownloadError::Download`] if it can't be finished, [`DownloadError::Verify`] if it doesn't match.
pub async fn download(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    expected: &Artifact,
    progress: &mut (dyn FnMut(u64, u64) + Send),
) -> Result<PathBuf, DownloadError> {
    if matches(dest, expected).await.unwrap_or(false) {
        progress(expected.size, expected.size);
        return Ok(dest.to_owned());
    }
    let part = part_path(dest);
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await.map_err(|e| DownloadError::Download(e.to_string()))?;
    }
    let mut have = tokio::fs::metadata(&part).await.map_or(0, |meta| meta.len());
    if have > expected.size {
        let _ = tokio::fs::remove_file(&part).await;
        have = 0;
    }
    if have < expected.size {
        let mut request = client.get(url);
        if have > 0 {
            request = request.header(reqwest::header::RANGE, format!("bytes={have}-"));
        }
        let response = request.send().await.map_err(|e| DownloadError::Download(e.to_string()))?;
        let status = response.status();
        let append = match status.as_u16() {
            206 if have > 0 => true,
            200 => false,
            // The part already holds everything the server has.
            416 => true,
            _ => return Err(DownloadError::Download(format!("the server answered {status}"))),
        };
        if !append {
            have = 0;
        }
        let mut file = tokio::fs::OpenOptions::new()
            .create(true)
            .write(true)
            .append(append)
            .truncate(!append)
            .open(&part)
            .await
            .map_err(|e| DownloadError::Download(e.to_string()))?;
        if status.as_u16() != 416 {
            let mut body = response.bytes_stream();
            while let Some(chunk) = body.next().await {
                let chunk = chunk.map_err(|e| DownloadError::Download(e.to_string()))?;
                have += chunk.len() as u64;
                if have > expected.size {
                    drop(file);
                    let _ = tokio::fs::remove_file(&part).await;
                    return Err(DownloadError::Verify(format!(
                        "it's larger than the {} bytes expected",
                        expected.size
                    )));
                }
                file.write_all(&chunk).await.map_err(|e| DownloadError::Download(e.to_string()))?;
                progress(have, expected.size);
            }
        }
        file.flush().await.map_err(|e| DownloadError::Download(e.to_string()))?;
        file.sync_all().await.map_err(|e| DownloadError::Download(e.to_string()))?;
    }
    let size = tokio::fs::metadata(&part).await.map_err(|e| DownloadError::Download(e.to_string()))?.len();
    if size < expected.size {
        return Err(DownloadError::Download(format!("it stopped at {size} of {} bytes", expected.size)));
    }
    let hash = sha256_file(&part).await.map_err(|e| DownloadError::Download(e.to_string()))?;
    if size != expected.size || hash != expected.sha256 {
        let _ = tokio::fs::remove_file(&part).await;
        return Err(DownloadError::Verify(format!(
            "{size} bytes with SHA-256 {hash}, not {} bytes with {}",
            expected.size, expected.sha256
        )));
    }
    tokio::fs::rename(&part, dest).await.map_err(|e| DownloadError::Download(e.to_string()))?;
    Ok(dest.to_owned())
}

/// Whether the file at `path` is `expected`, by size then SHA-256.
///
/// # Errors
/// If it can't be read.
pub async fn matches(path: &Path, expected: &Artifact) -> std::io::Result<bool> {
    let size = tokio::fs::metadata(path).await?.len();
    Ok(size == expected.size && sha256_file(path).await? == expected.sha256)
}

/// A file's SHA-256, lowercase hex.
///
/// # Errors
/// If it can't be read.
pub async fn sha256_file(path: &Path) -> std::io::Result<String> {
    let mut file = tokio::fs::File::open(path).await?;
    let mut hasher = Sha256::new();
    let mut buffer = vec![0u8; 1 << 16];
    loop {
        let read = file.read(&mut buffer).await?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hex(&hasher.finalize()))
}

/// Bytes as lowercase hex.
#[must_use]
pub fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, byte| {
        let _ = write!(out, "{byte:02x}");
        out
    })
}

fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.file_name().map(std::ffi::OsStr::to_os_string).unwrap_or_default();
    name.push(".part");
    dest.with_file_name(name)
}

/// The HTTP client for downloads: rustls with the operating system's roots, plain HTTP allowed for a development
/// stack on the LAN, and no overall timeout (a slow link may take minutes), only a connect timeout.
///
/// # Errors
/// If the TLS configuration can't be built.
pub fn http_client() -> Result<reqwest::Client, String> {
    let provider = std::sync::Arc::new(rustls::crypto::ring::default_provider());
    let verifier = rustls_platform_verifier::Verifier::new(provider.clone())
        .map_err(|e| format!("the system's certificates couldn't be loaded: {e}"))?;
    let tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(std::sync::Arc::new(verifier))
        .with_no_client_auth();
    reqwest::Client::builder()
        .use_preconfigured_tls(tls)
        .connect_timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};

    use sha2::{Digest, Sha256};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::*;

    /// A tiny HTTP server that serves `body`, honouring `Range: bytes=N-` with a 206, and dropping the connection after
    /// `cut_after` bytes of its first answer.
    async fn serve(body: Vec<u8>, cut_after: Option<usize>) -> (String, Arc<AtomicUsize>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let requests = Arc::new(AtomicUsize::new(0));
        let counted = Arc::clone(&requests);
        tokio::spawn(async move {
            loop {
                let Ok((mut socket, _)) = listener.accept().await else { return };
                let n = counted.fetch_add(1, Ordering::SeqCst);
                let body = body.clone();
                tokio::spawn(async move {
                    let mut request = vec![0u8; 4096];
                    let read = socket.read(&mut request).await.unwrap_or(0);
                    let request = String::from_utf8_lossy(&request[..read]).to_lowercase();
                    let from = request.lines().find_map(|line| line.strip_prefix("range: bytes=")).and_then(|range| {
                        range.trim_end_matches('-').trim().trim_end_matches('-').parse::<usize>().ok()
                    });
                    let (status, slice) = match from {
                        Some(from) => ("206 Partial Content", &body[from..]),
                        None => ("200 OK", &body[..]),
                    };
                    let head =
                        format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", slice.len());
                    let _ = socket.write_all(head.as_bytes()).await;
                    let send = match cut_after {
                        Some(cut) if n == 0 => &slice[..cut],
                        _ => slice,
                    };
                    let _ = socket.write_all(send).await;
                    let _ = socket.shutdown().await;
                });
            }
        });
        (format!("http://{address}/file"), requests)
    }

    fn artifact(body: &[u8]) -> Artifact {
        Artifact { file: "f".into(), sha256: hex(&Sha256::digest(body)), size: body.len() as u64 }
    }

    #[tokio::test]
    async fn a_cut_download_resumes_from_its_part_and_is_checked() {
        let body: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let (url, requests) = serve(body.clone(), Some(70_000)).await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("app.zip");
        let client = http_client().unwrap();
        let mut seen = 0;
        let first = download(&client, &url, &dest, &artifact(&body), &mut |have, _| seen = have).await;
        assert!(matches!(first, Err(DownloadError::Download(_))), "{first:?}");
        assert!(seen >= 70_000 || seen == 0);
        let part_size = std::fs::metadata(part_path(&dest)).unwrap().len();
        assert!(part_size > 0 && part_size < body.len() as u64);

        download(&client, &url, &dest, &artifact(&body), &mut |_, _| {}).await.unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), body);
        assert!(!part_path(&dest).exists());
        assert_eq!(requests.load(Ordering::SeqCst), 2);

        // Already there and matching: nothing is fetched.
        download(&client, &url, &dest, &artifact(&body), &mut |_, _| {}).await.unwrap();
        assert_eq!(requests.load(Ordering::SeqCst), 2);
    }

    #[tokio::test]
    async fn a_file_that_doesnt_match_is_refused_and_deleted() {
        let body = b"the real app".to_vec();
        let (url, _) = serve(b"a tampered app".to_vec(), None).await;
        let dir = tempfile::tempdir().unwrap();
        let dest = dir.path().join("app.zip");
        let mut wrong = artifact(&body);
        wrong.size = 14;
        let result = download(&http_client().unwrap(), &url, &dest, &wrong, &mut |_, _| {}).await;
        assert!(matches!(result, Err(DownloadError::Verify(_))), "{result:?}");
        assert!(!dest.exists());
        assert!(!part_path(&dest).exists());

        let small = artifact(b"short");
        let result = download(&http_client().unwrap(), &url, &dest, &small, &mut |_, _| {}).await;
        assert!(matches!(result, Err(DownloadError::Verify(_))), "{result:?}");
    }
}
