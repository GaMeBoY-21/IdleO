package com.idleo.controlplane.images.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * What checks found, cached by digest (ADR 0042): {@code images.scans}, and every ELF file's SHA-256 in {@code
 * images.scan_executables}, so a newer blocklist judges a cached image again without downloading it.
 */
@Component
class ScanStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A secret Trivy saw: where, and what kind. Never the secret. */
    record Secret(String path, String rule, String category, String title) {}

    /** One vulnerability, for the full list. */
    record Vulnerability(
            String severity, String id, String packageName, String installed, String fixed, String title) {}

    /** What one digest's check found. */
    record Scan(
            String digest,
            long compressedBytes,
            long unpackedBytes,
            int layers,
            @Nullable String cudaRequire,
            @Nullable String cudaVersion,
            @Nullable String cudaNeeded,
            List<String> entrypoint,
            List<String> cmd,
            @Nullable String workingDir,
            @Nullable String user,
            boolean hasShell,
            int critical,
            int high,
            int medium,
            int low,
            List<Secret> secrets,
            String trivyVersion,
            Instant checkedAt,
            String blocklistVersion,
            @Nullable String blockedReason,
            @Nullable String blockedDetail,
            @Nullable Instant blockedAt,
            @Nullable String blockedPath) {

        boolean blocked() {
            return blockedReason != null;
        }

        /** Judged by a blocklist, and not blocked: what may run. */
        boolean judged() {
            return !blocklistVersion.isEmpty();
        }
    }

    /** One ELF file of a scanned image. */
    record Executable(String sha256, String path, int layer) {}

    private final JdbcClient jdbc;

    ScanStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Scan scan(ResultSet rs, int n) throws SQLException {
        return new Scan(
                rs.getString("digest"),
                rs.getLong("compressed_bytes"),
                rs.getLong("unpacked_bytes"),
                rs.getInt("layers"),
                rs.getString("cuda_require"),
                rs.getString("cuda_version"),
                rs.getString("cuda_needed"),
                JSON.readValue(rs.getString("entrypoint"), new TypeReference<List<String>>() {}),
                JSON.readValue(rs.getString("cmd"), new TypeReference<List<String>>() {}),
                rs.getString("working_dir"),
                rs.getString("image_user"),
                rs.getBoolean("has_shell"),
                rs.getInt("critical"),
                rs.getInt("high"),
                rs.getInt("medium"),
                rs.getInt("low"),
                JSON.readValue(rs.getString("secrets"), new TypeReference<List<Secret>>() {}),
                rs.getString("trivy_version"),
                rs.getTimestamp("checked_at").toInstant(),
                rs.getString("blocklist_version"),
                rs.getString("blocked_reason"),
                rs.getString("blocked_detail"),
                ImageStore.instant(rs, "blocked_at"),
                rs.getString("blocked_path"));
    }

    private static final String COLUMNS = """
            digest, compressed_bytes, unpacked_bytes, layers, cuda_require, cuda_version, cuda_needed, entrypoint, cmd,
            working_dir, image_user, has_shell, critical, high, medium, low, secrets, trivy_version, checked_at,
            blocklist_version, blocked_reason, blocked_detail, blocked_at, blocked_path""";

    Optional<Scan> find(String digest) {
        return jdbc.sql("select " + COLUMNS + " from images.scans where digest = :digest")
                .param("digest", digest)
                .query(ScanStore::scan)
                .optional();
    }

    /** Keeps a scan and its files; false if one for the digest is there already (another check got there first). */
    boolean insert(Scan scan, List<Vulnerability> vulnerabilities, List<Executable> executables) {
        int inserted = jdbc.sql("""
                        insert into images.scans
                            (digest, compressed_bytes, unpacked_bytes, layers, cuda_require, cuda_version, cuda_needed,
                             entrypoint, cmd, working_dir, image_user, has_shell, critical, high, medium, low,
                             vulnerabilities, secrets, trivy_version, checked_at)
                        values (:digest, :compressed, :unpacked, :layers, :cudaRequire, :cudaVersion, :cudaNeeded,
                                cast(:entrypoint as jsonb), cast(:cmd as jsonb), :workingDir, :user, :hasShell,
                                :critical, :high, :medium, :low, cast(:vulnerabilities as jsonb), cast(:secrets as jsonb),
                                :trivy, :checkedAt)
                        on conflict (digest) do nothing
                        """)
                .param("digest", scan.digest())
                .param("compressed", scan.compressedBytes())
                .param("unpacked", scan.unpackedBytes())
                .param("layers", scan.layers())
                .param("cudaRequire", scan.cudaRequire())
                .param("cudaVersion", scan.cudaVersion())
                .param("cudaNeeded", scan.cudaNeeded())
                .param("entrypoint", JSON.writeValueAsString(scan.entrypoint()))
                .param("cmd", JSON.writeValueAsString(scan.cmd()))
                .param("workingDir", scan.workingDir())
                .param("user", scan.user())
                .param("hasShell", scan.hasShell())
                .param("critical", scan.critical())
                .param("high", scan.high())
                .param("medium", scan.medium())
                .param("low", scan.low())
                .param("vulnerabilities", JSON.writeValueAsString(vulnerabilities))
                .param("secrets", JSON.writeValueAsString(scan.secrets()))
                .param("trivy", scan.trivyVersion())
                .param("checkedAt", Timestamp.from(scan.checkedAt()))
                .update();
        if (inserted == 0) {
            return false;
        }
        for (var executable : executables) {
            jdbc.sql("insert into images.scan_executables (digest, sha256, path, layer) values (:d, :s, :p, :l)")
                    .param("d", scan.digest())
                    .param("s", executable.sha256())
                    .param("p", executable.path())
                    .param("l", executable.layer())
                    .update();
        }
        return true;
    }

    /** The full list of a digest's vulnerabilities, most severe first. */
    List<Vulnerability> vulnerabilities(String digest) {
        return jdbc.sql("select vulnerabilities from images.scans where digest = :digest")
                .param("digest", digest)
                .query((rs, n) -> JSON.readValue(rs.getString(1), new TypeReference<List<Vulnerability>>() {}))
                .optional()
                .orElse(List.of());
    }

    /** The first of a digest's files whose SHA-256 is in {@code hashes}, if any. */
    Optional<Executable> listed(String digest, Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        select sha256, path, layer from images.scan_executables
                        where digest = :digest and sha256 in (:hashes)
                        order by layer, path
                        limit 1
                        """)
                .param("digest", digest)
                .param("hashes", hashes)
                .query((rs, n) -> new Executable(rs.getString("sha256"), rs.getString("path"), rs.getInt("layer")))
                .optional();
    }

    /** Scans not blocked and not judged by {@code version}, locked: what a newer blocklist judges again. */
    List<String> toJudge(String version, int limit) {
        return jdbc.sql("""
                        select digest from images.scans
                        where blocked_reason is null and blocklist_version <> :version
                        order by checked_at
                        limit :limit
                        for update skip locked
                        """)
                .param("version", version)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    void judged(String digest, String version) {
        jdbc.sql("update images.scans set blocklist_version = :version where digest = :digest")
                .param("version", version)
                .param("digest", digest)
                .update();
    }

    void block(String digest, String reason, String detail, @Nullable String path, Instant now) {
        jdbc.sql("""
                        update images.scans
                        set blocked_reason = :reason, blocked_detail = :detail, blocked_path = :path, blocked_at = :now
                        where digest = :digest
                        """)
                .param("reason", reason)
                .param("detail", detail)
                .param("path", path)
                .param("now", Timestamp.from(now))
                .param("digest", digest)
                .update();
    }

    void unblock(String digest) {
        jdbc.sql("""
                        update images.scans
                        set blocked_reason = null, blocked_detail = null, blocked_path = null, blocked_at = null
                        where digest = :digest
                        """).param("digest", digest).update();
    }

    /** The digests among {@code digests} with a scan. */
    Set<String> present(Collection<String> digests) {
        if (digests.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select digest from images.scans where digest in (:d)")
                .param("d", digests)
                .query(String.class)
                .list());
    }
}
