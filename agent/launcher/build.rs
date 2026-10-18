//! Embeds the icon and the manifest (common controls 6, for the uninstaller's task dialogs) into the Windows program.

fn main() {
    idleo_brand::embed_windows_resources("IdleO");
}
