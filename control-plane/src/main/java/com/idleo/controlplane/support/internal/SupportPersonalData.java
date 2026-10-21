package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.RetentionRule;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * What support holds about a person (ADR 0037, docs/reference/personal-data-map.md): their messages and our answers. Erasure
 * empties every ticket's address and words; resolved tickets are deleted after {@code legal.retention.support-tickets}.
 */
@Configuration(proxyBeanMethods = false)
class SupportPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;

    SupportPersonalData(JdbcClient jdbc, ExportRows rows) {
        this.jdbc = jdbc;
        this.rows = rows;
    }

    @Override
    public String module() {
        return "support";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(jdbc.sql("select count(*), min(created_at), max(created_at) from support.tickets"
                        + " where user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new Group(
                        "messages",
                        "messages to support",
                        rs.getLong(1),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()))
                .single());
    }

    @Override
    public List<Part> export(UUID userId) {
        var tickets = rows.list(jdbc, """
                select id, number, topic, status, reply_to, session_id, host_id, created_at, due_at, resolved_at
                from support.tickets where user_id = :user order by created_at
                """, Map.of("user", userId));
        for (var ticket : tickets) {
            ticket.put(
                    "messages",
                    rows.list(
                            jdbc,
                            "select author, body, created_at from support.messages where ticket_id = :ticket"
                                    + " order by created_at, id",
                            Map.of("ticket", UUID.fromString((String) ticket.get("id")))));
        }
        return List.of(new Part("messages", "supportTickets", tickets));
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        if (step != ErasureStep.DELETE_RECORDS) {
            return;
        }
        jdbc.sql("""
                        update support.messages set body = ''
                        where body <> '' and ticket_id in (select id from support.tickets where user_id = :user)
                        """).param("user", userId).update();
        jdbc.sql("update support.tickets set reply_to = null where user_id = :user and reply_to is not null")
                .param("user", userId)
                .update();
    }

    /** Resolved tickets, with their messages, after {@code legal.retention.support-tickets}. */
    @Bean
    RetentionRule supportTicketsRule(LegalSettings legal) {
        return RetentionRule.of(
                "support-tickets",
                now -> jdbc.sql("delete from support.tickets where status = 'resolved' and resolved_at < :before")
                        .param(
                                "before",
                                Timestamp.from(now.minus(legal.retention().supportTickets())))
                        .update());
    }
}
