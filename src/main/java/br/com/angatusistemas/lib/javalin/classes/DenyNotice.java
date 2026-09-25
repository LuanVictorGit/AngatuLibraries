package br.com.angatusistemas.lib.javalin.classes;

/**
 * Recusa que o servidor vai mostrar ao navegador — o que a página de recusa do projeto precisa
 * para se montar (ver {@link br.com.angatusistemas.lib.javalin.JavalinAPI#setDenyPage}).
 *
 * <p>Os textos são da própria biblioteca, em PT-BR, e nunca trazem nada vindo da requisição.
 * Ainda assim são texto puro: escape-os ao montar o HTML.</p>
 *
 * @param status            Código HTTP da recusa: 429 (acessos demais) ou 403 (bloqueio longo ou
 *                          conteúdo recusado)
 * @param code              Código curto: {@code too_many_requests}, {@code blocked} ou
 *                          {@code rejected} — o mesmo que a chamada de API recebe no JSON
 * @param title             Título curto, em linguagem comum
 * @param message           Explicação em uma frase, texto puro
 * @param retryAfterSeconds Segundos até a liberação, ou {@code 0} quando não há prazo exato a
 *                          mostrar
 * @author Angatu Sistemas
 */
public record DenyNotice(int status, String code, String title, String message, long retryAfterSeconds) {
}
