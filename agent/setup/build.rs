//! Bakes the app's addresses into the setup program, and embeds the icon and the manifest (`asInvoker`: a program
//! called "Setup" must never ask for admin rights; it installs per user).
//!
//! The addresses come from the environment at build time: `IDLEO_SETUP_API_URL`, `IDLEO_SETUP_APP_URL`,
//! `IDLEO_SETUP_SITE_URL`, `IDLEO_SETUP_CONTROL_PLANE` (`host:port`) and `IDLEO_SETUP_CA_ROOT` (a PEM file); and, for a
//! development build only, `IDLEO_SETUP_REGISTRY` (`host:port`), where the development image registry is (ADR 0035).
//! `./start.sh --lan` sets them to the Mac's LAN address and the development CA. Without them a development build
//! points at this machine's local stack; a release build (`prod-keys`) refuses to build without every one.

use std::path::Path;

fn main() {
    idleo_brand::embed_windows_resources("IdleO setup");
    let release = std::env::var_os("CARGO_FEATURE_PROD_KEYS").is_some();
    let value = |name: &str, development: &str| {
        println!("cargo:rerun-if-env-changed={name}");
        match std::env::var(name) {
            Ok(value) if !value.trim().is_empty() => value.trim().to_owned(),
            _ => {
                assert!(!release, "{name} must be set for a release build of the setup program");
                development.to_owned()
            }
        }
    };
    let api = value("IDLEO_SETUP_API_URL", "http://localhost:8080");
    let app = value("IDLEO_SETUP_APP_URL", "http://localhost:3000");
    let site = value("IDLEO_SETUP_SITE_URL", "http://localhost:3002");
    let control_plane = value("IDLEO_SETUP_CONTROL_PLANE", "localhost:9443");
    let (host, port) = control_plane.rsplit_once(':').expect("IDLEO_SETUP_CONTROL_PLANE is host:port");
    let port: u16 = port.parse().expect("IDLEO_SETUP_CONTROL_PLANE's port is a number");
    println!("cargo:rerun-if-env-changed=IDLEO_SETUP_REGISTRY");
    let registry = std::env::var("IDLEO_SETUP_REGISTRY")
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty());
    assert!(
        !release || registry.is_none(),
        "IDLEO_SETUP_REGISTRY is for development builds: a release build has no development registry"
    );
    let manifest_dir = std::env::var("CARGO_MANIFEST_DIR").expect("cargo sets CARGO_MANIFEST_DIR");
    let dev_root = Path::new(&manifest_dir).join("../../private/dev-ca/root.pem");
    println!("cargo:rerun-if-env-changed=IDLEO_SETUP_CA_ROOT");
    println!("cargo:rerun-if-changed={}", dev_root.display());
    let root_path = std::env::var("IDLEO_SETUP_CA_ROOT").ok().filter(|path| !path.is_empty());
    assert!(
        !release || root_path.is_some(),
        "IDLEO_SETUP_CA_ROOT must be set for a release build of the setup program"
    );
    let root = root_path.map_or_else(
        || std::fs::read_to_string(&dev_root).unwrap_or_default(),
        |path| std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("IDLEO_SETUP_CA_ROOT {path}: {e}")),
    );
    if let Some(path) = std::env::var_os("IDLEO_SETUP_CA_ROOT") {
        println!("cargo:rerun-if-changed={}", Path::new(&path).display());
    }
    let out = Path::new(&std::env::var("OUT_DIR").expect("cargo sets OUT_DIR")).join("setup_config.rs");
    std::fs::write(
        out,
        format!(
            "/// The addresses and root baked into this build.\n#[must_use]\npub fn baked() -> crate::steps::Config {{\n    crate::steps::Config {{\n        api_url: {api:?}.to_owned(),\n        app_url: {app:?}.to_owned(),\n        site_url: {site:?}.to_owned(),\n        control_plane_host: {host:?}.to_owned(),\n        control_plane_port: {port},\n        ca_root_pem: {root:?}.to_owned(),\n        registry: {registry:?}.map(str::to_owned),\n    }}\n}}\n",
            registry = registry.as_deref()
        ),
    )
    .expect("OUT_DIR is writable");
}
