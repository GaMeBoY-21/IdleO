//! The launcher replaces itself only by rename (ADR 0034): an update stages a newer `idleo-launcher.new.exe`; at its
//! next start the launcher renames itself to `.old` (Windows allows renaming a running program), moves the new one
//! into place and runs it again. The `.old` one is deleted at the start after.

use std::fs;
use std::io;
use std::path::Path;

use idleo_release::paths::Layout;

/// What the start found.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SelfUpdate {
    /// Nothing staged: carry on.
    None,
    /// The new launcher is in place: run it with the same arguments, and exit.
    Swapped,
}

/// Deletes a launcher replaced last time, and swaps in a staged one if this is the launcher in place.
///
/// # Errors
/// If a rename fails; the launcher then carries on as it is.
pub fn at_start(layout: &Layout, running: &Path) -> io::Result<SelfUpdate> {
    let _ = fs::remove_file(layout.launcher_old());
    let staged = layout.launcher_staged();
    if !staged.exists() || !same_file(running, &layout.launcher()) {
        return Ok(SelfUpdate::None);
    }
    fs::rename(layout.launcher(), layout.launcher_old())?;
    if let Err(e) = fs::rename(&staged, layout.launcher()) {
        // Put the old one back, so the Run entry always finds a launcher.
        let _ = fs::rename(layout.launcher_old(), layout.launcher());
        return Err(e);
    }
    Ok(SelfUpdate::Swapped)
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (a.canonicalize(), b.canonicalize()) {
        (Ok(a), Ok(b)) => a == b,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout() -> (tempfile::TempDir, Layout) {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::under(root.path());
        fs::create_dir_all(layout.programs()).unwrap();
        fs::write(layout.launcher(), "launcher 0.1.0").unwrap();
        (root, layout)
    }

    #[test]
    fn a_staged_launcher_is_swapped_in_by_rename_and_the_old_one_goes_next_time() {
        let (_root, layout) = layout();
        fs::write(layout.launcher_staged(), "launcher 0.2.0").unwrap();
        assert_eq!(at_start(&layout, &layout.launcher()).unwrap(), SelfUpdate::Swapped);
        assert_eq!(fs::read_to_string(layout.launcher()).unwrap(), "launcher 0.2.0");
        assert_eq!(fs::read_to_string(layout.launcher_old()).unwrap(), "launcher 0.1.0");
        assert!(!layout.launcher_staged().exists());

        assert_eq!(at_start(&layout, &layout.launcher()).unwrap(), SelfUpdate::None);
        assert!(!layout.launcher_old().exists(), "deleted at the next start");
    }

    #[test]
    fn nothing_staged_or_a_copy_elsewhere_changes_nothing() {
        let (root, layout) = layout();
        assert_eq!(at_start(&layout, &layout.launcher()).unwrap(), SelfUpdate::None);
        fs::write(layout.launcher_staged(), "launcher 0.2.0").unwrap();
        let elsewhere = root.path().join("idleo-uninstall.exe");
        fs::write(&elsewhere, "a copy").unwrap();
        assert_eq!(at_start(&layout, &elsewhere).unwrap(), SelfUpdate::None, "a copy in %TEMP% never swaps");
        assert_eq!(fs::read_to_string(layout.launcher()).unwrap(), "launcher 0.1.0");
    }
}
