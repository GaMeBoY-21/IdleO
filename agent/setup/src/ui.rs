//! The setup window (ADR 0033, ADR 0061): 720×520, a native title bar, a WebView2 page built into the program, in the IdleO design,
//! dark or light as Windows is set, and sharp at every display scale (the manifest makes the program per-monitor DPI aware, and
//! the window's size is in logical pixels). Rust drives the page with typed messages ([`idleo_setup::ipc`]); the steps run on their
//! own thread. With no WebView2 (an old Windows 10), a plain message asks for a Windows update instead.
//!
//! Three ways to run it:
//! - normally: the real setup;
//! - `--demo` (or `IdleO-Setup-Demo.exe`): the screens with pretend progress, changing nothing on the PC; the title bar says
//!   "Demo";
//! - `--smoke`, with either: open the window, wait for its page to load and say so, then close, with exit code 0 (or 1 if the
//!   page didn't load in 20 seconds). That is what the Windows CI job runs to prove the window and its page load.

use std::process::ExitCode;
use std::time::Duration;

use idleo_setup::demo;
use idleo_setup::ipc::{self, FromPage, ToPage};
use idleo_setup::steps::{self, Ending, Engine};
use tao::dpi::LogicalSize;
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use tokio::sync::{mpsc, oneshot};
use wry::{WebContext, WebViewBuilder};

use crate::windows_system::WindowsSystem;

/// Where Windows' WebView2 runtime is downloaded, for the rare Windows 10 without it.
const WEBVIEW2_URL: &str = "https://go.microsoft.com/fwlink/p/?LinkId=2124703";
/// How long `--smoke` waits for the page to load.
const SMOKE_LIMIT: Duration = Duration::from_secs(20);
/// How long `--smoke` keeps the window open once the page has loaded: long enough to be a window, not a flash.
const SMOKE_HOLD: Duration = Duration::from_secs(2);

/// How this run goes.
#[derive(Debug, Clone, Copy, Default)]
pub struct Mode {
    /// The screens with pretend progress; nothing is changed on the PC.
    pub demo: bool,
    /// Open, load the page, close: for the CI check.
    pub smoke: bool,
    /// Carrying on after the restart WSL needed.
    pub resume: bool,
}

impl Mode {
    /// Reads `--demo`, `--smoke` and `--resume` from the command line.
    pub fn from_args() -> Self {
        let has = |flag: &str| std::env::args().any(|arg| arg == flag);
        Self { demo: has("--demo"), smoke: has("--smoke"), resume: has("--resume") }
    }
}

enum UserEvent {
    /// A screen for the page.
    Show(ToPage),
    /// A message from the page.
    Page(String),
    /// The steps ended, and for a development build's failed setup, the last screen with where the log is.
    Ended(Ending, Option<ToPage>),
    /// `--smoke`: the page has loaded and the window has been up long enough.
    SmokeDone,
    /// `--smoke`: the page never loaded.
    SmokeTimedOut,
}

#[allow(clippy::too_many_lines)] // The window's start-up and its one event loop, in the order things must come up.
pub fn run(mode: Mode) -> ExitCode {
    let layout = idleo_release::paths::Layout::for_user();
    // The demo writes nothing: no log folder, no log file.
    if !mode.demo {
        let _ = std::fs::create_dir_all(layout.logs());
        if let Ok(file) = std::fs::OpenOptions::new().create(true).append(true).open(layout.logs().join("setup.log")) {
            let _ = tracing_subscriber::fmt().with_ansi(false).with_writer(std::sync::Mutex::new(file)).try_init();
        }
    }
    if wry::webview_version().is_err() {
        agent_windows::message_box(
            "IdleO",
            "Please update Windows, then open this again.\n\nWindows needs its WebView2 part, which comes with Windows updates.",
        );
        let _ = idleo_release::browser::open(WEBVIEW2_URL);
        return ExitCode::FAILURE;
    }
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let title = match (mode.demo, idleo_release::keys::DEV_BUILD) {
        (true, _) => "IdleO setup — Demo",
        (false, true) => "IdleO setup — Development build",
        (false, false) => "IdleO setup",
    };
    let window = match WindowBuilder::new()
        .with_title(title)
        .with_inner_size(LogicalSize::new(720.0, 520.0))
        .with_resizable(false)
        .with_maximizable(false)
        .build(&event_loop)
    {
        Ok(window) => window,
        Err(e) => {
            tracing::error!(error = %e, "the window couldn't be made");
            return ExitCode::FAILURE;
        }
    };
    let page_proxy = event_loop.create_proxy();
    // WebView2 keeps its profile in a folder: not beside the program (which may be in Downloads), and not in IdleO's own
    // folders (which an undo removes while it is open). The temporary folder is the right place, and goes with the machine's
    // next clean-up.
    let mut context = WebContext::new(Some(std::env::temp_dir().join("IdleO-Setup-WebView")));
    let webview = match WebViewBuilder::new_with_web_context(&mut context)
        .with_html(idleo_setup::page::page())
        .with_devtools(false)
        .with_ipc_handler(move |request| {
            let _ = page_proxy.send_event(UserEvent::Page(request.body().clone()));
        })
        .build(&window)
    {
        Ok(webview) => webview,
        Err(e) => {
            agent_windows::message_box("IdleO", "Please update Windows, then open this again.");
            tracing::error!(error = %e, "the web view couldn't be made");
            return ExitCode::FAILURE;
        }
    };

    let (to_steps, from_page) = mpsc::unbounded_channel::<FromPage>();
    let (cancel, cancelled) = oneshot::channel::<()>();
    let mut cancel = Some(cancel);
    let mut started = Some((from_page, cancelled));
    let proxy = event_loop.create_proxy();
    let mut finished: Option<Ending> = None;
    if mode.smoke {
        let proxy = proxy.clone();
        std::thread::spawn(move || {
            std::thread::sleep(SMOKE_LIMIT);
            let _ = proxy.send_event(UserEvent::SmokeTimedOut);
        });
    }

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(UserEvent::Show(screen)) => {
                let _ = webview.evaluate_script(&ipc::script(&screen));
            }
            Event::UserEvent(UserEvent::Page(text)) => match FromPage::parse(&text) {
                Some(FromPage::Ready) => {
                    if idleo_release::keys::DEV_BUILD && !mode.demo {
                        let _ = webview.evaluate_script("window.idleo.dev('Development build');");
                    }
                    if mode.smoke {
                        // The page has loaded: that is what is being proved. Hold the window up for a moment, then go.
                        let proxy = proxy.clone();
                        std::thread::spawn(move || {
                            std::thread::sleep(SMOKE_HOLD);
                            let _ = proxy.send_event(UserEvent::SmokeDone);
                        });
                    }
                    if let Some((from_page, cancelled)) = started.take() {
                        let proxy = proxy.clone();
                        if mode.demo {
                            std::thread::spawn(move || demo_thread(&proxy, from_page, cancelled));
                        } else {
                            let resume = mode.resume;
                            std::thread::spawn(move || steps_thread(&proxy, from_page, cancelled, resume));
                        }
                    }
                }
                Some(FromPage::Close) => {
                    if finished.is_some() {
                        *control_flow = ControlFlow::Exit;
                    }
                }
                Some(FromPage::ConfirmClose) => {
                    let _ = to_steps.send(FromPage::ConfirmClose);
                    if let Some(cancel) = cancel.take() {
                        let _ = cancel.send(());
                    }
                }
                Some(message) => {
                    let _ = to_steps.send(message);
                }
                None => {}
            },
            Event::UserEvent(UserEvent::Ended(ending, last_screen)) => {
                if let Some(last) = ended(&webview, ending, last_screen) {
                    finished = Some(last);
                } else {
                    *control_flow = ControlFlow::Exit;
                }
            }
            Event::UserEvent(UserEvent::SmokeDone) => *control_flow = ControlFlow::ExitWithCode(0),
            Event::UserEvent(UserEvent::SmokeTimedOut) => *control_flow = ControlFlow::ExitWithCode(1),
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                if finished.is_some() || mode.smoke {
                    *control_flow = ControlFlow::Exit;
                } else {
                    let _ = webview.evaluate_script("window.idleo.askClose();");
                }
            }
            _ => {}
        }
    })
}

/// The steps ended. A finished setup, and a development build's failed one with its last screen (where the log is),
/// stay open until closed: the ending is returned. Anything else closes the window.
fn ended(webview: &wry::WebView, ending: Ending, last_screen: Option<ToPage>) -> Option<Ending> {
    tracing::info!(?ending, "setup ended");
    match (&ending, last_screen) {
        (Ending::Done { .. }, _) => Some(ending),
        (Ending::Undo | Ending::Kept, Some(screen)) => {
            let _ = webview.evaluate_script(&ipc::script(&screen));
            Some(ending)
        }
        _ => None,
    }
}

/// The demo, on its own thread: every screen, with pretend progress. Nothing here can touch the PC: it is handed no machine.
fn demo_thread(
    proxy: &tao::event_loop::EventLoopProxy<UserEvent>,
    mut from_page: mpsc::UnboundedReceiver<FromPage>,
    cancelled: oneshot::Receiver<()>,
) {
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else { return };
    let ending = runtime.block_on(async {
        let show_proxy = proxy.clone();
        let mut show = move |screen: ToPage| {
            let _ = show_proxy.send_event(UserEvent::Show(screen));
        };
        tokio::select! {
            ending = demo::run(&mut show, &mut from_page, demo::Pace::default()) => ending,
            _ = cancelled => Ending::Undo,
        }
    });
    let _ = proxy.send_event(UserEvent::Ended(ending, None));
}

/// Runs the steps on their own thread, with a runtime of their own; a confirmed "Stop setting up?" cancels whatever
/// step is running, and everything done is undone.
fn steps_thread(
    proxy: &tao::event_loop::EventLoopProxy<UserEvent>,
    mut from_page: mpsc::UnboundedReceiver<FromPage>,
    cancelled: oneshot::Receiver<()>,
    resume: bool,
) {
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else { return };
    let (ending, last_screen) = runtime.block_on(async {
        let system = match WindowsSystem::new(idleo_setup::config::baked()) {
            Ok(system) => system,
            Err(e) => {
                tracing::error!(error = %e, "setup couldn't start");
                return (Ending::Undo, None);
            }
        };
        let show_proxy = proxy.clone();
        let mut show = move |screen: ToPage| {
            let _ = show_proxy.send_event(UserEvent::Show(screen));
        };
        let (ending, summary) = {
            let mut engine = Engine::new(
                &system,
                idleo_setup::config::baked(),
                idleo_release::keys::RELEASE_KEY,
                &mut show,
                &mut from_page,
            )
            .dev_build(idleo_release::keys::DEV_BUILD);
            let ending = tokio::select! {
                ending = engine.run(resume) => ending,
                _ = cancelled => Ending::Undo,
            };
            (ending, engine.summary())
        };
        // A development build keeps the log of a setup that didn't pass, and says where it is; a release build
        // leaves nothing behind.
        let dev_log = idleo_release::keys::DEV_BUILD && summary.failed;
        if ending == Ending::Undo {
            let running = std::env::current_exe().ok();
            let removal = if dev_log {
                steps::undo_keeping_logs(&system, running.as_deref())
            } else {
                steps::undo(&system, running.as_deref())
            };
            tracing::info!(problems = ?removal.problems, "undid setup");
        }
        let last_screen = (idleo_release::keys::DEV_BUILD
            && (ending == Ending::Kept || dev_log && ending == Ending::Undo))
            .then(|| ToPage::Stopped {
                kept: ending == Ending::Kept,
                log: summary.log.display().to_string(),
                report_problem: summary.report_problem,
            });
        (ending, last_screen)
    });
    let _ = proxy.send_event(UserEvent::Ended(ending, last_screen));
}
