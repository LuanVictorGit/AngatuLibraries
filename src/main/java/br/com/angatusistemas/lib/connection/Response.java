package br.com.angatusistemas.lib.connection;

/**
 * Resposta de uma requisição HTTP feita pelo {@link Request}: corpo, código de status e, quando
 * não houve resposta HTTP, a falha que impediu a troca.
 *
 * <p><strong>Duas situações, dois sinais.</strong> Quando o servidor responde, qualquer que seja o
 * código (200, 207, 404, 522...), {@link #getCode()} traz o código recebido e
 * {@link #isNetworkError()} é {@code false}. Quando não há resposta utilizável — conexão recusada,
 * nome que não resolve, TLS, tempo esgotado, corpo acima do limite, URL inválida —,
 * {@link #isNetworkError()} é {@code true}, {@link #getError()} traz a exceção e o corpo traz a
 * explicação em português, começando por {@code "Erro: "}.</p>
 *
 * <p><strong>Compatibilidade:</strong> numa falha sem resposta HTTP, {@link #getCode()} continua
 * {@code 500} e {@link #getStatusCode()} continua {@link StatusCode#INTERNAL_SERVER_ERROR}, como
 * nas versões anteriores — código antigo que tratava "500" como falha segue funcionando. Para
 * distinguir um 500 de verdade do servidor de uma falha de rede, use {@link #isNetworkError()}:
 * antes os dois eram idênticos, e quem precisava decidir se repetia um {@code POST} não tinha
 * como saber se o servidor chegou a processá-lo.</p>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * Response resp = Request.query("GET", "https://api.exemplo.com/users");
 * if (resp.isSuccess()) {
 *     String json = resp.getBody();
 * } else if (resp.isNetworkError()) {
 *     // sem resposta do servidor: resp.getError() diz o motivo
 * } else {
 *     // o servidor respondeu com erro: resp.getCode() (ex.: 404, 422, 500)
 * }
 * </pre>
 *
 * @author Angatu Sistemas
 * @see Request
 * @see StatusCode
 */
public final class Response {

    /** Corpo da resposta ({@code null} só quando quem criou a instância passou {@code null}). */
    private final String body;
    /** Status como constante do enum, ou {@code null} quando o código não tem constante. */
    private final StatusCode status;
    /** Código HTTP recebido, mesmo sem constante no enum (ex.: 207, 522). */
    private final int code;
    /**
     * Falha que impediu a resposta HTTP, ou {@code null} quando o servidor respondeu.
     * {@code transient}: uma exceção não serializa com o Gson no JDK 21, e a resposta
     * continua serializável como antes.
     */
    private final transient Exception error;

    /**
     * Cria a resposta a partir de uma constante de status (construtor original).
     *
     * @param body   Corpo da resposta (pode ser {@code null})
     * @param status Status HTTP; {@code null} equivale a código desconhecido ({@code -1})
     */
    public Response(String body, StatusCode status) {
        this.body = body;
        this.status = status;
        this.code = status != null ? status.code() : -1;
        this.error = null;
    }

    /**
     * Cria a resposta a partir do código HTTP recebido, preservando códigos que não têm
     * constante em {@link StatusCode} (ex.: 207, 522).
     *
     * @param body Corpo da resposta (pode ser {@code null})
     * @param code Código HTTP recebido
     */
    public Response(String body, int code) {
        this(body, code, null);
    }

    /**
     * Cria a resposta com o código HTTP e a falha que impediu a troca, se houve.
     *
     * @param body  Corpo da resposta, ou a explicação da falha (pode ser {@code null})
     * @param code  Código HTTP recebido; numa falha sem resposta HTTP o {@link Request} usa
     *              {@code 500}, por compatibilidade
     * @param error Falha que impediu a resposta HTTP, ou {@code null} se o servidor respondeu
     */
    public Response(String body, int code, Exception error) {
        this.body = body;
        this.status = StatusCode.fromCode(code);
        this.code = code;
        this.error = error;
    }

    /**
     * Retorna o corpo da resposta. O {@link Request} devolve {@code ""} quando o servidor não
     * envia corpo e a explicação da falha (começando por {@code "Erro: "}) quando não houve
     * resposta HTTP.
     *
     * @return Corpo da resposta
     */
    public String getBody() {
        return body;
    }

    /**
     * Retorna o status HTTP como enum.
     *
     * @return Status HTTP, ou {@code null} se o código não tiver constante em {@link StatusCode}
     */
    public StatusCode getStatus() {
        return status;
    }

    /**
     * Retorna o status HTTP como enum (mesmo valor de {@link #getStatus()}).
     *
     * @return Status HTTP, ou {@code null} se o código não tiver constante em {@link StatusCode}
     */
    public StatusCode getStatusCode() {
        return status;
    }

    /**
     * Retorna o código numérico do status HTTP, tal como recebido do servidor.
     *
     * <p>Antes, um código sem constante no enum (207, 522...) virava {@code -1} e se perdia.
     * Agora o número recebido é devolvido sempre; {@code -1} só aparece numa instância criada
     * com {@link #Response(String, StatusCode)} e status {@code null}.</p>
     *
     * @return Código HTTP (ex.: 200, 207, 404, 522); {@code 500} numa falha sem resposta HTTP
     */
    public int getCode() {
        return code;
    }

    /**
     * Verifica se a resposta tem código exatamente 200 (OK).
     *
     * @return {@code true} se o servidor respondeu 200
     */
    public boolean ok() {
        return error == null && code == 200;
    }

    /**
     * Verifica se a requisição foi bem-sucedida: o servidor respondeu com um código entre 200 e
     * 299, inclusive os que não têm constante no enum (ex.: 207).
     *
     * @return {@code true} se houve resposta HTTP com código 2xx
     */
    public boolean isSuccess() {
        return error == null && code >= 200 && code <= 299;
    }

    /**
     * Indica se a troca falhou sem resposta HTTP utilizável: URL inválida, nome que não
     * resolve, conexão recusada, falha de TLS, tempo esgotado ou corpo acima do limite.
     *
     * <p><strong>Não quer dizer que repetir é seguro.</strong> Num tempo esgotado depois do
     * envio, o servidor pode ter recebido e processado o pedido; só repita automaticamente um
     * {@code POST} que seja idempotente do lado do servidor.</p>
     *
     * @return {@code true} se não houve resposta HTTP; o motivo está em {@link #getError()}
     */
    public boolean isNetworkError() {
        return error != null;
    }

    /**
     * Retorna a falha que impediu a resposta HTTP.
     *
     * @return Exceção da falha, ou {@code null} quando o servidor respondeu
     */
    public Exception getError() {
        return error;
    }

    /**
     * Representação para log: código e corpo, e o tipo da falha quando não houve resposta HTTP.
     *
     * @return Texto no formato {@code Response{code=..., body='...'}}
     */
    @Override
    public String toString() {
        if (error == null) {
            return String.format("Response{code=%d, body='%s'}", code, body);
        }
        return String.format("Response{code=%d, error=%s, body='%s'}", code,
                error.getClass().getSimpleName(), body);
    }
}
