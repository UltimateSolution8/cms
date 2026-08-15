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

    /** Attributes of the subject that change what is permitted, chiefly age. */
    @FunctionalInterface
    public interface SubjectAttributeLookup {
        boolean isChild(String subjectId);
    }

    /**
     * The registry of surfaces allowed to submit consent.
     *
     * <p>Used at capture rather than at decision. The question it answers is not "may this
     * processing happen" but "is this submission from a surface the group knows about" — and an
     * unrecognised surface writing consent records is either an integration nobody reviewed or an
     * attempt to manufacture evidence. Both want catching at the door.
     */
    @FunctionalInterface
    public interface ApplicationRegistry {
        Optional<RegisteredApplication> find(String applicationId);
    }

    /**
     * A surface the group has registered.
     *
     * @param environment PRODUCTION, STAGING and so on. Carried because a staging build writing
     *                    into the production ledger is a real and unremarkable accident, and the
     *                    registry is where it becomes visible
     */
    public record RegisteredApplication(String applicationId, String entityId, String name,
                                        String platform, String environment, boolean active) {
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
