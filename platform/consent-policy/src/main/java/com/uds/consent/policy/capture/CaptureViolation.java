package com.uds.consent.policy.capture;

/**
 * A reason a consent submission was refused.
 *
 * <p>Refusing at capture is deliberate. A consent record that was invalid the moment it was
 * created is worse than no record at all: it looks like evidence, it will be relied on by every
 * downstream system, and its invalidity surfaces only when a regulator or a complainant goes
 * looking. Better to fail the integration loudly during the pilot.
 *
 * @param purposeCode purpose the violation relates to, or {@code null} where it concerns the
 *                    submission as a whole
 * @param code        machine-readable violation code
 * @param detail      what to fix, addressed to the engineer integrating the surface
 */
public record CaptureViolation(String purposeCode, Code code, String detail) {

    public enum Code {
        /** A consent control arrived already selected. Prohibited by DPDP Rule 8. */
        PRE_SELECTED_OPTION,

        /**
         * Refusing was not offered as plainly as accepting. DPDP s.6(6) requires withdrawal to be
         * as easy as giving, and Rule 8's bar on disguised refusal carries the same intent into
         * the initial interaction.
         */
        REFUSAL_NOT_EQUALLY_AVAILABLE,

        /**
         * Several purposes were accepted by one undifferentiated action. Invalid under Korea's
         * PIPA, and invalid anywhere for a purpose flagged as requiring separate consent.
         */
        BUNDLED_CONSENT,

        /** The purpose requires its own consent step and did not get one. */
        SEPARATE_CONSENT_REQUIRED,

        /** The capture method is not a clear affirmative action, as DPDP s.6 requires. */
        NOT_AN_AFFIRMATIVE_ACTION,

        /** The purpose code is not in the registry. */
        UNKNOWN_PURPOSE,

        /** The purpose is retired and may not be used for new capture. */
        RETIRED_PURPOSE,

        /** The purpose has no lawful basis in this jurisdiction. */
        PURPOSE_NOT_PERMITTED_IN_JURISDICTION,

        /** Subject is under eighteen and the purpose is not permitted for children (DPDP s.9). */
        CHILD_PURPOSE_NOT_PERMITTED,

        /** Subject is under eighteen and consent was not verifiably given by a guardian. */
        PARENTAL_CONSENT_REQUIRED,

        /**
         * A guardian consented and the diligence performed on that guardian was not recorded.
         *
         * <p>Distinct from {@link #PARENTAL_CONSENT_REQUIRED}, which catches a child capture that
         * did not claim parental consent at all. This catches one that claims it and offers no
         * evidence — the submission asserts {@code PARENTAL_VERIFIED} or names a
         * {@code PARENT_GUARDIAN} actor and says nothing about how anyone was verified.
         *
         * <p>Two codes rather than one because the remediation differs. The first is a consent
         * flow that has to be rebuilt; the second is a consent flow that is very likely already
         * doing the work and not reporting it, and telling an integrator to "obtain parental
         * consent" when the real answer is "send us the token reference you already have" wastes
         * everybody's time. DPDP Rule 10 requires the fiduciary to observe due diligence, so the
         * diligence is the obligation and this is the code that names it.
         */
        GUARDIAN_VERIFICATION_NOT_EVIDENCED,

        /** No language was recorded, so it cannot be shown the subject could read the notice. */
        LANGUAGE_NOT_RECORDED,

        /** No notice version was recorded, so what the subject saw cannot be reproduced. */
        NOTICE_VERSION_NOT_RECORDED,

        /**
         * A notice version was recorded and does not exist.
         *
         * <p>Distinct from {@link #NOTICE_VERSION_NOT_RECORDED}, which catches an absent field.
         * This catches a present one that points at nothing — a citation of notice v99 that was
         * never published. Both destroy the evidence plane's central promise; only this one looks
         * entirely well-formed while doing it.
         */
        NOTICE_VERSION_UNKNOWN,

        /**
         * The notice exists but not in the language the subject was served in.
         *
         * <p>A notice the subject could not read is not an informed notice, and a consent record
         * citing one is evidence of the wrong thing. Usually a translation-procurement gap rather
         * than a bug, which is why it is a separate code: the remediation is a purchase order.
         */
        NOTICE_LANGUAGE_UNAVAILABLE,

        /** Consent was claimed for a purpose that does not rest on consent in this jurisdiction. */
        CONSENT_NOT_THE_BASIS,

        /**
         * The submitting surface is unknown to the application registry, or is registered but
         * decommissioned. Consent whose origin cannot be traced to something the group owns is
         * consent the group cannot stand behind.
         */
        APPLICATION_NOT_REGISTERED,

        /**
         * The surface is registered to a different group entity than the one it is capturing for.
         *
         * <p>What a leaked credential looks like from the inside: every field in the submission is
         * individually well-formed, and only the relationship between two of them is wrong.
         */
        APPLICATION_ENTITY_MISMATCH
    }

    public static CaptureViolation of(String purposeCode, Code code, String detail) {
        return new CaptureViolation(purposeCode, code, detail);
    }

    public static CaptureViolation submission(Code code, String detail) {
        return new CaptureViolation(null, code, detail);
    }
}
