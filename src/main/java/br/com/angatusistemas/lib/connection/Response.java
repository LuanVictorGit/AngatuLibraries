package br.com.angatusistemas.lib.connection;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Encapsula a resposta de uma requisição HTTP: corpo (body) e status HTTP.
 *
 * <p>É retornada por {@link Request#query} e pode ser usada diretamente:</p>
 * <pre>
 * Response resp = Request.query("GET", "https://api.exemplo.com/users");
 * if (resp.isSuccess()) {
 *     String json = resp.getBody();
 * }
 * </pre>
 *
 * @author Angatu Sistemas
 * @see Request
 * @see StatusCode
 */
@Getter
@AllArgsConstructor
public final class Response {

	/** Corpo da resposta (pode ser {@code null} se não houver conteúdo). */
	private final String body;
	/** Status HTTP da resposta (pode ser {@code null} se o código não for conhecido). */
	private final StatusCode status;

	/**
	 * Verifica se a resposta tem status exatamente igual a {@link StatusCode#OK} (200).
	 *
	 * @return {@code true} se o status for 200
	 */
	public boolean ok() {
		return status == StatusCode.OK;
	}

	/**
	 * Retorna o status HTTP como enum.
	 *
	 * @return Status HTTP, ou {@code null} se o código não for mapeado
	 */
	public StatusCode getStatusCode() {
		return status;
	}

	/**
	 * Retorna o código numérico do status HTTP.
	 *
	 * @return Código HTTP (ex: 200, 404), ou {@code -1} se o status for desconhecido
	 */
	public int getCode() {
		return status != null ? status.code() : -1;
	}

	/**
	 * Verifica se a requisição foi bem-sucedida (status 2xx).
	 *
	 * @return {@code true} se o status estiver entre 200 e 299
	 */
	public boolean isSuccess() {
		return status != null && status.code() >= 200 && status.code() < 300;
	}

	@Override
	public String toString() {
		return String.format("Response{code=%d, body='%s'}", getCode(), body);
	}
}
