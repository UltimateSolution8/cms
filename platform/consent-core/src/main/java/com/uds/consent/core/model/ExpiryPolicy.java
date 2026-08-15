package com.uds.consent.core.model;

/**
 * How consent for a purpose ceases to be valid with the passage of time.
 *
 * <p>This exists because consent is a time series, not a boolean. TRAI's TCCCPR gives explicit
 * transactional consent a seven-day life and ties inferred consent to the duration of the
 * contractual relationship. A schema storing consent as a flag is wrong on its first day.
 */
public enum ExpiryPolicy {

    /** Valid until withdrawn or invalidated. */
    NONE,

    /** Valid for a configured number of days from grant. See the purpose's {@code expiryDays}. */
    FIXED_DAYS,

    /**
     * Valid while the subject has a live contractual relationship with the entity. The
     * relationship's end date is carried on the artefact; the expiry sweeper writes an EXPIRED
     * event when it passes.
     */
    CONTRACT_LIFETIME,

    /**
     * TRAI TCCCPR: explicit consent captured for a transactional communication lapses seven days
     * after it is given. Modelled separately from {@link #FIXED_DAYS} so that the constraint is
     * visible in the registry and cannot be quietly re-tuned to thirty days.
     */
    TRAI_TRANSACTIONAL_7D;

    /** The fixed window this policy implies, or {@code null} where it depends on configuration. */
    public Integer fixedDays() {
        return this == TRAI_TRANSACTIONAL_7D ? 7 : null;
    }
}
