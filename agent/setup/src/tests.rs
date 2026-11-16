//! Setup on a fake machine (ADR 0033): every path the spec names, on the Mac.

use std::collections::VecDeque;
use std::io::Write;

use ed25519_dalek::{Signer, SigningKey};
use idleo_release::apps::Apps;
use idleo_release::channel::LinkState;
use idleo_release::install::{self, Machine, RUNONCE_KEY, RUNONCE_VALUE, UNINSTALL_KEY};
use idleo_release::manifest::{Artifact, Distro, Manifest};
use idleo_release::paths;
use idleo_release::wsl::Wsl;
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;

use crate::ipc::{FromPage, Problem, ToPage};
use crate::steps::{Config, Ending, Engine, Summary, undo, undo_keeping_logs};
use crate::system::fake::{FakeSystem, LinkGoes, rtx};
use crate::system::{FactsRequest, Latest, Rejected};
use crate::system::{Gpu, System};

fn key() -> SigningKey {
    SigningKey::from_bytes(&[3; 32])
}

fn config() -> Config {
    Config {
        api_url: "http://192.168.1.20:8080".into(),
        host_url: "http://192.168.1.20:3003".into(),
        site_url: "http://192.168.1.20:3002".into(),
        control_plane_host: "192.168.1.20".into(),
        control_plane_port: 9443,
        registry: Some("192.168.1.20:5000".into()),
        ca_root_pem: "-----BEGIN CERTIFICATE-----\nroot\n-----END CERTIFICATE-----\n".into(),
    }
}

fn app_zip() -> Vec<u8> {
    let mut zip = zip_writer();
    for program in paths::PROGRAMS {
        zip.start_file(paths::exe(program), zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(program.as_bytes()).unwrap();
    }
    zip.finish().unwrap().into_inner()
}

fn zip_writer() -> zip::ZipWriter<std::io::Cursor<Vec<u8>>> {
    zip::ZipWriter::new(std::io::Cursor::new(Vec::new()))
}

fn artifact(file: &str, bytes: &[u8]) -> Artifact {
    Artifact {
        file: file.into(),
        sha256: idleo_release::download::hex(&Sha256::digest(bytes)),
        size: bytes.len() as u64,
    }
}

/// A signed release of 0.2.0 with distro 0.1.0, and the files its links serve. `tamper` changes the manifest after
/// signing; `wrong_file` serves a different app zip than the manifest names.
fn release(tamper: bool, wrong_file: bool) -> (Latest, Vec<(String, Vec<u8>)>) {
    let zip = app_zip();
    let tarball = b"the sealed linux image".to_vec();
    let manifest = Manifest {
        version: "0.2.0".into(),
        channel: "stable".into(),
        released_at: time::OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap(),
        rollout_percent: 100,
        min_windows_build: 19_045,
        app: artifact("0.2.0/idleo-app-0.2.0.zip", &zip),
        distro: Distro {
            version: "0.1.0".into(),
            file: "distro/idleo-distro-0.1.0.tar.gz".into(),
            sha256: idleo_release::download::hex(&Sha256::digest(&tarball)),
            size: tarball.len() as u64,
        },
        setup: artifact("0.2.0/IdleO-Setup.exe", b"setup"),
    };
    let mut bytes = serde_json::to_vec(&manifest).unwrap();
    let signature = key().sign(&bytes).to_bytes().to_vec();
    if tamper {
        let at = bytes.iter().position(|b| *b == b'1').unwrap();
        bytes[at] = b'9';
    }
    let mut served_zip = zip;
    if wrong_file {
        served_zip[12] ^= 0xff;
    }
    let latest = Latest { manifest: bytes, signature, app_url: "app".into(), distro_url: "distro".into() };
    (latest, vec![("app".into(), served_zip), ("distro".into(), tarball)])
}

fn laptop() -> FakeSystem {
    let (latest, files) = release(false, false);
    FakeSystem::new(latest, files)
}

/// Runs setup with these page answers queued, and returns how it ended and every screen it showed.
async fn run(system: &FakeSystem, answers: &[FromPage], resume: bool) -> (Ending, Vec<ToPage>) {
    let (ending, screens, _) = run_as(system, answers, resume, false).await;
    (ending, screens)
}

/// [`run`] as a development build (or not), with how the run went for the last screen.
async fn run_as(
    system: &FakeSystem,
    answers: &[FromPage],
    resume: bool,
    dev_build: bool,
) -> (Ending, Vec<ToPage>, Summary) {
    let (send, mut events) = mpsc::unbounded_channel();
    for answer in answers {
        send.send(answer.clone()).unwrap();
    }
    let mut screens = Vec::new();
    let mut page = |screen: ToPage| screens.push(screen);
    let (ending, summary) = {
        let mut engine = Engine::new(system, config(), Some(key().verifying_key().to_bytes()), &mut page, &mut events)
            .dev_build(dev_build);
        (engine.run(resume).await, engine.summary())
    };
    drop(send);
    (ending, screens, summary)
}

/// [`run`] with answers that depend on what is on the screen: `react` sees every screen and may answer it.
async fn run_reactive(
    system: &FakeSystem,
    first: &[FromPage],
    react: impl Fn(&ToPage) -> Option<FromPage>,
) -> (Ending, Vec<ToPage>) {
    let (send, mut events) = mpsc::unbounded_channel();
    for answer in first {
        send.send(answer.clone()).unwrap();
    }
    let mut screens = Vec::new();
    let reply = send.clone();
    let mut page = |screen: ToPage| {
        if let Some(answer) = react(&screen) {
            reply.send(answer).unwrap();
        }
        screens.push(screen);
    };
    let ending = {
        let mut engine = Engine::new(system, config(), Some(key().verifying_key().to_bytes()), &mut page, &mut events);
        engine.run(false).await
    };
    drop(send);
    (ending, screens)
}

fn measured(system: &FakeSystem) -> Vec<String> {
    system.events.borrow().iter().map(|event| format!("{} {}", event.step, event.outcome)).collect()
}

fn fixes(screens: &[ToPage]) -> Vec<Problem> {
    screens
        .iter()
        .filter_map(|screen| match screen {
            ToPage::Fix { problem, .. } => Some(*problem),
            _ => None,
        })
        .collect()
}

#[tokio::test]
async fn the_happy_path_runs_every_step_in_order_and_leaves_a_listed_install() {
    let system = laptop();
    let (ending, screens) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(matches!(screens.first(), Some(ToPage::Welcome { already_set_up: false, .. })));
    assert!(matches!(screens.last(), Some(ToPage::Done { pending: true, .. })));
    assert!(
        screens
            .iter()
            .any(|s| matches!(s, ToPage::Approve { short_url, .. } if short_url == "app.idleo.test/link/42/"))
    );
    let steps: Vec<String> =
        measured(&system).into_iter().filter(|e| e.ends_with(" passed") && !e.starts_with("click")).collect();
    assert_eq!(
        steps,
        [
            "setup_opened passed",
            "checks passed",
            "wsl passed",
            "download passed",
            "install passed",
            "distro passed",
            "self_test passed",
            "link passed",
            "listed passed",
            "start passed"
        ]
    );
    let clicks: Vec<_> =
        system.events.borrow().iter().filter(|e| e.step == "click").filter_map(|e| e.reason.clone()).collect();
    assert_eq!(clicks, ["set_up", "approve_seen"]);
    assert!(system.events.borrow().iter().all(|e| e.setup_version == idleo_agent::AGENT_VERSION));

    let layout = system.layout();
    let apps = Apps::new(layout.clone());
    assert_eq!(apps.current().as_deref(), Some("0.2.0"));
    assert!(layout.launcher().exists());
    let toml = std::fs::read_to_string(layout.agent_config()).unwrap();
    assert!(
        toml.contains("host = \"192.168.1.20\"")
            && toml.contains("port = 9443")
            && toml.contains("host_url = \"http://192.168.1.20:3003\"")
    );
    assert!(layout.agent_dir().join("ca-root.pem").exists());
    assert!(install::is_installed(&system));
    assert_eq!(system.registry().get(UNINSTALL_KEY, "DisplayVersion").as_deref(), Some("0.2.0"));
    assert_eq!(Wsl::new(system.wsl_runner()).ours().unwrap(), ["IdleO-0.1.0"]);
    assert!(!layout.updates().join("idleo-distro-0.1.0.tar.gz").exists(), "the tarball goes once imported");
    assert!(*system.started.borrow());
    assert_eq!(system.opened.borrow().first().map(String::as_str), Some("https://app.idleo.test/link/42/"));
}

#[tokio::test]
async fn a_live_host_is_told_so() {
    let mut system = laptop();
    system.listed = LinkState::Active;
    let (ending, screens) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: false });
    assert!(matches!(screens.last(), Some(ToPage::Done { pending: false, .. })));
}

#[tokio::test]
async fn after_the_restart_wsl_needed_setup_carries_on_after_wsl() {
    let system = laptop();
    {
        let mut wsl = system.machine.wsl.state();
        wsl.version = None;
        wsl.install_needs_restart = true;
    }
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::RestartNow], false).await;
    assert_eq!(ending, Ending::Restarting);
    assert!(matches!(screens.last(), Some(ToPage::Restart)));
    assert!(*system.restarted.borrow());
    let resume = system.registry().get(RUNONCE_KEY, RUNONCE_VALUE).unwrap();
    assert!(resume.ends_with("\" --resume") && resume.contains("setup"));
    assert!(
        system.layout().setup_dir().join(paths::exe(paths::SETUP_EXE)).exists(),
        "a copy survives the Downloads folder"
    );

    system.machine.wsl.restart();
    let installs_before = system.machine.wsl.state().calls.iter().filter(|c| c.starts_with("--install")).count();
    let (ending, screens) = run(&system, &[], true).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(matches!(screens.first(), Some(ToPage::Progress { .. })), "no Welcome after the restart");
    assert_eq!(system.machine.wsl.state().calls.iter().filter(|c| c.starts_with("--install")).count(), installs_before);
    let passed: Vec<String> = measured(&system).into_iter().filter(|e| e.ends_with(" passed")).collect();
    assert_eq!(passed.iter().filter(|e| *e == "checks passed").count(), 1, "the checks aren't run again");
}

#[tokio::test(start_paused = true)]
async fn an_old_driver_is_checked_again_every_ten_seconds_and_setup_carries_on_by_itself() {
    let system = laptop();
    let old = Gpu { driver: "522.06".into(), ..rtx() };
    *system.gpus.borrow_mut() = VecDeque::from([vec![old.clone()], vec![old.clone()], vec![old], vec![rtx()]]);
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::DriverTooOld]);
    assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { rechecking: true, text, .. } if text == "Update it (about 2 minutes) and we'll carry on by ourselves.")));
    assert_eq!(system.opened.borrow()[0], "https://www.nvidia.com/en-us/software/nvidia-app/");
    assert!(measured(&system).contains(&"checks fixed".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn each_other_fix_screen_has_its_text_and_button() {
    let mut off = laptop();
    off.virtualization = false;
    let (ending, screens) =
        run(&off, &[FromPage::SetUp, FromPage::Fix, FromPage::Help, FromPage::ConfirmClose], false).await;
    assert_eq!(ending, Ending::Undo);
    assert_eq!(fixes(&screens), [Problem::VirtualizationOff]);
    assert!(
        screens.iter().any(|s| matches!(s, ToPage::Fix { button, help: Some(help), .. } if button == "Check again" && help == "Show me how for my laptop"))
    );
    assert_eq!(
        *off.opened.borrow(),
        ["http://192.168.1.20:3002/help/virtualization/asus/"],
        "only the link goes anywhere"
    );

    let mut full = laptop();
    full.free_bytes = 37_000_000_000;
    let (_, screens) = run(&full, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { problem: Problem::NotEnoughDisk, text, .. } if text.contains("needs 60 GB free") && text.contains("there are 37") && text.contains("Free up 23 GB"))));

    let mut old_windows = laptop();
    old_windows.windows_build = 19_044;
    let (_, screens) = run(&old_windows, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    assert_eq!(fixes(&screens), [Problem::WindowsTooOld]);
}

#[tokio::test(start_paused = true)]
async fn on_battery_asks_to_plug_in_and_carries_on_by_itself_once_it_is() {
    let mut unplugged = laptop();
    unplugged.on_battery = true;
    *unplugged.plugs_in_after.borrow_mut() = Some(3);
    let (ending, screens) = run(&unplugged, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::OnBattery]);
    assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { problem: Problem::OnBattery, title, button, rechecking: true, .. } if title == "Plug in your laptop" && button == "Check again")));
    assert!(measured(&unplugged).contains(&"checks fixed".to_owned()));
}

#[tokio::test]
async fn no_rtx_gpu_leaves_nothing_behind() {
    let system = laptop();
    *system.gpus.borrow_mut() =
        VecDeque::from([vec![Gpu { name: "NVIDIA GeForce GTX 1650".into(), vram_mb: 4_096, driver: "572.16".into() }]]);
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Undo);
    assert_eq!(fixes(&screens), [Problem::NoRtxGpu]);
    let removal = undo(&system, None);
    assert_eq!(removal.problems, Vec::<String>::new());
    assert_eq!(install::leftovers(&system), Vec::<String>::new());
    assert!(system.machine.registry.is_empty());
    assert!(measured(&system).contains(&"checks failed".to_owned()));
}

#[tokio::test]
async fn closing_mid_way_undoes_every_step_done() {
    let system = laptop();
    system.machine.wsl.add_foreign("Ubuntu");
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Denied]);
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    assert_eq!(ending, Ending::Undo);
    assert!(screens.iter().any(|s| matches!(s, ToPage::LinkFailed { message } if message.contains("wasn't approved"))));
    assert!(install::is_installed(&system), "everything up to the link was done");
    let removal = undo(&system, None);
    assert_eq!(removal.distros, ["IdleO-0.1.0"]);
    assert_eq!(install::leftovers(&system), Vec::<String>::new());
    assert_eq!(Wsl::new(system.wsl_runner()).list().unwrap(), ["Ubuntu"], "the owner's distribution is untouched");
}

#[tokio::test]
async fn a_denied_or_expired_link_can_be_tried_again() {
    let system = laptop();
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Expired, LinkGoes::Approved]);
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::TryAgain], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(screens.iter().any(|s| matches!(s, ToPage::LinkFailed { message } if message.contains("ran out"))));
}

#[tokio::test]
async fn a_tampered_manifest_or_a_wrong_file_stops_with_nothing_installed() {
    for (tamper, wrong) in [(true, false), (false, true)] {
        let (latest, files) = release(tamper, wrong);
        let system = FakeSystem::new(latest, files);
        let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
        assert_eq!(ending, Ending::Undo);
        assert_eq!(fixes(&screens), [Problem::SomethingDidntPass]);
        assert!(!system.layout().programs().exists(), "nothing installed");
        assert!(!install::is_installed(&system));
        let _ = undo(&system, None);
        assert_eq!(install::leftovers(&system), Vec::<String>::new());
    }
}

#[tokio::test]
async fn a_failed_self_test_is_reported_and_leaves_the_laptop_unlisted() {
    let mut system = laptop();
    system.self_test_passes = false;
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Undo);
    assert_eq!(fixes(&screens), [Problem::SomethingDidntPass]);
    let failed =
        system.events.borrow().iter().find(|e| e.step == "self_test" && e.outcome == "failed").cloned().unwrap();
    assert_eq!(failed.reason.as_deref(), Some("gpu_in_container"));
    assert_eq!(failed.detail.as_deref(), Some("distro 0.1.0 (took 900 ms)"));
    assert!(!system.linked());
}

#[tokio::test]
async fn repair_runs_the_steps_again_but_never_links_again() {
    let system = laptop();
    run(&system, &[FromPage::SetUp], false).await;
    system.events.borrow_mut().clear();
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Denied]);
    let (ending, screens) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(matches!(screens.first(), Some(ToPage::Welcome { already_set_up: true, .. })));
    let measured = measured(&system);
    assert!(!measured.iter().any(|e| e.starts_with("link ")), "{measured:?}");
    assert!(measured.contains(&"self_test passed".to_owned()));
    assert_eq!(system.links.borrow().len(), 1, "the link was never asked for");
}

#[tokio::test]
async fn installed_but_not_linked_carries_on_at_the_link() {
    let system = laptop();
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Denied]);
    run(&system, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    system.events.borrow_mut().clear();
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Approved]);
    let (ending, screens) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(
        matches!(screens.first(), Some(ToPage::Welcome { unfinished: true, .. })),
        "an install that stopped part-way offers Repair"
    );
    let started: Vec<String> = measured(&system).into_iter().filter(|e| e.ends_with(" started")).collect();
    assert_eq!(started, ["link started", "start started"]);
}

/// A self-test with these checks, `(name, passed, what it found)`.
fn checks(found: &[(&str, bool, &str)]) -> Vec<idleo_agent::self_test::Check> {
    found
        .iter()
        .enumerate()
        .map(|(i, (name, passed, detail))| idleo_agent::self_test::Check {
            name: (*name).into(),
            passed: *passed,
            detail: (*detail).into(),
            took_ms: 100 + i as u64,
        })
        .collect()
}

fn failing_self_test(system: &mut FakeSystem) {
    system.self_test_checks = Some(checks(&[
        ("gpu_visible", true, "RTX 4080"),
        ("gpu_in_container", false, "nvidia-smi: no devices found"),
        ("lockdown", false, "a shell answered on port 22"),
    ]));
}

fn sent_events(system: &FakeSystem) -> Vec<crate::system::InstallEvent> {
    system.events.borrow().clone()
}

// ---------------------------------------------------------------------------------------------------------------
// Setup diagnostics: what is sent when something doesn't pass, the log, and keeping a failed install.
// ---------------------------------------------------------------------------------------------------------------

#[tokio::test]
async fn each_failed_check_is_its_own_event_with_what_it_found_and_how_long_it_took() {
    let mut system = laptop();
    failing_self_test(&mut system);
    let (ending, _) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Undo);
    let failed: Vec<_> =
        sent_events(&system).into_iter().filter(|e| e.step == "self_test" && e.outcome == "failed").collect();
    let reasons: Vec<_> = failed.iter().map(|e| e.reason.clone().unwrap()).collect();
    assert_eq!(reasons, ["gpu_in_container", "lockdown"], "only the checks that failed, one event each");
    assert_eq!(failed[0].detail.as_deref(), Some("nvidia-smi: no devices found (took 101 ms)"));
    assert_eq!(failed[1].detail.as_deref(), Some("a shell answered on port 22 (took 102 ms)"));
    let closed = sent_events(&system).into_iter().find(|e| e.step == "setup_closed").unwrap();
    assert_eq!(closed.reason.as_deref(), Some("self_test"));
}

#[tokio::test]
async fn a_failed_step_sends_the_error_text_and_not_the_person() {
    let system = laptop();
    let profile = system.identity.profile.clone();
    system.machine.wsl.state().import_error = Some(format!(
        "Wsl/Service/RegisterDistro: cannot create {profile}\\AppData\\Local\\IdleO\\wsl\\0.1.0 for Asha on TESTRIG-7"
    ));
    let (ending, _) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Undo);
    let events = sent_events(&system);
    let failed = events.iter().find(|e| e.step == "distro" && e.outcome == "failed").unwrap();
    assert_eq!(failed.reason.as_deref(), Some("distro_import"));
    let detail = failed.detail.as_deref().unwrap();
    assert!(detail.contains("RegisterDistro: cannot create %USERPROFILE%"), "{detail}");
    assert!(detail.contains("for <user> on <machine>"), "{detail}");
    // Nothing anywhere in what went to IdleO names the person or the machine.
    let wire = serde_json::to_string(&events).unwrap().to_lowercase();
    for secret in ["asha", "testrig", "c:\\\\users"] {
        assert!(!wire.contains(secret), "{secret} was sent: {wire}");
    }
}

#[tokio::test]
async fn long_details_are_cut_and_every_request_stays_under_the_servers_limit() {
    let mut system = laptop();
    let long = "x".repeat(3_000);
    let many: Vec<(String, bool, String)> = (0..15).map(|i| (format!("check_{i}"), false, long.clone())).collect();
    system.self_test_checks = Some(
        many.iter()
            .map(|(name, passed, detail)| idleo_agent::self_test::Check {
                name: name.clone(),
                passed: *passed,
                detail: detail.clone(),
                took_ms: 5,
            })
            .collect(),
    );
    run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    let failed: Vec<_> =
        sent_events(&system).into_iter().filter(|e| e.step == "self_test" && e.outcome == "failed").collect();
    assert_eq!(failed.len(), 15);
    assert!(failed.iter().all(|e| e.detail.as_ref().unwrap().chars().count() <= 2_000));
    let sizes = system.batch_bytes.borrow().clone();
    assert!(sizes.iter().all(|size| *size <= 16 * 1024), "{sizes:?}");
}

#[tokio::test]
async fn every_reason_setup_sends_is_one_the_server_accepts() {
    // A spread of failures, each with text that would break a looser rule: spaces, capitals, commas, a path.
    let mut scenarios: Vec<FakeSystem> = Vec::new();
    for (tamper, wrong) in [(true, false), (false, true)] {
        let (latest, files) = release(tamper, wrong);
        scenarios.push(FakeSystem::new(latest, files));
    }
    let import_fails = laptop();
    import_fails.machine.wsl.state().import_error = Some("The Operation Failed, At C:\\Some Path".into());
    scenarios.push(import_fails);
    let mut self_test = laptop();
    failing_self_test(&mut self_test);
    scenarios.push(self_test);
    let mut unreachable = laptop();
    unreachable.latest = Err("error sending request: Connection refused".into());
    scenarios.push(unreachable);
    let no_gpu = laptop();
    *no_gpu.gpus.borrow_mut() = VecDeque::from([vec![]]);
    scenarios.push(no_gpu);
    let denied = laptop();
    *denied.links.borrow_mut() = VecDeque::from([LinkGoes::Denied]);
    scenarios.push(denied);
    for system in &scenarios {
        run(system, &[FromPage::SetUp, FromPage::Fix, FromPage::Fix, FromPage::ConfirmClose], false).await;
    }
    run(&laptop(), &[FromPage::SetUp], false).await;
    let mut seen = 0;
    for system in &scenarios {
        for event in sent_events(system) {
            if let Some(reason) = event.reason.clone() {
                seen += 1;
                assert!(crate::reasons::is_valid(&reason), "{reason:?} in {event:?}");
            }
        }
    }
    assert!(seen > 10, "{seen}");
}

#[tokio::test]
async fn the_setup_log_has_every_step_every_command_and_the_self_test() {
    let mut system = laptop();
    failing_self_test(&mut system);
    // A command's output longer than 4 KB is cut.
    system.machine.wsl.state().exec.insert("never".into(), (0, String::new()));
    let (_, _, summary) = run_as(&system, &[FromPage::SetUp, FromPage::Fix], false, true).await;
    assert!(summary.failed);
    let log = std::fs::read_to_string(&summary.log).unwrap();
    assert!(summary.log.starts_with(system.layout().logs()));
    assert!(summary.log.file_name().unwrap().to_string_lossy().starts_with("setup-"));
    for step in ["checks", "wsl", "download", "install", "distro", "self_test"] {
        assert!(log.contains(&format!("step {step}: started")), "{step}\n{log}");
    }
    assert!(log.contains("step self_test: failed after"), "{log}");
    assert!(log.contains("ran `wsl.exe --import IdleO-0.1.0"), "{log}");
    assert!(log.contains(": exit 0 after"), "{log}");
    assert!(log.contains("self-test answered:"), "{log}");
    assert!(log.contains("\"name\": \"gpu_in_container\""), "{log}");
}

#[tokio::test]
async fn a_commands_output_is_cut_to_four_kilobytes_in_the_log() {
    let system = laptop();
    system.machine.wsl.state().import_error = Some("e".repeat(10_000));
    let (_, _, summary) = run_as(&system, &[FromPage::SetUp, FromPage::Fix], false, true).await;
    let log = std::fs::read_to_string(&summary.log).unwrap();
    assert!(log.contains("stderr:"), "{log}");
    assert!(log.contains("more bytes)"), "{log}");
    assert!(log.len() < 12 * 1024, "{}", log.len());
}

#[tokio::test]
async fn a_release_build_undoes_everything_including_the_log() {
    let mut system = laptop();
    failing_self_test(&mut system);
    let (ending, screens, summary) = run_as(&system, &[FromPage::SetUp, FromPage::Fix], false, false).await;
    assert_eq!(ending, Ending::Undo);
    assert!(summary.log.exists());
    assert!(
        screens.iter().all(|s| !matches!(s, ToPage::Fix { keep: true, .. })),
        "a release build never offers to keep it"
    );
    let removal = undo(&system, None);
    assert_eq!(removal.problems, Vec::<String>::new());
    assert!(!summary.log.exists());
    assert!(!system.layout().logs().exists());
    assert_eq!(install::leftovers(&system), Vec::<String>::new());
}

#[tokio::test]
async fn a_release_build_ignores_a_page_that_asks_to_keep_it_anyway() {
    let mut system = laptop();
    failing_self_test(&mut system);
    let (ending, _) = run(&system, &[FromPage::SetUp, FromPage::Keep, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Undo, "Keep did nothing; OK undid it");
    assert!(!sent_events(&system).iter().any(|e| e.reason.as_deref() == Some("keep")));
}

#[tokio::test]
async fn a_development_build_keeps_a_failed_install_and_the_next_run_repairs_it() {
    let mut system = laptop();
    failing_self_test(&mut system);
    let (ending, screens, summary) = run_as(&system, &[FromPage::SetUp, FromPage::Keep], false, true).await;
    assert_eq!(ending, Ending::Kept);
    assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { keep: true, .. })), "the button is offered");
    // Everything so far is still there; nothing is linked, started or set to start at sign-in.
    assert!(install::is_installed(&system));
    assert!(Wsl::new(system.wsl_runner()).ours().unwrap().contains(&"IdleO-0.1.0".to_owned()));
    assert!(system.registry().get(install::RUN_KEY, install::RUN_VALUE).is_none(), "no Run entry");
    assert!(system.registry().get(UNINSTALL_KEY, "UninstallString").is_some(), "the uninstall entry stays");
    assert!(!system.linked());
    assert!(!*system.started.borrow(), "the launcher wasn't started");
    assert!(summary.log.exists());
    let measured_first = measured(&system);
    assert!(measured_first.contains(&"click passed".to_owned()));
    assert!(sent_events(&system).iter().any(|e| e.step == "click" && e.reason.as_deref() == Some("keep")));
    assert!(measured_first.contains(&"setup_closed failed".to_owned()));

    // The fix is made; running setup again offers Repair and carries on from the self-test, not from the start.
    system.events.borrow_mut().clear();
    system.self_test_checks = None;
    let (ending, screens, _) = run_as(&system, &[FromPage::SetUp], false, true).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(matches!(screens.first(), Some(ToPage::Welcome { unfinished: true, .. })));
    let started: Vec<String> = measured(&system).into_iter().filter(|e| e.ends_with(" started")).collect();
    assert_eq!(started, ["self_test started", "link started", "start started"]);
    assert!(system.linked());
    assert!(*system.started.borrow());
    assert!(system.registry().get(install::RUN_KEY, install::RUN_VALUE).is_some(), "Repair puts the Run entry back");
}

#[tokio::test]
async fn repair_after_a_kept_import_failure_starts_again_at_the_import() {
    let system = laptop();
    system.machine.wsl.state().import_error = Some("0x80070057".into());
    let (ending, _, _) = run_as(&system, &[FromPage::SetUp, FromPage::Keep], false, true).await;
    assert_eq!(ending, Ending::Kept);
    system.machine.wsl.state().import_error = None;
    system.events.borrow_mut().clear();
    let (ending, _, _) = run_as(&system, &[FromPage::SetUp], false, true).await;
    assert_eq!(ending, Ending::Done { pending: true });
    let started: Vec<String> = measured(&system).into_iter().filter(|e| e.ends_with(" started")).collect();
    assert_eq!(started, ["distro started", "self_test started", "link started", "start started"]);
}

#[tokio::test]
async fn a_development_build_that_is_uninstalled_after_keeping_removes_everything_but_the_log() {
    let mut system = laptop();
    failing_self_test(&mut system);
    let (_, _, summary) = run_as(&system, &[FromPage::SetUp, FromPage::Keep], false, true).await;
    // The uninstall entry removes everything, the log included.
    let removal = idleo_release::install::remove_all(&system, None);
    assert_eq!(removal.problems, Vec::<String>::new());
    assert!(!summary.log.exists());
    assert_eq!(install::leftovers(&system), Vec::<String>::new());
}

#[tokio::test]
async fn a_development_builds_undo_keeps_only_the_logs_folder() {
    let mut system = laptop();
    failing_self_test(&mut system);
    let (ending, _, summary) = run_as(&system, &[FromPage::SetUp, FromPage::Fix], false, true).await;
    assert_eq!(ending, Ending::Undo);
    let removal = undo_keeping_logs(&system, None);
    assert_eq!(removal.problems, Vec::<String>::new());
    assert!(summary.log.exists(), "the log is how the failure gets looked at");
    assert!(!system.layout().setup_dir().exists() && !system.layout().programs().exists());
    assert!(system.registry().get(UNINSTALL_KEY, "UninstallString").is_none());
    assert_eq!(Wsl::new(system.wsl_runner()).ours().unwrap(), Vec::<String>::new());
}

#[tokio::test]
async fn a_batch_idleo_refuses_is_logged_shown_in_a_development_build_and_never_retried_forever() {
    let mut system = laptop();
    system.reject_events =
        Some(Rejected { status: "422".into(), answer: "{\"type\":\"/problems/validation-failed\"}".into() });
    let (ending, screens, summary) = run_as(&system, &[FromPage::SetUp], false, true).await;
    assert_eq!(ending, Ending::Done { pending: true }, "reporting never gets in the install's way");
    assert!(matches!(
        screens.last(),
        Some(ToPage::Done { report_problem: Some(problem), .. }) if problem == "422"
    ));
    let log = std::fs::read_to_string(&summary.log).unwrap();
    assert!(log.contains("IdleO didn't take") && log.contains("422") && log.contains("validation-failed"), "{log}");
    assert_eq!(
        *system.batches_offered.borrow(),
        usize::from(crate::steps::GIVE_UP_REPORTING),
        "tried a few times, then stopped"
    );
    assert!(log.contains("not reported to IdleO"), "{log}");
}

#[tokio::test]
async fn a_release_build_says_nothing_about_a_refused_batch() {
    let mut system = laptop();
    system.reject_events = Some(Rejected { status: "no answer".into(), answer: "timed out".into() });
    let (ending, screens, _) = run_as(&system, &[FromPage::SetUp], false, false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(matches!(screens.last(), Some(ToPage::Done { report_problem: None, .. })));
}

fn facts(found: &[(&str, &str)]) -> Vec<idleo_agent::facts::Fact> {
    found
        .iter()
        .map(|(name, value)| idleo_agent::facts::Fact { name: (*name).into(), value: (*value).into() })
        .collect()
}

/// What a laptop with a blocked LAN finds: the facts, two of them about the network.
fn laptop_facts() -> Vec<idleo_agent::facts::Fact> {
    facts(&[
        ("wsl_version", "WSL version: 2.3.26.0; Kernel version: 5.15.167.4-1"),
        ("networking_mode", "nat"),
        ("kernel", "5.15.167.4-microsoft-standard-WSL2"),
        ("nvidia_driver", "572.16"),
        ("cuda_version", "12.8"),
        ("host_ram", "32768 MB"),
        ("wsl_vm_memory", "used 1840 MB of 15921 MB (available 13700 MB)"),
        ("reach_gateway", "reachable: connected (3 ms)"),
        ("reach_storage", "reachable: connected (12 ms)"),
    ])
}

#[tokio::test]
async fn a_development_build_asks_for_facts_and_sends_them_as_facts_not_failures() {
    let mut system = laptop();
    system.facts = laptop_facts();
    let (ending, _, _) = run_as(&system, &[FromPage::SetUp], false, true).await;
    assert_eq!(ending, Ending::Done { pending: true }, "facts never stop setup, and it doesn't undo");
    assert_eq!(*system.facts_asked.borrow(), [Some(FactsRequest { storage: None })], "asked once, with --facts");
    let events = sent_events(&system);
    let sent: Vec<_> = events.iter().filter(|e| e.step == "facts").collect();
    assert_eq!(sent.len(), 2);
    assert!(sent.iter().all(|e| e.outcome == "passed"), "facts never fail");
    let machine = sent.iter().find(|e| e.reason.as_deref() == Some("facts_system")).unwrap();
    let detail = machine.detail.as_deref().unwrap();
    for line in [
        "kernel: 5.15.167.4-microsoft-standard-WSL2",
        "nvidia_driver: 572.16",
        "cuda_version: 12.8",
        "networking_mode: nat",
    ] {
        assert!(detail.contains(line), "{line} in {detail}");
    }
    let network = sent.iter().find(|e| e.reason.as_deref() == Some("facts_network")).unwrap();
    let detail = network.detail.as_deref().unwrap();
    assert!(detail.contains("reach_gateway: reachable: connected (3 ms)"), "{detail}");
    assert!(detail.contains("reach_storage: reachable: connected"), "{detail}");
    assert!(!events.iter().any(|e| e.outcome == "failed"), "no failure was reported for a fact");
}

#[tokio::test]
async fn facts_are_sent_when_the_self_test_fails_too_and_are_scrubbed_like_the_rest() {
    let mut system = laptop();
    failing_self_test(&mut system);
    system.facts = facts(&[
        ("wsl_version", r"WSL version: 2.3.26.0; log in C:\Users\Asha Rao\AppData for Asha on TESTRIG-7"),
        ("reach_gateway", "reachable: connected (3 ms)"),
    ]);
    let (ending, _, _) = run_as(&system, &[FromPage::SetUp, FromPage::Fix], false, true).await;
    assert_eq!(ending, Ending::Undo);
    let events = sent_events(&system);
    let machine = events.iter().find(|e| e.reason.as_deref() == Some("facts_system")).unwrap();
    assert_eq!(
        machine.detail.as_deref(),
        Some("wsl_version: WSL version: 2.3.26.0; log in %USERPROFILE%\\AppData for <user> on <machine>")
    );
    assert!(events.iter().any(|e| e.reason.as_deref() == Some("facts_network")));
    assert!(
        events.iter().any(|e| e.reason.as_deref() == Some("gpu_in_container")),
        "the failed checks are still reported"
    );
}

#[tokio::test]
async fn a_release_build_never_asks_for_facts() {
    let mut system = laptop();
    system.facts = laptop_facts();
    let (ending, _, _) = run_as(&system, &[FromPage::SetUp], false, false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(*system.facts_asked.borrow(), [None], "no --facts");
    assert!(!sent_events(&system).iter().any(|e| e.step == "facts"));
}

#[test]
fn storage_is_found_from_the_download_link() {
    use crate::steps::host_and_port;
    assert_eq!(
        host_and_port("http://192.168.1.20:9100/releases/0.2.0/app.zip?X-Amz=1").as_deref(),
        Some("192.168.1.20:9100")
    );
    assert_eq!(
        host_and_port("https://abc.r2.cloudflarestorage.com/releases/a.zip").as_deref(),
        Some("abc.r2.cloudflarestorage.com:443")
    );
    assert_eq!(host_and_port("http://storage.local/x").as_deref(), Some("storage.local:80"));
    assert_eq!(host_and_port("https://user:pw@host.example:8443/x").as_deref(), Some("host.example:8443"));
    assert_eq!(host_and_port("not a url"), None);
    assert_eq!(host_and_port("https:///nothing"), None);
}

#[tokio::test]
async fn a_development_build_tells_the_distribution_where_the_development_registry_is_and_a_release_build_never_does() {
    let system = laptop();
    let (ending, _, _) = run_as(&system, &[FromPage::SetUp], false, true).await;
    assert_eq!(ending, Ending::Done { pending: true });
    let calls = system.machine.wsl.state().calls.clone();
    let marker: Vec<&String> = calls.iter().filter(|call| call.contains("/etc/idleo/development")).collect();
    assert_eq!(marker.len(), 1, "{calls:?}");
    assert!(marker[0].contains("registry=%s") && marker[0].contains("192.168.1.20:5000"), "{marker:?}");

    let release = laptop();
    let (ending, _, _) = run_as(&release, &[FromPage::SetUp], false, false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(
        !release.machine.wsl.state().calls.iter().any(|call| call.contains("/etc/idleo/development")),
        "a release distribution carries no development marker"
    );
}

// --- the failures setup can meet, each with a plain screen and one button (ADR 0061) ---------------------------------

use crate::steps::{AUTO_RETRIES, Step, drive_of, looks_like_antivirus, overall_percent, time_left};
use crate::system::fake::Hang;

fn nothing_left(system: &FakeSystem) {
    assert_eq!(install::leftovers(system), Vec::<String>::new());
    assert!(system.machine.registry.is_empty());
}

#[tokio::test]
async fn an_arm_laptop_and_windows_in_s_mode_are_told_so_plainly_and_nothing_is_installed() {
    for (arm, s_mode, problem, reason) in [
        (true, false, Problem::ArmLaptop, "fix:arm_laptop"),
        (false, true, Problem::WindowsSMode, "fix:windows_s_mode"),
    ] {
        let mut system = laptop();
        system.arm = arm;
        system.s_mode = s_mode;
        let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
        assert_eq!(ending, Ending::Undo, "{problem:?}");
        assert_eq!(fixes(&screens), [problem]);
        assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { button, rechecking: false, .. } if button == "OK")));
        assert_eq!(undo(&system, None).problems, Vec::<String>::new());
        nothing_left(&system);
        let reasons: Vec<String> = system.events.borrow().iter().filter_map(|e| e.reason.clone()).collect();
        assert!(reasons.contains(&reason.to_owned()), "{reasons:?}");
        assert!(system.opened.borrow().is_empty(), "nothing is opened for a laptop that can't host");
    }
}

#[tokio::test(start_paused = true)]
async fn no_nvidia_card_or_driver_goes_to_nvidias_page_and_carries_on_when_one_shows() {
    let system = laptop();
    *system.gpus.borrow_mut() = VecDeque::from([vec![], vec![], vec![rtx()]]);
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::NoNvidiaGpu]);
    assert_eq!(system.opened.borrow()[0], "https://www.nvidia.com/en-us/software/nvidia-app/");
    assert!(measured(&system).contains(&"checks fixed".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn a_lost_connection_is_retried_three_times_by_itself_and_then_the_person_is_asked() {
    // Two failures: setup says so under the step, waits 2 s then 4 s, and never shows a screen.
    let system = laptop();
    *system.latest_fails.borrow_mut() = 2;
    let (ending, screens) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(fixes(&screens).is_empty());
    let details: Vec<&str> = screens
        .iter()
        .filter_map(|s| match s {
            ToPage::Progress { steps, .. } => steps.iter().find_map(|step| step.detail.as_deref()),
            _ => None,
        })
        .filter(|detail| detail.starts_with("Connection lost"))
        .collect();
    assert_eq!(details, ["Connection lost · trying again in 2 s", "Connection lost · trying again in 4 s"]);

    // Five failures: three retries (2, 4, 8 s), then "Check your connection"; Try again, and it counts afresh.
    let stubborn = laptop();
    *stubborn.latest_fails.borrow_mut() = 5;
    let tries = |screen: &ToPage| {
        matches!(screen, ToPage::Fix { problem: Problem::CantReachIdleo, .. }).then_some(FromPage::TryAgain)
    };
    let (ending, screens) = run_reactive(&stubborn, &[FromPage::SetUp], tries).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::CantReachIdleo]);
    assert_eq!(AUTO_RETRIES, 3);
    assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { title, text, button, .. } if title == "Check your connection" && text.contains("online") && button == "Try again")));
}

#[tokio::test(start_paused = true)]
async fn a_proxy_is_named_on_the_connection_screen_and_in_the_measurement() {
    let mut system = laptop();
    system.proxy = true;
    *system.latest_fails.borrow_mut() = 4;
    let tries = |screen: &ToPage| {
        matches!(screen, ToPage::Fix { problem: Problem::CantReachIdleo, .. }).then_some(FromPage::TryAgain)
    };
    let (_, screens) = run_reactive(&system, &[FromPage::SetUp], tries).await;
    assert!(
        screens
            .iter()
            .any(|s| matches!(s, ToPage::Fix { problem: Problem::CantReachIdleo, text, .. } if text.contains("proxy")))
    );
    let reasons: Vec<String> = system.events.borrow().iter().filter_map(|e| e.reason.clone()).collect();
    assert!(reasons.contains(&"proxy".to_owned()), "{reasons:?}");
}

#[tokio::test(start_paused = true)]
async fn an_antivirus_that_blocks_or_takes_a_file_is_named_and_try_again_carries_on() {
    let blocked = laptop();
    *blocked.download_error.borrow_mut() = Some("The operation did not complete successfully (os error 225)".into());
    let (ending, screens) = run(&blocked, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::AntivirusBlocked]);
    assert!(
        screens.iter().any(
            |s| matches!(s, ToPage::Fix { text, button, .. } if text.contains("app-0.2.0.zip") && button == "Try again")
        ),
        "names the file"
    );

    // The antivirus doesn't say a word: the file is simply gone after it was downloaded whole.
    let quiet = laptop();
    *quiet.quarantine.borrow_mut() = true;
    let (ending, screens) = run(&quiet, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::AntivirusBlocked]);
}

#[test]
fn what_looks_like_an_antivirus_is_what_windows_says_when_one_takes_a_file() {
    assert!(looks_like_antivirus(
        "Operation did not complete successfully because the file contains a virus (os error 225)"
    ));
    assert!(looks_like_antivirus("The file was deleted (os error 226) by potentially unwanted software"));
    assert!(!looks_like_antivirus("connection reset by peer"));
    assert!(!looks_like_antivirus("Access is denied. (os error 5)"));
}

// --- already installed, and a setup that can be stopped and run again at any moment --------------------------------

#[tokio::test]
async fn an_installed_idleo_offers_open_or_repair_and_open_changes_nothing() {
    let system = laptop();
    let (ending, _) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    let before = state_of(&system);

    let (ending, screens) = run(&system, &[FromPage::OpenIdleo], false).await;
    assert_eq!(ending, Ending::OpenedExisting);
    assert!(matches!(screens.first(), Some(ToPage::Welcome { already_set_up: true, demo: false, .. })));
    assert_eq!(*system.opened_idleo.borrow(), 1);
    assert_eq!(screens.len(), 1, "no step ran");
    assert_eq!(state_of(&system), before, "nothing touched");
}

/// What an install leaves: the registry and every file, with the fake root taken out of the words.
fn state_of(system: &FakeSystem) -> (Vec<String>, Vec<String>) {
    let root = system.machine.root.path().display().to_string();
    let registry = system
        .machine
        .registry
        .snapshot()
        .into_iter()
        .flat_map(|(key, values)| values.into_iter().map(move |(name, value)| format!("{key}\\{name} = {value:?}")))
        .map(|line| line.replace(&root, "<root>"))
        .collect();
    let mut files = Vec::new();
    let mut folders = vec![system.machine.root.path().to_owned()];
    while let Some(folder) = folders.pop() {
        for entry in std::fs::read_dir(&folder).into_iter().flatten().filter_map(Result::ok) {
            let path = entry.path();
            if path.is_dir() {
                folders.push(path);
            } else if !path.to_string_lossy().contains("logs") && !path.to_string_lossy().contains("setup") {
                files.push(path.strip_prefix(system.machine.root.path()).unwrap().display().to_string());
            }
        }
    }
    files.sort();
    (registry, files)
}

#[tokio::test(start_paused = true)]
async fn setup_stopped_dead_at_any_step_and_run_again_ends_in_the_same_good_state() {
    let reference = laptop();
    let (ending, _) = run(&reference, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    let good = state_of(&reference);
    assert!(good.0.iter().any(|line| line.contains("UninstallString")), "the reference is a finished install");

    let mut points = vec![Hang::Latest, Hang::Download, Hang::Link, Hang::Listed];
    points.extend(Step::ALL.iter().map(|step| Hang::After(step.name())));
    for hang in points {
        let mut system = laptop();
        system.hang_at = Some(hang);
        let first =
            tokio::time::timeout(std::time::Duration::from_secs(3_600), run(&system, &[FromPage::SetUp], false)).await;
        assert!(first.is_err(), "{hang:?}: the first run was stopped dead");
        // The laptop comes back: setup is run again, by hand or by the restart's entry.
        system.hang_at = None;
        let (ending, _) = run(&system, &[FromPage::SetUp, FromPage::SetUp], true).await;
        assert_eq!(ending, Ending::Done { pending: true }, "{hang:?}");
        assert_eq!(state_of(&system), good, "{hang:?}: not the same good state");
        assert!(system.linked() && install::is_installed(&system), "{hang:?}");
        // And once more, with everything done: still the same.
        let (ending, _) = run(&system, &[FromPage::SetUp, FromPage::SetUp], true).await;
        assert_eq!(ending, Ending::Done { pending: true }, "{hang:?} again");
        assert_eq!(state_of(&system), good, "{hang:?} twice");
    }
}

#[tokio::test(start_paused = true)]
async fn running_setup_twice_in_a_row_repairs_and_never_links_a_second_time() {
    let system = laptop();
    let (_, _) = run(&system, &[FromPage::SetUp], false).await;
    let good = state_of(&system);
    let links_before = system.links.borrow().len();
    let (ending, screens) = run(&system, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(matches!(screens.first(), Some(ToPage::Welcome { already_set_up: true, .. })));
    assert!(!screens.iter().any(|s| matches!(s, ToPage::Approve { .. })), "the link stays");
    assert_eq!(system.links.borrow().len(), links_before);
    assert_eq!(state_of(&system), good);
}

// --- diagnostics ----------------------------------------------------------------------------------------------------

#[tokio::test]
async fn diagnostics_on_a_failure_saves_a_scrubbed_zip_outside_what_an_undo_removes() {
    let (latest, files) = release(true, false);
    let system = FakeSystem::new(latest, files);
    let (ending, screens) =
        run(&system, &[FromPage::SetUp, FromPage::Diagnostics, FromPage::ShowDiagnostics, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Undo);
    let saved: Vec<&String> = screens
        .iter()
        .filter_map(|s| match s {
            ToPage::DiagnosticsSaved { path } => Some(path),
            _ => None,
        })
        .collect();
    assert_eq!(saved.len(), 1);
    let zip = std::path::PathBuf::from(saved[0]);
    assert!(zip.starts_with(system.diagnostics_folder()) && zip.exists());
    assert_eq!(*system.revealed.borrow(), [zip.clone()], "Show in folder reveals the very file");
    // What the undo removes is the app's folders; the zip is in the Downloads folder beside them, and is still there.
    let removal = undo(&system, None);
    assert_eq!(removal.problems, Vec::<String>::new());
    assert!(zip.exists(), "an undo never takes the host's diagnostics");

    let mut archive = zip::ZipArchive::new(std::fs::File::open(&zip).unwrap()).unwrap();
    let names: Vec<String> =
        (0..archive.len()).map(|i| archive.by_index(i).unwrap().name().unwrap().into_owned()).collect();
    assert!(names.iter().any(|name| name == "system.txt") && names.iter().any(|name| name == "about.txt"));
    assert!(names.iter().any(|name| name.starts_with("logs/setup-")), "{names:?}");
    let mut everything = String::new();
    for i in 0..archive.len() {
        std::io::Read::read_to_string(&mut archive.by_index(i).unwrap(), &mut everything).unwrap();
    }
    for private in ["Asha", "TESTRIG-7", "Asha Rao", "PRIVATE KEY"] {
        assert!(!everything.contains(private), "{private:?} is in the diagnostics");
    }
}

// --- the numbers on the progress screen -----------------------------------------------------------------------------

#[test]
fn the_time_left_is_told_in_words_once_there_is_enough_to_go_by() {
    let s = std::time::Duration::from_secs;
    assert_eq!(time_left(500_000, 300_000_000, s(10)), None, "under a megabyte: no guess");
    assert_eq!(time_left(100_000_000, 300_000_000, s(2)), None, "under three seconds: no guess");
    assert_eq!(time_left(100_000_000, 300_000_000, s(10)).as_deref(), Some("less than a minute left"), "20 s");
    assert_eq!(time_left(100_000_000, 1_000_000_000, s(60)).as_deref(), Some("about 9 min left"), "540 s");
    assert_eq!(time_left(100_000_000, 1_000_000_000, s(65)).as_deref(), Some("about 10 min left"), "585 s rounds up");
    assert_eq!(time_left(10_000_000, 1_000_000_000, s(100)).as_deref(), Some("about 2 h 45 min left"));
    assert_eq!(time_left(300_000_000, 300_000_000, s(10)), None, "done");
}

#[test]
fn the_overall_climbs_with_each_step_and_never_passes_one_hundred() {
    let done = |n: usize| Step::ALL[..n].to_vec();
    assert_eq!(overall_percent(&done(0), 0), 0);
    assert_eq!(overall_percent(&done(2), 0), 25);
    assert_eq!(overall_percent(&done(2), 50), 31, "halfway through the third step of eight");
    assert_eq!(overall_percent(&done(8), 0), 100);
    assert_eq!(overall_percent(&done(8), 500), 100);
}

#[test]
fn the_drive_is_read_from_the_path_it_installs_to() {
    assert_eq!(drive_of(r"C:\Users\asha\AppData\Local\IdleO").as_deref(), Some("C:"));
    assert_eq!(drive_of("d:/stuff").as_deref(), Some("D:"));
    assert_eq!(drive_of("/mnt/c/Users"), None);
    assert_eq!(drive_of(""), None);
}

#[tokio::test(start_paused = true)]
async fn the_download_screen_carries_its_own_percentage_and_the_overall() {
    let system = laptop();
    let (_, screens) = run(&system, &[FromPage::SetUp], false).await;
    let download = screens.iter().find_map(|s| match s {
        ToPage::Progress { steps, overall, .. } => steps
            .iter()
            .find(|step| step.label == Step::Download.label() && step.state == "current" && step.percent.is_some())
            .map(|step| (step.detail.clone().unwrap(), step.percent.unwrap(), *overall)),
        _ => None,
    });
    let (detail, percent, overall) = download.expect("a download screen with a percentage");
    assert!(detail.starts_with("Downloading · ") && detail.contains(" MB"), "{detail}");
    assert!(percent > 0 && overall >= 25, "{percent} {overall}");
}
