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
use crate::steps::{Config, Ending, Engine, undo};
use crate::system::Latest;
use crate::system::fake::{FakeSystem, LinkGoes, rtx};
use crate::system::{Gpu, System};

fn key() -> SigningKey {
    SigningKey::from_bytes(&[3; 32])
}

fn config() -> Config {
    Config {
        api_url: "http://192.168.1.20:8080".into(),
        app_url: "http://192.168.1.20:3000".into(),
        site_url: "http://192.168.1.20:3002".into(),
        control_plane_host: "192.168.1.20".into(),
        control_plane_port: 9443,
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
    let (send, mut events) = mpsc::unbounded_channel();
    for answer in answers {
        send.send(answer.clone()).unwrap();
    }
    let mut screens = Vec::new();
    let mut page = |screen: ToPage| screens.push(screen);
    let ending = {
        let mut engine = Engine::new(system, config(), Some(key().verifying_key().to_bytes()), &mut page, &mut events);
        engine.run(resume).await
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
    assert!(matches!(screens.last(), Some(ToPage::Done { pending: true })));
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
            && toml.contains("app_url = \"http://192.168.1.20:3000\"")
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
    assert!(matches!(screens.last(), Some(ToPage::Done { pending: false })));
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
    let old = Gpu { driver: "531.79".into(), ..rtx() };
    *system.gpus.borrow_mut() = VecDeque::from([vec![old.clone()], vec![old.clone()], vec![old], vec![rtx()]]);
    let (ending, screens) = run(&system, &[FromPage::SetUp, FromPage::Fix], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert_eq!(fixes(&screens), [Problem::DriverTooOld]);
    assert!(screens.iter().any(|s| matches!(s, ToPage::Fix { rechecking: true, text, .. } if text == "Your graphics driver needs an update (2 min).")));
    assert_eq!(system.opened.borrow()[0], "https://www.nvidia.com/en-us/software/nvidia-app/");
    assert!(measured(&system).contains(&"checks fixed".to_owned()));
}

#[tokio::test(start_paused = true)]
async fn each_other_fix_screen_has_its_text_and_button() {
    let mut off = laptop();
    off.virtualization = false;
    let (ending, screens) = run(&off, &[FromPage::SetUp, FromPage::Fix, FromPage::ConfirmClose], false).await;
    assert_eq!(ending, Ending::Undo);
    assert_eq!(fixes(&screens), [Problem::VirtualizationOff]);
    assert_eq!(off.opened.borrow()[0], "http://192.168.1.20:3002/help/virtualization/asus/");

    let mut full = laptop();
    full.free_bytes = 37_000_000_000;
    let (_, screens) = run(&full, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    assert!(screens.iter().any(
        |s| matches!(s, ToPage::Fix { problem: Problem::NotEnoughDisk, text, .. } if text == "Free up 23 GB to host.")
    ));

    let mut old_windows = laptop();
    old_windows.windows_build = 19_044;
    let (_, screens) = run(&old_windows, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    assert_eq!(fixes(&screens), [Problem::WindowsTooOld]);

    let mut unplugged = laptop();
    unplugged.on_battery = true;
    let (ending, screens) = run(&unplugged, &[FromPage::SetUp], false).await;
    assert_eq!(ending, Ending::Done { pending: true }, "unplugged only warns");
    assert!(
        screens
            .iter()
            .any(|s| matches!(s, ToPage::Progress { note: Some(note), .. } if note.contains("Plug your laptop in")))
    );
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
    assert_eq!(failed.reason.as_deref(), Some("self_test:gpu_in_container"));
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
async fn installed_but_not_linked_starts_at_the_link() {
    let system = laptop();
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Denied]);
    run(&system, &[FromPage::SetUp, FromPage::ConfirmClose], false).await;
    system.events.borrow_mut().clear();
    *system.links.borrow_mut() = VecDeque::from([LinkGoes::Approved]);
    let (ending, screens) = run(&system, &[], false).await;
    assert_eq!(ending, Ending::Done { pending: true });
    assert!(!screens.iter().any(|s| matches!(s, ToPage::Welcome { .. })));
    let started: Vec<String> = measured(&system).into_iter().filter(|e| e.ends_with(" started")).collect();
    assert_eq!(started, ["link started", "start started"]);
}
