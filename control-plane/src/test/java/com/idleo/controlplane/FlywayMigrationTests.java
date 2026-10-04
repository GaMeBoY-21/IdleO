package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@ControlPlaneTest
class FlywayMigrationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsOneSchemaPerModulePlusFlywaysOwn() {
        var schemas = jdbc.queryForList(
                "select schema_name from information_schema.schemata where schema_name not like 'pg\\_%'"
                        + " and schema_name not in ('information_schema', 'public')",
                String.class);

        assertThat(schemas)
                .containsExactlyInAnyOrder(
                        "flyway",
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
                        "notifications");
    }

    @Test
    void recordsV1InFlywaysOwnSchema() {
        var applied = jdbc.queryForList(
                "select version from flyway.flyway_schema_history where success order by installed_rank", String.class);

        assertThat(applied).contains("1");
    }

    @Test
    void runsOnThePinnedPostgresMajorVersion() {
        var version = jdbc.queryForObject("show server_version", String.class);

        assertThat(version).startsWith("18.");
    }
}
