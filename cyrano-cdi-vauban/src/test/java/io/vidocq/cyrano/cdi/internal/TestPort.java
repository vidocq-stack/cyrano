/*
 * Copyright (c) 2026 Vidocq contributors. Apache License 2.0.
 */
package io.vidocq.cyrano.cdi.internal;

/**
 * Port HTTP fixe utilisé conjointement par le serveur JDK inline et par
 * {@code @RegisterRestClient(baseUri=...)} dans
 * {@link CyranoRestClientCdiIntegrationTest}.
 *
 * <p>Une {@code @Retention(RUNTIME)} d'annotation Java exige que les valeurs
 * de ses membres soient des <em>compile-time constants</em>. On choisit donc
 * un port haut (hors plage éphémère/utilisateur courante) au lieu d'allouer
 * dynamiquement via {@code new ServerSocket(0)}.</p>
 */
final class TestPort {

    /** Port de test — doit être libre sur la machine cible. */
    static final int SERVER_PORT = 18857;

    private TestPort() {}
}


