#!/usr/bin/env bash
# The suites and which files each one answers for: the one place scripts/check-all.sh and scripts/check-changed.sh
# read. Source it; don't run it.
#
# A suite's checks are scripts/checks/<suite>.sh, except drill, web-browser, e2e, k8s and gitops (suite_command). Its
# paths follow the workflows' `paths:` filters (.github/workflows/*.yml), so a change runs what CI would run for it.

ALL_SUITES=(agent cli scanner control-plane ops drill proto distro images sdk web deploy web-browser e2e k8s gitops)

# suite_command SUITE: runs the suite, from the repository root, and returns its status.
suite_command() {
  local root="$1" suite="$2"
  case "$suite" in
    agent | cli | scanner | control-plane | ops | proto | distro | images | sdk | web | deploy) "$root/scripts/checks/$suite.sh" ;;
    # The restore drill runs on the Mac's cluster (ADR 0047): up.sh brings it up (stopping ./start.sh's stack) or
    # updates it.
    drill) "$root/scripts/k8s/up.sh" && "$root/ops/backup/drill.sh" ;;
    web-browser) "$root/scripts/e2e/agent-online.sh" --web ;;
    e2e) "$root/scripts/e2e/agent-online.sh" && "$root/scripts/e2e/real-runner.sh" && "$root/scripts/e2e/start-check.sh" ;;
    # The Mac's cluster from the images up, and the proof (ADR 0045); up.sh stops ./start.sh's stack first.
    k8s) "$root/scripts/k8s/up.sh" && "$root/scripts/k8s/smoke.sh" ;;
    # The same cluster under Flux, and its canaries: a good release, a bad one rolled back, an old agent (ADR 0046).
    gitops) "$root/scripts/k8s/canary-test.sh" ;;
    *)
      echo "no suite called $suite; the suites are: ${ALL_SUITES[*]}" >&2
      return 2
      ;;
  esac
}

# suites_for_path PATH: the suites a changed file (relative to the root) puts in play, one per line. First match wins
# for the directory a file lives in; the prefixes below are the whole map.
suites_for_path() {
  case "$1" in
    scripts/checks/migrations.sh) echo control-plane ;;
    scripts/checks/*.sh) local s="${1#scripts/checks/}"; echo "${s%.sh}" ;;
    # The server's images (ADR 0045): what they're built from runs the cluster's proof (.github/workflows/k8s.yml).
    scanner/Dockerfile*) echo scanner e2e k8s ;;
    control-plane/Dockerfile* | web/Dockerfile*) echo k8s ;;
    web/scripts/nginx-config.ts) echo web web-browser k8s ;;
    # ./start.sh's PostgreSQL (WAL-G); its first start makes the database's two users, which the control plane's tests
    # check.
    ops/postgres/*) echo ops control-plane ;;
    deploy/* | .sops.yaml) echo deploy k8s gitops ;;
    scripts/deploy/*) echo deploy ;;
    scripts/k8s/*) echo k8s gitops ;;
    agent/distro/*) echo agent distro e2e ;;
    agent/images/*) echo images ;;
    agent/*) echo agent e2e ;;
    cli/src/error.rs) echo cli sdk e2e ;;
    cli/*) echo cli e2e ;;
    scanner/*) echo scanner e2e ;;
    control-plane/*) echo control-plane e2e ;;
    # A change to the agent protocol reaches both ends.
    proto/*) echo proto agent control-plane e2e ;;
    # The API contract is read by the control plane, both clients and the web app.
    api/cuda-drivers.json) echo agent control-plane e2e ;;
    api/*) echo control-plane cli sdk web e2e ;;
    templates/*) echo control-plane web e2e ;;
    web/*) echo web web-browser ;;
    sdk/*) echo sdk e2e ;;
    docs/site/generated/*) echo cli sdk web ;;
    docs/site/*) echo web ;;
    docs/runbooks/alerts.md | ops/alerts/* | ops/alertmanager/* | ops/grafana/*) echo ops ;;
    ops/backup/*) echo drill ops ;;
    scripts/ops/*) echo ops ;;
    scripts/go-public/*) echo agent ;;
    scripts/lib/stack.sh) echo e2e k8s ;;
    scripts/e2e/* | scripts/lib/real-sign-in.sh | start.sh) echo e2e ;;
    scripts/lib/toolchains.sh | scripts/check-all.sh | scripts/check-changed.sh | scripts/lib/suites.sh | scripts/lib/quiet.sh) echo "" ;;
    *) echo "" ;;
  esac
}
