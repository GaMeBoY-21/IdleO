//! Whole checks against a registry in the test (wiremock), each image made here, byte by byte: what passes, what is refused
//! and why, and that a check that can't finish is reported as not checked, never as a pass.

use std::io::Write as _;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use idleo_scanner::check::{self, Ending, Job, Settings, Sink};
use idleo_scanner::control::{Next, Phase, Refused, Resolution, Result_};
use idleo_scanner::guard::Allowed;
use idleo_scanner::layers::Limits;
use idleo_scanner::registry::{Login, sha256};
use idleo_scanner::tar::build::Tar;
use idleo_scanner::trivy;
use serde_json::json;
use wiremock::matchers::{header, method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

const GZIP: &str = "application/vnd.oci.image.layer.v1.tar+gzip";
const MANIFEST: &str = "application/vnd.oci.image.manifest.v1+json";
const INDEX: &str = "application/vnd.oci.image.index.v1+json";
const ROOMY: Limits = Limits { layer_bytes: 1 << 30, image_bytes: 1 << 31 };

/// What a sink was told.
#[derive(Default)]
struct Told {
    resolved: Mutex<Vec<serde_json::Value>>,
    result: Mutex<Option<serde_json::Value>>,
    refused: Mutex<Option<(String, String)>>,
    failed: Mutex<Option<String>>,
}

impl Sink for Told {
    fn resolved(&self, resolution: &Resolution) -> impl Future<Output = Result<Next, String>> + Send {
        self.resolved.lock().unwrap().push(serde_json::to_value(resolution).unwrap());
        std::future::ready(Ok(Next::Proceed))
    }
    fn progress(&self, _: Phase, _: u64, _: u64) -> impl Future<Output = Result<Next, String>> + Send {
        std::future::ready(Ok(Next::Proceed))
    }
    fn result(&self, result: &Result_) -> impl Future<Output = Result<(), String>> + Send {
        *self.result.lock().unwrap() = Some(serde_json::to_value(result).unwrap());
        std::future::ready(Ok(()))
    }
    fn refused(&self, refused: &Refused) -> impl Future<Output = Result<(), String>> + Send {
        *self.refused.lock().unwrap() = Some((refused.reason.to_owned(), refused.detail.clone()));
        std::future::ready(Ok(()))
    }
    fn failed(&self, detail: &str) -> impl Future<Output = Result<(), String>> + Send {
        *self.failed.lock().unwrap() = Some(detail.to_owned());
        std::future::ready(Ok(()))
    }
}

fn gzip(bytes: &[u8]) -> Vec<u8> {
    let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
    encoder.write_all(bytes).unwrap();
    encoder.finish().unwrap()
}

fn elf(tag: &str) -> Vec<u8> {
    let mut bytes = b"\x7fELF\x02\x01\x01\x00".to_vec();
    bytes.extend_from_slice(tag.as_bytes());
    bytes
}

/// An image to serve: its layers (already compressed), its config and, if it's multi-platform, the platform it's for.
struct Image {
    layers: Vec<Vec<u8>>,
    config: serde_json::Value,
}

impl Image {
    fn of(layers: Vec<Vec<u8>>) -> Self {
        Self {
            layers,
            config: json!({
                "architecture": "amd64", "os": "linux",
                "config": { "Env": ["PATH=/usr/bin", "CUDA_VERSION=12.4.1"], "Cmd": ["python", "main.py"], "WorkingDir": "/app" }
            }),
        }
    }

    /// Serves its blobs, and its manifest under `reference` (a tag or a digest); returns the manifest's bytes.
    async fn serve(&self, server: &MockServer, repo: &str, reference: &str) -> Vec<u8> {
        self.serve_with(server, repo, reference, None).await
    }

    /// The same, answering only requests that carry `auth` as their Authorization.
    async fn serve_with(&self, server: &MockServer, repo: &str, reference: &str, auth: Option<&str>) -> Vec<u8> {
        let config = serde_json::to_vec(&self.config).unwrap();
        let blob = |bytes: &[u8]| json!({ "mediaType": GZIP, "digest": sha256(bytes), "size": bytes.len() });
        let manifest = serde_json::to_vec(&json!({
            "schemaVersion": 2,
            "mediaType": MANIFEST,
            "config": { "mediaType": "application/vnd.oci.image.config.v1+json", "digest": sha256(&config), "size": config.len() },
            "layers": self.layers.iter().map(|layer| blob(layer)).collect::<Vec<_>>(),
        }))
        .unwrap();
        let given = |route: String| {
            let mock = Mock::given(method("GET")).and(path(route));
            match auth {
                Some(auth) => mock.and(header("Authorization", auth)),
                None => mock,
            }
        };
        for bytes in self.layers.iter().chain([&config]) {
            given(format!("/v2/{repo}/blobs/{}", sha256(bytes)))
                .respond_with(ResponseTemplate::new(200).set_body_bytes(bytes.clone()))
                .mount(server)
                .await;
        }
        for name in [reference.to_owned(), sha256(&manifest)] {
            given(format!("/v2/{repo}/manifests/{name}"))
                .respond_with(
                    ResponseTemplate::new(200).insert_header("Content-Type", MANIFEST).set_body_bytes(manifest.clone()),
                )
                .mount(server)
                .await;
        }
        manifest
    }
}

/// A Trivy that checks it was given an OCI layout and prints `report`, or fails, or takes its time.
fn fake_trivy(dir: &tempfile::TempDir, body: &str) -> PathBuf {
    let path = dir.path().join("trivy");
    std::fs::write(
        &path,
        format!("#!/bin/sh\ntest -f \"$3/index.json\" && test -f \"$3/oci-layout\" || exit 9\n{body}\n"),
    )
    .unwrap();
    std::fs::set_permissions(&path, std::os::unix::fs::PermissionsExt::from_mode(0o755)).unwrap();
    path
}

const REPORT: &str = r#"cat <<'EOF'
{"Trivy":{"Version":"0.75.0"},"Results":[
 {"Target":"img","Vulnerabilities":[{"VulnerabilityID":"CVE-2024-1","PkgName":"openssl","InstalledVersion":"3.0","Severity":"CRITICAL","Title":"x"}]},
 {"Target":"/app/.env","Secrets":[{"RuleID":"aws-access-key-id","Category":"AWS","Title":"AWS Access Key ID","Match":"AWS_ACCESS_KEY_ID=****"}]}]}
EOF"#;

struct Setup {
    server: MockServer,
    scratch: tempfile::TempDir,
    bin: tempfile::TempDir,
}

impl Setup {
    async fn new() -> Self {
        Self {
            server: MockServer::start().await,
            scratch: tempfile::tempdir().unwrap(),
            bin: tempfile::tempdir().unwrap(),
        }
    }

    fn reference(&self, rest: &str) -> String {
        format!("{}/{rest}", self.server.address())
    }

    fn settings(&self, trivy_body: &str) -> Settings {
        Settings {
            scratch: self.scratch.path().to_owned(),
            trivy: trivy::Settings {
                program: fake_trivy(&self.bin, trivy_body).display().to_string(),
                cache: self.bin.path().display().to_string(),
                offline: true,
                timeout: Duration::from_secs(30),
            },
            allowed: Allowed::from_list("127.0.0.1"),
            keep_free_bytes: 0,
        }
    }

    fn job(reference: String, login: Option<Login>, limits: Limits, deadline: Duration) -> Job {
        Job {
            id: "0190f3a2-0000-7000-8000-000000000001".to_owned(),
            reference,
            login,
            platform: "linux/amd64".to_owned(),
            limits,
            deadline,
        }
    }

    async fn run(&self, job: &Job, trivy_body: &str) -> (Ending, Told) {
        let told = Told::default();
        let ending = check::run(job, &self.settings(trivy_body), &told).await;
        assert!(
            std::fs::read_dir(self.scratch.path()).unwrap().next().is_none(),
            "the check's scratch folder is deleted whatever happened"
        );
        (ending, told)
    }
}

#[tokio::test]
async fn a_program_deep_in_a_lower_layer_is_hashed_and_everything_is_reported() {
    let setup = Setup::new().await;
    let listed = elf("a build on the test blocklist");
    let lower = gzip(&Tar::default().file("opt/tools/deep/inside/miner", &listed).file("bin/sh", &elf("dash")).done());
    let upper = gzip(&Tar::default().file("opt/tools/deep/inside/.wh.miner", b"").file("app/.env", b"AWS=1").done());
    let manifest = Image::of(vec![lower, upper]).serve(&setup.server, "test/img", "v1").await;
    let job = Setup::job(setup.reference("test/img:v1"), None, ROOMY, Duration::from_secs(60));
    let (ending, told) = setup.run(&job, REPORT).await;
    assert_eq!(ending, Ending::Checked, "{:?} {:?}", told.refused.lock().unwrap(), told.failed.lock().unwrap());
    let result = told.result.lock().unwrap().clone().unwrap();
    assert_eq!(result["digest"], sha256(&manifest), "pinned to the manifest's own digest");
    assert_eq!(told.resolved.lock().unwrap()[0]["digest"], sha256(&manifest));
    let executables = result["executables"].as_array().unwrap();
    let deep =
        executables.iter().find(|e| e["path"] == "opt/tools/deep/inside/miner").expect("the deleted program is hashed");
    assert_eq!(format!("sha256:{}", deep["sha256"].as_str().unwrap()), sha256(&listed));
    assert_eq!(deep["layer"], 0);
    assert_eq!(result["hasShell"], true);
    assert_eq!(result["config"]["cudaVersion"], "12.4.1");
    assert_eq!(result["config"]["workingDir"], "/app");
    assert_eq!(result["findings"]["critical"], 1);
    assert_eq!(result["findings"]["secrets"][0]["path"], "/app/.env");
    assert!(!result.to_string().contains("AWS_ACCESS_KEY_ID="), "a secret's text is never reported");
}

#[tokio::test]
async fn an_image_without_the_platform_is_reported_with_the_platforms_it_has_and_nothing_is_downloaded() {
    let setup = Setup::new().await;
    let child = serde_json::to_vec(&json!({"schemaVersion": 2})).unwrap();
    let index = serde_json::to_vec(&json!({
        "schemaVersion": 2, "mediaType": INDEX,
        "manifests": [{ "mediaType": MANIFEST, "digest": sha256(&child), "size": child.len(),
                        "platform": { "architecture": "arm64", "os": "linux", "variant": "v8" } }]
    }))
    .unwrap();
    Mock::given(method("GET"))
        .and(path("/v2/test/arm/manifests/latest"))
        .respond_with(ResponseTemplate::new(200).insert_header("Content-Type", INDEX).set_body_bytes(index))
        .mount(&setup.server)
        .await;
    let job = Setup::job(setup.reference("test/arm"), None, ROOMY, Duration::from_secs(60));
    let (ending, told) = setup.run(&job, REPORT).await;
    assert_eq!(ending, Ending::Stopped);
    assert_eq!(told.resolved.lock().unwrap()[0]["platforms"], json!(["linux/arm64/v8"]));
    assert_eq!(told.resolved.lock().unwrap()[0]["digest"], json!(null));
}

#[tokio::test]
async fn a_layer_that_unpacks_far_past_its_size_is_refused() {
    let setup = Setup::new().await;
    let bomb = gzip(&Tar::default().file("zeroes", &vec![0_u8; 48 << 20]).done());
    assert!(bomb.len() < 1 << 20);
    Image::of(vec![bomb]).serve(&setup.server, "test/bomb", "latest").await;
    let limits = Limits { layer_bytes: 4 << 20, image_bytes: 1 << 30 };
    let (ending, told) =
        setup.run(&Setup::job(setup.reference("test/bomb"), None, limits, Duration::from_secs(60)), REPORT).await;
    assert_eq!(ending, Ending::Refused("too_large_unpacked"));
    assert!(told.refused.lock().unwrap().as_ref().unwrap().1.contains("too large once unpacked"));
}

#[tokio::test]
async fn a_path_with_dot_dot_and_links_pointing_outside_are_refused() {
    for (name, tar) in [
        ("dotdot", Tar::default().file("app/../../etc/cron.d/x", b"x").done()),
        ("symlink", Tar::default().entry("app/escape", b'2', "../../../host").done()),
        ("hardlink", Tar::default().entry("app/hard", b'1', "../outside").done()),
    ] {
        let setup = Setup::new().await;
        Image::of(vec![gzip(&tar)]).serve(&setup.server, &format!("test/{name}"), "latest").await;
        let job = Setup::job(setup.reference(&format!("test/{name}")), None, ROOMY, Duration::from_secs(60));
        let (ending, _) = setup.run(&job, REPORT).await;
        assert_eq!(ending, Ending::Refused("unsafe_path"), "{name}");
    }
}

#[tokio::test]
async fn a_layer_that_isnt_what_its_manifest_says_is_refused() {
    let setup = Setup::new().await;
    let honest = gzip(&Tar::default().file("a", b"a").done());
    let config = serde_json::to_vec(&Image::of(vec![]).config).unwrap();
    let manifest = serde_json::to_vec(&json!({
        "schemaVersion": 2, "mediaType": MANIFEST,
        "config": { "mediaType": "application/vnd.oci.image.config.v1+json", "digest": sha256(&config), "size": config.len() },
        "layers": [{ "mediaType": GZIP, "digest": sha256(&honest), "size": honest.len() }],
    }))
    .unwrap();
    Mock::given(method("GET"))
        .and(path("/v2/test/swap/manifests/latest"))
        .respond_with(ResponseTemplate::new(200).insert_header("Content-Type", MANIFEST).set_body_bytes(manifest))
        .mount(&setup.server)
        .await;
    Mock::given(method("GET"))
        .and(path(format!("/v2/test/swap/blobs/{}", sha256(&config))))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(config))
        .mount(&setup.server)
        .await;
    let mut other = honest.clone();
    *other.last_mut().unwrap() ^= 1;
    Mock::given(method("GET"))
        .and(path(format!("/v2/test/swap/blobs/{}", sha256(&honest))))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(other))
        .mount(&setup.server)
        .await;
    let (ending, told) =
        setup.run(&Setup::job(setup.reference("test/swap"), None, ROOMY, Duration::from_secs(60)), REPORT).await;
    assert_eq!(ending, Ending::Refused("pull_failed"));
    assert!(told.refused.lock().unwrap().as_ref().unwrap().1.contains("isn't the one its manifest names"));
}

#[tokio::test]
async fn a_private_registry_is_read_with_the_renters_token_and_refused_without_it() {
    let setup = Setup::new().await;
    let layer = gzip(&Tar::default().file("usr/bin/job", &elf("job")).done());
    // The registry's token service wants the renter's user name and token; the registry wants the bearer token it gives.
    let basic = format!("Basic {}", base64_of("renter:ghp_readonly_token"));
    Mock::given(method("GET"))
        .and(path("/token"))
        .and(header("Authorization", basic.as_str()))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({ "token": "issued-bearer" })))
        .mount(&setup.server)
        .await;
    Mock::given(method("GET")).and(path("/token")).respond_with(ResponseTemplate::new(401)).mount(&setup.server).await;
    let challenge = format!(r#"Bearer realm="http://{}/token",service="test""#, setup.server.address());
    Image::of(vec![layer]).serve_with(&setup.server, "me/private", "v2", Some("Bearer issued-bearer")).await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(401).insert_header("WWW-Authenticate", challenge.as_str()))
        .mount(&setup.server)
        .await;

    let reference = setup.reference("me/private:v2");
    let (ending, told) = setup.run(&Setup::job(reference.clone(), None, ROOMY, Duration::from_secs(60)), REPORT).await;
    assert_eq!(ending, Ending::Refused("pull_failed"));
    assert!(told.refused.lock().unwrap().as_ref().unwrap().1.contains("read-only token"), "says what to give");

    let wrong = Login { username: "renter".to_owned(), token: "not-it".to_owned() };
    let (ending, told) =
        setup.run(&Setup::job(reference.clone(), Some(wrong), ROOMY, Duration::from_secs(60)), REPORT).await;
    assert_eq!(ending, Ending::Refused("pull_failed"));
    assert!(told.refused.lock().unwrap().as_ref().unwrap().1.contains("refused the user name and token"));

    let login = Login { username: "renter".to_owned(), token: "ghp_readonly_token".to_owned() };
    let (ending, told) = setup.run(&Setup::job(reference, Some(login), ROOMY, Duration::from_secs(60)), REPORT).await;
    assert_eq!(ending, Ending::Checked, "{:?} {:?}", told.refused.lock().unwrap(), told.failed.lock().unwrap());
    let reported = format!("{:?}", told.result.lock().unwrap());
    assert!(
        !reported.contains("ghp_readonly_token") && !reported.contains("issued-bearer"),
        "no credential is reported"
    );
}

fn base64_of(text: &str) -> String {
    use base64::Engine as _;
    base64::engine::general_purpose::STANDARD.encode(text)
}

#[tokio::test]
async fn a_scan_that_fails_or_runs_past_its_deadline_is_not_checked_never_a_pass() {
    let setup = Setup::new().await;
    Image::of(vec![gzip(&Tar::default().file("a", b"a").done())]).serve(&setup.server, "test/slow", "latest").await;
    let job = Setup::job(setup.reference("test/slow"), None, ROOMY, Duration::from_secs(60));
    let (ending, told) = setup.run(&job, "echo 'FATAL database broken' >&2; exit 1").await;
    assert_eq!(ending, Ending::Failed);
    assert!(told.failed.lock().unwrap().as_ref().unwrap().contains("Trivy failed"));
    assert!(told.result.lock().unwrap().is_none());

    let quick = Setup::job(setup.reference("test/slow"), None, ROOMY, Duration::from_secs(2));
    let (ending, told) = setup.run(&quick, "sleep 20").await;
    assert_eq!(ending, Ending::Failed);
    assert!(told.failed.lock().unwrap().as_ref().unwrap().contains("ran past"));
    assert!(told.result.lock().unwrap().is_none());
}

#[tokio::test]
async fn a_registry_on_a_private_address_is_never_contacted_unless_allowed() {
    let setup = Setup::new().await;
    Image::of(vec![gzip(&Tar::default().file("a", b"a").done())]).serve(&setup.server, "test/img", "latest").await;
    let mut settings = setup.settings(REPORT);
    settings.allowed = Allowed::default();
    let told = Told::default();
    let ending =
        check::run(&Setup::job(setup.reference("test/img"), None, ROOMY, Duration::from_secs(60)), &settings, &told)
            .await;
    assert_eq!(ending, Ending::Refused("pull_failed"));
    assert!(setup.server.received_requests().await.unwrap().is_empty(), "not one request reached it");
}
