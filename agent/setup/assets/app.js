// The setup window's page (ADR 0033, ADR 0061): Rust sends a screen with window.idleo.receive(...), and the page sends the
// person's choices back with window.ipc.postMessage(JSON). No network, no storage: everything is in the program.
(function () {
  "use strict";
  var app = document.getElementById("app");
  var confirmBox = document.getElementById("confirm");
  var saved = document.getElementById("saved");
  var current = null;
  var demo = false;

  function send(type) {
    window.ipc.postMessage(JSON.stringify({ type: type }));
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      var value = attrs[key];
      if (value === null || value === undefined || value === false) return;
      if (key === "text") node.textContent = value;
      else node.setAttribute(key, value === true ? "" : value);
    });
    (children || []).forEach(function (child) { if (child) node.appendChild(child); });
    return node;
  }

  function button(text, action, kind) {
    return el("button", { type: "button", class: kind || "primary", "data-action": action, text: text });
  }

  function title(text) { return el("h1", { id: "title", text: text }); }

  function bar(percent, label) {
    return el("div", { class: "bar", role: "progressbar", "aria-valuemin": "0", "aria-valuemax": "100", "aria-valuenow": String(percent), "aria-label": label }, [el("span", { style: "width:" + percent + "%" })]);
  }

  // "Send diagnostics" is on every screen that is a failure.
  function diagnostics() { return button("Send diagnostics", "diagnostics", "quiet"); }

  var screens = {
    welcome: function (s) {
      demo = !!s.demo;
      var tiles = [
        ["1", "We check your laptop", "Graphics, Windows and space, in a minute or two."],
        ["2", "We set up a safe space for renters' jobs", "Sealed off from your files and your home network."],
        ["3", "You approve in your browser", "One click links this laptop to your account."]
      ];
      var heading = s.unfinished ? "Setup didn't finish on this laptop" : s.already_set_up ? "IdleO is already set up on this laptop" : "Earn with this laptop";
      var buttons = s.already_set_up
        ? [button("Open IdleO", "open_idleo"), button("Repair", "set_up", "secondary")]
        : [button(s.unfinished ? "Repair" : "Set up", "set_up")];
      var hint = s.unfinished ? "Carries on from the step that didn't pass." : s.already_set_up ? "Repair checks everything again. Your link stays." : "About 5 minutes. Renters never see your files.";
      return [
        title(heading),
        el("div", { class: "tiles" }, tiles.map(function (t) {
          return el("section", { class: "tile" }, [
            el("span", { class: "badge", "aria-hidden": "true", text: t[0] }),
            el("h2", { text: t[1] }),
            el("p", { class: "muted small", text: t[2] })
          ]);
        })),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, buttons.concat([el("p", { class: "muted small", text: hint })]))
      ];
    },
    progress: function (s) {
      var steps = el("ol", { class: "steps", "aria-label": "Setup steps" }, s.steps.map(function (step) {
        var mark = step.state === "done" ? "✓" : step.state === "failed" ? "!" : "";
        var label = el("span", { class: "label" }, [el("span", { text: step.label })]);
        if (step.detail) label.appendChild(el("span", { class: "detail", text: step.detail }));
        if (step.percent !== null && step.percent !== undefined) label.appendChild(bar(step.percent, step.label));
        return el("li", { class: step.state, "aria-current": step.state === "current" ? "step" : false }, [el("span", { class: "mark", "aria-hidden": "true", text: mark }), label]);
      }));
      var overall = s.overall || 0;
      var parts = [
        title("Setting up"),
        el("div", { class: "overall" }, [bar(overall, "Setup so far"), el("p", { class: "muted small num", text: overall + "% done" })]),
        el("section", { class: "tile grow" }, [steps])
      ];
      if (s.note) parts.push(el("p", { class: "note", text: s.note }));
      return parts;
    },
    fix: function (s) {
      var final = s.button === "OK";
      var action = s.problem === "cant_reach_idleo" || s.problem === "antivirus_blocked" ? "try_again" : "fix";
      var tone = final || s.problem === "cant_reach_idleo" || s.problem === "something_didnt_pass" ? "red" : "amber";
      var below = [];
      if (s.help) below.push(button(s.help, "help", "quiet"));
      below.push(diagnostics());
      if (s.keep) below.push(button("Keep it for troubleshooting", "keep", "quiet"));
      return [
        el("div", { class: "head" }, [el("span", { class: "icon " + tone, "aria-hidden": "true", text: final ? "×" : "!" }), title(s.title)]),
        el("p", { id: "text", class: "muted", text: s.text }),
        s.rechecking ? el("p", { class: "muted small", text: "We check again every few seconds, and carry on by ourselves." }) : null,
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button(s.button, action)]),
        el("div", { class: "below" }, below)
      ];
    },
    approve: function (s) {
      return [
        title("We opened your browser. Approve this laptop there."),
        el("section", { class: "tile" }, [el("p", { class: "muted small", text: "This laptop" }), el("p", { class: "machine", text: s.machine_name })]),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [
          button("Open again", "open_again", "secondary"),
          el("p", { class: "muted small", text: "Or open " + s.short_url + " on your phone" })
        ])
      ];
    },
    link_failed: function (s) {
      return [
        el("div", { class: "head" }, [el("span", { class: "icon amber", "aria-hidden": "true", text: "!" }), title(s.message)]),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button("Try again", "try_again")]),
        el("div", { class: "below" }, [diagnostics()])
      ];
    },
    restart: function () {
      return [
        title("Windows needs one restart to finish."),
        el("p", { id: "text", class: "muted", text: "Setup carries on by itself after you sign in again. You don't need to open it." }),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button("Restart now", "restart_now"), button("Later", "later", "secondary")])
      ];
    },
    done: function (s) {
      return [
        el("div", { class: "head" }, [el("span", { class: "icon volt", "aria-hidden": "true", text: "✓" }), title("You're set.")]),
        el("p", { id: "text", text: "IdleO lives by the clock and earns only when you're not using your laptop." }),
        el("section", { class: "tile" }, [el("p", { text: s.pending ? "We check every new laptop before it goes live. You'll get an email." : "Live. Renters can book this laptop now." })]),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button("Close", "close")]),
        s.report_problem ? el("p", { class: "muted small", text: "Couldn't report to IdleO: " + s.report_problem }) : null
      ];
    },
    stopped: function (s) {
      return [
        title(s.kept ? "Kept for troubleshooting." : "Not set up."),
        el("p", { id: "text", class: "muted", text: s.kept ? "Everything installed so far is still here. Run IdleO-Setup again and choose Repair." : "What was installed has been removed." }),
        el("section", { class: "tile" }, [el("p", { class: "muted small", text: "The setup log" }), el("p", { class: "path", text: s.log })]),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button("Close", "close")]),
        el("div", { class: "below" }, [diagnostics()]),
        s.report_problem ? el("p", { class: "muted small", text: "Couldn't report to IdleO: " + s.report_problem }) : null
      ];
    }
  };

  function note(text, showFile) {
    saved.replaceChildren(el("span", { text: text }));
    if (showFile) saved.appendChild(button("Show in folder", "show_diagnostics", "secondary"));
    saved.hidden = false;
  }

  window.idleo = {
    receive: function (screen) {
      // Diagnostics are news on whatever screen asked: a note, not a new screen.
      if (screen.screen === "diagnostics_saved") return note("Saved to " + screen.path + ". Your name and your computer's name were taken out.", true);
      if (screen.screen === "diagnostics_failed") return note("Diagnostics couldn't be saved: " + screen.message, false);
      var render = screens[screen.screen];
      if (!render) return;
      current = screen;
      saved.hidden = true;
      var nodes = render(screen);
      if (demo) nodes.unshift(el("span", { class: "ribbon", text: "Demo · nothing is installed" }));
      app.replaceChildren.apply(app, nodes);
      var first = app.querySelector("button");
      if (first) {
        first.setAttribute("aria-describedby", "title text");
        first.focus();
      }
    },
    askClose: function () {
      if (current && (current.screen === "done" || current.screen === "stopped")) return send("close");
      confirmBox.hidden = false;
      confirmBox.querySelector("[data-action=cancel_close]").focus();
    },
    dev: function (text) {
      document.body.appendChild(el("p", { class: "dev", text: text }));
    }
  };

  document.addEventListener("click", function (event) {
    var target = event.target.closest("button[data-action]");
    if (!target) return;
    var action = target.getAttribute("data-action");
    if (action === "cancel_close") { confirmBox.hidden = true; return; }
    if (action === "confirm_close") { confirmBox.hidden = true; app.replaceChildren(el("h1", { text: "Removing what was set up…" })); }
    send(action);
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && !confirmBox.hidden) confirmBox.hidden = true;
  });
  send("ready");
})();
