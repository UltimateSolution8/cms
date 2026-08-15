package com.uds.consent.service.sweeper;

import com.uds.consent.core.model.ActorType;
import com.uds.consent.core.model.CaptureMethod;
import com.uds.consent.core.model.ConsentEvent;
import com.uds.consent.core.model.ConsentEventType;
import com.uds.consent.ledger.service.ConsentLedger;
import com.uds.consent.ledger.store.ConsentEventStore;
import com.uds.consent.service.config.PlatformProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns lapsed consent into durable EXPIRED events.
 *
 * <p>The decision engine already treats a lapsed consent as expired without waiting for this, and
 * that is the important part — a TRAI transactional consent that ran out an hour ago is gone
 * whether or not a batch job has run. What the sweeper adds is the evidence: the ledger should
 * tell the whole story, and "we stopped relying on this on the eighth day" needs a record, not an
 * inference from an absent one.
 */
@Component
public class ExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    private final ConsentEventStore events;
    private final ConsentLedger ledger;
    private final PlatformProperties properties;

    public ExpirySweeper(ConsentEventStore events, ConsentLedger ledger,
                         PlatformProperties properties) {
        this.events = events;
        this.ledger = ledger;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${uds.consent.sweeper.expiry-interval:PT5M}")
    public void sweep() {
        if (!properties.getSweeper().isExpiryEnabled()) {
            return;
        }
        int written = sweepAsOf(Instant.now());
        if (written > 0) {
            log.info("wrote {} EXPIRED event(s)", written);
        }
    }

    /**
     * Expires everything lapsed as at {@code asOf}.
     *
     * <p>Separate from the scheduled entry point so that tests can drive it with a controlled
     * clock. Time-dependent behaviour that can only be exercised by waiting is time-dependent
     * behaviour that does not get tested.
     *
     * @return number of events written
     */
    public int sweepAsOf(Instant asOf) {
        List<String[]> lapsed = events.findLapsedArtefacts(
                asOf, properties.getSweeper().getExpiryBatchSize());

        int written = 0;
        for (String[] key : lapsed) {
            String entityId = key[0];
            String subjectId = key[1];
            String purposeCode = key[2];

            ledger.record(new ConsentEvent(
                    UUID.randomUUID().toString(), entityId, subjectId, purposeCode, 0,
                    ConsentEventType.EXPIRED, null, null, null, null,
                    CaptureMethod.NOT_APPLICABLE, null, null, null,
                    asOf, null, null, ActorType.SYSTEM, "expiry-sweeper",
                    "consent validity window elapsed", null,
                    // Keyed on the subject, purpose and sweep instant so that two instances
                    // running the sweep concurrently write one event, not two.
                    "expiry:" + entityId + ':' + subjectId + ':' + purposeCode + ':' + asOf,
                    Map.of(), 0L, null, null));
            written++;
        }
        return written;
    }
}
