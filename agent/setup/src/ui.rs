//! The setup window (ADR 0033): 720×520, fixed, a WebView2 page built into the program, in the Bento look. Rust
//! drives the page with typed messages ([`idleo_setup::ipc`]); the steps run on their own thread. With no WebView2
//! (an old Windows 10), a plain message asks for a Windows update instead.

use std::process::ExitCode;

use base64::Engine as _;
use idleo_setup::ipc::{self, FromPage, ToPage};
use idleo_setup::steps::{self, Ending, Engine};
use tao::dpi::LogicalSize;
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use tokio::sync::{mpsc, oneshot};
use wry::WebViewBuilder;

use crate::windows_system::WindowsSystem;

const PAGE: &str = include_str!("../assets/index.html");
const STYLE: &str = include_str!("../assets/style.css");
const SCRIPT: &str = include_str!("../assets/app.js");
/// Plus Jakarta Sans, the one face (OFL; its licence is web/public/fonts/LICENSE-PlusJakartaSans.txt, shipped beside
/// the programs in the app zip). Embedded, never fetched.
const FONT: &[u8] = include_bytes!("../../../web/public/fonts/plus-jakarta-sans-subset.woff2");
/// Where Windows' WebView2 runtime is downloaded, for the rare Windows 10 without it.
const WEBVIEW2_URL: &str = "https://go.microsoft.com/fwlink/p/?LinkId=2124703";

enum UserEvent {
    /// A screen for the page.
    Show(ToPage),
    /// A message from the page.
    Page(String),
    /// The steps ended.
    Ended(Ending),
}

fn page() -> String {
    let font = base64::engine::general_purpose::STANDARD.encode(FONT);
    PAGE.replace("/*STYLE*/", &STYLE.replace("/*FONT*/", &font)).replace("/*SCRIPT*/", SCRIPT)
}

pub fn run() -> ExitCode {
    let layout = idleo_release::paths::Layout::for_user();
    let _ = std::fs::create_dir_all(layout.logs());
    if let Ok(file) = std::fs::OpenOptions::new().create(true).append(true).open(layout.logs().join("setup.log")) {
        let _ = tracing_subscriber::fmt().with_ansi(false).with_writer(std::sync::Mutex::new(file)).try_init();
    }
    if wry::webview_version().is_err() {
        agent_windows::message_box(
            "IdleO",
            "Please update Windows, then open this again.\n\nWindows needs its WebView2 part, which comes with Windows updates.",
        );
        let _ = idleo_release::browser::open(WEBVIEW2_URL);
        return ExitCode::FAILURE;
    }
    let resume = std::env::args().any(|arg| arg == "--resume");
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let title = if idleo_release::keys::DEV_BUILD { "IdleO setup — Development build" } else { "IdleO setup" };
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
    let webview = match WebViewBuilder::new()
        .with_html(page())
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

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(UserEvent::Show(screen)) => {
                let _ = webview.evaluate_script(&ipc::script(&screen));
            }
            Event::UserEvent(UserEvent::Page(text)) => match FromPage::parse(&text) {
                Some(FromPage::Ready) => {
                    if idleo_release::keys::DEV_BUILD {
                        let _ = webview.evaluate_script("window.idleo.dev('Development build');");
                    }
                    if let Some((from_page, cancelled)) = started.take() {
                        let proxy = proxy.clone();
                        std::thread::spawn(move || steps_thread(&proxy, from_page, cancelled, resume));
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
            Event::UserEvent(UserEvent::Ended(ending)) => {
                tracing::info!(?ending, "setup ended");
                match ending {
                    Ending::Done { .. } => finished = Some(ending),
                    Ending::Restarting | Ending::Later | Ending::Undo => *control_flow = ControlFlow::Exit,
                }
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => {
                if finished.is_some() {
                    *control_flow = ControlFlow::Exit;
                } else {
                    let _ = webview.evaluate_script("window.idleo.askClose();");
                }
            }
            _ => {}
        }
    });
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
    let ending = runtime.block_on(async {
        let system = match WindowsSystem::new(idleo_setup::config::baked()) {
            Ok(system) => system,
            Err(e) => {
                tracing::error!(error = %e, "setup couldn't start");
                return Ending::Undo;
            }
        };
        let show_proxy = proxy.clone();
        let mut show = move |screen: ToPage| {
            let _ = show_proxy.send_event(UserEvent::Show(screen));
        };
        let ending = {
            let mut engine = Engine::new(
                &system,
                idleo_setup::config::baked(),
                idleo_release::keys::RELEASE_KEY,
                &mut show,
                &mut from_page,
            );
            tokio::select! {
                ending = engine.run(resume) => ending,
                _ = cancelled => Ending::Undo,
            }
        };
        if ending == Ending::Undo {
            let running = std::env::current_exe().ok();
            let removal = steps::undo(&system, running.as_deref());
            tracing::info!(problems = ?removal.problems, "undid setup");
        }
        ending
    });
    let _ = proxy.send_event(UserEvent::Ended(ending));
}
