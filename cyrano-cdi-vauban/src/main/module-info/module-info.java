/**
 * Optional integration of Cyrano with the Vauban container — automatic discovery of
 * interfaces annotated {@code @RegisterRestClient} via a Build Compatible Extension,
 * production of the corresponding CDI beans qualified with {@code @RestClient}.
 *
 * <p>Optional module: a standalone SE deployment does not need this module
 * and can build its proxies via {@code RestClientBuilder.newBuilder()}.</p>
 *
 * <p>Base URI configuration relies on {@code @RegisterRestClient(baseUri=...)}
 * (low priority) and MicroProfile Config (Ravel, high priority,
 * {@code &lt;fqn&gt;/mp-rest/url}).</p>
 *
 * <p><strong>JPMS note — testCompile workaround</strong>:
 * {@code module-info.java} lives under {@code src/main/module-info/} so the Maven
 * Compiler Plugin does not pick up JPMS during {@code testCompile} (vauban-core is
 * test-scope, absent from {@code target/javamodules/}).</p>
 *
 * <p>For strict JPMS deployment in production, plan to add
 * {@code opens io.vidocq.cyrano.cdi.internal to io.vidocq.vauban.core} so that
 * Vauban can introspect the internal CDI beans (to be activated in M3).</p>
 */
module io.vidocq.cyrano.cdi.vauban {
    requires transitive io.vidocq.cyrano.core;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    exports io.vidocq.cyrano.cdi.internal;

    //ECB Cyrano — exposes Vauban via ServiceLoader + JPMS providers (spec MP Rest Client 4.0 6).
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension;

    //M4-3 — ProviderInstantiator CDI-aware (outs @RegisterProvider via BeanManager).
    provides io.vidocq.cyrano.runtime.ProviderInstantiator
            with io.vidocq.cyrano.cdi.internal.CyranoCdiProviderInstantiator;
}

