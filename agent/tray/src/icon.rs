//! The tray icon, drawn in code (ADR 0033, ADR 0060, ADR 0061): the brand's disc in the state's colour with the state's shape
//! inside it ([`idleo_brand::glyph_icon_rgba`]), drawn at the size the taskbar asks for. At 100% that is 16 pixels, at 125%
//! 20, at 150% 24 and at 200% 32, so it is crisp at every scale, never one picture stretched. A thin dark outline lets it
//! read on Windows' light taskbar as well as its dark one. No image files.

use crate::status::TrayState;

/// The icon's size at 100% scale, in pixels.
pub const BASE_SIZE: u32 = 16;

/// The pixels, in `state`, at `size` a side, as RGBA rows.
#[must_use]
pub fn rgba(state: TrayState, size: u32) -> Vec<u8> {
    idleo_brand::glyph_icon_rgba(state.colour().rgb(), state.glyph(), size)
}

/// The size the taskbar's icons are at a display `scale` (1.0 is 100%): 16 pixels at 100%, in proportion above it, never
/// under 16 or over 64.
#[must_use]
pub fn size_for_scale(scale: f64) -> u32 {
    let scaled = (f64::from(BASE_SIZE) * scale).round();
    if scaled.is_nan() {
        return BASE_SIZE;
    }
    // Clamped to 16..=64 first, so the cast can't truncate or change sign.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let size = scaled.clamp(f64::from(BASE_SIZE), 64.0) as u32;
    size
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::status::Colour;

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
    fn each_colour_is_its_own() {
        let ready = rgba(TrayState::Ready, 32);
        let middle = ((16 * 32 + 16 + 9) * 4) as usize; // beside the ring, on the fill
        assert_eq!(&ready[middle..middle + 4], &[0xC8, 0xFF, 0x3D, 255]);
        assert_eq!(&rgba(TrayState::Problem, 32)[middle..middle + 4][..3], &[0xE5, 0x48, 0x4D][..]);
        assert_eq!(&rgba(TrayState::WaitingForChecks, 32)[middle..middle + 4][..3], &[0xD9, 0xA4, 0x00][..]);
    }

    #[test]
    fn every_state_reads_on_a_light_and_a_dark_taskbar() {
        // The icon's edge is its dark outline and, inside it, the state's colour: on a dark taskbar the colour stands out
        // and on a light one the outline does, each by at least 3:1 (WCAG 1.4.11, for a graphic).
        for state in TrayState::ALL {
            let fill = state.colour().rgb();
            assert!(contrast(fill, DARK_TASKBAR) >= 3.0, "{state:?} on a dark taskbar");
            assert!(contrast(idleo_brand::INK, LIGHT_TASKBAR) >= 3.0, "the outline of {state:?} on a light taskbar");
            // And the shape inside the disc reads against the colour it is drawn on.
            assert!(contrast(idleo_brand::INK, fill) >= 4.5, "the shape on {state:?}");
        }
        assert!(
            [Colour::Volt, Colour::Grey, Colour::Amber, Colour::Red]
                .iter()
                .all(|colour| contrast(colour.rgb(), DARK_TASKBAR) >= 3.0)
        );
    }

    #[test]
    fn the_edge_of_the_disc_is_the_dark_outline_at_every_scale() {
        for size in [16_u32, 20, 24, 32, 48] {
            let pixels = rgba(TrayState::Ready, size);
            let row = (size / 2) as usize;
            let first_solid = (0..size as usize)
                .map(|x| (row * size as usize + x) * 4)
                .find(|at| pixels[*at + 3] >= 128)
                .expect("the disc has a solid edge");
            assert!(pixels[first_solid] < 0x60, "{size}: the first solid pixel from the left is the dark outline");
        }
    }

    #[test]
    fn every_state_is_a_different_picture_at_every_taskbar_size() {
        for size in [16_u32, 20, 24, 32, 40, 48] {
            let pictures: Vec<Vec<u8>> = TrayState::ALL.iter().map(|state| rgba(*state, size)).collect();
            for (i, a) in pictures.iter().enumerate() {
                assert_eq!(a.len(), (size * size * 4) as usize);
                for b in &pictures[i + 1..] {
                    assert_ne!(a, b, "two states look the same at {size} px");
                }
            }
        }
    }

    #[test]
    fn the_size_follows_the_display_scale() {
        let sizes: Vec<u32> =
            [1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 5.0, 0.5, f64::NAN].iter().map(|s| size_for_scale(*s)).collect();
        assert_eq!(sizes, [16, 20, 24, 28, 32, 48, 64, 16, 16]);
    }
}
