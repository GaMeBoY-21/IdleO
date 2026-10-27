package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/** The prod profile refuses development settings at startup (ADR 0013). */
class ProductionSettingsGuardTests {

    private static final String GOOGLE = "https://securetoken.google.com/demo-project";
    /** A key production might have: random, and in no file of this repository but this test. */
    private static final String PRODUCTION_KEY = "q0Rz4mL8YkV2wN6cT1pH9sJ3uE5bA7dG0fK2xC4vB8M=";

    private static final String OTLP = "https://otlp.example.invalid/otlp";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProductionSettingsGuard.class);

    private ApplicationContextRunner prod(String... properties) {
        // Production's web app and email, unless a test sets another.
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "identity.web-app-url=https://app.example.invalid",
                        "notifications.web-app-url=https://app.example.invalid",
                        "hosts.link.web-app-url=https://app.example.invalid",
                        "notifications.email.from=IdleO <notifications@example.invalid>",
                        "spring.mail.host=smtp.example.invalid",
                        "spring.mail.username=resend",
                        "spring.mail.password=" + PRODUCTION_KEY,
                        "notifications.email.webhook-secret=whsec_" + PRODUCTION_KEY,
                        "identity.invite-code-key=" + PRODUCTION_KEY,
                        "payouts.pan.key=" + PRODUCTION_KEY,
                        "images.scanner.token=production-scanner-token-0123456789abcdef",
                        "images.registry-token.key=" + PRODUCTION_KEY,
                        "identity.provider-admin.credentials=file:/run/secrets/identity-provider-admin.json",
                        "legal.grievance.name=Asha Rao",
                        "legal.grievance.email=grievance@idleo.in",
                        "legal.grievance.postal-address=A real address",
                        "management.otlp.metrics.export.url=" + OTLP + "/v1/metrics",
                        "management.opentelemetry.tracing.export.otlp.endpoint=" + OTLP + "/v1/traces",
                        "management.opentelemetry.logging.export.otlp.endpoint=" + OTLP + "/v1/logs",
                        "backups.storage.endpoint=https://account.r2.example.invalid",
                        "backups.storage.bucket=idleo-backups",
                        "backups.storage.prefix=production",
                        "backups.storage.access-key-id=reader",
                        "backups.storage.secret-access-key=" + PRODUCTION_KEY)
                .withPropertyValues(properties);
    }

    @Test
    void prodRefusesToStartWithDevelopmentHostsAllowed() {
        prod("agentgateway.allow-development-hosts=true", "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("Refusing to start the prod profile with development settings: "
                                + "agentgateway.allow-development-hosts is true"));
    }

    @Test
    void prodRefusesToStartWithALocalOrPlainHttpIssuer() {
        for (var issuer : new String[] {
            "http://localhost:8099/idleo-local",
            "https://localhost/idleo-local",
            "https://127.0.0.1:8443/x",
            "http://idp.example.invalid/realm"
        }) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.issuers[1].issuer-uri=" + issuer)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining("identity issuer " + issuer));
        }
    }

    @Test
    void prodRefusesToStartWithLocalObjectStorage() {
        prod("sessions.storage.provider=minio", "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("sessions.storage.provider is minio"));
    }

    @Test
    void prodRefusesToStartWithADevelopmentTemplateCatalog() {
        prod(
                        "sessions.templates.catalog=file:../templates/development.yaml",
                        "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(
                        context -> assertThat(context)
                                .hasFailed()
                                .getFailure()
                                .hasMessageContaining(
                                        "sessions.templates.catalog is file:../templates/development.yaml, not the bundled catalog"));
        prod("sessions.templates.catalog=classpath:templates/catalog.yaml", "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesLocalOrWildcardWebOrigins() {
        for (var origin :
                new String[] {"*", "http://localhost:3000", "https://127.0.0.1", "http://app.example.invalid"}) {
            prod(
                            "web.allowed-origins=https://app.example.invalid," + origin,
                            "identity.issuers[0].issuer-uri=" + GOOGLE)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining("web origin " + origin + " is *, plain HTTP or on this machine"));
        }
        prod("web.allowed-origins=https://app.example.invalid", "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesToServeTheApiDocuments() {
        prod("springdoc.api-docs.enabled=true", "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("springdoc.api-docs.enabled is true"));
    }

    @Test
    void prodRefusesAWebAppForCliSignInsThatIsLocalOrPlainHttp() {
        for (var url : new String[] {"http://localhost:3000", "https://127.0.0.1", "http://app.example.invalid"}) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.web-app-url=" + url)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining("identity.web-app-url " + url));
        }
    }

    @Test
    void prodStartsWithProductionSettings() {
        prod("agentgateway.allow-development-hosts=false", "identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void otherProfilesMayUseDevelopmentSettings() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues(
                        "agentgateway.allow-development-hosts=true",
                        "identity.issuers[0].issuer-uri=http://localhost:8099/idleo-local")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesAHostLinkAddressThatIsPlainHttpOrOnThisMachine() {
        for (var url : new String[] {"http://localhost:3000", "http://app.example.invalid", "http://127.0.0.1:3000"}) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, "hosts.link.web-app-url=" + url)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining("hosts.link.web-app-url " + url));
        }
    }

    @Test
    void prodRefusesDevelopmentEmailSettings() {
        for (var setting : new String[][] {
            {"notifications.web-app-url=http://localhost:3000", "notifications.web-app-url http://localhost:3000"},
            {"notifications.web-app-url=http://app.example.invalid", "notifications.web-app-url http://app"},
            {"notifications.email.from=IdleO <notifications@localhost>", "notifications.email.from"},
            {"spring.mail.host=localhost", "spring.mail.host is localhost, on this machine"},
            {"spring.mail.host=", "spring.mail.host is missing"},
            {"notifications.email.provider=recording", "notifications.email.provider is recording, not smtp"}
        }) {
            prod(setting[0], "identity.issuers[0].issuer-uri=" + GOOGLE)
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
    }

    @Test
    void prodRefusesToStartWithoutTheCodesHmacKey() {
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.invite-code-key=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("identity.invite-code-key is missing"));
    }

    @Test
    void prodRefusesTheTestsCommittedKeys() throws Exception {
        var committed = new YamlPropertySourceLoader()
                .load("test", new ClassPathResource("application-test.yaml"))
                .getFirst();
        for (var key : ProductionSettingsGuard.SECRET_KEYS) {
            var value = committed.getProperty(key);
            assertThat(value).as(key).isNotNull();
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, key + "=" + value)
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining(key + " is the tests' committed value"));
        }
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.admin.totp-key=" + PRODUCTION_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesAnotherImagePlatformAndDevelopmentRegistries() {
        for (var setting : new String[][] {
            {"images.platform=linux/arm64", "images.platform is linux/arm64, not linux/amd64"},
            {"images.dev-registries[0]=registry:5000", "images.dev-registries names development registries"},
        }) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, setting[0])
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
    }

    /** The scanner's limits as application.yaml gives them: this runner doesn't read the file. */
    private static final String[] IMAGE_LIMITS = {
        "images.max-compressed=15000000000B",
        "images.max-layers=127",
        "images.max-layer-unpacked=40000000000B",
        "images.max-image-unpacked=60000000000B",
        "images.pull-time-limit=20m",
        "images.check-deadline=60m",
        "images.claim-lasts=2m",
    };

    @Test
    void prodStartsWithOwnImagesOffAndNeedsNoScannerSecretsForThat() throws IOException {
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "images.scanner.token=", "images.registry-token.key=")
                .run(context -> assertThat(context).hasNotFailed());
        var committed = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"))
                .getFirst();
        assertThat(committed.getProperty("images.enabled")).isEqualTo("${IMAGES_ENABLED:false}");
    }

    @Test
    void prodAllowsOwnImagesOnlyWithTheScannersLimitsItsTokenAndTheRegistryTokenKey() {
        prod(concat("identity.issuers[0].issuer-uri=" + GOOGLE, "images.enabled=true"))
                .run(context -> assertThat(context).hasNotFailed());
        for (var setting : new String[][] {
            {"images.scanner.token=short", "images.scanner.token is missing or shorter than 32 characters"},
            {"images.scanner.token=", "images.scanner.token is missing or shorter than 32 characters"},
            {"images.registry-token.key=", "images.registry-token.key is missing"},
            {"images.max-compressed=0B", "images.max-compressed is missing or zero"},
            {"images.max-layer-unpacked=0B", "images.max-layer-unpacked is missing or zero"},
            {"images.max-image-unpacked=0B", "images.max-image-unpacked is missing or zero"},
            {"images.max-layers=0", "images.max-layers is missing or zero"},
            {"images.pull-time-limit=0s", "images.pull-time-limit is missing or zero"},
            {"images.check-deadline=0s", "images.check-deadline is missing or zero"},
            {"images.claim-lasts=0s", "images.claim-lasts is missing or zero"},
        }) {
            prod(concat("identity.issuers[0].issuer-uri=" + GOOGLE, "images.enabled=true", setting[0]))
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
        // A limit left out altogether is as bad as a zero one.
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "images.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("images.max-compressed is missing or zero"));
    }

    /** The image limits first, then the settings given, which override them. */
    private static String[] concat(String... settings) {
        return java.util.stream.Stream.concat(java.util.Arrays.stream(IMAGE_LIMITS), java.util.Arrays.stream(settings))
                .toArray(String[]::new);
    }

    @Test
    void prodRefusesToStartWithoutTheSignInProvidersServiceAccount() {
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.provider-admin.credentials=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("identity.provider-admin.credentials is missing"));
    }

    @Test
    void prodRefusesTheGrievanceOfficersPlaceholder() {
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "legal.grievance.email=grievance@example.invalid")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("legal.grievance.email is missing or development's placeholder"));
    }

    @Test
    void prodRefusesToLetAnyoneInWithoutAnInviteCode() {
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.invite-only=false")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("identity.invite-only is false"));
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "identity.invite-only=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesTheRenterLimitsLiftedButMayTightenThem() {
        ProductionSettingsGuard.PRODUCTION_LIMITS.forEach((name, ceiling) -> {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, name + "=" + (ceiling + 1))
                    .run(context -> assertThat(context)
                            .hasFailed()
                            .getFailure()
                            .hasMessageContaining(name + " is " + (ceiling + 1) + ", above production's " + ceiling));
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, name + "=" + ceiling)
                    .run(context -> assertThat(context).hasNotFailed());
        });
    }

    @Test
    void theGuardsLimitsAreTheOnesApplicationYamlShips() throws Exception {
        var shipped = new YamlPropertySourceLoader()
                .load("shipped", new ClassPathResource("application.yaml"))
                .getFirst();
        ProductionSettingsGuard.PRODUCTION_LIMITS.forEach(
                (name, ceiling) -> assertThat(Integer.parseInt(String.valueOf(shipped.getProperty(name))))
                        .as(name)
                        .isEqualTo(ceiling));
    }

    @Test
    void prodRefusesToStartWithoutSomewhereToSendMetricsTracesAndLogs() {
        ProductionSettingsGuard.OTLP_ENDPOINTS.forEach((endpoint, enabled) -> {
            for (var setting : new String[][] {
                {endpoint + "=", endpoint + " is missing"},
                // As application-prod.yaml reads it with OTLP_ENDPOINT unset.
                {endpoint + "=${OTLP_ENDPOINT}/v1/x", endpoint + " is missing"},
                {endpoint + "=http://otlp.example.invalid/v1/x", endpoint + " http://otlp.example.invalid/v1/x"},
                {endpoint + "=http://localhost:4318/v1/x", endpoint + " http://localhost:4318/v1/x"},
                {enabled + "=false", enabled + " is false"}
            }) {
                prod("identity.issuers[0].issuer-uri=" + GOOGLE, setting[0])
                        .run(context ->
                                assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
            }
        });
    }

    @Test
    void prodRefusesToStartWithoutBackupsToWatch() {
        for (var setting : new String[][] {
            {"backups.storage.endpoint=", "backups.storage.endpoint is missing"},
            {"backups.storage.endpoint=http://localhost:9100", "backups.storage.endpoint http://localhost:9100"},
            {"backups.create-bucket=true", "backups.create-bucket is true"}
        }) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, setting[0])
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
        for (var setting : ProductionSettingsGuard.BACKUP_SETTINGS) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, setting + "=")
                    .run(context ->
                            assertThat(context).hasFailed().getFailure().hasMessageContaining(setting + " is missing"));
        }
    }

    @Test
    void prodTakesPaymentsOnlyWithLiveKeysRazorpaysApiAWebhookSecretAndWhatInvoicesNeed() {
        var live = new String[] {
            "identity.issuers[0].issuer-uri=" + GOOGLE,
            "billing.razorpay.key-id=rzp_live_IdleoNotARealKey",
            "billing.razorpay.key-secret=" + PRODUCTION_KEY,
            "billing.razorpay.webhook-secret=" + PRODUCTION_KEY,
            "legal.invoicing.gstin=29AAGCB7383J1Z4",
            "legal.invoicing.legal-name=IdleO Technologies Private Limited",
            "legal.invoicing.address=A real address"
        };
        prod(live).run(context -> assertThat(context).hasNotFailed());
        prod("identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context)
                        .as("without Razorpay, payments are off")
                        .hasNotFailed());
        for (var setting : new String[][] {
            {"billing.razorpay.key-id=rzp_test_IdleoNotARealKey", "billing.razorpay.key-id isn't a live key"},
            {"billing.razorpay.webhook-secret=", "billing.razorpay.webhook-secret is missing"},
            {"billing.razorpay.api-url=http://localhost:8091", "billing.razorpay.api-url http://localhost:8091"},
            {"legal.invoicing.gstin=", "legal.invoicing.gstin is missing"},
            {"legal.invoicing.legal-name=", "legal.invoicing.legal-name is missing"},
            {"legal.invoicing.address=", "legal.invoicing.address is missing"},
            {"legal.invoicing.tcs=true", "legal.invoicing.tcs is true"}
        }) {
            prod(live)
                    .withPropertyValues(setting[0])
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
    }

    @Test
    void prodPaysOutOnlyWithLiveKeysAWebhookSecretAnAccountThePanKeyAndTheCasTds() {
        var live = new String[] {
            "identity.issuers[0].issuer-uri=" + GOOGLE,
            "payouts.razorpayx.key-id=rzp_live_IdleoNotARealKey",
            "payouts.razorpayx.key-secret=" + PRODUCTION_KEY,
            "payouts.razorpayx.webhook-secret=" + PRODUCTION_KEY,
            "payouts.razorpayx.account-number=409001396356",
            "legal.tds.confirmed=true"
        };
        prod(live).run(context -> assertThat(context).hasNotFailed());
        prod("identity.issuers[0].issuer-uri=" + GOOGLE)
                .run(context -> assertThat(context)
                        .as("without RazorpayX, payouts are off")
                        .hasNotFailed());
        for (var setting : new String[][] {
            {"payouts.razorpayx.key-id=rzp_test_IdleoNotARealKey", "payouts.razorpayx.key-id isn't a live key"},
            {"payouts.razorpayx.webhook-secret=", "payouts.razorpayx.webhook-secret is missing"},
            {"payouts.razorpayx.account-number=", "payouts.razorpayx.account-number is missing"},
            {"payouts.razorpayx.api-url=http://localhost:8092", "payouts.razorpayx.api-url http://localhost:8092"},
            {"legal.tds.confirmed=false", "legal.tds.confirmed is false"},
            {"payouts.pan.key=", "payouts.pan.key is missing"},
            {"payouts.pan.verifier=fake", "payouts.pan.verifier is fake"}
        }) {
            prod(live)
                    .withPropertyValues(setting[0])
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
    }

    @Test
    void liveRazorpayXKeysAreRefusedOutsideProd() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("payouts.razorpayx.key-id=rzp_live_IdleoNotARealKey")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("RazorpayX's live keys outside the prod profile"));
    }

    @Test
    void liveKeysAreRefusedOutsideProd() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("billing.razorpay.key-id=rzp_live_IdleoNotARealKey")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("live keys outside the prod profile"));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("billing.razorpay.key-id=rzp_test_IdleoNotARealKey")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void prodRefusesASenderOffOurDomainAndEmailWithoutItsSecrets() {
        for (var setting : new String[][] {
            {"notifications.email.from=IdleO <alerts@gmail.com>", "isn't on the web app's domain"},
            {"notifications.email.from=IdleO <x@notexample.invalid>", "isn't on the web app's domain"},
            {"notifications.email.webhook-secret=", "notifications.email.webhook-secret is missing"},
            {"notifications.email.webhook-secret=not-a-svix-secret", "notifications.email.webhook-secret is missing"},
            {"spring.mail.username=", "spring.mail.username is missing"},
            {"spring.mail.password=", "spring.mail.password is missing"}
        }) {
            prod("identity.issuers[0].issuer-uri=" + GOOGLE, setting[0])
                    .run(context -> assertThat(context).hasFailed().getFailure().hasMessageContaining(setting[1]));
        }
        // A sending subdomain is ours too.
        prod("identity.issuers[0].issuer-uri=" + GOOGLE, "notifications.email.from=IdleO <n@mail.example.invalid>")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ourDomainIsTheWebAppsParentOrUnderIt() {
        assertThat(ProductionSettingsGuard.isOurDomain("IdleO <n@idleo.in>", "https://app.idleo.in"))
                .isTrue();
        assertThat(ProductionSettingsGuard.isOurDomain("n@mail.idleo.in", "https://app.idleo.in/"))
                .isTrue();
        assertThat(ProductionSettingsGuard.isOurDomain("n@idleo.in.evil.com", "https://app.idleo.in"))
                .isFalse();
        assertThat(ProductionSettingsGuard.isOurDomain("n@xidleo.in", "https://app.idleo.in"))
                .isFalse();
        assertThat(ProductionSettingsGuard.isOurDomain("n@resend.dev", "https://app.idleo.in"))
                .isFalse();
    }
}
