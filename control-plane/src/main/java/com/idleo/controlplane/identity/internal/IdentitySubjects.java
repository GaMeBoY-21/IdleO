package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminSubject;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A user is about themselves (ADR 0052). */
@Configuration(proxyBeanMethods = false)
class IdentitySubjects {

    @Bean
    AdminSubject userSubject(JdbcClient jdbc) {
        return AdminSubject.of(
                "user",
                id -> jdbc.sql("select id from identity.users where id = :id")
                        .param("id", id)
                        .query(UUID.class)
                        .optional());
    }
}
