package br.com.angatusistemas.lib;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.javalin.JavalinAPI;

/**
 * A biblioteca roda só atrás do Coolify: o pedido de HTTPS gerenciado é recusado na hora, antes
 * de qualquer efeito global.
 *
 * @author Angatu Sistemas
 */
@SuppressWarnings("removal")
class AngatuLibHttpOnlyTest {

    @Test
    @DisplayName("pedir HTTPS gerenciado falha alto, sem redirecionar o System.out nem subir servidor")
    void managedHttpsIsRefusedBeforeAnyGlobalEffect() {
        PrintStream before = System.out;

        UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class,
                () -> new AngatuLib("meusite.com.br", 443, true, true));
        assertTrue(failure.getMessage().contains("Coolify"), failure.getMessage());
        assertSame(before, System.out, "o System.out foi redirecionado por uma subida que falhou");
        assertNull(AngatuLib.getInstance());

        assertThrows(UnsupportedOperationException.class, () -> JavalinAPI.setup(443, true, true, null));
        assertNull(JavalinAPI.get(), "o servidor subiu mesmo com o pedido recusado");
    }
}
