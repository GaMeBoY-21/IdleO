// Development only: never built into a program. The browser preview (`cargo run -p setup --example preview`) puts this in front of
// a page's own script, and a fake channel stands in for Rust. `window.__previewScreens` maps a name to the messages Rust would
// send the page, in order, as JSON (written by examples/preview.rs from the very types the programs use); the page's own
// messages to Rust are logged and, where the preview knows what Rust would do, answered (`window.__previewReact`).
(function () {
  "use strict";
  var screens = window.__previewScreens || {};
  var names = Object.keys(screens);
  window.ipc = {
    postMessage: function (text) {
      if (window.console) console.log("to Rust:", text);
      if (window.__previewReact) window.__previewReact(JSON.parse(text));
    }
  };
  function play(name) {
    var messages = screens[name];
    if (!messages) return false;
    messages.forEach(function (message) {
      if (message.__tab) window.idleo.showTab(message.__tab);
      else if (message.__ask_close) window.idleo.askClose();
      else window.idleo.receive(message);
    });
    return true;
  }
  window.idleoPreview = {
    names: names,
    screens: screens,
    show: function (name) {
      var confirm = document.getElementById("confirm");
      if (confirm) confirm.hidden = true;
      var shown = play(name);
      if (shown) location.hash = name;
      return shown;
    }
  };
  document.addEventListener("DOMContentLoaded", function () {
    var at = Math.max(0, names.indexOf(location.hash.slice(1)));
    window.idleoPreview.show(names[at]);
    document.addEventListener("keydown", function (event) {
      var step = event.key === "ArrowRight" && event.altKey ? 1 : event.key === "ArrowLeft" && event.altKey ? -1 : 0;
      if (!step) return;
      at = (at + step + names.length) % names.length;
      window.idleoPreview.show(names[at]);
    });
  });
})();
