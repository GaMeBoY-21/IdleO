//! Bytes as lower-case hexadecimal, for names made from hashes.

use std::fmt::Write;

#[must_use]
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().fold(String::with_capacity(bytes.len() * 2), |mut out, byte| {
        let _ = write!(out, "{byte:02x}");
        out
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bytes_are_two_digits_each() {
        assert_eq!(hex(&[0, 15, 16, 255]), "000f10ff");
        assert_eq!(hex(&[]), "");
    }
}
