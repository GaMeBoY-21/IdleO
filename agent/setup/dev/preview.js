// Development only: never built into the setup program (ui.rs includes assets/ only). The screenshots script
// (web/e2e/screenshots.ts, `pnpm screenshots windows-setup`) puts this in front of assets/app.js in a copy of the page
// and opens it in a browser; open that copy yourself to click through the screens: the arrow keys step, and #<name>
// in the address picks one. A fake IPC stands in for Rust and shows what the page would send. The messages are the
// ones agent/setup/src/ipc.rs sends (ToPage, as JSON), with invented values.
(function () {
  "use strict";
  var steps = function (states, detail) {
    var labels = [
      "Checking your laptop",
      "Turning on Windows' Linux support",
      "Downloading IdleO",
      "Installing IdleO",
      "Setting up the safe space for renters' jobs",
      "Testing the safe space",
      "Linking to your account",
      "Starting IdleO"
    ];
    return labels.map(function (label, index) {
      return { label: label, state: states[index], detail: detail && detail[index] ? detail[index] : null };
    });
  };
  var fix = function (problem, text, button, extra) {
    return Object.assign({ screen: "fix", problem: problem, text: text, button: button, rechecking: false, keep: false }, extra || {});
  };
  var screens = {
    "welcome": { screen: "welcome", already_set_up: false, dev_build: false, unfinished: false },
    "welcome-repair": { screen: "welcome", already_set_up: true, dev_build: false, unfinished: false },
    "welcome-unfinished": { screen: "welcome", already_set_up: false, dev_build: false, unfinished: true },
    "progress": {
      screen: "progress",
      steps: steps(["done", "done", "current", "waiting", "waiting", "waiting", "waiting", "waiting"], { 2: "Downloading · 120 of 310 MB" }),
      note: null
    },
    "progress-unplugged": {
      screen: "progress",
      steps: steps(["done", "done", "done", "done", "current", "waiting", "waiting", "waiting"]),
      note: "Plug your laptop in: IdleO pauses while it runs on battery."
    },
    "fix-windows": fix("windows_too_old", "This laptop needs Windows 10 22H2 or Windows 11 first.", "Open Windows Update"),
    "fix-driver": fix("driver_too_old", "Your graphics driver needs an update (2 min).", "Update driver", { rechecking: true }),
    "fix-virtualization": fix("virtualization_off", "One setting is off in your laptop's startup menu.", "Show me how"),
    "fix-no-rtx": fix("no_rtx_gpu", "This laptop's graphics card can't run renters' jobs yet.", "OK"),
    "fix-disk": fix("not_enough_disk", "Free up 23 GB to host.", "Open Storage settings"),
    "fix-didnt-pass": fix("something_didnt_pass", "Something didn't pass. We've been told; you don't need to do anything.", "OK"),
    "fix-cant-reach": fix("cant_reach_idleo", "We can't reach IdleO. Check you're online.", "Try again"),
    "fix-troubleshooting": fix("something_didnt_pass", "Something didn't pass. We've been told; you don't need to do anything.", "OK", { keep: true }),
    "approve": { screen: "approve", machine_name: "ASUS ROG Strix", url: "https://app.idleo.in/link/3f2a/", short_url: "app.idleo.in/link/3f2a/" },
    "link-failed": { screen: "link_failed", message: "The link ran out after 10 minutes. Try again to get a new one." },
    "restart": { screen: "restart" },
    "done-live": { screen: "done", pending: false, report_problem: null },
    "done-pending": { screen: "done", pending: true, report_problem: null },
    "stopped-kept": { screen: "stopped", kept: true, log: "C:\\Users\\asha\\AppData\\Local\\IdleO\\logs\\setup.log", report_problem: null },
    "stopped-removed": { screen: "stopped", kept: false, log: "C:\\Users\\asha\\AppData\\Local\\IdleO\\logs\\setup.log", report_problem: "no answer" },
    "confirm-close": { screen: "progress", steps: steps(["done", "done", "current", "waiting", "waiting", "waiting", "waiting", "waiting"], { 2: "Downloading · 120 of 310 MB" }), note: null, ask: true }
  };
  var names = Object.keys(screens);
  window.ipc = {
    postMessage: function (text) {
      if (window.console) console.log("to Rust:", text);
    }
  };
  window.idleoPreview = {
    names: names,
    show: function (name) {
      var screen = screens[name];
      if (!screen) return false;
      document.getElementById("confirm").hidden = true;
      window.idleo.receive(screen);
      if (screen.ask) window.idleo.askClose();
      if (screen.screen === "welcome" && name === "welcome-unfinished") window.idleo.dev("Development build");
      location.hash = name;
      return true;
    }
  };
  document.addEventListener("DOMContentLoaded", function () {
    var at = Math.max(0, names.indexOf(location.hash.slice(1)));
    window.idleoPreview.show(names[at]);
    document.addEventListener("keydown", function (event) {
      var step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
      if (step === 0) return;
      at = (at + step + names.length) % names.length;
      window.idleoPreview.show(names[at]);
    });
  });
})();
