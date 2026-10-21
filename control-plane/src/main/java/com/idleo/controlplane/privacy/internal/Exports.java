package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.SecurityLog;
import com.idleo.controlplane.notifications.AccountEmails;
import com.idleo.controlplane.sessions.PersonalArchives;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Download my data" (ADR 0037): one export at a time and one a day. A job asks every module for its part ({@link
 * PersonalData#export}), writes a JSON file for each group and a note on what's in them into a zip, stores it, and
 * tells the person by email and in the app. The zip is fetched only through a short link given to its owner, signed in,
 * and deleted after {@code privacy.export-keep} (the exports retention rule).
 */
@Service
@EnableConfigurationProperties(PrivacyProperties.class)
class Exports {

    private static final Logger log = LoggerFactory.getLogger(Exports.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(INDIA);
    static final Duration DOWNLOAD_LINK = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdGenerator ids;
    private final Clock clock;
    private final PrivacyProperties properties;
    private final List<PersonalData> modules;
    private final PersonalArchives archives;
    private final AccountEmails emails;
    private final SecurityLog security;
    private final JsonMapper json;

    Exports(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            IdGenerator ids,
            Clock clock,
            PrivacyProperties properties,
            List<PersonalData> modules,
            PersonalArchives archives,
            AccountEmails emails,
            SecurityLog security,
            JsonMapper json) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
        this.modules = List.copyOf(modules);
        this.archives = archives;
        this.emails = emails;
        this.security = security;
        this.json = json;
    }

    /** One export as its owner sees it. */
    record Export(
            UUID id,
            String state,
            Instant requestedAt,
            @Nullable Instant readyAt,
            @Nullable Instant expiresAt,
            @Nullable Long sizeBytes) {}

    private Instant now() {
        return Instant.now(clock);
    }

    /** Asks for an export: 409 {@code export-in-progress} while one is being made, 429 within a day of the last. */
    Export request(UUID userId) {
        return transactions.execute(status -> {
            jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .param("key", "export:" + userId)
                    .query((rs, n) -> 1)
                    .single();
            var exports = list(userId);
            if (exports.stream().anyMatch(export -> export.state().equals("requested"))) {
                throw ApiProblem.of(Problem.EXPORT_IN_PROGRESS, "Your last export isn't ready yet.");
            }
            var now = now();
            var last = exports.stream()
                    .filter(export -> !export.state().equals("failed"))
                    .map(Export::requestedAt)
                    .max(Instant::compareTo);
            if (last.isPresent() && last.get().plus(properties.exportEvery()).isAfter(now)) {
                throw ApiProblem.of(Problem.EXPORT_TOO_SOON, "An export can be asked for once a day.")
                        .retryAfter(Duration.between(now, last.get().plus(properties.exportEvery())));
            }
            var id = ids.newId();
            jdbc.sql(
                            "insert into privacy.exports (id, user_id, state, requested_at) values (:id, :user, 'requested', :now)")
                    .param("id", id)
                    .param("user", userId)
                    .param("now", Timestamp.from(now))
                    .update();
            security.record(SecurityLog.Kind.EXPORT_REQUESTED, userId);
            return new Export(id, "requested", now, null, null, null);
        });
    }

    /** The person's exports, newest first: the last few, ready ones while they're kept. */
    List<Export> list(UUID userId) {
        return jdbc.sql("""
                        select id, state, requested_at, ready_at, expires_at, size_bytes from privacy.exports
                        where user_id = :user order by requested_at desc limit 10
                        """)
                .param("user", userId)
                .query((rs, n) -> new Export(
                        rs.getObject("id", UUID.class),
                        rs.getString("state"),
                        rs.getTimestamp("requested_at").toInstant(),
                        instant(rs.getTimestamp("ready_at")),
                        instant(rs.getTimestamp("expires_at")),
                        rs.getObject("size_bytes", Long.class)))
                .list();
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** A short link to an export, and when it stops working. */
    record Download(URL url, Instant expiresAt) {}

    /** A short link to the person's own ready export; 404 for anyone else's, or one gone. */
    Download download(UUID userId, UUID exportId) {
        var export = list(userId).stream()
                .filter(candidate -> candidate.id().equals(exportId))
                .filter(candidate -> candidate.state().equals("ready"))
                .filter(candidate ->
                        candidate.expiresAt() != null && candidate.expiresAt().isAfter(now()))
                .findFirst()
                .orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such export, or it has been deleted."));
        return new Download(
                archives.link(exportId, fileName(export.requestedAt()), DOWNLOAD_LINK), now().plus(DOWNLOAD_LINK));
    }

    static String fileName(Instant requestedAt) {
        return "idleo-data-" + DateTimeFormatter.ISO_LOCAL_DATE.withZone(INDIA).format(requestedAt) + ".zip";
    }

    @Scheduled(fixedDelayString = "${privacy.export-sweep-every}", initialDelayString = "PT20S")
    void sweep() {
        try {
            while (makeNext()) {
                // one at a time, until none is waiting
            }
        } catch (RuntimeException e) {
            log.warn("The export job failed; it runs again shortly", e);
        }
    }

    /** Makes the oldest waiting export, if any: whether there was one. */
    boolean makeNext() {
        var next = transactions.execute(status -> jdbc.sql("""
                        update privacy.exports set attempts = attempts + 1
                        where id = (select id from privacy.exports where state = 'requested'
                                    order by requested_at for update skip locked limit 1)
                        returning id, user_id, attempts, requested_at
                        """)
                .query((rs, n) -> new Waiting(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getInt("attempts"),
                        rs.getTimestamp("requested_at").toInstant()))
                .optional()
                .orElse(null));
        if (next == null) {
            return false;
        }
        try {
            make(next);
        } catch (RuntimeException e) {
            log.warn("Export {} failed on try {}", next.id(), next.attempts(), e);
            if (next.attempts() >= MAX_ATTEMPTS) {
                jdbc.sql("update privacy.exports set state = 'failed' where id = :id and state = 'requested'")
                        .param("id", next.id())
                        .update();
            }
            return false;
        }
        return true;
    }

    private record Waiting(UUID id, UUID userId, int attempts, Instant requestedAt) {}

    private void make(Waiting export) {
        Path zip = null;
        try {
            zip = Files.createTempFile("idleo-export-", ".zip");
            write(export.userId(), zip);
            archives.store(export.id(), zip);
            var size = Files.size(zip);
            var now = now();
            var expiresAt = now.plus(properties.exportKeep());
            int marked = jdbc.sql("""
                            update privacy.exports set state = 'ready', ready_at = :now, expires_at = :expires,
                                                       size_bytes = :size
                            where id = :id and state = 'requested'
                            """)
                    .param("now", Timestamp.from(now))
                    .param("expires", Timestamp.from(expiresAt))
                    .param("size", size)
                    .param("id", export.id())
                    .update();
            if (marked == 1) {
                emails.tell(
                        export.userId(),
                        "export-ready:" + export.id(),
                        "Your data is ready to download",
                        "Everything we hold about you is in one file, in Account. It's kept until "
                                + DAY.format(expiresAt) + ", and so are the links to your files inside it.",
                        "/account/data/");
                log.info("Export {} is ready ({} bytes)", export.id(), size);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (zip != null) {
                try {
                    Files.deleteIfExists(zip);
                } catch (IOException ignored) {
                    // A temporary file; the system clears its folder.
                }
            }
        }
    }

    /** The zip: a JSON file for each group, every group present even when empty, and a note on what they hold. */
    void write(UUID userId, Path zip) throws IOException {
        var groups = new LinkedHashMap<String, Map<String, Object>>();
        PersonalData.GROUPS.forEach(group -> groups.put(group, new LinkedHashMap<>()));
        for (var module : modules) {
            for (var part : module.export(userId)) {
                var group = groups.get(part.group());
                if (group == null) {
                    throw new IllegalStateException(module.module() + " exports to an unknown group " + part.group());
                }
                group.put(part.key(), part.records());
            }
        }
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("README.txt"));
            out.write(readme(userId).getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            for (var group : groups.entrySet()) {
                out.putNextEntry(new ZipEntry(group.getKey() + ".json"));
                out.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(group.getValue()));
                out.closeEntry();
            }
        }
    }

    private String readme(UUID userId) {
        return """
                Everything IdleO holds about you, as of %s (UTC).

                account.json   your account, how you sign in, your API keys, the notices you agreed to, and your
                               security log (each sign-in and change to your access, with the address it came from)
                sessions.json  every session you booked: what it ran, its parameters, inputs and timeline
                files.json     your uploads and what your sessions left in storage, each with a link
                money.json     your credit: every line of your wallet, in paise and rupees, with its purpose
                hosting.json   your machines, their GPUs and prices, and every line of their earnings
                messages.json  everything we told you, in the app and by email, and what you chose to be told
                reports.json   the reports you made and the ratings you gave

                Money is in paise (an integer: 100 paise is one rupee) with the rupees beside it. Times are UTC.
                The links in files.json work for 7 days from when this was made. Your account's id: %s
                """.formatted(now(), userId);
    }
}
