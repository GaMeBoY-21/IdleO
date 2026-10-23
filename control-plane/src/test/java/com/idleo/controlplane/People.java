package com.idleo.controlplane;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * A person with something in every table the personal data map can find them in (ADR 0037), each value carrying
 * their marker, so a test can tell whose data is whose: an export must hold all of one person's and none of another's,
 * and erasure must leave of them only what the map says it keeps. Written straight into the tables where the real path
 * needs an agent, and through the API where it's quick (the account, the Terms, a key, credit).
 */
public final class People {

    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static final com.fasterxml.uuid.impl.TimeBasedEpochGenerator IDS =
            com.fasterxml.uuid.Generators.timeBasedEpochGenerator();

    private People() {}

    /** One made person: how to call as them, their marker and address, and their ids for the map's conditions. */
    public record Made(
            TestUsers.TestUser user, String subject, String marker, String email, PersonalDataMap.Person ids) {

        /** A bearer header for them, signed in at the provider at {@code signedIn}. */
        public String signedInAt(Instant signedIn) {
            return TestUsers.bearer(subject, email, signedIn, Duration.ofDays(30));
        }
    }

    public static S3Client storage(Environment environment) {
        return S3Client.builder()
                .endpointOverride(URI.create(environment.getRequiredProperty("sessions.storage.endpoint")))
                .region(Region.of(environment.getRequiredProperty("sessions.storage.region")))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        environment.getRequiredProperty("sessions.storage.access-key-id"),
                        environment.getRequiredProperty("sessions.storage.secret-access-key"))))
                .build();
    }

    public static Made make(MockMvc mvc, JdbcTemplate jdbc, Environment environment, MutableClock clock, String marker)
            throws Exception {
        var email = marker + "@example.com";
        var subject = UUID.randomUUID().toString();
        var user = TestUsers.signUpAs(mvc, subject, email, Duration.ofDays(30));
        var me = user.id();
        var now = clock.instant();
        var bucket = environment.getRequiredProperty("sessions.storage.bucket");

        // Identity: the Terms, a key through the API; a device sign-in, an invite code, a try, an admin's trace.
        mvc.perform(post("/v1/me/notices")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(notices(environment)))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/api-keys")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + marker + " laptop\",\"scope\":\"full\"}"))
                .andExpect(status().isCreated());
        jdbc.update(
                "insert into identity.cli_logins (id, user_code_hash, poll_token_hash, device_name, created_at,"
                        + " expires_at, approved_by, approved_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                IDS.generate(),
                random32(),
                random32(),
                marker + " desktop",
                ts(now),
                ts(now.plusSeconds(600)),
                me,
                ts(now));
        jdbc.update(
                "insert into identity.invite_codes (id, code_hash, note, created_by, created_at, used_by, used_at)"
                        + " values (?, ?, ?, 'operator', ?, ?, ?)",
                IDS.generate(),
                random32(),
                "for " + marker,
                ts(now),
                me,
                ts(now));
        jdbc.update(
                "insert into identity.activation_attempts (id, user_id, attempted_at) values (?, ?, ?)",
                IDS.generate(),
                me,
                ts(now));
        for (var schema : List.of("identity", "hosts", "trust")) {
            jdbc.update(
                    "insert into " + schema + ".idempotency_keys (user_id, idempotency_key, request_hash, state,"
                            + " response_status, response_body, locked_at, created_at, expires_at)"
                            + " values (?, ?, ?, 'completed', 201, ?, ?, ?, ?)",
                    me,
                    marker + "-key",
                    random32(),
                    "{\"name\":\"" + marker + "\"}",
                    ts(now),
                    ts(now),
                    ts(now.plus(Duration.ofDays(1))));
        }
        jdbc.update(
                "insert into identity.admins (user_id, granted_by, granted_at) values (?, 'operator', ?)", me, ts(now));
        jdbc.update(
                "insert into identity.admin_recovery_codes (id, user_id, code_hash, created_at) values (?, ?, ?, ?)",
                IDS.generate(),
                me,
                random32(),
                ts(now));
        jdbc.update(
                "insert into identity.admin_code_attempts (id, user_id, succeeded, attempted_at) values (?, ?, false, ?)",
                IDS.generate(),
                me,
                ts(now));
        var identity =
                jdbc.queryForObject("select id from identity.external_identities where user_id = ?", UUID.class, me);
        jdbc.update(
                "insert into identity.admin_sessions (id, user_id, identity_id, token_hash, created_at, expires_at,"
                        + " ends_by) values (?, ?, ?, ?, ?, ?, ?)",
                IDS.generate(),
                me,
                identity,
                random32(),
                ts(now),
                ts(now.plusSeconds(1800)),
                ts(now.plusSeconds(3600)));

        // Hosting: a removed machine (a busy one would stop the erasure), its GPU, price, history, link and setup.
        var host = IDS.generate();
        var install = IDS.generate();
        jdbc.update(
                "insert into hosts.hosts (id, owner_user_id, name, status, created_at, updated_at, version, os,"
                        + " agent_version, removed_at) values (?, ?, ?, 'removed', ?, ?, 0, 'windows', '0.1.0', ?)",
                host,
                me,
                marker + "'s gaming PC",
                ts(now),
                ts(now),
                ts(now));
        jdbc.update(
                "insert into hosts.host_gpus (host_id, gpu_uuid, model, vram_mb, first_seen_at, last_reported_at)"
                        + " values (?, ?, 'NVIDIA GeForce RTX 4060 Laptop GPU', 8188, ?, ?)",
                host,
                "GPU-" + marker,
                ts(now),
                ts(now));
        jdbc.update(
                "insert into hosts.price_listings (host_id, workload_type, price_paise_per_min, created_at, updated_at)"
                        + " values (?, 'batch', 12, ?, ?)",
                host,
                ts(now),
                ts(now));
        jdbc.update(
                "insert into hosts.status_changes (id, host_id, from_status, to_status, reason, changed_by, created_at)"
                        + " values (?, ?, 'active', 'removed', 'removed by its owner', ?, ?)",
                IDS.generate(),
                host,
                "owner " + me,
                ts(now));
        jdbc.update(
                "insert into hosts.host_links (id, poll_token_hash, machine_name, os, agent_version, gpus, created_at,"
                        + " expires_at, approved_by, approved_at, host_id, install_id)"
                        + " values (?, ?, ?, 'windows', '0.1.0', cast(? as jsonb), ?, ?, ?, ?, ?, ?)",
                IDS.generate(),
                random32(),
                marker.toUpperCase(Locale.ROOT) + "-PC",
                "[]",
                ts(now),
                ts(now.plusSeconds(600)),
                me,
                ts(now),
                host,
                install);
        jdbc.update(
                "insert into hosts.install_events (id, install_id, step, outcome, at, received_at)"
                        + " values (?, ?, 'listed', 'passed', ?, ?)",
                IDS.generate(),
                install,
                ts(now),
                ts(now));
        jdbc.update(
                "insert into hosts.self_tests (host_id, passed, distro_version, checks, reported_at)"
                        + " values (?, true, '0.1.1', cast('[]' as jsonb), ?)",
                host,
                ts(now));
        jdbc.update(
                "insert into hosts.update_results (host_id, version, outcome, detail, reported_at)"
                        + " values (?, '0.1.0', 'installed', '', ?)",
                host,
                ts(now));
        jdbc.update(
                "insert into hosts.enrolment_codes (host_id, code_hash, expires_at, created_at) values (?, ?, ?, ?)",
                host,
                random32(),
                ts(now.plusSeconds(600)),
                ts(now));

        // A finished session on their own machine, with its upload, inputs, timeline, attempt, and objects in storage.
        var upload = IDS.generate();
        var uploadKey = "uploads/" + upload + "/files/" + marker + "-data.jsonl";
        jdbc.update(
                "insert into sessions.uploads (id, user_id, name, size_bytes, part_size_bytes, status, idempotency_key,"
                        + " request_hash, created_at, completed_at, expires_at, version, file_count, last_used_at)"
                        + " values (?, ?, ?, 5, 67108864, 'ready', ?, 'hash', ?, ?, ?, 0, 1, ?)",
                upload,
                me,
                marker + "-data.jsonl",
                "upload-" + upload,
                ts(now),
                ts(now),
                ts(now.plus(Duration.ofDays(30))),
                ts(now));
        jdbc.update(
                "insert into sessions.upload_files (upload_id, path, stored_path, size_bytes, object_key, completed_at)"
                        + " values (?, ?, ?, 5, ?, ?)",
                upload,
                marker + "-data.jsonl",
                marker + "-data.jsonl",
                uploadKey,
                ts(now));
        var session = IDS.generate();
        jdbc.update(
                "insert into sessions.sessions (id, renter_id, host_id, type, state, attempt, template_id,"
                        + " image_digest, vram_needed_mb, max_price_paise_per_min, price_paise_per_min, max_spend_paise,"
                        + " max_runtime_seconds, idempotency_key, request_hash, state_changed_at, version, created_at,"
                        + " started_at, params, presets) values (?, ?, ?, 'batch', 'COMPLETED', 1, 'echo-input',"
                        + " 'image@sha256:1', 1024, 20, 12, 1000, 600, ?, 'hash', ?, 0, ?, ?, cast(? as jsonb),"
                        + " cast(? as jsonb))",
                session,
                me,
                host,
                marker + "-booking",
                ts(now),
                ts(now),
                ts(now),
                "{\"note\":\"" + marker + "\"}",
                "{\"size\":\"" + marker + "\"}");
        jdbc.update(
                "insert into sessions.session_inputs (session_id, name, upload_id) values (?, 'data', ?)",
                session,
                upload);
        jdbc.update(
                "insert into sessions.session_events (id, session_id, from_state, to_state, reason, at)"
                        + " values (?, ?, 'RUNNING', 'COMPLETED', 'exited', ?)",
                IDS.generate(),
                session,
                ts(now));
        jdbc.update(
                "insert into sessions.attempts (session_id, attempt, host_id, gpu_uuids, command_id, minute_index_base,"
                        + " created_at, price_paise_per_min) values (?, 1, ?, ?, ?, 0, ?, 12)",
                session,
                host,
                new String[] {"GPU-" + marker},
                IDS.generate(),
                ts(now));
        jdbc.update(
                "insert into sessions.lease_coverage (id, session_id, attempt, covered_from, covered_until)"
                        + " values (?, ?, 1, ?, ?)",
                IDS.generate(),
                session,
                ts(now),
                ts(now.plusSeconds(60)));
        var checkpointKey = "sessions/" + session + "/attempt-1/checkpoints/00000001/state.bin";
        jdbc.update(
                "insert into sessions.checkpoints (id, session_id, attempt, object_key, step, created_at, completed_at)"
                        + " values (?, ?, 1, ?, 1, ?, ?)",
                IDS.generate(),
                session,
                checkpointKey,
                ts(now),
                ts(now));
        try (var s3 = storage(environment)) {
            for (var key : List.of(
                    uploadKey,
                    checkpointKey,
                    "sessions/" + session + "/attempt-1/outputs/" + marker + "-result.json",
                    "sessions/" + session + "/attempt-1/logs/00000000.log",
                    "speedtest/" + host + "/probe")) {
                s3.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString(marker));
            }
        }
        jdbc.update(
                "insert into agentgateway.agent_commands (id, host_id, session_id, type, payload, message_id,"
                        + " requires_ack, status, created_at) values (?, ?, ?, 'start_session', ?, ?, true, 'acked', ?)",
                IDS.generate(),
                host,
                session,
                marker.getBytes(StandardCharsets.UTF_8),
                IDS.generate(),
                ts(now));
        jdbc.update(
                "insert into scheduling.offers (id, session_id, host_id, attempt_number, status, expires_at,"
                        + " created_at, session_attempt, gpu_uuid) values (?, ?, ?, 1, 'accepted', ?, ?, 1, ?)",
                IDS.generate(),
                session,
                host,
                ts(now),
                ts(now),
                "GPU-" + marker);

        // Metering and money: the session's record of minutes, and credit given through the console.
        jdbc.update(
                "insert into metering.metered_sessions (session_id, renter_id, started_at, max_spend_paise,"
                        + " created_at, next_window_at, ended_at, closed_at) values (?, ?, ?, 1000, ?, ?, ?, ?)",
                session,
                me,
                ts(now),
                ts(now),
                ts(now),
                ts(now),
                ts(now));
        jdbc.update("insert into metering.metered_attempts (session_id, attempt) values (?, 1)", session);
        jdbc.update(
                "insert into metering.usage_minutes (session_id, minute_index, minute_at) values (?, 0, ?)",
                session,
                ts(now));
        var admin = TestAdmins.signIn(mvc, jdbc, clock);
        mvc.perform(admin.as(post("/admin/v1/credit-grants")
                        .header("Idempotency-Key", "grant-" + marker + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + me + "\",\"paise\":2500,\"reason\":\"pilot welcome credit\"}")))
                .andExpect(status().isCreated());

        // Money in (ADR 0039): billing details for a business, and a top-up whose payment failed, through the fake
        // Razorpay.
        mvc.perform(put("/v1/wallet/billing-details")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateCode\":\"29\",\"gstin\":\"29AAGCB7383J1Z4\",\"businessName\":\"Studio "
                                + marker + "\"}"))
                .andExpect(status().isOk());
        var razorpay = TestRazorpayConfiguration.RAZORPAY;
        var paidTopUp = JSON.readTree(mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", "topup-" + marker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":10000}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var paid = razorpay.pay(paidTopUp.get("orderId").asString(), "card");
        var captured = razorpay.event("payment.captured", paid.paymentId());
        mvc.perform(post("/v1/payments/razorpay/webhook")
                        .header("X-Razorpay-Signature", captured.signature())
                        .header("x-razorpay-event-id", captured.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captured.body()))
                .andExpect(status().isOk());
        // Some of it asked back: a money ticket opens, and the request waits for an admin.
        mvc.perform(post("/v1/wallet/refund-requests")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":2000,\"note\":\"I bought too much, " + marker + "\"}"))
                .andExpect(status().isCreated());
        // A chargeback on the payment, raised and won: the frozen credit came back.
        var disputeId = "disp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        for (var event : List.of("payment.dispute.created", "payment.dispute.won")) {
            var dispute = razorpay.disputeEvent(
                    event, disputeId, paid.paymentId(), 1_500, Instant.parse("2026-12-01T18:30:00Z"));
            mvc.perform(post("/v1/payments/razorpay/webhook")
                            .header("X-Razorpay-Signature", dispute.signature())
                            .header("x-razorpay-event-id", dispute.eventId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(dispute.body()))
                    .andExpect(status().isOk());
        }
        var failedTopUp = JSON.readTree(mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", user.bearer())
                        .header("Idempotency-Key", "topup-failed-" + marker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":6000}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var failure = razorpay.event(
                "payment.failed", razorpay.fail(failedTopUp.get("orderId").asString(), "upi"));
        mvc.perform(post("/v1/payments/razorpay/webhook")
                        .header("X-Razorpay-Signature", failure.signature())
                        .header("x-razorpay-event-id", failure.eventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failure.body()))
                .andExpect(status().isOk());

        // Trust: a rating with a comment, and a report answered.
        jdbc.update(
                "insert into trust.ended_sessions (session_id, renter_id, host_id, state, started_at, ended_at,"
                        + " recorded_at) values (?, ?, ?, 'COMPLETED', ?, ?, ?)",
                session,
                me,
                host,
                ts(now),
                ts(now),
                ts(now));
        jdbc.update(
                "insert into trust.ratings (session_id, renter_id, host_id, stars, comment, rated_at, changed_at)"
                        + " values (?, ?, ?, 4, ?, ?, ?)",
                session,
                me,
                host,
                "fast, thanks " + marker,
                ts(now),
                ts(now));
        jdbc.update(
                "insert into trust.disputes (id, session_id, renter_id, reason, details, state, created_at,"
                        + " resolution_reason, resolved_by, resolved_at)"
                        + " values (?, ?, ?, 'slow', ?, 'not_refunded', ?, 'it ran at full speed', 'admin', ?)",
                IDS.generate(),
                session,
                me,
                "slow for " + marker,
                ts(now),
                ts(now));

        // Trust, ADR 0038: a review of software no renter may run, settled, with a signal, an admin's note and a minute
        // of
        // what the job did.
        var review = IDS.generate();
        jdbc.update(
                "insert into trust.abuse_items (id, session_id, attempt, renter_id, host_id, state, action, banned,"
                        + " created_at, updated_at, resolved_at, resolved_by, resolution)"
                        + " values (?, ?, 1, ?, ?, 'cleared', 'stopped', false, ?, ?, ?, ?, ?)",
                review,
                session,
                me,
                host,
                ts(now),
                ts(now),
                ts(now),
                IDS.generate(),
                "a false alarm for " + marker);
        jdbc.update(
                "insert into trust.abuse_signals (id, item_id, kind, detail, sha256, count, stopped, at)"
                        + " values (?, ?, 'process_name', 'xmrig', '', 0, false, ?)",
                IDS.generate(),
                review,
                ts(now));
        jdbc.update(
                "insert into trust.abuse_notes (id, item_id, author_id, note, at) values (?, ?, ?, ?, ?)",
                IDS.generate(),
                review,
                IDS.generate(),
                "it was a test script for " + marker,
                ts(now));
        jdbc.update(
                "insert into trust.behavior_samples (session_id, attempt, minute_index, host_id, util_pct, memory_pct,"
                        + " pcie_kb_s, net_out, net_in, printed, conn_drops, at)"
                        + " values (?, 1, 0, ?, 97, 4, 120, 600, 900, 3, 0, ?)",
                session,
                host,
                ts(now));

        // Support: a message, signed in, about their session.
        mvc.perform(post("/v1/support/tickets")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"session\",\"message\":\"My session %s was slow\",\"replyTo\":\"%s\","
                                        .formatted(marker, email)
                                + "\"sessionId\":\"" + session + "\",\"elapsedMs\":5000}"))
                .andExpect(status().isAccepted());

        // Notifications: a message, a choice, and a bounce for their address.
        jdbc.update(
                "insert into notifications.notifications (id, user_id, kind, dedupe_key, title, body, link,"
                        + " created_at, in_app, email_wanted) values (?, ?, 'session-completed', ?, ?, ?, '/', ?, true,"
                        + " false)",
                IDS.generate(),
                me,
                "fixture:" + marker,
                "Done, " + marker,
                "Your session finished.",
                ts(now));
        jdbc.update(
                "insert into notifications.preferences (user_id, kind, email, in_app, updated_at)"
                        + " values (?, 'low-credit', false, true, ?)",
                me,
                ts(now));
        var addressHash = sha256(email.toLowerCase(Locale.ROOT));
        jdbc.update(
                "insert into notifications.email_suppressions (address_hash, reason, event_id, created_at)"
                        + " values (?, 'bounced', ?, ?) on conflict do nothing",
                addressHash,
                "event-" + marker,
                ts(now));

        return new Made(
                user,
                subject,
                marker,
                email,
                new PersonalDataMap.Person(
                        me, List.of(session), List.of(host), List.of(upload), List.of(install), addressHash));
    }

    private static String notices(Environment environment) {
        return "{\"termsVersion\":\"%s\",\"privacyVersion\":\"%s\",\"ageConfirmed\":true}"
                .formatted(
                        environment.getRequiredProperty("legal.notices.terms-version"),
                        environment.getRequiredProperty("legal.notices.privacy-version"));
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static byte[] random32() {
        var bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
