package com.uds.consent.service.api;

import com.uds.consent.core.model.ConsentReceipt;
import com.uds.consent.ledger.store.ReceiptStore;
import com.uds.consent.service.ReceiptService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Fetching a receipt by the number on it.
 *
 * <p>This endpoint is what makes {@code receiptId} worth quoting. Its javadoc has always called it
 * "a stable identifier the subject can quote in a grievance", and until receipts were persisted
 * there was nothing on the other end of that number — a principal quoting it to the grievance
 * officer was quoting something that existed nowhere.
 *
 * <p>What comes back is the document as issued, not a fresh answer to the same question. Rebuilding
 * would reflect a purpose registry that has moved and consents that have since expired, quietly
 * contradicting the copy the subject is holding.
 */
@RestController
@RequestMapping("/v1/receipts")
public class ReceiptController {

    private final ReceiptService receipts;

    public ReceiptController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    /**
     * The receipt as issued.
     *
     * <p>Requires a credential. A receipt names a subject and every purpose they agreed to, so an
     * unauthenticated endpoint keyed on a guessable identifier would be a disclosure channel — and
     * the identifier is a UUID rather than a sequence for the same reason. A subject-facing
     * preference centre reaches this through its own authenticated session, not by holding a
     * platform credential.
     */
    @GetMapping("/{receiptId}")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public ConsentReceipt receipt(@PathVariable String receiptId) {
        return receipts.reproduce(receiptId);
    }

    /**
     * The receipt with its hash, for a holder checking a copy they were sent.
     *
     * <p>The hash is over the same canonical form the ledger chains its events with, so verifying
     * a receipt uses the same code path as verifying an event rather than a parallel one that can
     * drift.
     */
    @GetMapping("/{receiptId}/verification")
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public Map<String, Object> verification(@PathVariable String receiptId) {
        ReceiptStore.StoredReceipt stored = receipts.findStored(receiptId);
        return Map.of("receiptId", stored.receiptId(),
                "issuedAt", stored.issuedAt(),
                "payloadHash", stored.payloadHash(),
                "algorithm", "SHA-256",
                "evidenceHash", stored.evidenceHash() == null ? "" : stored.evidenceHash(),
                "payload", stored.payload());
    }

    /**
     * A page of the receipts issued to a subject, newest first.
     *
     * <p>Paged rather than capped, because the evidence bundle's truncation notice names this
     * route as where the receipts it could not fit can be read. A route that returned only the
     * newest 500 with no way to reach the rest would make that pointer a false statement — which
     * is the defect class this platform has corrected three times.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('CAPTURE', 'ADMIN')")
    public List<ReceiptStore.StoredReceipt> forSubject(@RequestParam String entityId,
                                                       @RequestParam String subjectId,
                                                       @RequestParam(defaultValue = "50")
                                                       int limit,
                                                       @RequestParam(defaultValue = "0")
                                                       int offset) {
        return receipts.forSubject(entityId, subjectId, Math.min(limit, 500), Math.max(offset, 0));
    }
}
