package com.idleo.controlplane.payouts.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.payouts.PayoutMethodChanged;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where a host's payouts go (ADR 0040): a UPI ID that RazorpayX's Fund Account Validation says is {@code active}, whose
 * registered name matches the name on their verified PAN.
 *
 * <ul>
 *   <li>{@link #check}: RazorpayX asks the bank; the host sees the registered name it returned. An answer that takes a
 *       while arrives by webhook ({@code fund_account.validation.*}) or {@link #sweep}.
 *   <li>{@link #confirm} (after a fresh sign-in): a score at the threshold or above makes it where payouts go; below
 *       it, an admin looks ({@link #decide}). Replacing an earlier UPI ID holds payouts for {@code payouts.change-hold}
 *       and tells the host by email ({@link PayoutMethodChanged}), so a stolen account can't redirect earnings at once.
 *   <li>{@link #suspect}: a payout to it came back from the bank: no more payouts until the host checks a UPI ID again.
 * </ul>
 */
@Service
class PayoutMethods {

    private static final Logger log = LoggerFactory.getLogger(PayoutMethods.class);

    /** RazorpayX's rule for a VPA: 3 to 100 of letters, digits, dots and hyphens, with one @. */
    static final Pattern VPA = Pattern.compile("[A-Za-z0-9.-]{1,98}@[A-Za-z0-9.-]{1,98}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RazorpayXClient razorpayx;
    private final Pans pans;
    private final PayoutsProperties properties;
    private final IdGenerator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AuditTrail audit;
    private final PayoutsMetrics metrics;

    PayoutMethods(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            RazorpayXClient razorpayx,
            Pans pans,
            PayoutsProperties properties,
            IdGenerator ids,
            Clock clock,
            ApplicationEventPublisher events,
            AuditTrail audit,
            PayoutsMetrics metrics) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.razorpayx = razorpayx;
        this.pans = pans;
        this.properties = properties;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
        this.audit = audit;
        this.metrics = metrics;
    }

    record Method(
            UUID id,
            UUID userId,
            @Nullable String vpa,
            String state,
            @Nullable String validationId,
            @Nullable String fundAccountId,
            @Nullable String accountStatus,
            @Nullable String registeredName,
            @Nullable Integer nameMatchScore,
            @Nullable String failure,
            Instant checkedAt,
            @Nullable Instant confirmedAt,
            @Nullable Instant holdUntil,
            @Nullable Instant endedAt,
            @Nullable String decidedBy,
            @Nullable String reason) {

        /** Payouts may go to it now. */
        boolean payable(Instant now) {
            return state.equals("active") && (holdUntil == null || !holdUntil.isAfter(now));
        }
    }

    /** Why a check can't be made; the controller says it in the catalog's words. */
    enum Refusal {
        UNAVAILABLE,
        PAN_NOT_VERIFIED,
        MALFORMED,
        TOO_MANY,
        NOT_USABLE
    }

    static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final Refusal refusal;
        final @Nullable Duration retryAfter;

        Refused(Refusal refusal, String message, @Nullable Duration retryAfter) {
            super(message);
            this.refusal = refusal;
            this.retryAfter = retryAfter;
        }
    }

    /**
     * Asks RazorpayX who a UPI ID belongs to. The answer is kept as a check the host can confirm; RazorpayX refusing it
     * or being out of reach is a failed check with the reason, never an error.
     *
     * @throws Refused when payouts aren't set up, the PAN isn't verified, the UPI ID isn't one, or too many checks were
     *     made today
     */
    Method check(UUID userId, String rawVpa) {
        if (!razorpayx.enabled()) {
            throw new Refused(Refusal.UNAVAILABLE, "Payouts aren't set up on this server.", null);
        }
        var pan = pans.find(userId)
                .filter(Pans.Pan::verified)
                .orElseThrow(() -> new Refused(
                        Refusal.PAN_NOT_VERIFIED,
                        "Your PAN has to be verified before a UPI ID is checked against it.",
                        null));
        var vpa = rawVpa == null ? "" : rawVpa.strip();
        if (vpa.length() < 3 || vpa.length() > 100 || !VPA.matcher(vpa).matches()) {
            throw new Refused(
                    Refusal.MALFORMED,
                    "A UPI ID looks like name@bank: letters, digits, dots and hyphens, with one @.",
                    null);
        }
        var since = clock.instant().minus(Duration.ofDays(1));
        var recent = jdbc.sql("""
                        select checked_at from payouts.methods where user_id = :userId and checked_at > :since
                        order by checked_at
                        """)
                .param("userId", userId)
                .param("since", Timestamp.from(since))
                .query((rs, n) -> rs.getTimestamp(1).toInstant())
                .list();
        if (recent.size() >= properties.checksPerDay()) {
            var wait = Duration.between(clock.instant(), recent.getFirst().plus(Duration.ofDays(1)));
            throw new Refused(
                    Refusal.TOO_MANY,
                    "At most " + properties.checksPerDay() + " UPI ID checks a day.",
                    wait.isNegative() ? Duration.ofSeconds(1) : wait);
        }
        var id = ids.newId();
        jdbc.sql("""
                        insert into payouts.methods (id, user_id, vpa, state, checked_at)
                        values (:id, :userId, :vpa, 'checking', :now)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("vpa", vpa)
                .param("now", Timestamp.from(now()))
                .update();
        try {
            var validation = razorpayx.validateVpa(vpa, contactName(pan.name()), userId.toString(), id.toString());
            transactions.executeWithoutResult(status -> apply(id, validation));
        } catch (RazorpayXClient.Refused refused) {
            fail(id, "RazorpayX couldn't check it: " + refused.getMessage());
        } catch (RazorpayXClient.Unreachable unreachable) {
            log.warn("A UPI ID check couldn't reach RazorpayX: {}", unreachable.getMessage());
            fail(id, "RazorpayX couldn't be reached. Try again in a few minutes.");
        }
        return find(id).orElseThrow();
    }

    /** What RazorpayX said of a check: from the call itself, a webhook, or the sweep. */
    void apply(UUID methodId, RazorpayXClient.Validation validation) {
        var method = find(methodId).orElseThrow();
        if (!method.state().equals("checking")) {
            return;
        }
        String state;
        String failure = null;
        switch (validation.status()) {
            case "completed" -> {
                if ("active".equals(validation.accountStatus())) {
                    state = "checked";
                } else {
                    state = "failed";
                    failure = "Your bank says this UPI ID isn't active. Check it and try again.";
                }
            }
            case "failed" -> {
                state = "failed";
                failure = "The bank couldn't check this UPI ID. Check it and try again.";
            }
            default -> state = "checking";
        }
        jdbc.sql("""
                        update payouts.methods
                        set state = :state, validation_id = :validationId, fund_account_id = :fundAccountId,
                            account_status = :accountStatus, registered_name = :registeredName,
                            name_match_score = :score, failure = :failure
                        where id = :id and state = 'checking'
                        """)
                .param("state", state)
                .param("validationId", validation.id())
                .param("fundAccountId", validation.fundAccountId())
                .param("accountStatus", validation.accountStatus())
                .param("registeredName", truncate(validation.registeredName(), 200))
                .param("score", validation.nameMatchScore())
                .param("failure", failure)
                .param("id", methodId)
                .update();
        if (!state.equals("checking")) {
            metrics.upiChecked(state.equals("checked") ? "active" : "invalid");
        }
    }

    /** A webhook about a check, by RazorpayX's id. */
    void applyValidation(RazorpayXClient.Validation validation) {
        jdbc.sql("select id from payouts.methods where validation_id = :id")
                .param("id", validation.id())
                .query(UUID.class)
                .optional()
                .ifPresent(methodId -> apply(methodId, validation));
    }

    private void fail(UUID methodId, String why) {
        jdbc.sql("update payouts.methods set state = 'failed', failure = :why where id = :id and state = 'checking'")
                .param("why", why)
                .param("id", methodId)
                .update();
        metrics.upiChecked("failed");
    }

    /** Checks RazorpayX hasn't answered yet, asked again; given up after a day. */
    @Scheduled(fixedDelayString = "${payouts.send-every}", initialDelayString = "${payouts.send-every}")
    void sweep() {
        if (!razorpayx.enabled()) {
            return;
        }
        var waiting = jdbc.sql("""
                        select id, validation_id, checked_at from payouts.methods
                        where state = 'checking' and validation_id is not null order by checked_at limit 50
                        """)
                .query((rs, n) -> new Object[] {
                    rs.getObject("id", UUID.class),
                    rs.getString("validation_id"),
                    rs.getTimestamp("checked_at").toInstant()
                })
                .list();
        for (var row : waiting) {
            var id = (UUID) row[0];
            if (((Instant) row[2]).isBefore(clock.instant().minus(Duration.ofDays(1)))) {
                fail(id, "The bank didn't answer. Check the UPI ID again.");
                continue;
            }
            try {
                var validation = razorpayx.fetchValidation((String) row[1]);
                transactions.executeWithoutResult(status -> apply(id, validation));
            } catch (RazorpayXClient.Refused | RazorpayXClient.Unreachable e) {
                log.info("A UPI ID check is still waiting for RazorpayX: {}", e.getMessage());
            }
        }
    }

    /**
     * Makes a check where payouts go, or sends it to an admin when the name match is below the threshold. The caller
     * has checked the sign-in is fresh.
     *
     * @throws Refused when the check isn't the person's latest, isn't an active UPI ID, or is too old
     */
    Method confirm(UUID userId, UUID methodId) {
        return Optional.ofNullable(transactions.execute(status -> {
                    lockUser(userId);
                    var method = find(methodId)
                            .filter(found -> found.userId().equals(userId))
                            .orElseThrow(() -> notUsable("There's no such check."));
                    var latest = latest(userId).map(Method::id).orElse(null);
                    if (!method.state().equals("checked")
                            || !methodId.equals(latest)
                            || method.checkedAt()
                                    .plus(properties.checkValidFor())
                                    .isBefore(clock.instant())) {
                        throw notUsable("Only your latest check of an active UPI ID, from the last hour, can be used.");
                    }
                    if (!pans.verified(userId)) {
                        throw new Refused(Refusal.PAN_NOT_VERIFIED, "Your PAN isn't verified.", null);
                    }
                    var score = method.nameMatchScore();
                    if (score != null && score >= properties.nameMatchThreshold()) {
                        activate(method, null);
                        metrics.upiChecked("active");
                    } else {
                        jdbc.sql("""
                                        update payouts.methods set state = 'replaced', ended_at = :now
                                        where user_id = :userId and state = 'review'
                                        """)
                                .param("now", Timestamp.from(now()))
                                .param("userId", userId)
                                .update();
                        jdbc.sql("""
                                        update payouts.methods set state = 'review', confirmed_at = :now
                                        where id = :id
                                        """)
                                .param("now", Timestamp.from(now()))
                                .param("id", methodId)
                                .update();
                        metrics.upiChecked("review");
                    }
                    return find(methodId).orElseThrow();
                }))
                .orElseThrow();
    }

    /**
     * An admin approves a UPI ID whose name match was below the threshold, or rejects it, with a reason, audited.
     *
     * @throws IllegalArgumentException when it isn't waiting for an admin
     */
    Method decide(UUID methodId, boolean approve, String reason, AuditTrail.Actor actor) {
        return Optional.ofNullable(transactions.execute(status -> {
                    var method = find(methodId).orElseThrow(() -> new IllegalArgumentException("No such UPI ID."));
                    lockUser(method.userId());
                    method = find(methodId).orElseThrow();
                    if (!method.state().equals("review")) {
                        throw new IllegalArgumentException(
                                "This UPI ID isn't waiting for review: it's " + method.state() + ".");
                    }
                    if (approve) {
                        activate(method, actor.label());
                    } else {
                        jdbc.sql("""
                                        update payouts.methods set state = 'rejected', ended_at = :now,
                                            decided_by = :by, reason = :reason
                                        where id = :id
                                        """)
                                .param("now", Timestamp.from(now()))
                                .param("by", actor.label())
                                .param("reason", reason)
                                .param("id", methodId)
                                .update();
                    }
                    audit.record(new AuditTrail.Entry(
                            actor,
                            approve ? "payout_method.approve" : "payout_method.reject",
                            "payout_method",
                            methodId.toString(),
                            reason,
                            Map.of("state", "review", "nameMatchScore", String.valueOf(method.nameMatchScore())),
                            Map.of("state", approve ? "active" : "rejected")));
                    metrics.upiChecked(approve ? "approved_by_admin" : "rejected");
                    return find(methodId).orElseThrow();
                }))
                .orElseThrow();
    }

    /** Makes a method where payouts go, ending the one before it; a change holds payouts and tells the host. */
    private void activate(Method method, @Nullable String decidedBy) {
        var now = now();
        var previous = inUse(method.userId());
        Instant holdUntil = null;
        if (previous.isPresent()) {
            // The same UPI ID checked again (after a reversal, say) redirects nothing: no hold.
            if (!java.util.Objects.equals(lower(previous.get().vpa()), lower(method.vpa()))) {
                holdUntil = now.plus(properties.changeHold());
            }
            jdbc.sql("update payouts.methods set state = 'replaced', ended_at = :now where id = :id")
                    .param("now", Timestamp.from(now))
                    .param("id", previous.get().id())
                    .update();
        }
        jdbc.sql("""
                        update payouts.methods
                        set state = 'active', confirmed_at = coalesce(confirmed_at, :now), hold_until = :holdUntil,
                            decided_by = :decidedBy
                        where id = :id
                        """)
                .param("now", Timestamp.from(now))
                .param("holdUntil", holdUntil == null ? null : Timestamp.from(holdUntil))
                .param("decidedBy", decidedBy)
                .param("id", method.id())
                .update();
        if (holdUntil != null) {
            events.publishEvent(new PayoutMethodChanged(method.userId(), method.id(), holdUntil));
        }
    }

    /** A payout to the method came back from the bank: no payout goes to it until the host checks again. */
    void suspect(UUID methodId) {
        jdbc.sql("update payouts.methods set state = 'suspect' where id = :id and state = 'active'")
                .param("id", methodId)
                .update();
    }

    /** The method in use (active, or suspect after a reversal), if any. */
    Optional<Method> inUse(UUID userId) {
        return jdbc.sql("""
                        select * from payouts.methods where user_id = :userId and state in ('active', 'suspect')
                        """)
                .param("userId", userId)
                .query(PayoutMethods::method)
                .optional();
    }

    /** One waiting for an admin, if any. */
    Optional<Method> inReview(UUID userId) {
        return jdbc.sql("select * from payouts.methods where user_id = :userId and state = 'review'")
                .param("userId", userId)
                .query(PayoutMethods::method)
                .optional();
    }

    /** The person's latest check, whatever came of it. */
    Optional<Method> latest(UUID userId) {
        return jdbc.sql("""
                        select * from payouts.methods where user_id = :userId order by checked_at desc, id desc limit 1
                        """)
                .param("userId", userId)
                .query(PayoutMethods::method)
                .optional();
    }

    Optional<Method> find(UUID id) {
        return jdbc.sql("select * from payouts.methods where id = :id")
                .param("id", id)
                .query(PayoutMethods::method)
                .optional();
    }

    /** UPI IDs waiting for an admin, oldest first. */
    List<Method> reviews(int limit) {
        return jdbc.sql("select * from payouts.methods where state = 'review' order by confirmed_at limit " + limit)
                .query(PayoutMethods::method)
                .list();
    }

    private void lockUser(UUID userId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "payout-method:" + userId)
                .query((rs, n) -> null)
                .list();
    }

    private static Refused notUsable(String message) {
        return new Refused(Refusal.NOT_USABLE, message, null);
    }

    /**
     * The name RazorpayX's contact takes (https://razorpay.com/docs/api/x/contacts/create/): 3 to 50 of letters, digits,
     * spaces and {@code ’ - _ / ( ) .}, not ending in one of those but a dot. The PAN's name fits it but for length and
     * the straight apostrophe.
     */
    static String contactName(String panName) {
        var name = panName.replace('\'', '’').replaceAll("[^A-Za-z0-9 ’\\-_/().]", "");
        if (name.length() > 50) {
            name = name.substring(0, 50);
        }
        name = name.strip().replaceAll("[ ’\\-_/()]+$", "");
        while (name.length() < 3) {
            name = name + ".";
        }
        return name;
    }

    private static @Nullable String lower(@Nullable String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    private static @Nullable String truncate(@Nullable String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    private static Method method(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Method(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("vpa"),
                rs.getString("state"),
                rs.getString("validation_id"),
                rs.getString("fund_account_id"),
                rs.getString("account_status"),
                rs.getString("registered_name"),
                rs.getObject("name_match_score", Integer.class),
                rs.getString("failure"),
                rs.getTimestamp("checked_at").toInstant(),
                instant(rs.getTimestamp("confirmed_at")),
                instant(rs.getTimestamp("hold_until")),
                instant(rs.getTimestamp("ended_at")),
                rs.getString("decided_by"),
                rs.getString("reason"));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
