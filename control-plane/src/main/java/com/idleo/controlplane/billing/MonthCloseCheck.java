package com.idleo.controlplane.billing;

import java.time.YearMonth;

/**
 * One item of the month-close checklist (ADR 0055): something that must hold before the Owner closes a month, such as
 * the ledger's checks passing or the month's settlements reconciled. Each module that owns one declares it as a bean;
 * billing refuses to close a month while any of them doesn't hold.
 */
public interface MonthCloseCheck {

    /** Its place on the checklist, smallest first. */
    int order();

    /** What must hold, in a few words: "Settlements reconciled". */
    String name();

    /** Whether it holds for {@code month}, in India's time, and if not, what's left, in words for the Owner. */
    Result check(YearMonth month);

    record Result(boolean done, String detail) {

        public static Result done(String detail) {
            return new Result(true, detail);
        }

        public static Result notYet(String detail) {
            return new Result(false, detail);
        }
    }
}
