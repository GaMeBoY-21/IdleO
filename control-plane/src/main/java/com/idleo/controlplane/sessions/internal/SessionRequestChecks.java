package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.sessions.CreateSessionRequest;
import com.idleo.controlplane.sessions.SessionRequests;
import com.idleo.controlplane.sessions.Template;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The one check of a booking against its template (ADR 0019), for booking and quoting alike.
 *
 * <ul>
 *   <li>The template exists; GPU memory and runtime fit it; the parameters pass {@link SessionParams}.
 *   <li>The input fits what the template accepts ({@code input-not-accepted}, {@code input-required}), and is exactly
 *       one of an upload or a URL.
 *   <li>An upload must be the renter's own ({@code not-found} otherwise), ready ({@code upload-not-ready}), and no
 *       larger than the template takes ({@code input-too-large}).
 *   <li>A URL must be https, at most 2,048 characters, with no user info, and not {@code localhost} or a private,
 *       loopback or link-local address literal. The job fetches it inside its sandbox; the agent never does.
 * </ul>
 */
@Component
class SessionRequestChecks implements SessionRequests {

    static final int MAX_URL_LENGTH = 2_048;
    private static final long MIB = 1024L * 1024;

    private final TemplateCatalog catalog;
    private final UploadStore uploads;

    SessionRequestChecks(TemplateCatalog catalog, UploadStore uploads) {
        this.catalog = catalog;
        this.uploads = uploads;
    }

    @Override
    public Checked check(UUID renterId, CreateSessionRequest request) {
        var template = catalog.find(request.templateId())
                .orElseThrow(
                        () -> ApiProblem.invalid("templateId", "There is no template " + request.templateId() + "."));
        int vram = request.vramNeededMb() == null ? template.minVramMb() : request.vramNeededMb();
        if (vram < template.minVramMb()) {
            throw ApiProblem.invalid(
                    "vramNeededMb", "must be at least the template's " + template.minVramMb() + " MB.");
        }
        var maxRuntime = request.maxRuntimeSeconds() == null
                ? template.defaultMaxRuntime()
                : Duration.ofSeconds(request.maxRuntimeSeconds());
        if (maxRuntime.compareTo(template.maxRuntimeCap()) > 0) {
            throw ApiProblem.invalid(
                    "maxRuntimeSeconds",
                    "must be at most the template's " + template.maxRuntimeCap().toSeconds() + " seconds.");
        }
        var params = SessionParams.resolve(template, request.params());
        var input = request.input();
        var uploadId = input == null ? null : input.uploadId();
        var url = input == null ? null : input.url();
        if (uploadId != null && url != null) {
            throw ApiProblem.invalid("input", "must be exactly one of an uploadId or a url.");
        }
        boolean given = uploadId != null || url != null;
        var accepts = template.input();
        if (given && accepts.accepts() == Template.Accepts.NONE) {
            throw ApiProblem.of(Problem.INPUT_NOT_ACCEPTED, "Template " + template.id() + " takes no input.");
        }
        if (!given && accepts.accepts() == Template.Accepts.REQUIRED) {
            throw ApiProblem.of(
                    Problem.INPUT_REQUIRED, "Template " + template.id() + " needs an input: " + kinds(accepts) + ".");
        }
        String storedName = null;
        if (uploadId != null) {
            if (!accepts.upload()) {
                throw ApiProblem.of(
                        Problem.INPUT_NOT_ACCEPTED, "Template " + template.id() + " takes a URL, not an upload.");
            }
            storedName = upload(renterId, uploadId, accepts).storedName();
        }
        if (url != null) {
            if (!accepts.url()) {
                throw ApiProblem.of(
                        Problem.INPUT_NOT_ACCEPTED, "Template " + template.id() + " takes an upload, not a URL.");
            }
            urlProblem(url).ifPresent(problem -> {
                throw ApiProblem.invalid("input.url", problem);
            });
        }
        var env = SessionParams.env(params, storedName, url);
        return new Checked(template, vram, maxRuntime, params, uploadId, url, env);
    }

    private UploadStore.Upload upload(UUID renterId, UUID uploadId, Template.Input accepts) {
        var upload = uploads.find(uploadId)
                .filter(found -> found.userId().equals(renterId))
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "No such upload."));
        if (upload.status() != UploadStore.Status.READY) {
            throw ApiProblem.of(
                    Problem.UPLOAD_NOT_READY,
                    "Upload " + uploadId + " is " + UploadService.status(upload)
                            + "; only a ready one can be an input.");
        }
        long limit = accepts.maxSizeMb() == null ? Long.MAX_VALUE : accepts.maxSizeMb() * MIB;
        if (upload.sizeBytes() > limit) {
            throw ApiProblem.of(
                    Problem.INPUT_TOO_LARGE,
                    "Upload " + uploadId + " is " + upload.sizeBytes() + " bytes; this template takes at most "
                            + accepts.maxSizeMb() + " MiB.");
        }
        return upload;
    }

    private static String kinds(Template.Input accepts) {
        if (accepts.upload() && accepts.url()) {
            return "an uploadId or an https url";
        }
        return accepts.upload() ? "an uploadId" : "an https url";
    }

    /** Why the job may not be given {@code url}; empty if it may. */
    static Optional<String> urlProblem(String url) {
        if (url.length() > MAX_URL_LENGTH) {
            return Optional.of("must be at most " + MAX_URL_LENGTH + " characters.");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.of("isn't a URL.");
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            return Optional.of("must be https.");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("can't carry a user name or password.");
        }
        var host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.of("needs a host.");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return Optional.of("can't point at localhost.");
        }
        var literal = literal(host);
        if (literal != null && !isPublic(literal)) {
            return Optional.of("can't point at a private, loopback or link-local address.");
        }
        return Optional.empty();
    }

    /** The address an IP literal names, never looking anything up; null for a host name. */
    private static @Nullable InetAddress literal(String host) {
        var bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!bare.contains(":") && !bare.matches("[0-9.]+")) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(bare);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        var bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // 0.0.0.0/8, and 100.64.0.0/10, the shared space overlay networks (Tailscale) use.
            return first != 0 && !(first == 100 && second >= 64 && second < 128);
        }
        if (address instanceof Inet6Address) {
            // fc00::/7, unique local addresses.
            return (bytes[0] & 0xfe) != 0xfc;
        }
        return true;
    }
}
