//! Embeds the icon and the manifest into the Windows program (`idleo_brand`).

fn main() {
    idleo_brand::embed_windows_resources("IdleO tray");
}
