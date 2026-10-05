//! Invented GPUs for development on a Mac and for tests, chosen only with `--fake-gpu`. Their
//! uuids are plainly fake (`GPU-00000000-fake-…`); otherwise they look like real GPUs to the
//! control plane.

use std::sync::Mutex;

use super::{GpuError, GpuInfo, GpuReading, GpuSource};

const VRAM_MB: i32 = 12_282;

/// One or more fake GPUs whose readings drift like a lightly used card's.
#[derive(Debug)]
pub struct FakeGpus {
    gpus: Vec<GpuInfo>,
    state: Mutex<(u64, Vec<GpuReading>)>,
}

impl FakeGpus {
    /// `count` fake GPUs (at least one).
    #[must_use]
    pub fn new(count: u8) -> Self {
        let gpus: Vec<GpuInfo> = (0..count.max(1))
            .map(|i| GpuInfo {
                uuid: format!("GPU-00000000-fake-{i:04x}-000000000000"),
                name: "IdleO Fake GPU".to_owned(),
                vram_mb: VRAM_MB,
                driver_version: "0.0".to_owned(),
                cuda_driver_version: "0.0".to_owned(),
                compute_capability: "0.0".to_owned(),
            })
            .collect();
        let readings = gpus
            .iter()
            .map(|gpu| GpuReading {
                uuid: gpu.uuid.clone(),
                temp_c: 45,
                util_pct: 5,
                vram_used_mb: 600,
                power_w: Some(25),
            })
            .collect();
        let seed = getrandom::u64().unwrap_or(0x9e37_79b9_7f4a_7c15) | 1;
        Self { gpus, state: Mutex::new((seed, readings)) }
    }
}

impl GpuSource for FakeGpus {
    fn gpus(&self) -> Result<Vec<GpuInfo>, GpuError> {
        Ok(self.gpus.clone())
    }

    fn read(&self) -> Result<Vec<GpuReading>, GpuError> {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let (seed, readings) = &mut *state;
        for reading in readings.iter_mut() {
            reading.temp_c = drift(seed, reading.temp_c, 2, 35, 83);
            reading.util_pct = drift(seed, reading.util_pct, 10, 0, 100);
            reading.vram_used_mb = drift(seed, reading.vram_used_mb, 256, 300, VRAM_MB - 300);
            reading.power_w = reading.power_w.map(|w| drift(seed, w, 5, 10, 175));
        }
        Ok(readings.clone())
    }
}

/// Moves `value` by up to `step` either way, staying in `[min, max]`.
fn drift(seed: &mut u64, value: i32, step: i32, min: i32, max: i32) -> i32 {
    // xorshift64: plenty for drifting fake readings.
    *seed ^= *seed << 13;
    *seed ^= *seed >> 7;
    *seed ^= *seed << 17;
    let span = u64::try_from(2 * step + 1).unwrap_or(1);
    let delta = i32::try_from(*seed % span).unwrap_or(0) - step;
    (value + delta).clamp(min, max)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fake_gpus_are_plainly_fake_and_drift_within_range() {
        let source = FakeGpus::new(2);
        let gpus = source.gpus().unwrap();
        assert_eq!(gpus.len(), 2);
        assert!(gpus.iter().all(|gpu| gpu.uuid.starts_with("GPU-00000000-fake-")));
        assert_ne!(gpus[0].uuid, gpus[1].uuid);

        for _ in 0..1000 {
            for reading in source.read().unwrap() {
                assert!((35..=83).contains(&reading.temp_c));
                assert!((0..=100).contains(&reading.util_pct));
                assert!((300..=VRAM_MB - 300).contains(&reading.vram_used_mb));
                assert!(reading.power_w.is_some_and(|w| (10..=175).contains(&w)));
            }
        }
    }

    #[test]
    fn zero_means_one() {
        assert_eq!(FakeGpus::new(0).gpus().unwrap().len(), 1);
    }
}
