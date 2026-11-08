# Rolling out an agent release

Who: Operations or the Owner, on the console's **Fleet** (ADR 0056). The release itself is signed and published as ever (docs/runbooks/hosts.md, `scripts/release/`); the control plane only decides which hosts are offered it, and the agent still checks every signature itself.

## The steps

1. Publish the signed release with the share you'd allow at most (`--rollout 100` usually). Within the feed's refresh it shows under **Rollouts** at **5%** of hosts, chosen by host id.
2. Watch its row for a day: its hosts, the sessions they lost against the rest's, and their reconnects a day against the rest's. The hosts behind it show as "behind" in the list below, filterable by agent version.
3. **Widen** to 25%, then 50%, then 100%, each with a reason. Another admin approves on **Approvals**; with no one else to, it goes at once. Hosts already offered it still are.
4. When most hosts have it, the stragglers are the hosts list filtered to the old version: **Notify these owners** sends them a notice ("update your app"), or write a **Broadcast** to an agent-version range on **Notices**.

## When it pauses

- **By hand:** **Pause**, with why. No host is offered it from that moment; hosts that updated keep it.
- **By itself:** its hosts lost more than 10 points more sessions than the rest (at least 5 sessions), or reconnected at least 3 times a day each and more than twice the rest. The row says which, the audit log has `rollout.pause` by the system, and the Owners are emailed.

Then:

1. Look at those hosts: Fleet filtered to the release's version, each host's **Presence history** tab, and their sessions.
2. If the release is at fault, leave it paused and publish a fixed one; the new release starts its own rollout at 5%.
3. If it wasn't the release (a network outage hit everyone), **Run again** at the same share, with the reason.

## The thresholds

`hosts.rollouts.*` in application.yaml: the window (24 h), the fewest sessions (5), the lost-share margin (0.10), the fewest reconnects (3) and the factor over the rest (2). Change them with numbers from real hosts, in a pull request, saying why.
