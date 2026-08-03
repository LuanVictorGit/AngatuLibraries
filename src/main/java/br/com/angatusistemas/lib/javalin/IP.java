package br.com.angatusistemas.lib.javalin;

import io.javalin.http.Context;

/**
 * Utilitário para obtenção do IP real do cliente a partir do {@link Context} do Javalin.
 * <p>
 * Considera cenários com proxy reverso (ex: Nginx, Cloudflare, CDNs),
 * verificando os headers mais comuns utilizados para repassar o IP original.
 * </p>
 *
 * <p>Ordem de verificação:</p>
 * <ol>
 *     <li>{@code X-Forwarded-For} (pode conter múltiplos IPs — pega o primeiro)</li>
 *     <li>{@code X-Real-IP}</li>
 *     <li>{@code CF-Connecting-IP} (Cloudflare)</li>
 *     <li>{@code True-Client-IP} (CDNs como Akamai)</li>
 *     <li>IP direto da conexão ({@link Context#ip()})</li>
 * </ol>
 *
 * <p><b>Importante:</b> Headers como {@code X-Forwarded-For} podem ser manipulados
 * caso o servidor não esteja protegido por um proxy confiável. Para aplicações
 * críticas, configure o proxy para sobrescrever estes headers.</p>
 *
 * @author Angatu Sistemas
 */
public final class IP {

    /** Headers de proxy verificados em ordem de prioridade. */
    private static final String[] FORWARDED_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP", "True-Client-IP"
    };

    private IP() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Retorna o IP real do cliente baseado no contexto da requisição.
     *
     * @param request Contexto da requisição do Javalin (não pode ser nulo)
     * @return IP do cliente em formato String
     */
    public static String get(Context request) {
        for (String header : FORWARDED_HEADERS) {
            String value = request.header(header);
            if (value != null && !value.isBlank() && !"unknown".equalsIgnoreCase(value)) {
                // X-Forwarded-For pode conter: cliente, proxy1, proxy2
                return value.split(",")[0].trim();
            }
        }
        return request.ip();
    }

}
