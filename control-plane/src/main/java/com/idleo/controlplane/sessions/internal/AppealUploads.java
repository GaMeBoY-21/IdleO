package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.AccountStandings;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Files for an appeal (ADR 0053): the one upload a suspended or banned account may make, through the same path as any
 * other. One file of at most {@value #MOST_BYTES} bytes, and at most {@value #MOST_IN_A_MONTH} in 30 days.
 */
@Component
class AppealUploads {

    static final long MOST_BYTES = 10L * 1024 * 1024;
    static final int MOST_IN_A_MONTH = 3;

    private final AccountStandings standings;
    private final JdbcClient jdbc;
    private final Clock clock;

    AppealUploads(AccountStandings standings, JdbcClient jdbc, Clock clock) {
        this.standings = standings;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static ApiProblem held() {
        return ApiProblem.of(
                Problem.ACCOUNT_SUSPENDED, "While your account is suspended, you can upload only files for an appeal.");
    }

    /** Before an upload is made: a held account may make only an appeal's, within its caps. */
    void checkCreate(UUID user, boolean forAppeal, List<UploadService.FileSpec> files) {
        if (!standings.held(user) && !forAppeal) {
            return;
        }
        if (!forAppeal) {
            throw held();
        }
        if (files.size() != 1) {
            throw ApiProblem.invalid("files", "An appeal's file is one file.");
        }
        if (files.getFirst().sizeBytes() > MOST_BYTES) {
            throw ApiProblem.invalid("files", "An appeal's file is at most 10 MB.");
        }
        long recent = jdbc.sql(
                        "select count(*) from sessions.appeal_uploads where user_id = :user and created_at > :since")
                .param("user", user)
                .param("since", Timestamp.from(clock.instant().minus(Duration.ofDays(30))))
                .query(Long.class)
                .single();
        if (recent >= MOST_IN_A_MONTH) {
            throw ApiProblem.of(Problem.ACTION_REFUSED, "You can upload at most 3 files for appeals in 30 days.");
        }
    }

    /** Once it's made: it's an appeal's. */
    void mark(UUID user, UUID upload) {
        jdbc.sql("""
                        insert into sessions.appeal_uploads (upload_id, user_id, created_at) values (:upload, :user, :now)
                        on conflict do nothing
                        """)
                .param("upload", upload)
                .param("user", user)
                .param("now", Timestamp.from(clock.instant()))
                .update();
    }

    /** Sending its parts: a held account, only for an appeal's upload. */
    void checkSend(UUID user, UUID upload) {
        if (standings.held(user) && !isAppeal(upload)) {
            throw held();
        }
    }

    boolean isAppeal(UUID upload) {
        return jdbc.sql("select count(*) from sessions.appeal_uploads where upload_id = :upload")
                        .param("upload", upload)
                        .query(Long.class)
                        .single()
                > 0;
    }
}
