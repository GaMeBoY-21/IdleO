package com.idleo.controlplane;

/**
 * One module's part of the console's made-up data (ADR 0052): its own tables, with realistic spreads, for measuring the
 * console's budgets on the Mac or in the weekly check. Never in production: the command refuses without {@code
 * console.seed.allowed}, which {@link ProductionSettingsGuard} refuses in production.
 */
public interface ConsoleSeed {

    /** How much to make. */
    record Sizes(int users, int hosts, int sessions, int payments) {}

    /** The earliest made-up time, epoch milliseconds: nothing made here is newer than a month before this release. */
    long EPOCH_MS = 1_700_000_000_000L;

    /**
     * SQL for the id of made-up row {@code n}: a UUIDv7 in shape, in time order, the same each time so another module can
     * name it. {@code kind} tells the kinds apart; {@code everyMs} is how far apart in time rows are.
     */
    static String id(String kind, String n, long everyMs) {
        var ms = "(" + EPOCH_MS + " + (" + n + ")::bigint * " + everyMs + ")";
        return "cast(overlay(overlay(md5('" + kind + "' || (" + n + ")) placing lpad(to_hex(" + ms
                + "::bigint), 12, '0') from 1 for 12) placing '7' from 13 for 1) as uuid)";
    }

    /** SQL for the time of made-up row {@code n}. */
    static String at(String n, long everyMs) {
        return "to_timestamp((" + EPOCH_MS + " + (" + n + ")::bigint * " + everyMs + ") / 1000.0)";
    }

    /** How far apart users are: a hundred thousand of them over a couple of years. */
    long USER_EVERY_MS = 600_000L;

    /** SQL for the user's id given their number. */
    static String userId(String n) {
        return id("user", n, USER_EVERY_MS);
    }

    /** Which user a row belongs to: a few are very busy, as real ones are. */
    static String busyUser(int users) {
        return "floor(" + users + " * power(random(), 4))::int";
    }

    /** What this module makes. */
    void seed(Sizes sizes);
}
