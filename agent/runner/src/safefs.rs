//! Reading a folder the job controls (ADR 0035). The runner is root and the job writes the folder, so a symlink the job
//! plants (a checkpoint file that points at `/etc/shadow`, a folder that's really the runner's own) would make the runner
//! upload a file of the distribution as the renter's output. So nothing here follows a symlink, anywhere on a path, even
//! one swapped in while the runner is reading: on Linux every open is `openat2` with `RESOLVE_BENEATH |
//! RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS` from a folder opened first, and what's opened must be a regular file.
//!
//! Elsewhere (a Mac, for the unit tests; the runner ships only for Linux) the same checks are made by looking before
//! opening, which a determined job could race; it's never used where a job runs.

use std::io;
use std::path::Path;

/// What an entry is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// A regular file.
    File,
    /// A folder.
    Dir,
    /// A symlink, device, socket or anything else: never read.
    Other,
}

/// One entry of a walk, by its path inside the root, joined by `/`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    /// The path inside the root.
    pub rel: String,
    /// What it is.
    pub kind: Kind,
}

/// A folder opened for reading without following links.
#[derive(Debug)]
pub struct Tree {
    #[cfg(target_os = "linux")]
    root: rustix::fd::OwnedFd,
    #[cfg(not(target_os = "linux"))]
    root: std::path::PathBuf,
}

/// Whether `rel` is a path inside a folder and nothing else: no `..`, no leading `/`, no empty parts.
fn clean(rel: &str) -> io::Result<()> {
    if rel.is_empty()
        || rel.split('/').any(|part| part.is_empty() || part == "." || part == ".." || part.contains('\0'))
    {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{rel:?} isn't a path inside the folder")));
    }
    Ok(())
}

#[cfg(target_os = "linux")]
mod imp {
    use std::io;
    use std::path::Path;

    use rustix::fd::OwnedFd;
    use rustix::fs::{AtFlags, Dir, FileType, Mode, OFlags, ResolveFlags, fstat, openat2, statat};

    use super::{Entry, Kind, Tree, clean};

    const BENEATH: ResolveFlags =
        ResolveFlags::BENEATH.union(ResolveFlags::NO_SYMLINKS).union(ResolveFlags::NO_MAGICLINKS);

    fn kind(file_type: FileType) -> Kind {
        match file_type {
            FileType::RegularFile => Kind::File,
            FileType::Directory => Kind::Dir,
            _ => Kind::Other,
        }
    }

    pub fn open(root: &Path) -> io::Result<Tree> {
        let fd = rustix::fs::open(
            root,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        Ok(Tree { root: fd })
    }

    fn open_dir(tree: &Tree, rel: &str) -> io::Result<OwnedFd> {
        if rel.is_empty() {
            return Ok(rustix::io::fcntl_dupfd_cloexec(&tree.root, 0)?);
        }
        clean(rel)?;
        Ok(openat2(&tree.root, rel, OFlags::RDONLY | OFlags::DIRECTORY | OFlags::CLOEXEC, Mode::empty(), BENEATH)?)
    }

    pub fn list(tree: &Tree, rel: &str) -> io::Result<Vec<Entry>> {
        let dir = open_dir(tree, rel)?;
        let mut entries = Vec::new();
        for entry in Dir::read_from(&dir)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if name == "." || name == ".." {
                continue;
            }
            let found = match entry.file_type() {
                FileType::Unknown => {
                    kind(FileType::from_raw_mode(statat(&dir, entry.file_name(), AtFlags::SYMLINK_NOFOLLOW)?.st_mode))
                }
                known => kind(known),
            };
            entries.push(Entry { rel: if rel.is_empty() { name } else { format!("{rel}/{name}") }, kind: found });
        }
        entries.sort_by(|a, b| a.rel.cmp(&b.rel));
        Ok(entries)
    }

    /// Empties and removes a folder using only file descriptors: nothing it deletes is looked up by a path a job could
    /// have swapped for a symlink halfway through.
    fn empty(dir: &OwnedFd) -> io::Result<()> {
        let names: Vec<(std::ffi::CString, FileType)> = Dir::read_from(dir)?
            .map(|entry| entry.map(|entry| (entry.file_name().to_owned(), entry.file_type())))
            .collect::<Result<_, _>>()?;
        for (name, file_type) in names {
            if name.as_bytes() == b"." || name.as_bytes() == b".." {
                continue;
            }
            let is_dir = match file_type {
                FileType::Unknown => {
                    FileType::from_raw_mode(statat(dir, &name, AtFlags::SYMLINK_NOFOLLOW)?.st_mode)
                        == FileType::Directory
                }
                known => known == FileType::Directory,
            };
            if is_dir {
                let child = rustix::fs::openat(
                    dir,
                    &name,
                    OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
                    Mode::empty(),
                )?;
                empty(&child)?;
                rustix::fs::unlinkat(dir, &name, AtFlags::REMOVEDIR)?;
            } else {
                rustix::fs::unlinkat(dir, &name, AtFlags::empty())?;
            }
        }
        Ok(())
    }

    pub fn remove_dir_all(tree: &Tree, rel: &str) -> io::Result<()> {
        clean(rel)?;
        let (parent, name) = rel.rsplit_once('/').map_or(("", rel), |(parent, name)| (parent, name));
        let parent = open_dir(tree, parent)?;
        let child = rustix::fs::openat(
            &parent,
            name,
            OFlags::RDONLY | OFlags::DIRECTORY | OFlags::NOFOLLOW | OFlags::CLOEXEC,
            Mode::empty(),
        )?;
        empty(&child)?;
        drop(child);
        rustix::fs::unlinkat(&parent, name, AtFlags::REMOVEDIR)?;
        Ok(())
    }

    pub fn open_file(tree: &Tree, rel: &str) -> io::Result<std::fs::File> {
        clean(rel)?;
        let fd = openat2(
            &tree.root,
            rel,
            OFlags::RDONLY | OFlags::NOFOLLOW | OFlags::CLOEXEC | OFlags::NONBLOCK,
            Mode::empty(),
            BENEATH,
        )?;
        if FileType::from_raw_mode(fstat(&fd)?.st_mode) != FileType::RegularFile {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{rel} isn't a regular file")));
        }
        Ok(std::fs::File::from(fd))
    }
}

#[cfg(not(target_os = "linux"))]
mod imp {
    use std::io;
    use std::path::Path;

    use super::{Entry, Kind, Tree, clean};

    pub fn open(root: &Path) -> io::Result<Tree> {
        if !std::fs::symlink_metadata(root)?.is_dir() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "not a folder"));
        }
        Ok(Tree { root: root.to_path_buf() })
    }

    fn kind(metadata: &std::fs::Metadata) -> Kind {
        if metadata.is_file() {
            Kind::File
        } else if metadata.is_dir() {
            Kind::Dir
        } else {
            Kind::Other
        }
    }

    pub fn list(tree: &Tree, rel: &str) -> io::Result<Vec<Entry>> {
        let mut path = tree.root.clone();
        if !rel.is_empty() {
            clean(rel)?;
            for part in rel.split('/') {
                path.push(part);
                if std::fs::symlink_metadata(&path)?.is_symlink() {
                    return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{rel} goes through a symlink")));
                }
            }
        }
        let mut entries = Vec::new();
        for entry in std::fs::read_dir(path)? {
            let entry = entry?;
            let name = entry.file_name().to_string_lossy().into_owned();
            let found = kind(&std::fs::symlink_metadata(entry.path())?);
            entries.push(Entry { rel: if rel.is_empty() { name } else { format!("{rel}/{name}") }, kind: found });
        }
        entries.sort_by(|a, b| a.rel.cmp(&b.rel));
        Ok(entries)
    }

    pub fn remove_dir_all(tree: &Tree, rel: &str) -> io::Result<()> {
        list(tree, rel)?;
        std::fs::remove_dir_all(tree.root.join(rel))
    }

    pub fn open_file(tree: &Tree, rel: &str) -> io::Result<std::fs::File> {
        clean(rel)?;
        let path = tree.root.join(rel);
        let mut walked = tree.root.clone();
        for part in rel.split('/') {
            walked.push(part);
            if std::fs::symlink_metadata(&walked)?.is_symlink() {
                return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{rel} goes through a symlink")));
            }
        }
        if !std::fs::symlink_metadata(&path)?.is_file() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, format!("{rel} isn't a regular file")));
        }
        std::fs::File::open(path)
    }
}

impl Tree {
    /// Opens `root`, which itself must not be a symlink.
    ///
    /// # Errors
    /// If it can't be opened as a folder.
    pub fn open(root: &Path) -> io::Result<Self> {
        imp::open(root)
    }

    /// The entries directly inside `rel` (the root when empty), by their paths from the root, sorted.
    ///
    /// # Errors
    /// If `rel` isn't a folder inside the root, or goes through a symlink.
    pub fn list(&self, rel: &str) -> io::Result<Vec<Entry>> {
        imp::list(self, rel)
    }

    /// Every entry under `rel`, folders and what's in them, sorted by path.
    ///
    /// # Errors
    /// If a folder can't be read.
    pub fn walk(&self, rel: &str) -> io::Result<Vec<Entry>> {
        let mut all = Vec::new();
        let mut folders = vec![rel.to_owned()];
        while let Some(folder) = folders.pop() {
            for entry in self.list(&folder)? {
                if entry.kind == Kind::Dir {
                    folders.push(entry.rel.clone());
                }
                all.push(entry);
            }
        }
        all.sort_by(|a, b| a.rel.cmp(&b.rel));
        Ok(all)
    }

    /// Removes a folder inside the root and everything in it, without following a symlink anywhere: a link in it is
    /// removed, never what it points to.
    ///
    /// # Errors
    /// If it isn't a folder inside the root, or something can't be removed.
    pub fn remove_dir_all(&self, rel: &str) -> io::Result<()> {
        imp::remove_dir_all(self, rel)
    }

    /// A regular file inside the root, opened for reading, with no symlink anywhere on its path.
    ///
    /// # Errors
    /// If it's missing, isn't a regular file or goes through a symlink.
    pub fn open_file(&self, rel: &str) -> io::Result<std::fs::File> {
        imp::open_file(self, rel)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree() -> (tempfile::TempDir, Tree) {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("a/b")).unwrap();
        std::fs::write(dir.path().join("a/b/c.txt"), b"hello").unwrap();
        std::fs::write(dir.path().join("top.txt"), b"top").unwrap();
        let tree = Tree::open(dir.path()).unwrap();
        (dir, tree)
    }

    #[test]
    fn a_walk_lists_files_and_folders_sorted() {
        let (_dir, tree) = tree();
        let entries: Vec<(String, Kind)> = tree.walk("").unwrap().into_iter().map(|e| (e.rel, e.kind)).collect();
        assert_eq!(
            entries,
            [
                ("a".to_owned(), Kind::Dir),
                ("a/b".to_owned(), Kind::Dir),
                ("a/b/c.txt".to_owned(), Kind::File),
                ("top.txt".to_owned(), Kind::File)
            ]
        );
        assert_eq!(std::io::read_to_string(tree.open_file("a/b/c.txt").unwrap()).unwrap(), "hello");
    }

    #[cfg(unix)]
    #[test]
    fn a_symlink_is_never_followed_as_a_file_or_a_folder_or_part_of_a_path() {
        let (dir, tree) = tree();
        let outside = tempfile::tempdir().unwrap();
        std::fs::write(outside.path().join("secret"), b"the distribution's secret").unwrap();
        std::os::unix::fs::symlink(outside.path().join("secret"), dir.path().join("link-to-file")).unwrap();
        std::os::unix::fs::symlink(outside.path(), dir.path().join("link-to-folder")).unwrap();
        std::os::unix::fs::symlink("a/b", dir.path().join("a/inner")).unwrap();

        let kinds: Vec<(String, Kind)> = tree.walk("").unwrap().into_iter().map(|e| (e.rel, e.kind)).collect();
        assert!(kinds.contains(&("link-to-file".to_owned(), Kind::Other)), "{kinds:?}");
        assert!(kinds.contains(&("link-to-folder".to_owned(), Kind::Other)));
        assert!(!kinds.iter().any(|(rel, _)| rel.starts_with("link-to-folder/")), "never walked into");
        assert!(tree.open_file("link-to-file").is_err());
        assert!(tree.open_file("link-to-folder/secret").is_err(), "a symlink in the middle of a path");
        assert!(tree.open_file("a/inner/c.txt").is_err());
        assert!(tree.list("link-to-folder").is_err());
    }

    #[test]
    fn paths_that_climb_or_start_at_the_root_are_refused() {
        let (_dir, tree) = tree();
        for bad in ["../x", "a/../top.txt", "/etc/passwd", "a//b", "", ".", "a/./b/c.txt"] {
            assert!(tree.open_file(bad).is_err(), "{bad}");
        }
    }

    #[cfg(unix)]
    #[test]
    fn removing_a_folder_removes_a_link_in_it_and_never_what_it_points_to() {
        let (dir, tree) = tree();
        let outside = tempfile::tempdir().unwrap();
        std::fs::write(outside.path().join("keep"), b"mine").unwrap();
        std::os::unix::fs::symlink(outside.path(), dir.path().join("a/b/escape")).unwrap();

        tree.remove_dir_all("a").unwrap();

        assert!(!dir.path().join("a").exists());
        assert!(dir.path().join("top.txt").exists());
        assert_eq!(
            std::fs::read(outside.path().join("keep")).unwrap(),
            b"mine",
            "what the link pointed at is untouched"
        );
        assert!(tree.remove_dir_all("top.txt").is_err(), "a file isn't a folder");
        assert!(tree.remove_dir_all("../x").is_err());
    }

    #[test]
    fn a_folder_is_not_a_file() {
        let (_dir, tree) = tree();
        assert!(tree.open_file("a/b").is_err());
    }
}
