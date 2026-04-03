package br.com.angatusistemas.lib.console;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * [PT] OutputStream que intercepta a escrita de bytes e redireciona o conteúdo para o logger
 * do console ({@link Console#log(String)}) sempre que uma quebra de linha ({@code '\n'}) é encontrada
 * ou quando o buffer é explicitamente limpo.
 * <p>
 * Útil para redirecionar a saída de streams como {@code System.out} ou {@code System.err}
 * para um sistema de logging centralizado.
 * </p>
 * <p>
 * Exemplo de uso:
 * <pre>
 * PrintStream customOut = new PrintStream(new InterceptorOutputStream());
 * System.setOut(customOut);
 * </pre>
 * </p>
 * 
 * [EN] OutputStream that intercepts byte writing and redirects the content to the console logger
 * ({@link Console#log(String)}) whenever a newline character ({@code '\n'}) is encountered
 * or when the buffer is explicitly flushed.
 * <p>
 * Useful for redirecting the output of streams like {@code System.out} or {@code System.err}
 * to a centralized logging system.
 * </p>
 * <p>
 * Usage example:
 * <pre>
 * PrintStream customOut = new PrintStream(new InterceptorOutputStream());
 * System.setOut(customOut);
 * </pre>
 * </p>
 * 
 * @author [Sua equipe]
 * @see java.io.OutputStream
 * @see Console#log(String)
 */
public final class InterceptorOutputStream extends OutputStream {

    // Buffer interno para acumular bytes até encontrar uma quebra de linha
    // Internal buffer to accumulate bytes until a newline is found
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * [PT] Construtor padrão. Cria uma nova instância do interceptor.
     * [EN] Default constructor. Creates a new interceptor instance.
     */
    public InterceptorOutputStream() {
        // Construtor explícito para documentação
    }

    /**
     * [PT] Escreve um único byte no stream.
     * <p>
     * Se o byte for uma quebra de linha ({@code '\n'}), o conteúdo acumulado no buffer
     * é enviado ao logger e o buffer é limpo. Caso contrário, o byte é armazenado no buffer.
     * </p>
     * 
     * [EN] Writes a single byte to the stream.
     * <p>
     * If the byte is a newline character ({@code '\n'}), the accumulated content in the buffer
     * is sent to the logger and the buffer is cleared. Otherwise, the byte is stored in the buffer.
     * </p>
     *
     * @param b [PT] o byte a ser escrito (representado como int 0-255)
     *          [EN] the byte to write (represented as an int 0-255)
     */
    @Override
    public void write(int b) {
        if (b == '\n') {
            flushBuffer();
        } else {
            buffer.write(b);
        }
    }

    /**
     * [PT] Força o envio de qualquer conteúdo pendente no buffer para o logger.
     * <p>
     * Útil para garantir que mensagens sem quebra de linha no final sejam registradas.
     * </p>
     * 
     * [EN] Forces any pending content in the buffer to be sent to the logger.
     * <p>
     * Useful to ensure messages without a trailing newline are logged.
     * </p>
     */
    @Override
    public void flush() {
        flushBuffer();
    }

    /**
     * [PT] Envia o conteúdo atual do buffer para o logger do console e limpa o buffer.
     * <p>
     * Se o buffer estiver vazio, nenhuma ação é executada.
     * A mensagem é convertida usando o charset UTF-8 e uma quebra de linha é adicionada.
     * </p>
     * 
     * [EN] Sends the current buffer content to the console logger and clears the buffer.
     * <p>
     * If the buffer is empty, no action is taken.
     * The message is converted using the UTF-8 charset and a newline is added.
     * </p>
     */
    private void flushBuffer() {
        if (buffer.size() > 0) {
            String message = buffer.toString(StandardCharsets.UTF_8);
            buffer.reset();
            Console.log(message + "\n");
        }
    }
}