package com.idleo.controlplane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the prod profile with a development setting (ADR 0013). Development-only behaviour is
 * always a setting, never a code path, and every such setting is checked here:
 *
 * <ul>
 *   <li>{@code agentgateway.allow-development-hosts}, which lets a Mac or fake GPUs connect;
 *   <li>a trusted identity issuer that is plain HTTP or on this machine, like the local sign-in server;
 *   <li>{@code sessions.storage.provider: minio}, the local object storage (ADR 0016);
 *   <li>a {@code sessions.templates.catalog} other than the bundled one, such as templates/development.yaml
 *       for the agent's development runner (ADR 0016);
 *   <li>a web origin in {@code web.allowed-origins} that is {@code *}, plain HTTP or on this machine (ADR 0018);
 *   <li>an {@code identity.web-app-url}, where CLI sign-ins are approved, that is plain HTTP or on this machine
 *       (ADR 0025);
 *   <li>a {@code hosts.link.web-app-url}, where a host link is approved, that is plain HTTP or on this machine (ADR 0032);
 *   <li>a {@code notifications.web-app-url}, where an email's links go, that is plain HTTP or on this machine; a
 *       {@code notifications.email.from} on this machine; a mail server ({@code spring.mail.host}) on this machine,
 *       such as the local mail catcher; or an email provider other than {@code smtp} (ADR 0026);
 *   <li>{@code springdoc.api-docs.enabled}: the API contract is generated in tests and committed, never served
 *       (ADR 0018);
 *   <li>no {@code identity.invite-code-key}, the HMAC key for invite and recovery codes (ADR 0024), or either it or
 *       {@code identity.admin.totp-key} set to the tests' committed value (application-test.yaml, which ships in the
 *       jar and is read from there, so the values are never copied);
 *   <li>{@code identity.invite-only} false, which lets anyone in without a code (ADR 0024);
 *   <li>a renter limit above production's ({@link #PRODUCTION_LIMITS}, ADR 0025), as the end-to-end harness and the
 *       tests lift them;
 *   <li>no OTLP endpoint for metrics, traces or logs, one that is plain HTTP or on this machine, or any of the three
 *       exports turned off ({@link #OTLP_ENDPOINTS}, ADR 0036).
 * </ul>
 *
 * <p>It runs before any other bean is created, so nothing starts with such a setting, not even briefly.
 */
@Component
class ProductionSettingsGuard implements BeanFactoryPostProcessor {

    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1");
    static final String BUNDLED_CATALOG = "classpath:templates/catalog.yaml";

    /**
     * Production's renter limits, as application.yaml sets them (a test keeps the two equal). The prod profile may
     * tighten one; lifting one is the harness's setting, never production's.
     */
    static final Map<String, Integer> PRODUCTION_LIMITS = Map.of(
            "limits.requests-per-minute", 60,
            "limits.log-reads-per-minute", 120,
            "limits.cli-logins-per-hour", 10,
            "limits.host-links-per-hour", 10,
            "sessions.limits.new-per-hour", 10,
            "sessions.limits.new-per-hour-first-day", 2,
            "sessions.limits.at-once", 3);

    /**
     * Where metrics, traces and logs go (ADR 0036), each with the switch that turns its export off: production sends
     * all three, over HTTPS, to an endpoint that isn't this machine.
     */
    static final Map<String, String> OTLP_ENDPOINTS = new TreeMap<>(Map.of(
            "management.otlp.metrics.export.url", "management.otlp.metrics.export.enabled",
            "management.opentelemetry.tracing.export.otlp.endpoint", "management.tracing.export.otlp.enabled",
            "management.opentelemetry.logging.export.otlp.endpoint", "management.logging.export.otlp.enabled"));

    /** The keys whose committed test values production must never use. */
    static final List<String> SECRET_KEYS = List.of("identity.admin.totp-key", "identity.invite-code-key");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        var environment = beanFactory.getBean(Environment.class);
        if (!environment.matchesProfiles("prod")) {
            return;
        }
        var problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start the prod profile with development settings: " + String.join("; ", problems));
        }
    }

    record IssuerUri(String issuerUri) {}

    /** Every development setting in this environment, whatever its profile. */
    static List<String> problems(Environment environment) {
        var problems = new ArrayList<String>();
        var binder = Binder.get(environment);
        if (binder.bind("agentgateway.allow-development-hosts", Boolean.class).orElse(false)) {
            problems.add("agentgateway.allow-development-hosts is true");
        }
        if ("minio"
                .equalsIgnoreCase(
                        binder.bind("sessions.storage.provider", String.class).orElse(""))) {
            problems.add("sessions.storage.provider is minio");
        }
        var catalog = binder.bind("sessions.templates.catalog", String.class).orElse(BUNDLED_CATALOG);
        if (!BUNDLED_CATALOG.equals(catalog.strip())) {
            problems.add("sessions.templates.catalog is " + catalog + ", not the bundled catalog");
        }
        for (var origin : binder.bind("web.allowed-origins", Bindable.listOf(String.class))
                .orElse(List.of())) {
            if (isDevelopmentOrigin(origin)) {
                problems.add("web origin " + origin.strip() + " is *, plain HTTP or on this machine");
            }
        }
        if (binder.bind("springdoc.api-docs.enabled", Boolean.class).orElse(false)) {
            problems.add("springdoc.api-docs.enabled is true");
        }
        for (var issuer : binder.bind("identity.issuers", Bindable.listOf(IssuerUri.class))
                .orElse(List.of())) {
            if (isDevelopmentIssuer(issuer.issuerUri())) {
                problems.add("identity issuer " + issuer.issuerUri() + " is plain HTTP or on this machine");
            }
        }
        var webApp = binder.bind("identity.web-app-url", String.class).orElse("");
        if (isDevelopmentIssuer(webApp)) {
            problems.add("identity.web-app-url " + webApp + " is plain HTTP or on this machine");
        }
        var linkApproval = binder.bind("hosts.link.web-app-url", String.class).orElse("");
        if (isDevelopmentIssuer(linkApproval)) {
            problems.add("hosts.link.web-app-url " + linkApproval + " is plain HTTP or on this machine");
        }
        var emailLinks = binder.bind("notifications.web-app-url", String.class).orElse("");
        if (isDevelopmentIssuer(emailLinks)) {
            problems.add("notifications.web-app-url " + emailLinks + " is plain HTTP or on this machine");
        }
        var from = binder.bind("notifications.email.from", String.class).orElse("");
        if (from.toLowerCase(Locale.ROOT).contains("localhost")) {
            problems.add("notifications.email.from " + from + " is on this machine");
        }
        var mailHost = binder.bind("spring.mail.host", String.class).orElse("");
        if (mailHost.isBlank() || LOCAL_HOSTS.contains(mailHost.strip().toLowerCase(Locale.ROOT))) {
            problems.add("spring.mail.host is " + (mailHost.isBlank() ? "missing" : mailHost + ", on this machine"));
        }
        var emailProvider =
                binder.bind("notifications.email.provider", String.class).orElse("smtp");
        if (!"smtp".equalsIgnoreCase(emailProvider)) {
            problems.add("notifications.email.provider is " + emailProvider + ", not smtp");
        }
        if (binder.bind("identity.invite-code-key", String.class).orElse("").isBlank()) {
            problems.add("identity.invite-code-key is missing");
        }
        var committed = committedTestValues();
        for (var key : SECRET_KEYS) {
            var value = binder.bind(key, String.class).orElse("").strip();
            if (!value.isEmpty() && value.equals(committed.getProperty(key))) {
                problems.add(key + " is the tests' committed value");
            }
        }
        if (!binder.bind("identity.invite-only", Boolean.class).orElse(true)) {
            problems.add("identity.invite-only is false");
        }
        for (var signal : OTLP_ENDPOINTS.entrySet()) {
            var url = binder.bind(signal.getKey(), String.class).orElse("");
            if (url.isBlank() || url.contains("${")) {
                problems.add(signal.getKey() + " is missing");
            } else if (isDevelopmentIssuer(url)) {
                problems.add(signal.getKey() + " " + url + " is plain HTTP or on this machine");
            }
            if (!binder.bind(signal.getValue(), Boolean.class).orElse(true)) {
                problems.add(signal.getValue() + " is false");
            }
        }
        new TreeMap<>(PRODUCTION_LIMITS).forEach((name, ceiling) -> {
            var value = binder.bind(name, Integer.class);
            if (value.isBound() && value.get() > ceiling) {
                problems.add(name + " is " + value.get() + ", above production's " + ceiling);
            }
        });
        return problems;
    }

    /** The values application-test.yaml commits, read from the classpath; empty if it isn't there. */
    private static Properties committedTestValues() {
        var values = new Properties();
        var resource = new ClassPathResource("application-test.yaml");
        if (!resource.exists()) {
            return values;
        }
        try {
            for (var source : new YamlPropertySourceLoader().load("committed-test-values", resource)) {
                for (var key : SECRET_KEYS) {
                    var value = source.getProperty(key);
                    if (value != null) {
                        values.setProperty(key, value.toString().strip());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("application-test.yaml can't be read", e);
        }
        return values;
    }

    private static boolean isDevelopmentOrigin(String origin) {
        var trimmed = origin.strip();
        return trimmed.contains("*") || isDevelopmentIssuer(trimmed);
    }

    private static boolean isDevelopmentIssuer(String issuerUri) {
        try {
            var uri = URI.create(issuerUri.strip());
            var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return !scheme.equals("https") || LOCAL_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
