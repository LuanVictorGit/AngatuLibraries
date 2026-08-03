package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidade persistida (via {@link Saveable}) com a configuração de rate limit
 * de uma rota, na tabela {@code routeratelimitconfigs}.
 *
 * <p>É salva em banco para que as configurações sobrevivam a reinicializações
 * do servidor e sejam recarregadas nos caches in-memory na inicialização.</p>
 *
 * @author Angatu Sistemas
 * @see Saveable
 * @see RateLimitConfig
 */
@Getter
@Setter
@NoArgsConstructor
public class RouteRateLimitConfig extends Saveable {

    private String id;
    private String pathPattern;
    private int requestsPerSecond;
    private int requestsPerMinute;
    private long blockSeconds;
    private boolean perIp;
    private boolean enabled;
    private long createdAt;

    /**
     * Cria uma configuração de rate limit para uma rota, ativa por padrão.
     *
     * @param pathPattern        Padrão do path (ex: {@code "/api/*"})
     * @param requestsPerSecond  Limite de requisições por segundo
     * @param requestsPerMinute  Limite de requisições por minuto
     * @param blockSeconds       Duração do bloqueio em segundos após exceder
     * @param perIp              {@code true} para aplicar o limite por IP
     */
    public RouteRateLimitConfig(String pathPattern, int requestsPerSecond, int requestsPerMinute, long blockSeconds,
            boolean perIp) {
        this.id = UUID.randomUUID().toString();
        this.pathPattern = pathPattern;
        this.requestsPerSecond = requestsPerSecond;
        this.requestsPerMinute = requestsPerMinute;
        this.blockSeconds = blockSeconds;
        this.perIp = perIp;
        this.enabled = true;
        this.createdAt = Instant.now().getEpochSecond();
    }
}
