package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminSearch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The console's search over renters' images (ADR 0052): by digest, with or without its {@code sha256:}, or by id. */
@Configuration(proxyBeanMethods = false)
class ImagesSearch {

    private static final String PREFIX = "sha256:";

    @Bean
    AdminSearch imagesAdminSearch(JdbcClient jdbc) {
        return AdminSearch.of("image", AdminPermission.IMAGES_READ, (query, limit) -> {
            var id = query.id();
            var statement = id.isPresent()
                    ? jdbc.sql("select id, reference, state from images.images where id = :id")
                            .param("id", id.get())
                    : jdbc.sql("""
                                    select id, reference, state from images.images
                                    where digest = :digest
                                    order by id desc
                                    limit :limit
                                    """)
                            .param("digest", query.lower().startsWith(PREFIX) ? query.lower() : PREFIX + query.lower())
                            .param("limit", limit);
            return statement
                    .query((rs, n) -> new AdminSearch.SearchHit(
                            "image", rs.getObject("id").toString(), rs.getString("reference"), rs.getString("state")))
                    .list();
        });
    }
}
