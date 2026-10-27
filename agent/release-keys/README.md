# Release keys

`release.pub` is the Windows app's production release key (ADR 0034): the raw 32-byte Ed25519 public key in base64, on
one line. A release build (`--features prod-keys`) bakes it into the setup app, the launcher and the agent, and refuses
to compile while it is empty. The control plane reads the same value from `IDLEO_RELEASE_PUBLIC_KEY`.

It is empty until the product owner makes the key with `scripts/release/new-release-key.sh`, which writes the public half here and
the private half only to a path she chooses. The private key is never in this repository, never read by anything we
ship, and only ever passed to `idleo-release sign --key <path>`.

A normal build bakes in the development key from `private/release-dev/` (`scripts/release/dev-key.sh`) instead, says
"Development build" in its windows, and refuses any control plane on `idleo.in`.
