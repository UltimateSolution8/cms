package com.uds.consent.policy;

import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.ExpiryPolicy;
import com.uds.consent.core.model.FailureBehavior;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.policy.jurisdiction.CcpaModule;
import com.uds.consent.policy.jurisdiction.DpdpModule;
import com.uds.consent.policy.jurisdiction.GdprModule;
import com.uds.consent.policy.jurisdiction.JurisdictionModule;
import com.uds.consent.policy.jurisdiction.PdpaMalaysiaModule;
import com.uds.consent.policy.jurisdiction.PdpaSingaporeModule;
import com.uds.consent.policy.jurisdiction.PipaModule;
import com.uds.consent.policy.jurisdiction.TccprModule;
import com.uds.consent.policy.port.PolicyPorts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory stand-ins for everything the decision engine reads.
 *
 * <p>These exist so the golden suite can enumerate hundreds of cases across six jurisdictions in
 * milliseconds. A decision suite that needs a database gets run before releases; one that runs in
 * a second gets run on every commit, and consent decisions are precisely the code where a
 * regression is silent — a wrongly permissive answer produces no error, just a call to someone
 * who asked not to be called.
 */
public final class Fixtures {

    public static final String ENTITY = "DENAVE_IN";
    public static final String SUBJECT = "subject-1";
    public static final String POLICY_VERSION = "test-policy-1";
    public static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    private Fixtures() {
    }

    /** Every jurisdiction module, wired as the running service wires them. */
    public static List<JurisdictionModule> allModules() {
        return List.of(new DpdpModule(), new TccprModule(), new GdprModule(Jurisdiction.UK),
                new GdprModule(Jurisdiction.EU), new PipaModule(), new PdpaSingaporeModule(),
                new PdpaMalaysiaModule(), new CcpaModule());
    }

    /** Mutable purpose registry. */
    public static class Catalog implements PolicyPorts.PurposeCatalog {

        private final Map<String, PurposeDefinition> purposes = new HashMap<>();

        public Catalog with(PurposeDefinition purpose) {
            purposes.put(purpose.code(), purpose);
            return this;
        }

        @Override
        public Optional<PurposeDefinition> find(String purposeCode) {
            return Optional.ofNullable(purposes.get(purposeCode));
        }

        @Override
        public List<PurposeDefinition> all() {
            return List.copyOf(purposes.values());
        }
    }

    /** Mutable consent state. */
    public static class Artefacts implements PolicyPorts.ArtefactLookup {

        private final Map<String, ConsentArtefact> byKey = new HashMap<>();

        public Artefacts with(String purposeCode, ConsentStatus status, Instant expiresAt) {
            return with(purposeCode, status, expiresAt, LegalBasis.CONSENT);
        }

        public Artefacts with(String purposeCode, ConsentStatus status, Instant expiresAt,
                              LegalBasis basis) {
            byKey.put(key(ENTITY, SUBJECT, purposeCode), new ConsentArtefact(ENTITY, SUBJECT,
                    purposeCode, 1, status, basis, "NOTICE_TEST", 1, "en",
                    CaptureMethod.CLICK_THROUGH, Channel.WEB, "APP", Jurisdiction.IN,
                    NOW.minusSeconds(60), expiresAt, null, NOW.minusSeconds(60), 1L, "hash"));
            return this;
        }

        @Override
        public Optional<ConsentArtefact> find(String entityId, String subjectId, String purposeCode) {
            return Optional.ofNullable(byKey.get(key(entityId, subjectId, purposeCode)));
        }

        private static String key(String entityId, String subjectId, String purposeCode) {
            return entityId + '|' + subjectId + '|' + purposeCode;
        }
    }

    /** Mutable suppression state, keyed by channel. */
    public static class Suppressions implements PolicyPorts.SuppressionLookup {

        private final Map<Channel, PolicyPorts.Hit> hits = new HashMap<>();

        public Suppressions statutory(Channel channel) {
            hits.put(channel, PolicyPorts.Hit.of(SuppressionSource.NCPR_INDIA,
                    SuppressionScope.GLOBAL));
            return this;
        }

        public Suppressions optOut(Channel channel) {
            hits.put(channel, PolicyPorts.Hit.of(SuppressionSource.INBOUND_OPT_OUT,
                    SuppressionScope.ENTITY));
            return this;
        }

        @Override
        public Optional<PolicyPorts.Hit> find(String entityId, String subjectId, Channel channel,
                                              String clientId, String campaignId, Instant at) {
            return Optional.ofNullable(hits.get(channel));
        }
    }

    /** Builds an engine over the supplied state. */
    public static PolicyEngine engine(Catalog catalog, Artefacts artefacts,
                                      Suppressions suppressions, boolean contactable,
                                      boolean isChild) {
        return new PolicyEngine(catalog, artefacts, suppressions,
                (entityId, subjectId) -> contactable,
                subjectId -> isChild,
                allModules(), POLICY_VERSION);
    }

    /** Engine with nothing suppressed, provenance clean, subject an adult. */
    public static PolicyEngine engine(Catalog catalog, Artefacts artefacts) {
        return engine(catalog, artefacts, new Suppressions(), true, false);
    }

    // ---------------------------------------------------------------------------------------
    // Purposes mirroring the seeded taxonomy closely enough to be meaningful.
    // ---------------------------------------------------------------------------------------

    /** Promotional calling: consent in India, legitimate interest in the UK. */
    public static PurposeDefinition promotionalCall() {
        return purpose("MKT_OUTBOUND_CALL", Map.of(
                        Jurisdiction.IN, LegalBasis.CONSENT,
                        Jurisdiction.UK, LegalBasis.LEGITIMATE_INTEREST,
                        Jurisdiction.SG, LegalBasis.CONSENT,
                        Jurisdiction.KR, LegalBasis.CONSENT),
                Set.of(Channel.VOICE_CALL), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED,
                Set.of("CONTACT_BUSINESS"), false, false, false);
    }

    /** The TRAI seven-day transactional window. */
    public static PurposeDefinition transactionalSms() {
        return purpose("TXN_SERVICE_SMS", Map.of(Jurisdiction.IN, LegalBasis.CONSENT),
                Set.of(Channel.SMS), ExpiryPolicy.TRAI_TRANSACTIONAL_7D, null,
                FailureBehavior.FAIL_CLOSED, Set.of("CONTACT_PERSONAL"), false, false, false);
    }

    /** Consent inferred from a live contract, valid only while it subsists. */
    public static PurposeDefinition relationship() {
        return purpose("SALES_RELATIONSHIP", Map.of(Jurisdiction.IN, LegalBasis.INFERRED_CONSENT),
                Set.of(Channel.VOICE_CALL, Channel.EMAIL), ExpiryPolicy.CONTRACT_LIFETIME, null,
                FailureBehavior.FAIL_CLOSED, Set.of("CONTACT_BUSINESS"), false, false, false);
    }

    /** Employment administration: a legitimate use, needing no consent record. */
    public static PurposeDefinition employmentAdmin() {
        return purpose("HR_EMPLOYMENT_ADMIN",
                Map.of(Jurisdiction.IN, LegalBasis.LEGITIMATE_USE_EMPLOYMENT),
                Set.of(), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_OPEN,
                Set.of("EMPLOYMENT"), false, false, false);
    }

    /** Biometric attendance: a legitimate use in India, sensitive data in Malaysia. */
    public static PurposeDefinition biometricAttendance() {
        return purpose("HR_ATTENDANCE_BIOMETRIC", Map.of(
                        Jurisdiction.IN, LegalBasis.LEGITIMATE_USE_EMPLOYMENT,
                        Jurisdiction.MY, LegalBasis.CONSENT),
                Set.of(Channel.KIOSK), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED,
                Set.of("BIOMETRIC_FINGERPRINT"), false, false, false);
    }

    /** A criminal record check: sought separately, never bundled. */
    public static PurposeDefinition criminalRecordCheck() {
        return purpose("BGV_CRIMINAL_RECORD", Map.of(
                        Jurisdiction.IN, LegalBasis.CONSENT,
                        Jurisdiction.KR, LegalBasis.CONSENT),
                Set.of(), ExpiryPolicy.FIXED_DAYS, 180, FailureBehavior.FAIL_CLOSED,
                Set.of("CRIMINAL_RECORD"), true, false, false);
    }

    /** Strictly necessary site function: permitted for children, fails open. */
    public static PurposeDefinition strictlyNecessary() {
        return purpose("WEB_STRICTLY_NECESSARY",
                Map.of(Jurisdiction.IN, LegalBasis.LEGITIMATE_USE_VOLUNTARY,
                        Jurisdiction.UK, LegalBasis.CONTRACT_PERFORMANCE),
                Set.of(Channel.WEB), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_OPEN,
                Set.of("DEVICE_TELEMETRY"), false, true, false);
    }

    /** Advertising: never permitted for children. */
    public static PurposeDefinition advertising() {
        return purpose("WEB_ADVERTISING", Map.of(Jurisdiction.IN, LegalBasis.CONSENT),
                Set.of(Channel.WEB), ExpiryPolicy.FIXED_DAYS, 365, FailureBehavior.FAIL_CLOSED,
                Set.of("WEB_BEHAVIOUR"), false, false, false);
    }

    public static PurposeDefinition retired() {
        return purpose("MKT_LEGACY_BLAST", Map.of(Jurisdiction.IN, LegalBasis.CONSENT),
                Set.of(Channel.EMAIL), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED,
                Set.of("CONTACT_BUSINESS"), false, false, true);
    }

    public static PurposeDefinition purpose(String code, Map<Jurisdiction, LegalBasis> bases,
                                            Set<Channel> channels, ExpiryPolicy expiryPolicy,
                                            Integer expiryDays, FailureBehavior failure,
                                            Set<String> dataCategories, boolean separateConsent,
                                            boolean children, boolean retired) {
        return new PurposeDefinition(code, 1, code, code + " description",
                new HashMap<>(bases), new HashSet<>(dataCategories), new HashSet<>(channels),
                expiryPolicy, expiryDays, failure, "NOTICE_TEST", separateConsent, children,
                retired);
    }

    /** A catalog seeded with the full set above. */
    public static Catalog fullCatalog() {
        Catalog catalog = new Catalog();
        List<PurposeDefinition> purposes = new ArrayList<>(List.of(
                promotionalCall(), transactionalSms(), relationship(), employmentAdmin(),
                biometricAttendance(), criminalRecordCheck(), strictlyNecessary(), advertising(),
                retired()));
        purposes.forEach(catalog::with);
        return catalog;
    }
}
