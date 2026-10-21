# A personal data breach

What to do when personal data may have been seen, changed, lost or deleted by someone who shouldn't have (ADR 0037). Every step marked LEGAL(confirm) is our reading of the Digital Personal Data Protection Act 2023 and its Rules 2025, which a lawyer must confirm before launch; until then, follow it as written and take advice on the day.

## What counts

A breach is any of these, by anyone, on purpose or by mistake, whatever its size (LEGAL(confirm): the Act has no minimum):

- Someone reads personal data they shouldn't: a person's account, sessions, files, outputs, logs, messages, money records or addresses (docs/reference/personal-data-map.md lists every place).
- Personal data is changed or deleted by someone who shouldn't have, or lost: a bucket or a backup made public, a key leaked, a database copied, a laptop with an export stolen.
- Personal data is sent to the wrong person: an email to the wrong address, someone else's file in an export.

A host's machine seeing a renter's job while it runs is not a breach: that's how the product works, inside the sandbox (ADR 0035). The sandbox failing is one.

If unsure, treat it as a breach until shown otherwise, and write down why you decided either way.

## Who decides

The founder on duty decides it's a breach and runs this page; with no founder reachable within the hour, whoever found it does, and tells them as soon as they can. Keep one written timeline from the first minute (a document only the people handling it can open): every time, what was seen, what was done, by whom.

## The first hour: contain, and keep the evidence

1. **Stop it getting worse.** Revoke what leaked: rotate the key or token (docs/runbooks/deployment.md lists every secret and where it lives), revoke an API key or an admin, suspend an account (admin console, Users) or a host (Hosts), make a bucket private, take a page down. If the control plane itself is compromised, stop it (docs/runbooks/deployment.md).
2. **Keep the evidence.** Before anything is cleaned up: export the logs of the hours around it from Grafana Cloud (Loki) to a file, as logs are kept 14 days; copy the audit log and the security log rows involved (`identity.audit_log`, `identity.security_events`, which hold the addresses); note the backups that cover the time (docs/runbooks/backups.md), and don't let them age out: copy the full backup and WAL of that window aside. Never edit or delete audit rows.
3. **Find out what and who.** Which data, of whom (account ids), from when to when, and how. The personal data map says what each place holds. Write the list of affected account ids; it's what the notices go to.

## Telling the people affected

Each affected person is told without delay (LEGAL(confirm): as soon as we can say something useful, not after the investigation ends), by email, in plain words. Write the notice; the lawyer reads it if there's time. It says (LEGAL(confirm) on what it must contain):

- what happened, and when;
- which of their data it was;
- what it may mean for them;
- what we've done, and are doing;
- what they can do (change a password at their sign-in provider, revoke an API key, watch for emails pretending to be us);
- who to write to: the grievance officer on the Contact page.

Send it from the admin console, **Notices**: paste the account ids, the subject and the words, and give the reason (the incident's name, never a person's name or address). It goes to each person's verified email and the audit log records who was told, by id. Keep a copy of the words in the timeline. Someone with no address we can write to is listed after sending: write to them another way if there is one, and note it.

## Telling the Data Protection Board

LEGAL(confirm) throughout: the Rules ask for two reports to the Data Protection Board of India.

1. **An intimation, without delay** once we know of the breach: what happened, roughly how many people, what we've done so far. Send it the same day; don't wait for the facts to be complete.
2. **A detailed report within 72 hours** of knowing (or as the Board allows): use the template below.

The Board's own form and address take precedence over this template; LEGAL(confirm) which channel the Board accepts.

### Template: detailed report

```
To: the Data Protection Board of India
From: <company name>, <registered address>   LEGAL(confirm): who signs, and in what capacity
Date and time sent: <IST>

1. The breach
   When it happened (from–to, IST):
   When we found out, and how:
   What happened (in a few sentences):
   Its cause, as far as known:

2. The personal data
   Which kinds (account, sessions, files, money records, messages, addresses…):
   How many people, and how many of them under 18 (we don't knowingly hold any):
   Whether it was read, changed, lost or deleted:

3. What we did
   To stop it (with times):
   To keep evidence:
   To prevent it again:

4. The people affected
   When and how they were told (with the notice's words attached):
   What we advised them to do:

5. Who to contact about this report
   Name, role, email, phone:   LEGAL(confirm)
```

## Afterwards

Within a week, while it's fresh, write in the timeline:

- the cause, in a sentence a newcomer understands, and the fix that makes it impossible (not just unlikely); land the fix with a test;
- what this page got wrong or left out, and change it;
- the record for our own register of breaches (LEGAL(confirm) whether one is required and for how long it's kept): date, what, how many, notices sent, reports made, the fix.

The evidence copied aside is kept until the lawyer says it can go, then deleted like everything else.
