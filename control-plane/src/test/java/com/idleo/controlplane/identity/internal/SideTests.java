package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.identity.AuditTrail;
import com.idleo.controlplane.identity.RequiresAcceptedTerms;
import com.idleo.controlplane.identity.RequiresUnlocked;
import com.idleo.controlplane.identity.Side;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * One account, two sides (ADR 0058): an invite code unlocks renting, hosting or both, a second code unlocks the other
 * side, and each side's calls refuse an account that hasn't unlocked it.
 */
@ControlPlaneTest
@TestPropertySource(properties = "identity.invite-only=true")
@DirtiesContext
class SideTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InviteService invites;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping routes;

    private String code(InviteService.Unlocks unlocks) {
        return invites.create(1, "test", Optional.empty(), unlocks, AuditTrail.Actor.operator("tester"))
                .codes()
                .getFirst();
    }

    private MockHttpServletRequestBuilder activate(String bearer, String code) {
        return post("/v1/me/activate")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
    }

    /** A call on the hosting side: adding a laptop. Refused or let through before its body is read. */
    private MockHttpServletRequestBuilder addALaptop(String bearer) {
        return post("/v1/hosts")
                .header("Authorization", bearer)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    /** A call on the renting side: booking. */
    private MockHttpServletRequestBuilder book(String bearer) {
        return post("/v1/sessions")
                .header("Authorization", bearer)
                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    @Test
    void aRentingCodeUnlocksRentingOnlyAndAHostingCodeThenUnlocksTheRest() throws Exception {
        var renter = TestUsers.signUp(mvc).bearer();
        mvc.perform(activate(renter, code(InviteService.Unlocks.RENTING)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.unlocked").value(org.hamcrest.Matchers.contains("renting")));

        mvc.perform(addALaptop(renter))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/side-not-unlocked"))
                .andExpect(jsonPath("$.detail").value(AccessRules.notUnlocked(Side.HOSTING)));
        mvc.perform(book(renter)).andExpect(status().is(org.hamcrest.Matchers.not(403)));

        mvc.perform(activate(renter, code(InviteService.Unlocks.HOSTING)))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.unlocked").value(org.hamcrest.Matchers.containsInAnyOrder("renting", "hosting")));
        mvc.perform(addALaptop(renter)).andExpect(status().is(org.hamcrest.Matchers.not(403)));
    }

    @Test
    void aHostingCodeLeavesRentingLocked() throws Exception {
        var host = TestUsers.signUp(mvc).bearer();
        mvc.perform(activate(host, code(InviteService.Unlocks.HOSTING))).andExpect(status().isOk());

        mvc.perform(book(host))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/side-not-unlocked"))
                .andExpect(jsonPath("$.detail").value(AccessRules.notUnlocked(Side.RENTING)));
        mvc.perform(get("/v1/me").header("Authorization", host))
                .andExpect(jsonPath("$.unlocked").value(org.hamcrest.Matchers.contains("hosting")));
    }

    @Test
    void aCodeForBothWritesNoRowsAsEveryAccountBeforeKindsHad() throws Exception {
        var both = TestUsers.signUp(mvc);
        mvc.perform(activate(both.bearer(), code(InviteService.Unlocks.BOTH)))
                .andExpect(
                        jsonPath("$.unlocked").value(org.hamcrest.Matchers.containsInAnyOrder("renting", "hosting")));
        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.user_sides where user_id = ?", Integer.class, both.id()))
                .isZero();
    }

    @Test
    void aCodeThatWouldUnlockNothingNewIsLeftUnused() throws Exception {
        var renter = TestUsers.signUp(mvc).bearer();
        mvc.perform(activate(renter, code(InviteService.Unlocks.RENTING))).andExpect(status().isOk());
        var spare = code(InviteService.Unlocks.RENTING);

        mvc.perform(activate(renter, spare)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select count(*) from identity.invite_codes where used_by is null and note = 'test'",
                        Integer.class))
                .isPositive();
        mvc.perform(activate(TestUsers.signUp(mvc).bearer(), spare)).andExpect(status().isOk());
    }

    @Test
    void everyCallThatNeedsTheTermsSaysWhichSideItIsOn() {
        // A creating call is on one side or the other, except an upload: an appeal's evidence is one, from either.
        var unmarked = routes.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().hasMethodAnnotation(RequiresAcceptedTerms.class))
                .filter(entry -> !entry.getValue().hasMethodAnnotation(RequiresUnlocked.class))
                .map(entry -> entry.getValue().getBeanType().getSimpleName() + "."
                        + entry.getValue().getMethod().getName())
                .toList();
        assertThat(unmarked).containsExactly("UploadController.create");
    }
}
