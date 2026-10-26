//! A small tar reader for image layers, written here rather than taken from a crate so that every size it trusts is capped:
//! a layer is a stranger's file. It reads ustar, GNU and PAX headers from any [`Read`] (a decompressing stream), one entry at a
//! time, and never seeks or writes anything. Long names and PAX records are held in memory only up to [`MAX_META_BYTES`]; an
//! entry's data is read by the caller or skipped by reading, so a counting reader under it sees every byte.

use std::io::{self, Read};

/// The most a GNU long name or a PAX header may hold: real ones are a few hundred bytes.
pub const MAX_META_BYTES: u64 = 64 * 1024;

const BLOCK: usize = 512;

/// What an entry is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// A regular file, with data.
    File,
    /// A hard link to an earlier entry, without data.
    HardLink,
    /// A symbolic link, without data.
    Symlink,
    /// A directory.
    Directory,
    /// A device, a FIFO or anything else that holds no data.
    Other,
}

/// One entry's header, its long name and PAX records already applied.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    /// The path as written in the layer.
    pub path: String,
    /// What it is.
    pub kind: Kind,
    /// Its data's size in bytes.
    pub size: u64,
    /// A link's target.
    pub link: String,
}

/// Why a layer couldn't be read.
#[derive(Debug)]
pub enum Error {
    /// The stream broke or ended early.
    Io(io::Error),
    /// The bytes aren't a tar archive we accept.
    Malformed(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(e) => write!(f, "{e}"),
            Self::Malformed(why) => write!(f, "{why}"),
        }
    }
}

impl From<io::Error> for Error {
    fn from(e: io::Error) -> Self {
        Self::Io(e)
    }
}

/// Reads entries from a tar stream.
pub struct Reader<R> {
    inner: R,
    /// Data bytes of the current entry not read yet, then its padding.
    remaining: u64,
    padding: u64,
    done: bool,
}

impl<R: Read> Reader<R> {
    /// A reader over `inner`.
    pub fn new(inner: R) -> Self {
        Self { inner, remaining: 0, padding: 0, done: false }
    }

    /// The next entry, skipping what's left of the current one; `None` at the end of the archive.
    ///
    /// # Errors
    /// If the stream breaks, or a header is malformed or too large.
    pub fn next_entry(&mut self) -> Result<Option<Header>, Error> {
        if self.done {
            return Ok(None);
        }
        self.skip_rest()?;
        let mut long_name: Option<String> = None;
        let mut long_link: Option<String> = None;
        let mut pax: Vec<(String, String)> = Vec::new();
        loop {
            let mut block = [0_u8; BLOCK];
            if !self.read_block(&mut block)? {
                // A stream that ends without its two zero blocks: accepted, as docker and containerd do.
                self.done = true;
                return Ok(None);
            }
            if block.iter().all(|&b| b == 0) {
                self.done = true;
                return Ok(None);
            }
            check_sum(&block)?;
            let size = number(&block[124..136])?;
            let flag = block[156];
            match flag {
                b'L' | b'K' | b'x' => {
                    let meta = self.read_meta(size)?;
                    match flag {
                        b'L' => long_name = Some(text(trim_nul(&meta))),
                        b'K' => long_link = Some(text(trim_nul(&meta))),
                        _ => pax = pax_records(&meta)?,
                    }
                    continue;
                }
                // A global PAX header applies to every entry after it; nothing we read depends on one, so it's read and dropped.
                b'g' => {
                    self.read_meta(size)?;
                    continue;
                }
                _ => {}
            }
            let mut path = long_name.take().unwrap_or_else(|| ustar_path(&block));
            let mut link = long_link.take().unwrap_or_else(|| text(trim_nul(&block[157..257])));
            let mut size = size;
            for (key, value) in pax.drain(..) {
                match key.as_str() {
                    "path" => path = value,
                    "linkpath" => link = value,
                    "size" => {
                        size = value.parse().map_err(|_| Error::Malformed("a PAX size isn't a number".to_owned()))?;
                    }
                    _ => {}
                }
            }
            let kind = match flag {
                b'0' | 0 | b'7' => Kind::File,
                b'1' => Kind::HardLink,
                b'2' => Kind::Symlink,
                b'5' => Kind::Directory,
                b'3' | b'4' | b'6' => Kind::Other,
                other => return Err(Error::Malformed(format!("an entry of unknown type {:?}", char::from(other)))),
            };
            // Only a regular file carries data; for the rest a size is still skipped, as tar does.
            self.remaining = size;
            self.padding = pad(size);
            return Ok(Some(Header { path, kind, size: if kind == Kind::File { size } else { 0 }, link }));
        }
    }

    /// Reads into `buf` from the current entry's data; 0 at its end.
    ///
    /// # Errors
    /// If the stream breaks or ends inside the entry.
    pub fn read_data(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.remaining == 0 {
            return Ok(0);
        }
        let want = usize::try_from(self.remaining.min(buf.len() as u64)).unwrap_or(buf.len());
        let read = self.inner.read(&mut buf[..want])?;
        if read == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "the layer ended inside a file"));
        }
        self.remaining -= read as u64;
        Ok(read)
    }

    fn skip_rest(&mut self) -> Result<(), Error> {
        let skip = self.remaining + self.padding;
        if skip > 0 {
            let copied = io::copy(&mut (&mut self.inner).take(skip), &mut io::sink())?;
            if copied < skip {
                return Err(Error::Io(io::Error::new(io::ErrorKind::UnexpectedEof, "the layer ended inside a file")));
            }
        }
        self.remaining = 0;
        self.padding = 0;
        Ok(())
    }

    fn read_meta(&mut self, size: u64) -> Result<Vec<u8>, Error> {
        if size > MAX_META_BYTES {
            return Err(Error::Malformed(format!("a long name or PAX header of {size} bytes is more than we read")));
        }
        let mut meta = vec![0_u8; usize::try_from(size).unwrap_or(0)];
        self.inner.read_exact(&mut meta)?;
        io::copy(&mut (&mut self.inner).take(pad(size)), &mut io::sink())?;
        Ok(meta)
    }

    /// Reads one block; false at a clean end of stream before it.
    fn read_block(&mut self, block: &mut [u8; BLOCK]) -> Result<bool, Error> {
        let mut filled = 0;
        while filled < BLOCK {
            let read = self.inner.read(&mut block[filled..])?;
            if read == 0 {
                if filled == 0 {
                    return Ok(false);
                }
                return Err(Error::Malformed("the layer ends inside a header".to_owned()));
            }
            filled += read;
        }
        Ok(true)
    }
}

fn pad(size: u64) -> u64 {
    (BLOCK as u64 - size % BLOCK as u64) % BLOCK as u64
}

fn trim_nul(bytes: &[u8]) -> &[u8] {
    let end = bytes.iter().position(|&b| b == 0).unwrap_or(bytes.len());
    &bytes[..end]
}

fn text(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

fn ustar_path(block: &[u8; BLOCK]) -> String {
    let name = text(trim_nul(&block[0..100]));
    let ustar = &block[257..262] == b"ustar";
    let prefix = if ustar { text(trim_nul(&block[345..500])) } else { String::new() };
    if prefix.is_empty() { name } else { format!("{prefix}/{name}") }
}

/// A size field: octal text, or GNU's base-256 with the top bit set.
fn number(field: &[u8]) -> Result<u64, Error> {
    if field.first().is_some_and(|b| b & 0x80 != 0) {
        let mut value: u64 = u64::from(field[0] & 0x7f);
        for &b in &field[1..] {
            value = value.checked_mul(256).and_then(|v| v.checked_add(u64::from(b))).ok_or_else(too_big)?;
        }
        return Ok(value);
    }
    let digits = trim_nul(field);
    let digits = std::str::from_utf8(digits).map_err(|_| Error::Malformed("a size isn't text".to_owned()))?.trim();
    if digits.is_empty() {
        return Ok(0);
    }
    u64::from_str_radix(digits, 8).map_err(|_| Error::Malformed("a size isn't an octal number".to_owned()))
}

fn too_big() -> Error {
    Error::Malformed("a size is too large".to_owned())
}

fn check_sum(block: &[u8; BLOCK]) -> Result<(), Error> {
    let stored = number(&block[148..156])?;
    let sum: u64 =
        block.iter().enumerate().map(|(i, &b)| if (148..156).contains(&i) { 32 } else { u64::from(b) }).sum();
    if stored == sum { Ok(()) } else { Err(Error::Malformed("a header's checksum is wrong".to_owned())) }
}

/// PAX records: `<length> <key>=<value>\n`, each length counting the whole record.
fn pax_records(meta: &[u8]) -> Result<Vec<(String, String)>, Error> {
    let bad = || Error::Malformed("a PAX header is malformed".to_owned());
    let mut records = Vec::new();
    let mut rest = meta;
    while !rest.is_empty() {
        let space = rest.iter().position(|&b| b == b' ').ok_or_else(bad)?;
        let length: usize = std::str::from_utf8(&rest[..space]).ok().and_then(|n| n.parse().ok()).ok_or_else(bad)?;
        if length <= space + 1 || length > rest.len() || rest[length - 1] != b'\n' {
            return Err(bad());
        }
        let record = &rest[space + 1..length - 1];
        let equals = record.iter().position(|&b| b == b'=').ok_or_else(bad)?;
        records.push((text(&record[..equals]), text(&record[equals + 1..])));
        rest = &rest[length..];
    }
    Ok(records)
}

#[cfg(any(test, feature = "testing"))]
pub mod build {
    //! Tar archives made in memory for the tests, byte by byte, so a test can write what no honest tool would.

    use super::BLOCK;

    /// One header block.
    #[must_use]
    pub fn header(path: &str, flag: u8, size: u64, link: &str) -> [u8; BLOCK] {
        let mut block = [0_u8; BLOCK];
        let name = path.as_bytes();
        block[..name.len().min(100)].copy_from_slice(&name[..name.len().min(100)]);
        block[100..108].copy_from_slice(b"0000644\0");
        block[108..116].copy_from_slice(b"0000000\0");
        block[116..124].copy_from_slice(b"0000000\0");
        let size = format!("{size:011o}\0");
        block[124..136].copy_from_slice(size.as_bytes());
        block[136..148].copy_from_slice(b"00000000000\0");
        block[156] = flag;
        let link = link.as_bytes();
        block[157..157 + link.len().min(100)].copy_from_slice(&link[..link.len().min(100)]);
        block[257..263].copy_from_slice(b"ustar\0");
        block[263..265].copy_from_slice(b"00");
        block[148..156].copy_from_slice(b"        ");
        let sum: u32 = block.iter().map(|&b| u32::from(b)).sum();
        block[148..156].copy_from_slice(format!("{sum:06o}\0 ").as_bytes());
        block
    }

    /// An archive builder.
    #[derive(Default)]
    pub struct Tar(pub Vec<u8>);

    impl Tar {
        /// A regular file.
        #[must_use]
        pub fn file(mut self, path: &str, data: &[u8]) -> Self {
            self.0.extend_from_slice(&header(path, b'0', data.len() as u64, ""));
            self.0.extend_from_slice(data);
            self.0.resize(self.0.len().div_ceil(BLOCK) * BLOCK, 0);
            self
        }

        /// A link or a directory: `flag` b'1' hard, b'2' symbolic, b'5' a directory.
        #[must_use]
        pub fn entry(mut self, path: &str, flag: u8, link: &str) -> Self {
            self.0.extend_from_slice(&header(path, flag, 0, link));
            self
        }

        /// A PAX header for the next entry.
        #[must_use]
        pub fn pax(mut self, records: &[(&str, &str)]) -> Self {
            let mut body = Vec::new();
            for (key, value) in records {
                let record_tail = format!(" {key}={value}\n");
                let mut length = record_tail.len() + 1;
                while format!("{length}{record_tail}").len() != length {
                    length += 1;
                }
                body.extend_from_slice(format!("{length}{record_tail}").as_bytes());
            }
            self.0.extend_from_slice(&header("pax", b'x', body.len() as u64, ""));
            self.0.extend_from_slice(&body);
            self.0.resize(self.0.len().div_ceil(BLOCK) * BLOCK, 0);
            self
        }

        /// The archive, with its two zero blocks.
        #[must_use]
        pub fn done(mut self) -> Vec<u8> {
            self.0.extend_from_slice(&[0_u8; BLOCK * 2]);
            self.0
        }
    }
}

#[cfg(test)]
mod tests {
    use super::build::{Tar, header};
    use super::*;

    fn entries(bytes: &[u8]) -> Result<Vec<(Header, Vec<u8>)>, Error> {
        let mut reader = Reader::new(bytes);
        let mut out = Vec::new();
        while let Some(header) = reader.next_entry()? {
            let mut data = Vec::new();
            let mut buf = [0_u8; 100];
            loop {
                let n = reader.read_data(&mut buf)?;
                if n == 0 {
                    break;
                }
                data.extend_from_slice(&buf[..n]);
            }
            out.push((header, data));
        }
        Ok(out)
    }

    #[test]
    fn files_links_and_directories_are_read_in_order_with_their_data() {
        let tar = Tar::default()
            .entry("usr/", b'5', "")
            .file("usr/bin/tool", b"\x7fELFdata")
            .entry("usr/bin/same", b'1', "usr/bin/tool")
            .entry("bin", b'2', "usr/bin")
            .done();
        let read = entries(&tar).unwrap();
        let kinds: Vec<_> = read.iter().map(|(h, _)| (h.path.as_str(), h.kind, h.link.as_str())).collect();
        assert_eq!(
            kinds,
            [
                ("usr/", Kind::Directory, ""),
                ("usr/bin/tool", Kind::File, ""),
                ("usr/bin/same", Kind::HardLink, "usr/bin/tool"),
                ("bin", Kind::Symlink, "usr/bin")
            ]
        );
        assert_eq!(read[1].1, b"\x7fELFdata");
    }

    #[test]
    fn a_pax_path_and_size_replace_the_headers() {
        let long = format!("opt/{}/model.so", "a".repeat(150));
        let tar = Tar::default().pax(&[("path", &long)]).file("short", b"abc").done();
        let read = entries(&tar).unwrap();
        assert_eq!(read[0].0.path, long);
        assert_eq!(read[0].1, b"abc");
    }

    #[test]
    fn an_oversized_long_name_or_pax_header_is_refused_before_it_is_read() {
        let mut tar = header("././@LongLink", b'L', MAX_META_BYTES + 1, "").to_vec();
        tar.extend(std::iter::repeat_n(b'a', 1024));
        assert!(matches!(entries(&tar), Err(Error::Malformed(why)) if why.contains("more than we read")));
    }

    #[test]
    fn a_bad_checksum_or_unknown_type_is_refused() {
        let mut tar = Tar::default().file("a", b"x").done();
        tar[0] = b'b';
        assert!(matches!(entries(&tar), Err(Error::Malformed(why)) if why.contains("checksum")));
        let odd = Tar(header("a", b'Q', 0, "").to_vec()).done();
        assert!(matches!(entries(&odd), Err(Error::Malformed(why)) if why.contains("unknown type")));
    }

    #[test]
    fn a_stream_cut_inside_a_file_is_an_error_not_a_short_file() {
        let tar = Tar::default().file("a", &[7_u8; 2000]).done();
        assert!(entries(&tar[..BLOCK + 600]).is_err());
    }

    #[test]
    fn base_256_sizes_are_read() {
        assert_eq!(number(&[0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0]).unwrap(), 256);
        assert!(number(&[0xff; 12]).is_err(), "a size past u64 is refused");
    }
}
