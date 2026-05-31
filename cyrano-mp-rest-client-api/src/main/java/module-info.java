/**
 * Explicit JPMS repackage of the MicroProfile Rest Client 4.0 API.
 *
 * <p>The upstream JAR is an automatic module, unusable with jlink.
 * This module republishes the same API packages with an explicit module-info.</p>
 */
module io.vidocq.cyrano.mp.rest.client.api {
    requires java.logging;
    requires transitive jakarta.ws.rs;

    requires static jakarta.cdi;
    requires static jakarta.inject;
    requires static jakarta.annotation;

    exports org.eclipse.microprofile.rest.client;
    exports org.eclipse.microprofile.rest.client.annotation;
    exports org.eclipse.microprofile.rest.client.ext;
    exports org.eclipse.microprofile.rest.client.inject;
    exports org.eclipse.microprofile.rest.client.spi;
}

