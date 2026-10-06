package com.idleo.controlplane;

/**
 * An amount of money in integer paise (ADR 0005, ADR 0014). Never floating point, and no arithmetic
 * that silently overflows: every operation throws {@link ArithmeticException} instead. The API carries
 * plain integer paise; formatting rupees is the web app's job.
 */
public record Paise(long value) implements Comparable<Paise> {

    public static final Paise ZERO = new Paise(0);

    public static Paise of(long value) {
        return new Paise(value);
    }

    public Paise plus(Paise other) {
        return new Paise(Math.addExact(value, other.value));
    }

    public Paise minus(Paise other) {
        return new Paise(Math.subtractExact(value, other.value));
    }

    public Paise times(long factor) {
        return new Paise(Math.multiplyExact(value, factor));
    }

    public Paise negate() {
        return new Paise(Math.negateExact(value));
    }

    public boolean isPositive() {
        return value > 0;
    }

    public boolean isNegative() {
        return value < 0;
    }

    public boolean isGreaterThan(Paise other) {
        return value > other.value;
    }

    @Override
    public int compareTo(Paise other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return value + " paise";
    }
}
