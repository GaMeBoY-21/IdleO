//! The lease clock (ADR 0007, proto/README.md "The lease clock"): time that keeps counting while the
//! machine sleeps, so a laptop that slept through its lease's end stops the work when it wakes instead
//! of running on beside the attempt that replaced it. Never `std::time::Instant`, whose behaviour
//! across suspend "varies across platforms and Rust versions".
//!
//! - Linux: `CLOCK_BOOTTIME`, which "also includes any time that the system is suspended".
//! - Windows: `QueryPerformanceCounter`, read in the `agent-windows` crate, which counts sleep,
//!   hibernation and connected standby.
//! - macOS (development only): `CLOCK_MONOTONIC`. Apple's `clock_gettime(3)` says it "will continue
//!   to increment while the system is asleep", unlike `CLOCK_UPTIME_RAW`. On Linux the same name
//!   stops during suspend, which is why Linux uses `CLOCK_BOOTTIME`.

use std::sync::Mutex;
use std::time::Duration;

/// A clock that keeps counting while the machine sleeps, read as time since an arbitrary start.
/// Only differences between two readings mean anything.
pub trait LeaseClock: Send + Sync {
    /// The time now.
    fn now(&self) -> Duration;
}

/// The operating system's sleep-counting clock.
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemLeaseClock;

impl LeaseClock for SystemLeaseClock {
    #[cfg(target_os = "linux")]
    fn now(&self) -> Duration {
        timespec(rustix::time::clock_gettime(rustix::time::ClockId::Boottime))
    }

    #[cfg(target_os = "macos")]
    fn now(&self) -> Duration {
        timespec(rustix::time::clock_gettime(rustix::time::ClockId::Monotonic))
    }

    #[cfg(windows)]
    fn now(&self) -> Duration {
        // QueryPerformanceCounter can't fail on Windows XP or later; if it ever did, time standing
        // still would only make leases look fresh for as long as it failed, so fail loudly instead.
        agent_windows::performance_counter().expect("QueryPerformanceCounter failed")
    }
}

#[cfg(unix)]
fn timespec(time: rustix::time::Timespec) -> Duration {
    Duration::new(u64::try_from(time.tv_sec).unwrap_or(0), u32::try_from(time.tv_nsec).unwrap_or(0))
}

/// A clock tests move by hand, for example to jump over a sleep.
#[derive(Debug, Default)]
pub struct FakeLeaseClock {
    now: Mutex<Duration>,
}

impl FakeLeaseClock {
    /// Moves the clock forward by `by`.
    pub fn advance(&self, by: Duration) {
        *self.now.lock().unwrap_or_else(std::sync::PoisonError::into_inner) += by;
    }
}

impl LeaseClock for FakeLeaseClock {
    fn now(&self) -> Duration {
        *self.now.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_system_clock_moves_forward() {
        let first = SystemLeaseClock.now();
        std::thread::sleep(Duration::from_millis(20));
        let second = SystemLeaseClock.now();
        assert!(second >= first + Duration::from_millis(15), "{first:?} then {second:?}");
    }

    #[test]
    fn the_fake_clock_moves_only_when_told() {
        let clock = FakeLeaseClock::default();
        assert_eq!(clock.now(), Duration::ZERO);
        clock.advance(Duration::from_secs(90));
        assert_eq!(clock.now(), Duration::from_secs(90));
    }
}
