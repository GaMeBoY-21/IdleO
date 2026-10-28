//! The tray icon, drawn in code (ADR 0033, ADR 0043): the brand's disc ([`idleo_brand::icon_rgba`]) at 32×32, in the
//! state's colour, with a thin dark outline so it reads on Windows' light taskbar as well as its dark one. No image
//! files.

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

    /// WCAG relative luminance of an sRGB colour.
    fn luminance(rgb: [u8; 3]) -> f64 {
        let linear = |channel: u8| {
            let value = f64::from(channel) / 255.0;
            if value <= 0.04045 { value / 12.92 } else { ((value + 0.055) / 1.055).powf(2.4) }
        };
        0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2])
    }

    /// WCAG contrast ratio of two colours.
    fn contrast(a: [u8; 3], b: [u8; 3]) -> f64 {
        let (a, b) = (luminance(a), luminance(b));
        (a.max(b) + 0.05) / (a.min(b) + 0.05)
    }

    /// Windows 11's taskbar, dark and light.
    const DARK_TASKBAR: [u8; 3] = [0x20, 0x20, 0x20];
    const LIGHT_TASKBAR: [u8; 3] = [0xF3, 0xF3, 0xF3];

    #[test]
    fn each_state_has_its_own_colour() {
        let volt = rgba(Colour::Volt);
        let middle = ((16 * SIZE + 16) * 4) as usize;
        assert_eq!(&volt[middle..middle + 4], &[0xC8, 0xFF, 0x3D, 255]);
        assert_eq!(&rgba(Colour::Red)[middle..middle + 4], &[0xF0, 0x44, 0x38, 255]);
        assert_eq!(&rgba(Colour::Ember)[middle..middle + 4], &[0xFF, 0x7A, 0x2F, 255]);
        assert_ne!(rgba(Colour::Grey), volt);
    }

    #[test]
    fn every_state_reads_on_a_light_and_a_dark_taskbar() {
        // The icon's edge is its dark outline and, inside it, the state's colour: on a dark taskbar the colour stands out
        // and on a light one the outline does, each by at least 3:1 (WCAG 1.4.11, for a graphic).
        for colour in [Colour::Volt, Colour::Grey, Colour::Ember, Colour::Red] {
            let fill = colour.rgb();
            assert!(contrast(fill, DARK_TASKBAR) >= 3.0, "{colour:?} on a dark taskbar");
            assert!(contrast(idleo_brand::INK, LIGHT_TASKBAR) >= 3.0, "the outline of {colour:?} on a light taskbar");
            // And the ring inside the disc reads against the colour it is drawn on.
            assert!(contrast(idleo_brand::INK, fill) >= 4.5, "the ring on {colour:?}");
        }
    }

    #[test]
    fn the_edge_of_the_disc_is_the_dark_outline() {
        let pixels = rgba(Colour::Volt);
        let at = ((16 * SIZE + 1) * 4) as usize;
        assert_eq!(&pixels[at..at + 4], &[0x0C, 0x0A, 0x1F, 255]);
    }
}
