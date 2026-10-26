//! The NVIDIA driver a CUDA version needs on Windows, read from `api/cuda-drivers.json`: the one copy of the rule, which the
//! control plane's `CudaDrivers` reads too, to match a renter's own image to a host's driver (ADR 0042).
//!
//! The rule is minor version compatibility: every CUDA X.y application runs on any driver from the X.0 release on. NVIDIA's
//! CUDA Compatibility guide, "Minor Version Compatibility"
//! (<https://docs.nvidia.com/deploy/cuda-compatibility/minor-version-compatibility.html>), lists CUDA 12.x as needing a
//! driver of at least 525; the CUDA Toolkit release notes' table "CUDA Toolkit and Corresponding Driver Versions"
//! (<https://docs.nvidia.com/cuda/cuda-toolkit-release-notes/>) gives CUDA 12.0 GA as 525.60.13 on Linux and 527.41 on
//! Windows, CUDA 11.x as 452.39 on Windows, and CUDA 13.x as the R580 branch. WSL 2 uses the Windows driver; NVIDIA's CUDA on
//! WSL User Guide says never to install a Linux driver inside the distribution.
//!
//! The job image (`agent/images/pytorch/`) is built on CUDA 12.8.1 with `PyTorch`'s cu128 wheels, chosen because CUDA 12.8 is
//! the first with Blackwell (RTX 50, `sm_120`) support, so [`min_nvidia_driver`] is 527.41: the floor for every GPU older than
//! Blackwell, whose laptops ship drivers of 570 or newer anyway. The guide's caveats apply below 570.65: code that needs PTX
//! compiled by the driver fails, and a feature that spans the toolkit and driver can answer
//! `cudaErrorCallRequiresNewerDriver`; the `PyTorch` wheels carry precompiled code for every GPU generation they support, so
//! neither is expected, and the laptop day is where a driver this old is first proven.

use std::sync::LazyLock;

use serde::Deserialize;

/// The table, as committed.
const TABLE: &str = include_str!("../../../api/cuda-drivers.json");

#[derive(Deserialize)]
struct Table {
    job_image_cuda: String,
    majors: Vec<Major>,
}

#[derive(Deserialize)]
struct Major {
    cuda: u32,
    windows_min_driver: String,
}

static PARSED: LazyLock<Table> = LazyLock::new(|| {
    let table: Table = serde_json::from_str(TABLE).expect("api/cuda-drivers.json is the committed table");
    assert!(
        table.majors.iter().all(|major| parse(&major.windows_min_driver).is_some()),
        "every driver in api/cuda-drivers.json is a version"
    );
    table
});

/// The oldest Windows driver CUDA `major` runs on, if the table knows that major.
#[must_use]
pub fn min_driver_for_cuda(major: u32) -> Option<&'static str> {
    PARSED.majors.iter().find(|entry| entry.cuda == major).map(|entry| entry.windows_min_driver.as_str())
}

/// The oldest NVIDIA Windows driver we accept: what our job image's CUDA needs (527.41, CUDA 12).
///
/// # Panics
/// Never: the committed table names the job image's CUDA major (a test holds it).
#[must_use]
pub fn min_nvidia_driver() -> &'static str {
    let major = cuda_major(&PARSED.job_image_cuda).expect("job_image_cuda is a CUDA version");
    min_driver_for_cuda(major).expect("the table names the job image's CUDA major")
}

/// Whether `driver` (for example `572.16`) is at least [`min_nvidia_driver`]. Unreadable versions fail.
#[must_use]
pub fn is_supported(driver: &str) -> bool {
    at_least(driver, min_nvidia_driver())
}

/// Whether `driver` runs an application built with CUDA `cuda` (for example `12.4` or `13.0.1`). A major the table doesn't
/// know, or an unreadable version, fails.
#[must_use]
pub fn supports_cuda(driver: &str, cuda: &str) -> bool {
    cuda_major(cuda).and_then(min_driver_for_cuda).is_some_and(|minimum| at_least(driver, minimum))
}

fn at_least(driver: &str, minimum: &str) -> bool {
    match (parse(driver), parse(minimum)) {
        (Some(found), Some(minimum)) => found >= minimum,
        _ => false,
    }
}

fn cuda_major(cuda: &str) -> Option<u32> {
    cuda.trim().split('.').next()?.parse().ok()
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
    fn the_job_images_floor_is_cuda_12s() {
        assert_eq!(min_nvidia_driver(), "527.41");
    }

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

    #[test]
    fn a_cuda_version_needs_its_majors_driver() {
        assert!(supports_cuda("572.16", "12.8"));
        assert!(supports_cuda("527.41", "12.4.1"));
        assert!(!supports_cuda("572.16", "13.0"));
        assert!(supports_cuda("581.57", "13.0.1"));
        assert!(supports_cuda("452.39", "11.8"));
        assert!(!supports_cuda("452.06", "11.0"));
        assert!(!supports_cuda("999.99", "14.0"), "a major the table doesn't know runs nowhere");
        assert!(!supports_cuda("0.0", "12.0"), "a fake GPU's driver runs no CUDA image");
        assert!(!supports_cuda("572.16", "twelve"));
    }
}
