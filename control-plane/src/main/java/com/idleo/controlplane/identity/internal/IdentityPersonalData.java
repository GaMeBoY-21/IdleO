package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the identity module holds about a person (ADR 0037, docs/reference/personal-data-map.md): the account, its sign-in
 * identities, API keys and device sign-ins, the notices it accepted and its security log; and its part of erasure.
 * The security log and the notices accepted stay, by opaque id; the account's row stays as a tombstone with no email
 * or name.
 */
@Component
class IdentityPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final Clock clock;

    IdentityPersonalData(JdbcClient jdbc, ExportRows rows, Clock clock) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.clock = clock;
    }

    @Override
    public String module() {
        return "identity";
    }

    @Override
    public List<Group> summary(UUID userId) {
        var params = Map.of("user", userId);
        var groups = new ArrayList<Group>();
        jdbc.sql("select created_at, updated_at from identity.users where id = :user")
                .params(params)
                .query((rs, n) -> new Group(
                        "account",
                        "account",
                        1,
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional()
                .ifPresent(groups::add);
        groups.add(count("API keys", "identity.api_keys", "created_at", "user_id = :user", params));
        groups.add(count("security log entries", "identity.security_events", "at", "user_id = :user", params));
        return groups;
    }

    private Group count(String label, String table, String time, String where, Map<String, ?> params) {
        return jdbc.sql("select count(*), min(%s), max(%s) from %s where %s".formatted(time, time, table, where))
                .params(params)
                .query((rs, n) -> new Group(
                        "account", label, rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part(
                        "account",
                        "account",
                        rows.one(
                                jdbc,
                                "select id, email, display_name, status, created_at, updated_at"
                                        + " from identity.users where id = :user",
                                params)),
                new Part(
                        "account",
                        "signInIdentities",
                        rows.list(
                                jdbc,
                                "select issuer, subject, created_at, last_sign_in_at, signed_in_at"
                                        + " from identity.external_identities where user_id = :user order by created_at",
                                params)),
                new Part(
                        "account",
                        "apiKeys",
                        rows.list(
                                jdbc,
                                "select name, scope, display_prefix, created_via, created_at, last_used_at, revoked_at"
                                        + " from identity.api_keys where user_id = :user order by created_at",
                                params)),
                new Part(
                        "account",
                        "deviceSignIns",
                        rows.list(
                                jdbc,
                                "select device_name, approved_at, redeemed_at from identity.cli_logins"
                                        + " where approved_by = :user order by approved_at",
                                params)),
                new Part(
                        "account",
                        "inviteCode",
                        rows.list(jdbc, "select used_at from identity.invite_codes where used_by = :user", params)),
                new Part(
                        "account",
                        "noticesAccepted",
                        rows.list(
                                jdbc,
                                "select terms_version, privacy_version, age_confirmed, accepted_at"
                                        + " from identity.notice_acceptances where user_id = :user order by accepted_at",
                                params)),
                new Part(
                        "account",
                        "securityLog",
                        rows.list(
                                jdbc,
                                "select kind, detail, host(address) as address, at from identity.security_events"
                                        + " where user_id = :user order by at",
                                params)));
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        var now = Timestamp.from(Instant.now(clock));
        switch (step) {
            case LOCK -> {
                jdbc.sql("update identity.users set status = 'suspended', updated_at = :now, version = version + 1"
                                + " where id = :user and status in ('waiting', 'active')")
                        .param("now", now)
                        .param("user", userId)
                        .update();
                jdbc.sql("update identity.api_keys set revoked_at = :now where user_id = :user and revoked_at is null")
                        .param("now", now)
                        .param("user", userId)
                        .update();
                // A device sign-in still waiting to be redeemed would hand out a key: it's gone.
                jdbc.sql("delete from identity.cli_logins where approved_by = :user and redeemed_at is null")
                        .param("user", userId)
                        .update();
                // revoke-admin's way: the role, its second factor, codes, tries and sessions go together.
                jdbc.sql("delete from identity.admins where user_id = :user")
                        .param("user", userId)
                        .update();
            }
            case DELETE_RECORDS -> {
                jdbc.sql("""
                                delete from identity.cli_logins
                                where approved_by = :user
                                   or api_key_id in (select id from identity.api_keys where user_id = :user)
                                """).param("user", userId).update();
                for (var table : List.of(
                        "identity.api_keys",
                        "identity.external_identities",
                        "identity.activation_attempts",
                        "identity.idempotency_keys")) {
                    jdbc.sql("delete from " + table + " where user_id = :user")
                            .param("user", userId)
                            .update();
                }
                jdbc.sql("update identity.invite_codes set note = null where used_by = :user")
                        .param("user", userId)
                        .update();
                jdbc.sql("""
                                update identity.users
                                set status = 'erased', email = null, display_name = null, email_verified = false,
                                    updated_at = :now, version = version + 1
                                where id = :user and status <> 'erased'
                                """).param("now", now).param("user", userId).update();
            }
            case REMOVE_HOSTS, DELETE_FILES, DE_IDENTIFY -> {}
        }
    }
}
