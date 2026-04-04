package br.com.angatusistemas.lib.connection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * [PT] Classe utilitária para realizar requisições HTTP (GET, POST, PUT, DELETE, etc.)
 * com suporte a token Bearer, corpo JSON e tratamento de respostas.
 * <p>
 * Os métodos retornam um objeto {@link Response} contendo o corpo da resposta e o status HTTP.
 * Timeouts padrão: 15 segundos para conexão e leitura.
 * </p>
 * <p>
 * Exemplo de uso:
 * <pre>
 * // GET simples
 * Response resp = Request.query("GET", "https://api.exemplo.com/users");
 *
 * // POST com JSON e token
 * String json = "{\"nome\":\"João\"}";
 * Response resp2 = Request.query("POST", "https://api.exemplo.com/users", json, "meu-token");
 * </pre>
 * </p>
 *
 * [EN] Utility class for making HTTP requests (GET, POST, PUT, DELETE, etc.)
 * with Bearer token support, JSON body and response handling.
 * <p>
 * Methods return a {@link Response} object containing the response body and HTTP status.
 * Default timeouts: 15 seconds for connection and read.
 * </p>
 * <p>
 * Usage example:
 * <pre>
 * // Simple GET
 * Response resp = Request.query("GET", "https://api.example.com/users");
 *
 * // POST with JSON and token
 * String json = "{\"name\":\"John\"}";
 * Response resp2 = Request.query("POST", "https://api.example.com/users", json, "my-token");
 * </pre>
 * </p>
 *
 * @author [Sua equipe]
 * @see Response
 * @see HttpURLConnection
 */
public final class Request {

    // Timeouts em milissegundos
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;

    private Request() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODOS PÚBLICOS ====================
    // ==================== PUBLIC METHODS ====================

    /**
     * [PT] Executa uma requisição HTTP sem corpo e sem token.
     *
     * [EN] Executes an HTTP request without body and without token.
     *
     * @param method  [PT] método HTTP (GET, POST, PUT, DELETE, etc.)
     *                [EN] HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param urlStr  [PT] URL completa da requisição
     *                [EN] complete request URL
     * @return [PT] objeto {@link Response} contendo o resultado
     *         [EN] {@link Response} object containing the result
     */
    public static Response query(String method, String urlStr) {
        return query(method, urlStr, null, null);
    }

    /**
     * [PT] Executa uma requisição HTTP com corpo (ex: JSON) mas sem token.
     *
     * [EN] Executes an HTTP request with body (e.g., JSON) but without token.
     *
     * @param method  [PT] método HTTP
     *                [EN] HTTP method
     * @param urlStr  [PT] URL completa
     *                [EN] complete URL
     * @param body    [PT] corpo da requisição (normalmente JSON) – pode ser {@code null}
     *                [EN] request body (usually JSON) – may be {@code null}
     * @return [PT] objeto {@link Response}
     *         [EN] {@link Response} object
     */
    public static Response query(String method, String urlStr, String body) {
        return query(method, urlStr, body, null);
    }

    /**
     * [PT] Executa uma requisição HTTP completa com corpo e token Bearer.
     * <p>
     * O token é adicionado no header {@code Authorization: Bearer <token>}.
     * O corpo, se fornecido, é enviado como UTF-8 e o header {@code Content-Type}
     * é definido como {@code application/json}.
     * </p>
     *
     * [EN] Executes a full HTTP request with body and Bearer token.
     * <p>
     * The token is added to the header {@code Authorization: Bearer <token>}.
     * If a body is provided, it is sent as UTF-8 and the {@code Content-Type}
     * header is set to {@code application/json}.
     * </p>
     *
     * @param method  [PT] método HTTP (GET, POST, PUT, DELETE, PATCH, etc.)
     *                [EN] HTTP method (GET, POST, PUT, DELETE, PATCH, etc.)
     * @param urlStr  [PT] URL completa
     *                [EN] complete URL
     * @param body    [PT] corpo da requisição (pode ser {@code null})
     *                [EN] request body (may be {@code null})
     * @param token   [PT] token Bearer (pode ser {@code null})
     *                [EN] Bearer token (may be {@code null})
     * @return [PT] objeto {@link Response} com corpo e status
     *         [EN] {@link Response} object with body and status
     */
    @SuppressWarnings("deprecation")
	public static Response query(String method, String urlStr, String body, String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            // Headers padrão
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setRequestProperty("Content-Type", "application/json");

            // Envia corpo se existir
            if (body != null && !body.isEmpty()) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = body.getBytes(StandardCharsets.UTF_8);
                    os.write(input);
                    os.flush();
                }
            }

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseBody = readStream(is);
            return new Response(responseBody, StatusCode.fromCode(statusCode));

        } catch (Exception e) {
            return new Response("❌ Erro: " + e.getMessage(), StatusCode.INTERNAL_SERVER_ERROR);
        } finally {
            if (conn != null) {
                conn.disconnect(); // Fecha a conexão para liberar recursos
            }
        }
    }

    // ==================== MÉTODOS PRIVADOS ====================
    // ==================== PRIVATE METHODS ====================

    /**
     * [PT] Lê todo o conteúdo de um {@link InputStream} como string UTF-8.
     *
     * [EN] Reads the entire content of an {@link InputStream} as a UTF-8 string.
     *
     * @param is [PT] stream de entrada (pode ser {@code null})
     *           [EN] input stream (may be {@code null})
     * @return [PT] conteúdo lido, ou {@code null} se a stream for nula
     *         [EN] read content, or {@code null} if stream is null
     * @throws IOException [PT] em caso de erro de leitura
     *                     [EN] on read error
     */
    private static String readStream(InputStream is) throws IOException {
        if (is == null) return null;

        // Usa StringBuilder para eficiência e BufferedReader para leitura linha a linha
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    /**
     * [PT] Classe que encapsula a resposta de uma requisição HTTP.
     * [EN] Class that encapsulates the response of an HTTP request.
     */
    public static class Response {
        private final String body;
        private final StatusCode statusCode;

        public Response(String body, StatusCode statusCode) {
            this.body = body;
            this.statusCode = statusCode;
        }

        /**
         * [PT] Retorna o corpo da resposta (string).
         * [EN] Returns the response body (string).
         */
        public String getBody() {
            return body;
        }

        /**
         * [PT] Retorna o status HTTP como enum.
         * [EN] Returns the HTTP status as enum.
         */
        public StatusCode getStatusCode() {
            return statusCode;
        }

        /**
         * [PT] Retorna o código numérico do status.
         * [EN] Returns the numeric status code.
         */
        public int getCode() {
            return statusCode != null ? statusCode.code() : -1;
        }

        /**
         * [PT] Verifica se a requisição foi bem-sucedida (status 2xx).
         * [EN] Checks if the request was successful (2xx status).
         */
        public boolean isSuccess() {
            return statusCode != null && statusCode.code() >= 200 && statusCode.code() < 300;
        }

        @Override
        public String toString() {
            return String.format("Response{code=%d, body='%s'}", getCode(), body);
        }
    }
}