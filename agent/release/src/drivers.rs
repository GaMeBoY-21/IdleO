//! The NVIDIA driver the sealed Linux image's CUDA needs on Windows.

/// The oldest NVIDIA Windows driver we accept: the R535 branch's, 536.25.
///
/// Source: NVIDIA's CUDA Toolkit release notes (<https://docs.nvidia.com/cuda/cuda-toolkit-release-notes/>), table
/// "CUDA Toolkit and Corresponding Driver Versions": CUDA 12.2 GA needs a Windows driver of at least 536.25 (CUDA
/// 12.0 GA, 527.41). WSL 2 uses the Windows driver; NVIDIA's CUDA on WSL User Guide says never to install a Linux
/// driver inside the distribution. TODO(confirm): the distro carries no CUDA toolkit of its own yet (the NVIDIA
/// container toolkit injects the driver's `nvidia-smi` and libraries), so no CUDA version pins this; confirm it when
/// the real runner fixes the CUDA version. Until then it is the documented R535 minimum.
pub const MIN_NVIDIA_DRIVER: &str = "536.25";

/// Whether `driver` (for example `572.16`) is at least [`MIN_NVIDIA_DRIVER`]. Unreadable versions fail.
#[must_use]
pub fn is_supported(driver: &str) -> bool {
    match (parse(driver), parse(MIN_NVIDIA_DRIVER)) {
        (Some(found), Some(minimum)) => found >= minimum,
        _ => false,
    }
}

fn parse(version: &str) -> Option<(u32, u32)> {
    let mut parts = version.trim().split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next().map_or(Some(0), |minor| minor.parse().ok())?;
    Some((major, minor))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_major_then_minor() {
        assert!(is_supported("572.16"));
        assert!(is_supported("536.25"));
        assert!(!is_supported("536.19"));
        assert!(!is_supported("531.79"));
        assert!(is_supported("600"));
        assert!(!is_supported("unknown"));
    }
}
