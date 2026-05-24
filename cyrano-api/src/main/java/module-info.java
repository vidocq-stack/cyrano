/**
 * API Cyrano : re-exposition contrôlée de la spec MicroProfile Rest Client 4.0 et SPI
 * publique stable. Le contenu sera étoffé au fil des milestones (M1+).
 *
 * <p><strong>Note JPMS — compatibilité jlink</strong> : la spec MP Rest Client est
 * importée via {@code io.vidocq.cyrano.mp.rest.client.api}, un module de repackage
 * explicite qui évite l'usage d'un module automatique dans le graphe.</p>
 */
module io.vidocq.cyrano.api {
    requires transitive io.vidocq.cyrano.mp.rest.client.api;
    requires transitive jakarta.ws.rs;

    exports io.vidocq.cyrano.spi;
}

