package com.uds.consent.service.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The asserted actor, at the boundary where it is read.
 *
 * <p>A unit test rather than part of {@code ActorAttributionIT} for one specific reason: the
 * sharpest thing this defends against cannot be sent over HTTP. A header containing a carriage
 * return is rejected by the JDK's own HTTP client before it leaves the process, which is a useful
 * second barrier and makes the case untestable from the outside — and "untestable from the outside"
 * is not the same as "cannot happen", because the header could arrive from a proxy, a rewritten
 * request, or a future non-JDK client.
 *
 * <p>What it defends against is log forging. The actor is echoed into log lines on several paths,
 * so a value carrying a newline and a plausible-looking timestamp lets a caller write whatever they
 * like into the service log — which is the log an incident is reconstructed from, and the one place
 * where fabricated evidence is most likely to be believed.
 */
class ActorTest {

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("control characters are stripped, so an actor cannot forge a log line")
    void controlCharactersAreRemoved() {
        bindRequestWithActor("mallory\r\n2026-08-17 09:00:00 INFO  purpose retired by nobody");

        String actorId = Actor.of(authenticated()).actorId();

        assertThat(actorId).doesNotContain("\n").doesNotContain("\r");
        assertThat(actorId).startsWith("mallory");
    }

    @Test
    @DisplayName("an over-long actor is truncated before it reaches an append-only table")
    void lengthIsBounded() {
        // 128 characters. The value is written to admin_audit_event, which cannot be edited or
        // deleted afterwards — so an unbounded header is a way to put arbitrary bulk into evidence
        // permanently.
        bindRequestWithActor("a".repeat(5_000));

        assertThat(Actor.of(authenticated()).actorId().length()).isEqualTo(128);
    }

    @Test
    @DisplayName("a blank or whitespace-only actor falls back to the credential on a read")
    void blankFallsBackToTheClient() {
        // Falls back rather than refuses, because reads legitimately arrive without an actor. What
        // must not happen is an empty string reaching the ledger and looking like an attribution.
        bindRequestWithActor("   ");

        assertThat(Actor.of(authenticated()).actorId()).isEqualTo("compliance-console");
    }

    @Test
    @DisplayName("a mutation with no actor is refused, naming the header")
    void mutationsRefuseWithoutAnActor() {
        bindRequestWithActor(null);

        assertThatThrownBy(() -> Actor.required(authenticated()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-UDS-Actor");
    }

    @Test
    @DisplayName("off a request there is no actor and nothing throws")
    void worksOutsideARequest() {
        // Sweepers and the outbox relay run on their own threads and pass their own literal actor.
        // Resolving here must not blow up or invent one — an exception on a scheduled path would
        // turn a missing header into a failed statutory sweep.
        RequestContextHolder.resetRequestAttributes();

        assertThat(Actor.of(authenticated()).actorId()).isEqualTo("compliance-console");
    }

    private static void bindRequestWithActor(String actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) {
            request.addHeader(Actor.HEADER, actor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static Authentication authenticated() {
        return new UsernamePasswordAuthenticationToken("compliance-console", "n/a", List.of());
    }
}
