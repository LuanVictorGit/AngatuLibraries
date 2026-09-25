package br.com.angatusistemas.lib.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Partes puras do cliente da DeepSeek: leitura das linhas do SSE e o trecho de erro que vai para o
 * log.
 *
 * @author Angatu Sistemas
 */
class DeepSeekStreamParsingTest {

    @Test
    @DisplayName("linha data: é lida com ou sem espaço depois dos dois-pontos")
    void readsDataLines() {
        assertEquals("{\"a\":1}", DeepSeek.sseData("data: {\"a\":1}"));
        assertEquals("{\"a\":1}", DeepSeek.sseData("data:{\"a\":1}"));
        assertEquals("[DONE]", DeepSeek.sseData("data: [DONE]"));
    }

    @Test
    @DisplayName("keep-alive, linha vazia e outros campos do SSE não são dados")
    void ignoresNonDataLines() {
        assertNull(DeepSeek.sseData(": keep-alive"));
        assertNull(DeepSeek.sseData(""));
        assertNull(DeepSeek.sseData("event: message"));
        assertNull(DeepSeek.sseData(null));
    }

    @Test
    @DisplayName("o trecho de erro para o log nunca leva a chave da API, nem mascarada pela metade")
    void errorSnippetHidesApiKeys() {
        String body = "{\"error\":{\"message\":\"Authentication Fails, Your api key: sk-1234567890abcdef is invalid\"}}";
        String snippet = DeepSeek.safeSnippet(body);
        assertFalse(snippet.contains("1234567890abcdef"));
        assertTrue(snippet.contains("sk-***"));
        assertFalse(DeepSeek.safeSnippet("chave sk-****cdef recusada").contains("cdef"));
    }

    @Test
    @DisplayName("o trecho de erro fica numa linha só e com tamanho limitado")
    void errorSnippetIsOneBoundedLine() {
        String snippet = DeepSeek.safeSnippet("linha 1\nlinha 2\r\n\tlinha 3 " + "x".repeat(2000));
        assertFalse(snippet.contains("\n"));
        assertTrue(snippet.length() <= 501);
        assertEquals("", DeepSeek.safeSnippet(null));
    }
}
