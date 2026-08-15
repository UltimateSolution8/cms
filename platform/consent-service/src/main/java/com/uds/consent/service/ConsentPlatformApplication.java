package com.uds.consent.service;

import com.uds.consent.service.config.PlatformProperties;
import com.uds.consent.service.config.SecurityConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UDS Consent &amp; Privacy Control Plane.
 *
 * <p>One deployable that carries all three planes: the control plane an administrator configures,
 * the enforcement plane every system asks before processing, and the append-only evidence plane
 * the burden of proof rests on. They are separated by package and by database guarantee rather
 * than by process, because at this group's scale a single service is easier to operate correctly
 * than three, and the boundaries that matter are enforced by the schema either way.
 *
 * <p>Splitting the decision path into its own service becomes worthwhile when load data says so —
 * most plausibly when Athena's dialer starts pre-flighting every call at peak. The architecture
 * does not stand in the way of that: the decision path already talks to the rest of the system
 * through {@code PolicyPorts}.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.uds.consent.service", "com.uds.consent.ledger"})
@EnableConfigurationProperties({
        PlatformProperties.class,
        SecurityConfiguration.ApiClientProperties.class})
@EnableScheduling
public class ConsentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConsentPlatformApplication.class, args);
    }
}
