package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.UserDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Linking a host by approval (ADR 0032), shaped like the CLI's device sign-in (ADR 0025).
 *
 * <ol>
 *   <li>The agent asks to be linked, with what it knows of its machine (name, OS, version, GPUs), and gets a link's id
 *       and a poll token only it holds, which is stored as a hash.
 *   <li>Its owner opens the link in the web app, signed in, sees exactly which machine is asking, and approves or
 *       denies it. Approving makes the host (the same service as {@code POST /v1/hosts}) with its listing, and ties the
 *       link to it.
 *   <li>The agent's poll, with its token, then gets the host's one-time enrolment code, exactly once: the code is made
 *       and the link spent in one transaction, under the link's row lock. The code is stored only as a hash (ADR
 *       0011), so it can't be made earlier and kept; the agent trades it for its certificate as always.
 * </ol>
 *
 * A link lasts {@code hosts.link.ttl}; after that, or once spent, it is expired. Each link is approved once, and
 * approving it again changes nothing. Approving and denying are in the audit log. An approved host is created pending
 * (ADR 0015): during the invite-only pilot an admin activates it ({@code hosts.auto-activate} is false).
 */
@Service
@EnableConfigurationProperties(HostLinkProperties.class)
class HostLinks {

    /** A GPU the machine says it has: what the approval page shows, and nothing the control plane relies on. */
    record Gpu(String model, int vramMb) {}

    record Machine(String name, String os, String agentVersion, List<Gpu> gpus) {}

    record Started(UUID id, String pollToken, Instant expiresAt) {}

    enum State {
        PENDING,
        APPROVED,
        DENIED,
        EXPIRED
    }

    /** A link as the approval page shows it. */
    record Link(
            UUID id,
            Machine machine,
            State state,
            Instant expiresAt,
            @Nullable UUID hostId) {

        Link withHost(UUID host) {
            return new Link(id, machine, State.APPROVED, expiresAt, host);
        }
    }

    /** What the owner confirmed: the name, and the listing the host starts with. */
    record Terms(
            String name,
            int batchPricePaisePerMin,
            int gpuTempCapC,
            @Nullable Integer keepFreeGb) {}

    sealed interface Approval {
        record Approved(Link link, UUID hostId) implements Approval {}

        record Denied() implements Approval {}

        record Expired() implements Approval {}

        record NotFound() implements Approval {}
    }

    sealed interface Denial {
        record Done(Link link) implements Denial {}

        record AlreadyApproved() implements Denial {}

        record Expired() implements Denial {}

        record NotFound() implements Denial {}
    }

    sealed interface Redemption {
        record Handed(
                UUID hostId,
                String hostName,
                String enrolmentCode,
                Instant codeExpiresAt,
                @Nullable String ownerHint) implements Redemption {}

        record WaitingForApproval() implements Redemption {}

        record Denied() implements Redemption {}

        record Expired() implements Redemption {}

        record NotFound() implements Redemption {}
    }

    private static final int MAX_CONCURRENT_SESSIONS = 1;
    private static final TypeReference<List<Gpu>> GPU_LIST = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final HostService hosts;
    private final HostListingService listings;
    private final UserDirectory users;
    private final AuditTrail audit;
    private final HostInstalls installs;
    private final IdGenerator ids;
    private final HostLinkProperties properties;
    private final HostMetrics metrics;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();

    HostLinks(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            HostService hosts,
            HostListingService listings,
            UserDirectory users,
            AuditTrail audit,
            HostInstalls installs,
            IdGenerator ids,
            HostLinkProperties properties,
            Clock clock,
            HostMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.hosts = hosts;
        this.listings = listings;
        this.users = users;
        this.audit = audit;
        this.installs = installs;
        this.ids = ids;
        this.properties = properties;
        this.clock = clock;
    }

    HostLinkProperties properties() {
        return properties;
    }

    Started start(Machine machine, @Nullable UUID installId) {
        var now = Instant.now(clock);
        var expiresAt = now.plus(properties.link().ttl());
        var token = new byte[32];
        random.nextBytes(token);
        var pollToken = base32(token);
        var id = ids.newId();
        jdbc.sql("""
                        insert into hosts.host_links
                            (id, poll_token_hash, machine_name, os, agent_version, gpus, created_at, expires_at,
                             install_id)
                        values (:id, :token, :name, :os, :version, cast(:gpus as jsonb), :now, :expiresAt,
                                :installId)
                        """)
                .param("id", id)
                .param("token", sha256(pollToken))
                .param("name", machine.name())
                .param("os", machine.os())
                .param("version", machine.agentVersion())
                .param("gpus", json.writeValueAsString(machine.gpus()))
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("installId", installId)
                .update();
        metrics.linkStarted();
        return new Started(id, pollToken, expiresAt);
    }

    Optional<Link> find(UUID id) {
        return lock(id, false).map(row -> row.link(Instant.now(clock)));
    }

    /**
     * Approves a link for this person, making their host. Approving a link they already approved returns the same host;
     * one that is denied or has run out is refused; another person's approved link is as if it didn't exist.
     */
    Approval approve(UUID id, UUID userId, Terms terms) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var found = lock(id, true);
            if (found.isEmpty()) {
                return new Approval.NotFound();
            }
            var row = found.get();
            var now = Instant.now(clock);
            if (row.approvedBy() != null) {
                return row.approvedBy().equals(userId)
                        ? new Approval.Approved(row.link(now), Objects.requireNonNull(row.hostId()))
                        : new Approval.NotFound();
            }
            if (row.deniedAt() != null) {
                return new Approval.Denied();
            }
            if (!row.expiresAt().isAfter(now)) {
                return new Approval.Expired();
            }
            var host = hosts.create(userId, terms.name());
            var keepFree = terms.keepFreeGb();
            listings.put(
                            userId,
                            host.id(),
                            terms.batchPricePaisePerMin(),
                            terms.gpuTempCapC(),
                            MAX_CONCURRENT_SESSIONS,
                            keepFree)
                    .orElseThrow();
            jdbc.sql("""
                            update hosts.host_links set approved_by = :user, approved_at = :now, host_id = :host
                            where id = :id
                            """)
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .param("host", host.id())
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    AuditTrail.Actor.owner(userId),
                    "host_link.approve",
                    "host_link",
                    id.toString(),
                    "the owner approved linking this machine",
                    null,
                    Map.of(
                            "hostId",
                            host.id().toString(),
                            "machineName",
                            row.machine().name())));
            if (row.installId() != null) {
                installs.approved(row.installId());
            }
            metrics.linkApproved();
            return new Approval.Approved(row.link(now).withHost(host.id()), host.id());
        }));
    }

    /** Denies a link: "this isn't my laptop". The agent's next poll hears it. */
    Denial deny(UUID id, UUID userId) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var found = lock(id, true);
            if (found.isEmpty()) {
                return new Denial.NotFound();
            }
            var row = found.get();
            var now = Instant.now(clock);
            if (row.approvedBy() != null) {
                return new Denial.AlreadyApproved();
            }
            if (row.deniedAt() != null) {
                return row.deniedBy().equals(userId) ? new Denial.Done(row.link(now)) : new Denial.NotFound();
            }
            if (!row.expiresAt().isAfter(now)) {
                return new Denial.Expired();
            }
            jdbc.sql("update hosts.host_links set denied_by = :user, denied_at = :now where id = :id")
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .param("id", id)
                    .update();
            audit.record(new AuditTrail.Entry(
                    AuditTrail.Actor.owner(userId),
                    "host_link.deny",
                    "host_link",
                    id.toString(),
                    "the person said this isn't their machine",
                    null,
                    Map.of("machineName", row.machine().name())));
            metrics.linkDenied();
            return new Denial.Done(new Link(id, row.machine(), State.DENIED, row.expiresAt(), null));
        }));
    }

    /**
     * The agent's poll: the host's enrolment code once approved, exactly once. A wrong poll token is as if there were no
     * link.
     */
    Redemption redeem(UUID id, String pollToken) {
        return Objects.requireNonNull(transactions.execute(status -> {
            var found = lock(id, true);
            if (found.isEmpty() || !MessageDigest.isEqual(found.get().pollTokenHash(), sha256(pollToken))) {
                return new Redemption.NotFound();
            }
            var row = found.get();
            if (row.deniedAt() != null) {
                return new Redemption.Denied();
            }
            if (row.redeemedAt() != null || !row.expiresAt().isAfter(Instant.now(clock))) {
                return new Redemption.Expired();
            }
            if (row.approvedBy() == null) {
                return new Redemption.WaitingForApproval();
            }
            var issued = hosts.issueCode(row.approvedBy(), Objects.requireNonNull(row.hostId()))
                    .orElseThrow();
            jdbc.sql("update hosts.host_links set redeemed_at = :now where id = :id")
                    .param("now", Timestamp.from(Instant.now(clock)))
                    .param("id", id)
                    .update();
            var hostName = jdbc.sql("select name from hosts.hosts where id = :id")
                    .param("id", row.hostId())
                    .query(String.class)
                    .single();
            return new Redemption.Handed(
                    row.hostId(), hostName, issued.code(), issued.expiresAt(), ownerHint(row.approvedBy()));
        }));
    }

    /** What the agent says when it's linked: the first letter of the owner's address, never more. */
    private @Nullable String ownerHint(UUID userId) {
        return users.verifiedEmail(userId)
                .filter(email -> !email.isBlank())
                .map(email -> email.substring(0, email.offsetByCodePoints(0, 1)))
                .orElse(null);
    }

    private Optional<Row> lock(UUID id, boolean forUpdate) {
        return jdbc.sql("""
                        select id, poll_token_hash, machine_name, os, agent_version, gpus::text as gpus, expires_at,
                               approved_by, host_id, denied_by, denied_at, redeemed_at, install_id
                        from hosts.host_links where id = :id
                        """ + (forUpdate ? " for update" : ""))
                .param("id", id)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getBytes("poll_token_hash"),
                        new Machine(
                                rs.getString("machine_name"),
                                rs.getString("os"),
                                rs.getString("agent_version"),
                                json.readValue(rs.getString("gpus"), GPU_LIST)),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("approved_by", UUID.class),
                        rs.getObject("host_id", UUID.class),
                        rs.getObject("denied_by", UUID.class),
                        rs.getTimestamp("denied_at") == null
                                ? null
                                : rs.getTimestamp("denied_at").toInstant(),
                        rs.getTimestamp("redeemed_at") == null
                                ? null
                                : rs.getTimestamp("redeemed_at").toInstant(),
                        rs.getObject("install_id", UUID.class)))
                .optional();
    }

    private record Row(
            UUID id,
            byte[] pollTokenHash,
            Machine machine,
            Instant expiresAt,
            @Nullable UUID approvedBy,
            @Nullable UUID hostId,
            @Nullable UUID deniedBy,
            @Nullable Instant deniedAt,
            @Nullable Instant redeemedAt,
            @Nullable UUID installId) {

        Link link(Instant now) {
            var state = deniedAt != null
                    ? State.DENIED
                    : approvedBy != null ? State.APPROVED : expiresAt.isAfter(now) ? State.PENDING : State.EXPIRED;
            return new Link(id, machine, state, expiresAt, hostId);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    /** Lower-case base32 without padding (RFC 4648 alphabet): 256 random bits are 52 characters. */
    private static String base32(byte[] bytes) {
        var out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return out.toString();
    }
}
