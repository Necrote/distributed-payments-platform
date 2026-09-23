package com.vivekpatel.payments.domain;

import java.util.Currency;

/**
 * An amount of money as a whole number of <b>minor currency units</b> plus an ISO-4217 code.
 *
 * <p>{@code Money.of(12500, "INR")} is Rs. 125.00. There is no {@code double} anywhere in this type
 * and there never will be: binary floating point cannot represent 0.1 exactly, so summing a ledger
 * in {@code double} eventually produces a balance that does not balance. {@code BigDecimal} would
 * also be correct, but a {@code long} of minor units is what the wire format, the database column
 * ({@code BIGINT}) and most payment processors already use, so it avoids a conversion at every
 * boundary and makes "amounts are integers" a compile-time property rather than a convention.
 *
 * <p>Limitation to state honestly in the README: this type assumes every supported currency has a
 * fixed minor-unit exponent (INR/USD = 2, JPY = 0). {@link Currency#getDefaultFractionDigits()} is
 * used for formatting only; arithmetic never inspects it.
 */
public record Money(long minorUnits, String currencyCode) {

    public Money {
        if (currencyCode == null || currencyCode.length() != 3) {
            throw new IllegalArgumentException("currencyCode must be a 3-letter ISO-4217 code");
        }
        currencyCode = currencyCode.toUpperCase();
        // Throws IllegalArgumentException for codes ISO-4217 does not know.
        Currency.getInstance(currencyCode);
    }

    public static Money of(long minorUnits, String currencyCode) {
        return new Money(minorUnits, currencyCode);
    }

    /** @throws IllegalArgumentException if the currencies differ - never silently coerce. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currencyCode);
    }

    /** @throws IllegalArgumentException if the currencies differ - never silently coerce. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currencyCode);
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    /** @return the amount in major units for display only, e.g. {@code "125.00 INR"}. */
    public String toDisplayString() {
        int digits = Math.max(Currency.getInstance(currencyCode).getDefaultFractionDigits(), 0);
        // absExact rather than abs: Math.abs(Long.MIN_VALUE) is negative, and a formatting helper
        // that silently flips a sign in a payments system is exactly the kind of bug nobody finds.
        long absolute = Math.absExact(minorUnits);
        String sign = minorUnits < 0L ? "-" : "";
        if (digits == 0) {
            return sign + absolute + " " + currencyCode;
        }
        long divisor = 1L;
        for (int i = 0; i < digits; i++) {
            divisor *= 10L;
        }
        return sign
                + String.format("%d.%0" + digits + "d %s", absolute / divisor, absolute % divisor,
                        currencyCode);
    }

    private void requireSameCurrency(Money other) {
        if (other == null || !currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currencyCode + " vs "
                            + (other == null ? "null" : other.currencyCode));
        }
    }
}
