package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The prod profile refuses development settings at startup (ADR 0013). */
class ProductionSettingsGuardTests {

    private static final String GOOGLE = "https://securetoken.google.com/demo-project";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProductionSettingsGuard.class);

    private ApplicationContextRunner prod(String... properties) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
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
}
