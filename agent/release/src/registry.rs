//! The signed-in user's registry (`HKEY_CURRENT_USER`), behind [`Registry`] (ADR 0033): the Run entry, the RunOnce
//! entry across a restart, and the Uninstall entry Settings → Apps shows. Never `HKEY_LOCAL_MACHINE`: nothing needs
//! admin rights. The tests on the Mac use [`MemoryRegistry`].

use std::collections::BTreeMap;
use std::io;
use std::sync::{Mutex, PoisonError};

/// A registry value we write.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Value {
    /// `REG_SZ`.
    Text(String),
    /// `REG_DWORD`.
    Number(u32),
}

/// `HKEY_CURRENT_USER`, or a stand-in for it.
pub trait Registry: Send + Sync {
    /// Sets `name` under `key`, creating the key.
    ///
    /// # Errors
    /// If it can't be written.
    fn set(&self, key: &str, name: &str, value: &Value) -> io::Result<()>;

    /// Reads a text value; `None` if it isn't there.
    fn get(&self, key: &str, name: &str) -> Option<String>;

    /// Deletes `name` under `key`; nothing if it isn't there.
    ///
    /// # Errors
    /// If it exists and can't be deleted.
    fn delete_value(&self, key: &str, name: &str) -> io::Result<()>;

    /// Deletes `key` and everything under it; nothing if it isn't there.
    ///
    /// # Errors
    /// If it exists and can't be deleted.
    fn delete_key(&self, key: &str) -> io::Result<()>;
}

/// An in-memory registry for tests: `key` → `name` → value.
#[derive(Debug, Default)]
pub struct MemoryRegistry(Mutex<BTreeMap<String, BTreeMap<String, Value>>>);

impl MemoryRegistry {
    /// Everything in it, for assertions.
    #[must_use]
    pub fn snapshot(&self) -> BTreeMap<String, BTreeMap<String, Value>> {
        self.0.lock().unwrap_or_else(PoisonError::into_inner).clone()
    }

    /// Whether it holds nothing at all.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.0.lock().unwrap_or_else(PoisonError::into_inner).values().all(BTreeMap::is_empty)
    }
}

impl Registry for MemoryRegistry {
    fn set(&self, key: &str, name: &str, value: &Value) -> io::Result<()> {
        self.0
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .entry(key.to_owned())
            .or_default()
            .insert(name.to_owned(), value.clone());
        Ok(())
    }

    fn get(&self, key: &str, name: &str) -> Option<String> {
        match self.0.lock().unwrap_or_else(PoisonError::into_inner).get(key)?.get(name)? {
            Value::Text(text) => Some(text.clone()),
            Value::Number(number) => Some(number.to_string()),
        }
    }

    fn delete_value(&self, key: &str, name: &str) -> io::Result<()> {
        if let Some(values) = self.0.lock().unwrap_or_else(PoisonError::into_inner).get_mut(key) {
            values.remove(name);
        }
        Ok(())
    }

    fn delete_key(&self, key: &str) -> io::Result<()> {
        let prefix = format!("{key}\\");
        self.0.lock().unwrap_or_else(PoisonError::into_inner).retain(|k, _| k != key && !k.starts_with(&prefix));
        Ok(())
    }
}

/// The real `HKEY_CURRENT_USER`.
#[cfg(windows)]
#[derive(Debug, Default, Clone, Copy)]
pub struct UserRegistry;

#[cfg(windows)]
impl Registry for UserRegistry {
    fn set(&self, key: &str, name: &str, value: &Value) -> io::Result<()> {
        match value {
            Value::Text(text) => agent_windows::registry::set_string(key, name, text),
            Value::Number(number) => agent_windows::registry::set_dword(key, name, *number),
        }
    }

    fn get(&self, key: &str, name: &str) -> Option<String> {
        agent_windows::registry::get_string(key, name).ok().flatten()
    }

    fn delete_value(&self, key: &str, name: &str) -> io::Result<()> {
        agent_windows::registry::delete_value(key, name)
    }

    fn delete_key(&self, key: &str) -> io::Result<()> {
        agent_windows::registry::delete_tree(key)
    }
}
