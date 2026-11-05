package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The console's search over tickets (ADR 0052): by number, with or without its #, or by id. */
@Configuration(proxyBeanMethods = false)
class SupportSearch {

    private static final String NUMBER = "#?[0-9]{1,18}";

    @Bean
    AdminSearch ticketsAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("ticket", AdminPermission.TICKETS_READ, (query, limit) -> {
            var id = query.id();
            if (id.isEmpty() && !query.text().matches(NUMBER)) {
                return List.of();
            }
            var statement = id.isPresent()
                    ? jdbc.sql("select id, number, topic, status from support.tickets where id = :id")
                            .param("id", id.get())
                    : jdbc.sql("select id, number, topic, status from support.tickets where number = :number")
                            .param("number", Long.parseLong(query.text().replace("#", "")));
            return statement
                    .query((rs, n) -> new AdminSearch.Hit(
                            "ticket",
                            rs.getObject("id").toString(),
                            "#" + rs.getLong("number"),
                            rs.getString("topic") + " · " + rs.getString("status")))
                    .list();
        });
    }
}
