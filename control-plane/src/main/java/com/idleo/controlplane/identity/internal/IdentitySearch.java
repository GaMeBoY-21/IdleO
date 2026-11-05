package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The console's search over people (ADR 0052): by id, by an exact email, or by part of a name. */
@Configuration(proxyBeanMethods = false)
class IdentitySearch {

    private static final int SHORTEST_NAME = 3;
    private static final String SELECT = "select id, email, display_name, status from identity.users ";

    @Bean
    AdminSearch usersSearch(JdbcClient jdbc) {
        return AdminSearch.of("user", AdminPermission.USERS_READ, (query, limit) -> {
            var id = query.id();
            if (id.isPresent()) {
                return found(jdbc.sql(SELECT + "where id = :id").param("id", id.get()), limit);
            }
            if (query.text().contains("@")) {
                return found(
                        jdbc.sql(SELECT + "where lower(email) = :email limit :limit")
                                .param("email", query.lower())
                                .param("limit", limit),
                        limit);
            }
            if (query.text().length() < SHORTEST_NAME) {
                return List.of();
            }
            return found(jdbc.sql(SELECT + """
                                    where display_name operator(public.%) :name
                                    order by public.similarity(display_name, :name) desc, id desc
                                    limit :limit
                                    """).param("name", query.text()).param("limit", limit), limit);
        });
    }

    private static List<AdminSearch.SearchHit> found(JdbcClient.StatementSpec statement, int limit) {
        return statement
                .query((rs, n) -> {
                    var email = rs.getString("email");
                    var name = rs.getString("display_name");
                    return new AdminSearch.SearchHit(
                            "user",
                            rs.getObject("id").toString(),
                            name != null ? name : email != null ? email : "No name or email",
                            email == null ? rs.getString("status") : email + " · " + rs.getString("status"));
                })
                .list()
                .stream()
                .limit(limit)
                .toList();
    }
}
