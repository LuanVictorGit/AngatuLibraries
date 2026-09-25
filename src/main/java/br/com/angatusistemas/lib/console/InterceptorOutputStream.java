package br.com.angatusistemas.lib.console;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * {@link OutputStream} que junta os bytes escritos em linhas e entrega cada linha ao
 * {@link Console#log(Object)} — é o que o {@code AngatuLib} coloca no {@code System.out}.
 *
 * <pre>
 * System.setOut(new PrintStream(new InterceptorOutputStream(), false, StandardCharsets.UTF_8));
 * </pre>
 *
 * <h2>O que mudou, e por quê</h2>
 * <ul>
 *   <li><strong>Uma linha lógica, uma linha de log.</strong> Antes, todo {@code flush()} virava
 *       uma linha: com o {@code PrintStream} em autoflush, {@code print} seguido de
 *       {@code println} saía em duas linhas, e um JSON de 20 KB em três pedaços, cada um com
 *       carimbo de hora. Agora a linha só sai no {@code '\n'} (ou ao passar de 64 KB);
 *       {@code flush()} não faz nada.</li>
 *   <li><strong>Sem recursão.</strong> Sem o {@code AngatuLib} iniciado, o {@code Console}
 *       escreve no {@code System.out} — que é este mesmo objeto. O próprio exemplo acima
 *       estourava a pilha. Uma linha produzida enquanto outra está sendo entregue vai direto à
 *       saída padrão do processo.</li>
 *   <li><strong>Texto como está.</strong> O {@code &} do texto capturado é protegido (ver
 *       {@link AnsiColor#escape(String)}): uma URL impressa não vira código de cor.</li>
 *   <li><strong>Memória devolvida.</strong> Depois de uma linha grande, o buffer é trocado por
 *       um novo — o {@code reset()} de um {@code ByteArrayOutputStream} não encolhe a
 *       capacidade.</li>
 * </ul>
 *
 * @author Angatu Sistemas
 * @see Console#log(Object)
 */
public final class InterceptorOutputStream extends OutputStream {

    /** Uma linha maior que isto é entregue em partes, em vez de crescer sem limite. */
    private static final int MAX_LINE_BYTES = 64 * 1024;

    /** Buffer maior que isto é descartado depois de entregue. */
    private static final int SHRINK_ABOVE_BYTES = 8 * 1024;

    /** Saída padrão do processo, para a linha produzida durante a entrega de outra. */
    private static final PrintStream RAW_OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    /** Marca da thread que está entregando uma linha ao {@code Console}. */
    private static final ThreadLocal<Boolean> DELIVERING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Bytes da linha em formação. */
    private ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

    /**
     * Cria uma nova instância do interceptor.
     */
    public InterceptorOutputStream() {
        // Construtor explícito para documentação
    }

    /**
     * Escreve um byte. {@code '\n'} entrega a linha; {@code '\r'} é ignorado (saídas Windows).
     *
     * @param b O byte a ser escrito (int de 0 a 255)
     */
    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            deliverLine();
        } else if (b != '\r') {
            buffer.write(b);
            if (buffer.size() >= MAX_LINE_BYTES) deliverFullBuffer();
        }
    }

    /**
     * Escreve um trecho de bytes de uma vez — o caminho normal de um {@code PrintStream}.
     *
     * <p>Antes, cada byte passava por uma chamada sincronizada própria, sob a trava do
     * {@code System.out} inteiro.</p>
     *
     * @param bytes  Dados
     * @param offset Início do trecho
     * @param length Tamanho do trecho
     */
    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        int start = offset;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            byte b = bytes[i];
            if (b == '\n' || b == '\r') {
                append(bytes, start, i - start);
                if (b == '\n') deliverLine();
                start = i + 1;
            }
        }
        append(bytes, start, end - start);
    }

    /**
     * Não entrega nada: a linha só sai no {@code '\n'}. Ver o Javadoc da classe.
     */
    @Override
    public void flush() {
        // de propósito: flush não é fim de linha
    }

    /**
     * Entrega a linha incompleta que houver — a última saída de um processo que termina sem
     * {@code '\n'}. O {@code AngatuLib} chama no desligamento; o objeto continua aceitando escrita
     * depois.
     */
    @Override
    public synchronized void close() {
        deliverLine();
    }

    /** Acrescenta um trecho ao buffer, entregando em partes o que passar do tamanho máximo. */
    private void append(byte[] bytes, int offset, int length) {
        int position = offset;
        int remaining = length;
        while (remaining > 0) {
            int room = MAX_LINE_BYTES - buffer.size();
            int chunk = Math.min(room, remaining);
            buffer.write(bytes, position, chunk);
            position += chunk;
            remaining -= chunk;
            if (buffer.size() >= MAX_LINE_BYTES) deliverFullBuffer();
        }
    }

    /**
     * Entrega uma parte da linha longa sem partir um caractere ao meio.
     *
     * <p>A linha passou de {@link #MAX_LINE_BYTES} e sai em partes. O corte caía num byte
     * qualquer: um caractere de dois ou três bytes ({@code ç}, {@code ã}) ficava metade em cada
     * parte, e as duas metades viravam {@code U+FFFD} no log. Agora os bytes do caractere
     * incompleto ficam para o começo da parte seguinte.</p>
     */
    private void deliverFullBuffer() {
        byte[] bytes = buffer.toByteArray();
        int cut = utf8Boundary(bytes, bytes.length);
        buffer = new ByteArrayOutputStream(256);
        buffer.write(bytes, cut, bytes.length - cut);
        deliver(new String(bytes, 0, cut, StandardCharsets.UTF_8));
    }

    /**
     * Onde termina o último caractere UTF-8 completo dos primeiros {@code length} bytes.
     *
     * @return {@code length} se o último caractere está inteiro; senão, o índice em que ele começa
     */
    static int utf8Boundary(byte[] bytes, int length) {
        int start = length - 1;
        int continuation = 0;
        while (start >= 0 && continuation < 3 && (bytes[start] & 0xC0) == 0x80) {
            start--;
            continuation++;
        }
        if (start < 0) return length;
        int lead = bytes[start] & 0xFF;
        int expected = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        return length - start < expected ? start : length;
    }

    /**
     * Entrega o buffer como uma linha de log e o esvazia. Buffer vazio não produz linha.
     */
    private void deliverLine() {
        int size = buffer.size();
        if (size == 0) return;
        String message = buffer.toString(StandardCharsets.UTF_8);
        if (size > SHRINK_ABOVE_BYTES) buffer = new ByteArrayOutputStream(256);
        else buffer.reset();
        deliver(message);
    }

    /** Entrega uma linha ao {@code Console} — ou direto à saída do processo, se for eco da entrega de outra. */
    private static void deliver(String message) {
        if (DELIVERING.get()) {
            RAW_OUT.println(message); // o Console escreveu no System.out, que é este objeto
            return;
        }
        DELIVERING.set(Boolean.TRUE);
        try {
            Console.log(AnsiColor.escape(message));
        } finally {
            DELIVERING.set(Boolean.FALSE);
        }
    }
}
