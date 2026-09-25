package br.com.angatusistemas.lib.browser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proxy de saída (SOCKS5) por onde passa toda conexão dos navegadores da BrowserAPI.
 *
 * <p>Só loopback: um servidor de eco local faz o papel do destino, e a regra de destino é
 * falsa. Nenhum navegador é aberto e nada sai da máquina — nomes como {@code fixado.test} nem
 * existem no DNS, e só conectam porque o proxy usa o endereço que a regra devolveu.</p>
 *
 * @author Angatu Sistemas
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class EgressProxyTest {

    private static final InetAddress LOOPBACK = loopback();

    private ServerSocket echo;
    private final AtomicInteger echoConnections = new AtomicInteger();

    @BeforeEach
    void startEchoServer() throws IOException {
        echo = new ServerSocket(0, 50, LOOPBACK);
        Thread acceptor = new Thread(() -> {
            while (!echo.isClosed()) {
                try {
                    Socket connection = echo.accept();
                    echoConnections.incrementAndGet();
                    Thread worker = new Thread(() -> echoBack(connection), "eco-teste");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }, "eco-teste-aceite");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopEchoServer() throws IOException {
        echo.close();
    }

    @Test
    @DisplayName("repassa os bytes até o destino permitido, conectando no endereço que a regra devolveu")
    void relaysToTheAddressReturnedByTheGuard() throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        BrowserAPI.DestinationGuard guard = host -> {
            asked.add(host);
            return new InetAddress[] {LOOPBACK};
        };
        try (BrowserAPI.EgressProxy proxy = new BrowserAPI.EgressProxy(guard);
                Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(LOOPBACK, proxy.port())))) {
            socket.setSoTimeout(5_000);
            socket.connect(InetSocketAddress.createUnresolved("fixado.test", echo.getLocalPort()), 5_000);
            byte[] message = "olá, destino".getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(message);
            byte[] answer = new byte[message.length];
            new DataInputStream(socket.getInputStream()).readFully(answer);
            assertArrayEquals(message, answer);
        }
        assertEquals(List.of("fixado.test"), asked);
    }

    @Test
    @DisplayName("recusa o destino bloqueado sem abrir conexão com ele")
    void refusesBlockedDestinationsWithoutConnecting() throws IOException {
        BrowserAPI.DestinationGuard guard = host -> {
            throw new IOException("Destino bloqueado por segurança: " + host);
        };
        try (BrowserAPI.EgressProxy proxy = new BrowserAPI.EgressProxy(guard);
                Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(LOOPBACK, proxy.port())))) {
            IOException error = assertThrows(IOException.class,
                    () -> socket.connect(InetSocketAddress.createUnresolved("interno.test", echo.getLocalPort()), 5_000));
            assertEquals("SOCKS: Connection not allowed by ruleset", error.getMessage());
        }
        assertEquals(0, echoConnections.get(), "o destino bloqueado não pode receber nem a conexão");
    }

    @Test
    @DisplayName("host que não resolve vira resposta de host inalcançável")
    void unresolvableHostIsReportedAsUnreachable() throws IOException {
        BrowserAPI.DestinationGuard guard = host -> {
            throw new UnknownHostException(host);
        };
        try (BrowserAPI.EgressProxy proxy = new BrowserAPI.EgressProxy(guard)) {
            assertEquals(4, rawConnectReply(proxy.port(), 3, "nada.test".getBytes(StandardCharsets.US_ASCII), echo.getLocalPort()));
        }
        assertEquals(0, echoConnections.get());
    }

    @Test
    @DisplayName("IPv4 e IPv6 literais também passam pela regra, que decide o destino")
    void literalAddressesAlsoGoThroughTheGuard() throws IOException {
        List<String> asked = new CopyOnWriteArrayList<>();
        BrowserAPI.DestinationGuard guard = host -> {
            asked.add(host);
            throw new IOException("bloqueado");
        };
        try (BrowserAPI.EgressProxy proxy = new BrowserAPI.EgressProxy(guard)) {
            assertEquals(2, rawConnectReply(proxy.port(), 1, new byte[] {127, 0, 0, 1}, echo.getLocalPort()));
            byte[] ipv6Loopback = new byte[16];
            ipv6Loopback[15] = 1;
            assertEquals(2, rawConnectReply(proxy.port(), 4, ipv6Loopback, echo.getLocalPort()));
        }
        assertEquals(List.of("127.0.0.1", "0:0:0:0:0:0:0:1"), asked);
        assertEquals(0, echoConnections.get());
    }

    @Test
    @DisplayName("só aceita o método sem autenticação e só o comando CONNECT")
    void acceptsOnlyNoAuthAndConnect() throws IOException {
        BrowserAPI.DestinationGuard guard = host -> new InetAddress[] {LOOPBACK};
        try (BrowserAPI.EgressProxy proxy = new BrowserAPI.EgressProxy(guard)) {
            try (Socket socket = new Socket(LOOPBACK, proxy.port())) {
                socket.setSoTimeout(5_000);
                socket.getOutputStream().write(new byte[] {5, 1, 2}); // só usuário e senha
                assertArrayEquals(new byte[] {5, (byte) 0xFF}, socket.getInputStream().readNBytes(2));
            }
            try (Socket socket = new Socket(LOOPBACK, proxy.port())) {
                socket.setSoTimeout(5_000);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();
                out.write(new byte[] {5, 1, 0});
                assertArrayEquals(new byte[] {5, 0}, in.readNBytes(2));
                int port = echo.getLocalPort();
                out.write(new byte[] {5, 2, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port}); // BIND
                assertEquals(7, in.readNBytes(10)[1]);
            }
        }
        assertEquals(0, echoConnections.get());
    }

    @Test
    @DisplayName("depois de fechado, o proxy não aceita mais conexões")
    void closedProxyAcceptsNothing() throws IOException {
        BrowserAPI.EgressProxy proxy = new BrowserAPI.EgressProxy(host -> new InetAddress[] {LOOPBACK});
        int port = proxy.port();
        proxy.close();
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(LOOPBACK, port)))) {
                socket.connect(InetSocketAddress.createUnresolved("fixado.test", echo.getLocalPort()), 2_000);
            }
        });
        assertEquals(0, echoConnections.get());
    }

    /** Faz o pedido CONNECT à mão e devolve o código de resposta do proxy. */
    private static int rawConnectReply(int proxyPort, int addressType, byte[] address, int port) throws IOException {
        try (Socket socket = new Socket(LOOPBACK, proxyPort)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(new byte[] {5, 1, 0});
            assertArrayEquals(new byte[] {5, 0}, in.readNBytes(2));
            out.write(new byte[] {5, 1, 0, (byte) addressType});
            if (addressType == 3) out.write(address.length);
            out.write(address);
            out.write(new byte[] {(byte) (port >> 8), (byte) port});
            byte[] reply = in.readNBytes(10);
            assertEquals(5, reply[0]);
            return reply[1];
        }
    }

    private static void echoBack(Socket connection) {
        try (connection) {
            InputStream in = connection.getInputStream();
            OutputStream out = connection.getOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            // o teste já terminou
        }
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
