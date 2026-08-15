package com.uds.consent.core.model;

/**
 * The medium a processing activity uses, or through which consent was captured.
 *
 * <p>Channel matters independently of purpose because suppression registries are
 * channel-specific: India's NCPR suppresses calls and SMS but says nothing about email, and a
 * subject may accept SMS while refusing calls for the same purpose.
 */
public enum Channel {

    WEB(false),
    MOBILE_APP(false),

    /** Outbound voice. Subject to NCPR (India), DNC (Singapore), TPS/CTPS (UK). */
    VOICE_CALL(true),

    /** A2P SMS. Requires DLT-registered sender ID and template in India. */
    SMS(true),

    /** WhatsApp and equivalent messaging. Treated as a commercial communication channel. */
    WHATSAPP(true),

    EMAIL(true),

    /** Attendance or access-control terminal, typically capturing biometrics. */
    KIOSK(false),

    /** Wet-signature form, scanned and stored as evidence. */
    PAPER(false),

    /** Server-to-server capture from an integrated system. */
    API(false),

    /**
     * Records loaded in bulk from a purchased list, a client hand-off or an appending service.
     * Every record on this channel must carry a provenance entry or it cannot be contacted.
     */
    BULK_IMPORT(false),

    POSTAL(true);

    private final boolean commercialCommunication;

    Channel(boolean commercialCommunication) {
        this.commercialCommunication = commercialCommunication;
    }

    /**
     * Whether outbound use of this channel is a commercial communication, and so subject to
     * do-not-contact registry scrubbing before every campaign.
     */
    public boolean isCommercialCommunication() {
        return commercialCommunication;
    }
}
