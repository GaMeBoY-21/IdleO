# Email

The control plane sends a few kinds of email (ADR 0026): a renter's session finished, failed, stopped for credit or the spending cap, or is running low on credit; and a machine's owner is told it dropped a session. Each goes through one `EmailSender`, which speaks SMTP.

## Locally

Compose runs Mailpit, which keeps every email and delivers none. Start the stack (`scripts/e2e/agent-online.sh --keep-running`, or `control-plane/scripts/run-local.sh`) and open http://localhost:8025 to read what was sent. Its API is http://localhost:8025/api/v1/messages, which the end-to-end test reads.

Ports: `LOCAL_MAIL_SMTP_PORT` (1025) and `LOCAL_MAIL_WEB_PORT` (8025). Another stack that holds them needs one of them moved.

Emails to `someone@example.com` addresses arrive in Mailpit whoever they are for: the development sign-in server's accounts are all `@example.com`.

## Choosing a provider (later, Nikki's decision)

Any provider that offers an SMTP relay with STARTTLS on port 587 works with no code change: Amazon SES, Postmark, Resend, Brevo, Mailgun and others. Pick on price, on India delivery (inbox placement at Gmail and Outlook, from an Indian sending address), and on whether it gives a dedicated IP and a bounce and complaint feed.

Then set, in the production environment (never in a file in the repo; the variables are listed with fake values in `control-plane/.env.example`):

| Variable | What |
| --- | --- |
| `MAIL_HOST` | The provider's SMTP host |
| `MAIL_PORT` | 587 unless the provider says otherwise |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | The provider's SMTP credentials |
| `NOTIFICATIONS_EMAIL_FROM` | The sender, for example `IdleO <notifications@yourdomain>`, on a domain the provider has verified |
| `NOTIFICATIONS_WEB_APP_URL` | The web app's address, https, where the links in an email go |

The prod profile refuses to start with a mail server, sender or link on this machine, or plain HTTP, or without any of these.

A provider that offers only an HTTP API is a second implementation of `EmailSender`; nothing else changes.

## DNS records for the sending domain

Without these, mail goes to spam or is refused. Use a subdomain for it (for example `mail.yourdomain`), so a mistake can't touch the main domain's mail. The provider's dashboard gives the exact values.

- **SPF:** a TXT record on the sending domain naming the provider's servers, for example `v=spf1 include:<provider's host> -all`. One SPF record per domain.
- **DKIM:** the provider signs each message; it gives two or three CNAME or TXT records (`<selector>._domainkey`) to publish. Wait until the dashboard says verified.
- **DMARC:** a TXT record at `_dmarc.<domain>`. Start with `v=DMARC1; p=none; rua=mailto:<an address you read>` and watch the reports for two weeks; then `p=quarantine`, then `p=reject` once only our mail passes.
- **A return path** (the provider's "custom MAIL FROM" or "bounce domain"), as a CNAME, so SPF aligns with the sending domain.
- **Reverse DNS and a dedicated IP** only if the provider offers one and the volume ever warrants it.

Check with the provider's own verifier, then send a test to a Gmail and an Outlook address and read the "Show original" headers: `spf=pass`, `dkim=pass`, `dmarc=pass`.

## Bounces and complaints

Today nothing reads them: the provider's dashboard shows them. Watch it in the first weeks. A person who reports an email as spam should be switched off for that kind; that needs the provider's feed, and is a later change.

## Trouble

| What you see | Cause and fix |
| --- | --- |
| A renter says they got no email | Look in the database: `select kind, email_wanted, email_claimed_at, email_sent_at from notifications.notifications where user_id = '<id>' order by id desc`. `email_wanted` false: they switched that kind off, or it never emails (no-host-free and host-lost are in the app only). Claimed and not sent for over a minute: the control plane stopped between taking the email and handing it over, which loses it; the message is still in their app. Neither claimed nor sent: it is waiting to be retried; look for the cause in the control plane's log (`Retrying incomplete event publications failed`, or a mail server error). |
| Every email fails | The log says `The mail server didn't take the email`: check `MAIL_HOST`, the credentials and that the sender is verified at the provider. Messages stay in the app; the events are retried, so mail goes out once it works. |
| Emails go to spam | The DNS records above, in this order: SPF, DKIM, DMARC. |
| A link in an email goes to the wrong place | `NOTIFICATIONS_WEB_APP_URL`. |
