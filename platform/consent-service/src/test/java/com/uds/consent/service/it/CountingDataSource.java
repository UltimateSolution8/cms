package com.uds.consent.service.it;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link DataSource} that counts the statements prepared through it.
 *
 * <p>Exists for one assertion: how many database round trips a single decision costs. That number
 * is the one that actually regresses — a new lookup added to the hot path, a cache that stopped
 * caching, an N+1 introduced inside a loop — and unlike a latency figure it is identical on a
 * laptop, on CI and in production. A latency test alone tells you something got slower; the count
 * tells you what.
 *
 * <p>Counts {@code prepareStatement}, {@code prepareCall} and {@code createStatement} rather than
 * executions. Spring's {@code JdbcClient} prepares one statement per query, so for this codebase the
 * two are the same number, and preparing is the point at which the driver is about to talk to the
 * server.
 *
 * <p>Deliberately not a Spring bean of its own: it wraps whatever DataSource the application built,
 * connection pool and all, so what is measured is the real path rather than a parallel one
 * assembled for the test.
 */
public class CountingDataSource extends DelegatingDataSource {

    private final AtomicInteger statements = new AtomicInteger();
    private volatile boolean counting;

    public CountingDataSource(DataSource target) {
        super(target);
    }

    /** Starts counting from zero. Off by default so that context start-up is not measured. */
    public void reset() {
        statements.set(0);
        counting = true;
    }

    public int count() {
        return statements.get();
    }

    public void stop() {
        counting = false;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection delegate) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (counting && ("prepareStatement".equals(name) || "prepareCall".equals(name)
                        || "createStatement".equals(name))) {
                    statements.incrementAndGet();
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    // Unwrapped, or every SQLException the pool throws would reach the caller as an
                    // UndeclaredThrowableException and a genuine database failure inside a test
                    // would be unreadable.
                    throw e.getCause();
                }
            }
        };
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }
}
