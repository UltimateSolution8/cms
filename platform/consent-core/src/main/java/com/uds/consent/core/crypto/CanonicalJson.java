package com.uds.consent.core.crypto;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Deterministic JSON serialisation, so that the same logical event always produces the same bytes
 * and therefore the same hash.
 *
 * <p>Object keys are sorted, no insignificant whitespace is emitted, nulls are written rather than
 * dropped, and temporal values are rendered as ISO-8601 strings rather than epoch numbers. Any of
 * these varying would silently break hash-chain verification on a future JVM or Jackson upgrade.
 *
 * <p>The ledger stores the canonical string it hashed alongside the event. Verification re-reads
 * that stored string rather than re-serialising from the structured columns, so the chain stays
 * verifiable even after the schema gains fields years from now.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .addModule(new JavaTimeModule())
            .build();

    private CanonicalJson() {
    }

    /** Serialises to canonical form. */
    public static String serialize(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("canonical serialisation failed", e);
        }
    }

    /** Parses canonical (or any) JSON back into the given type. */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("could not parse JSON as " + type.getSimpleName(), e);
        }
    }

    /**
     * The configured mapper, for callers that need it directly. Treat as read-only: reconfiguring
     * it would change every hash the platform produces.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
