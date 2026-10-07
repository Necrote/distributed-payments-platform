package com.vivekpatel.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalRequestHashTest {

    /** Components deliberately declared out of alphabetical order. */
    private record Request(
            String merchantId,
            long amount,
            String currency,
            String paymentMethodToken,
            String externalReference) {
    }

    private static final Request REQUEST =
            new Request("merchant-123", 12500, "INR", "tok_test_123", null);

    @Test
    void canonicalFormHasSortedKeysNoWhitespaceAndKeepsNulls() {
        assertThat(CanonicalRequestHash.canonicalJson(REQUEST))
                .isEqualTo("{\"amount\":12500,\"currency\":\"INR\",\"externalReference\":null,"
                        + "\"merchantId\":\"merchant-123\",\"paymentMethodToken\":\"tok_test_123\"}");
    }

    @Test
    void hashIsSha256HexOfTheCanonicalForm() {
        // Independently computed: printf '%s' '<canonical json above>' | sha256sum
        assertThat(CanonicalRequestHash.of(REQUEST))
                .isEqualTo("22414cf4b4fd9efefb943a4a5e830d5ba1d144481d3a079591ffc623f8ad9077");
    }

    @Test
    void keyOrderDoesNotChangeTheHash() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("paymentMethodToken", "tok_test_123");
        reversed.put("merchantId", "merchant-123");
        reversed.put("externalReference", null);
        reversed.put("currency", "INR");
        reversed.put("amount", 12500);

        assertThat(CanonicalRequestHash.of(reversed)).isEqualTo(CanonicalRequestHash.of(REQUEST));
    }

    @Test
    void nestedObjectsAreSortedToo() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1);
        inner.put("a", 2);
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("y", List.of(inner));
        outer.put("b", inner);

        assertThat(CanonicalRequestHash.canonicalJson(outer))
                .isEqualTo("{\"b\":{\"a\":2,\"z\":1},\"y\":[{\"a\":2,\"z\":1}]}");
    }

    @Test
    void anyChangedValueChangesTheHash() {
        String original = CanonicalRequestHash.of(REQUEST);

        assertThat(CanonicalRequestHash.of(
                        new Request("merchant-123", 999, "INR", "tok_test_123", null)))
                .isNotEqualTo(original);
        assertThat(CanonicalRequestHash.of(
                        new Request("merchant-123", 12500, "INR", "tok_test_123", "")))
                .as("empty string and null are different requests")
                .isNotEqualTo(original);
    }
}
