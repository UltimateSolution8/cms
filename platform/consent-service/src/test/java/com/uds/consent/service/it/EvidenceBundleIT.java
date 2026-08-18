package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.ReconfirmationStore;
import com.uds.consent.ledger.store.SubjectStore;
import com.uds.consent.ledger.store.SuppressionStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.EvidenceBundleService;
import com.uds.consent.service.ReceiptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One call that answers a complaint.
 *
 * <p>The Board has been constituted since 6 June 2026 and its grievance portal is live, so the
 * question this bundle answers is no longer hypothetical. Every part of it already existed in a
 * separate store and nothing composed them — which meant the answer was assembled by hand across
 * six endpoints, under time pressure, by whoever was on shift.
 *
 * <p>The assertions are chosen around what a complaint actually turns on. Not "the endpoint returns
 * 200", but: does it contain the notice <em>as served</em> rather than the current one; does it
 * carry the chain and its verification so the reader need not trust the platform; does a
 * suppression on a channel nobody asked about still appear; and is the act of assembling somebody's
 * entire file itself recorded.
 */
class EvidenceBundleIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";
    private static final String PURPOSE = "MKT_OUTBOUND_CALL";

    @Autowired
    private EvidenceBundleService bundles;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private ReceiptService receipts;

    @Autowired
    private SuppressionStore suppressions;

    @Autowired
    private AdminAuditStore audit;

    @Autowired
    private SubjectStore subjects;

    @Autowired
    private ReconfirmationStore reconfirmations;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("the bundle carries the events, their hashes and the chain verification")
    void theBundleProvesItsOwnIntegrity() {
        String subject = grant();

        EvidenceBundleService.Bundle bundle = bundles.assemble(ENTITY, subject, Instant.now());

        assertThat(bundle.events()).isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.eventHash()).isNotBlank();
                    assertThat(event.sequenceNumber()).isPositive();
                    // The canonical bytes, so a reader can recompute the chain rather than take
                    // the verification below on trust. An export whose integrity claim cannot be
                    // independently checked is a claim, not evidence.
                    assertThat(event.canonicalPayload()).isNotBlank();
                });

        assertThat(bundle.integrity().intact()).isTrue();
        assertThat(bundle.integrity().eventsChecked()).isEqualTo(bundle.events().size());
    }

    @Test
    @DisplayName("the notice in the bundle is the one that was served, with its text")
    void theNoticeIsTheOneTheSubjectSaw() {
        // What a grievance is usually about. A consent record proves somebody agreed; it does not
        // prove what they were told, and returning today's notice text would answer a question
        // nobody asked while looking exactly like an answer to the one they did.
        String subject = grant();

        EvidenceBundleService.Bundle bundle = bundles.assemble(ENTITY, subject, Instant.now());

        assertThat(bundle.noticesServed()).isNotEmpty()
                .anySatisfy(served -> {
                    assertThat(served.noticeId()).isEqualTo(NOTICE);
                    assertThat(served.version()).isEqualTo(1);
                    assertThat(served.languageTag()).isEqualTo("en");
                    assertThat(served.servedAt()).isNotNull();
                    // Either the text or an explicit statement of why it is missing. A silently
                    // empty body would read as "there was no notice", which is a materially
                    // different and much worse fact than "the version could not be reproduced".
                    assertThat(served.body() != null || served.gap() != null)
                            .withFailMessage("the notice was neither reproduced nor explained")
                            .isTrue();
                });
    }

    @Test
    @DisplayName("a suppression on a channel nobody asked about still appears")
    void suppressionsAreSweptAcrossEveryChannel() {
        String subject = grant();
        suppressions.add(ENTITY, SuppressionScope.ENTITY, SuppressionSource.INBOUND_OPT_OUT,
                Channel.VOICE_CALL, com.uds.consent.core.model.IdentifierType.PHONE,
                // A hash rather than a number, as everywhere else: the suppression tables must not
                // become a second contact database.
                "eb-hash-" + subject,
                subject, null, null, Instant.now().minus(1, ChronoUnit.MINUTES), null,
                "asked not to be called", "evidence-bundle-it");

        EvidenceBundleService.Bundle bundle = bundles.assemble(ENTITY, subject, Instant.now());

        // The reader of a bundle does not know which channel to ask about — that is what they are
        // trying to find out. A "do not call" omitted because nobody passed VOICE_CALL would be
        // the single most damaging omission the document could make.
        assertThat(bundle.suppressions())
                .anySatisfy(entry -> assertThat(entry.channel()).isEqualTo(Channel.VOICE_CALL));
    }

    @Test
    @DisplayName("receipts issued to the subject travel with the bundle")
    void receiptsAreIncluded() {
        String subject = grant();
        receipts.issue(ENTITY, subject, Instant.now());

        assertThat(bundles.assemble(ENTITY, subject, Instant.now()).receipts())
                .isNotEmpty()
                .allSatisfy(receipt -> assertThat(receipt.receiptId()).isNotBlank());
    }

    @Test
    @DisplayName("a bundle that could not fit everything says so, and says where the rest is")
    void truncationIsDeclaredRatherThanSilent() {
        // The class promises "everything the platform holds about one person". Two sections are
        // bounded, and for any long-lived principal the promise was already false — silently, in
        // the document handed to the Data Protection Board, with nothing on the page to say so. An
        // incomplete copy that states what it omits is a lawful answer under DPDP s.11 and GDPR
        // Art. 15(1); one that does not is a claim about the extent of processing that is wrong.
        String subject = grant();
        for (int i = 0; i <= EvidenceBundleService.RECEIPT_CAP; i++) {
            receipts.issue(ENTITY, subject, Instant.now());
        }

        EvidenceBundleService.Bundle bundle =
                bundles.assemble(ENTITY, subject, Instant.now());

        assertThat(bundle.receipts()).hasSize(EvidenceBundleService.RECEIPT_CAP);
        assertThat(bundle.truncation())
                .withFailMessage("the bundle silently dropped receipts and said nothing")
                .singleElement()
                .satisfies(cut -> {
                    assertThat(cut.section()).isEqualTo("receipts");
                    assertThat(cut.returned()).isEqualTo(EvidenceBundleService.RECEIPT_CAP);
                    assertThat(cut.cap()).isEqualTo(EvidenceBundleService.RECEIPT_CAP);
                    // The pointer has to be usable as written. A reader assembling a query from a
                    // description under time pressure assembles it wrongly, so this carries the
                    // subject and the offset already in it.
                    assertThat(cut.remainderAt())
                            .contains("/v1/receipts")
                            .contains("subjectId=" + subject)
                            .contains("offset=" + EvidenceBundleService.RECEIPT_CAP);
                });

        // And the section it points at actually returns the remainder, rather than being a route
        // that exists and cannot deliver — which is this programme's third-most-repeated defect.
        assertThat(receipts.forSubject(ENTITY, subject, 500, EvidenceBundleService.RECEIPT_CAP))
                .isNotEmpty();
    }

    @Test
    @DisplayName("a bundle that fits carries no truncation notice at all")
    void anUntruncatedBundleIsSilent() {
        // The common case, and the one that makes the notice above mean something. A truncation
        // list that were always present, empty or not, would be read past.
        String subject = grant();
        receipts.issue(ENTITY, subject, Instant.now());

        assertThat(bundles.assemble(ENTITY, subject, Instant.now()).truncation()).isEmpty();
    }

    @Test
    @DisplayName("a principal with no history produces an empty bundle rather than an error")
    void anUnknownSubjectIsNotAnError() {
        // A complaint from somebody the platform has never heard of is a real and common case —
        // mistaken identity, a wrong number, a person who dealt with a different group entity. The
        // answer "we hold nothing about this person" is a legitimate answer and has to be
        // producible, in the same shape, rather than arriving as a 500 that looks like a fault.
        EvidenceBundleService.Bundle bundle =
                bundles.assemble(ENTITY, "eb-nobody-" + UUID.randomUUID(), Instant.now());

        assertThat(bundle.events()).isEmpty();
        assertThat(bundle.noticesServed()).isEmpty();
        assertThat(bundle.receipts()).isEmpty();
        assertThat(bundle.integrity().intact()).isTrue();
    }

    @Test
    @DisplayName("assembling somebody's whole file is itself recorded")
    void theReadIsAudited() {
        String subject = grant();
        int before = audit.recent(ENTITY, 200).size();

        ResponseEntity<String> response = rest.withBasicAuth("compliance-console", "admin-secret")
                .getForEntity("/v1/admin/evidence/subject/" + ENTITY + "/" + subject, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The most concentrated disclosure the platform can produce. An administrative read that
        // leaves no trace is one nobody can review afterwards — and "who pulled this person's
        // file, and when" is a question that gets asked precisely when the answer matters.
        assertThat(audit.recent(ENTITY, 200)).hasSizeGreaterThan(before)
                .anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("EVIDENCE_BUNDLE_ASSEMBLED");
                    assertThat(entry.targetId()).isEqualTo(subject);
                });
    }

    @Test
    @DisplayName("the bundle is entity-scoped like every other subject read")
    void theBundleIsScoped() {
        // It composes six stores' worth of personal data behind one path, which makes it the
        // single most valuable route to get the isolation wrong on.
        assertThat(rest.withBasicAuth("denave-console", "denave-secret")
                .getForEntity("/v1/admin/evidence/subject/MATRIX/somebody", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        for (String credential : List.of("denave-web", "athena-dialer")) {
            String password = "denave-web".equals(credential) ? "capture-secret" : "decision-secret";
            assertThat(rest.withBasicAuth(credential, password)
                    .getForEntity("/v1/admin/evidence/subject/" + ENTITY + "/somebody",
                            String.class)
                    .getStatusCode())
                    .withFailMessage("%s could pull a subject's whole file", credential)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("what the group was told about the subject's age travels with the bundle")
    void ageAssertionsAreIncluded() {
        // "You profiled my child" is answered by these rows and by nothing else in the bundle. The
        // consent events show a capture; only the assertions show that somebody declared a minor,
        // when they declared it, and which surface said so.
        String subject = grant();
        Instant asserted = Instant.now().minus(2, ChronoUnit.HOURS);
        subjects.assertAge(ENTITY, subject, true, "evidence-bundle-it", asserted,
                ActorType.PARENT_GUARDIAN.name(), "guardian-1", "declared at sign-up");

        assertThat(bundles.assemble(ENTITY, subject, Instant.now()).ageAssertions())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.isChild()).isTrue();
                    assertThat(entry.source()).isEqualTo("evidence-bundle-it");
                    assertThat(entry.actorType()).isEqualTo(ActorType.PARENT_GUARDIAN.name());
                });
    }

    @Test
    @DisplayName("the Korean re-confirmation history travels with the bundle, answered or not")
    void reconfirmationsAreIncluded() {
        // "You kept mailing me and never asked whether I still wanted it." The outstanding rows are
        // the accusation and the sent ones are the defence, so the bundle carries both — a list
        // filtered to what is still open would hand the reader only the damaging half.
        String subject = grant();
        Instant consented = Instant.now().minus(800, ChronoUnit.DAYS);
        reconfirmations.raise(ENTITY, subject, PURPOSE, consented);

        assertThat(bundles.assemble(ENTITY, subject, Instant.now()).reconfirmations())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.purposeCode()).isEqualTo(PURPOSE);
                    assertThat(entry.status()).isEqualTo("DUE");
                });
    }

    @Test
    @DisplayName("every subject-scoped table in the schema is represented in the bundle")
    void theBundleIsCompleteAndStaysComplete() {
        // The guard, and the reason it exists rather than a line in a runbook.
        //
        // This class promises "everything the platform holds about one person". That promise was
        // true when it was written and quietly stopped being true twice: subject_age_assertion and
        // consent_reconfirmation were both added as subject-scoped stores by people thinking about
        // the obligation in front of them, and neither reached the bundle. Nobody removed anything;
        // the schema just grew around a claim that had already been made. This test asks the
        // database what exists rather than asking a developer to remember, so the next store to be
        // added fails here instead of being discovered as a hole in a document handed to the Board.
        //
        // A table may legitimately be absent, but the reason has to be written down here — which is
        // the whole difference between a considered omission and an oversight, and the difference
        // is invisible in the finished export.
        Map<String, String> accountedFor = Map.ofEntries(
                Map.entry("consent_event", "bundle.events(), with hashes and canonical payload"),
                Map.entry("consent_artefact", "bundle.currentState()"),
                Map.entry("consent_receipt", "bundle.receipts()"),
                Map.entry("enforcement_decision", "bundle.enforcementDenials()"),
                Map.entry("rights_request", "bundle.rightsRequests()"),
                Map.entry("suppression_entry", "bundle.suppressions(), swept across every channel"),
                Map.entry("provenance_record", "bundle.provenance()"),
                Map.entry("consent_manager_link", "bundle.consentManagerLinks()"),
                Map.entry("subject_age_assertion", "bundle.ageAssertions()"),
                Map.entry("consent_reconfirmation", "bundle.reconfirmations()"),
                Map.entry("retention_action", "bundle.retentionActions()"),
                // Absent, deliberately, and here is why for each.
                Map.entry("consent_chain_head", "not carried as rows: it is the chain tip, and what "
                        + "a reader needs from it is bundle.integrity(), which verifies against it"),
                Map.entry("subject", "not carried: it is the mutable read model — an entity id and "
                        + "an is_child flag — projected from subject_age_assertion, which is the "
                        + "evidence. Exporting the projection alongside the evidence would invite a "
                        + "reader to treat today's flag as a historical fact, the exact confusion "
                        + "the assertion table was added to end"),
                Map.entry("subject_identifier", "not carried: these are the hashed identifiers the "
                        + "subject is looked up by. They tell the reader nothing they do not "
                        + "already know — they are holding the file — and putting a correlatable "
                        + "set of identifier hashes into an exported, forwardable document is a "
                        + "disclosure with no corresponding benefit"));

        // Partitions are excluded. V28 range-partitions enforcement_decision by month, so
        // information_schema now reports fourteen tables that are one table — and a guard listing
        // enforcement_decision_2027_03 as an unexplained subject-scoped store would be noise that
        // grows by one entry a month until somebody deletes the assertion to stop it. A partition
        // carries no rows its parent does not; explaining the parent explains all of them.
        List<String> subjectScoped = jdbc.queryForList("""
                select c.table_name from information_schema.columns c
                 where c.table_schema = 'public' and c.column_name = 'subject_id'
                   and not exists (select 1 from pg_class p
                                    where p.relname = c.table_name and p.relispartition)
                 intersect
                select c.table_name from information_schema.columns c
                 where c.table_schema = 'public' and c.column_name = 'entity_id'
                """, String.class);

        assertThat(subjectScoped)
                .withFailMessage("no subject-scoped tables found; the query is wrong, not the schema")
                .isNotEmpty();
        assertThat(subjectScoped)
                .withFailMessage("""
                        A subject-scoped table exists that the evidence bundle neither carries nor \
                        explains. Either add it to Bundle, or add it to accountedFor with the \
                        reason it is left out. Found: %s""", subjectScoped)
                .allSatisfy(table -> assertThat(accountedFor).containsKey(table));
    }

    private String grant() {
        String subject = "eb-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(PURPOSE)),
                true, Instant.now().truncatedTo(ChronoUnit.SECONDS), "eb-" + subject, null,
                Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
        return subject;
    }
}
