---
title: FAQ
description: Short answers about what we provide, what it costs, what happens when things go wrong, and who can see what.
order: 6
path: /docs/faq/
---

## What do you provide?

The GPU. You bring your own model, data and code. We don't supply models, images or transcription, and no service we offer does.

## What does it cost?

You pay for each minute your session runs, at the price of the GPU it was given. See [Pricing](/pricing/) for today's price ranges, from live figures. A minute that has started counts as a whole one.

## What is the hold?

When you request a GPU we set aside up to 30 minutes of its price from your credit. It's a hold, not a charge: it is released as your session settles, and anything not used goes back to your available credit.

## What is the spending cap?

The most a session may cost you, shown before you request. When it is reached, the session saves a checkpoint and stops. A session never takes your credit below zero.

## What if the host's machine goes offline?

The session moves to another GPU and carries on from your last saved checkpoint. Minutes after that checkpoint are not charged, and any already charged are given back.

## Who can see my files and results?

You, and the job that runs them. The host never sees who you are, your inputs or your settings. Your uploads are kept in My files until 30 days after a session last used them, and you can delete them yourself at any time.

## Is it safe to run my code on a stranger's laptop?

Your job runs in a sandbox: a plain user, a read-only system, no host folders mounted, no special privileges, and no way out to the host's home network. The host's own files are out of reach.

## How big can my uploads be?

A folder of up to 1,000 files, or one file, up to 40 GiB. A link may point at anything the job can download inside its sandbox.

## Can I use it without the website?

Yes. The command line signs in through the website once, then runs, follows and downloads from your terminal. The API is the same one the website uses, called with an API key from your Account page. See the [Command line](/docs/cli/) and [API](/docs/api/) references.

## Why do I need an invite code?

IdleO is in its pilot, so access is by invite. Ask the person who invited you; a code works once.

## Something went wrong. Can I get my money back?

Report a problem with a session from its page within 7 days of its end. A person reads it, and may refund some or all of what you paid, taken from our share first. Time lost to a host dropping out is refunded without a report.

## How do hosts get paid?

A host keeps 80% of what renters pay for their sessions, and we keep 20%. Payouts start with the paid pilot; until then, earnings are counted but not paid out.

## Which GPUs can I get?

GPUs with 8, 12, 16 and 24 GB of memory. [What each can run](/gpus/) lists the usual cards, with what each class typically manages.
