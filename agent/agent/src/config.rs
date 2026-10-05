//! `agent.toml`: where the control plane is, which root to trust, and where state lives.

use std::fmt;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Deserialize;

/// The config file's name inside the default directory.
pub const CONFIG_FILE: &str = "agent.toml";
/// The root certificate's default file name, next to the config file.
pub const DEFAULT_CA_ROOT: &str = "ca-root.pem";
const DEFAULT_WELCOME_TIMEOUT: Duration = Duration::from_secs(10);

/// The loaded configuration, with every path made absolute.
#[derive(Debug, Clone)]
pub struct Config {
    /// Where the agent dials.
    pub control_plane: ControlPlane,
    /// Our root certificate: the only trust anchor for the control plane's certificate.
    pub ca_root: PathBuf,
    /// Where `host.key`, `host.crt` and `host.json` live.
    pub data_dir: PathBuf,
    /// How log lines are written.
    pub log_format: LogFormat,
    /// How long to wait for `Welcome` after sending `Hello`.
    pub welcome_timeout: Duration,
}

/// The control plane's agent endpoint.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ControlPlane {
    /// Hostname, also the name its certificate must carry.
    pub host: String,
    /// TCP port.
    pub port: u16,
}

/// Log output format.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogFormat {
    /// One JSON object per line, for log shippers.
    Json,
    /// Human-readable lines.
    #[default]
    Text,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct File {
    control_plane: ControlPlane,
    ca_root: Option<PathBuf>,
    data_dir: Option<PathBuf>,
    log_format: Option<LogFormat>,
    welcome_timeout_secs: Option<u64>,
}

/// Why the config couldn't be loaded.
#[derive(Debug)]
pub enum ConfigError {
    /// The file couldn't be read.
    Read(PathBuf, std::io::Error),
    /// The file isn't valid `agent.toml`.
    Parse(PathBuf, String),
    /// A value is out of range.
    Invalid(PathBuf, &'static str),
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Read(path, e) => write!(f, "can't read {}: {e}", path.display()),
            Self::Parse(path, e) => write!(f, "{} isn't a valid agent config: {e}", path.display()),
            Self::Invalid(path, what) => write!(f, "{}: {what}", path.display()),
        }
    }
}

impl std::error::Error for ConfigError {}

/// The default directory for the config file and the agent's state:
/// - Windows: `%ProgramData%\IdleO\agent`
/// - Linux: `/var/lib/idleo-agent`
/// - macOS: `~/Library/Application Support/IdleO/agent`, for development only
#[must_use]
pub fn default_dir() -> PathBuf {
    if cfg!(windows) {
        let program_data = std::env::var_os("ProgramData").unwrap_or_else(|| r"C:\ProgramData".into());
        PathBuf::from(program_data).join("IdleO").join("agent")
    } else if cfg!(target_os = "macos") {
        let home = std::env::var_os("HOME").unwrap_or_else(|| "/".into());
        PathBuf::from(home).join("Library/Application Support/IdleO/agent")
    } else {
        PathBuf::from("/var/lib/idleo-agent")
    }
}

/// The config file used when `--config` isn't given.
#[must_use]
pub fn default_path() -> PathBuf {
    default_dir().join(CONFIG_FILE)
}

impl Config {
    /// Loads a config file. Relative paths in it are relative to the file's own directory.
    ///
    /// # Errors
    /// If the file can't be read or parsed, or a value is out of range.
    pub fn load(path: &Path) -> Result<Self, ConfigError> {
        let text = std::fs::read_to_string(path).map_err(|e| ConfigError::Read(path.to_owned(), e))?;
        Self::parse(&text, path)
    }

    fn parse(text: &str, path: &Path) -> Result<Self, ConfigError> {
        let file: File =
            toml::from_str(text).map_err(|e| ConfigError::Parse(path.to_owned(), e.message().to_owned()))?;
        let invalid = |what| ConfigError::Invalid(path.to_owned(), what);
        if file.control_plane.host.trim().is_empty() {
            return Err(invalid("control_plane.host is empty"));
        }
        if file.control_plane.port == 0 {
            return Err(invalid("control_plane.port is 0"));
        }
        let welcome_timeout = match file.welcome_timeout_secs {
            None => DEFAULT_WELCOME_TIMEOUT,
            Some(0) => return Err(invalid("welcome_timeout_secs is 0")),
            Some(secs) => Duration::from_secs(secs),
        };
        let base = path.parent().unwrap_or(Path::new("."));
        Ok(Self {
            control_plane: file.control_plane,
            ca_root: base.join(file.ca_root.unwrap_or_else(|| DEFAULT_CA_ROOT.into())),
            data_dir: file.data_dir.map_or_else(default_dir, |dir| base.join(dir)),
            log_format: file.log_format.unwrap_or_default(),
            welcome_timeout,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relative_paths_are_relative_to_the_config_file() {
        let config = Config::parse(
            "ca_root = \"root.pem\"\ndata_dir = \"state\"\nlog_format = \"json\"\n\
             [control_plane]\nhost = \"agents.example.invalid\"\nport = 443\n",
            Path::new("/etc/idleo/agent.toml"),
        )
        .unwrap();
        assert_eq!(config.ca_root, Path::new("/etc/idleo/root.pem"));
        assert_eq!(config.data_dir, Path::new("/etc/idleo/state"));
        assert_eq!(config.log_format, LogFormat::Json);
        assert_eq!(config.welcome_timeout, Duration::from_secs(10));
    }

    #[test]
    fn defaults_apply_when_keys_are_missing() {
        let config = Config::parse(
            "[control_plane]\nhost = \"agents.example.invalid\"\nport = 443\n",
            Path::new("/etc/idleo/agent.toml"),
        )
        .unwrap();
        assert_eq!(config.ca_root, Path::new("/etc/idleo/ca-root.pem"));
        assert_eq!(config.data_dir, default_dir());
        assert_eq!(config.log_format, LogFormat::Text);
    }

    #[test]
    fn unknown_keys_and_bad_values_are_refused() {
        let path = Path::new("agent.toml");
        let base = "[control_plane]\nhost = \"agents.example.invalid\"\nport = 443\n";
        assert!(matches!(Config::parse(&format!("colour = \"red\"\n{base}"), path), Err(ConfigError::Parse(..))));
        assert!(matches!(Config::parse(&format!("log_format = \"xml\"\n{base}"), path), Err(ConfigError::Parse(..))));
        assert!(matches!(
            Config::parse(&format!("welcome_timeout_secs = 0\n{base}"), path),
            Err(ConfigError::Invalid(..))
        ));
        assert!(matches!(
            Config::parse("[control_plane]\nhost = \"\"\nport = 443\n", path),
            Err(ConfigError::Invalid(..))
        ));
    }
}
