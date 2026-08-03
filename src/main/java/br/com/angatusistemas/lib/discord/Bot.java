package br.com.angatusistemas.lib.discord;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

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
 * <p><strong>Configuração necessária no arquivo .env:</strong>
 * <pre>
 * DISCORD_BOT_TOKEN=seu_token_aqui
 * </pre>
 * </p>
 *
 * <p><strong>Integração:</strong> usa {@link Env} para o token e
 * {@link Console} para logs; as ações de botão são registradas via
 * {@link #onButtonClick} e executadas em listener interno.</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>{@code Bot.setup()} (token do .env) ou {@code Bot.setup(token)};</li>
 *   <li>{@code Bot.sendMessage(canalId, texto)} — métodos são bloqueantes
 *       (usam {@code .complete()}) até a resposta do Discord;</li>
 *   <li>Botões: {@code sendMessageWithButton} + {@code onButtonClick}.</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong>
 * <pre>
 * Bot.setup();
 * Bot.sendMessage("123456789012345678", "Olá mundo!");
 * Bot.sendMessageWithButton("123456789012345678", "Confirma?", "btn_ok", "Sim");
 * Bot.onButtonClick("btn_ok", event -&gt; event.reply("Confirmado!").setEphemeral(true).queue());
 * </pre>
 * </p>
 *
 * <p><strong>Boas práticas:</strong> os métodos de envio são bloqueantes —
 * chame-os fora da thread de UI; registre os botões antes de enviar a mensagem;
 * trate {@code null} no retorno como falha (canal inexistente ou erro).</p>
 *
 * <p><strong>Limitações:</strong> requer {@code net.dv8tion:JDA:6.4.1} — a
 * classe é detectável (linkável) sem ela e o guard exibe instruções de
 * instalação; {@code setup()} usa {@code awaitReady()} (bloqueia até o bot
 * conectar); máximos de 5 botões por {@code ActionRow}.</p>
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

    /** Coordenadas Maven da dependência JDA. */
    private static final String JDA_COORDINATES = "net.dv8tion:JDA:6.4.1";
    /** Nome da funcionalidade para mensagens de dependência ausente. */
    private static final String DISCORD_FEATURE = "Discord Bot (JDA)";

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
        Dependencies.require("net.dv8tion.jda.api.JDA", JDA_COORDINATES, DISCORD_FEATURE);
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
     * @param token Token do bot Discord
     * @return {@code true} se inicializado com sucesso
     */
    public static synchronized boolean setup(String token) {
        Dependencies.require("net.dv8tion.jda.api.JDA", JDA_COORDINATES, DISCORD_FEATURE);
        return JdaSupport.setup(token);
    }

    /**
     * Retorna a instância JDA (para uso avançado).
     *
     * @return Instância JDA ou {@code null} se não inicializado
     */
    public static JDA getJDA() {
        return JdaSupport.jda;
    }

    /**
     * Verifica se o bot está inicializado.
     *
     * @return {@code true} se inicializado
     */
    public static boolean isInitialized() {
        return JdaSupport.initialized;
    }

    // ==================== ENVIO DE MENSAGENS ====================

    /**
     * Envia uma mensagem de texto simples para um canal (bloqueante).
     *
     * @param channelId ID do canal
     * @param message   Conteúdo da mensagem
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendMessage(String channelId, String message) {
        return JdaSupport.sendMessage(channelId, message);
    }

    /**
     * Envia uma mensagem com um botão (bloqueante).
     *
     * <p>Use {@link #onButtonClick(String, Consumer)} para registrar a ação do botão.</p>
     *
     * @param channelId   ID do canal
     * @param message     Texto da mensagem
     * @param buttonId    ID único do botão (para callback via onButtonClick)
     * @param buttonLabel Texto exibido no botão
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendMessageWithButton(String channelId, String message, String buttonId, String buttonLabel) {
        return JdaSupport.sendMessageWithButton(channelId, message, buttonId, buttonLabel);
    }

    /**
     * Envia uma mensagem com múltiplos botões (máximo 5 por ActionRow).
     *
     * @param channelId ID do canal
     * @param message   Texto da mensagem
     * @param buttons   Mapa de ID → rótulo do botão
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendMessageWithButtons(String channelId, String message, Map<String, String> buttons) {
        return JdaSupport.sendMessageWithButtons(channelId, message, buttons);
    }

    // ==================== ENVIO DE IMAGENS ====================

    /**
     * Envia uma imagem a partir de uma URL (bloqueante).
     *
     * @param channelId ID do canal
     * @param imageUrl  URL da imagem
     * @param caption   Legenda (pode ser {@code null})
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendImageFromUrl(String channelId, String imageUrl, String caption) {
        return JdaSupport.sendImageFromUrl(channelId, imageUrl, caption);
    }

    /**
     * Envia uma imagem a partir de uma string Base64 (bloqueante).
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
        return JdaSupport.sendImageFromBase64(channelId, base64, caption);
    }

    /**
     * Envia uma imagem a partir de um arquivo local (bloqueante).
     *
     * @param channelId ID do canal
     * @param filePath  Caminho do arquivo
     * @param caption   Legenda (opcional)
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendImageFromFile(String channelId, String filePath, String caption) {
        return JdaSupport.sendImageFromFile(channelId, filePath, caption);
    }

    /**
     * Envia uma imagem a partir de um BufferedImage (bloqueante).
     *
     * @param channelId ID do canal
     * @param image     Imagem a ser enviada
     * @param format    Formato da imagem (ex: "png", "jpg")
     * @param caption   Legenda (opcional)
     * @return A mensagem enviada ou {@code null} em caso de erro
     */
    public static Message sendBufferedImage(String channelId, BufferedImage image, String format, String caption) {
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
     * @param buttonId ID do botão (deve ser único)
     * @param action   Ação a ser executada (recebe o evento)
     */
    public static void onButtonClick(String buttonId, Consumer<ButtonInteractionEvent> action) {
        JdaSupport.buttonActions.put(buttonId, action);
        Console.debug("Ação registrada para botão: " + buttonId);
    }

    /**
     * Remove a ação associada a um botão.
     *
     * @param buttonId ID do botão
     */
    public static void removeButtonAction(String buttonId) {
        JdaSupport.buttonActions.remove(buttonId);
        Console.debug("Ação removida para botão: " + buttonId);
    }

    // ==================== IMPLEMENTAÇÃO (JDA — LAZY) ====================

    /**
     * Implementação da integração com o JDA. Classe separada para manter as
     * referências ao JDA fora do bytecode da {@link Bot} — a classe pública
     * pode ser vinculada sem o JDA e o guard exibe a mensagem de instalação
     * antes de qualquer uso.
     */
    private static final class JdaSupport {

        private static JDA jda;
        private static boolean initialized = false;
        private static final Map<String, Consumer<ButtonInteractionEvent>> buttonActions = new ConcurrentHashMap<>();

        private JdaSupport() {
        }

        static synchronized boolean setup(String token) {
            if (initialized) {
                Console.warn("DiscordBot já foi inicializado.");
                return true;
            }

            try {
                jda = JDABuilder.createDefault(token)
                        .enableIntents(
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT
                        )
                        .addEventListeners(new ButtonListener())
                        .build();
                jda.awaitReady();
                initialized = true;
                Console.log("DiscordBot inicializado com sucesso como: " + jda.getSelfUser().getName());
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Console.error("Inicialização do DiscordBot interrompida", e);
                return false;
            } catch (Exception e) {
                Console.error("Falha ao inicializar DiscordBot", e);
                return false;
            }
        }

        static Message sendMessage(String channelId, String message) {
            if (!initialized) return null;
            try {
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) {
                    Console.error("Canal não encontrado: " + channelId);
                    return null;
                }
                return channel.sendMessage(message).complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar mensagem", e);
                return null;
            }
        }

        static Message sendMessageWithButton(String channelId, String message, String buttonId, String buttonLabel) {
            if (!initialized) return null;
            try {
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) return null;
                Button button = Button.primary(buttonId, buttonLabel);
                return channel.sendMessage(message)
                        .addComponents(ActionRow.of(button))
                        .complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar mensagem com botão", e);
                return null;
            }
        }

        static Message sendMessageWithButtons(String channelId, String message, Map<String, String> buttons) {
            if (!initialized) return null;
            try {
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) return null;
                List<Button> buttonList = new ArrayList<>();
                for (Map.Entry<String, String> entry : buttons.entrySet()) {
                    buttonList.add(Button.primary(entry.getKey(), entry.getValue()));
                }
                return channel.sendMessage(message)
                        .addComponents(ActionRow.of(buttonList))
                        .complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar mensagem com múltiplos botões", e);
                return null;
            }
        }

        static Message sendImageFromUrl(String channelId, String imageUrl, String caption) {
            if (!initialized) return null;
            try {
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) return null;
                URL url = URI.create(imageUrl).toURL();
                String fileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
                FileUpload fileUpload = FileUpload.fromData(url.openStream(), fileName);
                MessageCreateBuilder builder = new MessageCreateBuilder();
                if (caption != null && !caption.isEmpty()) builder.setContent(caption);
                builder.setFiles(fileUpload);
                return channel.sendMessage(builder.build()).complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar imagem por URL", e);
                return null;
            }
        }

        static Message sendImageFromBase64(String channelId, String base64, String caption) {
            if (!initialized) return null;
            try {
                String clean = base64.contains(",") ? base64.split(",")[1] : base64;
                byte[] bytes = Base64.getDecoder().decode(clean);
                String mimeType = detectMimeType(base64);
                String extension = mimeTypeToExtension(mimeType);
                FileUpload fileUpload = FileUpload.fromData(bytes, "image." + extension);
                MessageCreateBuilder builder = new MessageCreateBuilder();
                if (caption != null && !caption.isEmpty()) builder.setContent(caption);
                builder.setFiles(fileUpload);
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) return null;
                return channel.sendMessage(builder.build()).complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar imagem por Base64", e);
                return null;
            }
        }

        static Message sendImageFromFile(String channelId, String filePath, String caption) {
            if (!initialized) return null;
            try {
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) return null;
                File file = new File(filePath);
                if (!file.exists()) {
                    Console.error("Arquivo não encontrado: " + filePath);
                    return null;
                }
                FileUpload fileUpload = FileUpload.fromData(file);
                MessageCreateBuilder builder = new MessageCreateBuilder();
                if (caption != null && !caption.isEmpty()) builder.setContent(caption);
                builder.setFiles(fileUpload);
                return channel.sendMessage(builder.build()).complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar imagem por arquivo", e);
                return null;
            }
        }

        static Message sendBufferedImage(String channelId, BufferedImage image, String format, String caption) {
            if (!initialized) return null;
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, format, baos);
                byte[] bytes = baos.toByteArray();
                FileUpload fileUpload = FileUpload.fromData(bytes, "image." + format);
                MessageCreateBuilder builder = new MessageCreateBuilder();
                if (caption != null && !caption.isEmpty()) builder.setContent(caption);
                builder.setFiles(fileUpload);
                TextChannel channel = jda.getTextChannelById(channelId);
                if (channel == null) return null;
                return channel.sendMessage(builder.build()).complete();
            } catch (Exception e) {
                Console.error("Erro ao enviar BufferedImage", e);
                return null;
            }
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

        private static class ButtonListener extends ListenerAdapter {
            @Override
            public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
                String componentId = event.getComponentId();
                if (buttonActions.containsKey(componentId)) {
                    Console.debug("Botão clicado: " + componentId + " por " + event.getUser().getName());
                    try {
                        buttonActions.get(componentId).accept(event);
                    } catch (Exception e) {
                        Console.error("Erro ao processar clique do botão %s", componentId, e);
                        if (!event.isAcknowledged()) {
                            event.reply("Ocorreu um erro ao processar sua ação.").setEphemeral(true).queue();
                        }
                    }
                }
            }
        }
    }
}
