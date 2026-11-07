package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.AdminPermission;
import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.RequiresPermission;
import com.idleo.controlplane.identity.Approvals;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CA's files for the console (ADR 0055): which there are, the packs made, and asking for one file or the month's
 * pack, which another admin approves and the asker then downloads from Approvals.
 */
@RestController
@RequestMapping("/admin/v1/tax")
@ConditionalOnWebApplication
class AdminTaxController {

    private final CaPacks packs;
    private final Approvals approvals;

    AdminTaxController(CaPacks packs, Approvals approvals) {
        this.packs = packs;
        this.approvals = approvals;
    }

    /** One of the CA's files: its name, what it is, and whether the month's pack holds it. */
    record AdminTaxExport(String name, String description, String contentType, boolean inPack) {}

    /** A pack made: its month, how many files, its manifest's SHA-256, who asked and when. */
    record AdminCaPack(String month, int files, String manifestSha256, String actor, Instant at) {}

    record AdminTaxExports(List<AdminTaxExport> exports, List<AdminCaPack> packs) {}

    /** Asking for a file ({@code export}, or {@code ca-pack} for the month's pack) for a month (YYYY-MM), with why. */
    record AdminTaxExportRequest(
            @NotBlank String export,
            @NotBlank String month,
            @NotBlank @Size(min = 5, max = 500) String reason) {}

    @RequiresPermission(AdminPermission.TAX_EXPORT)
    @GetMapping("/exports")
    AdminTaxExports exports() {
        return new AdminTaxExports(
                packs.exports().stream()
                        .map(export -> new AdminTaxExport(
                                export.name(), export.description(), export.contentType(), export.inPack()))
                        .toList(),
                packs.latest(12).stream()
                        .map(made -> new AdminCaPack(
                                made.month(), made.files(), made.manifestSha256(), made.actor(), made.at()))
                        .toList());
    }

    /** Asks for a file or the month's pack: another admin approves, then the asker downloads it once from Approvals. */
    @RequiresPermission(AdminPermission.TAX_EXPORT)
    @PostMapping("/exports")
    ResponseEntity<Approvals.AdminApproval> ask(
            @AuthenticationPrincipal CurrentUser admin, @Valid @RequestBody AdminTaxExportRequest request) {
        YearMonth month;
        try {
            month = YearMonth.parse(request.month().strip());
        } catch (DateTimeParseException e) {
            throw ApiProblem.invalid("month", "is a month such as 2026-10.");
        }
        var name = request.export().strip();
        String what;
        if (name.equals(CaPacks.PACK)) {
            what = "the CA pack of " + month;
        } else {
            var export =
                    packs.export(name).orElseThrow(() -> ApiProblem.invalid("export", "isn't one of the CA's files."));
            what = export.fileName(month);
        }
        return approvals.submit(
                admin,
                BillingGatedActions.TAX_EXPORT,
                null,
                null,
                Map.of("export", name, "month", month.toString()),
                List.of(new Approvals.ApprovalChange("a file for the CA: " + what, null, "made once")),
                request.reason());
    }
}
