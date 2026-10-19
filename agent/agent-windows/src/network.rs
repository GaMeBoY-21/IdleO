//! Whether the connection the machine reaches the internet by is metered (ADR 0035), so a job image of several gigabytes is
//! never downloaded on the owner's data allowance.
//!
//! This is the `WinRT` `NetworkInformation` API (`GetInternetConnectionProfile().GetConnectionCost()`), which the `windows` crate
//! projects as safe functions: there is no `unsafe` here at all, and the module sits in this crate only because every call
//! into Windows does. A connection counts as metered when Windows says its cost is `Fixed` (a data cap) or `Variable` (pay by
//! the use), or that it is roaming or over its limit. When Windows can't say (no profile: offline, or the API refuses), the
//! answer is "unknown", which the agent treats as metered: a download waits rather than guesses.

use windows::Networking::Connectivity::{NetworkCostType, NetworkInformation};

/// `Some(true)` if the internet connection is metered, `Some(false)` if it isn't, `None` if Windows can't say.
#[must_use]
pub fn connection_is_metered() -> Option<bool> {
    let profile = NetworkInformation::GetInternetConnectionProfile().ok()?;
    let cost = profile.GetConnectionCost().ok()?;
    let kind = cost.NetworkCostType().ok()?;
    let limited = cost.Roaming().unwrap_or(false) || cost.OverDataLimit().unwrap_or(false);
    Some(limited || matches!(kind, NetworkCostType::Fixed | NetworkCostType::Variable))
}
