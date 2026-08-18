package com.uds.consent.service;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.ledger.service.BlastRadiusService;
import com.uds.consent.ledger.store.AdminAuditStore;
import com.uds.consent.ledger.store.NoticeStore;
import com.uds.consent.ledger.store.PurposeRegistryStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes notices, translations and purpose versions.
 *
 * <p>Until this existed, every taxonomy change was a Flyway migration and a redeploy — which put
 * ninety-five outstanding translations behind ninety-five deployments and made the operations
 * manual's instruction to "publish a new notice version" something no one could actually do.
 *
 * <p><strong>Why this does not weaken the evidence plane.</strong> {@code V2__append_only_guards}
 * revokes UPDATE, DELETE and TRUNCATE on {@code notice_version}, {@code notice_translation} and
 * {@code purpose_version} from the application role and leaves INSERT alone. Every write here is an
 * insert. Publishing appends a new version; it never rewrites the one somebody consented against.
 *
 * <p>This class exists to enforce the four things the database cannot:
 * <ul>
 *   <li><strong>The version number is computed, never accepted.</strong> A caller who picks their
 *       own number eventually picks one that exists, and the unique constraint reports that as a
 *       failure on a publish legal believes succeeded.</li>
 *   <li><strong>A legitimate-interest basis with no assessment reference is refused with a reason.
 *       </strong> {@code ck_lia_present} already refuses it; a constraint name in a 500 tells the
 *       compliance officer nothing about the LIA they have not written.</li>
 *   <li><strong>The blast radius is computed before the insert and returned with the response.</strong>
 *       The operations manual instructs running it first. This makes not running it impossible.</li>
 *   <li><strong>{@code published_by} is populated.</strong> The column has existed since V1 and has
 *       never been written to. An immutable record of a change nobody can be attributed with is
 *       half a control.</li>
 * </ul>
 */
@Service
public class PublishingService {

    private final NoticeStore notices;
    private final PurposeRegistryStore purposes;
    private final BlastRadiusService blastRadius;
    private final AdminAuditStore audit;

    public PublishingService(NoticeStore notices, PurposeRegistryStore purposes,
                             BlastRadiusService blastRadius, AdminAuditStore audit) {
        this.notices = notices;
        this.purposes = purposes;
        this.blastRadius = blastRadius;
        this.audit = audit;
    }

    /**
     * Publishes the next version of a notice.
     *
     * <p>The impact is computed against the version about to be created, with the material-change
     * judgement passed in rather than read back — before the insert there is no row to read it
     * from, and the whole point of the calculation is to be available while there is still a choice
     * about whether to publish.
     */
    @Transactional
    public PublishedNotice publishNotice(String noticeId, String jurisdiction,
                                         boolean materialChange, String withdrawalUri,
                                         String rightsUri, String grievanceUri, String actor) {
        String entityId = notices.entityOf(noticeId)
                .orElseThrow(() -> new IllegalArgumentException("notice " + noticeId
                        + " is not registered; register the notice against its entity before "
                        + "publishing a version"));
        int version = notices.nextVersion(noticeId);

        List<BlastRadiusService.Impact> impact =
                blastRadius.forNoticeChange(noticeId, version, materialChange);

        NoticeStore.NoticeVersion published = notices.publishVersion(noticeId, version, jurisdiction,
                materialChange, withdrawalUri, rightsUri, grievanceUri, actor);

        audit.record(actor, "NOTICE_VERSION_PUBLISHED", entityId, "notice_version",
                noticeId + ":" + version,
                Map.of("jurisdiction", jurisdiction,
                        "materialChange", String.valueOf(materialChange),
                        "affectedPurposes", String.valueOf(impact.size())));

        return new PublishedNotice(published, impact,
                notices.missingMandatoryLanguages(noticeId, published.id()));
    }

    /**
     * Adds a translation to a published notice version.
     *
     * <p>Not a version bump. The version is the notice's content and legal shape; a translation is
     * the same content in another language, and bumping the version for one would force re-consent
     * on subjects whose language did not change. That is the distinction Rule 3 turns on.
     */
    @Transactional
    public NoticeStore.Translation addTranslation(String noticeId, int version, String languageTag,
                                                  String title, String body, String actor) {
        NoticeStore.NoticeVersion target = notices.findVersion(noticeId, version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "notice " + noticeId + " has no version " + version));

        if (notices.findTranslation(target.id(), languageTag).isPresent()) {
            // Refused rather than replaced. Somebody may have read the existing text and consented
            // against it; overwriting it would destroy the only copy of what they were shown.
            throw new IllegalArgumentException("notice " + noticeId + " version " + version
                    + " already has a " + languageTag + " translation; publish a new version to "
                    + "change text that has been shown to anyone");
        }

        notices.addTranslation(target.id(), languageTag, title, body);
        audit.record(actor, "NOTICE_TRANSLATION_ADDED", notices.entityOf(noticeId).orElse(null),
                "notice_translation",
                noticeId + ":" + version + ":" + languageTag,
                Map.of("languageTag", languageTag));
        return new NoticeStore.Translation(languageTag, title, body);
    }

    /**
     * Publishes the next version of a purpose, creating the purpose itself if it is new.
     *
     * <p>Validation happens before the insert and in this order deliberately: the checks that can
     * be explained come first, so a caller sees "no legitimate interests assessment for GB" rather
     * than a constraint violation the database raises at the same moment for the same reason.
     */
    @Transactional
    public PublishedPurpose publishPurpose(NewVersion request, String actor) {
        validate(request);

        boolean isNew = !purposes.purposeExists(request.purposeCode());
        if (isNew) {
            purposes.createPurpose(request.purposeCode(), request.name(), request.owner());
        }
        int version = purposes.nextVersion(request.purposeCode());

        // Before the insert, so the numbers describe the population holding consent against the
        // versions this one supersedes rather than a population that already includes it.
        BlastRadiusService.Impact impact =
                blastRadius.forPurposeChange(request.purposeCode(), version, request.materialChange());

        purposes.publishVersion(new PurposeRegistryStore.NewPurposeVersion(
                request.purposeCode(), version, request.name(), request.description(),
                request.legalBases(), request.dataCategories(), request.channels(),
                request.expiryPolicy(), request.expiryDays(), request.failureBehavior(),
                request.noticeId(), request.requiresSeparateConsent(),
                request.permittedForChildren(), request.materialChange(), request.retired()), actor);

        audit.record(actor, "PURPOSE_VERSION_PUBLISHED", null, "purpose_version",
                request.purposeCode() + ":" + version,
                Map.of("materialChange", String.valueOf(request.materialChange()),
                        "action", impact.action().name(),
                        "standingConsentAffected", String.valueOf(impact.standingConsentAffected()),
                        "newPurpose", String.valueOf(isNew)));

        return new PublishedPurpose(request.purposeCode(), version, impact);
    }

    private void validate(NewVersion request) {
        if (request.legalBases().isEmpty()) {
            // A purpose with no jurisdiction rows is denied everywhere. That is a legitimate state
            // to arrive at by retirement, and never a legitimate state to publish into — it would
            // read as a deliberate prohibition rather than as the omission it is.
            throw new IllegalArgumentException("purpose " + request.purposeCode() + " has no legal "
                    + "basis for any jurisdiction; such a purpose is denied everywhere, which is "
                    + "achieved by retiring it rather than by publishing it empty");
        }

        request.legalBases().forEach((jurisdiction, entry) -> {
            if (entry.legalBasis() == LegalBasis.LEGITIMATE_INTEREST
                    && (entry.assessmentRef() == null || entry.assessmentRef().isBlank())) {
                throw new IllegalArgumentException("legal basis LEGITIMATE_INTEREST for "
                        + jurisdiction + " requires an assessment reference: GDPR Art.6(1)(f) is "
                        + "only available with a documented Legitimate Interests Assessment, and "
                        + "the reference is what makes it producible on request");
            }
        });

        if (request.expiryPolicy() == ExpiryPolicy.FIXED_DAYS
                && (request.expiryDays() == null || request.expiryDays() <= 0)) {
            throw new IllegalArgumentException("expiry policy FIXED_DAYS requires a positive "
                    + "expiryDays");
        }

        List<String> unknown = purposes.unknownDataCategories(request.dataCategories());
        if (!unknown.isEmpty()) {
            // Refused rather than inserted, and not only because of the foreign key. The sensitive
            // and biometric flags live on data_category, so a category invented at publish time
            // would be processed as ordinary personal data — which under Malaysia's PDPA 2024 is
            // the difference between lawful and not.
            throw new IllegalArgumentException("unknown data categories " + unknown
                    + "; add them to the data_category vocabulary with their sensitive and "
                    + "biometric flags before referencing them");
        }

        if (request.noticeId() != null && !notices.noticeExists(request.noticeId())) {
            throw new IllegalArgumentException("notice " + request.noticeId() + " is not registered");
        }
    }

    /** What a caller submits. The version number is absent by design — this service assigns it. */
    public record NewVersion(String purposeCode, String name, String owner, String description,
                             Map<Jurisdiction, PurposeRegistryStore.BasisEntry> legalBases,
                             Set<String> dataCategories, Set<Channel> channels,
                             ExpiryPolicy expiryPolicy, Integer expiryDays,
                             FailureBehavior failureBehavior, String noticeId,
                             boolean requiresSeparateConsent, boolean permittedForChildren,
                             boolean materialChange, boolean retired) {

        public NewVersion {
            legalBases = legalBases == null ? Map.of() : Map.copyOf(legalBases);
            dataCategories = dataCategories == null ? Set.of() : Set.copyOf(dataCategories);
            channels = channels == null ? Set.of() : Set.copyOf(channels);
        }
    }

    /**
     * @param impact             who was holding consent against a superseded version, and what has
     *                           to happen to them. Returned with the publish rather than left to be
     *                           asked for separately
     * @param missingMandatoryLanguages mandatory languages this version has no translation for,
     *                           returned immediately because a version published with gaps is one
     *                           whose gaps someone has to be told about while they still remember
     *                           publishing it
     */
    public record PublishedNotice(NoticeStore.NoticeVersion version,
                                  List<BlastRadiusService.Impact> impact,
                                  List<String> missingMandatoryLanguages) {
    }

    public record PublishedPurpose(String purposeCode, int version,
                                   BlastRadiusService.Impact impact) {
    }
}
