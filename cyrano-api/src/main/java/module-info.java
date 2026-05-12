/**
 * API Cyrano : re-exposition contrôlée de la spec MicroProfile Rest Client 4.0 et SPI
 * publique stable. Le contenu sera étoffé au fil des milestones (M1+).
 *
 * <p><strong>Note JPMS — module automatique sans {@code Automatic-Module-Name}</strong> :
 * {@code microprofile-rest-client-api:4.0} n'a ni {@code Automatic-Module-Name} dans son
 * {@code MANIFEST.MF}, ni {@code module-info.class}. Le nom JPMS
 * {@code microprofile.rest.client.api} est dérivé du nom d'artefact Maven par Java
 * (strip version + remplacement {@code -} par {@code .}). Ce nom est stable entre versions
 * (la version est strippée avant dérivation, l'artifact ID Eclipse MicroProfile n'a jamais changé).</p>
 *
 * <p>Pour la compilation, le POM parent force ce JAR sur le module-path via
 * {@code target/javamodules/} (voir {@code maven-dependency-plugin} en phase
 * {@code initialize}). Pour {@code jlink}, ce même répertoire passé au
 * {@code --module-path} suffit — jlink résout les modules automatiques par nom
 * de fichier exactement comme javac.</p>
 */
module io.vidocq.cyrano.api {
    requires transitive microprofile.rest.client.api;
    requires transitive jakarta.ws.rs;

    exports io.vidocq.cyrano.spi;
}

