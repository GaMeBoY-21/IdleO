package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.billing.VerifiedPans;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PANs (ADR 0040): a host's before their first payout, and a renter's before they buy past {@code
 * legal.payouts.renter-pan-above-paise} in a financial year. The person gives the PAN and the name exactly as on it;
 * the format is checked here (five letters, four digits, a letter, the fourth letter an individual's, a family's or a
 * business's), then the {@link PanVerifier}: verified, or an admin's to look at (a name that doesn't match, or no
 * provider yet), or rejected (no such PAN). Sealed at rest ({@link PanSeal}); only the last four characters are shown
 * anywhere but the tax export and an admin's audited look.
 */
@Service
class Pans implements VerifiedPans {

    /** Five letters, four digits, a letter; the fourth letter says whose (the Income Tax Department's PAN structure). */
    static final Pattern PAN = Pattern.compile("[A-Z]{3}[PHCFATBLJ][A-Z][0-9]{4}[A-Z]");

    /** As on the PAN: letters, spaces, dots, apostrophes and hyphens, 1 to 85 characters. */
    static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z .'-]{0,84}");

    private final JdbcClient jdbc;
    private final PanSeal seal;
    private final PanVerifier verifier;
    private final AuditTrail audit;
    private final Clock clock;
    private final PayoutsMetrics metrics;

    Pans(JdbcClient jdbc, PanSeal seal, PanVerifier verifier, AuditTrail audit, Clock clock, PayoutsMetrics metrics) {
        this.jdbc = jdbc;
        this.seal = seal;
        this.verifier = verifier;
        this.audit = audit;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * A person's PAN as they may see it.
     *
     * @param holder individual or business
     * @param state review, verified or rejected
     * @param reason why it was rejected, or an admin's words, for the person
     */
    record Pan(
            UUID userId,
            String last4,
            String name,
            String holder,
            String state,
            String checked,
            String verifier,
            Instant submittedAt,
            @Nullable Instant decidedAt,
            @Nullable String decidedBy,
            @Nullable String reason) {

        boolean verified() {
            return state.equals("verified");
        }

        /** An individual (P) or a family (H): the yearly threshold before tax is deducted applies to them. */
        boolean individual() {
            return holder.equals("individual");
        }
    }

    /** The PAN's shape is wrong; the message says how. */
    static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final String field;

        Malformed(String field, String message) {
            super(message);
            this.field = field;
        }
    }

    /** The PAN is verified already: changing it goes through support. */
    static final class AlreadyVerified extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    static String normalise(String pan) {
        return pan == null ? "" : pan.strip().toUpperCase(Locale.ROOT);
    }

    static String normaliseName(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", " ");
    }

    /**
     * Records the person's PAN and checks it.
     *
     * @throws Malformed when the PAN or the name isn't the right shape
     * @throws AlreadyVerified when a verified PAN is on record
     */
    @Transactional
    Pan submit(UUID userId, String rawPan, String rawName) {
        var pan = normalise(rawPan);
        var name = normaliseName(rawName);
        if (!PAN.matcher(pan).matches()) {
            throw new Malformed(
                    "pan", "A PAN is 10 characters: five letters, four digits and a letter, like ABCPE1234F.");
        }
        if (!NAME.matcher(name).matches()) {
            throw new Malformed("name", "Write the name exactly as it is on the PAN card, in English letters.");
        }
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "pan:" + userId)
                .query((rs, n) -> null)
                .list();
        if (find(userId).filter(Pan::verified).isPresent()) {
            throw new AlreadyVerified();
        }
        var checked = verifier.verify(pan, name);
        var state = switch (checked) {
            case VERIFIED -> "verified";
            case INVALID -> "rejected";
            case NAME_MISMATCH, NOT_CHECKED -> "review";
        };
        var reason = switch (checked) {
            case INVALID -> "The Income Tax Department has no such PAN. Check it and give it again.";
            case NAME_MISMATCH -> "The name doesn't match the PAN's. We'll look at it.";
            default -> null;
        };
        var now = now();
        jdbc.sql("""
                        insert into payouts.pans
                            (user_id, pan_sealed, pan_last4, name, holder, state, checked, verifier, submitted_at,
                             decided_at, decided_by, reason)
                        values (:userId, :sealed, :last4, :name, :holder, :state, :checked, :verifier, :now,
                                :decidedAt, :decidedBy, :reason)
                        on conflict (user_id) do update set
                            pan_sealed = excluded.pan_sealed, pan_last4 = excluded.pan_last4, name = excluded.name,
                            holder = excluded.holder, state = excluded.state, checked = excluded.checked,
                            verifier = excluded.verifier, submitted_at = excluded.submitted_at,
                            decided_at = excluded.decided_at, decided_by = excluded.decided_by,
                            reason = excluded.reason
                        """)
                .param("userId", userId)
                .param("sealed", seal.seal(userId, pan))
                .param("last4", pan.substring(6))
                .param("name", name)
                .param("holder", pan.charAt(3) == 'P' || pan.charAt(3) == 'H' ? "individual" : "business")
                .param("state", state)
                .param("checked", checked.name().toLowerCase(Locale.ROOT))
                .param("verifier", verifier.name())
                .param("now", Timestamp.from(now))
                .param("decidedAt", state.equals("review") ? null : Timestamp.from(now))
                .param("decidedBy", state.equals("review") ? null : verifier.name())
                .param("reason", reason)
                .update();
        metrics.panChecked(state);
        return find(userId).orElseThrow();
    }

    Optional<Pan> find(UUID userId) {
        return jdbc.sql("select * from payouts.pans where user_id = :userId")
                .param("userId", userId)
                .query(Pans::pan)
                .optional();
    }

    @Override
    public boolean verified(UUID userId) {
        return find(userId).filter(Pan::verified).isPresent();
    }

    /** The PAN itself, for the tax export and an admin's audited look; nothing else opens it. */
    Optional<String> full(UUID userId) {
        return jdbc.sql("select pan_sealed from payouts.pans where user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> rs.getBytes("pan_sealed"))
                .optional()
                .map(stored -> seal.open(userId, stored));
    }

    /** An admin reads the whole PAN, with a reason, audited in the same transaction. */
    @Transactional
    Optional<String> reveal(UUID userId, String reason, AuditTrail.Actor actor) {
        var pan = full(userId);
        if (pan.isPresent()) {
            audit.record(new AuditTrail.Entry(actor, "pan.reveal", "user", userId.toString(), reason, null, null));
        }
        return pan;
    }

    /**
     * An admin confirms a PAN in review (no provider yet, or a name that didn't match) or rejects it, with a reason,
     * audited in the same transaction.
     *
     * @throws IllegalArgumentException when it isn't waiting for an admin
     */
    @Transactional
    Pan decide(UUID userId, boolean verify, String reason, AuditTrail.Actor actor) {
        var before = find(userId).orElseThrow(() -> new IllegalArgumentException("This person has given no PAN."));
        if (!before.state().equals("review")) {
            throw new IllegalArgumentException("This PAN isn't waiting for review: it's " + before.state() + ".");
        }
        var state = verify ? "verified" : "rejected";
        jdbc.sql("""
                        update payouts.pans set state = :state, decided_at = :now, decided_by = :by, reason = :reason
                        where user_id = :userId and state = 'review'
                        """)
                .param("state", state)
                .param("now", Timestamp.from(now()))
                .param("by", actor.label())
                .param("reason", verify ? null : reason)
                .param("userId", userId)
                .update();
        audit.record(new AuditTrail.Entry(
                actor,
                verify ? "pan.verify" : "pan.reject",
                "user",
                userId.toString(),
                reason,
                Map.of("state", before.state()),
                Map.of("state", state)));
        metrics.panChecked(verify ? "verified_by_admin" : "rejected_by_admin");
        return find(userId).orElseThrow();
    }

    /** PANs waiting for an admin, oldest first. */
    List<Pan> inReview(int limit) {
        return jdbc.sql("select * from payouts.pans where state = 'review' order by submitted_at limit " + limit)
                .query(Pans::pan)
                .list();
    }

    private static Pan pan(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        var decided = rs.getTimestamp("decided_at");
        return new Pan(
                rs.getObject("user_id", UUID.class),
                rs.getString("pan_last4"),
                rs.getString("name"),
                rs.getString("holder"),
                rs.getString("state"),
                rs.getString("checked"),
                rs.getString("verifier"),
                rs.getTimestamp("submitted_at").toInstant(),
                decided == null ? null : decided.toInstant(),
                rs.getString("decided_by"),
                rs.getString("reason"));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
