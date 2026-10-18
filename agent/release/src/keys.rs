//! The release key baked into this build (ADR 0034), and the one thing a development build refuses.

include!(concat!(env!("OUT_DIR"), "/release_key.rs"));

/// The domain of the production control plane. A development build refuses it, so a build signed with the
/// development key can never talk to production.
pub const PRODUCTION_DOMAIN: &str = "idleo.in";

/// What a window's title or the tray's tooltip starts with in a development build.
pub const DEV_PREFIX: &str = "Dev · ";

/// Checks that this build may talk to a control plane at `host`.
///
/// # Errors
/// In a development build, for `idleo.in` and anything under it.
pub fn check_control_plane(host: &str) -> Result<(), String> {
    check_host(host, DEV_BUILD)
}

fn check_host(host: &str, development: bool) -> Result<(), String> {
    let host = host.trim().trim_end_matches('.').to_ascii_lowercase();
    if development && (host == PRODUCTION_DOMAIN || host.ends_with(&format!(".{PRODUCTION_DOMAIN}"))) {
        return Err(format!(
            "this is a development build, which never talks to {PRODUCTION_DOMAIN}; install the released app instead"
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_development_build_refuses_production() {
        assert!(check_host("idleo.in", true).is_err());
        assert!(check_host("agents.idleo.in", true).is_err());
        assert!(check_host("API.IDLEO.IN.", true).is_err());
        assert!(check_host("192.168.1.20", true).is_ok());
        assert!(check_host("notidleo.in", true).is_ok());
        assert!(check_host("agents.idleo.in", false).is_ok());
    }
}
