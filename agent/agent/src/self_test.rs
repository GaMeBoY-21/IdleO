//! The self-test (ADR 0033): one implementation, run by the setup app (`idleo-agent self-test --json`), by an
//! update's trial (`--quick`), and by the running agent after enrolment, after each update and when its tray asks, whose
//! report goes to the control plane as `SelfTestReport`.
//!
//! Named checks, each with whether it passed, what it found and how long it took:
//!
//! 1. `distro_registered`: our distribution is registered, as WSL 2.
//! 2. `lockdown_config`: its `/etc/wsl.conf` is `agent/distro/wsl.conf`, byte for byte.
//! 3. `gpu_visible`: `nvidia-smi` inside it lists the GPU NVML sees on Windows.
//! 4. `container_runtime`: containerd answers.
//! 5. `runner`: the job runner (ADR 0035) answers, the sandbox's network rules are loaded, a scratch disk can be made and
//!    removed, and nothing is left behind by sessions that have finished (`leftovers`: no container, process, mount, loop
//!    device, scratch file or session network that no running session holds).
//! 6. `gpu_in_container`: the GPU-check image prints the GPU's name and memory from inside a container, and, once the
//!    job image is on the laptop, a real CUDA calculation (a matrix multiply through `PyTorch`, checked against the CPU's
//!    answer) runs in it; before it's downloaded that part is "skipped: image not downloaded yet", not a failure.
//! 7. `lockdown`: `verify-lockdown.sh` passes inside the distribution.
//! 8. `network_lockdown`: in a throwaway container on a session network, the gateway, an address in each private range
//!    and a link-local address are unreachable, and a public HTTPS address is reachable (when the laptop is online). This
//!    is the real check that a renter's job can't reach the owner's home network.
//! 9. `disk`: free space where the distribution's disk lives.
//! 10. `storage_speed`: 32 MB up to our storage and back, only in the running agent once linked.
//!
//! Beside the checks, a development build's setup can ask for environment facts (`--facts`, [`crate::facts`]); they never
//! change whether the test passed.
//!
//! `--quick` skips the slow ones (`gpu_in_container`, `lockdown`, `storage_speed`). On Linux and macOS, and with fake
//! GPUs, every check is skipped and the test passes, so the local stack is unchanged.

use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;
use std::time::{Duration, Instant};

use idleo_release::wsl::{Runner, Wsl};
use serde::{Deserialize, Serialize};

use crate::telemetry::GpuSource;

/// The lockdown the distribution must carry: the repository's own file.
pub const WSL_CONF: &str = include_str!("../../distro/wsl.conf");
/// Where the GPU-check image's tarball is baked into the distribution (`agent/distro/`).
pub const GPU_CHECK_IMAGE: &str = "docker.io/idleo/gpu-check:local";
/// The least free disk, in GB, for a host to pass: room for the default 20 GB the owner keeps and a job's files.
pub const MIN_FREE_GB: u64 = 30;
/// The size of the speed check's object.
pub const SPEED_TEST_BYTES: usize = 32 * 1024 * 1024;

/// One check.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Check {
    /// Its name, for example `gpu_visible`.
    pub name: String,
    /// Whether it passed.
    pub passed: bool,
    /// What it found, in one line: never a path or a user name.
    pub detail: String,
    /// How long it took, in milliseconds.
    pub took_ms: u64,
}

/// A self-test's results, also `self-test --json`'s output.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Report {
    /// Every check that ran, in order.
    pub checks: Vec<Check>,
    /// True if every check passed.
    pub passed: bool,
    /// The distribution's version; empty without one.
    pub distro_version: String,
    /// Environment facts, only when asked for in a development build (`self-test --facts`, [`crate::facts`]). They
    /// are never checks: nothing in them can fail the test, and they aren't sent to the control plane.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub facts: Vec<crate::facts::Fact>,
}

impl Report {
    /// As the protocol carries it.
    #[must_use]
    pub fn to_message(&self) -> agent_proto::v1::SelfTestReport {
        agent_proto::v1::SelfTestReport {
            checks: self
                .checks
                .iter()
                .map(|check| agent_proto::v1::SelfTestCheck {
                    name: check.name.clone(),
                    passed: check.passed,
                    detail: check.detail.clone(),
                    took: prost_types::Duration::try_from(Duration::from_millis(check.took_ms)).ok(),
                })
                .collect(),
            passed: self.passed,
            distro_version: self.distro_version.clone(),
        }
    }
}

/// The storage speed check: the running agent asks the control plane for a grant over its stream, writes and reads
/// one object, and says how fast. Returns the detail line, or why it failed.
pub trait SpeedCheck: Send + Sync {
    /// Runs the check.
    fn measure(&self) -> Pin<Box<dyn Future<Output = Result<String, String>> + Send + '_>>;
}

/// What the self-test asks the job runner (ADR 0035): its own answer, and its checks.
pub trait RunnerChecks: Send + Sync {
    /// The runner's `hello`.
    fn hello(&self) -> Pin<Box<dyn Future<Output = Result<jobcore::protocol::HelloAnswer, String>> + Send + '_>>;
    /// The runner's checks (`quick` leaves out the container and the CUDA calculation), with the job image to run the CUDA
    /// calculation in, if it has been downloaded.
    fn checks(
        &self,
        cuda_image: Option<String>,
        quick: bool,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<jobcore::protocol::Check>, String>> + Send + '_>>;
}

impl RunnerChecks for crate::runner::container::ContainerRunner {
    fn hello(&self) -> Pin<Box<dyn Future<Output = Result<jobcore::protocol::HelloAnswer, String>> + Send + '_>> {
        Box::pin(async move { Self::hello(self).await.map_err(|failure| failure.message) })
    }

    fn checks(
        &self,
        cuda_image: Option<String>,
        quick: bool,
    ) -> Pin<Box<dyn Future<Output = Result<Vec<jobcore::protocol::Check>, String>> + Send + '_>> {
        Box::pin(async move { self.self_check(cuda_image, quick).await.map_err(|failure| failure.message) })
    }
}

/// Names the job image to run the CUDA calculation in, if there is one.
pub type CudaImage = Arc<dyn Fn() -> Option<String> + Send + Sync>;

/// What the self-test looks at.
pub struct Environment {
    /// What runs `wsl.exe`.
    pub wsl: Arc<dyn Runner>,
    /// Our distribution's version, if one is installed; `None` on Linux and macOS.
    pub distro_version: Option<String>,
    /// The GPUs, through NVML.
    pub gpus: Arc<dyn GpuSource>,
    /// Where the distribution's disk lives, for the free-space check.
    pub disk_dir: PathBuf,
    /// Free bytes on a folder's volume.
    pub free_bytes: fn(&std::path::Path) -> std::io::Result<u64>,
    /// Skip everything: Linux, macOS and fake GPUs.
    pub skip: Option<String>,
    /// The job runner, for the `runner` and `network_lockdown` checks and the CUDA calculation.
    pub runner: Option<Arc<dyn RunnerChecks>>,
    /// Names the job image to run the CUDA calculation in, asked each time (the agent learns its images from the control
    /// plane's Welcome); `None`, or an answer of none, until the image has been downloaded.
    pub cuda_image: Option<CudaImage>,
}

#[allow(clippy::too_many_lines)] // The checks in the order they run, each with its own words: clearer whole than split.
/// Runs the self-test. `quick` leaves out the slow checks; `speed` is the storage speed check, only in the running,
/// linked agent.
pub async fn run(environment: &Environment, quick: bool, speed: Option<&dyn SpeedCheck>) -> Report {
    if let Some(why) = &environment.skip {
        let checks = names(quick, speed.is_some())
            .into_iter()
            .map(|name| Check { name: name.to_owned(), passed: true, detail: format!("skipped: {why}"), took_ms: 0 })
            .collect();
        return Report { checks, passed: true, distro_version: String::new(), facts: Vec::new() };
    }
    let mut checks = Vec::new();
    let Some(version) = environment.distro_version.clone() else {
        checks.push(Check {
            name: "distro_registered".to_owned(),
            passed: false,
            detail: "no IdleO distribution is installed".to_owned(),
            took_ms: 0,
        });
        return finish(checks, String::new());
    };
    let name = idleo_release::manifest::distro_name(&version);
    let wsl = Wsl::new(Arc::clone(&environment.wsl));

    checks.push(timed("distro_registered", || match wsl.wsl_version_of(&name) {
        Ok(Some(2)) => (true, format!("{name}, WSL 2")),
        Ok(Some(v)) => (false, format!("{name} is WSL {v}, not 2")),
        Ok(None) => (false, format!("{name} isn't registered")),
        Err(e) => (false, e.to_string()),
    }));
    if !checks[0].passed {
        return finish(checks, version);
    }
    checks.push(timed("lockdown_config", || match wsl.exec(&name, "cat", &["/etc/wsl.conf"]) {
        Ok(output) if output.ok() && output.stdout == WSL_CONF => (true, "matches agent/distro/wsl.conf".to_owned()),
        Ok(output) if output.ok() => (false, "differs from agent/distro/wsl.conf".to_owned()),
        Ok(output) => (false, first_line(&output.text())),
        Err(e) => (false, e.to_string()),
    }));
    let gpu = environment.gpus.gpus().ok().and_then(|gpus| gpus.into_iter().next()).map(|gpu| gpu.name);
    checks.push(timed("gpu_visible", || {
        let Some(gpu) = &gpu else { return (false, "NVML found no GPU on Windows".to_owned()) };
        match wsl.exec(&name, "nvidia-smi", &["--query-gpu=name", "--format=csv,noheader"]) {
            Ok(output) if output.ok() && output.stdout.lines().any(|line| line.trim() == gpu.trim()) => {
                (true, format!("{} inside the distribution", gpu.trim()))
            }
            Ok(output) if output.ok() => (false, format!("nvidia-smi lists {:?}, not {gpu}", output.stdout.trim())),
            Ok(output) => (false, first_line(&output.text())),
            Err(e) => (false, e.to_string()),
        }
    }));
    checks.push(timed("container_runtime", || match wsl.exec(&name, "ctr", &["version"]) {
        Ok(output) if output.ok() => (true, "containerd answers".to_owned()),
        Ok(output) => (false, first_line(&output.text())),
        Err(e) => (false, e.to_string()),
    }));
    // The job runner answers for itself and runs its own checks (ADR 0035), once; what they found is read below.
    let runner_started = Instant::now();
    let (hello, results) = match &environment.runner {
        Some(runner) => {
            let hello = runner.hello().await;
            let results = if hello.is_ok() {
                runner.checks(environment.cuda_image.as_ref().and_then(|name| name()), quick).await
            } else {
                Err(String::new())
            };
            (Some(hello), Some(results))
        }
        None => (None, None),
    };
    let found = |name: &str| -> Option<&jobcore::protocol::Check> {
        results
            .as_ref()
            .and_then(|results| results.as_ref().ok())
            .and_then(|list| list.iter().find(|check| check.name == name))
    };
    let (runner_passed, runner_detail) = match (&hello, &results) {
        (None, _) => (false, "no job runner is set up on this machine".to_owned()),
        (Some(Err(why)), _) => (false, format!("the job runner doesn't answer: {why}")),
        (Some(Ok(answer)), _) if !answer.rules_loaded => {
            (false, "the job runner answers, but the sandbox's network rules aren't loaded".to_owned())
        }
        (Some(Ok(answer)), Some(Err(why))) => {
            (false, format!("the job runner {} couldn't run its checks: {why}", answer.runner_version))
        }
        (Some(Ok(answer)), _) => {
            let failed: Vec<String> = ["sandbox_rules", "scratch_disk", "leftovers"]
                .iter()
                .filter_map(|name| found(name).filter(|check| check.status != jobcore::protocol::CheckStatus::Passed))
                .map(|check| format!("{}: {}", check.name, check.detail))
                .collect();
            if failed.is_empty() {
                (
                    true,
                    format!(
                        "answers (version {}), the sandbox's rules are loaded, a scratch disk can be made and removed, nothing is left from finished sessions",
                        answer.runner_version
                    ),
                )
            } else {
                (false, failed.join("; "))
            }
        }
    };
    checks.push(Check {
        name: "runner".to_owned(),
        passed: runner_passed,
        detail: cut(&runner_detail),
        took_ms: millis(runner_started),
    });
    if !quick {
        checks.push(timed("gpu_in_container", || {
            let args = ["run", "--rm", "--gpus", "0", GPU_CHECK_IMAGE, "idleo-gpu-check"];
            let smi = match wsl.exec(&name, "ctr", &args) {
                Ok(output) if output.ok() && gpu.as_ref().is_some_and(|gpu| output.stdout.contains(gpu.trim())) => {
                    Ok(first_line(&output.stdout))
                }
                Ok(output) if output.ok() => Err(format!("the container saw {:?}", first_line(&output.stdout))),
                Ok(output) => Err(first_line(&output.text())),
                Err(e) => Err(e.to_string()),
            };
            // Then the real calculation, in the job image, once it's here: skipped (not failed) before.
            match (smi, found("cuda_matmul")) {
                (Err(why), _) => (false, why),
                (Ok(smi), Some(cuda)) if cuda.status == jobcore::protocol::CheckStatus::Failed => {
                    (false, format!("{smi}; the CUDA calculation failed: {}", cuda.detail))
                }
                (Ok(smi), Some(cuda)) if cuda.status == jobcore::protocol::CheckStatus::Skipped => {
                    (true, format!("{smi}; CUDA calculation skipped: {}", cuda.detail))
                }
                (Ok(smi), Some(cuda)) => (true, format!("{smi}; {}", cuda.detail)),
                (Ok(smi), None) => (true, format!("{smi}; CUDA calculation skipped: no job runner answered")),
            }
        }));
        checks.push(timed("lockdown", || match wsl.exec(&name, "/usr/local/share/idleo/verify-lockdown.sh", &[]) {
            Ok(output) if output.ok() => (true, "verify-lockdown.sh passed".to_owned()),
            Ok(output) => (false, last_line(&output.text())),
            Err(e) => (false, e.to_string()),
        }));
        checks.push(timed("network_lockdown", || match found("network_lockdown") {
            Some(check) if check.status == jobcore::protocol::CheckStatus::Failed => (false, check.detail.clone()),
            Some(check) if check.status == jobcore::protocol::CheckStatus::Skipped => {
                (true, format!("skipped: {}", check.detail))
            }
            Some(check) => (true, check.detail.clone()),
            None => (false, "the job runner didn't run it".to_owned()),
        }));
    }
    checks.push(timed("disk", || match (environment.free_bytes)(&environment.disk_dir) {
        Ok(bytes) => {
            let gb = bytes / 1_000_000_000;
            (gb >= MIN_FREE_GB, format!("{gb} GB free (at least {MIN_FREE_GB} GB needed)"))
        }
        Err(e) => (false, format!("free space couldn't be read: {e}")),
    }));
    if !quick && let Some(speed) = speed {
        let started = Instant::now();
        let (passed, detail) = match speed.measure().await {
            Ok(detail) => (true, detail),
            Err(e) => (false, e),
        };
        checks.push(Check { name: "storage_speed".to_owned(), passed, detail, took_ms: millis(started) });
    }
    finish(checks, version)
}

fn names(quick: bool, speed: bool) -> Vec<&'static str> {
    let mut names = vec!["distro_registered", "lockdown_config", "gpu_visible", "container_runtime", "runner"];
    if !quick {
        names.extend(["gpu_in_container", "lockdown", "network_lockdown"]);
    }
    names.push("disk");
    if !quick && speed {
        names.push("storage_speed");
    }
    names
}

fn finish(checks: Vec<Check>, distro_version: String) -> Report {
    let passed = !checks.is_empty() && checks.iter().all(|check| check.passed);
    Report { checks, passed, distro_version, facts: Vec::new() }
}

fn timed(name: &str, check: impl FnOnce() -> (bool, String)) -> Check {
    let started = Instant::now();
    let (passed, detail) = check();
    Check { name: name.to_owned(), passed, detail: cut(&detail), took_ms: millis(started) }
}

fn millis(started: Instant) -> u64 {
    u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX)
}

fn first_line(text: &str) -> String {
    cut(text.lines().map(str::trim).find(|line| !line.is_empty()).unwrap_or("no output"))
}

fn last_line(text: &str) -> String {
    cut(text.lines().map(str::trim).rfind(|line| !line.is_empty()).unwrap_or("no output"))
}

/// At most 200 characters: details are one line for the admin console.
fn cut(text: &str) -> String {
    text.chars().take(200).collect()
}

/// Megabits a second for `bytes` in `took`.
#[must_use]
pub fn mbit_per_second(bytes: usize, took: Duration) -> u64 {
    let seconds = took.as_secs_f64().max(0.001);
    // Under 2^53 for any real transfer, so the cast is exact enough for a speed shown in whole Mbit/s.
    #[allow(clippy::cast_precision_loss, clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let speed = ((bytes as f64) * 8.0 / 1_000_000.0 / seconds).round() as u64;
    speed
}

#[cfg(test)]
mod tests {
    use idleo_release::wsl::fake::FakeWsl;

    use super::*;
    use crate::telemetry::{FakeGpus, FakeModel};

    const GPU: &str = "NVIDIA GeForce RTX 4080 Laptop GPU";

    /// A job runner that answers as told.
    struct FakeRunner {
        hello: Result<jobcore::protocol::HelloAnswer, String>,
        checks: Vec<jobcore::protocol::Check>,
    }

    fn check(name: &str, status: CheckStatus, detail: &str) -> jobcore::protocol::Check {
        jobcore::protocol::Check { name: name.to_owned(), status, detail: detail.to_owned() }
    }

    impl FakeRunner {
        fn healthy() -> Self {
            Self {
                hello: Ok(jobcore::protocol::HelloAnswer {
                    runner_version: "0.1.0".to_owned(),
                    distro_version: "0.1.0".to_owned(),
                    gpu: true,
                    rules_loaded: true,
                    scratch_free_bytes: 50_000_000_000,
                    development: false,
                }),
                checks: vec![
                    check("sandbox_rules", CheckStatus::Passed, "loaded"),
                    check("scratch_disk", CheckStatus::Passed, "made, written and removed"),
                    check("leftovers", CheckStatus::Passed, "nothing is left from finished sessions"),
                    check(
                        "network_lockdown",
                        CheckStatus::Passed,
                        "the home network is unreachable and the internet is reachable",
                    ),
                    check("cuda_matmul", CheckStatus::Skipped, "the job image isn't downloaded yet"),
                ],
            }
        }

        fn with(mut self, check: jobcore::protocol::Check) -> Self {
            self.checks.retain(|existing| existing.name != check.name);
            self.checks.push(check);
            self
        }
    }

    impl RunnerChecks for FakeRunner {
        fn hello(&self) -> Pin<Box<dyn Future<Output = Result<jobcore::protocol::HelloAnswer, String>> + Send + '_>> {
            Box::pin(async move { self.hello.clone() })
        }

        fn checks(
            &self,
            _cuda_image: Option<String>,
            quick: bool,
        ) -> Pin<Box<dyn Future<Output = Result<Vec<jobcore::protocol::Check>, String>> + Send + '_>> {
            Box::pin(async move {
                Ok(self
                    .checks
                    .iter()
                    .filter(|c| !quick || matches!(c.name.as_str(), "sandbox_rules" | "scratch_disk" | "leftovers"))
                    .cloned()
                    .collect())
            })
        }
    }

    use jobcore::protocol::CheckStatus;

    fn environment(fake: &FakeWsl, free_gb: u64) -> Environment {
        with_runner(fake, free_gb, FakeRunner::healthy())
    }

    fn with_runner(fake: &FakeWsl, free_gb: u64, runner: FakeRunner) -> Environment {
        let free: fn(&std::path::Path) -> std::io::Result<u64> =
            if free_gb >= 100 { |_| Ok(120_000_000_000) } else { |_| Ok(12_000_000_000) };
        Environment {
            wsl: Arc::new(fake.clone()),
            distro_version: Some("0.1.0".to_owned()),
            gpus: Arc::new(FakeGpus::new(1, &FakeModel { name: GPU.to_owned(), vram_mb: 12_282 })),
            disk_dir: PathBuf::from("/disk"),
            free_bytes: free,
            skip: None,
            runner: Some(Arc::new(runner)),
            cuda_image: None,
        }
    }

    fn healthy() -> FakeWsl {
        let fake = FakeWsl::ready();
        fake.state().distros.insert("IdleO-0.1.0".to_owned(), "/disk".to_owned());
        let mut state = fake.state();
        state.exec.insert("cat".to_owned(), (0, WSL_CONF.to_owned()));
        state.exec.insert("nvidia-smi".to_owned(), (0, format!("{GPU}\n")));
        state.exec.insert("ctr".to_owned(), (0, format!("{GPU}, 12282 MiB\n")));
        state.exec.insert("/usr/local/share/idleo/verify-lockdown.sh".to_owned(), (0, "PASS all\n".to_owned()));
        drop(state);
        fake
    }

    struct Speed;

    impl SpeedCheck for Speed {
        fn measure(&self) -> Pin<Box<dyn Future<Output = Result<String, String>> + Send + '_>> {
            Box::pin(async { Ok("up 48 Mbit/s, down 96 Mbit/s".to_owned()) })
        }
    }

    #[tokio::test]
    async fn a_healthy_laptop_passes_every_check() {
        let fake = healthy();
        let report = run(&environment(&fake, 120), false, Some(&Speed)).await;
        let names: Vec<_> = report.checks.iter().map(|check| check.name.as_str()).collect();
        assert_eq!(
            names,
            [
                "distro_registered",
                "lockdown_config",
                "gpu_visible",
                "container_runtime",
                "runner",
                "gpu_in_container",
                "lockdown",
                "network_lockdown",
                "disk",
                "storage_speed"
            ]
        );
        assert!(report.passed, "{report:?}");
        assert_eq!(report.distro_version, "0.1.0");
        assert_eq!(report.to_message().checks.len(), 10);
        let in_container = report.checks.iter().find(|c| c.name == "gpu_in_container").unwrap();
        assert!(
            in_container.detail.contains("CUDA calculation skipped: the job image isn't downloaded yet"),
            "{in_container:?}"
        );
    }

    #[tokio::test]
    async fn quick_leaves_out_the_slow_checks() {
        let fake = healthy();
        let report = run(&environment(&fake, 120), true, Some(&Speed)).await;
        let names: Vec<_> = report.checks.iter().map(|check| check.name.as_str()).collect();
        assert_eq!(
            names,
            ["distro_registered", "lockdown_config", "gpu_visible", "container_runtime", "runner", "disk"]
        );
        assert!(report.passed);
    }

    #[tokio::test]
    async fn each_problem_fails_its_check() {
        let fake = healthy();
        fake.state().exec.insert("cat".to_owned(), (0, "[automount]\nenabled = true\n".to_owned()));
        fake.state().exec.insert("nvidia-smi".to_owned(), (9, "NVIDIA-SMI has failed".to_owned()));
        let report = run(&environment(&fake, 10), true, None).await;
        let failed: Vec<_> = report.checks.iter().filter(|c| !c.passed).map(|c| c.name.as_str()).collect();
        assert_eq!(failed, ["lockdown_config", "gpu_visible", "disk"]);
        assert!(!report.passed);

        let missing = FakeWsl::ready();
        let report = run(&environment(&missing, 120), true, None).await;
        assert_eq!(report.checks.len(), 1, "nothing else can run without the distribution");
        assert!(!report.passed);
    }

    #[tokio::test]
    async fn the_job_runner_has_to_answer_with_the_rules_loaded_and_a_scratch_disk_that_works() {
        let fake = healthy();
        let down = FakeRunner { hello: Err("the socket isn't there".to_owned()), ..FakeRunner::healthy() };
        let report = run(&with_runner(&fake, 120, down), true, None).await;
        let runner = report.checks.iter().find(|c| c.name == "runner").unwrap();
        assert!(!runner.passed && runner.detail.contains("doesn't answer"), "{runner:?}");

        let mut unruled = FakeRunner::healthy();
        unruled.hello.as_mut().unwrap().rules_loaded = false;
        let report = run(&with_runner(&fake, 120, unruled), true, None).await;
        assert!(
            report.checks.iter().any(|c| c.name == "runner" && !c.passed && c.detail.contains("rules aren't loaded"))
        );

        let no_disk = FakeRunner::healthy().with(check("scratch_disk", CheckStatus::Failed, "mkfs failed"));
        let report = run(&with_runner(&fake, 120, no_disk), true, None).await;
        assert!(report.checks.iter().any(|c| c.name == "runner" && !c.passed && c.detail.contains("mkfs failed")));
        assert!(!report.passed);

        let leaking = FakeRunner::healthy().with(check(
            "leftovers",
            CheckStatus::Failed,
            "left from finished sessions: 1 process",
        ));
        let report = run(&with_runner(&fake, 120, leaking), true, None).await;
        assert!(
            report.checks.iter().any(|c| c.name == "runner" && !c.passed && c.detail.contains("1 process")),
            "a runner that leaks fails the self-test"
        );
        assert!(!report.passed);
    }

    #[tokio::test]
    async fn a_job_that_can_reach_the_home_network_fails_the_self_test_and_being_offline_does_not() {
        let fake = healthy();
        let open = FakeRunner::healthy().with(check(
            "network_lockdown",
            CheckStatus::Failed,
            "a job can reach the home network: 192.168.1.1:80",
        ));
        let report = run(&with_runner(&fake, 120, open), false, None).await;
        let lockdown = report.checks.iter().find(|c| c.name == "network_lockdown").unwrap();
        assert!(!lockdown.passed && lockdown.detail.contains("192.168.1.1:80"), "{lockdown:?}");
        assert!(!report.passed);

        let offline = FakeRunner::healthy().with(check(
            "network_lockdown",
            CheckStatus::Skipped,
            "the internet wasn't tried because this machine is offline",
        ));
        let report = run(&with_runner(&fake, 120, offline), false, None).await;
        let lockdown = report.checks.iter().find(|c| c.name == "network_lockdown").unwrap();
        assert!(lockdown.passed && lockdown.detail.starts_with("skipped: "), "{lockdown:?}");
        assert!(report.passed, "{report:?}");
    }

    #[tokio::test]
    async fn the_cuda_calculation_runs_when_the_image_is_here_and_a_wrong_answer_fails_the_gpu_check() {
        let fake = healthy();
        let ran = FakeRunner::healthy().with(check(
            "cuda_matmul",
            CheckStatus::Passed,
            "matmul ok on NVIDIA GeForce RTX 4080 Laptop GPU",
        ));
        let report = run(&with_runner(&fake, 120, ran), false, None).await;
        let gpu = report.checks.iter().find(|c| c.name == "gpu_in_container").unwrap();
        assert!(gpu.passed && gpu.detail.contains("matmul ok on"), "{gpu:?}");

        let wrong =
            FakeRunner::healthy().with(check("cuda_matmul", CheckStatus::Failed, "the GPU and the CPU disagree"));
        let report = run(&with_runner(&fake, 120, wrong), false, None).await;
        let gpu = report.checks.iter().find(|c| c.name == "gpu_in_container").unwrap();
        assert!(!gpu.passed && gpu.detail.contains("the GPU and the CPU disagree"), "{gpu:?}");
    }

    #[tokio::test]
    async fn linux_macos_and_fake_gpus_skip_everything_and_pass() {
        let fake = FakeWsl::default();
        let skipped = Environment { skip: Some("not a Windows host".to_owned()), ..environment(&fake, 120) };
        let report = run(&skipped, false, None).await;
        assert!(report.passed);
        assert!(report.checks.iter().all(|check| check.detail.starts_with("skipped")));
        assert!(fake.state().calls.is_empty(), "wsl.exe was never run");
    }

    #[test]
    fn speeds_are_megabits_a_second() {
        assert_eq!(mbit_per_second(SPEED_TEST_BYTES, Duration::from_secs(1)), 268);
    }
}
