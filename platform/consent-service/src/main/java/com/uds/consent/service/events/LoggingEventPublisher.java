package com.uds.consent.service.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes outbox messages to the log instead of a broker.
 *
 * <p>The default, so that a developer machine and the test suite need nothing running. Fine for
 * development and for the Denave pilot before downstream consumers exist; not fine once a dialer
 * depends on hearing about withdrawals, because a log line is not a delivery guarantee.
 */
@Component
@ConditionalOnProperty(name = "uds.consent.events.publisher", havingValue = "log",
        matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    public LoggingEventPublisher() {
        log.warn("consent events are being written to the log, not published to a broker. "
                + "Set uds.consent.events.publisher=kafka before any downstream system relies on "
                + "hearing about withdrawals.");
    }

    @Override
    public void publish(String topic, String key, String payload) {
        log.info("consent-event topic={} key={} payload={}", topic, key, payload);
    }
}
