//! The Windows app's look, in code (ADR 0033, ADR 0060): the round icon, the same disc the tray shows in its state's
//! colour and every program carries as its own icon, and the manifest each program embeds. No image files.
//!
//! The manifest matters as much as the icon:
//! - `asInvoker`: Windows guesses that a program called "Setup" installs for all users and asks for admin rights;
//!   ours installs per user and must never ask.
//! - Common controls 6: the uninstaller's task dialogs need it.
//! - Per-monitor DPI awareness: sharp on a laptop's high-DPI screen.
#![forbid(unsafe_code)]

/// The brand's volt, as the web app's primary and a ready tray use it: #C8FF3D.
pub const VOLT: [u8; 3] = [0xC8, 0xFF, 0x3D];

/// The brand's ink, the canvas's dark: #08090A. The disc's thin outline and its ring are drawn in it, so the disc reads on
/// a light taskbar, where volt alone would not.
pub const INK: [u8; 3] = [0x08, 0x09, 0x0A];

/// The manifest every Windows program of the app embeds.
pub const MANIFEST: &str = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity type="win32" name="IdleO.App" version="1.0.0.0"/>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3">
    <security>
      <requestedPrivileges>
        <requestedExecutionLevel level="asInvoker" uiAccess="false"/>
      </requestedPrivileges>
    </security>
  </trustInfo>
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application>
      <supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/>
    </application>
  </compatibility>
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware>
      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>
    </windowsSettings>
  </application>
  <dependency>
    <dependentAssembly>
      <assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0"
        processorArchitecture="*" publicKeyToken="6595b64144ccf1df" language="*"/>
    </dependentAssembly>
  </dependency>
</assembly>
"#;

/// How wide the disc's outline is, in pixels at 32 pixels a side (it scales with the icon).
const OUTLINE: f64 = 1.5;
/// The ring's inner and outer radius at 32 pixels a side.
const RING: (f64, f64) = (5.0, 7.4);
/// Half the ring's gap at the top, in radians: the ring is a power button at rest.
const GAP: f64 = 0.42;

/// What is drawn inside the disc, in the brand's dark: the tray's states differ by shape as well as colour, so a colour-blind
/// host (and anyone on a taskbar with a tint) still tells them apart.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Glyph {
    /// The power ring at rest: ready.
    Ring,
    /// A triangle: running a job.
    Play,
    /// Two bars: paused.
    Pause,
    /// A clock's hands: waiting for IdleO's checks.
    Clock,
    /// A cross: offline.
    Cross,
    /// An exclamation mark: a problem.
    Exclaim,
    /// An arrow up: an update is ready.
    Up,
}

impl Glyph {
    /// Every glyph.
    pub const ALL: [Self; 7] = [Self::Ring, Self::Play, Self::Pause, Self::Clock, Self::Cross, Self::Exclaim, Self::Up];

    /// Whether the point `(x, y)`, in a 32-unit square centred on the disc (y down), is inside the glyph.
    fn contains(self, x: f64, y: f64) -> bool {
        let segment = |ax: f64, ay: f64, bx: f64, by: f64, half: f64| {
            let (dx, dy) = (bx - ax, by - ay);
            let along = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).clamp(0.0, 1.0);
            let (nx, ny) = (ax + dx * along - x, ay + dy * along - y);
            nx * nx + ny * ny <= half * half
        };
        let rectangle =
            |left: f64, top: f64, right: f64, bottom: f64| (left..=right).contains(&x) && (top..=bottom).contains(&y);
        let triangle = |a: (f64, f64), b: (f64, f64), c: (f64, f64)| {
            let side = |p: (f64, f64), q: (f64, f64)| (q.0 - p.0) * (y - p.1) - (q.1 - p.1) * (x - p.0);
            let (d1, d2, d3) = (side(a, b), side(b, c), side(c, a));
            !((d1 < 0.0 || d2 < 0.0 || d3 < 0.0) && (d1 > 0.0 || d2 > 0.0 || d3 > 0.0))
        };
        let distance = x.hypot(y);
        match self {
            Self::Ring => false,
            Self::Play => triangle((-4.5, -7.5), (-4.5, 7.5), (8.0, 0.0)),
            Self::Pause => rectangle(-6.5, -7.0, -2.0, 7.0) || rectangle(2.0, -7.0, 6.5, 7.0),
            Self::Clock => {
                (RING.0..=RING.1 + 0.6).contains(&distance)
                    || segment(0.0, 0.0, 0.0, -5.0, 1.1)
                    || segment(0.0, 0.0, 3.8, 2.2, 1.1)
            }
            Self::Cross => segment(-6.5, -6.5, 6.5, 6.5, 1.5) || segment(-6.5, 6.5, 6.5, -6.5, 1.5),
            Self::Exclaim => rectangle(-1.5, -8.5, 1.5, 2.5) || x.hypot(y - 6.4) <= 1.9,
            Self::Up => triangle((-7.0, 0.0), (7.0, 0.0), (0.0, -8.0)) || rectangle(-2.2, -0.5, 2.2, 8.5),
        }
    }
}

/// The icon at `size` pixels a side, as RGBA rows (the brand: a disc in `rgb` with a thin dark outline and, inside it, a
/// ring with a small gap at the top, in the same dark). The outline keeps the disc readable on a light taskbar, and the
/// fill on a dark one.
#[must_use]
pub fn icon_rgba(rgb: [u8; 3], size: u32) -> Vec<u8> {
    glyph_icon_rgba(rgb, Glyph::Ring, size)
}

/// The icon with `glyph` inside the disc, at `size` pixels a side. Drawn from shapes at the size asked for, never scaled
/// from another, so it is as crisp at 16 pixels (100%) as at 48 (300%).
#[must_use]
pub fn glyph_icon_rgba(rgb: [u8; 3], glyph: Glyph, size: u32) -> Vec<u8> {
    let scale = f64::from(size) / 32.0;
    let centre = f64::from(size) / 2.0;
    let radius = centre - 0.5 * scale;
    let inside = radius - OUTLINE * scale;
    let (inner, outer) = (RING.0 * scale, RING.1 * scale);
    let mut pixels = Vec::with_capacity((size * size * 4) as usize);
    for y in 0..size {
        for x in 0..size {
            let dx = f64::from(x) + 0.5 - centre;
            let dy = f64::from(y) + 0.5 - centre;
            let distance = (dx * dx + dy * dy).sqrt();
            let disc = (radius - distance + 0.5).clamp(0.0, 1.0);
            let fill = (inside - distance + 0.5).clamp(0.0, 1.0);
            // The angle from straight up: 0 at the top, π at the bottom. The ring is missing within `GAP` of the top.
            let angle = dx.atan2(-dy).abs();
            let open = ((angle - GAP) * distance + 0.5).clamp(0.0, 1.0);
            let ring = if glyph == Glyph::Ring {
                (distance - inner + 0.5).clamp(0.0, 1.0) * (outer - distance + 0.5).clamp(0.0, 1.0) * open
            } else {
                // The glyph's coverage, from 4 × 4 samples inside the pixel, in the 32-unit square.
                let mut covered = 0.0;
                for sy in 0..4 {
                    for sx in 0..4 {
                        let u = (f64::from(x) + (f64::from(sx) + 0.5) / 4.0 - centre) / scale;
                        let v = (f64::from(y) + (f64::from(sy) + 0.5) / 4.0 - centre) / scale;
                        if glyph.contains(u, v) {
                            covered += 1.0 / 16.0;
                        }
                    }
                }
                covered
            };
            let mix = |channel: u8, ink: u8| {
                let base = f64::from(ink) * (1.0 - fill) + f64::from(channel) * fill;
                byte(base * (1.0 - ring) + f64::from(ink) * ring)
            };
            pixels.extend_from_slice(&[
                mix(rgb[0], INK[0]),
                mix(rgb[1], INK[1]),
                mix(rgb[2], INK[2]),
                byte(disc * 255.0),
            ]);
        }
    }
    pixels
}

#[cfg(test)]
// Pixel coordinates in tests are small whole numbers: the casts between them and `f64` are exact.
#[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation, clippy::cast_sign_loss, clippy::type_complexity)]
mod glyph_tests {
    use super::*;

    #[test]
    fn the_ring_is_the_icon_every_program_carries_and_the_other_glyphs_are_not_it() {
        for size in [16, 20, 24, 32, 48] {
            assert_eq!(icon_rgba(VOLT, size), glyph_icon_rgba(VOLT, Glyph::Ring, size));
            let ring = glyph_icon_rgba(VOLT, Glyph::Ring, size);
            for glyph in Glyph::ALL.into_iter().filter(|glyph| *glyph != Glyph::Ring) {
                assert_ne!(glyph_icon_rgba(VOLT, glyph, size), ring, "{glyph:?} at {size}");
            }
        }
    }

    #[test]
    fn every_glyph_is_drawn_in_ink_inside_the_disc_at_every_size_and_no_two_are_alike() {
        let ink_pixels = |glyph: Glyph, size: u32| {
            let pixels = glyph_icon_rgba(VOLT, glyph, size);
            let centre = f64::from(size) / 2.0;
            let mut count = 0;
            for (index, pixel) in pixels.as_chunks::<4>().0.iter().enumerate() {
                let (x, y) =
                    ((index % size as usize) as f64 + 0.5 - centre, (index / size as usize) as f64 + 0.5 - centre);
                // Strictly inside the disc's outline, where only a glyph is dark.
                if x.hypot(y) < centre - 2.5 * f64::from(size) / 32.0 && pixel[0] < 0x60 {
                    count += 1;
                }
            }
            count
        };
        for size in [16, 20, 24, 32, 48, 64] {
            for glyph in Glyph::ALL {
                assert!(ink_pixels(glyph, size) >= 5, "{glyph:?} at {size} px has no mark inside the disc");
            }
        }
        let all: Vec<Vec<u8>> = Glyph::ALL.iter().map(|glyph| glyph_icon_rgba(VOLT, *glyph, 32)).collect();
        for (i, a) in all.iter().enumerate() {
            for b in &all[i + 1..] {
                assert_ne!(a, b);
            }
        }
    }

    #[test]
    fn a_glyph_is_in_the_same_places_at_every_size() {
        // Drawn from shapes, not scaled: the same points, as fractions of the icon, are ink (true) or the disc's colour
        // (false) at 16 pixels as at 64.
        let cases: [(Glyph, &[(f64, f64, bool)]); 3] = [
            (Glyph::Pause, &[(0.367, 0.5, true), (0.633, 0.5, true), (0.5, 0.5, false), (0.367, 0.2, false)]),
            (Glyph::Exclaim, &[(0.5, 0.35, true), (0.5, 0.7, true), (0.5, 0.6, false), (0.3, 0.4, false)]),
            (Glyph::Up, &[(0.5, 0.4, true), (0.5, 0.7, true), (0.25, 0.7, false), (0.5, 0.74, true)]),
        ];
        for (glyph, points) in cases {
            for size in [16_usize, 24, 32, 64] {
                let pixels = glyph_icon_rgba(VOLT, glyph, u32::try_from(size).unwrap());
                for (x, y, ink) in points {
                    let index = (((y * size as f64) as usize) * size + (x * size as f64) as usize) * 4;
                    assert_eq!(pixels[index] < 0x60, *ink, "{glyph:?} at {size} px, ({x}, {y})");
                }
            }
        }
    }

    #[test]
    fn the_base64_of_a_font_is_standard_and_padded() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn a_page_has_the_font_the_design_and_no_way_to_reach_outside() {
        let page = page("IdleO", ".x { color: red; }", "<main id=\"app\"></main>", "window.ok = true;");
        assert!(page.contains("font/woff2;base64,d09GMg"), "the Inter font is in the page");
        assert!(!page.contains("/*FONT_INTER*/"));
        assert!(page.contains("--primary: #c8ff3d") && page.contains(".x { color: red; }"));
        assert!(page.contains("default-src 'none'"), "nothing fetched");
        assert!(page.contains("<main id=\"app\">") && page.contains("window.ok = true;"));
        assert!(!page.contains("http://") && !page.contains("https://"), "no address anywhere");
    }
}

/// 0.0 to 255.0, rounded to a byte.
fn byte(value: f64) -> u8 {
    // Clamped to 0..=255 first, so the cast can't truncate or wrap.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let byte = value.round().clamp(0.0, 255.0) as u8;
    byte
}

/// The icon as an `.ico` file, at 16, 24, 32, 48 and 64 pixels, each a 32-bit bitmap.
#[must_use]
pub fn ico(rgb: [u8; 3]) -> Vec<u8> {
    let sizes = [16u32, 24, 32, 48, 64];
    let images: Vec<Vec<u8>> = sizes.iter().map(|size| bitmap(&icon_rgba(rgb, *size), *size)).collect();
    let mut out = Vec::new();
    out.extend_from_slice(&[0, 0, 1, 0]);
    out.extend_from_slice(&u16::try_from(sizes.len()).unwrap_or(0).to_le_bytes());
    let mut offset = 6 + 16 * sizes.len();
    for (size, image) in sizes.iter().zip(&images) {
        let side = u8::try_from(*size).unwrap_or(0);
        out.extend_from_slice(&[side, side, 0, 0]);
        out.extend_from_slice(&1u16.to_le_bytes());
        out.extend_from_slice(&32u16.to_le_bytes());
        out.extend_from_slice(&u32::try_from(image.len()).unwrap_or(0).to_le_bytes());
        out.extend_from_slice(&u32::try_from(offset).unwrap_or(0).to_le_bytes());
        offset += image.len();
    }
    for image in images {
        out.extend_from_slice(&image);
    }
    out
}

/// One icon image: a BITMAPINFOHEADER, the pixels bottom row first as BGRA, then an all-clear AND mask.
fn bitmap(rgba: &[u8], size: u32) -> Vec<u8> {
    let mut out = Vec::new();
    let width = i32::try_from(size).unwrap_or(0);
    out.extend_from_slice(&40u32.to_le_bytes());
    out.extend_from_slice(&width.to_le_bytes());
    out.extend_from_slice(&(width * 2).to_le_bytes());
    out.extend_from_slice(&1u16.to_le_bytes());
    out.extend_from_slice(&32u16.to_le_bytes());
    out.extend_from_slice(&[0; 24]);
    let row = (size * 4) as usize;
    for y in (0..size as usize).rev() {
        for pixel in rgba[y * row..(y + 1) * row].as_chunks::<4>().0 {
            out.extend_from_slice(&[pixel[2], pixel[1], pixel[0], pixel[3]]);
        }
    }
    let mask_row = (size as usize).div_ceil(32) * 4;
    out.extend(std::iter::repeat_n(0, mask_row * size as usize));
    out
}

/// For a build script: when building for Windows, writes the icon, the manifest and the version information into
/// `OUT_DIR` and compiles them into the program. Does nothing for another target.
///
/// # Panics
/// If `OUT_DIR` can't be written, as a build script should.
#[cfg(feature = "build")]
pub fn embed_windows_resources(description: &str) {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }
    let out = std::path::PathBuf::from(std::env::var("OUT_DIR").expect("cargo sets OUT_DIR"));
    std::fs::write(out.join("idleo.ico"), ico(VOLT)).expect("OUT_DIR is writable");
    std::fs::write(out.join("idleo.manifest"), MANIFEST).expect("OUT_DIR is writable");
    println!("cargo:rerun-if-env-changed=IDLEO_APP_VERSION");
    let version =
        std::env::var("IDLEO_APP_VERSION").or_else(|_| std::env::var("CARGO_PKG_VERSION")).unwrap_or_default();
    let numbers: Vec<&str> = version.split(['-', '+']).next().unwrap_or("0.0.0").split('.').collect();
    let comma = format!(
        "{},{},{},0",
        numbers.first().unwrap_or(&"0"),
        numbers.get(1).unwrap_or(&"0"),
        numbers.get(2).unwrap_or(&"0")
    );
    let script = format!(
        "1 ICON \"idleo.ico\"\n1 24 \"idleo.manifest\"\n1 VERSIONINFO\nFILEVERSION {comma}\nPRODUCTVERSION {comma}\nBEGIN\n  BLOCK \"StringFileInfo\"\n  BEGIN\n    BLOCK \"040904B0\"\n    BEGIN\n      VALUE \"CompanyName\", \"IdleO\"\n      VALUE \"FileDescription\", \"{description}\"\n      VALUE \"FileVersion\", \"{version}\"\n      VALUE \"ProductName\", \"IdleO\"\n      VALUE \"ProductVersion\", \"{version}\"\n    END\n  END\n  BLOCK \"VarFileInfo\"\n  BEGIN\n    VALUE \"Translation\", 0x409, 1200\n  END\nEND\n"
    );
    let rc = out.join("idleo.rc");
    std::fs::write(&rc, script).expect("OUT_DIR is writable");
    embed_resource::compile(&rc, embed_resource::NONE).manifest_required().expect("the Windows resources compile");
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pixel(bytes: &[u8], size: u32, x: u32, y: u32) -> [u8; 4] {
        let at = ((y * size + x) * 4) as usize;
        [bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]
    }

    #[test]
    fn the_icon_is_a_disc_with_a_dark_outline_and_a_ring_open_at_the_top() {
        let volt = icon_rgba(VOLT, 32);
        assert_eq!(volt.len(), 32 * 32 * 4);
        assert_eq!(pixel(&volt, 32, 0, 0)[3], 0, "the corner is clear");
        assert_eq!(pixel(&volt, 32, 16, 16), [0xC8, 0xFF, 0x3D, 255], "the middle is the colour");
        assert_eq!(pixel(&volt, 32, 22, 16), [INK[0], INK[1], INK[2], 255], "the ring is dark");
        assert_eq!(pixel(&volt, 32, 16, 9), [0xC8, 0xFF, 0x3D, 255], "the ring is open at the top");
        // The edge of the disc, on its left: a dark outline, not the colour.
        assert_eq!(pixel(&volt, 32, 1, 16), [INK[0], INK[1], INK[2], 255]);
        assert_eq!(icon_rgba(VOLT, 64).len(), 64 * 64 * 4);
    }

    #[test]
    fn the_ico_file_holds_five_sizes() {
        let file = ico(VOLT);
        assert_eq!(&file[..4], &[0, 0, 1, 0]);
        assert_eq!(u16::from_le_bytes([file[4], file[5]]), 5);
        assert_eq!(file[6], 16, "the first image is 16 pixels");
        let last = 6 + 16 * 4;
        assert_eq!(file[last], 64);
        let size = u32::from_le_bytes(file[last + 8..last + 12].try_into().unwrap()) as usize;
        let offset = u32::from_le_bytes(file[last + 12..last + 16].try_into().unwrap()) as usize;
        assert_eq!(offset + size, file.len(), "the images fill the file");
    }

    #[test]
    fn the_manifest_never_asks_for_admin_rights() {
        assert!(MANIFEST.contains(r#"level="asInvoker""#));
        assert!(MANIFEST.contains("Microsoft.Windows.Common-Controls"));
    }
}

/// The shared design: tokens and components, as a style sheet with the font still to go in (`/*FONT_INTER*/`).
pub const UI_CSS: &str = include_str!("../assets/ui.css");
/// The face of the web app (web/DESIGN.md "Typography"): Inter, for everything. OFL; the licence is
/// `assets/fonts/LICENSE-Inter.txt`, shipped beside the programs in the app zip. Embedded, never fetched; it is the file the
/// web app serves (a test in web/ keeps the two the same).
pub const INTER_WOFF2: &[u8] = include_bytes!("../assets/fonts/inter-400-700.woff2");

/// Standard base64 of `bytes`: the font goes into the style sheet as a data address, and this crate has no dependencies.
#[must_use]
pub fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let triple = u32::from(chunk[0]) << 16
            | u32::from(*chunk.get(1).unwrap_or(&0)) << 8
            | u32::from(*chunk.get(2).unwrap_or(&0));
        for (i, shift) in [18, 12, 6, 0].into_iter().enumerate() {
            if i <= chunk.len() {
                out.push(ALPHABET[(triple >> shift & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// A whole page for a WebView2 window: the design, the font, the page's own style sheet and script, and a content-security
/// policy that lets nothing in from outside (no network, no storage, inline script and style only, the font and images as
/// data). `body` is the page's markup, `title` its title.
#[must_use]
pub fn page(title: &str, style: &str, body: &str, script: &str) -> String {
    let css = format!("{UI_CSS}\n{style}").replace("/*FONT_INTER*/", &base64(INTER_WOFF2));
    format!(
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; img-src data:\">\n<title>{title}</title>\n<style>{css}</style>\n</head>\n<body>\n{body}\n<script>{script}</script>\n</body>\n</html>\n"
    )
}

/// The preview's fake channel (`dev/preview-shim.js`): development only, never put in a program's page. The browser preview
/// (`cargo run -p setup --example preview`) puts it in front of a page's own script.
pub const PREVIEW_SHIM: &str = include_str!("../dev/preview-shim.js");
