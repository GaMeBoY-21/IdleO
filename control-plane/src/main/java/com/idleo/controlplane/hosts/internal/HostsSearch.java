package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The console's search over hosts (ADR 0052): by id, or by part of a name. */
@Configuration(proxyBeanMethods = false)
class HostsSearch {

    private static final int SHORTEST_NAME = 3;

    @Bean
    AdminSearch hostsAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("host", AdminPermission.HOSTS_READ, (query, limit) -> {
            var id = query.id();
            if (id.isEmpty() && query.text().length() < SHORTEST_NAME) {
                return List.of();
            }
            var statement = id.isPresent()
                    ? jdbc.sql("select id, name, status from hosts.hosts where id = :id")
                            .param("id", id.get())
                    : jdbc.sql("""
                                    select id, name, status from hosts.hosts
                                    where name operator(public.%) :name
                                    order by public.similarity(name, :name) desc, id desc
                                    limit :limit
                                    """).param("name", query.text()).param("limit", limit);
            return statement
                    .query((rs, n) -> new AdminSearch.SearchHit(
                            "host", rs.getObject("id").toString(), rs.getString("name"), rs.getString("status")))
                    .list();
        });
    }
}
