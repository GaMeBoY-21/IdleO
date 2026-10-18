//! Bakes the release key into the build (ADR 0034).
//!
//! - With `--features prod-keys`: `agent/release-keys/release.pub`, the production key. The build fails while it's
//!   empty, so a release can never ship without one.
//! - Otherwise: the development key, from `IDLEO_RELEASE_DEV_PUBLIC_KEY` (base64) or `private/release-dev/release.pub`
//!   (made by `scripts/release/dev-key.sh`). Without either, the build has no key and refuses every release, which is
//!   right for CI and tests: they sign with keys of their own and pass them in.
//!
//! Only public keys are ever read here.

use std::path::Path;

fn main() {
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").expect("cargo sets CARGO_MANIFEST_DIR");
    let crate_dir = Path::new(&manifest_dir);
    let production = Path::new(&crate_dir).join("../release-keys/release.pub");
    let development = Path::new(&crate_dir).join("../../private/release-dev/release.pub");
    println!("cargo:rerun-if-changed={}", production.display());
    println!("cargo:rerun-if-changed={}", development.display());
    println!("cargo:rerun-if-env-changed=IDLEO_RELEASE_DEV_PUBLIC_KEY");

    let prod = std::env::var_os("CARGO_FEATURE_PROD_KEYS").is_some();
    let key = if prod {
        let text = std::fs::read_to_string(&production).unwrap_or_default();
        assert!(
            !text.trim().is_empty(),
            "agent/release-keys/release.pub is empty: make the production key with scripts/release/new-release-key.sh \
             before a release build"
        );
        Some(decode(text.trim(), "agent/release-keys/release.pub"))
    } else if let Ok(text) = std::env::var("IDLEO_RELEASE_DEV_PUBLIC_KEY") {
        Some(decode(text.trim(), "IDLEO_RELEASE_DEV_PUBLIC_KEY"))
    } else if let Ok(text) = std::fs::read_to_string(&development) {
        Some(decode(text.trim(), "private/release-dev/release.pub"))
    } else {
        None
    };
    let out = Path::new(&std::env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("release_key.rs");
    let key = key.map_or_else(|| "None".to_owned(), |bytes| format!("Some({bytes:?})"));
    std::fs::write(
        out,
        format!(
            "/// The release key baked into this build, the raw Ed25519 public key.\n\
             pub const RELEASE_KEY: Option<[u8; 32]> = {key};\n\
             /// True for a normal build (the development key), false for a release build (`prod-keys`).\n\
             pub const DEV_BUILD: bool = {};\n",
            !prod
        ),
    )
    .expect("OUT_DIR is writable");
}

/// Base64 of 32 bytes, decoded without a dependency: the build script stays small.
fn decode(text: &str, from: &str) -> [u8; 32] {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut bits = 0u32;
    let mut count = 0;
    let mut out = Vec::with_capacity(32);
    for byte in text.bytes().filter(|byte| *byte != b'=') {
        let value = ALPHABET.iter().position(|c| *c == byte).unwrap_or_else(|| panic!("{from} isn't base64"));
        bits = (bits << 6) | u32::try_from(value).expect("under 64");
        count += 6;
        if count >= 8 {
            count -= 8;
            out.push(u8::try_from((bits >> count) & 0xff).expect("masked to a byte"));
        }
    }
    out.try_into().unwrap_or_else(|_| panic!("{from} isn't a 32-byte Ed25519 public key"))
}
