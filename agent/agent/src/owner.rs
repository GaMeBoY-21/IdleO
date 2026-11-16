//! What the machine's owner is doing, sampled for [`crate::availability`] (ADR 0033).
//!
//! - Windows: time since the last keyboard or mouse input (`GetLastInputInfo`), whether a game or full-screen app is
//!   open (`SHQueryUserNotificationState`), and whether it runs on battery (`GetSystemPowerStatus`). The agent runs as
//!   the signed-in owner, never as a service, so these see the owner's own session.
//! - Linux and macOS: nobody, always: the local stack and the end-to-end test are unchanged.

use std::time::Duration;

/// The owner's side of one sample. GPU use and our own sessions come from the agent's other readings.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Sample {
    /// Time since the last keyboard or mouse input.
    pub idle: Duration,
    /// A game or a full-screen app is open.
    pub fullscreen: bool,
    /// Running on battery.
    pub on_battery: bool,
    /// The connection is metered, for the host's "not on metered connections". When Windows can't say, it isn't: hosting
    /// doesn't stop on a guess (a download does wait on one, see [`crate::care::Metered`]).
    pub metered: bool,
}

impl Sample {
    /// Nobody at the machine.
    pub const QUIET: Self = Self { idle: Duration::MAX, fullscreen: false, on_battery: false, metered: false };
}

/// Samples the owner's side of the machine.
pub trait MachineInputs: Send + Sync {
    /// One sample, now. Never fails: a reading that can't be taken is reported the safe way (see each system).
    fn sample(&self) -> Sample;
}

/// How long a reading of the connection's cost is kept: it changes when someone flips a switch, not every second.
#[cfg(windows)]
const METERED_FOR: Duration = Duration::from_secs(5);

/// The operating system's view.
#[derive(Debug, Default)]
pub struct SystemOwner {
    #[cfg(windows)]
    metered: std::sync::Mutex<Option<(std::time::Instant, bool)>>,
}

#[cfg(windows)]
impl SystemOwner {
    /// Whether the connection is metered, read at most every [`METERED_FOR`].
    fn metered(&self) -> bool {
        let mut cached = self.metered.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some((at, answer)) = *cached
            && at.elapsed() < METERED_FOR
        {
            return answer;
        }
        let answer = agent_windows::connection_is_metered().unwrap_or(false);
        *cached = Some((std::time::Instant::now(), answer));
        answer
    }
}
impl MachineInputs for SystemOwner {
    #[cfg(windows)]
    fn sample(&self) -> Sample {
        let idle = agent_windows::idle_time().unwrap_or_else(|e| {
            tracing::warn!(error = %e, "couldn't read the last input time; counting the owner as here");
            Duration::ZERO
        });
        let fullscreen = agent_windows::user_notification_state().is_ok_and(is_fullscreen);
        let on_battery = agent_windows::ac_line_status().is_ok_and(is_on_battery);
        Sample { idle, fullscreen, on_battery, metered: self.metered() }
    }

    #[cfg(not(windows))]
    fn sample(&self) -> Sample {
        Sample::QUIET
    }
}

/// `SHQueryUserNotificationState`'s answer: busy (2), a full-screen Direct3D app (3) or presentation mode (4) count
/// as a game or full-screen app.
#[must_use]
pub fn is_fullscreen(state: i32) -> bool {
    matches!(state, 2..=4)
}

/// `SYSTEM_POWER_STATUS.ACLineStatus`: 0 is on battery; 1 is plugged in, and 255 (unknown, as desktops say) counts as
/// plugged in.
#[must_use]
pub fn is_on_battery(ac_line_status: u8) -> bool {
    ac_line_status == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_unknown_power_state_counts_as_plugged_in() {
        assert!(is_on_battery(0));
        assert!(!is_on_battery(1));
        assert!(!is_on_battery(255));
    }

    #[test]
    fn busy_full_screen_and_presentation_mode_count() {
        // QUNS_NOT_PRESENT 1, BUSY 2, RUNNING_D3D_FULL_SCREEN 3, PRESENTATION_MODE 4, ACCEPTS_NOTIFICATIONS 5,
        // QUIET_TIME 6, APP 7.
        assert_eq!((1..=7).map(is_fullscreen).collect::<Vec<_>>(), [false, true, true, true, false, false, false]);
    }

    #[test]
    fn linux_and_macos_see_nobody() {
        if !cfg!(windows) {
            assert_eq!(SystemOwner::default().sample(), Sample::QUIET);
        }
    }
}
