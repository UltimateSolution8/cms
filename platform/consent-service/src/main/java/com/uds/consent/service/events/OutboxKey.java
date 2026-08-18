package com.uds.consent.service.events;

/**
 * The one place an outbox {@code event_key} is taken apart.
 *
 * <p>{@code ConsentLedger} keys events {@code entityId|subjectId} and {@code RetentionSweeper} uses
 * {@code entityId:subjectId}, so both separators are handled. This used to be a private method on
 * {@code WebhookEventPublisher}; the propagation reconciler needs the same answer, and rules §2 is
 * explicit that a second resolver is how two layers come to disagree about who the caller is. Phase
 * 11's worst defect was exactly that shape, so this is shared rather than copied.
 *
 * <p><strong>An unrecognised key yields nothing rather than a guess.</strong> Getting this wrong in
 * the publisher would deliver one group company's consent changes to another's endpoint — a
 * cross-entity disclosure created by the mechanism meant to honour a withdrawal. Getting it wrong in
 * the reconciler is quieter and still bad: the relay runs group-level, so row-level security would
 * happily accept a gap filed against the wrong fiduciary.
 *
 * <p><strong>Not every topic has a key this can read.</strong> {@code PrincipalPortalService}
 * enqueues {@code rights.verification.requested} keyed on the request reference alone, with no
 * separator — so it yields no entity and no subject, and can never route to a subscription or be
 * covered by the propagation register. That is recorded in {@code REGULATORY_HANDOFF.md} §8.7 as
 * structurally uncoverable rather than as a gap somebody could close by configuration.
 */
final class OutboxKey {

    private OutboxKey() {
    }

    /** The entity a message belongs to, or {@code ""} if the key does not carry one. */
    static String entityFrom(String key) {
        int separator = separatorIn(key);
        return separator < 0 ? "" : key.substring(0, separator);
    }

    /** The subject a message concerns, or {@code null} if the key does not carry one. */
    static String subjectFrom(String key) {
        int separator = separatorIn(key);
        if (separator < 0 || separator + 1 >= key.length()) {
            return null;
        }
        String subject = key.substring(separator + 1);
        return subject.isBlank() ? null : subject;
    }

    private static int separatorIn(String key) {
        if (key == null) {
            return -1;
        }
        int separator = key.indexOf('|');
        return separator < 0 ? key.indexOf(':') : separator;
    }
}
