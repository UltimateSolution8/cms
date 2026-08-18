package com.uds.consent.core.model;

/**
 * Where a suppression entry came from.
 *
 * <p>Statutory registries are enforced today, unlike DPDP's substantive obligations, and TRAI in
 * particular acts against telemarketers with financial penalties and disconnection. Scrubbing
 * against them is not a future compliance item.
 */
public enum SuppressionSource {

    /** India: TRAI National Customer Preference Register, via the DLT platform. */
    NCPR_INDIA(true),

    /** Singapore: PDPC Do Not Call Registry. Checked before every telemarketing contact. */
    DNC_SINGAPORE(true),

    /**
     * United States: a universal opt-out signal — Global Privacy Control or equivalent.
     *
     * <p>Statutory, because in the twelve states that mandate it the signal <em>is</em> a valid
     * opt-out request and honouring it is not discretionary. Recorded through the same suppression
     * machinery as a preference register, which is the right shape: it arrives from outside, it
     * outranks a consent record, and it applies until the subject says otherwise.
     *
     * <p>Until this existed {@code CcpaModule} emitted an obligation to honour a GPC signal while
     * nothing in the platform could receive one — an instruction to respect something it had no
     * way to record, which is the wrong way round.
     */
    UNIVERSAL_OPT_OUT(true),

    /** United Kingdom: Telephone Preference Service. */
    TPS_UK(true),

    /** United Kingdom: Corporate Telephone Preference Service, for B2B numbers. */
    CTPS_UK(true),

    /** Subject opted out through a UDS preference centre or unsubscribe link. */
    INBOUND_OPT_OUT(false),

    /** Subject asked an agent to stop contacting them, recorded on the call. */
    AGENT_RECORDED(false),

    /** Suppression supplied by a client for its own campaign. */
    CLIENT_SUPPLIED(false),

    /** Entered by an administrator, e.g. following a grievance. Requires a reason. */
    MANUAL(false),

    /** Hard bounce, invalid number, or repeated delivery failure. */
    DELIVERY_FAILURE(false);

    private final boolean statutory;

    SuppressionSource(boolean statutory) {
        this.statutory = statutory;
    }

    /**
     * Whether this source is a statutory registry. Statutory suppressions are always global and
     * may not be overridden by a consent record — a subject on the NCPR is not contactable on a
     * promotional purpose even if a consent row exists.
     */
    public boolean isStatutory() {
        return statutory;
    }
}
