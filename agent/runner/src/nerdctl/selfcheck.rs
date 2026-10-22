//! The self-test's checks of this distribution (ADR 0035), run by `Request::SelfCheck`: the sandbox's rules are loaded, a
//! scratch disk can be encrypted (ADR 0038) and made and removed, a throwaway container on a session network can't reach the home network (and can
//! reach the internet, when there is one), and, once the job image is on the laptop, a real CUDA calculation runs in it.

use std::collections::BTreeMap;
use std::net::Ipv4Addr;
use std::time::Duration;

use jobcore::protocol::{Check, CheckStatus, Spec};

use super::{Nerdctl, SELF_CHECK_IMAGE, nerdctl};
use crate::container::Plan;
use crate::machine::{Machine, Stream};
use crate::sandbox;

/// A probe finishes within this, or it's a failure.
const PROBE_LIMIT: Duration = Duration::from_secs(45);

/// A CUDA matrix multiply on the GPU, checked against the CPU's answer.
const MATMUL: &str = "import torch\ntorch.backends.cuda.matmul.allow_tf32 = False\n\
a = torch.randn(512, 512, device='cuda')\nb = torch.randn(512, 512, device='cuda')\n\
assert torch.allclose((a @ b).cpu(), a.cpu() @ b.cpu(), rtol=1e-3, atol=1e-2), 'the GPU and the CPU disagree'\n\
print('matmul ok on', torch.cuda.get_device_name(0))";

fn check(name: &str, status: CheckStatus, detail: impl Into<String>) -> Check {
    Check { name: name.to_owned(), status, detail: detail.into() }
}

struct Probe {
    exit_code: i32,
    stdout: String,
    network: String,
}

impl Nerdctl {
    pub(super) async fn checks(&self, cuda_image: Option<&str>, quick: bool) -> Vec<Check> {
        let mut checks = vec![
            match self.sandbox_ready().await {
                Ok(()) => {
                    check("sandbox_rules", CheckStatus::Passed, format!("loaded, version {}", &sandbox::hash()[..12]))
                }
                Err(why) => check("sandbox_rules", CheckStatus::Failed, why),
            },
            self.encryption_check().await,
            self.scratch_check().await,
            self.leftovers_check().await,
        ];
        if !quick {
            checks.push(self.network_check().await);
            checks.push(self.cuda_check(cuda_image).await);
        }
        checks
    }

    /// Nothing is left from finished sessions: no container, process, mount, loop device, scratch file or session network that no
    /// session running now holds. In the quick check too: it reads the distribution and starts nothing.
    async fn leftovers_check(&self) -> Check {
        let left = self.what_is_left().await;
        if left.is_empty() {
            check("leftovers", CheckStatus::Passed, "nothing is left from finished sessions")
        } else {
            check("leftovers", CheckStatus::Failed, format!("left from finished sessions: {}", left.join(", ")))
        }
    }

    /// `scratch_encryption`: a small encrypted volume is made with a key of its own, written, read, closed and erased, and its
    /// image never held what was written in the clear. A release distribution runs no job without it; a development one warns
    /// (skipped, with why) and makes plain disks.
    async fn encryption_check(&self) -> Check {
        const NAME: &str = "scratch_encryption";
        match self.encryption_round_trip().await {
            Ok(detail) => check(NAME, CheckStatus::Passed, detail),
            Err(why) if Self::development() => check(
                NAME,
                CheckStatus::Skipped,
                format!("a development distribution makes plain scratch disks here: {why}"),
            ),
            Err(why) => {
                check(NAME, CheckStatus::Failed, format!("scratch disks can't be encrypted, so no job will run: {why}"))
            }
        }
    }

    async fn scratch_check(&self) -> Check {
        let scratch = match self.make_scratch("selfcheck-scratch", 64).await {
            Ok(scratch) => scratch,
            Err(why) => return check("scratch_disk", CheckStatus::Failed, why),
        };
        let wrote = std::fs::write(scratch.work.join("probe"), vec![0_u8; 1024 * 1024]);
        let free = self.scratch_free(&scratch);
        self.remove_scratch(&scratch).await;
        let gone = !scratch.root.exists();
        match (wrote, gone) {
            (Err(why), _) => check("scratch_disk", CheckStatus::Failed, format!("writing to it failed: {why}")),
            (Ok(()), false) => check("scratch_disk", CheckStatus::Failed, "it couldn't be removed"),
            (Ok(()), true) => check(
                "scratch_disk",
                CheckStatus::Passed,
                format!("made, written ({} MiB free) and removed", free >> 20),
            ),
        }
    }

    /// Runs `args` in `image` in a container with the sandbox's flags, and returns what it printed.
    async fn probe(&self, image: &str, entrypoint: &str, args: Vec<String>, gpu: bool) -> Result<Probe, String> {
        let key = "selfcheck-probe";
        let scratch = self.make_scratch(key, 64).await?;
        let plan = Plan {
            name: "idleo-selfcheck".to_owned(),
            session: "selfcheck".to_owned(),
            attempt: 0,
            network: String::new(),
            spec: Spec {
                image: image.to_owned(),
                args,
                env: BTreeMap::new(),
                memory_mb: 2048,
                cpu_millicores: 1000,
                pids_limit: 256,
                scratch_mb: 64,
                max_runtime_secs: 60,
                checkpoint_interval_secs: 0,
                gpu,
            },
            work: scratch.work.clone(),
            input: scratch.input.clone(),
            resume: None,
            logs: scratch.logs(),
            upload_cap_kbps: 0,
            slots: Vec::new(),
            development: Self::development(),
            entrypoint: Some(entrypoint.to_owned()),
        };
        let result = async {
            let container = self.start_container(plan).await.map_err(|why| why.to_string())?;
            let mut output = container.take_output();
            let mut text = String::new();
            let collected = tokio::time::timeout(PROBE_LIMIT, async {
                let exit = container.wait();
                tokio::pin!(exit);
                let mut exit_code = None;
                loop {
                    tokio::select! {
                        exited = &mut exit, if exit_code.is_none() => exit_code = Some(exited.code),
                        chunk = async { match output.as_mut() { Some(rx) => rx.recv().await, None => std::future::pending().await } } => match chunk {
                            Some((Stream::Stdout, bytes)) => text.push_str(&String::from_utf8_lossy(&bytes)),
                            Some((Stream::Stderr, _)) => {}
                            None => {
                                output = None;
                            }
                        },
                    }
                    if exit_code.is_some() && output.is_none() {
                        return exit_code.unwrap_or(-1);
                    }
                }
            })
            .await;
            let network = nerdctl(&["inspect", "--format", "{{range .NetworkSettings.Networks}}{{.NetworkID}}{{end}}", "idleo-selfcheck"])
                .await
                .map(|out| out.stdout.trim().to_owned())
                .unwrap_or_default();
            container.remove().await;
            match collected {
                Ok(exit_code) => Ok(Probe { exit_code, stdout: text, network }),
                Err(_) => Err("the probe didn't finish in time".to_owned()),
            }
        }
        .await;
        self.remove_scratch(&scratch).await;
        result
    }

    async fn network_check(&self) -> Check {
        const NAME: &str = "network_lockdown";
        let mut targets: Vec<String> = [
            "10.0.0.1:80",
            "10.255.255.254:80",
            "172.16.0.1:80",
            "172.31.255.254:80",
            "192.168.0.1:80",
            "192.168.1.1:80",
            "169.254.169.254:80",
            "100.64.0.1:80",
        ]
        .map(str::to_owned)
        .to_vec();
        let route = std::fs::read_to_string("/proc/net/route").unwrap_or_default();
        if let Some(gateway) = sandbox::default_gateway(&route) {
            targets.push(format!("{gateway}:80"));
            targets.push(format!("{gateway}:443"));
        }
        let own = sandbox::own_addresses(&std::fs::read_to_string("/proc/net/fib_trie").unwrap_or_default());
        targets.extend(own.iter().filter(|address| !address.is_loopback()).map(|address| format!("{address}:80")));
        // The session network's own gateway, the distribution's address on the bridge: nothing on this machine is reachable.
        targets.push("172.30.0.1:80".to_owned());
        let public = "1.1.1.1:443";
        let script = format!(
            "for t in {} {public}; do ( timeout 3 bash -c \"exec 3<>/dev/tcp/${{t%:*}}/${{t#*:}}\" 2>/dev/null; echo \"$t $?\" ) & done; wait",
            targets.join(" ")
        );
        let probe = match self.probe(SELF_CHECK_IMAGE, "bash", vec!["-c".to_owned(), script], false).await {
            Ok(probe) => probe,
            Err(why) => return check(NAME, CheckStatus::Failed, format!("the throwaway container didn't run: {why}")),
        };
        let _ = (&probe.exit_code, &probe.network);
        let mut reachable = Vec::new();
        let mut public_reached = false;
        let mut answered = 0;
        for line in probe.stdout.lines() {
            let Some((target, status)) = line.split_once(' ') else { continue };
            answered += 1;
            let blocked = status.trim() == "124";
            if target == public {
                public_reached = status.trim() == "0";
            } else if !blocked {
                reachable.push(target.to_owned());
            }
        }
        if answered < targets.len() + 1 {
            return check(
                NAME,
                CheckStatus::Failed,
                format!("the probe answered for {answered} of {} addresses", targets.len() + 1),
            );
        }
        if !reachable.is_empty() {
            return check(
                NAME,
                CheckStatus::Failed,
                format!("a job can reach the home network: {}", reachable.join(", ")),
            );
        }
        if public_reached {
            return check(NAME, CheckStatus::Passed, "the home network is unreachable and the internet is reachable");
        }
        // Not reached from the job. If this machine can't reach it either, it's offline, which isn't a failure.
        let online = tokio::time::timeout(
            Duration::from_secs(4),
            tokio::net::TcpStream::connect((Ipv4Addr::new(1, 1, 1, 1), 443)),
        )
        .await
        .is_ok_and(|connected| connected.is_ok());
        if online {
            check(
                NAME,
                CheckStatus::Failed,
                "the home network is unreachable, but so is the internet, which this machine can reach",
            )
        } else {
            check(
                NAME,
                CheckStatus::Skipped,
                "the home network is unreachable; the internet wasn't tried because this machine is offline",
            )
        }
    }

    async fn cuda_check(&self, image: Option<&str>) -> Check {
        const NAME: &str = "cuda_matmul";
        let Some(image) = image else {
            return check(NAME, CheckStatus::Skipped, "the job image isn't downloaded yet");
        };
        if !Self::gpu_present() {
            return check(NAME, CheckStatus::Skipped, "no GPU is visible in this distribution");
        }
        if !Self::image_present(image).await {
            return check(NAME, CheckStatus::Skipped, "the job image isn't downloaded yet");
        }
        match self.probe(image, "python", vec!["-c".to_owned(), MATMUL.to_owned()], true).await {
            Ok(probe) if probe.exit_code == 0 && probe.stdout.contains("matmul ok") => {
                check(NAME, CheckStatus::Passed, probe.stdout.trim().to_owned())
            }
            Ok(probe) => check(
                NAME,
                CheckStatus::Failed,
                format!("the calculation failed (exit code {}): {}", probe.exit_code, probe.stdout.trim()),
            ),
            Err(why) => check(NAME, CheckStatus::Failed, why),
        }
    }
}
