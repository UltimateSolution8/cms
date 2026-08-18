package com.uds.consent.core.audit;

/**
 * The API credential serving the request on this thread.
 *
 * <p>Exists so that {@code admin_audit_event} can record both the human who acted and the
 * credential they acted with, without threading a second parameter through some forty call sites
 * across seven services that already take an {@code actorId}. That churn would have buried the
 * change worth reviewing — the refusal on unattributed mutations — in a diff of mechanical edits.
 *
 * <p><strong>Why the two are not the same thing.</strong> {@code compliance-console} is one
 * credential held by a compliance team. Until now the audit table recorded only that, so it could
 * say a purpose was retired and not by whom — and because the table is append-only, that ambiguity
 * is permanent. The credential is still worth recording: it is the fact the platform can verify,
 * where the human is the fact the caller asserts.
 *
 * <p><strong>Thread local, and therefore requiring discipline.</strong> Set by a servlet filter on
 * every request and cleared in its {@code finally}, exactly as {@code CorrelationIdFilter} handles
 * the correlation id. Two failure modes follow and both are handled deliberately: a value that
 * outlives its request would attribute the next caller's action to the previous one, which the
 * clear prevents; and work that runs off the request thread — the sweepers, the outbox relay — sees
 * no value at all, which is correct, because those write their own literal actor and have no
 * credential behind them.
 *
 * <p>Not inheritable. An {@code InheritableThreadLocal} would leak a caller's identity into any
 * pool thread spawned during their request and keep it there for whatever ran next.
 */
public final class CallerContext {

    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();

    private CallerContext() {
    }

    /** Binds the authenticated credential to this thread for the duration of one request. */
    public static void setClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            CLIENT_ID.remove();
        } else {
            CLIENT_ID.set(clientId);
        }
    }

    /**
     * The credential serving this thread, or null off a request.
     *
     * <p>Null is a real answer rather than a missing one: a sweeper writing a retention action has
     * no credential behind it, and inventing "system" here would make a scheduled job
     * indistinguishable from a caller who forgot to authenticate.
     */
    public static String clientId() {
        return CLIENT_ID.get();
    }

    /** Clears the binding. Must run in a {@code finally}; a stale value misattributes the next request. */
    public static void clear() {
        CLIENT_ID.remove();
    }
}
