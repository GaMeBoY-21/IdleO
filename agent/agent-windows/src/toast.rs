//! Windows toasts (ADR 0061): a notification in the corner and the Action Center, from the `WinRT` `ToastNotification` API,
//! which the `windows` crate projects as safe functions: there is no `unsafe` here at all, and the module sits in this crate
//! only because every call into Windows does.
//!
//! A toast belongs to an app by its AppUserModelID, which the Start-menu shortcut carries (`IdleO.Host`), so no registration
//! is needed. A click activates the toast's `launch` address by protocol (`idleo:open/earnings`), which the per-user
//! `idleo:` registration answers with the launcher. Windows' Focus mode and the user's notification settings apply as they do
//! to every app: nothing here asks to break through them.

use std::io;

use windows::Data::Xml::Dom::XmlDocument;
use windows::UI::Notifications::{ToastNotification, ToastNotificationManager};
use windows::core::HSTRING;

/// Shows a toast for the app `app_id`.
///
/// # Errors
/// If Windows refuses (the XML, or no notifier for the app).
pub fn toast(app_id: &str, title: &str, text: &str, launch: &str) -> io::Result<()> {
    let xml = XmlDocument::new().map_err(io::Error::from)?;
    xml.LoadXml(&HSTRING::from(crate::toast_xml(title, text, launch))).map_err(io::Error::from)?;
    let notification = ToastNotification::CreateToastNotification(&xml).map_err(io::Error::from)?;
    ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(app_id))
        .and_then(|notifier| notifier.Show(&notification))
        .map_err(io::Error::from)
}
