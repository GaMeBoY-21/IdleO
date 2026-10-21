package com.idleo.controlplane;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What real visits to the app and the website meet (ADR 0036): {@code POST /v1/vitals}, sent by the pages'
 * {@code src/telemetry/report.ts} with {@code navigator.sendBeacon} as plain text, so no sign-in and no CORS preflight.
 * A batch is at most 8 KiB and 20 items; the request limits count it per address, apart from other calls
 * ({@code limits.vitals-per-minute}). Nothing names a person: no user, and the address is used only for that count,
 * never stored.
 *
 * <ul>
 *   <li>A vital (LCP, INP, CLS, TTFB) becomes {@code browser.vitals.<name>} by {@code app}, {@code route} and
 *       {@code device} (phone or laptop), which the dashboard takes the 75th percentile of and the slow-page alert
 *       reads.
 *   <li>A JavaScript error becomes {@code browser.errors} by {@code app}, {@code route} and {@code release}, which the
 *       browser-errors alert reads, and a log line with its message, its source file and line (named from the build's
 *       source maps when the control plane has them, {@link SourceMaps}), the route and the release.
 * </ul>
 *
 * A route, a release and every other tag are bounded: what isn't a plain code, or comes after the first
 * {@link #MAX_ROUTES} routes or {@link #MAX_RELEASES} releases, is counted as {@code other}, so no visitor can make
 * series. Not part of the public API, so it's left out of the contract.
 */
@Hidden
@RestController
class BrowserVitals {

    private static final Logger log = LoggerFactory.getLogger(BrowserVitals.class);
    static final int MAX_BODY_BYTES = 8 * 1024;
    static final int MAX_ITEMS = 20;
    static final int MAX_ROUTES = 100;
    static final int MAX_RELEASES = 20;
    private static final Pattern ROUTE = Pattern.compile("/[a-z0-9/:._-]{0,80}");
    private static final Pattern RELEASE = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    private static final Pattern SOURCE = Pattern.compile("(https?://[a-z0-9.:-]+)?/[A-Za-z0-9/._-]{1,160}");
    private static final Set<String> APPS = Set.of("app", "site");
    private static final Set<String> DEVICES = Set.of("phone", "laptop");
    /** Seconds, around the 2.5 s the slow-page alert and Google's "good" LCP draw the line at. */
    private static final double[] SECONDS = {0.1, 0.2, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0, 10.0};
    /** Layout shift, around Google's 0.1 and 0.25. */
    private static final double[] SHIFT = {0.01, 0.05, 0.1, 0.15, 0.25, 0.5, 1.0};

    private static final Map<String, String> VITALS = Map.of("LCP", "lcp", "INP", "inp", "CLS", "cls", "TTFB", "ttfb");

    /** What a message mustn't carry: an email address, a token or key, an address's query. */
    private static final Pattern PERSONAL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
            + "|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*"
            + "|idleo_[A-Za-z0-9_-]{8,}"
            + "|\\?[^\\s\"']*");

    private final MeterRegistry registry;
    private final SourceMaps sourceMaps;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<String> routes = ConcurrentHashMap.newKeySet();
    private final Set<String> releases = ConcurrentHashMap.newKeySet();

    BrowserVitals(MeterRegistry registry, SourceMaps sourceMaps) {
        this.registry = registry;
        this.sourceMaps = sourceMaps;
    }

    @PostMapping("/v1/vitals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void report(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "A batch of vitals is at most 8 KiB.");
        }
        byte[] body;
        try (var in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw ApiProblem.of(Problem.REQUEST_TOO_LARGE, "A batch of vitals is at most 8 KiB.");
        }
        JsonNode batch;
        try {
            batch = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiProblem.of(Problem.MALFORMED_REQUEST, "The body isn't a batch of vitals.");
        }
        var app = batch.path("app").asString("");
        var items = batch.path("items");
        if (!APPS.contains(app) || !items.isArray() || items.size() > MAX_ITEMS) {
            throw ApiProblem.of(Problem.MALFORMED_REQUEST, "A batch names its app and holds at most 20 items.");
        }
        var release = bounded(batch.path("release").asString(""), RELEASE, releases, MAX_RELEASES);
        for (var item : items) {
            var route = bounded(item.path("route").asString("").toLowerCase(Locale.ROOT), ROUTE, routes, MAX_ROUTES);
            switch (item.path("type").asString("")) {
                case "vital" -> vital(app, route, item);
                case "error" -> error(app, route, release, item);
                default -> {
                    // A kind a later page knows and this control plane doesn't: ignored.
                }
            }
        }
    }

    private void vital(String app, String route, JsonNode item) {
        var name = VITALS.get(item.path("name").asString(""));
        var device = item.path("device").asString("");
        var value = item.path("value").asDouble(-1);
        if (name == null || !DEVICES.contains(device) || value < 0 || value > 600_000) {
            return;
        }
        var shift = name.equals("cls");
        DistributionSummary.builder("browser.vitals." + name)
                .description("What real visits met: " + item.path("name").asString())
                .baseUnit(shift ? null : "seconds")
                .serviceLevelObjectives(shift ? SHIFT : SECONDS)
                .tags("app", app, "route", route, "device", device)
                .register(registry)
                .record(shift ? Math.min(value, 10) : value / 1000.0);
    }

    private void error(String app, String route, String release, JsonNode item) {
        registry.counter("browser.errors", "app", app, "route", route, "release", release)
                .increment();
        var source = item.path("source").asString("");
        source = SOURCE.matcher(source).matches() ? source : "";
        var line = Math.max(0, item.path("line").asInt(0));
        var column = Math.max(0, item.path("column").asInt(0));
        var message = PERSONAL.matcher(item.path("message").asString(""))
                .replaceAll("[removed]")
                .replaceAll("[\\r\\n]+", " ");
        message = message.length() > 300 ? message.substring(0, 300) : message;
        var original = sourceMaps.original(source, line, column).orElse("");
        log.warn(
                "JavaScript error in the {} ({}) on {}: {} at {}:{}:{}{}",
                app,
                release,
                route,
                message,
                source.isEmpty() ? "?" : source,
                line,
                column,
                original.isEmpty() ? "" : " (" + original + ")");
    }

    /** The value when it's a plain code among the first {@code max} seen, else {@code other}. */
    private static String bounded(String value, Pattern shape, Set<String> seen, int max) {
        if (!shape.matcher(value).matches()) {
            return "other";
        }
        if (seen.contains(value) || (seen.size() < max && seen.add(value))) {
            return value;
        }
        return "other";
    }
}
