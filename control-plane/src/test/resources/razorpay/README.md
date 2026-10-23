# Razorpay's own samples

Every file here is a sample request or response copied word for word from Razorpay's documentation (fetched
2026-10-23), so the fake Razorpay (`FakeRazorpay`) and the tests only ever use shapes Razorpay documents (ADR 0039).
Tests change the values they need (ids, amounts, notes) and never add a field. The page each came from:

| File | Page |
| --- | --- |
| `webhook-payment-*.json` (authorized, captured and failed by netbanking, card and UPI) | https://razorpay.com/docs/webhooks/payments/ |
| `webhook-order-paid.json` | https://razorpay.com/docs/webhooks/orders/ |
| `webhook-refund-*.json` (created, processed, failed) | https://razorpay.com/docs/webhooks/refunds/ |
| `webhook-payment-dispute-*.json` (created, won, lost, closed, under review, action required) | https://razorpay.com/docs/webhooks/disputes/ |
| `api-order-create.json`, `api-order-create-error.json` | https://razorpay.com/docs/api/orders/create/ |
| `api-payment-fetch-*.json` (UPI, card, netbanking, an unknown id) | https://razorpay.com/docs/api/payments/fetch-with-id/ |
| `api-refund-create.json` | https://razorpay.com/docs/api/refunds/create-normal/ |
| `api-payments-fetch-all.json` | https://razorpay.com/docs/api/payments/fetch-all-payments/ |
| `api-refunds-fetch-all.json` | https://razorpay.com/docs/api/refunds/fetch-all/ |
| `api-refunds-of-payment.json` | https://razorpay.com/docs/api/refunds/fetch-multiple-refund-payment/ |

One change: the documentation's `api-payments-fetch-all.json` misses the comma after the second payment's `upi`
object, so it isn't JSON as printed; the comma is added and nothing else.
