#!/usr/bin/env python3
"""Writes deploy/policies/base/images-signed.yaml (ADR 0048): for each signer, an ImageValidatingPolicy for pods (and,
through autogen, the controllers that make them) and one for debug containers, each skipped when every image it covers
is already named by a pod controller it verified. Twelve policies share one shape, so they're written from this list;
scripts/checks/deploy.sh fails if the file differs from what this writes.

    scripts/deploy/signed-policies.py            write it
    scripts/deploy/signed-policies.py --check    exit non-zero if it differs
"""
import os
import sys

SIGNERS = [
    ("idleo-images-signed",
     "Our images (ADR 0048): signed keylessly by this repository's images workflow on main or a release tag, through\n"
     "GitHub's OIDC issuer and the public transparency log, with the same workflow's CycloneDX bill of materials and SLSA\n"
     "build provenance: the three checks scripts/images/verify.sh makes. An image with our name and anything less\n"
     "(re-tagged, built elsewhere, or signed by another workflow) is refused.",
     ["ghcr.io/gameboy-21/idleo-*"],
     r"^https://github\.com/GaMeBoY-21/IdleO/\.github/workflows/images\.yml@refs/(heads/main|tags/v.+)$", True),
    ("idleo-flux-signed", "Flux's controllers: signed by Flux's shared controller release workflow at a release tag.",
     ["ghcr.io/fluxcd/source-controller*", "ghcr.io/fluxcd/kustomize-controller*", "ghcr.io/fluxcd/helm-controller*",
      "ghcr.io/fluxcd/notification-controller*"],
     r"^https://github\.com/fluxcd/gha-workflows/\.github/workflows/controller-release\.yaml@refs/tags/v.+$", False),
    ("idleo-flagger-signed", "Flagger: signed by its release workflow at a release tag.",
     ["ghcr.io/fluxcd/flagger:*", "ghcr.io/fluxcd/flagger@*"],
     r"^https://github\.com/fluxcd/flagger/\.github/workflows/release\.yml@refs/tags/v.+$", False),
    ("idleo-cloudnative-pg-signed", "CloudNativePG's operator: signed by its release workflow at a release tag.",
     ["ghcr.io/cloudnative-pg/cloudnative-pg*"],
     r"^https://github\.com/cloudnative-pg/cloudnative-pg/\.github/workflows/release-publish\.yml@refs/tags/v.+$", False),
    ("idleo-postgresql-signed", "CloudNativePG's PostgreSQL images: signed by the postgres-containers build on main.",
     ["ghcr.io/cloudnative-pg/postgresql*"],
     r"^https://github\.com/cloudnative-pg/postgres-containers/\.github/workflows/bake_targets\.yml@refs/heads/main$", False),
]

HEADER = """\
# Signed images (ADR 0048), checked by Kyverno in every namespace but kube-system and Kyverno's own: as a pod or a
# debug container is admitted, and, through autogen, as a pod controller (Deployment, StatefulSet, DaemonSet, Job,
# CronJob) is written. Each fails closed: if Kyverno can't answer, nothing is admitted (docs/runbooks/kyverno.md).
#
# A digest is checked online (GHCR for the signature and attestations, verified against the transparency log's proof
# inside each Sigstore bundle) only the first time: when a pod controller naming it is written, or a pod names it that
# no controller does. After that, a pod naming an image some verified pod controller already names is admitted
# without a network call (Kyverno's watched copy of every pod controller, pod-controllers.yaml), so a pod replaced
# while GHCR or the internet is down (a crashed control plane, a database instance rebuilt after a failover, every pod
# after the server restarts) still starts, in milliseconds. A digest nothing names yet is still checked online, and
# refused if it can't be (ADR 0048, "When GHCR is down").
#
# Pod controllers count only where these policies apply (not kube-system or kyverno), where each was verified as it
# was written; those written before the policies existed (Flux's own at bootstrap) came from git, Flux's root of trust.
# Which vendors sign, and which are held by their digest alone, is in the ADR and in images-pinned.yaml's list.
"""

def unverified_condition(globs):
    """A match condition: true when the object names an image these globs cover that no verified pod controller
    names. Autogen rewrites object.spec to each controller's pod template."""
    prefixes = ", ".join("'" + g.rstrip("*") + "'" for g in globs)
    kinds = [("apps/v1", "deployments", "o.spec.template.spec"), ("apps/v1", "statefulsets", "o.spec.template.spec"),
             ("apps/v1", "daemonsets", "o.spec.template.spec"), ("batch/v1", "jobs", "o.spec.template.spec"),
             ("batch/v1", "cronjobs", "o.spec.jobTemplate.spec.template.spec")]
    specs = "\n              + ".join(
        f"dyn(globalContext.Get('idleo-{res}', ''))\n"
        f"                  .filter(o, !(o.metadata.?namespace.orValue('') in ['kube-system', 'kyverno'])).map(o, {path})"
        for api, res, path in kinds)
    return f"""  # Checked only when the object names an image these policies cover that no pod controller already names: a pod
  # controller where they apply was verified as it was written, so its images need no second look, and a pod naming
  # them is admitted with no call to GHCR or Rekor. Anything else is checked in full below.
  matchConditions:
    - name: names-an-unverified-image
      expression: >-
        cel.bind(images,
          (object.spec.containers + (has(object.spec.initContainers) ? object.spec.initContainers : [])
            + (has(object.spec.ephemeralContainers) ? object.spec.ephemeralContainers : []))
            .map(c, c.image).filter(i, [{prefixes}].exists(p, i.startsWith(p))),
          images.size() > 0 && cel.bind(verified,
            {specs},
            images.exists(i, !verified.exists(s, s.containers.exists(c, c.image == i)
              || (has(s.initContainers) && s.initContainers.exists(c, c.image == i))))))
"""

def policy(name, comment, globs, subject, ours, debug):
    resources = "pods/ephemeralcontainers" if debug else "pods"
    lines = [f"# {l}" for l in comment.split("\n")]
    if debug:
        lines = [f"# The same, for a debug container added to a running pod (kubectl debug)."]
    out = "\n".join(lines) + "\n"
    out += f"""apiVersion: policies.kyverno.io/v1
kind: ImageValidatingPolicy
metadata:
  name: {name}{"-debug" if debug else ""}
spec:
  failurePolicy: Fail
  webhookConfiguration:
    timeoutSeconds: 30
  evaluation:
    background:
      enabled: false
  validationActions: [Deny]
  # Every image is already pinned by digest (images-pinned.yaml): nothing to resolve, so no mutating webhook.
  validationConfigurations:
    mutateDigest: false
  matchConstraints:
    namespaceSelector:
      matchExpressions:
        - {{ key: kubernetes.io/metadata.name, operator: NotIn, values: [kube-system, kyverno] }}
    resourceRules:
      - apiGroups: [""]
        apiVersions: [v1]
        operations: [CREATE, UPDATE]
        resources: [{resources}]
"""
    if not debug:
        out += """  # The same check as each pod controller is written, so its pod template is verified before any pod.
  autogen:
    podControllers:
      controllers: [deployments, statefulsets, daemonsets, jobs, cronjobs]
"""
    out += unverified_condition(globs)
    out += "  matchImageReferences:\n" + "".join(f'    - glob: "{g}"\n' for g in globs)
    out += f"""  attestors:
    - name: workflow
      cosign:
        keyless:
          identities:
            - issuer: https://token.actions.githubusercontent.com
              subjectRegExp: '{subject}'
        ctlog:
          url: https://rekor.sigstore.dev
"""
    if ours:
        out += """  attestations:
    - name: sbom
      intoto:
        type: https://cyclonedx.org/bom
    - name: provenance
      intoto:
        type: https://slsa.dev/provenance/v1
"""
    out += f"""  validations:
    - expression: (images.containers + images.initContainers + images.ephemeralContainers).map(image, verifyImageSignatures(image, [attestors.workflow])).all(e, e > 0)
      message: '{name}: no signature from {subject} (or GHCR or Sigstore could not be reached to check one: docs/runbooks/image-refused.md)'
"""
    if ours:
        out += f"""    - expression: >-
        (images.containers + images.initContainers + images.ephemeralContainers).map(image, verifyAttestationSignatures(image, attestations.sbom, [attestors.workflow]))
          .all(e, e > 0)
      message: "{name}: no CycloneDX bill of materials attested by the images workflow"
    - expression: >-
        (images.containers + images.initContainers + images.ephemeralContainers).map(image, verifyAttestationSignatures(image, attestations.provenance,
          [attestors.workflow])).all(e, e > 0)
      message: "{name}: no SLSA build provenance attested by the images workflow"
"""
    return out

docs = []
for s in SIGNERS:
    docs.append(policy(*s, debug=False))
    docs.append(policy(*s, debug=True))
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "deploy", "policies", "base",
                    "images-signed.yaml")
text = HEADER + "\n" + "---\n".join(docs)
if sys.argv[1:] == ["--check"]:
    if open(path).read() != text:
        sys.exit("deploy/policies/base/images-signed.yaml differs from scripts/deploy/signed-policies.py: run it")
else:
    open(path, "w").write(text)
