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

    /**
     * California — CCPA/CPRA.
     *
     * <p>Note for the taxonomy work rather than for the code: <strong>the CCPA's B2B exemption
     * expired on 1 January 2023.</strong> Denave's Californian business contacts have had full
     * access, deletion, correction and opt-out rights for three years. Every other state's law
     * excludes commercial-context contacts, so California is the exception rather than the
     * template — and it is the exception that applies to the group's largest US population.
     */
    US_CA("California, United States", true),

    /** Colorado — CPA. Universal opt-out honouring is mandatory. */
    US_CO("Colorado, United States", true),

    /** Connecticut — CTDPA. Universal opt-out honouring is mandatory. */
    US_CT("Connecticut, United States", true),

    /** Texas — TDPSA. Universal opt-out honouring is mandatory. */
    US_TX("Texas, United States", true),

    /** Oregon — OCPA. Universal opt-out honouring is mandatory. */
    US_OR("Oregon, United States", true),

    /** Montana — MCDPA. Universal opt-out honouring is mandatory. */
    US_MT("Montana, United States", true),

    /** Delaware — DPDPA. Universal opt-out honouring is mandatory. */
    US_DE("Delaware, United States", true),

    /** New Jersey — NJDPA. Universal opt-out honouring is mandatory. */
    US_NJ("New Jersey, United States", true),

    /** Nebraska — NDPA. Universal opt-out honouring is mandatory. */
    US_NE("Nebraska, United States", true),

    /** New Hampshire — NHPA. Universal opt-out honouring is mandatory. */
    US_NH("New Hampshire, United States", true),

    /** Minnesota — MCDPA. Universal opt-out honouring is mandatory. */
    US_MN("Minnesota, United States", true),

    /**
     * Maryland — MODPA. The strictest of the set, and the one whose shape is different.
     *
     * <p>Maryland <strong>bans the sale of sensitive data outright</strong>. Consent does not cure
     * it. That matters structurally rather than as a detail: a platform built on the intuition
     * that consent is the top-level gate will get this wrong, because here a freely given, current,
     * itemised consent still does not make the processing lawful. The engine already expresses it
     * — a purpose with no {@code purpose_legal_basis} row for a jurisdiction is denied at Gate 3 —
     * so this is a taxonomy decision rather than an engine change, and it is worth a golden case
     * precisely because the intuition it violates is so common.
     */
    US_MD("Maryland, United States", true),

    /** Virginia — VCDPA. No universal opt-out mandate. */
    US_VA("Virginia, United States", false),

    /** Utah — UCPA. No universal opt-out mandate, and the weakest of the set. */
    US_UT("Utah, United States", false),

    /** Iowa — ICDPA. No universal opt-out mandate. */
    US_IA("Iowa, United States", false),

    /** Anything not separately modelled. Falls back to the strictest configured baseline. */
    OTHER("Other", false);

    private final String displayName;
    private final boolean universalOptOut;

    Jurisdiction(String displayName) {
        this(displayName, false);
    }

    Jurisdiction(String displayName, boolean universalOptOut) {
        this.displayName = displayName;
        this.universalOptOut = universalOptOut;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * Whether this jurisdiction legally requires honouring a universal opt-out signal.
     *
     * <p>A predicate on the enum rather than a constant scattered through the modules, because the
     * list changes every legislative session and a list that lives in one place gets updated once.
     * Twelve states currently mandate it; the largest settlement to date is $1.55m, and three
     * regulators ran a coordinated sweep in September 2025 — so this is actively enforced rather
     * than latent.
     */
    public boolean usesUniversalOptOut() {
        return universalOptOut;
    }

    /** Whether this is a United States jurisdiction, for reporting that groups them. */
    public boolean isUnitedStates() {
        return name().startsWith("US_");
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
