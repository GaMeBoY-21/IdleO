package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import com.idleo.controlplane.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A renter's billing details (ADR 0039): their state, which decides CGST and SGST or IGST on their invoices, and for a
 * business invoice a GSTIN (its shape, state and check character are checked) and the business's name. The state is
 * asked before the first top-up. The answer also says what GST is charged now, and lists the states.
 */
@RestController
@RequestMapping("/v1/wallet/billing-details")
class BillingDetails {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Gst gst;

    BillingDetails(JdbcClient jdbc, Clock clock, Gst gst) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.gst = gst;
    }

    record Details(
            String stateCode,
            @Nullable String gstin,
            @Nullable String businessName) {}

    record BillingDetailsRequest(
            @NotBlank @Size(min = 2, max = 2) String stateCode,
            @Nullable @Size(max = 15) String gstin,
            @Nullable @Size(max = 200) String businessName) {}

    record GstState(String code, String name) {}

    /**
     * {@code details} is null until they're given. {@code gstPercent} is what's added to renters' prices now (zero while
     * we aren't registered), and {@code pricesIncludeGst} whether the prices renters see have it in them.
     */
    record BillingDetailsResponse(
            @Nullable Details details, int gstPercent, boolean pricesIncludeGst, List<GstState> states) {}

    @GetMapping
    BillingDetailsResponse get(@AuthenticationPrincipal CurrentUser user) {
        return response(of(user.id()).orElse(null));
    }

    @PutMapping
    BillingDetailsResponse put(
            @AuthenticationPrincipal CurrentUser user, @Valid @RequestBody BillingDetailsRequest request) {
        var state = request.stateCode().strip();
        if (!GstStates.known(state)) {
            throw ApiProblem.invalid("stateCode", "isn't one of India's GST state codes.");
        }
        var gstin = blankToNull(request.gstin());
        if (gstin != null) {
            gstin = gstin.toUpperCase(Locale.ROOT);
            if (!Gstin.valid(gstin)) {
                throw ApiProblem.invalid("gstin", "isn't a valid GSTIN: check its 15 characters.");
            }
            if (!gstin.startsWith(state)) {
                throw ApiProblem.invalid("gstin", "is registered in another state than the one chosen.");
            }
        }
        var business = blankToNull(request.businessName());
        jdbc.sql("""
                        insert into billing.billing_details (user_id, state_code, gstin, business_name, updated_at)
                        values (:userId, :state, :gstin, :business, :now)
                        on conflict (user_id) do update
                        set state_code = excluded.state_code, gstin = excluded.gstin,
                            business_name = excluded.business_name, updated_at = excluded.updated_at
                        """)
                .param("userId", user.id())
                .param("state", state)
                .param("gstin", gstin)
                .param("business", business)
                .param("now", Timestamp.from(Instant.now(clock)))
                .update();
        return response(of(user.id()).orElseThrow());
    }

    /** Required before a top-up is ordered. */
    Details required(UUID userId) {
        return of(userId)
                .orElseThrow(() -> ApiProblem.of(
                        Problem.BILLING_DETAILS_REQUIRED, "Say which state you're in before your first top-up."));
    }

    Optional<Details> of(UUID userId) {
        return jdbc.sql("select state_code, gstin, business_name from billing.billing_details where user_id = :userId")
                .param("userId", userId)
                .query((rs, n) ->
                        new Details(rs.getString("state_code"), rs.getString("gstin"), rs.getString("business_name")))
                .optional();
    }

    private BillingDetailsResponse response(@Nullable Details details) {
        return new BillingDetailsResponse(
                details,
                gst.gstPercent(),
                gst.settings().pricesIncludeGst(),
                GstStates.NAMES.entrySet().stream()
                        .map(state -> new GstState(state.getKey(), state.getValue()))
                        .toList());
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
