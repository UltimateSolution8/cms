package com.uds.consent.core.model;

/**
 * How the platform satisfied itself that the person consenting on a child's behalf is an
 * identifiable adult.
 *
 * <p>DPDP Rule 10 does not ask a fiduciary to record that it verified a guardian. It asks it to
 * <em>observe due diligence</em> to check that the individual identifying as a parent is an adult
 * who is identifiable, and it describes two routes to that. Those routes carry different
 * evidentiary weight and fail in different ways, so a boolean {@code guardianVerified} would record
 * the conclusion and lose the reasoning — and the reasoning is the part the Board would ask for.
 *
 * <p>Hence an enum rather than a flag. A group that verified ten thousand guardians by one route
 * and a hundred by another has two different exposures if the first route turns out to be weak, and
 * cannot discover that from a column of {@code true}.
 */
public enum GuardianVerificationMethod {

    /**
     * The parent is already a user of this fiduciary's own service, and their identity and age
     * were checked when they themselves registered. The child's consent is authorised from that
     * already-verified account.
     *
     * <p>Rule 10's first route: identity and age "available with" the Data Fiduciary. The strength
     * of this one is entirely inherited — it is exactly as good as the check performed at the
     * parent's own registration, which is why {@code reference} must point at that account rather
     * than merely assert it happened.
     */
    EXISTING_VERIFIED_ACCOUNT,

    /**
     * Identity and age verified against a virtual token issued by a Digital Locker service
     * provider, mapped to a government-issued credential.
     *
     * <p>Rule 10's second route, for a parent the fiduciary does not already know. The token is the
     * evidence and the reference points at it.
     *
     * <p>UDS does not integrate with DigiLocker today — see the hand-off. Recording the method
     * before the integration exists is deliberate: the evidence model has to be able to describe
     * the route on the day the integration lands, and a schema that could not would force the
     * integration to invent one.
     */
    DIGILOCKER_VIRTUAL_TOKEN,

    /**
     * Some other documented check, described in the reference.
     *
     * <p>Exists so that a real-world route the Rules did not anticipate is recorded <em>as</em>
     * something else rather than mislabelled as one of the two above. A capture that used a
     * notarised declaration is not a DigiLocker token, and a register that says it was would be
     * worse than one that says "other": the first is wrong, the second is reviewable.
     *
     * <p>Expect this to attract scrutiny in an audit, which is the intended effect.
     */
    OTHER_DOCUMENTED
}
