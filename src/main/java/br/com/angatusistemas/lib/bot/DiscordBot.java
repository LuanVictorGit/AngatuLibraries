package br.com.angatusistemas.lib.bot;

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
 * [PT] Classe utilitária para integração com Discord utilizando JDA (Java Discord API).
 * <p>
 * Fornece métodos simplificados para enviar mensagens (texto, imagens, arquivos),
 * botões interativos (com callbacks), e gerenciamento do bot.
 * </p>
 * <p>
 * <b>Configuração necessária no arquivo .env:</b>
 * <pre>
 * DISCORD_BOT_TOKEN=seu_token_aqui
 * </pre>
 * </p>
 * <p>
 * <b>Exemplo de uso:</b>
 * <pre>
 * // Inicializar o bot
 * DiscordBot.setup();
 *
 * // Enviar mensagem simples
 * DiscordBot.sendMessage("123456789012345678", "Olá mundo!");
 *
 * // Enviar mensagem com botão
 * DiscordBot.sendMessageWithButton("channelId", "Clique aqui!", "meu_botao", "Texto do botão");
 *
 * // Registrar ação do botão
 * DiscordBot.onButtonClick("meu_botao", event -> {
 *     event.reply("Você clicou!").setEphemeral(true).queue();
 * });
 * </pre>
 * </p>
 *
 * [EN] Utility class for Discord integration using JDA (Java Discord API).
 * <p>
 * Provides simplified methods for sending messages (text, images, files),
 * interactive buttons (with callbacks), and bot management.
 * </p>
 * <p>
 * <b>Required configuration in .env file:</b>
 * <pre>
 * DISCORD_BOT_TOKEN=your_token_here
 * </pre>
 * </p>
 * <p>
 * <b>Usage example:</b>
 * <pre>
 * // Initialize the bot
 * DiscordBot.setup();
 *
 * // Send simple message
 * DiscordBot.sendMessage("123456789012345678", "Hello world!");
 *
 * // Send message with button
 * DiscordBot.sendMessageWithButton("channelId", "Click here!", "my_button", "Button text");
 *
 * // Register button action
 * DiscordBot.onButtonClick("my_button", event -> {
 *     event.reply("You clicked!").setEphemeral(true).queue();
 * });
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://github.com/discord-jda/JDA">JDA on GitHub</a>
 */
public final class DiscordBot {

    private static JDA jda;
    private static boolean initialized = false;
    private static final Map<String, Consumer<ButtonInteractionEvent>> buttonActions = new ConcurrentHashMap<>();

    private DiscordBot() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    // ==================== INICIALIZAÇÃO ====================

    /**
     * [PT] Inicializa o bot Discord usando o token do arquivo .env (chave DISCORD_BOT_TOKEN).
     *
     * [EN] Initializes the Discord bot using the token from the .env file (key DISCORD_BOT_TOKEN).
     *
     * @return [PT] true se inicializado com sucesso, false caso contrário
     *         [EN] true if initialized successfully, false otherwise
     */
    public static boolean setup() {
        String token = Env.get().get("DISCORD_BOT_TOKEN");
        if (token == null || token.trim().isEmpty()) {
            Console.error("Token do Discord não configurado. Adicione DISCORD_BOT_TOKEN no .env");
            return false;
        }
        return setup(token);
    }

    /**
     * [PT] Inicializa o bot Discord com um token fornecido explicitamente.
     *
     * [EN] Initializes the Discord bot with an explicitly provided token.
     *
     * @param token [PT] token do bot Discord
     *              [EN] Discord bot token
     * @return [PT] true se inicializado com sucesso
     *         [EN] true if initialized successfully
     */
    public static synchronized boolean setup(String token) {
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
            Console.error("Inicialização do DiscordBot interrompida: {}", e);
            return false;
        } catch (Exception e) {
            Console.error("Falha ao inicializar DiscordBot: {}", e);
            return false;
        }
    }

    /**
     * [PT] Retorna a instância JDA (para uso avançado).
     *
     * [EN] Returns the JDA instance (for advanced usage).
     *
     * @return [PT] instância JDA ou null se não inicializado
     *         [EN] JDA instance or null if not initialized
     */
    public static JDA getJDA() {
        return jda;
    }

    /**
     * [PT] Verifica se o bot está inicializado.
     *
     * [EN] Checks if the bot is initialized.
     *
     * @return [PT] true se inicializado
     *         [EN] true if initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }

    // ==================== ENVIO DE MENSAGENS ====================

    /**
     * [PT] Envia uma mensagem de texto simples para um canal.
     *
     * [EN] Sends a plain text message to a channel.
     *
     * @param channelId [PT] ID do canal
     *                  [EN] channel ID
     * @param message   [PT] conteúdo da mensagem
     *                  [EN] message content
     * @return [PT] a mensagem enviada ou null em caso de erro
     *         [EN] the sent message or null on error
     */
    public static Message sendMessage(String channelId, String message) {
        if (!initialized) return null;
        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) {
                Console.error("Canal não encontrado: " + channelId);
                return null;
            }
            return channel.sendMessage(message).complete();
        } catch (Exception e) {
            Console.error("Erro ao enviar mensagem: {}", e);
            return null;
        }
    }

    /**
     * [PT] Envia uma mensagem com um botão.
     * <p>
     * Use {@link #onButtonClick(String, Consumer)} para registrar a ação do botão.
     * </p>
     *
     * [EN] Sends a message with a single button.
     * <p>
     * Use {@link #onButtonClick(String, Consumer)} to register the button action.
     * </p>
     *
     * @param channelId   [PT] ID do canal
     *                    [EN] channel ID
     * @param message     [PT] texto da mensagem
     *                    [EN] message text
     * @param buttonId    [PT] ID único do botão (para callback via onButtonClick)
     *                    [EN] unique button ID (for callback via onButtonClick)
     * @param buttonLabel [PT] texto exibido no botão
     *                    [EN] button label
     * @return [PT] a mensagem enviada ou null
     *         [EN] the sent message or null
     */
    public static Message sendMessageWithButton(String channelId, String message, String buttonId, String buttonLabel) {
        if (!initialized) return null;
        try {
            TextChannel channel = jda.getTextChannelById(channelId);
            if (channel == null) return null;
            Button button = Button.primary(buttonId, buttonLabel);
            return channel.sendMessage(message)
                    .addComponents(ActionRow.of(button))
                    .complete();
        } catch (Exception e) {
            Console.error("Erro ao enviar mensagem com botão: {}", e);
            return null;
        }
    }

    /**
     * [PT] Envia uma mensagem com múltiplos botões (máximo 5 por ActionRow).
     *
     * [EN] Sends a message with multiple buttons (max 5 per ActionRow).
     *
     * @param channelId [PT] ID do canal
     *                  [EN] channel ID
     * @param message   [PT] texto da mensagem
     *                  [EN] message text
     * @param buttons   [PT] mapa de ID -> rótulo do botão
     *                  [EN] map of ID -> button label
     * @return [PT] a mensagem enviada ou null
     *         [EN] the sent message or null
     */
    public static Message sendMessageWithButtons(String channelId, String message, Map<String, String> buttons) {
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
            Console.error("Erro ao enviar mensagem com múltiplos botões: {}", e);
            return null;
        }
    }

    // ==================== ENVIO DE IMAGENS ====================

    /**
     * [PT] Envia uma imagem a partir de uma URL.
     *
     * [EN] Sends an image from a URL.
     *
     * @param channelId [PT] ID do canal
     *                  [EN] channel ID
     * @param imageUrl  [PT] URL da imagem
     *                  [EN] image URL
     * @param caption   [PT] legenda (pode ser null)
     *                  [EN] caption (may be null)
     * @return [PT] a mensagem enviada ou null
     *         [EN] the sent message or null
     */
    public static Message sendImageFromUrl(String channelId, String imageUrl, String caption) {
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
            Console.error("Erro ao enviar imagem por URL: {}", e);
            return null;
        }
    }

    /**
     * [PT] Envia uma imagem a partir de uma string Base64.
     * <p>
     * Aceita formatos como "data:image/png;base64,..." ou apenas a parte Base64.
     * </p>
     *
     * [EN] Sends an image from a Base64 string.
     * <p>
     * Accepts formats like "data:image/png;base64,..." or just the Base64 part.
     * </p>
     *
     * @param channelId [PT] ID do canal
     *                  [EN] channel ID
     * @param base64    [PT] string Base64 da imagem
     *                  [EN] Base64 string of the image
     * @param caption   [PT] legenda (opcional)
     *                  [EN] caption (optional)
     * @return [PT] a mensagem enviada ou null
     *         [EN] the sent message or null
     */
    public static Message sendImageFromBase64(String channelId, String base64, String caption) {
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
            Console.error("Erro ao enviar imagem por Base64: {}", e);
            return null;
        }
    }

    /**
     * [PT] Envia uma imagem a partir de um arquivo local.
     *
     * [EN] Sends an image from a local file.
     *
     * @param channelId [PT] ID do canal
     *                  [EN] channel ID
     * @param filePath  [PT] caminho do arquivo
     *                  [EN] file path
     * @param caption   [PT] legenda (opcional)
     *                  [EN] caption (optional)
     * @return [PT] a mensagem enviada ou null
     *         [EN] the sent message or null
     */
    public static Message sendImageFromFile(String channelId, String filePath, String caption) {
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
            Console.error("Erro ao enviar imagem por arquivo: {}", e);
            return null;
        }
    }

    /**
     * [PT] Envia uma imagem a partir de um BufferedImage.
     *
     * [EN] Sends an image from a BufferedImage.
     *
     * @param channelId [PT] ID do canal
     *                  [EN] channel ID
     * @param image     [PT] imagem a ser enviada
     *                  [EN] image to send
     * @param format    [PT] formato da imagem (ex: "png", "jpg")
     *                  [EN] image format (e.g., "png", "jpg")
     * @param caption   [PT] legenda (opcional)
     *                  [EN] caption (optional)
     * @return [PT] a mensagem enviada ou null
     *         [EN] the sent message or null
     */
    public static Message sendBufferedImage(String channelId, BufferedImage image, String format, String caption) {
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
            Console.error("Erro ao enviar BufferedImage: {}", e);
            return null;
        }
    }

    // ==================== BOTÕES E CALLBACKS ====================

    /**
     * [PT] Registra uma ação para ser executada quando um botão com o ID especificado for clicado.
     *
     * [EN] Registers an action to be executed when a button with the specified ID is clicked.
     *
     * @param buttonId [PT] ID do botão (deve ser único)
     *                 [EN] button ID (must be unique)
     * @param action   [PT] ação a ser executada (recebe o evento)
     *                 [EN] action to execute (receives the event)
     */
    public static void onButtonClick(String buttonId, Consumer<ButtonInteractionEvent> action) {
        buttonActions.put(buttonId, action);
        Console.debug("Ação registrada para botão: " + buttonId);
    }

    /**
     * [PT] Remove a ação associada a um botão.
     *
     * [EN] Removes the action associated with a button.
     *
     * @param buttonId [PT] ID do botão
     *                 [EN] button ID
     */
    public static void removeButtonAction(String buttonId) {
        buttonActions.remove(buttonId);
        Console.debug("Ação removida para botão: " + buttonId);
    }

    // ==================== LISTENER INTERNO ====================

    private static class ButtonListener extends ListenerAdapter {
        @Override
        public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
            // JDA 6: getComponentId() substitui getButton().getId()
            // JDA 6: getComponentId() replaces getButton().getId()
            String componentId = event.getComponentId();
            if (buttonActions.containsKey(componentId)) {
                Console.debug("Botão clicado: " + componentId + " por " + event.getUser().getName());
                try {
                    buttonActions.get(componentId).accept(event);
                } catch (Exception e) {
                    Console.error("Erro ao processar clique do botão {}: {}", componentId, e);
                    if (!event.isAcknowledged()) {
                        event.reply("Ocorreu um erro ao processar sua ação.").setEphemeral(true).queue();
                    }
                }
            }
        }
    }

    // ==================== MÉTODOS AUXILIARES ====================

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
}