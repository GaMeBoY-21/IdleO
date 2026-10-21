Resend's own sample webhook payloads, copied verbatim from its documentation on 2026-10-20, never edited:

- `email.bounced.json`: https://resend.com/docs/webhooks/emails/bounced
- `email.complained.json`: https://resend.com/docs/webhooks/emails/complained
- `email.delivered.json`: https://resend.com/docs/webhooks/emails/delivered

`EmailEventsApiTests` signs them the way Resend does (Svix) with the tests' webhook secret.
