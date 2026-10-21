package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private static final ApplicationModules MODULES = ApplicationModules.of(ControlPlaneApplication.class);

    @Test
    void hasTheModulesFromTheTechnicalDesign() {
        assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
                .containsExactlyInAnyOrder(
                        "identity",
                        "hosts",
                        "agentgateway",
                        "overlay",
                        "scheduling",
                        "sessions",
                        "metering",
                        "billing",
                        "payouts",
                        "trust",
                        "notifications",
                        "privacy",
                        "support");
    }

    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        // Written to target/spring-modulith-docs; not committed.
        new Documenter(MODULES).writeDocumentation();
    }
}
