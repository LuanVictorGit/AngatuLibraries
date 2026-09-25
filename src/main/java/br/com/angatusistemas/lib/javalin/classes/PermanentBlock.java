package br.com.angatusistemas.lib.javalin.classes;

import java.time.Instant;
import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entidade persistida (via {@link Saveable}) que registra um bloqueio longo de um IP na tabela
 * {@code permanentblocks}.
 *
 * <p>Cada bloqueio possui hash do IP, motivo, contagem de violações que o causaram e expiração
 * (24 horas por padrão). O {@code JavalinAPI} decide pelo bloqueio em memória e grava esta
 * linha fora da requisição; na subida, os bloqueios ainda válidos voltam para a memória.
 * Bloqueios expirados são removidos pela limpeza diária do {@code JavalinAPI}.</p>
 *
 * @author Angatu Sistemas
 * @see Saveable
 */
@Getter
@Setter
@NoArgsConstructor
public class PermanentBlock extends Saveable {

    /**
     * Duração padrão do bloqueio em segundos (24 horas).
     *
     * <p>Eram 30 dias. Bloqueio automático erra — e quando erra, ninguém
     * reclama: a pessoa apenas conclui que o site não funciona e não volta.
     * Um mês de punição sem recurso é caro demais para uma decisão tomada por
     * heurística.</p>
     *
     * <p>Vinte e quatro horas continuam inviabilizando quem está abusando e devolvem sozinhas
     * o acesso de quem caiu ali por engano. O bloqueio não se renova sozinho: se o abuso
     * voltar depois de vencer, as violações somam de novo e um novo bloqueio é criado.</p>
     */
    public static final long DEFAULT_DURATION_SEC = 24L * 60 * 60;

    private String id;
    private String ipHash;
    private String reason;
    private long blockedAt;
    private long expiresAt;
    private int violationCount;
    private String blockedBy;

    /**
     * Cria um bloqueio com duração padrão de 24 horas.
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
