package br.com.angatusistemas.lib.javalin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.com.angatusistemas.lib.javalin.classes.SlidingWindowCounter;

/**
 * As peças do rate limit que não dependem do servidor: janela deslizante, forma canônica do
 * caminho e o sujeito do limite (IPv4 inteiro, IPv6 por /64).
 *
 * @author Angatu Sistemas
 */
class RateLimitPrimitivesTest {

    @Test
    @DisplayName("janela de 1 segundo cobre só o segundo atual")
    void oneSecondWindowCoversOnlyTheCurrentSecond() {
        SlidingWindowCounter counter = new SlidingWindowCounter(1);
        assertTrue(counter.checkAndIncrement(1, 100));
        assertFalse(counter.checkAndIncrement(1, 100), "segunda no mesmo segundo passa do limite");
        assertTrue(counter.checkAndIncrement(1, 101), "o segundo seguinte é outra janela");
    }

    @Test
    @DisplayName("janela de 60 segundos cobre exatamente 60 segundos")
    void sixtySecondWindowCoversExactlySixtySeconds() {
        SlidingWindowCounter counter = new SlidingWindowCounter(60);
        assertTrue(counter.checkAndIncrement(1, 1_000));
        assertFalse(counter.checkAndIncrement(1, 1_059));
        assertTrue(counter.checkAndIncrement(1, 1_060));
    }

    @Test
    @DisplayName("caminho canônico: barras repetidas viram uma e a final sai")
    void canonicalPathCollapsesSlashes() {
        assertEquals("/api/login", JavalinAPI.canonicalPath("//api//login/"));
        assertEquals("/api/login", JavalinAPI.canonicalPath("/api/login"));
        assertEquals("/", JavalinAPI.canonicalPath("/"));
        assertEquals("/", JavalinAPI.canonicalPath("//"));
        assertEquals("/", JavalinAPI.canonicalPath(""));
        assertEquals("/x", JavalinAPI.canonicalPath("x"));
    }

    @Test
    @DisplayName("sujeito do limite: IPv4 inteiro, IPv6 pelo /64, IPv4 embutido volta a IPv4")
    void rateLimitSubjectGroupsIpv6BySlash64() {
        assertEquals("203.0.113.9", JavalinAPI.rateLimitSubject("203.0.113.9"));
        assertEquals("2001:db8:1:2::/64", JavalinAPI.rateLimitSubject("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
        assertEquals("2001:db8:1:2::/64", JavalinAPI.rateLimitSubject("2001:db8:1:2::1"));
        assertEquals("1.2.3.4", JavalinAPI.rateLimitSubject("::ffff:1.2.3.4"));
    }

    @Test
    @DisplayName("rede do IPv6: prefixo /48; IPv4, inclusive embutido, fica de fora")
    void ipv6NetworkIsTheSlash48() {
        assertEquals("2001:db8:9::/48", JavalinAPI.ipv6Network("2001:db8:9:1::1"));
        assertEquals("2001:db8:9::/48", JavalinAPI.ipv6Network("2001:db8:9:ffff:1:2:3:4"));
        assertEquals(null, JavalinAPI.ipv6Network("203.0.113.9"));
        assertEquals(null, JavalinAPI.ipv6Network("::ffff:1.2.3.4"));
    }

    @Test
    @DisplayName("endereço do socket sai literal: sem colchetes e sem zona")
    void socketAddressIsALiteral() {
        assertEquals("0:0:0:0:0:0:0:1", IP.socketAddress("[0:0:0:0:0:0:0:1]"));
        assertEquals("fe80::1", IP.socketAddress("fe80::1%eth0"));
        assertEquals("fe80::1", IP.socketAddress("[fe80::1%eth0]"));
        assertEquals("203.0.113.5", IP.socketAddress("203.0.113.5"));
    }

    @Test
    @DisplayName("rede privada, loopback, link-local e CGNAT não são tratados como uma pessoa")
    void privateAndLocalAddressesAreRecognized() {
        for (String ip : new String[] {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1",
                "169.254.1.1", "100.64.0.1", "::1", "0:0:0:0:0:0:0:1", "fd00::1", "fe80::1", "::ffff:10.0.0.1"}) {
            assertTrue(IP.isPrivateOrLocal(ip), ip);
        }
        for (String ip : new String[] {"8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:db8::1", "203.0.113.9"}) {
            assertFalse(IP.isPrivateOrLocal(ip), ip);
        }
    }
}
