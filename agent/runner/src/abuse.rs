//! What the runner looks for in a session (ADR 0038): software no renter may run on a host's GPU, and the pools it reports to.
//!
//! **Stop only on high-confidence signals; flag everything else.** A false stop costs an honest renter their job, so a
//! heuristic never stops one by itself:
//!
//! - a process whose **executable's SHA-256** is in the list **stops** the session (renaming a binary doesn't beat the hash);
//! - a process whose **name** is in the list is **flagged** (a coincidental name stops no one);
//! - a lookup of a **mining pool's domain** (or any name below it) is answered `NXDOMAIN` and **flagged**; a second one in the
//!   same session **stops** it.
//!
//! The list is one versioned data file in the repository, `agent/runner/abuse/blocklist.json`, each entry with its source,
//! built into the runner so it ships with the distribution. A hash only catches a build that was never changed: anyone can
//! recompile an open-source miner. That is why a name flags, why the DNS check exists, and why the behaviour reported every
//! minute (`Event::Behavior`) reaches an admin who decides. A miner that uses DNS-over-HTTPS or a raw address on port 443 beats
//! the DNS check too; the review queue is for what the rest cannot be sure of.
//!
//! This module is the list and the matching, with no I/O beyond reading the development marker's test entries.

use std::collections::{HashMap, HashSet};

use jobcore::protocol::AbuseKind;
use serde::Deserialize;

/// The list, as the repository keeps it.
pub const BUILT_IN: &str = include_str!("../abuse/blocklist.json");

/// What a listed program is for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    /// A cryptocurrency miner.
    Miner,
    /// A password cracker.
    PasswordCracker,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct File {
    #[serde(rename = "_about")]
    _about: Option<String>,
    version: String,
    names: Vec<NameEntry>,
    hashes: Vec<HashEntry>,
    pools: Vec<PoolEntry>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct NameEntry {
    name: String,
    kind: Kind,
    source: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct HashEntry {
    sha256: String,
    name: String,
    kind: Kind,
    source: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PoolEntry {
    domain: String,
    source: String,
}

/// What a process in a session matched.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Finding {
    /// Its name is listed: flagged, not stopped.
    Name {
        /// The name it was found under.
        name: String,
        /// What it is for.
        kind: Kind,
    },
    /// Its executable is a listed build: the session is stopped.
    Hash {
        /// The listed name of the build.
        name: String,
        /// The executable's SHA-256, lower-case hex.
        sha256: String,
        /// What it is for.
        kind: Kind,
    },
}

/// A process in a session's container, as the runner saw it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessSeen {
    /// Its id in the distribution.
    pub pid: u32,
    /// When it started (clock ticks since boot), so a pid reused later is a new process.
    pub started: u64,
    /// The kernel's name for it (`/proc/<pid>/comm`, at most 15 characters).
    pub comm: String,
    /// The last part of its executable's path, lower-cased.
    pub exe_name: String,
    /// The executable's SHA-256, only when the runner was asked to work it out.
    pub sha256: Option<String>,
}

/// The list in use.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Blocklist {
    version: String,
    names: HashMap<String, Kind>,
    hashes: HashMap<String, (String, Kind)>,
    pools: Vec<String>,
}

impl Blocklist {
    /// The list built into this runner.
    ///
    /// # Panics
    /// Never in a release: a test parses it, so a malformed file doesn't build into a runner that would panic.
    #[must_use]
    pub fn built_in() -> Self {
        Self::parse(BUILT_IN).expect("the built-in blocklist is valid")
    }

    /// Parses a list, refusing what can't be matched safely or isn't sourced.
    ///
    /// # Errors
    /// What is wrong with it, in a line.
    pub fn parse(json: &str) -> Result<Self, String> {
        let file: File = serde_json::from_str(json).map_err(|e| format!("the blocklist isn't valid: {e}"))?;
        let sourced = |source: &str| source.starts_with("https://") && source.len() > 12;
        let mut names = HashMap::new();
        for entry in file.names {
            let name = entry.name.to_ascii_lowercase();
            if name.is_empty()
                || name.len() > 15
                || !name
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'_' | b'-'))
            {
                return Err(format!("{:?} can't be the name of a process (at most 15 of a-z, 0-9, . _ -)", entry.name));
            }
            if !sourced(&entry.source) {
                return Err(format!("{name}: every entry names its source, an https address"));
            }
            names.insert(name, entry.kind);
        }
        let mut hashes = HashMap::new();
        for entry in file.hashes {
            if entry.sha256.len() != 64
                || !entry.sha256.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                return Err(format!("{}: a SHA-256 is 64 lower-case hex digits", entry.name));
            }
            if !sourced(&entry.source) {
                return Err(format!("{}: every entry names its source, an https address", entry.name));
            }
            hashes.insert(entry.sha256, (entry.name, entry.kind));
        }
        let mut pools = Vec::new();
        for entry in file.pools {
            let domain = entry.domain.to_ascii_lowercase();
            let labels_ok = domain.split('.').count() >= 2
                && domain.split('.').all(|label| {
                    !label.is_empty()
                        && label.len() <= 63
                        && label.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
                });
            if !labels_ok {
                return Err(format!("{:?} isn't a domain name", entry.domain));
            }
            if !sourced(&entry.source) {
                return Err(format!("{domain}: every entry names its source, an https address"));
            }
            pools.push(domain);
        }
        Ok(Self { version: file.version, names, hashes, pools })
    }

    /// Adds the entries a development distribution's marker file (`/etc/idleo/development`) names for the tests: lines
    /// `abuse-name=`, `abuse-pool=` and `abuse-sha256=`. A release distribution has no marker and so never reads any.
    #[must_use]
    pub fn with_test_entries(mut self, marker: &str) -> Self {
        for line in marker.lines() {
            let line = line.trim();
            if let Some(name) = line.strip_prefix("abuse-name=") {
                // A process name is at most 15 characters in the kernel; a longer test name is cut the way `comm` cuts it.
                self.names.insert(name.trim().to_ascii_lowercase().chars().take(15).collect(), Kind::Miner);
            } else if let Some(domain) = line.strip_prefix("abuse-pool=") {
                self.pools.push(domain.trim().to_ascii_lowercase());
            } else if let Some(hash) = line.strip_prefix("abuse-sha256=") {
                self.hashes.insert(hash.trim().to_ascii_lowercase(), ("a test build".to_owned(), Kind::Miner));
            }
        }
        self
    }

    /// The list's version, a date.
    #[must_use]
    pub fn version(&self) -> &str {
        &self.version
    }

    /// Whether any executable hash is listed, so the runner only reads executables when there is something to compare with.
    #[must_use]
    pub fn has_hashes(&self) -> bool {
        !self.hashes.is_empty()
    }

    /// How many names, hashes and pool domains are listed.
    #[must_use]
    pub fn counts(&self) -> (usize, usize, usize) {
        (self.names.len(), self.hashes.len(), self.pools.len())
    }

    /// The listed domain a looked-up name is, or is below: `eu.nanopool.org` and `nanopool.org` are `nanopool.org`;
    /// `mynanopool.org` is not.
    #[must_use]
    pub fn pool_matching(&self, queried: &str) -> Option<&str> {
        let queried = queried.trim_end_matches('.').to_ascii_lowercase();
        self.pools
            .iter()
            .find(|domain| queried == **domain || queried.ends_with(&format!(".{domain}")))
            .map(String::as_str)
    }

    /// What a process matches: a hash first (it stops), else a name.
    #[must_use]
    pub fn check(&self, process: &ProcessSeen) -> Option<Finding> {
        if let Some(sha256) = &process.sha256
            && let Some((name, kind)) = self.hashes.get(sha256)
        {
            return Some(Finding::Hash { name: name.clone(), sha256: sha256.clone(), kind: *kind });
        }
        for candidate in [process.comm.to_ascii_lowercase(), process.exe_name.to_ascii_lowercase()] {
            // `comm` is cut to 15 characters; a name is listed as it fits there.
            let candidate: String = candidate.chars().take(15).collect();
            if let Some(kind) = self.names.get(&candidate) {
                return Some(Finding::Name { name: candidate, kind: *kind });
            }
        }
        None
    }
}

/// What the runner does about a signal.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    /// Nothing new.
    Nothing,
    /// Tell the control plane; the session goes on.
    Flag(Report),
    /// Tell the control plane and stop the session.
    Stop(Report),
}

/// What an `Event::Abuse` says.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Report {
    /// Which signal.
    pub kind: AbuseKind,
    /// The process's name or the pool's domain.
    pub detail: String,
    /// The executable's SHA-256, for a hash.
    pub sha256: String,
    /// Dropped connections in all, for [`AbuseKind::ConnectionDrops`].
    pub count: u64,
}

/// How many minutes in a row the fair-use limit must have dropped a job's new connections before an admin is told.
pub const DROP_MINUTES: u32 = 5;

/// What one session has shown so far, and what that makes of the next signal: **only a listed build, or a second lookup of a
/// pool, stops a session**; a name, a first lookup and dropped connections only tell.
#[derive(Debug, Default)]
pub struct Judge {
    told: HashSet<String>,
    pool_lookups: u32,
    drop_minutes: u32,
    drops_told: bool,
}

impl Judge {
    /// A process matched the list.
    pub fn process(&mut self, finding: &Finding) -> Verdict {
        match finding {
            Finding::Name { name, .. } => {
                if self.told.insert(format!("name:{name}")) {
                    Verdict::Flag(Report {
                        kind: AbuseKind::ProcessName,
                        detail: name.clone(),
                        sha256: String::new(),
                        count: 0,
                    })
                } else {
                    Verdict::Nothing
                }
            }
            Finding::Hash { name, sha256, .. } => Verdict::Stop(Report {
                kind: AbuseKind::ProcessHash,
                detail: name.clone(),
                sha256: sha256.clone(),
                count: 0,
            }),
        }
    }

    /// The forwarder refused a lookup of the listed pool `domain`: the first tells, the second stops.
    pub fn pool_lookup(&mut self, domain: &str) -> Verdict {
        self.pool_lookups += 1;
        let report = Report { kind: AbuseKind::PoolLookup, detail: domain.to_owned(), sha256: String::new(), count: 0 };
        match self.pool_lookups {
            1 => Verdict::Flag(report),
            2 => Verdict::Stop(report),
            _ => Verdict::Nothing,
        }
    }

    /// A minute has passed in which the fair-use limit dropped `dropped` new connections (`total` in all): after
    /// [`DROP_MINUTES`] minutes in a row, once, an admin is told.
    pub fn minute(&mut self, dropped: u64, total: u64) -> Verdict {
        self.drop_minutes = if dropped > 0 { self.drop_minutes + 1 } else { 0 };
        if self.drop_minutes >= DROP_MINUTES && !self.drops_told {
            self.drops_told = true;
            return Verdict::Flag(Report {
                kind: AbuseKind::ConnectionDrops,
                detail: String::new(),
                sha256: String::new(),
                count: total,
            });
        }
        Verdict::Nothing
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn name_finding(name: &str) -> Finding {
        Finding::Name { name: name.to_owned(), kind: Kind::Miner }
    }

    #[test]
    fn a_name_only_tells_and_only_once() {
        let mut judge = Judge::default();
        assert!(matches!(
            judge.process(&name_finding("xmrig")),
            Verdict::Flag(Report { kind: AbuseKind::ProcessName, .. })
        ));
        assert_eq!(judge.process(&name_finding("xmrig")), Verdict::Nothing, "told once however often it starts");
        assert!(
            matches!(judge.process(&name_finding("hashcat")), Verdict::Flag(_)),
            "another name is another thing to tell"
        );
    }

    #[test]
    fn a_listed_build_stops_the_session_and_says_which() {
        let mut judge = Judge::default();
        let found = Finding::Hash { name: "xmrig 6.21".to_owned(), sha256: "d".repeat(64), kind: Kind::Miner };
        match judge.process(&found) {
            Verdict::Stop(report) => {
                assert_eq!((report.kind, report.detail.as_str()), (AbuseKind::ProcessHash, "xmrig 6.21"));
                assert_eq!(report.sha256, "d".repeat(64));
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn the_first_lookup_of_a_pool_tells_and_the_second_stops() {
        let mut judge = Judge::default();
        assert!(matches!(judge.pool_lookup("nanopool.org"), Verdict::Flag(Report { kind: AbuseKind::PoolLookup, .. })));
        match judge.pool_lookup("2miners.com") {
            Verdict::Stop(report) => assert_eq!(report.detail, "2miners.com"),
            other => panic!("{other:?}"),
        }
        assert_eq!(judge.pool_lookup("2miners.com"), Verdict::Nothing, "already stopped; nothing more to say");
    }

    #[test]
    fn dropped_connections_tell_an_admin_after_five_minutes_in_a_row_and_never_stop_anyone() {
        let mut judge = Judge::default();
        for minute in 1..=4 {
            assert_eq!(judge.minute(300, 300 * minute), Verdict::Nothing);
        }
        // A quiet minute starts the count again.
        assert_eq!(judge.minute(0, 1200), Verdict::Nothing);
        for _ in 0..4 {
            assert_eq!(judge.minute(10, 1300), Verdict::Nothing);
        }
        match judge.minute(10, 1310) {
            Verdict::Flag(report) => assert_eq!((report.kind, report.count), (AbuseKind::ConnectionDrops, 1310)),
            other => panic!("{other:?}"),
        }
        assert_eq!(judge.minute(10, 1320), Verdict::Nothing, "told once");
    }

    fn process(comm: &str, exe: &str, sha256: Option<&str>) -> ProcessSeen {
        ProcessSeen {
            pid: 100,
            started: 1,
            comm: comm.to_owned(),
            exe_name: exe.to_owned(),
            sha256: sha256.map(str::to_owned),
        }
    }

    #[test]
    fn the_list_in_the_repository_is_valid_sourced_and_versioned() {
        let list = Blocklist::built_in();
        let (names, hashes, pools) = list.counts();
        assert!(names >= 10 && pools >= 10, "{names} names, {pools} pools");
        assert_eq!(hashes, 0, "no executable hash is listed until a source publishes one (docs/runbooks/abuse.md)");
        assert!(list.version().starts_with("2026-"), "{}", list.version());
        assert!(!list.has_hashes());
    }

    #[test]
    fn a_listed_name_is_flagged_by_the_kernels_name_or_the_executables_and_nothing_else_is() {
        let list = Blocklist::built_in();
        assert!(matches!(list.check(&process("xmrig", "xmrig", None)), Some(Finding::Name { kind: Kind::Miner, .. })));
        // Renamed on disk but not in the kernel's name, or the other way round: either is enough to flag.
        assert!(list.check(&process("python3", "XMRig", None)).is_some());
        assert!(list.check(&process("t-rex", "run.sh", None)).is_some());
        assert!(matches!(
            list.check(&process("hashcat", "hashcat", None)),
            Some(Finding::Name { kind: Kind::PasswordCracker, .. })
        ));
        // `comm` is cut at 15 characters, so a longer listed name still matches there.
        assert!(list.check(&process("srbminer-multi", "srbminer-multi", None)).is_some());
        for innocent in ["python3", "bash", "train.py", "xmrig-fan-club", "notxmrig", "mining-data"] {
            assert_eq!(list.check(&process(innocent, innocent, None)), None, "{innocent}");
        }
    }

    #[test]
    fn a_listed_build_is_found_by_its_hash_whatever_it_is_called() {
        let hash = "a".repeat(64);
        let json = format!(
            r#"{{"version":"t","names":[],"hashes":[{{"sha256":"{hash}","name":"xmrig 6.21","kind":"miner","source":"https://example.invalid/release"}}],"pools":[]}}"#
        );
        let list = Blocklist::parse(&json).unwrap();
        assert!(list.has_hashes());
        let found = list.check(&process("totally-innocent", "totally-innocent", Some(&hash)));
        assert_eq!(
            found,
            Some(Finding::Hash { name: "xmrig 6.21".to_owned(), sha256: hash.clone(), kind: Kind::Miner })
        );
        assert_eq!(list.check(&process("totally-innocent", "totally-innocent", Some(&"b".repeat(64)))), None);
        assert_eq!(
            list.check(&process("totally-innocent", "totally-innocent", None)),
            None,
            "no hash read, none matched"
        );
    }

    #[test]
    fn a_pool_matches_its_domain_and_everything_below_it_and_nothing_that_merely_ends_alike() {
        let list = Blocklist::built_in();
        assert_eq!(list.pool_matching("nanopool.org"), Some("nanopool.org"));
        assert_eq!(list.pool_matching("xmr-eu1.nanopool.org."), Some("nanopool.org"), "below it, with the root dot");
        assert_eq!(list.pool_matching("ETH.2Miners.com"), Some("2miners.com"));
        for innocent in ["mynanopool.org", "nanopool.org.example.com", "pypi.org", "huggingface.co", "org"] {
            assert_eq!(list.pool_matching(innocent), None, "{innocent}");
        }
    }

    #[test]
    fn a_bad_entry_is_refused_rather_than_matching_wrongly() {
        let with = |entry: &str| format!(r#"{{"version":"t","names":[{entry}],"hashes":[],"pools":[]}}"#);
        assert!(
            Blocklist::parse(&with(r#"{"name":"xmrig","kind":"miner","source":"https://example.invalid/x"}"#)).is_ok()
        );
        for bad in [
            r#"{"name":"","kind":"miner","source":"https://example.invalid/x"}"#,
            r#"{"name":"a-name-over-fifteen-chars","kind":"miner","source":"https://example.invalid/x"}"#,
            r#"{"name":"two words","kind":"miner","source":"https://example.invalid/x"}"#,
            r#"{"name":"xmrig","kind":"miner","source":"http://example.invalid/x"}"#,
            r#"{"name":"xmrig","kind":"miner"}"#,
            r#"{"name":"xmrig","kind":"gambling","source":"https://example.invalid/x"}"#,
        ] {
            assert!(Blocklist::parse(&with(bad)).is_err(), "{bad}");
        }
        let hash = |sha: &str| {
            format!(
                r#"{{"version":"t","names":[],"hashes":[{{"sha256":"{sha}","name":"x","kind":"miner","source":"https://example.invalid/x"}}],"pools":[]}}"#
            )
        };
        assert!(Blocklist::parse(&hash(&"a".repeat(63))).is_err() && Blocklist::parse(&hash(&"A".repeat(64))).is_err());
        let pool = |domain: &str| {
            format!(
                r#"{{"version":"t","names":[],"hashes":[],"pools":[{{"domain":"{domain}","source":"https://example.invalid/x"}}]}}"#
            )
        };
        assert!(Blocklist::parse(&pool("nanopool.org")).is_ok());
        assert!(Blocklist::parse(&pool("org")).is_err() && Blocklist::parse(&pool("a..b")).is_err());
        assert!(Blocklist::parse(&pool("bad domain.com")).is_err());
    }

    #[test]
    fn a_development_marker_adds_test_entries_and_a_release_distribution_has_none() {
        let marker = "registry=host.docker.internal:5000\nabuse-name=idleo-testminer\nabuse-pool=pool.abuse-test.invalid\nabuse-sha256=".to_owned()
            + &"c".repeat(64)
            + "\n";
        let list = Blocklist::built_in().with_test_entries(&marker);
        assert!(list.check(&process("idleo-testminer", "x", None)).is_some());
        assert_eq!(list.pool_matching("a.pool.abuse-test.invalid"), Some("pool.abuse-test.invalid"));
        assert!(matches!(list.check(&process("x", "x", Some(&"c".repeat(64)))), Some(Finding::Hash { .. })));
        let release = Blocklist::built_in().with_test_entries("");
        assert_eq!(release, Blocklist::built_in());
        assert!(release.check(&process("idleo-testminer", "x", None)).is_none());
    }
}
