package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ExportRows;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.hosts.HostOwners;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the sessions module holds about a renter (ADR 0037, docs/reference/personal-data-map.md): their sessions, inputs
 * and timelines; their uploads; and in storage their files, each attempt's outputs, logs and checkpoints. A session
 * stays as a money record, its parameters and request cleared; everything in storage goes, and the listing is checked
 * empty.
 */
@Component
class SessionsPersonalData implements PersonalData {

    /** How long an export's links to files work: seven days, the most a pre-signed link may last. */
    static final Duration LINKS = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final ExportRows rows;
    private final SessionStorage storage;
    private final AgentCommands commands;
    private final HostOwners owners;

    SessionsPersonalData(
            JdbcClient jdbc, ExportRows rows, SessionStorage storage, AgentCommands commands, HostOwners owners) {
        this.owners = owners;
        this.jdbc = jdbc;
        this.rows = rows;
        this.storage = storage;
        this.commands = commands;
    }

    @Override
    public String module() {
        return "sessions";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of(
                group("sessions", "sessions", "sessions.sessions", "created_at", "renter_id = :user", userId),
                group(
                        "files",
                        "uploads",
                        "sessions.uploads",
                        "created_at",
                        "user_id = :user and status in ('pending', 'ready')",
                        userId));
    }

    private Group group(String group, String label, String table, String time, String where, UUID userId) {
        return jdbc.sql("select count(*), min(%s), max(%s) from %s where %s".formatted(time, time, table, where))
                .param("user", userId)
                .query((rs, n) -> new Group(
                        group, label, rs.getLong(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))))
                .single();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public List<Part> export(UUID userId) {
        var params = Map.of("user", userId);
        return List.of(
                new Part("sessions", "sessions", rows.list(jdbc, """
                                select id, template_id, type, state, attempt, created_at, started_at, state_changed_at,
                                       error_code, price_paise_per_min, max_price_paise_per_min, max_spend_paise,
                                       max_runtime_seconds, vram_needed_mb, params, presets
                                from sessions.sessions where renter_id = :user order by created_at
                                """, params)),
                new Part("sessions", "inputs", rows.list(jdbc, """
                                select i.session_id, i.name, i.upload_id, i.url from sessions.session_inputs i
                                join sessions.sessions s on s.id = i.session_id
                                where s.renter_id = :user order by s.created_at, i.name
                                """, params)),
                new Part("sessions", "timeline", rows.list(jdbc, """
                                select e.session_id, e.from_state, e.to_state, e.reason, e.at
                                from sessions.session_events e join sessions.sessions s on s.id = e.session_id
                                where s.renter_id = :user order by e.at, e.id
                                """, params)),
                new Part("files", "uploads", uploads(userId)),
                new Part("files", "sessionFiles", sessionFiles(userId)));
    }

    /** Each upload with its files and a link to each, while the upload is still kept. */
    private List<Map<String, Object>> uploads(UUID userId) {
        var uploads = rows.list(jdbc, """
                select id, name, status, size_bytes, file_count, created_at, completed_at, last_used_at, expires_at
                from sessions.uploads where user_id = :user order by created_at
                """, Map.of("user", userId));
        for (var upload : uploads) {
            var files = rows.list(jdbc, """
                    select path, size_bytes, completed_at, object_key from sessions.upload_files
                    where upload_id = :upload order by path
                    """, Map.of("upload", UUID.fromString((String) upload.get("id"))));
            var ready = "ready".equals(upload.get("status"));
            for (var file : files) {
                var key = (String) file.remove("objectKey");
                file.put("link", ready && file.get("completedAt") != null ? link(key) : null);
            }
            upload.put("files", files);
        }
        return uploads;
    }

    /** What each attempt of each of their sessions left in storage: outputs, logs and checkpoints, with links. */
    private List<Map<String, Object>> sessionFiles(UUID userId) {
        var files = new ArrayList<Map<String, Object>>();
        for (var sessionId : sessions(userId)) {
            var prefix = StorageLayout.sessionPrefix(sessionId);
            for (var object : storage.list(prefix)) {
                var file = new LinkedHashMap<String, Object>();
                file.put("sessionId", sessionId.toString());
                file.put("path", object.key().substring(prefix.length()));
                file.put("sizeBytes", object.size());
                file.put("storedAt", object.lastModified().toString());
                file.put("link", link(object.key()));
                files.add(file);
            }
        }
        return files;
    }

    private String link(String key) {
        return storage.downloadLink(key, LINKS).toString();
    }

    private List<UUID> sessions(UUID userId) {
        return jdbc.sql("select id from sessions.sessions where renter_id = :user order by created_at")
                .param("user", userId)
                .query(UUID.class)
                .list();
    }

    @Override
    public List<Blocker> blockers(UUID userId) {
        var live = jdbc.sql("""
                        select count(*) from sessions.sessions
                        where renter_id = :user and state not in (:final)
                        """)
                .param("user", userId)
                .param("final", finalStates())
                .query(Long.class)
                .single();
        return live == 0
                ? List.of()
                : List.of(new Blocker(
                        "session-running",
                        "You have a session that hasn't ended. Stop it, or wait for it to finish.",
                        null));
    }

    private static List<String> finalStates() {
        return java.util.Arrays.stream(SessionState.values())
                .filter(state -> state.isFinal)
                .map(Enum::name)
                .toList();
    }

    @Override
    @Transactional
    public void erase(UUID userId, ErasureStep step) {
        switch (step) {
            case DELETE_FILES -> {
                for (var upload : jdbc.sql("select id from sessions.uploads where user_id = :user")
                        .param("user", userId)
                        .query(UUID.class)
                        .list()) {
                    for (var multipart : jdbc.sql("""
                                    select object_key, multipart_id from sessions.upload_files
                                    where upload_id = :upload and multipart_id is not null
                                    """)
                            .param("upload", upload)
                            .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                            .list()) {
                        storage.abortMultipart(multipart.getKey(), multipart.getValue());
                    }
                    storage.deletePrefix(StorageLayout.uploadPrefix(upload));
                }
                for (var session : sessions(userId)) {
                    storage.deletePrefix(StorageLayout.sessionPrefix(session));
                }
                for (var host : owners.ownedBy(userId)) {
                    storage.deletePrefix(StorageLayout.speedTestPrefix(host.id()));
                }
            }
            case DELETE_RECORDS -> {
                var params = Map.of("user", userId);
                jdbc.sql("""
                                delete from sessions.session_inputs
                                where session_id in (select id from sessions.sessions where renter_id = :user)
                                   or upload_id in (select id from sessions.uploads where user_id = :user)
                                """).params(params).update();
                jdbc.sql("""
                                delete from sessions.upload_files
                                where upload_id in (select id from sessions.uploads where user_id = :user)
                                """).params(params).update();
                jdbc.sql("delete from sessions.uploads where user_id = :user")
                        .params(params)
                        .update();
                jdbc.sql("""
                                delete from sessions.checkpoints
                                where session_id in (select id from sessions.sessions where renter_id = :user)
                                """).params(params).update();
                for (var session : sessions(userId)) {
                    commands.forget(session);
                }
            }
            case DE_IDENTIFY -> {
                // The columns are required, so emptied rather than nulled: no parameter, no preset, and a key that
                // is only the session's own id.
                jdbc.sql("""
                                update sessions.sessions
                                set params = '{}'::jsonb, presets = '{}'::jsonb, idempotency_key = id::text,
                                    request_hash = ''
                                where renter_id = :user and idempotency_key <> id::text
                                """).param("user", userId).update();
                // The owner's GPUs' own uuids, hardware ids, in the attempts their machines ran.
                var hosts = owners.ownedBy(userId).stream()
                        .map(HostOwners.OwnedHost::id)
                        .toList();
                if (!hosts.isEmpty()) {
                    jdbc.sql("update sessions.attempts set gpu_uuids = '{}' where host_id in (:hosts)"
                                    + " and cardinality(gpu_uuids) > 0")
                            .param("hosts", hosts)
                            .update();
                }
            }
            case LOCK, REMOVE_HOSTS -> {}
        }
    }
}
