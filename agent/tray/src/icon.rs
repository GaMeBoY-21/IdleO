//! The tray icon, drawn in code (ADR 0033): the brand's disc ([`idleo_brand::icon_rgba`]) at 32×32, in the state's
//! colour. No image files.

use crate::status::Colour;

/// The icon's size in pixels, each side.
pub const SIZE: u32 = 32;

/// The icon as RGBA bytes, row by row.
#[must_use]
pub fn rgba(colour: Colour) -> Vec<u8> {
    idleo_brand::icon_rgba(colour.rgb(), SIZE)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_state_has_its_own_colour() {
        let green = rgba(Colour::Green);
        let middle = ((16 * SIZE + 16) * 4) as usize;
        assert_eq!(&green[middle..middle + 4], &[0x65, 0xA3, 0x0D, 255]);
        assert_eq!(&rgba(Colour::Red)[middle..middle + 4], &[0xDC, 0x26, 0x26, 255]);
        assert_ne!(rgba(Colour::Grey), green);
    }
}
