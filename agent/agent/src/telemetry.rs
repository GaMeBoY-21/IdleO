//! GPU facts and readings, behind [`GpuSource`]: NVML for real GPUs, or invented GPUs when asked
//! for with `--fake-gpu`. The agent never falls back to fake GPUs by itself.

use std::fmt;
use std::sync::Mutex;

mod fake;
#[cfg(all(feature = "nvml", any(windows, target_os = "linux")))]
mod nvml;

pub use fake::FakeGpus;
#[cfg(all(feature = "nvml", any(windows, target_os = "linux")))]
pub use nvml::NvmlGpus;

/// One GPU's fixed facts, sent in `Hello`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GpuInfo {
    /// As `nvidia-smi` reports it, for example `GPU-8a6b…`.
    pub uuid: String,
    /// Marketing name.
    pub name: String,
    /// Total memory, in MB.
    pub vram_mb: i32,
    /// NVIDIA driver version.
    pub driver_version: String,
    /// Highest CUDA version the driver supports, for example `12.8`.
    pub cuda_driver_version: String,
    /// CUDA compute capability, for example `8.9`.
    pub compute_capability: String,
}

/// One GPU's readings at one moment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GpuReading {
    /// Which GPU.
    pub uuid: String,
    /// Core temperature, °C.
    pub temp_c: i32,
    /// Utilisation, percent.
    pub util_pct: i32,
    /// Memory in use, MB.
    pub vram_used_mb: i32,
    /// Power draw in watts, if the GPU reports it.
    pub power_w: Option<i32>,
}

/// A GPU reading failed, or no GPU source is available.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GpuError(pub String);

impl fmt::Display for GpuError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for GpuError {}

/// Where GPU facts and readings come from. Calls may block briefly (NVML is a C library), so the
/// agent makes them off the async threads.
pub trait GpuSource: Send + Sync {
    /// Every GPU's fixed facts.
    ///
    /// # Errors
    /// If the GPUs can't be read.
    fn gpus(&self) -> Result<Vec<GpuInfo>, GpuError>;

    /// Every GPU's readings now.
    ///
    /// # Errors
    /// If the GPUs can't be read.
    fn read(&self) -> Result<Vec<GpuReading>, GpuError>;
}

/// The real GPUs: NVML on Windows and Linux builds with the `nvml` feature.
///
/// # Errors
/// If NVML can't be loaded or finds no GPU, or this build has no NVML support. There is no
/// fallback: fake GPUs must be asked for.
pub fn system() -> Result<Box<dyn GpuSource>, GpuError> {
    #[cfg(all(feature = "nvml", any(windows, target_os = "linux")))]
    {
        NvmlGpus::new().map(|gpus| Box::new(gpus) as Box<dyn GpuSource>)
    }
    #[cfg(not(all(feature = "nvml", any(windows, target_os = "linux"))))]
    {
        Err(GpuError(
            "this build can't read NVIDIA GPUs (NVML support is for Windows and Linux builds with the `nvml` feature); \
             use --fake-gpu for development"
                .to_owned(),
        ))
    }
}

/// Readings taken every second, summarised once per heartbeat: per GPU, the highest temperature
/// and power, the mean utilisation, and the latest memory in use.
#[derive(Debug, Default)]
pub struct Readings(Mutex<Vec<Summary>>);

#[derive(Debug)]
struct Summary {
    uuid: String,
    samples: u32,
    max_temp_c: i32,
    util_total: i64,
    vram_used_mb: i32,
    max_power_w: Option<i32>,
    last: Option<GpuReading>,
}

impl Readings {
    /// Adds one round of readings.
    pub fn record(&self, readings: &[GpuReading]) {
        let mut summaries = self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        for reading in readings {
            let index = summaries.iter().position(|s| s.uuid == reading.uuid).unwrap_or_else(|| {
                summaries.push(Summary {
                    uuid: reading.uuid.clone(),
                    samples: 0,
                    max_temp_c: i32::MIN,
                    util_total: 0,
                    vram_used_mb: 0,
                    max_power_w: None,
                    last: None,
                });
                summaries.len() - 1
            });
            let summary = &mut summaries[index];
            summary.samples += 1;
            summary.max_temp_c = summary.max_temp_c.max(reading.temp_c);
            summary.util_total += i64::from(reading.util_pct);
            summary.vram_used_mb = reading.vram_used_mb;
            summary.max_power_w = summary.max_power_w.max(reading.power_w);
        }
    }

    /// The summary since the last call, per GPU. A GPU with no new reading repeats its last
    /// summary, so every heartbeat covers every GPU seen.
    pub fn summarise(&self) -> Vec<GpuReading> {
        let mut summaries = self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        summaries
            .iter_mut()
            .filter_map(|summary| {
                if summary.samples > 0 {
                    let mean = summary.util_total / i64::from(summary.samples);
                    summary.last = Some(GpuReading {
                        uuid: summary.uuid.clone(),
                        temp_c: summary.max_temp_c,
                        util_pct: i32::try_from(mean).unwrap_or(i32::MAX),
                        vram_used_mb: summary.vram_used_mb,
                        power_w: summary.max_power_w,
                    });
                    summary.samples = 0;
                    summary.max_temp_c = i32::MIN;
                    summary.util_total = 0;
                    summary.max_power_w = None;
                }
                summary.last.clone()
            })
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn reading(uuid: &str, temp_c: i32, util_pct: i32, vram_used_mb: i32, power_w: Option<i32>) -> GpuReading {
        GpuReading { uuid: uuid.to_owned(), temp_c, util_pct, vram_used_mb, power_w }
    }

    #[test]
    fn summaries_take_the_max_temperature_and_power_mean_utilisation_and_latest_memory() {
        let readings = Readings::default();
        readings.record(&[reading("a", 60, 10, 1000, Some(80)), reading("b", 40, 0, 10, None)]);
        readings.record(&[reading("a", 70, 20, 1500, Some(120)), reading("b", 45, 50, 20, None)]);
        readings.record(&[reading("a", 65, 33, 1200, Some(90)), reading("b", 41, 100, 30, None)]);

        assert_eq!(readings.summarise(), [reading("a", 70, 21, 1200, Some(120)), reading("b", 45, 50, 30, None)]);
    }

    #[test]
    fn a_gpu_without_new_readings_repeats_its_last_summary() {
        let readings = Readings::default();
        readings.record(&[reading("a", 60, 10, 1000, Some(80))]);
        let first = readings.summarise();

        assert_eq!(readings.summarise(), first);
        readings.record(&[reading("a", 50, 90, 900, Some(70))]);
        assert_eq!(readings.summarise(), [reading("a", 50, 90, 900, Some(70))]);
    }

    #[test]
    fn nothing_recorded_means_an_empty_summary() {
        assert_eq!(Readings::default().summarise(), []);
    }
}
