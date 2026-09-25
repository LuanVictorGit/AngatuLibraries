package br.com.angatusistemas.lib.discord;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javax.imageio.ImageIO;

import org.jetbrains.annotations.NotNull;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.env.Env;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

/**
 * Classe utilitária para integração com Discord utilizando JDA (Java Discord API).
 *
 * <p><strong>Propósito:</strong> abstrair o JDA em chamadas simples: envio de
 * mensagens (texto, imagens, arquivos), botões interativos com callbacks e
 * gerenciamento do bot.</p>
 *
 * <p><strong>Quando usar:</strong> em aplicações que precisam de um bot do
 * Discord (notificações, comandos com botões, relatórios).</p>
 *
 * <p><strong>Quando NÃO usar:</strong> para bots com comandos slash complexos,
 * moderação ou guilds — use o JDA diretamente (acessível via
 * {@link #getJDA()}); sem o token configurado ({@code DISCORD_BOT_TOKEN} no
 * {@code .env}) o {@link #setup()} falha com mensagem clara.</p>
 *
 * <p><strong>Configuração necessária no arquivo .env:</strong></p>
 * <pre>
 * DISCORD_BOT_TOKEN=seu_token_aqui
 * </pre>
 *
 * <p><strong>Integração:</strong> usa {@link Env} para o token e
 * {@link Console} para logs; as ações de botão são registradas via
 * {@link #onButtonClick} e executadas em listener interno.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>{@code Bot.setup()} (token do .env) ou {@code Bot.setup(token)};</li>
 *   <li>{@code Bot.sendMessage(canalId, texto)} — os envios bloqueiam até a
 *       resposta do Discord, com prazo: no máximo 15 s para texto e botões e
 *       60 s para imagens e arquivos;</li>
 *   <li>Botões: {@code sendMessageWithButton} + {@code onButtonClick};</li>
 *   <li>Ao encerrar a aplicação (ou para trocar o token), {@link #shutdown()}.</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * Bot.setup();
 * Bot.sendMessage("123456789012345678", "Olá mundo!");
 * Bot.sendMessageWithButton("123456789012345678", "Confirma?", "btn_ok", "Sim");
 * Bot.onButtonClick("btn_ok", event -&gt; event.reply("Confirmado!").setEphemeral(true).queue());
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> os métodos de envio são bloqueantes —
 * chame-os fora da thread de UI; registre os botões antes de enviar a mensagem;
 * trate {@code null} no retorno como falha (canal inexistente, prazo esgotado ou erro).</p>
 *
 * <p><strong>Limitações:</strong> requer {@code net.dv8tion:JDA:6.4.1} — a
 * classe é detectável (linkável) sem ela: {@link #isInitialized()} responde
 * {@code false}, {@link #removeButtonAction} e {@link #shutdown()} não fazem nada, e
 * os demais métodos exibem as instruções de instalação e lançam
 * {@link br.com.angatusistemas.lib.dependencies.MissingDependencyException};
 * {@code setup()} espera no máximo 30 s pela confirmação do Discord (além do login
 * inicial, limitado pelos timeouts HTTP do JDA) e, se ela não vier, desliga a sessão
 * e devolve {@code false}; o bot não liga o intent privilegiado {@code MESSAGE_CONTENT}
 * (use {@link #setup(String, boolean)} se ler conteúdo de mensagens pelo JDA);
 * máximos de 5 botões por {@code ActionRow}.</p>
 *
 * <p><strong>Extensões futuras:</strong> variantes assíncronas
 * ({@code CompletableFuture<Message>}) e suporte a comandos slash são
 * evoluções naturais sem quebrar a API.</p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://github.com/discord-jda/JDA">JDA on GitHub</a>
 * @see Env
 */
public final class Bot {

    /** Classe do JDA usada para detectar a dependência. */
    private static final String JDA_CLASS = "net.dv8tion.jda.api.JDA";
    /** Coordenadas Maven da dependência JDA. */
    private static final String JDA_COORDINATES = "net.dv8tion:JDA:6.4.1";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String DISCORD_FEATURE = "Discord Bot (JDA)";

    /** Prazo para o Discord confirmar a conexão em {@link #setup()}. */
    static final Duration READY_TIMEOUT = Duration.ofSeconds(30);
    /**
     * Prazo para o JDA <em>começar</em> um envio. Passado dele, o JDA descarta o pedido que ainda
     * esperava na fila (limite de taxa, reconexão) em vez de postá-lo tarde demais.
     */
    static final Duration SEND_START_TIMEOUT = Duration.ofSeconds(10);
    /** Espera máxima de quem chama um envio de texto ou botões. */
    static final Duration TEXT_SEND_WAIT = Duration.ofSeconds(15);
    /** Espera máxima de quem chama um envio com arquivo (o upload em si leva tempo). */
    static final Duration UPLOAD_SEND_WAIT = Duration.ofSeconds(60);
    /** Espera por um desligamento limpo em {@link #shutdown()} antes de forçar. */
    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);
    /**
     * Quanto esperar antes de responder a um botão sem ação registrada. O Discord dá 3 s para a
     * primeira resposta; a espera deixa um listener próprio do consumidor (via {@link #getJDA()})
     * responder primeiro.
     */
    static final Duration UNKNOWN_BUTTON_GRACE = Duration.ofMillis(1500);

    /** Tamanho máximo de imagem baixada por URL: 10 MiB, o limite de upload de um bot sem impulsos. */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    /** Prazo para conectar ao servidor da imagem. */
    static final Duration IMAGE_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Prazo total do download da imagem (todos os redirecionamentos somados). */
    static final Duration IMAGE_TOTAL_TIMEOUT = Duration.ofSeconds(20);
    /** Redirecionamentos seguidos, cada um revalidado. */
    static final int MAX_IMAGE_REDIRECTS = 3;
    /** Nome de arquivo usado quando a URL não traz um. */
    static final String DEFAULT_IMAGE_NAME = "imagem";

    private Bot() {
        throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
    }

    // ==================== INICIALIZAÇÃO ====================

    /**
     * Inicializa o bot Discord usando o token do arquivo .env
     * (chave {@code DISCORD_BOT_TOKEN}).
     *
     * <p><strong>Pré-condições:</strong> token configurado no {@code .env} e
     * dependência JDA no classpath.</p>
     *
     * <p><strong>Pós-condições:</strong> bot conectado ao gateway; ações de
     * botão passam a ser processadas.</p>
     *
     * @return {@code true} se inicializado com sucesso, {@code false} caso contrário
     */
    public static boolean setup() {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        String token = Env.get().get("DISCORD_BOT_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            Console.error("Token do Discord não configurado. Adicione DISCORD_BOT_TOKEN no .env");
            return false;
        }
        return setup(token);
    }

    /**
     * Inicializa o bot Discord com um token fornecido explicitamente.
     *
     * <p>Espera no máximo 30 s pela confirmação do Discord. Se ela não vier (Discord fora do ar,
     * token recusado, conexão encerrada), a sessão aberta é desligada — nada fica conectando em
     * segundo plano — e o método devolve {@code false}; pode ser chamado de novo depois. Se o bot já
     * estiver conectado, não abre uma segunda sessão (isso faria cada clique de botão rodar duas
     * vezes). Uma sessão que o Discord encerrou de vez (token redefinido, por exemplo) é descartada
     * e uma nova é aberta.</p>
     *
     * @param token Token do bot Discord
     * @return {@code true} se o bot está conectado ao final da chamada
     */
    public static boolean setup(String token) {
        return setup(token, false);
    }

    /**
     * Inicializa o bot Discord escolhendo se o intent privilegiado {@code MESSAGE_CONTENT} fica
     * ligado.
     *
     * <p>Esta classe não lê conteúdo de mensagens (ela envia mensagens e trata botões), então
     * {@link #setup(String)} não liga o intent. Ligue-o só se você lê o conteúdo de mensagens
     * recebidas pelo JDA ({@link #getJDA()}) — e ative antes "Message Content Intent" no portal de
     * desenvolvedores do Discord: com a opção desligada lá, o Discord recusa a conexão (código
     * 4014).</p>
     *
     * @param token                Token do bot Discord
     * @param messageContentIntent {@code true} para ligar o intent {@code MESSAGE_CONTENT}
     * @return {@code true} se o bot está conectado ao final da chamada
     */
    public static boolean setup(String token, boolean messageContentIntent) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.setup(token, messageContentIntent);
    }

    /**
     * Desliga o bot e libera a sessão com o Discord.
     *
     * <p>Espera até 10 s pelos envios em andamento e então força o encerramento. Depois disso
     * {@link #isInitialized()} responde {@code false} e {@link #setup()} pode abrir uma sessão nova
     * (com outro token, por exemplo). As ações de botão registradas são mantidas. Sem o JDA no
     * classpath, não faz nada.</p>
     */
    public static void shutdown() {
        if (!Dependencies.isPresent(JDA_CLASS)) {
            return;
        }
        JdaSupport.shutdown();
    }

    /**
     * Retorna a instância JDA (para uso avançado).
     *
     * @return Instância JDA conectada (ou reconectando), ou {@code null} se o bot não foi
     *         inicializado, foi desligado ou teve a sessão encerrada pelo Discord
     */
    public static JDA getJDA() {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.liveJda();
    }

    /**
     * Verifica se o bot está inicializado.
     *
     * <p>Sem o JDA no classpath, responde {@code false} (não lança exceção).</p>
     *
     * @return {@code true} se há uma sessão ativa (conectada ou reconectando)
     */
    public static boolean isInitialized() {
        return Dependencies.isPresent(JDA_CLASS) && JdaSupport.liveJda() != null;
    }

    // ==================== ENVIO DE MENSAGENS ====================

    /**
     * Envia uma mensagem de texto simples para um canal (bloqueante, no máximo 15 s).
     *
     * @param channelId ID do canal
     * @param message   Conteúdo da mensagem
     * @return A mensagem enviada ou {@code null} em caso de erro ou prazo esgotado
     */
    public static Message sendMessage(String channelId, String message) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendMessage(channelId, message);
    }

    /**
     * Envia uma mensagem com um botão (bloqueante, no máximo 15 s).
     *
     * <p>Use {@link #onButtonClick(String, Consumer)} para registrar a ação do botão.</p>
     *
     * @param channelId   ID do canal
     * @param message     Texto da mensagem
     * @param buttonId    ID único do botão (para callback via onButtonClick)
     * @param buttonLabel Texto exibido no botão
     * @return A mensagem enviada ou {@code null} em caso de erro ou prazo esgotado
     */
    public static Message sendMessageWithButton(String channelId, String message, String buttonId, String buttonLabel) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendMessageWithButton(channelId, message, buttonId, buttonLabel);
    }

    /**
     * Envia uma mensagem com múltiplos botões (máximo 5 por ActionRow; bloqueante, no máximo 15 s).
     *
     * @param channelId ID do canal
     * @param message   Texto da mensagem
     * @param buttons   Mapa de ID → rótulo do botão
     * @return A mensagem enviada ou {@code null} em caso de erro ou prazo esgotado
     */
    public static Message sendMessageWithButtons(String channelId, String message, Map<String, String> buttons) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendMessageWithButtons(channelId, message, buttons);
    }

    // ==================== ENVIO DE IMAGENS ====================

    /**
     * Envia uma imagem a partir de uma URL (bloqueante).
     *
     * <p>A imagem é baixada pela aplicação e enviada ao Discord como anexo — por isso a URL passa
     * por uma checagem antes de qualquer conexão: só {@code http}/{@code https}, sem usuário e
     * senha na URL, e o host precisa resolver <em>apenas</em> para endereços públicos (loopback,
     * rede privada, link-local — incluindo o serviço de metadados da nuvem —, endereço curinga,
     * multicast e faixas reservadas são recusados). Sem isso, uma URL como
     * {@code file:///app/.env} ou {@code http://169.254.169.254/...} vinda de um usuário
     * publicaria no Discord um arquivo ou serviço interno do servidor. Redirecionamentos são
     * seguidos manualmente (até 3), cada destino checado de novo; a conexão é direta (sem proxy),
     * para que a checagem valha para o endereço de fato conectado.</p>
     *
     * <p>Limites: 5 s para conectar, 20 s para o download inteiro e 10 MiB de tamanho. O nome do
     * anexo vem do caminho da URL, sem query string nem fragmento (que costumam carregar tokens de
     * acesso).</p>
     *
     * @param channelId ID do canal
     * @param imageUrl  URL da imagem ({@code http} ou {@code https}, host público)
     * @param caption   Legenda (pode ser {@code null})
     * @return A mensagem enviada ou {@code null} se a URL for recusada, o download falhar ou o
     *         envio der erro
     */
    public static Message sendImageFromUrl(String channelId, String imageUrl, String caption) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendImageFromUrl(channelId, imageUrl, caption);
    }

    /**
     * Envia uma imagem a partir de uma string Base64 (bloqueante, no máximo 60 s).
     *
     * <p>Aceita formatos como {@code "data:image/png;base64,..."} ou apenas a
     * parte Base64.</p>
     *
     * @param channelId ID do canal
     * @param base64    String Base64 da imagem
     * @param caption   Legenda (opcional)
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendImageFromBase64(String channelId, String base64, String caption) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendImageFromBase64(channelId, base64, caption);
    }

    /**
     * Envia uma imagem a partir de um arquivo local (bloqueante, no máximo 60 s).
     *
     * @param channelId ID do canal
     * @param filePath  Caminho do arquivo
     * @param caption   Legenda (opcional)
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendImageFromFile(String channelId, String filePath, String caption) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendImageFromFile(channelId, filePath, caption);
    }

    /**
     * Envia uma imagem a partir de um BufferedImage (bloqueante, no máximo 60 s).
     *
     * @param channelId ID do canal
     * @param image     Imagem a ser enviada
     * @param format    Formato da imagem (ex: "png", "jpg")
     * @param caption   Legenda (opcional)
     * @return A mensagem enviada ou {@code null} em caso de erro (inclusive formato sem suporte no
     *         ImageIO)
     */
    public static Message sendBufferedImage(String channelId, BufferedImage image, String format, String caption) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.sendBufferedImage(channelId, image, format, caption);
    }

    // ==================== BOTÕES E CALLBACKS ====================

    /**
     * Registra uma ação para ser executada quando um botão com o ID especificado
     * for clicado.
     *
     * <p><strong>Pré-condições:</strong> bot inicializado (a ação só é invocada
     * com o bot conectado).</p>
     *
     * <p>Cliques em botões sem ação registrada (por exemplo, de mensagens enviadas antes de um
     * reinício) recebem uma resposta efêmera "Este botão não está mais disponível.", a menos que
     * outro listener responda antes — sem resposta nenhuma, o Discord mostra "Esta interação
     * falhou" para quem clicou.</p>
     *
     * @param buttonId ID do botão (deve ser único)
     * @param action   Ação a ser executada (recebe o evento)
     * @throws IllegalArgumentException se o ID ou a ação forem nulos
     */
    public static void onButtonClick(String buttonId, Consumer<ButtonInteractionEvent> action) {
        Dependencies.require(JDA_CLASS, JDA_COORDINATES, DISCORD_FEATURE);
        if (buttonId == null || action == null) {
            throw new IllegalArgumentException("O ID do botão e a ação não podem ser nulos.");
        }
        JdaSupport.buttonActions.put(buttonId, action);
        Console.debug("Ação registrada para botão: %s", buttonId);
    }

    /**
     * Remove a ação associada a um botão.
     *
     * <p>Sem o JDA no classpath não há ação registrada, e o método não faz nada.</p>
     *
     * @param buttonId ID do botão
     */
    public static void removeButtonAction(String buttonId) {
        if (buttonId == null || !Dependencies.isPresent(JDA_CLASS)) {
            return;
        }
        JdaSupport.buttonActions.remove(buttonId);
        Console.debug("Ação removida para botão: %s", buttonId);
    }

    // ==================== CHECAGEM DE URL DE IMAGEM (SÓ JDK) ====================

    /** Resolução de nome para endereços; separada para os testes não dependerem de DNS. */
    @FunctionalInterface
    interface HostResolver {
        /**
         * Resolve o host para todos os seus endereços.
         *
         * @param host Nome ou literal IP (IPv6 pode vir entre colchetes)
         * @return Endereços do host
         * @throws UnknownHostException se o nome não resolver
         */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Resolvedor do sistema (usa o cache de DNS da JVM). */
    static final HostResolver SYSTEM_RESOLVER = InetAddress::getAllByName;

    /**
     * Confere uma URL de imagem antes de qualquer conexão.
     *
     * <p>Exige {@code http}/{@code https}, host presente, nenhuma credencial embutida e que
     * <strong>todos</strong> os endereços do host sejam públicos — basta um privado para recusar,
     * porque não se sabe em qual deles o cliente HTTP vai conectar.</p>
     *
     * <p>Limite conhecido: o cliente HTTP do JDK resolve o nome de novo ao conectar. Na prática ele
     * encontra a resposta no cache de DNS da JVM (30 s por padrão), a mesma conferida aqui poucos
     * milissegundos antes; mas com {@code networkaddress.cache.ttl=0}, um DNS que muda de resposta
     * entre as duas consultas ("DNS rebinding") passaria. Em HTTPS o certificado barra esse caso.</p>
     *
     * @param imageUrl URL recebida
     * @param resolver Resolução de nomes
     * @return A URI conferida
     * @throws IllegalArgumentException se a URL for inválida ou apontar para endereço não público
     * @throws UnknownHostException     se o host não resolver
     */
    static URI checkImageUrl(String imageUrl, HostResolver resolver) throws UnknownHostException {
        return checkImageUrl(imageUrl, resolver, Bot::isPublicAddress);
    }

    /**
     * {@link #checkImageUrl(String, HostResolver)} com a regra de endereço explícita — só os testes
     * usam outra regra que não {@link #isPublicAddress}, para baixar de um servidor local.
     */
    static URI checkImageUrl(String imageUrl, HostResolver resolver, Predicate<InetAddress> allowedAddress)
            throws UnknownHostException {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new IllegalArgumentException("URL de imagem vazia.");
        }
        URI uri;
        try {
            uri = new URI(imageUrl.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL de imagem inválida.", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Só são aceitas URLs de imagem http ou https.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URL de imagem com usuário e senha não é aceita.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URL de imagem sem host válido.");
        }
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (!allowedAddress.test(address)) {
                throw new IllegalArgumentException("O host da imagem aponta para um endereço que não é público ("
                        + address.getHostAddress() + ") e foi recusado.");
            }
        }
        return uri;
    }

    /**
     * Diz se o endereço é unicast público — o único tipo que um download disparado por URL externa
     * pode alcançar.
     *
     * <p>Recusa: curinga ({@code 0.0.0.0}, {@code ::}), loopback, link-local (inclui
     * {@code 169.254.169.254}, metadados da nuvem), privado ({@code 10/8}, {@code 172.16/12},
     * {@code 192.168/16}), CGNAT ({@code 100.64/10}), multicast, faixas reservadas, de documentação
     * e de testes, IPv6 local único ({@code fc00::/7}) e IPv4 embutido em IPv6 (mapeado,
     * compatível, NAT64 e 6to4) quando o IPv4 embutido não é público.</p>
     */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicIpv4(bytes, 0);
        }
        if (!(address instanceof Inet6Address) || bytes.length != 16) {
            return false;
        }
        int first = bytes[0] & 0xFF;
        if ((first & 0xFE) == 0xFC) {
            return false; // fc00::/7 — endereço local único (a "rede privada" do IPv6)
        }
        if (first == 0x20 && (bytes[1] & 0xFF) == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8) {
            return false; // 2001:db8::/32 — documentação
        }
        if (first == 0x20 && (bytes[1] & 0xFF) == 0x02) {
            return isPublicIpv4(bytes, 2); // 2002::/16 — 6to4, IPv4 nos bytes 2 a 5
        }
        if (isZero(bytes, 0, 10) && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
            return isPublicIpv4(bytes, 12); // ::ffff:a.b.c.d — IPv4 mapeado
        }
        if (isZero(bytes, 0, 12)) {
            return isPublicIpv4(bytes, 12); // ::a.b.c.d — IPv4 compatível (obsoleto)
        }
        if (first == 0x00 && (bytes[1] & 0xFF) == 0x64 && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B
                && isZero(bytes, 4, 12)) {
            return isPublicIpv4(bytes, 12); // 64:ff9b::/96 — NAT64
        }
        return true;
    }

    /** IPv4 público a partir de 4 bytes começando em {@code offset}. */
    private static boolean isPublicIpv4(byte[] bytes, int offset) {
        int a = bytes[offset] & 0xFF;
        int b = bytes[offset + 1] & 0xFF;
        int c = bytes[offset + 2] & 0xFF;
        if (a == 0 || a == 10 || a == 127) {
            return false; // "esta rede", privado, loopback
        }
        if (a == 100 && b >= 64 && b <= 127) {
            return false; // 100.64.0.0/10 — CGNAT
        }
        if (a == 169 && b == 254) {
            return false; // link-local (metadados da nuvem)
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false; // privado
        }
        if (a == 192 && b == 168) {
            return false; // privado
        }
        if (a == 192 && b == 0 && (c == 0 || c == 2)) {
            return false; // 192.0.0.0/24 (IETF) e 192.0.2.0/24 (documentação)
        }
        if (a == 192 && b == 88 && c == 99) {
            return false; // 192.88.99.0/24 — relay 6to4 (obsoleto)
        }
        if (a == 198 && (b == 18 || b == 19)) {
            return false; // 198.18.0.0/15 — testes de desempenho
        }
        if ((a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)) {
            return false; // documentação
        }
        return a < 224; // 224/4 multicast, 240/4 reservado e 255.255.255.255
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Nome do anexo a partir da URL: último segmento do caminho — {@link URI#getPath()} já vem sem
     * query string e fragmento, onde costumam estar tokens de URL assinada —, só com letras,
     * dígitos, ponto, hífen e sublinhado, e com extensão inferida do {@code Content-Type} quando
     * falta.
     */
    static String imageFileName(URI uri, String contentType) {
        String path = uri == null ? null : uri.getPath();
        String last = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        String clean = last.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[._]+", "");
        if (clean.length() > 100) {
            clean = clean.substring(clean.length() - 100); // mantém o fim, onde está a extensão
        }
        if (clean.isEmpty()) {
            clean = DEFAULT_IMAGE_NAME;
        }
        if (clean.lastIndexOf('.') <= 0) {
            clean = clean + "." + extensionFor(contentType);
        }
        return clean;
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return "png";
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/jpeg") || type.startsWith("image/jpg")) {
            return "jpg";
        }
        if (type.startsWith("image/gif")) {
            return "gif";
        }
        if (type.startsWith("image/webp")) {
            return "webp";
        }
        return "png";
    }

    /** Host de uma URL para log — nunca a URL inteira, que pode trazer token na query string. */
    static String hostForLog(String url) {
        try {
            String host = url == null ? null : new URI(url.strip()).getHost();
            return host != null ? host : "(URL inválida)";
        } catch (URISyntaxException e) {
            return "(URL inválida)";
        }
    }

    /** Imagem baixada: bytes e nome de arquivo. */
    record DownloadedImage(byte[] bytes, String fileName) {
    }

    /**
     * Download de imagem por URL, só com o JDK (nenhum tipo do JDA aqui).
     *
     * <p>Classe à parte para o {@link HttpClient} — que abre uma thread de seletor ao ser criado —
     * só existir quando alguém de fato baixar uma imagem, e não no carregamento de {@link Bot}.</p>
     */
    static final class ImageDownloads {

        private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

        /**
         * Sem redirecionamento automático (cada destino é conferido aqui) e sem proxy: com proxy,
         * quem resolve e conecta é o proxy, e a checagem de endereço deixaria de valer. Executor
         * limitado a 2 threads daemon com nome, que morrem quando ociosas.
         */
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(IMAGE_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .executor(boundedExecutor())
                .build();

        private ImageDownloads() {
        }

        private static ThreadPoolExecutor boundedExecutor() {
            ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "Angatu-Bot-Download-" + THREAD_COUNTER.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    });
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }

        /**
         * Baixa a imagem, conferindo a URL inicial e cada redirecionamento.
         *
         * @throws IllegalArgumentException se alguma URL for recusada
         * @throws IOException              se o download falhar, passar do tamanho ou do prazo
         * @throws InterruptedException     se a thread for interrompida
         */
        static DownloadedImage download(String imageUrl) throws IOException, InterruptedException {
            return download(imageUrl, SYSTEM_RESOLVER, Bot::isPublicAddress, IMAGE_TOTAL_TIMEOUT);
        }

        /**
         * {@link #download(String)} com resolução, regra de endereço e prazo explícitos — outra regra
         * e outro prazo só nos testes, contra um servidor local.
         */
        static DownloadedImage download(String imageUrl, HostResolver resolver, Predicate<InetAddress> allowedAddress,
                Duration totalTimeout) throws IOException, InterruptedException {
            URI original = checkImageUrl(imageUrl, resolver, allowedAddress);
            URI current = original;
            long deadline = System.nanoTime() + totalTimeout.toNanos();
            for (int redirects = 0;; redirects++) {
                HttpResponse<byte[]> response = fetch(current, deadline, totalTimeout);
                int status = response.statusCode();
                if (isRedirect(status)) {
                    if (redirects >= MAX_IMAGE_REDIRECTS) {
                        throw new IOException("Redirecionamentos demais ao baixar a imagem (mais de "
                                + MAX_IMAGE_REDIRECTS + ").");
                    }
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("Redirecionamento sem cabeçalho Location."));
                    URI next;
                    try {
                        next = current.resolve(location.strip());
                    } catch (IllegalArgumentException e) {
                        throw new IOException("Redirecionamento para uma URL inválida.", e);
                    }
                    current = checkImageUrl(next.toString(), resolver, allowedAddress);
                    continue;
                }
                if (status / 100 != 2) {
                    throw new IOException("O servidor da imagem respondeu HTTP " + status + ".");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                return new DownloadedImage(response.body(), imageFileName(original, contentType));
            }
        }

        private static HttpResponse<byte[]> fetch(URI uri, long deadlineNanos, Duration totalTimeout)
                throws IOException, InterruptedException {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("A imagem não terminou de chegar em " + totalTimeout.toSeconds() + " s.");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remainingNanos))
                    .header("Accept", "image/*").GET().build();
            CompletableFuture<HttpResponse<byte[]>> future = CLIENT.sendAsync(request, ImageDownloads::bodyFor);
            try {
                // O timeout do HttpRequest só vale até os cabeçalhos; este get limita também o corpo.
                return future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException("A imagem não terminou de chegar em " + totalTimeout.toSeconds() + " s.");
            } catch (InterruptedException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() instanceof CompletionException && e.getCause().getCause() != null
                        ? e.getCause().getCause() : e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Falha ao baixar a imagem.", cause);
            }
        }

        /** Corpo só para 2xx, com teto de tamanho; nos demais (redirecionamento, erro), nem lê. */
        private static HttpResponse.BodySubscriber<byte[]> bodyFor(HttpResponse.ResponseInfo info) {
            if (info.statusCode() / 100 != 2) {
                return new CappedBody(0, OptionalLong.empty(), true);
            }
            return new CappedBody(MAX_IMAGE_BYTES, info.headers().firstValueAsLong("Content-Length"), false);
        }

        private static boolean isRedirect(int status) {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }
    }

    /**
     * Corpo da resposta acumulado em memória até um teto. Passou do teto, a assinatura é cancelada
     * (a conexão fecha) e o download falha — em vez de ler, e só depois descartar, um arquivo de
     * tamanho arbitrário.
     */
    static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

        private final long limit;
        private final OptionalLong declaredLength;
        private final boolean discard;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        CappedBody(long limit, OptionalLong declaredLength, boolean discard) {
            this.limit = limit;
            this.declaredLength = declaredLength;
            this.discard = discard;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (discard) {
                subscription.cancel();
                result.complete(new byte[0]);
                return;
            }
            if (declaredLength.isPresent() && declaredLength.getAsLong() > limit) {
                subscription.cancel();
                result.completeExceptionally(tooLarge());
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int size = item.remaining();
                if (buffer.size() + (long) size > limit) {
                    subscription.cancel();
                    result.completeExceptionally(tooLarge());
                    return;
                }
                byte[] chunk = new byte[size];
                item.get(chunk);
                buffer.write(chunk, 0, size);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(buffer.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        private IOException tooLarge() {
            return new IOException("A imagem passa do limite de " + (limit / (1024 * 1024)) + " MiB.");
        }
    }

    // ==================== IMPLEMENTAÇÃO (JDA — LAZY) ====================

    /**
     * Implementação da integração com o JDA. Classe separada para manter as
     * referências ao JDA fora do bytecode da {@link Bot} — a classe pública
     * pode ser vinculada sem o JDA e o guard exibe a mensagem de instalação
     * antes de qualquer uso.
     */
    private static final class JdaSupport {

        /** Sessão atual; {@code volatile} porque é lida sem trava por qualquer thread que envia. */
        private static volatile JDA jda;
        /** Listener de botões da sessão atual (aposentado quando a sessão sai). */
        private static volatile ButtonListener activeListener;
        private static final Map<String, Consumer<ButtonInteractionEvent>> buttonActions = new ConcurrentHashMap<>();

        private JdaSupport() {
        }

        /** Sessão em uso, ou {@code null} se não há ou se o Discord já a encerrou. */
        static JDA liveJda() {
            JDA current = jda;
            return isAlive(current) ? current : null;
        }

        private static boolean isAlive(JDA candidate) {
            if (candidate == null) {
                return false;
            }
            JDA.Status status = candidate.getStatus();
            return status != JDA.Status.SHUTTING_DOWN && status != JDA.Status.SHUTDOWN
                    && status != JDA.Status.FAILED_TO_LOGIN;
        }

        /**
         * Abre a sessão e espera a confirmação com prazo.
         *
         * <h4>Por que tanto cuidado</h4>
         * <ul>
         *   <li>{@code awaitReady()} não tem prazo no JDA 6.4.1: com o Discord fora do ar, a
         *       aplicação ficava parada no {@code setup()} para sempre. A espera agora é de
         *       {@link #READY_TIMEOUT}.</li>
         *   <li>Uma sessão que falhou precisa ser desligada: senão ela segue reconectando em segundo
         *       plano, a nova tentativa abre outra, e cada clique de botão roda duas vezes.</li>
         *   <li>Uma sessão encerrada de vez pelo Discord (token redefinido) não pode travar o
         *       estado em "inicializado": ela é descartada e a próxima chamada conecta de novo.</li>
         * </ul>
         */
        static synchronized boolean setup(String token, boolean messageContentIntent) {
            if (isAlive(jda)) {
                Console.warn("O bot do Discord já está inicializado.");
                return true;
            }
            discardCurrent();
            if (token == null || token.isBlank()) {
                Console.error("Token do Discord vazio: o bot não foi inicializado.");
                return false;
            }

            ButtonListener listener = new ButtonListener();
            ReadyWatcher watcher = new ReadyWatcher();
            JDA created = null;
            try {
                // createDefault já inclui GUILDS e GUILD_MESSAGES. MESSAGE_CONTENT é privilegiado e
                // esta classe não lê conteúdo de mensagem: ligado sem a opção no portal do Discord,
                // a conexão é recusada com o código 4014.
                JDABuilder builder = JDABuilder.createDefault(token.strip()).addEventListeners(listener, watcher);
                if (messageContentIntent) {
                    builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
                }
                created = builder.build();

                boolean settled = watcher.await(READY_TIMEOUT);
                created.removeEventListener(watcher);
                if (!settled || created.getStatus() != JDA.Status.CONNECTED) {
                    JDA.Status status = created.getStatus();
                    listener.retire();
                    created.shutdownNow();
                    if (!settled) {
                        Console.error("O Discord não confirmou a conexão do bot em %d s (estado: %s). A sessão "
                                + "foi desligada; tente o setup() de novo mais tarde.",
                                Long.valueOf(READY_TIMEOUT.toSeconds()), status);
                    } else {
                        Console.error("O Discord encerrou a conexão do bot: %s", describe(watcher.closeCode()));
                    }
                    return false;
                }

                jda = created;
                activeListener = listener;
                Console.log("Bot do Discord inicializado como: %s", created.getSelfUser().getName());
                return true;
            } catch (InterruptedException e) {
                listener.retire();
                if (created != null) {
                    created.shutdownNow();
                }
                Thread.currentThread().interrupt();
                Console.error("Inicialização do bot do Discord interrompida.");
                return false;
            } catch (RuntimeException e) {
                // Token inválido cai aqui: o JDA lança no build() e já desliga a própria sessão.
                listener.retire();
                if (created != null) {
                    created.shutdownNow();
                }
                Console.error("Falha ao inicializar o bot do Discord", e);
                return false;
            }
        }

        private static String describe(CloseCode code) {
            if (code == null) {
                return "sem código de encerramento (confira o token e a conexão).";
            }
            if (code == CloseCode.DISALLOWED_INTENTS) {
                return "código 4014 — intent não autorizado. Ative \"Message Content Intent\" no portal do "
                        + "Discord ou use Bot.setup(token, false).";
            }
            if (code == CloseCode.AUTHENTICATION_FAILED) {
                return "código 4004 — token recusado. Gere um token novo no portal do Discord.";
            }
            return "código " + code.getCode() + " (" + code.getMeaning() + ").";
        }

        /** Desliga e esquece uma sessão já morta, para o setup poder abrir outra. */
        private static void discardCurrent() {
            JDA previous = jda;
            ButtonListener previousListener = activeListener;
            jda = null;
            activeListener = null;
            if (previousListener != null) {
                previousListener.retire();
            }
            if (previous != null) {
                previous.shutdownNow();
            }
        }

        static synchronized void shutdown() {
            JDA current = jda;
            ButtonListener listener = activeListener;
            jda = null;
            activeListener = null;
            if (listener != null) {
                listener.retire();
            }
            if (current == null) {
                return;
            }
            current.shutdown();
            try {
                if (!current.awaitShutdown(SHUTDOWN_WAIT)) {
                    current.shutdownNow();
                }
            } catch (InterruptedException e) {
                current.shutdownNow();
                Thread.currentThread().interrupt();
            }
            Console.log("Bot do Discord desligado.");
        }

        /**
         * Canal de texto pelo ID, com o motivo registrado no console quando não há.
         */
        private static TextChannel channel(String channelId) {
            JDA current = liveJda();
            if (current == null) {
                Console.warn("Bot do Discord não inicializado: chame Bot.setup() antes de enviar.");
                return null;
            }
            try {
                TextChannel channel = current.getTextChannelById(channelId);
                if (channel == null) {
                    Console.error("Canal do Discord não encontrado: %s", channelId);
                }
                return channel;
            } catch (RuntimeException e) {
                // getTextChannelById lança para ID fora do formato numérico do Discord.
                Console.error("ID de canal do Discord inválido: %s", channelId);
                return null;
            }
        }

        /**
         * Executa o envio e espera o resultado com prazo.
         *
         * <p>{@code .complete()} sem prazo prendia a thread de quem chamou enquanto o Discord não
         * respondesse — o timeout padrão do JDA é zero, "para sempre". Aqui o pedido tem prazo para
         * começar ({@link #SEND_START_TIMEOUT}, depois disso o JDA o descarta da fila) e quem chama
         * espera no máximo {@code wait}; passado isso, o pedido é cancelado e o método devolve
         * {@code null}.</p>
         */
        private static Message send(RestAction<Message> action, Duration wait, String what) {
            CompletableFuture<Message> future;
            try {
                future = action.timeout(SEND_START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).submit();
            } catch (RuntimeException e) {
                // Sessão desligada no meio do caminho: o JDA recusa novos pedidos.
                Console.error("Erro ao " + what, e);
                return null;
            }
            try {
                return future.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                Console.error("O Discord não respondeu em %d s ao %s; o envio foi cancelado.",
                        Long.valueOf(wait.toSeconds()), what);
                return null;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                Console.warn("Envio ao Discord interrompido (%s).", what);
                return null;
            } catch (ExecutionException e) {
                Console.error("Erro ao " + what, e.getCause());
                return null;
            } catch (CancellationException e) {
                Console.error("Envio ao Discord cancelado (%s).", what);
                return null;
            }
        }

        /** Mensagem com um anexo; fecha o anexo se algo falhar antes de o JDA assumi-lo. */
        private static Message sendFile(TextChannel channel, FileUpload upload, String caption, String what) {
            MessageCreateData data;
            try {
                MessageCreateBuilder builder = new MessageCreateBuilder();
                if (caption != null && !caption.isEmpty()) {
                    builder.setContent(caption);
                }
                builder.setFiles(upload);
                data = builder.build();
            } catch (RuntimeException e) {
                closeQuietly(upload);
                Console.error("Erro ao " + what, e);
                return null;
            }
            // A partir daqui o JDA fecha o anexo ao concluir, falhar ou cancelar o pedido.
            return send(channel.sendMessage(data), UPLOAD_SEND_WAIT, what);
        }

        private static void closeQuietly(FileUpload upload) {
            try {
                upload.close();
            } catch (IOException | RuntimeException ignored) {
                // Nada a fazer: o anexo não chegou a ser usado.
            }
        }

        static Message sendMessage(String channelId, String message) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            try {
                return send(channel.sendMessage(message), TEXT_SEND_WAIT, "enviar mensagem");
            } catch (RuntimeException e) {
                Console.error("Erro ao enviar mensagem", e);
                return null;
            }
        }

        static Message sendMessageWithButton(String channelId, String message, String buttonId, String buttonLabel) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            try {
                Button button = Button.primary(buttonId, buttonLabel);
                return send(channel.sendMessage(message).addComponents(ActionRow.of(button)), TEXT_SEND_WAIT,
                        "enviar mensagem com botão");
            } catch (RuntimeException e) {
                Console.error("Erro ao enviar mensagem com botão", e);
                return null;
            }
        }

        static Message sendMessageWithButtons(String channelId, String message, Map<String, String> buttons) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            try {
                List<Button> buttonList = new ArrayList<>();
                for (Map.Entry<String, String> entry : buttons.entrySet()) {
                    buttonList.add(Button.primary(entry.getKey(), entry.getValue()));
                }
                return send(channel.sendMessage(message).addComponents(ActionRow.of(buttonList)), TEXT_SEND_WAIT,
                        "enviar mensagem com múltiplos botões");
            } catch (RuntimeException e) {
                Console.error("Erro ao enviar mensagem com múltiplos botões", e);
                return null;
            }
        }

        static Message sendImageFromUrl(String channelId, String imageUrl, String caption) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            DownloadedImage image;
            try {
                image = ImageDownloads.download(imageUrl);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Console.warn("Download da imagem interrompido (%s).", hostForLog(imageUrl));
                return null;
            } catch (IllegalArgumentException | IOException e) {
                Console.error("Imagem por URL não enviada (%s): %s", hostForLog(imageUrl), e.getMessage());
                return null;
            }
            return sendFile(channel, FileUpload.fromData(image.bytes(), image.fileName()), caption,
                    "enviar imagem por URL");
        }

        static Message sendImageFromBase64(String channelId, String base64, String caption) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            byte[] bytes;
            String extension;
            try {
                String clean = base64.contains(",") ? base64.split(",")[1] : base64;
                bytes = Base64.getDecoder().decode(clean.strip());
                extension = mimeTypeToExtension(detectMimeType(base64));
            } catch (RuntimeException e) {
                Console.error("Erro ao enviar imagem por Base64: conteúdo Base64 inválido", e);
                return null;
            }
            return sendFile(channel, FileUpload.fromData(bytes, "image." + extension), caption,
                    "enviar imagem por Base64");
        }

        static Message sendImageFromFile(String channelId, String filePath, String caption) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            FileUpload upload;
            try {
                File file = new File(filePath);
                if (!file.isFile()) {
                    Console.error("Arquivo não encontrado: %s", filePath);
                    return null;
                }
                upload = FileUpload.fromData(file);
            } catch (RuntimeException e) {
                Console.error("Erro ao enviar imagem por arquivo", e);
                return null;
            }
            return sendFile(channel, upload, caption, "enviar imagem por arquivo");
        }

        static Message sendBufferedImage(String channelId, BufferedImage image, String format, String caption) {
            TextChannel channel = channel(channelId);
            if (channel == null) {
                return null;
            }
            byte[] bytes;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                // ImageIO.write devolve false (sem lançar) quando não há escritor para o formato — e
                // antes isso virava um anexo vazio enviado ao Discord.
                if (!ImageIO.write(image, format, baos)) {
                    Console.error("Formato de imagem sem suporte no ImageIO: %s", format);
                    return null;
                }
                bytes = baos.toByteArray();
            } catch (IOException | RuntimeException e) {
                Console.error("Erro ao enviar BufferedImage", e);
                return null;
            }
            return sendFile(channel, FileUpload.fromData(bytes, "image." + format), caption, "enviar BufferedImage");
        }

        private static String detectMimeType(String base64) {
            if (base64.startsWith("data:image/")) {
                int start = "data:image/".length();
                int end = base64.indexOf(';');
                if (end > start) {
                    return base64.substring(start, end);
                }
            }
            return "png";
        }

        private static String mimeTypeToExtension(String mimeType) {
            switch (mimeType) {
                case "jpeg": return "jpg";
                case "jpg":  return "jpg";
                case "png":  return "png";
                case "gif":  return "gif";
                case "webp": return "webp";
                default:     return "png";
            }
        }

        /**
         * Espera a sessão assentar: conectada ({@code CONNECTED}) ou encerrada
         * ({@link ShutdownEvent}, que traz o código de encerramento).
         *
         * <p>Conta no {@code ShutdownEvent}, e não na troca de estado para {@code SHUTDOWN}, porque o
         * JDA muda o estado antes de disparar o evento — contar na troca perderia o código.</p>
         */
        private static final class ReadyWatcher implements EventListener {
            private final CountDownLatch settled = new CountDownLatch(1);
            private volatile CloseCode closeCode;

            @Override
            public void onEvent(@NotNull GenericEvent event) {
                if (event instanceof StatusChangeEvent change && change.getNewStatus() == JDA.Status.CONNECTED) {
                    settled.countDown();
                } else if (event instanceof ShutdownEvent shutdown) {
                    closeCode = shutdown.getCloseCode();
                    settled.countDown();
                }
            }

            boolean await(Duration timeout) throws InterruptedException {
                return settled.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            CloseCode closeCode() {
                return closeCode;
            }
        }

        /**
         * Listener de botões de <em>uma</em> sessão.
         *
         * <p>Aposentado quando a sessão sai (falha no setup, {@code shutdown()}, troca de token):
         * uma sessão antiga que ainda não terminou de fechar não executa ação nenhuma, e um clique
         * nunca roda duas vezes.</p>
         */
        private static final class ButtonListener extends ListenerAdapter {
            private volatile boolean active = true;

            void retire() {
                active = false;
            }

            @Override
            public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
                if (!active) {
                    return;
                }
                String componentId = event.getComponentId();
                Consumer<ButtonInteractionEvent> action = buttonActions.get(componentId);
                if (action == null) {
                    acknowledgeUnknownLater(event);
                    return;
                }
                // Argumentos, e não concatenação: o nome vem de qualquer usuário do Discord, e o
                // Console só neutraliza códigos de cor (&) nos argumentos.
                Console.debug("Botão clicado: %s por %s", componentId, event.getUser().getName());
                try {
                    action.accept(event);
                } catch (Exception e) {
                    Console.error("Erro ao processar clique do botão %s", componentId, e);
                    if (!event.isAcknowledged()) {
                        event.reply("Ocorreu um erro ao processar sua ação.").setEphemeral(true).queue(null,
                                failure -> Console.debug("Resposta de erro ao botão não enviada: %s", failure));
                    }
                }
            }

            /**
             * Responde a um botão sem ação registrada — mas só se ninguém responder antes.
             *
             * <p>Sem resposta, o Discord mostra "Esta interação falhou" para quem clicou. A resposta
             * espera {@link #UNKNOWN_BUTTON_GRACE} porque o consumidor pode tratar botões no próprio
             * listener (via {@link Bot#getJDA()}); responder na hora tomaria dele a interação, e a
             * resposta dele falharia com "já respondida".</p>
             */
            private static void acknowledgeUnknownLater(ButtonInteractionEvent event) {
                CompletableFuture.delayedExecutor(UNKNOWN_BUTTON_GRACE.toMillis(), TimeUnit.MILLISECONDS)
                        .execute(() -> {
                            try {
                                if (!event.isAcknowledged()) {
                                    event.reply("Este botão não está mais disponível.").setEphemeral(true).queue(
                                            null, failure -> Console.debug("Resposta a botão sem ação não enviada: "
                                                    + failure));
                                }
                            } catch (RuntimeException e) {
                                Console.debug("Resposta a botão sem ação não enviada: %s", e);
                            }
                        });
            }
        }
    }
}
