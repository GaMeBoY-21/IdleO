//! The Windows app's tray icon and the IdleO window (ADR 0033, ADR 0061): a round icon by the clock whose colour and shape say
//! what the laptop is doing, a right-click menu, a 400 × 600 window opened by a left click, and Windows toasts.
//!
//! Everything it shows and sends is worked out here as data, tested without a desktop: [`status`] (the state and the
//! words), [`menu`] (the items and what each does), [`icon`] (the pixels), [`window`] (what the window shows and asks),
//! [`notify`] (which toasts, and when) and [`session`] (talking to the agent over the local channel). The window system's
//! part is `app`, on Windows only.
#![forbid(unsafe_code)]

pub mod icon;
pub mod menu;
pub mod notify;
pub mod page;
pub mod placement;
pub mod session;
pub mod status;
pub mod window;
