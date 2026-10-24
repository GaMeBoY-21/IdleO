package com.idleo.controlplane;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * India's financial year, April to March in India's time (ADR 0039, ADR 0040): invoice numbers run in one, and tax
 * deducted at source counts within one. Named {@code 2026-27} for any day from 1 April 2026 to 31 March 2027.
 */
public record FinancialYear(int startYear) {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    public static FinancialYear of(Instant at) {
        var day = LocalDate.ofInstant(at, INDIA);
        return new FinancialYear(day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1);
    }

    /** {@code 2026-27}; refused unless it's that shape with consecutive years. */
    public static FinancialYear parse(String name) {
        if (name == null || !name.matches("[0-9]{4}-[0-9]{2}")) {
            throw new IllegalArgumentException("a financial year is written 2026-27");
        }
        var year = new FinancialYear(Integer.parseInt(name.substring(0, 4)));
        if (!year.name().equals(name)) {
            throw new IllegalArgumentException("a financial year is two consecutive years, like 2026-27");
        }
        return year;
    }

    public String name() {
        return startYear + "-" + String.format("%02d", (startYear + 1) % 100);
    }

    /** 1 April, midnight in India. */
    public Instant start() {
        return LocalDate.of(startYear, 4, 1).atStartOfDay(INDIA).toInstant();
    }

    /** 1 April of the next year, midnight in India: the first moment after it. */
    public Instant end() {
        return LocalDate.of(startYear + 1, 4, 1).atStartOfDay(INDIA).toInstant();
    }

    @Override
    public String toString() {
        return name();
    }
}
