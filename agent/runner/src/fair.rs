//! Fair use of the host's home internet (ADR 0038): a job can't run a scan or a flood from someone's home, and can't fill the
//! owner's upload so their own internet stops working. Two limits, per session, in one nftables table the runner owns,
//! `inet idleo_fair`, beside the sandbox's own (`sandbox.rs`), which holds no numbers that differ between sessions:
//!
//! - **New connections**: 50 a second with a burst of 100. A job opening more is dropped at the packet, and counted.
//! - **Upload**: at most the cap the agent gave the session (half of what the laptop measured its upload to be, never under a
//!   floor), as a token bucket over everything the session sends out, with a burst of a quarter of a second. Download isn't
//!   capped: a download makes about 0.6% of its size in upload traffic (acknowledgements), which a cap of at least 5 Mbit/s
//!   carries for a download of 800 Mbit/s (measured, ADR 0038).
//!
//! The rules are `limit rate`, not a queue: nft drops what is over the rate and the job's TCP slows to fit (measured over
//! 26 s and 38 s: 99% and 101% of the cap). Nothing here needs `tc`, which the distribution doesn't carry and Microsoft's
//! 6.1 WSL kernel doesn't build (`NET_SCH_TBF`), while `NFT_LIMIT` is in every WSL kernel's configuration.
//!
//! One chain for each session network (`fair_<N>`, called from the forward hook for `idl<N>` only), whose rules the runner
//! replaces in one transaction when a session starts on that network and empties when it ends; two named counters for each
//! (`conn_drops_<N>`, `up_drops_<N>`) that say what was dropped. Our own traffic is not in these rules at all: the runner's
//! uploads of checkpoints and outputs start in the distribution itself, never from a session's network.

use std::collections::BTreeMap;
use std::fmt::Write as _;

use serde_json::Value;

use crate::nerdctl::SLOTS;
use crate::sandbox::BRIDGE_PREFIX;

/// The nftables table the runner owns, in the `inet` family.
pub const TABLE: &str = "idleo_fair";
/// New connections a session may open each second.
pub const NEW_CONNECTIONS_PER_SECOND: u32 = 50;
/// How many a session may open at once before that rate holds.
pub const NEW_CONNECTION_BURST: u32 = 100;
/// The upload cap used when the agent doesn't give one (it has not measured the laptop's upload yet), in kbit/s.
pub const DEFAULT_UPLOAD_CAP_KBPS: u32 = 5_000;
/// How much a session may send ahead of the cap, as time at the cap's rate.
pub const BURST_MS: u64 = 250;

/// What was dropped for one session, since its rules were put in place.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Drops {
    /// New connections refused for being over the rate.
    pub connections: u64,
    /// Packets dropped for being over the upload cap.
    pub upload_packets: u64,
    /// The bytes in those.
    pub upload_bytes: u64,
}

/// The upload cap in effect for a session: the agent's, or the default when it gave none.
#[must_use]
pub fn effective_cap_kbps(asked: u32) -> u32 {
    if asked == 0 { DEFAULT_UPLOAD_CAP_KBPS } else { asked }
}

/// The table, its counters and its chains, for every session network: loaded once, when the table isn't there. A session's
/// rules go into its chain with [`session_script`].
#[must_use]
pub fn base_script() -> String {
    let mut script = format!("table inet {TABLE}\ndelete table inet {TABLE}\n\ntable inet {TABLE} {{\n");
    for slot in 0..SLOTS {
        let _ = write!(script, "    counter conn_drops_{slot} {{\n    }}\n    counter up_drops_{slot} {{\n    }}\n");
    }
    for slot in 0..SLOTS {
        let _ = write!(script, "    chain fair_{slot} {{\n    }}\n");
    }
    script.push_str("    chain forward_in {\n        type filter hook forward priority filter - 1; policy accept;\n");
    for slot in 0..SLOTS {
        let _ = writeln!(script, "        iifname \"{BRIDGE_PREFIX}{slot}\" jump fair_{slot}");
    }
    script.push_str("    }\n}\n");
    script
}

/// The rules of a session on network `slot` with an upload cap of `cap_kbps`, replacing whatever the network's chain held,
/// and its counters set to zero, in one transaction.
#[must_use]
pub fn session_script(slot: u8, cap_kbps: u32) -> String {
    let bytes_per_second = u64::from(cap_kbps) * 125;
    let burst = (bytes_per_second * BURST_MS / 1000).max(8_000);
    format!(
        "flush chain inet {TABLE} fair_{slot}\n\
         reset counter inet {TABLE} conn_drops_{slot}\n\
         reset counter inet {TABLE} up_drops_{slot}\n\
         add rule inet {TABLE} fair_{slot} ct state new limit rate over {NEW_CONNECTIONS_PER_SECOND}/second burst {NEW_CONNECTION_BURST} packets counter name \"conn_drops_{slot}\" drop\n\
         add rule inet {TABLE} fair_{slot} limit rate over {bytes_per_second} bytes/second burst {burst} bytes counter name \"up_drops_{slot}\" drop\n"
    )
}

/// Takes a session's rules out of its network's chain, when the session ends.
#[must_use]
pub fn clear_script(slot: u8) -> String {
    format!("flush chain inet {TABLE} fair_{slot}\n")
}

/// Whether `nft -j list chain inet idleo_fair fair_<slot>` shows exactly the two rules [`session_script`] made, with the cap
/// asked for: a session starts only when its limits are in place.
///
/// # Errors
/// What differs, in a line.
pub fn verify_session(listing: &str, slot: u8, cap_kbps: u32) -> Result<(), String> {
    let parsed: Value = serde_json::from_str(listing).map_err(|e| format!("nft's listing isn't JSON: {e}"))?;
    let rules: Vec<&Value> = parsed
        .get("nftables")
        .and_then(Value::as_array)
        .ok_or("nft's listing has no \"nftables\" list")?
        .iter()
        .filter_map(|item| item.get("rule"))
        .collect();
    if rules.len() != 2 {
        return Err(format!("the fair-use rules of network {slot} are {} rules, not 2", rules.len()));
    }
    // Each limit as (rate, per, unit); nft says "bytes" for a rate in bytes and nothing for one in packets.
    let limits: Vec<(u64, &str, &str)> = rules
        .iter()
        .filter_map(|rule| {
            rule.get("expr")?.as_array()?.iter().find_map(|statement| {
                let limit = statement.get("limit")?;
                Some((
                    limit.get("rate")?.as_u64()?,
                    limit.get("per").and_then(Value::as_str).unwrap_or(""),
                    limit.get("rate_unit").and_then(Value::as_str).unwrap_or(""),
                ))
            })
        })
        .collect();
    let wanted_bytes = u64::from(cap_kbps) * 125;
    if limits != [(u64::from(NEW_CONNECTIONS_PER_SECOND), "second", ""), (wanted_bytes, "second", "bytes")] {
        return Err(format!(
            "the fair-use limits of network {slot} are {limits:?}, not {NEW_CONNECTIONS_PER_SECOND}/second and {wanted_bytes} bytes/second"
        ));
    }
    Ok(())
}

/// What each session network's counters hold, from `nft -j list counters table inet idleo_fair`: `(packets, bytes)` by counter
/// name.
///
/// # Errors
/// If the text isn't what `nft -j` prints.
pub fn parse_counters(listing: &str) -> Result<BTreeMap<String, (u64, u64)>, String> {
    let parsed: Value = serde_json::from_str(listing).map_err(|e| format!("nft's listing isn't JSON: {e}"))?;
    let items = parsed.get("nftables").and_then(Value::as_array).ok_or("nft's listing has no \"nftables\" list")?;
    Ok(items
        .iter()
        .filter_map(|item| item.get("counter"))
        .filter_map(|counter| {
            Some((
                counter.get("name")?.as_str()?.to_owned(),
                (counter.get("packets")?.as_u64()?, counter.get("bytes")?.as_u64()?),
            ))
        })
        .collect())
}

/// What one session network has dropped, from [`parse_counters`].
#[must_use]
pub fn drops_of(counters: &BTreeMap<String, (u64, u64)>, slot: u8) -> Drops {
    let connections = counters.get(&format!("conn_drops_{slot}")).map_or(0, |(packets, _)| *packets);
    let (upload_packets, upload_bytes) = counters.get(&format!("up_drops_{slot}")).copied().unwrap_or((0, 0));
    Drops { connections, upload_packets, upload_bytes }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_base_has_a_chain_and_two_counters_for_every_session_network_and_calls_each_from_its_bridge_only() {
        let script = base_script();
        for slot in 0..SLOTS {
            assert!(script.contains(&format!("chain fair_{slot} {{")), "{slot}");
            assert!(script.contains(&format!("counter conn_drops_{slot} {{")));
            assert!(script.contains(&format!("counter up_drops_{slot} {{")));
            assert!(script.contains(&format!("iifname \"idl{slot}\" jump fair_{slot}")));
        }
        assert_eq!(
            script.matches("jump fair_").count(),
            usize::from(SLOTS),
            "one jump a network, nothing for the rest"
        );
        assert!(script.contains("hook forward priority filter - 1"), "before the sandbox's own chain");
        assert!(!script.contains("accept\n"), "these rules only ever drop; the sandbox decides what is let through");
    }

    #[test]
    fn a_sessions_rules_are_a_connection_rate_and_an_upload_cap_replacing_the_last_in_one_transaction() {
        let script = session_script(5, 25_000);
        let lines: Vec<&str> = script.lines().collect();
        assert_eq!(lines[0], "flush chain inet idleo_fair fair_5");
        assert!(lines[1].starts_with("reset counter") && lines[2].starts_with("reset counter"));
        assert_eq!(
            lines[3],
            "add rule inet idleo_fair fair_5 ct state new limit rate over 50/second burst 100 packets counter name \"conn_drops_5\" drop"
        );
        // 25 Mbit/s is 3,125,000 bytes a second, a quarter of a second of it ahead.
        assert_eq!(
            lines[4],
            "add rule inet idleo_fair fair_5 limit rate over 3125000 bytes/second burst 781250 bytes counter name \"up_drops_5\" drop"
        );
        assert_eq!(lines.len(), 5);
        assert_eq!(clear_script(5), "flush chain inet idleo_fair fair_5\n");
    }

    #[test]
    fn a_small_cap_still_has_room_for_one_packet_burst_and_no_cap_means_the_default() {
        assert!(session_script(0, 1).contains("burst 8000 bytes"));
        assert_eq!(effective_cap_kbps(0), 5_000);
        assert_eq!(effective_cap_kbps(40_000), 40_000);
        assert!(session_script(0, effective_cap_kbps(0)).contains("625000 bytes/second"));
    }

    /// `nft -j list chain inet idleo_fair fair_5` as nft 1.0.9 prints it for the rules of [`session_script`].
    fn chain_listing(connections: u64, bytes: u64) -> String {
        format!(
            r#"{{"nftables": [{{"metainfo": {{"version": "1.0.9", "release_name": "Old Doc Yak #3", "json_schema_version": 1}}}},
              {{"chain": {{"family": "inet", "table": "idleo_fair", "name": "fair_5", "handle": 1}}}},
              {{"rule": {{"family": "inet", "table": "idleo_fair", "chain": "fair_5", "handle": 132,
                         "expr": [{{"match": {{"op": "in", "left": {{"ct": {{"key": "state"}}}}, "right": "new"}}}},
                                  {{"limit": {{"rate": {connections}, "burst": 100, "per": "second", "inv": true}}}},
                                  {{"counter": "conn_drops_5"}}, {{"drop": null}}]}}}},
              {{"rule": {{"family": "inet", "table": "idleo_fair", "chain": "fair_5", "handle": 133,
                         "expr": [{{"limit": {{"rate": {bytes}, "burst": 781250, "per": "second", "inv": true,
                                              "rate_unit": "bytes", "burst_unit": "bytes"}}}},
                                  {{"counter": "up_drops_5"}}, {{"drop": null}}]}}}}]}}"#
        )
    }

    #[test]
    fn a_sessions_rules_are_checked_by_the_numbers_they_hold_and_anything_else_is_refused() {
        assert_eq!(verify_session(&chain_listing(50, 3_125_000), 5, 25_000), Ok(()));
        assert!(verify_session(&chain_listing(50, 3_125_000), 5, 10_000).unwrap_err().contains("1250000"));
        assert!(verify_session(&chain_listing(500, 3_125_000), 5, 25_000).unwrap_err().contains("not 50/second"));
        // The same numbers as packets, not bytes, are not the same limit.
        let in_packets = chain_listing(50, 3_125_000).replace(r#""rate_unit": "bytes", "#, "");
        assert!(verify_session(&in_packets, 5, 25_000).is_err());
        let empty = r#"{"nftables": [{"chain": {"name": "fair_5"}}]}"#;
        assert!(verify_session(empty, 5, 25_000).unwrap_err().contains("0 rules, not 2"));
        assert!(verify_session("not json", 5, 25_000).unwrap_err().contains("isn't JSON"));
    }

    #[test]
    fn the_counters_are_read_by_name_and_a_network_with_none_dropped_has_zeroes() {
        let listing = r#"{"nftables": [{"metainfo": {"version": "1.0.9"}},
            {"counter": {"family": "inet", "name": "conn_drops_5", "table": "idleo_fair", "handle": 1, "packets": 321, "bytes": 19260}},
            {"counter": {"family": "inet", "name": "up_drops_5", "table": "idleo_fair", "handle": 2, "packets": 40, "bytes": 52000}},
            {"counter": {"family": "inet", "name": "up_drops_6", "table": "idleo_fair", "handle": 3, "packets": 9, "bytes": 900}}]}"#;
        let counters = parse_counters(listing).unwrap();
        assert_eq!(drops_of(&counters, 5), Drops { connections: 321, upload_packets: 40, upload_bytes: 52_000 });
        assert_eq!(drops_of(&counters, 7), Drops::default());
        assert!(parse_counters("[]").is_err());
    }
}
