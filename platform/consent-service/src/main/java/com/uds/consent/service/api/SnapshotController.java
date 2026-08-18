package com.uds.consent.service.api;

import com.uds.consent.core.crypto.CanonicalJson;
import com.uds.consent.core.snapshot.ConsentSnapshot;
import com.uds.consent.core.snapshot.SignedSnapshot;
import com.uds.consent.service.SigningKeys;
import com.uds.consent.service.SnapshotService;
import com.uds.consent.service.api.dto.ConsentApi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Issues signed snapshots to devices, and publishes the keys that verify them.
 *
 * <p>A field app fetches a snapshot when it starts, when the user changes, and whenever the
 * policy version it holds goes stale — then works from it, offline, for the rest of the shift.
 */
@RestController
@RequestMapping("/v1")
public class SnapshotController {

    private final SnapshotService snapshots;
    private final SigningKeys keys;

    public SnapshotController(SnapshotService snapshots, SigningKeys keys) {
        this.snapshots = snapshots;
        this.keys = keys;
    }

    /**
     * Issues a snapshot for a subject.
     *
     * <p>Returned as a JWS compact token. Every SDK the group needs already has a JWS
     * implementation, so verification on the device is a library call rather than bespoke parsing
     * that each platform gets subtly wrong.
     */
    @GetMapping("/snapshot/{entityId}/{subjectId}")
    @PreAuthorize("hasAnyRole('DECISION', 'CAPTURE', 'ADMIN')")
    public ConsentApi.SnapshotResponse issue(@PathVariable String entityId,
                                             @PathVariable String subjectId) {
        Instant now = Instant.now();
        SignedSnapshot signed = snapshots.issue(entityId, subjectId, now);
        ConsentSnapshot payload = decodePayload(signed);
        return new ConsentApi.SnapshotResponse(signed.compact(), signed.keyId(),
                payload.issuedAt(), payload.expiresAt());
    }

    /**
     * The keys a device should trust.
     *
     * <p>Unauthenticated on purpose: a public verification key is public, and requiring a
     * credential to fetch it would mean a device that has lost its credential also loses the
     * ability to verify snapshots it already holds — turning an authentication problem into an
     * enforcement one.
     */
    @GetMapping("/keys")
    public List<ConsentApi.VerificationKey> verificationKeys() {
        // Every key still trusted, not just the one this instance signs with. That is what makes a
        // rotation a non-event: a device holding a snapshot signed minutes before the change can
        // still find the key that verifies it, for as long as the outgoing key stays RETIRED
        // rather than COMPROMISED. Returning one key — which is what this did — meant every
        // rotation silently stopped enforcement on every device mid-shift.
        return keys.verificationKeys().entrySet().stream()
                .map(entry -> new ConsentApi.VerificationKey(entry.getKey(), "Ed25519",
                        Base64.getEncoder().encodeToString(entry.getValue().getEncoded())))
                .toList();
    }

    /** The purposes a snapshot carries, so a client can size its local store. */
    @GetMapping("/snapshot/purposes")
    @PreAuthorize("hasAnyRole('DECISION', 'CAPTURE', 'ADMIN')")
    public Set<String> snapshotPurposes() {
        return snapshots.snapshotPurposes();
    }

    private static ConsentSnapshot decodePayload(SignedSnapshot signed) {
        String payload = new String(Base64.getUrlDecoder().decode(signed.segments()[1]),
                StandardCharsets.UTF_8);
        return CanonicalJson.parse(payload, ConsentSnapshot.class);
    }
}
