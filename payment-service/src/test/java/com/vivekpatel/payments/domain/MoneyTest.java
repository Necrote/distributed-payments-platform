package com.vivekpatel.payments.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Worked example of the standard: boundaries, sign handling, and the failure cases. */
class MoneyTest {

    @Test
    @DisplayName("minor units are preserved exactly and the currency code is normalised")
    void constructsFromMinorUnits() {
        Money money = Money.of(12500L, "inr");
        assertEquals(12500L, money.minorUnits());
        assertEquals("INR", money.currencyCode());
    }

    @Test
    @DisplayName("an unknown or malformed currency code is rejected at construction")
    void rejectsBadCurrencyCodes() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(1L, "RUPEE"));
        assertThrows(IllegalArgumentException.class, () -> Money.of(1L, "ZZZ"));
        assertThrows(IllegalArgumentException.class, () -> Money.of(1L, null));
    }

    @Test
    @DisplayName("arithmetic across currencies fails loudly instead of coercing")
    void rejectsMixedCurrencyArithmetic() {
        Money rupees = Money.of(100L, "INR");
        Money dollars = Money.of(100L, "USD");
        assertThrows(IllegalArgumentException.class, () -> rupees.plus(dollars));
        assertThrows(IllegalArgumentException.class, () -> rupees.minus(dollars));
    }

    @Test
    @DisplayName("addition and subtraction stay exact")
    void addsAndSubtracts() {
        assertEquals(Money.of(300L, "INR"), Money.of(100L, "INR").plus(Money.of(200L, "INR")));
        assertEquals(Money.of(-100L, "INR"), Money.of(100L, "INR").minus(Money.of(200L, "INR")));
    }

    @Test
    @DisplayName("overflow throws rather than wrapping around into a negative balance")
    void overflowThrows() {
        Money huge = Money.of(Long.MAX_VALUE, "INR");
        assertThrows(ArithmeticException.class, () -> huge.plus(Money.of(1L, "INR")));
    }

    @Test
    @DisplayName("zero and sign predicates are exact at the boundary")
    void signPredicates() {
        assertTrue(Money.of(1L, "INR").isPositive());
        assertFalse(Money.of(0L, "INR").isPositive());
        assertFalse(Money.of(-1L, "INR").isPositive());
        assertTrue(Money.of(0L, "INR").isZero());
        assertFalse(Money.of(1L, "INR").isZero());
    }

    @Test
    @DisplayName("display formatting handles two-digit, zero-digit and negative amounts")
    void formatsForDisplay() {
        assertEquals("125.00 INR", Money.of(12500L, "INR").toDisplayString());
        assertEquals("0.50 INR", Money.of(50L, "INR").toDisplayString());
        assertEquals("-0.50 INR", Money.of(-50L, "INR").toDisplayString());
        assertEquals("1250 JPY", Money.of(1250L, "JPY").toDisplayString());
    }
}
