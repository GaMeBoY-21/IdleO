//! The GPU memory classes renters choose between (ADR 0022): 8, 12, 16 and 24 GB. A card belongs to the largest class
//! its memory covers, as the server counts it (`GpuClasses`): a class asks for its size in thousands of MB, which
//! every card sold under that size has.

/// The classes, in GB.
pub const CLASSES_GB: [u32; 4] = [8, 12, 16, 24];

/// The class a card of this many MB belongs to, if it reaches the smallest.
#[must_use]
pub fn class_of(vram_mb: i32) -> Option<u32> {
    let mb = u32::try_from(vram_mb).ok()?;
    CLASSES_GB.iter().rev().find(|&&gb| gb * 1_000 <= mb).copied()
}

/// What `--gpu-memory 12GB` asks for, in MB: a class's size in thousands of MB.
///
/// # Errors
/// If it isn't a size like `12GB`, `12 gb` or `12`, or isn't one of the classes.
pub fn parse_gpu_memory(text: &str) -> Result<u32, String> {
    let digits = text.trim().to_ascii_lowercase();
    let digits = digits.trim_end_matches("gb").trim_end_matches('g').trim();
    let gb: u32 = digits.parse().map_err(|_| format!("{text:?} isn't a GPU memory size like 12GB"))?;
    if CLASSES_GB.contains(&gb) {
        Ok(gb * 1_000)
    } else {
        Err(format!("GPU memory comes in 8, 12, 16 or 24 GB; {text:?} isn't one of them"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_card_belongs_to_the_largest_class_it_covers() {
        assert_eq!(class_of(8_188), Some(8));
        assert_eq!(class_of(12_282), Some(12));
        assert_eq!(class_of(16_376), Some(16));
        assert_eq!(class_of(24_564), Some(24));
        assert_eq!(class_of(11_999), Some(8));
        assert_eq!(class_of(6_000), None);
        assert_eq!(class_of(-1), None);
    }

    #[test]
    fn a_size_is_a_class_in_thousands_of_mb() {
        for text in ["12GB", "12gb", "12 GB", " 12 ", "12g"] {
            assert_eq!(parse_gpu_memory(text), Ok(12_000), "{text}");
        }
        assert_eq!(parse_gpu_memory("24GB"), Ok(24_000));
    }

    #[test]
    fn anything_else_is_refused() {
        for text in ["", "10GB", "x", "-8GB", "12.5GB", "12MB"] {
            assert!(parse_gpu_memory(text).is_err(), "{text}");
        }
    }
}
