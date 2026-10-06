//! `idleo-agent run`: keeps one mutual-TLS stream open to the control plane, sends heartbeats,
//! reconnects with capped backoff, and renews the certificate before it expires. Sessions run in the
//! [`supervisor`](crate::supervisor), which outlives any one stream: this loop hands it commands and
//! carries its statuses.

use std::collections::HashMap;
use std::fmt;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use agent_proto::v1::agent_gateway_service_client::AgentGatewayServiceClient;
use agent_proto::v1::open_stream_request::Body as AgentBody;
use agent_proto::v1::open_stream_response::Body as ServerBody;
use agent_proto::v1::{
    GpuTelemetry, Heartbeat, Hello, Offer, OfferDeclineReason, OfferReply, OpenStreamRequest, OpenStreamResponse,
    RenewCertificateRequest, Welcome,
};
use rustls::RootCertStore;
use time::OffsetDateTime;
use tokio::sync::{mpsc, watch};
use tokio::task::JoinHandle;
use tokio::time::{Instant, MissedTickBehavior};
use tokio_stream::wrappers::ReceiverStream;
use tonic::transport::Channel;
use tonic::{Code, Status, Streaming};
use uuid::Uuid;

use crate::backoff::{Backoff, full_jitter};
use crate::lease_clock::LeaseClock;
use crate::offers::{self, OfferFacts};
use crate::owner::OwnerActivity;
use crate::pki::{self, NewKey};
use crate::runner::SessionRunner;
use crate::state::{self, StateError};
use crate::supervisor::{self, Shared};
use crate::telemetry::{GpuError, GpuInfo, GpuSource, Readings};
use crate::tls::{self, Target};
use crate::{AGENT_VERSION, new_message_id, system, version};

const RENEW_TIMEOUT: Duration = Duration::from_secs(30);
/// The least time an offer must have left for the agent to accept it.
const REPLY_MARGIN: Duration = Duration::from_millis(10);

/// Every interval and limit the connection manager uses. [`Timing::default`] is production.
#[derive(Debug, Clone)]
pub struct Timing {
    /// The first reconnect ceiling.
    pub backoff_base: Duration,
    /// The highest reconnect ceiling.
    pub backoff_cap: Duration,
    /// How long a connection must stay up after `Welcome` before the backoff resets.
    pub healthy_after: Duration,
    /// The delay after the control plane refuses the certificate or the host, or the
    /// certificate has expired: no faster than this…
    pub slow_retry: Duration,
    /// …plus up to this much jitter.
    pub slow_retry_jitter: Duration,
    /// How long to wait for `Welcome` after sending `Hello`.
    pub welcome_timeout: Duration,
    /// How often to check whether the certificate needs renewing.
    pub renewal_check_every: Duration,
    /// Renew when the certificate has this long or less left.
    pub renew_within: Duration,
    /// How often the GPUs are read.
    pub sample_every: Duration,
    /// How long to wait for the control plane to close the stream on shutdown.
    pub shutdown_grace: Duration,
    /// An `OfferReply` without an `Ack` is resent after this long.
    pub reply_resend_after: Duration,
    /// A `SessionStatus` without an `Ack` is resent after this long.
    pub status_resend_after: Duration,
    /// How often lease deadlines are checked, stream or no stream.
    pub deadline_check_every: Duration,
}

impl Default for Timing {
    fn default() -> Self {
        Self {
            backoff_base: Duration::from_secs(1),
            backoff_cap: Duration::from_secs(60),
            healthy_after: Duration::from_secs(60),
            slow_retry: Duration::from_secs(600),
            slow_retry_jitter: Duration::from_secs(60),
            welcome_timeout: Duration::from_secs(10),
            renewal_check_every: Duration::from_hours(6),
            renew_within: Duration::from_hours(10 * 24),
            sample_every: Duration::from_secs(1),
            shutdown_grace: Duration::from_secs(3),
            reply_resend_after: Duration::from_secs(3),
            status_resend_after: Duration::from_secs(5),
            deadline_check_every: Duration::from_millis(250),
        }
    }
}

/// What `run` needs.
pub struct RunOptions {
    /// Where the control plane is.
    pub target: Target,
    /// The only trust anchors.
    pub roots: Arc<RootCertStore>,
    /// Where the identity lives.
    pub data_dir: PathBuf,
    /// GPU facts and readings.
    pub gpus: Arc<dyn GpuSource>,
    /// Whether the owner is at the machine.
    pub owner: Arc<dyn OwnerActivity>,
    /// The clock leases and offer expiries count on: one that keeps counting during sleep.
    pub lease_clock: Arc<dyn LeaseClock>,
    /// What runs sessions; `None` for an agent that can't run any yet, which declines offers.
    pub runner: Option<Arc<dyn SessionRunner>>,
    /// Intervals and limits.
    pub timing: Timing,
    /// Receives an [`Event`] at each step, for tests and diagnostics.
    pub events: Option<mpsc::UnboundedSender<Event>>,
}

/// A step of the connection manager.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// A connection attempt began.
    Connecting,
    /// `Welcome` arrived.
    Welcomed {
        /// The heartbeat interval it set.
        heartbeat_interval: Duration,
    },
    /// The agent will retry after `delay`.
    Retrying {
        /// How long it waits.
        delay: Duration,
        /// The backoff ceiling the delay was drawn under; `None` for the slow retry.
        ceiling: Option<Duration>,
        /// Why the last attempt ended.
        reason: EndReason,
    },
    /// A renewed certificate was saved; the agent reconnects with it.
    Renewed {
        /// When the new certificate expires.
        not_after: OffsetDateTime,
    },
    /// The control plane wouldn't renew yet (`FAILED_PRECONDITION`).
    RenewalDeclined,
    /// The agent answered an offer.
    OfferAnswered {
        /// The offer.
        offer_id: String,
        /// The decline reason, or `None` when accepted.
        declined: Option<OfferDeclineReason>,
    },
}

/// Why a connection attempt ended.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EndReason {
    /// A network or TLS failure, `UNAVAILABLE`, or any other error: normal backoff.
    Failed(String),
    /// `Welcome` didn't arrive in time, or broke the protocol: normal backoff.
    BadWelcome(String),
    /// `ABORTED`: a newer connection for this host replaced ours. Normal backoff.
    Replaced,
    /// `UNAUTHENTICATED` or `PERMISSION_DENIED`: certificate revoked or host suspended. Slow retry.
    Refused(String),
    /// The certificate has expired. Slow retry.
    Expired,
}

impl EndReason {
    fn is_slow(&self) -> bool {
        matches!(self, Self::Refused(_) | Self::Expired)
    }
}

impl fmt::Display for EndReason {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failed(e) => f.write_str(e),
            Self::BadWelcome(e) => write!(f, "bad Welcome: {e}"),
            Self::Replaced => f.write_str("replaced by a newer connection for this host"),
            Self::Refused(e) => write!(f, "refused by the control plane: {e}"),
            Self::Expired => f.write_str("the host certificate has expired"),
        }
    }
}

/// Why `run` stopped without being asked to.
#[derive(Debug)]
pub enum RunError {
    /// The identity is missing, unreadable or too open.
    State(StateError),
    /// The GPUs can't be read at start.
    Gpu(GpuError),
    /// Sessions can't be set up, for example storage TLS.
    Sessions(String),
}

impl fmt::Display for RunError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::State(e) => e.fmt(f),
            Self::Gpu(e) => e.fmt(f),
            Self::Sessions(e) => f.write_str(e),
        }
    }
}

impl std::error::Error for RunError {}

impl RunError {
    /// The process exit code for this failure (documented in the README).
    #[must_use]
    pub fn exit_code(&self) -> u8 {
        match self {
            Self::State(StateError::NotEnrolled(_)) => 8,
            Self::State(_) => 6,
            Self::Gpu(_) => 7,
            Self::Sessions(_) => 1,
        }
    }
}

enum Outcome {
    Shutdown,
    Renewed,
    Ended { reason: EndReason, healthy_for: Option<Duration> },
}

/// Runs until `shutdown` becomes true. Every attempt reloads the identity, so a re-enrolment
/// while running is picked up at the next reconnect.
///
/// # Errors
/// If the identity is missing or unsafe, or the GPUs can't be read at start.
pub async fn run(options: RunOptions, mut shutdown: watch::Receiver<bool>) -> Result<(), RunError> {
    state::load(&options.data_dir).map_err(RunError::State)?;
    let gpus = read_gpus(&options.gpus).await.map_err(RunError::Gpu)?;
    tracing::info!(gpus = gpus.len(), version = AGENT_VERSION, "agent starting");

    let readings = Arc::new(Readings::default());
    let utilisation: supervisor::Utilisation = {
        let readings = Arc::clone(&readings);
        Arc::new(move |gpus: &[String]| readings.take_mean_utilisation(gpus))
    };
    let (sessions, reports) = supervisor::new(
        Arc::clone(&options.lease_clock),
        utilisation,
        options.runner.clone(),
        gpus.iter().map(|gpu| gpu.uuid.clone()),
    )
    .map_err(RunError::Sessions)?;
    let supervising = AbortOnDrop(tokio::spawn(supervisor::supervise(
        sessions.clone(),
        reports,
        options.timing.deadline_check_every,
    )));

    let sampler = AbortOnDrop(tokio::spawn(sample(
        Arc::clone(&options.gpus),
        Arc::clone(&readings),
        options.timing.sample_every,
    )));
    let mut manager = Manager {
        backoff: Backoff::new(options.timing.backoff_base, options.timing.backoff_cap),
        next_renewal_check: Instant::now(),
        readings,
        sessions,
        options,
    };
    let result = manager.run(&mut shutdown).await;
    drop(sampler);
    drop(supervising);
    result
}

struct Manager {
    options: RunOptions,
    backoff: Backoff,
    next_renewal_check: Instant,
    readings: Arc<Readings>,
    sessions: Shared,
}

impl Manager {
    fn emit(&self, event: Event) {
        if let Some(events) = &self.options.events {
            let _ = events.send(event);
        }
    }

    async fn run(&mut self, shutdown: &mut watch::Receiver<bool>) -> Result<(), RunError> {
        loop {
            if *shutdown.borrow_and_update() {
                return Ok(());
            }
            let outcome = self.attempt(shutdown).await?;
            let (reason, healthy_for) = match outcome {
                Outcome::Shutdown => return Ok(()),
                Outcome::Renewed => continue,
                Outcome::Ended { reason, healthy_for } => (reason, healthy_for),
            };
            if healthy_for.is_some_and(|up| up >= self.options.timing.healthy_after) {
                self.backoff.reset();
            }
            let (delay, ceiling) = if reason.is_slow() {
                (self.options.timing.slow_retry + full_jitter(self.options.timing.slow_retry_jitter), None)
            } else {
                let ceiling = self.backoff.next_ceiling();
                (full_jitter(ceiling), Some(ceiling))
            };
            log_end(&reason, delay);
            self.emit(Event::Retrying { delay, ceiling, reason });
            tokio::select! {
                () = tokio::time::sleep(delay) => {}
                () = stopped(shutdown) => return Ok(()),
            }
        }
    }

    async fn attempt(&mut self, shutdown: &mut watch::Receiver<bool>) -> Result<Outcome, RunError> {
        self.emit(Event::Connecting);
        let identity = state::load(&self.options.data_dir).map_err(RunError::State)?;
        if identity.not_after <= OffsetDateTime::now_utc() {
            tracing::error!(
                host_id = %identity.host_id,
                expired = %state::format_time(identity.not_after),
                "the host certificate has expired, so the control plane will refuse it; get a new enrolment code from \
                 the web app and run `idleo-agent enrol --code <code> --force`"
            );
            return Ok(Outcome::Ended { reason: EndReason::Expired, healthy_for: None });
        }
        let host_id = identity.host_id;
        let not_after = identity.not_after;
        let config = match tls::client_config(&self.options.roots, Some((identity.chain, identity.key))) {
            Ok(config) => config,
            Err(e) => return Ok(failed(format!("the host key doesn't suit its certificate: {e}"))),
        };
        let channel = match self.options.target.connect(config).await {
            Ok(channel) => channel,
            Err(e) => return Ok(failed(tls::describe(&e))),
        };
        Ok(self.session(channel, host_id, not_after, shutdown).await)
    }

    #[allow(clippy::too_many_lines)] // One select loop: splitting it would scatter the stream's state.
    async fn session(
        &mut self,
        channel: Channel,
        host_id: Uuid,
        not_after: OffsetDateTime,
        shutdown: &mut watch::Receiver<bool>,
    ) -> Outcome {
        let gpus = match read_gpus(&self.options.gpus).await {
            Ok(gpus) => gpus,
            Err(e) => return failed(format!("couldn't read the GPUs: {e}")),
        };
        let largest_gpu_vram_mb = gpus.iter().map(|gpu| gpu.vram_mb).max().unwrap_or(0);
        let (outbound, requests) = mpsc::channel(16);
        let hello = hello(gpus, self.sessions.lock().running());
        tracing::debug!(?hello, "sending Hello");
        // Hello goes in before the call starts: the control plane answers nothing until it has it.
        if outbound.send(envelope(AgentBody::Hello(hello))).await.is_err() {
            return failed("the stream closed before Hello".to_owned());
        }

        let deadline = Instant::now() + self.options.timing.welcome_timeout;
        let mut client = AgentGatewayServiceClient::new(channel.clone());
        let welcome = tokio::time::timeout_at(deadline, async {
            let mut inbound = client.open_stream(ReceiverStream::new(requests)).await?.into_inner();
            let first = inbound.message().await?;
            Ok::<_, Status>((inbound, first))
        });
        let (mut inbound, policy) = match welcome.await {
            Err(_) => return bad_welcome(format!("no Welcome within {:?}", self.options.timing.welcome_timeout)),
            Ok(Err(status)) => return ended_by(&status, None),
            Ok(Ok((_, None))) => return failed("the stream closed before Welcome".to_owned()),
            Ok(Ok((inbound, Some(message)))) => match check_welcome(message) {
                Ok(policy) => (inbound, policy),
                Err(e) => return bad_welcome(e),
            },
        };
        let interval = policy.heartbeat_interval;
        let welcomed_at = Instant::now();
        tracing::info!(%host_id, heartbeat_interval = ?interval, "connected");
        self.emit(Event::Welcomed { heartbeat_interval: interval });

        let mut heartbeats = tokio::time::interval_at(welcomed_at + interval, interval);
        heartbeats.set_missed_tick_behavior(MissedTickBehavior::Delay);
        let mut renewal: Option<JoinHandle<Renewal>> = None;
        let healthy_for = || Some(welcomed_at.elapsed());
        // Offer replies kept until acked, by message id, with when they were last sent (ADR 0015).
        let mut unacked: HashMap<String, (OpenStreamRequest, Instant)> = HashMap::new();
        let mut resends = tokio::time::interval(Duration::from_secs(1));
        resends.set_missed_tick_behavior(MissedTickBehavior::Delay);
        // Every status not yet acked goes out again on this new stream (ADR 0016).
        self.sessions.lock().unsend_all();
        let due = self.sessions.lock().due(Instant::now(), self.options.timing.status_resend_after);
        for status in due {
            let _ = outbound.send(status).await;
        }

        loop {
            tokio::select! {
                () = stopped(shutdown) => {
                    close(outbound, inbound, self.options.timing.shutdown_grace).await;
                    tracing::info!("stream closed for shutdown");
                    return Outcome::Shutdown;
                }
                _ = heartbeats.tick() => {
                    let heartbeat = Heartbeat {
                        gpus: self.readings.summarise().into_iter().map(|r| GpuTelemetry {
                            uuid: r.uuid,
                            temp_c: r.temp_c,
                            util_pct: r.util_pct,
                            vram_used_mb: r.vram_used_mb,
                            power_w: r.power_w,
                        }).collect(),
                        // Measured by the onboarding benchmark, which doesn't exist yet.
                        net_up_kbps: 0,
                        owner_active: self.options.owner.is_active(),
                        sessions: self.sessions.lock().running(),
                    };
                    // A send fails only once the stream has ended; the next read says why.
                    let _ = outbound.send(envelope(AgentBody::Heartbeat(heartbeat))).await;
                }
                () = self.sessions.changed() => {
                    let due = self.sessions.lock().due(Instant::now(), self.options.timing.status_resend_after);
                    for status in due {
                        let _ = outbound.send(status).await;
                    }
                }
                _ = resends.tick() => {
                    let now = Instant::now();
                    let due = self.sessions.lock().due(now, self.options.timing.status_resend_after);
                    for status in due {
                        let _ = outbound.send(status).await;
                    }
                    for (request, sent_at) in unacked.values_mut() {
                        if now.duration_since(*sent_at) >= self.options.timing.reply_resend_after {
                            tracing::debug!(message_id = %request.message_id, "resending an unacked offer reply");
                            *sent_at = now;
                            let _ = outbound.send(request.clone()).await;
                        }
                    }
                }
                message = inbound.message() => match message {
                    Ok(Some(OpenStreamResponse { body: Some(ServerBody::Offer(offer)), .. })) => {
                        let received_at = self.options.lease_clock.now();
                        let (running_sessions, can_run) = {
                            let mut sessions = self.sessions.lock();
                            (i32::try_from(sessions.running().len()).unwrap_or(i32::MAX), sessions.can_run())
                        };
                        let facts = |vram_needed_mb, expired| OfferFacts {
                            expired,
                            shutting_down: *shutdown.borrow(),
                            can_run,
                            running_sessions,
                            max_concurrent_sessions: policy.max_concurrent_sessions,
                            owner_active: self.options.owner.is_active(),
                            hottest_gpu_c: self.readings.hottest_latest_c(),
                            gpu_temp_cap_c: policy.gpu_temp_cap_c,
                            largest_gpu_vram_mb,
                            vram_needed_mb,
                        };
                        if let Some(reply) = self.answer(offer, received_at, facts) {
                            unacked.insert(reply.message_id.clone(), (reply.clone(), Instant::now()));
                            let _ = outbound.send(reply).await;
                        }
                    }
                    Ok(Some(OpenStreamResponse { message_id, body: Some(ServerBody::StartSession(start)) })) => {
                        let received_at = self.options.lease_clock.now();
                        let ack = self.sessions.lock().start(&message_id, start, received_at);
                        if let Some(ack) = ack {
                            let _ = outbound.send(ack).await;
                        }
                    }
                    Ok(Some(OpenStreamResponse { message_id, body: Some(ServerBody::RefreshCredentials(refresh)) })) => {
                        let received_at = self.options.lease_clock.now();
                        let ack = self.sessions.lock().refresh(&message_id, &refresh, received_at);
                        if let Some(ack) = ack {
                            let _ = outbound.send(ack).await;
                        }
                    }
                    Ok(Some(OpenStreamResponse { body: Some(ServerBody::LeaseRenew(renew)), .. })) => {
                        let received_at = self.options.lease_clock.now();
                        self.sessions.lock().renew(&renew, received_at);
                    }
                    Ok(Some(OpenStreamResponse { message_id, body: Some(ServerBody::StopSession(stop)) })) => {
                        let ack = self.sessions.lock().stop(&message_id, &stop);
                        if let Some(ack) = ack {
                            let _ = outbound.send(ack).await;
                        }
                    }
                    Ok(Some(OpenStreamResponse { body: Some(ServerBody::Ack(ack)), .. })) => {
                        if unacked.remove(&ack.acked_message_id).is_none() {
                            self.sessions.lock().acked(&ack.acked_message_id);
                        }
                    }
                    Ok(Some(message)) => log_unhandled(&message),
                    Ok(None) => return Outcome::Ended {
                        reason: EndReason::Failed("the control plane closed the stream".to_owned()),
                        healthy_for: healthy_for(),
                    },
                    Err(status) => return ended_by(&status, healthy_for()),
                },
                () = tokio::time::sleep_until(self.next_renewal_check), if renewal.is_none() => {
                    self.next_renewal_check = Instant::now() + self.options.timing.renewal_check_every;
                    let left = not_after - OffsetDateTime::now_utc();
                    if left <= self.options.timing.renew_within {
                        tracing::info!(not_after = %state::format_time(not_after), "renewing the host certificate");
                        renewal = Some(tokio::spawn(renew(
                            channel.clone(),
                            Arc::clone(&self.options.roots),
                            self.options.data_dir.clone(),
                            host_id,
                        )));
                    } else {
                        tracing::debug!(not_after = %state::format_time(not_after), "certificate renewal not due");
                    }
                }
                result = async { renewal.as_mut().expect("guarded").await }, if renewal.is_some() => {
                    renewal = None;
                    match result.unwrap_or_else(|e| Renewal::Failed(format!("renewal task failed: {e}"))) {
                        Renewal::Saved(not_after) => {
                            tracing::info!(not_after = %state::format_time(not_after), "certificate renewed; reconnecting with it");
                            self.emit(Event::Renewed { not_after });
                            close(outbound, inbound, self.options.timing.shutdown_grace).await;
                            return Outcome::Renewed;
                        }
                        Renewal::Declined(message) => {
                            tracing::info!(%message, "the control plane won't renew yet; checking again later");
                            self.emit(Event::RenewalDeclined);
                        }
                        Renewal::Failed(e) => tracing::warn!(error = %e, "certificate renewal failed; checking again later"),
                    }
                }
            }
        }
    }
}

impl Manager {
    /// Decides on an offer within its `expires_in`, counted from receipt; the reply to send, or `None`
    /// for an invalid offer, which is ignored (it expires at the control plane).
    fn answer(
        &self,
        offer: Offer,
        received_at: Duration,
        facts: impl Fn(i32, bool) -> OfferFacts,
    ) -> Option<OpenStreamRequest> {
        let expires_in =
            offer.expires_in.and_then(|value| Duration::try_from(value).ok()).filter(|value| !value.is_zero());
        let (Some(spec), Some(expires_in)) = (offer.spec.as_ref(), expires_in) else {
            tracing::warn!(offer_id = %offer.offer_id, "ignored an offer without a spec or an expiry");
            return None;
        };
        if offer.offer_id.is_empty() {
            tracing::warn!("ignored an offer without an id");
            return None;
        }
        // A reply needs time to arrive: one sent with less than this left counts as too late.
        let expired = self.options.lease_clock.now() + REPLY_MARGIN >= received_at + expires_in;
        let decision = offers::decide(&facts(spec.vram_needed_mb, expired));
        let declined = decision.err();
        match declined {
            None => tracing::info!(offer_id = %offer.offer_id, image = %spec.image, "accepted an offer"),
            Some(reason) => tracing::info!(offer_id = %offer.offer_id, ?reason, "declined an offer"),
        }
        self.emit(Event::OfferAnswered { offer_id: offer.offer_id.clone(), declined });
        Some(envelope(AgentBody::OfferReply(OfferReply {
            offer_id: offer.offer_id,
            accepted: declined.is_none(),
            decline_reason: declined.unwrap_or(OfferDeclineReason::Unspecified).into(),
        })))
    }
}

/// Resolves once shutdown is requested (or whoever could request it has gone).
async fn stopped(shutdown: &mut watch::Receiver<bool>) {
    let _ = shutdown.wait_for(|stop| *stop).await;
}

fn failed(reason: String) -> Outcome {
    Outcome::Ended { reason: EndReason::Failed(reason), healthy_for: None }
}

fn bad_welcome(reason: String) -> Outcome {
    Outcome::Ended { reason: EndReason::BadWelcome(reason), healthy_for: None }
}

fn ended_by(status: &Status, healthy_for: Option<Duration>) -> Outcome {
    let reason = match status.code() {
        Code::Aborted => EndReason::Replaced,
        Code::Unauthenticated | Code::PermissionDenied => {
            EndReason::Refused(format!("{:?}: {}", status.code(), status.message()))
        }
        _ => EndReason::Failed(tls::describe(status)),
    };
    Outcome::Ended { reason, healthy_for }
}

fn log_end(reason: &EndReason, delay: Duration) {
    match reason {
        EndReason::Replaced => tracing::warn!(
            retry_in = ?delay,
            "the control plane replaced this connection with a newer one for the same host; if this repeats, another \
             machine may be using this host's certificate"
        ),
        EndReason::Refused(e) => tracing::error!(
            error = %e,
            retry_in = ?delay,
            "the control plane refused this host: its certificate was revoked or the host is suspended; re-enrol \
             with a new code from the web app, or contact support. Retrying slowly in case the host is reinstated"
        ),
        EndReason::Expired => tracing::warn!(retry_in = ?delay, "waiting for a new enrolment"),
        EndReason::Failed(_) | EndReason::BadWelcome(_) => {
            tracing::warn!(reason = %reason, retry_in = ?delay, "disconnected; reconnecting");
        }
    }
}

fn envelope(body: AgentBody) -> OpenStreamRequest {
    OpenStreamRequest { message_id: new_message_id(), body: Some(body) }
}

fn hello(gpus: Vec<GpuInfo>, running_sessions: Vec<agent_proto::v1::RunningSession>) -> Hello {
    Hello {
        agent_version: AGENT_VERSION.to_owned(),
        os: system::os().into(),
        os_version: system::os_version(),
        gpus: gpus
            .into_iter()
            .map(|gpu| agent_proto::v1::GpuInfo {
                uuid: gpu.uuid,
                name: gpu.name,
                vram_mb: gpu.vram_mb,
                driver_version: gpu.driver_version,
                cuda_driver_version: gpu.cuda_driver_version,
                compute_capability: gpu.compute_capability,
            })
            .collect(),
        running_sessions,
    }
}

/// The rules `Welcome` sets for this connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Policy {
    heartbeat_interval: Duration,
    gpu_temp_cap_c: i32,
    max_concurrent_sessions: i32,
}

/// Checks `Welcome` against ADR 0007 and returns its policy.
fn check_welcome(message: OpenStreamResponse) -> Result<Policy, String> {
    if message.message_id.is_empty() {
        return Err("the message has no message_id".to_owned());
    }
    let Some(ServerBody::Welcome(Welcome { policy: Some(policy), minimum_agent_version })) = message.body else {
        return Err("the first message isn't a Welcome with a policy".to_owned());
    };
    let interval = policy
        .heartbeat_interval
        .and_then(|interval| Duration::try_from(interval).ok())
        .filter(|interval| !interval.is_zero())
        .ok_or("the heartbeat interval is missing or zero")?;
    if policy.gpu_temp_cap_c <= 0 || policy.max_concurrent_sessions <= 0 {
        return Err("the temperature cap or session limit is zero".to_owned());
    }
    if minimum_agent_version.is_empty() {
        return Err("minimum_agent_version is empty".to_owned());
    }
    match version::is_newer(&minimum_agent_version, AGENT_VERSION) {
        Some(true) => tracing::error!(
            minimum = %minimum_agent_version,
            ours = AGENT_VERSION,
            "THIS AGENT IS OLDER THAN THE CONTROL PLANE SUPPORTS: it will get no work until it is updated; install the \
             latest agent"
        ),
        Some(false) => {}
        None => tracing::warn!(minimum = %minimum_agent_version, "minimum_agent_version isn't a semantic version"),
    }
    Ok(Policy {
        heartbeat_interval: interval,
        gpu_temp_cap_c: policy.gpu_temp_cap_c,
        max_concurrent_sessions: policy.max_concurrent_sessions,
    })
}

fn log_unhandled(message: &OpenStreamResponse) {
    let kind = match &message.body {
        Some(ServerBody::Welcome(_)) => "Welcome",
        Some(ServerBody::Offer(_)) => "Offer",
        Some(ServerBody::StartSession(_)) => "StartSession",
        Some(ServerBody::LeaseRenew(_)) => "LeaseRenew",
        Some(ServerBody::StopSession(_)) => "StopSession",
        Some(ServerBody::UpdateAgent(_)) => "UpdateAgent",
        Some(ServerBody::Ack(_)) => "Ack",
        Some(ServerBody::RefreshCredentials(_)) => "RefreshCredentials",
        None => "empty",
    };
    tracing::debug!(kind, message_id = %message.message_id, "message not handled by this agent version; ignored");
}

/// Half-closes our side, then waits briefly for the control plane to finish the stream.
async fn close(outbound: mpsc::Sender<OpenStreamRequest>, mut inbound: Streaming<OpenStreamResponse>, grace: Duration) {
    drop(outbound);
    let _ = tokio::time::timeout(grace, async { while let Ok(Some(_)) = inbound.message().await {} }).await;
}

enum Renewal {
    Saved(OffsetDateTime),
    Declined(String),
    Failed(String),
}

/// Renews over the current connection with a new key pair; saves only a certificate that passes
/// every check.
async fn renew(channel: Channel, roots: Arc<RootCertStore>, data_dir: PathBuf, host_id: Uuid) -> Renewal {
    let key = match NewKey::generate() {
        Ok(key) => key,
        Err(e) => return Renewal::Failed(format!("couldn't generate a key: {e}")),
    };
    let csr_der = match key.csr_der() {
        Ok(csr) => csr,
        Err(e) => return Renewal::Failed(format!("couldn't build the CSR: {e}")),
    };
    let mut client = AgentGatewayServiceClient::new(channel);
    let call = client.renew_certificate(RenewCertificateRequest { csr_der });
    let response = match tokio::time::timeout(RENEW_TIMEOUT, call).await {
        Err(_) => return Renewal::Failed("no answer within 30 seconds".to_owned()),
        Ok(Err(status)) if status.code() == Code::FailedPrecondition => {
            return Renewal::Declined(status.message().to_owned());
        }
        Ok(Err(status)) => return Renewal::Failed(tls::describe(&status)),
        Ok(Ok(response)) => response.into_inner(),
    };
    let Some(certificate) = response.certificate else {
        return Renewal::Failed("the reply holds no certificate".to_owned());
    };
    let issued = match pki::verify_issued(&certificate.certificate_chain_der, &roots, key.public_key_raw()) {
        Ok(issued) if issued.host_id == host_id => issued,
        Ok(issued) => return Renewal::Failed(format!("the new certificate is for another host ({})", issued.host_id)),
        Err(e) => return Renewal::Failed(format!("the new certificate failed a check, so nothing was saved: {e}")),
    };
    let key_pem = key.private_key_pem();
    let not_after = issued.not_after;
    match tokio::task::spawn_blocking(move || state::save(&data_dir, &key_pem, &issued, false)).await {
        Ok(Ok(_)) => Renewal::Saved(not_after),
        Ok(Err(e)) => Renewal::Failed(format!("couldn't save the new certificate: {e}")),
        Err(e) => Renewal::Failed(format!("saving the new certificate failed: {e}")),
    }
}

async fn read_gpus(source: &Arc<dyn GpuSource>) -> Result<Vec<GpuInfo>, GpuError> {
    let source = Arc::clone(source);
    tokio::task::spawn_blocking(move || source.gpus()).await.map_err(|e| GpuError(format!("GPU read failed: {e}")))?
}

/// Reads the GPUs every `every`, for the heartbeat summaries.
async fn sample(source: Arc<dyn GpuSource>, readings: Arc<Readings>, every: Duration) {
    let mut ticks = tokio::time::interval(every);
    ticks.set_missed_tick_behavior(MissedTickBehavior::Delay);
    let mut failing = false;
    loop {
        ticks.tick().await;
        let source = Arc::clone(&source);
        match tokio::task::spawn_blocking(move || source.read()).await {
            Ok(Ok(round)) => {
                failing = false;
                readings.record(&round);
            }
            Ok(Err(e)) if !failing => {
                failing = true;
                tracing::warn!(error = %e, "couldn't read the GPUs; heartbeats repeat the last readings");
            }
            Ok(Err(_)) => {}
            Err(e) => tracing::warn!(error = %e, "GPU read task failed"),
        }
    }
}

struct AbortOnDrop(JoinHandle<()>);

impl Drop for AbortOnDrop {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use agent_proto::v1::AgentPolicy;

    fn welcome(interval: Option<prost_types::Duration>, minimum: &str) -> OpenStreamResponse {
        OpenStreamResponse {
            message_id: new_message_id(),
            body: Some(ServerBody::Welcome(Welcome {
                policy: Some(AgentPolicy {
                    heartbeat_interval: interval,
                    gpu_temp_cap_c: 85,
                    max_concurrent_sessions: 1,
                }),
                minimum_agent_version: minimum.to_owned(),
            })),
        }
    }

    #[test]
    fn a_valid_welcome_gives_the_interval() {
        let five = prost_types::Duration { seconds: 5, nanos: 0 };
        assert_eq!(
            check_welcome(welcome(Some(five), "0.1.0")),
            Ok(Policy { heartbeat_interval: Duration::from_secs(5), gpu_temp_cap_c: 85, max_concurrent_sessions: 1 })
        );
    }

    #[test]
    fn a_zero_or_missing_interval_is_invalid() {
        assert!(check_welcome(welcome(None, "0.1.0")).is_err());
        assert!(check_welcome(welcome(Some(prost_types::Duration::default()), "0.1.0")).is_err());
        assert!(check_welcome(welcome(Some(prost_types::Duration { seconds: -1, nanos: 0 }), "0.1.0")).is_err());
    }

    #[test]
    fn a_welcome_without_an_id_or_minimum_version_is_invalid() {
        let five = prost_types::Duration { seconds: 5, nanos: 0 };
        let mut message = welcome(Some(five), "0.1.0");
        message.message_id.clear();
        assert!(check_welcome(message).is_err());
        assert!(check_welcome(welcome(Some(five), "")).is_err());
    }

    #[test]
    fn statuses_map_to_their_retry_rules() {
        let reason = |code| match ended_by(&Status::new(code, "x"), None) {
            Outcome::Ended { reason, .. } => reason,
            _ => unreachable!(),
        };
        assert_eq!(reason(Code::Aborted), EndReason::Replaced);
        assert!(reason(Code::Unauthenticated).is_slow());
        assert!(reason(Code::PermissionDenied).is_slow());
        assert!(matches!(reason(Code::Unavailable), EndReason::Failed(_)));
        assert!(!reason(Code::Unavailable).is_slow());
        assert!(!reason(Code::Internal).is_slow());
    }
}
