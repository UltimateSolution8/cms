package com.uds.consent.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Platform configuration.
 *
 * <p>Everything here changes behaviour that a regulator or an auditor might ask about, so each
 * setting is documented with what it affects rather than only what it is.
 */
@ConfigurationProperties(prefix = "uds.consent")
public class PlatformProperties {

    /**
     * Version stamp written onto every decision. Bump it whenever the purpose registry or a
     * jurisdiction rule changes, so that a decision taken last March can be reproduced against
     * the rules that were actually in force then.
     */
    private String policyVersion = "policy-2026.08.1";

    /**
     * Secret mixed into identifier hashes before they reach the ledger.
     *
     * <p>Must come from the KMS in any real deployment. The space of Indian mobile numbers is
     * small enough to enumerate exhaustively, so a bare hash of a phone number is reversible in
     * practice — the pepper is what stops an attacker holding a copy of the database from
     * recovering the numbers in it. Rotating it requires a planned re-hash of every stored
     * identifier, so treat it as versioned alongside the ledger itself.
     */
    private String identifierPepper = "";

    /** Calling code applied to national-format phone numbers during normalisation. */
    private String defaultCallingCode = "91";

    /**
     * How often the in-process purpose and application registries reload from the database.
     *
     * <p>This is the window during which two instances can disagree about policy after a publish.
     * The publishing path refreshes the instance that served it immediately, so the interval only
     * bounds how long the others lag. Shortening it costs a small query; lengthening it widens the
     * window in which a retired purpose is still being relied upon somewhere.
     */
    private Duration registryRefreshInterval = Duration.ofMinutes(5);

    private final Snapshot snapshot = new Snapshot();
    private final Sweeper sweeper = new Sweeper();
    private final Events events = new Events();
    private final Features features = new Features();
    private final RateLimit rateLimit = new RateLimit();
    private final Rights rights = new Rights();

    /**
     * Per-caller throughput ceilings.
     *
     * <p>The platform had none anywhere, including on the two routes that need no credential at
     * all. A dialer in a retry loop, a misconfigured cron, or anyone at all against
     * {@code GET /v1/notices/*} was an outage — and the outage takes the decision API with it,
     * which means every downstream system either stops calling or starts guessing.
     *
     * <p>Limits are per route class rather than global, because the classes have genuinely
     * different shapes. A batch scrub of a thousand identifiers is one request and a great deal of
     * work; a decision is one request and almost none. One number covering both would either
     * throttle the dialer's normal traffic or leave batch effectively unlimited.
     *
     * <p><strong>Per instance, not fleet-wide, and this is a real limitation rather than a
     * simplification worth glossing.</strong> Four replicas mean four times these numbers in
     * aggregate, and a caller whose requests land on one instance is limited four times harder
     * than one whose spread evenly. That is still enormously better than no limit — it bounds what
     * a single runaway client can do to a single instance, which is the failure being defended
     * against. Fleet-wide limiting needs shared state; when there is a Redis, the counter moves
     * there and nothing else about this changes.
     */
    public static class RateLimit {

        /** Off switch, for an environment fronted by a gateway that already limits. */
        private boolean enabled = true;

        /**
         * Distinct callers tracked before the least recently seen are evicted.
         *
         * <p>Bounded because unauthenticated routes are keyed by client IP, and an unbounded map
         * keyed by attacker-supplied values is a memory exhaustion primitive dressed as a defence.
         * Eviction under pressure means a caller may get a fresh bucket, which is the correct
         * failure direction: a limiter that runs the process out of heap has caused the outage it
         * was there to prevent.
         */
        private int maxTrackedCallers = 50_000;

        /**
         * The unauthenticated routes: {@code GET /v1/notices/*} and {@code GET /v1/keys}.
         *
         * <p>Keyed by IP, since there is no credential. Generous per second because a notice is
         * read by real people on real pages and a shared corporate NAT is one IP for a building.
         */
        private final Limit publicRoutes = new Limit(20, 60);

        /** {@code POST /v1/evaluate}. The hot path: high, because this is what normal looks like. */
        private final Limit decision = new Limit(200, 400);

        /**
         * {@code POST /v1/evaluate/batch}.
         *
         * <p>Low, and deliberately so. Each call already carries up to a thousand identifiers, so
         * ten per second is ten thousand decisions per second from one caller — far above any
         * legitimate scrub, and far below what an unbounded retry loop would attempt.
         */
        private final Limit batch = new Limit(10, 20);

        /** Consent writes, provenance, suppression opt-outs, rights intake. */
        private final Limit capture = new Limit(100, 200);

        /** The console. A human clicking, not a machine polling. */
        private final Limit admin = new Limit(50, 100);

        /** Anything not matched above. */
        private final Limit other = new Limit(100, 200);

        /**
         * Every request from one address, counted <em>before</em> authentication runs.
         *
         * <p>The limits above are per credential per route class and are enforced after Spring
         * Security, which means an invalid credential never reaches them: Phase 12 measured 500
         * bad-password requests producing 500 × 401 and zero 429s, at ~113 ms of BCrypt each. This
         * one runs in front of the security chain so that a flood is refused before it is priced.
         *
         * <p><strong>Deliberately loose, because it cannot tell callers apart.</strong> Running
         * before authentication means keying on the client address alone, and a corporate NAT — or
         * an ingress without {@code server.forward-headers-strategy} configured — is one address
         * for a whole building or a whole fleet. 400/s comfortably exceeds Denave's dialer at its
         * ordinary 200/s while still being two orders of magnitude below what a flood attempts.
         * <strong>This is a flood ceiling, not a fairness limit.</strong> Tightening it to make it
         * feel more protective is how a legitimate integrator gets refused; fairness between
         * callers belongs above, where the credential is known.
         */
        private final Limit preAuth = new Limit(400, 800);

        /**
         * A steady rate and the burst allowed above it.
         *
         * <p>A token bucket rather than a fixed window, because a fixed window lets a caller send
         * two full windows' worth across a boundary and calls it compliant — which is exactly the
         * shape a retry storm has.
         *
         * @param permitsPerSecond tokens added each second
         * @param burst            bucket capacity, so the most that can arrive at once
         */
        public static class Limit {

            private int permitsPerSecond;
            private int burst;

            public Limit(int permitsPerSecond, int burst) {
                this.permitsPerSecond = permitsPerSecond;
                this.burst = burst;
            }

            public int getPermitsPerSecond() {
                return permitsPerSecond;
            }

            public void setPermitsPerSecond(int permitsPerSecond) {
                this.permitsPerSecond = permitsPerSecond;
            }

            public int getBurst() {
                return burst;
            }

            public void setBurst(int burst) {
                this.burst = burst;
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxTrackedCallers() {
            return maxTrackedCallers;
        }

        public void setMaxTrackedCallers(int maxTrackedCallers) {
            this.maxTrackedCallers = maxTrackedCallers;
        }

        public Limit getPublicRoutes() {
            return publicRoutes;
        }

        public Limit getDecision() {
            return decision;
        }

        public Limit getBatch() {
            return batch;
        }

        public Limit getCapture() {
            return capture;
        }

        public Limit getAdmin() {
            return admin;
        }

        public Limit getOther() {
            return other;
        }

        public Limit getPreAuth() {
            return preAuth;
        }
    }

    /**
     * Regulatory surfaces built ahead of the obligation that triggers them, dark until it does.
     *
     * <p>Both of these are correct code for regimes that do not yet reach UDS, and both were being
     * carried as live, scheduled, route-bearing production surface. That is not free: it is review
     * budget on every release, test time on every build, and — worse — an operator seeing a queue
     * or an endpoint for an obligation nobody in the group owes, which is how a real one stops
     * standing out.
     *
     * <p>The code stays. The migrations stay: dropping {@code consent_reconfirmation} or the
     * Consent Manager register would make re-enabling them a schema change rather than a property
     * change, and a table costs nothing to keep. What changes is that neither runs, and neither is
     * reachable, until somebody sets a flag — which is a decision with a date and an owner rather
     * than a default nobody chose.
     *
     * <p>Each flag records what turns it back on. That is the part worth writing down: a disabled
     * feature with no re-enabling condition is a feature that gets deleted by the next person to
     * read the file, or left off past the day it became mandatory.
     */
    public static class Features {

        /**
         * Korea's two-year re-confirmation queue (Network Act Enforcement Decree Art. 62-3).
         *
         * <p>Off. The obligation is real and the implementation is sound, but Art. 62-3 is silent
         * on what follows from a recipient who never answers, and that silence has now been
         * recorded as absent from primary text three consecutive times. Denave Korea is one entity
         * of fifteen and is not the pilot.
         *
         * <p><strong>Turn on when:</strong> Korean counsel confirms the consequence of silence, or
         * Denave Korea begins consent-based marketing at volume — whichever comes first. Nothing
         * is lost by the delay: {@code ReconfirmationStore.findDue} derives the queue from consent
         * dates already in the ledger, so switching it on later raises every obligation that
         * accrued while it was off.
         */
        private boolean koreaReconfirmation = false;

        /**
         * The Consent Manager relay surface (DPDP Rules 2025, Rule 4).
         *
         * <p>Off. Three routes and a role for an intermediary ecosystem that does not exist yet:
         * registration with the Board is deferred, and {@code consent_manager.public_key} is
         * populated but unverified because the Board has published no signing standard. An open
         * relay that accepts consent on a principal's behalf, authenticated by HTTP Basic and
         * verifying no signature, is the widest write surface the platform has.
         *
         * <p><strong>Turn on when:</strong> UDS registers with the Board <em>and</em> a signing
         * standard exists to verify against. Both, not either — registration without verification
         * is the surface without the control.
         */
        private boolean consentManagerRelay = false;

        public boolean isKoreaReconfirmation() {
            return koreaReconfirmation;
        }

        public void setKoreaReconfirmation(boolean koreaReconfirmation) {
            this.koreaReconfirmation = koreaReconfirmation;
        }

        public boolean isConsentManagerRelay() {
            return consentManagerRelay;
        }

        public void setConsentManagerRelay(boolean consentManagerRelay) {
            this.consentManagerRelay = consentManagerRelay;
        }
    }

    public static class Snapshot {

        /**
         * How long a signed snapshot stays usable on a device before it must be refreshed.
         *
         * <p>A genuine trade-off. Long-lived snapshots keep a field force working through a day
         * with no connectivity; short-lived ones bound how long a withdrawal can go unseen by a
         * device that has been offline. Fifteen minutes suits connected apps; the Denave field
         * apps override this upward, and accept a longer window in exchange for working at all
         * in places with no signal.
         */
        private Duration validity = Duration.ofMinutes(15);

        /**
         * Base64 PKCS#8 Ed25519 private key used to sign snapshots. Empty generates an ephemeral
         * key at startup, which is fine for local development and unacceptable anywhere else —
         * every snapshot issued before a restart stops verifying after it.
         */
        private String signingKeyBase64 = "";

        /**
         * Base64 X.509 Ed25519 public key matching {@link #signingKeyBase64}, published to SDKs
         * for offline verification. Required whenever a private key is configured: the JDK's
         * Ed25519 private key encoding does not carry the public point, and deriving it would
         * mean hand-rolling scalar multiplication in the component the entire offline enforcement
         * story depends on.
         */
        private String verificationKeyBase64 = "";

        /** Key identifier published with each snapshot so that rotation does not break verification. */
        private String signingKeyId = "dev-ephemeral";

        public String getVerificationKeyBase64() {
            return verificationKeyBase64;
        }

        public void setVerificationKeyBase64(String verificationKeyBase64) {
            this.verificationKeyBase64 = verificationKeyBase64;
        }

        public Duration getValidity() {
            return validity;
        }

        public void setValidity(Duration validity) {
            this.validity = validity;
        }

        public String getSigningKeyBase64() {
            return signingKeyBase64;
        }

        public void setSigningKeyBase64(String signingKeyBase64) {
            this.signingKeyBase64 = signingKeyBase64;
        }

        public String getSigningKeyId() {
            return signingKeyId;
        }

        public void setSigningKeyId(String signingKeyId) {
            this.signingKeyId = signingKeyId;
        }
    }

    public static class Sweeper {

        /** Whether the expiry sweeper runs. Disabled in tests so that time is controlled. */
        private boolean expiryEnabled = true;

        /** Maximum lapsed artefacts turned into EXPIRED events per pass. */
        private int expiryBatchSize = 500;

        /** Whether the nightly ledger integrity sweep runs. */
        private boolean integrityEnabled = true;

        /** Chains verified per page during the integrity sweep. */
        private int integrityPageSize = 200;

        /**
         * Whether the projection reconciliation sweep runs.
         *
         * <p>On by default. It is the only control that looks at {@code consent_artefact} — the row
         * every decision reads — and asks whether it still agrees with the chain that produced it.
         */
        private boolean projectionReconciliationEnabled = true;

        /** Subjects per page in the reconciliation sweep. Same shape and reason as the integrity one. */
        private int projectionReconciliationPageSize = 200;

        /**
         * Divergences named individually in the log before the sweep summarises the rest.
         *
         * <p>A projector defect affects every subject at once, so an unbounded log would be
         * megabytes and the first line — the one an operator needs — would be buried.
         */
        private int projectionReconciliationReportLimit = 20;

        /**
         * Divergences the sweep <strong>retains</strong> for the entity-scoped admin route.
         *
         * <p>The cap is on what is held, never on what is counted. A systemic projector defect
         * produces one divergence per artefact, and an unbounded {@code ArrayList} of them sits in
         * memory on the instance that ran the sweep until the next one replaces it. The count in
         * {@code /projection/last} stays exact — a capped count would make a systemic defect look
         * smaller than it is, which is the opposite of what the control is for.
         */
        private int projectionDivergenceCap = 500;

        /** Divergences returned per page by {@code GET /v1/admin/projection/divergences}. */
        private int projectionDivergencePageSize = 100;

        /** Whether the rights-request SLA sweep runs. Disabled in tests so time is controlled. */
        private boolean rightsSlaEnabled = true;

        /** Open requests examined per SLA pass, breached and approaching each. */
        private int rightsSlaBatchSize = 500;

        /**
         * How far ahead a deadline is warned about.
         *
         * <p>Three days by default, which is a compromise. Korea's ten-day period leaves little
         * room, so a longer window would warn about requests that have barely been opened; a
         * shorter one would give an Indian or European request no useful notice at all. Entities
         * operating predominantly under one regime should set this to suit it.
         */
        private Duration rightsSlaWarningWindow = Duration.ofDays(3);

        public boolean isExpiryEnabled() {
            return expiryEnabled;
        }

        public void setExpiryEnabled(boolean expiryEnabled) {
            this.expiryEnabled = expiryEnabled;
        }

        public int getExpiryBatchSize() {
            return expiryBatchSize;
        }

        public void setExpiryBatchSize(int expiryBatchSize) {
            this.expiryBatchSize = expiryBatchSize;
        }

        public boolean isIntegrityEnabled() {
            return integrityEnabled;
        }

        public void setIntegrityEnabled(boolean integrityEnabled) {
            this.integrityEnabled = integrityEnabled;
        }

        public int getIntegrityPageSize() {
            return integrityPageSize;
        }

        public void setIntegrityPageSize(int integrityPageSize) {
            this.integrityPageSize = integrityPageSize;
        }

        public boolean isProjectionReconciliationEnabled() {
            return projectionReconciliationEnabled;
        }

        public void setProjectionReconciliationEnabled(boolean projectionReconciliationEnabled) {
            this.projectionReconciliationEnabled = projectionReconciliationEnabled;
        }

        public int getProjectionReconciliationPageSize() {
            return projectionReconciliationPageSize;
        }

        public void setProjectionReconciliationPageSize(int projectionReconciliationPageSize) {
            this.projectionReconciliationPageSize = projectionReconciliationPageSize;
        }

        public int getProjectionReconciliationReportLimit() {
            return projectionReconciliationReportLimit;
        }

        public int getProjectionDivergenceCap() {
            return projectionDivergenceCap;
        }

        public void setProjectionDivergenceCap(int projectionDivergenceCap) {
            this.projectionDivergenceCap = projectionDivergenceCap;
        }

        public int getProjectionDivergencePageSize() {
            return projectionDivergencePageSize;
        }

        public void setProjectionDivergencePageSize(int projectionDivergencePageSize) {
            this.projectionDivergencePageSize = projectionDivergencePageSize;
        }

        public void setProjectionReconciliationReportLimit(int projectionReconciliationReportLimit) {
            this.projectionReconciliationReportLimit = projectionReconciliationReportLimit;
        }

        public boolean isRightsSlaEnabled() {
            return rightsSlaEnabled;
        }

        public void setRightsSlaEnabled(boolean rightsSlaEnabled) {
            this.rightsSlaEnabled = rightsSlaEnabled;
        }

        public int getRightsSlaBatchSize() {
            return rightsSlaBatchSize;
        }

        public void setRightsSlaBatchSize(int rightsSlaBatchSize) {
            this.rightsSlaBatchSize = rightsSlaBatchSize;
        }

        public Duration getRightsSlaWarningWindow() {
            return rightsSlaWarningWindow;
        }

        public void setRightsSlaWarningWindow(Duration rightsSlaWarningWindow) {
            this.rightsSlaWarningWindow = rightsSlaWarningWindow;
        }

        /** Whether the breach SLA sweep runs. Disabled in tests so time is controlled. */
        private boolean breachSlaEnabled = true;

        /** Outstanding notification obligations examined per pass. */
        private int breachSlaBatchSize = 200;

        public boolean isBreachSlaEnabled() {
            return breachSlaEnabled;
        }

        public void setBreachSlaEnabled(boolean breachSlaEnabled) {
            this.breachSlaEnabled = breachSlaEnabled;
        }

        public int getBreachSlaBatchSize() {
            return breachSlaBatchSize;
        }

        public void setBreachSlaBatchSize(int breachSlaBatchSize) {
            this.breachSlaBatchSize = breachSlaBatchSize;
        }

        /** Whether the retention sweep runs. Disabled in tests so time is controlled. */
        private boolean retentionEnabled = true;

        /** Subjects proposed for erasure per activity per pass. */
        private int retentionBatchSize = 500;

        /**
         * How far before the erasure date the data principal is told.
         *
         * <p>DPDP Rules 2025, Rule 8 sets a floor of forty-eight hours. The default is a week,
         * because the point of the intimation is to give somebody a real chance to act on it and
         * a notice arriving on a Friday evening for a Sunday erasure meets the letter of the Rule
         * and none of its purpose. Shortening this below 48 hours is a breach, not a tuning
         * decision.
         */
        private Duration retentionNoticeLeadTime = Duration.ofDays(7);

        public boolean isRetentionEnabled() {
            return retentionEnabled;
        }

        public void setRetentionEnabled(boolean retentionEnabled) {
            this.retentionEnabled = retentionEnabled;
        }

        public int getRetentionBatchSize() {
            return retentionBatchSize;
        }

        public void setRetentionBatchSize(int retentionBatchSize) {
            this.retentionBatchSize = retentionBatchSize;
        }

        public Duration getRetentionNoticeLeadTime() {
            return retentionNoticeLeadTime;
        }

        /** Korea's Art. 62-3 re-confirmation queue. Harmless elsewhere; it raises nothing. */
        private boolean reconfirmationEnabled = true;

        private int reconfirmationBatchSize = 500;

        public boolean isReconfirmationEnabled() {
            return reconfirmationEnabled;
        }

        public void setReconfirmationEnabled(boolean reconfirmationEnabled) {
            this.reconfirmationEnabled = reconfirmationEnabled;
        }

        public int getReconfirmationBatchSize() {
            return reconfirmationBatchSize;
        }

        public void setReconfirmationBatchSize(int reconfirmationBatchSize) {
            this.reconfirmationBatchSize = reconfirmationBatchSize;
        }

        public void setRetentionNoticeLeadTime(Duration retentionNoticeLeadTime) {
            this.retentionNoticeLeadTime = retentionNoticeLeadTime;
        }
    }

    public static class Events {

        /**
         * Where outbox messages go: {@code log} or {@code kafka}. Defaults to log so that a
         * developer machine needs no broker; production sets kafka.
         */
        private String publisher = "log";

        /** Topic consent events are published to. */
        private String topic = "uds.consent.events";

        /** Messages drained from the outbox per relay pass. */
        private int relayBatchSize = 200;

        public String getPublisher() {
            return publisher;
        }

        public void setPublisher(String publisher) {
            this.publisher = publisher;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public int getRelayBatchSize() {
            return relayBatchSize;
        }

        public void setRelayBatchSize(int relayBatchSize) {
            this.relayBatchSize = relayBatchSize;
        }
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public String getIdentifierPepper() {
        return identifierPepper;
    }

    public void setIdentifierPepper(String identifierPepper) {
        this.identifierPepper = identifierPepper;
    }

    public String getDefaultCallingCode() {
        return defaultCallingCode;
    }

    public void setDefaultCallingCode(String defaultCallingCode) {
        this.defaultCallingCode = defaultCallingCode;
    }

    public Duration getRegistryRefreshInterval() {
        return registryRefreshInterval;
    }

    public void setRegistryRefreshInterval(Duration registryRefreshInterval) {
        this.registryRefreshInterval = registryRefreshInterval;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public Sweeper getSweeper() {
        return sweeper;
    }

    public Events getEvents() {
        return events;
    }

    public Features getFeatures() {
        return features;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Rights getRights() {
        return rights;
    }

    /**
     * Bounds on rights-request intake.
     *
     * <p>One setting, because there is one thing here a deployment might legitimately want to
     * change. Everything else about the statutory clock is a statutory period and belongs in
     * {@code StatutoryClock} where it is cited, not in configuration where it can be edited into
     * something the group did not publish.
     */
    public static class Rights {

        /**
         * How far in the past a caller-supplied {@code receivedAt} may be.
         *
         * <p>Not a data-quality check. An instant far enough in the past files a request that is
         * already beyond its deadline on the day it arrives, which writes a statutory breach into
         * the group's own record without one having happened — the mirror image of forward-dating,
         * and the direction somebody inside would use. Ninety days is chosen to sit at India's
         * Rule 14(3) ceiling: a genuine late entry inside that window still leaves a deadline the
         * group could in principle have met, and one outside it does not.
         *
         * <p>Raising this does not make late requests acceptable; it makes them enterable. A group
         * that needs to record one older than this should record it as the breach it is, through
         * an explicit escalation, rather than through an intake call that looks like every other.
         */
        private Duration maxBackdate = Duration.ofDays(90);

        public Duration getMaxBackdate() {
            return maxBackdate;
        }

        public void setMaxBackdate(Duration maxBackdate) {
            this.maxBackdate = maxBackdate;
        }
    }
}
