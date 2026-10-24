# RazorpayX's own samples

Every file here is a sample response or webhook copied from RazorpayX's documentation (fetched 2026-10-24 from the
Markdown copies the pages link, `https://razorpay.com/docs/build/llm-docs/<page>.md`), so the fake RazorpayX
(`FakeRazorpayX`) and the tests only ever use shapes RazorpayX documents (ADR 0040). Tests change the values they need
(ids, amounts, statuses, names, times) and never add a field. The page each came from:

| File | Page |
| --- | --- |
| `api-validation-vpa.json` | https://razorpay.com/docs/api/x/account-validation/vpa/ |
| `api-validation-fetch.json` | https://razorpay.com/docs/api/x/account-validation/fetch-transactions-with-id/ |
| `api-payout-create-vpa.json` | https://razorpay.com/docs/api/x/payouts/create/vpa/ |
| `api-payout-fetch.json` | https://razorpay.com/docs/api/x/payouts/fetch-with-id/ |
| `api-payouts-fetch-all.json` | https://razorpay.com/docs/api/x/payouts/fetch-all/ |
| `api-balance-fetch.json`, `api-error.json` (its failure) | https://razorpay.com/docs/api/x/account-validation/balance-fetch/ |
| `webhook-payout-*.json` (pending, rejected, queued, initiated, processed, updated, reversed, failed) | https://razorpay.com/docs/webhooks/payouts/ |
| `webhook-validation-*.json` (completed for a bank account and a VPA, failed for a bank account) | https://razorpay.com/docs/webhooks/account-validation/ |

## What was changed

Many of the printed samples aren't JSON as printed: a comma is missing after `status_details` (every payout sample and
the three payout API answers), after the VPA validation's `name_match_score`, and the processed payout's and the VPA
validation's samples end with stray commas. Those commas were added or removed and nothing else; the files are then
pretty-printed. The documentation has no sample of a VPA check RazorpayX found `invalid`, or of a failed VPA check: the
fake uses the completed VPA sample with `account_status` and `name_match_score` changed, and the failed bank account
sample's shape.

## Where the documentation disagrees with itself

- A check's answer is `validation_results` in the create call's sample and `results` in the fetch call's and the
  webhooks'; `RazorpayXClient` reads either.
- `name_match_score` is described as a string and printed as a number; the client takes either.
- The payout pages describe UPI as the only `mode` for a VPA but print `IMPS` and `NEFT` in samples; we send `UPI`.
