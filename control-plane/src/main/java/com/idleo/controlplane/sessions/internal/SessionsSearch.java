package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The console's search over sessions (ADR 0052): by id. */
@Configuration(proxyBeanMethods = false)
class SessionsSearch {

    @Bean
    AdminSearch sessionsAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of(
                "session",
                AdminPermission.SESSIONS_READ,
                (query, limit) -> query.id()
                        .map(id -> jdbc.sql("select id, template_id, state from sessions.sessions where id = :id")
                                .param("id", id)
                                .query((rs, n) -> new AdminSearch.Hit(
                                        "session",
                                        rs.getObject("id").toString(),
                                        rs.getString("template_id"),
                                        rs.getString("state")))
                                .list())
                        .orElse(List.of()));
    }
}
