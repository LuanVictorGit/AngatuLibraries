package br.com.angatusistemas.lib.connection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Classe utilitária para realizar requisições HTTP (GET, POST, PUT, DELETE, PATCH)
 * com suporte a token Bearer e corpo JSON.
 *
 * <p>Timeouts padrão: 15 segundos para conexão e leitura.</p>
 *
 * <p>Exemplo de uso:
 * <pre>
 * // GET simples
 * Response resp = Request.query("GET", "https://api.exemplo.com/users");
 *
 * // POST com JSON e token
 * String json = "{\"nome\":\"João\"}";
 * Response resp2 = Request.query("POST", "https://api.exemplo.com/users", json, "meu-token");
 *
 * if (resp2.isSuccess()) {
 *     System.out.println(resp2.getBody());
 * }
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see Response
 * @see StatusCode
 */
public final class Request {

    /** Timeout de conexão em milissegundos. */
    private static final int CONNECT_TIMEOUT_MS = 15000;
    /** Timeout de leitura em milissegundos. */
    private static final int READ_TIMEOUT_MS = 15000;

    private Request() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== MÉTODOS PÚBLICOS ====================

    /**
     * Executa uma requisição HTTP sem corpo e sem token.
     *
     * @param method Método HTTP (GET, POST, PUT, DELETE, PATCH, etc.)
     * @param urlStr URL completa da requisição
     * @return {@link Response} contendo o corpo e o status da resposta
     */
    public static Response query(String method, String urlStr) {
        return query(method, urlStr, null, null);
    }

    /**
     * Executa uma requisição HTTP com corpo (ex: JSON) mas sem token.
     *
     * @param method Método HTTP
     * @param urlStr URL completa
     * @param body   Corpo da requisição (normalmente JSON) — pode ser {@code null}
     * @return {@link Response} contendo o corpo e o status da resposta
     */
    public static Response query(String method, String urlStr, String body) {
        return query(method, urlStr, body, null);
    }

    /**
     * Executa uma requisição HTTP completa com corpo e token Bearer.
     *
     * <p>O token é enviado no header {@code Authorization: Bearer <token>}.
     * Se um corpo for fornecido, ele é enviado em UTF-8 com
     * {@code Content-Type: application/json}.</p>
     *
     * @param method Método HTTP (GET, POST, PUT, DELETE, PATCH, etc.)
     * @param urlStr URL completa
     * @param body   Corpo da requisição (pode ser {@code null})
     * @param token  Token Bearer (pode ser {@code null})
     * @return {@link Response} com corpo e status
     */
    public static Response query(String method, String urlStr, String body, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();

            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            // Headers padrão
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }

            // Envia o corpo apenas quando existir
            if (body != null && !body.isEmpty()) {
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseBody = readStream(is);
            return new Response(responseBody, StatusCode.fromCode(statusCode));

        } catch (IOException e) {
            return new Response("Erro: " + e.getMessage(), StatusCode.INTERNAL_SERVER_ERROR);
        } finally {
            if (conn != null) {
                conn.disconnect(); // Libera os recursos da conexão
            }
        }
    }

    // ==================== MÉTODOS PRIVADOS ====================

    /**
     * Lê todo o conteúdo de um {@link InputStream} como string UTF-8,
     * preservando quebras de linha.
     *
     * @param is Stream de entrada (pode ser {@code null})
     * @return Conteúdo lido, ou {@code null} se a stream for nula
     * @throws IOException em caso de erro de leitura
     */
    private static String readStream(InputStream is) throws IOException {
        if (is == null) return null;
        try (InputStream input = is) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
