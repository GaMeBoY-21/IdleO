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
 *       such as the local mail catcher; or an email provider other than {@code smtp} (ADR 0026); a sender that isn't on
 *       the web app's domain, no SMTP credentials, or no signing secret for the provider's webhook (ADR 0036);
 *   <li>{@code springdoc.api-docs.enabled}: the API contract is generated in tests and committed, never served
 *       (ADR 0018);
 *   <li>no {@code identity.invite-code-key}, the HMAC key for invite and recovery codes (ADR 0024), or either it or
 *       {@code identity.admin.totp-key} set to the tests' committed value (application-test.yaml, which ships in the
 *       jar and is read from there, so the values are never copied);
 *   <li>{@code identity.invite-only} false, which lets anyone in without a code (ADR 0024);
 *   <li>no grievance officer ({@code legal.grievance.*}), or development's placeholder (ADR 0037);
 *   <li>no {@code identity.provider-admin.credentials}, the sign-in provider's service account that erasing an account
 *       deletes its sign-in identity with (ADR 0037);
 *   <li>a renter limit above production's ({@link #PRODUCTION_LIMITS}, ADR 0025), as the end-to-end harness and the
 *       tests lift them;
 *   <li>no OTLP endpoint for metrics, traces or logs, one that is plain HTTP or on this machine, or any of the three
 *       exports turned off ({@link #OTLP_ENDPOINTS}, ADR 0036);
 *   <li>no backup storage to watch ({@code backups.storage.*}), one that is plain HTTP or on this machine, or
 *       {@code backups.create-bucket}, development's (ADR 0036);
 *   <li>Razorpay's test keys, an API other than Razorpay's (the fake), live keys without a webhook secret or without
 *       the GSTIN, legal name and address invoices carry, or TCS on (ADR 0039); and live keys anywhere but prod;
 *   <li>RazorpayX's test keys, an API other than Razorpay's, keys without a webhook secret or account number, live keys
 *       before the CA has confirmed the TDS settings ({@code legal.tds.confirmed}), no PAN key or the tests' one, or the
 *       fake PAN check (ADR 0040); and RazorpayX's live keys anywhere but prod;
 *   <li>an {@code images.platform} other than linux/amd64, any {@code images.dev-registries}, or no scanner token or
 *       registry-token key, or the tests' (ADR 0042).
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
            "limits.vitals-per-minute", 30,
            "limits.support-messages-per-hour", 5,
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

    /** Where the database's backups are (ADR 0036), besides the endpoint: all needed to watch them. */
    static final List<String> BACKUP_SETTINGS = List.of(
            "backups.storage.bucket",
            "backups.storage.prefix",
            "backups.storage.access-key-id",
            "backups.storage.secret-access-key");

    /** The keys whose committed test values production must never use. */
    static final List<String> SECRET_KEYS = List.of(
            "identity.admin.totp-key",
            "identity.invite-code-key",
            "notifications.email.webhook-secret",
            "payouts.pan.key",
            "images.scanner.token",
            "images.registry-token.key");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        var environment = beanFactory.getBean(Environment.class);
        if (!environment.matchesProfiles("prod")) {
            // Live keys take real money: only production may hold them (ADR 0039).
            if (Binder.get(environment)
                    .bind("billing.razorpay.key-id", String.class)
                    .orElse("")
                    .strip()
                    .startsWith("rzp_live_")) {
                throw new IllegalStateException("Refusing to start with Razorpay's live keys outside the prod profile");
            }
            // Live RazorpayX keys move real money out: only production may hold them (ADR 0040).
            if (Binder.get(environment)
                    .bind("payouts.razorpayx.key-id", String.class)
                    .orElse("")
                    .strip()
                    .startsWith("rzp_live_")) {
                throw new IllegalStateException(
                        "Refusing to start with RazorpayX's live keys outside the prod profile");
            }
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
        // The grievance officer the pages name (ADR 0037): a real one, never development's placeholder.
        for (var field : List.of("legal.grievance.name", "legal.grievance.email", "legal.grievance.postal-address")) {
            var value = binder.bind(field, String.class).orElse("").toLowerCase(Locale.ROOT);
            if (value.isBlank() || value.contains("example.invalid") || value.contains("development")) {
                problems.add(field + " is missing or development's placeholder");
            }
        }
        // Erasing an account deletes it at the sign-in provider too (ADR 0037): never without the means to.
        if (binder.bind("identity.provider-admin.credentials", String.class)
                .orElse("")
                .isBlank()) {
            problems.add("identity.provider-admin.credentials is missing");
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
        if (!from.isBlank() && !isOurDomain(from, emailLinks)) {
            // Never the address itself: the log takes no email address, ours included.
            problems.add("notifications.email.from isn't on the web app's domain");
        }
        if (!binder.bind("notifications.email.webhook-secret", String.class)
                .orElse("")
                .strip()
                .startsWith("whsec_")) {
            problems.add("notifications.email.webhook-secret is missing");
        }
        for (var credential : List.of("spring.mail.username", "spring.mail.password")) {
            if (binder.bind(credential, String.class).orElse("").isBlank()) {
                problems.add(credential + " is missing");
            }
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
        var backupEndpoint =
                binder.bind("backups.storage.endpoint", String.class).orElse("");
        if (backupEndpoint.isBlank()) {
            problems.add("backups.storage.endpoint is missing");
        } else if (isDevelopmentIssuer(backupEndpoint)) {
            problems.add("backups.storage.endpoint " + backupEndpoint + " is plain HTTP or on this machine");
        }
        for (var setting : BACKUP_SETTINGS) {
            if (binder.bind(setting, String.class).orElse("").isBlank()) {
                problems.add(setting + " is missing");
            }
        }
        if (binder.bind("backups.create-bucket", Boolean.class).orElse(false)) {
            problems.add("backups.create-bucket is true");
        }
        problems.addAll(paymentProblems(binder));
        problems.addAll(payoutProblems(binder));
        problems.addAll(imageProblems(binder));
        new TreeMap<>(PRODUCTION_LIMITS).forEach((name, ceiling) -> {
            var value = binder.bind(name, Integer.class);
            if (value.isBound() && value.get() > ceiling) {
                problems.add(name + " is " + value.get() + ", above production's " + ceiling);
            }
        });
        return problems;
    }

    /**
     * Money in (ADR 0039): production may run without Razorpay (payments off), but with a key only live keys, Razorpay's
     * own API, a webhook secret, and a GSTIN, legal name and address to put on the invoices; and never TCS, which
     * isn't built.
     */
    static List<String> paymentProblems(Binder binder) {
        var problems = new ArrayList<String>();
        var apiUrl = binder.bind("billing.razorpay.api-url", String.class).orElse("https://api.razorpay.com");
        if (!apiUrl.strip().replaceAll("/+$", "").equals("https://api.razorpay.com")) {
            problems.add("billing.razorpay.api-url " + apiUrl + " isn't Razorpay's");
        }
        if (binder.bind("legal.invoicing.tcs", Boolean.class).orElse(false)) {
            problems.add("legal.invoicing.tcs is true, which isn't built");
        }
        var keyId =
                binder.bind("billing.razorpay.key-id", String.class).orElse("").strip();
        if (keyId.isEmpty()) {
            return problems;
        }
        if (!keyId.startsWith("rzp_live_")) {
            problems.add("billing.razorpay.key-id isn't a live key");
        }
        if (binder.bind("billing.razorpay.webhook-secret", String.class)
                .orElse("")
                .isBlank()) {
            problems.add("billing.razorpay.webhook-secret is missing");
        }
        for (var field : List.of("legal.invoicing.gstin", "legal.invoicing.legal-name", "legal.invoicing.address")) {
            if (binder.bind(field, String.class).orElse("").isBlank()) {
                problems.add(field + " is missing, and live payments need it on their invoices");
            }
        }
        return problems;
    }

    /**
     * Renters' own images (ADR 0042): only linux/amd64, no development registry at a private address, and the scanner's
     * token and the registry tokens' key both set.
     */
    static List<String> imageProblems(Binder binder) {
        var problems = new ArrayList<String>();
        var platform = binder.bind("images.platform", String.class)
                .orElse("linux/amd64")
                .strip();
        if (!platform.equals("linux/amd64")) {
            problems.add("images.platform is " + platform + ", not linux/amd64");
        }
        if (!binder.bind("images.dev-registries", Bindable.listOf(String.class))
                .orElse(List.of())
                .isEmpty()) {
            problems.add("images.dev-registries names development registries");
        }
        if (binder.bind("images.scanner.token", String.class).orElse("").strip().length() < 32) {
            problems.add("images.scanner.token is missing or shorter than 32 characters");
        }
        if (binder.bind("images.registry-token.key", String.class).orElse("").isBlank()) {
            problems.add("images.registry-token.key is missing");
        }
        return problems;
    }

    /**
     * Money out (ADR 0040): production may run without RazorpayX (payouts off), but always with a PAN key and never the
     * fake PAN check; with a key, only live keys, Razorpay's own API, a webhook secret and the account to pay from, and
     * only once the CA has confirmed the TDS settings.
     */
    static List<String> payoutProblems(Binder binder) {
        var problems = new ArrayList<String>();
        if (binder.bind("payouts.pan.key", String.class).orElse("").isBlank()) {
            problems.add("payouts.pan.key is missing");
        }
        if (binder.bind("payouts.pan.verifier", String.class)
                .orElse("manual")
                .strip()
                .equalsIgnoreCase("fake")) {
            problems.add("payouts.pan.verifier is fake");
        }
        var apiUrl = binder.bind("payouts.razorpayx.api-url", String.class).orElse("https://api.razorpay.com");
        if (!apiUrl.strip().replaceAll("/+$", "").equals("https://api.razorpay.com")) {
            problems.add("payouts.razorpayx.api-url " + apiUrl + " isn't Razorpay's");
        }
        var keyId =
                binder.bind("payouts.razorpayx.key-id", String.class).orElse("").strip();
        if (keyId.isEmpty()) {
            return problems;
        }
        if (!keyId.startsWith("rzp_live_")) {
            problems.add("payouts.razorpayx.key-id isn't a live key");
        }
        for (var field : List.of("payouts.razorpayx.webhook-secret", "payouts.razorpayx.account-number")) {
            if (binder.bind(field, String.class).orElse("").isBlank()) {
                problems.add(field + " is missing");
            }
        }
        if (!binder.bind("legal.tds.confirmed", Boolean.class).orElse(false)) {
            problems.add("legal.tds.confirmed is false, and live payouts deduct tax only as the CA has confirmed");
        }
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

    /**
     * Whether the sender's address is on the web app's domain or one under it: {@code notifications@mail.idleo.in} for
     * {@code https://app.idleo.in}, whose domain is {@code idleo.in}. A provider sends only from a domain whose DNS we
     * control (docs/runbooks/email.md).
     */
    static boolean isOurDomain(String from, String webAppUrl) {
        try {
            var host = URI.create(webAppUrl.strip()).getHost();
            if (host == null || !host.contains(".")) {
                return false;
            }
            var site = host.toLowerCase(Locale.ROOT).substring(host.indexOf('.') + 1);
            var address = from.contains("<") ? from.substring(from.indexOf('<') + 1, from.lastIndexOf('>')) : from;
            var domain = address.substring(address.lastIndexOf('@') + 1).strip().toLowerCase(Locale.ROOT);
            return site.contains(".") ? domain.equals(site) || domain.endsWith("." + site) : domain.equals(host);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return false;
        }
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
