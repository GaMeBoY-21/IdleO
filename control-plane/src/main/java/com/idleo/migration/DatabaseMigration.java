package com.idleo.migration;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Runs Flyway's migrations, then exits: the chart's migration Job (deploy/helm/idleo, ADR 0045), as the migration
 * user, before a release's control plane starts. The control plane itself runs on the cluster profile with Flyway off,
 * as the application user, which can't change the schema.
 *
 * <p>Only the database and Flyway start, configured as the control plane's are (application.yaml's {@code
 * spring.flyway}), with the address and the migration user from the environment ({@code SPRING_DATASOURCE_*}): no web
 * or agent server, no scheduled job, nothing that could serve a request as the owner. It lives outside the control
 * plane's packages so the application's component scan never finds it.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class DatabaseMigration {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigration.class);

    public static void main(String[] args) {
        var application = new SpringApplication(DatabaseMigration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        // Flyway migrates while the context starts; a failed migration fails the start, and the Job with it.
        try (var context = application.run(args)) {
            var current = context.getBean(Flyway.class).info().current();
            log.info("The database is at version {}", current == null ? "none" : current.getVersion());
        }
    }
}
