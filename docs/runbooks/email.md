# Email

The control plane sends a few kinds of email (ADR 0026): a renter's session finished, failed, stopped for credit or the spending cap, or is running low on credit; and a machine's owner is told it dropped a session. Each goes through one `EmailSender`, which speaks SMTP.

## Locally

Compose runs Mailpit, which keeps every email and delivers none. Start the stack (`scripts/e2e/agent-online.sh --keep-running`, or `control-plane/scripts/run-local.sh`) and open http://localhost:8025 to read what was sent. Its API is http://localhost:8025/api/v1/messages, which the end-to-end test reads.

Ports: `LOCAL_MAIL_SMTP_PORT` (1025) and `LOCAL_MAIL_WEB_PORT` (8025). Another stack that holds them needs one of them moved.

Emails to `someone@example.com` addresses arrive in Mailpit whoever they are for: the development sign-in server's accounts are all `@example.com`.

## The provider: Resend

We keep SMTP (`spring.mail.*`), so any provider with an SMTP relay works with no code change; Resend is the default (ADR 0036). Its free plan, from [resend.com/pricing](https://resend.com/pricing) (read 2026-10-20): **3,000 emails a month, at most 100 a day**, 3 domains, 30 days of logs, one webhook endpoint, and the SMTP relay. A pilot sends a few a day per active renter; past 100 a day, the paid plan, or the email-failing alert fires (docs/runbooks/alerts.md).

**Setting it up (Nikki, once):**

1. Sign up at resend.com. Domains → Add domain: a **subdomain** of ours just for this mail, for example `mail.<domain>` (Resend recommends a subdomain, so this mail's reputation is its own), region nearest India.
2. Add the DNS records Resend shows (below), and wait until it says Verified.
3. API Keys → Create, "Sending access" for that domain only. That key is the SMTP password.
4. Webhooks → Add endpoint: `https://api.<domain>/v1/email-events/resend`, events `email.bounced` and `email.complained`. Copy its signing secret (`whsec_…`).
5. Set, in the production environment (never in a file in the repo; fake values are listed in `control-plane/.env.example`):

| Variable | What |
| --- | --- |
| `MAIL_HOST` | `smtp.resend.com` ([resend.com/docs/send-with-smtp](https://resend.com/docs/send-with-smtp)) |
| `MAIL_PORT` | `587` (STARTTLS, which production requires) |
| `MAIL_USERNAME` | `resend` |
| `MAIL_PASSWORD` | The API key from step 3 |
| `NOTIFICATIONS_EMAIL_FROM` | `IdleO <notifications@mail.<domain>>`: on the web app's domain or under it, which the prod profile checks |
| `NOTIFICATIONS_EMAIL_WEBHOOK_SECRET` | The webhook's `whsec_…` from step 4 |
| `NOTIFICATIONS_WEB_APP_URL` | The web app's address, https, where the links in an email go |

The prod profile refuses to start without any of these, with a mail server, sender or link on this machine or plain HTTP, or with a sender off our domain (`ProductionSettingsGuard`).

The alerts use the same relay from `alerts@mail.<domain>` (docs/runbooks/monitoring.md). A provider that offers only an HTTP API is a second implementation of `EmailSender`; nothing else changes.

## What every email carries

- **A plain-text part and its HTML twin**, the HTML without images, tracking or remote styles.
- **A stable `Message-ID`**, `<notification id@sender's domain>`: the email for one notification has the same id however often it's tried, so a resend after a failed try is the same email to the receiving server.
- **`List-Unsubscribe`** (RFC 2369), on the kinds a person can switch off: it points at Account, where they choose; mail clients show it as an unsubscribe button. Money and safety messages don't carry it: they're always sent.

## DNS records for the sending domain

Without these, mail goes to spam or is refused. On the sending subdomain, exactly as Resend's dashboard shows them (copy and paste; in Cloudflare, CNAMEs not proxied):

- **SPF:** a TXT and an MX record on Resend's return-path subdomain (`send.mail.<domain>` by default), naming Resend's servers. Resend generates both.
- **DKIM:** a TXT record at `resend._domainkey.mail.<domain>` with Resend's public key.
- **DMARC:** a TXT record at `_dmarc.<domain>`, on the main domain, which covers the subdomain. Start with
  `v=DMARC1; p=none; rua=mailto:dmarc@<domain>;` (an address you read, or a free DMARC report service's). After **two clean weeks** (the reports show our mail passing SPF and DKIM and nothing else sending as us), change it to `v=DMARC1; p=quarantine; rua=mailto:dmarc@<domain>;`, and later `p=reject`. ([resend.com/docs/dashboard/domains/dmarc](https://resend.com/docs/dashboard/domains/dmarc))

**Check them:** Resend's domain page says Verified for each; `dig TXT resend._domainkey.mail.<domain>` and `dig TXT _dmarc.<domain>` show them from outside; then send a test to a Gmail and an Outlook address and read "Show original": `SPF: PASS`, `DKIM: PASS`, `DMARC: PASS`.

## Bounces and complaints

Resend calls `POST /v1/email-events/resend` for each bounce and complaint. The control plane checks Resend's signature (Svix's scheme: `svix-id`, `svix-timestamp`, `svix-signature`, HMAC-SHA256 under the `whsec_` secret, at most 5 minutes old) before it reads a byte, and refuses anything unsigned with a 401. The same event delivered again changes nothing.

- **A hard bounce** (`bounce.type` `Permanent`): no more email to that address, money and safety included (it can't be delivered). Messages still arrive in the app, and Account says **"We couldn't email you: check your address"** until the person's address changes: a new address isn't suppressed.
- **A temporary bounce:** nothing; the provider retries.
- **A complaint** (marked as spam): no more email of the kinds they can switch off; money and safety still go.

Kept as the address's SHA-256 in `notifications.email_suppressions`, never the address. Each is counted (`notifications.emails`, outcome `bounced`, `soft_bounced`, `complained`, `suppressed` for an email not sent because of one). To clear an address by hand (the person says it works now): `delete from notifications.email_suppressions where address_hash = sha256(lower('<address>')::bytea)`.

## Trouble

| What you see | Cause and fix |
| --- | --- |
| A renter says they got no email | Look in the database: `select kind, email_wanted, email_claimed_at, email_sent_at from notifications.notifications where user_id = '<id>' order by id desc`. `email_wanted` false: they switched that kind off, or it never emails (no-host-free and host-lost are in the app only). Claimed and not sent for over a minute: the control plane stopped between taking the email and handing it over, which loses it; the message is still in their app. Neither claimed nor sent: it is waiting to be retried; look for the cause in the control plane's log (`Retrying incomplete event publications failed`, or a mail server error). |
| Every email fails | The log says `The mail server didn't take the email`: check `MAIL_HOST`, the credentials and that the sender is verified at the provider. Messages stay in the app; the events are retried, so mail goes out once it works. The email-failing alert says so (docs/runbooks/alerts.md). |
| One person gets no email, and Account says "We couldn't email you" | Their address hard-bounced: Resend's Logs say why. Once they've fixed it at their mailbox, clear it (Bounces and complaints, above). |
| Resend's webhook shows failures | A 401: `NOTIFICATIONS_EMAIL_WEBHOOK_SECRET` isn't the endpoint's signing secret, or the server's clock is off by more than 5 minutes. |
| Emails go to spam | The DNS records above, in this order: SPF, DKIM, DMARC. |
| A link in an email goes to the wrong place | `NOTIFICATIONS_WEB_APP_URL`. |
