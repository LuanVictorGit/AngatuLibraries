package br.com.angatusistemas.lib.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Leitura de {@code public/}: nada fora dela, e caminhos sempre relativos a ela.
 *
 * <p>Nos testes o classpath é pasta ({@code target/test-classes}) — exatamente o caso em que um
 * {@code ..} no caminho lia arquivo de fora de {@code public/}.</p>
 *
 * @author Angatu Sistemas
 */
class AssetsAPITest {

    @Test
    @DisplayName("lê o que está em public/")
    void readsFilesInsidePublic() {
        assertNotNull(AssetsAPI.readAssetAsString("/index.html"));
        assertTrue(AssetsAPI.assetExists("/sobre.html"));
    }

    @Test
    @DisplayName("caminho com .. não sai de public/")
    void pathTraversalIsRefused() {
        // Esta classe existe no classpath, uma pasta acima de public/
        String outside = "/../br/com/angatusistemas/lib/javalin/AssetsAPITest.class";
        assertNotNull(AssetsAPITest.class.getClassLoader().getResource(outside.substring(4)), "pré-condição do teste");

        assertNull(AssetsAPI.readAssetAsBytes(outside));
        assertFalse(AssetsAPI.assetExists(outside));
        assertNull(AssetsAPI.readAssetAsBytes("/./index.html"));
        assertNull(AssetsAPI.readAssetAsBytes("/css\\..\\index.html"));
        assertEquals(-1, AssetsAPI.getAssetSize(outside));
    }

    @Test
    @DisplayName("a listagem devolve caminhos relativos a public/, sem subpastas no modo não recursivo")
    void listingReturnsPathsRelativeToPublic() {
        List<String> top = AssetsAPI.listAssets("/");
        assertTrue(top.contains("/index.html"), top.toString());
        assertTrue(top.contains("/sobre.html"), top.toString());
        assertTrue(top.stream().allMatch(p -> p.startsWith("/") && !p.startsWith("/public")), top.toString());

        List<String> all = AssetsAPI.listAllAssetsRecursive("/");
        assertTrue(all.containsAll(top), all.toString());
    }

    @Test
    @DisplayName("módulo JavaScript sai com tipo de script, que o nosniff exige")
    void moduleScriptsGetAScriptType() {
        assertTrue(AssetsAPI.getContentType("/js/app.mjs").startsWith("text/javascript"));
        assertTrue(AssetsAPI.getContentType("/js/app.js").startsWith("text/javascript"));
        assertEquals("application/octet-stream", AssetsAPI.getContentType("/arquivo.desconhecido"));
    }
}
