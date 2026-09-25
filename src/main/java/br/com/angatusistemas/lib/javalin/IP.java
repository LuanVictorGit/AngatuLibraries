package br.com.angatusistemas.lib.javalin;

import java.util.Enumeration;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import br.com.angatusistemas.lib.console.Console;
import io.javalin.http.Context;

/**
 * IP real do cliente — a <strong>mesma</strong> regra para toda a biblioteca: rate limit,
 * bloqueio, log e o filtro de rede do projeto leem daqui.
 *
 * <h2>De onde o IP vem</h2>
 * <ol>
 *   <li>Cabeçalho declarado com {@link JavalinAPI#setClientIpHeader(String)} (ex.:
 *       {@code CF-Connecting-IP}), quando existir na requisição;</li>
 *   <li>proxy declarado com {@link JavalinAPI#setTrustedProxyHops(int)}: o item do
 *       {@code X-Forwarded-For} contado <strong>da direita</strong>, pulando exatamente os proxies
 *       confiáveis — o começo da lista é o que o cliente escreve; na falta dele, o
 *       {@code X-Real-IP} gravado pelo proxy;</li>
 *   <li>nada declarado (padrão): se a conexão veio de rede privada — o Traefik do Coolify, um
 *       nginx na mesma máquina —, vale o último item do {@code X-Forwarded-For}, que é o
 *       endereço que esse proxy viu; se veio direto da internet, vale o IP do socket e cabeçalho
 *       nenhum é lido;</li>
 *   <li>o IP do socket ({@link Context#ip()}).</li>
 * </ol>
 *
 * <h2>Por que a regra antiga era perigosa</h2>
 * <p>Esta classe devolvia o <strong>primeiro</strong> item do {@code X-Forwarded-For} e confiava
 * em {@code X-Real-IP}, {@code CF-Connecting-IP} e {@code True-Client-IP} sem condição. Todos
 * esses valores o cliente escreve: mandar {@code X-Forwarded-For: 8.8.8.8} bastava para passar
 * por fora de bloqueio, de limite por IP e de lista de rede, e para registrar no log o endereço
 * de outra pessoa. E a quantidade de proxies declarada no {@code JavalinAPI} não valia aqui.</p>
 *
 * <p>O valor devolvido é sempre um endereço literal (dígitos, hexadecimal, {@code :} e
 * {@code .}); lixo num cabeçalho de proxy nunca chega a chave de limite nem a log.</p>
 *
 * @author Angatu Sistemas
 * @see JavalinAPI#setTrustedProxyHops(int)
 * @see JavalinAPI#setClientIpHeader(String)
 */
public final class IP {

    /** Nenhuma quantidade declarada: confia em um proxy só quando a conexão vem de rede privada. */
    static final int AUTO = -1;

    /** Endereço literal: nunca um nome que alguém tentaria resolver. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern PRIVATE_172 = Pattern.compile("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    private static final Pattern CGNAT_100 = Pattern.compile("^100\\.(6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])\\..*");

    private static volatile int trustedProxyHops = AUTO;
    private static volatile String trustedHeader;
    private static final AtomicBoolean AUTO_NOTICE_SHOWN = new AtomicBoolean();

    private IP() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    /**
     * Retorna o IP real do cliente, pela regra descrita na classe.
     *
     * @param request Contexto da requisição do Javalin (não pode ser nulo)
     * @return IP do cliente, como endereço literal
     */
    public static String get(Context request) {
        String peer = socketAddress(request.ip());

        String header = trustedHeader;
        if (header != null) {
            String declared = literal(request.header(header));
            if (declared != null) return declared;
        }

        int hops = trustedProxyHops;
        if (hops == 0) return peer;

        String forwarded = forwardedFor(request);
        if (hops == AUTO) {
            // Conexão direta da internet: qualquer cabeçalho de proxy foi o cliente que escreveu.
            if (forwarded == null || !isPrivateOrLocal(peer)) return peer;
            String viaProxy = fromForwardedChain(forwarded, 1);
            if (viaProxy == null) return peer;
            if (AUTO_NOTICE_SHOWN.compareAndSet(false, true)) {
                Console.info("IP do cliente lido do X-Forwarded-For: a conexão veio de um proxy em rede privada. "
                        + "Declare JavalinAPI.setTrustedProxyHops(1) — ou 2, com Cloudflare na frente — "
                        + "para fixar essa regra.");
            }
            return viaProxy;
        }

        String viaProxy = fromForwardedChain(forwarded, hops);
        if (viaProxy != null) return viaProxy;
        String realIp = literal(request.header("X-Real-IP")); // proxy que grava só o X-Real-IP
        return realIp != null ? realIp : peer;
    }

    /**
     * O IP é de loopback, de rede privada, link-local ou CGNAT?
     *
     * <p>Um endereço assim atrás do servidor é, na prática, um proxy ou uma rede compartilhada:
     * nunca é tratado como uma pessoa só.</p>
     *
     * @param ip Endereço literal
     * @return {@code true} se não for um endereço público de internet
     */
    static boolean isPrivateOrLocal(String ip) {
        if (ip == null || ip.isBlank()) return true;
        String v = ip.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("[")) v = v.substring(1);
        if (v.startsWith("::ffff:")) v = v.substring(7); // IPv4 dentro de IPv6
        return v.startsWith("127.") || v.equals("::1") || v.startsWith("0:0:0:0:0:0:0:1")
                || v.startsWith("10.") || v.startsWith("192.168.") || v.startsWith("169.254.")
                || v.startsWith("fc") || v.startsWith("fd")
                || v.startsWith("fe8") || v.startsWith("fe9") || v.startsWith("fea") || v.startsWith("feb")
                || PRIVATE_172.matcher(v).matches() || CGNAT_100.matcher(v).matches();
    }

    /** Declarado por {@link JavalinAPI#setTrustedProxyHops(int)}. */
    static void setTrustedProxyHops(int hops) {
        trustedProxyHops = Math.max(0, hops);
    }

    /** Declarado por {@link JavalinAPI#setClientIpHeader(String)}; {@code null} desliga. */
    static void setTrustedHeader(String header) {
        trustedHeader = header == null || header.isBlank() ? null : header.trim();
    }

    /**
     * O endereço do socket como literal: sem colchetes e sem zona ({@code [::1]} vira
     * {@code ::1}, {@code fe80::1%eth0} vira {@code fe80::1}).
     *
     * <p>O servidor devolve o IPv6 do socket entre colchetes, e o que saía daqui não era o
     * endereço literal que esta classe promete: um filtro de rede ou uma lista de liberação do
     * projeto comparava {@code [2001:db8::1]} com {@code 2001:db8::1}, e não casava.</p>
     *
     * @param raw Endereço como o servidor informou
     * @return O literal, ou o próprio valor se ele não for um endereço
     */
    static String socketAddress(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.startsWith("[") && v.endsWith("]")) v = v.substring(1, v.length() - 1);
        int zone = v.indexOf('%');
        if (zone > 0) v = v.substring(0, zone);
        return IP_LITERAL.matcher(v).matches() ? v : raw;
    }

    /**
     * Todas as linhas do {@code X-Forwarded-For}, numa lista só, na ordem em que chegaram.
     *
     * <p>Um proxy que acrescenta a própria linha em vez de emendar na existente (o HAProxy com
     * {@code option forwardfor}, por exemplo) deixa duas linhas no pedido. Lendo só a primeira —
     * a que o cliente escreveu —, o cliente escolhia o próprio IP; o endereço que o proxy viu
     * está na última.</p>
     */
    private static String forwardedFor(Context request) {
        Enumeration<String> lines = request.req().getHeaders("X-Forwarded-For");
        if (lines == null || !lines.hasMoreElements()) return null;
        String first = lines.nextElement();
        if (!lines.hasMoreElements()) return first;
        StringJoiner all = new StringJoiner(",");
        all.add(first);
        while (lines.hasMoreElements()) all.add(lines.nextElement());
        return all.toString();
    }

    /**
     * Item do {@code X-Forwarded-For} contado da direita, pulando {@code hops} proxies.
     *
     * @return Endereço literal, ou {@code null} se a lista estiver vazia ou o item for inválido
     */
    private static String fromForwardedChain(String forwarded, int hops) {
        if (forwarded == null || forwarded.isBlank()) return null;
        String[] chain = forwarded.split(",");
        if (chain.length == 0) return null; // "X-Forwarded-For: ," derrubava a requisição com 500
        // Lista menor que a quantidade declarada: o mais distante que existe.
        return literal(chain[Math.max(0, chain.length - hops)]);
    }

    /**
     * Normaliza um valor de cabeçalho para endereço literal: tira espaços, colchetes e porta.
     *
     * @return O endereço, ou {@code null} se o valor não for um IP
     */
    private static String literal(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || "unknown".equalsIgnoreCase(v)) return null;
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end < 0) return null;
            v = v.substring(1, end); // [::1]:443
        } else if (v.indexOf(':') == v.lastIndexOf(':') && v.indexOf(':') > 0) {
            v = v.substring(0, v.indexOf(':')); // 1.2.3.4:5678
        }
        return IP_LITERAL.matcher(v).matches() ? v : null;
    }
}
