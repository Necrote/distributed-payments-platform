package com.vivekpatel.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of a request's canonical JSON form, as lowercase hex - the value stored in
 * {@code idempotency_keys.request_hash}.
 *
 * <p>Canonical means: object keys sorted at every level, no whitespace. Two requests that mean the
 * same thing hash the same however the client ordered or spaced its JSON; change any value and the
 * hash changes. The input is the parsed request, never the raw bytes - hashing bytes would make a
 * reformatted retry look like a different request.
 *
 * <p>The mapper is private and built here on purpose, not Spring's shared {@code ObjectMapper}.
 * Every stored hash depends on this exact serialisation. If someone later turns on pretty-printing
 * or registers a module app-wide, the shared mapper's output changes, and every in-flight key starts
 * failing its hash comparison - a retry storm of 422s from a config change.
 */
public final class CanonicalRequestHash {

    private static final JsonMapper CANONICAL =
            JsonMapper.builder()
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .disable(SerializationFeature.INDENT_OUTPUT)
                    .build();

    private CanonicalRequestHash() {
    }

    /** @return 64 lowercase hex characters. */
    public static String of(Object request) {
        return sha256Hex(canonicalJson(request));
    }

    /**
     * Converting to a generic tree first turns every object - records included - into a map, and
     * {@code ORDER_MAP_ENTRIES_BY_KEYS} then sorts every map. That is one rule for every level,
     * rather than relying on how Jackson orders a particular record's properties.
     */
    static String canonicalJson(Object request) {
        try {
            Object tree = CANONICAL.convertValue(request, Object.class);
            return CANONICAL.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request cannot be serialised for hashing", e);
        }
    }

    private static String sha256Hex(String json) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every JRE is required to ship SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
