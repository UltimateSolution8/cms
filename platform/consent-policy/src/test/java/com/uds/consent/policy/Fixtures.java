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
        return allModules(noOptOuts(), dltRegistry());
    }

    /**
     * The same set with the TRAI ports supplied.
     *
     * <p>Only {@code TccprModule} takes ports, and only it needs them: the ninety-day cooling-off
     * is keyed on when a subscriber last opted out, which no other regime asks about.
     */
    public static List<JurisdictionModule> allModules(PolicyPorts.OptOutHistory optOuts,
                                                      PolicyPorts.DltRegistry dlt) {
        List<JurisdictionModule> modules = new ArrayList<>(List.of(
                new DpdpModule(), new TccprModule(optOuts, dlt),
                new GdprModule(Jurisdiction.UK), new GdprModule(Jurisdiction.EU), new PipaModule(),
                new PdpaSingaporeModule(), new PdpaMalaysiaModule()));

        // One CcpaModule per US jurisdiction, exactly as the service wires them. Built from the
        // enum rather than listed, so a state added to Jurisdiction is covered by the golden suite
        // the moment it exists rather than the next time somebody remembers this file.
        for (Jurisdiction jurisdiction : Jurisdiction.values()) {
            if (jurisdiction.isUnitedStates()) {
                modules.add(new CcpaModule(jurisdiction));
            }
        }
        return List.copyOf(modules);
    }

    /** Nobody has ever opted out. The default, so the cooling-off never fires unasked. */
    public static PolicyPorts.OptOutHistory noOptOuts() {
        return (entityId, subjectId, channel) -> Optional.empty();
    }

    /** Everyone opted out at {@code when}, which is how the cooling-off cases are driven. */
    public static PolicyPorts.OptOutHistory optedOutAt(Instant when) {
        return (entityId, subjectId, channel) -> Optional.of(when);
    }

    /**
     * Denave's DLT registrations, mirroring the seed.
     *
     * <p>The promotional template is registered and the transactional one is not, because both
     * states matter: a named template is what a sender puts on the wire, and an unregistered one
     * is a campaign that the operator will refuse — and the platform should say so before the
     * campaign rather than during it.
     */
    public static PolicyPorts.DltRegistry dltRegistry() {
        Map<String, PolicyPorts.DltRegistration> registrations = Map.of(
                "MKT_OUTBOUND_SMS", new PolicyPorts.DltRegistration("DENAVE", "P", "140",
                        "1107160000000012345", true),
                "TXN_SERVICE_SMS", new PolicyPorts.DltRegistration("DENSRV", "S", "1600",
                        "PENDING_REGISTRATION", false));
        return (entityId, purposeCode) ->
                Optional.ofNullable(registrations.get(purposeCode));
    }

    /** The application ids the seeded registry below knows about. */
    public static final String APP = "DENAVE_WEB";
    public static final String APP_OTHER_ENTITY = "MATRIX_BGV";
    public static final String APP_RETIRED = "DENAVE_LEGACY_FORM";

    /** Athena's dialer: owned by Athena, scoped to act for Denave. The shared-surface case. */
    public static final String APP_SHARED = "ATHENA_DIALER";

    /**
     * The registry of surfaces, mirroring the shape of the seeded one.
     *
     * <p>Includes an inactive surface and one belonging to a different entity, because those are
     * the two cases the check exists for and a fixture that only holds valid rows would let the
     * check pass while doing nothing.
     */
    public static PolicyPorts.ApplicationRegistry applications() {
        Map<String, PolicyPorts.RegisteredApplication> registry = new HashMap<>();
        registry.put(APP, new PolicyPorts.RegisteredApplication(
                APP, ENTITY, "denave.com", "WEB", "PRODUCTION", true));
        registry.put(APP_OTHER_ENTITY, new PolicyPorts.RegisteredApplication(
                APP_OTHER_ENTITY, "MATRIX", "BGV workflow", "BACKEND", "PRODUCTION", true));
        registry.put(APP_RETIRED, new PolicyPorts.RegisteredApplication(
                APP_RETIRED, ENTITY, "Legacy web form", "WEB", "PRODUCTION", false));
        // Owned by Athena, scoped to Denave. Without a row of this shape the entity check would
        // look correct while denying the one surface in the seed that legitimately crosses.
        registry.put(APP_SHARED, new PolicyPorts.RegisteredApplication(
                APP_SHARED, "ATHENA", "Outbound dialer", "BACKEND", "PRODUCTION", true,
                Set.of("ATHENA", ENTITY)));
        return applicationId -> Optional.ofNullable(registry.get(applicationId));
    }

    /** The notice the fixtures cite, published to version 1 in English and Hindi. */
    public static final String NOTICE = "NOTICE_TEST";

    /**
     * A notice registry with exactly one published version.
     *
     * <p>Deliberately narrow. The check exists to catch a citation of something that was never
     * published, so a fixture answering yes to everything would let it pass while doing nothing —
     * which is precisely the state the platform was in before the check existed.
     */
    public static PolicyPorts.NoticeLookup notices() {
        // English, Hindi and Korean — the languages the fixtures' own submissions are served in.
        // Bodo is deliberately absent: it is the group's real translation gap and the case the
        // language check exists to catch.
        Map<String, Set<String>> published = Map.of(NOTICE + ":1", Set.of("en", "hi", "ko"));
        return new PolicyPorts.NoticeLookup() {
            @Override
            public boolean exists(String noticeId, int version) {
                return published.containsKey(noticeId + ":" + version);
            }

            @Override
            public boolean hasTranslation(String noticeId, int version, String languageTag) {
                return published.getOrDefault(noticeId + ":" + version, Set.of())
                        .contains(languageTag);
            }
        };
    }

    /** A processor whose agreement covers outbound calling, and nothing else. */
    public static final String VENDOR = "ATHENA_DIALER";

    /** A processor with no authorisation for anything — a terminated or mis-scoped agreement. */
    public static final String VENDOR_UNAUTHORISED = "VENDOR_NO_DPA";

    /**
     * Vendor authorisation, scoped per purpose exactly as {@code vendor_purpose} is.
     *
     * <p>Scoped rather than boolean because that is where the failure lives: a processor engaged
     * for telemarketing being handed the same records for profiling is not an unknown vendor, it
     * is a known one exceeding its agreement, and a registry that only knew "is this a vendor"
     * would answer yes.
     */
    public static PolicyPorts.VendorAuthorisation vendors() {
        Map<String, Set<String>> authorised = Map.of(
                VENDOR, Set.of("MKT_OUTBOUND_CALL", "TXN_SERVICE_SMS"),
                VENDOR_UNAUTHORISED, Set.of());
        return (vendorId, purposeCode) ->
                authorised.getOrDefault(vendorId, Set.of()).contains(purposeCode);
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

        /** A Global Privacy Control signal, which binds as a statutory opt-out where mandated. */
        public Suppressions universalOptOut(Channel channel) {
            hits.put(channel, PolicyPorts.Hit.of(SuppressionSource.UNIVERSAL_OPT_OUT,
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
                (entityId, subjectId, at) -> isChild,
                applications(), vendors(),
                allModules(), POLICY_VERSION);
    }

    /**
     * An engine whose view of the subject's age depends on <em>when</em> it is asked.
     *
     * <p>The flat {@code boolean isChild} above cannot express the bug this exists to catch: a
     * subject who was a minor when the decision was taken and is an adult by the time anybody
     * replays it. This lookup answers by comparing the request instant against a birthday, which is
     * what an age assertion history amounts to.
     *
     * @param adultFrom the instant the subject stopped being a child. A decision at or after it
     *                  sees an adult; one before it sees a child
     */
    public static PolicyEngine engineWithSubjectAdultFrom(Catalog catalog, Artefacts artefacts,
                                                          Instant adultFrom) {
        return new PolicyEngine(catalog, artefacts, new Suppressions(),
                (entityId, subjectId) -> true,
                (entityId, subjectId, at) -> at.isBefore(adultFrom),
                applications(), vendors(),
                allModules(), POLICY_VERSION);
    }

    /** Engine with nothing suppressed, provenance clean, subject an adult. */
    public static PolicyEngine engine(Catalog catalog, Artefacts artefacts) {
        return engine(catalog, artefacts, new Suppressions(), true, false);
    }

    /**
     * An engine that knows when the subject last opted out.
     *
     * <p>Separate builder because the cooling-off is the one rule keyed on a fact the suppression
     * lookup deliberately does not carry: an opt-out that has lapsed, or that was scoped to one
     * campaign, no longer suppresses while the ninety days it started are still running.
     */
    public static PolicyEngine engineWithOptOut(Catalog catalog, Artefacts artefacts,
                                                Instant lastOptOut) {
        return new PolicyEngine(catalog, artefacts, new Suppressions(),
                (entityId, subjectId) -> true,
                (entityId, subjectId, at) -> false,
                applications(), vendors(),
                allModules(optedOutAt(lastOptOut), dltRegistry()), POLICY_VERSION);
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

    /** Promotional SMS — the traffic the DLT registry's promotional header carries. */
    public static PurposeDefinition promotionalSms() {
        return purpose("MKT_OUTBOUND_SMS", Map.of(Jurisdiction.IN, LegalBasis.CONSENT),
                Set.of(Channel.SMS), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED,
                Set.of("CONTACT_PERSONAL"), false, false, false);
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

    /**
     * Biometric attendance: a legitimate use in India, sensitive data in Malaysia.
     *
     * <p>Note that the category is declared biometric explicitly rather than being recognised by
     * the shape of its code. That mirrors the registry, where {@code data_category.biometric} is a
     * column — and it means a fixture named {@code FINGERPRINT_TEMPLATE} would behave identically,
     * which is the property the production change exists to give.
     */
    public static PurposeDefinition biometricAttendance() {
        return sensitivePurpose("HR_ATTENDANCE_BIOMETRIC", Map.of(
                        Jurisdiction.IN, LegalBasis.LEGITIMATE_USE_EMPLOYMENT,
                        Jurisdiction.MY, LegalBasis.CONSENT),
                Set.of(Channel.KIOSK), ExpiryPolicy.NONE, null, FailureBehavior.FAIL_CLOSED,
                Set.of("BIOMETRIC_FINGERPRINT"), Set.of("BIOMETRIC_FINGERPRINT"),
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
        return sensitivePurpose(code, bases, channels, expiryPolicy, expiryDays, failure,
                dataCategories, Set.of(), Set.of(), separateConsent, children, retired);
    }

    /** As {@link #purpose} but with the sensitive and biometric subsets stated. */
    public static PurposeDefinition sensitivePurpose(String code,
                                                     Map<Jurisdiction, LegalBasis> bases,
                                                     Set<Channel> channels,
                                                     ExpiryPolicy expiryPolicy, Integer expiryDays,
                                                     FailureBehavior failure,
                                                     Set<String> dataCategories,
                                                     Set<String> sensitiveCategories,
                                                     Set<String> biometricCategories,
                                                     boolean separateConsent, boolean children,
                                                     boolean retired) {
        return new PurposeDefinition(code, 1, code, code + " description",
                new HashMap<>(bases), new HashSet<>(dataCategories),
                new HashSet<>(sensitiveCategories), new HashSet<>(biometricCategories),
                new HashSet<>(channels), expiryPolicy, expiryDays, failure, "NOTICE_TEST",
                separateConsent, children, retired);
    }

    /** A catalog seeded with the full set above. */
    public static Catalog fullCatalog() {
        Catalog catalog = new Catalog();
        List<PurposeDefinition> purposes = new ArrayList<>(List.of(
                promotionalCall(), promotionalSms(), transactionalSms(), relationship(),
                employmentAdmin(),
                biometricAttendance(), criminalRecordCheck(), strictlyNecessary(), advertising(),
                retired()));
        purposes.forEach(catalog::with);
        return catalog;
    }
}
