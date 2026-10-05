use std::fs;
use std::process::Command;

use idleo_agent::enrol::{self, EnrolError, EnrolmentCode};
use idleo_agent::pki::ReplyRejected;
use idleo_agent::state::{self, CERT_FILE, INFO_FILE, KEY_FILE};

use crate::support::{DAY, Enrol, TestServer, VALID_CODE};

fn files(dir: &std::path::Path) -> Vec<String> {
    let mut names: Vec<String> = fs::read_dir(dir)
        .map(|entries| entries.map(|e| e.unwrap().file_name().to_string_lossy().into_owned()).collect())
        .unwrap_or_default();
    names.sort();
    names
}

async fn enrol_with(
    server: &TestServer,
    dir: &std::path::Path,
    code: &str,
    force: bool,
) -> Result<enrol::Enrolled, EnrolError> {
    let code = EnrolmentCode::parse(code).unwrap();
    enrol::enrol(&server.target, &server.pki().agent_roots(), dir, &code, force).await
}

#[tokio::test]
async fn enrolment_saves_the_key_certificate_and_host_id() {
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();

    let enrolled = enrol_with(&server, dir.path(), VALID_CODE, false).await.unwrap();

    assert_eq!(enrolled.host_id, server.host_id());
    assert_eq!(files(dir.path()), [CERT_FILE, INFO_FILE, KEY_FILE]);
    let info: serde_json::Value = serde_json::from_slice(&fs::read(dir.path().join(INFO_FILE)).unwrap()).unwrap();
    assert_eq!(info["host_id"], server.host_id().to_string());
    assert!(info["not_after"].as_str().unwrap().ends_with('Z'));
    assert!(fs::read_to_string(dir.path().join(KEY_FILE)).unwrap().starts_with("-----BEGIN PRIVATE KEY-----"));
    assert_eq!(fs::read_to_string(dir.path().join(CERT_FILE)).unwrap().matches("BEGIN CERTIFICATE").count(), 2);
    let identity = state::load(dir.path()).unwrap();
    assert_eq!(identity.host_id, server.host_id());
    assert!(identity.not_after > time::OffsetDateTime::now_utc() + 29 * DAY);

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = fs::metadata(dir.path().join(KEY_FILE)).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }
    #[cfg(windows)]
    assert_eq!(agent_windows::file_access_sddl(&dir.path().join(KEY_FILE)).unwrap(), agent_windows::PRIVATE_FILE_SDDL);
}

#[tokio::test]
async fn a_refused_code_saves_nothing() {
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();

    let error = enrol_with(&server, dir.path(), "WRONG-C0DE0-00000-00000", false).await.unwrap_err();

    assert!(matches!(error, EnrolError::CodeRefused), "{error}");
    assert_eq!(error.exit_code(), 3);
    assert_eq!(files(dir.path()), Vec::<String>::new());
}

#[tokio::test]
async fn a_certificate_from_another_ca_is_refused_and_nothing_saved() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.enrol = Enrol::FromAnotherCa);
    let dir = tempfile::tempdir().unwrap();

    let error = enrol_with(&server, dir.path(), VALID_CODE, false).await.unwrap_err();

    assert!(matches!(error, EnrolError::BadReply(ReplyRejected::Untrusted(_))), "{error}");
    assert_eq!(error.exit_code(), 4);
    assert_eq!(files(dir.path()), Vec::<String>::new());
}

#[tokio::test]
async fn a_certificate_for_another_key_is_refused_and_nothing_saved() {
    let server = TestServer::tcp().await;
    server.behave(|b| b.enrol = Enrol::ForAnotherKey);
    let dir = tempfile::tempdir().unwrap();

    let error = enrol_with(&server, dir.path(), VALID_CODE, false).await.unwrap_err();

    assert_eq!(error.to_string(), EnrolError::BadReply(ReplyRejected::NotOurKey).to_string());
    assert_eq!(files(dir.path()), Vec::<String>::new());
}

#[tokio::test]
async fn an_existing_enrolment_is_kept_unless_forced_and_then_backed_up() {
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();
    enrol_with(&server, dir.path(), VALID_CODE, false).await.unwrap();
    let old_key = fs::read(dir.path().join(KEY_FILE)).unwrap();

    let error = enrol_with(&server, dir.path(), VALID_CODE, false).await.unwrap_err();
    assert!(matches!(error, EnrolError::AlreadyEnrolled(_)), "{error}");
    assert_eq!(error.exit_code(), 5);
    assert_eq!(fs::read(dir.path().join(KEY_FILE)).unwrap(), old_key);

    let enrolled = enrol_with(&server, dir.path(), VALID_CODE, true).await.unwrap();
    let backup = enrolled.backup.expect("a backup");
    assert_eq!(fs::read(backup.join(KEY_FILE)).unwrap(), old_key);
    assert!(backup.join(CERT_FILE).exists() && backup.join(INFO_FILE).exists());
    assert_ne!(fs::read(dir.path().join(KEY_FILE)).unwrap(), old_key);
    state::load(dir.path()).unwrap();
}

#[cfg(unix)]
#[tokio::test]
async fn the_agent_refuses_a_key_file_others_can_read() {
    use idleo_agent::state::StateError;
    use std::os::unix::fs::PermissionsExt;
    let server = TestServer::tcp().await;
    let dir = tempfile::tempdir().unwrap();
    server.enrol(dir.path()).await;
    fs::set_permissions(dir.path().join(KEY_FILE), fs::Permissions::from_mode(0o644)).unwrap();

    assert!(matches!(state::load(dir.path()), Err(StateError::KeyTooOpen(_, 0o644))));
    let (options, _events) = server.run_options(dir.path(), crate::support::timing());
    let (_stop, agent) = crate::support::spawn_agent(options);
    let error = agent.await.unwrap().unwrap_err();
    assert_eq!(error.exit_code(), 6);
    assert!(server.recorded().connections.is_empty());
}

/// A malformed code is refused by the binary before any network call, and never echoed.
#[test]
fn the_command_refuses_a_malformed_code_without_echoing_it() {
    let dir = tempfile::tempdir().unwrap();
    let config = dir.path().join("agent.toml");
    // Nothing listens on port 9; a network call would fail differently.
    fs::write(
        &config,
        "ca_root = \"missing.pem\"\ndata_dir = \"state\"\n[control_plane]\nhost = \"localhost\"\nport = 9\n",
    )
    .unwrap();

    let output = Command::new(env!("CARGO_BIN_EXE_idleo-agent"))
        .args(["--config", config.to_str().unwrap(), "enrol", "--code", "SECRET-NOT-A-CODE"])
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(2));
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(stderr.contains("isn't an enrolment code"), "{stderr}");
    assert!(!stderr.contains("SECRET"), "{stderr}");
    assert!(!dir.path().join("state").exists());
}
