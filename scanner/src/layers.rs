//! What a layer holds, read as a stream (ADR 0042): decompressed on the fly, never unpacked to disk. Every ELF file in it is
//! hashed with SHA-256, so the control plane can match the image against the abuse blocklist now and again whenever the list
//! gains an entry, without downloading it twice. A file that was deleted by a later layer is still in its own layer, and is
//! hashed too: an image is judged by every byte it ships.
//!
//! A layer is a stranger's file, so nothing in it is trusted:
//! - what it decompresses to is counted, and capped for the layer and for the whole image ([`Limits`]), so a small file that
//!   unpacks to terabytes stops at the cap;
//! - a path that is absolute after its leading `./` and `/` are taken off, or has a `..` part, is refused;
//! - a hard link whose target is such a path, or a symbolic link whose target, read from where the link is, climbs above the
//!   image's root, is refused: nothing here follows a link, but whatever unpacks the image later must not be pointed outside it.

use std::collections::BTreeSet;
use std::io::{self, Read};

use flate2::read::MultiGzDecoder;
use sha2::{Digest, Sha256};

use crate::tar::{self, Kind, Reader};

/// How a layer's bytes are compressed, from its media type.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compression {
    /// `tar+gzip` (OCI) or docker's `tar.gzip`.
    Gzip,
    /// `tar+zstd`.
    Zstd,
    /// A plain tar.
    None,
}

impl Compression {
    /// The compression a layer media type names; `None` for a type that isn't a filesystem layer we read.
    #[must_use]
    pub fn of(media_type: &str) -> Option<Self> {
        match media_type {
            "application/vnd.oci.image.layer.v1.tar+gzip"
            | "application/vnd.docker.image.rootfs.diff.tar.gzip"
            | "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip"
            | "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip" => Some(Self::Gzip),
            "application/vnd.oci.image.layer.v1.tar+zstd" => Some(Self::Zstd),
            "application/vnd.oci.image.layer.v1.tar" | "application/vnd.docker.image.rootfs.diff.tar" => {
                Some(Self::None)
            }
            _ => None,
        }
    }
}

/// The caps on what an image may decompress to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Limits {
    /// The most one layer may decompress to, in bytes.
    pub layer_bytes: u64,
    /// The most all layers together may decompress to.
    pub image_bytes: u64,
}

/// An ELF file found in a layer.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Executable {
    /// Its path in the image, without a leading `/`.
    pub path: String,
    /// Its SHA-256, lower-case hex.
    pub sha256: String,
    /// The layer it is in, counting from 0 at the bottom.
    pub layer: usize,
}

/// Why a layer can't be accepted. Each is a plain sentence for the renter.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refusal {
    /// It decompresses to more than a cap.
    TooLargeUnpacked(String),
    /// A path or a link points outside the image.
    UnsafePath(String),
    /// It isn't a layer we can read.
    Unreadable(String),
}

/// The whole image as its layers are read, bottom first.
#[derive(Debug, Default)]
pub struct Image {
    /// Every ELF file in every layer, in the order found.
    pub executables: Vec<Executable>,
    /// What all the layers decompressed to.
    pub unpacked_bytes: u64,
    /// The shell paths present once every layer is applied, whiteouts and all.
    shells: BTreeSet<String>,
}

/// The paths a typed command's `/bin/sh -c` may be found at.
const SHELLS: [&str; 2] = ["bin/sh", "usr/bin/sh"];

impl Image {
    /// Whether the image, all layers applied, has a `/bin/sh` (as a file or a link), so a command the renter types can run.
    #[must_use]
    pub fn has_shell(&self) -> bool {
        !self.shells.is_empty()
    }

    /// Reads layer `index` from `compressed`, which is the layer blob as downloaded.
    ///
    /// # Errors
    /// The [`Refusal`] that stops the image.
    pub fn read_layer(
        &mut self,
        index: usize,
        compressed: impl Read,
        compression: Compression,
        limits: Limits,
    ) -> Result<(), Refusal> {
        let budget = limits.layer_bytes.min(limits.image_bytes.saturating_sub(self.unpacked_bytes));
        let stream: Box<dyn Read> = match compression {
            Compression::Gzip => Box::new(MultiGzDecoder::new(compressed)),
            Compression::Zstd => Box::new(
                ruzstd::decoding::StreamingDecoder::new(compressed)
                    .map_err(|e| Refusal::Unreadable(format!("layer {} isn't zstd: {e}", index + 1)))?,
            ),
            Compression::None => Box::new(compressed),
        };
        let mut counted = Capped { inner: stream, read: 0, cap: budget };
        // What follows the archive's end is read too, so nothing hidden after it escapes the cap.
        let result = self.read_entries(index, &mut counted).and_then(|()| {
            io::copy(&mut counted, &mut io::sink())
                .map(|_| ())
                .map_err(|e| Refusal::Unreadable(format!("Layer {} couldn't be read to its end: {e}.", index + 1)))
        });
        if counted.read > budget {
            let which = if budget < limits.layer_bytes { "all layers together unpack" } else { "it unpacks" };
            return Err(Refusal::TooLargeUnpacked(format!(
                "Layer {} is too large once unpacked: {which} to more than {}.",
                index + 1,
                crate::gigabytes(if budget < limits.layer_bytes { limits.image_bytes } else { limits.layer_bytes })
            )));
        }
        self.unpacked_bytes += counted.read;
        result
    }

    fn read_entries(&mut self, index: usize, stream: &mut impl Read) -> Result<(), Refusal> {
        let layer = index + 1;
        let unreadable = |e: tar::Error| Refusal::Unreadable(format!("Layer {layer} couldn't be read: {e}."));
        let mut reader = Reader::new(stream);
        let mut added = BTreeSet::new();
        while let Some(header) = reader.next_entry().map_err(unreadable)? {
            let path =
                clean(&header.path).filter(|path| !path.is_empty() || header.kind == Kind::Directory).ok_or_else(
                    || Refusal::UnsafePath(format!("Layer {layer} has a file outside the image: {:?}.", header.path)),
                )?;
            if path.is_empty() {
                // The root folder itself, `./`, which many layers start with.
                continue;
            }
            match header.kind {
                Kind::HardLink => {
                    if clean(&header.link).is_none_or(|target| target.is_empty()) {
                        return Err(Refusal::UnsafePath(format!(
                            "Layer {layer} has a hard link to outside the image: {path} to {:?}.",
                            header.link
                        )));
                    }
                }
                Kind::Symlink => {
                    if escapes(&path, &header.link) {
                        return Err(Refusal::UnsafePath(format!(
                            "Layer {layer} has a link pointing outside the image: {path} to {:?}.",
                            header.link
                        )));
                    }
                }
                Kind::File => {
                    if let Some(sha256) = elf_hash(&mut reader).map_err(|e| unreadable(tar::Error::Io(e)))? {
                        self.executables.push(Executable { path: path.clone(), sha256, layer: index });
                    }
                }
                Kind::Directory | Kind::Other => {}
            }
            self.apply_whiteouts(&path, &mut added);
            if matches!(header.kind, Kind::File | Kind::HardLink | Kind::Symlink) && SHELLS.contains(&path.as_str()) {
                added.insert(path);
            }
        }
        self.shells.extend(added);
        Ok(())
    }

    /// A whiteout in this layer deletes a path from the layers below; an opaque marker empties a directory of them.
    fn apply_whiteouts(&mut self, path: &str, added: &mut BTreeSet<String>) {
        let (dir, name) = path.rsplit_once('/').unwrap_or(("", path));
        let under = |prefix: &str, shell: &str| prefix.is_empty() || shell.starts_with(&format!("{prefix}/"));
        if name == ".wh..wh..opq" {
            self.shells.retain(|shell| !under(dir, shell));
        } else if let Some(deleted) = name.strip_prefix(".wh.") {
            let gone = if dir.is_empty() { deleted.to_owned() } else { format!("{dir}/{deleted}") };
            self.shells.retain(|shell| shell != &gone && !under(&gone, shell));
            added.retain(|shell| shell != &gone);
        }
    }
}

/// The SHA-256 of the current entry if it starts with ELF's magic, reading it whole; `None` for anything else, read no
/// further than its first bytes (the tar reader skips the rest).
fn elf_hash<R: Read>(reader: &mut Reader<R>) -> io::Result<Option<String>> {
    let mut head = [0_u8; 4];
    let mut have = 0;
    while have < head.len() {
        let read = reader.read_data(&mut head[have..])?;
        if read == 0 {
            return Ok(None);
        }
        have += read;
    }
    if head != *b"\x7fELF" {
        return Ok(None);
    }
    let mut hasher = Sha256::new();
    hasher.update(head);
    let mut buf = vec![0_u8; 64 * 1024];
    loop {
        let read = reader.read_data(&mut buf)?;
        if read == 0 {
            break;
        }
        hasher.update(&buf[..read]);
    }
    Ok(Some(crate::hex(&hasher.finalize())))
}

/// The path without its leading `./` and `/`, and without a trailing `/` (empty for the root itself); `None` if a part of it
/// is `..`.
fn clean(path: &str) -> Option<String> {
    let mut parts = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." => return None,
            part => parts.push(part),
        }
    }
    Some(parts.join("/"))
}

/// Whether a symbolic link at `path` to `target` climbs above the image's root, read lexically from the link's folder. An
/// absolute target starts at the root, as it would inside the container.
fn escapes(path: &str, target: &str) -> bool {
    let mut depth: i64 =
        if target.starts_with('/') { 0 } else { i64::try_from(path.matches('/').count()).unwrap_or(0) };
    for part in target.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                depth -= 1;
                if depth < 0 {
                    return true;
                }
            }
            _ => depth += 1,
        }
    }
    false
}

/// Counts what passes through, and stops at the cap: one byte past it is read so that going over is seen.
struct Capped<R> {
    inner: R,
    read: u64,
    cap: u64,
}

impl<R: Read> Read for Capped<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.read > self.cap {
            return Err(io::Error::other("the layer unpacks to more than its cap"));
        }
        let room = usize::try_from((self.cap - self.read).saturating_add(1)).unwrap_or(usize::MAX);
        let want = buf.len().min(room);
        let read = self.inner.read(&mut buf[..want])?;
        self.read += read as u64;
        Ok(read)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use super::*;
    use crate::tar::build::Tar;

    const ROOMY: Limits = Limits { layer_bytes: 1 << 30, image_bytes: 1 << 31 };

    fn gzip(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        encoder.write_all(bytes).unwrap();
        encoder.finish().unwrap()
    }

    fn elf(tag: &str) -> Vec<u8> {
        let mut bytes = b"\x7fELF\x02\x01\x01".to_vec();
        bytes.extend_from_slice(tag.as_bytes());
        bytes
    }

    fn sha(bytes: &[u8]) -> String {
        crate::hex(&Sha256::digest(bytes))
    }

    #[test]
    fn every_elf_file_in_every_layer_is_hashed_even_one_a_later_layer_deletes() {
        let lower = Tar::default().file("opt/deep/tool", &elf("listed")).file("etc/motd", b"hello").done();
        let upper = Tar::default().file("opt/deep/.wh.tool", b"").file("usr/bin/app", &elf("app")).done();
        let mut image = Image::default();
        image.read_layer(0, gzip(&lower).as_slice(), Compression::Gzip, ROOMY).unwrap();
        image.read_layer(1, upper.as_slice(), Compression::None, ROOMY).unwrap();
        assert_eq!(
            image.executables,
            [
                Executable { path: "opt/deep/tool".into(), sha256: sha(&elf("listed")), layer: 0 },
                Executable { path: "usr/bin/app".into(), sha256: sha(&elf("app")), layer: 1 },
            ]
        );
        assert_eq!(image.unpacked_bytes, (lower.len() + upper.len()) as u64);
    }

    #[test]
    fn a_layer_that_unpacks_past_its_cap_is_refused_however_small_it_is() {
        // 64 MiB of zeroes is a few dozen KiB of gzip.
        let bomb = Tar::default().file("zeroes", &vec![0_u8; 64 << 20]).done();
        let small = gzip(&bomb);
        assert!(small.len() < 1 << 20, "the bomb is small: {} bytes", small.len());
        let tight = Limits { layer_bytes: 8 << 20, image_bytes: 1 << 30 };
        let refused = Image::default().read_layer(0, small.as_slice(), Compression::Gzip, tight).unwrap_err();
        assert!(
            matches!(&refused, Refusal::TooLargeUnpacked(why) if why.contains("Layer 1") && why.contains("it unpacks")),
            "{refused:?}"
        );
    }

    #[test]
    fn layers_that_together_unpack_past_the_image_cap_are_refused() {
        let layer = Tar::default().file("blob", &vec![1_u8; 6 << 20]).done();
        let limits = Limits { layer_bytes: 8 << 20, image_bytes: 10 << 20 };
        let mut image = Image::default();
        image.read_layer(0, gzip(&layer).as_slice(), Compression::Gzip, limits).unwrap();
        let refused = image.read_layer(1, gzip(&layer).as_slice(), Compression::Gzip, limits).unwrap_err();
        assert!(
            matches!(&refused, Refusal::TooLargeUnpacked(why) if why.contains("all layers together")),
            "{refused:?}"
        );
    }

    #[test]
    fn a_path_with_dot_dot_is_refused() {
        for bad in ["../etc/passwd", "usr/../../etc/shadow", "./..", "/"] {
            let tar = Tar::default().file(bad, b"x").done();
            let refused = Image::default().read_layer(0, tar.as_slice(), Compression::None, ROOMY).unwrap_err();
            assert!(matches!(refused, Refusal::UnsafePath(_)), "{bad}: {refused:?}");
        }
        let fine = Tar::default().entry("./", b'5', "").file("./usr/lib/x", b"x").file("/etc/abs", b"y").done();
        Image::default().read_layer(0, fine.as_slice(), Compression::None, ROOMY).unwrap();
    }

    #[test]
    fn a_link_pointing_outside_the_image_is_refused() {
        for (path, flag, target) in [
            ("usr/lib/escape", b'2', "../../../etc/passwd"),
            ("evil", b'2', "../host"),
            ("hard", b'1', "../../etc/shadow"),
            ("hard2", b'1', "/../outside"),
        ] {
            let tar = Tar::default().entry(path, flag, target).done();
            let refused = Image::default().read_layer(0, tar.as_slice(), Compression::None, ROOMY).unwrap_err();
            assert!(matches!(refused, Refusal::UnsafePath(_)), "{path} -> {target}: {refused:?}");
        }
        // What real images hold: absolute targets inside the root, and relative ones that stay in it.
        let fine = Tar::default()
            .entry("bin", b'2', "usr/bin")
            .entry("usr/bin/python", b'2', "/usr/bin/python3.12")
            .entry("usr/lib/x/libz.so", b'2', "../libz.so.1")
            .entry("usr/bin/same", b'1', "usr/bin/python3.12")
            .done();
        Image::default().read_layer(0, fine.as_slice(), Compression::None, ROOMY).unwrap();
    }

    #[test]
    fn a_shell_counts_only_if_no_later_layer_deletes_it() {
        let with_shell = Tar::default().file("bin/sh", &elf("dash")).done();
        let mut image = Image::default();
        image.read_layer(0, with_shell.as_slice(), Compression::None, ROOMY).unwrap();
        assert!(image.has_shell());
        let removes = Tar::default().file("bin/.wh.sh", b"").done();
        image.read_layer(1, removes.as_slice(), Compression::None, ROOMY).unwrap();
        assert!(!image.has_shell());
        let link = Tar::default().entry("usr/bin/sh", b'2', "dash").done();
        image.read_layer(2, link.as_slice(), Compression::None, ROOMY).unwrap();
        assert!(image.has_shell());
        let opaque = Tar::default().file("usr/bin/.wh..wh..opq", b"").done();
        image.read_layer(3, opaque.as_slice(), Compression::None, ROOMY).unwrap();
        assert!(!image.has_shell(), "an opaque directory hides what the layers below put in it");
    }

    #[test]
    fn a_layer_that_isnt_a_tar_is_unreadable() {
        let refused = Image::default().read_layer(0, &b"not a tar at all"[..], Compression::Gzip, ROOMY).unwrap_err();
        assert!(matches!(refused, Refusal::Unreadable(_)), "{refused:?}");
    }

    #[test]
    fn zstd_layers_are_read() {
        let tar = Tar::default().file("usr/bin/z", &elf("z")).done();
        let compressed = ruzstd::encoding::compress_to_vec(tar.as_slice(), ruzstd::encoding::CompressionLevel::Fastest);
        let mut image = Image::default();
        image.read_layer(0, compressed.as_slice(), Compression::Zstd, ROOMY).unwrap();
        assert_eq!(image.executables[0].sha256, sha(&elf("z")));
    }

    #[test]
    fn layer_media_types_are_known() {
        assert_eq!(Compression::of("application/vnd.docker.image.rootfs.diff.tar.gzip"), Some(Compression::Gzip));
        assert_eq!(Compression::of("application/vnd.oci.image.layer.v1.tar+zstd"), Some(Compression::Zstd));
        assert_eq!(Compression::of("application/vnd.cncf.helm.chart.content.v1.tar+gzip"), None);
    }
}
