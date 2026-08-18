package com.uds.consent.service.config;

import com.uds.consent.ledger.store.EntityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Names the entities whose published contact points resolve to nothing.
 *
 * <p>DPDP Rule 3 requires a notice to tell the data principal how to reach the fiduciary and how to
 * complain, and the ISO/IEC TS 27560 receipt reproduces both. {@code V3} seeds fifteen entities and
 * sets neither field on any of them, so until somebody configures them every receipt the platform
 * issues names a null DPO and — where the notice carries no grievance route either — a null
 * grievance route. That is a Rule 3 defect on a statutory artefact, and it has been silent because
 * a null serialises out of the JSON without complaint.
 *
 * <p>{@link EntityStore#resolveContacts} walks the parent chain, so setting the two fields once on
 * the group root answers for every subsidiary that has not set its own. This check says whether
 * anybody has.
 *
 * <p><strong>WARN rather than a refusal to start.</strong> A missing contact makes receipts
 * incomplete; it does not make decisions wrong, and taking the decision API out of service over a
 * configuration gap would turn a documentation failure into an outage. It is also the state every
 * development and test environment is legitimately in. The pepper, by contrast, is a hard gate —
 * the distinction is whether running without it corrupts evidence or merely leaves it thin.
 */
@Component
public class EntityContactCheck {

    private static final Logger log = LoggerFactory.getLogger(EntityContactCheck.class);

    private final EntityStore entities;

    public EntityContactCheck(EntityStore entities) {
        this.entities = entities;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        List<String> incomplete = new ArrayList<>();
        for (EntityStore.FiduciaryEntity entity : entities.findAll()) {
            if (!entity.active()) {
                continue;
            }
            EntityStore.Contacts contacts = entities.resolveContacts(entity.entityId());
            if (!contacts.complete()) {
                incomplete.add(entity.entityId()
                        + (contacts.dpoContact() == null ? " [no DPO contact]" : "")
                        + (contacts.grievanceUri() == null ? " [no grievance route]" : ""));
            }
        }

        if (incomplete.isEmpty()) {
            return;
        }
        log.warn("""
                        {} active entit(ies) publish no contact point after inheritance: {}.

                        Every consent receipt issued for these entities carries a null DPO contact \
                        or a null grievance route, which DPDP Rule 3 requires the data principal to \
                        be given. Contacts resolve up parent_entity_id, so setting them once on the \
                        group root answers for every subsidiary that has not set its own:

                          update fiduciary_entity set dpo_contact = ?, grievance_uri = ? \
                        where entity_id = 'UDS';""",
                incomplete.size(), incomplete);
    }
}
