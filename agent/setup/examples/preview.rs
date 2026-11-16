//! The browser preview (ADR 0061): every screen of the Windows app, on the Mac, in both themes, from the very pages and types the
//! programs use. One page with a list of screens: setup's (every step and every failure), the IdleO window's (each tab and each
//! state), and the tray's seven icons at every taskbar size.
//!
//! ```text
//! cargo run -p setup --example preview     # from agent/; prints where it wrote the page
//! open target/preview/index.html           # then click through; Alt+arrows step; the theme and scale are at the top
//! ```
//!
//! It writes three self-contained files to `target/preview/`: `index.html` (everything), and `setup.html` and `window.html`
//! (one program's page each, with the same fake channel, which the screenshots script opens). Nothing is fetched and nothing is
//! installed; the words in every screen are Rust's.

use std::collections::BTreeMap;
use std::path::PathBuf;

use idleo_release::channel::{
    AutoPause, Earnings, Live, PayoutNote, Problem as HostProblem, Reason, RecentJob, State, Status,
};
use idleo_release::channel::{LinkState, Prefetch, UpdatePhase};
use idleo_release::settings::{DayHours, HostSettings, Moment, ScheduleMode, Theme};
use idleo_setup::ipc::{Facts, Problem, StepView, ToPage};
use idleo_setup::steps::Step;
use idleo_tray::status::TrayState;
use serde_json::{Value, json};
use time::{OffsetDateTime, UtcOffset};

// --- setup ------------------------------------------------------------------------------------------------------------

fn steps(done: usize, detail: Option<&str>, percent: Option<u32>) -> Vec<StepView> {
    Step::ALL
        .iter()
        .enumerate()
        .map(|(index, step)| StepView {
            label: step.label().to_owned(),
            state: if index < done {
                "done"
            } else if index == done {
                "current"
            } else {
                "waiting"
            }
            .to_owned(),
            detail: (index == done).then(|| detail.map(str::to_owned)).flatten(),
            percent: (index == done).then_some(percent).flatten(),
        })
        .collect()
}

fn progress(done: usize, detail: Option<&str>, percent: Option<u32>) -> ToPage {
    let overall = idleo_setup::steps::overall_percent(&Step::ALL[..done], percent.unwrap_or(0));
    ToPage::Progress { steps: steps(done, detail, percent), note: None, overall }
}

fn fix(problem: Problem, keep: bool) -> ToPage {
    let facts = Facts {
        free_up_gb: 23,
        drive: "C:".into(),
        free_gb: 37,
        needed_gb: 60,
        file: "idleo-distro.tar.gz".into(),
        proxy: false,
        driver: "511.65".into(),
    };
    let (title, text) = problem.words(&facts);
    ToPage::Fix {
        problem,
        title,
        text,
        button: problem.button().to_owned(),
        rechecking: problem.rechecks(),
        keep,
        help: problem.help().map(str::to_owned),
    }
}

fn setup_screens() -> Vec<(String, Vec<Value>)> {
    let mut all: Vec<(String, Vec<ToPage>)> = vec![
        (
            "welcome".into(),
            vec![ToPage::Welcome { already_set_up: false, dev_build: false, unfinished: false, demo: false }],
        ),
        (
            "welcome-open-or-repair".into(),
            vec![ToPage::Welcome { already_set_up: true, dev_build: false, unfinished: false, demo: false }],
        ),
        (
            "welcome-unfinished".into(),
            vec![ToPage::Welcome { already_set_up: false, dev_build: false, unfinished: true, demo: false }],
        ),
        (
            "welcome-demo".into(),
            vec![ToPage::Welcome { already_set_up: false, dev_build: false, unfinished: false, demo: true }],
        ),
        ("step-1-checking".into(), vec![progress(0, Some("Checking Windows, the graphics card and the space"), None)]),
        ("step-2-linux-support".into(), vec![progress(1, Some("Windows will ask for permission"), None)]),
        ("step-3-download".into(), vec![progress(2, Some("Downloading · 120 of 310 MB · about 2 min left"), Some(39))]),
        ("step-3-connection-lost".into(), vec![progress(2, Some("Connection lost · trying again in 4 s"), Some(39))]),
        ("step-4-installing".into(), vec![progress(3, Some("Putting the programs in place"), None)]),
        ("step-5-safe-space".into(), vec![progress(4, Some("Setting up the sealed Linux space"), None)]),
        ("step-6-self-test".into(), vec![progress(5, Some("12 checks passed"), None)]),
        (
            "step-7-approve".into(),
            vec![ToPage::Approve {
                machine_name: "ASUS ROG Strix".into(),
                url: "https://app.idleo.in/link/3f2a/".into(),
                short_url: "app.idleo.in/link/3f2a/".into(),
            }],
        ),
        ("step-8-starting".into(), vec![progress(7, Some("Starting IdleO"), None)]),
        (
            "link-failed".into(),
            vec![ToPage::LinkFailed {
                message: "The link ran out after 10 minutes. Try again to get a new one.".into(),
            }],
        ),
        ("restart".into(), vec![ToPage::Restart]),
        ("done-live".into(), vec![ToPage::Done { pending: false, report_problem: None }]),
        ("done-pending".into(), vec![ToPage::Done { pending: true, report_problem: None }]),
        (
            "stopped-kept".into(),
            vec![ToPage::Stopped {
                kept: true,
                log: r"C:\Users\asha\AppData\Local\IdleO\logs\setup.log".into(),
                report_problem: None,
            }],
        ),
        (
            "stopped-removed".into(),
            vec![ToPage::Stopped {
                kept: false,
                log: r"C:\Users\asha\AppData\Local\IdleO\logs\setup.log".into(),
                report_problem: Some("no answer".into()),
            }],
        ),
        ("fix-keep-for-troubleshooting".into(), vec![fix(Problem::SomethingDidntPass, true)]),
        (
            "fix-diagnostics-saved".into(),
            vec![
                fix(Problem::SomethingDidntPass, false),
                ToPage::DiagnosticsSaved {
                    path: r"C:\Users\asha\Downloads\IdleO-diagnostics-20261116-103000.zip".into(),
                },
            ],
        ),
    ];
    for problem in Problem::ALL {
        all.push((format!("fix-{}", problem.name().replace('_', "-")), vec![fix(problem, false)]));
    }
    let mut ordered: Vec<(String, Vec<Value>)> = all
        .into_iter()
        .map(|(name, screens)| (name, screens.iter().map(|screen| serde_json::to_value(screen).unwrap()).collect()))
        .collect();
    ordered.push((
        "confirm-stop".into(),
        vec![
            serde_json::to_value(progress(2, Some("Downloading · 120 of 310 MB"), Some(39))).unwrap(),
            json!({ "__ask_close": true }),
        ],
    ));
    ordered
}

// --- the IdleO window -------------------------------------------------------------------------------------------------

fn at() -> OffsetDateTime {
    OffsetDateTime::from_unix_timestamp(1_763_300_000).unwrap()
}

fn ready() -> Status {
    Status {
        state: State::Available,
        reason: None,
        resumes_at: None,
        paused_until: None,
        earned_today_paise: Some(1_240),
        earning_now_paise: None,
        session_running: false,
        link_state: LinkState::Active,
        server_reachable: true,
        update: UpdatePhase::Idle,
        runner_installed: true,
        dev_build: false,
        auto_pause: AutoPause::default(),
        app_url: None,
        host_url: Some("https://host.idleo.in".into()),
        prefetch: None::<Prefetch>,
        site_url: Some("https://idleo.in".into()),
        settings: HostSettings::default(),
        live: None,
        earnings: Some(earnings(2)),
        problem: None,
        server_seen_at: Some(at()),
        version: "0.2.0".into(),
        updated_to: None,
    }
}

fn earnings(heard_minutes_ago: i64) -> Earnings {
    let job = |hours_ago: i64, seconds, paise| RecentJob {
        ended_at: at() - time::Duration::hours(hours_ago),
        seconds,
        earned_paise: paise,
    };
    Earnings {
        today_paise: 1_240,
        week_paise: Some(9_900),
        month_paise: Some(4_100_000),
        recent: vec![job(1, 1_380, 420), job(20, 4_000, 1_050), job(30, 2_700, 760), job(100, 45, 3)],
        next_payout_on: Some("2026-11-23".into()),
        last_payout: Some(PayoutNote { sent_at: at() - time::Duration::days(6), amount_paise: 99_900 }),
        updated_at: at() - time::Duration::minutes(heard_minutes_ago),
    }
}

fn hours_settings() -> HostSettings {
    let mut settings = HostSettings::default();
    settings.schedule.mode = ScheduleMode::Hours;
    settings.schedule.days[5] = DayHours { on: false, from: 0, to: 60 };
    settings.schedule.days[6] = DayHours { on: true, from: 9 * 60, to: 17 * 60 };
    settings.not_on_metered = false;
    settings.limits.max_gpu_temp_c = 80;
    settings.theme = Theme::System;
    settings
}

fn window_screens() -> Vec<(String, Vec<Value>)> {
    let offset = UtcOffset::from_hms(5, 30, 0).unwrap();
    let paused_by = |reason| Status { state: State::AutoPaused, reason: Some(reason), ..ready() };
    let view = |status: Option<&Status>, crashing: bool| {
        serde_json::to_value(idleo_tray::window::view(status, at(), offset, crashing, Some(16_384))).unwrap()
    };
    let tab = |name: &str| json!({ "__tab": name });
    let running = Status {
        session_running: true,
        earning_now_paise: Some(4_200),
        live: Some(Live {
            gpu_temp_c: Some(71),
            gpu_util_pct: Some(98),
            job_started_at: Some(at() - time::Duration::minutes(23)),
        }),
        ..ready()
    };
    let schedule_closed = Status {
        resumes_at: Some(at() + time::Duration::hours(7)),
        settings: hours_settings(),
        ..paused_by(Reason::Schedule)
    };
    let _ = Moment::of(at(), offset);
    let entries: Vec<(&str, Value, &str)> = vec![
        ("now-ready", view(Some(&ready()), false), "now"),
        ("now-running-a-job", view(Some(&running), false), "now"),
        ("now-paused-by-you", view(Some(&Status { state: State::HostingOff, ..ready() }), false), "now"),
        (
            "now-paused-for-an-hour",
            view(
                Some(&Status { state: State::Paused, paused_until: Some(at() + time::Duration::hours(1)), ..ready() }),
                false,
            ),
            "now",
        ),
        ("now-paused-using-the-laptop", view(Some(&paused_by(Reason::KeyboardMouse)), false), "now"),
        ("now-paused-outside-hours", view(Some(&schedule_closed), false), "now"),
        ("now-paused-too-hot", view(Some(&paused_by(Reason::TooHot)), false), "now"),
        ("now-paused-on-battery", view(Some(&paused_by(Reason::Unplugged)), false), "now"),
        (
            "now-waiting-for-checks",
            view(Some(&Status { link_state: LinkState::PendingApproval, ..ready() }), false),
            "now",
        ),
        (
            "now-offline",
            view(Some(&Status { server_reachable: false, earnings: Some(earnings(95)), ..ready() }), false),
            "now",
        ),
        ("now-starting", view(None, false), "now"),
        (
            "now-problem-driver",
            view(Some(&Status { problem: Some(HostProblem::DriverTooOld), ..ready() }), false),
            "now",
        ),
        (
            "now-problem-disk",
            view(Some(&Status { problem: Some(HostProblem::DiskNearlyFull), ..ready() }), false),
            "now",
        ),
        ("now-keeps-crashing", view(None, true), "now"),
        ("now-update-ready", view(Some(&Status { update: UpdatePhase::Ready, ..ready() }), false), "now"),
        ("schedule-when-idle", view(Some(&ready()), false), "schedule"),
        ("schedule-set-hours", view(Some(&Status { settings: hours_settings(), ..ready() }), false), "schedule"),
        ("limits", view(Some(&Status { settings: hours_settings(), ..ready() }), false), "limits"),
        ("earnings", view(Some(&ready()), false), "earnings"),
        (
            "earnings-nothing-yet",
            view(Some(&Status { earnings: None, earned_today_paise: Some(0), ..ready() }), false),
            "earnings",
        ),
        ("settings", view(Some(&Status { updated_to: Some("0.2.0".into()), ..ready() }), false), "settings"),
    ];
    entries.into_iter().map(|(name, state, tab_name)| (name.to_owned(), vec![state, tab(tab_name)])).collect()
}

/// The screens as the shim wants them: by name.
fn by_name(screens: &[(String, Vec<Value>)]) -> BTreeMap<String, Vec<Value>> {
    screens.iter().cloned().collect()
}

/// What Rust would do with the window's asks, as far as the preview can show it.
const WINDOW_REACT: &str = r#"
window.__previewReact = function (ask) {
  var current = window.__previewCurrent;
  if (!current) return;
  if (ask.type === "set_settings") { current.settings = ask.settings; current.theme = ask.settings.theme; window.idleo.receive(current); }
  if (ask.type === "pause") { current.paused = true; current.headline = "Paused"; window.idleo.receive(current); }
  if (ask.type === "resume") { current.paused = false; current.headline = "Ready for renters"; window.idleo.receive(current); }
};
(function () {
  var receive = null;
  document.addEventListener("DOMContentLoaded", function () {
    receive = window.idleo.receive;
    window.idleo.receive = function (state) { window.__previewCurrent = state; receive(state); };
  });
})();
"#;

// --- the tray's icons -------------------------------------------------------------------------------------------------

fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFF_u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 == 1 { (crc >> 1) ^ 0xEDB8_8320 } else { crc >> 1 };
        }
    }
    !crc
}

fn adler32(bytes: &[u8]) -> u32 {
    let (mut a, mut b) = (1_u32, 0_u32);
    for byte in bytes {
        a = (a + u32::from(*byte)) % 65_521;
        b = (b + a) % 65_521;
    }
    (b << 16) | a
}

/// An RGBA picture as a PNG file, in stored blocks, so there is no dependency.
fn png(rgba: &[u8], side: usize) -> Vec<u8> {
    fn chunk(out: &mut Vec<u8>, kind: [u8; 4], data: &[u8]) {
        out.extend_from_slice(&u32::try_from(data.len()).unwrap().to_be_bytes());
        let mut body = kind.to_vec();
        body.extend_from_slice(data);
        out.extend_from_slice(&body);
        out.extend_from_slice(&crc32(&body).to_be_bytes());
    }
    let mut raw = Vec::with_capacity((side * 4 + 1) * side);
    for row in rgba.chunks(side * 4) {
        raw.push(0);
        raw.extend_from_slice(row);
    }
    let mut zlib = vec![0x78, 0x01];
    let blocks: Vec<&[u8]> = raw.chunks(65_535).collect();
    for (index, block) in blocks.iter().enumerate() {
        let length = u16::try_from(block.len()).unwrap();
        zlib.push(u8::from(index + 1 == blocks.len()));
        zlib.extend_from_slice(&length.to_le_bytes());
        zlib.extend_from_slice(&(!length).to_le_bytes());
        zlib.extend_from_slice(block);
    }
    zlib.extend_from_slice(&adler32(&raw).to_be_bytes());
    let mut header = Vec::new();
    header.extend_from_slice(&u32::try_from(side).unwrap().to_be_bytes());
    header.extend_from_slice(&u32::try_from(side).unwrap().to_be_bytes());
    header.extend_from_slice(&[8, 6, 0, 0, 0]);
    let mut out = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    chunk(&mut out, *b"IHDR", &header);
    chunk(&mut out, *b"IDAT", &zlib);
    chunk(&mut out, *b"IEND", &[]);
    out
}

/// A data address for an icon, so the page needs no files beside it.
fn icon_address(state: TrayState, size: u32) -> String {
    let pixels = idleo_tray::icon::rgba(state, size);
    format!("data:image/png;base64,{}", idleo_brand::base64(&png(&pixels, usize::try_from(size).unwrap())))
}

fn tray_gallery() -> String {
    let words = |state: TrayState| match state {
        TrayState::Ready => "Ready for renters",
        TrayState::Running => "Running a job",
        TrayState::Paused => "Paused",
        TrayState::WaitingForChecks => "Waiting for our checks",
        TrayState::Offline => "Offline",
        TrayState::Problem => "A problem",
        TrayState::UpdateReady => "An update is ready",
    };
    let mut html = String::from("<div class=\"gallery\">");
    for state in TrayState::ALL {
        html.push_str(&format!("<section><h3>{}</h3>", words(state)));
        for (taskbar, ground) in [("dark", "#202020"), ("light", "#f3f3f3")] {
            html.push_str(&format!(
                "<div class=\"taskbar\" style=\"background:{ground}\" title=\"{taskbar} taskbar\">"
            ));
            for (scale, size) in [(100, 16_u32), (125, 20), (150, 24), (200, 32)] {
                html.push_str(&format!(
                    "<figure><img alt=\"{} at {scale}%\" src=\"{}\" width=\"{}\" height=\"{}\"><figcaption>{scale}%</figcaption></figure>",
                    words(state),
                    icon_address(state, size),
                    size * 4,
                    size * 4
                ));
            }
            html.push_str("</div>");
        }
        html.push_str("</section>");
    }
    html.push_str("</div>");
    html
}

// --- the page ---------------------------------------------------------------------------------------------------------

fn shimmed(screens: &[(String, Vec<Value>)], extra: &str) -> String {
    format!(
        "window.__previewScreens = {};\n{extra}\n{}",
        serde_json::to_string(&by_name(screens)).unwrap(),
        idleo_brand::PREVIEW_SHIM
    )
}

const OUTER: &str = r#"<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>IdleO · Windows app preview</title>
<style>
  :root { color-scheme: light dark; font-family: system-ui, "Segoe UI", sans-serif; }
  body { margin: 0; display: grid; grid-template-columns: 270px 1fr; height: 100vh; background: #1b1c1e; color: #eee; }
  nav { overflow: auto; padding: 12px; border-right: 1px solid #333; background: #141516; }
  nav h2 { font-size: 12px; text-transform: uppercase; letter-spacing: 0.6px; color: #888; margin: 16px 8px 6px; }
  nav button { display: block; width: 100%; text-align: left; font: inherit; font-size: 13px; padding: 5px 8px; border: 0; border-radius: 6px; background: none; color: #ccc; cursor: pointer; }
  nav button:hover { background: #232426; }
  nav button[aria-current="true"] { background: #c8ff3d; color: #08090a; }
  main { display: flex; flex-direction: column; min-width: 0; }
  header { display: flex; gap: 16px; align-items: center; padding: 10px 16px; border-bottom: 1px solid #333; font-size: 13px; }
  header select { font: inherit; }
  .stage { flex: 1; overflow: auto; padding: 24px; display: flex; justify-content: center; align-items: flex-start; }
  iframe { border: 1px solid #444; border-radius: 8px; background: #fff; transform-origin: top center; }
  .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 20px; width: 100%; }
  .gallery section { background: #232426; border-radius: 10px; padding: 12px; }
  .gallery h3 { margin: 0 0 8px; font-size: 14px; }
  .taskbar { display: flex; gap: 16px; padding: 10px; border-radius: 6px; margin-bottom: 8px; align-items: flex-end; }
  .taskbar figure { margin: 0; text-align: center; }
  .taskbar img { image-rendering: pixelated; display: block; }
  .taskbar figcaption { font-size: 11px; margin-top: 4px; color: #999; }
  .hint { color: #888; font-size: 12px; }
</style></head>
<body>
<nav id="nav" aria-label="Screens"></nav>
<main>
  <header>
    <label>Theme <select id="theme"><option value="">Follow this browser</option><option value="dark">Dark</option><option value="light">Light</option></select></label>
    <label>Looks like <select id="scale"><option value="1">100%</option><option value="1.25">125%</option><option value="1.5">150%</option><option value="2">200%</option></select></label>
    <span class="hint">Alt + ← → steps through the list</span>
  </header>
  <div class="stage" id="stage"></div>
</main>
<script>
  var SECTIONS = __SECTIONS__;
  var DOCS = { setup: __SETUP__, window: __WINDOW__ };
  var GALLERY = __GALLERY__;
  var sizes = { setup: [720, 520], window: [400, 600] };
  var nav = document.getElementById("nav"), stage = document.getElementById("stage");
  var frames = {}, flat = [], at = 0;
  function frameFor(kind) {
    if (!frames[kind]) {
      var f = document.createElement("iframe");
      f.width = sizes[kind][0]; f.height = sizes[kind][1];
      f.title = kind === "setup" ? "The setup window" : "The IdleO window";
      f.srcdoc = DOCS[kind];
      frames[kind] = f;
    }
    return frames[kind];
  }
  function apply() {
    var theme = document.getElementById("theme").value, scale = Number(document.getElementById("scale").value);
    Object.keys(frames).forEach(function (kind) {
      var f = frames[kind];
      var root = f.contentDocument && f.contentDocument.documentElement;
      if (root) { if (theme) root.setAttribute("data-theme", theme); else root.removeAttribute("data-theme"); }
      f.style.transform = "scale(" + scale + ")";
      f.style.marginBottom = (sizes[kind][1] * (scale - 1)) + "px";
    });
  }
  function select(i) {
    at = (i + flat.length) % flat.length;
    var item = flat[at];
    Array.prototype.forEach.call(nav.querySelectorAll("button"), function (b, n) { b.setAttribute("aria-current", n === at ? "true" : "false"); });
    stage.replaceChildren();
    if (item.kind === "tray") { stage.innerHTML = GALLERY; return; }
    var f = frameFor(item.kind);
    stage.appendChild(f);
    var go = function () { f.contentWindow.idleoPreview.show(item.name); apply(); };
    if (f.contentDocument && f.contentDocument.readyState === "complete" && f.contentWindow.idleoPreview) go(); else f.addEventListener("load", function once() { f.removeEventListener("load", once); setTimeout(go, 50); });
  }
  SECTIONS.forEach(function (section) {
    var h = document.createElement("h2"); h.textContent = section.title; nav.appendChild(h);
    section.names.forEach(function (name) {
      var b = document.createElement("button"); b.type = "button"; b.textContent = name;
      var index = flat.length; flat.push({ kind: section.kind, name: name });
      b.addEventListener("click", function () { select(index); });
      nav.appendChild(b);
    });
  });
  document.getElementById("theme").addEventListener("change", apply);
  document.getElementById("scale").addEventListener("change", apply);
  document.addEventListener("keydown", function (e) { if (e.altKey && e.key === "ArrowRight") select(at + 1); if (e.altKey && e.key === "ArrowLeft") select(at - 1); });
  select(0);
</script></body></html>
"#;

fn main() {
    let setup = setup_screens();
    let window = window_screens();
    let setup_html = idleo_setup::page::page_with(&shimmed(&setup, ""));
    let window_html = idleo_tray::page::page_with(&shimmed(&window, WINDOW_REACT));
    let sections = json!([
        { "title": "Setup window · 720 × 520", "kind": "setup", "names": setup.iter().map(|(name, _)| name).collect::<Vec<_>>() },
        { "title": "IdleO window · 400 × 600", "kind": "window", "names": window.iter().map(|(name, _)| name).collect::<Vec<_>>() },
        { "title": "Tray icons", "kind": "tray", "names": ["every state, every taskbar size"] },
    ]);
    // Inside a script, `</script>` anywhere in a string would end it: the pages are full of them, so every `</` is escaped.
    let in_script = |json: String| json.replace("</", "<\\/");
    let index = OUTER
        .replace("__SECTIONS__", &in_script(sections.to_string()))
        .replace("__SETUP__", &in_script(serde_json::to_string(&setup_html).unwrap()))
        .replace("__WINDOW__", &in_script(serde_json::to_string(&window_html).unwrap()))
        .replace("__GALLERY__", &in_script(serde_json::to_string(&tray_gallery()).unwrap()));
    let folder = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap()).join("../target/preview");
    std::fs::create_dir_all(&folder).unwrap();
    std::fs::write(folder.join("index.html"), index).unwrap();
    std::fs::write(folder.join("setup.html"), setup_html).unwrap();
    std::fs::write(folder.join("window.html"), window_html).unwrap();
    let folder = folder.canonicalize().unwrap();
    println!("Wrote the preview to {}", folder.display());
    println!("Open it with:  open {}", folder.join("index.html").display());
}
