package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.ConsoleSeed;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Identity's made-up people and their sign-ins (ADR 0052): the same few names, as real names repeat. */
@Component
class IdentitySeed implements ConsoleSeed {

    private final JdbcClient jdbc;

    IdentitySeed(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void seed(Sizes sizes) {
        var n = "n";
        jdbc.sql("""
                        insert into identity.users
                            (id, status, email, email_verified, display_name, created_at, updated_at, version)
                        select %s,
                               case when n %% 50 = 0 then 'suspended' else 'active' end,
                               'seed' || n || '@seed.invalid',
                               true,
                               (array['Asha','Ravi','Priya','Arjun','Meera','Kiran','Divya','Vikram'])[1 + n %% 8] || ' '
                                   || (array['Iyer','Sharma','Reddy','Nair','Gupta','Rao','Menon','Singh'])[1 + (n / 8) %% 8]
                                   || ' ' || n,
                               %s, %s, 1
                        from generate_series(0, :users - 1) n
                        on conflict do nothing
                        """.formatted(
                        ConsoleSeed.userId(n), ConsoleSeed.at(n, USER_EVERY_MS), ConsoleSeed.at(n, USER_EVERY_MS)))
                .param("users", sizes.users())
                .update();
        // Each person signs in a few times, from one of a few hundred networks.
        jdbc.sql("""
                        insert into identity.security_events (id, user_id, kind, address, device, at)
                        select %s, %s, 'sign_in',
                               cast('203.' || (n %% 200) || '.' || (n %% 250) || '.' || (n %% 250) as inet),
                               md5('browser' || (n %% 400)),
                               %s
                        from (select generate_series(0, :events - 1) as n, %s as owner) events
                        on conflict do nothing
                        """.formatted(
                                ConsoleSeed.id("security", "n", 60_000L),
                                ConsoleSeed.userId("owner"),
                                ConsoleSeed.at("n", 60_000L),
                                ConsoleSeed.busyUser(sizes.users())))
                .param("events", sizes.users() * 3)
                .update();
    }
}
