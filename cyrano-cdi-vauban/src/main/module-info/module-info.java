/**
 * Intégration CDI de Cyrano pour le container Vauban — découverte automatique des
 * interfaces annotées {@code @RegisterRestClient} via Build Compatible Extension,
 * production des beans CDI correspondants qualifiés {@code @RestClient}.
 *
 * <p>Module optionnel : un déploiement standalone SE n'a pas besoin de ce module
 * et peut construire ses proxies via {@code RestClientBuilder.newBuilder()}.</p>
 *
 * <p>La configuration de base URI s'appuie sur {@code @RegisterRestClient(baseUri=...)}
 * en priorité basse et MicroProfile Config (Ravel) en priorité haute ({@code &lt;fqn&gt;/mp-rest/url}).</p>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong> :
 * {@code module-info.java} est dans {@code src/main/module-info/} pour éviter que
 * Maven Compiler Plugin détecte JPMS lors de {@code testCompile} (vauban-core est
 * test-scope, absent de {@code target/javamodules/}).</p>
 *
 * <p>Pour un déploiement JPMS strict en production, prévoir
 * {@code opens io.vidocq.cyrano.cdi.internal to io.vidocq.vauban.core} afin que
 * Vauban puisse instancier les beans CDI internes (à activer en M3).</p>
 */
module io.vidocq.cyrano.cdi.vauban {
    requires transitive io.vidocq.cyrano.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    exports io.vidocq.cyrano.cdi.internal;

    // BCE Cyrano — expose  Vauban via ServiceLoader + JPMS provides (spec MP Rest Client 4.0 6).
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension;

    // M4-3 — ProviderInstantiator CDI-aware (rsout les @RegisterProvider via BeanManager).
    provides io.vidocq.cyrano.runtime.ProviderInstantiator
            with io.vidocq.cyrano.cdi.internal.CyranoCdiProviderInstantiator;
}

