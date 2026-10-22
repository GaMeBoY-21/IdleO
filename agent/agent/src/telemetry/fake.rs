//! Invented GPUs for development on a Mac and for tests, chosen only with `--fake-gpu`. Their
//! uuids are plainly fake (`GPU-00000000-fake-…`); otherwise they look like real GPUs to the
//! control plane, under the model name and memory they're given (`--fake-gpu-model`,
//! `--fake-gpu-vram-mb`), so a local stack can show laptops of different sizes and prices.

use std::sync::Mutex;

use super::{GpuError, GpuInfo, GpuReading, GpuSource};

/// What a fake GPU says it is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FakeModel {
    pub name: String,
    pub vram_mb: i32,
}

impl FakeModel {
    /// The least memory a fake GPU may have: its readings drift between 300 MB in use and 300 MB free.
    pub const MIN_VRAM_MB: i32 = 1_024;
}

impl Default for FakeModel {
    fn default() -> Self {
        Self { name: "IdleO Fake GPU".to_owned(), vram_mb: 12_282 }
    }
}

/// One or more fake GPUs whose readings drift like a lightly used card's.
#[derive(Debug)]
pub struct FakeGpus {
    gpus: Vec<GpuInfo>,
    state: Mutex<(u64, Vec<GpuReading>)>,
}

impl FakeGpus {
    /// `count` fake GPUs (at least one), each of `model`, with at least [`FakeModel::MIN_VRAM_MB`].
    #[must_use]
    pub fn new(count: u8, model: &FakeModel) -> Self {
        let gpus: Vec<GpuInfo> = (0..count.max(1))
            .map(|i| GpuInfo {
                uuid: format!("GPU-00000000-fake-{i:04x}-000000000000"),
                name: model.name.clone(),
                vram_mb: model.vram_mb.max(FakeModel::MIN_VRAM_MB),
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
                vram_total_mb: gpu.vram_mb,
                pcie_kb_per_s: 0,
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
        for (reading, gpu) in readings.iter_mut().zip(&self.gpus) {
            reading.temp_c = drift(seed, reading.temp_c, 2, 35, 83);
            reading.util_pct = drift(seed, reading.util_pct, 10, 0, 100);
            reading.vram_used_mb = drift(seed, reading.vram_used_mb, 256, 300, gpu.vram_mb - 300);
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
        let source = FakeGpus::new(2, &FakeModel::default());
        let gpus = source.gpus().unwrap();
        assert_eq!(gpus.len(), 2);
        assert!(gpus.iter().all(|gpu| gpu.uuid.starts_with("GPU-00000000-fake-")));
        assert!(gpus.iter().all(|gpu| gpu.name == "IdleO Fake GPU" && gpu.vram_mb == 12_282));
        assert_ne!(gpus[0].uuid, gpus[1].uuid);

        for _ in 0..1000 {
            for reading in source.read().unwrap() {
                assert!((35..=83).contains(&reading.temp_c));
                assert!((0..=100).contains(&reading.util_pct));
                assert!((300..=12_282 - 300).contains(&reading.vram_used_mb));
                assert!(reading.power_w.is_some_and(|w| (10..=175).contains(&w)));
            }
        }
    }

    #[test]
    fn a_fake_gpu_takes_the_model_and_memory_it_is_given() {
        let source = FakeGpus::new(1, &FakeModel { name: "RTX 4060 Laptop".to_owned(), vram_mb: 8_188 });
        let gpus = source.gpus().unwrap();
        assert_eq!((gpus[0].name.as_str(), gpus[0].vram_mb), ("RTX 4060 Laptop", 8_188));
        for _ in 0..1000 {
            let reading = &source.read().unwrap()[0];
            assert!((300..=8_188 - 300).contains(&reading.vram_used_mb), "memory in use stays within the card's");
        }
    }

    #[test]
    fn a_fake_gpu_has_at_least_the_least_memory() {
        let source = FakeGpus::new(1, &FakeModel { name: "Tiny".to_owned(), vram_mb: 10 });
        assert_eq!(source.gpus().unwrap()[0].vram_mb, FakeModel::MIN_VRAM_MB);
    }

    #[test]
    fn zero_means_one() {
        assert_eq!(FakeGpus::new(0, &FakeModel::default()).gpus().unwrap().len(), 1);
    }
}
