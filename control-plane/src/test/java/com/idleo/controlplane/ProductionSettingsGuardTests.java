package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/** The prod profile refuses development settings at startup (ADR 0013). */
class ProductionSettingsGuardTests {

    private static final String GOOGLE = "https://securetoken.google.com/demo-project";
    /** A key production might have: random, and in no file of this repository but this test. */
    private static final String PRODUCTION_KEY = "q0Rz4mL8YkV2wN6cT1pH9sJ3uE5bA7dG0fK2xC4vB8M=";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProductionSettingsGuard.class);

    private ApplicationContextRunner prod(String... properties) {
        // Production's web app and email, unless a test sets another.
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "identity.web-app-url=https://app.example.invalid",
                        "notifications.web-app-url=https://app.example.invalid",
                        "notifications.email.from=IdleO <notifications@example.invalid>",
                        "spring.mail.host=smtp.example.invalid",
                        "identity.invite-code-key=" + PRODUCTION_KEY)
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
}
