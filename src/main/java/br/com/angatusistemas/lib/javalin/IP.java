package br.com.angatusistemas.lib.javalin;

import io.javalin.http.Context;

/**
 * Utilitário para obtenção do IP real do cliente a partir do {@link Context} do Javalin.
 * <p>
 * Este método considera cenários com proxy reverso (ex: Nginx, Cloudflare),
 * verificando os headers mais comuns utilizados para repassar o IP original.
 * </p>
 *
 * <p>Ordem de verificação:</p>
 * <ol>
 *     <li>X-Forwarded-For (pode conter múltiplos IPs, pega o primeiro)</li>
 *     <li>X-Real-IP</li>
 *     <li>CF-Connecting-IP (Cloudflare)</li>
 *     <li>IP direto da conexão (ctx.ip())</li>
 * </ol>
 *
 * <p><b>Importante:</b> Headers como X-Forwarded-For podem ser manipulados
 * caso o servidor não esteja protegido por um proxy confiável.</p>
 */
public class IP {

    /**
     * Retorna o IP real do cliente baseado no contexto da requisição.
     *
     * @param request Contexto da requisição do Javalin
     * @return IP do cliente em formato String
     */
    public static String get(Context request) {
        String ip;

        // 1. X-Forwarded-For (pode conter múltiplos IPs)
        ip = request.header("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            // formato: client, proxy1, proxy2
            return ip.split(",")[0].trim();
        }

        // 2. X-Real-IP
        ip = request.header("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }

        // 3. Cloudflare
        ip = request.header("CF-Connecting-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }

        // 4. fallback (IP direto)
        return request.ip();
    }

}