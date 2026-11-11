package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.WebOrigins;
import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An admin's passkeys (ADR 0051), through Spring Security's own WebAuthn support: its {@link
 * Webauthn4JRelyingPartyOperations} checks every registration and assertion, with user verification required and the
 * relying party's id from configuration. What's ours is the storage, in place of its HTTP session: challenges live in the
 * database, used once, for five minutes, so any instance can finish what another began.
 */
@Service
@ConditionalOnWebApplication
class AdminPasskeys implements UserCredentialRepository, PublicKeyCredentialUserEntityRepository {

    private static final Logger log = LoggerFactory.getLogger(AdminPasskeys.class);
    private static final Duration CHALLENGE_LIFE = Duration.ofMinutes(5);

    private static final AuthenticatorSelectionCriteria SELECTION = AuthenticatorSelectionCriteria.builder()
            .userVerification(UserVerificationRequirement.REQUIRED)
            .residentKey(ResidentKeyRequirement.REQUIRED)
            .build();

    private final JdbcClient jdbc;
    private final IdGenerator ids;
    private final Clock clock;
    private final PublicKeyCredentialRpEntity rp;
    private final Webauthn4JRelyingPartyOperations operations;

    /** The name a registration in progress gives the passkey; a request's own, read once by {@link #save}. */
    private final ThreadLocal<String> naming = new ThreadLocal<>();

    AdminPasskeys(JdbcClient jdbc, IdGenerator ids, Clock clock, AdminProperties properties, Environment environment) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
        var passkeys = properties.passkeys();
        this.rp = PublicKeyCredentialRpEntity.builder()
                .id(passkeys.rpId())
                .name(passkeys.rpName())
                .build();
        // A passkey is used on the admin site alone (ADR 0058): an assertion made on another site's page is refused.
        var origins = WebOrigins.of(environment).admin();
        this.operations = new Webauthn4JRelyingPartyOperations(this, this, rp, Set.copyOf(origins));
        operations.setCustomizeCreationOptions(options -> options.authenticatorSelection(SELECTION));
        operations.setCustomizeRequestOptions(
                options -> options.userVerification(UserVerificationRequirement.REQUIRED));
    }

    /** A passkey as its owner sees it. */
    record Passkey(
            UUID id,
            String name,
            Instant createdAt,
            @Nullable Instant lastUsedAt) {}

    /** What a browser sends for a challenge: the credential's parts, each in base64url. */
    record Assertion(
            UUID challengeId,
            String credentialId,
            String clientDataJson,
            String authenticatorData,
            String signature,
            @Nullable String userHandle) {}

    /** A new challenge, with what to pass to {@code navigator.credentials.create} or {@code get}. */
    record Registering(UUID challengeId, PublicKeyCredentialCreationOptions options) {}

    record Asserting(UUID challengeId, PublicKeyCredentialRequestOptions options) {}

    /** The options to make a new passkey, leaving out the ones this admin already has. */
    @Transactional
    Registering startRegistration(UUID userId) {
        var options = operations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication(userId)));
        return new Registering(
                keepChallenge(userId, "register", options.getChallenge().getBytes()), options);
    }

    /** Checks and keeps a new passkey under {@code name}; empty when it doesn't check out. */
    @Transactional
    Optional<Passkey> finishRegistration(
            UUID userId,
            UUID challengeId,
            String name,
            String credentialId,
            String clientDataJson,
            String attestationObject,
            List<String> transports) {
        var challenge = spend(userId, challengeId, "register");
        if (challenge.isEmpty()) {
            return Optional.empty();
        }
        var options = PublicKeyCredentialCreationOptions.builder()
                .rp(rp)
                .user(findByUsername(userId.toString()))
                .challenge(new Bytes(challenge.get()))
                .pubKeyCredParams(
                        PublicKeyCredentialParameters.EdDSA,
                        PublicKeyCredentialParameters.ES256,
                        PublicKeyCredentialParameters.RS256)
                .authenticatorSelection(SELECTION)
                .timeout(CHALLENGE_LIFE)
                .build();
        var response = AuthenticatorAttestationResponse.builder()
                .clientDataJSON(Bytes.fromBase64(clientDataJson))
                .attestationObject(Bytes.fromBase64(attestationObject))
                .transports(
                        transports.stream().map(AuthenticatorTransport::valueOf).toList())
                .build();
        var credential = credential(credentialId, response);
        naming.set(name);
        try {
            var record = operations.registerCredential(
                    new ImmutableRelyingPartyRegistrationRequest(options, new RelyingPartyPublicKey(credential, name)));
            return list(userId).stream()
                    .filter(passkey -> passkey.id().equals(idOf(record.getCredentialId())))
                    .findFirst();
        } catch (RuntimeException refused) {
            log.warn(
                    "A passkey registration was refused: {}", refused.getClass().getSimpleName());
            return Optional.empty();
        } finally {
            naming.remove();
        }
    }

    /** The options to sign with one of this admin's passkeys; none when they have none. */
    @Transactional
    Optional<Asserting> startAssertion(UUID userId) {
        var credentials = findByUserId(handle(userId));
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        var options = options(new Bytes(random()), credentials);
        return Optional.of(new Asserting(
                keepChallenge(userId, "assert", options.getChallenge().getBytes()), options));
    }

    /** Whether this assertion is good: this admin's own passkey, signing this challenge, with the user verified. */
    boolean check(UUID userId, Assertion assertion) {
        var challenge = spend(userId, assertion.challengeId(), "assert");
        if (challenge.isEmpty()) {
            return false;
        }
        var credentials = findByUserId(handle(userId));
        var rawId = Bytes.fromBase64(assertion.credentialId());
        if (credentials.stream()
                .noneMatch(credential -> credential.getCredentialId().equals(rawId))) {
            return false;
        }
        var response = AuthenticatorAssertionResponse.builder()
                .clientDataJSON(Bytes.fromBase64(assertion.clientDataJson()))
                .authenticatorData(Bytes.fromBase64(assertion.authenticatorData()))
                .signature(Bytes.fromBase64(assertion.signature()))
                .userHandle(assertion.userHandle() == null ? null : Bytes.fromBase64(assertion.userHandle()))
                .build();
        var credential = credential(assertion.credentialId(), response);
        try {
            var user = operations.authenticate(new RelyingPartyAuthenticationRequest(
                    options(new Bytes(challenge.get()), credentials), credential));
            return userId.toString().equals(user.getName());
        } catch (RuntimeException refused) {
            log.warn("A passkey assertion was refused: {}", refused.getClass().getSimpleName());
            return false;
        }
    }

    /** Spring Security's builder has no type for the response it's given. */
    @SuppressWarnings("unchecked")
    private static <R extends AuthenticatorResponse> PublicKeyCredential<R> credential(String id, R response) {
        return (PublicKeyCredential<R>) PublicKeyCredential.builder()
                .id(id)
                .rawId(Bytes.fromBase64(id))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .response(response)
                .build();
    }

    private PublicKeyCredentialRequestOptions options(Bytes challenge, List<CredentialRecord> credentials) {
        return PublicKeyCredentialRequestOptions.builder()
                .challenge(challenge)
                .rpId(rp.getId())
                .timeout(CHALLENGE_LIFE)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .allowCredentials(credentials.stream()
                        .map(credential ->
                                org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor.builder()
                                        .id(credential.getCredentialId())
                                        .transports(credential.getTransports())
                                        .build())
                        .toList())
                .build();
    }

    /** This admin's passkeys, oldest first. */
    List<Passkey> list(UUID userId) {
        return jdbc.sql("""
                        select id, name, created_at, last_used_at from identity.admin_passkeys
                        where user_id = :userId order by created_at
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Passkey(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null
                                ? null
                                : rs.getTimestamp("last_used_at").toInstant()))
                .list();
    }

    /** Removes one of this admin's passkeys. TOTP stays, so this can't lock anyone out. */
    boolean remove(UUID userId, UUID passkeyId) {
        return jdbc.sql("delete from identity.admin_passkeys where id = :id and user_id = :userId")
                        .param("id", passkeyId)
                        .param("userId", userId)
                        .update()
                == 1;
    }

    private UUID keepChallenge(UUID userId, String kind, byte[] challenge) {
        var id = ids.newId();
        jdbc.sql("""
                        insert into identity.admin_passkey_challenges (id, user_id, kind, challenge, expires_at)
                        values (:id, :userId, :kind, :challenge, :expiresAt)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("kind", kind)
                .param("challenge", challenge)
                .param("expiresAt", Timestamp.from(now().plus(CHALLENGE_LIFE)))
                .update();
        return id;
    }

    /** The challenge, used up by this; empty when it's used, old, or someone else's. */
    private Optional<byte[]> spend(UUID userId, UUID challengeId, String kind) {
        return jdbc.sql("""
                        update identity.admin_passkey_challenges set used_at = :now
                        where id = :id and user_id = :userId and kind = :kind and used_at is null and expires_at > :now
                        returning challenge
                        """)
                .param("now", Timestamp.from(now()))
                .param("id", challengeId)
                .param("userId", userId)
                .param("kind", kind)
                .query((rs, n) -> rs.getBytes("challenge"))
                .optional();
    }

    private static UsernamePasswordAuthenticationToken authentication(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
    }

    private static byte[] random() {
        return Bytes.random().getBytes();
    }

    /** The WebAuthn user handle: the admin's id, which says nothing about them. */
    private static Bytes handle(UUID userId) {
        return new Bytes(ByteBuffer.allocate(16)
                .putLong(userId.getMostSignificantBits())
                .putLong(userId.getLeastSignificantBits())
                .array());
    }

    private static UUID userOf(Bytes handle) {
        var bytes = ByteBuffer.wrap(handle.getBytes());
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private @Nullable UUID idOf(Bytes credentialId) {
        return jdbc.sql("select id from identity.admin_passkeys where credential_id = :id")
                .param("id", credentialId.getBytes())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    // Spring Security's two repositories, over our tables. An admin's "username" there is their user id.

    @Override
    public @Nullable PublicKeyCredentialUserEntity findById(Bytes id) {
        return entity(userOf(id));
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        return Objects.requireNonNull(entity(UUID.fromString(username)));
    }

    private @Nullable PublicKeyCredentialUserEntity entity(UUID userId) {
        return jdbc.sql("select coalesce(u.email, u.id::text) from identity.users u where u.id = :id")
                .param("id", userId)
                .query(String.class)
                .optional()
                .map(account -> ImmutablePublicKeyCredentialUserEntity.builder()
                        .id(handle(userId))
                        .name(userId.toString())
                        .displayName(account)
                        .build())
                .orElse(null);
    }

    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        // An admin's entity is their account; nothing to keep.
    }

    @Override
    public void delete(Bytes id) {
        jdbc.sql("delete from identity.admin_passkeys where credential_id = :id")
                .param("id", id.getBytes())
                .update();
    }

    @Override
    public void save(CredentialRecord record) {
        var known = idOf(record.getCredentialId());
        if (known != null) {
            jdbc.sql("""
                            update identity.admin_passkeys
                            set signature_count = :count, backup_state = :backup, last_used_at = :now
                            where id = :id
                            """)
                    .param("count", record.getSignatureCount())
                    .param("backup", record.isBackupState())
                    .param("now", Timestamp.from(now()))
                    .param("id", known)
                    .update();
            return;
        }
        jdbc.sql("""
                        insert into identity.admin_passkeys
                            (id, user_id, name, credential_id, public_key, signature_count, uv_initialized,
                             backup_eligible, backup_state, transports, attestation_object, client_data_json, created_at)
                        values (:id, :userId, :name, :credentialId, :publicKey, :count, :uv, :eligible, :backup,
                                string_to_array(:transports, ','), :attestation, :clientData, :now)
                        """)
                .param("id", ids.newId())
                .param("userId", userOf(record.getUserEntityUserId()))
                .param("name", Objects.requireNonNull(naming.get()))
                .param("credentialId", record.getCredentialId().getBytes())
                .param("publicKey", record.getPublicKey().getBytes())
                .param("count", record.getSignatureCount())
                .param("uv", record.isUvInitialized())
                .param("eligible", record.isBackupEligible())
                .param("backup", record.isBackupState())
                .param(
                        "transports",
                        String.join(
                                ",",
                                record.getTransports().stream()
                                        .map(AuthenticatorTransport::getValue)
                                        .toList()))
                .param(
                        "attestation",
                        Objects.requireNonNull(record.getAttestationObject()).getBytes())
                .param(
                        "clientData",
                        Objects.requireNonNull(record.getAttestationClientDataJSON())
                                .getBytes())
                .param("now", Timestamp.from(now()))
                .update();
    }

    @Override
    public @Nullable CredentialRecord findByCredentialId(Bytes credentialId) {
        return jdbc.sql("select * from identity.admin_passkeys where credential_id = :id")
                .param("id", credentialId.getBytes())
                .query(AdminPasskeys::record)
                .optional()
                .orElse(null);
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return jdbc.sql("select * from identity.admin_passkeys where user_id = :userId order by created_at")
                .param("userId", userOf(userId))
                .query(AdminPasskeys::record)
                .list();
    }

    private static CredentialRecord record(ResultSet rs, int row) throws SQLException {
        Array transports = rs.getArray("transports");
        var last = rs.getTimestamp("last_used_at");
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(new Bytes(rs.getBytes("credential_id")))
                .userEntityUserId(handle(rs.getObject("user_id", UUID.class)))
                .publicKey(new ImmutablePublicKeyCose(rs.getBytes("public_key")))
                .signatureCount(rs.getLong("signature_count"))
                .uvInitialized(rs.getBoolean("uv_initialized"))
                .backupEligible(rs.getBoolean("backup_eligible"))
                .backupState(rs.getBoolean("backup_state"))
                .transports(new HashSet<>(Arrays.stream((String[]) transports.getArray())
                        .map(AuthenticatorTransport::valueOf)
                        .toList()))
                .label(rs.getString("name"))
                .attestationObject(new Bytes(rs.getBytes("attestation_object")))
                .attestationClientDataJSON(new Bytes(rs.getBytes("client_data_json")))
                .created(rs.getTimestamp("created_at").toInstant())
                .lastUsed(last == null ? rs.getTimestamp("created_at").toInstant() : last.toInstant())
                .build();
    }
}
