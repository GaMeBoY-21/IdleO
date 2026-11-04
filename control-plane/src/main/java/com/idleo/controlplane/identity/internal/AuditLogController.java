package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminPages;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.NoContactDetails;
import com.idleo.controlplane.PersonalDataMask;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit log, newest first, for the admin console (ADR 0027, ADR 0051): searched, checked and exported. Nothing can
 * change a row.
 */
@RestController
@RequestMapping("/admin/v1/audit")
@ConditionalOnWebApplication
class AuditLogController {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** The most one export holds; a wider one is refused, so a date range narrows it. */
    static final int EXPORT_LIMIT = 50_000;

    private static final String SELECT = """
            select a.*, coalesce(u.email, a.actor_name) as shown_name, host(a.ip) as address
            from identity.audit_log a
            left join identity.users u on u.id = a.actor_user_id
            where (cast(:actor as text) is null
                   or a.actor_name ilike '%' || cast(:actor as text) || '%'
                   or u.email ilike '%' || cast(:actor as text) || '%'
                   or a.actor_user_id::text = cast(:actor as text))
              and (cast(:action as text) is null or a.action = cast(:action as text)
                   or (right(cast(:action as text), 1) = '.'
                       and starts_with(a.action, cast(:action as text))))
              and (cast(:targetType as text) is null or a.target_type = cast(:targetType as text))
              and (cast(:targetId as text) is null or a.target_id = cast(:targetId as text))
              and (cast(:approvalId as uuid) is null or a.approval_id = cast(:approvalId as uuid))
              and (cast(:requestId as text) is null or a.request_id = cast(:requestId as text))
              and (cast(:from as timestamptz) is null or a.created_at >= cast(:from as timestamptz))
              and (cast(:to as timestamptz) is null or a.created_at < cast(:to as timestamptz))
              and (cast(:after as uuid) is null or a.id < cast(:after as uuid))
            order by a.id desc
            limit :limit
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditChain chain;
    private final AuditOffsiteCopies copies;
    private final AuditTrail audit;
    private final TransactionTemplate transactions;

    AuditLogController(
            JdbcClient jdbc,
            JsonMapper json,
            AuditChain chain,
            AuditOffsiteCopies copies,
            AuditTrail audit,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.chain = chain;
        this.copies = copies;
        this.audit = audit;
        this.transactions = transactions;
    }

    /**
     * One action: who did it ({@code actorKind} {@code admin}, {@code operator}, {@code owner} or {@code system}), what
     * ({@code host.activate}), to what, why, and what changed. The log keeps an admin or an owner by id only (ADR
     * 0037); {@code actorName} is their email as the account has it now, or the id once the account is erased. Where it
     * came from: the request, the admin session, the address and browser, and the approval it ran under, if any.
     *
     * @param seq its place in the hash chain
     */
    record AuditEntry(
            UUID id,
            long seq,
            String actorKind,
            @Nullable UUID actorUserId,
            String actorName,
            String action,
            String targetType,
            String targetId,
            String reason,
            @Nullable Map<String, Object> before,
            @Nullable Map<String, Object> after,
            Instant createdAt,
            @Nullable String requestId,
            @Nullable UUID adminSessionId,
            @Nullable String ip,
            @Nullable String userAgent,
            @Nullable UUID approvalId) {}

    record AuditPage(List<AuditEntry> entries, @Nullable String nextCursor) {}

    /** One check of the chain: how many rows and how long; when broken, the first row that doesn't add up, and how. */
    record AuditChainCheck(
            long rows,
            long headSeq,
            boolean intact,
            @Nullable Long brokenSeq,
            @Nullable String detail,
            long tookMs,
            String trigger,
            Instant checkedAt) {

        static AuditChainCheck of(AuditChain.Check check) {
            return new AuditChainCheck(
                    check.rows(),
                    check.headSeq(),
                    check.intact(),
                    check.brokenSeq(),
                    check.detail(),
                    check.tookMs(),
                    check.trigger(),
                    check.checkedAt());
        }
    }

    /** One day's off-site copy. */
    record AuditCopy(LocalDate day, long rows, long lastSeq, String headHash, String objectKey, Instant writtenAt) {}

    /** How the log stands: its latest check, and its latest off-site copies, newest first. */
    record AuditStanding(@Nullable AuditChainCheck latestCheck, List<AuditCopy> copies) {}

    /** What to export, as the search filters it, and why (5 to 500 characters). */
    record AuditExport(
            @Nullable String actor,
            @Nullable String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable UUID approvalId,
            @Nullable String requestId,
            @Nullable Instant from,
            @Nullable Instant to,

            @NotBlank @Size(min = 5, max = 500) @NoContactDetails
            String reason) {}

    /**
     * The log, filtered: {@code actor} is part of the actor's name or their user id, {@code action} an action or the
     * start of one ({@code host.} for every host action), {@code targetType}, {@code targetId}, {@code approvalId} and
     * {@code requestId} exact, and {@code from} and {@code to} a time range.
     */
    @RequiresPermission(AdminPermission.AUDIT_READ)
    @GetMapping
    AuditPage list(
            @RequestParam(required = false) @Nullable String actor,
            @RequestParam(required = false) @Nullable String action,
            @RequestParam(required = false) @Nullable String targetType,
            @RequestParam(required = false) @Nullable String targetId,
            @RequestParam(required = false) @Nullable UUID approvalId,
            @RequestParam(required = false) @Nullable String requestId,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int size = AdminPages.limit(limit);
        var entries = search(
                new Filter(actor, action, targetType, targetId, approvalId, requestId, from, to),
                AdminPages.after(cursor),
                size + 1);
        var page = entries.size() > size ? entries.subList(0, size) : entries;
        return new AuditPage(
                List.copyOf(page),
                entries.size() > size ? AdminPages.cursorAfter(page.getLast().id()) : null);
    }

    @RequiresPermission(AdminPermission.AUDIT_READ)
    @GetMapping("/standing")
    AuditStanding standing() {
        return new AuditStanding(
                chain.newest().map(AuditChainCheck::of).orElse(null),
                copies.latest(14).stream()
                        .map(copy -> new AuditCopy(
                                copy.day(),
                                copy.rows(),
                                copy.lastSeq(),
                                copy.headHash(),
                                copy.objectKey(),
                                copy.writtenAt()))
                        .toList());
    }

    /** Walks the whole chain now. A break also pages. */
    @RequiresPermission(AdminPermission.AUDIT_READ)
    @PostMapping("/verify")
    AuditChainCheck verify() {
        return AuditChainCheck.of(chain.verify("admin"));
    }

    /**
     * The search's rows as CSV, up to {@value #EXPORT_LIMIT}, with addresses masked as on screen. Logged. 409 {@code
     * action-refused} for more than that: narrow the dates.
     */
    @RequiresPermission(value = AdminPermission.EXPORT_CREATE, shownOnce = true)
    @PostMapping(value = "/export", produces = "text/csv")
    @ApiResponse(
            responseCode = "200",
            description = "The rows as CSV",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AuditExport request) {
        var rows = search(
                new Filter(
                        request.actor(),
                        request.action(),
                        request.targetType(),
                        request.targetId(),
                        request.approvalId(),
                        request.requestId(),
                        request.from(),
                        request.to()),
                null,
                EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) {
            throw ApiProblem.of(
                    Problem.ACTION_REFUSED, "That's more than " + EXPORT_LIMIT + " rows; narrow the dates.");
        }
        var csv = new StringBuilder(
                "seq,id,created_at,actor_kind,actor_user_id,action,target_type,target_id,reason,request_id,"
                        + "admin_session_id,ip,approval_id\n");
        for (var row : rows) {
            csv.append(row.seq()).append(',');
            csv.append(row.id()).append(',');
            csv.append(row.createdAt()).append(',');
            csv.append(row.actorKind()).append(',');
            csv.append(row.actorUserId() == null ? "" : row.actorUserId()).append(',');
            csv.append(cell(row.action())).append(',');
            csv.append(cell(row.targetType())).append(',');
            csv.append(cell(row.targetId())).append(',');
            csv.append(cell(row.reason())).append(',');
            csv.append(cell(row.requestId())).append(',');
            csv.append(row.adminSessionId() == null ? "" : row.adminSessionId()).append(',');
            csv.append(cell(row.ip() == null ? null : PersonalDataMask.ip(row.ip())))
                    .append(',');
            csv.append(row.approvalId() == null ? "" : row.approvalId()).append('\n');
        }
        transactions.executeWithoutResult(status -> audit.record(new AuditTrail.Entry(
                audit.admin(admin),
                "audit.export",
                "audit_log",
                "search",
                request.reason(),
                null,
                Map.of("rows", rows.size()))));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"idleo-audit.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A CSV cell: quoted, and never read as a formula by a spreadsheet. */
    private static String cell(@Nullable String value) {
        if (value == null) {
            return "";
        }
        var safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private record Filter(
            @Nullable String actor,
            @Nullable String action,
            @Nullable String targetType,
            @Nullable String targetId,
            @Nullable UUID approvalId,
            @Nullable String requestId,
            @Nullable Instant from,
            @Nullable Instant to) {}

    private List<AuditEntry> search(Filter filter, @Nullable UUID after, int limit) {
        return jdbc.sql(SELECT)
                .param("actor", blankToNull(filter.actor()))
                .param("action", blankToNull(filter.action()))
                .param("targetType", blankToNull(filter.targetType()))
                .param("targetId", blankToNull(filter.targetId()))
                .param("approvalId", filter.approvalId())
                .param("requestId", blankToNull(filter.requestId()))
                .param("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                .param("to", filter.to() == null ? null : Timestamp.from(filter.to()))
                .param("after", after)
                .param("limit", limit)
                .query(this::entry)
                .list();
    }

    private AuditEntry entry(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(
                rs.getObject("id", UUID.class),
                rs.getLong("seq"),
                rs.getString("actor_kind"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("shown_name"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("reason"),
                object(rs.getString("before")),
                object(rs.getString("after")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("request_id"),
                rs.getObject("admin_session_id", UUID.class),
                rs.getString("address"),
                rs.getString("user_agent"),
                rs.getObject("approval_id", UUID.class));
    }

    private @Nullable Map<String, Object> object(@Nullable String text) {
        return text == null ? null : json.readValue(text, OBJECT);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
