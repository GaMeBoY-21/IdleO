//! The setup window's page (ADR 0033, ADR 0061): the markup, style and script in `assets/`, on the shared design
//! (`idleo_brand::page`), assembled the same way for the window and for the browser preview.

/// The page's markup.
pub const BODY: &str = include_str!("../assets/index.html");
/// The page's own style, on top of the shared design.
pub const STYLE: &str = include_str!("../assets/style.css");
/// The page's script.
pub const SCRIPT: &str = include_str!("../assets/app.js");

/// The whole page.
#[must_use]
pub fn page() -> String {
    idleo_brand::page("IdleO setup", STYLE, BODY, SCRIPT)
}

/// The whole page with a preview's script in front of its own, so a fake channel stands in for Rust (`examples/preview.rs`).
#[must_use]
pub fn page_with(preview: &str) -> String {
    idleo_brand::page("IdleO setup", STYLE, BODY, &format!("{preview}\n{SCRIPT}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_page_has_the_font_the_design_and_no_way_out() {
        let page = page();
        assert!(page.contains("font/woff2;base64,") && page.contains("--primary: #c8ff3d"));
        assert!(page.contains("default-src 'none'") && !page.contains("/*FONT_INTER*/"));
        assert!(!page.contains("http://") && !page.contains("https://"), "the page never reaches out");
        assert!(page.contains("id=\"app\"") && page.contains("id=\"confirm\""));
        assert!(!page.contains("__previewScreens"), "the preview's fake channel is never in the program's page");
    }

    #[test]
    fn every_message_the_script_sends_is_one_rust_parses_and_every_screen_it_draws_is_one_rust_sends() {
        for message in [
            "ready",
            "set_up",
            "fix",
            "help",
            "try_again",
            "open_again",
            "restart_now",
            "later",
            "close",
            "keep",
            "confirm_close",
            "open_idleo",
            "diagnostics",
            "show_diagnostics",
        ] {
            assert!(SCRIPT.contains(&format!("\"{message}\"")), "the script never sends {message}");
            assert!(
                crate::ipc::FromPage::parse(&format!("{{\"type\":\"{message}\"}}")).is_some(),
                "Rust doesn't know {message}"
            );
        }
        for screen in [
            "welcome",
            "progress",
            "fix",
            "approve",
            "link_failed",
            "restart",
            "done",
            "stopped",
            "diagnostics_saved",
            "diagnostics_failed",
        ] {
            assert!(SCRIPT.contains(&format!("{screen}")), "the script doesn't draw {screen}");
        }
        assert!(!SCRIPT.contains("fetch(") && !SCRIPT.contains("localStorage") && !SCRIPT.contains("XMLHttpRequest"));
    }
}
