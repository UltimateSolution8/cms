package com.uds.consent.service;

import com.uds.consent.core.crypto.IdentifierHasher;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.ProvenanceSourceType;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.ledger.store.SubjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where contact data came from, and whether the group can defend holding it.
 *
 * <p>This is the capability no commercial consent platform offers and the one carrying the most
 * commercial weight here. Denave's prospect database was not all collected directly — parts were
 * purchased, appended, or supplied by clients — and the plan names unsubstantiable provenance as
 * the single biggest commercial risk in the programme.
 *
 * <p>Two rules run through everything below.
 *
 * <p><strong>Ingestion cannot self-certify.</strong> There is no parameter on any write path that
 * marks a record substantiated. Records arrive quarantined and leave only when a named human
 * accepts named evidence. The bulk importer is the least-supervised code path in the platform and
 * therefore the last place that should be trusted to declare its own rows clean.
 *
 * <p><strong>A bad row does not fail the batch.</strong> An import of two hundred thousand
 * contacts that aborts on row 4,000 tells the operator nothing except to try again. Each row is
 * accepted or rejected on its own and the caller gets the tally, so a malformed file becomes a
 * list of corrections rather than a repeated failure.
 */
@Service
public class ProvenanceService {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceService.class);

    private final ProvenanceStore store;
    private final SubjectStore subjects;
    private final IdentifierHasher hasher;
    private final AdminAuditStore audit;
    private final TransactionTemplate perRow;

    public ProvenanceService(ProvenanceStore store, SubjectStore subjects, IdentifierHasher hasher,
                             AdminAuditStore audit, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.subjects = subjects;
        this.hasher = hasher;
        this.audit = audit;
        // Each row of a batch commits or rolls back alone. Without this the first constraint
        // violation would mark the whole transaction rollback-only, every subsequent row would
        // fail on a poisoned connection, and the per-row report the caller is promised would be
        // a list of misleading errors caused by one bad row near the top of the file.
        this.perRow = new TransactionTemplate(transactionManager);
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Records where one contact came from.
     *
     * <p>Accepts either a known {@code subjectId} or a real-world identifier, which is hashed here
     * so no caller holds the pepper and nothing plaintext reaches the store.
     */
    @Transactional
    public Result record(Submission submission, String actorId) {
        return doRecord(submission, actorId);
    }

    private Result doRecord(Submission submission, String actorId) {
        String subjectId = resolveSubject(submission);
        ProvenanceStore.Ingestion ingestion = store.recordIdempotent(
                submission.entityId(),
                subjectId,
                submission.sourceType().name(),
                submission.sourceName(),
                submission.acquiredAt(),
                submission.originalLegalBasis() == null ? null
                        : submission.originalLegalBasis().name(),
                submission.evidenceRef(),
                submission.contractRef());

        if (ingestion.inserted()) {
            audit.record(actorId, "PROVENANCE_RECORDED", submission.entityId(),
                    "provenance_record", String.valueOf(ingestion.id()),
                    Map.of("sourceType", submission.sourceType().name(),
                            "sourceName", submission.sourceName()));
        }
        return new Result(ingestion.id(), subjectId, ingestion.inserted(), true, null);
    }

    /**
     * The backfill entry point: many records, one report.
     *
     * <p>Writes one audit entry for the batch rather than one per row. A two-hundred-thousand-row
     * import that produced two hundred thousand audit entries would bury every other admin action
     * in the trail, and the compliance-relevant fact is that a named person imported a named file
     * on a date — not that row 92,144 existed.
     */
    public BatchResult recordBatch(String entityId, List<Submission> submissions, String batchRef,
                                   String actorId) {
        List<Result> results = new ArrayList<>(submissions.size());
        int inserted = 0;
        int duplicates = 0;
        int rejected = 0;

        for (Submission submission : submissions) {
            try {
                Result result = perRow.execute(
                        status -> doRecord(submission.withEntity(entityId), actorId));
                results.add(result);
                if (result.inserted()) {
                    inserted++;
                } else {
                    duplicates++;
                }
            } catch (RuntimeException e) {
                // The row's own identifier is deliberately absent from the message. A rejection
                // report ends up in a ticket, and a ticket carrying phone numbers is a second
                // copy of the data outside the platform's controls.
                results.add(new Result(0, null, false, false, reasonFor(e)));
                rejected++;
            }
        }

        audit.record(actorId, "PROVENANCE_BATCH_IMPORTED", entityId, "provenance_record",
                batchRef == null ? "unnamed-batch" : batchRef,
                Map.of("submitted", String.valueOf(submissions.size()),
                        "inserted", String.valueOf(inserted),
                        "duplicates", String.valueOf(duplicates),
                        "rejected", String.valueOf(rejected)));

        log.info("provenance batch '{}' for entity {}: {} submitted, {} inserted, {} duplicate, "
                + "{} rejected — all quarantined pending substantiation",
                batchRef, entityId, submissions.size(), inserted, duplicates, rejected);
        return new BatchResult(submissions.size(), inserted, duplicates, rejected, results);
    }

    /**
     * Releases a record from quarantine.
     *
     * <p>Requires a note describing the evidence and records who accepted it. Substantiation is a
     * judgement a person makes and stands behind — which is why it is audited, why it is one
     * record at a time, and why there is no bulk equivalent.
     */
    @Transactional
    public ProvenanceStore.Record substantiate(long id, String evidenceNote, String actorId) {
        ProvenanceStore.Record before = store.find(id).orElseThrow(() ->
                new IllegalArgumentException("no provenance record with id " + id));

        store.substantiate(id, evidenceNote, actorId);
        audit.record(actorId, "PROVENANCE_SUBSTANTIATED", before.entityId(), "provenance_record",
                String.valueOf(id), Map.of("sourceType", before.sourceType(),
                        "sourceName", before.sourceName(), "evidenceNote", evidenceNote));

        log.info("provenance record {} substantiated by {} ({} / {})", id, actorId,
                before.sourceType(), before.sourceName());
        return store.find(id).orElseThrow();
    }

    /** The triage queue: everything the group holds and cannot currently lawfully use. */
    @Transactional(readOnly = true)
    public List<ProvenanceStore.Record> quarantined(String entityId, int limit, int offset) {
        return store.findQuarantined(entityId, limit, offset);
    }

    /**
     * Quarantine and contactability by source.
     *
     * <p>The report that turns a vague worry about purchased data into a number leadership can act
     * on, which is why the plan puts this in Phase 0 rather than letting it surface mid-pilot.
     */
    @Transactional(readOnly = true)
    public List<ProvenanceStore.SourceSummary> summariseBySource(String entityId) {
        return store.summariseBySource(entityId);
    }

    @Transactional(readOnly = true)
    public Optional<ProvenanceStore.Record> latestForSubject(String entityId, String subjectId) {
        return store.findLatestForSubject(entityId, subjectId);
    }

    private String resolveSubject(Submission submission) {
        if (submission.subjectId() != null && !submission.subjectId().isBlank()) {
            return submission.subjectId();
        }
        if (submission.identifierType() == null || submission.identifierValue() == null) {
            throw new IllegalArgumentException(
                    "provenance needs either a subjectId or an identifier to attach to");
        }
        String hash = hasher.hash(submission.identifierType(), submission.identifierValue());
        // Creating the subject is right here, unlike on the scrub path. Recording provenance is
        // an assertion that the group holds this person's data; the subject record is what makes
        // that assertion answerable to a rights request.
        return subjects.resolveOrCreate(submission.entityId(), submission.identifierType(), hash);
    }

    private static String reasonFor(RuntimeException e) {
        if (e instanceof IllegalArgumentException) {
            return e.getMessage();
        }
        if (e.getClass().getSimpleName().contains("DataIntegrityViolation")) {
            return "rejected by the database: check the entity id and source type";
        }
        return "could not be recorded";
    }

    /**
     * One provenance assertion, before it has a subject id.
     *
     * @param originalLegalBasis what the original collector relied on, if known. Nullable, and a
     *                           null here is itself information — it means nobody can say
     */
    public record Submission(
            String entityId,
            String subjectId,
            IdentifierType identifierType,
            String identifierValue,
            ProvenanceSourceType sourceType,
            String sourceName,
            Instant acquiredAt,
            LegalBasis originalLegalBasis,
            String evidenceRef,
            String contractRef) {

        Submission withEntity(String entityId) {
            return this.entityId != null && !this.entityId.isBlank() ? this
                    : new Submission(entityId, subjectId, identifierType, identifierValue,
                            sourceType, sourceName, acquiredAt, originalLegalBasis, evidenceRef,
                            contractRef);
        }
    }

    /**
     * @param inserted false when an identical record was already on file — not an error
     * @param accepted false when the row could not be recorded at all
     */
    public record Result(long id, String subjectId, boolean inserted, boolean accepted,
                         String rejectionReason) {
    }

    public record BatchResult(int submitted, int inserted, int duplicates, int rejected,
                              List<Result> results) {
    }
}
