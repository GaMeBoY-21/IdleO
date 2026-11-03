# 0050. A long suite that goes red or silent opens a ticket

- **Status:** Accepted
- **Date:** 2026-11-03

## Context

The long suites aren't required checks (ADR 0044): they take up to an hour, so nothing stops when one goes red. The hostile suite, the proof of the job sandbox, was red on every run from the day it first ran and nobody was told. Only the nightly scan reported anywhere (ADR 0048).

## Decision

**Every long suite reports through one reporter:** the composite action `.github/actions/report-suite`, which runs `scripts/ops/report-suite.py` as the last step of each suite's job. It sends `idleo_suite_passed{suite}` (1 or 0) to Grafana Cloud over OTLP, and `idleo_suite_findings{suite}` where a suite counts findings. The nightly scan moved onto it; its metric is the same one now, with `suite="scan"`.

**Only main and the schedule count.** A pull request's run reports nothing: a red branch is that branch's business. A cancelled run reports nothing either, since a newer run replaced it or it ran out of time.

**Two alerts for each suite, at `digest`, the daily ticket route, never a page:** one when its last result was red, one when no result came inside its interval plus a margin (`ops/alerts/long-suites.yaml`, `security.yaml`). Weekly suites get 8 days and the nightly scan 30 hours. The suites are hostile, failover, k8s, gitops, security, e2e, web-browser and scan.

**e2e and the browser run gained a weekly schedule.** They ran only on a push to main that touched their paths, so a quiet week would have looked like a suite that had stopped.

`scripts/checks/ops.sh` checks every suite has the reporter, a schedule and both alerts at the right window, so a new one can't be half wired.

## Alternatives considered

- **Making them required checks.** ADR 0044 rejected it: they'd hold every merge on an hour's run and a runner's mood.
- **GitHub's own failure emails.** They go to whoever last touched the workflow, and say nothing when a suite stops running, which is what a billing stop did on Oct 26.

## Consequences

- Until the `GRAFANA_CLOUD_OTLP_*` secrets exist each run warns and nothing reports, so once the rules are loaded every `*Missing` alert fires. That is correct: nobody is hearing from those suites yet.
- A report that can't be sent turns the suite's job red, so a broken reporter shows on the run.
- A suite that always runs out of time is reported as missing, not failing.
