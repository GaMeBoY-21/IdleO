// The IdleO window's page (ADR 0061): Rust sends the whole state with window.idleo.receive(...) (tray/src/window.rs), and the
// page sends the host's choices back with window.ipc.postMessage(JSON). It draws and nothing else: every figure, time and
// amount arrives already written. No network, no storage: everything is in the program.
(function () {
  "use strict";
  var TABS = ["now", "schedule", "limits", "earnings", "settings"];
  var DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
  var DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
  var panel = document.getElementById("panel");
  var confirmBox = document.getElementById("confirm");
  var state = null;
  var tab = "now";
  var choosing = false;
  var editing = false;
  var waiting = false;
  var hoursError = null;
  var lastHeadline = "";

  function send(message) {
    window.ipc.postMessage(JSON.stringify(message));
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      var value = attrs[key];
      if (value === null || value === undefined || value === false) return;
      if (key === "text") node.textContent = value;
      else if (key === "disabled" || key === "hidden") node[key] = true;
      else node.setAttribute(key, value === true ? "" : value);
    });
    (children || []).forEach(function (child) { if (child) node.appendChild(child); });
    return node;
  }

  function clone(value) { return JSON.parse(JSON.stringify(value)); }

  var connected = function () { return !!(state && state.connected); };

  function save(change) {
    if (!connected()) return;
    var next = clone(state.settings);
    change(next);
    state.settings = next;
    send({ type: "set_settings", settings: next });
    render();
  }

  // --- widgets -------------------------------------------------------------------------------------------------------

  var uid = 0;
  function nextId(prefix) { uid += 1; return prefix + uid; }

  function toggle(key, label, hint, on, change) {
    var id = nextId("t");
    var button = el("button", { type: "button", role: "switch", class: "switch", "aria-checked": on ? "true" : "false", "aria-labelledby": id, "data-key": key, disabled: !connected() });
    button.addEventListener("click", function () { change(!on); });
    var what = el("div", { class: "what" }, [el("span", { id: id, text: label }), hint ? el("span", { class: "small muted", text: hint }) : null]);
    return el("div", { class: "line" }, [what, button]);
  }

  function select(key, label, options, value, change) {
    var id = nextId("s");
    var control = el("select", { id: id, "data-key": key, disabled: !connected() }, options.map(function (option) {
      return el("option", { value: String(option[0]), text: option[1], selected: String(option[0]) === String(value) });
    }));
    control.value = String(value);
    control.addEventListener("change", function () { editing = false; change(control.value); });
    control.addEventListener("focus", function () { editing = true; });
    control.addEventListener("blur", function () { editing = false; flush(); });
    return el("div", { class: "field" }, [el("label", { for: id, text: label }), control]);
  }

  function range(key, label, min, max, step, value, unit, change) {
    var id = nextId("r");
    var output = el("span", { class: "value", text: value + unit, "aria-hidden": "true" });
    var control = el("input", { type: "range", id: id, min: min, max: max, step: step, value: value, "data-key": key, "aria-valuetext": value + unit, disabled: !connected() });
    control.addEventListener("input", function () {
      editing = true;
      output.textContent = control.value + unit;
      control.setAttribute("aria-valuetext", control.value + unit);
    });
    control.addEventListener("change", function () { editing = false; change(Number(control.value)); });
    control.addEventListener("blur", function () { editing = false; flush(); });
    return el("div", { class: "field" }, [el("div", { class: "row between" }, [el("label", { for: id, text: label }), output]), control]);
  }

  function radios(key, options, value, change) {
    var group = el("div", { class: "stack", role: "radiogroup" }, options.map(function (option, index) {
      var on = option[0] === value;
      var button = el("button", { type: "button", role: "radio", class: "radio", "aria-checked": on ? "true" : "false", tabindex: on || (!value && index === 0) ? "0" : "-1", "data-key": key + "-" + option[0], disabled: !connected() }, [
        el("span", { class: "mark", "aria-hidden": "true" }),
        el("span", { class: "stack" }, [el("span", { text: option[1] }), el("span", { class: "small muted", text: option[2] })])
      ]);
      button.addEventListener("click", function () { change(option[0]); });
      return button;
    }));
    group.addEventListener("keydown", function (event) {
      var step = event.key === "ArrowDown" || event.key === "ArrowRight" ? 1 : event.key === "ArrowUp" || event.key === "ArrowLeft" ? -1 : 0;
      if (!step) return;
      event.preventDefault();
      var items = Array.prototype.slice.call(group.querySelectorAll("[role=radio]"));
      var at = items.indexOf(document.activeElement);
      var target = items[(at + step + items.length) % items.length];
      target.focus();
      target.click();
    });
    return group;
  }

  function tile(children, kind) { return el("section", { class: "tile" + (kind ? " " + kind : "") }, children); }

  function figure(label, value) {
    return el("div", { class: "tile" }, [el("p", { class: "small muted", text: label }), el("p", { class: "figure", text: value || "—" })]);
  }

  // --- tabs ----------------------------------------------------------------------------------------------------------

  function nowTab() {
    var s = state;
    var glyph = { volt: "✓", grey: "–", amber: "…", red: "!" }[s.colour] || "";
    var nodes = [el("div", { class: "state" }, [
      el("span", { class: "badge " + s.colour, "aria-hidden": "true", text: s.state === "running" ? "▶" : s.state === "paused" ? "‖" : glyph }),
      el("div", { class: "stack" }, [el("h1", { text: s.headline }), s.detail ? el("p", { class: "muted", text: s.detail }) : null])
    ])];
    if (s.problem) {
      var fix = s.problem.action ? el("button", { type: "button", class: "primary", "data-action": "fix", "data-fix": s.problem.action, text: s.problem.button }) : null;
      nodes.push(tile([el("h3", { text: s.problem.title }), el("p", { class: "muted small", text: s.problem.fix }), fix ? el("div", { class: "row", style: "margin-top:12px" }, [fix]) : null], "danger"));
    }
    nodes.push(pauseSwitch());
    if (s.live) {
      var bits = [];
      if (s.live.temp) bits.push("GPU " + s.live.temp);
      if (s.live.util) bits.push("in use " + s.live.util);
      nodes.push(tile([el("p", { class: "small muted", text: "While the job runs" }), el("p", { class: "value", text: bits.join(" · ") || "—" })]));
    }
    var e = s.earnings;
    nodes.push(el("div", { class: "figures" }, [figure("Today", e.today), figure("This week", e.week), figure("This month", e.month)]));
    if (e.next_payout) nodes.push(tile([el("p", { class: "small muted", text: "Next payout" }), el("p", { class: "value", text: e.next_payout })]));
    if (e.last_updated) nodes.push(el("p", { class: "small muted", text: e.last_updated }));
    return nodes;
  }

  function pauseSwitch() {
    var s = state;
    var id = nextId("p");
    var on = s.paused;
    var button = el("button", { type: "button", role: "switch", class: "switch", "aria-checked": on ? "true" : "false", "aria-labelledby": id, "data-key": "pause", disabled: !connected() });
    button.addEventListener("click", function () {
      if (on) { choosing = false; send({ type: "resume" }); } else { choosing = !choosing; render(); }
    });
    var hint = on ? s.headline : "Stops renters' jobs from starting. A running job saves its progress first.";
    var rows = [el("div", { class: "line" }, [el("div", { class: "what" }, [el("span", { id: id, text: "Pause hosting" }), el("span", { class: "small muted", text: hint })]), button])];
    if (choosing && !on) {
      rows.push(el("div", { class: "choices", role: "group", "aria-label": "Pause hosting" }, s.pause_choices.map(function (choice) {
        var b = el("button", { type: "button", class: "secondary", "data-key": "choice-" + choice.id, text: choice.label });
        b.addEventListener("click", function () { choosing = false; send({ type: "pause", choice: choice.id }); });
        return b;
      })));
    }
    return tile(rows);
  }

  function minutes(time, isTo) {
    var parts = String(time).split(":");
    var total = Number(parts[0]) * 60 + Number(parts[1]);
    return isTo && total === 0 ? 1440 : total;
  }
  function clock(min) {
    var m = min % 1440;
    return ("0" + Math.floor(m / 60)).slice(-2) + ":" + ("0" + (m % 60)).slice(-2);
  }

  function scheduleTab() {
    var s = state.settings;
    var nodes = [radios("mode", [
      ["when_idle", "Only when idle", "Renters get the laptop whenever you're away. This is how IdleO has always worked."],
      ["hours", "Set hours", "Only inside the hours you choose below, and only while you're away."],
      ["always", "Always", "Even while you're using it. Games, heat and battery still pause it."]
    ], s.schedule.mode, function (mode) { hoursError = null; save(function (next) { next.schedule.mode = mode; }); })];
    if (s.schedule.mode === "hours") {
      var rows = [];
      DAYS.forEach(function (day, index) {
        var d = s.schedule.days[index];
        var sw = el("button", { type: "button", role: "switch", class: "switch", "aria-checked": d.on ? "true" : "false", "aria-label": DAY_NAMES[index], "data-key": "day-" + index, disabled: !connected() });
        sw.addEventListener("click", function () { save(function (next) { next.schedule.days[index].on = !d.on; }); });
        var from = el("input", { type: "time", value: clock(d.from), "aria-label": DAY_NAMES[index] + " from", "data-key": "from-" + index, disabled: !connected() || !d.on });
        var to = el("input", { type: "time", value: clock(d.to), "aria-label": DAY_NAMES[index] + " to", "data-key": "to-" + index, disabled: !connected() || !d.on });
        function changed() {
          editing = false;
          var f = minutes(from.value, false);
          var t = minutes(to.value, true);
          if (f === t || isNaN(f) || isNaN(t)) { hoursError = DAY_NAMES[index] + ": the hours must start and end at different times."; render(); return; }
          hoursError = null;
          save(function (next) { next.schedule.days[index].from = f; next.schedule.days[index].to = t; });
        }
        [from, to].forEach(function (input) {
          input.addEventListener("change", changed);
          input.addEventListener("focus", function () { editing = true; });
          input.addEventListener("blur", function () { editing = false; flush(); });
        });
        rows.push(el("span", { text: day, class: d.on ? "" : "muted" }), sw, el("div", { class: "times" }, [from, el("span", { class: "muted", text: "to", "aria-hidden": "true" }), to]));
      });
      nodes.push(tile([el("div", { class: "days" }, rows), el("p", { class: "small muted", style: "margin-top:10px", text: "A window that ends before it starts runs past midnight into the next day." }), hoursError ? el("p", { class: "small", role: "alert", style: "color:var(--danger);margin-top:6px", text: hoursError }) : null]));
    }
    nodes.push(tile([
      toggle("mains", "Only on mains power", "Pauses within seconds of unplugging.", s.auto_pause.unplugged, function (on) { save(function (next) { next.auto_pause.unplugged = on; }); }),
      toggle("games", "Pause when a full-screen game or video is running", null, s.auto_pause.games, function (on) { save(function (next) { next.auto_pause.games = on; }); }),
      toggle("metered", "Not on metered connections", "No jobs and no job image downloads on your data allowance.", s.not_on_metered, function (on) { save(function (next) { next.not_on_metered = on; }); })
    ]));
    nodes.push(select("again", "Start again after you stop using the laptop", [[5, "5 minutes"], [10, "10 minutes"], [20, "20 minutes"], [30, "30 minutes"], [60, "1 hour"]], s.auto_pause.start_again_after_minutes, function (value) { save(function (next) { next.auto_pause.start_again_after_minutes = Number(value); }); }));
    return nodes;
  }

  function limitsTab() {
    var l = state.settings.limits;
    var memory = state.memory_gb ? "About " + state.memory_gb + " GB of this laptop's memory." : null;
    return [
      tile([range("temp", "Highest GPU temperature", 60, 90, 1, l.max_gpu_temp_c, " °C", function (value) { save(function (next) { next.limits.max_gpu_temp_c = value; }); }),
        el("p", { class: "small muted", style: "margin-top:8px", text: "Hosting pauses above this, and carries on by itself once the GPU is 5 °C cooler." })]),
      tile([range("disk", "Most disk the Linux space may use", 20, 500, 5, l.max_disk_gb, " GB", function (value) { save(function (next) { next.limits.max_disk_gb = value; }); }),
        el("p", { class: "small muted", style: "margin-top:8px", text: "IdleO cleans up old job images by itself and warns you before the disk is full." })]),
      tile([select("memory", "Memory a job may take", [[25, "25% of memory"], [50, "50% of memory"], [75, "75% of memory"], [90, "90% of memory"]], l.max_memory_percent, function (value) { save(function (next) { next.limits.max_memory_percent = Number(value); }); }),
        memory ? el("p", { class: "small muted", style: "margin-top:8px", text: memory }) : null])
    ];
  }

  function earningsTab() {
    var e = state.earnings;
    var nodes = [el("div", { class: "figures" }, [figure("Today", e.today), figure("This week", e.week), figure("This month", e.month)])];
    if (e.next_payout) nodes.push(tile([el("p", { class: "small muted", text: "Next payout" }), el("p", { class: "value", text: e.next_payout })]));
    if (e.last_payout) nodes.push(tile([el("p", { class: "small muted", text: "Last payout, before any deductions" }), el("p", { class: "value", text: e.last_payout })]));
    var list = el("div", { class: "list" }, e.recent.length ? e.recent.map(function (job) {
      return el("div", { class: "line" }, [el("div", { class: "what" }, [el("span", { text: job.when }), el("span", { class: "small muted", text: job.length })]), el("span", { class: "value", text: job.earned })]);
    }) : [el("p", { class: "muted", text: "No jobs yet. They show here when they finish." })]);
    nodes.push(tile([el("h3", { text: "Recent jobs", style: "margin-bottom:6px" }), list]));
    if (e.last_updated) nodes.push(el("p", { class: "small muted", text: e.last_updated }));
    if (state.has_host_site) nodes.push(el("div", { class: "row" }, [el("button", { type: "button", class: "secondary", "data-action": "open_host_site", text: "See everything on the host site" })]));
    return nodes;
  }

  function settingsTab() {
    var s = state.settings;
    var n = s.notifications;
    function note(key, label) {
      return toggle("n-" + key, label, null, n[key], function (on) { save(function (next) { next.notifications[key] = on; }); });
    }
    var nodes = [
      tile([toggle("autostart", "Start with Windows", "IdleO opens by itself when you sign in.", s.start_with_windows, function (on) { save(function (next) { next.start_with_windows = on; }); })]),
      tile([el("h3", { text: "Notifications", style: "margin-bottom:6px" }),
        note("live", "You're live"), note("first_job", "Your first job"), note("paused_when_back", "A job paused because you came back"),
        note("payout", "A payout was sent"), note("update", "An update was installed"), note("problems", "A problem that needs you")]),
      tile([select("theme", "Theme", [["system", "Follow Windows"], ["light", "Light"], ["dark", "Dark"]], s.theme, function (value) { save(function (next) { next.theme = value; }); })]),
      tile([el("h3", { text: "Updates" }), el("p", { class: "muted small", text: state.channel }),
        el("p", { class: "small", style: "margin-top:6px", text: state.updated_to ? "Updated to v" + state.updated_to : "Version " + state.version })]),
      el("div", { class: "row" }, [el("button", { type: "button", class: "secondary", "data-action": "send_diagnostics", text: "Send diagnostics" })]),
      el("p", { class: "small muted", text: "Saves a zip with your logs and settings. Your name, your computer's name and your keys are taken out first. Send it to support if they ask." }),
      el("div", { class: "row" }, [el("button", { type: "button", class: "danger", "data-action": "remove_idleo", text: "Remove IdleO" })])
    ];
    return nodes;
  }

  var RENDERERS = { now: nowTab, schedule: scheduleTab, limits: limitsTab, earnings: earningsTab, settings: settingsTab };

  // --- rendering -----------------------------------------------------------------------------------------------------

  function applyTheme() {
    var theme = state && state.theme;
    if (theme === "light" || theme === "dark") document.documentElement.setAttribute("data-theme", theme);
    else document.documentElement.removeAttribute("data-theme");
  }

  function renderTabs() {
    TABS.forEach(function (name) {
      var button = document.getElementById("tab-" + name);
      var on = name === tab;
      button.setAttribute("aria-selected", on ? "true" : "false");
      button.tabIndex = on ? 0 : -1;
    });
    panel.setAttribute("aria-labelledby", "tab-" + tab);
  }

  function render() {
    if (!state) return;
    waiting = false;
    var focused = document.activeElement && document.activeElement.getAttribute && document.activeElement.getAttribute("data-key");
    var scroll = panel.scrollTop;
    uid = 0;
    var conn = document.getElementById("conn");
    conn.hidden = connected();
    var children = [];
    if (!connected()) children.push(el("p", { class: "note", role: "status", text: "IdleO is starting. You can change settings once it's running." }));
    RENDERERS[tab]().forEach(function (node) { children.push(node); });
    panel.replaceChildren.apply(panel, children);
    panel.scrollTop = scroll;
    renderTabs();
    if (focused) {
      var again = panel.querySelector('[data-key="' + focused + '"]');
      if (again && !again.disabled) again.focus();
    }
    if (state.headline !== lastHeadline) {
      lastHeadline = state.headline;
      document.getElementById("announce").textContent = state.headline;
    }
  }

  function flush() { if (waiting) render(); }

  // --- from Rust -----------------------------------------------------------------------------------------------------

  window.idleo = {
    receive: function (next) {
      state = next;
      applyTheme();
      if (editing) { waiting = true; return; }
      render();
    },
    showTab: function (name) {
      if (TABS.indexOf(name) < 0) name = "now";
      tab = name;
      choosing = false;
      render();
      var button = document.getElementById("tab-" + name);
      if (button) button.focus();
    },
    dev: function (text) { document.body.appendChild(el("p", { class: "dev", text: text })); }
  };

  // --- from the person -----------------------------------------------------------------------------------------------

  document.querySelector(".tabs").addEventListener("click", function (event) {
    var button = event.target.closest("[data-tab]");
    if (button) window.idleo.showTab(button.getAttribute("data-tab"));
  });
  document.querySelector(".tabs").addEventListener("keydown", function (event) {
    var step = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
    var at = TABS.indexOf(tab);
    if (event.key === "Home") at = 0;
    else if (event.key === "End") at = TABS.length - 1;
    else if (step) at = (at + step + TABS.length) % TABS.length;
    else return;
    event.preventDefault();
    window.idleo.showTab(TABS[at]);
  });
  document.addEventListener("click", function (event) {
    var target = event.target.closest("[data-action]");
    if (!target) return;
    var action = target.getAttribute("data-action");
    if (action === "fix") send({ type: "fix", action: target.getAttribute("data-fix") });
    else if (action === "open_host_site") send({ type: "open_host_site" });
    else if (action === "send_diagnostics") send({ type: "send_diagnostics" });
    else if (action === "remove_idleo") { confirmBox.hidden = false; confirmBox.querySelector("[data-action=cancel_remove]").focus(); }
    else if (action === "cancel_remove") { confirmBox.hidden = true; var back = panel.querySelector('[data-action=remove_idleo]'); if (back) back.focus(); }
    else if (action === "confirm_remove") { confirmBox.hidden = true; send({ type: "remove_idleo" }); }
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && !confirmBox.hidden) confirmBox.hidden = true;
  });
  send({ type: "ready" });
})();
