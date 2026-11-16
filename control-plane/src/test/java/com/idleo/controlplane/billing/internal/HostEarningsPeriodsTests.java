package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/**
 * The week and the month the IdleO window's earnings are summed over: India's calendar, a week from Monday, a month from
 * the 1st, the same days as the web app's Earnings.
 */
class HostEarningsPeriodsTests {

    private static final java.time.ZoneId IST = OwnerEarningsController.IST;

    private static Instant ist(String dateTime) {
        return java.time.LocalDateTime.parse(dateTime).atZone(IST).toInstant();
    }

    private static ZonedDateTime inIst(Instant instant) {
        return instant.atZone(IST);
    }

    @Test
    void aWeekRunsFromMondayMidnightInIndia() {
        // Thursday 9 October 2025, 14:23 in India.
        var periods = HostEarningsService.Periods.of(ist("2025-10-09T14:23:00"));
        assertThat(inIst(periods.weekStart()).toString()).startsWith("2025-10-06T00:00");
        assertThat(inIst(periods.weekEnd()).toString()).startsWith("2025-10-13T00:00");
    }

    @Test
    void mondayJustAfterMidnightIsTheStartOfANewWeekAndSundayNightIsTheEndOfTheOldOne() {
        var monday = HostEarningsService.Periods.of(ist("2025-10-13T00:00:01"));
        assertThat(inIst(monday.weekStart()).toString()).startsWith("2025-10-13T00:00");
        var sunday = HostEarningsService.Periods.of(ist("2025-10-12T23:59:59"));
        assertThat(inIst(sunday.weekStart()).toString()).startsWith("2025-10-06T00:00");
        assertThat(sunday.weekEnd()).isEqualTo(monday.weekStart());
    }

    @Test
    void theIstDayDecidesNotUtc() {
        // 20:00 UTC on Sunday the 12th is 01:30 on Monday the 13th in India: the new week.
        var periods = HostEarningsService.Periods.of(Instant.parse("2025-10-12T20:00:00Z"));
        assertThat(inIst(periods.weekStart()).toString()).startsWith("2025-10-13T00:00");
    }

    @Test
    void aMonthRunsFromTheFirstAndWrapsTheYear() {
        var october = HostEarningsService.Periods.of(ist("2025-10-31T23:59:00"));
        assertThat(inIst(october.monthStart()).toString()).startsWith("2025-10-01T00:00");
        assertThat(inIst(october.monthEnd()).toString()).startsWith("2025-11-01T00:00");
        var december = HostEarningsService.Periods.of(ist("2025-12-15T10:00:00"));
        assertThat(inIst(december.monthEnd()).toString()).startsWith("2026-01-01T00:00");
    }
}
