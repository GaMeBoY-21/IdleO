//! Opening an address in the person's default browser: the approval page, the dashboard, a help page.

/// How to open `url` in the person's default browser on this system, as a program and its arguments.
#[must_use]
pub fn command(os: &str, url: &str) -> (&'static str, Vec<String>) {
    match os {
        "macos" => ("open", vec![url.to_owned()]),
        "windows" => ("explorer.exe", vec![url.to_owned()]),
        _ => ("xdg-open", vec![url.to_owned()]),
    }
}

/// Opens `url` in the default browser; false if there is none to open (a headless machine), in which case the caller
/// shows the address for the person to open anywhere.
#[must_use]
pub fn open(url: &str) -> bool {
    let os = if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "macos"
    } else {
        "linux"
    };
    let (program, args) = command(os, url);
    std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_system_has_its_opener() {
        assert_eq!(command("macos", "http://x/").0, "open");
        assert_eq!(command("linux", "http://x/").0, "xdg-open");
        assert_eq!(command("windows", "http://x/").0, "explorer.exe");
        assert_eq!(command("linux", "http://x/?a=1&b=2").1, ["http://x/?a=1&b=2"]);
    }
}
