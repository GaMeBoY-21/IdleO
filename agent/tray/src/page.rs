//! The IdleO window's page (ADR 0061): the markup, the style and the script in `assets/`, on the shared design
//! (`idleo_brand::page`), assembled the same way for the window and for the browser preview.

/// The page's markup.
pub const BODY: &str = include_str!("../assets/body.html");
/// The page's own style, on top of the shared design.
pub const STYLE: &str = include_str!("../assets/style.css");
/// The page's script.
pub const SCRIPT: &str = include_str!("../assets/app.js");

/// The whole page.
#[must_use]
pub fn page() -> String {
    idleo_brand::page("IdleO", STYLE, BODY, SCRIPT)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::window::TABS;

    #[test]
    fn the_page_has_every_tab_the_model_knows_and_the_font_and_no_way_out() {
        let page = page();
        for tab in TABS {
            assert!(page.contains(&format!("data-tab=\"{tab}\"")), "no {tab} tab");
            assert!(page.contains(&format!("id=\"tab-{tab}\"")));
        }
        assert!(page.contains("role=\"tablist\"") && page.contains("role=\"tabpanel\""));
        assert!(page.contains("font/woff2;base64,") && page.contains("default-src 'none'"));
        assert!(!page.contains("/*FONT_INTER*/"));
        assert!(!page.contains("http://") && !page.contains("https://"), "the page never reaches out");
    }

    #[test]
    fn the_script_speaks_only_what_the_model_understands() {
        // Every message type the script sends is one `FromWindow` parses.
        for message in
            ["ready", "pause", "resume", "set_settings", "fix", "open_host_site", "send_diagnostics", "remove_idleo"]
        {
            assert!(SCRIPT.contains(&format!("\"{message}\"")), "the script never sends {message}");
        }
        for tab in TABS {
            assert!(SCRIPT.contains(&format!("\"{tab}\"")), "the script doesn't know the {tab} tab");
        }
        assert!(SCRIPT.contains("window.idleo = {") && SCRIPT.contains("receive:") && SCRIPT.contains("showTab:"));
        assert!(!SCRIPT.contains("fetch(") && !SCRIPT.contains("XMLHttpRequest") && !SCRIPT.contains("localStorage"));
    }
}
