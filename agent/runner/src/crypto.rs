//! The encrypted scratch disk (ADR 0038): each session's scratch disk is a LUKS2 volume whose key is 512 random bits from
//! the kernel's random source, made by the runner when the disk is made, held only in the runner's memory (the service
//! never swaps: `MemorySwapMax=0`), handed to `cryptsetup` through a pipe and never through a file, an argument or the
//! environment, and wiped from memory once the volume is open. At the end the volume is closed and its header erased
//! (`cryptsetup erase`) before the image is deleted, so a copy of the distribution's virtual disk holds nothing that
//! could be read: the key is gone, and so is every slot that could have unlocked the data.
//!
//! This module is the arguments and the key; `nerdctl.rs` runs them.

use zeroize::Zeroizing;

/// The cipher: AES in XTS mode, the standard for disks.
pub const CIPHER: &str = "aes-xts-plain64";
/// The key's size in bits; XTS splits it into two 256-bit AES keys.
pub const KEY_BITS: u32 = 512;
/// The key's size in bytes.
pub const KEY_BYTES: usize = (KEY_BITS / 8) as usize;
/// PBKDF2's iterations: LUKS2's minimum, for a key that is random already.
pub const PBKDF_ITERATIONS: u32 = 1000;
/// The prefix of every device-mapper name the runner makes, so a leftover one is found after a crash.
pub const MAPPER_PREFIX: &str = "idleo-";

/// A scratch disk's key: random, never written anywhere, and zeroed when dropped.
pub struct ScratchKey(Zeroizing<[u8; KEY_BYTES]>);

impl ScratchKey {
    /// A fresh key from the kernel's random source.
    ///
    /// # Errors
    /// If the random source can't be read.
    pub fn random() -> Result<Self, String> {
        let mut key = Zeroizing::new([0_u8; KEY_BYTES]);
        getrandom::fill(key.as_mut()).map_err(|e| format!("the kernel's random source couldn't be read: {e}"))?;
        Ok(Self(key))
    }

    /// The key's bytes, for `cryptsetup`'s standard input.
    #[must_use]
    pub fn bytes(&self) -> &[u8] {
        self.0.as_ref()
    }
}

impl std::fmt::Debug for ScratchKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("ScratchKey(redacted)")
    }
}

/// The device-mapper name of a scratch disk: `idleo-<key>`, where the key is `<session>-<attempt>` or a self-check's own.
///
/// # Errors
/// If the key has anything but letters, digits, `-` and `_`, or is too long for a device-mapper name.
pub fn mapper_name(key: &str) -> Result<String, String> {
    let name = format!("{MAPPER_PREFIX}{key}");
    let safe = !key.is_empty() && key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    // The kernel's limit is 127 characters (DM_NAME_LEN 128, with the terminating zero).
    if safe && name.len() <= 127 { Ok(name) } else { Err(format!("{key:?} can't name a scratch disk")) }
}

/// `cryptsetup luksFormat`: a LUKS2 volume over the image, with the key from standard input. PBKDF2 at its fewest
/// iterations (LUKS2's minimum, 1,000), since the key is 512 random bits and stretching it buys nothing: asked for a time
/// instead (`--iter-time 1`), cryptsetup still benchmarks the volume's digest and spends about 1.9 s on it (measured, the
/// development distribution), where 1,000 iterations make the whole volume in under 60 ms.
#[must_use]
pub fn format_arguments(image: &str) -> Vec<String> {
    [
        "luksFormat",
        "--batch-mode",
        "--type",
        "luks2",
        "--cipher",
        CIPHER,
        "--key-size",
        &KEY_BITS.to_string(),
        "--pbkdf",
        "pbkdf2",
        "--pbkdf-force-iterations",
        &PBKDF_ITERATIONS.to_string(),
        "--key-file",
        "-",
        image,
    ]
    .map(str::to_owned)
    .to_vec()
}

/// `cryptsetup open`: the volume as `/dev/mapper/<mapper>`, unlocked with the key from standard input.
#[must_use]
pub fn open_arguments(image: &str, mapper: &str) -> Vec<String> {
    ["open", "--type", "luks2", "--key-file", "-", image, mapper].map(str::to_owned).to_vec()
}

/// `cryptsetup close`: the mapping gone, and the loop device `cryptsetup open` made for the image with it.
#[must_use]
pub fn close_arguments(mapper: &str) -> Vec<String> {
    ["close", mapper].map(str::to_owned).to_vec()
}

/// `cryptsetup erase`: every key slot in the image's header overwritten, so nothing can unlock it again.
#[must_use]
pub fn erase_arguments(image: &str) -> Vec<String> {
    ["erase", "--batch-mode", image].map(str::to_owned).to_vec()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_key_is_512_random_bits_and_never_printed() {
        let one = ScratchKey::random().unwrap();
        let two = ScratchKey::random().unwrap();
        assert_eq!(one.bytes().len(), 64);
        assert_ne!(one.bytes(), two.bytes(), "two keys are never the same");
        assert_ne!(one.bytes(), [0_u8; 64], "a key isn't all zeroes");
        assert_eq!(format!("{one:?}"), "ScratchKey(redacted)");
    }

    #[test]
    fn the_key_goes_through_standard_input_never_an_argument() {
        let image = "/var/lib/idleo/scratch/s-1.img";
        for args in [format_arguments(image), open_arguments(image, "idleo-s-1")] {
            let at = args.iter().position(|arg| arg == "--key-file").unwrap();
            assert_eq!(args[at + 1], "-", "the key is read from standard input: {args:?}");
            assert!(!args.iter().any(|arg| arg.starts_with("--key-file=")), "{args:?}");
        }
        let format = format_arguments(image);
        for pair in [
            ["--type", "luks2"],
            ["--cipher", "aes-xts-plain64"],
            ["--key-size", "512"],
            ["--pbkdf", "pbkdf2"],
            ["--pbkdf-force-iterations", "1000"],
        ] {
            assert!(format.windows(2).any(|window| window == pair), "{pair:?} in {format:?}");
        }
        assert!(format.contains(&"--batch-mode".to_owned()), "never a question on the terminal");
        assert_eq!(format.last().map(String::as_str), Some(image));
        assert_eq!(erase_arguments(image), ["erase", "--batch-mode", image]);
        assert_eq!(close_arguments("idleo-s-1"), ["close", "idleo-s-1"]);
    }

    #[test]
    fn a_mapper_is_named_from_its_scratch_key_and_nothing_else_is_accepted() {
        let session = "01a16015-48aa-743b-ad72-3659aaea6802";
        assert_eq!(mapper_name(&format!("{session}-2")).unwrap(), format!("idleo-{session}-2"));
        assert_eq!(mapper_name("selfcheck-scratch").unwrap(), "idleo-selfcheck-scratch");
        for bad in ["", "../x", "a/b", "a b", "a\nb", &"x".repeat(200)] {
            assert!(mapper_name(bad).is_err(), "{bad:?}");
        }
    }
}
