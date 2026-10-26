package com.idleo.controlplane.images.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.RetentionPeriods;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The images module's retention rules (ADR 0042, ADR 0037): a registry token {@code legal.retention.registry-tokens}
 * after it was last used (the images that named it can't be pulled again without a new one); a submission {@code
 * retention.periods.images} after it was last booked or checked; and a digest's findings {@code
 * retention.periods.image-scans} after no renter's image names it, with the hashes of its files.
 */
@Configuration(proxyBeanMethods = false)
class ImagesRetention {

    @Bean
    RetentionRule registryTokensRule(JdbcClient jdbc, LegalSettings legal) {
        return RetentionRule.of(
                "registry-tokens",
                now -> jdbc.sql("delete from images.registry_logins where last_used_at < :before")
                        .param(
                                "before",
                                Timestamp.from(now.minus(legal.retention().registryTokens())))
                        .update());
    }

    @Bean
    RetentionRule imagesRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "images",
                now -> jdbc.sql("""
                                delete from images.images
                                where state in ('ready', 'blocked', 'failed')
                                  and coalesce(last_used_at, checked_at, created_at) < :before
                                """)
                        .param("before", Timestamp.from(now.minus(periods.of("images"))))
                        .update());
    }

    @Bean
    RetentionRule imageScansRule(JdbcClient jdbc, RetentionPeriods periods) {
        return RetentionRule.of(
                "image-scans",
                now -> jdbc.sql("""
                                delete from images.scans s
                                where s.checked_at < :before
                                  and not exists (select 1 from images.images i where i.digest = s.digest)
                                """)
                        .param("before", Timestamp.from(now.minus(periods.of("image-scans"))))
                        .update());
    }
}
