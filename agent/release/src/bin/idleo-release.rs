//! `idleo-release`: the Windows app's release tool (ADR 0034), run by `scripts/release/*.sh`, never shipped.
//!
//! - `keygen --public <path> --private <path>`: a new Ed25519 key pair; the private half is written readable only by
//!   you, the public half as base64 on one line. Neither is printed.
//! - `manifest …`: writes `latest.json` for a release from its files (their sizes and SHA-256).
//! - `sign --key <path> <manifest>`: the only command that reads a private key; writes `<manifest>.sig`.
//! - `verify --public <path> <manifest>`: checks a manifest and its `.sig`, as the app will.
//! - `upload …`: puts files in the release bucket (S3: MinIO locally, R2 in production), keys from the environment.
#![forbid(unsafe_code)]

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use clap::{Parser, Subcommand};
use ed25519_dalek::{Signer, SigningKey};
use idleo_release::download::{hex, sha256_file};
use idleo_release::manifest::{self, Artifact, CHANNEL, Distro, Manifest};
use rusty_s3::{Bucket, Credentials, S3Action, UrlStyle};
use time::OffsetDateTime;

#[derive(Parser)]
#[command(name = "idleo-release", about = "The Windows app's release tool: keys, manifests, signatures, uploads")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Make a new Ed25519 key pair. Prints nothing secret.
    Keygen {
        /// Where the public key goes (base64, one line).
        #[arg(long)]
        public: PathBuf,
        /// Where the private key goes (base64 of its seed, readable only by you). Never inside the repository.
        #[arg(long)]
        private: PathBuf,
    },
    /// Write a release's manifest from its files.
    Manifest {
        /// The app's version.
        #[arg(long)]
        version: String,
        /// The share of hosts offered it, 0 to 100.
        #[arg(long, value_parser = clap::value_parser!(u8).range(0..=100))]
        rollout: u8,
        /// The oldest Windows build it runs on.
        #[arg(long, default_value_t = 19045)]
        min_windows_build: u32,
        /// The app zip.
        #[arg(long)]
        app: PathBuf,
        /// The distro tarball.
        #[arg(long)]
        distro: PathBuf,
        /// The distro's version.
        #[arg(long)]
        distro_version: String,
        /// The bare setup program.
        #[arg(long)]
        setup: PathBuf,
        /// Where to write it.
        #[arg(long)]
        out: PathBuf,
    },
    /// Sign a manifest with a private key; writes `<manifest>.sig`.
    Sign {
        /// The private key file. Read, never printed or copied.
        #[arg(long)]
        key: PathBuf,
        /// The manifest.
        manifest: PathBuf,
    },
    /// Check a manifest and its `.sig` against a public key, as the app will.
    Verify {
        /// The public key file.
        #[arg(long)]
        public: PathBuf,
        /// The manifest.
        manifest: PathBuf,
    },
    /// Put files in the release bucket, with keys from `IDLEO_RELEASE_S3_ACCESS_KEY_ID` and
    /// `IDLEO_RELEASE_S3_SECRET_ACCESS_KEY`.
    Upload {
        /// The S3 endpoint, for example `http://localhost:9100`.
        #[arg(long)]
        endpoint: String,
        /// The bucket.
        #[arg(long, default_value = "releases")]
        bucket: String,
        /// The signing region.
        #[arg(long, default_value = "us-east-1")]
        region: String,
        /// Create the bucket first if it isn't there (development).
        #[arg(long)]
        create_bucket: bool,
        /// Pairs of a local file and its key in the bucket: FILE=KEY.
        #[arg(required = true, value_name = "FILE=KEY")]
        files: Vec<String>,
    },
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(e) => return fail(&e),
    };
    let result = runtime.block_on(async {
        match cli.command {
            Command::Keygen { public, private } => keygen(&public, &private),
            Command::Manifest { version, rollout, min_windows_build, app, distro, distro_version, setup, out } => {
                write_manifest(&version, rollout, min_windows_build, &app, &distro, &distro_version, &setup, &out).await
            }
            Command::Sign { key, manifest } => sign(&key, &manifest),
            Command::Verify { public, manifest } => verify(&public, &manifest),
            Command::Upload { endpoint, bucket, region, create_bucket, files } => {
                upload(&endpoint, &bucket, &region, create_bucket, &files).await
            }
        }
    });
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => fail(&e),
    }
}

fn fail(error: &dyn std::fmt::Display) -> ExitCode {
    eprintln!("idleo-release: {error}");
    ExitCode::FAILURE
}

fn keygen(public: &Path, private: &Path) -> Result<(), String> {
    if private.exists() {
        return Err(format!("{} exists; a key is never overwritten", private.display()));
    }
    let mut seed = [0u8; 32];
    getrandom::fill(&mut seed).map_err(|e| format!("no randomness: {e}"))?;
    let key = SigningKey::from_bytes(&seed);
    write_private(private, &STANDARD.encode(seed))?;
    if let Some(parent) = public.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(public, format!("{}\n", STANDARD.encode(key.verifying_key().to_bytes())))
        .map_err(|e| format!("{}: {e}", public.display()))?;
    println!("Wrote the public key to {} and the private key to {}.", public.display(), private.display());
    Ok(())
}

#[cfg(unix)]
fn write_private(path: &Path, text: &str) -> Result<(), String> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)
        .map_err(|e| format!("{}: {e}", path.display()))?;
    writeln!(file, "{text}").map_err(|e| e.to_string())
}

#[cfg(not(unix))]
fn write_private(path: &Path, text: &str) -> Result<(), String> {
    use std::io::Write;
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|e| format!("{}: {e}", path.display()))?;
    writeln!(file, "{text}").map_err(|e| e.to_string())
}

#[allow(clippy::too_many_arguments)] // One per flag of the command.
async fn write_manifest(
    version: &str,
    rollout: u8,
    min_windows_build: u32,
    app: &Path,
    distro: &Path,
    distro_version: &str,
    setup: &Path,
    out: &Path,
) -> Result<(), String> {
    let artifact = async |path: &Path, file: String| -> Result<Artifact, String> {
        let size = tokio::fs::metadata(path).await.map_err(|e| format!("{}: {e}", path.display()))?.len();
        let sha256 = sha256_file(path).await.map_err(|e| format!("{}: {e}", path.display()))?;
        Ok(Artifact { file, sha256, size })
    };
    let name = |path: &Path| path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let distro_file = artifact(distro, format!("distro/{}", name(distro))).await?;
    let manifest = Manifest {
        version: version.to_owned(),
        channel: CHANNEL.to_owned(),
        released_at: OffsetDateTime::now_utc().replace_nanosecond(0).map_err(|e| e.to_string())?,
        rollout_percent: rollout,
        min_windows_build,
        app: artifact(app, format!("{version}/{}", name(app))).await?,
        distro: Distro {
            version: distro_version.to_owned(),
            file: distro_file.file,
            sha256: distro_file.sha256,
            size: distro_file.size,
        },
        setup: artifact(setup, format!("{version}/{}", name(setup))).await?,
    };
    let text = serde_json::to_string_pretty(&manifest).map_err(|e| e.to_string())?;
    std::fs::write(out, format!("{text}\n")).map_err(|e| format!("{}: {e}", out.display()))?;
    println!("Wrote {} for {version} at {rollout}% rollout.", out.display());
    Ok(())
}

fn sign(key: &Path, manifest_path: &Path) -> Result<(), String> {
    let seed = STANDARD
        .decode(std::fs::read_to_string(key).map_err(|e| format!("the key file couldn't be read: {e}"))?.trim())
        .map_err(|_| "the key file isn't a base64 private key".to_owned())?;
    let seed: [u8; 32] = seed.try_into().map_err(|_| "the key file isn't a 32-byte Ed25519 key".to_owned())?;
    let key = SigningKey::from_bytes(&seed);
    let bytes = std::fs::read(manifest_path).map_err(|e| format!("{}: {e}", manifest_path.display()))?;
    let signature = key.sign(&bytes);
    let out = sig_path(manifest_path);
    std::fs::write(&out, format!("{}\n", STANDARD.encode(signature.to_bytes())))
        .map_err(|e| format!("{}: {e}", out.display()))?;
    println!("Signed {} ({}).", manifest_path.display(), out.display());
    Ok(())
}

fn verify(public: &Path, manifest_path: &Path) -> Result<(), String> {
    let key = STANDARD
        .decode(std::fs::read_to_string(public).map_err(|e| format!("{}: {e}", public.display()))?.trim())
        .map_err(|_| "the public key isn't base64".to_owned())?;
    let key: [u8; 32] = key.try_into().map_err(|_| "the public key isn't 32 bytes".to_owned())?;
    let bytes = std::fs::read(manifest_path).map_err(|e| format!("{}: {e}", manifest_path.display()))?;
    let signature = manifest::decode_signature(
        &std::fs::read_to_string(sig_path(manifest_path)).map_err(|e| format!("the signature: {e}"))?,
    )
    .map_err(|e| e.to_string())?;
    let verified = manifest::verify(&bytes, &signature, &key).map_err(|e| e.to_string())?;
    println!("{} {} verifies ({}% rollout).", manifest_path.display(), verified.version, verified.rollout_percent);
    Ok(())
}

fn sig_path(manifest: &Path) -> PathBuf {
    let mut name = manifest.file_name().map(std::ffi::OsStr::to_os_string).unwrap_or_default();
    name.push(".sig");
    manifest.with_file_name(name)
}

async fn upload(endpoint: &str, bucket: &str, region: &str, create: bool, files: &[String]) -> Result<(), String> {
    let key_id = std::env::var("IDLEO_RELEASE_S3_ACCESS_KEY_ID")
        .map_err(|_| "set IDLEO_RELEASE_S3_ACCESS_KEY_ID and IDLEO_RELEASE_S3_SECRET_ACCESS_KEY".to_owned())?;
    let secret = std::env::var("IDLEO_RELEASE_S3_SECRET_ACCESS_KEY")
        .map_err(|_| "set IDLEO_RELEASE_S3_SECRET_ACCESS_KEY".to_owned())?;
    let credentials = Credentials::new(key_id, secret);
    let bucket = Bucket::new(
        endpoint.parse().map_err(|e| format!("the endpoint isn't a URL: {e}"))?,
        UrlStyle::Path,
        bucket.to_owned(),
        region.to_owned(),
    )
    .map_err(|e| e.to_string())?;
    let client = idleo_release::download::http_client()?;
    let one_hour = Duration::from_secs(3600);
    if create {
        let url = bucket.create_bucket(&credentials).sign(one_hour);
        let response = client.put(url).send().await.map_err(|e| e.to_string())?;
        let status = response.status();
        let body = response.text().await.unwrap_or_default();
        if !status.is_success() && !body.contains("BucketAlreadyOwnedByYou") && !body.contains("BucketAlreadyExists") {
            return Err(format!("couldn't create the bucket: {status} {body}"));
        }
    }
    for pair in files {
        let (file, key) = pair.split_once('=').ok_or_else(|| format!("{pair} isn't FILE=KEY"))?;
        let bytes = tokio::fs::read(file).await.map_err(|e| format!("{file}: {e}"))?;
        let url = bucket.put_object(Some(&credentials), key).sign(one_hour);
        let size = bytes.len();
        let response = client.put(url).body(bytes).send().await.map_err(|e| format!("{key}: {e}"))?;
        if !response.status().is_success() {
            return Err(format!("{key}: {} {}", response.status(), response.text().await.unwrap_or_default()));
        }
        println!("Uploaded {key} ({} MB, sha256 {}).", size / 1_000_000, short(file).await);
    }
    Ok(())
}

async fn short(file: &str) -> String {
    sha256_file(Path::new(file)).await.map_or_else(|_| hex(&[]), |sha| sha[..12].to_owned())
}
