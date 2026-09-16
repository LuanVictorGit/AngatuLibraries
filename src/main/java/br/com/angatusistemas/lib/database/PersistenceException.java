package br.com.angatusistemas.lib.database;

/**
 * Falha ao falar com o banco: conexão indisponível, arquivo travado, disco cheio, SQL recusado.
 *
 * <h3>Por que esta classe existe</h3>
 * <p>Até a versão anterior, {@code read} e {@code write} capturavam a {@code SQLException},
 * escreviam no log e <strong>devolviam um valor de recuo</strong>. O problema é que esse valor de
 * recuo também é um resultado legítimo:</p>
 *
 * <table border="1">
 *   <caption>O que o chamador via</caption>
 *   <tr><th>Método</th><th>Recuo</th><th>Significado ambíguo</th></tr>
 *   <tr><td>{@code findById}</td><td>{@code null}</td><td>"o registro não existe"</td></tr>
 *   <tr><td>{@code mutate}</td><td>{@code null}</td><td>"o registro não existe"</td></tr>
 *   <tr><td>{@code save}</td><td>{@code false}</td><td>"não gravou"</td></tr>
 *   <tr><td>{@code query}/{@code findAll}</td><td>lista vazia</td><td>"não há registros"</td></tr>
 * </table>
 *
 * <p>Quem consome a biblioteca não tinha como distinguir "o banco falhou" de "não havia nada", e
 * a diferença entre as duas coisas é enorme: aplicações reagem a "não existe" <strong>criando o
 * registro com os valores padrão</strong>. Uma falha passageira de banco virava, assim, uma
 * gravação destrutiva por cima de configuração real — e o sintoma que chegava ao usuário não era
 * "deu erro", era "minhas configurações voltaram de fábrica".</p>
 *
 * <p>Por isso a falha agora <strong>sobe</strong>. Uma requisição que devolve erro é honesta;
 * uma que devolve "vazio" faz o chamador apagar dados. Quem precisa de tolerância captura esta
 * exceção explicitamente — e aí a decisão de seguir em frente está escrita no código, em vez de
 * escondida aqui dentro.</p>
 *
 * <p>É {@code RuntimeException} de propósito: a persistência aparece em praticamente toda
 * assinatura da biblioteca, e obrigar {@code throws} em cada uma delas só produziria
 * {@code catch} vazio — que é exatamente o comportamento que esta classe veio corrigir.</p>
 *
 * @author Angatu Sistemas
 */
public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
