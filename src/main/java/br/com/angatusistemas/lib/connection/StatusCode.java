package br.com.angatusistemas.lib.connection;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Enumeração dos códigos de status HTTP mais usados, conforme a RFC 9110 (que substituiu a
 * RFC 7231) e extensões.
 *
 * <p>Inclui códigos informativos (1xx), sucesso (2xx), redirecionamento (3xx),
 * erro do cliente (4xx) e erro do servidor (5xx). O método {@link #fromCode(int)}
 * permite obter a constante a partir do valor numérico.</p>
 *
 * <p>Esta enumeração é utilizada pela classe {@link Request} para representar o
 * status das respostas HTTP. Nem todo código existente tem constante aqui (ex.: 207, 522):
 * {@link Response#getCode()} guarda sempre o número recebido, e {@link #fromCode(int)} devolve
 * {@code null} para os que faltam.</p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#name-status-codes">RFC 9110 — códigos de status</a>
 */
public enum StatusCode {

    // 1xx — informativos

    /** 100 — o cliente pode continuar enviando o corpo do pedido. */
    CONTINUE(100),
    /** 101 — o servidor aceitou trocar de protocolo (ex.: WebSocket). */
    SWITCHING_PROTOCOLS(101),
    /** 102 — o pedido foi recebido e ainda está sendo processado (WebDAV). */
    PROCESSING(102),

    // 2xx — sucesso

    /** 200 — pedido atendido com sucesso. */
    OK(200),
    /** 201 — recurso criado com sucesso. */
    CREATED(201),
    /** 202 — pedido aceito para processamento posterior. */
    ACCEPTED(202),
    /** 203 — sucesso, com conteúdo alterado por um intermediário. */
    NON_AUTHORITATIVE_INFORMATION(203),
    /** 204 — sucesso, sem corpo na resposta. */
    NO_CONTENT(204),
    /** 205 — sucesso; o cliente deve limpar o formulário que enviou. */
    RESET_CONTENT(205),
    /** 206 — resposta parcial a um pedido com {@code Range}. */
    PARTIAL_CONTENT(206),

    // 3xx — redirecionamento

    /** 300 — há mais de uma representação disponível para o recurso. */
    MULTIPLE_CHOICES(300),
    /** 301 — o recurso mudou de endereço em definitivo. */
    MOVED_PERMANENTLY(301),
    /** 302 — o recurso está temporariamente em outro endereço. */
    FOUND(302),
    /** 303 — a resposta deve ser buscada em outro endereço, com GET. */
    SEE_OTHER(303),
    /** 304 — o recurso não mudou desde a versão que o cliente já tem. */
    NOT_MODIFIED(304),
    /** 307 — redirecionamento temporário que preserva o método e o corpo. */
    TEMPORARY_REDIRECT(307),
    /** 308 — redirecionamento definitivo que preserva o método e o corpo. */
    PERMANENT_REDIRECT(308),

    // 4xx — erro do cliente

    /** 400 — pedido malformado ou inválido. */
    BAD_REQUEST(400),
    /** 401 — falta autenticação válida. */
    UNAUTHORIZED(401),
    /** 402 — reservado para uso futuro (pagamento exigido). */
    PAYMENT_REQUIRED(402),
    /** 403 — autenticado, mas sem permissão para o recurso. */
    FORBIDDEN(403),
    /** 404 — recurso não encontrado. */
    NOT_FOUND(404),
    /** 405 — método HTTP não permitido para o recurso. */
    METHOD_NOT_ALLOWED(405),
    /** 406 — nenhuma representação atende ao {@code Accept} do pedido. */
    NOT_ACCEPTABLE(406),
    /** 407 — falta autenticação no proxy. */
    PROXY_AUTHENTICATION_REQUIRED(407),
    /** 408 — o servidor desistiu de esperar o pedido. */
    REQUEST_TIMEOUT(408),
    /** 409 — conflito com o estado atual do recurso. */
    CONFLICT(409),
    /** 410 — o recurso foi removido em definitivo. */
    GONE(410),
    /** 411 — o pedido precisa do cabeçalho {@code Content-Length}. */
    LENGTH_REQUIRED(411),
    /** 412 — uma pré-condição do pedido falhou. */
    PRECONDITION_FAILED(412),
    /** 413 — corpo do pedido grande demais. */
    PAYLOAD_TOO_LARGE(413),
    /** 414 — URL longa demais. */
    URI_TOO_LONG(414),
    /** 415 — formato do corpo não suportado. */
    UNSUPPORTED_MEDIA_TYPE(415),
    /** 416 — o intervalo pedido em {@code Range} não pode ser atendido. */
    RANGE_NOT_SATISFIABLE(416),
    /** 417 — a expectativa do cabeçalho {@code Expect} não pode ser atendida. */
    EXPECTATION_FAILED(417),
    /** 418 — código de brincadeira da RFC 2324 ("sou um bule de chá"). */
    IM_A_TEAPOT(418),
    /** 422 — pedido bem formado, mas com conteúdo que não passa na validação. */
    UNPROCESSABLE_ENTITY(422),
    /** 429 — pedidos demais num intervalo curto (limite de taxa). */
    TOO_MANY_REQUESTS(429),

    // 5xx — erro do servidor

    /** 500 — erro interno do servidor. */
    INTERNAL_SERVER_ERROR(500),
    /** 501 — o servidor não implementa a funcionalidade pedida. */
    NOT_IMPLEMENTED(501),
    /** 502 — o gateway recebeu uma resposta inválida do servidor de origem. */
    BAD_GATEWAY(502),
    /** 503 — serviço indisponível no momento (sobrecarga ou manutenção). */
    SERVICE_UNAVAILABLE(503),
    /** 504 — o gateway esgotou o tempo esperando o servidor de origem. */
    GATEWAY_TIMEOUT(504),
    /** 505 — versão do HTTP não suportada. */
    HTTP_VERSION_NOT_SUPPORTED(505);

    /** Lookup O(1) de código numérico → constante, construído uma única vez. */
    private static final Map<Integer, StatusCode> BY_CODE = buildLookup();

    /** Valor numérico do status. */
    private final int code;

    StatusCode(int code) {
        this.code = code;
    }

    /**
     * Retorna o código numérico do status HTTP.
     *
     * @return Valor inteiro do código (ex: 200, 404)
     */
    public int code() {
        return code;
    }

    /**
     * Obtém a constante {@code StatusCode} correspondente ao código numérico.
     *
     * @param code Código HTTP (ex: 200, 404)
     * @return Constante do enum, ou {@code null} se o código não tiver constante
     */
    public static StatusCode fromCode(int code) {
        return BY_CODE.get(code);
    }

    /** Monta o mapa imutável de código → constante. */
    private static Map<Integer, StatusCode> buildLookup() {
        Map<Integer, StatusCode> map = new HashMap<>();
        for (StatusCode status : values()) {
            map.put(status.code, status);
        }
        return Collections.unmodifiableMap(map);
    }
}
