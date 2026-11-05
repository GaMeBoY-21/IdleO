package com.idleo.controlplane.support.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.WorkQueue;
import com.idleo.controlplane.identity.AuditTrail;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What support has waiting (ADR 0053): tickets and grievances, and privacy requests (a correction comes as one of these).
 * Every answer goes out the way an answer on the ticket's own screen does.
 */
@Configuration(proxyBeanMethods = false)
class SupportWorkQueues {

    private static final String OPEN = """
            select t.*, (select min(m.created_at) from support.messages m
                         where m.ticket_id = t.id and m.author = 'admin') as first_reply
            from support.tickets t where t.status <> 'resolved' and %s
            """;

    @Bean
    WorkQueue ticketQueue(JdbcClient jdbc, Tickets tickets) {
        return new TicketQueue("ticket", AdminPermission.TICKETS_READ, "t.topic <> 'privacy'", jdbc, tickets);
    }

    @Bean
    WorkQueue privacyRequestQueue(JdbcClient jdbc, Tickets tickets) {
        return new TicketQueue("data-request", AdminPermission.PRIVACY_READ, "t.topic = 'privacy'", jdbc, tickets);
    }

    private record TicketQueue(String type, AdminPermission permission, String which, JdbcClient jdbc, Tickets tickets)
            implements WorkQueue {

        @Override
        public List<Item> open() {
            return jdbc.sql(OPEN.formatted(which))
                    .query((rs, n) -> {
                        var topic = rs.getString("topic");
                        var id = rs.getObject("id", UUID.class);
                        var replied = rs.getTimestamp("first_reply");
                        return new Item(
                                id.toString(),
                                rs.getObject("user_id") == null ? null : "user",
                                rs.getObject("user_id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                "#" + rs.getLong("number") + " " + topic.replace('_', ' '),
                                "/admin/tickets/?id=" + id,
                                topic.equals("privacy")
                                        ? "data-request"
                                        : topic.equals("grievance") ? "grievance" : "ticket",
                                rs.getTimestamp("created_at").toInstant(),
                                null,
                                rs.getString("status").equals("waiting"),
                                replied == null ? null : replied.toInstant(),
                                rs.getTimestamp("updated_at").toInstant(),
                                0,
                                false);
                    })
                    .list();
        }

        @Override
        public boolean replies() {
            return true;
        }

        @Override
        public void reply(String sourceId, String body, String status, UUID admin) {
            tickets.answer(UUID.fromString(sourceId), body, status, actor(admin));
        }

        @Override
        public boolean hasStatus() {
            return true;
        }

        @Override
        public void status(String sourceId, String status, String reason, UUID admin) {
            tickets.setStatus(UUID.fromString(sourceId), status, reason, actor(admin));
        }

        private static AuditTrail.Actor actor(UUID admin) {
            return new AuditTrail.Actor(AuditTrail.Actor.Kind.ADMIN, admin, admin.toString());
        }
    }
}
