//! The NVIDIA driver the sealed Linux image's CUDA needs on Windows.

/// The oldest NVIDIA Windows driver we accept: 527.41, the one CUDA 12.0 GA needs.
///
/// The job image (`agent/images/pytorch/`) is built on CUDA 12.8.1 with `PyTorch`'s cu128 wheels, chosen because CUDA 12.8 is
/// the first with Blackwell (RTX 50, `sm_120`) support, and because every CUDA 12.x application runs on any driver from the
/// CUDA 12.0 release on, by minor version compatibility: NVIDIA's CUDA Compatibility guide, "Minor Version Compatibility"
/// (<https://docs.nvidia.com/deploy/cuda-compatibility/minor-version-compatibility.html>), lists CUDA 12.x as needing a
/// driver of at least 525; the CUDA Toolkit release notes' table "CUDA Toolkit and Corresponding Driver Versions"
/// (<https://docs.nvidia.com/cuda/cuda-toolkit-release-notes/>) gives CUDA 12.0 GA as 525.60.13 on Linux and 527.41 on
/// Windows, and CUDA 12.8 GA as 570.26 and 570.65. WSL 2 uses the Windows driver; NVIDIA's CUDA on WSL User Guide says never
/// to install a Linux driver inside the distribution. So 527.41 is the floor for every GPU older than Blackwell, whose
/// laptops ship drivers of 570 or newer anyway. The guide's caveats apply below 570.65: code that needs PTX compiled by the
/// driver fails, and a feature that spans the toolkit and driver can answer `cudaErrorCallRequiresNewerDriver`; the
/// `PyTorch` wheels carry precompiled code for every GPU generation they support, so neither is expected, and the laptop day
/// is where a driver this old is first proven.
pub const MIN_NVIDIA_DRIVER: &str = "527.41";

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
        assert!(is_supported("527.41"));
        assert!(is_supported("536.25"));
        assert!(!is_supported("527.40"));
        assert!(!is_supported("522.06"));
        assert!(is_supported("600"));
        assert!(!is_supported("unknown"));
    }
}
