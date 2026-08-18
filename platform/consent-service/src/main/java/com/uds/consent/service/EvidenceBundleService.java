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
import com.uds.consent.ledger.store.PropagationTargetStore;
import com.uds.consent.ledger.store.PropagationGapStore;
import com.uds.consent.ledger.store.WebhookStore;
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
    private final PropagationTargetStore propagationTargets;
    private final PropagationGapStore propagationGaps;
    private final WebhookStore webhooks;

    public EvidenceBundleService(ConsentLedger ledger, LedgerIntegrityVerifier verifier,
                                 NoticeStore notices, ReceiptStore receipts,
                                 EnforcementEvidenceStore enforcement, RightsRequestStore rights,
                                 SuppressionStore suppressions, ProvenanceStore provenance,
                                 ConsentManagerStore consentManagers, SubjectStore subjects,
                                 ReconfirmationStore reconfirmations, RetentionStore retention,
                                 PropagationTargetStore propagationTargets,
                                 PropagationGapStore propagationGaps, WebhookStore webhooks) {
        this.propagationTargets = propagationTargets;
        this.propagationGaps = propagationGaps;
        this.webhooks = webhooks;
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
        List<ReceiptStore.StoredReceipt> receiptsRead = cappedAcross(ids, RECEIPT_CAP, "receipts",
                id -> receipts.findForSubject(entityId, id, RECEIPT_CAP + 1),
                id -> "GET /v1/receipts?entityId=" + entityId + "&subjectId=" + id
                        + "&limit=500&offset=" + RECEIPT_CAP,
                truncation);
        List<EnforcementEvidenceStore.Denial> denialsRead = cappedAcross(ids, DENIAL_CAP,
                "enforcementDenials",
                id -> enforcement.denials(entityId, id, null, DENIAL_CAP + 1, 0),
                id -> "GET /v1/admin/enforcement/denials?entityId=" + entityId + "&subjectId="
                        + id + "&limit=1000&offset=" + DENIAL_CAP,
                truncation);

        List<PropagationRecord> propagation = propagation(entityId, ids);

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
                // Which downstream systems were told about this person, and which were not. Bounded
                // by the register, so no cap and no truncation entry — see the record's javadoc.
                propagation,
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
     * Reads a capped section across every id this person is known by, and records what it left out.
     *
     * <p>Detected by asking each store for {@code cap + 1} and seeing whether it came back: one
     * extra row is enough to know there is more, and it costs nothing. <strong>The total is
     * deliberately not counted.</strong> A count over an entity-scoped, month-partitioned table
     * under a row-level security predicate is a scan, paid on every bundle assembled for every
     * principal, to change "there are more" into "there are 1,347 more" — and the reader's next
     * action is the same either way, because {@code remainderAt} is the route that answers it.
     *
     * <p><strong>The cap is applied per id, and that is a correction rather than a preference.</strong>
     * It used to cap the union and then build one pointer from the canonical id alone. Both stores
     * query a single {@code subject_id} with no alias expansion, and the bundle's list is a
     * concatenation by id rather than one ordered sequence — so for a merged principal that pointer
     * named the wrong id and its offset was measured against a list that never existed. Following
     * it returned neither the remainder nor anything adjacent to it.
     *
     * <p>Rules §9: a pointer is only honest if the route can deliver it. So each id that overflowed
     * gets its own entry, with the request that actually returns <em>its</em> remainder. A merged
     * principal is exactly the one most likely to exceed the cap, which is what makes this worth
     * the extra rows rather than a note. The unmerged case is one id and is unchanged.
     */
    private static <T> List<T> cappedAcross(List<String> ids, int cap, String section,
                                            java.util.function.Function<String, List<T>> lookup,
                                            java.util.function.Function<String, String> remainderAt,
                                            List<Truncation> truncation) {
        List<T> combined = new ArrayList<>();
        for (String id : ids) {
            List<T> read = lookup.apply(id);
            if (read.size() > cap) {
                truncation.add(new Truncation(section, cap, cap, remainderAt.apply(id)));
                read = read.subList(0, cap);
            }
            combined.addAll(read);
        }
        return List.copyOf(combined);
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
                         List<PropagationRecord> propagation,
                         LedgerIntegrityVerifier.ChainVerification integrity) {
    }

    /**
     * Which systems were told about this person's consent changes, and which were not.
     *
     * <p><strong>A summary per system, not a log per message</strong>, and the shape is the point.
     * GDPR Art. 19 limb 2 entitles the principal to be told <em>the recipients</em>; it does not ask
     * for a delivery journal. Bounded by the propagation register — a handful of rows — so this
     * section needs no cap and produces no {@link Truncation} entry. An earlier design multiplied an
     * already-uncapped section by the register size and pointed its remainder at a route with no
     * subject parameter, which would have rebuilt the exact defect the truncation notice exists to
     * close, inside the phase that promised not to.
     *
     * <p>Every system the entity has registered appears, <strong>including the ones with nothing to
     * show</strong>. A recipient list that silently omits the systems nobody could reach is the same
     * false statement as a receipt answering "no recipients" where the truth is that nobody wrote
     * them down.
     *
     * <p><strong>Read the record's javadoc before drawing a conclusion from a zero.</strong> Only
     * {@code delivered} and {@code failed} are facts about <em>this</em> principal;
     * {@code systemUnmetDays} is register-level, and {@code deliveryAttributed} distinguishes "not
     * told" from "told before the platform recorded who". Presenting register-level counts as
     * per-subject evidence was a live defect in this section for the length of one review cycle.
     */
    private List<PropagationRecord> propagation(String entityId, List<String> ids) {
        List<PropagationTargetStore.Coverage> registered = propagationTargets.coverage(entityId);
        if (registered.isEmpty()) {
            // No register, no claim. The same deliberate no-op as an empty fulfilment_target: the
            // platform will not imply an obligation UDS has not declared.
            return List.of();
        }

        // Unioned across merged ids for the same reason every other section is: the ledger is
        // append-only, so events written before a merge stay under the id they were written
        // against, and a bundle reading only the canonical id answers with half a person.
        Map<String, WebhookStore.SubjectDelivery> bySystem = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            for (WebhookStore.SubjectDelivery delivery : webhooks.deliveriesForSubject(entityId, id)) {
                bySystem.merge(delivery.systemCode(), delivery, EvidenceBundleService::combine);
            }
        }

        Map<String, Long> occasions = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            for (PropagationGapStore.Gap gap : propagationGaps.forSubject(entityId, id)) {
                occasions.merge(gap.systemCode(), 1L, Long::sum);
            }
        }

        List<PropagationRecord> records = new ArrayList<>(registered.size());
        for (PropagationTargetStore.Coverage target : registered) {
            WebhookStore.SubjectDelivery delivery = bySystem.get(target.systemCode());
            records.add(new PropagationRecord(
                    target.systemCode(),
                    target.topic(),
                    target.mandatory(),
                    target.subscriptionId() != null,
                    delivery == null ? 0L : delivery.delivered(),
                    delivery == null ? 0L : delivery.failed(),
                    delivery == null ? null : delivery.firstAt(),
                    delivery == null ? null : delivery.lastAt(),
                    occasions.getOrDefault(target.systemCode(), 0L),
                    delivery != null));
        }
        return List.copyOf(records);
    }

    private static WebhookStore.SubjectDelivery combine(WebhookStore.SubjectDelivery a,
                                                        WebhookStore.SubjectDelivery b) {
        return new WebhookStore.SubjectDelivery(a.systemCode(), a.subscriptionId(),
                a.delivered() + b.delivered(), a.failed() + b.failed(),
                earliest(a.firstAt(), b.firstAt()), latest(a.lastAt(), b.lastAt()));
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    /**
     * One downstream system that had to hear about this person, and what can be shown about it.
     *
     * @param reachable      whether an active subscription currently carries this system code.
     *                       <strong>False is the finding</strong>: the group declared this system
     *                       must be told and nothing can tell it
     * @param delivered      attempts that arrived. Zero beside a non-zero {@code failed} means the
     *                       system was written to and never reached — counted apart because a failed
     *                       attempt must never read as propagation
     * @param systemUnmetDays days on which this system was recorded as untold — <strong>about some
     *                        principal's message, not necessarily this one's</strong>. The gap table
     *                        is deduplicated on {@code (entity, topic, system, day)} and does not
     *                        include the subject, so the first uncovered message of a day writes the
     *                        row and later ones are discarded. Admitting the subject to that key
     *                        would make growth targets × subjects × days, unbounded by population.
     *                        <p>So this is a <strong>register-level</strong> count reported beside a
     *                        per-subject record, and a zero here does <em>not</em> mean this
     *                        principal's withdrawal was propagated. It means no gap row for this
     *                        system happened to be written on a day when one was needed. The
     *                        per-principal facts in this record are {@code delivered} and
     *                        {@code failed}
     * @param deliveryAttributed whether any delivery row for this system carries this subject at
     *                        all. <strong>False with {@code delivered == 0} is "we cannot say",
     *                        not "nobody was told"</strong> — {@code webhook_delivery.subject_id}
     *                        arrived in {@code V31} and earlier rows are deliberately not
     *                        backfilled, because a derived guess in append-only evidence is a
     *                        fabricated fact. Without this flag a principal propagated to in June
     *                        would read identically to one propagated to never, which is precisely
     *                        the null-is-not-empty distinction the receipt spends a paragraph on
     */
    public record PropagationRecord(String systemCode, String topic, boolean mandatory,
                                    boolean reachable, long delivered, long failed,
                                    Instant firstDeliveredAt, Instant lastDeliveredAt,
                                    long systemUnmetDays, boolean deliveryAttributed) {
    }

    /**
     * One section the bundle could not fit, and where the rest of it is.
     *
     * @param section     the bundle field that was cut, by its name in this document
     * @param returned    how many rows <em>this entry's subject id</em> contributed to the
     *                    section. Equal to {@code cap} by construction, because an entry exists
     *                    only where that id overflowed.
     *                    <p><strong>It is not the length of the section array</strong>, and the
     *                    difference is the merged principal: the cap applies per id, so a person
     *                    merged from three ids can produce three entries while the array holds
     *                    everything all three returned. A reader counting the array and a reader
     *                    summing these will agree only when the subject was never merged. Read
     *                    {@code mergedFrom} on the bundle before drawing a conclusion from either
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
