//! The signed-in owner's session: who they are (their SID), whether a game or full-screen app is open, whether the
//! machine runs on battery, and whether firmware virtualization is on. The agent runs as the owner, never as a
//! service, so all of these see the owner's own desktop (ADR 0033).

use std::ffi::c_void;
use std::io;

use windows::Win32::Foundation::{CloseHandle, HANDLE, HLOCAL, LocalFree};
use windows::Win32::Security::Authorization::ConvertSidToStringSidW;
use windows::Win32::Security::{GetTokenInformation, TOKEN_QUERY, TOKEN_USER, TokenUser};
use windows::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
use windows::Win32::System::Threading::{
    GetCurrentProcess, IsProcessorFeaturePresent, OpenProcessToken, PF_VIRT_FIRMWARE_ENABLED,
};
use windows::Win32::UI::Shell::SHQueryUserNotificationState;
use windows::core::PWSTR;

/// A handle closed when dropped.
struct Handle(HANDLE);

impl Drop for Handle {
    fn drop(&mut self) {
        // SAFETY: the handle came from OpenProcessToken, is valid, and is closed exactly once.
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

/// The signed-in user's SID, for example `S-1-5-21-…-1001`: the one account, beside SYSTEM, that may open the agent's
/// key file and its local channel.
///
/// # Errors
/// If Windows can't say.
pub fn current_user_sid() -> io::Result<String> {
    let mut token = HANDLE::default();
    // SAFETY: GetCurrentProcess returns a pseudo-handle that needs no closing; `token` is a valid out-pointer.
    unsafe { OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &raw mut token) }.map_err(io::Error::other)?;
    let token = Handle(token);
    let mut needed = 0u32;
    // SAFETY: asking for the size only: no buffer, and `needed` is a valid out-pointer. It "fails" with
    // ERROR_INSUFFICIENT_BUFFER by design, so its result is ignored.
    unsafe {
        let _ = GetTokenInformation(token.0, TokenUser, None, 0, &raw mut needed);
    }
    // u64 words keep the buffer aligned for TOKEN_USER, whose first field is a pointer.
    let mut buffer = vec![0u64; (needed as usize).div_ceil(8).max(1)];
    let length = u32::try_from(buffer.len() * 8).map_err(io::Error::other)?;
    // SAFETY: `buffer` is writable for `length` bytes and aligned for TOKEN_USER; Windows writes at most that many.
    unsafe {
        GetTokenInformation(token.0, TokenUser, Some(buffer.as_mut_ptr().cast::<c_void>()), length, &raw mut needed)
    }
    .map_err(io::Error::other)?;
    // SAFETY: on success the buffer starts with a TOKEN_USER whose SID points into the same buffer, alive here.
    let sid = unsafe { (*buffer.as_ptr().cast::<TOKEN_USER>()).User.Sid };
    let mut text = PWSTR::null();
    // SAFETY: `sid` is a valid SID; Windows allocates `text` with LocalAlloc.
    unsafe { ConvertSidToStringSidW(sid, &raw mut text) }.map_err(io::Error::other)?;
    // SAFETY: on success `text` is a NUL-terminated UTF-16 string.
    let result = unsafe { text.to_string() };
    // SAFETY: `text` came from a LocalAlloc in Windows and is freed exactly once.
    unsafe {
        LocalFree(Some(HLOCAL(text.0.cast())));
    }
    result.map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "the SID isn't valid UTF-16"))
}

/// `SHQueryUserNotificationState`'s answer as a number (2 busy, 3 a full-screen Direct3D app, 4 presentation mode,
/// …): the agent decides what counts as a game.
///
/// # Errors
/// If Windows can't say.
pub fn user_notification_state() -> io::Result<i32> {
    // SAFETY: takes no pointers and has no preconditions.
    let state = unsafe { SHQueryUserNotificationState() }.map_err(io::Error::other)?;
    Ok(state.0)
}

/// `SYSTEM_POWER_STATUS.ACLineStatus`: 0 on battery, 1 plugged in, 255 unknown.
///
/// # Errors
/// If Windows can't say.
pub fn ac_line_status() -> io::Result<u8> {
    let mut status = SYSTEM_POWER_STATUS::default();
    // SAFETY: `status` is a valid, writable SYSTEM_POWER_STATUS.
    unsafe { GetSystemPowerStatus(&raw mut status) }.map_err(io::Error::other)?;
    Ok(status.ACLineStatus)
}

/// Whether virtualization is turned on in the firmware, which WSL 2 needs. With a hypervisor already running (Hyper-V,
/// or WSL 2 itself), Windows reports the feature as absent, so a hypervisor counts as on.
#[must_use]
pub fn virtualization_enabled() -> bool {
    // SAFETY: takes a constant and has no preconditions.
    let firmware = unsafe { IsProcessorFeaturePresent(PF_VIRT_FIRMWARE_ENABLED) }.as_bool();
    firmware || hypervisor_present()
}

#[cfg(target_arch = "x86_64")]
fn hypervisor_present() -> bool {
    // CPUID leaf 1 exists on every x86-64 processor; bit 31 of ECX is "a hypervisor is present".
    let leaf = std::arch::x86_64::__cpuid(1);
    leaf.ecx & (1 << 31) != 0
}

#[cfg(not(target_arch = "x86_64"))]
fn hypervisor_present() -> bool {
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_session_reads() {
        assert!(current_user_sid().unwrap().starts_with("S-1-"));
        user_notification_state().unwrap();
        assert!(matches!(ac_line_status().unwrap(), 0 | 1 | 255));
        let _ = virtualization_enabled();
    }
}
