//! Real NVIDIA GPUs through NVML, loaded at run time from the driver (`nvml.dll` or
//! `libnvidia-ml.so`).

use nvml_wrapper::Nvml;
use nvml_wrapper::enum_wrappers::device::{PcieUtilCounter, TemperatureSensor};

use super::{GpuError, GpuInfo, GpuReading, GpuSource};

const MB: u64 = 1024 * 1024;

/// The host's NVIDIA GPUs.
pub struct NvmlGpus(Nvml);

impl NvmlGpus {
    /// Loads NVML and checks there is at least one GPU.
    ///
    /// # Errors
    /// If NVML can't be loaded, or finds no GPU.
    pub fn new() -> Result<Self, GpuError> {
        let nvml = Nvml::init().map_err(|e| {
            GpuError(format!(
                "NVIDIA's NVML library couldn't be loaded ({e}); install or update the NVIDIA driver \
                 (or use --fake-gpu for development)"
            ))
        })?;
        match nvml.device_count() {
            Ok(0) => Err(GpuError("NVML found no NVIDIA GPU on this machine".to_owned())),
            Ok(_) => Ok(Self(nvml)),
            Err(e) => Err(GpuError(format!("NVML couldn't list the GPUs: {e}"))),
        }
    }
}

fn nvml_error(what: &'static str) -> impl FnOnce(nvml_wrapper::error::NvmlError) -> GpuError {
    move |e| GpuError(format!("NVML couldn't read the {what}: {e}"))
}

fn megabytes(bytes: u64) -> i32 {
    i32::try_from(bytes / MB).unwrap_or(i32::MAX)
}

impl GpuSource for NvmlGpus {
    fn gpus(&self) -> Result<Vec<GpuInfo>, GpuError> {
        let driver_version = self.0.sys_driver_version().map_err(nvml_error("driver version"))?;
        let cuda = self.0.sys_cuda_driver_version().map_err(nvml_error("CUDA version"))?;
        let cuda_driver_version = format!(
            "{}.{}",
            nvml_wrapper::cuda_driver_version_major(cuda),
            nvml_wrapper::cuda_driver_version_minor(cuda)
        );
        let count = self.0.device_count().map_err(nvml_error("GPU count"))?;
        (0..count)
            .map(|index| {
                let device = self.0.device_by_index(index).map_err(nvml_error("GPU"))?;
                let capability = device.cuda_compute_capability().map_err(nvml_error("compute capability"))?;
                Ok(GpuInfo {
                    uuid: device.uuid().map_err(nvml_error("GPU uuid"))?,
                    name: device.name().map_err(nvml_error("GPU name"))?,
                    vram_mb: megabytes(device.memory_info().map_err(nvml_error("GPU memory"))?.total),
                    driver_version: driver_version.clone(),
                    cuda_driver_version: cuda_driver_version.clone(),
                    compute_capability: format!("{}.{}", capability.major, capability.minor),
                })
            })
            .collect()
    }

    fn read(&self) -> Result<Vec<GpuReading>, GpuError> {
        let count = self.0.device_count().map_err(nvml_error("GPU count"))?;
        (0..count)
            .map(|index| {
                let device = self.0.device_by_index(index).map_err(nvml_error("GPU"))?;
                let temperature = device.temperature(TemperatureSensor::Gpu).map_err(nvml_error("temperature"))?;
                let utilisation = device.utilization_rates().map_err(nvml_error("utilisation"))?;
                let memory = device.memory_info().map_err(nvml_error("GPU memory"))?;
                // PCIe traffic, both directions, for the review of abuse; many laptop GPUs don't report it, and none is 0.
                let pcie = [PcieUtilCounter::Send, PcieUtilCounter::Receive]
                    .into_iter()
                    .map(|counter| device.pcie_throughput(counter).unwrap_or(0))
                    .fold(0_u32, u32::saturating_add);
                Ok(GpuReading {
                    uuid: device.uuid().map_err(nvml_error("GPU uuid"))?,
                    temp_c: i32::try_from(temperature).unwrap_or(i32::MAX),
                    util_pct: i32::try_from(utilisation.gpu).unwrap_or(100),
                    vram_used_mb: megabytes(memory.used),
                    vram_total_mb: megabytes(memory.total),
                    pcie_kb_per_s: i32::try_from(pcie).unwrap_or(i32::MAX),
                    // Laptop GPUs often don't report power; absent, never zero.
                    power_w: device.power_usage().ok().and_then(|milliwatts| i32::try_from(milliwatts / 1000).ok()),
                })
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// CI runners have no NVIDIA driver: NVML must fail with a clear message, never fall back.
    #[test]
    fn without_nvml_the_error_says_what_to_do() {
        if let Err(GpuError(message)) = NvmlGpus::new() {
            assert!(message.contains("NVML") || message.contains("NVIDIA"), "{message}");
        }
    }
}
