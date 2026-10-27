//! Reconnect delays: exponential backoff with full jitter.

use std::time::Duration;

/// Exponential backoff: the ceiling starts at `base` and doubles after each failure up to `cap`.
/// Each actual delay is a uniformly random point below the ceiling ("full jitter"), so agents that
/// lost the control plane together don't come back together.
#[derive(Debug, Clone)]
pub struct Backoff {
    base: Duration,
    cap: Duration,
    failures: u32,
}

impl Backoff {
    /// A backoff starting at `base` and capped at `cap`.
    #[must_use]
    pub fn new(base: Duration, cap: Duration) -> Self {
        Self { base, cap, failures: 0 }
    }

    /// The ceiling for the next delay; each call doubles the following one, up to the cap.
    pub fn next_ceiling(&mut self) -> Duration {
        let ceiling = self.base.saturating_mul(1u32.checked_shl(self.failures).unwrap_or(u32::MAX)).min(self.cap);
        self.failures = self.failures.saturating_add(1);
        ceiling
    }

    /// Starts again from `base`, after a connection proved healthy.
    pub fn reset(&mut self) {
        self.failures = 0;
    }
}

/// A uniformly random duration in `[0, ceiling]`.
#[must_use]
pub fn full_jitter(ceiling: Duration) -> Duration {
    jitter_with(ceiling, random_u64())
}

fn jitter_with(ceiling: Duration, random: u64) -> Duration {
    let nanos = u64::try_from(ceiling.as_nanos()).unwrap_or(u64::MAX);
    Duration::from_nanos(random % nanos.saturating_add(1))
}

fn random_u64() -> u64 {
    // The system generator only fails on broken platforms; the ceiling itself is then a safe
    // delay.
    getrandom::u64().unwrap_or(u64::MAX)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ceilings_double_from_one_second_to_the_cap() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(60));
        let ceilings: Vec<u64> = (0..10).map(|_| backoff.next_ceiling().as_secs()).collect();
        assert_eq!(ceilings, [1, 2, 4, 8, 16, 32, 60, 60, 60, 60]);
    }

    #[test]
    fn reset_starts_again_from_the_base() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(60));
        for _ in 0..5 {
            backoff.next_ceiling();
        }
        backoff.reset();
        assert_eq!(backoff.next_ceiling(), Duration::from_secs(1));
    }

    #[test]
    fn many_failures_never_overflow() {
        let mut backoff = Backoff::new(Duration::from_secs(1), Duration::from_secs(60));
        for _ in 0..200 {
            assert!(backoff.next_ceiling() <= Duration::from_secs(60));
        }
    }

    #[test]
    fn jitter_stays_within_the_ceiling() {
        let ceiling = Duration::from_secs(8);
        assert_eq!(jitter_with(ceiling, 0), Duration::ZERO);
        assert_eq!(jitter_with(ceiling, 8_000_000_000), ceiling);
        assert!(jitter_with(ceiling, u64::MAX) <= ceiling);
        for _ in 0..1000 {
            assert!(full_jitter(ceiling) <= ceiling);
        }
    }
}
