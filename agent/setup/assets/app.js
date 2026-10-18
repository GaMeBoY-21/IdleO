// The setup window's page (ADR 0033): Rust sends a screen with window.idleo.receive(...), and the page sends the
// person's choices back with window.ipc.postMessage(JSON). No network, no storage: everything is in the program.
(function () {
  "use strict";
  var app = document.getElementById("app");
  var confirm = document.getElementById("confirm");
  var current = null;

  function send(type) {
    window.ipc.postMessage(JSON.stringify({ type: type }));
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (key) {
      if (key === "text") node.textContent = attrs[key];
      else node.setAttribute(key, attrs[key]);
    });
    (children || []).forEach(function (child) { node.appendChild(child); });
    return node;
  }

  function button(text, action, kind) {
    return el("button", { type: "button", class: kind || "primary", "data-action": action, text: text });
  }

  var screens = {
    welcome: function (s) {
      var tiles = [
        ["1", "We check your laptop", "Graphics, Windows and space, in a minute or two."],
        ["2", "We set up a safe space for renters' jobs", "Sealed off from your files and your home network."],
        ["3", "You approve in your browser", "One click links this laptop to your account."]
      ];
      return [
        el("h1", { text: s.already_set_up ? "IdleO is already set up on this laptop" : "Earn with this laptop" }),
        el("div", { class: "tiles" }, tiles.map(function (t) {
          return el("section", { class: "tile" }, [
            el("span", { class: "badge", "aria-hidden": "true", text: t[0] }),
            el("h2", { text: t[1] }),
            el("p", { class: "muted small", text: t[2] })
          ]);
        })),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [
          button(s.already_set_up ? "Repair" : "Set up", "set_up"),
          el("p", { class: "muted", text: s.already_set_up ? "Checks everything again. Your link stays." : "About 5 minutes. Renters never see your files." })
        ])
      ];
    },
    progress: function (s) {
      var list = el("ol", { class: "steps" }, s.steps.map(function (step) {
        var mark = step.state === "done" ? "✓" : step.state === "failed" ? "!" : "";
        var label = el("span", { class: "label", text: step.label });
        if (step.detail) label.appendChild(el("span", { class: "detail", text: step.detail }));
        return el("li", { class: step.state }, [el("span", { class: "mark", "aria-hidden": "true", text: mark }), label]);
      }));
      var parts = [el("h1", { text: "Setting up" }), el("section", { class: "tile grow" }, [list])];
      if (s.note) parts.push(el("p", { class: "note", text: s.note }));
      return parts;
    },
    fix: function (s) {
      return [
        el("h1", { text: s.text }),
        el("p", { class: "muted", text: s.rechecking ? "We'll carry on by ourselves the moment it's fixed." : "" }),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button(s.button, s.problem === "cant_reach_idleo" ? "try_again" : "fix")])
      ];
    },
    approve: function (s) {
      return [
        el("h1", { text: "We opened your browser. Approve this laptop there." }),
        el("section", { class: "tile" }, [el("p", { class: "muted small", text: "This laptop" }), el("p", { class: "machine", text: s.machine_name })]),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [
          button("Open again", "open_again", "secondary"),
          el("p", { class: "muted small", text: "Or open " + s.short_url + " on your phone" })
        ])
      ];
    },
    link_failed: function (s) {
      return [el("h1", { text: s.message }), el("div", { class: "grow" }), el("div", { class: "row" }, [button("Try again", "try_again")])];
    },
    restart: function () {
      return [
        el("h1", { text: "Windows needs one restart to finish." }),
        el("p", { class: "muted", text: "Setup carries on by itself after." }),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button("Restart now", "restart_now"), button("Later", "later", "secondary")])
      ];
    },
    done: function (s) {
      return [
        el("h1", { text: "You're set." }),
        el("p", { text: "IdleO lives by the clock and earns only when you're not using your laptop." }),
        el("section", { class: "tile" }, [el("p", { text: s.pending ? "We check every new laptop before it goes live. You'll get an email." : "Live. Renters can book this laptop now." })]),
        el("div", { class: "grow" }),
        el("div", { class: "row" }, [button("Close", "close")])
      ];
    }
  };

  window.idleo = {
    receive: function (screen) {
      current = screen;
      var render = screens[screen.screen];
      if (!render) return;
      app.replaceChildren.apply(app, render(screen));
      var first = app.querySelector("button");
      if (first) first.focus();
    },
    askClose: function () {
      if (current && current.screen === "done") return send("close");
      confirm.hidden = false;
      confirm.querySelector("[data-action=cancel_close]").focus();
    },
    dev: function (text) {
      document.body.appendChild(el("p", { class: "dev", text: text }));
    }
  };

  document.addEventListener("click", function (event) {
    var target = event.target.closest("button[data-action]");
    if (!target) return;
    var action = target.getAttribute("data-action");
    if (action === "cancel_close") { confirm.hidden = true; return; }
    if (action === "confirm_close") { confirm.hidden = true; app.replaceChildren(el("h1", { text: "Removing what was set up…" })); }
    if (action === "fix") target.disabled = current && current.problem === "no_rtx_gpu" ? true : false;
    send(action);
  });
  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && !confirm.hidden) confirm.hidden = true;
  });
  send("ready");
})();
