package br.com.angatusistemas.lib.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code imageUrlToBase64}: a URL costuma vir de quem usa o sistema, então só {@code http}/
 * {@code https} para destino público passa — loopback, rede interna, link-local (metadado de nuvem)
 * e curinga são recusados antes de qualquer conexão.
 *
 * <p>Sem rede: todos os destinos são IP literal ou {@code localhost}, resolvidos na própria máquina,
 * e a recusa acontece antes de abrir a conexão.</p>
 *
 * @author Angatu Sistemas
 */
class ImageUrlSafetyTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "file:///etc/passwd",
            "file:///C:/Windows/win.ini",
            "ftp://exemplo.com/foto.png",
            "jar:file:/app.jar!/segredo.png",
            "data:image/png;base64,AAAA",
            "gopher://127.0.0.1:6379/_INFO",
            "http:///sem-host.png",
            "isto não é uma url"})
    @DisplayName("esquema diferente de http/https, ou URL sem host, é recusado")
    void onlyHttpUrlsAreAccepted(String url) {
        assertThrows(IOException.class, () -> ImageAPI.imageUrlToBase64(url));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "http://127.0.0.1/foto.png",
            "http://127.1.2.3:8080/foto.png",
            "http://localhost/foto.png",
            "https://LOCALHOST:8443/foto.png",
            "http://[::1]/foto.png",
            "http://0.0.0.0/foto.png",
            "http://[::]/foto.png",
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "http://10.0.0.5/foto.png",
            "http://172.16.10.1/foto.png",
            "http://192.168.0.1/foto.png",
            "http://100.64.0.1/foto.png",
            "http://[fd00:ec2::254]/latest/meta-data/",
            "http://[fe80::1]/foto.png",
            "http://[::ffff:127.0.0.1]/foto.png",
            "http://[::ffff:10.0.0.1]/foto.png",
            "http://[64:ff9b::a00:1]/foto.png",
            "http://[2002:a00:1::1]/foto.png",
            "http://224.0.0.1/foto.png",
            "http://255.255.255.255/foto.png"})
    @DisplayName("destino de rede interna, local, reservada ou curinga é recusado antes da conexão")
    void internalDestinationsAreRejected(String url) {
        IOException rejected = assertThrows(IOException.class, () -> ImageAPI.imageUrlToBase64(url));
        assertTrue(rejected.getMessage().startsWith("Endereço não permitido"), rejected.getMessage());
    }

    @ParameterizedTest(name = "{0} → proibido: {1}")
    @CsvSource({
            "8.8.8.8, false",
            "1.1.1.1, false",
            "200.160.2.3, false",
            "2001:4860:4860::8888, false",
            "2804:14c::1, false",
            "127.0.0.1, true",
            "10.1.2.3, true",
            "172.31.255.255, true",
            "172.32.0.1, false",
            "192.168.10.10, true",
            "169.254.1.1, true",
            "100.64.0.1, true",
            "100.128.0.1, false",
            "0.1.2.3, true",
            "198.18.0.1, true",
            "240.0.0.1, true",
            "::1, true",
            "fc00::1, true",
            "fe80::1, true",
            "::ffff:192.168.1.1, true",
            "64:ff9b::808:808, false",
            "2002:808:808::1, false",
            "2001:db8::1, true"})
    @DisplayName("classificação de endereços: público passa, interno e reservado não")
    void addressClassification(String address, boolean forbidden) throws Exception {
        assertEquals(forbidden, ImageAPI.isForbiddenAddress(InetAddress.getByName(address)));
    }

    @Test
    @DisplayName("URL nula ou vazia é erro de programação")
    void nullOrBlankUrlIsAnArgumentError() {
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.imageUrlToBase64(null));
        assertThrows(IllegalArgumentException.class, () -> ImageAPI.imageUrlToBase64("  "));
    }
}
