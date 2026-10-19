//! The sandbox's network rules (ADR 0035): the one rules file, `agent/distro/sandbox.nft`, built into the runner so the
//! rules and the runner that checks them can never be of different versions.
//!
//! `idleo-runner load-rules` loads the file with `@HASH@` replaced by the SHA-256 of its text, and before every
//! container start the runner reads the loaded tables back and refuses to run a job unless they carry the hash of its own
//! copy ([`verify`]). Which addresses a job can't reach that depend on the laptop's network, its default gateway and this
//! distribution's own addresses, go into a set the runner refreshes before every start ([`extra_blocked_script`]).

use std::collections::BTreeSet;
use std::fmt::Write as _;
use std::net::Ipv4Addr;

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

/// Checks the loaded tables against this runner's rules: each must exist and carry this version's hash, and the inet table
/// must still have the chain that holds the drops.
///
/// # Errors
/// What's wrong, in a line.
pub fn verify(inet_listing: &str, nat_listing: &str) -> Result<(), String> {
    let tag = format!("comment \"idleo-sandbox {}\"", hash());
    for (name, listing) in [("inet idleo", inet_listing), ("ip idleo_nat", nat_listing)] {
        if listing.trim().is_empty() {
            return Err(format!("the network rules aren't loaded (no table {name})"));
        }
        if !listing.contains(&tag) {
            return Err(format!("the network rules in table {name} aren't this runner's version"));
        }
    }
    for needed in ["chain session", "chain forward", "chain input", "set blocked_v4"] {
        if !inet_listing.contains(needed) {
            return Err(format!("the network rules are incomplete (no {needed})"));
        }
    }
    if !nat_listing.contains("masquerade") {
        return Err("the network rules are incomplete (no masquerade)".to_owned());
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

    /// What `nft list table` would print, as far as `verify` reads it.
    fn loaded(table: &str) -> String {
        let kept = |line: &&str| {
            line.contains("comment")
                || line.trim_start().starts_with("chain ")
                || line.contains("set blocked_v4")
                || line.contains("masquerade")
        };
        format!("table {table} {{\n{}\n}}", rendered().lines().filter(kept).collect::<Vec<_>>().join("\n"))
    }

    #[test]
    fn the_rules_are_built_in_and_the_hash_names_their_text() {
        assert!(RULES.contains("@HASH@"));
        assert!(!rendered().contains("@HASH@"));
        assert_eq!(hash().len(), 64);
        assert_eq!(hash(), jobcore::storage::hex(&Sha256::digest(RULES.as_bytes())));
    }

    #[test]
    fn the_rules_block_every_home_network_range_and_allow_only_dns_and_the_web() {
        for range in ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10", "224.0.0.0/4"]
        {
            assert!(RULES.contains(range), "{range} must be blocked");
        }
        let session = RULES.split("chain session {").nth(1).unwrap().split("\n    }").next().unwrap();
        let accepts: Vec<&str> = session.lines().map(str::trim).filter(|line| line.ends_with("accept")).collect();
        assert_eq!(
            accepts,
            [
                "ct state established,related accept",
                "ip daddr @resolvers_v4 udp dport 53 accept",
                "ip daddr @resolvers_v4 tcp dport 53 accept",
                "tcp dport { 80, 443 } accept",
            ],
            "nothing else is let through"
        );
        assert_eq!(
            session.lines().map(str::trim).rfind(|line| !line.is_empty()),
            Some("drop"),
            "and the rest is dropped"
        );
        assert!(session.contains("meta nfproto ipv6 drop"));
        assert!(RULES.contains("iifname \"idl*\" drop"), "nothing on this machine is reachable");
        assert!(RULES.contains("oifname \"idl*\" ct state new drop"), "nothing starts a connection to a job");
    }

    #[test]
    fn rules_of_this_version_pass_and_anything_else_is_refused() {
        assert_eq!(verify(&loaded("inet idleo"), &loaded("ip idleo_nat")), Ok(()));
        assert!(verify("", &loaded("ip idleo_nat")).unwrap_err().contains("aren't loaded"));
        assert!(verify(&loaded("inet idleo"), "").unwrap_err().contains("aren't loaded"));
        let older = loaded("inet idleo").replace(&hash(), &"0".repeat(64));
        assert!(verify(&older, &loaded("ip idleo_nat")).unwrap_err().contains("version"));
        let gutted = loaded("inet idleo").replace("chain session", "chain other");
        assert!(verify(&gutted, &loaded("ip idleo_nat")).unwrap_err().contains("incomplete"));
        let no_nat = loaded("ip idleo_nat").replace("masquerade", "");
        assert!(verify(&loaded("inet idleo"), &no_nat).unwrap_err().contains("masquerade"));
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
