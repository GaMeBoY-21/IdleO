# Real sign-in for a local control plane, shared by control-plane/scripts/run-local.sh and ./start.sh (through
# scripts/lib/stack.sh). Sourced; needs root, the repository.
# shellcheck shell=bash

# Real sign-in (docs/runbooks/identity-provider-setup.md): reads control-plane/.env, checks its issuer and audience
# are your own Identity Platform project's, and exports them as IDENTITY_ISSUERS_<n>_*. Says what's wrong and returns 1
# otherwise. Nothing else from .env is used.
stack_real_sign_in() {
  local n="$1" issuer audience
  if [[ -f "$root/control-plane/.env" ]]; then
    issuer="$(sed -n 's/^IDENTITY_ISSUERS_0_ISSUERURI=//p' "$root/control-plane/.env" | tail -1)"
    audience="$(sed -n 's/^IDENTITY_ISSUERS_0_AUDIENCE=//p' "$root/control-plane/.env" | tail -1)"
  fi
  issuer="${issuer:-${IDENTITY_ISSUERS_0_ISSUERURI:-}}"
  audience="${audience:-${IDENTITY_ISSUERS_0_AUDIENCE:-}}"
  if [[ -z "$issuer" || -z "$audience" ]]; then
    echo "real sign-in needs IDENTITY_ISSUERS_0_ISSUERURI and IDENTITY_ISSUERS_0_AUDIENCE in control-plane/.env. Check those two lines (identity-provider-setup.md, section 7)." >&2
    return 1
  fi
  if [[ "$audience" == "demo-project" || "$audience" == "idleo-local" || "$issuer" != https://securetoken.google.com/* ]]; then
    echo "IDENTITY_ISSUERS_0_ISSUERURI must be https://securetoken.google.com/<your-project-id> and IDENTITY_ISSUERS_0_AUDIENCE your project id, not the example or the development sign-in server. Check those two lines in control-plane/.env." >&2
    return 1
  fi
  if [[ "$issuer" != "https://securetoken.google.com/$audience" ]]; then
    echo "the issuer ($issuer) and the audience ($audience) must end in the same project id. Check those two lines in control-plane/.env." >&2
    return 1
  fi
  export "IDENTITY_ISSUERS_${n}_ISSUERURI=$issuer" "IDENTITY_ISSUERS_${n}_AUDIENCE=$audience"
  real_sign_in_project="$audience"
}
