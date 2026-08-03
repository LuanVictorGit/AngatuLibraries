package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidade persistida (via {@link Saveable}) que registra um bloqueio permanente
 * de um IP na tabela {@code permanentblocks}.
 *
 * <p>Cada bloqueio possui hash do IP, motivo, contagem de violações que o
 * causaram e expiração (30 dias por padrão). Bloqueios expirados são removidos
 * automaticamente pela limpeza periódica do {@code JavalinAPI}.</p>
 *
 * @author Angatu Sistemas
 * @see Saveable
 */
@Getter
@Setter
@NoArgsConstructor
public class PermanentBlock extends Saveable {

    /** Duração padrão do bloqueio permanente em segundos (30 dias). */
    public static final long DEFAULT_DURATION_SEC = 30L * 24 * 60 * 60;

    private String id;
    private String ipHash;
    private String reason;
    private long blockedAt;
    private long expiresAt;
    private int violationCount;
    private String blockedBy;

    /**
     * Cria um bloqueio permanente com duração padrão de 30 dias.
     *
     * @param ipHash         Hash SHA-256 do IP bloqueado
     * @param reason         Motivo do bloqueio
     * @param violationCount Número de violações que causaram o bloqueio
     * @param blockedBy      Quem realizou o bloqueio (ex: "System", "Admin")
     */
    public PermanentBlock(String ipHash, String reason, int violationCount, String blockedBy) {
        this.id = UUID.randomUUID().toString();
        this.ipHash = ipHash;
        this.reason = reason;
        this.blockedAt = Instant.now().getEpochSecond();
        this.expiresAt = this.blockedAt + DEFAULT_DURATION_SEC;
        this.violationCount = violationCount;
        this.blockedBy = blockedBy;
    }

    /**
     * Verifica se o bloqueio já expirou.
     *
     * @return {@code true} se a expiração já passou
     */
    public boolean isExpired() {
        return Instant.now().getEpochSecond() >= expiresAt;
    }
}
