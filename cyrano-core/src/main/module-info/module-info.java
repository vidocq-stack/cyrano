/**
 * Implémentation MicroProfile Rest Client 4.0 standalone — utilisable en SE pur,
 * sans CDI ni container.
 *
 * <p>Composants prévus (cf. ROADMAP.md M1) :</p>
 * <ul>
 *   <li>{@code CyranoRestClientBuilder} — implémente {@code RestClientBuilder} de la spec.</li>
 *   <li>{@code CyranoInterfaceScanner} — scanne les annotations JAX-RS sur l'interface.</li>
 *   <li>{@code CyranoProxyGenerator} — génère un proxy nommé {@code Cyrano$&lt;Interface&gt;}
 *       via la Class-File API (JEP 484), pas {@code java.lang.reflect.Proxy}.</li>
 *   <li>{@code CyranoInvocationHandler} — résout les paramètres, construit {@code HttpRequest}.</li>
 *   <li>{@code CyranoHttpTransport} — transport via {@code java.net.http.HttpClient}
 *       avec {@code VirtualThreadPerTaskExecutor}.</li>
 * </ul>
 *
 * <p>La sérialisation JSON est produite via Jakarta JSON-B (jakarta.json.bind) ;
 * champollion en est l'implémentation de référence fournie à l'exécution.</p>
 *
 * <p><strong>Note JPMS — workaround testCompile</strong> :
 * {@code module-info.java} est dans {@code src/main/module-info/} (pas
 * {@code src/main/java/}) pour que Maven Compiler Plugin ne détecte pas JPMS lors de
 * {@code testCompile}. {@code maven-clean-plugin} purge {@code module-info.class} avant
 * {@code testCompile} (builds incrémentaux). Une exécution {@code prepare-package}
 * recompile {@code module-info.java} seul avant l'assemblage du JAR. Les tests
 * s'exécutent sur le classpath ({@code useModulePath=false}) — le câblage JPMS est
 * validé par le smoke TCK.</p>
 */
module io.vidocq.cyrano.core {
    requires transitive io.vidocq.cyrano.api;

    // JSON-B pour la sérialisation des corps de requête / réponse (spec §4.2)
    requires jakarta.json.bind;
    requires jakarta.json;

    // Transport HTTP via le JDK (java.net.http)
    requires java.net.http;

    // SPI runtime exportée — point d'entrée stable pour les adaptateurs (cyrano-cdi-vauban).
    // Le package io.vidocq.cyrano.internal reste volontairement non exporté
    // (frontière JPMS du projet).
    exports io.vidocq.cyrano.runtime;

    // SPI MicroProfile Rest Client : le resolver est aussi dclar via META-INF/services
    // pour le fallback ClassLoader (RestClientBuilderResolver.instance() utilise les deux).
    provides org.eclipse.microprofile.rest.client.spi.RestClientBuilderResolver
            with io.vidocq.cyrano.runtime.CyranoRestClientBuilderResolver;

    // M4-3 — SPI d'instanciation des providers (filters, mappers...) : permet aux
    // adaptateurs comme cyrano-cdi-vauban de fournir des instances gres par CDI.
    uses io.vidocq.cyrano.runtime.ProviderInstantiator;
}

