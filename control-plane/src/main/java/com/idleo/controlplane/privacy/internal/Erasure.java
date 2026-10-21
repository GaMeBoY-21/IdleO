package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.PersonalData.ErasureStep;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.SignInAccounts;
import com.idleo.controlplane.identity.UserDirectory;
import com.idleo.controlplane.notifications.AccountEmails;
import com.idleo.controlplane.sessions.ErasureMarks;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Erases an account whose wait is over (ADR 0037), in eight steps, each done for every module and then recorded, so a
 * job stopped anywhere picks up at the next step, and every step is safe to run twice:
 *
 * <ol>
 *   <li>lock: suspended, keys and device sign-ins revoked, no admin role;
 *   <li>their machines removed, as "Remove" does;
 *   <li>their files, outputs, checkpoints and session logs deleted from storage, and checked gone;
 *   <li>profile, sign-in identities, choices, inbox, links, exports and tickets' personal fields deleted;
 *   <li>money records and ratings kept by opaque id only, leftover free credit forfeited;
 *   <li>their accounts at the sign-in provider deleted;
 *   <li>the opaque id added to the erased list, in the database and in storage, for a restore to erase again;
 *   <li>a last email to the address they had, and nothing about them kept to send it.
 * </ol>
 *
 * Each step is audited by id only. Before it starts, the address and the provider accounts are put aside, because step
 * 4 deletes where they're read from; both are cleared at the end. What stops a deletion at its start postpones it.
 */
@Service
class Erasure {

    private static final Logger log = LoggerFactory.getLogger(Erasure.class);
    static final int STEPS = 8;
    private static final TypeReference<List<Map<String, String>>> IDENTITIES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final List<PersonalData> modules;
    private final Deletions deletions;
    private final UserDirectory users;
    private final SignInAccounts providers;
    private final ErasureMarks marks;
    private final AccountEmails emails;
    private final AuditTrail audit;
    private final JsonMapper json;

    Erasure(
            JdbcClient jdbc,
            JdbcTemplate template,
            TransactionTemplate transactions,
            Clock clock,
            List<PersonalData> modules,
            Deletions deletions,
            UserDirectory users,
            SignInAccounts providers,
            ErasureMarks marks,
            AccountEmails emails,
            AuditTrail audit,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.transactions = transactions;
        this.clock = clock;
        this.modules = List.copyOf(modules);
        this.deletions = deletions;
        this.users = users;
        this.providers = providers;
        this.marks = marks;
        this.emails = emails;
        this.audit = audit;
        this.json = json;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    @Scheduled(fixedDelayString = "${privacy.erasure-sweep-every}", initialDelayString = "PT30S")
    void sweep() {
        try {
            deletions.remindDue();
            for (var id : due()) {
                erase(id, STEPS);
            }
        } catch (RuntimeException e) {
            log.warn("The erasure job failed; it runs again shortly", e);
        }
    }

    /** Requests whose wait is over, and erasures stopped halfway. */
    List<UUID> due() {
        return jdbc.sql("""
                        select id from privacy.deletion_requests
                        where (state = 'scheduled' and delete_after <= :now) or state = 'erasing'
                        order by delete_after
                        """)
                .param("now", Timestamp.from(now()))
                .query(UUID.class)
                .list();
    }

    /**
     * Runs the request's erasure through step {@code through} (all {@link #STEPS} but in tests, which stop it on the
     * way); whether it got that far. One instance at a time, under the request's own advisory lock.
     */
    boolean erase(UUID requestId, int through) {
        // A session-level lock on one connection held for the whole erasure; every step uses its own.
        return Boolean.TRUE.equals(template.execute((ConnectionCallback<Boolean>) connection -> {
            var key = "erasure:" + requestId;
            try (var lock = connection.prepareStatement("select pg_try_advisory_lock(hashtextextended(?, 0))")) {
                lock.setString(1, key);
                try (var result = lock.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        return false;
                    }
                }
            }
            try {
                return locked(requestId, through);
            } finally {
                try (var unlock = connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?, 0))")) {
                    unlock.setString(1, key);
                    unlock.execute();
                }
            }
        }));
    }

    private boolean locked(UUID requestId, int through) {
        var request = deletions.find(requestId);
        if (request.state().equals("scheduled")) {
            var blockers = deletions.blockers(request.userId());
            if (!blockers.isEmpty()) {
                log.info(
                        "Erasure {} waits: {}",
                        requestId,
                        blockers.stream().map(PersonalData.Blocker::code).toList());
                return false;
            }
            begin(request);
        } else if (!request.state().equals("erasing")) {
            return false;
        }
        for (int step = deletions.find(requestId).step() + 1; step <= Math.min(through, STEPS); step++) {
            run(requestId, step);
        }
        return deletions.find(requestId).step() >= through;
    }

    /** Puts aside what the last steps need, before step 4 deletes where it's read from. */
    private void begin(Deletions.DeletionRequest request) {
        var address = users.verifiedEmail(request.userId()).orElse(null);
        var identities = providers.of(request.userId()).stream()
                .map(account -> Map.of("issuer", account.issuer(), "subject", account.subject()))
                .toList();
        jdbc.sql("""
                        update privacy.deletion_requests
                        set state = 'erasing', erasing_since = :now, address = :address,
                            identities = cast(:identities as jsonb), confirm_token_hash = null
                        where id = :id and state = 'scheduled'
                        """)
                .param("now", Timestamp.from(now()))
                .param("address", address)
                .param("identities", json.writeValueAsString(identities))
                .param("id", request.id())
                .update();
        log.info("Erasing account {} ({})", request.userId(), request.id());
    }

    private void run(UUID requestId, int step) {
        var request = deletions.find(requestId);
        var userId = request.userId();
        switch (step) {
            case 1 -> each(userId, ErasureStep.LOCK);
            case 2 -> each(userId, ErasureStep.REMOVE_HOSTS);
            case 3 -> each(userId, ErasureStep.DELETE_FILES);
            case 4 -> each(userId, ErasureStep.DELETE_RECORDS);
            case 5 -> each(userId, ErasureStep.DE_IDENTIFY);
            case 6 -> identities(requestId).forEach(providers::delete);
            case 7 -> {
                var at = now();
                jdbc.sql("insert into privacy.erased_users (user_id, erased_at) values (:user, :at)"
                                + " on conflict (user_id) do nothing")
                        .param("user", userId)
                        .param("at", Timestamp.from(at))
                        .update();
                marks.mark(userId, at);
            }
            case 8 -> lastEmail(request);
            default -> throw new IllegalArgumentException("no step " + step);
        }
        transactions.executeWithoutResult(status -> {
            if (step == STEPS) {
                jdbc.sql("""
                                update privacy.deletion_requests
                                set step = :step, state = 'erased', erased_at = :now, address = null, identities = null
                                where id = :id
                                """)
                        .param("step", step)
                        .param("now", Timestamp.from(now()))
                        .param("id", requestId)
                        .update();
            } else {
                jdbc.sql("update privacy.deletion_requests set step = :step where id = :id and step < :step")
                        .param("step", step)
                        .param("id", requestId)
                        .update();
            }
            audit.record(new AuditTrail.Entry(
                    AuditTrail.Actor.system("account erasure"),
                    "account.erase",
                    "user",
                    userId.toString(),
                    request.legalOrder() ? "a legal order" : "the person asked, and their wait is over",
                    null,
                    Map.of("step", step, "of", STEPS, "what", WHAT[step - 1])));
        });
        log.info("Erasure {}: step {} of {} done", requestId, step, STEPS);
    }

    static final String[] WHAT = {
        "locked",
        "machines removed",
        "files deleted",
        "records deleted",
        "money records de-identified",
        "sign-in provider accounts deleted",
        "added to the erased list",
        "last email sent"
    };

    private void each(UUID userId, ErasureStep step) {
        for (var module : modules) {
            module.erase(userId, step);
        }
    }

    private List<SignInAccounts.ProviderAccount> identities(UUID requestId) {
        var text = jdbc.sql("select identities::text from privacy.deletion_requests where id = :id")
                .param("id", requestId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (text == null) {
            return List.of();
        }
        return json.readValue(text, IDENTITIES).stream()
                .map(account -> new SignInAccounts.ProviderAccount(account.get("issuer"), account.get("subject")))
                .toList();
    }

    private void lastEmail(Deletions.DeletionRequest request) {
        var address = jdbc.sql("select address from privacy.deletion_requests where id = :id")
                .param("id", request.id())
                .query(String.class)
                .optional()
                .orElse(null);
        if (address == null) {
            return;
        }
        emails.sendOnce(
                address,
                UUID.nameUUIDFromBytes(("deletion-done:" + request.id()).getBytes(StandardCharsets.UTF_8)),
                "Your account is deleted",
                "We've deleted your IdleO account and everything we don't have to keep. Your money records stay, under "
                        + "a number that no longer leads to you, for as long as the law requires. Our backups that still "
                        + "hold your account are deleted within 30 days. This is the last email we send you.",
                "/");
    }

    /**
     * After a restore of the database (ADR 0037, ops/backup/restore.sh): every account on the erased list, in the
     * database or in storage, is erased again, steps 1 to 7; no email. How many it went through.
     */
    int reapply() {
        var erased = new LinkedHashMap<UUID, Instant>();
        jdbc.sql("select user_id, erased_at from privacy.erased_users")
                .query((rs, n) -> erased.put(
                        rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()))
                .list();
        marks.marked().forEach(erased::putIfAbsent);
        for (var entry : erased.entrySet()) {
            var userId = entry.getKey();
            var accounts = providers.of(userId);
            for (var step : ErasureStep.values()) {
                each(userId, step);
            }
            accounts.forEach(providers::delete);
            jdbc.sql("insert into privacy.erased_users (user_id, erased_at) values (:user, :at)"
                            + " on conflict (user_id) do nothing")
                    .param("user", userId)
                    .param("at", Timestamp.from(entry.getValue()))
                    .update();
        }
        return erased.size();
    }
}
