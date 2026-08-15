package com.uds.consent.ledger.store;

import com.uds.consent.core.model.ConsentEvent;

/**
 * A ledger event as it exists on disk, paired with the exact canonical bytes that were hashed
 * when it was written.
 *
 * <p>Verification re-reads {@code canonicalPayload} rather than re-serialising the structured
 * columns. That keeps the chain verifiable across schema changes — a column added in 2029 would
 * otherwise change the serialisation of a 2026 event and break every hash behind it — and it
 * makes divergence between the payload and the columns itself detectable as tampering.
 *
 * @param event            the reconstructed domain event
 * @param canonicalPayload the exact string that was hashed at write time
 */
public record StoredEvent(ConsentEvent event, String canonicalPayload) {
}
