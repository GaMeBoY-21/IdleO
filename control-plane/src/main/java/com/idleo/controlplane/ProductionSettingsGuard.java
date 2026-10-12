package com.idleo.controlplane;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
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
 *   <li>{@code springdoc.api-docs.enabled}: the API contract is generated in tests and committed, never served
 *       (ADR 0018).
 * </ul>
 *
 * <p>It runs before any other bean is created, so nothing starts with such a setting, not even briefly.
 */
@Component
class ProductionSettingsGuard implements BeanFactoryPostProcessor {

    private static final List<String> LOCAL_HOSTS = List.of("localhost", "127.0.0.1", "[::1]", "::1");
    static final String BUNDLED_CATALOG = "classpath:templates/catalog.yaml";

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
        return problems;
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
