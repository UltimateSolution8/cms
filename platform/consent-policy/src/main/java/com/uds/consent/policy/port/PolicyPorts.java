package com.uds.consent.policy.port;

import com.uds.consent.core.model.Channel;
import com.uds.consent.core.model.ConsentArtefact;
import com.uds.consent.core.model.PurposeDefinition;
import com.uds.consent.core.model.SuppressionScope;
import com.uds.consent.core.model.SuppressionSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The four things the decision engine needs from the outside world, expressed as interfaces it
 * owns rather than as dependencies on the ledger module.
 *
 * <p>The point is testability. Consent decisions are the part of this platform where being wrong
 * is most expensive and least visible — a wrongly permissive answer produces no error, just a
 * call to someone who asked not to be called. Keeping the engine free of any database, broker or
 * Spring context means the golden decision suite can enumerate hundreds of cases across six
 * jurisdictions in milliseconds, which is the only way that suite gets run on every commit.
 */
public final class PolicyPorts {

    private PolicyPorts() {
    }

    /** Current consent state for a subject and purpose. */
    @FunctionalInterface
    public interface ArtefactLookup {
        Optional<ConsentArtefact> find(String entityId, String subjectId, String purposeCode);
    }

    /** The purpose registry. */
    public interface PurposeCatalog {
        Optional<PurposeDefinition> find(String purposeCode);

        List<PurposeDefinition> all();
    }

    /** Do-not-contact state. */
    public interface SuppressionLookup {
        Optional<Hit> find(String entityId, String subjectId, Channel channel, String clientId,
                           String campaignId, Instant at);
    }

    /**
     * Whether the subject's record can be contacted at all given where it came from.
     *
     * <p>Returns true for a subject with no provenance record, which is the ordinary case for
     * someone who gave consent directly on a UDS surface. Quarantine is about records acquired
     * from somewhere else.
     */
    @FunctionalInterface
    public interface ProvenanceLookup {
        boolean isContactable(String entityId, String subjectId);
    }

    /**
     * Attributes of the subject that change what is permitted, chiefly age.
     *
     * <p><strong>Both parameters beyond the subject id are load-bearing, and neither was here
     * before.</strong> The method used to be {@code isChild(String subjectId)}, wired to the
     * mutable {@code subject.is_child} column. Two problems followed from that signature, and they
     * are fixed together because fixing one would leave the same class of bug with a smaller blast
     * radius.
     *
     * <p><em>The instant.</em> {@link com.uds.consent.core.decision.DecisionRequest#at()} exists so
     * that a decision can be replayed as it stood — its javadoc says so. The child gate ignored it
     * and read today's flag, so replaying a 2026 decision about somebody who has since turned
     * eighteen answered "not a child", and every behavioural-tracking decision taken while they were
     * fifteen read back as lawful. That is the one direction an audit trail must never be wrong in.
     *
     * <p><em>The entity.</em> Every other lookup on this path is entity-scoped and this one was not.
     * It happened to be safe because subject ids are UUIDs, which is a coincidence in the id format
     * rather than a property of the design, and the row-level-security layer cannot help a query
     * that never names an entity.
     *
     * <p>There is deliberately no unscoped or undated variant left to call.
     */
    @FunctionalInterface
    public interface SubjectAttributeLookup {

        /**
         * Whether the subject was a child <em>as at</em> {@code at} — not whether they are one now.
         *
         * @param at the decision instant, which on a replay is historical
         */
        boolean isChildAt(String entityId, String subjectId, Instant at);
    }

    /**
     * The registry of surfaces allowed to submit consent, and to ask.
     *
     * <p>At capture the question is "is this submission from a surface the group knows about" — an
     * unrecognised surface writing consent records is either an integration nobody reviewed or an
     * attempt to manufacture evidence, and both want catching at the door.
     *
     * <p>At decision the question is narrower and the failure it catches is different: a
     * credential leaked from one entity's surface being used to read another entity's answers. The
     * decision path had been accepting {@code applicationId}, mapping it into the request, and
     * never looking at it.
     */
    @FunctionalInterface
    public interface ApplicationRegistry {
        Optional<RegisteredApplication> find(String applicationId);
    }

    /**
     * Whether a notice reference is real.
     *
     * <p>The gap this closes is narrow and severe. Before it, a capture could cite notice version
     * 99 that was never published, or a language with no translation, and the platform would
     * accept the record and the {@code NOTICE_VERSION_NOT_RECORDED} check would pass — because it
     * only ever tested that the field was <em>present</em>. The result looks like valid evidence
     * and is not, and the discovery happens years later when somebody asks to see what the person
     * actually read.
     *
     * <p>Two questions rather than one, because they fail differently. A version that does not
     * exist is an integration bug. A version that exists in no language the subject reads is a
     * procurement gap wearing an integration bug's clothes, and telling them apart is the
     * difference between paging an engineer and buying a translation.
     */
    public interface NoticeLookup {

        boolean exists(String noticeId, int version);

        boolean hasTranslation(String noticeId, int version, String languageTag);
    }

    /**
     * Whether a processor may receive data for a purpose.
     *
     * <p>A vendor authorised for telemarketing is not thereby authorised for profiling. That
     * distinction lives in the data processing agreement, and until this port existed it lived
     * <em>only</em> there — contractual rather than enforceable, with
     * {@code VendorStore.isAuthorisedFor} carrying a javadoc claiming it was read on the decision
     * path while nothing but a test called it.
     */
    @FunctionalInterface
    public interface VendorAuthorisation {
        boolean isAuthorisedFor(String vendorId, String purposeCode);
    }

    /**
     * A surface the group has registered.
     *
     * @param environment       PRODUCTION, STAGING and so on. Carried because a staging build
     *                          writing into the production ledger is a real and unremarkable
     *                          accident, and the registry is where it becomes visible
     * @param servedEntityIds   entities this surface may act for. Distinct from {@code entityId},
     *                          which is who owns it. An outsourced-services group runs shared
     *                          operational systems as a matter of course — Athena BPO's dialer
     *                          exists to place Denave's calls — and collapsing ownership into
     *                          reach either denies that surface everything it is for, or discards
     *                          the check entirely
     */
    public record RegisteredApplication(String applicationId, String entityId, String name,
                                        String platform, String environment, boolean active,
                                        java.util.Set<String> servedEntityIds) {

        public RegisteredApplication {
            servedEntityIds = servedEntityIds == null ? java.util.Set.of()
                    : java.util.Set.copyOf(servedEntityIds);
        }

        /** A surface with no recorded reach beyond the entity that owns it. */
        public RegisteredApplication(String applicationId, String entityId, String name,
                                     String platform, String environment, boolean active) {
            this(applicationId, entityId, name, platform, environment, active,
                    java.util.Set.of(entityId));
        }

        /**
         * Whether this surface may act for an entity.
         *
         * <p>The owning entity always counts, so a registry row with no scope rows behaves as it
         * did before the scope table existed rather than as a surface authorised for nothing.
         */
        public boolean serves(String entityId) {
            return this.entityId.equals(entityId) || servedEntityIds.contains(entityId);
        }
    }

    /**
     * When a subject last asked not to be contacted, whether or not that ask is still in force.
     *
     * <p>Distinct from {@link SuppressionLookup}, which answers "may we contact them now". This
     * answers "how long ago did they say no", and the two differ precisely where it matters: an
     * opt-out scoped to one campaign, or one that has since lapsed, stops suppressing while the
     * ninety-day cooling-off it started is still running.
     *
     * <p>That gap is not hypothetical for this group. The re-permissioning campaign against
     * Denave's quarantined records is the commercial point of the provenance work, and
     * re-soliciting consent inside ninety days of an opt-out is exactly what TRAI's February 2025
     * amendment restricts.
     */
    @FunctionalInterface
    public interface OptOutHistory {

        Optional<Instant> lastOptOutAt(String entityId, String subjectId, Channel channel);
    }

    /**
     * Whether a Korean marketing consent is past its two-yearly confirmation.
     *
     * <p>Enforcement Decree of the Network Act, Art. 62-3. A port rather than a store reference so
     * that the Korean module stays testable without a database, like the rest of
     * {@code consent-policy}.
     *
     * <p>Read on the decision path to raise an obligation, never to deny. The Decree fixes the
     * interval and the content of the confirmation and says nothing about what silence means, so a
     * platform that turned an unanswered confirmation into a denial would be enforcing a rule
     * nobody can point to — against the group's own commercial interest, on its own authority. The
     * obligation makes the position visible; a denial would make it false.
     */
    @FunctionalInterface
    public interface ReconfirmationStatus {

        boolean isOverdue(String entityId, String subjectId, String purposeCode, Instant at);

        /** The default everywhere the obligation does not apply, and in every unit test. */
        static ReconfirmationStatus none() {
            return (entityId, subjectId, purposeCode, at) -> false;
        }
    }

    /**
     * The DLT registration a message must go out under.
     *
     * <p>Returns which header and template, not merely that one is required. The obligations
     * "use-dlt-registered-header" and "use-dlt-registered-template" told a sender something it
     * already knew; naming the registration is what lets an outbound message be tied to a
     * registered template, a live consent and a preference check in one place — which is the join
     * a TRAI investigation asks about.
     */
    @FunctionalInterface
    public interface DltRegistry {

        Optional<DltRegistration> find(String entityId, String purposeCode);
    }

    /**
     * @param category    P, S, T or G. A promotional message sent under a service header is the
     *                    mis-send that gets caught
     * @param series      140 for promotional, 1600 for transactional; null where inapplicable
     * @param templateRef the id that has to appear on the wire, or a marker that none is
     *                    registered yet
     */
    public record DltRegistration(String header, String category, String series,
                                  String templateRef, boolean usable) {
    }

    /**
     * A matched suppression.
     *
     * @param source   where it came from
     * @param scope    how far it reaches
     * @param statutory whether it derives from a statutory registry, in which case no consent
     *                  record can override it
     */
    public record Hit(SuppressionSource source, SuppressionScope scope, boolean statutory) {

        public static Hit of(SuppressionSource source, SuppressionScope scope) {
            return new Hit(source, scope, source.isStatutory());
        }
    }
}
