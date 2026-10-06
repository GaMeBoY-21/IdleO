package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.agentgateway.SessionCredentials;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.CheckpointRef;
import gpu.agent.v1.Session.Credentials;
import gpu.agent.v1.Session.RefreshCredentials;
import gpu.agent.v1.Session.StartSession;
import gpu.agent.v1.Session.StoragePermission;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting an attempt (ADR 0016). When a host accepts, the attempt is recorded and its StartSession queued
 * in the same transaction, without credentials. At each delivery the agent gateway asks for credentials
 * through {@link SessionCredentials}: fresh storage grants for the current attempt, never stored. Once the
 * StartSession is on the wire, the agent holds a lease, recorded as the send time plus the lease's length.
 */
@Component
@EnableConfigurationProperties(LeaseProperties.class)
class SessionStarts implements SessionCredentials {

    static final String COMMAND_TYPE = "start_session";

    private final JdbcClient jdbc;
    private final AgentCommands commands;
    private final TemplateCatalog catalog;
    private final StorageGrants grants;
    private final SessionStorage storage;
    private final LeaseProperties leases;
    private final IdGenerator ids;
    private final SessionStore store;
    private final LeaseBook leaseBook;
    private final Checkpoints checkpoints;

    SessionStarts(
            JdbcClient jdbc,
            AgentCommands commands,
            TemplateCatalog catalog,
            StorageGrants grants,
            SessionStorage storage,
            LeaseProperties leases,
            IdGenerator ids,
            SessionStore store,
            LeaseBook leaseBook,
            Checkpoints checkpoints) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.catalog = catalog;
        this.grants = grants;
        this.storage = storage;
        this.leases = leases;
        this.ids = ids;
        this.store = store;
        this.leaseBook = leaseBook;
        this.checkpoints = checkpoints;
    }

    /** Records the session's current attempt on its host and queues the attempt's StartSession. */
    @Transactional(propagation = Propagation.MANDATORY)
    void queue(SessionStore.Session session, String gpuUuid) {
        var hostId = Objects.requireNonNull(session.hostId(), "an accepted session has a host");
        var template = catalog.find(session.templateId())
                .orElseThrow(() -> new IllegalStateException("No template " + session.templateId()));
        var commandId = ids.newId();
        int minuteIndexBase = minuteIndexBase(session);
        var resumeFrom = session.attempt() == 1 ? null : newestCompleteCheckpoint(session.id(), session.attempt());
        jdbc.sql("""
                        insert into sessions.attempts
                            (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,
                             resume_from_attempt, resume_from_seq, price_paise_per_min, created_at)
                        values (:sessionId, :attempt, :hostId, :gpuUuids, :commandId, :base,
                                :resumeAttempt, :resumeSeq, :price, :now)
                        """)
                .param("sessionId", session.id())
                .param("attempt", session.attempt())
                .param("hostId", hostId)
                .param("gpuUuids", new String[] {gpuUuid})
                .param("commandId", commandId)
                .param("base", minuteIndexBase)
                .param("resumeAttempt", resumeFrom == null ? null : resumeFrom.attempt())
                .param("resumeSeq", resumeFrom == null ? null : resumeFrom.seq())
                .param("price", Objects.requireNonNull(session.pricePaisePerMin(), "an accepted session has a price"))
                .param("now", Timestamp.from(store.now()))
                .update();
        var start = StartSession.newBuilder()
                .setSessionId(session.id().toString())
                .setCommandId(commandId.toString())
                .setAttempt(session.attempt())
                .setSpec(SessionSpecs.batch(
                        template, session.vramNeededMb(), Duration.ofSeconds(session.maxRuntimeSeconds())))
                .setLeaseTtl(SessionSpecs.duration(leases.ttl()))
                .setMinuteIndexBase(minuteIndexBase)
                .addGpuUuids(gpuUuid);
        if (resumeFrom != null) {
            start.setResumeFrom(CheckpointRef.newBuilder()
                    .setPrefix(StorageLayout.checkpointsPrefix(session.id(), resumeFrom.attempt()))
                    .setSeq(resumeFrom.seq()));
        }
        commands.enqueue(
                hostId,
                session.id(),
                COMMAND_TYPE,
                OpenStreamResponse.newBuilder().setStartSession(start),
                null,
                true);
    }

    /**
     * The session-wide minute index of this attempt's first minute, from the control plane's own clock (ADR
     * 0007): 0 until any attempt has run; after that, one past the minutes since the session's minute 0, so
     * it never lands on a minute an earlier attempt may have reported.
     */
    private int minuteIndexBase(SessionStore.Session session) {
        if (session.startedAt() == null) {
            return 0;
        }
        return Math.toIntExact(
                Duration.between(session.startedAt(), store.now()).toMinutes() + 1);
    }

    /**
     * The newest checkpoint of an earlier attempt whose completion marker is in storage (proto/README.md
     * "Resume"): storage is listed, not the agent's word or only what was reported, so a checkpoint finished
     * just before its host died still counts. A later attempt only starts after the earlier one is fenced,
     * so a higher attempt's checkpoint is always newer. It's recorded if it wasn't yet.
     */
    private StorageGrants.@Nullable ResumeFrom newestCompleteCheckpoint(UUID sessionId, int attempt) {
        for (int earlier = attempt - 1; earlier >= 1; earlier--) {
            var newest = checkpoints.newest(sessionId, earlier);
            if (newest.isPresent()) {
                return new StorageGrants.ResumeFrom(earlier, newest.get().seq());
            }
        }
        return null;
    }

    @Override
    public Optional<Credentials> mint(UUID hostId, StartSession start) {
        var sessionId = UUID.fromString(start.getSessionId());
        record Current(
                @Nullable Integer resumeAttempt, @Nullable Integer resumeSeq) {}
        var current = jdbc.sql("""
                        select a.resume_from_attempt, a.resume_from_seq
                        from sessions.sessions s
                        join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                        where s.id = :sessionId and s.attempt = :attempt and s.host_id = :hostId
                          and a.command_id = :commandId and s.state in ('PROVISIONING', 'RUNNING')
                        """)
                .param("sessionId", sessionId)
                .param("attempt", start.getAttempt())
                .param("hostId", hostId)
                .param("commandId", UUID.fromString(start.getCommandId()))
                .query((rs, n) -> new Current(
                        rs.getObject("resume_from_attempt", Integer.class),
                        rs.getObject("resume_from_seq", Integer.class)))
                .optional();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(credentials(
                sessionId,
                start.getAttempt(),
                current.get().resumeAttempt(),
                current.get().resumeSeq()));
    }

    /** Fresh grants for the attempt: its own folder, and its input and resume checkpoint to read. */
    private Credentials credentials(
            UUID sessionId, int attempt, @Nullable Integer resumeAttempt, @Nullable Integer resumeSeq) {
        var resumeFrom = resumeAttempt == null || resumeSeq == null
                ? null
                : new StorageGrants.ResumeFrom(resumeAttempt, resumeSeq);
        var minted = grants.mint(sessionId, attempt, resumeFrom, storage.hasAny(StorageLayout.inputPrefix(sessionId)));
        var credentials = Credentials.newBuilder().setAttemptGrant(message(minted.attemptGrant()));
        if (minted.readGrant() != null) {
            credentials.setReadGrant(message(minted.readGrant()));
        }
        return credentials.build();
    }

    @Override
    public Optional<Credentials> mintRefresh(UUID hostId, RefreshCredentials refresh) {
        var sessionId = UUID.fromString(refresh.getSessionId());
        record Current(
                @Nullable Integer resumeAttempt, @Nullable Integer resumeSeq) {}
        return jdbc.sql("""
                        select a.resume_from_attempt, a.resume_from_seq
                        from sessions.sessions s
                        join sessions.attempts a on a.session_id = s.id and a.attempt = s.attempt
                        where s.id = :sessionId and s.attempt = :attempt and s.host_id = :hostId
                          and s.state in ('PROVISIONING', 'RUNNING', 'FINISHING') and a.ended_at is null
                        """)
                .param("sessionId", sessionId)
                .param("attempt", refresh.getAttempt())
                .param("hostId", hostId)
                .query((rs, n) -> new Current(
                        rs.getObject("resume_from_attempt", Integer.class),
                        rs.getObject("resume_from_seq", Integer.class)))
                .optional()
                .map(current ->
                        credentials(sessionId, refresh.getAttempt(), current.resumeAttempt(), current.resumeSeq()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void refreshed(UUID hostId, RefreshCredentials refresh, Instant sentAt) {
        jdbc.sql("""
                        update sessions.attempts set grants_minted_at = :at, refresh_queued_at = null
                        where session_id = :sessionId and attempt = :attempt and host_id = :hostId
                        """)
                .param("at", Timestamp.from(sentAt.truncatedTo(ChronoUnit.MICROS)))
                .param("sessionId", UUID.fromString(refresh.getSessionId()))
                .param("attempt", refresh.getAttempt())
                .param("hostId", hostId)
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void delivered(UUID hostId, StartSession start, Instant sentAt) {
        var sessionId = UUID.fromString(start.getSessionId());
        var at = sentAt.truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        update sessions.attempts
                        set start_delivered_at = coalesce(start_delivered_at, :at), grants_minted_at = :at
                        where session_id = :sessionId and attempt = :attempt and command_id = :commandId
                        """)
                .param("at", Timestamp.from(at))
                .param("sessionId", sessionId)
                .param("attempt", start.getAttempt())
                .param("commandId", UUID.fromString(start.getCommandId()))
                .update();
        // The agent counts its lease from receipt, which is after this send. A redelivery never shortens it.
        if (jdbc.sql("""
                                update sessions.sessions set lease_expires_at = greatest(lease_expires_at, :expiresAt)
                                where id = :sessionId and attempt = :attempt and host_id = :hostId
                                  and state in ('PROVISIONING', 'RUNNING')
                                """)
                        .param("expiresAt", Timestamp.from(at.plus(leases.ttl())))
                        .param("sessionId", sessionId)
                        .param("attempt", start.getAttempt())
                        .param("hostId", hostId)
                        .update()
                == 1) {
            leaseBook.record(sessionId, start.getAttempt(), at, leases.ttl());
        }
    }

    private static gpu.agent.v1.Session.StorageGrant message(StorageGrant grant) {
        return gpu.agent.v1.Session.StorageGrant.newBuilder()
                .setEndpoint(grant.endpoint())
                .setBucket(grant.bucket())
                .setAccessKeyId(grant.accessKeyId())
                .setSecretAccessKey(grant.secretAccessKey())
                .setSessionToken(grant.sessionToken())
                .addAllPrefixes(grant.prefixes())
                .setPermission(
                        grant.readWrite()
                                ? StoragePermission.STORAGE_PERMISSION_READ_WRITE
                                : StoragePermission.STORAGE_PERMISSION_READ_ONLY)
                .setExpiresIn(SessionSpecs.duration(grant.ttl()))
                .build();
    }
}
