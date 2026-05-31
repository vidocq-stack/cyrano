/**
 * Cyrano API: controlled re-exposure of the MicroProfile Rest Client 4.0 spec
 * and stable public SPI. The content will be expanded over the milestones (M1+).
 *
 * <p><strong>JPMS note — jlink compatibility</strong>: the MP Rest Client spec is
 * imported via {@code io.vidocq.cyrano.mp.rest.client.api}, an explicit repackage
 * module that avoids the use of an automatic module in the graph.</p>
 */
module io.vidocq.cyrano.api {
    requires transitive io.vidocq.cyrano.mp.rest.client.api;
    requires transitive jakarta.ws.rs;

    exports io.vidocq.cyrano.spi;
}
