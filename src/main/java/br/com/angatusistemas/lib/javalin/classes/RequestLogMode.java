package br.com.angatusistemas.lib.javalin.classes;

/**
 * O que o log de requisições do {@code JavalinAPI} escreve no terminal.
 *
 * <p>O padrão é {@link #ALL}. Sem mexer no código, a variável de ambiente
 * {@code ANGATU_REQUEST_LOG} escolhe o modo na subida ({@code all}, {@code errors} ou
 * {@code off}); {@code JavalinAPI.setRequestLogMode(...)} troca a qualquer momento e vence a
 * variável.</p>
 *
 * @author Angatu Sistemas
 */
public enum RequestLogMode {

    /** Uma linha para toda requisição — o padrão. */
    ALL,

    /** Só as requisições que terminam com status 400 ou mais, ou com exceção. */
    ERRORS,

    /** Nenhuma linha: o log de requisições fica desligado. */
    OFF
}
