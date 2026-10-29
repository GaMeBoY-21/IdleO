package com.idleo.controlplane;

import com.idleo.controlplane.sessions.SessionBookings;
import java.util.UUID;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Before every test, ends the sessions an earlier test left waiting for a host or being offered to one. All the
 * tests share one database, and a new host is offered whatever session is waiting: the host a test connects would be
 * matched to an earlier test's session, and until the offer lapses it counts as busy (a quote's {@code hostsFitNow}
 * one short, an availability row without it). A session that ran and lost its host is cancelled the next time
 * it is waiting, so it never outlives a test by more than one.
 */
public final class NoSessionsLeftWaiting implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        var spring = SpringExtension.getApplicationContext(context);
        var jdbc = spring.getBean(JdbcTemplate.class);
        var sessions = spring.getBean(SessionBookings.class);
        var transaction = new TransactionTemplate(spring.getBean(PlatformTransactionManager.class));
        var waiting = jdbc.queryForList(
                "select id from sessions.sessions where state in ('REQUESTED', 'MATCHED', 'PROVISIONING')", UUID.class);
        for (var id : waiting) {
            transaction.executeWithoutResult(status -> sessions.cancel(id, "left behind by an earlier test"));
        }
    }
}
