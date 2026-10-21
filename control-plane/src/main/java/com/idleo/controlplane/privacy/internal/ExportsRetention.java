package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.RetentionRule;
import com.idleo.controlplane.sessions.PersonalArchives;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A made export is deleted from storage once it's past {@code privacy.export-keep} (ADR 0037); its row says so. */
@Component
class ExportsRetention implements RetentionRule {

    private final JdbcClient jdbc;
    private final PersonalArchives archives;

    ExportsRetention(JdbcClient jdbc, PersonalArchives archives) {
        this.jdbc = jdbc;
        this.archives = archives;
    }

    @Override
    public String name() {
        return "exports";
    }

    @Override
    public long apply(Instant now) {
        long deleted = 0;
        for (var id : jdbc.sql("select id from privacy.exports where state = 'ready' and expires_at <= :now")
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .list()) {
            deleted += archives.delete(id);
            jdbc.sql("update privacy.exports set state = 'expired' where id = :id and state = 'ready'")
                    .param("id", id)
                    .update();
        }
        return deleted;
    }
}
