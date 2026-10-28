//! The tray on the Windows desktop: a `tao` event loop on the main thread holds the icon and its native menu; a
//! background thread keeps the local channel to the agent open, reconnecting with backoff, and hands each status to
//! the loop. The menu is rebuilt from [`idleo_tray::menu`] on every status and every minute (so "this evening" goes
//! at 6 pm), and each click does what the model says.

use std::collections::HashMap;
use std::process::ExitCode;
use std::time::{Duration, Instant};

use idleo_release::channel::{Request, Status};
use idleo_tray::menu::{self, Action, Item};
use idleo_tray::{icon, session, status};
use tao::event::{Event, StartCause};
use tao::event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy};
use time::{OffsetDateTime, UtcOffset};
use tokio::sync::mpsc;
use tray_icon::menu::{CheckMenuItem, IsMenuItem, Menu, MenuEvent, MenuId, MenuItem, PredefinedMenuItem, Submenu};
use tray_icon::{Icon, TrayIcon, TrayIconBuilder};

/// What reaches the event loop from elsewhere.
enum UserEvent {
    /// A new status, or `None` when the agent went away.
    Status(Option<Status>),
    /// A menu item was clicked.
    Menu(MenuId),
}

pub fn run() -> ExitCode {
    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    let (requests, queued) = mpsc::unbounded_channel::<Request>();
    let proxy = event_loop.create_proxy();
    std::thread::spawn(move || connect(&proxy, queued));
    let menu_proxy = event_loop.create_proxy();
    MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
        let _ = menu_proxy.send_event(UserEvent::Menu(event.id));
    }));

    let offset = UtcOffset::current_local_offset().unwrap_or(UtcOffset::UTC);
    let mut current: Option<Status> = None;
    let mut tray: Option<TrayIcon> = None;
    let mut actions: HashMap<MenuId, Action> = HashMap::new();
    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::WaitUntil(Instant::now() + Duration::from_secs(60));
        let rebuild = match event {
            Event::NewEvents(StartCause::Init | StartCause::ResumeTimeReached { .. }) => true,
            Event::UserEvent(UserEvent::Status(status)) => {
                current = status;
                true
            }
            Event::UserEvent(UserEvent::Menu(id)) => {
                match actions.get(&id) {
                    Some(Action::Send(request)) => {
                        let _ = requests.send(request.clone());
                    }
                    Some(Action::OpenDashboard) => {
                        if let Some(url) = current.as_ref().and_then(|status| status.app_url.as_deref()) {
                            let _ = idleo_release::browser::open(&menu::dashboard_url(url));
                        }
                    }
                    None => {}
                }
                false
            }
            _ => false,
        };
        if rebuild {
            let (built, wired) = native_menu(&menu::build(current.as_ref(), OffsetDateTime::now_utc(), offset));
            actions = wired;
            let tooltip = status::tooltip(current.as_ref(), offset);
            let picture = picture(status::colour(current.as_ref()));
            match &tray {
                Some(tray) => {
                    tray.set_menu(Some(Box::new(built)));
                    let _ = tray.set_tooltip(Some(tooltip));
                    let _ = tray.set_icon(picture);
                }
                None => {
                    tray = TrayIconBuilder::new()
                        .with_menu(Box::new(built))
                        .with_menu_on_left_click(true)
                        .with_tooltip(tooltip)
                        .with_icon(picture.unwrap_or_else(fallback))
                        .build()
                        .map_err(|e| tracing::error!(error = %e, "the tray icon couldn't be made"))
                        .ok();
                }
            }
        }
    })
}

fn picture(colour: status::Colour) -> Option<Icon> {
    Icon::from_rgba(icon::rgba(colour), icon::SIZE, icon::SIZE).ok()
}

fn fallback() -> Icon {
    picture(status::Colour::Ember).expect("a 32×32 RGBA picture is a valid icon")
}

/// The native menu for the model, and what each clickable item does.
fn native_menu(items: &[Item]) -> (Menu, HashMap<MenuId, Action>) {
    let menu = Menu::new();
    let mut actions = HashMap::new();
    for item in items {
        let built = native_item(item, &mut actions);
        let _ = menu.append(built.as_ref());
    }
    (menu, actions)
}

fn native_item(item: &Item, actions: &mut HashMap<MenuId, Action>) -> Box<dyn IsMenuItem> {
    match item {
        Item::Label(text) => Box::new(MenuItem::new(text, false, None)),
        Item::Separator => Box::new(PredefinedMenuItem::separator()),
        Item::Button { text, action } => {
            let built = MenuItem::new(text, true, None);
            actions.insert(built.id().clone(), action.clone());
            Box::new(built)
        }
        Item::Check { text, checked, action } => {
            let built = CheckMenuItem::new(text, true, *checked, None);
            actions.insert(built.id().clone(), action.clone());
            Box::new(built)
        }
        Item::Submenu { text, items } => {
            let submenu = Submenu::new(text, true);
            for inner in items {
                let built = native_item(inner, actions);
                let _ = submenu.append(built.as_ref());
            }
            Box::new(submenu)
        }
    }
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
                    let ended = session::run(&mut client, &mut requests, &mut forward).await;
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
