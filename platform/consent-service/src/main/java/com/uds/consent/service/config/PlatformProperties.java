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

    private final Snapshot snapshot = new Snapshot();
    private final Sweeper sweeper = new Sweeper();
    private final Events events = new Events();

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

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public Sweeper getSweeper() {
        return sweeper;
    }

    public Events getEvents() {
        return events;
    }
}
