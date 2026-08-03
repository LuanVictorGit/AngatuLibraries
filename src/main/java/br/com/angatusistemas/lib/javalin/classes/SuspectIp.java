package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidade persistida (via {@link Saveable}) com estatísticas de um IP suspeito:
 * total de violações, timestamps da primeira/última violação e flag de bloqueio
 * permanente.
 *
 * <p>Utilizada pelo rate limiting do {@code JavalinAPI} para acumular violações
 * de segurança (SQL Injection, XSS, burst attacks) e decidir bloqueios permanentes.</p>
 *
 * @author Angatu Sistemas
 * @see Saveable
 */
@Getter
@Setter
@NoArgsConstructor
public class SuspectIp extends Saveable {

    private String id;
    private String ipHash;
    private int totalViolations;
    private long firstViolationAt;
    private long lastViolationAt;
    private boolean isPermanentlyBlocked;

    /**
     * Cria um registro de IP suspeito com zero violações no instante atual.
     *
     * @param ipHash Hash SHA-256 do IP suspeito
     */
    public SuspectIp(String ipHash) {
        this.id = UUID.randomUUID().toString();
        this.ipHash = ipHash;
        this.totalViolations = 0;
        long now = Instant.now().getEpochSecond();
        this.firstViolationAt = now;
        this.lastViolationAt = now;
        this.isPermanentlyBlocked = false;
    }

    /**
     * Incrementa a contagem de violações de forma thread-safe e atualiza o
     * timestamp da última violação.
     */
    public synchronized void incrementViolations() {
        this.totalViolations++;
        this.lastViolationAt = Instant.now().getEpochSecond();
    }
}
