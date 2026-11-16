//! "Send diagnostics" (ADR 0061): one zip a host can send to support, made on the laptop, scrubbed of the person and the
//! machine, and holding only what helps: the logs, setup's and the update's progress, the settings, and a few lines about
//! the laptop. Never a key, a certificate, the identity files, a job's files or anything a renter sent.
//!
//! The setup window, the IdleO window, the tray and the launcher all call [`write_zip`]; it needs no agent, so it works
//! when the agent is the thing that is broken.

use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};

use time::OffsetDateTime;
use zip::write::SimpleFileOptions;

use crate::paths::Layout;
use crate::scrub::{Identity, scrub};

/// The most of one file that goes in: its last megabyte (a log's newest lines are the useful ones).
pub const MAX_FILE_BYTES: u64 = 1024 * 1024;

/// The files that go in the zip: where they are, and their names inside it.
#[must_use]
pub fn included(layout: &Layout) -> Vec<(PathBuf, String)> {
    let mut files = Vec::new();
    let mut add = |path: PathBuf, name: String| {
        if path.is_file() {
            files.push((path, name));
        }
    };
    if let Ok(entries) = fs::read_dir(layout.logs()) {
        let mut logs: Vec<_> = entries.filter_map(Result::ok).map(|entry| entry.path()).collect();
        logs.sort();
        for path in logs {
            let name =
                format!("logs/{}", path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default());
            add(path, name);
        }
    }
    add(layout.setup_state(), "setup-state.json".to_owned());
    add(layout.update_state(), "update-state.json".to_owned());
    add(layout.crash_report(), "crash-loop.json".to_owned());
    // The agent's folder holds the key, the certificate and the link: only its two plain settings files go.
    add(layout.agent_dir().join("settings.toml"), "settings.toml".to_owned());
    add(layout.agent_config(), "agent.toml".to_owned());
    files
}

/// The text of `path`, its last [`MAX_FILE_BYTES`], with a note when the start was cut.
fn tail(path: &Path) -> io::Result<String> {
    let mut file = File::open(path)?;
    let length = file.metadata()?.len();
    let mut bytes = Vec::new();
    let cut = length > MAX_FILE_BYTES;
    if cut {
        io::Seek::seek(&mut file, io::SeekFrom::End(-i64::try_from(MAX_FILE_BYTES).unwrap_or(i64::MAX)))?;
    }
    file.read_to_end(&mut bytes)?;
    let text = String::from_utf8_lossy(&bytes).into_owned();
    Ok(if cut { format!("[the start of this file was cut: it is {length} bytes]\n{text}") } else { text })
}

/// What the zip's `about.txt` says: when, which version, and what is in it.
fn about(now: OffsetDateTime, version: &str, names: &[String]) -> String {
    let when = now.format(&time::format_description::well_known::Rfc3339).unwrap_or_default();
    format!(
        "IdleO diagnostics\nMade {when} by IdleO {version}\n\nThis file was made on your laptop and has not been sent anywhere.\n\
         Your name, your computer's name and your user folder were taken out. It holds no keys, no certificates and nothing\n\
         from a renter's job. Send it to support if they ask for it.\n\nIt holds:\n{}\n",
        names.iter().map(|name| format!("  {name}")).collect::<Vec<_>>().join("\n")
    )
}

/// Where the zip goes by default: the Downloads folder, or the temporary folder when there isn't one. It is not under the
/// app's own folders, so it survives an uninstall.
#[must_use]
pub fn default_folder() -> PathBuf {
    std::env::var_os("USERPROFILE")
        .map(|profile| PathBuf::from(profile).join("Downloads"))
        .filter(|folder| folder.is_dir())
        .unwrap_or_else(std::env::temp_dir)
}

/// The zip's name at `now`: `IdleO-diagnostics-20261116-103000.zip`.
#[must_use]
pub fn file_name(now: OffsetDateTime) -> String {
    format!(
        "IdleO-diagnostics-{:04}{:02}{:02}-{:02}{:02}{:02}.zip",
        now.year(),
        u8::from(now.month()),
        now.day(),
        now.hour(),
        now.minute(),
        now.second()
    )
}

/// Writes the diagnostics zip into `folder` and returns its path. `notes` are more files to put in, by name (the agent's
/// status, the Windows build): their text is scrubbed like the rest.
///
/// # Errors
/// If the zip can't be written.
pub fn write_zip(
    layout: &Layout,
    who: &Identity,
    version: &str,
    now: OffsetDateTime,
    notes: &[(String, String)],
    folder: &Path,
) -> io::Result<PathBuf> {
    fs::create_dir_all(folder)?;
    let dest = folder.join(file_name(now));
    let temporary = dest.with_extension("zip.part");
    let result = (|| -> io::Result<()> {
        let mut zip = zip::ZipWriter::new(File::create(&temporary)?);
        let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        let mut names = Vec::new();
        let mut put = |zip: &mut zip::ZipWriter<File>, name: &str, text: &str| -> io::Result<()> {
            zip.start_file(name, options).map_err(io::Error::other)?;
            zip.write_all(scrub(text, who).as_bytes())?;
            names.push(name.to_owned());
            Ok(())
        };
        for (name, text) in notes {
            put(&mut zip, name, text)?;
        }
        for (path, name) in included(layout) {
            // One unreadable file never stops the rest: a diagnostic about a broken laptop mustn't fail because of it.
            match tail(&path) {
                Ok(text) => put(&mut zip, &name, &text)?,
                Err(e) => put(&mut zip, &format!("{name}.unreadable.txt"), &e.to_string())?,
            }
        }
        let text = about(now, version, &names);
        zip.start_file("about.txt", options).map_err(io::Error::other)?;
        zip.write_all(scrub(&text, who).as_bytes())?;
        zip.finish().map_err(io::Error::other)?;
        Ok(())
    })();
    if let Err(e) = result {
        let _ = fs::remove_file(&temporary);
        return Err(e);
    }
    fs::rename(&temporary, &dest)?;
    Ok(dest)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn who() -> Identity {
        Identity { profile: r"C:\Users\Asha Rao".into(), user: "Asha".into(), machine: "TESTRIG-7".into() }
    }

    fn laptop(root: &Path) -> Layout {
        let layout = Layout::under(&root.join("Local"));
        fs::create_dir_all(layout.logs()).unwrap();
        fs::create_dir_all(layout.agent_dir()).unwrap();
        fs::create_dir_all(layout.setup_dir()).unwrap();
        fs::write(layout.logs().join("hostd.log"), r"started in C:\Users\Asha Rao\AppData on TESTRIG-7").unwrap();
        fs::write(layout.logs().join("tray.log"), "tray started").unwrap();
        fs::write(layout.setup_state(), r#"{"completed":["checks"]}"#).unwrap();
        fs::write(layout.agent_dir().join("settings.toml"), "[auto_pause]\n").unwrap();
        fs::write(layout.agent_config(), "api_url = \"https://api.idleo.in\"\n").unwrap();
        // What must never go in.
        fs::write(layout.agent_dir().join("host.key"), "-----BEGIN PRIVATE KEY-----").unwrap();
        fs::write(layout.agent_dir().join("host.crt"), "-----BEGIN CERTIFICATE-----").unwrap();
        fs::write(layout.agent_dir().join("host.json"), "{}").unwrap();
        fs::create_dir_all(layout.agent_dir().join("work/s1-1")).unwrap();
        fs::write(layout.agent_dir().join("work/s1-1/input.bin"), "a renter's file").unwrap();
        layout
    }

    fn read_zip(path: &Path) -> Vec<(String, String)> {
        let mut archive = zip::ZipArchive::new(File::open(path).unwrap()).unwrap();
        (0..archive.len())
            .map(|i| {
                let mut file = archive.by_index(i).unwrap();
                let mut text = String::new();
                file.read_to_string(&mut text).unwrap();
                (file.name().unwrap().into_owned(), text)
            })
            .collect()
    }

    #[test]
    fn only_what_helps_goes_in_and_never_a_key_or_a_renters_file() {
        let dir = tempfile::tempdir().unwrap();
        let layout = laptop(dir.path());
        let names: Vec<String> = included(&layout).into_iter().map(|(_, name)| name).collect();
        assert_eq!(
            names,
            ["logs/hostd.log", "logs/tray.log", "setup-state.json", "settings.toml", "agent.toml"],
            "in this order, and nothing else"
        );
    }

    #[test]
    fn the_zip_is_scrubbed_and_says_what_it_holds() {
        let dir = tempfile::tempdir().unwrap();
        let layout = laptop(dir.path());
        let now = OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap();
        let notes = vec![("status.json".to_owned(), r#"{"machine":"TESTRIG-7"}"#.to_owned())];
        let zip = write_zip(&layout, &who(), "0.2.0", now, &notes, &dir.path().join("Downloads")).unwrap();
        assert_eq!(zip.file_name().unwrap().to_string_lossy(), "IdleO-diagnostics-20251009-085320.zip");
        let files = read_zip(&zip);
        let all = files.iter().fold(String::new(), |all, (name, text)| all + name + "\n" + text + "\n");
        for private in ["Asha", "TESTRIG-7", "Rao", "PRIVATE KEY", "CERTIFICATE", "a renter's file"] {
            assert!(!all.contains(private), "{private:?} is in the zip:\n{all}");
        }
        let hostd = &files.iter().find(|(name, _)| name == "logs/hostd.log").unwrap().1;
        assert_eq!(hostd, r"started in %USERPROFILE%\AppData on <machine>");
        let about = &files.iter().find(|(name, _)| name == "about.txt").unwrap().1;
        assert!(about.contains("0.2.0") && about.contains("logs/hostd.log") && about.contains("status.json"));
        assert!(about.contains("has not been sent anywhere"));
        // No half-written file is left beside it.
        assert!(
            !dir.path().join("Downloads").read_dir().unwrap().any(|e| e.unwrap().path().extension().unwrap() == "part")
        );
    }

    #[test]
    fn a_huge_log_is_cut_to_its_newest_megabyte() {
        let dir = tempfile::tempdir().unwrap();
        let layout = laptop(dir.path());
        let big = format!("{}END", "x".repeat(3 * 1024 * 1024));
        fs::write(layout.logs().join("hostd.log"), big).unwrap();
        let now = OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap();
        let zip = write_zip(&layout, &who(), "0.2.0", now, &[], dir.path()).unwrap();
        let hostd = read_zip(&zip).into_iter().find(|(name, _)| name == "logs/hostd.log").unwrap().1;
        assert!(hostd.starts_with("[the start of this file was cut"));
        assert!(hostd.ends_with("END"));
        assert!(hostd.len() < 1024 * 1024 + 200);
    }

    #[test]
    fn a_laptop_with_nothing_but_a_log_still_makes_a_zip() {
        let dir = tempfile::tempdir().unwrap();
        let layout = Layout::under(&dir.path().join("Local"));
        fs::create_dir_all(layout.logs()).unwrap();
        fs::write(layout.logs().join("setup.log"), "step checks failed").unwrap();
        let now = OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap();
        let zip = write_zip(&layout, &who(), "0.2.0", now, &[], dir.path()).unwrap();
        let names: Vec<String> = read_zip(&zip).into_iter().map(|(name, _)| name).collect();
        assert_eq!(names, ["logs/setup.log", "about.txt"]);
    }
}
