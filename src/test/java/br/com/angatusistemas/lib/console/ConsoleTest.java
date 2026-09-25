package br.com.angatusistemas.lib.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O log mostra o dado como ele é: {@code &} não vira código de cor, e uma linha lógica sai numa
 * linha só.
 *
 * @author Angatu Sistemas
 */
class ConsoleTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void captureStandardOutput() {
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStandardOutput() {
        System.setOut(originalOut);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("o & de um argumento aparece como está")
    void ampersandInsideAnArgumentIsKept() {
        Console.info("Requisição recebida: %s", "/api/busca?q=cafe&categoria=bebidas&limite=5");
        Console.log("Empresa: %s", "R&D");
        String out = output();
        assertTrue(out.contains("/api/busca?q=cafe&categoria=bebidas&limite=5"), out);
        assertTrue(out.contains("R&D"), out);
    }

    @Test
    @DisplayName("códigos de cor no formato continuam funcionando")
    void colorCodesInTheFormatStillWork() {
        Console.log("&cvermelho %s", "&a-literal");
        String out = output();
        assertTrue(out.contains("\u001B[38;5;203m"), "o &c do formato virou cor");
        assertTrue(out.contains("&a-literal"), "o &a do argumento ficou literal: " + out);
    }

    @Test
    @DisplayName("&& vira um & literal no AnsiColor")
    void doubleAmpersandIsALiteral() {
        assertEquals("R&D" + AnsiColor.RESET, AnsiColor.parse("R&&D"));
        assertEquals("a&&b", AnsiColor.escape("a&b"));
    }

    @Test
    @DisplayName("print seguido de println sai numa linha só, e o & capturado é preservado")
    void interceptorJoinsPartialWritesIntoOneLine() {
        PrintStream intercepted = new PrintStream(new InterceptorOutputStream(), false, StandardCharsets.UTF_8);
        intercepted.print("a=");
        intercepted.flush();
        intercepted.print(1);
        intercepted.println(" & fim");
        String out = output();
        assertEquals(1, out.lines().count(), out);
        assertTrue(out.contains("a=1 & fim"), out);
    }

    @Test
    @DisplayName("linha longa sai em partes sem partir caractere ao meio")
    void longLineIsSplitWithoutBreakingACharacter() {
        InterceptorOutputStream interceptor = new InterceptorOutputStream();
        PrintStream intercepted = new PrintStream(interceptor, false, StandardCharsets.UTF_8);
        intercepted.print("a".repeat(65_535)); // um byte antes do limite de 64 KiB
        intercepted.println("ção");
        String out = output();
        assertEquals(2, out.lines().count(), "a linha longa não saiu em duas partes");
        assertTrue(out.contains("ção"), out.substring(Math.max(0, out.length() - 200)));
        assertFalse(out.contains("�"), "um caractere foi partido entre as duas partes");
    }

    @Test
    @DisplayName("fronteira UTF-8: o caractere incompleto do fim fica para a parte seguinte")
    void utf8BoundaryKeepsTheIncompleteCharacterForTheNextPart() {
        byte[] bytes = "aç".getBytes(StandardCharsets.UTF_8); // 61 C3 A7
        assertEquals(3, InterceptorOutputStream.utf8Boundary(bytes, 3), "caractere inteiro");
        assertEquals(1, InterceptorOutputStream.utf8Boundary(bytes, 2), "só o primeiro byte do ç");
        byte[] emoji = "a😀".getBytes(StandardCharsets.UTF_8); // 61 F0 9F 98 80
        assertEquals(1, InterceptorOutputStream.utf8Boundary(emoji, 4));
        assertEquals(5, InterceptorOutputStream.utf8Boundary(emoji, 5));
    }

    @Test
    @DisplayName("linha sem quebra no fim sai quando o interceptador é fechado")
    void lineWithoutTrailingBreakIsDeliveredOnClose() {
        InterceptorOutputStream interceptor = new InterceptorOutputStream();
        PrintStream intercepted = new PrintStream(interceptor, false, StandardCharsets.UTF_8);
        intercepted.print("processamento concluído");
        intercepted.flush();
        assertFalse(output().contains("concluído"), "flush não é fim de linha");
        interceptor.close();
        assertTrue(output().contains("processamento concluído"), output());
    }

    @Test
    @DisplayName("erro e stack trace saem numa escrita só")
    void errorAndStackTraceAreOneWrite() {
        Console.error("falhou: %s", "motivo", new IllegalStateException("boom"));
        String out = output();
        assertTrue(out.contains("falhou: motivo"), out);
        assertTrue(out.contains("IllegalStateException: boom"), out);
        assertFalse(out.contains("%s"), out);
    }
}
