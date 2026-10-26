package com.idleo.controlplane.images.internal;

import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An image reference as a renter writes it, read by the OCI distribution grammar with Docker Hub's defaults (no registry
 * is {@code docker.io}, a one-part name there is {@code library/<name>}, no tag is {@code latest}): the same rules as the
 * scanner's {@code reference.rs}, which reads it again rather than trust it. A registry named by an address, or by a name
 * with no dot, is refused unless it's one of {@code images.dev-registries}: a reference must never point our own network.
 */
record ImageReference(
        String registry,
        String repository,
        @Nullable String tag,
        @Nullable String digest) {

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern COMPONENT = Pattern.compile("[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    /** Docker Hub's namespace for its official images: {@code ubuntu} is {@code library/ubuntu}. */
    private static final String OFFICIAL = "library";

    private static final Pattern ADDRESS = Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+|\\[.*\\]");

    /** Why a reference can't be used, in the renter's words. */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** The reference {@code text}, read; {@code allowed} the development registries that may be private. */
    static ImageReference parse(String text, List<String> allowed) {
        var trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.length() > 512) {
            throw new Invalid(
                    "A reference is 1 to 512 characters, like pytorch/pytorch:2.5.1-cuda12.4-cudnn9-runtime.");
        }
        String digest = null;
        var rest = trimmed;
        var at = trimmed.indexOf('@');
        if (at >= 0) {
            digest = trimmed.substring(at + 1);
            rest = trimmed.substring(0, at);
            if (!DIGEST.matcher(digest).matches()) {
                throw new Invalid("A digest is sha256: and 64 lower-case hex digits.");
            }
        }
        var slash = rest.lastIndexOf('/') + 1;
        var colon = rest.indexOf(':', slash);
        String tag = null;
        var name = rest;
        if (colon >= 0) {
            tag = rest.substring(colon + 1);
            name = rest.substring(0, colon);
            if (!TAG.matcher(tag).matches()) {
                throw new Invalid("\"" + tag + "\" isn't a tag: letters, digits, '_', '.' and '-', at most 128.");
            }
        }
        var firstSlash = name.indexOf('/');
        var first = firstSlash < 0 ? "" : name.substring(0, firstSlash);
        String registry;
        String repository;
        if (firstSlash > 0 && (first.contains(".") || first.contains(":") || first.equals("localhost"))) {
            registry = first.toLowerCase(Locale.ROOT);
            repository = name.substring(firstSlash + 1);
        } else {
            registry = "docker.io";
            repository = name;
        }
        if (registry.equals("docker.io") && !repository.contains("/")) {
            repository = OFFICIAL + '/' + repository;
        }
        checkRegistry(registry, allowed);
        for (var component : repository.split("/", -1)) {
            if (!COMPONENT.matcher(component).matches()) {
                throw new Invalid("\"" + repository + "\" isn't a repository: lower-case letters, digits and '.', '_' "
                        + "or '-' between them, in parts split by '/'.");
            }
        }
        if (tag == null && digest == null) {
            tag = "latest";
        }
        return new ImageReference(registry, repository, tag, digest);
    }

    private static void checkRegistry(String registry, List<String> allowed) {
        if (allowed.stream().anyMatch(dev -> dev.equalsIgnoreCase(registry))) {
            return;
        }
        var host = registry.contains(":") && !registry.startsWith("[")
                ? registry.substring(0, registry.lastIndexOf(':'))
                : registry;
        var port = host.length() < registry.length() ? registry.substring(host.length() + 1) : null;
        if (port != null && !port.matches("\\d{1,5}")) {
            throw new Invalid("\"" + registry + "\" isn't a registry's name.");
        }
        if (ADDRESS.matcher(host).matches() || host.equals("localhost") || !host.contains(".")) {
            throw new Invalid("Use a registry by its public name, like ghcr.io or docker.io; not an address.");
        }
        var ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        for (var label : ascii.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                throw new Invalid("\"" + registry + "\" isn't a registry's name.");
            }
        }
        if (ascii.endsWith(".internal") || ascii.endsWith(".local") || ascii.endsWith(".localhost")) {
            throw new Invalid("Use a registry by its public name, like ghcr.io or docker.io.");
        }
    }

    /** The reference as it's stored and shown: {@code registry/repository[:tag][@digest]}. */
    String canonical() {
        return registry + "/" + repository + (tag == null ? "" : ":" + tag) + (digest == null ? "" : "@" + digest);
    }

    /** The reference pinned to {@code pinned}: what sessions run. */
    String pinned(String pinned) {
        return registry + "/" + repository + "@" + pinned;
    }

    Optional<String> requestedDigest() {
        return Optional.ofNullable(digest);
    }
}
