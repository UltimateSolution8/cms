package com.uds.consent.core.model;

import java.time.Duration;

/**
 * How far a caller's clock is allowed to be wrong before the platform stops believing it.
 *
 * <p>One value, in one place, because there were two places about to hold it. Several thousand
 * Android devices across five countries have clock skew; a field agent's tablet syncing after a day
 * offline has more. Every instant the platform accepts from outside — {@code occurredAt} on a
 * consent event, {@code receivedAt} on a rights request — has to decide how much of that to absorb
 * silently and where to start refusing, and two different answers to that question would be two
 * different definitions of "now" inside one evidence plane.
 *
 * <p>Five minutes is chosen against ordinary NTP drift rather than against a threat model. It is
 * wide enough that no correctly configured client is ever refused, and narrow enough that a
 * deliberately forward-dated instant — which moves a statutory deadline outward — cannot hide
 * inside it.
 */
public final class ClockTolerance {

    /**
     * The window either side of the server's clock within which a caller's instant is taken at
     * face value.
     *
     * <p>Used by the artefact projector to decide whether two events are genuinely ordered or
     * merely skewed apart, and by rights intake to decide whether a {@code receivedAt} in the
     * future is a wrong clock or a claim about the future.
     */
    public static final Duration SKEW = Duration.ofMinutes(5);

    private ClockTolerance() {
    }
}
