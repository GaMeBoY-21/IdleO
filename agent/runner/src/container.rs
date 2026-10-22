//! The container every job runs in (ADR 0035, docs/plan/technical-design.md "Sandbox configuration"), as `nerdctl create`
//! flags: the container is made, the file nerdctl keeps of what it prints is moved onto its encrypted scratch disk (ADR
//! 0038), and only then is it started. One pure function builds them, so a test can hold every setting the sandbox promises, and nothing else can add a
//! flag.
//!
//! Every time: the image pinned by digest; user 10001:10001; a read-only root with a 1 GiB `/tmp`; `/work` the session's
//! scratch disk and the inputs (and a resume checkpoint) mounted read-only inside it, and nothing else from the machine;
//! all capabilities dropped, `no-new-privileges` and the default seccomp profile; limits on memory (with no swap beyond
//! it), CPUs and processes; its own PID, IPC and network namespaces; its own bridge network, with DNS only to the public
//! resolvers the sandbox's rules allow; no IPv6; the GPU only when there is one. Its environment is the spec's
//! `SESSION_PARAM_*` and `SESSION_INPUT_*`, plus the variables of the job contract, and never a grant or a token.

use std::collections::BTreeMap;
use std::path::PathBuf;

use jobcore::protocol::Spec;

/// The user and group a job runs as.
pub const JOB_USER: &str = "10001:10001";
/// Where the job's scratch disk is.
pub const WORK: &str = "/work";
/// The containerd namespace our containers live in, apart from anything else on the machine.
pub const NAMESPACE: &str = "idleo";
/// The public DNS servers a job is given: the only ones the sandbox's rules let it ask.
pub const RESOLVERS: [&str; 2] = ["1.1.1.1", "8.8.8.8"];
/// Where a job says its input was wrong, inside `/work` (the job contract's `SESSION_FAILURE_FILE`).
pub const FAILURE_FILE: &str = "idleo-failure.json";
/// The size of `/tmp`.
pub const TMP_MIB: u32 = 1024;
/// `/dev/shm`'s most, in MiB: `PyTorch`'s data loaders need it.
pub const SHM_MAX_MIB: u32 = 2048;
/// Open files, soft and hard.
pub const NOFILE: (u32, u32) = (1024, 4096);

/// What one container needs to be built.
#[derive(Debug, Clone)]
pub struct Plan {
    /// Its name, `idleo-<session>-<attempt>`.
    pub name: String,
    /// The session's id, a label for cleaning up after a crash.
    pub session: String,
    /// The attempt, likewise.
    pub attempt: i32,
    /// The session network's name.
    pub network: String,
    /// What to run.
    pub spec: Spec,
    /// The scratch disk's folder that becomes `/work`.
    pub work: PathBuf,
    /// The folder of staged inputs, mounted read-only at `/work/input`.
    pub input: PathBuf,
    /// The checkpoint to resume from, mounted read-only at `/work/resume`.
    pub resume: Option<PathBuf>,
    /// The folder on the scratch disk, out of the job's sight, where nerdctl's file of what it prints is kept (ADR 0038).
    pub logs: PathBuf,
    /// The most the session may upload, in kbit/s; 0 is "not said" and the runner's default holds (ADR 0038).
    pub upload_cap_kbps: u32,
    /// The input slots that were staged, for `SESSION_INPUT_<SLOT>_DIR`.
    pub slots: Vec<String>,
    /// Whether this is a development distribution, which may run a job without a GPU.
    pub development: bool,
    /// An entrypoint in place of the image's, for the self-test's throwaway containers only; never set for a job.
    pub entrypoint: Option<String>,
}

/// Why a spec can't be run.
#[derive(Debug, PartialEq, Eq)]
pub struct Refused(pub String);

/// An image reference the runner will pull: pinned by digest, and nothing a flag could be made of.
///
/// # Errors
/// Why it isn't.
pub fn check_image(image: &str) -> Result<(), Refused> {
    let (name, digest) =
        image.split_once("@sha256:").ok_or_else(|| Refused("the image isn't pinned by digest".to_owned()))?;
    let name_ok = !name.is_empty()
        && !name.starts_with(['-', '/', '.'])
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'.' | b'_' | b'-' | b'/' | b':'));
    let digest_ok = digest.len() == 64 && digest.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
    if name_ok && digest_ok { Ok(()) } else { Err(Refused("the image reference isn't valid".to_owned())) }
}

/// Whether `name` may be an environment variable of a job: the spec's own, `SESSION_PARAM_*` and `SESSION_INPUT_*`.
fn job_variable(name: &str) -> bool {
    (name.starts_with("SESSION_PARAM_") || name.starts_with("SESSION_INPUT_"))
        && name.bytes().all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_')
}

/// The job's environment: the spec's `SESSION_PARAM_*` and `SESSION_INPUT_*`, then the contract's variables, which win.
/// Anything else in the spec is left out.
#[must_use]
pub fn environment(plan: &Plan) -> BTreeMap<String, String> {
    let mut env: BTreeMap<String, String> = plan
        .spec
        .env
        .iter()
        .filter(|(name, value)| job_variable(name) && !value.contains('\0'))
        .map(|(name, value)| (name.clone(), value.clone()))
        .collect();
    for slot in &plan.slots {
        env.insert(format!("SESSION_INPUT_{}_DIR", slot.to_ascii_uppercase()), format!("{WORK}/input/{slot}/"));
    }
    env.insert("SESSION_OUTPUT_DIR".to_owned(), format!("{WORK}/outputs"));
    env.insert("SESSION_CHECKPOINT_DIR".to_owned(), format!("{WORK}/checkpoints"));
    env.insert("SESSION_FAILURE_FILE".to_owned(), format!("{WORK}/{FAILURE_FILE}"));
    if plan.spec.checkpoint_interval_secs > 0 {
        env.insert("SESSION_CHECKPOINT_INTERVAL".to_owned(), plan.spec.checkpoint_interval_secs.to_string());
    }
    if plan.resume.is_some() {
        env.insert("SESSION_RESUME_DIR".to_owned(), format!("{WORK}/resume"));
    }
    env.insert("HOME".to_owned(), WORK.to_owned());
    // Without it a job's prints wait in a buffer, and the renter's live log arrives in lumps.
    env.insert("PYTHONUNBUFFERED".to_owned(), "1".to_owned());
    env
}

/// `/dev/shm`'s size in MiB: 2 GiB, or a quarter of the memory limit if that's smaller.
#[must_use]
pub fn shm_mib(memory_mb: u32) -> u32 {
    SHM_MAX_MIB.min(memory_mb / 4)
}

/// The arguments of `nerdctl create`, in full.
///
/// # Errors
/// If the spec can't be run: an unpinned image, a limit of zero, or no GPU outside a development distribution.
pub fn create_arguments(plan: &Plan) -> Result<Vec<String>, Refused> {
    check_image(&plan.spec.image)?;
    if !plan.spec.gpu && !plan.development {
        return Err(Refused("a job without a GPU only runs in a development distribution".to_owned()));
    }
    create_arguments_unchecked(plan)
}

/// The same flags for an image that isn't pinned by digest: only for the image baked into the distribution, which the
/// self-test runs to prove the sandbox on this very machine. Every other setting is the same.
///
/// # Errors
/// If a limit is zero.
pub fn create_arguments_trusted(plan: &Plan) -> Result<Vec<String>, Refused> {
    create_arguments_unchecked(plan)
}

fn create_arguments_unchecked(plan: &Plan) -> Result<Vec<String>, Refused> {
    let spec = &plan.spec;
    if spec.memory_mb == 0 || spec.cpu_millicores == 0 || spec.pids_limit == 0 || spec.scratch_mb == 0 {
        return Err(Refused("a resource limit in the spec is zero".to_owned()));
    }
    let mut args: Vec<String> = Vec::new();
    let mut add = |flags: &[&str]| args.extend(flags.iter().map(|flag| (*flag).to_owned()));
    add(&["--namespace", NAMESPACE, "create", "--pull", "never"]);
    add(&["--name", &plan.name]);
    add(&[
        "--label",
        &format!("idleo.session={}", plan.session),
        "--label",
        &format!("idleo.attempt={}", plan.attempt),
    ]);
    add(&["--network", &plan.network]);
    for resolver in RESOLVERS {
        add(&["--dns", resolver]);
    }
    add(&["--sysctl", "net.ipv6.conf.all.disable_ipv6=1", "--sysctl", "net.ipv6.conf.default.disable_ipv6=1"]);
    add(&["--user", JOB_USER, "--read-only"]);
    add(&["--tmpfs", &format!("/tmp:rw,nosuid,nodev,size={TMP_MIB}m")]);
    add(&["--shm-size", &format!("{}m", shm_mib(spec.memory_mb))]);
    add(&["--mount", &format!("type=bind,src={},dst={WORK}", plan.work.display())]);
    add(&["--mount", &format!("type=bind,src={},dst={WORK}/input,ro", plan.input.display())]);
    if let Some(resume) = &plan.resume {
        add(&["--mount", &format!("type=bind,src={},dst={WORK}/resume,ro", resume.display())]);
    }
    add(&["--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--security-opt", "seccomp=builtin"]);
    add(&["--memory", &format!("{}m", spec.memory_mb), "--memory-swap", &format!("{}m", spec.memory_mb)]);
    add(&["--cpus", &format!("{:.3}", f64::from(spec.cpu_millicores) / 1000.0)]);
    add(&["--pids-limit", &spec.pids_limit.to_string()]);
    add(&["--ulimit", &format!("nofile={}:{}", NOFILE.0, NOFILE.1), "--ulimit", "core=0"]);
    // A private PID namespace is nerdctl's default unless `--pid host` or `--pid container:<id>` is given, which the runner
    // never gives (a test holds that no `--pid` appears).
    add(&["--ipc", "private"]);
    if spec.gpu {
        args.extend(["--gpus".to_owned(), "all".to_owned()]);
    }
    args.extend(["--workdir".to_owned(), WORK.to_owned()]);
    if let Some(entrypoint) = &plan.entrypoint {
        args.extend(["--entrypoint".to_owned(), entrypoint.clone()]);
    }
    for (name, value) in environment(plan) {
        args.extend(["--env".to_owned(), format!("{name}={value}")]);
    }
    args.push(spec.image.clone());
    args.extend(spec.args.iter().cloned());
    Ok(args)
}

#[cfg(test)]
mod tests {
    use super::*;

    const DIGEST: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn plan() -> Plan {
        Plan {
            name: "idleo-s-1".to_owned(),
            session: "s".to_owned(),
            attempt: 1,
            network: "idleo-slot-3".to_owned(),
            spec: Spec {
                image: format!("registry.example/idleo/pytorch@sha256:{DIGEST}"),
                args: vec!["/opt/idleo/run.sh".to_owned()],
                env: BTreeMap::from([
                    ("SESSION_PARAM_COMMAND".to_owned(), "python main.py".to_owned()),
                    ("SESSION_INPUT_CODE_DIR".to_owned(), "/work/input/code/".to_owned()),
                    ("AWS_SECRET_ACCESS_KEY".to_owned(), "hunter2-secret".to_owned()),
                    ("PATH".to_owned(), "/bin".to_owned()),
                ]),
                memory_mb: 8192,
                cpu_millicores: 4000,
                pids_limit: 512,
                scratch_mb: 4096,
                max_runtime_secs: 600,
                checkpoint_interval_secs: 120,
                gpu: true,
            },
            work: PathBuf::from("/var/lib/idleo/mnt/s-1/work"),
            input: PathBuf::from("/var/lib/idleo/mnt/s-1/input"),
            resume: None,
            logs: PathBuf::from("/var/lib/idleo/mnt/s-1/logs"),
            upload_cap_kbps: 25_000,
            slots: vec!["code".to_owned()],
            development: false,
            entrypoint: None,
        }
    }

    fn has(args: &[String], pair: &[&str]) -> bool {
        args.windows(pair.len()).any(|window| window.iter().zip(pair).all(|(a, b)| a == b))
    }

    #[test]
    fn every_setting_the_sandbox_promises_is_there() {
        let args = create_arguments(&plan()).unwrap();
        for pair in [
            &["--user", "10001:10001"][..],
            &["--cap-drop", "ALL"],
            &["--security-opt", "no-new-privileges"],
            &["--security-opt", "seccomp=builtin"],
            &["--memory", "8192m"],
            &["--memory-swap", "8192m"],
            &["--cpus", "4.000"],
            &["--pids-limit", "512"],
            &["--ulimit", "nofile=1024:4096"],
            &["--shm-size", "2048m"],
            &["--ipc", "private"],
            &["--network", "idleo-slot-3"],
            &["--dns", "1.1.1.1"],
            &["--dns", "8.8.8.8"],
            &["--sysctl", "net.ipv6.conf.all.disable_ipv6=1"],
            &["--tmpfs", "/tmp:rw,nosuid,nodev,size=1024m"],
            &["--pull", "never"],
            &["--gpus", "all"],
            &["--workdir", "/work"],
        ] {
            assert!(has(&args, pair), "{pair:?} missing from {args:?}");
        }
        assert!(args.contains(&"--read-only".to_owned()));
        assert_eq!(args[..4], ["--namespace", "idleo", "create", "--pull"], "made, never started, by these arguments");
        assert!(!args.contains(&"--detach".to_owned()) && !args.contains(&"run".to_owned()));
        assert_eq!(
            &args[args.len() - 2..],
            [format!("registry.example/idleo/pytorch@sha256:{DIGEST}"), "/opt/idleo/run.sh".to_owned()]
        );
    }

    #[test]
    fn nothing_that_widens_the_sandbox_can_appear() {
        let mut with_resume = plan();
        with_resume.resume = Some(PathBuf::from("/var/lib/idleo/mnt/s-1/resume"));
        for plan in [plan(), with_resume] {
            let args = create_arguments(&plan).unwrap();
            for banned in [
                "--privileged",
                "--device",
                "--volume",
                "-v",
                "--pid",
                "--userns",
                "--uts",
                "--cap-add",
                "--add-host",
                "--network=host",
                "--security-opt=seccomp=unconfined",
                "--mount=type=bind,src=/",
            ] {
                assert!(!args.iter().any(|arg| arg == banned), "{banned} in {args:?}");
            }
            assert!(!has(&args, &["--network", "host"]) && !has(&args, &["--ipc", "host"]));
            assert!(!args.iter().any(|arg| arg.contains("unconfined")), "{args:?}");
            let mounts: Vec<&String> = args.windows(2).filter(|w| w[0] == "--mount").map(|w| &w[1]).collect();
            let expected = if plan.resume.is_some() { 3 } else { 2 };
            assert_eq!(mounts.len(), expected, "only /work, the inputs and a resume checkpoint: {mounts:?}");
            assert!(mounts[0].ends_with("dst=/work"));
            assert!(mounts[1..].iter().all(|mount| mount.ends_with(",ro")), "{mounts:?}");
            assert!(
                mounts.iter().all(|mount| mount.contains("/var/lib/idleo/mnt/")),
                "nothing of the distribution's: {mounts:?}"
            );
        }
    }

    #[test]
    fn the_environment_is_the_specs_params_and_inputs_plus_the_contract_and_never_a_secret() {
        let env = environment(&plan());
        assert_eq!(env["SESSION_PARAM_COMMAND"], "python main.py");
        assert_eq!(env["SESSION_INPUT_CODE_DIR"], "/work/input/code/");
        assert_eq!(env["SESSION_OUTPUT_DIR"], "/work/outputs");
        assert_eq!(env["SESSION_CHECKPOINT_DIR"], "/work/checkpoints");
        assert_eq!(env["HOME"], "/work");
        assert_eq!(env["SESSION_CHECKPOINT_INTERVAL"], "120");
        assert_eq!(env["SESSION_FAILURE_FILE"], "/work/idleo-failure.json");
        assert!(!env.contains_key("SESSION_RESUME_DIR"), "only on a resumed attempt");
        assert!(!env.contains_key("AWS_SECRET_ACCESS_KEY") && !env.contains_key("PATH"), "{env:?}");
        let mut resumed = plan();
        resumed.resume = Some(PathBuf::from("/x"));
        assert_eq!(environment(&resumed)["SESSION_RESUME_DIR"], "/work/resume");
        let printed = create_arguments(&plan()).unwrap().join(" ");
        assert!(!printed.contains("hunter2") && !printed.to_lowercase().contains("token"), "{printed}");
    }

    #[test]
    fn a_slot_names_its_input_folder_even_when_the_spec_didnt() {
        let mut p = plan();
        p.spec.env.clear();
        p.slots = vec!["model".to_owned(), "data".to_owned()];
        let env = environment(&p);
        assert_eq!(env["SESSION_INPUT_MODEL_DIR"], "/work/input/model/");
        assert_eq!(env["SESSION_INPUT_DATA_DIR"], "/work/input/data/");
    }

    #[test]
    fn shared_memory_is_two_gib_or_a_quarter_of_memory_if_smaller() {
        assert_eq!(shm_mib(16384), 2048);
        assert_eq!(shm_mib(8192), 2048);
        assert_eq!(shm_mib(4096), 1024);
        assert_eq!(shm_mib(1024), 256);
    }

    #[test]
    fn only_an_image_pinned_by_digest_is_run() {
        assert!(check_image(&format!("nvidia/cuda:12.8.1-base-ubuntu24.04@sha256:{DIGEST}")).is_ok());
        assert!(check_image(&format!("localhost:5000/idleo/pytorch@sha256:{DIGEST}")).is_ok());
        for bad in [
            "ubuntu:24.04".to_owned(),
            "ubuntu@sha256:abc".to_owned(),
            format!("--privileged@sha256:{DIGEST}"),
            format!("-v@sha256:{DIGEST}"),
            format!("Ubuntu@sha256:{DIGEST}"),
            format!("a b@sha256:{DIGEST}"),
            format!("@sha256:{DIGEST}"),
        ] {
            assert!(check_image(&bad).is_err(), "{bad}");
        }
        let mut unpinned = plan();
        unpinned.spec.image = "ubuntu:24.04".to_owned();
        assert!(create_arguments(&unpinned).is_err());
    }

    #[test]
    fn no_gpu_is_for_a_development_distribution_only_and_zero_limits_are_refused() {
        let mut cpu = plan();
        cpu.spec.gpu = false;
        assert!(create_arguments(&cpu).is_err(), "a release distribution never runs a job without a GPU");
        cpu.development = true;
        let args = create_arguments(&cpu).unwrap();
        assert!(!args.contains(&"--gpus".to_owned()));
        let mut zero = plan();
        zero.spec.pids_limit = 0;
        assert!(create_arguments(&zero).is_err());
    }
}
