//! The control plane's internal API for the scanner (ADR 0042): on its own port, which the load balancer never routes, with
//! a bearer token of the scanner's. The scanner asks for work, says what a reference resolved to (the control plane decides
//! the limits and whether a cached result already answers it), reports progress (which keeps its claim), and ends a check
//! with its findings, a refusal (the image is blocked) or a failure (not checked: the image can't be booked). The control
//! plane decides every verdict; the scanner only gathers facts.

use std::time::Duration;

use reqwest::{Client, StatusCode, Url};
use serde::{Deserialize, Serialize};

use crate::layers::Executable;
use crate::registry::Login;
use crate::trivy::Findings;

/// A check to do.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Claim {
    /// The check.
    pub check_id: String,
    /// This scanner's claim on it, which every later step names: a scanner whose claim lapsed can't touch the check.
    pub claim: String,
    /// The reference as the renter gave it.
    pub reference: String,
    /// A private registry's credentials.
    #[serde(default)]
    pub login: Option<Login>,
    /// `linux/amd64`.
    pub platform: String,
    /// The most one layer may unpack to.
    pub max_layer_unpacked_bytes: u64,
    /// The most the image may unpack to.
    pub max_image_unpacked_bytes: u64,
    /// How long the whole check may take.
    pub deadline_seconds: u64,
}

/// What the reference resolved to.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Resolution {
    /// The platform manifest's digest, when there is one for the platform.
    pub digest: Option<String>,
    /// The platforms the image has, when it hasn't the one asked for.
    pub platforms: Vec<String>,
    /// The layers' compressed sizes together.
    pub compressed_bytes: u64,
    /// How many layers.
    pub layers: u32,
}

/// What the image's config says, for matching and for running it.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageConfig {
    /// `NVIDIA_REQUIRE_CUDA`, as written.
    pub cuda_require: Option<String>,
    /// `CUDA_VERSION`, as written.
    pub cuda_version: Option<String>,
    /// Its entrypoint.
    pub entrypoint: Vec<String>,
    /// Its command.
    pub cmd: Vec<String>,
    /// Its working directory.
    pub working_dir: Option<String>,
    /// Its user, as written (empty is root).
    pub user: Option<String>,
}

/// A finished check's facts.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Result_ {
    /// The digest checked.
    pub digest: String,
    /// Its layers' compressed sizes together.
    pub compressed_bytes: u64,
    /// How many layers it has.
    pub layers: u32,
    /// The config.
    pub config: ImageConfig,
    /// What every layer unpacks to together.
    pub unpacked_bytes: u64,
    /// Whether `/bin/sh` is there.
    pub has_shell: bool,
    /// Every ELF file in every layer.
    pub executables: Vec<ExecutableWire>,
    /// Trivy's.
    pub findings: Findings,
}

/// An ELF file, for the wire.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutableWire {
    /// Its path.
    pub path: String,
    /// Its SHA-256.
    pub sha256: String,
    /// Its layer, from 0.
    pub layer: usize,
}

impl From<&Executable> for ExecutableWire {
    fn from(e: &Executable) -> Self {
        Self { path: e.path.clone(), sha256: e.sha256.clone(), layer: e.layer }
    }
}

/// Why an image is refused: the reason is a stable code, the detail plain words.
#[derive(Debug, Clone, Serialize)]
pub struct Refused {
    /// `pull_failed`, `unpack_failed`, `too_large_unpacked` or `unsafe_path`.
    pub reason: &'static str,
    /// What the renter reads.
    pub detail: String,
}

/// What a step was, for progress.
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Phase {
    /// Resolving and downloading.
    Downloading,
    /// Reading the layers.
    Reading,
    /// Trivy.
    Scanning,
}

/// The control plane, from the scanner's side.
#[derive(Clone)]
pub struct ControlPlane {
    http: Client,
    base: Url,
    token: String,
}

/// The control plane's answer to a step: carry on, or stop (the claim was lost, the renter removed the image, or a cached
/// result or a limit already decided it).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Next {
    /// Carry on.
    Proceed,
    /// Stop and delete what's downloaded.
    Stop,
}

#[derive(Deserialize)]
struct Proceed {
    proceed: bool,
}

impl ControlPlane {
    /// The control plane at `base` (its internal port), with the scanner's `token`.
    ///
    /// # Errors
    /// If no client can be built.
    pub fn new(base: Url, token: String) -> Result<Self, String> {
        let http = Client::builder()
            .use_preconfigured_tls(crate::tls_config())
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(60))
            .build()
            .map_err(|e| format!("no HTTP client: {e}"))?;
        Ok(Self { http, base, token })
    }

    /// The next check, if there is one.
    ///
    /// # Errors
    /// If the control plane can't be reached or refuses the scanner.
    pub async fn claim(&self, scanner: &str) -> Result<Option<Claim>, String> {
        let response = self.post("claim", None, &serde_json::json!({ "scanner": scanner })).await?;
        match response.status() {
            StatusCode::NO_CONTENT => Ok(None),
            status if status.is_success() => {
                response.json().await.map(Some).map_err(|e| format!("a claim isn't JSON: {e}"))
            }
            status => Err(format!("the control plane answered a claim with {status}")),
        }
    }

    /// Says what the reference resolved to.
    ///
    /// # Errors
    /// If the control plane can't be reached.
    pub async fn resolved(&self, check: &str, claim: &str, resolution: &Resolution) -> Result<Next, String> {
        self.step(&format!("checks/{check}/resolved"), claim, resolution).await
    }

    /// Reports progress, which keeps the claim.
    ///
    /// # Errors
    /// If the control plane can't be reached.
    pub async fn progress(
        &self,
        check: &str,
        claim: &str,
        phase: Phase,
        done: u64,
        total: u64,
    ) -> Result<Next, String> {
        self.step(
            &format!("checks/{check}/progress"),
            claim,
            &serde_json::json!({ "phase": phase, "doneBytes": done, "totalBytes": total }),
        )
        .await
    }

    /// The check's facts.
    ///
    /// # Errors
    /// If the control plane can't be reached or refuses them.
    pub async fn result(&self, check: &str, claim: &str, result: &Result_) -> Result<(), String> {
        self.end(&format!("checks/{check}/result"), claim, result).await
    }

    /// The image is refused.
    ///
    /// # Errors
    /// If the control plane can't be reached.
    pub async fn refused(&self, check: &str, claim: &str, refused: &Refused) -> Result<(), String> {
        self.end(&format!("checks/{check}/refused"), claim, refused).await
    }

    /// The check couldn't be done: the image is not checked.
    ///
    /// # Errors
    /// If the control plane can't be reached.
    pub async fn failed(&self, check: &str, claim: &str, detail: &str) -> Result<(), String> {
        self.end(&format!("checks/{check}/failed"), claim, &serde_json::json!({ "detail": detail })).await
    }

    async fn step(&self, path: &str, claim: &str, body: &impl Serialize) -> Result<Next, String> {
        let response = self.post(path, Some(claim), body).await?;
        if response.status() == StatusCode::CONFLICT || response.status() == StatusCode::NOT_FOUND {
            return Ok(Next::Stop);
        }
        if !response.status().is_success() {
            return Err(format!("the control plane answered {}", response.status()));
        }
        let proceed: Proceed = response.json().await.map_err(|e| format!("an answer isn't JSON: {e}"))?;
        Ok(if proceed.proceed { Next::Proceed } else { Next::Stop })
    }

    async fn end(&self, path: &str, claim: &str, body: &impl Serialize) -> Result<(), String> {
        let response = self.post(path, Some(claim), body).await?;
        // A check whose claim was lost is somebody else's now: what this scanner found is dropped, and that's fine.
        if response.status().is_success() || response.status() == StatusCode::CONFLICT {
            Ok(())
        } else {
            Err(format!("the control plane answered {}", response.status()))
        }
    }

    async fn post(&self, path: &str, claim: Option<&str>, body: &impl Serialize) -> Result<reqwest::Response, String> {
        let url = self.base.join(&format!("internal/v1/scanner/{path}")).map_err(|e| format!("bad path: {e}"))?;
        let mut request = self.http.post(url).bearer_auth(&self.token).json(body);
        if let Some(claim) = claim {
            request = request.header("Scanner-Claim", claim);
        }
        request.send().await.map_err(|e| format!("the control plane couldn't be reached: {e}"))
    }
}
