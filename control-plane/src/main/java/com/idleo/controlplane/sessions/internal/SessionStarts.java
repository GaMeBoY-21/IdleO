package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.agentgateway.AgentCommands;
import com.idleo.controlplane.agentgateway.SessionCredentials;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.Session.CheckpointRef;
import gpu.agent.v1.Session.Credentials;
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

    SessionStarts(
            JdbcClient jdbc,
            AgentCommands commands,
            TemplateCatalog catalog,
            StorageGrants grants,
            SessionStorage storage,
            LeaseProperties leases,
            IdGenerator ids,
            SessionStore store) {
        this.jdbc = jdbc;
        this.commands = commands;
        this.catalog = catalog;
        this.grants = grants;
        this.storage = storage;
        this.leases = leases;
        this.ids = ids;
        this.store = store;
    }

    /** Records the session's current attempt on its host and queues the attempt's StartSession. */
    @Transactional(propagation = Propagation.MANDATORY)
    void queue(SessionStore.Session session, String gpuUuid) {
        var hostId = Objects.requireNonNull(session.hostId(), "an accepted session has a host");
        var template = catalog.find(session.templateId())
                .orElseThrow(() -> new IllegalStateException("No template " + session.templateId()));
        var commandId = ids.newId();
        // The first attempt starts at the session's minute 0.
        int minuteIndexBase = 0;
        StorageGrants.@Nullable ResumeFrom resumeFrom = null;
        jdbc.sql("""
                        insert into sessions.attempts
                            (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,
                             resume_from_attempt, resume_from_seq, created_at)
                        values (:sessionId, :attempt, :hostId, :gpuUuids, :commandId, :base,
                                :resumeAttempt, :resumeSeq, :now)
                        """)
                .param("sessionId", session.id())
                .param("attempt", session.attempt())
                .param("hostId", hostId)
                .param("gpuUuids", new String[] {gpuUuid})
                .param("commandId", commandId)
                .param("base", minuteIndexBase)
                .param("resumeAttempt", resumeFrom == null ? null : resumeFrom.attempt())
                .param("resumeSeq", resumeFrom == null ? null : resumeFrom.seq())
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
        var resumeAttempt = current.get().resumeAttempt();
        var resumeSeq = current.get().resumeSeq();
        var resumeFrom = resumeAttempt == null || resumeSeq == null
                ? null
                : new StorageGrants.ResumeFrom(resumeAttempt, resumeSeq);
        var minted = grants.mint(
                sessionId, start.getAttempt(), resumeFrom, storage.hasAny(StorageLayout.inputPrefix(sessionId)));
        var credentials = Credentials.newBuilder().setAttemptGrant(message(minted.attemptGrant()));
        if (minted.readGrant() != null) {
            credentials.setReadGrant(message(minted.readGrant()));
        }
        return Optional.of(credentials.build());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void delivered(UUID hostId, StartSession start, Instant sentAt) {
        var sessionId = UUID.fromString(start.getSessionId());
        var at = sentAt.truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        update sessions.attempts set start_delivered_at = coalesce(start_delivered_at, :at)
                        where session_id = :sessionId and attempt = :attempt and command_id = :commandId
                        """)
                .param("at", Timestamp.from(at))
                .param("sessionId", sessionId)
                .param("attempt", start.getAttempt())
                .param("commandId", UUID.fromString(start.getCommandId()))
                .update();
        // The agent counts its lease from receipt, which is after this send. A redelivery never shortens it.
        jdbc.sql("""
                        update sessions.sessions set lease_expires_at = greatest(lease_expires_at, :expiresAt)
                        where id = :sessionId and attempt = :attempt and host_id = :hostId
                          and state in ('PROVISIONING', 'RUNNING')
                        """)
                .param("expiresAt", Timestamp.from(at.plus(leases.ttl())))
                .param("sessionId", sessionId)
                .param("attempt", start.getAttempt())
                .param("hostId", hostId)
                .update();
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
