package br.com.angatusistemas.lib.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.cdimascio.dotenv.Dotenv;

/**
 * Carga do {@code .env} que nunca derruba a aplicação: arquivo fora do UTF-8 cai para as
 * variáveis do sistema, e as linhas que o dotenv-java descarta em silêncio viram aviso com o nome
 * da chave — nunca com o valor.
 *
 * @author Angatu Sistemas
 */
class EnvTest {

    @TempDir
    Path directory;

    private final List<String> warnings = new ArrayList<>();

    private Dotenv load(byte[] content) throws IOException {
        Files.write(directory.resolve(".env"), content);
        return Env.EnvLoader.load(directory.toString(), warnings::add);
    }

    private Dotenv load(String content, Charset charset) throws IOException {
        return load(content.getBytes(charset));
    }

    private void assertNoWarningContains(String... secrets) {
        for (String warning : warnings) {
            for (String secret : secrets) {
                assertFalse(warning.contains(secret), "o aviso vazou um valor: " + warning);
            }
        }
    }

    @Test
    @DisplayName("arquivo em ANSI/Latin-1 não lança exceção e cai para as variáveis do sistema")
    void nonUtf8FileFallsBackToSystemEnvironment() throws IOException {
        assumeFalse(System.getenv().isEmpty(), "o teste precisa de ao menos uma variável de ambiente");
        Dotenv env = load("TESTE_LOJA=São João Café\nTESTE_EMAIL=loja@exemplo.com\nTESTE_OUTRA=1\n",
                StandardCharsets.ISO_8859_1);

        assertTrue(env.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE).isEmpty(), "nada do arquivo foi carregado");
        assertNull(env.get("TESTE_OUTRA"));
        Map.Entry<String, String> anySystemVariable = System.getenv().entrySet().iterator().next();
        assertEquals(anySystemVariable.getValue(), env.get(anySystemVariable.getKey()));

        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("linha 1"), warnings.get(0));
        assertTrue(warnings.get(0).contains("TESTE_LOJA"), warnings.get(0));
        assertNoWarningContains("Jo", "Caf", "loja@exemplo.com");
    }

    @Test
    @DisplayName("arquivo corrigido volta a carregar na chamada seguinte")
    void correctedFileLoadsAgain() throws IOException {
        load("TESTE_LOJA=São João\n", StandardCharsets.ISO_8859_1);
        warnings.clear();
        Dotenv env = load("TESTE_LOJA=São João\n", StandardCharsets.UTF_8);
        assertEquals("São João", env.get("TESTE_LOJA"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("arquivo UTF-8 correto carrega tudo sem aviso, inclusive valor de várias linhas")
    void validFileLoadsWithoutWarnings() throws IOException {
        Dotenv env = load("# comentário\n  # comentário recuado\nTESTE_LOJA=São João\n"
                + "TESTE_EMAIL=loja@exemplo.com # contato\n"
                + "TESTE_PEM=\"-----BEGIN-----\nAbCd=\n-----END-----\"\n\nTESTE_OUTRA=1\n", StandardCharsets.UTF_8);
        assertEquals("São João", env.get("TESTE_LOJA"));
        assertEquals("loja@exemplo.com", env.get("TESTE_EMAIL"));
        assertEquals("-----BEGIN-----\nAbCd=\n-----END-----", env.get("TESTE_PEM"));
        assertEquals("1", env.get("TESTE_OUTRA"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("BOM no começo do arquivo gera aviso com o nome da primeira chave")
    void byteOrderMarkIsReported() throws IOException {
        byte[] text = "TESTE_EMAIL=loja@exemplo.com\r\nTESTE_OUTRA=1\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[text.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(text, 0, withBom, 3, text.length);

        Dotenv env = load(withBom);

        assertEquals("1", env.get("TESTE_OUTRA"));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("TESTE_EMAIL") && warnings.get(0).contains("BOM"), warnings.get(0));
        assertNoWarningContains("loja@exemplo.com");
    }

    @Test
    @DisplayName("aspas que não fecham geram aviso com a chave e as linhas perdidas")
    void unclosedQuoteIsReported() throws IOException {
        Dotenv env = load("TESTE_BANCO=\"loja\nTESTE_EMAIL=loja@exemplo.com\nTESTE_OUTRA=1\n", StandardCharsets.UTF_8);

        assertNull(env.get("TESTE_OUTRA"), "o dotenv-java perde as linhas seguintes; o aviso é o que as revela");
        assertEquals(1, warnings.size(), warnings.toString());
        String warning = warnings.get(0);
        assertTrue(warning.contains("TESTE_BANCO") && warning.contains("linhas 1 a 3"), warning);
        assertNoWarningContains("loja", "exemplo");
    }

    @Test
    @DisplayName("linha de continuação de um valor secreto nunca aparece no aviso")
    void continuationLinesOfSecretsAreNeverQuoted() throws IOException {
        load("TESTE_CHAVE=\"-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqh=\nSEGREDO9=\nTESTE_OUTRA=1\n",
                StandardCharsets.UTF_8);
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("TESTE_CHAVE"), warnings.get(0));
        assertNoWarningContains("MIIE", "SEGREDO", "BEGIN");
    }

    @Test
    @DisplayName("export no começo da linha e linha fora do formato geram aviso sem o valor")
    void malformedLinesAreReportedByKeyOrLineNumber() throws IOException {
        Dotenv env = load("export TESTE_API=abc123\nsegredo-solto-sem-chave\nTESTE_OUTRA=1\n", StandardCharsets.UTF_8);
        assertEquals("1", env.get("TESTE_OUTRA"));
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("TESTE_API") && warnings.get(0).contains("export"), warnings.get(0));
        assertTrue(warnings.get(1).contains("linha 2"), warnings.get(1));
        assertNoWarningContains("abc123", "segredo-solto");
    }

    @Test
    @DisplayName("localiza a linha do primeiro byte fora do UTF-8, com CRLF ou LF")
    void findsTheLineOfTheFirstInvalidByte() {
        byte[] crlf = "A=1\r\nB=2\r\nC=".getBytes(StandardCharsets.US_ASCII);
        byte[] content = new byte[crlf.length + 1];
        System.arraycopy(crlf, 0, content, 0, crlf.length);
        content[crlf.length] = (byte) 0xE9;
        assertEquals(3, Env.EnvFileCheck.firstInvalidUtf8Line(content));
        assertEquals(0, Env.EnvFileCheck.firstInvalidUtf8Line("A=São\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("sem arquivo .env, carrega só o sistema e não avisa nada")
    void missingFileLoadsSystemEnvironmentSilently() {
        Dotenv env = Env.EnvLoader.load(directory.toString(), warnings::add);
        assertTrue(env.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE).isEmpty());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("variável do sistema vence a do arquivo com o mesmo nome")
    void systemVariableWinsOverFile() throws IOException {
        Map.Entry<String, String> system = System.getenv().entrySet().stream()
                .filter(entry -> entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*"))
                .filter(entry -> !entry.getValue().isEmpty() && !entry.getValue().equals("valor-do-arquivo"))
                .findFirst().orElse(null);
        assumeFalse(system == null, "o teste precisa de uma variável de ambiente com nome simples");
        Dotenv env = load(system.getKey() + "=valor-do-arquivo\n", StandardCharsets.UTF_8);
        assertEquals(1, env.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE).size(), "a linha do arquivo foi lida");
        assertEquals(system.getValue(), env.get(system.getKey()));
    }

    @Test
    @DisplayName("Env.get e Env.reload funcionam e a recarga troca a instância")
    void getAndReloadWork() {
        Dotenv first = Env.get();
        assertNotNull(first);
        assertSame(first, Env.get(), "get devolve a mesma instância até o reload");
        Env.reload();
        assertNotNull(Env.get());
        assertNotSame(first, Env.get(), "reload troca a instância");
    }
}
