package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.Side;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which sides of the account a person has unlocked (ADR 0058, {@code identity.user_sides}). A waiting account has none.
 * An active one with no rows has both: everyone did before codes had kinds, and the release before still activates people
 * that way; only a code that unlocks one side writes rows, one a side.
 */
@Component
class UserSides {

    private final JdbcClient jdbc;

    UserSides(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Set<Side> of(UUID userId, boolean waiting) {
        if (waiting) {
            return EnumSet.noneOf(Side.class);
        }
        var rows = jdbc.sql("select side from identity.user_sides where user_id = :user")
                .param("user", userId)
                .query(String.class)
                .list();
        if (rows.isEmpty()) {
            return EnumSet.allOf(Side.class);
        }
        var sides = EnumSet.noneOf(Side.class);
        rows.forEach(side -> sides.add(Side.valueOf(side.toUpperCase(Locale.ROOT))));
        return sides;
    }
}
