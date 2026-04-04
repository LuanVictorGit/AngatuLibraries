package br.com.angatusistemas.lib.connection;

/**
 * [PT] Enumeração dos códigos de status HTTP conforme as especificações RFC 7231 e extensões.
 * <p>
 * Inclui códigos informativos (1xx), sucesso (2xx), redirecionamento (3xx),
 * erro do cliente (4xx) e erro do servidor (5xx). O método {@link #fromCode(int)} permite
 * obter a constante a partir do valor numérico.
 * </p>
 * <p>
 * Esta enumeração é utilizada pela classe {@link Request} para representar o status
 * das respostas HTTP.
 * </p>
 *
 * [EN] Enumeration of HTTP status codes as per RFC 7231 and extensions.
 * <p>
 * Includes informational (1xx), success (2xx), redirection (3xx),
 * client error (4xx) and server error (5xx) codes. The {@link #fromCode(int)} method
 * allows retrieving the constant from the numeric value.
 * </p>
 * <p>
 * This enumeration is used by the {@link Request} class to represent HTTP response status.
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://tools.ietf.org/html/rfc7231">RFC 7231</a>
 */
public enum StatusCode {

    // 1xx Informational
    CONTINUE(100),
    SWITCHING_PROTOCOLS(101),
    PROCESSING(102),

    // 2xx Success
    OK(200),
    CREATED(201),
    ACCEPTED(202),
    NON_AUTHORITATIVE_INFORMATION(203),
    NO_CONTENT(204),
    RESET_CONTENT(205),
    PARTIAL_CONTENT(206),

    // 3xx Redirection
    MULTIPLE_CHOICES(300),
    MOVED_PERMANENTLY(301),
    FOUND(302),
    SEE_OTHER(303),
    NOT_MODIFIED(304),
    TEMPORARY_REDIRECT(307),
    PERMANENT_REDIRECT(308),

    // 4xx Client Error
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    PAYMENT_REQUIRED(402),
    FORBIDDEN(403),
    NOT_FOUND(404),
    METHOD_NOT_ALLOWED(405),
    NOT_ACCEPTABLE(406),
    PROXY_AUTHENTICATION_REQUIRED(407),
    REQUEST_TIMEOUT(408),
    CONFLICT(409),
    GONE(410),
    LENGTH_REQUIRED(411),
    PRECONDITION_FAILED(412),
    PAYLOAD_TOO_LARGE(413),
    URI_TOO_LONG(414),
    UNSUPPORTED_MEDIA_TYPE(415),
    RANGE_NOT_SATISFIABLE(416),
    EXPECTATION_FAILED(417),
    IM_A_TEAPOT(418),
    UNPROCESSABLE_ENTITY(422),
    TOO_MANY_REQUESTS(429),

    // 5xx Server Error
    INTERNAL_SERVER_ERROR(500),
    NOT_IMPLEMENTED(501),
    BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503),
    GATEWAY_TIMEOUT(504),
    HTTP_VERSION_NOT_SUPPORTED(505);

    private final int code;

    StatusCode(int code) {
        this.code = code;
    }

    /**
     * [PT] Retorna o código numérico do status HTTP.
     * [EN] Returns the numeric HTTP status code.
     *
     * @return [PT] valor inteiro do código (ex: 200, 404)
     *         [EN] integer value of the code (e.g., 200, 404)
     */
    public int code() {
        return code;
    }

    /**
     * [PT] Obtém a constante {@code StatusCode} correspondente ao código numérico.
     * <p>
     * Percorre todos os valores definidos e retorna a primeira correspondência.
     * Se nenhum for encontrado, retorna {@code null}.
     * </p>
     *
     * [EN] Returns the {@code StatusCode} constant corresponding to the numeric code.
     * <p>
     * Iterates over all defined values and returns the first match.
     * If none is found, returns {@code null}.
     * </p>
     *
     * @param code [PT] código HTTP (ex: 200, 404)
     *             [EN] HTTP code (e.g., 200, 404)
     * @return [PT] constante do enum ou {@code null} se não existir
     *         [EN] enum constant or {@code null} if it does not exist
     */
    public static StatusCode fromCode(int code) {
        for (StatusCode status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}