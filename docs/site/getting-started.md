---
title: Getting started
description: Sign in, bring your files, request a GPU, and download what it made.
order: 1
path: /docs/
---

{{product}} rents you a gaming GPU by the minute. **We provide the GPU. You bring your own model, data and code.** This page takes you from a new account to your first results.

## 1. Sign in

[Sign in](/sign-in/) with your email: we send a link, no password. While {{product}} is in its pilot it is invite-only, so the first time you sign in it asks for an invite code from the person who invited you. A code works once.

## 2. Pick a service

Home shows what you can run. Today that is **Fine-tune a model** and **Run your code**; the [Services](/docs/services/) page says what each one takes.

## 3. Bring your files

Upload a folder or a file from your laptop, up to 40 GiB, or paste an https link for the job to fetch itself. An upload starts as soon as you choose it and carries on where it stopped if you choose the same files again after a dropped connection. Uploads are kept in **My files** until 30 days after a session last used them, so a big model goes up once.

## 4. Request a GPU

Choose what to run and press **Request a GPU**. You don't pick a machine: we match the best free GPU that fits, and you pay that GPU's price by the minute. Before you press, the screen shows the price range for the GPUs that fit now, an estimate, the hold we'll set aside (up to 30 minutes of the price) and your spending cap, the most the session may cost.

## 5. Follow it, then download

The session screen shows its state, the time it has run, what it has cost so far against the cap, and its logs. **Stop session** saves a checkpoint first and stops charging. When it ends, download its outputs and, if you like, **Run again** with the same setup.

If something went wrong, you can report a problem with a session within 7 days of its end; a person reads it and may refund part or all of what you paid.

## From a terminal or a script

The command line, `idleo`, signs in through the web app and then runs, follows and downloads from your terminal; see the [Command line](/docs/cli/) reference. It isn't a packaged download yet; for now it is built from the project's repository. From Python, or a notebook, the [Python SDK](/docs/sdk/) does the same. Other scripts can call the [API](/docs/api/) with an API key made on your Account page.

## Where next

- [What each GPU can run](/gpus/) and [what it costs](/pricing/)
- [FAQ](/docs/faq/)
- Have a gaming laptop or desktop? [Earn with your GPU](/earn/) and the [host guide](/docs/hosting/)
