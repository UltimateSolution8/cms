package com.uds.consent.service.it;

import com.uds.consent.core.decision.DecisionRequest;
import com.uds.consent.core.decision.DecisionResponse;
import com.uds.consent.core.decision.DenialReason;
import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.IdentifierType;
import com.uds.consent.core.model.Jurisdiction;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;
import com.uds.consent.ledger.store.DltRegistryStore;
import com.uds.consent.ledger.store.SuppressionStore;
import com.uds.consent.policy.PolicyEngine;
import com.uds.consent.policy.capture.CaptureSubmission;
import com.uds.consent.service.ConsentCaptureService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TRAI's ninety-day cooling-off, against the real suppression table.
 *
 * <p>The golden suite proves the rule; this proves it is wired to the dates that actually exist.
 * The distinction matters more here than usual, because the query behind it is deliberately unlike
 * every other suppression lookup in the platform: it ignores {@code effective_to} and it ignores
 * scope. Those decide whether the group may contact somebody <em>now</em>. The cooling-off runs
 * from when they said no, and an opt-out that has lapsed or was scoped to one campaign stops
 * suppressing while the ninety days it started are still running — which is exactly the case the
 * rule exists for, and exactly the case a lookup built on the ordinary suppression query would
 * pass.
 *
 * <p>Commercially this is the sharpest rule in the platform. The re-permissioning campaign against
 * Denave's quarantined records is the whole point of the provenance work, and it is the activity
 * the cooling-off restricts.
 */
class TraiCoolingOffIT extends PostgresIntegrationTest {

    private static final String ENTITY = "DENAVE_IN";
    private static final String APP = "DENAVE_WEB";
    private static final String NOTICE = "NOTICE_DENAVE_B2B";

    @Autowired
    private PolicyEngine policy;

    @Autowired
    private SuppressionStore suppression;

    @Autowired
    private DltRegistryStore dlt;

    @Autowired
    private ConsentCaptureService capture;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a lapsed opt-out no longer suppresses but still bars re-solicitation")
    void aLapsedOptOutStillStartsTheClock() {
        String subject = grant();
        Instant optedOut = Instant.now().minus(30, ChronoUnit.DAYS);

        // Deliberately expired: effective_to is in the past, so the ordinary suppression lookup
        // finds nothing and the request reaches the TCCCPR module with a live consent behind it.
        // This is the shape the rule exists for, and the shape a check built on the suppression
        // query would wave through.
        insertOptOut(subject, optedOut, Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(suppression.findForSubject(ENTITY, subject, Channel.VOICE_CALL, null, null,
                Instant.now())).isEmpty();
        assertThat(suppression.lastOptOutAt(ENTITY, subject, Channel.VOICE_CALL))
                .isPresent();

        DecisionResponse decision = decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.WITHIN_COOLING_OFF_PERIOD);
    }

    @Test
    @DisplayName("an opt-out older than ninety days releases the purpose")
    void theCoolingOffExpires() {
        String subject = grant();
        insertOptOut(subject, Instant.now().minus(120, ChronoUnit.DAYS),
                Instant.now().minus(100, ChronoUnit.DAYS));

        // A rule that never released would be a permanent ban rather than a cooling-off, and would
        // quietly cost the group every contact who ever unsubscribed.
        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a live opt-out still denies as a suppression, not as a cooling-off")
    void anActiveOptOutDeniesForTheRightReason() {
        String subject = grant();
        suppression.add(ENTITY, SuppressionScope.ENTITY, SuppressionSource.INBOUND_OPT_OUT,
                Channel.VOICE_CALL, IdentifierType.PHONE, "hash-" + subject, subject, null, null,
                Instant.now().minus(1, ChronoUnit.DAYS), null, "asked on a call", "test");

        // The reason a regulator reads has to be the right one. Suppression outranks the
        // cooling-off in the gate order because it is the stronger and more specific answer:
        // "they are on our do-not-contact list", not "they may not be asked again yet".
        DecisionResponse decision = decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.SUPPRESSED_OPT_OUT);
    }

    @Test
    @DisplayName("a statutory registry entry does not start a cooling-off")
    void ncprIsNotAnOptOut() {
        String subject = grant();
        // A national preference registration is a standing state rather than an act of opting out
        // of this group's messaging. Treating a decades-old NCPR listing as a fresh opt-out would
        // put every registered number permanently inside a cooling-off, denying with the wrong
        // reason where the registry already denies with the right one.
        insertOptOut(subject, Instant.now().minus(2, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS), SuppressionSource.NCPR_INDIA);

        assertThat(suppression.lastOptOutAt(ENTITY, subject, Channel.VOICE_CALL)).isEmpty();
        assertThat(decide(subject, "MKT_OUTBOUND_CALL", Channel.VOICE_CALL).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("the seeded DLT registrations are readable and honestly incomplete")
    void theRegistryReportsWhatIsNotYetRegistered() {
        Optional<DltRegistryStore.Registration> promotional =
                dlt.find(ENTITY, "MKT_OUTBOUND_SMS");

        assertThat(promotional).isPresent();
        assertThat(promotional.get().header()).isEqualTo("DENAVE");
        assertThat(promotional.get().category()).isEqualTo("P");
        assertThat(promotional.get().series()).isEqualTo("140");

        // The seed ships PENDING_REGISTRATION rather than a plausible-looking fake, and the store
        // reports it as unusable. A convincing placeholder would be believed and sent, and the
        // operator would reject the campaign.
        assertThat(promotional.get().usable()).isFalse();
        assertThat(dlt.find(ENTITY, "PURPOSE_WITH_NO_TEMPLATE")).isEmpty();
    }

    // -----------------------------------------------------------------------------------

    private DecisionResponse decide(String subjectId, String purposeCode, Channel channel) {
        return policy.evaluate(new DecisionRequest(ENTITY, subjectId, purposeCode, channel,
                Jurisdiction.IN, APP, Instant.now(), null, null, null, Map.of()));
    }

    private void insertOptOut(String subjectId, Instant from, Instant to) {
        insertOptOut(subjectId, from, to, SuppressionSource.INBOUND_OPT_OUT);
    }

    /**
     * Writes a suppression row directly so that {@code effective_to} can be set in the past.
     *
     * <p>The service deliberately offers no way to create an already-expired opt-out — it is not
     * something a surface should be able to do — but it is exactly the state the platform will
     * find in the real table once the first campaign-scoped opt-outs age out.
     */
    private void insertOptOut(String subjectId, Instant from, Instant to,
                              SuppressionSource source) {
        jdbc.update("""
                insert into suppression_entry (entity_id, scope, source, channel, identifier_type,
                                               identifier_hash, subject_id, effective_from,
                                               effective_to, reason, created_by)
                values (?, 'ENTITY', ?, 'VOICE_CALL', 'PHONE', ?, ?, ?, ?, ?, 'test')
                """, ENTITY, source.name(), "hash-" + subjectId, subjectId,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to),
                "opted out during a call");
    }

    private String grant() {
        String subject = "trai-" + UUID.randomUUID();
        ConsentCaptureService.Result result = capture.capture(new CaptureSubmission(ENTITY, subject,
                Jurisdiction.IN, "en", Channel.WEB, APP, CaptureMethod.CHECKBOX_OPT_IN,
                ActorType.SUBJECT, subject, NOTICE, 1,
                List.of(CaptureSubmission.PurposeChoice.acceptedSeparately("MKT_OUTBOUND_CALL")),
                true, Instant.now().minus(200, ChronoUnit.DAYS), "trai-" + subject, null,
                Map.of()));

        assertThat(result.isAccepted())
                .withFailMessage("capture rejected: %s", result.violations())
                .isTrue();
        return subject;
    }
}
