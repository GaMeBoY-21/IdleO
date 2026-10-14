package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invite codes (ADR 0024): operators make them, a waiting user spends one to become active.
 *
 * <ul>
 *   <li>A code is stored only as a hash, is used once and may expire.
 *   <li>A user may try {@value #MAX_ATTEMPTS} codes an hour. Every try counts, and is kept even when it
 *       fails; the count and the new try are made under the user's row lock, so parallel requests can't
 *       slip past the limit.
 *   <li>Spending a code and activating the user are one transaction: the code is claimed by a conditional
 *       update, so two users can't spend the same code.
 * </ul>
 */
@Service
class InviteService {

    static final int MAX_ATTEMPTS = 5;
    static final Duration ATTEMPT_WINDOW = Duration.ofHours(1);

    enum Result {
        ACTIVATED,
        ALREADY_ACTIVE,
        INVALID,
        TOO_MANY_ATTEMPTS
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final CodeHashes hashes;
    private final SecureRandom random = new SecureRandom();

    InviteService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            CodeHashes hashes) {
        this.hashes = hashes;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
    }

    /** The most codes one request makes. */
    static final int MAX_COUNT = 200;

    /**
     * Makes {@code count} codes in one transaction and returns them, the only time they exist in the clear. The note
     * says who they're for, and is the audit row's reason; the row lists the codes' ids, never the codes.
     */
    Created create(int count, String note, Optional<Duration> expiresAfter, AuditTrail.Actor actor) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("make from 1 to " + MAX_COUNT + " codes at a time");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("invite codes need a note saying who they're for");
        }
        return transactions.execute(status -> {
            var now = Instant.now(clock);
            var expiresAt = expiresAfter.map(now::plus).orElse(null);
            var codes = new ArrayList<String>();
            var codeIds = new ArrayList<UUID>();
            while (codes.size() < count) {
                var code = InviteCodes.generate(random);
                var id = ids.newId();
                int inserted = jdbc.sql("""
                                insert into identity.invite_codes (id, code_hash, note, created_by, created_at, expires_at)
                                values (:id, :hash, :note, :createdBy, :now, :expiresAt)
                                on conflict (code_hash) do nothing
                                """)
                        .param("id", id)
                        .param("hash", hashes.hash(CodeHashes.Kind.INVITE, code))
                        .param("note", note.strip())
                        .param("createdBy", actor.label())
                        .param("now", Timestamp.from(now))
                        .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                        .update();
                if (inserted == 1) {
                    codes.add(code);
                    codeIds.add(id);
                }
            }
            var after = new LinkedHashMap<String, Object>();
            after.put("count", count);
            after.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
            after.put("ids", codeIds.stream().map(UUID::toString).toList());
            audit.record(new AuditTrail.Entry(
                    actor, "invite.create", "invite_code", codeIds.getFirst().toString(), note, null, after));
            return new Created(List.copyOf(codes), expiresAt);
        });
    }

    /** New codes, in the clear only here, and when they lapse (never, when null). */
    record Created(List<String> codes, @Nullable Instant expiresAt) {}

    /** Tries {@code code} for this user. The try is kept whatever the result. */
    Result activate(UUID userId, String code) {
        return transactions.execute(status -> {
            var now = Instant.now(clock);
            var userStatus = jdbc.sql("select status from identity.users where id = :id for update")
                    .param("id", userId)
                    .query(String.class)
                    .single();
            if (!userStatus.equals(UserStatus.WAITING.dbValue())) {
                return Result.ALREADY_ACTIVE;
            }
            int recent = jdbc.sql("""
                            select count(*) from identity.activation_attempts
                            where user_id = :id and attempted_at > :since
                            """)
                    .param("id", userId)
                    .param("since", Timestamp.from(now.minus(ATTEMPT_WINDOW)))
                    .query(Integer.class)
                    .single();
            if (recent >= MAX_ATTEMPTS) {
                return Result.TOO_MANY_ATTEMPTS;
            }
            jdbc.sql("insert into identity.activation_attempts (id, user_id, attempted_at) values (:id, :user, :now)")
                    .param("id", ids.newId())
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .update();
            int claimed = jdbc.sql("""
                            update identity.invite_codes set used_by = :user, used_at = :now
                            where code_hash = :hash and used_at is null
                              and (expires_at is null or expires_at > :now)
                            """)
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .param("hash", hashes.hash(CodeHashes.Kind.INVITE, code))
                    .update();
            if (claimed == 0) {
                return Result.INVALID;
            }
            jdbc.sql("""
                            update identity.users set status = 'active', updated_at = :now, version = version + 1
                            where id = :id
                            """).param("now", Timestamp.from(now)).param("id", userId).update();
            return Result.ACTIVATED;
        });
    }
}
