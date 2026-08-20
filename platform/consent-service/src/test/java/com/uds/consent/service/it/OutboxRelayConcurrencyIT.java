package com.uds.consent.service.it;

import com.uds.consent.ledger.store.OutboxStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two relays draining at once take disjoint batches.
 *
 * <p>The shipped deployment runs three replicas and every one of them schedules the relay.
 * {@code OutboxRelay} — unlike the seven sweepers — takes no {@code SweepLock}, and until
 * {@link OutboxStore#claimUnpublished(int)} existed the batch was selected with no lock at all. So
 * three relays drained the same rows every two seconds: every subscriber received each event up to
 * three times as a matter of course, and {@code webhook_delivery} — the row that proves a
 * withdrawal <em>arrived</em> — carried up to three rows per attempt.
 *
 * <p>The class javadoc conceded duplicate delivery for the crash window. It never described
 * systematic triplication as the steady state, and no operations document did either. An evidence
 * table that over-counts is a poor foundation for the proof Phase 17 built on it.
 *
 * <p><strong>The fix is {@code for update skip locked}, not a {@code SweepLock}</strong> — an
 * advisory lock would serialise fan-out onto one instance, which is a throughput change. This lets
 * all three replicas work, on rows none of the others can see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "uds.consent.events.relay-interval=PT1H")
class OutboxRelayConcurrencyIT extends PostgresIntegrationTest {

    @Autowired
    private OutboxStore outbox;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("two concurrent claims over the same backlog return disjoint batches")
    void concurrentClaimsDoNotOverlap() throws Exception {
        String topic = "uds.consent.events";
        String key = "relay-it|" + UUID.randomUUID();
        for (int i = 0; i < 6; i++) {
            outbox.enqueue(topic, key, Map.of("eventType", "GRANTED", "n", i));
        }

        // The first claim is held open inside its transaction — which is exactly what the relay
        // does while it publishes — so the second must find those rows locked and skip them.
        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);
        AtomicReference<List<OutboxStore.PendingMessage>> firstBatch = new AtomicReference<>();
        AtomicReference<List<OutboxStore.PendingMessage>> secondBatch = new AtomicReference<>();

        Thread first = new Thread(() -> transactions.executeWithoutResult(status -> {
            firstBatch.set(outbox.claimUnpublished(3));
            firstHasClaimed.countDown();
            try {
                secondHasFinished.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        first.start();

        assertThat(firstHasClaimed.await(20, TimeUnit.SECONDS)).isTrue();
        transactions.executeWithoutResult(status -> secondBatch.set(outbox.claimUnpublished(3)));
        secondHasFinished.countDown();
        first.join(20_000);

        Set<Long> firstIds = firstBatch.get().stream()
                .map(OutboxStore.PendingMessage::id).collect(java.util.stream.Collectors.toSet());
        Set<Long> secondIds = secondBatch.get().stream()
                .map(OutboxStore.PendingMessage::id).collect(java.util.stream.Collectors.toSet());

        assertThat(firstIds).isNotEmpty();
        assertThat(secondIds).isNotEmpty();
        // The property, and the only one worth asserting: no message is handed to two relays.
        // Asserting that a lock was taken would pass with the lock taken on the wrong rows.
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Test
    @DisplayName("an unlocked read still sees everything, so inspection is not broken by the claim")
    void theUnlockedReadIsUnchanged() {
        String topic = "uds.consent.events";
        String key = "relay-it-read|" + UUID.randomUUID();
        outbox.enqueue(topic, key, Map.of("eventType", "WITHDRAWN"));

        // fetchUnpublished stays a plain read for tests and for inspection. Keeping both is what
        // lets the claim be transactional without every existing caller needing a transaction —
        // and the javadoc on each says which is which, because a reader who drives publication
        // from the unlocked one reintroduces the triplication.
        assertThat(outbox.fetchUnpublished(1000))
                .anyMatch(m -> m.eventKey().equals(key));
    }
}
