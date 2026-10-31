# An image refused at deploy

Every pod is checked as it's admitted (ADR 0048), in two places:

- **The API server itself**, by two native ValidatingAdmissionPolicies in `deploy/policies/base/`: `idleo-images-pinned` (every image named with its registry, pinned by digest, never `latest`, and from a repository on the list in the ConfigMap `kyverno/idleo-allowed-images`) and `idleo-pods-unprivileged` (no privileged container, no host network, PID or IPC, no hostPath). Pod Security "restricted" sits beside them in every namespace we own.
- **Kyverno**, by the ImageValidatingPolicies in `deploy/policies/base/images-signed.yaml`: our images must carry the images workflow's keyless signature, bill of materials and provenance; Flux's, Flagger's and CloudNativePG's must carry their own release workflows' signatures.

A refused Deployment or StatefulSet doesn't fail itself: its pods do, so the reason is in the events.

```sh
export KUBECONFIG=<the cluster's>     # the Mac's: private/k8s/kubeconfig
kubectl -n idleo get events --field-selector reason=FailedCreate --sort-by=.lastTimestamp | tail
kubectl -n flux-system get helmreleases -A          # a Helm upgrade that waits on refused pods times out here
```

## Which check refused it

The message names it:

| The message says | What it is | What to do |
| --- | --- | --- |
| `ValidatingAdmissionPolicy 'idleo-images-pinned' ... pinned by digest` | An image by tag | Pin it: `name:tag@sha256:…`. Ours are pinned by `release.yaml` (`scripts/deploy/release.py`); a vendor's in its HelmRelease's values |
| `... 'idleo-images-pinned' ... allowed repositories` | A repository not on the list, or a name without its registry (`valkey/valkey` for `docker.io/valkey/valkey`) | Name the registry. A new vendor image goes on the list in `images-pinned.yaml`, in a reviewed pull request; say in ADR 0048's list whether it's signed |
| `... 'idleo-images-pinned' ... tagged latest` | `:latest` | Use the release's own tag and digest |
| `... 'idleo-pods-unprivileged' ...` or `violates PodSecurity "restricted"` | A pod asking for more than it may | Fix the pod: non-root, read-only root, all capabilities dropped, no privilege escalation, `seccompProfile: RuntimeDefault`, no host anything. Never relabel the namespace to get past it |
| `ImageValidatingPolicy idleo-images-signed ... no signature` (or `bill of materials`, `provenance`) | One of ours without the images workflow's signature or attestations | See "Signing or policy?" below |
| `idleo-<vendor>-signed ... no signature` | A vendor image without its release workflow's signature | The pinned digest isn't the vendor's release: check it (below). A vendor that changed how it signs needs its policy changed, in a reviewed pull request |
| `failed calling webhook ... kyverno` | Kyverno isn't answering: the check failed closed | kyverno.md |
| `Get "https://ghcr.io/v2/": ...` or `... (or GHCR or Sigstore could not be reached to check one ...)` | A new digest while Kyverno can't reach GHCR or Sigstore: it can't be checked, so it's refused | kyverno.md, "It's up but can't verify". Pods whose images a verified controller already names still start |

## Signing or policy?

For one of our images, ask the same question by hand, from a machine with `cosign` and `gh` signed in:

```sh
scripts/images/verify.sh ghcr.io/gameboy-21/idleo-control-plane@sha256:<the digest the pod named>
```

- **verify.sh fails too**: the image is the problem. The usual cause is a digest that was never published by the images workflow on main: a pull request's build, a hand-typed digest, a copy from somewhere else. Take the digests `scripts/deploy/release.py` resolves for a commit of main, which the deploy pull request does by itself. If the images workflow's signing step failed on main, the build has no signature: run the workflow again for that commit, don't deploy it.
- **verify.sh passes, Kyverno refuses**: the policy is the problem. Compare `images-signed.yaml`'s `subjectRegExp` with what verify.sh checked (the workflow's path and `refs/heads/main` or `refs/tags/v…`); a renamed workflow or a new release branch needs both changed together. Kyverno also needs to reach GHCR, Sigstore's transparency log and its TUF mirror on HTTPS: its log (`kubectl -n kyverno logs deploy/kyverno-admission-controller`) says if it couldn't. Its network policy is in `deploy/infrastructure/base/kyverno.yaml`.

For a vendor image, `cosign verify <image@digest> --certificate-identity-regexp '<the policy's subjectRegExp>' --certificate-oidc-issuer https://token.actions.githubusercontent.com` answers the same question.

`scripts/deploy/policy-test.sh` (part of `scripts/checks/deploy.sh`) runs the committed expectations (`deploy/policies/tests/`) against staging's and production's policies: add a case there for whatever was refused, so it stays decided.

## Never

- Never add an exception for one image to get a deploy through, and never copy the Mac's `development` list (`deploy/policies/local`) anywhere else: `scripts/checks/deploy.sh` fails if staging's or production's policies name the local registry.
- Never delete the ConfigMap `kyverno/idleo-allowed-images`: without it every pod outside kube-system is refused (the binding's `parameterNotFoundAction: Deny`). Flux puts it back within its interval; `flux reconcile kustomization policies` does it at once.
