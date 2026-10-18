//! The local channel's server (ADR 0033): `idleo-hostd` answers the tray and the launcher over a named pipe that
//! only the signed-in owner and SYSTEM can open (`\\.\pipe\idleo-<owner SID>`). The protocol is
//! [`idleo_release::channel`]; this is what each request does.
//!
//! - `status`: the status now. Every client also gets it on connecting and on every change.
//! - `set_hosting` / `pause` / `resume`: a `SetPause` for the control plane, the same pause as the web app's. The tray
//!   sees the result when `HostStatus` comes back.
//! - `set_auto_pause`: saved to `settings.toml` and applied at once.
//! - `self_test`: runs the full self-test, sends its report to the control plane, and answers with it.
//! - `prepare_uninstall`: stops a running job after its checkpoint (the owner-return stop), waits for it, then sends
//!   `Leave` and waits up to 15 seconds for the control plane to remove the host.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body as AgentBody;
use idleo_release::channel::{
    AutoPause, ChannelError, Check, Message, Request, UninstallStep, decode_request, encode_message, read_line,
};
use time::OffsetDateTime;
use tokio::io::{AsyncBufRead, AsyncWrite, AsyncWriteExt};

use crate::host::{Host, Kind, Leaving};
use crate::self_test::Report;
use crate::supervisor::{OWNER_STOP_GRACE, Shared};

/// How long the goodbye waits for the control plane before the uninstall carries on without it.
pub const LEAVE_WAITS: Duration = Duration::from_secs(15);
/// The longest a pause may last, as the control plane allows.
pub const LONGEST_PAUSE: Duration = Duration::from_hours(7 * 24);

/// Runs the self-test, full or `quick`, and returns its report.
pub type SelfTestFn = Arc<dyn Fn(bool) -> Pin<Box<dyn Future<Output = Report> + Send>> + Send + Sync>;

/// What a request needs.
#[derive(Clone)]
pub struct Context {
    /// The hub.
    pub host: Arc<Host>,
    /// The running sessions.
    pub sessions: Shared,
    /// The self-test.
    pub self_test: SelfTestFn,
    /// How often to check while waiting (tests make it short).
    pub poll: Duration,
}

/// Serves one client until it hangs up: the status first and on every change, and an answer to each request.
///
/// # Errors
/// If the pipe fails, or the client speaks another version (the line is refused and the connection closed).
pub async fn serve<R, W>(context: Context, mut reader: R, mut writer: W) -> Result<(), ChannelError>
where
    R: AsyncBufRead + Unpin + Send,
    W: AsyncWrite + Unpin + Send,
{
    let mut status = context.host.subscribe();
    let first = status.borrow_and_update().clone();
    send(&mut writer, &Message::Status(first)).await?;
    let (answers, mut answered) = tokio::sync::mpsc::unbounded_channel::<Message>();
    loop {
        tokio::select! {
            line = read_line(&mut reader) => {
                let (id, request) = decode_request(&line?)?;
                // Each request runs on its own, so a long one (an uninstall, a self-test) never holds up the status.
                let context = context.clone();
                let answers = answers.clone();
                tokio::spawn(async move {
                    handle(&context, id, request, &answers).await;
                });
            }
            changed = status.changed() => {
                if changed.is_err() {
                    return Ok(());
                }
                let now = status.borrow_and_update().clone();
                send(&mut writer, &Message::Status(now)).await?;
            }
            Some(message) = answered.recv() => send(&mut writer, &message).await?,
        }
    }
}

async fn send<W: AsyncWrite + Unpin>(writer: &mut W, message: &Message) -> Result<(), ChannelError> {
    writer.write_all(encode_message(message)?.as_bytes()).await.map_err(ChannelError::Io)?;
    writer.flush().await.map_err(ChannelError::Io)
}

async fn handle(context: &Context, id: u64, request: Request, answers: &tokio::sync::mpsc::UnboundedSender<Message>) {
    let answer = |message: Message| {
        let _ = answers.send(message);
    };
    let done = Message::Done { id };
    match request {
        Request::Status => answer(Message::Status(context.host.status())),
        Request::SetHosting { on } => {
            pause(&context.host, None, on);
            answer(done);
        }
        Request::Pause { until } => {
            let now = OffsetDateTime::now_utc();
            if until <= now || until - now > LONGEST_PAUSE {
                answer(Message::Failed { id, message: "A pause must end within 7 days.".to_owned() });
            } else {
                pause(&context.host, Some(until), false);
                answer(done);
            }
        }
        Request::Resume => {
            pause(&context.host, None, true);
            answer(done);
        }
        Request::SetAutoPause { settings } => match set_auto_pause(&context.host, settings) {
            Ok(()) => answer(done),
            Err(message) => answer(Message::Failed { id, message }),
        },
        Request::SelfTest => {
            let report = (context.self_test)(false).await;
            context.host.queue(AgentBody::SelfTestReport(report.to_message()), Kind::SelfTest);
            answer(Message::SelfTest {
                id,
                passed: report.passed,
                checks: report
                    .checks
                    .into_iter()
                    .map(|check| Check { name: check.name, passed: check.passed, detail: check.detail })
                    .collect(),
            });
        }
        Request::PrepareUninstall => {
            for step in prepare_uninstall(context).await {
                answer(Message::Uninstall { id, step });
            }
        }
    }
}

fn set_auto_pause(host: &Host, settings: AutoPause) -> Result<(), String> {
    host.set_auto_pause(settings)
}

/// Queues the owner's pause for the control plane: `resume`, or a pause until `until` (none: until resumed).
fn pause(host: &Host, until: Option<OffsetDateTime>, resume: bool) {
    let until = until.map(|at| prost_types::Timestamp {
        seconds: at.unix_timestamp(),
        nanos: i32::try_from(at.nanosecond()).unwrap_or(0),
    });
    host.queue(
        AgentBody::SetPause(agent_proto::v1::SetPause { until: if resume { None } else { until }, resume }),
        Kind::SetPause,
    );
}

/// Stops a running job after its checkpoint, then says goodbye. Returns the steps it went through.
async fn prepare_uninstall(context: &Context) -> Vec<UninstallStep> {
    let mut steps = Vec::new();
    let running = !context.sessions.lock().running().is_empty();
    if running {
        steps.push(UninstallStep::StoppingSession);
        context.sessions.lock().owner_stop(OWNER_STOP_GRACE);
        let waited = tokio::time::timeout(OWNER_STOP_GRACE + Duration::from_secs(30), async {
            while !context.sessions.lock().running().is_empty() {
                tokio::time::sleep(context.poll).await;
            }
        })
        .await;
        if waited.is_err() {
            tracing::warn!("a job was still running after its grace; leaving anyway");
        }
    }
    steps.push(UninstallStep::Leaving);
    if context.host.leaving() != Leaving::Done {
        context.host.queue(AgentBody::Leave(agent_proto::v1::Leave {}), Kind::Leave);
    }
    let left = tokio::time::timeout(LEAVE_WAITS, async {
        while context.host.leaving() != Leaving::Done {
            tokio::time::sleep(context.poll).await;
        }
    })
    .await;
    steps.push(if left.is_ok() { UninstallStep::Left } else { UninstallStep::Unreachable });
    steps
}

/// Serves the signed-in owner's named pipe for as long as the agent runs, one task per client.
#[cfg(windows)]
pub async fn serve_pipe(context: Context) {
    let sid = match agent_windows::current_user_sid() {
        Ok(sid) => sid,
        Err(e) => {
            tracing::error!(error = %e, "the owner's SID couldn't be read; the tray can't connect");
            return;
        }
    };
    let name = idleo_release::channel::pipe_name(&sid);
    let mut first = true;
    loop {
        let server = match agent_windows::create_pipe_server(&name, first) {
            Ok(server) => server,
            Err(e) => {
                tracing::error!(error = %e, "the local channel couldn't be opened; is another agent running?");
                tokio::time::sleep(Duration::from_secs(5)).await;
                continue;
            }
        };
        first = false;
        if let Err(e) = server.connect().await {
            tracing::warn!(error = %e, "a local channel client couldn't connect");
            continue;
        }
        let context = context.clone();
        tokio::spawn(async move {
            let (reader, writer) = tokio::io::split(server);
            if let Err(e) = serve(context, tokio::io::BufReader::new(reader), writer).await {
                tracing::debug!(error = %e, "a local channel client left");
            }
        });
    }
}

/// Elsewhere there's no tray: the channel isn't served.
#[cfg(not(windows))]
pub async fn serve_pipe(_context: Context) {
    std::future::pending::<()>().await;
}

#[cfg(test)]
mod tests {
    use idleo_release::channel::{Client, LinkState, State};
    use tokio::io::BufReader;

    use super::*;
    use crate::owner::{MachineInputs, Sample};

    struct Quiet;

    impl MachineInputs for Quiet {
        fn sample(&self) -> Sample {
            Sample::QUIET
        }
    }

    struct Fixture {
        host: Arc<Host>,
        client: Client<
            BufReader<tokio::io::ReadHalf<tokio::io::DuplexStream>>,
            tokio::io::WriteHalf<tokio::io::DuplexStream>,
        >,
        _dir: tempfile::TempDir,
    }

    fn fixture() -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        let host = Host::new(Arc::new(Quiet), dir.path().to_owned(), false);
        let clock: Arc<dyn crate::lease_clock::LeaseClock> = Arc::new(crate::lease_clock::SystemLeaseClock);
        let (sessions, _reports) = crate::supervisor::new(
            clock,
            Arc::new(|_: &[String]| 0),
            None,
            Vec::<String>::new(),
            dir.path().join("work"),
        )
        .unwrap();
        let self_test: SelfTestFn = Arc::new(|_quick| {
            Box::pin(async {
                Report {
                    checks: vec![crate::self_test::Check {
                        name: "disk".into(),
                        passed: true,
                        detail: "120 GB free".into(),
                        took_ms: 3,
                    }],
                    passed: true,
                    distro_version: "0.1.0".into(),
                    facts: Vec::new(),
                }
            })
        });
        let context = Context { host: Arc::clone(&host), sessions, self_test, poll: Duration::from_millis(5) };
        let (client_side, agent_side) = tokio::io::duplex(1 << 16);
        let (agent_read, agent_write) = tokio::io::split(agent_side);
        tokio::spawn(serve(context, BufReader::new(agent_read), agent_write));
        let (client_read, client_write) = tokio::io::split(client_side);
        Fixture { host, client: Client::new(BufReader::new(client_read), client_write), _dir: dir }
    }

    async fn answer(client: &mut Fixture, id: u64) -> Message {
        loop {
            match client.client.next().await.unwrap() {
                Message::Status(_) => {}
                other => {
                    let mine = match &other {
                        Message::Done { id: got }
                        | Message::Failed { id: got, .. }
                        | Message::SelfTest { id: got, .. }
                        | Message::Uninstall { id: got, .. } => *got == id,
                        Message::Status(_) => false,
                    };
                    if mine {
                        return other;
                    }
                }
            }
        }
    }

    #[tokio::test]
    async fn a_client_gets_the_status_first_and_on_every_change() {
        let mut fixture = fixture();
        let Message::Status(first) = fixture.client.next().await.unwrap() else { panic!() };
        assert_eq!(first.link_state, LinkState::Unknown);
        assert!(!first.runner_installed);
        fixture.host.set_reachable(true);
        let Message::Status(next) = fixture.client.next().await.unwrap() else { panic!() };
        assert!(next.server_reachable);
    }

    #[tokio::test]
    async fn tray_pauses_become_set_pause_messages_for_the_control_plane() {
        let mut fixture = fixture();
        let off = fixture.client.send(&Request::SetHosting { on: false }).await.unwrap();
        assert_eq!(answer(&mut fixture, off).await, Message::Done { id: off });
        let sent = fixture.host.due(tokio::time::Instant::now(), Duration::ZERO);
        assert!(matches!(&sent[0].body, Some(AgentBody::SetPause(p)) if p.until.is_none() && !p.resume));

        let until = OffsetDateTime::now_utc() + Duration::from_secs(3600);
        let id = fixture.client.send(&Request::Pause { until }).await.unwrap();
        assert_eq!(answer(&mut fixture, id).await, Message::Done { id });
        let sent = fixture.host.due(tokio::time::Instant::now(), Duration::ZERO);
        assert_eq!(sent.len(), 1, "only the latest pause is kept");
        assert!(
            matches!(&sent[0].body, Some(AgentBody::SetPause(p)) if p.until.unwrap().seconds == until.unix_timestamp())
        );

        let too_long = fixture
            .client
            .send(&Request::Pause { until: OffsetDateTime::now_utc() + Duration::from_hours(8 * 24) })
            .await
            .unwrap();
        assert!(matches!(answer(&mut fixture, too_long).await, Message::Failed { .. }));

        let resume = fixture.client.send(&Request::Resume).await.unwrap();
        assert_eq!(answer(&mut fixture, resume).await, Message::Done { id: resume });
        let sent = fixture.host.due(tokio::time::Instant::now(), Duration::ZERO);
        assert!(matches!(&sent[0].body, Some(AgentBody::SetPause(p)) if p.resume));
    }

    #[tokio::test]
    async fn auto_pause_settings_are_checked_and_saved() {
        let mut fixture = fixture();
        let bad = fixture
            .client
            .send(&Request::SetAutoPause {
                settings: AutoPause { start_again_after_minutes: 7, ..AutoPause::default() },
            })
            .await
            .unwrap();
        assert!(matches!(answer(&mut fixture, bad).await, Message::Failed { .. }));
        let settings = AutoPause { games: false, ..AutoPause::default() };
        let good = fixture.client.send(&Request::SetAutoPause { settings }).await.unwrap();
        assert_eq!(answer(&mut fixture, good).await, Message::Done { id: good });
        assert_eq!(fixture.host.status().auto_pause, settings);
        assert_eq!(fixture.host.status().state, State::Available);
    }

    #[tokio::test]
    async fn a_self_test_is_answered_and_reported() {
        let mut fixture = fixture();
        let id = fixture.client.send(&Request::SelfTest).await.unwrap();
        let Message::SelfTest { passed, checks, .. } = answer(&mut fixture, id).await else { panic!() };
        assert!(passed);
        assert_eq!(checks[0].name, "disk");
        let sent = fixture.host.due(tokio::time::Instant::now(), Duration::ZERO);
        assert!(matches!(&sent[0].body, Some(AgentBody::SelfTestReport(report)) if report.passed));
    }

    #[tokio::test]
    async fn the_uninstall_says_goodbye_and_carries_on_if_nobody_answers() {
        let mut fixture = fixture();
        let id = fixture.client.send(&Request::PrepareUninstall).await.unwrap();
        // Nothing to stop: straight to Leave. Acked by the control plane: left.
        loop {
            if let Some(leave) = fixture
                .host
                .due(tokio::time::Instant::now(), Duration::ZERO)
                .into_iter()
                .find(|r| matches!(r.body, Some(AgentBody::Leave(_))))
            {
                fixture.host.acked(&leave.message_id);
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        assert_eq!(answer(&mut fixture, id).await, Message::Uninstall { id, step: UninstallStep::Leaving });
        assert_eq!(answer(&mut fixture, id).await, Message::Uninstall { id, step: UninstallStep::Left });
    }

    #[tokio::test(start_paused = true)]
    async fn an_unanswered_goodbye_gives_up_after_fifteen_seconds() {
        let fixture = fixture();
        let context_steps = {
            let dir = tempfile::tempdir().unwrap();
            let clock: Arc<dyn crate::lease_clock::LeaseClock> = Arc::new(crate::lease_clock::SystemLeaseClock);
            let (sessions, _reports) = crate::supervisor::new(
                clock,
                Arc::new(|_: &[String]| 0),
                None,
                Vec::<String>::new(),
                dir.path().join("work"),
            )
            .unwrap();
            let context = Context {
                host: Arc::clone(&fixture.host),
                sessions,
                self_test: Arc::new(|_quick| Box::pin(async { unreachable!() })),
                poll: Duration::from_millis(100),
            };
            prepare_uninstall(&context).await
        };
        assert_eq!(context_steps, [UninstallStep::Leaving, UninstallStep::Unreachable]);
    }
}
