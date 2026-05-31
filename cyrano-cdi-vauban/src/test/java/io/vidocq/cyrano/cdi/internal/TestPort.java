/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.cyrano.cdi.internal;

/**
 * Fixed HTTP port used jointly by the JDK inline server and by
 * {@code @RegisterRestClient(baseUri=...)} in
 * {@link CyranoRestClientCdiIntegrationTest}.
 *
 * <p>A Java annotation {@code @Retention(RUNTIME)} requires that values
 * of its members be <em>compile-time constants</em>. We therefore choose
 * a high port (excluding ephemeral/current user) instead of allocating
 * dynamiquement via {@code new ServerSocket(0)}.</p>
 */
final class TestPort {

    /** Test port — must be free on the target machine. */
    static final int SERVER_PORT = 18857;

    private TestPort() {}
}


