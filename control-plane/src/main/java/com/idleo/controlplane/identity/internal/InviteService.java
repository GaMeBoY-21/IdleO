package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.Side;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invite codes (ADR 0024): operators make them, a waiting user spends one to become active. A code unlocks renting,
 * hosting or both (ADR 0058), and an active person may spend a second to unlock the side they haven't.
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
        /** An active person unlocked the other side. */
        UNLOCKED,
        /** The person already has everything the code would unlock, or both sides: the code isn't used. */
        ALREADY_ACTIVE,
        INVALID,
        TOO_MANY_ATTEMPTS
    }

    /** What a code unlocks (ADR 0058). */
    enum Unlocks {
        RENTING(EnumSet.of(Side.RENTING)),
        HOSTING(EnumSet.of(Side.HOSTING)),
        BOTH(EnumSet.allOf(Side.class));

        private final Set<Side> sides;

        Unlocks(Set<Side> sides) {
            this.sides = sides;
        }

        Set<Side> sides() {
            return EnumSet.copyOf(sides);
        }

        String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Unlocks of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditTrail audit;
    private final CodeHashes hashes;
    private final UserSides sides;
    private final SecureRandom random = new SecureRandom();

    InviteService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            AuditTrail audit,
            CodeHashes hashes,
            UserSides sides) {
        this.hashes = hashes;
        this.sides = sides;
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
        return create(count, note, expiresAfter, Unlocks.BOTH, actor);
    }

    /** The same, for codes that unlock {@code unlocks}. */
    Created create(int count, String note, Optional<Duration> expiresAfter, Unlocks unlocks, AuditTrail.Actor actor) {
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
                                insert into identity.invite_codes
                                    (id, code_hash, note, created_by, created_at, expires_at, unlocks)
                                values (:id, :hash, :note, :createdBy, :now, :expiresAt, :unlocks)
                                on conflict (code_hash) do nothing
                                """)
                        .param("id", id)
                        .param("hash", hashes.hash(CodeHashes.Kind.INVITE, code))
                        .param("note", note.strip())
                        .param("createdBy", actor.label())
                        .param("now", Timestamp.from(now))
                        .param("expiresAt", expiresAt == null ? null : Timestamp.from(expiresAt))
                        .param("unlocks", unlocks.code())
                        .update();
                if (inserted == 1) {
                    codes.add(code);
                    codeIds.add(id);
                }
            }
            var after = new LinkedHashMap<String, Object>();
            after.put("count", count);
            after.put("unlocks", unlocks.code());
            after.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
            after.put("ids", codeIds.stream().map(UUID::toString).toList());
            audit.record(new AuditTrail.Entry(
                    actor, "invite.create", "invite_code", codeIds.getFirst().toString(), note, null, after));
            return new Created(List.copyOf(codes), expiresAt);
        });
    }

    /** New codes, in the clear only here, and when they lapse (never, when null). */
    record Created(List<String> codes, @Nullable Instant expiresAt) {}

    /**
     * Tries {@code code} for this user: a waiting account becomes active with what the code unlocks; an active one gains the
     * side it unlocks that the account hasn't. The try is kept whatever the result. A code that would unlock nothing new
     * isn't used, and isn't a try.
     */
    Result activate(UUID userId, String code) {
        return transactions.execute(status -> {
            var now = Instant.now(clock);
            var userStatus = jdbc.sql("select status from identity.users where id = :id for update")
                    .param("id", userId)
                    .query(String.class)
                    .single();
            var waiting = userStatus.equals(UserStatus.WAITING.dbValue());
            if (!waiting && !userStatus.equals(UserStatus.ACTIVE.dbValue())) {
                return Result.ALREADY_ACTIVE;
            }
            var have = sides.of(userId, waiting);
            if (have.containsAll(EnumSet.allOf(Side.class))) {
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
            var found = jdbc.sql("""
                            select id, unlocks from identity.invite_codes
                            where code_hash = :hash and used_at is null
                              and (expires_at is null or expires_at > :now)
                            for update
                            """)
                    .param("now", Timestamp.from(now))
                    .param("hash", hashes.hash(CodeHashes.Kind.INVITE, code))
                    .query((rs, row) ->
                            new FoundCode(rs.getObject("id", UUID.class), Unlocks.of(rs.getString("unlocks"))))
                    .optional();
            if (found.isEmpty()) {
                return Result.INVALID;
            }
            var unlocked = found.get().unlocks().sides();
            unlocked.removeAll(have);
            if (unlocked.isEmpty()) {
                return Result.ALREADY_ACTIVE;
            }
            jdbc.sql("update identity.invite_codes set used_by = :user, used_at = :now where id = :id")
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .param("id", found.get().id())
                    .update();
            // Both sides at once is no rows, as every account had before codes had kinds; one side is its row.
            var all = EnumSet.copyOf(have);
            all.addAll(unlocked);
            if (!(waiting && all.containsAll(EnumSet.allOf(Side.class)))) {
                for (var side : unlocked) {
                    jdbc.sql("""
                                    insert into identity.user_sides (user_id, side, unlocked_at, code_id)
                                    values (:user, :side, :now, :code)
                                    """)
                            .param("user", userId)
                            .param("side", side.code())
                            .param("now", Timestamp.from(now))
                            .param("code", found.get().id())
                            .update();
                }
            }
            if (!waiting) {
                return Result.UNLOCKED;
            }
            jdbc.sql("""
                            update identity.users set status = 'active', updated_at = :now, version = version + 1
                            where id = :id
                            """).param("now", Timestamp.from(now)).param("id", userId).update();
            return Result.ACTIVATED;
        });
    }

    private record FoundCode(UUID id, Unlocks unlocks) {}
}
