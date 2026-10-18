//! `HKEY_CURRENT_USER` only (ADR 0033): the app writes its Run, RunOnce and Uninstall entries there, so it never
//! needs admin rights.

use std::ffi::c_void;
use std::io;

use windows::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_SUCCESS, WIN32_ERROR};
use windows::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_DWORD, REG_OPTION_NON_VOLATILE, REG_SZ, RRF_RT_REG_SZ, RegCloseKey,
    RegCreateKeyExW, RegDeleteKeyValueW, RegDeleteTreeW, RegGetValueW, RegSetValueExW,
};
use windows::core::PCWSTR;

use crate::windows::wide;

fn check(status: WIN32_ERROR) -> io::Result<()> {
    if status == ERROR_SUCCESS {
        Ok(())
    } else {
        Err(io::Error::from_raw_os_error(i32::try_from(status.0).unwrap_or(i32::MAX)))
    }
}

/// A key opened for writing, closed when dropped.
struct Key(HKEY);

impl Key {
    fn create(path: &str) -> io::Result<Self> {
        let path = wide(path);
        let mut key = HKEY::default();
        // SAFETY: `path` is NUL-terminated UTF-16 that outlives the call; `key` is a valid out-pointer.
        check(unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                PCWSTR(path.as_ptr()),
                None,
                PCWSTR::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_SET_VALUE,
                None,
                &raw mut key,
                None,
            )
        })?;
        Ok(Self(key))
    }
}

impl Drop for Key {
    fn drop(&mut self) {
        // SAFETY: the key came from RegCreateKeyExW and is closed exactly once.
        unsafe {
            let _ = RegCloseKey(self.0);
        }
    }
}

/// Sets a `REG_SZ` value, creating the key.
///
/// # Errors
/// If it can't be written.
pub fn set_string(path: &str, name: &str, value: &str) -> io::Result<()> {
    let key = Key::create(path)?;
    let name = wide(name);
    let data: Vec<u8> = wide(value).iter().flat_map(|unit| unit.to_le_bytes()).collect();
    // SAFETY: the key is open for writing; `name` is NUL-terminated UTF-16; `data` is the value's UTF-16 bytes with
    // its terminating NUL, as REG_SZ requires.
    check(unsafe { RegSetValueExW(key.0, PCWSTR(name.as_ptr()), None, REG_SZ, Some(&data)) })
}

/// Sets a `REG_DWORD` value, creating the key.
///
/// # Errors
/// If it can't be written.
pub fn set_dword(path: &str, name: &str, value: u32) -> io::Result<()> {
    let key = Key::create(path)?;
    let name = wide(name);
    let data = value.to_le_bytes();
    // SAFETY: the key is open for writing; `name` is NUL-terminated UTF-16; `data` is the four bytes REG_DWORD needs.
    check(unsafe { RegSetValueExW(key.0, PCWSTR(name.as_ptr()), None, REG_DWORD, Some(&data)) })
}

/// Reads a `REG_SZ` value; `None` if it, or its key, isn't there.
///
/// # Errors
/// If it exists and can't be read.
pub fn get_string(path: &str, name: &str) -> io::Result<Option<String>> {
    let path = wide(path);
    let name = wide(name);
    let mut size = 0u32;
    // SAFETY: asking for the size only: no buffer, `size` is a valid out-pointer.
    let status = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            PCWSTR(path.as_ptr()),
            PCWSTR(name.as_ptr()),
            RRF_RT_REG_SZ,
            None,
            None,
            Some(&raw mut size),
        )
    };
    if status == ERROR_FILE_NOT_FOUND {
        return Ok(None);
    }
    check(status)?;
    let mut buffer = vec![0u16; (size as usize).div_ceil(2) + 1];
    let mut size = u32::try_from(buffer.len() * 2).map_err(io::Error::other)?;
    // SAFETY: `buffer` is writable for `size` bytes; RegGetValueW writes at most that many, NUL-terminated.
    let status = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            PCWSTR(path.as_ptr()),
            PCWSTR(name.as_ptr()),
            RRF_RT_REG_SZ,
            None,
            Some(buffer.as_mut_ptr().cast::<c_void>()),
            Some(&raw mut size),
        )
    };
    if status == ERROR_FILE_NOT_FOUND {
        return Ok(None);
    }
    check(status)?;
    let length = buffer.iter().position(|unit| *unit == 0).unwrap_or(buffer.len());
    Ok(Some(String::from_utf16_lossy(&buffer[..length])))
}

/// Deletes a value; nothing if it, or its key, isn't there.
///
/// # Errors
/// If it exists and can't be deleted.
pub fn delete_value(path: &str, name: &str) -> io::Result<()> {
    let path = wide(path);
    let name = wide(name);
    // SAFETY: both strings are NUL-terminated UTF-16 that outlive the call.
    let status = unsafe { RegDeleteKeyValueW(HKEY_CURRENT_USER, PCWSTR(path.as_ptr()), PCWSTR(name.as_ptr())) };
    if status == ERROR_FILE_NOT_FOUND { Ok(()) } else { check(status) }
}

/// Deletes a key and everything under it; nothing if it isn't there.
///
/// # Errors
/// If it exists and can't be deleted.
pub fn delete_tree(path: &str) -> io::Result<()> {
    let path = wide(path);
    // SAFETY: `path` is NUL-terminated UTF-16 that outlives the call.
    let status = unsafe { RegDeleteTreeW(HKEY_CURRENT_USER, PCWSTR(path.as_ptr())) };
    if status == ERROR_FILE_NOT_FOUND { Ok(()) } else { check(status) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn values_round_trip_under_a_scratch_key() {
        let key = format!(r"Software\IdleO-test-{}", std::process::id());
        set_string(&key, "Name", "IdleO").unwrap();
        set_dword(&key, "Size", 42).unwrap();
        assert_eq!(get_string(&key, "Name").unwrap().as_deref(), Some("IdleO"));
        delete_value(&key, "Name").unwrap();
        assert_eq!(get_string(&key, "Name").unwrap(), None);
        delete_tree(&key).unwrap();
        delete_tree(&key).unwrap();
        assert_eq!(get_string(&key, "Size").unwrap(), None);
    }
}
