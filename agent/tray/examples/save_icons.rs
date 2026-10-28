//! Saves the tray's four icons as pictures, for the product owner to review (`pnpm screenshots tray` runs this): each state
//! at 256 pixels on a clear ground, and as the taskbar shows it (32 pixels, magnified eight times, pixel for pixel) on
//! Windows' dark and light taskbars.
//!
//! `cargo run -p tray --example save_icons -- <folder>`
//!
//! The PNG is written by hand (stored, uncompressed blocks), so this adds no dependency.

use std::path::PathBuf;

use idleo_tray::icon;
use idleo_tray::status::Colour;

/// The taskbars the icon sits on: Windows 11 dark and light.
const TASKBARS: [(&str, [u8; 3]); 2] = [("dark", [0x20, 0x20, 0x20]), ("light", [0xF3, 0xF3, 0xF3])];
/// How much the taskbar's 32-pixel icon is magnified, and the margin around it, in pictures of it.
const ZOOM: usize = 8;
const MARGIN: usize = 32;

fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFF_u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 == 1 { (crc >> 1) ^ 0xEDB8_8320 } else { crc >> 1 };
        }
    }
    !crc
}

fn adler32(bytes: &[u8]) -> u32 {
    let (mut a, mut b) = (1_u32, 0_u32);
    for byte in bytes {
        a = (a + u32::from(*byte)) % 65_521;
        b = (b + a) % 65_521;
    }
    (b << 16) | a
}

fn chunk(out: &mut Vec<u8>, kind: [u8; 4], data: &[u8]) {
    out.extend_from_slice(&u32::try_from(data.len()).expect("a chunk fits").to_be_bytes());
    let mut body = kind.to_vec();
    body.extend_from_slice(data);
    out.extend_from_slice(&body);
    out.extend_from_slice(&crc32(&body).to_be_bytes());
}

/// An RGBA picture as a PNG file.
fn png(rgba: &[u8], width: usize, height: usize) -> Vec<u8> {
    let mut raw = Vec::with_capacity((width * 4 + 1) * height);
    for row in rgba.chunks(width * 4) {
        raw.push(0);
        raw.extend_from_slice(row);
    }
    let mut zlib = vec![0x78, 0x01];
    let blocks: Vec<&[u8]> = raw.chunks(65_535).collect();
    for (index, block) in blocks.iter().enumerate() {
        let length = u16::try_from(block.len()).expect("a block fits");
        zlib.push(u8::from(index + 1 == blocks.len()));
        zlib.extend_from_slice(&length.to_le_bytes());
        zlib.extend_from_slice(&(!length).to_le_bytes());
        zlib.extend_from_slice(block);
    }
    zlib.extend_from_slice(&adler32(&raw).to_be_bytes());
    let mut header = Vec::new();
    header.extend_from_slice(&u32::try_from(width).expect("a width").to_be_bytes());
    header.extend_from_slice(&u32::try_from(height).expect("a height").to_be_bytes());
    header.extend_from_slice(&[8, 6, 0, 0, 0]);
    let mut out = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    chunk(&mut out, *b"IHDR", &header);
    chunk(&mut out, *b"IDAT", &zlib);
    chunk(&mut out, *b"IEND", &[]);
    out
}

/// The 32-pixel icon magnified on a taskbar's colour, the way it is blended there.
fn on_taskbar(colour: Colour, taskbar: [u8; 3]) -> (Vec<u8>, usize) {
    let side = usize::try_from(icon::SIZE).expect("a size") * ZOOM + 2 * MARGIN;
    let small = icon::rgba(colour);
    let mut out = Vec::with_capacity(side * side * 4);
    for y in 0..side {
        for x in 0..side {
            let inside = |value: usize| value >= MARGIN && value < side - MARGIN;
            let alpha = if inside(x) && inside(y) {
                let at =
                    (((y - MARGIN) / ZOOM) * usize::try_from(icon::SIZE).expect("a size") + (x - MARGIN) / ZOOM) * 4;
                Some((&small[at..at + 3], u32::from(small[at + 3])))
            } else {
                None
            };
            for channel in 0..3 {
                let value = alpha.map_or(u32::from(taskbar[channel]), |(rgb, alpha)| {
                    (u32::from(rgb[channel]) * alpha + u32::from(taskbar[channel]) * (255 - alpha) + 127) / 255
                });
                out.push(u8::try_from(value).expect("a byte"));
            }
            out.push(255);
        }
    }
    (out, side)
}

fn main() {
    let folder = PathBuf::from(std::env::args().nth(1).expect("usage: save_icons <folder>"));
    std::fs::create_dir_all(&folder).expect("the folder can be made");
    for (name, colour) in
        [("volt", Colour::Volt), ("grey", Colour::Grey), ("ember", Colour::Ember), ("red", Colour::Red)]
    {
        let big = idleo_brand::icon_rgba(colour.rgb(), 256);
        std::fs::write(folder.join(format!("tray-{name}--256.png")), png(&big, 256, 256)).expect("a picture is saved");
        for (taskbar, rgb) in TASKBARS {
            let (pixels, side) = on_taskbar(colour, rgb);
            std::fs::write(folder.join(format!("tray-{name}--taskbar-{taskbar}.png")), png(&pixels, side, side))
                .expect("a picture is saved");
        }
    }
}
