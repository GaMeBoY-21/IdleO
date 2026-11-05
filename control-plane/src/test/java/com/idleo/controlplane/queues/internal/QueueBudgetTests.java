package com.idleo.controlplane.queues.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The inbox's budgets on 50k items (ADR 0053): its first page under 200 ms, and a claim under 100 ms, each at p95 on the
 * server. They run weekly with the console's: {@code ./mvnw test -Dgroups=budget -Dsurefire.excludedGroups=
 * -Dtest=QueueBudgetTests}.
 */
@ControlPlaneTest
@Tag("budget")
class QueueBudgetTests {

    private static final int ITEMS = 50_000;
    private static final int RUNS = 100;
    private static final int WARM_UP = 10;
    private static boolean seeded;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @BeforeEach
    void seed() {
        if (seeded) {
            return;
        }
        jdbc.update("""
                insert into queues.items (id, type, source_id, title, link, sla, opened_at, state, due_at, soon_at, score,
                    sort_key, money_paise, created_at)
                select gen_random_uuid(),
                       (array['ticket', 'abuse', 'report', 'chargeback', 'verification', 'review-minute'])[1 + n % 6],
                       'budget-' || n, 'Work ' || n, '/admin/', 'ticket', now() - (n % 1000) * interval '1 minute', 'open',
                       now() + (n % 2000) * interval '1 minute', now() + (n % 2000 - 500) * interval '1 minute',
                       n % 300, (n % 300) * 100000000000 + n, n % 100000, now()
                from generate_series(1, ?) n
                """, ITEMS);
        jdbc.execute("analyze queues.items");
        seeded = true;
    }

    private static long p95(List<Long> took) {
        Collections.sort(took);
        return took.get((int) Math.ceil(took.size() * 0.95) - 1);
    }

    @Test
    void theFirstPageAnswersWithin200Ms() throws Exception {
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var took = new ArrayList<Long>();
        for (int run = -WARM_UP; run < RUNS; run++) {
            var view = List.of("all", "unassigned", "breaching", "mine").get(Math.floorMod(run, 4));
            var started = System.nanoTime();
            var status = mvc.perform(admin.as(get("/admin/v1/queue").param("view", view)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (run >= 0) {
                took.add((System.nanoTime() - started) / 1_000_000);
            }
            assertThat(status).isEqualTo(200);
        }
        var p95 = p95(took);
        System.out.printf("budget: inbox first page p50 %d ms, p95 %d ms%n", took.get(took.size() / 2), p95);
        assertThat(p95).as("inbox p95").isLessThan(200);
    }

    @Test
    void aClaimAnswersWithin100Ms() throws Exception {
        var admin = TestAdmins.signInAs(mvc, jdbc, clock, "owner");
        var items = jdbc.queryForList(
                "select id from queues.items where assignee_id is null and source_id like 'budget-%' limit ?",
                UUID.class, RUNS + WARM_UP);
        var took = new ArrayList<Long>();
        for (int run = 0; run < items.size(); run++) {
            var started = System.nanoTime();
            var status = mvc.perform(admin.as(post("/admin/v1/queue/" + items.get(run) + "/claim")))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            if (run >= WARM_UP) {
                took.add((System.nanoTime() - started) / 1_000_000);
            }
            assertThat(status).isEqualTo(200);
        }
        var p95 = p95(took);
        System.out.printf("budget: claim p50 %d ms, p95 %d ms%n", took.get(took.size() / 2), p95);
        assertThat(p95).as("claim p95").isLessThan(100);
    }
}
