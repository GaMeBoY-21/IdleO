package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Which Terms and privacy notice a person has accepted (ADR 0037). The versions in force are {@code
 * legal.notices.*}; a person who hasn't accepted both is asked, by the app, before anything else.
 */
@Service
class NoticeAcceptances {

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final LegalSettings.Notices notices;

    NoticeAcceptances(JdbcClient jdbc, IdGenerator ids, Clock clock, LegalSettings legal) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        this.notices = legal.notices();
    }

    /**
     * Where a person stands: the versions in force, whether they accepted both, and whether they ever confirmed
     * their age.
     */
    record Standing(
            String termsVersion, String privacyVersion, boolean accepted, boolean ageConfirmed, int minimumAge) {}

    Standing standing(UUID userId) {
        var accepted = jdbc.sql("""
                        select count(*) > 0 from identity.notice_acceptances
                        where user_id = :user and terms_version = :terms and privacy_version = :privacy
                        """)
                .param("user", userId)
                .param("terms", notices.termsVersion())
                .param("privacy", notices.privacyVersion())
                .query(Boolean.class)
                .single();
        return new Standing(
                notices.termsVersion(), notices.privacyVersion(), accepted, ageConfirmed(userId), notices.minimumAge());
    }

    enum Outcome {
        ACCEPTED,
        /** The versions the person read aren't the ones in force any more. */
        CHANGED,
        /** Their first acceptance, without confirming their age. */
        AGE_NOT_CONFIRMED
    }

    /** Records that the person accepted these versions; accepting them again records nothing more. */
    Outcome accept(UUID userId, String termsVersion, String privacyVersion, boolean ageConfirmed) {
        if (!termsVersion.equals(notices.termsVersion()) || !privacyVersion.equals(notices.privacyVersion())) {
            return Outcome.CHANGED;
        }
        var standing = standing(userId);
        if (standing.accepted()) {
            return Outcome.ACCEPTED;
        }
        if (!standing.ageConfirmed() && !ageConfirmed) {
            return Outcome.AGE_NOT_CONFIRMED;
        }
        jdbc.sql("""
                        insert into identity.notice_acceptances
                            (id, user_id, terms_version, privacy_version, age_confirmed, accepted_at)
                        values (:id, :user, :terms, :privacy, :age, :now)
                        """)
                .param("id", ids.newId())
                .param("user", userId)
                .param("terms", termsVersion)
                .param("privacy", privacyVersion)
                .param("age", standing.ageConfirmed() || ageConfirmed)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        return Outcome.ACCEPTED;
    }

    private boolean ageConfirmed(UUID userId) {
        return jdbc.sql("select count(*) > 0 from identity.notice_acceptances where user_id = :user and age_confirmed")
                .param("user", userId)
                .query(Boolean.class)
                .single();
    }
}
