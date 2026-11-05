package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.ConsoleSeed;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Sessions' made-up runs (ADR 0052), all ended, booked by a few busy people and many quiet ones. */
@Component
class SessionsSeed implements ConsoleSeed {

    private static final long EVERY_MS = 60_000L;
    private static final long HOST_EVERY_MS = 3_600_000L;

    private final JdbcClient jdbc;

    SessionsSeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed(Sizes sizes) {
        jdbc.sql("""
                        insert into sessions.sessions
                            (id, renter_id, host_id, type, state, attempt, template_id, image_digest, vram_needed_mb,
                             max_price_paise_per_min, price_paise_per_min, max_spend_paise, max_runtime_seconds,
                             idempotency_key, request_hash, state_changed_at, version, created_at)
                        select %s, %s, %s, 'batch',
                               (array['COMPLETED','COMPLETED','COMPLETED','FAILED','CANCELLED'])[1 + n %% 5],
                               1,
                               (array['fine-tune','long-run','fine-tune-simulated'])[1 + n %% 3],
                               'seed@sha256:' || md5('image' || (n %% 40)) || md5('image' || (n %% 40)),
                               8192, 120, 60 + n %% 60, 10000, 3600,
                               'seed-' || n, md5('seed' || n), %s, 1, %s
                        from (select generate_series(0, :sessions - 1) as n, %s as owner) booked
                        on conflict do nothing
                        """.formatted(
                                ConsoleSeed.id("session", "n", EVERY_MS),
                                ConsoleSeed.userId("owner"),
                                ConsoleSeed.id("host", "n % :hosts", HOST_EVERY_MS),
                                ConsoleSeed.at("n", EVERY_MS),
                                ConsoleSeed.at("n", EVERY_MS),
                                ConsoleSeed.busyUser(sizes.users())))
                .param("sessions", sizes.sessions())
                .param("hosts", sizes.hosts())
                .update();
    }
}
