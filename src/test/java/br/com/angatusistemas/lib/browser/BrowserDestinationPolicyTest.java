package br.com.angatusistemas.lib.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regras de destino da BrowserAPI: esquemas aceitos, faixas de rede interna, leitura do host
 * como o navegador faz e a liberação explícita de rede interna.
 *
 * <p>Sem navegador e sem rede: o DNS é um resolvedor falso, e os IPs são literais.</p>
 *
 * @author Angatu Sistemas
 */
class BrowserDestinationPolicyTest {

    /** DNS falso: só conhece estes nomes. IP literal nunca chega aqui como nome a resolver. */
    private static final Map<String, List<String>> FAKE_DNS = Map.of(
            "public.example", List.of("93.184.216.34"),
            "loopback.example", List.of("127.0.0.1"),
            "mixed.example", List.of("93.184.216.34", "10.0.0.7"),
            "metadata.example", List.of("169.254.169.254"),
            "v6-internal.example", List.of("fd00:ec2::254"),
            "localhost", List.of("127.0.0.1"));

    private static InetAddress[] fakeResolve(String host) throws UnknownHostException {
        List<String> ips = FAKE_DNS.get(host);
        if (ips == null) {
            if (host.indexOf(':') >= 0) {
                return InetAddress.getAllByName(host); // IPv6 literal: só interpretação, sem DNS
            }
            throw new UnknownHostException(host);
        }
        InetAddress[] addresses = new InetAddress[ips.size()];
        for (int i = 0; i < addresses.length; i++) {
            addresses[i] = InetAddress.getByName(ips.get(i)); // literais: sem DNS
        }
        return addresses;
    }

    private static void check(String url) throws IOException {
        BrowserAPI.checkNavigationUrl(url, BrowserDestinationPolicyTest::fakeResolve, false);
    }

    // ==================== ESQUEMAS ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///C:/Windows/win.ini",
            "  FILE:///etc/passwd",
            "java\tscript:alert(1)",
            "data:text/html,<h1>x</h1>",
            "ftp://public.example/",
            "about:blank",
            "chrome://settings",
            "//public.example/",
            "public.example/caminho"})
    @DisplayName("só http e https passam: arquivo local, data:, javascript: e URL sem esquema são recusados")
    void rejectsEverySchemeButHttpAndHttps(String url) {
        IOException error = assertThrows(IOException.class, () -> check(url));
        assertTrue(error.getMessage().contains("http:// ou https://"), error.getMessage());
    }

    @Test
    @DisplayName("URL nula ou em branco é recusada com mensagem clara")
    void rejectsMissingUrl() {
        assertEquals("URL não informada.", assertThrows(IOException.class, () -> check(null)).getMessage());
        assertEquals("URL não informada.", assertThrows(IOException.class, () -> check("   ")).getMessage());
    }

    // ==================== REDE INTERNA ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/",
            "http://127.8.9.10/",
            "http://0.0.0.0/",
            "http://10.1.2.3/admin",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.0.1/",
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://100.64.0.1/",
            "http://100.127.255.255/",
            "http://192.0.0.192/",
            "http://[::1]:7070/",
            "http://[::]/",
            "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:7f00:1]/",
            "http://[fd00:ec2::254]/",
            "http://[fe80::1]/",
            "http://[64:ff9b::a00:1]/",
            "http://[2002:a00:1::]/",
            "http://2130706433/",
            "http://0x7f000001/",
            "http://0177.0.0.1/",
            "http://0x7f.1/",
            "http://127.1/",
            "http://%31%32%37.0.0.1/",
            "http://127.0.0.1\\@public.example/",
            "http://public.example:80@127.0.0.1/"})
    @DisplayName("loopback, rede privada, link-local, CGNAT e curinga são recusados, em qualquer grafia")
    void rejectsInternalAddressesInEverySpelling(String url) {
        IOException error = assertThrows(IOException.class, () -> check(url));
        assertTrue(error.getMessage().startsWith("Destino bloqueado por segurança"), error.getMessage());
        assertTrue(error.getMessage().contains("setAllowPrivateNetworkAccess(true)"), "a mensagem ensina a liberação explícita");
    }

    @ParameterizedTest
    @CsvSource({
            "http://loopback.example/, 127.0.0.1",
            "http://mixed.example/, 10.0.0.7",
            "http://metadata.example/, 169.254.169.254",
            "http://v6-internal.example/, fd00:ec2:0:0:0:0:0:254"})
    @DisplayName("nome que resolve para rede interna é recusado — basta um dos endereços devolvidos")
    void rejectsNamesResolvingToInternalAddresses(String url, String internalAddress) {
        IOException error = assertThrows(IOException.class, () -> check(url));
        assertTrue(error.getMessage().contains(internalAddress), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:7070/", "http://foo.localhost/", "http://LOCALHOST./"})
    @DisplayName("localhost e *.localhost são recusados sem nem consultar o DNS")
    void rejectsLocalhostNamesWithoutResolving(String url) {
        BrowserAPI.HostResolver mustNotBeCalled = host -> {
            fail("o DNS não deveria ser consultado para " + host);
            return null;
        };
        IOException error = assertThrows(IOException.class, () -> BrowserAPI.checkNavigationUrl(url, mustNotBeCalled, false));
        assertTrue(error.getMessage().contains("própria máquina"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://256.0.0.1/", "http://1.2.3.4.5/", "http://08.0.0.1/", "http://4294967296/", "http://1..2/"})
    @DisplayName("host que termina em número mas não é IPv4 válido é recusado, como o navegador recusa")
    void rejectsMalformedNumericHosts(String url) {
        IOException error = assertThrows(IOException.class, () -> check(url));
        assertTrue(error.getMessage().contains("host inválido"), error.getMessage());
    }

    @Test
    @DisplayName("host que não resolve é recusado com UnknownHostException")
    void rejectsUnresolvableHost() {
        assertThrows(UnknownHostException.class, () -> check("http://nao-existe.example/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://public.example/caminho?q=1#fragmento",
            "http://public.example:8080",
            "HTTP://PUBLIC.EXAMPLE/",
            "http:public.example",
            "http://public.example\\@127.0.0.1/",
            "http://usuario:senha@127.0.0.1@public.example/"})
    @DisplayName("destino público passa, lido como o navegador lê (barra invertida, último @, sem barras)")
    void acceptsPublicDestinations(String url) throws IOException {
        check(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:7070/relatorio", "http://localhost:7070/", "http://169.254.169.254/", "http://[::1]/"})
    @DisplayName("com a liberação explícita, a rede interna passa")
    void allowsInternalDestinationsWhenExplicitlyEnabled(String url) throws IOException {
        BrowserAPI.checkNavigationUrl(url, BrowserDestinationPolicyTest::fakeResolve, true);
    }

    @Test
    @DisplayName("a liberação de rede interna começa desligada e pode ser ligada e desligada")
    void privateNetworkAccessIsOffByDefault() {
        boolean original = BrowserAPI.isPrivateNetworkAccessAllowed();
        try {
            BrowserAPI.setAllowPrivateNetworkAccess(false);
            assertFalse(BrowserAPI.isPrivateNetworkAccessAllowed());
            BrowserAPI.setAllowPrivateNetworkAccess(true);
            assertTrue(BrowserAPI.isPrivateNetworkAccessAllowed());
        } finally {
            BrowserAPI.setAllowPrivateNetworkAccess(original);
        }
        assertFalse(original, "o padrão precisa ser seguro: rede interna bloqueada");
    }

    // ==================== REQUISIÇÕES DA PÁGINA ====================

    @Test
    @DisplayName("requisição da página: data: e blob: passam, outros esquemas e rede interna não")
    void judgesPageRequests() {
        assertNull(BrowserAPI.requestProblem("data:image/png;base64,AAAA", BrowserDestinationPolicyTest::fakeResolve, false));
        assertNull(BrowserAPI.requestProblem("blob:https://public.example/uuid", BrowserDestinationPolicyTest::fakeResolve, false));
        assertNull(BrowserAPI.requestProblem("https://public.example/app.js", BrowserDestinationPolicyTest::fakeResolve, false));
        assertNotNull(BrowserAPI.requestProblem("file:///C:/Windows/win.ini", BrowserDestinationPolicyTest::fakeResolve, false));
        assertNotNull(BrowserAPI.requestProblem("ws://public.example/", BrowserDestinationPolicyTest::fakeResolve, false));
        assertNotNull(BrowserAPI.requestProblem("http://10.0.0.1/", BrowserDestinationPolicyTest::fakeResolve, false));
        assertNull(BrowserAPI.requestProblem("http://10.0.0.1/", BrowserDestinationPolicyTest::fakeResolve, true));
    }

    // ==================== LEITURA DO HOST ====================

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "http://usuario:senha@host.example:8080/p | host.example",
            "http://[::1]:8080/ | [::1]",
            "http://a.example#@b.example/ | a.example",
            "http://a.example\\@b.example/ | a.example",
            "'  http://a.example/  ' | a.example",
            "http://%61.example/ | a.example",
            "http:///a.example/ | a.example",
            "https://A.Example?x=1 | A.Example"})
    @DisplayName("o host é extraído como o navegador extrai (padrão WHATWG)")
    void extractsTheHostLikeTheBrowser(String url, String host) {
        assertEquals(host, BrowserAPI.hostOf(url));
    }

    @Test
    @DisplayName("URL sem host devolve null")
    void missingHostIsNull() {
        assertNull(BrowserAPI.hostOf("http://"));
        assertNull(BrowserAPI.hostOf("http://usuario@/"));
    }

    @ParameterizedTest
    @CsvSource({
            "0177.0.0.1, 127.0.0.1",
            "0x7f.1, 127.0.0.1",
            "2130706433, 127.0.0.1",
            "127.1, 127.0.0.1",
            "1.2.3, 1.2.0.3",
            "1.2.3.4., 1.2.3.4",
            "0x, 0.0.0.0",
            "8.8.8.8, 8.8.8.8"})
    @DisplayName("IPv4 é lido como o Chromium lê: formas curtas, octal e hexadecimal")
    void readsIpv4LikeTheBrowser(String host, String expected) throws IOException {
        byte[] bytes = BrowserAPI.parseBrowserIpv4(host, host);
        assertNotNull(bytes);
        assertArrayEquals(InetAddress.getByName(expected).getAddress(), bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "foo.0x1g", "a1", "public.example"})
    @DisplayName("host que não termina em número é nome de domínio, não IPv4")
    void namesAreNotIpv4(String host) throws IOException {
        assertNull(BrowserAPI.parseBrowserIpv4(host, host));
    }

    // ==================== FAIXAS ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "0.1.2.3", "10.0.0.0", "127.0.0.1", "169.254.1.1", "172.16.0.0", "172.31.0.1", "192.168.255.255",
            "100.64.0.0", "100.127.1.1", "192.0.0.192", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fec0::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:10.0.0.1",
            "64:ff9b::7f00:1", "64:ff9b:1::1", "2002:c0a8:101::1", "2001:db8::1", "2001:0:4136:e378::1", "100::1"})
    @DisplayName("faixas que não são da internet pública são reconhecidas, inclusive IPv4 embutido em IPv6")
    void recognizesNonPublicRanges(String ip) throws UnknownHostException {
        assertTrue(BrowserAPI.isNonPublicAddress(InetAddress.getByName(ip)), ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8", "1.1.1.1", "93.184.216.34", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0",
            "192.169.0.1", "198.17.255.255", "198.20.0.0", "223.255.255.255",
            "2606:4700:4700::1111", "2002:808:808::1", "64:ff9b::808:808"})
    @DisplayName("endereços da internet pública não são confundidos com rede interna")
    void acceptsPublicRanges(String ip) throws UnknownHostException {
        assertFalse(BrowserAPI.isNonPublicAddress(InetAddress.getByName(ip)), ip);
    }

    @Test
    @DisplayName("IPv4 mapeado em IPv6 é julgado pelo IPv4, mesmo sem a conversão automática do Java")
    void judgesMappedIpv4InsideIpv6() throws UnknownHostException {
        byte[] mapped = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 10, 0, 0, 1};
        assertTrue(BrowserAPI.isNonPublicAddress(Inet6Address.getByAddress(null, mapped, -1)));
        byte[] mappedPublic = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 8, 8, 8, 8};
        assertFalse(BrowserAPI.isNonPublicAddress(Inet6Address.getByAddress(null, mappedPublic, -1)));
    }
}
