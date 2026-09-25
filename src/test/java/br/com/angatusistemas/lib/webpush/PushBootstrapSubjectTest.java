package br.com.angatusistemas.lib.webpush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * De onde vem o subject VAPID do projeto e o que conta como subject válido.
 *
 * <p>O subject era fixo no contato da Angatu Sistemas: todo projeto, de qualquer dono, se
 * apresentava aos push services com ele. Agora vem do parâmetro de
 * {@link PushBootstrap#setup(String)}, da propriedade {@code angatu.webpush.subject} ou da
 * variável {@code ANGATU_WEBPUSH_SUBJECT}, e o padrão só vale na falta dos três. Os testes usam
 * a regra sobre valores já lidos — nada depende do ambiente da máquina nem do banco.</p>
 *
 * @author Angatu Sistemas
 */
class PushBootstrapSubjectTest {

    @Test
    @DisplayName("sem propriedade nem variável, vale o subject padrão")
    void fallsBackToTheDefaultSubject() {
        assertEquals(PushBootstrap.DEFAULT_SUBJECT, PushBootstrap.resolveSubject(null, null));
        assertEquals(PushBootstrap.DEFAULT_SUBJECT, PushBootstrap.resolveSubject("   ", ""));
    }

    @Test
    @DisplayName("a propriedade de sistema tem prioridade sobre a variável de ambiente")
    void propertyWinsOverEnvironment() {
        assertEquals("mailto:propriedade@exemplo.com.br",
                PushBootstrap.resolveSubject("mailto:propriedade@exemplo.com.br", "mailto:variavel@exemplo.com.br"));
    }

    @Test
    @DisplayName("a variável de ambiente vale quando não há propriedade, sem espaços nas pontas")
    void environmentIsUsedWhenThereIsNoProperty() {
        assertEquals("https://exemplo.com.br/contato", PushBootstrap.resolveSubject(null, "  https://exemplo.com.br/contato  "));
    }

    @ParameterizedTest
    @ValueSource(strings = { "mailto:contato@empresa.com.br", "MAILTO:contato@empresa.com.br", "https://empresa.com.br",
            "https://empresa.com.br/contato" })
    @DisplayName("aceita mailto: com e-mail e URL https://")
    void acceptsValidSubjects(String subject) {
        assertEquals(subject, PushBootstrap.resolveSubject(subject, null));
    }

    @ParameterizedTest
    @ValueSource(strings = { "contato@empresa.com", "mailto:", "mailto:sem-arroba", "mailto:a b@c.com",
            "http://empresa.com.br", "https://", "empresa.com.br" })
    @DisplayName("recusa subject que não é mailto: com e-mail nem https://, dizendo de onde ele veio")
    void rejectsInvalidSubjects(String subject) {
        IllegalArgumentException fromProperty = assertThrows(IllegalArgumentException.class,
                () -> PushBootstrap.resolveSubject(subject, null));
        assertTrue(fromProperty.getMessage().contains("angatu.webpush.subject"), fromProperty.getMessage());

        IllegalArgumentException fromEnvironment = assertThrows(IllegalArgumentException.class,
                () -> PushBootstrap.resolveSubject(null, subject));
        assertTrue(fromEnvironment.getMessage().contains("ANGATU_WEBPUSH_SUBJECT"), fromEnvironment.getMessage());
    }

    @Test
    @DisplayName("setup(subject) recusa subject inválido antes de tocar no banco")
    void setupRejectsAnInvalidSubjectUpFront() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PushBootstrap.setup("contato@empresa.com"));
        assertTrue(e.getMessage().contains("setup(subject)"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PushBootstrap.setup(null));
    }
}
