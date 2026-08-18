package com.uds.consent.service;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.service.LedgerIntegrityVerifier;
import com.uds.consent.ledger.store.ConsentManagerStore;
import com.uds.consent.ledger.store.EnforcementEvidenceStore;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.ledger.store.ProvenanceStore;
import com.uds.consent.ledger.store.ReceiptStore;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.ledger.store.RetentionStore;
import com.uds.consent.ledger.store.RightsRequestStore;
import com.uds.consent.ledger.store.StoredEvent;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.ledger.store.SuppressionStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the platform holds about one person, assembled in one call.
 *
 * <p>Written because the Data Protection Board is constituted — Chairperson and Members appointed
 * 6 June 2026 — and its grievance portal is live. A complaint can arrive today. Every part of the
 * answer already existed and nothing composed them: consent events in the ledger, the notice text
 * as served, the receipts issued, the denials on the enforcement log, the rights requests with
 * their deadlines, the provenance record, the suppressions. Six endpoints, assembled by hand, under
 * time pressure, by whoever is on shift. That is how a detail gets missed, and the detail that gets
 * missed is the one the complaint turns on.
 *
 * <p><strong>"Everything" is a promise that decays.</strong> It was true when this class was written
 * and stopped being true the moment two subject-scoped stores were added without anyone thinking of
 * the bundle — the age assertions and the Korean re-confirmations, both of them precisely what a
 * particular kind of complaint turns on. Which is the ordinary way this kind of claim goes wrong:
 * nobody removes anything, the world just grows around it. {@code EvidenceBundleIT} now enumerates
 * the subject-scoped tables from {@code information_schema} and fails when one is not represented
 * here, because a completeness checklist that lives in a runbook is one that gets done once.
 *
 * <p><strong>And it decays the other way too.</strong> Two sections are bounded — receipts and
 * enforcement denials — and for a long-lived principal the bundle was already incomplete, silently,
 * while the paragraph above went on promising everything. The {@code truncation} section now says
 * on the document's own face which sections were cut and where the remainder is read. An
 * incomplete copy that states what it omits is a lawful answer under DPDP s.11 and GDPR
 * Art. 15(1); one that does not is a claim about the extent of processing that happens to be
 * wrong.
 *
 * <p><strong>The bundle proves its own integrity.</strong> It carries every event's hash and
 * sequence number and the {@link LedgerIntegrityVerifier} result over the same chain, so the reader
 * does not have to take the platform's word for it. An export that says "these are the events" and
 * an export that says "these are the events, here is the chain, and here is the verification"
 * differ by exactly the thing an adversarial reader will ask about.
 *
 * <p><strong>What it is not.</strong> Not a rights-request fulfilment: it answers what UDS holds
 * about a principal in the consent plane, not what DenCRM or the HRMS hold about them. Federated
 * retrieval across the group's systems remains deferred, and conflating the two would let somebody
 * hand a principal this bundle believing they had discharged a s.11 access request.
 */
@Service
public class EvidenceBundleService {

    /**
     * The two sections that are bounded, and the reason they are the only two.
     *
     * <p>Everything else in the bundle is bounded by the person: a principal has a handful of
     * suppressions, one provenance record, a few rights requests. Receipts grow with every consent
     * they give, and enforcement denials grow with every call a dialer declines to place — which
     * for a suppressed contact on an active campaign list is a row per attempt, indefinitely.
     * Those two are the ones that can make a bundle unbounded, and so they are the two that are
     * capped.
     *
     * <p><strong>These caps were silent, and silence made the class comment false.</strong> The
     * promise directly above is "everything the platform holds about one person"; for any
     * principal past these numbers it was already untrue, in the document handed to the Data
     * Protection Board, with nothing on the page to say so. An incomplete copy that states what it
     * omits is a lawful answer under DPDP s.11 and GDPR Art. 15(1); one that does not is a claim
     * about the extent of processing that happens to be wrong.
     */
    public static final int RECEIPT_CAP = 100;
    public static final int DENIAL_CAP = 200;

    private final ConsentLedger ledger;
    private final LedgerIntegrityVerifier verifier;
    private final NoticeStore notices;
    private final ReceiptStore receipts;
    private final EnforcementEvidenceStore enforcement;
    private final RightsRequestStore rights;
    private final SuppressionStore suppressions;
    private final ProvenanceStore provenance;
    private final ConsentManagerStore consentManagers;
    private final SubjectStore subjects;
    private final ReconfirmationStore reconfirmations;
    private final RetentionStore retention;

    public EvidenceBundleService(ConsentLedger ledger, LedgerIntegrityVerifier verifier,
                                 NoticeStore notices, ReceiptStore receipts,
                                 EnforcementEvidenceStore enforcement, RightsRequestStore rights,
                                 SuppressionStore suppressions, ProvenanceStore provenance,
                                 ConsentManagerStore consentManagers, SubjectStore subjects,
                                 ReconfirmationStore reconfirmations, RetentionStore retention) {
        this.ledger = ledger;
        this.verifier = verifier;
        this.notices = notices;
        this.receipts = receipts;
        this.enforcement = enforcement;
        this.rights = rights;
        this.suppressions = suppressions;
        this.provenance = provenance;
        this.consentManagers = consentManagers;
        this.subjects = subjects;
        this.reconfirmations = reconfirmations;
        this.retention = retention;
    }

    /**
     * Assembles the bundle.
     *
     * <p>Read-only and synchronous. If a principal's history ever outgrows a response this becomes
     * an asynchronous job with a download, but it is not that today and pretending otherwise would
     * add a queue, a status endpoint and a storage location to hold an export of personal data —
     * three new things to secure in exchange for a latency problem nobody has.
     */
    @Transactional(readOnly = true)
    public Bundle assemble(String entityId, String subjectId, Instant asOf) {
        // Every id whose history belongs to this person: the surviving subject, plus anything
        // merged into it. The ledger is append-only, so events written before a merge stay under
        // the id they were written against — assembling the person therefore means a union, and a
        // bundle that read only the canonical id would go back to answering a Board complaint with
        // half a person, which is the defect the merge exists to fix.
        List<String> ids = subjects.historyIdsFor(entityId, subjectId);
        String canonicalId = ids.get(0);

        List<StoredEvent> history = across(ids, id -> ledger.history(entityId, id));

        // Filled by the two capped sections below as they are read, so the notice is produced by
        // the data rather than by anybody remembering to check. An empty list is the common case
        // and means the bundle is complete.
        List<Truncation> truncation = new ArrayList<>();
        List<ReceiptStore.StoredReceipt> receiptsRead = capped(
                across(ids, id -> receipts.findForSubject(entityId, id, RECEIPT_CAP + 1)),
                RECEIPT_CAP, "receipts",
                "GET /v1/receipts?entityId=" + entityId + "&subjectId=" + canonicalId
                        + "&limit=500&offset=" + RECEIPT_CAP,
                truncation);
        List<EnforcementEvidenceStore.Denial> denialsRead = capped(
                across(ids, id -> enforcement.denials(entityId, id, null, DENIAL_CAP + 1, 0)),
                DENIAL_CAP, "enforcementDenials",
                "GET /v1/admin/enforcement/denials?entityId=" + entityId + "&subjectId="
                        + canonicalId + "&limit=1000&offset=" + DENIAL_CAP,
                truncation);

        return new Bundle(
                entityId,
                canonicalId,
                // Named in the document rather than silently folded in. A reader comparing this
                // bundle against one taken last year needs to know why it now contains events
                // under ids they have not seen — and "these were established to be the same
                // person, by this administrator, for this reason" is itself evidence.
                ids.subList(1, ids.size()),
                asOf,
                history.stream().map(stored -> describe(stored, asOf)).toList(),
                across(ids, id -> ledger.currentStateForSubject(entityId, id)),
                noticesServed(history),
                receiptsRead,
                denialsRead,
                across(ids, id -> rights.findForSubject(entityId, id)),
                activeSuppressions(entityId, ids, asOf),
                // First answer wins, canonical first. Provenance says where a contact record came
                // from; two merged subjects may each have one, and the surviving subject's is the
                // one describing the record still in use.
                ids.stream()
                        .map(id -> provenance.findLatestForSubject(entityId, id))
                        .flatMap(Optional::stream)
                        .findFirst()
                        .orElse(null),
                across(ids, id -> consentManagers.linksForSubject(entityId, id)),
                // What the group was told about this person's age, by whom, and when. The two
                // complaints these answer are the ones the bundle would otherwise be silent on:
                // "you profiled my child" turns on the assertions and their dates, and "you kept
                // mailing me and never asked again" turns on the confirmation history including
                // what each one disclosed.
                across(ids, id -> subjects.ageAssertionsFor(entityId, id)),
                across(ids, id -> reconfirmations.forSubject(entityId, id)),
                // Found by the completeness guard below rather than by anybody remembering it,
                // which is the guard doing exactly its job on the day it was written.
                across(ids, id -> retention.forSubject(entityId, id)),
                List.copyOf(truncation),
                // The surviving chain only. A merge does not join two hash chains — it cannot, and
                // pretending otherwise is exactly the kind of convenience that makes a ledger stop
                // being evidence. Each superseded subject's chain remains separately verifiable
                // under its own id; what this field answers is whether the record still being
                // written to is intact.
                verifier.verifyChain(entityId, canonicalId));
    }

    /**
     * Runs one store lookup across every id belonging to this person and concatenates the answers.
     *
     * <p>A helper rather than fourteen hand-written loops, because the failure mode being defended
     * against is precisely the one hand-written loops produce: somebody adds a fifteenth section
     * to the bundle, reads it against the canonical id alone, and the omission is invisible until
     * a regulator asks about a subject who happens to have been merged. There is one shape here
     * and every section uses it.
     */
    private static <T> List<T> across(List<String> ids,
                                      java.util.function.Function<String, List<T>> lookup) {
        if (ids.size() == 1) {
            return lookup.apply(ids.get(0));
        }
        List<T> combined = new ArrayList<>();
        for (String id : ids) {
            combined.addAll(lookup.apply(id));
        }
        return List.copyOf(combined);
    }

    /**
     * Trims a section to its cap and, when it had to, records that it did.
     *
     * <p>Detected by asking each store for {@code cap + 1} and seeing whether it came back: one
     * extra row is enough to know there is more, and it costs nothing. **The total is deliberately
     * not counted.** A count over an entity-scoped, month-partitioned table under a row-level
     * security predicate is a scan, paid on every bundle assembled for every principal, to change
     * "there are more" into "there are 1,347 more" — and the reader's next action is the same
     * either way, because {@code remainderAt} is the route that answers it.
     *
     * <p>The cap is applied to the union across merged ids rather than per id. A person whose
     * history spans two subject ids has one history, and reporting each id's truncation separately
     * would describe the platform's storage rather than the person the bundle is about.
     */
    private static <T> List<T> capped(List<T> read, int cap, String section, String remainderAt,
                                      List<Truncation> truncation) {
        if (read.size() <= cap) {
            return read;
        }
        truncation.add(new Truncation(section, cap, cap, remainderAt));
        return List.copyOf(read.subList(0, cap));
    }

    /**
     * The notice versions this principal was actually shown, with the text as served.
     *
     * <p>The part of the bundle that does the most work and is easiest to leave out. A consent
     * record proves somebody agreed; it does not prove what they were told, and "what were they
     * told" is the question a grievance is actually about. Resolved from the version cited on each
     * event rather than from the notice's current version, because the current version is precisely
     * what they were not shown.
     */
    private List<NoticeAsServed> noticesServed(List<StoredEvent> history) {
        // Keyed on notice and version so that four events citing one notice produce one entry
        // rather than four copies of the same wall of text.
        Map<String, NoticeAsServed> byVersion = new LinkedHashMap<>();

        for (StoredEvent stored : history) {
            ConsentEvent event = stored.event();
            if (event.noticeId() == null || event.noticeVersion() == null) {
                continue;
            }
            String key = event.noticeId() + "@" + event.noticeVersion() + "/" + event.languageTag();
            byVersion.computeIfAbsent(key, ignored -> {
                Optional<NoticeStore.NoticeVersion> version =
                        notices.findVersion(event.noticeId(), event.noticeVersion());

                String title = null;
                String body = null;
                if (version.isPresent() && event.languageTag() != null) {
                    Optional<NoticeStore.Translation> translation =
                            notices.findTranslation(version.get().id(), event.languageTag());
                    title = translation.map(NoticeStore.Translation::title).orElse(null);
                    body = translation.map(NoticeStore.Translation::body).orElse(null);
                }

                return new NoticeAsServed(event.noticeId(), event.noticeVersion(),
                        event.languageTag(), event.occurredAt(),
                        version.map(NoticeStore.NoticeVersion::publishedAt).orElse(null),
                        title, body,
                        // Stated rather than silently empty. A notice version that cannot be
                        // reproduced is a gap in the evidence, and the bundle should say so where
                        // the reader is looking rather than leave them to notice an absence.
                        version.isEmpty()
                                ? "the cited notice version is no longer in the notice store"
                                : (body == null
                                        ? "no stored translation for the language served"
                                        : null));
            });
        }
        return List.copyOf(byVersion.values());
    }

    /**
     * Active suppressions, channel by channel.
     *
     * <p>Swept across every channel rather than asked for one, because the reader of a bundle does
     * not know which channel to ask about — that is what they are trying to find out. A "do not
     * call" that the bundle omitted because nobody passed {@code VOICE_CALL} would be the single
     * most damaging omission in the document.
     */
    private List<SuppressionAsRecorded> activeSuppressions(String entityId, List<String> ids,
                                                            Instant asOf) {
        List<SuppressionAsRecorded> found = new ArrayList<>();
        // Across every id belonging to this person, and this is the section where that matters
        // most. A "do not call" recorded against a subject that was later merged away is still a
        // "do not call" — omitting it would produce a bundle stating that somebody may be phoned
        // when the record says otherwise, which is the single most damaging thing this document
        // could get wrong.
        for (String subjectId : ids) {
            for (Channel channel : Channel.values()) {
                suppressions.findForSubject(entityId, subjectId, channel, null, null, asOf)
                        .ifPresent(hit -> found.add(new SuppressionAsRecorded(channel, hit.source(),
                                hit.scope(), hit.reason())));
            }
        }
        return List.copyOf(found);
    }

    private static EventAsRecorded describe(StoredEvent stored, Instant asOf) {
        ConsentEvent event = stored.event();
        return new EventAsRecorded(
                event.eventId(), event.sequenceNumber(), event.purposeCode(),
                event.purposeVersion(), event.type().name(), event.legalBasis(),
                event.captureMethod(), event.actorType(), event.actorId(), event.channel(),
                event.applicationId(), event.jurisdiction(), event.noticeId(),
                event.noticeVersion(), event.languageTag(), event.occurredAt(),
                event.recordedAt(), event.expiresAt(), event.reason(), event.evidenceRef(),
                event.previousHash(), event.eventHash(),
                // The canonical bytes that were hashed, so a reader can recompute the chain
                // themselves rather than trusting the verification below.
                stored.canonicalPayload());
    }

    /**
     * @param ageAssertions   what the group was told about the subject's minority, when, and by
     *                        whom. Never inferred: silence about age produces no row, so an empty
     *                        list means nobody declared anything rather than that the subject is an
     *                        adult
     * @param reconfirmations Korea's two-yearly re-confirmations of marketing consent, sent and
     *                        unsent alike. The unsent ones are the complaint; the sent ones, with
     *                        their three Art. 62-3(2) disclosures, are the answer to it
     * @param retentionActions what was scheduled for erasure, when the Rule 8 notice went, and
     *                        whether the owning system confirmed. Open and closed alike, because
     *                        "did you actually delete it" is answered by the closed rows
     * @param truncation      the sections that did not fit, with the route that returns the rest.
     *                        Empty means the bundle is complete, which is the common case. A
     *                        non-empty list is the document saying so on its own face rather than
     *                        leaving a reader to assume a principal with ninety-nine receipts and
     *                        one with nine hundred look the same
     * @param integrity the chain verification over this principal's events. Carried inside the
     *                  bundle rather than offered as a separate endpoint so that the proof travels
     *                  with the thing it proves — an export whose verification lives elsewhere is
     *                  one somebody will forward without it
     */
    /**
     * @param subjectId  the surviving subject. Where the caller asked about an id that has since
     *                   been merged away, this is the id that answered — not the one they asked
     *                   about, because a bundle headed by a superseded id would invite a reader to
     *                   go and look for a record that is no longer being written to
     * @param mergedFrom subjects established to be the same person and folded into this one. Named
     *                   rather than silently included: a reader comparing this bundle with an
     *                   earlier one needs to know why it contains events under ids they have not
     *                   seen, and the merge itself — who asserted it and why — is evidence
     */
    public record Bundle(String entityId, String subjectId, List<String> mergedFrom,
                         Instant assembledAt,
                         List<EventAsRecorded> events, List<ConsentArtefact> currentState,
                         List<NoticeAsServed> noticesServed,
                         List<ReceiptStore.StoredReceipt> receipts,
                         List<EnforcementEvidenceStore.Denial> enforcementDenials,
                         List<RightsRequestStore.Request> rightsRequests,
                         List<SuppressionAsRecorded> suppressions,
                         ProvenanceStore.Record provenance,
                         List<ConsentManagerStore.Link> consentManagerLinks,
                         List<SubjectStore.AgeAssertion> ageAssertions,
                         List<ReconfirmationStore.Reconfirmation> reconfirmations,
                         List<RetentionStore.Action> retentionActions,
                         List<Truncation> truncation,
                         LedgerIntegrityVerifier.ChainVerification integrity) {
    }

    /**
     * One section the bundle could not fit, and where the rest of it is.
     *
     * @param section     the bundle field that was cut, by its name in this document
     * @param returned    how many rows are present here. Equal to {@code cap} by construction —
     *                    stated anyway, so a reader counting the array and a reader reading this
     *                    notice cannot reach different conclusions
     * @param cap         the ceiling that applied
     * @param remainderAt the request that returns the remainder, written out with this subject's
     *                    identifiers already in it and the offset already advanced. A pointer a
     *                    reader has to assemble is one they will assemble wrongly under pressure
     */
    public record Truncation(String section, int returned, int cap, String remainderAt) {
    }

    public record EventAsRecorded(String eventId, long sequenceNumber, String purposeCode,
                                  int purposeVersion, String type, LegalBasis legalBasis,
                                  CaptureMethod captureMethod, ActorType actorType, String actorId,
                                  Channel channel, String applicationId, Jurisdiction jurisdiction,
                                  String noticeId, Integer noticeVersion, String languageTag,
                                  Instant occurredAt, Instant recordedAt, Instant expiresAt,
                                  String reason, String evidenceRef, String previousHash,
                                  String eventHash, String canonicalPayload) {
    }

    /**
     * @param gap why the text is missing, when it is. Null when the notice reproduced cleanly
     */
    public record NoticeAsServed(String noticeId, int version, String languageTag,
                                 Instant servedAt, Instant publishedAt, String title, String body,
                                 String gap) {
    }

    public record SuppressionAsRecorded(Channel channel, SuppressionSource source,
                                        SuppressionScope scope, String reason) {
    }
}
