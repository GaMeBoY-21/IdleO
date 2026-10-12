//! What gets uploaded: a folder's files, or one file, with their paths and sizes, and a fingerprint that says
//! whether it's the same set as last time (so a half-sent upload carries on instead of starting over).

use std::fs;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use sha2::{Digest, Sha256};

use crate::error::{CliError, Result};

/// The most files one upload takes (ADR 0022).
pub const MAX_FILES: usize = 1_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Local {
    /// The path inside the upload, `/`-separated and relative: `config.json`, `shards/part-0.bin`.
    pub path: String,
    pub size: u64,
    /// Where it is on this computer.
    pub from: PathBuf,
    /// When it last changed, in seconds, so an edited file isn't taken for the one sent before.
    pub modified: u64,
}

/// The files of a folder (every regular file, at any depth; links and other special files are left out), or the one file.
///
/// # Errors
/// If something can't be read, or there are more than [`MAX_FILES`].
pub fn collect(root: &Path) -> Result<(String, Vec<Local>)> {
    let name = root.file_name().map_or_else(|| "files".to_owned(), |name| name.to_string_lossy().into_owned());
    let mut files = Vec::new();
    if root.is_dir() {
        walk(root, root, &mut files)?;
    } else {
        files.push(local(root, name.clone())?);
    }
    if files.is_empty() {
        return Err(CliError::usage(format!("{} has no files in it to upload", root.display())));
    }
    if files.len() > MAX_FILES {
        return Err(CliError::usage(format!(
            "{} has {} files; an upload takes at most {MAX_FILES}. Put them in an archive, or send a smaller folder.",
            root.display(),
            files.len()
        )));
    }
    files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok((name, files))
}

fn walk(root: &Path, dir: &Path, out: &mut Vec<Local>) -> Result<()> {
    let read = |e: std::io::Error| CliError::failed(format!("couldn't read {}: {e}", dir.display()));
    for entry in fs::read_dir(dir).map_err(read)? {
        let entry = entry.map_err(read)?;
        let kind = entry.file_type().map_err(read)?;
        let path = entry.path();
        if kind.is_dir() {
            walk(root, &path, out)?;
        } else if kind.is_file() {
            let relative = path.strip_prefix(root).expect("walked from the root");
            let parts: Vec<String> =
                relative.components().map(|part| part.as_os_str().to_string_lossy().into_owned()).collect();
            out.push(local(&path, parts.join("/"))?);
        }
    }
    Ok(())
}

fn local(path: &Path, inside: String) -> Result<Local> {
    let meta = fs::metadata(path).map_err(|e| CliError::failed(format!("couldn't read {}: {e}", path.display())))?;
    let modified =
        meta.modified().ok().and_then(|time| time.duration_since(UNIX_EPOCH).ok()).map_or(0, |d| d.as_secs());
    Ok(Local { path: inside, size: meta.len(), from: path.to_owned(), modified })
}

/// The same files, with the same sizes and modification times, always have the same fingerprint.
#[must_use]
pub fn fingerprint(name: &str, files: &[Local]) -> String {
    let mut hash = Sha256::new();
    hash.update(name.as_bytes());
    for file in files {
        hash.update(format!("\n{}\t{}\t{}", file.path, file.size, file.modified).as_bytes());
    }
    crate::hex::hex(&hash.finalize())
}

/// The bytes a part covers: part numbers start at 1, each `part_size` long but the last, which is what's left.
#[must_use]
pub fn part_range(number: u32, part_size: u64, file_size: u64) -> (u64, u64) {
    let start = u64::from(number - 1) * part_size;
    (start.min(file_size), (start + part_size).min(file_size))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        fs::write(dir.path().join("config.json"), "{}").unwrap();
        fs::create_dir(dir.path().join("shards")).unwrap();
        fs::write(dir.path().join("shards/part-0.bin"), vec![0_u8; 10]).unwrap();
        dir
    }

    #[test]
    fn a_folders_files_are_listed_with_their_paths_inside_it() {
        let dir = tree();
        let (name, files) = collect(dir.path()).unwrap();
        assert_eq!(name, dir.path().file_name().unwrap().to_string_lossy());
        let seen: Vec<(&str, u64)> = files.iter().map(|f| (f.path.as_str(), f.size)).collect();
        assert_eq!(seen, [("config.json", 2), ("shards/part-0.bin", 10)]);
    }

    #[test]
    fn one_file_is_an_upload_of_one() {
        let dir = tree();
        let (name, files) = collect(&dir.path().join("config.json")).unwrap();
        assert_eq!((name.as_str(), files.len(), files[0].path.as_str()), ("config.json", 1, "config.json"));
    }

    #[test]
    fn an_empty_folder_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        assert!(collect(dir.path()).unwrap_err().message.contains("no files"));
    }

    #[test]
    fn more_than_a_thousand_files_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        for i in 0..=MAX_FILES {
            fs::write(dir.path().join(format!("f{i}")), "").unwrap();
        }
        assert!(collect(dir.path()).unwrap_err().message.contains("at most 1000"));
    }

    #[cfg(unix)]
    #[test]
    fn links_are_left_out() {
        let dir = tree();
        std::os::unix::fs::symlink(dir.path().join("config.json"), dir.path().join("link.json")).unwrap();
        let (_, files) = collect(dir.path()).unwrap();
        assert_eq!(files.len(), 2);
    }

    #[test]
    fn the_fingerprint_follows_the_files() {
        let dir = tree();
        let (name, files) = collect(dir.path()).unwrap();
        let first = fingerprint(&name, &files);
        assert_eq!(first, fingerprint(&name, &files));
        let mut changed = files.clone();
        changed[0].size += 1;
        assert_ne!(first, fingerprint(&name, &changed));
        assert_ne!(first, fingerprint("another-name", &files));
        let mut touched = files;
        touched[1].modified += 1;
        assert_ne!(first, fingerprint(&name, &touched));
    }

    #[test]
    fn parts_cover_the_file_exactly() {
        assert_eq!(part_range(1, 64, 150), (0, 64));
        assert_eq!(part_range(2, 64, 150), (64, 128));
        assert_eq!(part_range(3, 64, 150), (128, 150));
        assert_eq!(part_range(1, 64, 0), (0, 0));
    }
}
