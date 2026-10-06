//! Deciding on an offer (ADR 0015): accept only work this host can do now.

use agent_proto::v1::OfferDeclineReason;

/// What the agent knows when an offer arrives.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(clippy::struct_excessive_bools)] // Facts the decision reads, each its own condition, not mode flags.
pub struct OfferFacts {
    /// The offer's `expires_in` has already run out.
    pub expired: bool,
    /// Ctrl-C or a service stop is under way.
    pub shutting_down: bool,
    /// The agent has a session runner at all.
    pub can_run: bool,
    /// Sessions running now, and the most the control plane allows (`Welcome.policy`).
    pub running_sessions: i32,
    pub max_concurrent_sessions: i32,
    /// The owner is at the machine.
    pub owner_active: bool,
    /// The hottest GPU's latest reading, if any reading has been taken.
    pub hottest_gpu_c: Option<i32>,
    /// The policy's temperature cap.
    pub gpu_temp_cap_c: i32,
    /// The largest GPU's memory, and what the offer needs.
    pub largest_gpu_vram_mb: i32,
    pub vram_needed_mb: i32,
}

/// Accept, or the reason to decline, checked in this order.
///
/// # Errors
/// The first reason the offer can't be taken.
pub fn decide(facts: &OfferFacts) -> Result<(), OfferDeclineReason> {
    if facts.expired {
        return Err(OfferDeclineReason::Expired);
    }
    if facts.shutting_down {
        return Err(OfferDeclineReason::ShuttingDown);
    }
    if !facts.can_run {
        return Err(OfferDeclineReason::InsufficientResources);
    }
    if facts.running_sessions >= facts.max_concurrent_sessions {
        return Err(OfferDeclineReason::AtCapacity);
    }
    if facts.owner_active {
        return Err(OfferDeclineReason::OwnerActive);
    }
    if facts.hottest_gpu_c.is_some_and(|temp| temp >= facts.gpu_temp_cap_c) {
        return Err(OfferDeclineReason::TooHot);
    }
    if facts.largest_gpu_vram_mb < facts.vram_needed_mb {
        return Err(OfferDeclineReason::InsufficientResources);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const FITS: OfferFacts = OfferFacts {
        expired: false,
        shutting_down: false,
        can_run: true,
        running_sessions: 0,
        max_concurrent_sessions: 1,
        owner_active: false,
        hottest_gpu_c: Some(60),
        gpu_temp_cap_c: 85,
        largest_gpu_vram_mb: 12_282,
        vram_needed_mb: 1024,
    };

    #[test]
    fn an_offer_that_fits_is_accepted() {
        assert_eq!(decide(&FITS), Ok(()));
        assert_eq!(decide(&OfferFacts { hottest_gpu_c: None, ..FITS }), Ok(()), "no reading yet");
    }

    #[test]
    fn each_reason_declines() {
        let cases = [
            (OfferFacts { expired: true, ..FITS }, OfferDeclineReason::Expired),
            (OfferFacts { shutting_down: true, ..FITS }, OfferDeclineReason::ShuttingDown),
            (OfferFacts { can_run: false, ..FITS }, OfferDeclineReason::InsufficientResources),
            (OfferFacts { running_sessions: 1, ..FITS }, OfferDeclineReason::AtCapacity),
            (OfferFacts { owner_active: true, ..FITS }, OfferDeclineReason::OwnerActive),
            (OfferFacts { hottest_gpu_c: Some(85), ..FITS }, OfferDeclineReason::TooHot),
            (OfferFacts { vram_needed_mb: 16_384, ..FITS }, OfferDeclineReason::InsufficientResources),
        ];
        for (facts, reason) in cases {
            assert_eq!(decide(&facts), Err(reason), "{facts:?}");
        }
    }

    #[test]
    fn an_expired_offer_is_declined_as_expired_whatever_else_is_true() {
        let everything_wrong = OfferFacts { expired: true, owner_active: true, shutting_down: true, ..FITS };
        assert_eq!(decide(&everything_wrong), Err(OfferDeclineReason::Expired));
    }
}
