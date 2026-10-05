//! The operating system, as `Hello` reports it.

use agent_proto::v1::OperatingSystem;

/// This build's operating system. macOS has no protocol value (agents run on Windows and Linux),
/// so a development build on a Mac reports unspecified.
#[must_use]
pub fn os() -> OperatingSystem {
    if cfg!(windows) {
        OperatingSystem::Windows
    } else if cfg!(target_os = "linux") {
        OperatingSystem::Linux
    } else {
        OperatingSystem::Unspecified
    }
}

/// The operating system's name and version, for example `Windows 11 26100` or
/// `Ubuntu 24.04.1 LTS (kernel 6.8.0-45-generic)`.
#[must_use]
pub fn os_version() -> String {
    version().unwrap_or_else(|| std::env::consts::OS.to_owned())
}

#[cfg(windows)]
fn version() -> Option<String> {
    let build = agent_windows::windows_build().ok()?;
    // Windows 11 still reports "Windows 10" in its product name; the build number tells them apart.
    let name = if build >= 22_000 { "Windows 11" } else { "Windows 10" };
    Some(format!("{name} {build}"))
}

#[cfg(target_os = "linux")]
fn version() -> Option<String> {
    let release = std::fs::read_to_string("/etc/os-release").ok()?;
    let name = release.lines().find_map(|line| line.strip_prefix("PRETTY_NAME="))?.trim_matches('"').to_owned();
    match std::fs::read_to_string("/proc/sys/kernel/osrelease") {
        Ok(kernel) => Some(format!("{name} (kernel {})", kernel.trim())),
        Err(_) => Some(name),
    }
}

#[cfg(target_os = "macos")]
fn version() -> Option<String> {
    let plist = std::fs::read_to_string("/System/Library/CoreServices/SystemVersion.plist").ok()?;
    let after = plist.split("<key>ProductVersion</key>").nth(1)?;
    let version = after.split("<string>").nth(1)?.split("</string>").next()?;
    Some(format!("macOS {version}"))
}

#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
fn version() -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_version_names_the_system() {
        let version = os_version();
        if cfg!(windows) {
            assert!(version.starts_with("Windows "), "{version}");
        }
        if cfg!(target_os = "macos") {
            assert!(version.starts_with("macOS "), "{version}");
        }
    }
}
