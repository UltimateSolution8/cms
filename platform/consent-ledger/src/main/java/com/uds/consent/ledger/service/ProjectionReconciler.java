package com.uds.consent.ledger.service;

import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.ledger.store.ConsentArtefactStore;
import com.uds.consent.ledger.store.ConsentEventStore;
import com.uds.consent.ledger.store.StoredEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks {@code consent_artefact} against the chain that produced it.
 *
 * <p><strong>Why this exists, stated plainly because the gap it closes was invisible for eighteen
 * phases.</strong> {@link LedgerIntegrityVerifier} walks {@code consent_event} and proves the chain
 * — every hash, every link. Nothing walked {@code consent_artefact} and asked whether it still
 * agrees with that chain. And the artefact is what {@code PolicyEngine} reads to decide, what
 * {@code ReceiptService} renders onto a document handed to a data principal, and what the evidence
 * bundle reports to the Board.
 *
 * <p>Two of this programme's last three headline defects were projection defects — a withdrawal
 * rewriting the purpose version the principal was recorded as having agreed to, and a re-served
 * notice erasing a live grant — and <em>every control the platform had passed both</em>. The reason
 * is structural: {@code last_event_hash} is <em>copied</em> onto the artefact rather than derived
 * from its {@code status}, so an artefact whose status is wrong stays perfectly self-consistent and
 * the integrity sweep verifies it happily. The same holds for a direct {@code UPDATE} by anyone with
 * the owner role.
 *
 * <p><strong>The fold is {@link ArtefactProjector#replay}, not a second implementation.</strong>
 * That is the single most important decision here. A reconciler with its own fold would drift from
 * the projector and then either report divergence where there is none, or — far worse — agree with
 * itself in precisely the places where the real projector is wrong, which is the failure mode it
 * was built to catch.
 *
 * <p><strong>It reports and does not repair.</strong> Re-projecting automatically would silently
 * erase the only distinction that matters here: a projector defect and a database edit produce the
 * same divergence, and one of them is a security incident. The report names what disagrees; what to
 * do about it is a runbook step with a human in it ({@code docs/OPERATIONS.md} §3).
 *
 * <p>Off the request path entirely. Re-deriving a chain per decision is unaffordable against a
 * measured 2.6 ms p95 at ~50 rps ({@code docs/CAPACITY.md} §7) — and it would not have detected any
 * of this anyway, for the copied-hash reason above.
 */
@Service
public class ProjectionReconciler {

    private final ConsentEventStore events;
    private final ConsentArtefactStore artefacts;

    public ProjectionReconciler(ConsentEventStore events, ConsentArtefactStore artefacts) {
        this.events = events;
        this.artefacts = artefacts;
    }

    /** Re-derives every purpose for one subject and reports what disagrees. */
    public List<Divergence> reconcileSubject(String entityId, String subjectId) {
        List<Divergence> found = new ArrayList<>();

        for (String purposeCode : events.findPurposeCodes(entityId, subjectId)) {
            List<ConsentEvent> chain = events.findChainForPurpose(entityId, subjectId, purposeCode)
                    .stream().map(StoredEvent::event).toList();
            Optional<ConsentArtefact> implied = ArtefactProjector.replay(chain);
            Optional<ConsentArtefact> projected = artefacts.find(entityId, subjectId, purposeCode);

            if (implied.isEmpty()) {
                continue;
            }
            if (projected.isEmpty()) {
                // The chain says there is an agreement and the projection has no row at all. Every
                // decision for this subject and purpose currently reads NOT_ASKED.
                found.add(Divergence.missing(entityId, subjectId, purposeCode, implied.get()));
                continue;
            }
            compare(found, entityId, subjectId, purposeCode, implied.get(), projected.get());
        }
        return found;
    }

    /**
     * Reconciles a page of subjects.
     *
     * @param limit  subjects per page
     * @param offset where to resume; the caller pages until a page comes back short
     */
    public Result reconcile(int limit, int offset) {
        List<String[]> keys = events.findAllChainKeys(limit, offset);
        List<Divergence> found = new ArrayList<>();
        for (String[] key : keys) {
            found.addAll(reconcileSubject(key[0], key[1]));
        }
        return new Result(keys.size(), List.copyOf(found));
    }

    /**
     * Artefacts with no chain behind them at all.
     *
     * <p>Walking chains and comparing each to its artefact catches an artefact that was
     * <strong>edited</strong> and is structurally blind to one that was <strong>inserted</strong> —
     * there is no chain to start the comparison from. That misses the more dangerous direction: a
     * fabricated {@code WITHDRAWN} refuses lawful processing, a fabricated {@code GRANTED}
     * authorises unlawful processing on a consent that never existed, and the same role writes
     * either. Run once per sweep rather than per page, because it is a question about the table.
     */
    public long countFabricated() {
        return artefacts.countWithoutChain();
    }

    public List<Divergence> findFabricated(int limit) {
        List<Divergence> found = new ArrayList<>();
        for (String[] row : artefacts.findWithoutChain(limit)) {
            found.add(new Divergence(row[0], row[1], row[2], null,
                    com.uds.consent.core.model.ConsentStatus.valueOf(row[3]),
                    "no chain: the projection holds " + row[3] + " and consent_event has no event "
                            + "for this subject and purpose at all. An artefact cannot arise "
                            + "without an event, so this row was inserted rather than projected."));
        }
        return found;
    }

    private static void compare(List<Divergence> found, String entityId, String subjectId,
                                String purposeCode, ConsentArtefact implied,
                                ConsentArtefact projected) {
        List<String> fields = new ArrayList<>();

        if (implied.status() != projected.status()) {
            fields.add("status: chain implies " + implied.status()
                    + ", projection holds " + projected.status());
        }
        // ConsentEvent.NO_PURPOSE_VERSION_ASSERTED means the event asserted no version — an expiry
        // or an invalidation ends an agreement without restating its terms. The projector carries
        // the prior version forward for exactly that reason, so comparing the raw values here would
        // report every expired artefact in the database as divergent. Compare only where both sides
        // actually assert one.
        if (implied.purposeVersion() != ConsentEvent.NO_PURPOSE_VERSION_ASSERTED
                && projected.purposeVersion() != ConsentEvent.NO_PURPOSE_VERSION_ASSERTED
                && implied.purposeVersion() != projected.purposeVersion()) {
            fields.add("purposeVersion: chain implies " + implied.purposeVersion()
                    + ", projection holds " + projected.purposeVersion());
        }
        if (!Objects.equals(implied.grantedAt(), projected.grantedAt())) {
            fields.add("grantedAt");
        }
        if (!Objects.equals(implied.withdrawnAt(), projected.withdrawnAt())) {
            fields.add("withdrawnAt");
        }
        if (!Objects.equals(implied.expiresAt(), projected.expiresAt())) {
            fields.add("expiresAt");
        }
        if (implied.captureMethod() != projected.captureMethod()) {
            fields.add("captureMethod: chain implies " + implied.captureMethod()
                    + ", projection holds " + projected.captureMethod());
        }
        if (implied.sequenceNumber() != projected.sequenceNumber()) {
            fields.add("sequenceNumber: chain implies " + implied.sequenceNumber()
                    + ", projection holds " + projected.sequenceNumber());
        }

        if (!fields.isEmpty()) {
            found.add(new Divergence(entityId, subjectId, purposeCode,
                    implied.status(), projected.status(), String.join("; ", fields)));
        }
    }

    /**
     * One artefact that does not agree with its chain.
     *
     * @param impliedStatus   what the events say, folded through the projector
     * @param projectedStatus what {@code consent_artefact} currently holds
     * @param detail          every field that differs, named, so the report is actionable without a
     *                        second query
     */
    public record Divergence(String entityId, String subjectId, String purposeCode,
                             com.uds.consent.core.model.ConsentStatus impliedStatus,
                             com.uds.consent.core.model.ConsentStatus projectedStatus,
                             String detail) {

        static Divergence missing(String entityId, String subjectId, String purposeCode,
                                  ConsentArtefact implied) {
            return new Divergence(entityId, subjectId, purposeCode, implied.status(), null,
                    "no artefact row: the chain implies " + implied.status()
                            + " and every decision currently reads NOT_ASKED");
        }
    }

    /** @param subjectsChecked subjects whose chains were re-derived on this page */
    public record Result(int subjectsChecked, List<Divergence> divergences) {

        public boolean allAgree() {
            return divergences.isEmpty();
        }
    }
}
