//! Comparing semantic versions: `Welcome.minimum_agent_version`, and whether a release is newer than what runs.

/// `major.minor.patch`, ignoring any pre-release or build suffix.
fn parse(version: &str) -> Option<(u64, u64, u64)> {
    let core = version.split(['-', '+']).next()?;
    let mut parts = core.split('.').map(|part| part.parse::<u64>().ok());
    let version = (parts.next()??, parts.next()??, parts.next()??);
    parts.next().is_none().then_some(version)
}

/// Whether `minimum` is newer than `ours`, or `None` if either isn't a semantic version. Pre-release suffixes are
/// ignored, so `0.2.0-rc.1` is not newer than `0.2.0`.
#[must_use]
pub fn is_newer(minimum: &str, ours: &str) -> Option<bool> {
    Some(parse(minimum)? > parse(ours)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_numerically() {
        assert_eq!(is_newer("0.2.0", "0.1.9"), Some(true));
        assert_eq!(is_newer("0.10.0", "0.9.0"), Some(true));
        assert_eq!(is_newer("0.1.0", "0.1.0"), Some(false));
        assert_eq!(is_newer("0.1.0", "1.0.0"), Some(false));
        assert_eq!(is_newer("1.0.0-rc.1", "0.9.0"), Some(true));
    }

    #[test]
    fn non_versions_compare_as_unknown() {
        assert_eq!(is_newer("latest", "0.1.0"), None);
        assert_eq!(is_newer("1.0", "0.1.0"), None);
        assert_eq!(is_newer("1.0.0.0", "0.1.0"), None);
    }
}
