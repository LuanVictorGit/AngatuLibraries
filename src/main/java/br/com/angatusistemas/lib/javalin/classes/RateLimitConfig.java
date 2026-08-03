package br.com.angatusistemas.lib.javalin.classes;

/**
 * Configuração de rate limit em memória: limites de requisições por segundo e
 * por minuto, duração do bloqueio e granularidade por IP.
 *
 * <p>Classe imutável de dados, usada pelo {@code JavalinAPI} para configurar
 * limites de requisições por rota ou globalmente:</p>
 * <pre>
 * // 3 req/s, 20 req/min, bloqueio de 2 minutos por IP
 * JavalinAPI.configureRateLimit("/api/*", new RateLimitConfig(3, 20, 120));
 * </pre>
 *
 * <p>Os campos também são acessíveis diretamente (públicos e finais) por
 * convenção da API.</p>
 *
 * @author Angatu Sistemas
 * @see RouteRateLimitConfig
 */
public final class RateLimitConfig {

    /** Máximo de requisições por segundo dentro da janela deslizante. */
    public final int requestsPerSecond;
    /** Máximo de requisições por minuto dentro da janela deslizante. */
    public final int requestsPerMinute;
    /** Duração do bloqueio em segundos quando os limites são excedidos. */
    public final long blockSeconds;
    /** {@code true} aplica o limite por IP; {@code false} compartilha o limite entre todos os IPs. */
    public final boolean perIp;

    /**
     * Cria uma configuração de rate limit completa.
     *
     * @param requestsPerSecond Máximo de requisições por segundo
     * @param requestsPerMinute Máximo de requisições por minuto
     * @param blockSeconds      Duração do bloqueio em segundos
     * @param perIp             {@code true} para limitar por IP
     */
    public RateLimitConfig(int requestsPerSecond, int requestsPerMinute, long blockSeconds, boolean perIp) {
        this.requestsPerSecond = requestsPerSecond;
        this.requestsPerMinute = requestsPerMinute;
        this.blockSeconds = blockSeconds;
        this.perIp = perIp;
    }

    /**
     * Cria uma configuração de rate limit com granularidade por IP
     * ({@code perIp = true}).
     *
     * @param requestsPerSecond Máximo de requisições por segundo
     * @param requestsPerMinute Máximo de requisições por minuto
     * @param blockSeconds      Duração do bloqueio em segundos
     */
    public RateLimitConfig(int requestsPerSecond, int requestsPerMinute, long blockSeconds) {
        this(requestsPerSecond, requestsPerMinute, blockSeconds, true);
    }

    /**
     * Retorna o máximo de requisições por segundo.
     *
     * @return Limite por segundo
     */
    public int getRequestsPerSecond() {
        return requestsPerSecond;
    }

    /**
     * Retorna o máximo de requisições por minuto.
     *
     * @return Limite por minuto
     */
    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    /**
     * Retorna a duração do bloqueio em segundos.
     *
     * @return Duração do bloqueio
     */
    public long getBlockSeconds() {
        return blockSeconds;
    }

    /**
     * Verifica se o limite é aplicado por IP.
     *
     * @return {@code true} se a granularidade é por IP
     */
    public boolean isPerIp() {
        return perIp;
    }
}
