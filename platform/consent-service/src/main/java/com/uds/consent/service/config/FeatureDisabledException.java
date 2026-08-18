package com.uds.consent.service.config;

/**
 * A route belonging to a regulatory surface this deployment has not switched on.
 *
 * <p>Answered as 404 rather than 403, and the difference is not cosmetic. 403 says the thing exists
 * and you may not have it, which invites an integrator to go and ask for the permission — and there
 * is no permission to be had, because the obligation does not reach UDS yet. 404 says there is
 * nothing here, which is true.
 *
 * <p>The message names the flag, because the person most likely to meet this is an operator who
 * turned a feature on in one environment and not another, and telling them which property to set
 * is the whole difference between a two-minute fix and a morning.
 *
 * @see PlatformProperties.Features
 */
public class FeatureDisabledException extends RuntimeException {

    private final String property;

    public FeatureDisabledException(String property, String what) {
        super(what + " is not enabled in this deployment. Set " + property + "=true to enable it.");
        this.property = property;
    }

    /** The configuration property that would turn it on. */
    public String property() {
        return property;
    }
}
