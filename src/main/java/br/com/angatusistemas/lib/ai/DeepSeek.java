package br.com.angatusistemas.lib.ai;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.env.Env;
import br.com.angatusistemas.lib.task.Task;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * [PT] Classe utilitária para integração com a API da DeepSeek (modelos de
 * linguagem).
 * <p>
 * Permite enviar instruções (prompts) e receber respostas de forma síncrona ou
 * assíncrona. Suporta histórico de conversas, temperatura, tokens máximos e
 * outros parâmetros.
 * </p>
 * <p>
 * <b>Configuração necessária no arquivo .env:</b>
 * 
 * <pre>
 * DEEPSEEK_API_KEY=sk-...
 * DEEPSEEK_MODEL=deepseek-chat (opcional, padrão)
 * </pre>
 * </p>
 * <p>
 * <b>Exemplo de uso:</b>
 * 
 * <pre>
 * // Inicialização automática com credenciais do .env
 * DeepSeek.initialize();
 *
 * // Envio simples e síncrono
 * String resposta = DeepSeek.ask("Explique o que é uma API REST");
 * System.out.println(resposta);
 *
 * // Com histórico de conversa
 * List&lt;DeepSeek.Message&gt; history = new ArrayList<>();
 * history.add(DeepSeek.userMessage("Qual é a capital do Brasil?"));
 * String resposta2 = DeepSeek.askWithHistory(history);
 * history.add(DeepSeek.assistantMessage(resposta2));
 * history.add(DeepSeek.userMessage("E a sua população?"));
 * String resposta3 = DeepSeek.askWithHistory(history);
 *
 * // Chamada assíncrona
 * DeepSeek.askAsync("Escreva um poema sobre Java", resposta -> {
 * 	System.out.println("Resposta: " + resposta);
 * });
 * </pre>
 * </p>
 *
 * [EN] Utility class for integration with the DeepSeek API (language models).
 * <p>
 * Allows sending prompts and receiving responses synchronously or
 * asynchronously. Supports conversation history, temperature, max tokens and
 * other parameters.
 * </p>
 * <p>
 * <b>Required configuration in .env file:</b>
 * 
 * <pre>
 * DEEPSEEK_API_KEY=sk-...
 * DEEPSEEK_MODEL=deepseek-chat (optional, default)
 * </pre>
 * </p>
 * <p>
 * <b>Usage example:</b>
 * 
 * <pre>
 * // Automatic initialization with .env credentials
 * DeepSeek.initialize();
 *
 * // Simple synchronous request
 * String response = DeepSeek.ask("Explain what a REST API is");
 * System.out.println(response);
 *
 * // With conversation history
 * List&lt;DeepSeek.Message&gt; history = new ArrayList<>();
 * history.add(DeepSeek.userMessage("What is the capital of Brazil?"));
 * String response2 = DeepSeek.askWithHistory(history);
 * history.add(DeepSeek.assistantMessage(response2));
 * history.add(DeepSeek.userMessage("And its population?"));
 * String response3 = DeepSeek.askWithHistory(history);
 *
 * // Asynchronous call
 * DeepSeek.askAsync("Write a poem about Java", response -> {
 * 	System.out.println("Response: " + response);
 * });
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://platform.deepseek.com/api-docs/">DeepSeek API Docs</a>
 */
public final class DeepSeek {

	private static final String DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions";
	private static final int DEFAULT_TIMEOUT_MS = 30000;
	private static final double DEFAULT_TEMPERATURE = 0.7;
	private static final int DEFAULT_MAX_TOKENS = 2000;

	private static String apiKey;
	private static String model;
	private static boolean initialized = false;

	private static final Gson gson = new Gson();

	private DeepSeek() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== INICIALIZAÇÃO ====================

	/**
	 * [PT] Inicializa o cliente DeepSeek com as configurações do arquivo .env.
	 * <p>
	 * Chaves esperadas: DEEPSEEK_API_KEY (obrigatória), DEEPSEEK_MODEL (opcional).
	 * </p>
	 *
	 * [EN] Initializes the DeepSeek client with settings from the .env file.
	 * <p>
	 * Expected keys: DEEPSEEK_API_KEY (required), DEEPSEEK_MODEL (optional).
	 * </p>
	 *
	 * @return [PT] true se inicializado com sucesso, false caso contrário [EN] true
	 *         if initialized successfully, false otherwise
	 */
	public static synchronized boolean initialize() {
		if (initialized)
			return true;

		apiKey = Env.get().get("DEEPSEEK_API_KEY");
		model = Env.get().get("DEEPSEEK_MODEL");
		if (model == null || model.trim().isEmpty()) {
			model = "deepseek-chat";
		}

		if (apiKey == null || apiKey.trim().isEmpty()) {
			Console.error("DeepSeek API key não configurada. Adicione DEEPSEEK_API_KEY no .env");
			return false;
		}

		initialized = true;
		Console.log("DeepSeek inicializado com modelo: " + model);
		return true;
	}

	/**
	 * [PT] Inicializa o cliente DeepSeek com credenciais fornecidas explicitamente.
	 *
	 * [EN] Initializes the DeepSeek client with explicitly provided credentials.
	 *
	 * @param apiKey [PT] chave da API DeepSeek [EN] DeepSeek API key
	 * @param model  [PT] nome do modelo (ex: "deepseek-chat") [EN] model name
	 *               (e.g., "deepseek-chat")
	 */
	public static synchronized void initialize(String apiKey, String model) {
		DeepSeek.apiKey = apiKey;
		DeepSeek.model = (model != null && !model.isEmpty()) ? model : "deepseek-chat";
		initialized = true;
		Console.log("DeepSeek inicializado com modelo: " + DeepSeek.model);
	}

	// ==================== MÉTODOS PRINCIPAIS ====================

	/**
	 * [PT] Envia uma instrução simples e retorna a resposta (síncrono).
	 *
	 * [EN] Sends a simple prompt and returns the response (synchronous).
	 *
	 * @param instruction [PT] texto da instrução/prompt [EN] instruction/prompt
	 *                    text
	 * @return [PT] resposta do modelo ou null em caso de erro [EN] model response
	 *         or null on error
	 */
	public static String ask(String instruction) {
		List<Message> messages = new ArrayList<>();
		messages.add(userMessage(instruction));
		return askWithHistory(messages);
	}

	/**
	 * [PT] Envia uma conversa com histórico e retorna a resposta (síncrono).
	 *
	 * [EN] Sends a conversation with history and returns the response
	 * (synchronous).
	 *
	 * @param history [PT] lista de mensagens (alternando usuário e assistente) [EN]
	 *                list of messages (alternating user and assistant)
	 * @return [PT] resposta do modelo ou null em caso de erro [EN] model response
	 *         or null on error
	 */
	public static String askWithHistory(List<Message> history) {
		return askWithHistory(history, DEFAULT_TEMPERATURE, DEFAULT_MAX_TOKENS);
	}

	/**
	 * [PT] Envia uma conversa com parâmetros avançados (temperatura, tokens
	 * máximos).
	 *
	 * [EN] Sends a conversation with advanced parameters (temperature, max tokens).
	 *
	 * @param history     [PT] lista de mensagens [EN] list of messages
	 * @param temperature [PT] criatividade da resposta (0.0 a 1.0) [EN] response
	 *                    creativity (0.0 to 1.0)
	 * @param maxTokens   [PT] número máximo de tokens na resposta [EN] maximum
	 *                    tokens in response
	 * @return [PT] resposta do modelo ou null [EN] model response or null
	 */
	public static String askWithHistory(List<Message> history, double temperature, int maxTokens) {
		if (!initialized && !initialize()) {
			Console.error("DeepSeek não inicializado. Chame initialize() primeiro.");
			return null;
		}

		try {
			JsonObject requestBody = buildRequest(history, temperature, maxTokens);
			String responseJson = sendRequest(requestBody.toString());
			return parseResponse(responseJson);
		} catch (Exception e) {
			Console.error("Erro ao chamar DeepSeek API: {}", e);
			return null;
		}
	}

	// ==================== MÉTODOS ASSÍNCRONOS ====================

	/**
	 * [PT] Envia uma instrução de forma assíncrona (usando a pool do Task).
	 *
	 * [EN] Sends a prompt asynchronously (using the Task pool).
	 *
	 * @param instruction [PT] texto da instrução [EN] prompt text
	 * @param callback    [PT] callback chamado com a resposta [EN] callback called
	 *                    with the response
	 */
	public static void askAsync(String instruction, DeepSeekCallback callback) {
		Task.runAsync(() -> {
			String response = ask(instruction);
			callback.onResponse(response);
		});
	}

	/**
	 * [PT] Envia uma conversa com histórico de forma assíncrona.
	 *
	 * [EN] Sends a conversation with history asynchronously.
	 *
	 * @param history  [PT] lista de mensagens [EN] list of messages
	 * @param callback [PT] callback com a resposta [EN] callback with the response
	 */
	public static void askAsyncWithHistory(List<Message> history, DeepSeekCallback callback) {
		Task.runAsync(() -> {
			String response = askWithHistory(history);
			callback.onResponse(response);
		});
	}

	/**
	 * [PT] Envia uma instrução e retorna um CompletableFuture (para programação
	 * reativa).
	 *
	 * [EN] Sends a prompt and returns a CompletableFuture (for reactive
	 * programming).
	 *
	 * @param instruction [PT] texto da instrução [EN] prompt text
	 * @return [PT] CompletableFuture com a resposta [EN] CompletableFuture with the
	 *         response
	 */
	public static CompletableFuture<String> askFuture(String instruction) {
		return CompletableFuture.supplyAsync(() -> ask(instruction));
	}

	// ==================== CONSTRUÇÃO DE MENSAGENS ====================

	/**
	 * [PT] Cria uma mensagem do tipo usuário.
	 *
	 * [EN] Creates a user-type message.
	 *
	 * @param content [PT] conteúdo da mensagem [EN] message content
	 * @return [PT] objeto Message [EN] Message object
	 */
	public static Message userMessage(String content) {
		return new Message("user", content);
	}

	/**
	 * [PT] Cria uma mensagem do tipo assistente.
	 *
	 * [EN] Creates an assistant-type message.
	 *
	 * @param content [PT] conteúdo da mensagem [EN] message content
	 * @return [PT] objeto Message [EN] Message object
	 */
	public static Message assistantMessage(String content) {
		return new Message("assistant", content);
	}

	/**
	 * [PT] Cria uma mensagem do tipo sistema (para instruções de comportamento).
	 *
	 * [EN] Creates a system-type message (for behavior instructions).
	 *
	 * @param content [PT] conteúdo da mensagem [EN] message content
	 * @return [PT] objeto Message [EN] Message object
	 */
	public static Message systemMessage(String content) {
		return new Message("system", content);
	}

	// ==================== MÉTODOS INTERNOS ====================

	private static JsonObject buildRequest(List<Message> messages, double temperature, int maxTokens) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("temperature", temperature);
		body.addProperty("max_tokens", maxTokens);
		body.addProperty("stream", false);

		JsonArray msgArray = new JsonArray();
		for (Message msg : messages) {
			JsonObject msgObj = new JsonObject();
			msgObj.addProperty("role", msg.role);
			msgObj.addProperty("content", msg.content);
			msgArray.add(msgObj);
		}
		body.add("messages", msgArray);

		return body;
	}

	private static String sendRequest(String jsonBody) throws Exception {
		URL url = URI.create(DEFAULT_API_URL).toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Authorization", "Bearer " + apiKey);
		conn.setDoOutput(true);
		conn.setConnectTimeout(DEFAULT_TIMEOUT_MS);
		conn.setReadTimeout(DEFAULT_TIMEOUT_MS);

		try (OutputStream os = conn.getOutputStream()) {
			os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
			os.flush();
		}

		int status = conn.getResponseCode();
		if (status != 200) {
			try (BufferedReader br = new BufferedReader(
					new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
				StringBuilder error = new StringBuilder();
				String line;
				while ((line = br.readLine()) != null)
					error.append(line);
				throw new IOException("HTTP " + status + ": " + error);
			}
		}

		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
			StringBuilder response = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null)
				response.append(line);
			return response.toString();
		}
	}

	private static String parseResponse(String json) {
		JsonObject obj = gson.fromJson(json, JsonObject.class);
		JsonArray choices = obj.getAsJsonArray("choices");
		if (choices != null && choices.size() > 0) {
			JsonObject firstChoice = choices.get(0).getAsJsonObject();
			JsonObject message = firstChoice.getAsJsonObject("message");
			if (message != null && message.has("content")) {
				return message.get("content").getAsString();
			}
		}
		return null;
	}

	// ==================== CLASSES DE SUPORTE ====================

	/**
	 * [PT] Representa uma mensagem na conversa. [EN] Represents a message in the
	 * conversation.
	 */
	public static class Message {
		public final String role;
		public final String content;

		public Message(String role, String content) {
			this.role = role;
			this.content = content;
		}
	}

	/**
	 * [PT] Interface de callback para chamadas assíncronas. [EN] Callback interface
	 * for asynchronous calls.
	 */
	@FunctionalInterface
	public interface DeepSeekCallback {
		void onResponse(String response);
	}
}