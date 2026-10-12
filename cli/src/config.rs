//! Where the CLI talks to and keeps its files: the API's address, and the config folder.

use std::path::PathBuf;

use reqwest::Url;

use crate::brand::BIN_NAME;
use crate::error::{CliError, Result};

/// The local stack (docs/runbooks/local-stack.md) until the product has an address: production sets
/// `IDLEO_API_URL` at deployment.
pub const DEFAULT_API_URL: &str = "http://localhost:8080";

/// The API's address: `--api`, else `IDLEO_API_URL`, else the local stack.
///
/// # Errors
/// If the address isn't an http or https URL.
pub fn api_url(flag: Option<&str>, env: Option<&str>) -> Result<Url> {
    let text = flag.or(env).filter(|text| !text.trim().is_empty()).unwrap_or(DEFAULT_API_URL);
    let url =
        Url::parse(text.trim()).map_err(|e| CliError::usage(format!("the API address {text:?} isn't a URL: {e}")))?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err(CliError::usage(format!("the API address {text:?} must start with http:// or https://")));
    }
    Ok(url)
}

/// The folder for the CLI's own files: `IDLEO_CONFIG_DIR`, else the operating system's usual place for them.
///
/// # Errors
/// If the system has no usual place and none was given.
pub fn config_dir(env: Option<&str>) -> Result<PathBuf> {
    if let Some(dir) = env.filter(|dir| !dir.trim().is_empty()) {
        return Ok(PathBuf::from(dir));
    }
    let var = |name: &str| std::env::var_os(name).filter(|value| !value.is_empty()).map(PathBuf::from);
    system_dir(var("HOME"), var("XDG_CONFIG_HOME"), var("APPDATA"))
        .ok_or_else(|| CliError::failed("this computer has no config folder; set IDLEO_CONFIG_DIR to one"))
}

/// Windows keeps it under `%APPDATA%`, macOS in the user's Library, and everything else under `$XDG_CONFIG_HOME` or
/// `~/.config`.
fn system_dir(home: Option<PathBuf>, xdg: Option<PathBuf>, appdata: Option<PathBuf>) -> Option<PathBuf> {
    let base = if cfg!(windows) {
        appdata?
    } else if cfg!(target_os = "macos") {
        home?.join("Library/Application Support")
    } else {
        xdg.or_else(|| home.map(|home| home.join(".config")))?
    };
    Some(base.join(BIN_NAME))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_flag_beats_the_environment_beats_the_local_stack() {
        assert_eq!(
            api_url(Some("https://a.example"), Some("https://b.example")).unwrap().as_str(),
            "https://a.example/"
        );
        assert_eq!(api_url(None, Some("https://b.example")).unwrap().as_str(), "https://b.example/");
        assert_eq!(api_url(None, None).unwrap().as_str(), "http://localhost:8080/");
        assert_eq!(api_url(None, Some("  ")).unwrap().as_str(), "http://localhost:8080/");
    }

    #[test]
    fn an_address_that_isnt_http_is_refused() {
        for bad in ["localhost:8080", "ftp://x.example", "not a url", "file:///etc"] {
            assert!(api_url(Some(bad), None).is_err(), "{bad}");
        }
    }

    #[test]
    fn the_config_folder_follows_the_system() {
        let home = Some(PathBuf::from("/home/asha"));
        let found = system_dir(home.clone(), Some(PathBuf::from("/xdg")), Some(PathBuf::from("C:/appdata")));
        if cfg!(windows) {
            assert_eq!(found, Some(PathBuf::from("C:/appdata/idleo")));
        } else if cfg!(target_os = "macos") {
            assert_eq!(found, Some(PathBuf::from("/home/asha/Library/Application Support/idleo")));
        } else {
            assert_eq!(found, Some(PathBuf::from("/xdg/idleo")));
            assert_eq!(system_dir(home, None, None), Some(PathBuf::from("/home/asha/.config/idleo")));
        }
        assert_eq!(system_dir(None, None, None), None);
    }

    #[test]
    fn the_config_folder_can_be_given() {
        assert_eq!(config_dir(Some("/tmp/idleo-x")).unwrap(), PathBuf::from("/tmp/idleo-x"));
    }
}
