use std::ffi::{OsStr, c_void};
use std::fs::File;
use std::io;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::{FromRawHandle, RawHandle};
use std::path::Path;
use std::time::Duration;

use windows::Win32::Foundation::{ERROR_SUCCESS, HLOCAL, LocalFree};
use windows::Win32::Security::Authorization::{
    ConvertSecurityDescriptorToStringSecurityDescriptorW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
    GetNamedSecurityInfoW, SDDL_REVISION_1, SE_FILE_OBJECT,
};
use windows::Win32::Security::{DACL_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
use windows::Win32::Storage::FileSystem::{
    CREATE_NEW, CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_GENERIC_WRITE, FILE_SHARE_NONE, GetDiskFreeSpaceExW,
};
use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
use windows::Win32::System::Registry::{HKEY_LOCAL_MACHINE, RRF_RT_REG_SZ, RegGetValueW};
use windows::Win32::System::SystemInformation::GetTickCount;
use windows::Win32::UI::Input::KeyboardAndMouse::{GetLastInputInfo, LASTINPUTINFO};
use windows::core::{PCWSTR, PWSTR};

use crate::PRIVATE_FILE_SDDL;

fn wide(text: impl AsRef<OsStr>) -> Vec<u16> {
    text.as_ref().encode_wide().chain(std::iter::once(0)).collect()
}

/// Bytes this user may still write on the volume holding `path`, from `GetDiskFreeSpaceExW`, which counts quotas.
///
/// # Errors
/// If Windows can't say, for example when `path` doesn't exist.
pub fn free_disk_space(path: &Path) -> io::Result<u64> {
    let name = wide(path);
    let mut available = 0u64;
    // SAFETY: `name` is a NUL-terminated UTF-16 string that outlives the call, and `available` is a valid, writable
    // u64; the two other outputs are optional and left out.
    unsafe { GetDiskFreeSpaceExW(PCWSTR(name.as_ptr()), Some(&raw mut available), None, None) }
        .map_err(io::Error::other)?;
    Ok(available)
}

/// Time since the last keyboard or mouse input in this session.
///
/// # Errors
/// If Windows can't say.
pub fn idle_time() -> io::Result<Duration> {
    let mut info = LASTINPUTINFO { cbSize: u32::try_from(size_of::<LASTINPUTINFO>()).unwrap_or(8), dwTime: 0 };
    // SAFETY: `info` is a valid, writable LASTINPUTINFO with cbSize set, as the API requires.
    let ok = unsafe { GetLastInputInfo(&raw mut info) };
    if !ok.as_bool() {
        return Err(io::Error::last_os_error());
    }
    // SAFETY: GetTickCount takes no arguments and has no preconditions.
    let now = unsafe { GetTickCount() };
    Ok(idle_since(now, info.dwTime))
}

/// Milliseconds between two 32-bit tick counts, which wrap every 49.7 days.
fn idle_since(now: u32, last_input: u32) -> Duration {
    Duration::from_millis(u64::from(now.wrapping_sub(last_input)))
}

/// Time since Windows started, from `QueryPerformanceCounter`. Microsoft documents that it counts
/// "including the time when the machine was in a sleep state such as standby, hibernate, or connected
/// standby", which is what a session lease needs (ADR 0007). Never `QueryUnbiasedInterruptTime`,
/// which leaves sleep out.
///
/// # Errors
/// If Windows can't say.
pub fn performance_counter() -> io::Result<Duration> {
    let mut frequency = 0i64;
    let mut count = 0i64;
    // SAFETY: both pointers are to valid, writable i64s, as the APIs require; they write one value.
    unsafe {
        QueryPerformanceFrequency(&raw mut frequency).map_err(io::Error::other)?;
        QueryPerformanceCounter(&raw mut count).map_err(io::Error::other)?;
    }
    ticks(count, frequency).ok_or_else(|| io::Error::other("the performance counter frequency is zero"))
}

/// `count` ticks at `frequency` ticks a second, without overflow for any 64-bit count.
fn ticks(count: i64, frequency: i64) -> Option<Duration> {
    let count = u64::try_from(count).ok()?;
    let frequency = u64::try_from(frequency).ok().filter(|frequency| *frequency > 0)?;
    let nanos = u128::from(count % frequency) * 1_000_000_000 / u128::from(frequency);
    Some(Duration::new(count / frequency, u32::try_from(nanos).ok()?))
}

/// The Windows build number, for example 26100.
///
/// # Errors
/// If the registry value can't be read or isn't a number.
pub fn windows_build() -> io::Result<u32> {
    let key = wide(r"SOFTWARE\Microsoft\Windows NT\CurrentVersion");
    let value = wide("CurrentBuildNumber");
    let mut buffer = [0u16; 32];
    let mut size = u32::try_from(size_of_val(&buffer)).unwrap_or(64);
    // SAFETY: the key and value names are NUL-terminated UTF-16; `buffer` is writable for `size`
    // bytes, and RegGetValueW writes at most that many (NUL-terminated for RRF_RT_REG_SZ).
    let status = unsafe {
        RegGetValueW(
            HKEY_LOCAL_MACHINE,
            PCWSTR(key.as_ptr()),
            PCWSTR(value.as_ptr()),
            RRF_RT_REG_SZ,
            None,
            Some(buffer.as_mut_ptr().cast::<c_void>()),
            Some(&raw mut size),
        )
    };
    if status != ERROR_SUCCESS {
        return Err(io::Error::from_raw_os_error(i32::try_from(status.0).unwrap_or(i32::MAX)));
    }
    let length = buffer.iter().position(|c| *c == 0).unwrap_or(buffer.len());
    String::from_utf16_lossy(&buffer[..length])
        .trim()
        .parse()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "CurrentBuildNumber isn't a number"))
}

/// A security descriptor from SDDL, freed with `LocalFree` when dropped.
struct SecurityDescriptor(PSECURITY_DESCRIPTOR);

impl SecurityDescriptor {
    fn from_sddl(sddl: &str) -> io::Result<Self> {
        let sddl = wide(sddl);
        let mut descriptor = PSECURITY_DESCRIPTOR::default();
        // SAFETY: `sddl` is NUL-terminated UTF-16 and `descriptor` is a valid out-pointer; on
        // success Windows allocates the descriptor with LocalAlloc, freed in Drop.
        unsafe {
            ConvertStringSecurityDescriptorToSecurityDescriptorW(
                PCWSTR(sddl.as_ptr()),
                SDDL_REVISION_1,
                &raw mut descriptor,
                None,
            )
        }
        .map_err(io::Error::from)?;
        Ok(Self(descriptor))
    }
}

impl Drop for SecurityDescriptor {
    fn drop(&mut self) {
        // SAFETY: the pointer came from a LocalAlloc in Windows and is freed exactly once.
        unsafe {
            LocalFree(Some(HLOCAL(self.0.0)));
        }
    }
}

/// Creates a new file that only SYSTEM and Administrators can open, for writing. The access list
/// is part of the creation, so no other account ever has a moment to open the file.
///
/// # Errors
/// If the file exists or can't be created.
pub fn create_private_file(path: &Path) -> io::Result<File> {
    let descriptor = SecurityDescriptor::from_sddl(PRIVATE_FILE_SDDL)?;
    let attributes = SECURITY_ATTRIBUTES {
        nLength: u32::try_from(size_of::<SECURITY_ATTRIBUTES>()).unwrap_or(24),
        lpSecurityDescriptor: descriptor.0.0,
        bInheritHandle: false.into(),
    };
    let name = wide(path.as_os_str());
    // SAFETY: `name` is NUL-terminated UTF-16 and `attributes` points to a valid descriptor that
    // outlives the call.
    let handle = unsafe {
        CreateFileW(
            PCWSTR(name.as_ptr()),
            FILE_GENERIC_WRITE.0,
            FILE_SHARE_NONE,
            Some(&raw const attributes),
            CREATE_NEW,
            FILE_ATTRIBUTE_NORMAL,
            None,
        )
    }
    .map_err(io::Error::from)?;
    // SAFETY: CreateFileW returned a new, valid handle that nothing else owns; File takes
    // ownership and closes it.
    Ok(unsafe { File::from_raw_handle(handle.0 as RawHandle) })
}

/// A file's access list in SDDL, for example `D:P(A;;FA;;;SY)(A;;FA;;;BA)`.
///
/// # Errors
/// If the file's security can't be read.
pub fn file_access_sddl(path: &Path) -> io::Result<String> {
    let name = wide(path.as_os_str());
    let mut descriptor = PSECURITY_DESCRIPTOR::default();
    // SAFETY: `name` is NUL-terminated UTF-16; only the descriptor out-pointer is requested, and
    // Windows allocates it with LocalAlloc, freed by the wrapper below.
    let status = unsafe {
        GetNamedSecurityInfoW(
            PCWSTR(name.as_ptr()),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION,
            None,
            None,
            None,
            None,
            &raw mut descriptor,
        )
    };
    if status != ERROR_SUCCESS {
        return Err(io::Error::from_raw_os_error(i32::try_from(status.0).unwrap_or(i32::MAX)));
    }
    let descriptor = SecurityDescriptor(descriptor);
    let mut text = PWSTR::null();
    // SAFETY: `descriptor` is valid; Windows allocates `text` with LocalAlloc.
    unsafe {
        ConvertSecurityDescriptorToStringSecurityDescriptorW(
            descriptor.0,
            SDDL_REVISION_1,
            DACL_SECURITY_INFORMATION,
            &raw mut text,
            None,
        )
    }
    .map_err(io::Error::from)?;
    // SAFETY: on success `text` is a NUL-terminated UTF-16 string.
    let sddl = unsafe { text.to_string() };
    // SAFETY: `text` came from a LocalAlloc in Windows and is freed exactly once.
    unsafe {
        LocalFree(Some(HLOCAL(text.0.cast())));
    }
    sddl.map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "the access list isn't valid UTF-16"))
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;

    #[test]
    fn idle_time_handles_the_tick_count_wrapping() {
        assert_eq!(idle_since(5_000, 2_000), Duration::from_secs(3));
        assert_eq!(idle_since(1_000, u32::MAX - 999), Duration::from_secs(2));
    }

    #[test]
    fn ticks_convert_exactly() {
        assert_eq!(ticks(30_000_000, 10_000_000), Some(Duration::from_secs(3)));
        assert_eq!(ticks(15_000_001, 10_000_000), Some(Duration::new(1, 500_000_100)));
        assert_eq!(ticks(i64::MAX, 10_000_000).map(|d| d.as_secs()), Some(922_337_203_685));
        assert_eq!(ticks(1, 0), None);
        assert_eq!(ticks(-1, 10_000_000), None);
    }

    #[test]
    fn the_performance_counter_moves_forward() {
        let first = performance_counter().unwrap();
        std::thread::sleep(Duration::from_millis(20));
        assert!(performance_counter().unwrap() >= first + Duration::from_millis(15));
    }

    #[test]
    fn idle_time_reads() {
        idle_time().unwrap();
    }

    #[test]
    fn the_build_number_reads() {
        assert!(windows_build().unwrap() >= 10_000);
    }

    #[test]
    fn a_private_file_allows_only_system_and_administrators() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("host.key");
        let mut file = create_private_file(&path).unwrap();
        file.write_all(b"secret").unwrap();
        drop(file);

        assert_eq!(file_access_sddl(&path).unwrap(), PRIVATE_FILE_SDDL);
        assert!(create_private_file(&path).is_err(), "an existing file is never replaced");
    }
}
