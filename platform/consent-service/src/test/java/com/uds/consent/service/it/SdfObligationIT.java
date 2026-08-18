package com.uds.consent.service.it;

import com.uds.consent.core.crypto.Hashes;
import com.uds.consent.ledger.store.AlgorithmicSystemStore;
import com.uds.consent.ledger.store.ProcessingActivityStore;
import com.uds.consent.ledger.store.SdfObligationStore;
import com.uds.consent.service.RopaService;
import com.uds.consent.service.SdfObligationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DPDP Rule 13 — including 13(4)'s localisation power — and the flag that until now was read by
 * nothing.
 *
 * <p>Cited as "Rule 14" in the version of this suite delivered with V20. That was wrong: Rule 14 is
 * rights, publication and grievance redressal. The named-category prohibition is Rule 13(4), on the
 * recommendation of the Rule 13(5) committee, and the general transfer restriction — which binds
 * every fiduciary, not only Significant ones — is Rule 15. Corrected in V21.
 *
 * <p>{@code fiduciary_entity.significant_fiduciary} has existed since the first migration. It was
 * set by the seed, mapped by {@code EntityStore}, documented in its own javadoc — and consumed
 * nowhere in any of the four modules. The designation was held and none of what it means was
 * modelled.
 *
 * <p>Two properties matter most here and pull in opposite directions, which is why both are
 * asserted rather than one. An entity the Government <em>has</em> notified must owe a DPIA, an
 * independent audit and a diligence check per algorithmic system, on a rolling twelve months. An
 * entity it has <em>not</em> notified must owe nothing at all — no register, no overdue count, and
 * no 404 either, because "this entity is not an SDF" is an answer and an error reads as a failure
 * to give one.
 *
 * <p>The suite designates an entity for the duration of a test and puts the flag back afterwards.
 * The container is shared with every other suite in the module, and leaving a designation behind
 * would give some later test a register it never asked for.
 */
class SdfObligationIT extends PostgresIntegrationTest {

    /** Designated for the length of a test, then put back. */
    private static final String ENTITY = "MATRIX";

    /** Never designated. The empty-register case is asserted against this one. */
    private static final String NOT_DESIGNATED = "DENAVE_IN";

    private static final LocalDate DESIGNATED_FROM = LocalDate.of(2026, 1, 15);
    private static final Instant NOW = Instant.parse("2026-08-17T09:00:00Z");

    @Autowired
    private SdfObligationService sdf;

    @Autowired
    private SdfObligationStore obligations;

    @Autowired
    private AlgorithmicSystemStore systems;

    @Autowired
    private RopaService ropa;

    @Autowired
    private ProcessingActivityStore activities;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void undesignate() {
        jdbc.update("update fiduciary_entity set significant_fiduciary = false "
                + "where entity_id = ?", ENTITY);
        jdbc.update("delete from sdf_obligation where entity_id = ?", ENTITY);
        jdbc.update("delete from algorithmic_system where entity_id = ?", ENTITY);
        jdbc.update("update data_category set transfer_restricted = false, "
                + "transfer_restriction_ref = null");
    }

    // -------------------------------------------------------------------------------------
    // The flag
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("an entity nobody has designated owes nothing, and says so rather than erroring")
    void aNonSignificantEntityHasAnEmptyRegister() {
        SdfObligationService.Register register = sdf.register(NOT_DESIGNATED, NOW);

        assertThat(register.significantFiduciary()).isFalse();
        assertThat(register.obligations()).isEmpty();
        assertThat(register.overdue()).isEmpty();

        // And raising against it does nothing. Manufacturing a register for an entity with no
        // designation would put obligations in front of an operator that no law imposes, and an
        // overdue count made of duties nobody has is a count nobody reads.
        assertThat(sdf.raiseDue(NOT_DESIGNATED, DESIGNATED_FROM, NOW)).isZero();
        assertThat(obligations.forEntity(NOT_DESIGNATED)).isEmpty();
    }

    @Test
    @DisplayName("designating an entity gives it a DPIA and an independent audit on a yearly cycle")
    void designationRaisesTheAnnualPair() {
        designate();

        assertThat(sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW)).isEqualTo(2);

        List<SdfObligationStore.Obligation> raised = obligations.forEntity(ENTITY);
        assertThat(raised).extracting(SdfObligationStore.Obligation::obligationType)
                .containsExactlyInAnyOrder("DPIA", "INDEPENDENT_AUDIT");
        assertThat(raised).allSatisfy(o -> {
            assertThat(o.periodStart()).isEqualTo(DESIGNATED_FROM);
            // Rule 13's "at least once every twelve months".
            assertThat(o.periodEnd()).isEqualTo(DESIGNATED_FROM.plusMonths(12));
            assertThat(o.open()).isTrue();
        });

        // Idempotent. A scheduler, an admin endpoint and a test may all call this on the same day
        // and the entity still owes one DPIA.
        assertThat(sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW)).isZero();
        assertThat(obligations.forEntity(ENTITY)).hasSize(2);
    }

    @Test
    @DisplayName("each registered algorithmic system owes its own diligence check")
    void diligenceIsPerSystemRatherThanPerYear() {
        designate();
        long dialer = systems.upsert(new AlgorithmicSystemStore.AlgorithmicSystem(ENTITY,
                "Dialer prioritisation", "Which prospects the predictive dialer calls first",
                List.of("MKT_OUTBOUND_CALL"), true, "operations"));
        systems.upsert(new AlgorithmicSystemStore.AlgorithmicSystem(ENTITY,
                "BGV risk scoring", "Which background-verification cases are escalated",
                List.of(), true, "verification"));

        // Two annual obligations plus one per system. Rule 13's diligence is a check about a
        // system, so a group running two of them owes two answers rather than one covering both.
        assertThat(sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW)).isEqualTo(4);
        assertThat(obligations.forEntity(ENTITY)).filteredOn(
                        o -> "ALGORITHMIC_DUE_DILIGENCE".equals(o.obligationType()))
                .hasSize(2)
                .extracting(SdfObligationStore.Obligation::algorithmicSystemId)
                .contains(dialer);
    }

    // -------------------------------------------------------------------------------------
    // "Evidence available on audit"
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a completion with no hashed artefact is refused")
    void anAssessmentMustBeEvidenced() {
        designate();
        sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW);
        long id = dpiaId();

        // Rule 13's requirement is that the evidence be available on audit. A row pointing at
        // "DPIA_2026_final_v3.pdf" and nothing else is a register of assertions — the document
        // can be replaced and the row still reads as satisfied.
        assertThatThrownBy(() -> sdf.complete(id, "KPMG India", "s3://uds-compliance/dpia-2026.pdf",
                "  ", "No high residual risks", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hash");

        assertThat(obligations.find(id).orElseThrow().open()).isTrue();
    }

    @Test
    @DisplayName("a completed assessment is not discharged until the Board has it")
    void completionAndReportingAreSeparateFacts() {
        designate();
        sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW);
        long id = dpiaId();

        sdf.complete(id, "KPMG India", "s3://uds-compliance/dpia-2026.pdf",
                Hashes.sha256Hex("the report bytes"), "No high residual risks", NOW);

        // Done the expensive part, missed the part that is checked. Rule 13 asks for the
        // observations to be furnished to the Board, and a register folding the two together
        // would show this entity as compliant.
        assertThat(obligations.find(id).orElseThrow().open()).isFalse();
        assertThat(obligations.find(id).orElseThrow().discharged()).isFalse();
        assertThat(sdf.register(ENTITY, NOW).completedButUnreported())
                .extracting(SdfObligationStore.Obligation::id).contains(id);

        obligations.markReportedToBoard(id, NOW.plusSeconds(86_400));

        assertThat(obligations.find(id).orElseThrow().discharged()).isTrue();
        assertThat(sdf.register(ENTITY, NOW).completedButUnreported()).isEmpty();
    }

    @Test
    @DisplayName("an assessment past its date shows as overdue, and completing it clears it")
    void theCycleIsVisibleWhenItSlips() {
        designate();
        sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW);

        // The period ends 15 January 2027, so nothing is late in August 2026 and everything is
        // late a year on.
        assertThat(sdf.register(ENTITY, NOW).overdue()).isEmpty();

        Instant afterTheCycle = Instant.parse("2027-03-01T00:00:00Z");
        assertThat(sdf.register(ENTITY, afterTheCycle).overdue()).hasSize(2);
        assertThat(sdf.countOverdueAcrossGroup(afterTheCycle)).isEqualTo(2);

        sdf.complete(dpiaId(), "KPMG India", "s3://uds-compliance/dpia-2026.pdf",
                Hashes.sha256Hex("the report bytes"), null, afterTheCycle);

        assertThat(sdf.register(ENTITY, afterTheCycle).overdue()).hasSize(1);
    }

    @Test
    @DisplayName("the next cycle runs from the last completion, not from the calendar")
    void theCycleRolls() {
        designate();
        sdf.raiseDue(ENTITY, DESIGNATED_FROM, NOW);
        sdf.complete(dpiaId(), "KPMG India", "s3://uds-compliance/dpia-2026.pdf",
                Hashes.sha256Hex("the report bytes"), null, NOW);

        // An entity that completed its DPIA covering the year to January 2027 owes the next one
        // for the year from January 2027 — "at least once every twelve months", not "once per
        // calendar year", which would let a January assessment and a December one satisfy two
        // cycles fourteen months apart.
        sdf.raiseDue(ENTITY, DESIGNATED_FROM, Instant.parse("2027-02-01T00:00:00Z"));

        assertThat(obligations.forEntity(ENTITY))
                .filteredOn(o -> "DPIA".equals(o.obligationType()))
                .hasSize(2)
                .extracting(SdfObligationStore.Obligation::periodStart)
                .containsExactlyInAnyOrder(DESIGNATED_FROM, DESIGNATED_FROM.plusMonths(12));
    }

    // -------------------------------------------------------------------------------------
    // Rule 13(4) — the hook, and the fact that it is empty on purpose
    // -------------------------------------------------------------------------------------

    @Test
    @DisplayName("no data category is restricted from leaving India today")
    void theRestrictedCategoryListIsEmptyBecauseItWasChecked() {
        // The Government may specify categories a Significant Data Fiduciary must not transfer
        // outside India at all. None are notified as at August 2026. This asserts the delivered
        // state so that a future seed quietly adding one has to come past this test.
        assertThat(jdbc.queryForObject(
                "select count(*) from data_category where transfer_restricted = true",
                Integer.class)).isZero();
        assertThat(ropa.forEntity(NOT_DESIGNATED).prohibitedTransfers()).isEmpty();
    }

    @Test
    @DisplayName("a restricted category turns a documented transfer into a prohibited one")
    void theHookWorksWhenACategoryIsNotified() {
        // Exercising the hook against a restriction this test imposes and then removes. The point
        // is that honouring a real notification is an update against one column — no release, no
        // migration, and a report that already consults it.
        String activityName = "SDF IT activity " + UUID.randomUUID();
        activities.create(new ProcessingActivityStore.Activity(null, NOT_DESIGNATED, activityName,
                "Cross-border processing for a Rule 13(4) test", "MKT_OUTBOUND_CALL", "DenCRM",
                List.of("CONTACT_BUSINESS"), List.of(), List.of("US"), 365, "Client contract",
                "compliance", null));

        assertThat(ropa.forEntity(NOT_DESIGNATED).prohibitedTransfers()).isEmpty();

        jdbc.update("update data_category set transfer_restricted = true, "
                + "transfer_restriction_ref = 'MeitY notification (test fixture)' "
                + "where code = 'CONTACT_BUSINESS'");

        RopaService.Ropa report = ropa.forEntity(NOT_DESIGNATED);
        assertThat(report.hasProhibitedTransfer()).isTrue();
        assertThat(report.prohibitedTransfers())
                .extracting(ProcessingActivityStore.Activity::name)
                .contains(activityName);

        // And it is not merely another cross-border row. Those are lawful and documentable; this
        // one has to stop, and a report ranking the two together would bury it.
        assertThat(report.crossBorderTransfers())
                .extracting(ProcessingActivityStore.Activity::name)
                .contains(activityName);

        jdbc.update("delete from processing_activity where name = ?", activityName);
    }

    // -------------------------------------------------------------------------------------

    private void designate() {
        jdbc.update("update fiduciary_entity set significant_fiduciary = true "
                + "where entity_id = ?", ENTITY);
    }

    private long dpiaId() {
        return obligations.forEntity(ENTITY).stream()
                .filter(o -> "DPIA".equals(o.obligationType()) && o.open())
                .findFirst()
                .orElseThrow()
                .id();
    }
}
