package br.com.angatusistemas.lib.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checagem de URL de {@link Bot#sendImageFromUrl}: só {@code http}/{@code https}, só endereços
 * públicos, e nome de anexo sem query string.
 *
 * <p>Nenhum teste consulta DNS: nomes vêm de um resolvedor falso e endereços são montados a partir
 * de literais IP, que o JDK interpreta sem ir à rede.</p>
 *
 * @author Angatu Sistemas
 */
class BotImageUrlTest {

    private static final Map<String, InetAddress[]> HOSTS = new HashMap<>();

    static {
        try {
            HOSTS.put("cdn.exemplo.com.br", new InetAddress[] { address("cdn.exemplo.com.br", 93, 184, 215, 14) });
            HOSTS.put("interno.exemplo.com.br", new InetAddress[] { address("interno.exemplo.com.br", 10, 0, 0, 5) });
            HOSTS.put("misto.exemplo.com.br", new InetAddress[] {
                    address("misto.exemplo.com.br", 93, 184, 215, 14), address("misto.exemplo.com.br", 192, 168, 0, 7) });
            HOSTS.put("metadados.exemplo.com.br",
                    new InetAddress[] { address("metadados.exemplo.com.br", 169, 254, 169, 254) });
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static InetAddress address(String host, int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(host, new byte[] { (byte) a, (byte) b, (byte) c, (byte) d });
    }

    /** Nomes pelo mapa; literais IP pelo JDK (literal não consulta DNS); o resto não resolve. */
    private static final Bot.HostResolver FAKE_DNS = host -> {
        InetAddress[] known = HOSTS.get(host);
        if (known != null) {
            return known;
        }
        boolean literal = host.startsWith("[") || host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.');
        if (literal) {
            return new InetAddress[] { InetAddress.getByName(host) };
        }
        throw new UnknownHostException(host);
    };

    private static InetAddress literal(String ip) throws UnknownHostException {
        return InetAddress.getByName(ip);
    }

    // ==================== ESQUEMA E FORMATO ====================

    @ParameterizedTest
    @ValueSource(strings = { "file:///app/.env", "FILE:///etc/passwd", "ftp://cdn.exemplo.com.br/a.png",
            "jar:file:///app/app.jar!/segredo.txt", "gopher://cdn.exemplo.com.br/", "data:image/png;base64,AAAA",
            "javascript:alert(1)", "/imagens/a.png", "cdn.exemplo.com.br/a.png" })
    @DisplayName("esquemas diferentes de http e https são recusados")
    void rejectsNonHttpSchemes(String url) {
        assertThrows(IllegalArgumentException.class, () -> Bot.checkImageUrl(url, FAKE_DNS));
    }

    @Test
    @DisplayName("URL sem host, vazia, malformada ou com usuário e senha é recusada")
    void rejectsMalformedUrls() {
        assertThrows(IllegalArgumentException.class, () -> Bot.checkImageUrl("http:///a.png", FAKE_DNS));
        assertThrows(IllegalArgumentException.class, () -> Bot.checkImageUrl("", FAKE_DNS));
        assertThrows(IllegalArgumentException.class, () -> Bot.checkImageUrl(null, FAKE_DNS));
        assertThrows(IllegalArgumentException.class, () -> Bot.checkImageUrl("http://exemplo com/a.png", FAKE_DNS));
        assertThrows(IllegalArgumentException.class,
                () -> Bot.checkImageUrl("https://usuario:senha@cdn.exemplo.com.br/a.png", FAKE_DNS));
    }

    // ==================== ENDEREÇOS ====================

    @ParameterizedTest
    @ValueSource(strings = { "http://127.0.0.1/a.png", "http://127.9.8.7/a.png", "http://[::1]/a.png",
            "http://0.0.0.0/a.png", "http://[::]/a.png", "http://169.254.169.254/latest/meta-data/",
            "http://[fe80::1]/a.png", "http://10.1.2.3/a.png", "http://172.16.0.1/a.png", "http://172.31.255.255/a.png",
            "http://192.168.0.10/a.png", "http://100.64.0.1/a.png", "http://100.127.255.255/a.png",
            "http://224.0.0.1/a.png", "http://[ff02::1]/a.png", "http://255.255.255.255/a.png", "http://240.0.0.1/a.png",
            "http://[fd00::1]/a.png", "http://[fc00::1]/a.png", "http://[::ffff:10.0.0.1]/a.png",
            "http://[::ffff:127.0.0.1]/a.png", "http://[2002:a00:1::1]/a.png", "http://[64:ff9b::7f00:1]/a.png",
            "http://192.0.2.1/a.png", "http://198.18.0.1/a.png", "http://[2001:db8::1]/a.png", "http://0.1.2.3/a.png" })
    @DisplayName("loopback, curinga, link-local, privado, CGNAT, multicast, reservado e IPv4 embutido são recusados")
    void rejectsNonPublicAddresses(String url) {
        assertThrows(IllegalArgumentException.class, () -> Bot.checkImageUrl(url, FAKE_DNS));
    }

    @Test
    @DisplayName("nome que resolve para rede interna ou para o serviço de metadados é recusado")
    void rejectsNamesResolvingInside() {
        assertThrows(IllegalArgumentException.class,
                () -> Bot.checkImageUrl("https://interno.exemplo.com.br/a.png", FAKE_DNS));
        assertThrows(IllegalArgumentException.class,
                () -> Bot.checkImageUrl("http://metadados.exemplo.com.br/latest/meta-data/", FAKE_DNS));
    }

    @Test
    @DisplayName("basta um endereço interno entre os do host para recusar")
    void rejectsWhenAnyResolvedAddressIsInternal() {
        assertThrows(IllegalArgumentException.class,
                () -> Bot.checkImageUrl("https://misto.exemplo.com.br/a.png", FAKE_DNS));
    }

    @Test
    @DisplayName("host que não resolve lança UnknownHostException")
    void unknownHostIsReported() {
        assertThrows(UnknownHostException.class, () -> Bot.checkImageUrl("https://nao-existe.invalid/a.png", FAKE_DNS));
    }

    @Test
    @DisplayName("URL pública http ou https é aceita, com porta e query string")
    void acceptsPublicUrls() throws Exception {
        URI uri = Bot.checkImageUrl("https://cdn.exemplo.com.br:8443/img/a.png?token=x", FAKE_DNS);
        assertEquals("cdn.exemplo.com.br", uri.getHost());
        Bot.checkImageUrl("HTTP://93.184.215.14/a.png", FAKE_DNS);
        Bot.checkImageUrl("http://[2606:4700:4700::1111]/a.png", FAKE_DNS);
    }

    @Test
    @DisplayName("as bordas das faixas privadas continuam públicas")
    void rangeBoundariesArePublic() throws Exception {
        assertTrue(Bot.isPublicAddress(literal("172.15.255.255")));
        assertTrue(Bot.isPublicAddress(literal("172.32.0.1")));
        assertTrue(Bot.isPublicAddress(literal("100.63.255.255")));
        assertTrue(Bot.isPublicAddress(literal("100.128.0.1")));
        assertTrue(Bot.isPublicAddress(literal("169.253.255.255")));
        assertTrue(Bot.isPublicAddress(literal("223.255.255.254")));
        assertFalse(Bot.isPublicAddress(literal("224.0.0.0")));
    }

    @Test
    @DisplayName("IPv4 mapeado mantido como Inet6Address também é classificado pelo IPv4 embutido")
    void mappedIpv6KeptAsInet6IsClassifiedByEmbeddedIpv4() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 10;
        mapped[15] = 1;
        assertFalse(Bot.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)));
        mapped[12] = 8;
        mapped[13] = 8;
        mapped[14] = 8;
        mapped[15] = 8;
        assertTrue(Bot.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)));
    }

    // ==================== NOME DO ANEXO ====================

    @Test
    @DisplayName("o nome do anexo vem do caminho, sem query string nem fragmento")
    void fileNameDropsQueryAndFragment() {
        assertEquals("produto.png",
                Bot.imageFileName(URI.create("https://cdn.exemplo.com.br/fotos/produto.png?token=segredo#x"), null));
    }

    @Test
    @DisplayName("caracteres fora de letras, dígitos, ponto, hífen e sublinhado viram sublinhado")
    void fileNameIsSanitized() {
        assertEquals("a_b_.webp", Bot.imageFileName(URI.create("https://x.com/a%20b%3F.webp"), "image/webp"));
        assertEquals("env.png", Bot.imageFileName(URI.create("https://x.com/.env"), null));
    }

    @Test
    @DisplayName("sem nome na URL usa \"imagem\"; sem extensão, ela vem do Content-Type")
    void fileNameDefaultsAndExtension() {
        assertEquals("imagem.png", Bot.imageFileName(URI.create("https://x.com/"), null));
        assertEquals("imagem.jpg", Bot.imageFileName(URI.create("https://x.com"), "image/jpeg"));
        assertEquals("foto.gif", Bot.imageFileName(URI.create("https://x.com/foto"), "image/gif; charset=binary"));
    }

    @Test
    @DisplayName("nome longo é cortado no início, preservando a extensão")
    void longFileNameKeepsExtension() {
        String name = Bot.imageFileName(URI.create("https://x.com/" + "a".repeat(300) + ".jpeg"), null);
        assertEquals(100, name.length());
        assertTrue(name.endsWith(".jpeg"));
    }

    @Test
    @DisplayName("o log mostra só o host, nunca a query string com token")
    void hostForLogNeverLeaksQuery() {
        assertEquals("cdn.exemplo.com.br", Bot.hostForLog("https://cdn.exemplo.com.br/a.png?token=segredo"));
        assertEquals("(URL inválida)", Bot.hostForLog("http://exemplo com/"));
        assertEquals("(URL inválida)", Bot.hostForLog(null));
    }
}
