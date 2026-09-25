package br.com.angatusistemas.lib.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Substituição de placeholders dos templates de e-mail: valores escapados como HTML, numa única
 * passada, e HTML sem escape só pelo mapa explicitamente confiável.
 *
 * @author Angatu Sistemas
 */
class EmailTemplateTest {

    @Test
    @DisplayName("valor com HTML é escapado e aparece como texto")
    void valuesAreHtmlEscaped() {
        String html = EmailAPI.renderTemplate("<p>Olá {{nome}}</p>",
                Map.of("nome", "<script>alert('x')</script> & \"cia\""), null);
        assertEquals("<p>Olá &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;cia&quot;</p>", html);
    }

    @Test
    @DisplayName("valor escapado não fecha um atributo entre aspas")
    void escapedValueCannotBreakOutOfAQuotedAttribute() {
        String html = EmailAPI.renderTemplate("<a title=\"{{t}}\">x</a>", Map.of("t", "\" onmouseover=\"alert(1)"), null);
        assertEquals("<a title=\"&quot; onmouseover=&quot;alert(1)\">x</a>", html);
    }

    @Test
    @DisplayName("o texto de um valor nunca é lido de novo como placeholder")
    void valuesAreNeverSubstitutedAgain() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mensagem", "{{token}}");
        values.put("token", "SEGREDO-123");
        String html = EmailAPI.renderTemplate("<p>{{mensagem}}</p><p>{{token}}</p>", values, null);
        assertEquals("<p>{{token}}</p><p>SEGREDO-123</p>", html);
    }

    @Test
    @DisplayName("placeholder sem chave no mapa fica como está")
    void unknownPlaceholdersStayUntouched() {
        assertEquals("Oi {{outro}} Ana", EmailAPI.renderTemplate("Oi {{outro}} {{nome}}", Map.of("nome", "Ana"), null));
        assertEquals("Oi {{ nome }}", EmailAPI.renderTemplate("Oi {{ nome }}", Map.of("nome", "Ana"), null));
    }

    @Test
    @DisplayName("placeholders vizinhos, repetidos e entre chaves extras são substituídos como antes")
    void adjacentRepeatedAndExtraBraces() {
        Map<String, String> values = Map.of("a", "1", "b", "2");
        assertEquals("12 1", EmailAPI.renderTemplate("{{a}}{{b}} {{a}}", values, null));
        assertEquals("{1}", EmailAPI.renderTemplate("{{{a}}}", values, null));
        assertEquals("x}} {{ 1", EmailAPI.renderTemplate("x}} {{ {{a}}", values, null));
    }

    @Test
    @DisplayName("valor nulo vira texto vazio")
    void nullValueBecomesEmptyText() {
        Map<String, String> values = new HashMap<>();
        values.put("nome", null);
        assertEquals("Oi !", EmailAPI.renderTemplate("Oi {{nome}}!", values, null));
    }

    @Test
    @DisplayName("HTML confiável entra sem escape; o texto no mesmo template continua escapado")
    void trustedHtmlIsInsertedAsIs() {
        String html = EmailAPI.renderTemplate("<h1>{{titulo}}</h1><table>{{linhas}}</table>",
                Map.of("titulo", "<b>"), Map.of("linhas", "<tr><td>1</td></tr>"));
        assertEquals("<h1>&lt;b&gt;</h1><table><tr><td>1</td></tr></table>", html);
    }

    @Test
    @DisplayName("HTML confiável também não é lido de novo como placeholder")
    void trustedHtmlIsNotSubstitutedAgain() {
        String html = EmailAPI.renderTemplate("{{bloco}}|{{token}}", Map.of("token", "S"), Map.of("bloco", "<i>{{token}}</i>"));
        assertEquals("<i>{{token}}</i>|S", html);
    }

    @Test
    @DisplayName("a mesma chave nos dois mapas é recusada")
    void sameKeyInBothMapsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> EmailAPI.renderTemplate("{{x}}", Map.of("x", "a"), Map.of("x", "<b>")));
    }

    @Test
    @DisplayName("sem valores, o template volta intacto")
    void withoutValuesTheTemplateIsReturnedAsIs() {
        assertEquals("{{a}} <p>", EmailAPI.renderTemplate("{{a}} <p>", null, null));
        assertEquals("{{a}} <p>", EmailAPI.renderTemplate("{{a}} <p>", Map.of(), Map.of()));
    }

    @Test
    @DisplayName("escapeHtml cobre os cinco caracteres e trata nulo")
    void escapeHtmlCoversTheFiveCharacters() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", EmailAPI.escapeHtml("&<>\"'"));
        assertEquals("&amp;amp;", EmailAPI.escapeHtml("&amp;"));
        assertEquals("", EmailAPI.escapeHtml(null));
        assertEquals("texto comum, sem nada", EmailAPI.escapeHtml("texto comum, sem nada"));
    }

    @Test
    @DisplayName("template enorme cheio de chaves sem correspondência é processado em tempo linear")
    void pathologicalTemplateIsProcessedInLinearTime() {
        String template = "{{".repeat(300_000) + "}}";
        String result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> EmailAPI.renderTemplate(template, Map.of("nome", "Ana"), null));
        assertEquals(template, result);
    }
}
