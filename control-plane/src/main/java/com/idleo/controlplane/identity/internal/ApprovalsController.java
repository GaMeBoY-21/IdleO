package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.AdminList;
import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.AdminReason;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The approvals queue (ADR 0051): what's waiting, what this admin asked for, what's decided; approve, reject, cancel. */
@RestController
@RequestMapping("/admin/v1/approvals")
@ConditionalOnWebApplication
class ApprovalsController {

    private final ApprovalEngine approvals;

    ApprovalsController(ApprovalEngine approvals) {
        this.approvals = approvals;
    }

    record ApprovalPage(
            List<Approvals.AdminApproval> requests,
            @Nullable String nextCursor) {}

    record ApprovalCount(long pending) {}

    /** {@code which}: pending (the default), mine or decided. */
    @RequiresPermission(AdminPermission.APPROVALS_READ)
    @AdminList("approvals")
    @GetMapping
    ApprovalPage list(
            @AuthenticationPrincipal CurrentUser admin,
            @RequestParam(defaultValue = "pending") String which,
            @RequestParam(required = false) @Nullable String sort,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        if (!List.of("pending", "mine", "decided").contains(which)) {
            throw ApiProblem.invalid("which", "must be pending, mine or decided.");
        }
        var page = approvals.list(which, admin.id(), sort, cursor, limit);
        return new ApprovalPage(page.rows(), page.nextCursor());
    }

    /** How many wait, for the sidebar. */
    @RequiresPermission(AdminPermission.APPROVALS_READ)
    @GetMapping("/count")
    ApprovalCount count() {
        return new ApprovalCount(approvals.pending());
    }

    @RequiresPermission(AdminPermission.APPROVALS_READ)
    @GetMapping("/{id}")
    Approvals.AdminApproval get(@PathVariable UUID id) {
        return approvals.find(id).orElseThrow(() -> ApiProblem.of(Problem.NOT_FOUND, "There's no such request."));
    }

    /**
     * Approves it, and it runs now, as it was asked. 409 {@code action-refused} for your own request, one already decided,
     * or one whose target has changed, which is then closed as failed.
     */
    @RequiresPermission(value = AdminPermission.APPROVALS_DECIDE, record = "approval")
    @PostMapping("/{id}/approve")
    Approvals.AdminApproval approve(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return approvals.approve(admin, id, request.reason());
    }

    @RequiresPermission(value = AdminPermission.APPROVALS_DECIDE, record = "approval")
    @PostMapping("/{id}/reject")
    Approvals.AdminApproval reject(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return approvals.reject(admin, id, request.reason());
    }

    /** Cancels a request still waiting: its requester's, an Owner's, or anyone's who may decide it. */
    @RequiresPermission(value = AdminPermission.APPROVALS_DECIDE, record = "approval")
    @PostMapping("/{id}/cancel")
    Approvals.AdminApproval cancel(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Valid @RequestBody AdminReason request) {
        return approvals.cancel(admin, id, request.reason());
    }

    /** The file an approved export makes, once, for whoever asked for it, within a day. It's written as it's sent. */
    @RequiresPermission(value = AdminPermission.APPROVALS_DECIDE, shownOnce = true)
    @PostMapping(value = "/{id}/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ApiResponse(
            responseCode = "200",
            description = "The file",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string")))
    void download(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable UUID id,
            @Parameter(hidden = true) HttpServletResponse response)
            throws IOException {
        var file = approvals.download(admin, id);
        response.setContentType(file.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.name() + "\"");
        file.writer().writeTo(response.getOutputStream());
    }
}
