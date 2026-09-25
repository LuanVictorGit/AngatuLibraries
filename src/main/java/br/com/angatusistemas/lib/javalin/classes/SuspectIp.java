package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidade persistida (via {@link Saveable}) com o histórico de um IP que estourou limites:
 * total de violações, timestamps da primeira/última violação e a marca de bloqueio longo.
 *
 * <p>É registro para quem administra, e não o que decide o bloqueio: o {@code JavalinAPI}
 * decide pela janela de violações em memória e grava estas linhas em lote, a cada minuto.
 * Contam como violação o estouro de rate limit e a rajada de requisições; a recusa de conteúdo
 * com cara de SQL Injection ou XSS não conta. A marca {@code isPermanentlyBlocked} é estado
 * derivado — a verdade do bloqueio é a linha em {@code permanentblocks}.</p>
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
     * Soma uma violação, agora, a esta instância.
     *
     * <p>Sincronizado só dentro da instância: duas cópias da mesma linha, lidas por threads
     * diferentes, somam cada uma a sua e a última gravação vence. Para somar sem perder nada,
     * altere a linha dentro de {@link Saveable#transaction(Runnable)} ou de
     * {@link Saveable#mutate}.</p>
     */
    public synchronized void incrementViolations() {
        registerViolations(1, Instant.now().getEpochSecond());
    }

    /**
     * Soma várias violações de uma vez — a gravação em lote do {@code JavalinAPI}.
     *
     * @param count Violações a somar (zero ou negativo não muda nada)
     * @param at    Instante da mais recente, em segundos (epoch)
     */
    public synchronized void registerViolations(int count, long at) {
        if (count <= 0) return;
        this.totalViolations += count;
        this.lastViolationAt = Math.max(this.lastViolationAt, at);
    }
}
