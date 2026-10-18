//! Environment facts, for a development build's setup only (`idleo-agent self-test --facts`): what the first real
//! laptop is made of, so a failure can be read without a second visit. WSL's version and networking mode, the kernel, the
//! NVIDIA driver and the CUDA version `nvidia-smi` reports, the host's RAM and what the WSL VM is using, and what the
//! distribution can reach: the default gateway, one address in each common LAN range, and our storage.
//!
//! These are facts, never checks. The LAN probes are expected to find nothing today (a renter's job must not reach the
//! owner's network, and the distribution's firewall is meant to say so); whatever they find is reported as it is and
//! nothing here can fail a self-test or stop setup. A release build collects nothing: [`collect`] answers an empty list.

use std::sync::Arc;

use idleo_release::wsl::{Output, Runner, Wsl};
use serde::{Deserialize, Serialize};

use crate::self_test::Environment;

/// What the probes below wait for one connection.
pub const PROBE_SECONDS: u32 = 3;
/// One address in each common private range: home routers, offices and VPNs use these.
pub const LAN_PROBES: [&str; 4] = ["192.168.1.1", "192.168.0.1", "10.0.0.1", "172.16.0.1"];

/// One thing found out, as `name: value`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Fact {
    /// Its name, for example `kernel` or `reach_gateway`.
    pub name: String,
    /// What was found, in words; one line or a few.
    pub value: String,
}

/// Collects the facts. Empty unless this is a development build, on a Windows host with our distribution; `storage` is
/// the `host:port` of our storage, for the reachability probe.
#[must_use]
pub fn collect(dev_build: bool, environment: &Environment, storage: Option<&str>) -> Vec<Fact> {
    let Some(version) = environment.distro_version.as_deref().filter(|_| dev_build && environment.skip.is_none())
    else {
        return Vec::new();
    };
    let name = idleo_release::manifest::distro_name(version);
    let wsl = Wsl::new(Arc::clone(&environment.wsl));
    let mut facts = Vec::new();
    let mut add = |name: &str, value: String| facts.push(Fact { name: name.to_owned(), value: cut(&value, 600) });

    add("wsl_version", host(&environment.wsl, "wsl.exe", &["--version"], |text| Some(lines(text, "; "))));
    add(
        "host_ram",
        host(
            &environment.wsl,
            "powershell.exe",
            &[
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory",
            ],
            |text| text.trim().parse::<u64>().ok().map(|bytes| format!("{} MB", bytes / 1_048_576)),
        ),
    );
    add("kernel", inside(&wsl, &name, "uname", &["-r"], |text| Some(first(text))));
    let gateway = inside(&wsl, &name, "ip", &["route", "show", "default"], gateway_of);
    let mode = inside(&wsl, &name, "wslinfo", &["--networking-mode"], |text| Some(first(text)));
    add("networking_mode", if mode.starts_with("unavailable") { inferred_mode(&gateway) } else { mode });
    add(
        "nvidia_driver",
        inside(&wsl, &name, "nvidia-smi", &["--query-gpu=driver_version", "--format=csv,noheader"], |text| {
            Some(first(text))
        }),
    );
    add("cuda_version", inside(&wsl, &name, "nvidia-smi", &[], cuda_version));
    add("wsl_vm_memory", inside(&wsl, &name, "free", &["-m"], memory_in_use));

    let (host_part, port_part) = storage.and_then(|address| address.rsplit_once(':')).unzip();
    let probes = wsl.exec(&name, "bash", &["-c", PROBES, "probe", host_part.unwrap_or(""), port_part.unwrap_or("")]);
    for (probe, verdict) in parse_probes(probes.ok().map(|output| output.stdout).as_deref().unwrap_or("")) {
        add(&format!("reach_{probe}"), verdict);
    }
    facts
}

/// What the distribution runs to try a TCP connection to the gateway, each LAN address and storage, each with its own
/// short time limit, printing `name|exit code|milliseconds|first line of any error`. A shell's `/dev/tcp`: no extra
/// tool is needed in the sealed image.
const PROBES: &str = r#"
probe() {
  start=$(date +%s%3N)
  err=$(timeout 3 bash -c 'exec 3<>/dev/tcp/$0/$1' "$2" "$3" 2>&1); rc=$?
  end=$(date +%s%3N)
  printf '%s|%s|%s|%s\n' "$1" "$rc" "$((end - start))" "$(printf '%s' "$err" | head -n 1 | tr '|' ' ')"
}
gw=$(ip route show default 2>/dev/null | awk '{print $3; exit}')
if [ -n "$gw" ]; then probe gateway "$gw" 53; else echo 'gateway|none|0|no default route'; fi
probe lan_192_168_1_1 192.168.1.1 80
probe lan_192_168_0_1 192.168.0.1 80
probe lan_10_0_0_1 10.0.0.1 80
probe lan_172_16_0_1 172.16.0.1 80
if [ -n "$1" ] && [ -n "$2" ]; then probe storage "$1" "$2"; else echo 'storage|none|0|no storage address given'; fi
"#;

/// One probe's answer in words: what the connection did, which is what tells blocked from open.
fn verdict(code: &str, millis: &str, error: &str) -> String {
    let lower = error.to_lowercase();
    match code {
        "0" => format!("reachable: connected ({millis} ms)"),
        "124" => format!("silent: no answer in {PROBE_SECONDS} s (blocked, or nothing there)"),
        "none" => format!("not tried: {error}"),
        _ if lower.contains("refused") => format!("reachable: the host answered, nothing listens there ({millis} ms)"),
        _ if lower.contains("no route") || lower.contains("unreachable") => format!("unreachable: {}", cut(error, 100)),
        _ => format!("failed: {} (exit {code}, {millis} ms)", cut(error, 100)),
    }
}

/// The probes' output as (name, verdict).
fn parse_probes(output: &str) -> Vec<(String, String)> {
    output
        .lines()
        .filter_map(|line| {
            let mut parts = line.splitn(4, '|');
            let (name, code, millis, error) = (parts.next()?, parts.next()?, parts.next()?, parts.next().unwrap_or(""));
            Some((name.to_owned(), verdict(code, millis, error)))
        })
        .collect()
}

/// `default via 172.28.0.1 dev eth0` gives `172.28.0.1`.
fn gateway_of(text: &str) -> Option<String> {
    text.split_whitespace().skip_while(|word| *word != "via").nth(1).map(str::to_owned)
}

/// Without `wslinfo`: a gateway in 172.16.0.0/12 is WSL's NAT; one on the LAN means mirrored; else unknown.
fn inferred_mode(gateway: &str) -> String {
    let octets: Vec<u8> = gateway.split('.').filter_map(|part| part.parse().ok()).collect();
    match octets.as_slice() {
        [172, second, ..] if (16..=31).contains(second) => "nat (inferred from the gateway)".to_owned(),
        [_, _, _, _] => "mirrored or bridged (inferred from the gateway)".to_owned(),
        _ => "unknown".to_owned(),
    }
}

/// `CUDA Version: 12.8` in `nvidia-smi`'s header.
fn cuda_version(text: &str) -> Option<String> {
    let rest = text.split("CUDA Version:").nth(1)?;
    rest.split_whitespace().next().map(str::to_owned)
}

/// `Mem:  total used free …` from `free -m`, as "used X MB of Y MB (available Z MB)".
fn memory_in_use(text: &str) -> Option<String> {
    let fields: Vec<&str> = text.lines().find(|line| line.starts_with("Mem:"))?.split_whitespace().collect();
    let (total, used) = (fields.get(1)?, fields.get(2)?);
    let available = fields.get(6).map_or(String::new(), |available| format!(" (available {available} MB)"));
    Some(format!("used {used} MB of {total} MB{available}"))
}

/// A program run on Windows; its output through `read`, or why there is none.
fn host(runner: &Arc<dyn Runner>, program: &str, args: &[&str], read: impl FnOnce(&str) -> Option<String>) -> String {
    answer(runner.run(program, args).map_err(|e| e.to_string()), read)
}

/// A program run inside the distribution as root.
fn inside(
    wsl: &Wsl<Arc<dyn Runner>>,
    distro: &str,
    program: &str,
    args: &[&str],
    read: impl FnOnce(&str) -> Option<String>,
) -> String {
    answer(wsl.exec(distro, program, args).map_err(|e| e.to_string()), read)
}

fn answer(result: Result<Output, String>, read: impl FnOnce(&str) -> Option<String>) -> String {
    match result {
        Ok(output) if output.ok() => {
            read(&output.stdout).unwrap_or_else(|| format!("unavailable: {}", first(&output.stdout)))
        }
        Ok(output) => format!("unavailable: {}", first(&output.text())),
        Err(e) => format!("unavailable: {}", cut(&e, 200)),
    }
}

fn first(text: &str) -> String {
    cut(text.lines().map(str::trim).find(|line| !line.is_empty()).unwrap_or("no output"), 200)
}

fn lines(text: &str, separator: &str) -> String {
    text.lines().map(str::trim).filter(|line| !line.is_empty()).collect::<Vec<_>>().join(separator)
}

fn cut(text: &str, max: usize) -> String {
    text.chars().take(max).collect()
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;
    use crate::self_test::Report;
    use crate::telemetry::{FakeGpus, FakeModel};

    /// A machine that answers each command line containing the first of a pair with the second.
    struct Script(Vec<(&'static str, Output)>);

    impl Runner for Script {
        fn run(&self, program: &str, args: &[&str]) -> std::io::Result<Output> {
            let line = format!("{program} {}", args.join(" "));
            self.0.iter().find(|(needle, _)| line.contains(needle)).map(|(_, output)| output.clone()).ok_or_else(|| {
                std::io::Error::new(std::io::ErrorKind::NotFound, format!("nothing scripted for {line}"))
            })
        }
    }

    fn out(stdout: &str) -> Output {
        Output { code: 0, stdout: stdout.to_owned(), stderr: String::new() }
    }

    fn environment(script: Script, skip: Option<&str>) -> Environment {
        Environment {
            wsl: Arc::new(script),
            distro_version: Some("0.1.0".to_owned()),
            gpus: Arc::new(FakeGpus::new(1, &FakeModel::default())),
            disk_dir: PathBuf::from("/disk"),
            free_bytes: |_| Ok(0),
            skip: skip.map(str::to_owned),
        }
    }

    const SMI: &str = "+-----------------------------------------------------------------------------------------+\n\
        | NVIDIA-SMI 570.86.15              Driver Version: 572.16         CUDA Version: 12.8     |\n\
        +-----------------------------------------------------------------------------------------+\n";

    /// A laptop whose LAN is blocked, as it should be, and whose gateway and storage answer.
    fn laptop() -> Script {
        Script(vec![
            (
                "wsl.exe --version",
                out("WSL version: 2.3.26.0\nKernel version: 5.15.167.4-1\nWindows version: 10.0.26100.4349\n"),
            ),
            ("powershell.exe", out("34359738368\r\n")),
            ("--exec uname -r", out("5.15.167.4-microsoft-standard-WSL2\n")),
            ("--exec ip route show default", out("default via 172.28.0.1 dev eth0 proto kernel\n")),
            ("--exec wslinfo --networking-mode", out("nat\n")),
            ("--exec nvidia-smi --query-gpu=driver_version", out("572.16\n")),
            ("--exec nvidia-smi", out(SMI)),
            (
                "--exec free -m",
                out(
                    "               total        used        free      shared  buff/cache   available\nMem:           15921        1840       12000          10        2081       13700\nSwap:           4096           0        4096\n",
                ),
            ),
            (
                "--exec bash -c",
                out(
                    "gateway|0|3|\nlan_192_168_1_1|124|3001|\nlan_192_168_0_1|1|2|bash: connect: Connection refused\nlan_10_0_0_1|1|1|bash: connect: Network is unreachable\nlan_172_16_0_1|124|3002|\nstorage|0|12|\n",
                ),
            ),
        ])
    }

    fn value<'a>(facts: &'a [Fact], name: &str) -> &'a str {
        facts.iter().find(|fact| fact.name == name).map_or("(missing)", |fact| fact.value.as_str())
    }

    #[test]
    fn a_development_build_collects_every_fact_in_words() {
        let facts = collect(true, &environment(laptop(), None), Some("192.168.1.20:9100"));
        assert_eq!(
            value(&facts, "wsl_version"),
            "WSL version: 2.3.26.0; Kernel version: 5.15.167.4-1; Windows version: 10.0.26100.4349"
        );
        assert_eq!(value(&facts, "host_ram"), "32768 MB");
        assert_eq!(value(&facts, "kernel"), "5.15.167.4-microsoft-standard-WSL2");
        assert_eq!(value(&facts, "networking_mode"), "nat");
        assert_eq!(value(&facts, "nvidia_driver"), "572.16");
        assert_eq!(value(&facts, "cuda_version"), "12.8");
        assert_eq!(value(&facts, "wsl_vm_memory"), "used 1840 MB of 15921 MB (available 13700 MB)");
        assert_eq!(value(&facts, "reach_gateway"), "reachable: connected (3 ms)");
        assert_eq!(value(&facts, "reach_storage"), "reachable: connected (12 ms)");
    }

    #[test]
    fn the_lan_probes_report_what_they_found_and_nothing_fails() {
        let facts = collect(true, &environment(laptop(), None), Some("192.168.1.20:9100"));
        assert_eq!(value(&facts, "reach_lan_192_168_1_1"), "silent: no answer in 3 s (blocked, or nothing there)");
        assert_eq!(
            value(&facts, "reach_lan_192_168_0_1"),
            "reachable: the host answered, nothing listens there (2 ms)"
        );
        assert_eq!(value(&facts, "reach_lan_10_0_0_1"), "unreachable: bash: connect: Network is unreachable");
        assert_eq!(value(&facts, "reach_lan_172_16_0_1"), "silent: no answer in 3 s (blocked, or nothing there)");
    }

    #[test]
    fn facts_travel_in_the_json_only_when_there_are_some_and_an_old_report_still_reads() {
        let mut report = Report { checks: Vec::new(), passed: true, distro_version: "0.1.0".into(), facts: Vec::new() };
        assert!(!serde_json::to_string(&report).unwrap().contains("facts"));
        let old = r#"{"checks":[],"passed":false,"distro_version":"0.1.0"}"#;
        let read: Report = serde_json::from_str(old).unwrap();
        assert!(!read.passed && read.facts.is_empty());
        report.facts.push(Fact { name: "kernel".into(), value: "5.15".into() });
        let text = serde_json::to_string(&report).unwrap();
        assert_eq!(serde_json::from_str::<Report>(&text).unwrap(), report);
        assert!(report.passed, "a fact is never a check: the report's verdict is the checks'");
    }

    #[test]
    fn a_release_build_a_fake_gpu_or_a_missing_distribution_collects_nothing() {
        assert!(collect(false, &environment(laptop(), None), None).is_empty(), "release build");
        assert!(collect(true, &environment(laptop(), Some("invented GPUs")), None).is_empty(), "skipped");
        let mut none = environment(laptop(), None);
        none.distro_version = None;
        assert!(collect(true, &none, None).is_empty(), "no distribution");
    }

    #[test]
    fn what_cannot_be_found_is_a_fact_too() {
        let broken = Script(vec![
            ("--exec nvidia-smi", Output { code: 9, stdout: String::new(), stderr: "NVIDIA-SMI has failed\n".into() }),
            ("--exec bash -c", out("gateway|none|0|no default route\nstorage|none|0|no storage address given\n")),
        ]);
        let facts = collect(true, &environment(broken, None), None);
        assert_eq!(value(&facts, "nvidia_driver"), "unavailable: NVIDIA-SMI has failed");
        assert_eq!(value(&facts, "cuda_version"), "unavailable: NVIDIA-SMI has failed");
        assert!(value(&facts, "wsl_version").starts_with("unavailable: nothing scripted"));
        assert_eq!(value(&facts, "reach_gateway"), "not tried: no default route");
        assert_eq!(value(&facts, "reach_storage"), "not tried: no storage address given");
        assert!(facts.iter().all(|fact| fact.value.chars().count() <= 600));
    }

    #[test]
    fn the_networking_mode_is_inferred_from_the_gateway_without_wslinfo() {
        assert_eq!(inferred_mode("172.28.0.1"), "nat (inferred from the gateway)");
        assert_eq!(inferred_mode("192.168.1.1"), "mirrored or bridged (inferred from the gateway)");
        assert_eq!(inferred_mode("unavailable: x"), "unknown");
        let no_wslinfo = Script(vec![
            ("--exec ip route show default", out("default via 172.28.0.1 dev eth0\n")),
            ("--exec bash -c", out("")),
        ]);
        let facts = collect(true, &environment(no_wslinfo, None), None);
        assert_eq!(value(&facts, "networking_mode"), "nat (inferred from the gateway)");
    }

    #[test]
    fn parsers_read_what_nvidia_smi_and_free_print() {
        assert_eq!(cuda_version(SMI).as_deref(), Some("12.8"));
        assert_eq!(cuda_version("no header"), None);
        assert_eq!(gateway_of("default via 10.255.255.254 dev eth0\n").as_deref(), Some("10.255.255.254"));
        assert_eq!(memory_in_use("Mem: 100 40 60"), Some("used 40 MB of 100 MB".to_owned()));
    }
}
