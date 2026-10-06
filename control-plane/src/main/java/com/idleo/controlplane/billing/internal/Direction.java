package com.idleo.controlplane.billing.internal;

/** Which side of an account an entry is on. */
enum Direction {
    DEBIT("debit"),
    CREDIT("credit");

    final String dbValue;

    Direction(String dbValue) {
        this.dbValue = dbValue;
    }

    static Direction fromDb(String value) {
        return switch (value) {
            case "debit" -> DEBIT;
            case "credit" -> CREDIT;
            default -> throw new IllegalArgumentException("unknown direction " + value);
        };
    }
}
