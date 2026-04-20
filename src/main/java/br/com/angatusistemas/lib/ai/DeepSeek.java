package br.com.angatusistemas.lib.ai;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.env.Env;

/**
 * [PT] Cliente para a API da DeepSeek com suporte a instrução de sistema por
 * chamada.
 * <p>
 * Permite definir dinamicamente o comportamento do assistente (instrução de
 * sistema) e enviar a mensagem do usuário em uma única chamada.
 * </p>
 * <p>
 * Exemplo:
 * 
 * <pre>
 * // Inicialização automática com token do .env
 * DeepSeek.initialize();
 *
 * // Enviar instrução + mensagem do usuário
 * String resposta = DeepSeek.ask("Você deve chamar o usuário sempre amorosamente", "Olá, tudo bem?");
 * System.out.println(resposta);
 *
 * // Com streaming
 * DeepSeek.askStream("Responda em português de forma criativa", "Crie uma história sobre um robô",
 * 		chunk -> System.out.print(chunk));
 * </pre>
 * </p>
 *
 * [EN] DeepSeek API client with per‑call system instruction support.
 * <p>
 * Allows dynamically setting the assistant's behavior (system instruction) and
 * sending the user's message in a single call.
 * </p>
 * <p>
 * Example:
 * 
 * <pre>
 * // Automatic initialization with token from .env
 * DeepSeek.initialize();
 *
 * // Send instruction + user message
 * String answer = DeepSeek.ask("You must always call the user affectionately", "Hello, how are you?");
 * System.out.println(answer);
 *
 * // With streaming
 * DeepSeek.askStream("Answer in English in a creative way", "Tell me a story about a robot",
 * 		chunk -> System.out.print(chunk));
 * </pre>
 * </p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://platform.deepseek.com/api-docs/">DeepSeek API Docs</a>
 */
public final class DeepSeek {

	private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
	private static final int TIMEOUT_SECONDS = 300;
	private static final double DEFAULT_TEMPERATURE = 1.0;

	private static String apiKey;
	private static String model = "deepseek-chat";
	private static boolean initialized = false;

	private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final Gson gson = new GsonBuilder().create();

	private DeepSeek() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	// ==================== INICIALIZAÇÃO ====================

	/**
	 * [PT] Inicializa o cliente com a chave da API lida do arquivo .env
	 * (DEEPSEEK_API_KEY).
	 *
	 * [EN] Initializes the client with the API key read from the .env file
	 * (DEEPSEEK_API_KEY).
	 *
	 * @return true se inicializado com sucesso, false caso contrário
	 */
	public static synchronized boolean initialize() {
		if (initialized)
			return true;
		String key = Env.get().get("DEEPSEEK_API_KEY");
		if (key == null || key.trim().isEmpty()) {
			Console.error("DeepSeek API key não configurada. Adicione DEEPSEEK_API_KEY no .env");
			return false;
		}
		apiKey = key;
		initialized = true;
		Console.log("DeepSeek inicializado com modelo: " + model);
		return true;
	}

	/**
	 * [PT] Inicializa o cliente com a chave e modelo fornecidos.
	 *
	 * [EN] Initializes the client with the provided key and model.
	 *
	 * @param apiKey chave da API DeepSeek
	 * @param model  nome do modelo (ex: "deepseek-chat", se null usa padrão)
	 */
	public static synchronized void initialize(String apiKey, String model) {
		DeepSeek.apiKey = apiKey;
		if (model != null && !model.trim().isEmpty()) {
			DeepSeek.model = model;
		}
		initialized = true;
		Console.log("DeepSeek inicializado com modelo: " + DeepSeek.model);
	}

	/**
	 * [PT] Define o modelo padrão (caso não seja fornecido na inicialização).
	 *
	 * [EN] Sets the default model (if not provided during initialization).
	 *
	 * @param model nome do modelo
	 */
	public static void setModel(String model) {
		DeepSeek.model = model;
	}

	// ==================== MÉTODOS PRINCIPAIS ====================

	/**
	 * [PT] Envia uma instrução de sistema e uma mensagem do usuário, retornando a
	 * resposta completa (síncrono).
	 *
	 * [EN] Sends a system instruction and a user message, returning the full
	 * response (synchronous).
	 *
	 * @param systemInstruction [PT] instrução que define o comportamento do
	 *                          assistente (pode ser null ou vazio) [EN] instruction
	 *                          that defines the assistant's behavior (may be null
	 *                          or empty)
	 * @param userMessage       [PT] texto enviado pelo usuário [EN] text sent by
	 *                          the user
	 * @return [PT] resposta do assistente ou null em caso de erro [EN] assistant's
	 *         response or null on error
	 */
	public static String ask(String systemInstruction, String userMessage) {
		if (!initialized && !initialize())
			return null;

		try {
			String jsonBody = buildRequestBody(systemInstruction, userMessage, false);
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_URL))
					.timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + apiKey).POST(HttpRequest.BodyPublishers.ofString(jsonBody))
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				Console.error("Erro na API DeepSeek: HTTP {} - {}", response.statusCode(), response.body());
				return null;
			}
			return parseResponse(response.body());
		} catch (Exception e) {
			Console.error("Erro ao chamar DeepSeek API: {}", e);
			return null;
		}
	}

	/**
	 * [PT] Envia instrução de sistema e mensagem do usuário com streaming (resposta
	 * em tempo real).
	 *
	 * [EN] Sends system instruction and user message with streaming (real-time
	 * response).
	 *
	 * @param systemInstruction [PT] instrução de sistema (comportamento do
	 *                          assistente) [EN] system instruction (assistant
	 *                          behavior)
	 * @param userMessage       [PT] mensagem do usuário [EN] user message
	 * @param onChunk           [PT] callback que recebe cada pedaço de texto
	 *                          (chunk) [EN] callback that receives each text chunk
	 */
	public static void askStream(String systemInstruction, String userMessage, Consumer<String> onChunk) {
		if (!initialized && !initialize())
			return;

		try {
			String jsonBody = buildRequestBody(systemInstruction, userMessage, true);
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_URL))
					.timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).header("Content-Type", "application/json")
					.header("Authorization", "Bearer " + apiKey).POST(HttpRequest.BodyPublishers.ofString(jsonBody))
					.build();

			HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				Console.error("Erro na API DeepSeek (stream): HTTP {}", response.statusCode());
				return;
			}

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("data: ")) {
						String data = line.substring(6).trim();
						if ("[DONE]".equals(data))
							break;
						String chunk = parseStreamChunk(data);
						if (chunk != null && !chunk.isEmpty()) {
							onChunk.accept(chunk);
						}
					}
				}
			}
		} catch (Exception e) {
			Console.error("Erro no streaming da DeepSeek: {}", e);
		}
	}

	// ==================== MÉTODOS INTERNOS ====================

	private static String buildRequestBody(String systemInstruction, String userMessage, boolean stream) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("temperature", DEFAULT_TEMPERATURE);
		body.addProperty("stream", stream);

		JsonArray messages = new JsonArray();

		// Instrução de sistema (se fornecida)
		if (systemInstruction != null && !systemInstruction.trim().isEmpty()) {
			JsonObject system = new JsonObject();
			system.addProperty("role", "system");
			system.addProperty("content", systemInstruction);
			messages.add(system);
		}

		// Mensagem do usuário
		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", userMessage);
		messages.add(user);

		body.add("messages", messages);
		return gson.toJson(body);
	}

	private static String parseResponse(String json) {
		try {
			JsonObject obj = gson.fromJson(json, JsonObject.class);
			JsonArray choices = obj.getAsJsonArray("choices");
			if (choices != null && choices.size() > 0) {
				JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
				if (message != null && message.has("content")) {
					return message.get("content").getAsString();
				}
			}
		} catch (Exception e) {
			Console.error("Erro ao parsear resposta DeepSeek: {}", e);
		}
		return null;
	}

	private static String parseStreamChunk(String chunkJson) {
		try {
			if (chunkJson == null || chunkJson.isEmpty())
				return null;
			JsonObject obj = gson.fromJson(chunkJson, JsonObject.class);
			JsonArray choices = obj.getAsJsonArray("choices");
			if (choices != null && choices.size() > 0) {
				JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
				if (delta != null && delta.has("content")) {
					return delta.get("content").getAsString();
				}
			}
		} catch (Exception e) {
			// Ignorar erros de parse em chunks parciais
		}
		return null;
	}
}