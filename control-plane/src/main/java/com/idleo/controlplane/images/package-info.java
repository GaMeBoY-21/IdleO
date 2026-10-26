/**
 * The images module (ADR 0042). Owns: renters' own container images, the check each must pass on our server before it can
 * reach a host, and the read-only registry tokens of private ones.
 *
 * <p>Its tables live in the {@code images} database schema, and no other module reads them.
 *
 * <ul>
 *   <li>{@code POST /v1/images}: a renter with a verified PAN (billing's {@link com.idleo.controlplane.billing.VerifiedPans},
 *       which payouts implements) submits a reference, with a user name and read-only token for a private registry
 *       (sealed at rest, {@code images.registry-token.key}); its check is queued. {@code GET /v1/images}, {@code GET
 *       /v1/images/{id}} (state, progress, results and warnings), {@code GET /v1/images/{id}/vulnerabilities} and {@code
 *       DELETE /v1/images/{id}}: the renter's own; another person's is {@code not-found}.
 *   <li>The check is done by the scanner (scanner/, a container of its own), which takes work from the internal API on
 *       {@code images.scanner.port}, a port of its own that the load balancer never routes, with its bearer token
 *       ({@code images.scanner.token}). It resolves the reference to one platform's manifest (the digest that is the only
 *       thing that will ever run), and this module decides: the platform, the size and the layers against their limits,
 *       a cached result for the digest, and, from the SHA-256 of every ELF file in every layer, the abuse blocklist the
 *       hosts module has verified ({@link com.idleo.controlplane.hosts.Blocklists}). A check that crashes or runs past its
 *       time is {@code failed}, not checked: never a pass.
 *   <li>A newer blocklist ({@link com.idleo.controlplane.hosts.BlocklistCached}, and a sweep) judges every cached result
 *       again from its hashes; one it blocks can't be booked, and {@link ImageBlocked} tells sessions (which cancels what
 *       hasn't started) and notifications (which tells the owner).
 *   <li>{@link CheckedImages}: what sessions books, offers and starts with (the pinned reference, its CUDA version, its
 *       entrypoint, command and working directory, its sizes, and a private image's token at each StartSession).
 *   <li>{@code /admin/v1/images}: the list by state, one image with its findings, and a manual block (audited).
 * </ul>
 */
@ApplicationModule
package com.idleo.controlplane.images;

import org.springframework.modulith.ApplicationModule;
