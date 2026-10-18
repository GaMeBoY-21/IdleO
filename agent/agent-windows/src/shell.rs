//! The Windows shell's pieces the app uses: the Start-menu shortcut (with the app's AppUserModelID), a plain message
//! box, the uninstaller's task dialogs (a question, then progress), and the local channel's named pipe with an access
//! list of the owner and SYSTEM only (ADR 0033).

use std::ffi::c_void;
use std::io;
use std::path::Path;
use std::sync::{Arc, Mutex, PoisonError};

use windows::Win32::Foundation::{HWND, LPARAM, S_FALSE, S_OK, WPARAM};
use windows::Win32::Security::SECURITY_ATTRIBUTES;
use windows::Win32::Storage::EnhancedStorage::PKEY_AppUserModel_ID;
use windows::Win32::System::Com::StructuredStorage::PROPVARIANT;
use windows::Win32::System::Com::{
    CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx, IPersistFile,
};
use windows::Win32::UI::Controls::{
    TASKDIALOG_BUTTON, TASKDIALOG_NOTIFICATIONS, TASKDIALOGCONFIG, TDE_CONTENT, TDE_MAIN_INSTRUCTION,
    TDF_ALLOW_DIALOG_CANCELLATION, TDF_CALLBACK_TIMER, TDF_SHOW_MARQUEE_PROGRESS_BAR, TDF_SHOW_PROGRESS_BAR,
    TDM_ENABLE_BUTTON, TDM_SET_ELEMENT_TEXT, TDM_SET_MARQUEE_PROGRESS_BAR, TDM_SET_PROGRESS_BAR_MARQUEE,
    TDM_SET_PROGRESS_BAR_POS, TDN_BUTTON_CLICKED, TDN_CREATED, TDN_TIMER, TaskDialogIndirect,
};
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
use windows::Win32::UI::Shell::{IShellLinkW, ShellLink};
use windows::Win32::UI::WindowsAndMessaging::{MB_ICONINFORMATION, MB_OK, MessageBoxW, SendMessageW};
use windows::core::{BOOL, Interface, PCWSTR};

use crate::windows::{SecurityDescriptor, wide};

/// Makes a shortcut at `link` to `target` with `arguments`, its icon the target's own, carrying `app_id` as its
/// AppUserModelID so Windows groups the app's windows and tray under it.
///
/// # Errors
/// If COM or the shortcut fails.
pub fn create_shortcut(link: &Path, target: &Path, arguments: &str, app_id: &str) -> io::Result<()> {
    if let Some(parent) = link.parent() {
        std::fs::create_dir_all(parent)?;
    }
    // SAFETY: no pointers; initialising COM again on this thread (or in another mode) is harmless here, so the
    // result is ignored and the calls below report any real failure.
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
    // SAFETY: ShellLink is the shell's CLSID for IShellLinkW; CoCreateInstance returns an owned interface.
    let shell: IShellLinkW =
        unsafe { CoCreateInstance(&ShellLink, None, CLSCTX_INPROC_SERVER) }.map_err(io::Error::other)?;
    let target_text = wide(target);
    let arguments = wide(arguments);
    // SAFETY: each string is NUL-terminated UTF-16 that outlives its call.
    unsafe {
        shell.SetPath(PCWSTR(target_text.as_ptr())).map_err(io::Error::other)?;
        shell.SetArguments(PCWSTR(arguments.as_ptr())).map_err(io::Error::other)?;
        shell.SetIconLocation(PCWSTR(target_text.as_ptr()), 0).map_err(io::Error::other)?;
    }
    let store: IPropertyStore = shell.cast().map_err(io::Error::other)?;
    let value = PROPVARIANT::from(app_id);
    let key = PKEY_AppUserModel_ID;
    // SAFETY: the key is the shell's own constant and `value` a valid PROPVARIANT, both alive for the call.
    unsafe {
        store.SetValue(&raw const key, &raw const value).map_err(io::Error::other)?;
        store.Commit().map_err(io::Error::other)?;
    }
    let file: IPersistFile = shell.cast().map_err(io::Error::other)?;
    let link = wide(link);
    // SAFETY: `link` is NUL-terminated UTF-16 that outlives the call.
    unsafe { file.Save(PCWSTR(link.as_ptr()), true) }.map_err(io::Error::other)
}

/// A plain message box with an OK button, for the one message that can't wait for a window: "Please update
/// Windows, then open this again".
pub fn message_box(title: &str, text: &str) {
    let title = wide(title);
    let text = wide(text);
    // SAFETY: both strings are NUL-terminated UTF-16 that outlive the modal call; no owner window.
    unsafe {
        MessageBoxW(None, PCWSTR(text.as_ptr()), PCWSTR(title.as_ptr()), MB_OK | MB_ICONINFORMATION);
    }
}

/// A question with buttons, in a task dialog. Returns the id of the button pressed, or `None` if it was closed.
///
/// # Errors
/// If the dialog can't be shown (no common controls 6 manifest).
pub fn ask(title: &str, heading: &str, text: &str, buttons: &[(i32, &str)]) -> io::Result<Option<i32>> {
    let title = wide(title);
    let heading = wide(heading);
    let text = wide(text);
    let labels: Vec<Vec<u16>> = buttons.iter().map(|(_, label)| wide(label)).collect();
    let buttons: Vec<TASKDIALOG_BUTTON> = buttons
        .iter()
        .zip(&labels)
        .map(|((id, _), label)| TASKDIALOG_BUTTON { nButtonID: *id, pszButtonText: PCWSTR(label.as_ptr()) })
        .collect();
    let config = TASKDIALOGCONFIG {
        cbSize: u32::try_from(size_of::<TASKDIALOGCONFIG>()).unwrap_or(0),
        dwFlags: TDF_ALLOW_DIALOG_CANCELLATION,
        pszWindowTitle: PCWSTR(title.as_ptr()),
        pszMainInstruction: PCWSTR(heading.as_ptr()),
        pszContent: PCWSTR(text.as_ptr()),
        cButtons: u32::try_from(buttons.len()).unwrap_or(0),
        pButtons: buttons.as_ptr(),
        ..Default::default()
    };
    let mut pressed = 0i32;
    // SAFETY: `config` and every string and button it points to outlive the modal call; `pressed` is a valid
    // out-pointer.
    unsafe { TaskDialogIndirect(&raw const config, Some(&raw mut pressed), None, None) }.map_err(io::Error::other)?;
    Ok((pressed != 2).then_some(pressed))
}

/// What the progress dialog shows; the work on another thread changes it, and the dialog reads it four times a
/// second.
#[derive(Debug, Clone, Default)]
pub struct Progress {
    /// The bold line.
    pub heading: String,
    /// The text under it.
    pub text: String,
    /// 0 to 100, or `None` for a moving bar.
    pub percent: Option<u32>,
    /// The work is over: the bar goes, and the button may close the dialog.
    pub done: bool,
}

const CLOSE: i32 = 1;

/// A task dialog with a progress bar and one Close button that works only once [`Progress::done`]. Returns when it's
/// closed.
///
/// # Errors
/// If the dialog can't be shown.
pub fn progress(title: &str, close_label: &str, view: &Arc<Mutex<Progress>>) -> io::Result<()> {
    let title = wide(title);
    let close = wide(close_label);
    let first = view.lock().unwrap_or_else(PoisonError::into_inner).clone();
    let heading = wide(&first.heading);
    let text = wide(&first.text);
    let buttons = [TASKDIALOG_BUTTON { nButtonID: CLOSE, pszButtonText: PCWSTR(close.as_ptr()) }];
    let config = TASKDIALOGCONFIG {
        cbSize: u32::try_from(size_of::<TASKDIALOGCONFIG>()).unwrap_or(0),
        dwFlags: TDF_CALLBACK_TIMER | TDF_SHOW_PROGRESS_BAR | TDF_SHOW_MARQUEE_PROGRESS_BAR,
        pszWindowTitle: PCWSTR(title.as_ptr()),
        pszMainInstruction: PCWSTR(heading.as_ptr()),
        pszContent: PCWSTR(text.as_ptr()),
        cButtons: 1,
        pButtons: buttons.as_ptr(),
        pfCallback: Some(progress_callback),
        lpCallbackData: Arc::as_ptr(view) as isize,
        ..Default::default()
    };
    // SAFETY: `config`, its strings and buttons, and the `view` its callback data points to all outlive the modal
    // call.
    unsafe { TaskDialogIndirect(&raw const config, None, None, None) }.map_err(io::Error::other)
}

/// Keeps the last text shown, so the dialog is told only of changes.
struct Shown {
    heading: String,
    text: String,
    done: bool,
}

thread_local! {
    static SHOWN: std::cell::RefCell<Option<Shown>> = const { std::cell::RefCell::new(None) };
}

extern "system" fn progress_callback(
    hwnd: HWND,
    notification: TASKDIALOG_NOTIFICATIONS,
    wparam: WPARAM,
    _lparam: LPARAM,
    data: isize,
) -> windows::core::HRESULT {
    // SAFETY: `data` is the `Arc<Mutex<Progress>>` that `progress` passed, alive for the whole modal call.
    let view = unsafe { &*(data as *const Mutex<Progress>) };
    let now = view.lock().unwrap_or_else(PoisonError::into_inner).clone();
    let send = |message: u32, wparam: usize, lparam: isize| {
        // SAFETY: `hwnd` is the live dialog this callback belongs to; the messages are task-dialog messages with
        // valid arguments.
        unsafe { SendMessageW(hwnd, message, Some(WPARAM(wparam)), Some(LPARAM(lparam))) }
    };
    match notification {
        TDN_CREATED => {
            send(TDM_SET_PROGRESS_BAR_MARQUEE.0 as u32, 1, 0);
            send(TDM_ENABLE_BUTTON.0 as u32, CLOSE as usize, 0);
            S_OK
        }
        TDN_TIMER => {
            SHOWN.with(|shown| {
                let mut shown = shown.borrow_mut();
                let changed =
                    shown.as_ref().is_none_or(|s| s.heading != now.heading || s.text != now.text || s.done != now.done);
                if changed {
                    let heading = wide(&now.heading);
                    let text = wide(&now.text);
                    send(TDM_SET_ELEMENT_TEXT.0 as u32, TDE_MAIN_INSTRUCTION.0 as usize, heading.as_ptr() as isize);
                    send(TDM_SET_ELEMENT_TEXT.0 as u32, TDE_CONTENT.0 as usize, text.as_ptr() as isize);
                    *shown = Some(Shown { heading: now.heading.clone(), text: now.text.clone(), done: now.done });
                }
            });
            match (now.done, now.percent) {
                (true, _) => {
                    send(TDM_SET_MARQUEE_PROGRESS_BAR.0 as u32, 0, 0);
                    send(TDM_SET_PROGRESS_BAR_POS.0 as u32, 100, 0);
                    send(TDM_ENABLE_BUTTON.0 as u32, CLOSE as usize, 1);
                }
                (false, Some(percent)) => {
                    send(TDM_SET_MARQUEE_PROGRESS_BAR.0 as u32, 0, 0);
                    send(TDM_SET_PROGRESS_BAR_POS.0 as u32, percent.min(100) as usize, 0);
                }
                (false, None) => {
                    send(TDM_SET_MARQUEE_PROGRESS_BAR.0 as u32, 1, 0);
                }
            }
            S_OK
        }
        // Closing before the work is over is refused: it can't be stopped half-way.
        TDN_BUTTON_CLICKED if !now.done && i32::try_from(wparam.0) == Ok(CLOSE) => S_FALSE,
        _ => S_OK,
    }
}

/// Creates one instance of the local channel's named pipe, whose access list allows only the signed-in owner and
/// SYSTEM, and which refuses clients from other machines.
///
/// # Errors
/// If the SID can't be read or the pipe can't be made (`first` and another process already holds the name).
pub fn create_pipe_server(name: &str, first: bool) -> io::Result<tokio::net::windows::named_pipe::NamedPipeServer> {
    let sid = crate::current_user_sid()?;
    let descriptor = SecurityDescriptor::from_sddl(&crate::pipe_sddl(&sid))?;
    let mut attributes = SECURITY_ATTRIBUTES {
        nLength: u32::try_from(size_of::<SECURITY_ATTRIBUTES>()).unwrap_or(24),
        lpSecurityDescriptor: descriptor.0.0,
        bInheritHandle: BOOL::from(false),
    };
    let mut options = tokio::net::windows::named_pipe::ServerOptions::new();
    options.first_pipe_instance(first).reject_remote_clients(true);
    // SAFETY: `attributes` is a valid SECURITY_ATTRIBUTES whose descriptor outlives the call, as
    // create_with_security_attributes_raw requires.
    unsafe { options.create_with_security_attributes_raw(name, (&raw mut attributes).cast::<c_void>()) }
}
