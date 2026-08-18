package com.uds.consent.core.model;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A versioned entry in the purpose registry — the controlled vocabulary the whole platform
 * decides against.
 *
 * <p>Purpose is kept strictly separate from data category. "GPS location, for field-attendance
 * verification" and "GPS location, for marketing personalisation" are two purposes over one data
 * category, and a subject may reasonably accept the first while refusing the second. Collapsing
 * them into a single "location" toggle is the single most common way consent systems become
 * indefensible.
 *
 * @param code                  stable registry code, e.g. {@code MKT_OUTBOUND_CALL}
 * @param version               incremented on any material change; consent is version-specific
 * @param name                  human-readable name shown in the compliance console
 * @param description           what is actually done with the data, in plain language
 * @param legalBases            basis per jurisdiction; absent means the purpose is not permitted
 * @param dataCategories        codes of the data categories this purpose touches
 * @param sensitiveCategories   the subset of {@code dataCategories} the registry marks sensitive
 * @param biometricCategories   the subset of {@code dataCategories} the registry marks biometric
 * @param channels              channels this purpose may use
 * @param expiryPolicy          how consent for this purpose lapses
 * @param expiryDays            window for {@link ExpiryPolicy#FIXED_DAYS}
 * @param failureBehavior       what to do when state is indeterminate
 * @param noticeId              notice that must be served for this purpose
 * @param requiresSeparateConsent   consent may not be bundled with other purposes
 * @param permittedForChildren  may be applied to a subject under eighteen
 * @param retired               no longer available for new capture; existing consent still reads
 */
public record PurposeDefinition(
        String code,
        int version,
        String name,
        String description,
        Map<Jurisdiction, LegalBasis> legalBases,
        Set<String> dataCategories,
        Set<String> sensitiveCategories,
        Set<String> biometricCategories,
        Set<Channel> channels,
        ExpiryPolicy expiryPolicy,
        Integer expiryDays,
        FailureBehavior failureBehavior,
        String noticeId,
        boolean requiresSeparateConsent,
        boolean permittedForChildren,
        boolean retired) {

    public PurposeDefinition {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(expiryPolicy, "expiryPolicy");
        Objects.requireNonNull(failureBehavior, "failureBehavior");
        legalBases = legalBases == null ? Map.of() : Map.copyOf(legalBases);
        dataCategories = dataCategories == null ? Set.of() : Set.copyOf(dataCategories);
        sensitiveCategories = sensitiveCategories == null ? Set.of() : Set.copyOf(sensitiveCategories);
        biometricCategories = biometricCategories == null ? Set.of() : Set.copyOf(biometricCategories);
        channels = channels == null ? Set.of() : Set.copyOf(channels);
    }

    /**
     * Whether this purpose touches any category the registry marks sensitive.
     *
     * <p>Read from the registry rather than inferred from the shape of a category code. A naming
     * convention works until the first category somebody names differently, and the failure is
     * silent: sensitive data handled as ordinary data, with nothing to notice it by.
     */
    public boolean touchesSensitiveData() {
        return !sensitiveCategories.isEmpty();
    }

    /** Whether this purpose touches any category the registry marks biometric. */
    public boolean touchesBiometricData() {
        return !biometricCategories.isEmpty();
    }

    /**
     * The lawful basis for this purpose in the given jurisdiction, or {@code null} if the purpose
     * has no lawful basis there — in which case processing is denied outright rather than falling
     * back to consent.
     */
    public LegalBasis legalBasisFor(Jurisdiction jurisdiction) {
        return legalBases.get(jurisdiction);
    }

    /** Whether this purpose is permitted at all in the given jurisdiction. */
    public boolean isPermittedIn(Jurisdiction jurisdiction) {
        return legalBases.containsKey(jurisdiction);
    }

    /** Whether the purpose may be exercised over the given channel. */
    public boolean permitsChannel(Channel channel) {
        return channels.isEmpty() || channels.contains(channel);
    }

    /**
     * The validity window in days implied by the expiry policy, or {@code null} where validity
     * is open-ended or determined by a contract end date carried on the artefact.
     */
    public Integer validityDays() {
        return switch (expiryPolicy) {
            case NONE, CONTRACT_LIFETIME -> null;
            case TRAI_TRANSACTIONAL_7D -> ExpiryPolicy.TRAI_TRANSACTIONAL_7D.fixedDays();
            case FIXED_DAYS -> expiryDays;
        };
    }
}
