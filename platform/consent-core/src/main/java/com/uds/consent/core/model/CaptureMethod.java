package com.uds.consent.core.model;

/**
 * How a consent artefact was obtained. Recorded on every event because the burden of proving
 * valid consent sits with the Data Fiduciary, and "we have a row in a table" is not proof —
 * the fiduciary must be able to say what the subject actually did.
 */
public enum CaptureMethod {

    /** Unticked box the subject ticked. The only compliant checkbox pattern under DPDP Rule 8. */
    CHECKBOX_OPT_IN(true),

    /** Explicit button press against a rendered notice. */
    CLICK_THROUGH(true),

    /** Opt-in confirmed by a second, independently actioned step. Strongest web evidence. */
    DOUBLE_OPT_IN(true),

    /** Confirmed by one-time password against a verified phone or email. */
    OTP_VERIFIED(true),

    /** Spoken consent on a recorded line; the recording is the evidence. */
    VERBAL_RECORDED(true),

    /** Physical signature; the scanned form is the evidence. */
    WET_SIGNATURE(true),

    /** Biometric enrolment performed knowingly by the subject at a kiosk. */
    BIOMETRIC_ENROLMENT(true),

    /** Verifiable parental consent under DPDP s.9, for a subject under eighteen. */
    PARENTAL_VERIFIED(true),

    /**
     * Loaded from an external source whose original lawful basis is documented in the
     * provenance store. Not itself an affirmative action — the provenance record carries the
     * proof, and where it cannot, the record is quarantined rather than contacted.
     */
    IMPORTED_WITH_PROVENANCE(false),

    /**
     * Inferred from a live contractual relationship, as TRAI permits. Not an affirmative action;
     * valid only while the relationship lasts.
     */
    INFERRED_FROM_RELATIONSHIP(false),

    /** No consent was sought because the purpose rests on a legitimate use. */
    NOT_APPLICABLE(false);

    private final boolean affirmativeAction;

    CaptureMethod(boolean affirmativeAction) {
        this.affirmativeAction = affirmativeAction;
    }

    /**
     * Whether this method constitutes the clear affirmative action DPDP s.6 requires. Capture
     * validation rejects a GRANTED event on a consent-based purpose unless this is true.
     */
    public boolean isAffirmativeAction() {
        return affirmativeAction;
    }
}
