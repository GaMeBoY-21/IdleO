package com.idleo.controlplane.sessions.internal;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What a link input may be (ADR 0019): https, at most 2,048 characters, with no user info, and not {@code localhost}
 * or a private, loopback or link-local address literal. The job fetches it inside its sandbox, where private networks
 * are blocked too; the agent never does.
 */
final class InputLinks {

    static final int MAX_URL_LENGTH = 2_048;

    private InputLinks() {}

    /** Why the job may not be given {@code url}; empty if it may. */
    static Optional<String> problem(String url) {
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
