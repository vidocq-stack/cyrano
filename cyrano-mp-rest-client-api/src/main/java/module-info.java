/**
 * Repackage JPMS explicite de l'API MicroProfile Rest Client 4.0.
 *
 * <p>Le JAR upstream est un module automatique, inutilisable avec jlink.
 * Ce module republie les memes packages API avec un module-info explicite.</p>
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

