package com.uds.consent.ledger.store;

import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.ConsentStatus;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.LegalBasis;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.ledger.service.BlastRadiusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stores, against a real PostgreSQL.
 *
 * <p>The service suite reaches these transitively, which means a bug in one of them surfaces as a
 * confusing failure two layers up. These cases put the assertions where the behaviour is.
 *
 * <p>Everything checked here is a property of the SQL rather than of the Java around it —
 * precedence between overlapping suppression scopes, the effective-date windows, {@code jsonb}
 * round-tripping, the outbox's claim semantics. An in-memory substitute would test a translation
 * of these queries, not the queries.
 */
class LedgerStoresIT extends StoreTestBase {

    private static final String ENTITY = "DENAVE_IN";
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Nested
    @DisplayName("SubjectStore — identifiers in, privacy-minimal references out")
    class Subjects {

        private final SubjectStore store = new SubjectStore(dataSource());

        @Test
        @DisplayName("the same identifier hash always resolves to the same subject")
        void resolutionIsStable() {
            String hash = hash();

            String first = store.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash);
            String second = store.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash);

            assertThat(second).isEqualTo(first);
            assertThat(store.resolve(ENTITY, IdentifierType.PHONE, hash)).contains(first);
        }

        @Test
        @DisplayName("an unknown identifier resolves to nothing rather than creating a subject")
        void resolveDoesNotCreate() {
            // The scrub path calls this. Checking whether a number may be contacted must not
            // itself add that number to the platform's records, or the act of complying would
            // grow the database it exists to constrain.
            assertThat(store.resolve(ENTITY, IdentifierType.PHONE, hash())).isEmpty();
        }

        @Test
        @DisplayName("the same hash under two identifier types is two different subjects")
        void identifierTypeIsPartOfTheKey() {
            String hash = hash();

            String byPhone = store.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash);
            String byEmail = store.resolveOrCreate(ENTITY, IdentifierType.EMAIL, hash);

            assertThat(byEmail).isNotEqualTo(byPhone);
        }

        @Test
        @DisplayName("a second identifier can be linked to a subject already known by another")
        void identifiersCanBeLinked() {
            String phoneHash = hash();
            String emailHash = hash();
            String subject = store.resolveOrCreate(ENTITY, IdentifierType.PHONE, phoneHash);

            store.linkIdentifier(ENTITY, subject, IdentifierType.EMAIL, emailHash);

            assertThat(store.resolve(ENTITY, IdentifierType.EMAIL, emailHash)).contains(subject);
            assertThat(store.identifiersFor(ENTITY, subject)).hasSize(2);
        }

        @Test
        @DisplayName("linking the same identifier twice is not an error")
        void linkingIsIdempotent() {
            String subject = store.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash());
            String emailHash = hash();

            store.linkIdentifier(ENTITY, subject, IdentifierType.EMAIL, emailHash);
            store.linkIdentifier(ENTITY, subject, IdentifierType.EMAIL, emailHash);

            assertThat(store.identifiersFor(ENTITY, subject)).hasSize(2);
        }

        @Test
        @DisplayName("an unknown subject is not a child, and a marked one is")
        void childFlagDefaultsToFalse() {
            // Defaults to false for an unknown subject, which is why capture surfaces that could
            // plausibly reach children must declare the age band rather than relying on this.
            assertThat(store.isChild("subject-that-does-not-exist")).isFalse();

            String subject = store.resolveOrCreate(ENTITY, IdentifierType.PHONE, hash());
            assertThat(store.isChild(subject)).isFalse();

            store.markChild(subject, true);
            assertThat(store.isChild(subject)).isTrue();
        }
    }

    @Nested
    @DisplayName("SuppressionStore — which do-not-contact entry wins")
    class Suppressions {

        private final SuppressionStore store = new SuppressionStore(dataSource());

        @Test
        @DisplayName("a statutory global entry applies without any entity of its own")
        void statutoryEntriesAreGlobal() {
            String hash = hash();
            store.addStatutoryBatch(SuppressionSource.NCPR_INDIA, Channel.VOICE_CALL,
                    IdentifierType.PHONE, List.of(hash), NOW.minus(1, ChronoUnit.DAYS), "loader");

            Optional<SuppressionStore.Hit> hit = store.findForIdentifier(ENTITY,
                    IdentifierType.PHONE, hash, Channel.VOICE_CALL, null, null, NOW);

            assertThat(hit).get().satisfies(found -> {
                assertThat(found.source()).isEqualTo(SuppressionSource.NCPR_INDIA);
                assertThat(found.scope()).isEqualTo(SuppressionScope.GLOBAL);
            });
        }

        @Test
        @DisplayName("suppression is per channel: an email opt-out does not stop a call")
        void suppressionIsChannelSpecific() {
            String hash = hash();
            store.add(ENTITY, SuppressionScope.ENTITY, SuppressionSource.INBOUND_OPT_OUT,
                    Channel.EMAIL, IdentifierType.EMAIL, hash, null, null, null,
                    NOW.minus(1, ChronoUnit.DAYS), null, "asked to stop emailing", "tester");

            assertThat(store.findForIdentifier(ENTITY, IdentifierType.EMAIL, hash, Channel.EMAIL,
                    null, null, NOW)).isPresent();
            assertThat(store.findForIdentifier(ENTITY, IdentifierType.EMAIL, hash,
                    Channel.VOICE_CALL, null, null, NOW)).isEmpty();
        }

        @Test
        @DisplayName("an entry that has not started yet does not suppress")
        void futureEntriesDoNotApplyYet() {
            String hash = hash();
            store.add(ENTITY, SuppressionScope.ENTITY, SuppressionSource.INBOUND_OPT_OUT,
                    Channel.SMS, IdentifierType.PHONE, hash, null, null, null,
                    NOW.plus(2, ChronoUnit.DAYS), null, "scheduled", "tester");

            assertThat(store.findForIdentifier(ENTITY, IdentifierType.PHONE, hash, Channel.SMS,
                    null, null, NOW)).isEmpty();
            assertThat(store.findForIdentifier(ENTITY, IdentifierType.PHONE, hash, Channel.SMS,
                    null, null, NOW.plus(3, ChronoUnit.DAYS))).isPresent();
        }

        @Test
        @DisplayName("an expired entry stops suppressing")
        void expiredEntriesLapse() {
            String hash = hash();
            store.add(ENTITY, SuppressionScope.ENTITY, SuppressionSource.MANUAL, Channel.SMS,
                    IdentifierType.PHONE, hash, null, null, null,
                    NOW.minus(10, ChronoUnit.DAYS), NOW.minus(1, ChronoUnit.DAYS),
                    "temporary hold", "tester");

            assertThat(store.findForIdentifier(ENTITY, IdentifierType.PHONE, hash, Channel.SMS,
                    null, null, NOW)).isEmpty();
        }

        @Test
        @DisplayName("a campaign-scoped entry does not leak into another campaign")
        void campaignScopeIsNarrow() {
            String hash = hash();
            store.add(ENTITY, SuppressionScope.CAMPAIGN, SuppressionSource.MANUAL,
                    Channel.VOICE_CALL, IdentifierType.PHONE, hash, null, null, "CAMP-1",
                    NOW.minus(1, ChronoUnit.DAYS), null, "excluded from this campaign", "tester");

            assertThat(store.findForIdentifier(ENTITY, IdentifierType.PHONE, hash,
                    Channel.VOICE_CALL, null, "CAMP-1", NOW)).isPresent();
            assertThat(store.findForIdentifier(ENTITY, IdentifierType.PHONE, hash,
                    Channel.VOICE_CALL, null, "CAMP-2", NOW)).isEmpty();
        }

        @Test
        @DisplayName("a statutory registry load does not duplicate an identifier already on it")
        void statutoryLoadIsIdempotent() {
            // These files are reloaded on a schedule and overlap heavily between editions.
            String hash = hash();
            store.addStatutoryBatch(SuppressionSource.DNC_SINGAPORE, Channel.SMS, IdentifierType.PHONE,
                    List.of(hash), NOW, "loader");
            store.addStatutoryBatch(SuppressionSource.DNC_SINGAPORE, Channel.SMS, IdentifierType.PHONE,
                    List.of(hash), NOW, "loader");

            assertThat(store.findForIdentifier(ENTITY, IdentifierType.PHONE, hash, Channel.SMS,
                    null, null, NOW.plus(1, ChronoUnit.HOURS))).isPresent();
        }
    }

    @Nested
    @DisplayName("ProvenanceStore — quarantine transitions")
    class Provenance {

        private final ProvenanceStore store = new ProvenanceStore(dataSource());

        @Test
        @DisplayName("a subject with no provenance record at all is contactable")
        void noRecordIsNotQuarantine() {
            // The ordinary case for someone who consented directly on a UDS surface, where the
            // consent event is itself the provenance. Quarantine is about records acquired from
            // somewhere else.
            assertThat(store.isContactable(ENTITY, "subject-with-no-provenance")).isTrue();
        }

        @Test
        @DisplayName("a record arrives quarantined and leaves only by substantiation")
        void quarantineIsTheDefault() {
            String subject = subject();
            long id = store.record(ENTITY, subject, "PURCHASED_LIST", "AcmeData", NOW, null, null,
                    null, false, null, null);

            assertThat(store.isContactable(ENTITY, subject)).isFalse();

            store.substantiate(id, "vendor produced their opt-in log", "priya");

            assertThat(store.isContactable(ENTITY, subject)).isTrue();
            assertThat(store.find(id)).get().satisfies(record -> {
                assertThat(record.substantiated()).isTrue();
                assertThat(record.quarantined()).isFalse();
            });
        }

        @Test
        @DisplayName("recording the same fact twice returns the original row")
        void ingestionIsIdempotent() {
            String subject = subject();

            ProvenanceStore.Ingestion first = store.recordIdempotent(ENTITY, subject,
                    "CLIENT_SUPPLIED", "HUL list", NOW, "CONSENT", null, "MSA-2024-11");
            ProvenanceStore.Ingestion second = store.recordIdempotent(ENTITY, subject,
                    "CLIENT_SUPPLIED", "HUL list", NOW, "CONSENT", null, "MSA-2024-11");

            assertThat(first.inserted()).isTrue();
            assertThat(second.inserted()).isFalse();
            assertThat(second.id()).isEqualTo(first.id());
        }

        @Test
        @DisplayName("a different acquisition date is a different fact, not a duplicate")
        void acquisitionDateIsPartOfTheKey() {
            String subject = subject();

            ProvenanceStore.Ingestion first = store.recordIdempotent(ENTITY, subject,
                    "APPENDED", "ZoomInfo", NOW, null, null, null);
            ProvenanceStore.Ingestion later = store.recordIdempotent(ENTITY, subject,
                    "APPENDED", "ZoomInfo", NOW.plus(90, ChronoUnit.DAYS), null, null, null);

            assertThat(later.inserted()).isTrue();
            assertThat(later.id()).isNotEqualTo(first.id());
        }

        @Test
        @DisplayName("ingestion cannot mark its own row substantiated")
        void ingestionCannotSelfCertify() {
            // There is no parameter for it on recordIdempotent, which is the point — the one code
            // path under the least human scrutiny is the one that must not be able to clear data
            // for use.
            String subject = subject();
            ProvenanceStore.Ingestion ingestion = store.recordIdempotent(ENTITY, subject,
                    "WEB_SCRAPED", "LinkedIn scrape 2023", NOW, "CONSENT", "evidence://nothing",
                    null);

            assertThat(store.find(ingestion.id())).get()
                    .satisfies(record -> assertThat(record.quarantined()).isTrue());
        }

        @Test
        @DisplayName("the latest record for a subject is the most recently acquired one")
        void latestIsByAcquisitionDate() {
            String subject = subject();
            store.record(ENTITY, subject, "PURCHASED_LIST", "Old vendor",
                    NOW.minus(400, ChronoUnit.DAYS), null, null, null, false, null, null);
            store.record(ENTITY, subject, "CLIENT_SUPPLIED", "Recent client",
                    NOW.minus(10, ChronoUnit.DAYS), null, null, null, false, null, null);

            assertThat(store.findLatestForSubject(ENTITY, subject)).get()
                    .satisfies(record -> assertThat(record.sourceName()).isEqualTo("Recent client"));
        }
    }

    @Nested
    @DisplayName("OutboxStore — claim and publish")
    class Outbox {

        private final OutboxStore store = new OutboxStore(dataSource());

        @Test
        @DisplayName("an enqueued message is pending until it is marked published")
        void publishRemovesFromPending() {
            String key = "key-" + UUID.randomUUID();
            store.enqueue("uds.consent.events", key, java.util.Map.of("type", "GRANTED"));

            OutboxStore.PendingMessage pending = store.fetchUnpublished(500).stream()
                    .filter(message -> message.eventKey().equals(key))
                    .findFirst()
                    .orElseThrow();
            assertThat(pending.payload()).contains("GRANTED");

            store.markPublished(pending.id());

            assertThat(store.fetchUnpublished(500))
                    .extracting(OutboxStore.PendingMessage::eventKey)
                    .doesNotContain(key);
        }

        @Test
        @DisplayName("a failed publish stays pending and counts its attempts")
        void failuresAreRetriedNotDropped() {
            // A withdrawal that fails to reach the broker and is then forgotten is a withdrawal
            // the group has not honoured. Failure has to leave the message where the relay will
            // find it again.
            String key = "key-" + UUID.randomUUID();
            store.enqueue("uds.consent.events", key, java.util.Map.of("type", "WITHDRAWN"));

            OutboxStore.PendingMessage first = store.fetchUnpublished(500).stream()
                    .filter(message -> message.eventKey().equals(key)).findFirst().orElseThrow();
            assertThat(first.attempts()).isZero();

            store.markFailed(first.id(), "broker unreachable");

            assertThat(store.fetchUnpublished(500))
                    .filteredOn(message -> message.eventKey().equals(key))
                    .singleElement()
                    .satisfies(message -> assertThat(message.attempts()).isEqualTo(1));
        }

        @Test
        @DisplayName("pending depth is the number an operator alerts on")
        void pendingCountTracksTheBacklog() {
            long before = store.pendingCount();
            store.enqueue("uds.consent.events", "key-" + UUID.randomUUID(),
                    java.util.Map.of("type", "EXPIRED"));

            assertThat(store.pendingCount()).isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("EntityStore — the group structure as configuration")
    class Entities {

        private final EntityStore store = new EntityStore(dataSource());

        @Test
        @DisplayName("the inheritance chain runs from the entity up to the group")
        void inheritanceChainReachesTheParent() {
            // Entity structure changes through M&A, so it is configuration rather than code. The
            // chain is what lets a policy set at group level apply to a subsidiary acquired after
            // it was written.
            List<EntityStore.FiduciaryEntity> chain = store.inheritanceChain("DENAVE_IN");

            assertThat(chain).isNotEmpty();
            assertThat(chain).extracting(EntityStore.FiduciaryEntity::entityId)
                    .startsWith("DENAVE_IN")
                    .contains("UDS");
        }

        @Test
        @DisplayName("contact points resolve up the chain, per field")
        void contactsInheritFromTheNearestAncestorThatHasThem() {
            // The defect this closes is live and silent. V3 seeds fifteen entities and sets
            // neither dpo_contact nor grievance_uri on any of them, while ReceiptService puts
            // both straight onto the ISO/IEC TS 27560 receipt — so every receipt the platform has
            // issued names a null contact point and, where the notice carried no grievance route,
            // a null grievance route. DPDP Rule 3 requires the principal be given both.
            //
            // Written against a fixture rather than the seed on purpose: the seed is deliberately
            // empty (a migration must not invent a DPO address onto a statutory artefact), so
            // asserting against it would only prove the platform is still in the state the
            // start-up warning describes.
            var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource());
            jdbc.sql("update fiduciary_entity set dpo_contact = 'dpo@example.test', "
                    + "grievance_uri = 'https://example.test/grievance' "
                    + "where entity_id = 'UDS'").update();
            jdbc.sql("update fiduciary_entity "
                    + "set grievance_uri = 'https://denave.example.test/grievance' "
                    + "where entity_id = 'DENAVE_IN'").update();

            EntityStore.Contacts denave = store.resolveContacts("DENAVE_IN");

            // Per field, not per entity. A subsidiary that publishes its own grievance route and
            // shares the group DPO is the ordinary case, and taking the nearest ancestor holding
            // *both* would silently discard the one it set.
            assertThat(denave.grievanceUri()).isEqualTo("https://denave.example.test/grievance");
            assertThat(denave.dpoContact()).isEqualTo("dpo@example.test");
            assertThat(denave.complete()).isTrue();

            // Two levels up, to prove it is a walk and not a single parent lookup.
            assertThat(store.resolveContacts("DENAVE_UK").dpoContact())
                    .isEqualTo("dpo@example.test");
        }

        @Test
        @DisplayName("an entity whose chain configures nothing resolves to null, not to a guess")
        void unconfiguredContactsAreReportedRatherThanInvented() {
            org.springframework.jdbc.core.simple.JdbcClient.create(dataSource())
                    .sql("update fiduciary_entity set dpo_contact = null, grievance_uri = null")
                    .update();

            EntityStore.Contacts contacts = store.resolveContacts("DENAVE_IN");

            // Null rather than a placeholder, and this is the assertion that keeps it honest. A
            // receipt naming an inbox nobody reads is worse than one naming none, because it looks
            // discharged. EntityContactCheck reports the gap at start-up; nothing invents a value.
            assertThat(contacts.dpoContact()).isNull();
            assertThat(contacts.grievanceUri()).isNull();
            assertThat(contacts.complete()).isFalse();
        }

        @Test
        @DisplayName("residency is per entity, because Rule 13 makes it a variable")
        void residencyIsConfigurable() {
            assertThat(store.find("DENAVE_IN")).get()
                    .satisfies(entity -> assertThat(entity.dataResidencyRegion()).isNotBlank());
        }

        @Test
        @DisplayName("every seeded entity is reachable in one call")
        void findAllReturnsTheGroup() {
            assertThat(store.findAll()).extracting(EntityStore.FiduciaryEntity::entityId)
                    .contains("UDS", "DENAVE_IN", "MATRIX", "ATHENA");
        }
    }

    @Nested
    @DisplayName("ApplicationRegistryStore — the surfaces allowed to write consent")
    class Applications {

        private final ApplicationRegistryStore store = new ApplicationRegistryStore(dataSource());

        @Test
        @DisplayName("a seeded surface resolves with its entity and environment")
        void seededApplicationsResolve() {
            assertThat(store.find("DENAVE_WEB")).get().satisfies(application -> {
                assertThat(application.entityId()).isEqualTo("DENAVE_IN");
                assertThat(application.environment()).isEqualTo("PRODUCTION");
                assertThat(application.active()).isTrue();
            });
        }

        @Test
        @DisplayName("an unregistered id resolves to nothing rather than to a permissive default")
        void unknownApplicationIsAbsent() {
            assertThat(store.find("NOT_A_REAL_APPLICATION")).isEmpty();
        }

        @Test
        @DisplayName("registering the same id twice updates rather than duplicating")
        void upsertReplaces() {
            String id = "IT_APP_" + UUID.randomUUID().toString().substring(0, 8);
            store.upsert(new ApplicationRegistryStore.Application(id, ENTITY, "Test surface",
                    "WEB", "STAGING", null, true));
            store.upsert(new ApplicationRegistryStore.Application(id, ENTITY, "Test surface",
                    "WEB", "STAGING", "now decommissioned", false));

            assertThat(store.find(id)).get().satisfies(application -> {
                assertThat(application.active()).isFalse();
                assertThat(application.description()).isEqualTo("now decommissioned");
            });
        }
    }

    @Nested
    @DisplayName("PurposeRegistryStore — the vocabulary every decision is made against")
    class Purposes {

        private final PurposeRegistryStore store = new PurposeRegistryStore(dataSource());

        @Test
        @DisplayName("a purpose carries the sensitivity its data categories are marked with")
        void sensitivityComesFromTheRegistry() {
            // The flags live on data_category and were previously read by nothing, which left
            // PdpaMalaysiaModule identifying biometrics by a "BIOMETRIC_" prefix on the code.
            // A convention holds until the first category somebody names differently.
            PurposeDefinition biometricAttendance = store.loadCurrentVersions().stream()
                    .filter(purpose -> "HR_ATTENDANCE_BIOMETRIC".equals(purpose.code()))
                    .findFirst()
                    .orElseThrow();

            assertThat(biometricAttendance.touchesBiometricData()).isTrue();
            assertThat(biometricAttendance.touchesSensitiveData()).isTrue();
            assertThat(biometricAttendance.biometricCategories())
                    .containsExactlyInAnyOrder("BIOMETRIC_FINGERPRINT", "BIOMETRIC_FACE");
        }

        @Test
        @DisplayName("an ordinary purpose reports no sensitive categories")
        void ordinaryPurposesAreNotSensitive() {
            PurposeDefinition outbound = store.loadCurrentVersions().stream()
                    .filter(purpose -> "MKT_OUTBOUND_CALL".equals(purpose.code()))
                    .findFirst()
                    .orElseThrow();

            assertThat(outbound.dataCategories()).isNotEmpty();
            assertThat(outbound.touchesSensitiveData()).isFalse();
            assertThat(outbound.touchesBiometricData()).isFalse();
        }
    }

    @Nested
    @DisplayName("BlastRadiusService — who has to be asked again")
    class BlastRadius {

        private final BlastRadiusService service = new BlastRadiusService(dataSource());

        @Test
        @DisplayName("asking about an unpublished version is refused, not answered")
        void unpublishedVersionIsRefused() {
            // The defect this replaces: the material-change flag was read from the very version
            // being asked about, and bool_or over no rows coalesced to false. Run before
            // publishing — which the operations notes instruct — a material change was reported
            // as NOTICE_UPDATE_ONLY. The one calculation whose job is to say "ask these people
            // again" said "carry on", in exactly the case it exists for.
            assertThatThrownBy(() -> service.forPurposeChange("MKT_OUTBOUND_CALL", 99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("has no version 99");
        }

        @Test
        @DisplayName("a material change to an unpublished version requires re-consent")
        void materialChangeBeforePublishingRequiresReConsent() {
            String subject = subject();
            grantAgainst(subject, "MKT_OUTBOUND_CALL", 1);

            BlastRadiusService.Impact impact =
                    service.forPurposeChange("MKT_OUTBOUND_CALL", 2, true);

            assertThat(impact.materialChange()).isTrue();
            assertThat(impact.action())
                    .isEqualTo(BlastRadiusService.Action.RE_CONSENT_REQUIRED);
            assertThat(impact.standingConsentAffected()).isPositive();
        }

        @Test
        @DisplayName("a cosmetic change to the same version needs only a notice update")
        void cosmeticChangeNeedsOnlyANotice() {
            grantAgainst(subject(), "MKT_OUTBOUND_CALL", 1);

            BlastRadiusService.Impact impact =
                    service.forPurposeChange("MKT_OUTBOUND_CALL", 2, false);

            assertThat(impact.materialChange()).isFalse();
            assertThat(impact.action())
                    .isEqualTo(BlastRadiusService.Action.NOTICE_UPDATE_ONLY);
        }

        @Test
        @DisplayName("nobody holding the old version means nothing to do, material or not")
        void nobodyAffectedIsNoAction() {
            // Deliberately a purpose with no artefacts. A material change that affects nobody is
            // still just a publish, and reporting RE_CONSENT_REQUIRED for it would train whoever
            // reads this to ignore the answer.
            BlastRadiusService.Impact impact =
                    service.forPurposeChange("BGV_CRIMINAL_RECORD", 2, true);

            assertThat(impact.totalAffected()).isZero();
            assertThat(impact.action()).isEqualTo(BlastRadiusService.Action.NO_ACTION);
        }

        @Test
        @DisplayName("the affected population is broken down by entity, not summed across the group")
        void theBreakdownIsPerEntity() {
            // The number that gets acted on is per entity, because the people who have to act are
            // per entity: Denave's compliance officer cannot re-consent Matrix's subjects and
            // should not be handed a figure that includes them. A single group total looks
            // authoritative and is unusable — it is the shape of number that gets divided by
            // guesswork before anybody can do anything with it.
            String denaveSubject = subject();
            grantAgainst(denaveSubject, "MKT_OUTBOUND_CALL", 1);

            String matrixSubject = subject();
            grantAgainstEntity("MATRIX", matrixSubject, "MKT_OUTBOUND_CALL", 1);

            BlastRadiusService.Impact impact =
                    service.forPurposeChange("MKT_OUTBOUND_CALL", 2, true);

            assertThat(impact.byEntityAndStatus())
                    .extracting(BlastRadiusService.Bucket::entityId)
                    .contains(ENTITY, "MATRIX");

            // And the total is still the total. Both facts have to hold at once: a breakdown that
            // does not add up is worse than no breakdown, because the disagreement is discovered
            // by whoever is presenting it.
            assertThat(impact.totalAffected())
                    .isEqualTo(impact.byEntityAndStatus().stream()
                            .mapToLong(BlastRadiusService.Bucket::count).sum());
        }

        @Test
        @DisplayName("a notice change is assessed through the purposes that cite it")
        void aNoticeChangeReachesItsPurposes() {
            // A notice is never consented to directly — it is the text a purpose's consent was
            // given against. The calculation has to walk that indirection, and the failure mode of
            // getting it wrong is a notice rewritten with nobody reported as affected, which reads
            // as good news.
            grantAgainst(subject(), "MKT_OUTBOUND_CALL", 1);

            assertThat(service.forNoticeChange("NOTICE_DENAVE_B2B", 2, true))
                    .isNotEmpty()
                    .anySatisfy(impact -> {
                        assertThat(impact.materialChange()).isTrue();
                        assertThat(impact.action())
                                .isEqualTo(BlastRadiusService.Action.RE_CONSENT_REQUIRED);
                    });
        }

        @Test
        @DisplayName("an unpublished notice version is refused on the same terms as a purpose")
        void anUnpublishedNoticeVersionIsRefused() {
            assertThatThrownBy(() -> service.forNoticeChange("NOTICE_DENAVE_B2B", 99))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("has no version 99");
        }

        private void grantAgainst(String subjectId, String purposeCode, int purposeVersion) {
            grantAgainstEntity(ENTITY, subjectId, purposeCode, purposeVersion);
        }

        private void grantAgainstEntity(String entityId, String subjectId, String purposeCode,
                                        int purposeVersion) {
            new ConsentArtefactStore(dataSource()).upsert(new ConsentArtefact(
                    entityId, subjectId, purposeCode, purposeVersion, ConsentStatus.GRANTED,
                    LegalBasis.CONSENT, "NOTICE_DENAVE_B2B", 1, "en", CaptureMethod.CHECKBOX_OPT_IN,
                    Channel.WEB, "DENAVE_WEB", Jurisdiction.IN, NOW, null, null, NOW, 1L,
                    "hash-" + subjectId), 0);
        }
    }

    // -------------------------------------------------------------------------------------------

    /** A hash-shaped value nothing else in the suite will collide with. */
    private static String hash() {
        return UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
    }

    private static String subject() {
        return "it-store-" + UUID.randomUUID();
    }
}
