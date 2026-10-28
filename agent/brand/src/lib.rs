//! The Windows app's look, in code (ADR 0033, ADR 0043): the round icon, the same disc the tray shows in its state's
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

/// The brand's ink, the page's dark: #0C0A1F. The disc's thin outline and its ring are drawn in it, so the disc reads on
/// a light taskbar, where volt alone would not.
pub const INK: [u8; 3] = [0x0C, 0x0A, 0x1F];

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

/// The icon at `size` pixels a side, as RGBA rows (the brand: a disc in `rgb` with a thin dark outline and, inside it, a
/// ring with a small gap at the top, in the same dark). The outline keeps the disc readable on a light taskbar, and the
/// fill on a dark one.
#[must_use]
pub fn icon_rgba(rgb: [u8; 3], size: u32) -> Vec<u8> {
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
            let ring = (distance - inner + 0.5).clamp(0.0, 1.0) * (outer - distance + 0.5).clamp(0.0, 1.0) * open;
            let mix = |channel: u8, ink: u8| {
                let base = f64::from(ink) * (1.0 - fill) + f64::from(channel) * fill;
                byte(base * (1.0 - ring) + f64::from(ink) * ring)
            };
            pixels.extend_from_slice(&[mix(rgb[0], INK[0]), mix(rgb[1], INK[1]), mix(rgb[2], INK[2]), byte(disc * 255.0)]);
        }
    }
    pixels
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
