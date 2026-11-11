package com.idleo.controlplane;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * The web's origins that may call the API from a browser (ADR 0018, ADR 0058), read once from configuration.
 *
 * <ul>
 *   <li>{@code web.allowed-origins} ({@code WEB_ALLOWED_ORIGINS}): the renter app's, the host site's and the website's,
 *       which call {@code /v1}.
 *   <li>{@code web.admin-origins} ({@code WEB_ADMIN_ORIGINS}): the admin site's (admin.idleo.in), the only origin that
 *       may call {@code /admin/v1}, and where an admin's passkey is used. It calls {@code /v1} too, to sign in.
 * </ul>
 *
 * Both lists are bound, not read with {@code @Value}: a YAML list is stored as indexed keys, which {@code @Value} can't
 * read.
 */
public record WebOrigins(List<String> sites, List<String> admin) {

    public WebOrigins {
        sites = List.copyOf(sites);
        admin = List.copyOf(admin);
    }

    public static WebOrigins of(Environment environment) {
        var binder = Binder.get(environment);
        return new WebOrigins(list(binder, "web.allowed-origins"), list(binder, "web.admin-origins"));
    }

    private static List<String> list(Binder binder, String name) {
        return binder.bind(name, Bindable.listOf(String.class)).orElse(List.of()).stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    /** Every origin that may call {@code /v1}: the sites' and the admin site's, each once. */
    public List<String> all() {
        var all = new LinkedHashSet<>(sites);
        all.addAll(admin);
        return List.copyOf(all);
    }

    /**
     * Whether a request with this {@code Origin} header may reach {@code /admin/v1} (ADR 0058, defence in depth beside
     * CORS): from a browser, only the admin site. A request with no {@code Origin} isn't a web page's (the operator's
     * tools); it goes on to the admin checks like any other.
     */
    public boolean admits(@Nullable String origin) {
        return origin == null || admin.contains(origin.strip());
    }

    /**
     * Whether a passkey made for {@code rpId} works on every admin origin: each one's host is the relying party's id or
     * under it (WebAuthn's rule), so admin.idleo.in takes passkeys made for idleo.in.
     */
    public static boolean coversHost(String rpId, String origin) {
        var host = URI.create(origin.strip()).getHost();
        if (host == null) {
            return false;
        }
        var id = rpId.strip().toLowerCase(Locale.ROOT);
        var lower = host.toLowerCase(Locale.ROOT);
        return lower.equals(id) || lower.endsWith("." + id);
    }
}
