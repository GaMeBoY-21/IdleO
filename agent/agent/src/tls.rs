//! TLS 1.3 to the control plane, trusting only our root, with rustls and the `ring` provider.

use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use hyper_util::rt::TokioIo;
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::{ClientConfig, RootCertStore};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_rustls::TlsConnector;
use tonic::transport::{Channel, Endpoint, Uri};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// HTTP/2 pings while idle: every 30 s, within the control plane's 20 s minimum (ADR 0011).
const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(30);
const KEEPALIVE_TIMEOUT: Duration = Duration::from_secs(20);

/// The one crypto provider the agent uses, for TLS and for checking issued certificates.
#[must_use]
pub fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// A client TLS config: TLS 1.3 only, `roots` as the only trust anchors, HTTP/2 by ALPN, and the
/// host's certificate when it has one.
///
/// # Errors
/// If the identity's key doesn't suit the certificate.
pub fn client_config(
    roots: &Arc<RootCertStore>,
    identity: Option<(Vec<CertificateDer<'static>>, PrivateKeyDer<'static>)>,
) -> Result<Arc<ClientConfig>, rustls::Error> {
    let builder = ClientConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_root_certificates(Arc::clone(roots));
    let mut config = match identity {
        Some((chain, key)) => builder.with_client_auth_cert(chain, key)?,
        None => builder.with_no_client_auth(),
    };
    config.alpn_protocols = vec![b"h2".to_vec()];
    Ok(Arc::new(config))
}

/// Any byte stream TLS can run over.
pub trait Io: AsyncRead + AsyncWrite + Send + Unpin + 'static {}
impl<T: AsyncRead + AsyncWrite + Send + Unpin + 'static> Io for T {}

type DialFuture = Pin<Box<dyn Future<Output = io::Result<Box<dyn Io>>> + Send>>;

/// Opens the byte stream under TLS: TCP in production; tests may use an in-memory pipe.
#[derive(Clone)]
pub struct Dialer(Arc<dyn Fn() -> DialFuture + Send + Sync>);

impl Dialer {
    /// Dials TCP, with a 10-second timeout.
    #[must_use]
    pub fn tcp(host: String, port: u16) -> Self {
        Self::custom(move || {
            let host = host.clone();
            async move {
                let stream = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect((host.as_str(), port)))
                    .await
                    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TCP connect timed out"))??;
                stream.set_nodelay(true)?;
                Ok(Box::new(stream) as Box<dyn Io>)
            }
        })
    }

    /// Dials with any function returning a byte stream.
    pub fn custom<F, Fut>(dial: F) -> Self
    where
        F: Fn() -> Fut + Send + Sync + 'static,
        Fut: Future<Output = io::Result<Box<dyn Io>>> + Send + 'static,
    {
        Self(Arc::new(move || Box::pin(dial())))
    }

    async fn dial(&self) -> io::Result<Box<dyn Io>> {
        (self.0)().await
    }
}

/// Where and how to reach the control plane.
#[derive(Clone)]
pub struct Target {
    /// Opens the byte stream.
    pub dialer: Dialer,
    /// The name the control plane's certificate must carry.
    pub server_name: ServerName<'static>,
    /// `host:port`, for the HTTP/2 `:authority`.
    pub authority: String,
}

impl Target {
    /// Dials `host:port` over TCP and expects `host` in the server certificate.
    ///
    /// # Errors
    /// If `host` isn't a valid DNS name or IP address.
    pub fn tcp(host: &str, port: u16) -> Result<Self, String> {
        let server_name =
            ServerName::try_from(host.to_owned()).map_err(|_| format!("{host} isn't a valid host name"))?;
        Ok(Self { dialer: Dialer::tcp(host.to_owned(), port), server_name, authority: format!("{host}:{port}") })
    }

    /// Connects a gRPC channel over TLS 1.3 with `config`.
    ///
    /// # Errors
    /// If dialing, the TLS handshake (including the server certificate check) or HTTP/2 setup
    /// fails.
    pub async fn connect(&self, config: Arc<ClientConfig>) -> Result<Channel, tonic::transport::Error> {
        // TLS is ours, under a plain-HTTP endpoint, so tonic never adds roots or protocol versions
        // of its own.
        let endpoint = Endpoint::from_shared(format!("http://{}", self.authority))?
            .http2_keep_alive_interval(KEEPALIVE_INTERVAL)
            .keep_alive_timeout(KEEPALIVE_TIMEOUT)
            .keep_alive_while_idle(true);
        let connector = TlsConnector::from(config);
        let dialer = self.dialer.clone();
        let server_name = self.server_name.clone();
        endpoint
            .connect_with_connector(tower::service_fn(move |_: Uri| {
                let connector = connector.clone();
                let dialer = dialer.clone();
                let server_name = server_name.clone();
                async move {
                    let stream = dialer.dial().await?;
                    let tls = tokio::time::timeout(CONNECT_TIMEOUT, connector.connect(server_name, stream))
                        .await
                        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "TLS handshake timed out"))??;
                    Ok::<_, io::Error>(TokioIo::new(tls))
                }
            }))
            .await
    }
}

/// An error and its causes on one line, for logs.
#[must_use]
pub fn describe(error: &(dyn std::error::Error + 'static)) -> String {
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        let cause_text = cause.to_string();
        if !text.contains(&cause_text) {
            text.push_str(": ");
            text.push_str(&cause_text);
        }
        source = cause.source();
    }
    text
}
