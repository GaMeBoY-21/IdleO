//! Calls to the API: the person's key on every one, the API's problems turned into exit codes, and a retry for what's
//! safe to repeat. Everything else the CLI does goes through this.

use std::fmt::Write;
use std::time::Duration;

use reqwest::{Method, RequestBuilder, Response, StatusCode, Url};
use rustls_platform_verifier::BuilderVerifierExt;
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::brand::BIN_NAME;
use crate::error::{CliError, Exit, Result};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);
/// Whole-file transfers to and from storage: a 64 MiB part over home internet can take minutes.
const TRANSFER_TIMEOUT: Duration = Duration::from_mins(30);
const RETRIES: u32 = 2;

/// How the CLI reaches the API, signed in or not.
#[derive(Clone)]
pub struct Client {
    http: reqwest::Client,
    transfers: reqwest::Client,
    base: Url,
    key: Option<String>,
}

impl Client {
    /// A client for this API address. `key` is the person's API key, if they have one.
    ///
    /// # Errors
    /// If the operating system's trust store can't be loaded.
    pub fn new(base: Url, key: Option<String>) -> Result<Self> {
        Ok(Self { http: build(Some(REQUEST_TIMEOUT))?, transfers: build(Some(TRANSFER_TIMEOUT))?, base, key })
    }

    /// The same client, signed in with another key (the one a sign-in has just been handed).
    #[must_use]
    pub fn with_key(&self, key: String) -> Self {
        Self { key: Some(key), ..self.clone() }
    }

    #[must_use]
    pub fn base(&self) -> &Url {
        &self.base
    }

    /// The client for storage and long-lived streams: no short whole-request timeout.
    #[must_use]
    pub fn transfers(&self) -> &reqwest::Client {
        &self.transfers
    }

    /// The key, or the error that sends the person to `login`.
    ///
    /// # Errors
    /// If there's no key.
    pub fn require_key(&self) -> Result<&str> {
        self.key.as_deref().ok_or_else(not_signed_in)
    }

    /// A request to the API with the key on it, when there is one.
    ///
    /// # Panics
    /// Never: the paths are the CLI's own.
    pub fn request(&self, method: Method, path: &str) -> RequestBuilder {
        let url = self.base.join(path.trim_start_matches('/')).expect("the CLI's own paths are relative URLs");
        let request = self.http.request(method, url);
        match &self.key {
            Some(key) => request.bearer_auth(key),
            None => request,
        }
    }

    /// A signed-in request to the API.
    ///
    /// # Errors
    /// If there's no key.
    pub fn authed(&self, method: Method, path: &str) -> Result<RequestBuilder> {
        self.require_key()?;
        Ok(self.request(method, path))
    }

    /// Sends it; a problem answer becomes the error the person reads.
    ///
    /// # Errors
    /// On a network failure, or any answer that isn't a success.
    pub async fn send(&self, request: RequestBuilder) -> Result<Response> {
        let response = request.send().await.map_err(|e| network(&self.base, &e))?;
        if response.status().is_success() {
            return Ok(response);
        }
        Err(problem(response).await)
    }

    /// GET, signed in, as JSON; a network failure or a server error is tried again twice.
    ///
    /// # Errors
    /// As [`Client::send`], or if the answer isn't what the API promised.
    pub async fn get<T: DeserializeOwned>(&self, path: &str, query: &[(&str, String)]) -> Result<T> {
        self.get_with(path, query, true).await
    }

    /// GET with no key, for the few calls that need none.
    ///
    /// # Errors
    /// As [`Client::get`].
    pub async fn get_open<T: DeserializeOwned>(&self, path: &str, query: &[(&str, String)]) -> Result<T> {
        self.get_with(path, query, false).await
    }

    async fn get_with<T: DeserializeOwned>(&self, path: &str, query: &[(&str, String)], signed_in: bool) -> Result<T> {
        let mut attempt = 0;
        loop {
            let request = if signed_in { self.authed(Method::GET, path)? } else { self.request(Method::GET, path) };
            match self.send(request.query(query)).await {
                Ok(response) => return json(response).await,
                Err(error) if attempt < RETRIES && worth_retrying(&error) => {
                    tokio::time::sleep(Duration::from_millis(500 * 2_u64.pow(attempt))).await;
                    attempt += 1;
                }
                Err(error) => return Err(error),
            }
        }
    }

    /// POST a JSON body, signed in, and read the JSON answer. `idempotency_key` is for creates that mustn't happen
    /// twice.
    ///
    /// # Errors
    /// As [`Client::send`].
    pub async fn post<B: Serialize + Sync, T: DeserializeOwned>(
        &self,
        path: &str,
        body: &B,
        idempotency_key: Option<&str>,
    ) -> Result<T> {
        let request = with_key(self.authed(Method::POST, path)?.json(body), idempotency_key);
        json(self.send(request).await?).await
    }

    /// POST a JSON body with no key (starting and polling a CLI sign-in).
    ///
    /// # Errors
    /// As [`Client::send`].
    pub async fn post_open<B: Serialize + Sync, T: DeserializeOwned>(&self, path: &str, body: &B) -> Result<T> {
        json(self.send(self.request(Method::POST, path).json(body)).await?).await
    }

    /// DELETE, signed in; the API answers with nothing.
    ///
    /// # Errors
    /// As [`Client::send`].
    pub async fn delete(&self, path: &str) -> Result<()> {
        self.send(self.authed(Method::DELETE, path)?).await.map(drop)
    }
}

fn with_key(request: RequestBuilder, key: Option<&str>) -> RequestBuilder {
    match key {
        Some(key) => request.header("Idempotency-Key", key),
        None => request,
    }
}

/// The error for no key, with what to do about it.
#[must_use]
pub fn not_signed_in() -> CliError {
    CliError::not_signed_in(format!("You're not signed in. Run `{BIN_NAME} login`, or set IDLEO_API_KEY."))
}

fn build(timeout: Option<Duration>) -> Result<reqwest::Client> {
    let tls =
        rustls::ClientConfig::builder_with_provider(std::sync::Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .and_then(BuilderVerifierExt::with_platform_verifier)
            .map_err(|e| CliError::failed(format!("couldn't set up secure connections: {e}")))?
            .with_no_client_auth();
    let mut builder = reqwest::Client::builder()
        .use_preconfigured_tls(tls)
        .connect_timeout(CONNECT_TIMEOUT)
        .user_agent(format!("{BIN_NAME}-cli/{}", env!("CARGO_PKG_VERSION")));
    if let Some(timeout) = timeout {
        builder = builder.timeout(timeout);
    }
    builder.build().map_err(|e| CliError::failed(format!("couldn't build the HTTP client: {e}")))
}

async fn json<T: DeserializeOwned>(response: Response) -> Result<T> {
    response.json().await.map_err(|e| {
        CliError::failed(format!("the answer wasn't what this version of {BIN_NAME} expects ({e}); try updating it"))
    })
}

fn worth_retrying(error: &CliError) -> bool {
    error.exit == Exit::Network || error.problem.as_deref() == Some("server-error")
}

/// What a network failure says, in words: where it couldn't reach, and why.
fn network(base: &Url, error: &reqwest::Error) -> CliError {
    let place = base.host_str().unwrap_or("the API");
    let why = if error.is_timeout() {
        "it took too long to answer"
    } else if error.is_connect() {
        "the connection failed"
    } else {
        "the request failed"
    };
    CliError::network(format!("Couldn't reach {place}: {why}. Check your connection and try again."))
}

/// The API's problem detail (RFC 9457) as the error the person reads, with the exit code its type has.
async fn problem(response: Response) -> CliError {
    let status = response.status();
    let retry_after = response
        .headers()
        .get(reqwest::header::RETRY_AFTER)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<u64>().ok());
    let body: serde_json::Value = response.json().await.unwrap_or(serde_json::Value::Null);
    from_problem(status, &body, retry_after)
}

/// Split out of [`problem`] so it can be tested without a server.
#[must_use]
pub fn from_problem(status: StatusCode, body: &serde_json::Value, retry_after: Option<u64>) -> CliError {
    let slug = body["type"].as_str().and_then(|text| text.strip_prefix("/problems/")).map(str::to_owned);
    if slug.is_none() && status.is_server_error() {
        let mut error = CliError::failed(format!("The service had a problem ({status}). Try again in a moment."));
        error.problem = Some("server-error".to_owned());
        return error;
    }
    let detail = body["detail"].as_str().or_else(|| body["title"].as_str()).unwrap_or("The request was refused.");
    let mut message = detail.to_owned();
    for field in body["errors"].as_array().into_iter().flatten() {
        if let (Some(name), Some(why)) = (field["field"].as_str(), field["detail"].as_str()) {
            let _ = write!(message, "\n  {name}: {why}");
        }
    }
    if let Some(seconds) = retry_after {
        let _ = write!(message, "\nTry again in {seconds} s.");
    }
    let exit = slug.as_deref().map_or(Exit::Failed, Exit::for_problem);
    if exit == Exit::NotSignedIn && slug.as_deref() == Some("unauthenticated") {
        let _ = write!(message, "\nRun `{BIN_NAME} login` to sign in again.");
    }
    let mut error = CliError::new(exit, message);
    error.problem = slug;
    if status.is_server_error() {
        error.problem = Some("server-error".to_owned());
    }
    error
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    #[test]
    fn a_problem_becomes_its_detail_and_its_exit_code() {
        let body = json!({"type": "/problems/insufficient-credit", "title": "Not enough credit", "detail": "Booking holds 150 paise, but only 40 is available."});
        let error = from_problem(StatusCode::CONFLICT, &body, None);
        assert_eq!(error.exit, Exit::NotEnoughCredit);
        assert_eq!(error.message, "Booking holds 150 paise, but only 40 is available.");
        assert_eq!(error.problem.as_deref(), Some("insufficient-credit"));
    }

    #[test]
    fn invalid_fields_are_listed() {
        let body = json!({"type": "/problems/validation-failed", "detail": "The request has invalid fields.",
            "errors": [{"field": "params.epochs", "detail": "must be at most 10"}]});
        let error = from_problem(StatusCode::BAD_REQUEST, &body, None);
        assert_eq!(error.exit, Exit::Usage);
        assert!(error.message.ends_with("\n  params.epochs: must be at most 10"), "{}", error.message);
    }

    #[test]
    fn a_limit_says_how_long_to_wait() {
        let body = json!({"type": "/problems/rate-limited", "detail": "More than 60 requests in a minute."});
        let error = from_problem(StatusCode::TOO_MANY_REQUESTS, &body, Some(17));
        assert_eq!(error.exit, Exit::RateLimited);
        assert!(error.message.ends_with("Try again in 17 s."), "{}", error.message);
    }

    #[test]
    fn an_expired_sign_in_says_to_log_in_again() {
        let body =
            json!({"type": "/problems/unauthenticated", "detail": "The bearer token is invalid or has expired."});
        let error = from_problem(StatusCode::UNAUTHORIZED, &body, None);
        assert_eq!(error.exit, Exit::NotSignedIn);
        assert!(error.message.contains("`idleo login`"), "{}", error.message);
    }

    #[test]
    fn a_server_error_with_no_problem_is_worth_retrying() {
        let error = from_problem(StatusCode::BAD_GATEWAY, &serde_json::Value::Null, None);
        assert_eq!(error.exit, Exit::Failed);
        assert!(worth_retrying(&error));
        let refused = from_problem(StatusCode::NOT_FOUND, &json!({"type": "/problems/not-found"}), None);
        assert!(!worth_retrying(&refused));
    }
}
