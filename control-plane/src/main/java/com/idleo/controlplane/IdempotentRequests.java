package com.idleo.controlplane;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a create request at most once per {@code Idempotency-Key}, following the IETF draft
 * "The Idempotency-Key HTTP Header Field" (draft-ietf-httpapi-idempotency-key-header-07,
 * https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/).
 *
 * <ul>
 *   <li>A missing or malformed key gets 400.
 *   <li>The same key with the same request body returns the stored response, byte for byte.
 *   <li>The same key with a different body gets 422.
 *   <li>A request while the first with that key is still running gets 409.
 *   <li>Keys are scoped to the user and expire after 24 hours.
 * </ul>
 *
 * <p>One implementation, shared by every module; each module keeps its own {@code
 * <schema>.idempotency_keys} table, so no module reads another's. The action and the stored
 * response commit in one transaction, so a created resource always has its response recorded. If
 * the action fails, the key is released and the client can retry. A key held by a request that
 * vanished (a crash between claiming the key and finishing) is taken over after a minute.
 */
@Component
public class IdempotentRequests {

    public static final String HEADER = "Idempotency-Key";
    static final Duration KEY_LIFETIME = Duration.ofHours(24);
    static final Duration ABANDONED_AFTER = Duration.ofMinutes(1);

    private static final Pattern TABLE = Pattern.compile("[a-z_]+\\.idempotency_keys");
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final Clock clock;

    IdempotentRequests(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Runs {@code action} once for this user and key, and returns its response as JSON. A repeat
     * returns the stored response instead of running the action again.
     *
     * @param table the module's own key table, for example {@code hosts.idempotency_keys}
     * @param request the parsed request body; its JSON form is the request's fingerprint
     */
    public ResponseEntity<String> execute(
            String table,
            UUID userId,
            @Nullable String idempotencyKeyHeader,
            Object request,
            Supplier<ResponseEntity<?>> action) {
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("not an idempotency key table: " + table);
        }
        var key = parseKey(idempotencyKeyHeader);
        var fingerprint = sha256(json.writeValueAsBytes(request));

        if (!claim(table, userId, key, fingerprint)) {
            var earlier = find(table, userId, key)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT, "A request with this Idempotency-Key is still being processed."));
            if (!Arrays.equals(earlier.requestHash(), fingerprint)) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "This Idempotency-Key was already used with a different request body.");
            }
            if (earlier.completed()) {
                return replay(earlier);
            }
            if (!takeOverAbandoned(table, userId, key)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A request with this Idempotency-Key is still being processed. Retry shortly.");
            }
        }

        try {
            return transactions.execute(status -> {
                var response = action.get();
                var body = json.writeValueAsString(response.getBody());
                var location = response.getHeaders().getLocation();
                jdbc.sql("""
                                update %s
                                set state = 'completed', response_status = :status,
                                    response_location = :location, response_body = :body
                                where user_id = :userId and idempotency_key = :key
                                """.formatted(table))
                        .param("status", response.getStatusCode().value())
                        .param("location", location == null ? null : location.toString())
                        .param("body", body)
                        .param("userId", userId)
                        .param("key", key)
                        .update();
                return toResponse(response.getStatusCode().value(), location, body);
            });
        } catch (RuntimeException failure) {
            release(table, userId, key);
            throw failure;
        }
    }

    private boolean claim(String table, UUID userId, String key, byte[] fingerprint) {
        var now = Instant.now(clock);
        Boolean claimed = transactions.execute(status -> {
            jdbc.sql("delete from %s where user_id = :userId and idempotency_key = :key and expires_at <= :now"
                            .formatted(table))
                    .param("userId", userId)
                    .param("key", key)
                    .param("now", Timestamp.from(now))
                    .update();
            return jdbc.sql("""
                                    insert into %s (user_id, idempotency_key, request_hash, state,
                                                    locked_at, created_at, expires_at)
                                    values (:userId, :key, :hash, 'in_progress', :now, :now, :expiresAt)
                                    on conflict (user_id, idempotency_key) do nothing
                                    """.formatted(table))
                            .param("userId", userId)
                            .param("key", key)
                            .param("hash", fingerprint)
                            .param("now", Timestamp.from(now))
                            .param("expiresAt", Timestamp.from(now.plus(KEY_LIFETIME)))
                            .update()
                    == 1;
        });
        return Boolean.TRUE.equals(claimed);
    }

    private boolean takeOverAbandoned(String table, UUID userId, String key) {
        var now = Instant.now(clock);
        return jdbc.sql("""
                                update %s set locked_at = :now
                                where user_id = :userId and idempotency_key = :key
                                  and state = 'in_progress' and locked_at <= :abandonedBefore
                                """.formatted(table))
                        .param("now", Timestamp.from(now))
                        .param("userId", userId)
                        .param("key", key)
                        .param("abandonedBefore", Timestamp.from(now.minus(ABANDONED_AFTER)))
                        .update()
                == 1;
    }

    private void release(String table, UUID userId, String key) {
        jdbc.sql("delete from %s where user_id = :userId and idempotency_key = :key and state = 'in_progress'"
                        .formatted(table))
                .param("userId", userId)
                .param("key", key)
                .update();
    }

    private Optional<StoredRequest> find(String table, UUID userId, String key) {
        return jdbc.sql("""
                        select request_hash, state, response_status, response_location, response_body
                        from %s where user_id = :userId and idempotency_key = :key
                        """.formatted(table))
                .param("userId", userId)
                .param("key", key)
                .query((rs, row) -> new StoredRequest(
                        rs.getBytes("request_hash"),
                        "completed".equals(rs.getString("state")),
                        rs.getInt("response_status"),
                        rs.getString("response_location"),
                        rs.getString("response_body")))
                .optional();
    }

    private static ResponseEntity<String> replay(StoredRequest stored) {
        var location = stored.responseLocation() == null ? null : URI.create(stored.responseLocation());
        return toResponse(stored.responseStatus(), location, stored.responseBody());
    }

    private static ResponseEntity<String> toResponse(int status, @Nullable URI location, @Nullable String body) {
        var builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (location != null) {
            builder.location(location);
        }
        return builder.body(body);
    }

    /** The header is a Structured Field String ("…"); a bare token is accepted too. */
    public static String parseKey(@Nullable String header) {
        if (header == null || header.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This request needs an Idempotency-Key header: a unique string of up to 255 characters.");
        }
        var value = header.strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        if (!KEY.matcher(value).matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The Idempotency-Key must be 1 to 255 visible ASCII characters, without spaces.");
        }
        return value;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record StoredRequest(
            byte[] requestHash,
            boolean completed,
            int responseStatus,
            @Nullable String responseLocation,
            @Nullable String responseBody) {}
}
