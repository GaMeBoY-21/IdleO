package com.idleo.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** A real PostgreSQL and a real Valkey, of the pinned versions, wired in as the application's own. */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /** Same image as compose.yaml; {@link ComposeImageTests} keeps the two identical. */
    static final String POSTGRES_IMAGE =
            "postgres:18.6@sha256:5a5a84b19854a9ffaa54082c166ff4ec27473a361e496e5ea167f298f2da9722";

    /** Same image as compose.yaml; {@link ComposeImageTests} keeps the two identical. */
    static final String VALKEY_IMAGE =
            "valkey/valkey:9.1.2@sha256:418652cfb58ef879d4978c33553735d7147016032d5aefaa14c828e611eb9dfd";

    /** Throwaway, test-only, and distinctive, so log tests can prove it never appears. */
    static final String DATABASE_PASSWORD = "tc-only-password-3f9c1d";

    static PostgreSQLContainer newPostgres() {
        return new PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE).asCompatibleSubstituteFor("postgres"))
                .withPassword(DATABASE_PASSWORD);
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return newPostgres();
    }

    static GenericContainer<?> newValkey() {
        return new GenericContainer<>(DockerImageName.parse(VALKEY_IMAGE)).withExposedPorts(6379);
    }

    /** Valkey speaks the Redis protocol; Spring Boot connects to it as Redis. */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> valkey() {
        return newValkey();
    }
}
