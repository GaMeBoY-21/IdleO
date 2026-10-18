//! The Windows app's tray icon (ADR 0033): a round icon by the clock in the host's colour, and a native menu with its
//! state, today's earnings, Hosting on and off, Pause, Auto-pause and Open dashboard. Nothing else; no Quit.
//!
//! Everything it shows and sends is worked out here as data, tested without a desktop: [`status`] (the words and
//! colour), [`menu`] (the items and what each sends), [`icon`] (the pixels) and [`session`] (talking to the agent
//! over the local channel). The window system's part is `app`, on Windows only.
#![forbid(unsafe_code)]

pub mod icon;
pub mod menu;
pub mod session;
pub mod status;
