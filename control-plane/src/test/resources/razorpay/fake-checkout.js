// The local stack's stand-in for Razorpay's Standard Checkout (ADR 0039; the real one is
// https://checkout.razorpay.com/v1/checkout.js), served by the fake Razorpay. It has the same shape the web app uses:
// `new Razorpay(options)`, `.on("payment.failed", fn)`, `.open()`, and calls `options.handler` with the documented
// razorpay_payment_id, razorpay_order_id and razorpay_signature, or the failure handlers with `{ error }`. The payment
// itself is made by the fake (`/_fake/pay`), which then sends Razorpay's webhooks to the control plane. Never shipped.
(function () {
  "use strict";
  var FAKE = "__FAKE_URL__";

  function Razorpay(options) {
    this.options = options;
    this.failed = [];
  }

  Razorpay.prototype.on = function (event, handler) {
    if (event === "payment.failed") this.failed.push(handler);
  };

  Razorpay.prototype.open = function () {
    var self = this;
    var dialog = document.createElement("div");
    dialog.setAttribute("role", "dialog");
    dialog.setAttribute("aria-modal", "true");
    dialog.setAttribute("aria-label", "Test payment");
    dialog.setAttribute("data-fake-checkout", "");
    dialog.style.cssText =
      "position:fixed;inset:0;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.5);z-index:2147483647";
    var box = document.createElement("div");
    box.style.cssText = "background:#fff;color:#111;padding:24px;border-radius:12px;font:16px system-ui;max-width:320px";
    var title = document.createElement("p");
    title.textContent = "Test payment of ₹" + (self.options.amount / 100).toFixed(2) + " (no real money)";
    box.appendChild(title);
    function button(label, outcome, method) {
      var b = document.createElement("button");
      b.type = "button";
      b.textContent = label;
      b.style.cssText = "display:block;width:100%;margin-top:8px;padding:10px;font:inherit";
      b.addEventListener("click", function () {
        dialog.remove();
        if (outcome === "dismiss") {
          if (self.options.modal && self.options.modal.ondismiss) self.options.modal.ondismiss();
          return;
        }
        fetch(FAKE + "/_fake/pay", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ order_id: self.options.order_id, outcome: outcome, method: method }),
        })
          .then(function (r) {
            return r.json();
          })
          .then(function (answer) {
            if (answer.error) {
              self.failed.forEach(function (handler) {
                handler({ error: answer.error });
              });
            } else {
              self.options.handler(answer);
            }
          });
      });
      box.appendChild(b);
    }
    button("Pay with test UPI", "captured", "upi");
    button("Pay with a test card", "captured", "card");
    button("Fail the payment", "failed", "upi");
    button("Close", "dismiss", "upi");
    dialog.appendChild(box);
    document.body.appendChild(dialog);
  };

  window.Razorpay = Razorpay;
})();
