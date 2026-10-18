//! Automatic updates (ADR 0034), the agent's half; the launcher runs the trial and any rollback.
//!
//! 1. **An offer** (`UpdateAgent`): the manifest's signature is checked against the key baked into this build, and a
//!    version that isn't newer is refused. The app zip, and the distro tarball only if its version changed, are
//!    downloaded into `updates\` with resume and checked against the manifest's size and SHA-256. Any failure is
//!    reported (`FAILED_VERIFY` or `FAILED_DOWNLOAD`) and nothing more happens. The tray shows "Update ready".
//! 2. **Installing** waits until no session runs and no offer was just accepted: it never interrupts a renter's job.
//!    The zip is unpacked into `app-<new>\`, a changed distro imported beside the old one, and the new version's
//!    `self-test --quick` must pass there. Then the trial is written, `current` switched, and the agent exits for the
//!    launcher to start the new one.
//! 3. **The new version** confirms the trial once it's connected and welcomed and its own quick self-test passes; it
//!    reports `INSTALLED`, removes the old distro (only now) and keeps two versions. After a rollback, the old version
//!    reports `ROLLED_BACK` with the launcher's reason and removes the distro the failed update brought.

use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, PoisonError};
use std::time::Duration;

use agent_proto::v1::open_stream_request::Body as AgentBody;
use agent_proto::v1::update_result::Outcome;
use agent_proto::v1::{UpdateAgent, UpdateResult};
use idleo_release::apps::Apps;
use idleo_release::channel::UpdatePhase;
use idleo_release::download::{self, DownloadError};
use idleo_release::manifest::{self, Manifest, ManifestError};
use idleo_release::paths::{self, Layout};
use idleo_release::update_state::{self, DistroSwitch, UpdateState};
use idleo_release::wsl::{Runner, Wsl};
use time::OffsetDateTime;

use crate::host::{Host, Kind};

/// The exit code that asks the launcher to start the version `current` now names.
pub const RESTART_FOR_UPDATE: u8 = 3;
/// After accepting an offer, installing waits this long for its session to start before counting the agent idle.
pub const OFFER_SETTLES_IN: Duration = Duration::from_secs(60);

/// The files of an update that are downloaded, checked and ready to install.
#[derive(Debug, Clone)]
struct Ready {
    manifest: Manifest,
    app_zip: PathBuf,
    distro_tarball: Option<PathBuf>,
}

/// What the updater needs from the machine.
pub struct Updater {
    layout: Layout,
    host: Arc<Host>,
    client: reqwest::Client,
    /// Runs a program and waits: the new version's quick self-test, and `wsl.exe`.
    runner: Arc<dyn Runner>,
    /// The release key: the baked-in one, or a test's.
    key: Option<[u8; 32]>,
    /// This version.
    version: String,
    ready: Mutex<Option<Ready>>,
    busy: Mutex<bool>,
    /// Asks `idleo-agent run` to exit for the launcher's restart.
    restart: tokio::sync::watch::Sender<bool>,
    /// Never confirm a trial: a development build made with `IDLEO_TEST_FAIL_TRIAL=1`.
    fail_trial: bool,
}

impl Updater {
    /// An updater for this installation.
    #[must_use]
    #[allow(clippy::too_many_arguments)] // Each is one thing the updater touches; tests pass fakes for all of them.
    pub fn new(
        layout: Layout,
        host: Arc<Host>,
        client: reqwest::Client,
        runner: Arc<dyn Runner>,
        key: Option<[u8; 32]>,
        version: &str,
        restart: tokio::sync::watch::Sender<bool>,
        fail_trial: bool,
    ) -> Arc<Self> {
        Arc::new(Self {
            layout,
            host,
            client,
            runner,
            key,
            version: version.to_owned(),
            ready: Mutex::new(None),
            busy: Mutex::new(false),
            restart,
            fail_trial,
        })
    }

    /// Whether this build's trials never confirm, to try a rollback: a development build made with
    /// `IDLEO_TEST_FAIL_TRIAL=1` (`scripts/release/publish.sh --dev`). A release build ignores it.
    #[must_use]
    pub fn fail_trial_built_in() -> bool {
        idleo_release::keys::DEV_BUILD && matches!(option_env!("IDLEO_TEST_FAIL_TRIAL"), Some("1"))
    }

    /// The distro version in use: `IdleO\distro`, written by setup and switched by updates.
    #[must_use]
    pub fn current_distro(layout: &Layout) -> Option<String> {
        std::fs::read_to_string(distro_file(layout)).ok().map(|text| text.trim().to_owned()).filter(|v| !v.is_empty())
    }

    fn report(&self, version: &str, outcome: Outcome, detail: &str) {
        tracing::info!(version, ?outcome, detail, "reporting how the update went");
        self.host.queue(
            AgentBody::UpdateResult(UpdateResult {
                version: version.to_owned(),
                outcome: outcome.into(),
                detail: detail.chars().take(300).collect(),
            }),
            Kind::UpdateResult,
        );
    }

    /// An `UpdateAgent` arrived: verify it, then download its files in the background.
    pub fn offer(self: &Arc<Self>, update: UpdateAgent) {
        {
            let mut busy = self.busy.lock().unwrap_or_else(PoisonError::into_inner);
            if *busy || self.ready.lock().unwrap_or_else(PoisonError::into_inner).is_some() {
                tracing::debug!(version = %update.version, "an update is already being handled");
                return;
            }
            *busy = true;
        }
        let this = Arc::clone(self);
        tokio::spawn(async move {
            this.prepare(update).await;
            *this.busy.lock().unwrap_or_else(PoisonError::into_inner) = false;
        });
    }

    async fn prepare(&self, update: UpdateAgent) {
        let verified = match self.key {
            Some(key) => manifest::verify(&update.manifest, &update.manifest_signature, &key),
            None => Err(ManifestError::NoKey),
        };
        let manifest = match verified {
            Ok(manifest) => manifest,
            Err(e) => {
                self.report(&update.version, Outcome::FailedVerify, &e.to_string());
                return;
            }
        };
        if idleo_release::version::is_newer(&manifest.version, &self.version) != Some(true) {
            self.report(
                &manifest.version,
                Outcome::FailedVerify,
                &format!("{} isn't newer than {}", manifest.version, self.version),
            );
            return;
        }
        self.host.set_update(UpdatePhase::Downloading);
        let updates = self.layout.updates();
        let app_zip = updates.join(file_name(&manifest.app.file));
        if let Err(e) = download::download(&self.client, &update.app_url, &app_zip, &manifest.app, &mut |_, _| {}).await
        {
            self.failed_download(&manifest.version, &e);
            return;
        }
        let distro_tarball = if Self::current_distro(&self.layout).as_deref() == Some(manifest.distro.version.as_str())
        {
            None
        } else {
            let tarball = updates.join(file_name(&manifest.distro.file));
            if let Err(e) = download::download(
                &self.client,
                &update.distro_url,
                &tarball,
                &manifest.distro.artifact(),
                &mut |_, _| {},
            )
            .await
            {
                self.failed_download(&manifest.version, &e);
                return;
            }
            Some(tarball)
        };
        tracing::info!(version = %manifest.version, "update downloaded and checked; installing when idle");
        *self.ready.lock().unwrap_or_else(PoisonError::into_inner) = Some(Ready { manifest, app_zip, distro_tarball });
        self.host.set_update(UpdatePhase::Ready);
    }

    fn failed_download(&self, version: &str, error: &DownloadError) {
        let outcome = match error {
            DownloadError::Verify(_) => Outcome::FailedVerify,
            DownloadError::Download(_) => Outcome::FailedDownload,
        };
        self.host.set_update(UpdatePhase::Idle);
        self.report(version, outcome, &error.to_string());
    }

    /// Whether this updater has asked the agent to restart into a new version.
    #[must_use]
    pub fn restarting(&self) -> tokio::sync::watch::Receiver<bool> {
        self.restart.subscribe()
    }

    /// The distro version this installation uses.
    #[must_use]
    pub fn distro(&self) -> Option<String> {
        Self::current_distro(&self.layout)
    }

    /// Whether an update is downloaded and waiting for an idle moment.
    #[must_use]
    pub fn is_ready(&self) -> bool {
        self.ready.lock().unwrap_or_else(PoisonError::into_inner).is_some()
    }

    /// Installs a ready update if the agent is idle now. Returns whether it switched versions and asked to restart.
    ///
    /// # Errors
    /// If installing failed; the update is dropped and the agent carries on with this version.
    pub fn install_if_idle(&self, idle: bool) -> Result<bool, String> {
        if !idle {
            return Ok(false);
        }
        let Some(ready) = self.ready.lock().unwrap_or_else(PoisonError::into_inner).take() else { return Ok(false) };
        self.host.set_update(UpdatePhase::Installing);
        match self.install(&ready) {
            Ok(()) => {
                let _ = self.restart.send(true);
                Ok(true)
            }
            Err(e) => {
                self.host.set_update(UpdatePhase::Idle);
                self.report(&ready.manifest.version, Outcome::FailedVerify, &format!("installing failed: {e}"));
                Err(e)
            }
        }
    }

    fn install(&self, ready: &Ready) -> Result<(), String> {
        let version = &ready.manifest.version;
        let apps = Apps::new(self.layout.clone());
        apps.install_zip(&ready.app_zip, version).map_err(|e| format!("unpacking {version}: {e}"))?;
        let previous_distro = Self::current_distro(&self.layout);
        let mut distro = None;
        if let Some(tarball) = &ready.distro_tarball {
            let new = ready.manifest.distro.version.clone();
            let wsl = Wsl::new(Arc::clone(&self.runner));
            let name = manifest::distro_name(&new);
            if !wsl.ours().map_err(|e| e.to_string())?.contains(&name) {
                wsl.import(&name, &self.layout.wsl_dir(&new), tarball).map_err(|e| e.to_string())?;
            }
            distro = Some(DistroSwitch { previous: previous_distro.clone().unwrap_or_default(), trial: new });
        }
        // The new version must pass its quick self-test here, against the distro it will use, before anything switches.
        let new_agent = apps.program(version, paths::AGENT_EXE);
        let distro_version =
            distro.as_ref().map_or_else(|| previous_distro.clone().unwrap_or_default(), |d| d.trial.clone());
        let output = self
            .runner
            .run(&new_agent.to_string_lossy(), &["self-test", "--quick", "--json", "--distro", &distro_version])
            .map_err(|e| format!("the new version couldn't be started: {e}"))?;
        if !output.ok() {
            if let Some(switch) = &distro {
                let _ = Wsl::new(Arc::clone(&self.runner)).unregister(&manifest::distro_name(&switch.trial));
            }
            return Err(format!(
                "the new version's quick self-test failed: {}",
                output.stdout.lines().last().unwrap_or("")
            ));
        }
        let state = UpdateState {
            trial: version.clone(),
            previous: self.version.clone(),
            started_at: OffsetDateTime::now_utc(),
            distro: distro.clone(),
            crashes: 0,
            outcome: update_state::Outcome::Trial,
        };
        state.save(&self.layout.update_state()).map_err(|e| e.to_string())?;
        if let Some(switch) = &distro {
            write_distro(&self.layout, &switch.trial).map_err(|e| e.to_string())?;
        }
        apps.set_current(version).map_err(|e| e.to_string())?;
        if let Err(e) = apps.stage_launcher(version) {
            tracing::warn!(error = %e, "the new launcher couldn't be staged; the old one carries on");
        }
        let _ = std::fs::remove_file(&ready.app_zip);
        if let Some(tarball) = &ready.distro_tarball {
            let _ = std::fs::remove_file(tarball);
        }
        tracing::info!(%version, "switched to the new version; restarting for its trial");
        Ok(())
    }

    /// After Welcome: confirm this version's trial, or report a rollback to it. `quick_self_test` runs this version's
    /// own quick self-test and says whether it passed.
    pub async fn welcomed<F>(&self, quick_self_test: F)
    where
        F: Future<Output = bool>,
    {
        let path = self.layout.update_state();
        let Some(mut state) = UpdateState::load(&path) else { return };
        match state.outcome.clone() {
            update_state::Outcome::Trial if state.trial == self.version => {
                if self.fail_trial {
                    tracing::warn!("built with IDLEO_TEST_FAIL_TRIAL=1: this trial is never confirmed");
                    return;
                }
                if !quick_self_test.await {
                    tracing::warn!(
                        "the quick self-test failed; the trial stays unconfirmed and the launcher rolls back"
                    );
                    return;
                }
                state.outcome = update_state::Outcome::Confirmed;
                if let Err(e) = state.save(&path) {
                    tracing::warn!(error = %e, "the confirmed trial couldn't be saved");
                    return;
                }
                self.after_confirmed(&state);
            }
            update_state::Outcome::Confirmed if state.trial == self.version => {
                if !self.host.waiting(&Kind::UpdateResult) {
                    self.report(&state.trial, Outcome::Installed, "the new version passed its trial");
                }
            }
            update_state::Outcome::RolledBack { reason } if state.previous == self.version => {
                if let Some(switch) = &state.distro {
                    let _ = Wsl::new(Arc::clone(&self.runner)).unregister(&manifest::distro_name(&switch.trial));
                    let _ = write_distro(&self.layout, &switch.previous);
                }
                if !self.host.waiting(&Kind::UpdateResult) {
                    self.report(&state.trial, Outcome::RolledBack, &reason);
                }
            }
            _ => {}
        }
    }

    fn after_confirmed(&self, state: &UpdateState) {
        if let Some(switch) = &state.distro
            && !switch.previous.is_empty()
            && switch.previous != switch.trial
            && let Err(e) = Wsl::new(Arc::clone(&self.runner)).unregister(&manifest::distro_name(&switch.previous))
        {
            tracing::warn!(error = %e, "the old distribution couldn't be removed");
        }
        if let Err(e) = Apps::new(self.layout.clone()).keep_only(&[&state.trial, &state.previous]) {
            tracing::warn!(error = %e, "older versions couldn't be removed");
        }
        self.report(&state.trial, Outcome::Installed, "the new version passed its trial");
    }

    /// The control plane acked the update's result: the trial is over.
    pub fn result_acked(&self) {
        if let Some(state) = UpdateState::load(&self.layout.update_state())
            && state.outcome != update_state::Outcome::Trial
        {
            let _ = UpdateState::clear(&self.layout.update_state());
        }
    }
}

/// `IdleO\distro`: the distro version in use.
#[must_use]
pub fn distro_file(layout: &Layout) -> PathBuf {
    layout.data().join("distro")
}

/// Switches the distro in use: a temporary file, then a rename.
///
/// # Errors
/// If it can't be written.
pub fn write_distro(layout: &Layout, version: &str) -> std::io::Result<()> {
    let path = distro_file(layout);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, format!("{version}\n"))?;
    std::fs::rename(&temporary, path)
}

fn file_name(key: &str) -> String {
    Path::new(key).file_name().map_or_else(|| key.replace('/', "_"), |name| name.to_string_lossy().into_owned())
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::sync::atomic::{AtomicBool, Ordering};

    use ed25519_dalek::{Signer, SigningKey};
    use idleo_release::download::hex;
    use idleo_release::wsl::Output;
    use idleo_release::wsl::fake::FakeWsl;
    use sha2::{Digest, Sha256};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;
    use crate::owner::{MachineInputs, Sample};

    struct Quiet;

    impl MachineInputs for Quiet {
        fn sample(&self) -> Sample {
            Sample::QUIET
        }
    }

    /// `wsl.exe` through the fake; any other program is the new agent's quick self-test, which passes unless told.
    struct Machine {
        wsl: FakeWsl,
        self_test_passes: AtomicBool,
        ran: Mutex<Vec<String>>,
    }

    impl Runner for Machine {
        fn run(&self, program: &str, args: &[&str]) -> io::Result<Output> {
            if program == "wsl.exe" {
                return self.wsl.run(program, args);
            }
            self.ran.lock().unwrap().push(format!("{program} {}", args.join(" ")));
            let code = i32::from(!self.self_test_passes.load(Ordering::SeqCst));
            Ok(Output { code, stdout: "{\"passed\":true}".to_owned(), stderr: String::new() })
        }
    }

    /// Serves files by path, as the release bucket's links would.
    async fn serve(files: Vec<(String, Vec<u8>)>) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        tokio::spawn(async move {
            loop {
                let Ok((mut socket, _)) = listener.accept().await else { return };
                let files = files.clone();
                tokio::spawn(async move {
                    let mut request = vec![0u8; 4096];
                    let read = socket.read(&mut request).await.unwrap_or(0);
                    let line = String::from_utf8_lossy(&request[..read]).lines().next().unwrap_or("").to_owned();
                    let path = line.split(' ').nth(1).unwrap_or("/");
                    let body = files.iter().find(|(p, _)| p == path).map(|(_, b)| b.clone());
                    let (status, body) = body.map_or(("404 Not Found", Vec::new()), |b| ("200 OK", b));
                    let head =
                        format!("HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                    let _ = socket.write_all(head.as_bytes()).await;
                    let _ = socket.write_all(&body).await;
                });
            }
        });
        format!("http://{address}")
    }

    fn app_zip(version: &str) -> Vec<u8> {
        use std::io::Write;
        let mut zip = zip::ZipWriter::new(std::io::Cursor::new(Vec::new()));
        for program in paths::PROGRAMS {
            zip.start_file(paths::exe(program), zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(format!("{program} {version}").as_bytes()).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    fn artifact(file: &str, bytes: &[u8]) -> idleo_release::manifest::Artifact {
        idleo_release::manifest::Artifact {
            file: file.to_owned(),
            sha256: hex(&Sha256::digest(bytes)),
            size: bytes.len() as u64,
        }
    }

    struct Fixture {
        root: tempfile::TempDir,
        layout: Layout,
        host: Arc<Host>,
        machine: Arc<Machine>,
        updater: Arc<Updater>,
        restart: tokio::sync::watch::Receiver<bool>,
        key: SigningKey,
    }

    /// An installed 0.1.0 with distro 0.1.0.
    fn fixture(version: &str) -> Fixture {
        let root = tempfile::tempdir().unwrap();
        let layout = Layout::under(root.path());
        std::fs::create_dir_all(layout.programs()).unwrap();
        let apps = Apps::new(layout.clone());
        let zip = root.path().join("old.zip");
        std::fs::write(&zip, app_zip("0.1.0")).unwrap();
        apps.install_zip(&zip, "0.1.0").unwrap();
        apps.set_current("0.1.0").unwrap();
        std::fs::copy(apps.program("0.1.0", paths::LAUNCHER_EXE), layout.launcher()).unwrap();
        write_distro(&layout, "0.1.0").unwrap();
        let wsl = FakeWsl::ready();
        wsl.state().distros.insert("IdleO-0.1.0".into(), "/d".into());
        let machine = Arc::new(Machine { wsl, self_test_passes: AtomicBool::new(true), ran: Mutex::new(Vec::new()) });
        let host = Host::new(Arc::new(Quiet), root.path().join("agent"), false);
        let key = SigningKey::from_bytes(&[9; 32]);
        let (restart, restarting) = tokio::sync::watch::channel(false);
        let updater = Updater::new(
            layout.clone(),
            Arc::clone(&host),
            idleo_release::download::http_client().unwrap(),
            Arc::clone(&machine) as Arc<dyn Runner>,
            Some(key.verifying_key().to_bytes()),
            version,
            restart,
            false,
        );
        Fixture { root, layout, host, machine, updater, restart: restarting, key }
    }

    /// A signed offer of `version` with distro `distro`, its files served.
    async fn offer(fixture: &Fixture, version: &str, distro: &str, tamper: bool) -> UpdateAgent {
        let zip = app_zip(version);
        let tarball = format!("distro {distro}").into_bytes();
        let manifest = Manifest {
            version: version.to_owned(),
            channel: "stable".into(),
            released_at: OffsetDateTime::from_unix_timestamp(1_760_000_000).unwrap(),
            rollout_percent: 100,
            min_windows_build: 19045,
            app: artifact(&format!("{version}/idleo-app-{version}.zip"), &zip),
            distro: idleo_release::manifest::Distro {
                version: distro.to_owned(),
                file: format!("distro/idleo-distro-{distro}.tar.gz"),
                sha256: hex(&Sha256::digest(&tarball)),
                size: tarball.len() as u64,
            },
            setup: artifact(&format!("{version}/IdleO-Setup.exe"), b"setup"),
        };
        let bytes = serde_json::to_vec(&manifest).unwrap();
        let signature = fixture.key.sign(&bytes).to_bytes().to_vec();
        // Tampered: the same size, one byte changed, so only the SHA-256 can tell.
        let mut served_zip = zip;
        if tamper {
            served_zip[10] ^= 0xff;
        }
        let base = serve(vec![("/app".into(), served_zip), ("/distro".into(), tarball)]).await;
        UpdateAgent {
            version: version.to_owned(),
            manifest: bytes,
            manifest_signature: signature,
            app_url: format!("{base}/app"),
            distro_url: format!("{base}/distro"),
            ..UpdateAgent::default()
        }
    }

    /// Waits for the offer's background work to finish: `offer` marks the updater busy before it returns.
    async fn settle(fixture: &Fixture) {
        for _ in 0..500 {
            if !*fixture.updater.busy.lock().unwrap() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        panic!("the update's background work didn't finish");
    }

    fn results(fixture: &Fixture) -> Vec<(String, Outcome, String)> {
        fixture
            .host
            .due(tokio::time::Instant::now(), Duration::ZERO)
            .into_iter()
            .filter_map(|request| match request.body {
                Some(AgentBody::UpdateResult(result)) => {
                    Some((result.version.clone(), result.outcome(), result.detail))
                }
                _ => None,
            })
            .collect()
    }

    #[tokio::test]
    async fn a_verified_update_waits_for_an_idle_moment_then_installs_and_restarts() {
        let fixture = fixture("0.1.0");
        fixture.updater.offer(offer(&fixture, "0.2.0", "0.1.0", false).await);
        settle(&fixture).await;
        assert!(fixture.updater.is_ready());
        assert_eq!(fixture.host.status().update, UpdatePhase::Ready);

        // A renter's job is running: nothing happens.
        assert_eq!(fixture.updater.install_if_idle(false), Ok(false));
        assert_eq!(Apps::new(fixture.layout.clone()).current().as_deref(), Some("0.1.0"));

        assert_eq!(fixture.updater.install_if_idle(true), Ok(true));
        assert!(*fixture.restart.borrow());
        let apps = Apps::new(fixture.layout.clone());
        assert_eq!(apps.current().as_deref(), Some("0.2.0"));
        let state = UpdateState::load(&fixture.layout.update_state()).unwrap();
        assert_eq!((state.trial.as_str(), state.previous.as_str()), ("0.2.0", "0.1.0"));
        assert_eq!(state.outcome, update_state::Outcome::Trial);
        assert_eq!(state.distro, None, "the distro didn't change");
        assert!(fixture.machine.ran.lock().unwrap()[0].contains("self-test --quick --json --distro 0.1.0"));
        assert!(fixture.layout.launcher_staged().exists(), "the new launcher is staged for its next start");
        assert!(!fixture.layout.updates().join("idleo-app-0.2.0.zip").exists(), "the download is cleaned up");
    }

    #[tokio::test]
    async fn a_new_distro_is_imported_beside_the_old_and_switched() {
        let fixture = fixture("0.1.0");
        fixture.updater.offer(offer(&fixture, "0.2.0", "0.2.0", false).await);
        settle(&fixture).await;
        assert_eq!(fixture.updater.install_if_idle(true), Ok(true));
        let names: Vec<String> = fixture.machine.wsl.state().distros.keys().cloned().collect();
        assert_eq!(names, ["IdleO-0.1.0", "IdleO-0.2.0"], "the old one stays until the trial is confirmed");
        assert_eq!(Updater::current_distro(&fixture.layout).as_deref(), Some("0.2.0"));
        let state = UpdateState::load(&fixture.layout.update_state()).unwrap();
        assert_eq!(state.distro, Some(DistroSwitch { previous: "0.1.0".into(), trial: "0.2.0".into() }));
    }

    #[tokio::test]
    async fn a_failed_quick_self_test_installs_nothing() {
        let fixture = fixture("0.1.0");
        fixture.machine.self_test_passes.store(false, Ordering::SeqCst);
        fixture.updater.offer(offer(&fixture, "0.2.0", "0.2.0", false).await);
        settle(&fixture).await;
        assert!(fixture.updater.install_if_idle(true).is_err());
        assert!(!*fixture.restart.borrow());
        assert_eq!(Apps::new(fixture.layout.clone()).current().as_deref(), Some("0.1.0"));
        assert_eq!(Updater::current_distro(&fixture.layout).as_deref(), Some("0.1.0"));
        assert!(!fixture.machine.wsl.state().distros.contains_key("IdleO-0.2.0"), "the new distro is removed again");
        assert!(UpdateState::load(&fixture.layout.update_state()).is_none());
        assert_eq!(results(&fixture)[0].1, Outcome::FailedVerify);
    }

    #[tokio::test]
    async fn a_tampered_manifest_a_wrong_hash_or_an_old_version_is_refused() {
        let fixture = fixture("0.1.0");
        let mut tampered = offer(&fixture, "0.2.0", "0.1.0", false).await;
        tampered.manifest[3] ^= 1;
        fixture.updater.offer(tampered);
        settle(&fixture).await;
        fixture.updater.offer(offer(&fixture, "0.2.0", "0.1.0", true).await);
        settle(&fixture).await;
        fixture.updater.offer(offer(&fixture, "0.0.9", "0.1.0", false).await);
        settle(&fixture).await;
        let outcomes: Vec<Outcome> = results(&fixture).into_iter().map(|(_, outcome, _)| outcome).collect();
        assert_eq!(outcomes, [Outcome::FailedVerify, Outcome::FailedVerify, Outcome::FailedVerify]);
        assert!(!fixture.updater.is_ready());
        assert_eq!(Apps::new(fixture.layout.clone()).installed().len(), 1, "nothing installed");
    }

    #[tokio::test]
    async fn the_new_version_confirms_its_trial_cleans_up_and_reports_installed() {
        let old = fixture("0.1.0");
        old.updater.offer(offer(&old, "0.2.0", "0.2.0", false).await);
        settle(&old).await;
        old.updater.install_if_idle(true).unwrap();
        // A third version left from before is removed too: two are kept.
        let extra = old.root.path().join("extra.zip");
        std::fs::write(&extra, app_zip("0.0.8")).unwrap();
        Apps::new(old.layout.clone()).install_zip(&extra, "0.0.8").unwrap();

        let (restart, _) = tokio::sync::watch::channel(false);
        let new = Updater::new(
            old.layout.clone(),
            Arc::clone(&old.host),
            idleo_release::download::http_client().unwrap(),
            Arc::clone(&old.machine) as Arc<dyn Runner>,
            None,
            "0.2.0",
            restart,
            false,
        );
        new.welcomed(async { true }).await;
        let state = UpdateState::load(&old.layout.update_state()).unwrap();
        assert_eq!(state.outcome, update_state::Outcome::Confirmed);
        assert_eq!(old.machine.wsl.state().distros.keys().cloned().collect::<Vec<_>>(), ["IdleO-0.2.0"]);
        assert_eq!(Apps::new(old.layout.clone()).installed().into_iter().collect::<Vec<_>>(), ["0.1.0", "0.2.0"]);
        let reported = results(&old);
        assert_eq!(reported.last().unwrap().1, Outcome::Installed);
        new.result_acked();
        assert!(UpdateState::load(&old.layout.update_state()).is_none(), "the trial is over once its result is acked");
    }

    #[tokio::test]
    async fn an_unconfirmed_trial_stays_open_for_the_launcher() {
        let fixture = fixture("0.2.0");
        let state = UpdateState {
            trial: "0.2.0".into(),
            previous: "0.1.0".into(),
            started_at: OffsetDateTime::now_utc(),
            distro: None,
            crashes: 0,
            outcome: update_state::Outcome::Trial,
        };
        state.save(&fixture.layout.update_state()).unwrap();
        fixture.updater.welcomed(async { false }).await;
        assert_eq!(UpdateState::load(&fixture.layout.update_state()).unwrap().outcome, update_state::Outcome::Trial);

        let (restart, _) = tokio::sync::watch::channel(false);
        let failing = Updater::new(
            fixture.layout.clone(),
            Arc::clone(&fixture.host),
            idleo_release::download::http_client().unwrap(),
            Arc::clone(&fixture.machine) as Arc<dyn Runner>,
            None,
            "0.2.0",
            restart,
            true,
        );
        failing.welcomed(async { true }).await;
        assert_eq!(
            UpdateState::load(&fixture.layout.update_state()).unwrap().outcome,
            update_state::Outcome::Trial,
            "IDLEO_TEST_FAIL_TRIAL never confirms"
        );
    }

    #[tokio::test]
    async fn after_a_rollback_the_old_version_reports_it_and_drops_the_new_distro() {
        let fixture = fixture("0.1.0");
        fixture.machine.wsl.state().distros.insert("IdleO-0.2.0".into(), "/d2".into());
        write_distro(&fixture.layout, "0.2.0").unwrap();
        let state = UpdateState {
            trial: "0.2.0".into(),
            previous: "0.1.0".into(),
            started_at: OffsetDateTime::now_utc(),
            distro: Some(DistroSwitch { previous: "0.1.0".into(), trial: "0.2.0".into() }),
            crashes: 3,
            outcome: update_state::Outcome::RolledBack { reason: "crashed 3 times".into() },
        };
        state.save(&fixture.layout.update_state()).unwrap();
        fixture.updater.welcomed(async { true }).await;
        assert_eq!(fixture.machine.wsl.state().distros.keys().cloned().collect::<Vec<_>>(), ["IdleO-0.1.0"]);
        assert_eq!(Updater::current_distro(&fixture.layout).as_deref(), Some("0.1.0"));
        let reported = results(&fixture);
        assert_eq!(reported, [("0.2.0".to_owned(), Outcome::RolledBack, "crashed 3 times".to_owned())]);
    }
}
