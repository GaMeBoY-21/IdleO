package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ConsoleSeed;
import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The console's budgets on the seeded data (ADR 0052): 100k users, 2k hosts, 1M sessions and 300k payments. The search box
 * under 150 ms, the first 50 of a busy person's timeline under 300 ms, and a list's page under 200 ms, each at p95 on the
 * server. It takes minutes, so it runs weekly: {@code ./mvnw test -Dgroups=budget -Dsurefire.excludedGroups=
 * -Dtest=ConsoleBudgetTests}.
 */
@ControlPlaneTest
@Tag("budget")
class ConsoleBudgetTests {

    private static final int WARM_UP = 5;
    private static final int RUNS = 40;

    /** Seeded once: the context is shared. */
    private static boolean seeded;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    List<ConsoleSeed> seeds;

    private TestAdmins.TestAdmin owner;

    @BeforeEach
    void theSeededData() throws Exception {
        if (!seeded) {
            var started = System.nanoTime();
            seeds.forEach(seed -> seed.seed(ConsoleSeedCommand.FULL));
            for (var table : List.of(
                    "identity.users",
                    "identity.security_events",
                    "hosts.hosts",
                    "sessions.sessions",
                    "billing.topups")) {
                jdbc.execute("analyze " + table);
            }
            System.out.printf("budget: seeded in %d s%n", (System.nanoTime() - started) / 1_000_000_000);
            seeded = true;
        }
        owner = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** The slowest of the fastest 95% of {@value #RUNS} calls, in milliseconds, after warming up. */
    private long p95(String what, MockHttpServletRequestBuilder... requests) throws Exception {
        var took = new ArrayList<Long>();
        for (int run = -WARM_UP; run < RUNS; run++) {
            var request = requests[Math.floorMod(run, requests.length)];
            var started = System.nanoTime();
            var status =
                    mvc.perform(owner.as(request)).andReturn().getResponse().getStatus();
            var ms = (System.nanoTime() - started) / 1_000_000;
            assertThat(status).as(what).isEqualTo(200);
            if (run >= 0) {
                took.add(ms);
            }
        }
        took.sort(Long::compare);
        var p95 = took.get((int) Math.ceil(took.size() * 0.95) - 1);
        System.out.printf("budget: %s p50 %d ms, p95 %d ms%n", what, took.get(took.size() / 2), p95);
        return p95;
    }

    private UUID busiest() {
        return jdbc.queryForObject(
                "select renter_id from sessions.sessions group by renter_id order by count(*) desc limit 1",
                UUID.class);
    }

    @Test
    void theSearchBoxAnswersWithin150Ms() throws Exception {
        var user = busiest();
        var session = jdbc.queryForObject("select id from sessions.sessions limit 1", UUID.class);
        var p95 = p95(
                "search",
                get("/admin/v1/search").param("q", "seed4242@seed.invalid"),
                get("/admin/v1/search").param("q", "Priya Reddy"),
                get("/admin/v1/search").param("q", user.toString()),
                get("/admin/v1/search").param("q", session.toString()),
                get("/admin/v1/search").param("q", "pay_seed1234"),
                get("/admin/v1/search").param("q", "Studio PC 15"));
        assertThat(p95).as("search p95").isLessThan(150);
    }

    @Test
    void aBusyPersonsTimelineAnswersWithin300Ms() throws Exception {
        var user = busiest();
        var p95 = p95(
                "timeline",
                get("/admin/v1/timeline")
                        .param("subject", "user")
                        .param("id", user.toString())
                        .param("limit", "50"));
        assertThat(p95).as("timeline p95").isLessThan(300);
    }

    @Test
    void aListsPageAnswersWithin200Ms() throws Exception {
        var user = busiest();
        var worst = List.of(
                        p95("users", get("/admin/v1/users")),
                        p95("users by email", get("/admin/v1/users").param("sort", "email:asc")),
                        p95("users by status", get("/admin/v1/users").param("status", "active")),
                        p95("hosts", get("/admin/v1/hosts")),
                        p95("sessions", get("/admin/v1/sessions")),
                        p95(
                                "a busy person's sessions",
                                get("/admin/v1/sessions").param("user", user.toString())),
                        p95("sessions by state", get("/admin/v1/sessions").param("sort", "state:asc")),
                        p95("payments", get("/admin/v1/payments")),
                        p95("payments by amount", get("/admin/v1/payments").param("sort", "amount:desc")))
                .stream()
                .max(Long::compare)
                .orElseThrow();
        assertThat(worst).as("worst list p95").isLessThan(200);
    }
}
