//! Sizes for people: `17.0 KB`, `12.0 MB`, `4.6 GB`, in 1024s like the web app's `formatBytes`.

const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];

#[must_use]
#[allow(clippy::cast_precision_loss)] // A size shown to one decimal doesn't need the last bits.
pub fn format_bytes(bytes: u64) -> String {
    if bytes < 1024 {
        return format!("{bytes} B");
    }
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    format!("{value:.1} {}", UNITS[unit])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_are_told_in_the_largest_unit() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(1023), "1023 B");
        assert_eq!(format_bytes(17_448), "17.0 KB");
        assert_eq!(format_bytes(12 * 1024 * 1024), "12.0 MB");
        assert_eq!(format_bytes(5 * 1024_u64.pow(3) / 2), "2.5 GB");
    }
}
