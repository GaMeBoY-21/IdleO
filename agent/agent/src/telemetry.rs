//! GPU facts and readings, behind [`GpuSource`]: NVML for real GPUs, or invented GPUs when asked
//! for with `--fake-gpu`. The agent never falls back to fake GPUs by itself.

use std::fmt;
use std::sync::Mutex;

mod fake;
#[cfg(all(feature = "nvml", any(windows, target_os = "linux")))]
mod nvml;

pub use fake::{FakeGpus, FakeModel};
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
    /// The GPU's memory in all, MB.
    pub vram_total_mb: i32,
    /// `PCIe` traffic between the GPU and the machine now, both directions together, in KB/s; 0 where the GPU doesn't report it
    /// (many laptop GPUs don't). Used only for the review of abuse, never for billing (ADR 0038).
    pub pcie_kb_per_s: i32,
    /// Power draw in watts, if the GPU reports it.
    pub power_w: Option<i32>,
}

/// What a session's GPUs did over the minute just ended, for its usage report.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct GpuUsage {
    /// Mean utilisation, percent.
    pub util_pct: i32,
    /// Mean memory in use, percent of the GPU's.
    pub memory_pct: i32,
    /// Mean `PCIe` traffic, KB/s.
    pub pcie_kb_per_s: i32,
}

impl GpuUsage {
    /// Only a utilisation: for tests and sources with nothing more to say.
    #[must_use]
    pub fn util(util_pct: i32) -> Self {
        Self { util_pct, ..Self::default() }
    }
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
    vram_total_mb: i32,
    max_power_w: Option<i32>,
    latest_temp_c: i32,
    last: Option<GpuReading>,
    /// Utilisation, memory and `PCIe` traffic since a session last took them, for its per-minute usage report.
    usage_util_total: i64,
    usage_memory_pct_total: i64,
    usage_pcie_total: i64,
    usage_samples: u32,
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
                    vram_total_mb: 0,
                    max_power_w: None,
                    latest_temp_c: reading.temp_c,
                    last: None,
                    usage_util_total: 0,
                    usage_memory_pct_total: 0,
                    usage_pcie_total: 0,
                    usage_samples: 0,
                });
                summaries.len() - 1
            });
            let summary = &mut summaries[index];
            summary.samples += 1;
            summary.max_temp_c = summary.max_temp_c.max(reading.temp_c);
            summary.util_total += i64::from(reading.util_pct);
            summary.usage_util_total += i64::from(reading.util_pct);
            if reading.vram_total_mb > 0 {
                summary.usage_memory_pct_total +=
                    i64::from(reading.vram_used_mb) * 100 / i64::from(reading.vram_total_mb);
            }
            summary.usage_pcie_total += i64::from(reading.pcie_kb_per_s);
            summary.usage_samples += 1;
            summary.vram_used_mb = reading.vram_used_mb;
            summary.vram_total_mb = reading.vram_total_mb;
            summary.latest_temp_c = reading.temp_c;
            summary.max_power_w = summary.max_power_w.max(reading.power_w);
        }
    }

    /// What `uuids` did since the last call for them, as means, and starts counting again: a session's usage report for the
    /// minute just ended. Zeroes with no readings.
    pub fn take_usage(&self, uuids: &[String]) -> GpuUsage {
        let mut summaries = self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let (mut util, mut memory, mut pcie, mut samples) = (0i64, 0i64, 0i64, 0i64);
        for summary in summaries.iter_mut().filter(|summary| uuids.contains(&summary.uuid)) {
            util += summary.usage_util_total;
            memory += summary.usage_memory_pct_total;
            pcie += summary.usage_pcie_total;
            samples += i64::from(summary.usage_samples);
            summary.usage_util_total = 0;
            summary.usage_memory_pct_total = 0;
            summary.usage_pcie_total = 0;
            summary.usage_samples = 0;
        }
        if samples == 0 {
            return GpuUsage::default();
        }
        let mean = |total: i64| i32::try_from(total / samples).unwrap_or(i32::MAX);
        GpuUsage { util_pct: mean(util).min(100), memory_pct: mean(memory).min(100), pcie_kb_per_s: mean(pcie) }
    }

    /// The hottest GPU's latest reading, if any GPU has been read.
    pub fn hottest_latest_c(&self) -> Option<i32> {
        let summaries = self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        summaries.iter().map(|summary| summary.latest_temp_c).max()
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
                        vram_total_mb: summary.vram_total_mb,
                        pcie_kb_per_s: 0,
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

    #[test]
    fn utilisation_is_taken_per_gpu_and_starts_again() {
        let readings = Readings::default();
        let reading = |uuid: &str, util_pct| GpuReading {
            uuid: uuid.to_owned(),
            temp_c: 60,
            util_pct,
            vram_used_mb: 1,
            vram_total_mb: 0,
            pcie_kb_per_s: 0,
            power_w: None,
        };
        readings.record(&[reading("a", 90), reading("b", 10)]);
        readings.record(&[reading("a", 70), reading("b", 10)]);
        assert_eq!(readings.take_usage(&["a".to_owned()]).util_pct, 80);
        assert_eq!(readings.take_usage(&["a".to_owned()]), GpuUsage::default(), "taken");
        assert_eq!(readings.take_usage(&["b".to_owned()]).util_pct, 10, "b untouched by a's take");
        assert_eq!(readings.summarise().len(), 2, "heartbeats unaffected");
    }

    #[test]
    fn a_sessions_minute_has_the_mean_memory_in_percent_and_the_mean_pcie_traffic() {
        let readings = Readings::default();
        let sample = |used: i32, pcie: i32| GpuReading {
            uuid: "a".to_owned(),
            temp_c: 60,
            util_pct: 98,
            vram_used_mb: used,
            vram_total_mb: 8_000,
            pcie_kb_per_s: pcie,
            power_w: None,
        };
        // A job that works the GPU hard with little of its memory and next to no traffic over the bus.
        readings.record(&[sample(400, 100)]);
        readings.record(&[sample(480, 300)]);
        assert_eq!(
            readings.take_usage(&["a".to_owned()]),
            GpuUsage { util_pct: 98, memory_pct: 5, pcie_kb_per_s: 200 },
            "400 and 480 MB of 8,000 are 5% and 6%, whose integer mean is 5"
        );
        assert_eq!(GpuUsage::util(42), GpuUsage { util_pct: 42, memory_pct: 0, pcie_kb_per_s: 0 });
    }

    fn reading(uuid: &str, temp_c: i32, util_pct: i32, vram_used_mb: i32, power_w: Option<i32>) -> GpuReading {
        GpuReading {
            uuid: uuid.to_owned(),
            temp_c,
            util_pct,
            vram_used_mb,
            vram_total_mb: 0,
            pcie_kb_per_s: 0,
            power_w,
        }
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
    fn the_hottest_latest_reading_is_kept_across_summaries() {
        let readings = Readings::default();
        assert_eq!(readings.hottest_latest_c(), None);
        readings.record(&[reading("a", 90, 10, 1000, None), reading("b", 40, 0, 10, None)]);
        readings.record(&[reading("a", 70, 10, 1000, None), reading("b", 45, 0, 10, None)]);
        readings.summarise();
        assert_eq!(readings.hottest_latest_c(), Some(70));
    }

    #[test]
    fn nothing_recorded_means_an_empty_summary() {
        assert_eq!(Readings::default().summarise(), []);
    }
}
