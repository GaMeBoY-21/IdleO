package com.idleo.controlplane.trust.internal;

import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The trust module's retention rules for the review of abuse (ADR 0038, ADR 0037): what each job did each minute is kept
 * {@code legal.retention.behaviour} (90 days), and a settled review item with its signals and notes {@code
 * legal.retention.abuse-reviews} after it was settled. An open item is never deleted.
 */
@Configuration(proxyBeanMethods = false)
class AbuseRetention {

    @Bean
    RetentionRule behaviourSamplesRule(JdbcClient jdbc, LegalSettings legal) {
        return RetentionRule.of(
                "behaviour-samples",
                now -> jdbc.sql("delete from trust.behavior_samples where at < :before")
                        .param(
                                "before",
                                Timestamp.from(now.minus(legal.retention().behaviour())))
                        .update());
    }

    @Bean
    RetentionRule abuseReviewsRule(JdbcClient jdbc, LegalSettings legal) {
        return RetentionRule.of("abuse-reviews", now -> {
            var before = Timestamp.from(now.minus(legal.retention().abuseReviews()));
            var settled = "select id from trust.abuse_items where resolved_at is not null and resolved_at < :before";
            jdbc.sql("delete from trust.abuse_notes where item_id in (" + settled + ")")
                    .param("before", before)
                    .update();
            jdbc.sql("delete from trust.abuse_signals where item_id in (" + settled + ")")
                    .param("before", before)
                    .update();
            return jdbc.sql("delete from trust.abuse_items where resolved_at is not null and resolved_at < :before")
                    .param("before", before)
                    .update();
        });
    }
}
