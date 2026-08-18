package com.uds.consent.service.it;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.ledger.service.BlastRadiusService;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.policy.capture.CaptureViolation;
import com.uds.consent.service.ConsentCaptureService;
import com.uds.consent.service.PublishingService;
import com.uds.consent.service.adapter.CachingPurposeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The control plane, writable and still append-only.
 *
 * <p>Two things are under test and they pull against each other. Legal must be able to publish a
 * notice, a translation or a purpose without an engineer, a migration and a deployment — that was
 * the largest gap in the platform, and it is why ninety-five outstanding translations had not
 * moved. And nothing published may ever become editable, because the evidence plane's whole claim
 * is that the text somebody read in 2026 can be produced in 2031.
 *
 * <p>Both hold at once only because every write here is an INSERT, which is asserted rather than
 * assumed.
 *
 * <p>Every test creates its own notice and its own purpose codes. The container is shared with the
 * rest of the suite, and publishing a new version of a seeded notice would leave that notice's
 * current version untranslated — breaking {@code NoticeIT} in a way that depends on class ordering
 * and would be blamed on anything but this file.
 */
class PublishingIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private PublishingService publishing;

    @Autowired
    private NoticeStore notices;

    @Autowired
    private PurposeRegistryStore purposes;

    @Autowired
    private CachingPurposeCatalog catalog;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private AdminAuditStore audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    // -----------------------------------------------------------------------------------
    // Purposes
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a published purpose version is immediately visible to the decision engine")
    void publishedPurposeReachesTheEngineWithoutARestart() {
        String code = nextCode("VISIBLE");
        PublishingService.PublishedPurpose published = publishing.publishPurpose(
                newVersion(code, LegalBasis.CONSENT, null, false), "compliance-console");

        assertThat(published.version()).isEqualTo(1);

        // The refresh is what makes a publish live on this instance. Until v4 the only routes to a
        // new purpose were an explicit POST against each replica or a restart, while the javadoc
        // described a timer that did not exist.
        catalog.refresh();

        PurposeDefinition definition = catalog.find(code).orElseThrow();
        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.legalBases()).containsEntry(Jurisdiction.IN, LegalBasis.CONSENT);
        assertThat(definition.channels()).contains(Channel.WEB);
        assertThat(definition.dataCategories()).contains("CONTACT_BUSINESS");
        // Read from data_category rather than inferred from the code's prefix, which is the
        // correction made in item 1 and the reason this assertion is here at all.
        assertThat(definition.touchesSensitiveData()).isFalse();
    }

    @Test
    @DisplayName("the caller never chooses the version; the platform assigns max + 1")
    void versionsAreAssignedNotAccepted() {
        String code = nextCode("SEQUENCE");

        assertThat(publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false),
                "compliance-console").version()).isEqualTo(1);
        assertThat(publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false),
                "compliance-console").version()).isEqualTo(2);
        assertThat(publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false),
                "compliance-console").version()).isEqualTo(3);

        // The wire contract has no version field at all, which is the point: a caller who names
        // their own number eventually names one that exists, and the collision surfaces as a
        // constraint violation on a publish the compliance team believes went through.
        assertThat(purposes.nextVersion(code)).isEqualTo(4);
    }

    @Test
    @DisplayName("legitimate interest with no assessment reference is refused with the reason")
    void legitimateInterestNeedsAnAssessment() {
        String code = nextCode("LIA");

        assertThatThrownBy(() -> publishing.publishPurpose(
                newVersion(code, LegalBasis.LEGITIMATE_INTEREST, null, false), "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                // ck_lia_present would refuse this too, as a 500 naming a constraint. The
                // difference matters: the person who has to act is a compliance officer who has
                // not written the assessment, and a constraint name says nothing about that.
                .hasMessageContaining("Legitimate Interests Assessment");

        // And it publishes once the assessment exists — a refusal that cannot be satisfied is a
        // bug rather than a control.
        assertThat(publishing.publishPurpose(
                newVersion(code, LegalBasis.LEGITIMATE_INTEREST, "LIA-2026-014", false),
                "compliance-console").version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a material change publishes with RE_CONSENT_REQUIRED and counts who is affected")
    void materialChangeReportsWhoMustBeAskedAgain() {
        String code = nextCode("MATERIAL");
        String notice = newNotice();

        publishEnglishNotice(notice);
        publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false, notice),
                "compliance-console");
        catalog.refresh();

        grantConsent(code, notice);

        BlastRadiusService.Impact impact = publishing.publishPurpose(
                newVersion(code, LegalBasis.CONSENT, null, true, notice),
                "compliance-console").impact();

        assertThat(impact.newVersion()).isEqualTo(2);
        assertThat(impact.materialChange()).isTrue();
        assertThat(impact.action()).isEqualTo(BlastRadiusService.Action.RE_CONSENT_REQUIRED);
        // The number that matters commercially: the size of the re-permissioning campaign. It is
        // computed before the insert, so it counts consent against the versions this one
        // supersedes and not against itself.
        assertThat(impact.standingConsentAffected()).isGreaterThanOrEqualTo(1);
        assertThat(impact.byEntity()).containsKey(ENTITY);
    }

    @Test
    @DisplayName("a cosmetic change over the same population is NOTICE_UPDATE_ONLY")
    void cosmeticChangeDoesNotForceReConsent() {
        String code = nextCode("COSMETIC");
        String notice = newNotice();

        publishEnglishNotice(notice);
        publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false, notice),
                "compliance-console");
        catalog.refresh();
        grantConsent(code, notice);

        BlastRadiusService.Impact impact = publishing.publishPurpose(
                newVersion(code, LegalBasis.CONSENT, null, false, notice),
                "compliance-console").impact();

        // Same affected population, different obligation. Telling those people is enough; asking
        // them again is not required, and forcing it would cost consent the group lawfully holds.
        assertThat(impact.standingConsentAffected()).isGreaterThanOrEqualTo(1);
        assertThat(impact.action()).isEqualTo(BlastRadiusService.Action.NOTICE_UPDATE_ONLY);
    }

    @Test
    @DisplayName("a first version affecting nobody is NO_ACTION rather than a re-consent campaign")
    void aBrandNewPurposeAffectsNobody() {
        BlastRadiusService.Impact impact = publishing.publishPurpose(
                newVersion(nextCode("FIRST"), LegalBasis.CONSENT, null, true),
                "compliance-console").impact();

        assertThat(impact.action()).isEqualTo(BlastRadiusService.Action.NO_ACTION);
        assertThat(impact.totalAffected()).isZero();
    }

    @Test
    @DisplayName("a data category outside the vocabulary is refused, flags and all")
    void unknownDataCategoryIsRefused() {
        PublishingService.NewVersion request = new PublishingService.NewVersion(
                nextCode("CATEGORY"), "Test", "legal", "Test purpose",
                Map.of(Jurisdiction.IN,
                        new PurposeRegistryStore.BasisEntry(LegalBasis.CONSENT, null, null)),
                Set.of("CONTACT_BUSINESS", "RETINA_SCAN"), Set.of(Channel.WEB),
                ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED, null,
                false, false, false, false);

        // Not merely a foreign key restated. The sensitive and biometric flags live on
        // data_category, so a category invented at publish time would be processed as ordinary
        // personal data — under Malaysia's PDPA 2024 that is the difference between lawful and not.
        assertThatThrownBy(() -> publishing.publishPurpose(request, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RETINA_SCAN")
                .hasMessageContaining("biometric");
    }

    @Test
    @DisplayName("a purpose permitted in no jurisdiction is refused rather than published silent")
    void aPurposePermittedNowhereIsRefused() {
        PublishingService.NewVersion request = new PublishingService.NewVersion(
                nextCode("EMPTY"), "Test", "legal", "Test purpose", Map.of(),
                Set.of("CONTACT_BUSINESS"), Set.of(Channel.WEB), ExpiryPolicy.NONE, null,
                FailureBehavior.FAIL_CLOSED, null, false, false, false, false);

        // The engine denies any jurisdiction with no legal-basis row, so an empty publish is not
        // an incomplete record. It reads as a deliberate prohibition everywhere, and would halt
        // lawful processing while looking entirely intentional.
        assertThatThrownBy(() -> publishing.publishPurpose(request, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denied everywhere");
    }

    @Test
    @DisplayName("FIXED_DAYS without a day count is refused before it reaches the constraint")
    void fixedDaysNeedsADayCount() {
        PublishingService.NewVersion request = new PublishingService.NewVersion(
                nextCode("EXPIRY"), "Test", "legal", "Test purpose",
                Map.of(Jurisdiction.IN,
                        new PurposeRegistryStore.BasisEntry(LegalBasis.CONSENT, null, null)),
                Set.of("CONTACT_BUSINESS"), Set.of(Channel.SMS), ExpiryPolicy.FIXED_DAYS, null,
                FailureBehavior.FAIL_CLOSED, null, false, false, false, false);

        assertThatThrownBy(() -> publishing.publishPurpose(request, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiryDays");
    }

    @Test
    @DisplayName("the publish is attributed to the authenticated principal, not to the system")
    void publishIsAttributed() {
        String code = nextCode("ATTRIBUTED");
        publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false),
                "alice@uds.example");

        assertThat(audit.recent(null, 200)).anySatisfy(entry -> {
            assertThat(entry.action()).isEqualTo("PURPOSE_VERSION_PUBLISHED");
            assertThat(entry.targetId()).isEqualTo(code + ":1");
            assertThat(entry.actorId()).isEqualTo("alice@uds.example");
        });

        // published_by has existed since V1 and was written by nothing until now. An immutable
        // record of a change nobody can be attributed with is half a control.
        assertThat(jdbc.queryForObject("select published_by from purpose_version "
                        + "where purpose_code = ? and version = 1", String.class, code))
                .isEqualTo("alice@uds.example");
    }

    // -----------------------------------------------------------------------------------
    // Notices and translations
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("publishing a notice version returns its blast radius and its language gaps")
    void noticePublishReportsImpactAndGaps() {
        String notice = newNotice();
        String code = nextCode("NOTICEIMPACT");

        publishEnglishNotice(notice);
        publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false, notice),
                "compliance-console");
        catalog.refresh();
        grantConsent(code, notice);

        PublishingService.PublishedNotice published = publishing.publishNotice(notice, "IN", true,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "compliance-console");

        assertThat(published.version().version()).isEqualTo(2);
        assertThat(published.version().materialChange()).isTrue();
        assertThat(published.version().publishedAt()).isNotNull();

        // Per purpose that points at the notice, because a notice is not consented to directly —
        // it is the text a purpose's consent was given against.
        assertThat(published.impact()).isNotEmpty();
        assertThat(published.impact()).allSatisfy(impact -> {
            assertThat(impact.materialChange()).isTrue();
            assertThat(impact.action()).isEqualTo(BlastRadiusService.Action.RE_CONSENT_REQUIRED);
        });

        // A version published with no translations has every mandatory language missing, and the
        // person who published it is told so while they still remember doing it.
        assertThat(published.missingMandatoryLanguages()).contains("en", "hi", "brx");
    }

    @Test
    @DisplayName("a translation can be added to a version published long ago, without a bump")
    void translationsAppendToAnExistingVersion() {
        String notice = newNotice();
        NoticeStore.NoticeVersion first = publishing.publishNotice(notice, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "compliance-console").version();

        publishing.addTranslation(notice, first.version(), "en", "Privacy notice",
                "How Denave uses business contact details.", "compliance-console");
        publishing.addTranslation(notice, first.version(), "brx", "गोनांथि बिजिरनाय",
                "बिथांखि आरो मोनथिहोनाय बिजाब.", "compliance-console");

        // The whole argument for a writable control plane, in one assertion: the Bodo translation
        // of a version published in March lands in September without touching what anybody read,
        // and without creating a version that would oblige re-consent from people whose language
        // never changed.
        assertThat(notices.findVersions(notice)).hasSize(1);
        assertThat(notices.availableLanguages(first.id())).containsExactly("brx", "en");
        assertThat(notices.missingMandatoryLanguages(notice, first.id()))
                .doesNotContain("en", "brx")
                .contains("ta");
    }

    @Test
    @DisplayName("replacing an existing translation is refused, because somebody read it")
    void existingTranslationsAreNeverOverwritten() {
        String notice = newNotice();
        NoticeStore.NoticeVersion version = publishing.publishNotice(notice, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "compliance-console").version();
        publishing.addTranslation(notice, version.version(), "en", "Original title",
                "Original body.", "compliance-console");

        assertThatThrownBy(() -> publishing.addTranslation(notice, version.version(), "en",
                "Rewritten", "Rewritten body.", "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publish a new version");

        // Untouched, which is the assertion that matters. The primary key would have failed the
        // insert anyway; what must never happen is an update that succeeds.
        assertThat(notices.findTranslation(version.id(), "en").orElseThrow().title())
                .isEqualTo("Original title");
    }

    @Test
    @DisplayName("published notice text stays immutable even to the schema owner's trigger")
    void publishedTextCannotBeEditedInPlace() {
        String notice = newNotice();
        NoticeStore.NoticeVersion version = publishing.publishNotice(notice, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "compliance-console").version();
        publishing.addTranslation(notice, version.version(), "en", "Original title",
                "Original body.", "compliance-console");

        // The load-bearing assertion for the whole item. A writable control plane is only safe
        // because publishing appends; if an UPDATE succeeded here, every claim about reproducing
        // what a person read would be a claim about what the text happens to say today.
        assertThatThrownBy(() -> jdbc.update(
                "update notice_translation set body = 'edited' where notice_version_id = ?",
                version.id()))
                .rootCause()
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "update notice_version set material_change = true where id = ?", version.id()))
                .rootCause()
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("publishing against an unregistered notice says what to do about it")
    void unregisteredNoticeIsRefused() {
        assertThatThrownBy(() -> publishing.publishNotice("NOTICE_DOES_NOT_EXIST", "IN", false,
                "https://a", "https://b", "https://c", "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not registered");
    }

    @Test
    @DisplayName("a translation for a version that does not exist is refused")
    void translationNeedsItsVersion() {
        String notice = newNotice();
        assertThatThrownBy(() -> publishing.addTranslation(notice, 99, "en", "t", "b",
                "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("has no version 99");
    }

    @Test
    @DisplayName("a capture citing a version that was never published is refused")
    void captureAgainstAnUnpublishedNoticeIsRefused() {
        String notice = newNotice();
        String code = nextCode("NOTICECHECK");
        publishEnglishNotice(notice);
        publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false, notice),
                "compliance-console");
        catalog.refresh();

        // Version 1 exists; version 7 does not. Before item 7 the platform accepted this and the
        // NOTICE_VERSION_NOT_RECORDED check passed, because it only ever tested that the field was
        // present — producing a record that looks like sound evidence and points at nothing.
        ConsentCaptureService.Result rejected = capture.capture(new CaptureSubmission(
                ENTITY, "nc-" + UUID.randomUUID(), Jurisdiction.IN, "en", Channel.WEB, APP,
                CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, "nc", notice, 7,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(code)),
                true, Instant.parse("2026-08-15T09:00:00Z"), "nc-" + UUID.randomUUID(), null,
                Map.of()));

        assertThat(rejected.isAccepted()).isFalse();
        assertThat(rejected.violations()).extracting(CaptureViolation::code)
                .contains(CaptureViolation.Code.NOTICE_VERSION_UNKNOWN);
    }

    @Test
    @DisplayName("a capture in a language the notice has no translation for is refused")
    void captureInAnUntranslatedLanguageIsRefused() {
        String notice = newNotice();
        String code = nextCode("LANGCHECK");
        publishEnglishNotice(notice);
        publishing.publishPurpose(newVersion(code, LegalBasis.CONSENT, null, false, notice),
                "compliance-console");
        catalog.refresh();

        // A notice the subject could not read is not an informed notice, and a consent record
        // citing one is evidence of the wrong thing. Bodo is the group's real gap here.
        ConsentCaptureService.Result rejected = capture.capture(new CaptureSubmission(
                ENTITY, "lc-" + UUID.randomUUID(), Jurisdiction.IN, "brx", Channel.WEB, APP,
                CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, "lc", notice, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(code)),
                true, Instant.parse("2026-08-15T09:00:00Z"), "lc-" + UUID.randomUUID(), null,
                Map.of()));

        assertThat(rejected.isAccepted()).isFalse();
        assertThat(rejected.violations()).extracting(CaptureViolation::code)
                .contains(CaptureViolation.Code.NOTICE_LANGUAGE_UNAVAILABLE);
    }

    // -----------------------------------------------------------------------------------
    // Over HTTP — the roles are the security boundary
    // -----------------------------------------------------------------------------------

    @Test
    @DisplayName("publishing needs ADMIN; capture credentials and anonymous callers are refused")
    void publishingIsAdminOnly() {
        String notice = newNotice();
        String path = "/v1/admin/notices/" + notice + "/versions";
        Map<String, Object> body = Map.of(
                "jurisdiction", "IN", "materialChange", false,
                "withdrawalUri", "https://uds.example/withdraw",
                "rightsUri", "https://uds.example/rights",
                "grievanceUri", "https://uds.example/grievance");

        assertThat(rest.postForEntity(path, body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A capture credential lives on however many web servers render the consent banner. If it
        // could publish notices, compromising any one of them would let an attacker rewrite what
        // the group asks people to agree to.
        assertThat(rest.withBasicAuth("denave-web", "capture-secret")
                .postForEntity(path, body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> allowed = asAdmin().postForEntity(path, body, String.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(allowed.getBody()).contains("missingMandatoryLanguages");
    }

    @Test
    @DisplayName("over HTTP a purpose publish is 201 with its impact, and the engine sees it")
    void purposePublishOverHttp() {
        String code = nextCode("HTTP");
        Map<String, Object> body = Map.of(
                "name", "HTTP published purpose",
                "owner", "legal",
                "description", "Published through the API rather than a migration.",
                "legalBases", Map.of("IN", Map.of("legalBasis", "CONSENT")),
                "dataCategories", List.of("CONTACT_BUSINESS"),
                "channels", List.of("EMAIL"),
                "expiryPolicy", "NONE",
                "failureBehavior", "FAIL_CLOSED",
                "materialChange", false);

        ResponseEntity<String> response = asAdmin().postForEntity(
                "/v1/admin/purposes/" + code + "/versions", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).contains(code).contains("impact");
        // The controller refreshes after the transaction commits, so the purpose is decidable on
        // this instance by the time the caller has their response.
        assertThat(catalog.find(code)).isPresent();
    }

    @Test
    @DisplayName("over HTTP a missing assessment reference is a 400 with the explanation")
    void badRequestsAreExplainedRatherThanLeaked() {
        Map<String, Object> body = Map.of(
                "name", "Missing LIA",
                "description", "No assessment reference.",
                "legalBases", Map.of("UK", Map.of("legalBasis", "LEGITIMATE_INTEREST")),
                "dataCategories", List.of("CONTACT_BUSINESS"),
                "channels", List.of("EMAIL"),
                "expiryPolicy", "NONE",
                "failureBehavior", "FAIL_CLOSED",
                "materialChange", false);

        ResponseEntity<String> response = asAdmin().postForEntity(
                "/v1/admin/purposes/" + nextCode("HTTPLIA") + "/versions", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Legitimate Interests Assessment");
    }

    @Test
    @DisplayName("a rejected publish leaves nothing behind — not even the purpose row")
    void aRejectedPublishIsWhollyRolledBack() {
        String code = nextCode("ROLLBACK");
        PublishingService.NewVersion request = new PublishingService.NewVersion(
                code, "Test", "legal", "Test purpose",
                Map.of(Jurisdiction.IN,
                        new PurposeRegistryStore.BasisEntry(LegalBasis.CONSENT, null, null)),
                Set.of("CONTACT_BUSINESS"), Set.of(Channel.WEB), ExpiryPolicy.NONE, null,
                FailureBehavior.FAIL_CLOSED, "NOTICE_DOES_NOT_EXIST", false, false, false, false);

        assertThatThrownBy(() -> publishing.publishPurpose(request, "compliance-console"))
                .isInstanceOf(IllegalArgumentException.class);

        // A purpose row with no versions is not harmless: it satisfies purposeExists, so the next
        // legitimate publish would skip creating it and inherit whatever name the failed attempt
        // used.
        assertThat(purposes.purposeExists(code)).isFalse();
    }

    // -----------------------------------------------------------------------------------

    private TestRestTemplate asAdmin() {
        return rest.withBasicAuth("compliance-console", "admin-secret");
    }

    private static String nextCode(String label) {
        return "TEST_PUB_" + label + "_" + SEQUENCE.incrementAndGet();
    }

    /**
     * A notice of this test's own, with the Rule 3 language requirements copied onto it.
     *
     * <p>Per test rather than shared, so that publishing a version here never changes which
     * version another suite's assertions resolve to.
     */
    private String newNotice() {
        String noticeId = "NOTICE_TEST_PUB_" + SEQUENCE.incrementAndGet();
        jdbc.update("insert into notice (notice_id, entity_id, name) values (?, ?, ?)",
                noticeId, ENTITY, "Publishing test notice");
        jdbc.update("""
                insert into notice_language_requirement (notice_id, language_tag, mandatory,
                                                         rationale)
                select ?, language_tag, mandatory, rationale
                  from notice_language_requirement
                 where notice_id = 'NOTICE_DENAVE_B2B'
                """, noticeId);
        return noticeId;
    }

    /**
     * Publishes version 1 of a notice with an English translation.
     *
     * <p>The translation is not incidental. Since item 7 the capture validator refuses a
     * submission citing a notice version that exists in no language the subject was served in —
     * so a fixture that published the version and stopped would be capturing consent against a
     * notice nobody could have read, which is exactly what that check exists to prevent.
     */
    private void publishEnglishNotice(String noticeId) {
        NoticeStore.NoticeVersion version = publishing.publishNotice(noticeId, "IN", false,
                "https://uds.example/withdraw", "https://uds.example/rights",
                "https://uds.example/grievance", "compliance-console").version();
        publishing.addTranslation(noticeId, version.version(), "en", "Privacy notice",
                "How Denave uses business contact details.", "compliance-console");
    }

    /** One subject holding consent, so a blast radius has something to count. */
    private void grantConsent(String purposeCode, String noticeId) {
        String subject = "pub-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(
                ENTITY, subject, Jurisdiction.IN, "en", Channel.WEB, APP,
                CaptureMethod.CHECKBOX_OPT_IN, ActorType.SUBJECT, subject, noticeId, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately(purposeCode)),
                true, Instant.parse("2026-08-15T09:00:00Z"), "pub-" + subject, null, Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
    }

    private static PublishingService.NewVersion newVersion(String code, LegalBasis basis,
                                                           String assessmentRef,
                                                           boolean materialChange) {
        return newVersion(code, basis, assessmentRef, materialChange, null);
    }

    private static PublishingService.NewVersion newVersion(String code, LegalBasis basis,
                                                           String assessmentRef,
                                                           boolean materialChange,
                                                           String noticeId) {
        return new PublishingService.NewVersion(code, code + " name", "legal",
                "Published by PublishingIT.",
                Map.of(Jurisdiction.IN,
                        new PurposeRegistryStore.BasisEntry(basis, assessmentRef, null)),
                Set.of("CONTACT_BUSINESS"), Set.of(Channel.WEB, Channel.EMAIL),
                ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED, noticeId,
                false, false, materialChange, false);
    }
}
