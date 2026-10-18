---
title: Host guide
description: What hosting asks of you, how it will keep you safe, and how you add a machine, set a price and pause.
order: 6
path: /docs/hosting/
---

A **host** is a person with an NVIDIA RTX gaming laptop or desktop who rents out the hours they aren't using it. You set the price; renters run their own jobs on your GPU; you keep 80% of what they pay. (Payouts start with the paid pilot. Until then, earnings are counted but not paid out.) The [Earn page](/earn/) has an earnings estimate you can play with.

## What you need

- Windows 10 or 11
- An NVIDIA RTX graphics card
- Plugged in, with an internet connection that stays on
- Free disk: you choose how much to keep for yourself (10 to 500 GB, 20 by default); a renter's files are only put on a machine with room for them beyond that

## How hosting will keep you safe

Hosting opens after our safety tests on real Windows laptops. These are the protections it will have:

- **Nothing at your home exposed.** The program on your machine will call out to us; nothing will connect in to it.
- **A locked box.** A renter's job will run in a sandbox as a plain user, with a read-only system, none of your folders mounted, no special privileges, and no access to your home network.
- **A short lease.** Work will run only while your machine and our servers are in touch: the lease is renewed every 30 seconds and lasts 2 minutes. If contact is lost, the work stops.
- **Privacy both ways.** Renters will never see who you are, and you'll never see who rented your machine, their files or their settings. You'll see that a session ran, how long, and what it earned.

## Add a machine

1. Sign in and open **Hosting**, then **Add a machine**.
2. Download the {{product}} app for Windows and open it. It sets everything up by itself.
3. The app opens a page in your browser: **Link this laptop to your account?** It shows your laptop as the app described it. Check that it is yours, give it a name, look at the price (already filled in from what others with your GPU charge, and yours to change) and press **Approve and start earning**. There is no code to copy.
4. New laptops are checked before they go live; an operator activates yours once it has passed, and its page shows it live.

The Windows app is still being finished: until it is, the Add a machine page says so and offers no download. A developer can link a laptop from a terminal with `idleo-agent link`, which opens the same page.

## Your price and limits

- The price is whole paise a minute, so it moves ₹0.60 an hour at a time. The page shows the range other hosts with the same GPU ask, or the usual range for a GPU like yours while there are too few of them to show one.
- You keep 80% of what a session is charged, and we keep 20%.
- You can set the temperature the machine pauses above (60 to 95 °C) and how much disk to keep free.
- A machine runs one session at a time.

## Pause, resume, remove

- **Pause** stops new sessions: until a time within 7 days, or until you resume. A session already running finishes.
- **Remove** takes the machine away for good. It can't be removed while it runs a session.

## What you see

For each machine: its state in words, its temperature and use, what it earned this week, the sessions it ran and each one's time and earnings, and any rating (shown once at least 5 renters have rated; never who rated or what they wrote). If a renter reports a problem with a session, that session's earnings are held back from payout until it is decided; a refund comes out of our share first.
