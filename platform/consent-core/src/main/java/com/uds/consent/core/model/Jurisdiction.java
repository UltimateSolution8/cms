package com.uds.consent.core.model;

import java.util.Locale;

/**
 * Jurisdictions the group operates in. Denave alone reaches five of these through its
 * international step-down subsidiaries, and their consent rules genuinely conflict — Korea
 * forbids the bundled consent request that India merely discourages.
 *
 * <p>The core data model is jurisdiction-agnostic; jurisdiction-specific behaviour lives in
 * policy modules layered over it.
 */
public enum Jurisdiction {

    /** India — DPDP Act 2023 + DPDP Rules 2025, and TRAI TCCCPR for commercial communication. */
    IN("India"),

    /** European Union — GDPR + ePrivacy Directive as implemented by member states. */
    EU("European Union"),

    /** United Kingdom — UK GDPR + PECR. */
    UK("United Kingdom"),

    /** Singapore — PDPA, including the mandatory Do Not Call Registry check. */
    SG("Singapore"),

    /** Malaysia — PDPA as amended in 2024; biometric data is sensitive personal data. */
    MY("Malaysia"),

    /** South Korea — PIPA. Requires separate, itemised consent per purpose. */
    KR("South Korea"),

    /** California — CCPA/CPRA. */
    US_CA("California, United States"),

    /** Anything not separately modelled. Falls back to the strictest configured baseline. */
    OTHER("Other");

    private final String displayName;

    Jurisdiction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Resolves an ISO 3166-1 alpha-2 country code to a jurisdiction, mapping EU member states
     * onto {@link #EU}. Unknown codes resolve to {@link #OTHER} rather than throwing, because a
     * decision request from an unmodelled country must still get a safe answer.
     */
    public static Jurisdiction ofCountry(String iso3166Alpha2) {
        if (iso3166Alpha2 == null || iso3166Alpha2.isBlank()) {
            return OTHER;
        }
        String code = iso3166Alpha2.trim().toUpperCase(Locale.ROOT);
        return switch (code) {
            case "IN" -> IN;
            case "GB", "UK" -> UK;
            case "SG" -> SG;
            case "MY" -> MY;
            case "KR" -> KR;
            case "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU",
                 "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES",
                 "SE" -> EU;
            default -> OTHER;
        };
    }
}
