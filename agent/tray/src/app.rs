//! The tray on the Windows desktop (ADR 0033, ADR 0061): a `tao` event loop on the main thread holds the icon, its native
//! menu and the IdleO window (a WebView2 window like setup's, made when first wanted); a background thread keeps the local
//! channel to the agent open, reconnecting with backoff, and hands each status to the loop.
//!
//! - **Left click** on the icon shows the window above it, and a click anywhere else closes it. **Right click** shows the menu.
//! - The icon is drawn at the size the taskbar asks for, from the display's scale.
//! - Each status can make a toast ([`idleo_tray::notify`]); a click on one opens the right tab through the `idleo:` address.
//! - Quit IdleO asks first when a renter's job is running, then has the agent stop (it saves the job's progress), and exits
//!   with the code that tells the launcher to stop everything too.

use std::collections::HashMap;
use std::process::ExitCode;
use std::sync::Arc;
use std::time::{Duration, Instant};

use idleo_release::channel::{QUIT_BY_OWNER_EXIT_CODE, Request, Status};
use idleo_release::crash::CrashReport;
use idleo_release::install::APP_USER_MODEL_ID;
use idleo_release::paths::Layout;
use idleo_tray::menu::{self, Action, Item};
use idleo_tray::notify::{Memory, Notifier};
use idleo_tray::placement::{self, Rect};
use idleo_tray::status::TrayState;
use idleo_tray::window::{self, Do, FromWindow};
use idleo_tray::{icon, page, session, status};
use tao::dpi::{LogicalSize, PhysicalPosition};
use tao::event::{Event, StartCause, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy, EventLoopWindowTarget};
use tao::window::{Window, WindowBuilder};
use time::{OffsetDateTime, UtcOffset};
use tokio::sync::mpsc;
use tray_icon::menu::{IsMenuItem, Menu, MenuEvent, MenuId, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent};
use wry::{WebContext, WebView, WebViewBuilder};

/// The window's size, in logical pixels.
const WINDOW: (f64, f64) = (400.0, 600.0);
/// After a click on the icon closed the window (by taking its focus), a second click inside this long is the same click.
const REOPEN_GUARD: Duration = Duration::from_millis(400);
/// What the launcher's `Stop it and quit` and the tray's confirmation use for their buttons.
const STOP: i32 = 101;
const CANCEL: i32 = 102;

/// What reaches the event loop from elsewhere.
// A status is a few hundred bytes and arrives a few times a minute; boxing it would only cost every place that reads it.
#[allow(clippy::large_enum_variant)]
enum UserEvent {
    /// A new status, or `None` when the agent went away.
    Status(Option<Status>),
    /// A menu item was clicked.
    Menu(MenuId),
    /// The tray icon was clicked.
    Tray(TrayIconEvent),
    /// The page said something.
    Page(String),
    /// Somebody asked for the window, through the agent.
    OpenWindow(Option<String>),
}

pub fn run() -> ExitCode {
    let layout = Layout::for_user();
    init_logging(&layout);
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let (requests, queued) = mpsc::unbounded_channel::<Request>();
    let proxy = event_loop.create_proxy();
    let connect_proxy = proxy.clone();
    std::thread::spawn(move || connect(&connect_proxy, queued));
    let menu_proxy = proxy.clone();
    MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
        let _ = menu_proxy.send_event(UserEvent::Menu(event.id));
    }));
    let tray_proxy = proxy.clone();
    TrayIconEvent::set_event_handler(Some(move |event: TrayIconEvent| {
        let _ = tray_proxy.send_event(UserEvent::Tray(event));
    }));

    let offset = UtcOffset::current_local_offset().unwrap_or(UtcOffset::UTC);
    let notified = layout.data().join("notified.json");
    let mut app = App {
        layout,
        requests,
        proxy,
        offset,
        current: None,
        crashing: false,
        tray: None,
        actions: HashMap::new(),
        window: None,
        icon_rect: None,
        hidden_by_blur: None,
        notifier: Notifier::new(Memory::load(&notified)),
        notified,
        last_icon: None,
    };
    event_loop.run(move |event, target, control_flow| {
        *control_flow = ControlFlow::WaitUntil(Instant::now() + Duration::from_secs(60));
        if let Some(code) = app.handle(event, target) {
            *control_flow = ControlFlow::ExitWithCode(code);
        }
    })
}

/// A rotating log of the tray's own, scrubbed of the person and the machine.
fn init_logging(layout: &Layout) {
    let who = idleo_release::scrub::Identity::from_environment();
    let log = Arc::new(idleo_release::logs::RotatingLog::standard(&layout.logs().join("tray.log"), move |text| {
        idleo_release::scrub::scrub(text, &who)
    }));
    let _ = tracing_subscriber::fmt().with_ansi(false).with_writer(log).try_init();
}

/// The IdleO window and its page.
struct Shown {
    window: Window,
    webview: WebView,
    /// The page has loaded and said so: it can be sent its state.
    ready: bool,
    // Kept for the life of the window: WebView2's user data folder.
    _context: WebContext,
}

struct App {
    layout: Layout,
    requests: mpsc::UnboundedSender<Request>,
    proxy: EventLoopProxy<UserEvent>,
    offset: UtcOffset,
    current: Option<Status>,
    crashing: bool,
    tray: Option<TrayIcon>,
    actions: HashMap<MenuId, Action>,
    window: Option<Shown>,
    icon_rect: Option<Rect>,
    hidden_by_blur: Option<Instant>,
    notifier: Notifier,
    notified: std::path::PathBuf,
    /// The state and size the icon was last drawn at, so it is only redrawn when something changed.
    last_icon: Option<(TrayState, u32)>,
}

impl App {
    /// Handles one event; `Some(code)` ends the program.
    fn handle(&mut self, event: Event<'_, UserEvent>, target: &EventLoopWindowTarget<UserEvent>) -> Option<i32> {
        match event {
            Event::NewEvents(StartCause::Init | StartCause::ResumeTimeReached { .. }) => {
                self.crashing = CrashReport::load(&self.layout.crash_report()).is_some();
                self.refresh(target);
            }
            Event::UserEvent(UserEvent::Status(status)) => {
                self.current = status;
                self.refresh(target);
            }
            Event::UserEvent(UserEvent::Menu(id)) => {
                if let Some(action) = self.actions.get(&id).cloned() {
                    return self.act(&action, target);
                }
            }
            Event::UserEvent(UserEvent::Tray(TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                rect,
                ..
            })) => {
                // Physical pixels of a screen: whole numbers well inside what an i32 holds.
                #[allow(clippy::cast_possible_truncation)]
                let (left, top) = (rect.position.x as i32, rect.position.y as i32);
                self.icon_rect = Some(Rect::new(
                    left,
                    top,
                    i32::try_from(rect.size.width).unwrap_or(24),
                    i32::try_from(rect.size.height).unwrap_or(24),
                ));
                let visible = self.window.as_ref().is_some_and(|shown| shown.window.is_visible());
                let just_closed = self.hidden_by_blur.is_some_and(|at| at.elapsed() < REOPEN_GUARD);
                if visible || just_closed {
                    self.hide();
                } else {
                    self.show(None, target);
                }
            }
            Event::UserEvent(UserEvent::OpenWindow(tab)) => self.show(tab.as_deref(), target),
            Event::UserEvent(UserEvent::Page(text)) => return self.page(&text, target),
            Event::WindowEvent { event: WindowEvent::Focused(false), .. } => {
                if self.window.as_ref().is_some_and(|shown| shown.window.is_visible()) {
                    self.hide();
                    self.hidden_by_blur = Some(Instant::now());
                }
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => self.hide(),
            _ => {}
        }
        None
    }

    #[allow(clippy::unused_self)] // Read as `self.now()` beside the other clock reads.
    fn now(&self) -> OffsetDateTime {
        OffsetDateTime::now_utc()
    }

    fn state(&self) -> TrayState {
        status::state(self.current.as_ref(), self.crashing)
    }

    /// Redraws everything that depends on the status: the icon, the tooltip, the menu, the window, and the toasts.
    fn refresh(&mut self, target: &EventLoopWindowTarget<UserEvent>) {
        let now = self.now();
        let state = self.state();
        let scale = target.primary_monitor().map_or(1.0, |monitor| monitor.scale_factor());
        let size = icon::size_for_scale(scale);
        let (built, wired) = native_menu(&menu::build(self.current.as_ref(), now, self.offset, self.crashing));
        self.actions = wired;
        let tooltip = status::tooltip(self.current.as_ref(), now, self.offset, self.crashing);
        let picture = (self.last_icon != Some((state, size)))
            .then(|| Icon::from_rgba(icon::rgba(state, size), size, size).ok())
            .flatten();
        if let Some(tray) = &self.tray {
            tray.set_menu(Some(Box::new(built)));
            let _ = tray.set_tooltip(Some(tooltip));
            if let Some(picture) = picture {
                let _ = tray.set_icon(Some(picture));
                self.last_icon = Some((state, size));
            }
        } else {
            let first = picture.unwrap_or_else(|| {
                Icon::from_rgba(icon::rgba(TrayState::Offline, size), size, size)
                    .expect("a square RGBA picture is a valid icon")
            });
            self.tray = TrayIconBuilder::new()
                .with_menu(Box::new(built))
                .with_menu_on_left_click(false)
                .with_tooltip(tooltip)
                .with_icon(first)
                .build()
                .map_err(|e| tracing::error!(error = %e, "the tray icon couldn't be made"))
                .ok();
            self.last_icon = Some((state, size));
        }
        self.push_state();
        self.toast();
    }

    /// Shows a toast for each thing the status says is news.
    fn toast(&mut self) {
        let toasts = self.notifier.observe(self.current.as_ref(), self.crashing, self.now(), self.offset);
        for toast in &toasts {
            if let Err(e) = agent_windows::toast(APP_USER_MODEL_ID, &toast.title, &toast.text, &toast.address()) {
                tracing::warn!(error = %e, "a toast couldn't be shown");
            }
        }
        self.notifier.memory().save(&self.notified);
    }

    /// What a click on a menu item does. `Some(code)` ends the program.
    fn act(&mut self, action: &Action, target: &EventLoopWindowTarget<UserEvent>) -> Option<i32> {
        match action {
            Action::Send(request) => {
                let _ = self.requests.send(request.clone());
            }
            Action::OpenWindow(tab) => self.show(*tab, target),
            Action::OpenHostSite => self.open_host_site(),
            Action::OpenHelp => {
                if let Some(url) = self.current.as_ref().and_then(Status::help_url) {
                    let _ = idleo_release::browser::open(&url);
                }
            }
            Action::Quit => return self.quit(),
        }
        None
    }

    fn open_host_site(&self) {
        if let Some(url) = self.current.as_ref().and_then(Status::dashboard_url) {
            let _ = idleo_release::browser::open(&url);
        }
    }

    /// Quit IdleO: asks first while a job runs; then the agent stops (saving the job's progress), and so does the tray.
    fn quit(&mut self) -> Option<i32> {
        if self.current.as_ref().is_some_and(|status| status.session_running) {
            let answer = agent_windows::ask(
                "Quit IdleO",
                "A renter's job is running",
                "It'll save its progress and stop within 2 minutes. IdleO then quits.",
                &[(STOP, "Stop it and quit"), (CANCEL, "Cancel")],
            );
            if !matches!(answer, Ok(Some(STOP))) {
                return None;
            }
        }
        let _ = self.requests.send(Request::Quit);
        // The agent answers and goes; the tray follows a moment later, so its request is sent first.
        std::thread::sleep(Duration::from_millis(600));
        Some(i32::from(QUIT_BY_OWNER_EXIT_CODE))
    }

    // --- the window ------------------------------------------------------------------------------------------------

    /// Makes the window and its page, once.
    fn make_window(&mut self, target: &EventLoopWindowTarget<UserEvent>) -> Option<&mut Shown> {
        if self.window.is_none() {
            if wry::webview_version().is_err() {
                tracing::warn!("WebView2 isn't there; the window can't open");
                self.open_host_site();
                return None;
            }
            let window = WindowBuilder::new()
                .with_title("IdleO")
                .with_inner_size(LogicalSize::new(WINDOW.0, WINDOW.1))
                .with_resizable(false)
                .with_maximizable(false)
                .with_visible(false)
                .build(target)
                .map_err(|e| tracing::error!(error = %e, "the window couldn't be made"))
                .ok()?;
            let page_proxy = self.proxy.clone();
            let mut context = WebContext::new(Some(self.layout.data().join("webview")));
            let webview = WebViewBuilder::new_with_web_context(&mut context)
                .with_html(page::page())
                .with_devtools(false)
                .with_ipc_handler(move |request| {
                    let _ = page_proxy.send_event(UserEvent::Page(request.body().clone()));
                })
                .build(&window)
                .map_err(|e| tracing::error!(error = %e, "the page couldn't be made"))
                .ok()?;
            self.window = Some(Shown { window, webview, ready: false, _context: context });
        }
        self.window.as_mut()
    }

    /// Shows the window above the tray icon, on `tab` if one is named.
    fn show(&mut self, tab: Option<&str>, target: &EventLoopWindowTarget<UserEvent>) {
        let icon_rect = self.icon_rect;
        if self.make_window(target).is_none() {
            return;
        }
        let now = self.now();
        let (offset, crashing, current) = (self.offset, self.crashing, self.current.clone());
        let Some(shown) = self.window.as_mut() else { return };
        let size = shown.window.outer_size();
        let (width, height) = (i32::try_from(size.width).unwrap_or(400), i32::try_from(size.height).unwrap_or(600));
        let scale = shown.window.scale_factor();
        let monitors: Vec<Rect> = shown
            .window
            .available_monitors()
            .map(|monitor| {
                Rect::new(
                    monitor.position().x,
                    monitor.position().y,
                    i32::try_from(monitor.size().width).unwrap_or(1920),
                    i32::try_from(monitor.size().height).unwrap_or(1080),
                )
            })
            .collect();
        let screen = icon_rect
            .and_then(|icon| monitors.iter().copied().find(|screen| screen.contains(icon.x, icon.y)))
            .or_else(|| monitors.first().copied())
            .unwrap_or_else(|| Rect::new(0, 0, 1920, 1080));
        let (x, y) = match icon_rect {
            Some(icon) => placement::above(icon, (width, height), screen, scale),
            None => placement::corner((width, height), screen, scale),
        };
        shown.window.set_outer_position(PhysicalPosition::new(x, y));
        let state = window::view(current.as_ref(), now, offset, crashing, idleo_agent_memory_mb());
        let _ = shown.webview.evaluate_script(&window::script(&state));
        if let Some(tab) = tab {
            let _ = shown.webview.evaluate_script(&window::show_tab_script(tab));
        }
        shown.window.set_visible(true);
        shown.window.set_focus();
        self.hidden_by_blur = None;
    }

    fn hide(&self) {
        if let Some(shown) = &self.window {
            shown.window.set_visible(false);
        }
    }

    /// Hands the page the state it should show now.
    fn push_state(&mut self) {
        let now = self.now();
        let state = window::view(self.current.as_ref(), now, self.offset, self.crashing, idleo_agent_memory_mb());
        if let Some(shown) = &self.window
            && shown.ready
        {
            let _ = shown.webview.evaluate_script(&window::script(&state));
        }
    }

    /// The page said something. `Some(code)` ends the program (it never does today).
    fn page(&mut self, text: &str, _target: &EventLoopWindowTarget<UserEvent>) -> Option<i32> {
        let ask = FromWindow::parse(text)?;
        if ask == FromWindow::Ready {
            if let Some(shown) = self.window.as_mut() {
                shown.ready = true;
            }
            self.push_state();
            return None;
        }
        match window::meaning(&ask, self.now(), self.offset) {
            Do::Send(request) => {
                let _ = self.requests.send(request);
            }
            Do::Open(address) => {
                let _ = idleo_release::browser::open(&address);
            }
            Do::HostSite => self.open_host_site(),
            Do::Diagnostics => self.save_diagnostics(),
            Do::Remove => self.remove(),
            Do::Nothing => {}
        }
        None
    }

    /// Saves the diagnostics zip (scrubbed, no keys) and shows where it is.
    fn save_diagnostics(&self) {
        let now = self.now();
        let who = idleo_release::scrub::Identity::from_environment();
        let status =
            self.current.as_ref().and_then(|status| serde_json::to_string_pretty(status).ok()).unwrap_or_default();
        let system = format!(
            "Windows build {}\nIdleO tray {}\ncrash loop reported: {}\n",
            agent_windows::windows_build().map_or_else(|e| format!("unknown ({e})"), |build| build.to_string()),
            env!("CARGO_PKG_VERSION"),
            self.crashing
        );
        let notes = vec![("status.json".to_owned(), status), ("system.txt".to_owned(), system)];
        match idleo_release::diagnostics::write_zip(
            &self.layout,
            &who,
            env!("CARGO_PKG_VERSION"),
            now,
            &notes,
            &idleo_release::diagnostics::default_folder(),
        ) {
            Ok(path) => {
                let _ = std::process::Command::new("explorer").arg(format!("/select,{}", path.display())).spawn();
                agent_windows::message_box(
                    "IdleO",
                    &format!(
                        "Saved diagnostics to:\n{}\n\nYour name and your computer's name were taken out. Send it to support if they ask.",
                        path.display()
                    ),
                );
            }
            Err(e) => agent_windows::message_box("IdleO", &format!("Diagnostics couldn't be saved: {e}")),
        }
    }

    /// Remove IdleO: the uninstaller, which asks about a running job and about earnings history kept on this laptop.
    fn remove(&self) {
        let _ = std::process::Command::new(self.layout.launcher()).arg("uninstall").spawn();
    }
}

/// The laptop's memory in MB, for "50% of memory is about 8 GB".
fn idleo_agent_memory_mb() -> Option<u64> {
    agent_windows::total_memory_mb().ok().filter(|mb| *mb > 0)
}

/// The native menu for the model, and what each clickable item does.
fn native_menu(items: &[Item]) -> (Menu, HashMap<MenuId, Action>) {
    let menu = Menu::new();
    let mut actions = HashMap::new();
    for item in items {
        let built: Box<dyn IsMenuItem> = match item {
            Item::Label(text) => Box::new(MenuItem::new(text, false, None)),
            Item::Separator => Box::new(PredefinedMenuItem::separator()),
            Item::Button { text, action } => {
                let built = MenuItem::new(text, true, None);
                actions.insert(built.id().clone(), action.clone());
                Box::new(built)
            }
        };
        let _ = menu.append(built.as_ref());
    }
    (menu, actions)
}

/// Keeps the channel to the agent open for the life of the tray.
fn connect(proxy: &EventLoopProxy<UserEvent>, mut requests: mpsc::UnboundedReceiver<Request>) {
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread().enable_all().build() else { return };
    runtime.block_on(async {
        let mut failures = 0;
        loop {
            match idleo_release::channel::connect() {
                Ok(mut client) => {
                    failures = 0;
                    let status_proxy = proxy.clone();
                    let mut forward = move |status: Status| {
                        let _ = status_proxy.send_event(UserEvent::Status(Some(status)));
                    };
                    let open_proxy = proxy.clone();
                    let mut open = move |tab: Option<String>| {
                        let _ = open_proxy.send_event(UserEvent::OpenWindow(tab));
                    };
                    let ended = session::run(&mut client, &mut requests, &mut forward, &mut open).await;
                    tracing::info!(error = %ended, "the agent's channel closed");
                }
                Err(e) => tracing::debug!(error = %e, "the agent isn't reachable yet"),
            }
            let _ = proxy.send_event(UserEvent::Status(None));
            tokio::time::sleep(session::backoff(failures)).await;
            failures = failures.saturating_add(1);
        }
    });
}
