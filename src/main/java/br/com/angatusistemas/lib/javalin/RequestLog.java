package br.com.angatusistemas.lib.javalin;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.javalin.classes.RequestLogMode;
import br.com.angatusistemas.lib.time.DataTime;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpResponseException;
import io.javalin.router.Endpoint;

/**
 * Log de toda requisição HTTP no terminal: uma linha por requisição, com horário, método,
 * caminho, IP, status e tempo — sem nunca atrasar a resposta.
 *
 * <pre>
 * [25/09 22:14:03] GET     /api/pedidos/42          IP=189.40.12.7 STATUS=200 TIME=14ms
 * [25/09 22:14:04] POST    /api/login               IP=189.40.12.7 STATUS=429 TIME=&lt;1ms DENIED=too_many_requests
 * [25/09 22:15:21] POST    /api/usuarios            IP=189.40.12.7 STATUS=500 TIME=142ms ERROR=NullPointerException
 * </pre>
 *
 * <p><strong>Onde entra:</strong> no {@code requestLogger} nativo do Javalin, que roda depois de a
 * resposta ser escrita, para toda requisição que chega ao servidor — rota, página, arquivo
 * estático, 404, recusa do filtro de segurança, erro e upgrade de WebSocket. Rota nova já nasce
 * com log, e nenhuma rota escreve o próprio. O tipo da exceção vem do {@code handlerWrapper} do
 * Javalin ({@link #wrap(Endpoint)}), que anota e relança: a resposta de erro continua a mesma.</p>
 *
 * <p><strong>Por que não atrasa:</strong> o Javalin chama o logger na thread da requisição, antes
 * de fechar a resposta. Ali só se copiam algumas referências para um registro, que entra numa
 * fila sem trava. Formatar, mascarar e escrever é trabalho de uma thread própria, que escreve em
 * lote: uma escrita no terminal para até {@value #BATCH} linhas. Com o terminal lento, a fila
 * chega ao limite e o excedente é descartado e contado — a requisição nunca espera pelo log.</p>
 *
 * <p><strong>O que nunca entra:</strong> corpo de requisição ou de resposta, arquivo enviado,
 * cabeçalho e cookie. Dos parâmetros da URL, o valor de todo nome com cara de segredo ou de dado
 * pessoal (senha, token, chave, código, e-mail, CPF, telefone...) sai como {@code ***}, e o resto
 * sai como veio na URL, cortado em {@value #MAX_VALUE} caracteres. Caractere de controle vira
 * {@code ?}: nada escrito na URL forja uma linha de log nem uma cor do terminal. O IP é o de
 * {@link IP#get(Context)}, com a regra de proxy da biblioteca — nunca o {@code X-Forwarded-For}
 * que o cliente escreveu.</p>
 *
 * @author Angatu Sistemas
 */
final class RequestLog {

    /** Atributo da requisição com o tipo da exceção que a encerrou. */
    static final String ERROR_ATTRIBUTE = "angatu.requestLog.error";

    /** Atributo da requisição com o motivo da recusa do filtro de segurança. */
    static final String DENIED_ATTRIBUTE = "angatu.requestLog.denied";

    /** Registros na fila, no máximo: cerca de 2 MiB. Além disso, descarta e conta. */
    private static final int DEFAULT_CAPACITY = 16_384;

    /** Linhas por escrita no terminal. */
    private static final int BATCH = 512;

    private static final int METHOD_WIDTH = 7;
    private static final int PATH_WIDTH = 24;
    private static final int MAX_METHOD = 16;
    private static final int MAX_PATH = 200;
    private static final int MAX_IP = 64;
    private static final int MAX_KEY = 32;
    private static final int MAX_VALUE = 48;
    private static final int MAX_PARAMS = 12;
    private static final int MAX_NAME = 80;

    /** Espera da thread de escrita com a fila vazia: começa curta e dobra até o teto. */
    private static final long MIN_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long MAX_IDLE_NANOS = TimeUnit.MILLISECONDS.toNanos(200);

    /** Data e hora da linha, no fuso do restante do log. */
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss", Locale.ROOT).withZone(DataTime.DEFAULT_ZONE);

    /**
     * Pedaços de nome de parâmetro cujo valor nunca vai ao terminal. A comparação é por trecho,
     * em minúsculas e sem separadores ({@code api_key} vira {@code apikey}): de propósito larga,
     * porque mascarar um valor inofensivo custa pouco e mostrar um segredo não tem volta.
     */
    private static final String[] SENSITIVE = {
            "pass", "senha", "pwd", "secret", "segredo", "token", "auth", "key", "chave", "session",
            "sessao", "sessão", "cookie", "jwt", "bearer", "code", "codigo", "código", "otp", "pin",
            "cvv", "cvc", "card", "cartao", "cartão", "cpf", "cnpj", "mail", "phone", "fone",
            "celular", "whats", "signature", "assinatura", "state", "nonce", "credential",
            "credencial", "hash", "salt"
    };

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    /** Registros aceitos na fila, desde a subida. */
    private static final AtomicLong SUBMITTED = new AtomicLong();
    /** Registros tirados da fila pela thread de escrita. */
    private static final AtomicLong TAKEN = new AtomicLong();
    /** Registros já entregues ao terminal (ou perdidos numa escrita que falhou). */
    private static final AtomicLong WRITTEN = new AtomicLong();
    /** Registros descartados com a fila cheia, desde o último aviso. */
    private static final LongAdder DROPPED = new LongAdder();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static volatile RequestLogMode mode = modeFromEnvironment();
    /** Limite da fila; ajustável só pelos testes do pacote. */
    static volatile int capacity = DEFAULT_CAPACITY;
    private static volatile Thread writer;

    private RequestLog() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /** O que se guarda de uma requisição: só referências, copiadas na thread dela. */
    private record Entry(long at, String method, String path, String query, String ip, int status,
            float millis, String error, String denied) {
    }

    // ==================== CONFIGURAÇÃO ====================

    /**
     * Troca o modo do log.
     *
     * @param newMode O modo novo; {@code null} volta ao padrão, {@link RequestLogMode#ALL}
     */
    static void setMode(RequestLogMode newMode) {
        mode = newMode == null ? RequestLogMode.ALL : newMode;
    }

    /** @return O modo em vigor */
    static RequestLogMode getMode() {
        return mode;
    }

    /**
     * O modo da subida: {@code ANGATU_REQUEST_LOG} ({@code all}, {@code errors} ou {@code off}),
     * lida do ambiente do processo como as demais {@code ANGATU_*}. Sem ela, {@link RequestLogMode#ALL}.
     */
    private static RequestLogMode modeFromEnvironment() {
        String value = System.getenv("ANGATU_REQUEST_LOG");
        if (value == null || value.isBlank()) return RequestLogMode.ALL;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all":
                return RequestLogMode.ALL;
            case "errors":
                return RequestLogMode.ERRORS;
            case "off":
                return RequestLogMode.OFF;
            default:
                Console.warn("ANGATU_REQUEST_LOG=%s não é um modo conhecido (all, errors, off): o log de requisições "
                        + "fica com todas.", value);
                return RequestLogMode.ALL;
        }
    }

    /** Sobe a thread de escrita, uma vez por processo. */
    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(RequestLog::writeLoop, "angatu-request-log");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
    }

    // ==================== NA THREAD DA REQUISIÇÃO ====================

    /**
     * O {@code RequestLogger} do Javalin (e o de upgrade de WebSocket): enfileira a linha da
     * requisição.
     *
     * <p>Roda na thread da requisição, antes de a resposta ser fechada — por isso não formata, não
     * escreve e não trava: lê o que a requisição já tem e põe na fila. Nunca lança; um registro
     * perdido é melhor que um erro na resposta.</p>
     *
     * @param ctx             Contexto da requisição, já respondida
     * @param executionTimeMs Tempo total, em milissegundos, medido pelo Javalin
     */
    static void record(Context ctx, Float executionTimeMs) {
        try {
            RequestLogMode current = mode;
            if (current == RequestLogMode.OFF) return;
            int status = ctx.statusCode();
            String error = ctx.attribute(ERROR_ATTRIBUTE);
            if (current == RequestLogMode.ERRORS && status < 400 && error == null) return;
            if (SUBMITTED.get() - TAKEN.get() >= capacity) {
                DROPPED.increment();
                return;
            }
            QUEUE.offer(new Entry(System.currentTimeMillis(), ctx.method().name(), ctx.path(), ctx.queryString(),
                    IP.get(ctx), status, executionTimeMs == null ? 0f : executionTimeMs, error,
                    ctx.attribute(DENIED_ATTRIBUTE)));
            SUBMITTED.incrementAndGet();
        } catch (RuntimeException ignored) {
            // O log nunca derruba nem atrasa a resposta.
        }
    }

    /**
     * O {@code handlerWrapper} do Javalin: envolve cada handler — rota, before e after — para
     * anotar o tipo da exceção que escapar dele, e a relança como veio. O tratamento de erro do
     * Javalin, e com ele a resposta, não muda em nada.
     *
     * <p>{@link HttpResponseException} fica de fora: é o jeito de uma rota responder com um status
     * ({@code NotFoundResponse}, a recusa do filtro), não um erro.</p>
     *
     * @param endpoint O endpoint cujo handler vai rodar
     * @return O handler envolvido
     */
    static Handler wrap(Endpoint endpoint) {
        Handler handler = endpoint.handler;
        return ctx -> {
            try {
                handler.handle(ctx);
            } catch (HttpResponseException response) {
                throw response;
            } catch (Exception | Error failure) {
                markError(ctx, failure);
                throw failure;
            }
        };
    }

    /**
     * Anota na requisição o tipo da exceção, para a linha do log. A primeira anotação vale.
     *
     * @param ctx     Contexto da requisição
     * @param failure A exceção
     */
    static void markError(Context ctx, Throwable failure) {
        if (ctx == null || failure == null) return;
        try {
            if (ctx.attribute(ERROR_ATTRIBUTE) != null) return;
            String name = failure.getClass().getSimpleName();
            ctx.attribute(ERROR_ATTRIBUTE, name.isEmpty() ? failure.getClass().getName() : name);
        } catch (RuntimeException ignored) {
            // anotar é opcional; a requisição segue
        }
    }

    /**
     * Anota o motivo de uma recusa do filtro de segurança — o mesmo código que o cliente recebe
     * no JSON ({@code too_many_requests}, {@code blocked}, {@code rejected}).
     *
     * @param ctx  Contexto da requisição
     * @param code Código da recusa
     */
    static void markDenied(Context ctx, String code) {
        try {
            ctx.attribute(DENIED_ATTRIBUTE, code);
        } catch (RuntimeException ignored) {
            // anotar é opcional; a recusa segue
        }
    }

    // ==================== NA THREAD DE ESCRITA ====================

    /** Esvazia a fila em lotes; com a fila vazia, espera cada vez mais, até o teto. */
    private static void writeLoop() {
        List<Entry> batch = new ArrayList<>(BATCH);
        StringBuilder text = new StringBuilder(BATCH * 128);
        long idle = MIN_IDLE_NANOS;
        while (true) {
            Entry entry;
            while (batch.size() < BATCH && (entry = QUEUE.poll()) != null) batch.add(entry);
            if (batch.isEmpty()) {
                reportDrops();
                LockSupport.parkNanos(RequestLog.class, idle);
                idle = Math.min(idle * 2, MAX_IDLE_NANOS);
                continue;
            }
            idle = MIN_IDLE_NANOS;
            TAKEN.addAndGet(batch.size());
            try {
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) text.append(System.lineSeparator());
                    appendLine(text, batch.get(i));
                }
                Console.logRaw(text.toString());
            } catch (Throwable failure) {
                // Terminal fechado ou linha que não formatou: o lote se perde, a thread continua.
            } finally {
                WRITTEN.addAndGet(batch.size());
                batch.clear();
                text.setLength(0);
            }
            reportDrops();
        }
    }

    /** Avisa, numa linha só, quantos registros a fila cheia descartou desde o último aviso. */
    private static void reportDrops() {
        long dropped = DROPPED.sumThenReset();
        if (dropped > 0) {
            Console.warn("Log de requisições: %d linha(s) descartada(s) — o terminal não acompanhou o ritmo "
                    + "das requisições.", dropped);
        }
    }

    /**
     * Espera a fila ser escrita, até o prazo: no desligamento do servidor e nos testes.
     *
     * @param timeoutMillis Prazo em milissegundos
     * @return {@code true} se tudo o que estava na fila quando a chamada começou foi escrito
     */
    static boolean flush(long timeoutMillis) {
        long target = SUBMITTED.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        Thread thread = writer;
        while (WRITTEN.get() < target) {
            if (thread == null || !thread.isAlive() || System.nanoTime() - deadline >= 0) return false;
            LockSupport.unpark(thread);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        return true;
    }

    // ==================== FORMATO ====================

    /** Uma linha: horário, método, caminho, IP, status, tempo e, quando houver, o resto. */
    private static void appendLine(StringBuilder out, Entry entry) {
        out.append("&6[").append(TIME.format(Instant.ofEpochMilli(entry.at()))).append("] &f");
        int column = out.length();
        appendSafe(out, entry.method(), MAX_METHOD);
        pad(out, column, METHOD_WIDTH);
        out.append(' ');
        column = out.length();
        appendSafe(out, entry.path(), MAX_PATH);
        pad(out, column, PATH_WIDTH);
        out.append(" &7IP=");
        appendSafe(out, entry.ip() == null ? "?" : entry.ip(), MAX_IP);
        out.append(' ').append(statusColor(entry.status())).append("STATUS=").append(entry.status());
        out.append(" &7TIME=");
        appendMillis(out, entry.millis());
        appendParams(out, entry.query());
        if (entry.denied() != null) {
            out.append(" &eDENIED=");
            appendSafe(out, entry.denied(), MAX_NAME);
        }
        if (entry.error() != null) {
            out.append(" &cERROR=");
            appendSafe(out, entry.error(), MAX_NAME);
        }
        out.append("&r");
    }

    /** Verde para sucesso, ciano para redirecionamento, amarelo para 4xx, vermelho para 5xx. */
    private static String statusColor(int status) {
        if (status >= 500) return "&c";
        if (status >= 400) return "&e";
        if (status >= 300) return "&b";
        return "&a";
    }

    private static void appendMillis(StringBuilder out, float millis) {
        if (millis < 1f) out.append("<1ms");
        else out.append(Math.round(millis)).append("ms");
    }

    /** Completa com espaços até a coluna ter a largura pedida. */
    private static void pad(StringBuilder out, int start, int width) {
        for (int filled = out.length() - start; filled < width; filled++) out.append(' ');
    }

    /**
     * Os parâmetros da URL como vieram ({@code page=2&q=camisa}), com o valor dos nomes sensíveis
     * trocado por {@code ***} e cada valor cortado.
     */
    private static void appendParams(StringBuilder out, String query) {
        if (query == null || query.isEmpty()) return;
        out.append(" &7PARAMS=");
        int count = 0;
        int from = 0;
        while (from < query.length()) {
            int amp = query.indexOf('&', from);
            int end = amp < 0 ? query.length() : amp;
            if (end > from) {
                if (count == MAX_PARAMS) {
                    out.append("&&…");
                    return;
                }
                if (count > 0) out.append("&&");
                int eq = query.indexOf('=', from);
                if (eq < 0 || eq > end) {
                    appendSafe(out, query, from, end, MAX_KEY);
                } else {
                    appendSafe(out, query, from, eq, MAX_KEY);
                    out.append('=');
                    if (isSensitive(query.substring(from, eq))) out.append("***");
                    else appendSafe(out, query, eq + 1, end, MAX_VALUE);
                }
                count++;
            }
            if (amp < 0) break;
            from = amp + 1;
        }
    }

    /** O nome do parâmetro tem cara de segredo ou de dado pessoal? (Ver {@link #SENSITIVE}.) */
    static boolean isSensitive(String rawKey) {
        String key = rawKey;
        if (key.indexOf('%') >= 0 || key.indexOf('+') >= 0) {
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                // escape inválido: compara o nome como veio
            }
        }
        StringBuilder normalized = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isLetterOrDigit(c)) normalized.append(Character.toLowerCase(c));
        }
        String name = normalized.toString();
        for (String fragment : SENSITIVE) {
            if (name.contains(fragment)) return true;
        }
        return false;
    }

    private static void appendSafe(StringBuilder out, String text, int max) {
        appendSafe(out, text, 0, text.length(), max);
    }

    /**
     * Copia o trecho para a linha: cortado em {@code max} caracteres, caractere de controle como
     * {@code ?} e {@code &} dobrado, para o dado nunca virar código de cor.
     */
    private static void appendSafe(StringBuilder out, String text, int from, int to, int max) {
        int end = Math.min(to, from + max);
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == ' ' || c == ' ') out.append('?');
            else if (c == '&') out.append("&&");
            else out.append(c);
        }
        if (to > end) out.append('…');
    }
}
