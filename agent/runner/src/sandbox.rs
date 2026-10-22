//! The sandbox's network rules (ADR 0035): the one rules file, `agent/distro/sandbox.nft`, built into the runner so the
//! rules and the runner that checks them can never be of different versions.
//!
//! `idleo-runner load-rules` loads the file with `@HASH@` replaced by the SHA-256 of its text, and before every
//! container start the runner reads the loaded tables back (`nft -j list table`) and refuses to run a job unless they
//! hold exactly what its own copy of the file makes ([`verify`], by content: a rule changed by hand under an unchanged
//! comment is found). It then loads the rules once more and checks again, and only if they still differ does the job not
//! start. Which addresses a job can't reach that depend on the laptop's network, its default gateway and this
//! distribution's own addresses, go into a set the runner refreshes before every start ([`extra_blocked_script`]).

use std::collections::BTreeSet;
use std::fmt::Write as _;
use std::net::Ipv4Addr;

use serde_json::Value;
use sha2::{Digest, Sha256};

/// The rules file's text, with `@HASH@` still in it.
pub const RULES: &str = include_str!("../../distro/sandbox.nft");

/// The tables the rules file makes, as `nft list table` names them.
pub const TABLES: [(&str, &str); 2] = [("inet", "idleo"), ("ip", "idleo_nat")];

/// The prefix of session bridges' names.
pub const BRIDGE_PREFIX: &str = "idl";

/// The SHA-256 of the rules file's text, in lower-case hex: the version of the rules.
#[must_use]
pub fn hash() -> String {
    jobcore::storage::hex(&Sha256::digest(RULES.as_bytes()))
}

/// The rules file as it is loaded: the hash in place.
#[must_use]
pub fn rendered() -> String {
    RULES.replace("@HASH@", &hash())
}

/// The tables as this runner's rules make them, in the form [`normalize`] gives: what the loaded ones must equal.
#[derive(Clone, Debug, PartialEq)]
pub struct Expected {
    pub inet: Value,
    pub nat: Value,
}

/// A table as `nft -j list table` reports it, made comparable: the metainfo (nft's version) and every `handle` (the
/// kernel's numbering of what was added when) are left out, and so are the members of the one set that depends on the
/// laptop's network and is refreshed before every start ([`extra_blocked_script`]). Everything else is kept, in order:
/// every set and its members, every chain and its type, hook and priority, every rule and what it matches and does.
///
/// # Errors
/// If the text isn't what `nft -j` prints.
pub fn normalize(listing: &str) -> Result<Value, String> {
    fn strip(value: &mut Value) {
        match value {
            Value::Object(map) => {
                map.remove("handle");
                if map.get("name").and_then(Value::as_str) == Some("blocked_extra_v4") && map.contains_key("type") {
                    map.remove("elem");
                }
                map.values_mut().for_each(strip);
            }
            Value::Array(items) => items.iter_mut().for_each(strip),
            _ => {}
        }
    }
    let parsed: Value = serde_json::from_str(listing).map_err(|e| format!("nft's listing isn't JSON: {e}"))?;
    let mut items =
        parsed.get("nftables").and_then(Value::as_array).cloned().ok_or("nft's listing has no \"nftables\" list")?;
    items.retain(|item| item.get("metainfo").is_none());
    let mut items = Value::Array(items);
    // A set is `{"set": {..., "name": ..., "elem": [...]}}`: strip looks inside each wrapper.
    strip(&mut items);
    Ok(items)
}

/// How two normalized tables first differ, in a line; `None` when they're the same.
fn difference(table: &str, expected: &Value, loaded: &Value) -> Option<String> {
    if expected == loaded {
        return None;
    }
    let (want, have) = (expected.as_array()?, loaded.as_array()?);
    let shorten = |value: Option<&Value>| {
        let text = value.map_or_else(|| "nothing".to_owned(), ToString::to_string);
        if text.len() > 240 { format!("{}…", text.chars().take(240).collect::<String>()) } else { text }
    };
    let at = (0..want.len().max(have.len())).find(|&index| want.get(index) != have.get(index)).unwrap_or(0);
    Some(format!(
        "the network rules in table {table} differ from this runner's at item {at} of {}: expected {}, found {}",
        want.len(),
        shorten(want.get(at)),
        shorten(have.get(at))
    ))
}

/// Checks the loaded tables, by what they contain and not by a comment they carry, against the tables this runner's rules
/// make: each must exist and be exactly them, apart from the addresses [`normalize`] leaves out.
///
/// # Errors
/// What's wrong, in a line.
pub fn verify(expected: &Expected, inet_listing: &str, nat_listing: &str) -> Result<(), String> {
    for (name, want, listing) in
        [("inet idleo", &expected.inet, inet_listing), ("ip idleo_nat", &expected.nat, nat_listing)]
    {
        if listing.trim().is_empty() {
            return Err(format!("the network rules aren't loaded (no table {name})"));
        }
        let loaded = normalize(listing).map_err(|why| format!("table {name}: {why}"))?;
        if let Some(why) = difference(name, want, &loaded) {
            return Err(why);
        }
    }
    Ok(())
}

/// The default gateway, from `/proc/net/route`'s text.
#[must_use]
pub fn default_gateway(route: &str) -> Option<Ipv4Addr> {
    route.lines().skip(1).find_map(|line| {
        let mut fields = line.split_whitespace();
        let (_, destination, gateway) = (fields.next()?, fields.next()?, fields.next()?);
        (destination == "00000000")
            .then(|| u32::from_str_radix(gateway, 16).ok().filter(|gateway| *gateway != 0))?
            .map(|gateway| Ipv4Addr::from(gateway.swap_bytes()))
    })
}

/// This machine's own IPv4 addresses, from `/proc/net/fib_trie`'s text: every `/32 host LOCAL` entry.
#[must_use]
pub fn own_addresses(fib_trie: &str) -> BTreeSet<Ipv4Addr> {
    let mut addresses = BTreeSet::new();
    let mut last: Option<Ipv4Addr> = None;
    for line in fib_trie.lines() {
        let line = line.trim();
        if let Some(address) = line.strip_prefix("|-- ").or_else(|| line.strip_prefix("+-- ")) {
            last = address.split_whitespace().next().and_then(|text| text.parse().ok());
        } else if line.starts_with("/32 host LOCAL") {
            addresses.extend(last);
        }
    }
    addresses
}

/// The `nft` script that makes the extra-blocked set exactly `addresses`, in one transaction, so there's no moment when
/// it's empty.
#[must_use]
pub fn extra_blocked_script(addresses: &BTreeSet<Ipv4Addr>) -> String {
    let mut script = String::from("flush set inet idleo blocked_extra_v4\n");
    if !addresses.is_empty() {
        let list: Vec<String> = addresses.iter().map(ToString::to_string).collect();
        let _ = writeln!(script, "add element inet idleo blocked_extra_v4 {{ {} }}", list.join(", "));
    }
    script
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_rules_are_built_in_and_the_hash_names_their_text() {
        assert!(RULES.contains("@HASH@"));
        assert!(!rendered().contains("@HASH@"));
        assert_eq!(hash().len(), 64);
        assert_eq!(hash(), jobcore::storage::hex(&Sha256::digest(RULES.as_bytes())));
    }

    #[test]
    fn the_rules_block_every_home_network_range_and_allow_only_the_web_and_dns_to_the_sessions_own_gateway() {
        for range in [
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "100.64.0.0/10",
            "224.0.0.0/4",
            "192.0.0.0/24",
            "198.18.0.0/15",
            "192.0.2.0/24",
            "198.51.100.0/24",
            "203.0.113.0/24",
        ] {
            assert!(RULES.contains(range), "{range} must be blocked");
        }
        let session = RULES.split("chain session {").nth(1).unwrap().split("\n    }").next().unwrap();
        let accepts: Vec<&str> = session.lines().map(str::trim).filter(|line| line.ends_with("accept")).collect();
        assert_eq!(
            accepts,
            ["ct state established,related accept", "tcp dport { 80, 443 } accept"],
            "nothing else is let through: DNS is not, from a session to anywhere it can forward to (ADR 0038)"
        );
        assert_eq!(
            session.lines().map(str::trim).rfind(|line| !line.is_empty()),
            Some("drop"),
            "and the rest is dropped"
        );
        assert!(session.contains("meta nfproto ipv6 drop"));
        assert!(!RULES.contains("resolvers_v4"), "no public resolver is asked from a session any more");
        assert!(RULES.contains("iifname \"idl*\" drop"), "nothing on this machine is reachable");
        // The one thing on this machine a job may reach: port 53, UDP and TCP, of its own network's gateway address.
        let input = RULES.split("chain input {").nth(1).unwrap().split("\n    }").next().unwrap();
        let accepts: Vec<&str> = input.lines().map(str::trim).filter(|line| line.ends_with("accept")).collect();
        assert_eq!(accepts, ["iifname . ip daddr @session_dns_v4 meta l4proto { tcp, udp } th dport 53 accept"]);
        assert!(
            input.find("@session_dns_v4").unwrap() < input.find("iifname \"idl*\" drop").unwrap(),
            "the DNS accept comes before the drop"
        );
        for slot in 0..crate::nerdctl::SLOTS {
            let pair = format!("\"{BRIDGE_PREFIX}{slot}\" . 172.30.{slot}.1");
            assert_eq!(RULES.matches(&pair).count(), 1, "network {slot} may ask its own gateway, once");
        }
        assert_eq!(RULES.matches(" . 172.30.").count(), usize::from(crate::nerdctl::SLOTS), "and no other pair");
        assert!(RULES.contains("oifname \"idl*\" ct state new drop"), "nothing starts a connection to a job");
    }

    /// `nft -j list table inet idleo`, as far as `normalize` reads it: nft's version, the kernel's handles, a set with its
    /// members and a chain of rules.
    fn listing(handle: u32, accepted_port: u16, extra: &str, comment: &str) -> String {
        format!(
            r#"{{"nftables": [
              {{"metainfo": {{"version": "1.0.9", "release_name": "Old Doc Yak #3", "json_schema_version": 1}}}},
              {{"table": {{"family": "inet", "name": "idleo", "handle": {handle}, "comment": "{comment}"}}}},
              {{"set": {{"family": "inet", "name": "blocked_v4", "table": "idleo", "type": "ipv4_addr", "handle": 1,
                        "flags": ["interval"], "elem": [{{"prefix": {{"addr": "10.0.0.0", "len": 8}}}}]}}}},
              {{"set": {{"family": "inet", "name": "blocked_extra_v4", "table": "idleo", "type": "ipv4_addr", "handle": 2,
                        "flags": ["interval"], "elem": [{extra}]}}}},
              {{"chain": {{"family": "inet", "table": "idleo", "name": "session", "handle": 3}}}},
              {{"rule": {{"family": "inet", "table": "idleo", "chain": "session", "handle": {handle},
                         "expr": [{{"match": {{"op": "==", "left": {{"payload": {{"protocol": "tcp", "field": "dport"}}}},
                                              "right": {accepted_port}}}}}, {{"accept": null}}]}}}}
            ]}}"#
        )
    }

    fn expected() -> Expected {
        let table = normalize(&listing(7, 443, "", "c")).unwrap();
        Expected { inet: table.clone(), nat: table }
    }

    #[test]
    fn a_listing_is_compared_without_nfts_version_the_kernels_handles_or_the_networks_addresses() {
        let loaded = listing(91, 443, r#"{"prefix": {"addr": "192.168.1.1", "len": 32}}"#, "c");
        assert_eq!(verify(&expected(), &loaded, &loaded), Ok(()));
    }

    #[test]
    fn the_loaded_rules_are_checked_by_what_they_do_and_anything_else_is_refused() {
        let same = listing(7, 443, "", "c");
        assert!(verify(&expected(), "", &same).unwrap_err().contains("aren't loaded (no table inet idleo)"));
        assert!(verify(&expected(), &same, " \n").unwrap_err().contains("(no table ip idleo_nat)"));
        assert!(verify(&expected(), "not json", &same).unwrap_err().contains("isn't JSON"));
        // The comment is the same and one rule was changed by hand: 443 became 25.
        let changed = verify(&expected(), &listing(7, 25, "", "c"), &same).unwrap_err();
        assert!(changed.contains("table inet idleo differ"), "{changed}");
        assert!(changed.contains("\"right\":443") && changed.contains("\"right\":25"), "{changed}");
        // A comment changed, a rule gone, a rule added, a set member gone.
        assert!(verify(&expected(), &listing(7, 443, "", "other"), &same).is_err());
        let fewer = same.replace(r#"{"rule""#, r#"{"nothing""#);
        assert!(verify(&expected(), &fewer, &same).is_err());
        let blocked_member_gone =
            same.replace(r#""elem": [{"prefix": {"addr": "10.0.0.0", "len": 8}}]"#, r#""elem": []"#);
        assert!(verify(&expected(), &blocked_member_gone, &same).is_err());
    }

    #[test]
    fn the_gateway_comes_from_the_default_route() {
        let route = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT\n\
                     eth0\t00000000\t0120A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n\
                     eth0\t0020A8C0\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n";
        assert_eq!(default_gateway(route), Some(Ipv4Addr::new(192, 168, 32, 1)));
        assert_eq!(default_gateway("Iface\tDestination\tGateway\n"), None);
    }

    #[test]
    fn this_machines_addresses_come_from_the_routing_table() {
        let trie = "Main:\n  +-- 0.0.0.0/0 3 0 5\n     |-- 0.0.0.0\n        /0 universe UNICAST\n     +-- 127.0.0.0/8 2 0 2\n        |-- 127.0.0.1\n           /32 host LOCAL\n     +-- 172.20.10.0/28 2 0 2\n        |-- 172.20.10.0\n           /32 link BROADCAST\n        |-- 172.20.10.2\n           /32 host LOCAL\n        |-- 172.20.10.15\n           /32 link BROADCAST\nLocal:\n";
        let own = own_addresses(trie);
        assert_eq!(own, BTreeSet::from([Ipv4Addr::LOCALHOST, Ipv4Addr::new(172, 20, 10, 2)]));
    }

    #[test]
    fn the_extra_set_is_replaced_in_one_transaction() {
        let script =
            extra_blocked_script(&BTreeSet::from([Ipv4Addr::new(203, 0, 113, 7), Ipv4Addr::new(192, 168, 1, 1)]));
        assert_eq!(
            script,
            "flush set inet idleo blocked_extra_v4\nadd element inet idleo blocked_extra_v4 { 192.168.1.1, 203.0.113.7 }\n"
        );
        assert_eq!(extra_blocked_script(&BTreeSet::new()), "flush set inet idleo blocked_extra_v4\n");
    }
}
