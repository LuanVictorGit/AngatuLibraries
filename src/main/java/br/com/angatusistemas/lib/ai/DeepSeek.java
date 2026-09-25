package br.com.angatusistemas.lib.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.env.Env;

/**
 * Cliente para a API da DeepSeek com suporte a instrução de sistema por chamada.
 *
 * <p>Permite definir dinamicamente o comportamento do assistente (instrução de sistema) e enviar
 * a mensagem do usuário em uma única chamada.</p>
 *
 * <p>Exemplo:</p>
 *
 * <pre>
 * // Inicialização automática com a chave do .env
 * DeepSeek.initialize();
 *
 * // Enviar instrução + mensagem do usuário
 * String resposta = DeepSeek.ask("Você deve chamar o usuário sempre amorosamente", "Olá, tudo bem?");
 * System.out.println(resposta);
 *
 * // Com streaming, sabendo se a resposta chegou inteira
 * DeepSeek.askStream("Responda em português de forma criativa", "Crie uma história sobre um robô",
 * 		chunk -&gt; System.out.print(chunk),
 * 		() -&gt; System.out.println("\n[fim]"),
 * 		erro -&gt; System.out.println("\n[resposta incompleta: " + erro.getMessage() + "]"));
 * </pre>
 *
 * <p><strong>Prazos:</strong> {@link #ask} espera no máximo 300 s pela resposta inteira; em
 * {@link #askStream}, a resposta precisa começar em 300 s e o stream é encerrado se ficar 120 s
 * sem receber nada. O timeout do {@code HttpClient} do JDK cobre só a chegada dos cabeçalhos — sem
 * esses prazos próprios, um corpo que parasse no meio prenderia a thread para sempre.</p>
 *
 * <p><strong>Threads:</strong> a vigilância do stream usa uma única thread daemon
 * ({@code Angatu-DeepSeek-Watchdog}), criada no primeiro stream.</p>
 *
 * @author Angatu Sistemas
 * @see <a href="https://platform.deepseek.com/api-docs/">DeepSeek API Docs</a>
 */
public final class DeepSeek {

	private static final URI API_URI = URI.create("https://api.deepseek.com/v1/chat/completions");
	/** Prazo total de {@link #ask} e prazo para o stream começar em {@link #askStream}. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
	/**
	 * Tempo máximo sem nenhum byte no meio de um stream. A DeepSeek manda linhas de keep-alive
	 * enquanto a requisição espera na fila, e toda linha conta como atividade; 120 s parados
	 * significam conexão morta, não resposta demorada.
	 */
	private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(120);
	/** Intervalo entre releituras da chave no {@code .env} quando ela está ausente. */
	private static final long KEY_RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final double DEFAULT_TEMPERATURE = 1.0;
	/** Tamanho máximo do corpo de erro copiado para o log. */
	private static final int ERROR_SNIPPET_LENGTH = 500;

	/** Classe do Gson usada para detectar a dependência. */
	private static final String GSON_CLASS = "com.google.gson.Gson";
	/** Coordenadas Maven da dependência Gson. */
	private static final String GSON_COORDINATES = "com.google.code.gson:gson:2.13.2";
	/** Nome da funcionalidade para mensagens de dependência ausente. */
	private static final String AI_FEATURE = "IA (DeepSeek)";

	/*
	 * Estado compartilhado entre as threads que chamam a API: volatile para que a chave definida por
	 * uma thread seja vista pelas demais sem trava. A chave presente é o que significa
	 * "inicializado".
	 */
	private static volatile String apiKey;
	private static volatile String model = "deepseek-chat";
	/**
	 * Próximo instante (System.nanoTime) em que uma chamada pode reler a chave do {@code .env}.
	 * Sem isto, cada ask/askStream sem chave entrava no {@code initialize()} sincronizado e
	 * escrevia um erro no console — sob carga, uma fila de threads numa trava e um log inundado.
	 */
	private static volatile long nextKeyLookupNanos = System.nanoTime();

	/**
	 * Cliente HTTP compartilhado, criado só na primeira chamada à API. Construir um
	 * {@link HttpClient} abre a thread de seletor dele, e isso não deve acontecer só porque alguém
	 * chamou {@link #setModel} ou {@link #initialize}; e, onde o seletor não pode ser aberto
	 * (sandbox), a falha fica na chamada à API em vez de inutilizar a classe inteira.
	 */
	private static final class Http {
		static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	private DeepSeek() {
		throw new UnsupportedOperationException("Classe utilitária não pode ser instanciada");
	}

	// ==================== INICIALIZAÇÃO ====================

	/**
	 * Inicializa o cliente com a chave da API lida do arquivo .env ({@code DEEPSEEK_API_KEY}).
	 *
	 * <p>Chamado sem chave configurada, registra o erro e devolve {@code false}. As chamadas de
	 * {@link #ask}/{@link #askStream} feitas sem inicialização tentam reler a chave no máximo a
	 * cada 30 s — entre uma tentativa e outra devolvem falha na hora, sem travar nem escrever no
	 * console. Esta chamada explícita sempre relê.</p>
	 *
	 * @return {@code true} se inicializado com sucesso, {@code false} caso contrário
	 */
	public static synchronized boolean initialize() {
		if (apiKey != null)
			return true;
		String key = Env.get().get("DEEPSEEK_API_KEY");
		if (key == null || key.isBlank()) {
			nextKeyLookupNanos = System.nanoTime() + KEY_RETRY_INTERVAL_NANOS;
			Console.error("DeepSeek: chave da API não configurada. Adicione DEEPSEEK_API_KEY no .env");
			return false;
		}
		apiKey = key.strip();
		Console.log("DeepSeek inicializado com o modelo: " + model);
		return true;
	}

	/**
	 * Inicializa o cliente com a chave e o modelo fornecidos.
	 *
	 * <p>Chave nula ou vazia é ignorada (com erro no console) e o cliente continua como estava —
	 * antes ela marcava o cliente como inicializado e toda requisição saía com
	 * {@code Bearer null}.</p>
	 *
	 * @param apiKey chave da API DeepSeek
	 * @param model  nome do modelo (ex: "deepseek-chat"; nulo ou vazio mantém o atual)
	 */
	public static synchronized void initialize(String apiKey, String model) {
		if (apiKey == null || apiKey.isBlank()) {
			Console.error("DeepSeek: chave da API vazia — inicialização ignorada.");
			return;
		}
		if (model != null && !model.isBlank()) {
			DeepSeek.model = model.strip();
		}
		DeepSeek.apiKey = apiKey.strip();
		Console.log("DeepSeek inicializado com o modelo: " + DeepSeek.model);
	}

	/**
	 * Define o modelo padrão (caso não seja fornecido na inicialização).
	 *
	 * @param model nome do modelo (nulo ou vazio é ignorado)
	 */
	public static void setModel(String model) {
		if (model == null || model.isBlank()) {
			Console.warn("DeepSeek: nome de modelo vazio ignorado.");
			return;
		}
		DeepSeek.model = model.strip();
	}

	// ==================== MÉTODOS PRINCIPAIS ====================

	/**
	 * Envia uma instrução de sistema e uma mensagem do usuário, retornando a resposta completa
	 * (síncrono).
	 *
	 * <p>Espera no máximo 300 s pela resposta inteira (cabeçalhos <em>e</em> corpo); passado o
	 * prazo, a requisição é cancelada e o método devolve {@code null}. Se a thread for interrompida,
	 * a requisição é cancelada, o sinal de interrupção é mantido e o método devolve
	 * {@code null}.</p>
	 *
	 * @param systemInstruction instrução que define o comportamento do assistente (pode ser
	 *                          {@code null} ou vazia)
	 * @param userMessage       texto enviado pelo usuário
	 * @return resposta do assistente ou {@code null} em caso de erro
	 */
	public static String ask(String systemInstruction, String userMessage) {
		Dependencies.require(GSON_CLASS, GSON_COORDINATES, AI_FEATURE);
		String key = keyForCall();
		if (key == null)
			return null;
		return exchange(Http.CLIENT, API_URI, key, model, systemInstruction, userMessage, REQUEST_TIMEOUT);
	}

	/**
	 * Envia instrução de sistema e mensagem do usuário com streaming (resposta em tempo real).
	 *
	 * <p>Falhas são registradas no console. Este método não diz se a resposta chegou inteira: um
	 * stream cortado no meio (queda de rede, 120 s sem dados) termina do mesmo jeito que um
	 * completo. Para distinguir os dois, use
	 * {@link #askStream(String, String, Consumer, Runnable, Consumer)}.</p>
	 *
	 * @param systemInstruction instrução de sistema (comportamento do assistente)
	 * @param userMessage       mensagem do usuário
	 * @param onChunk           callback que recebe cada pedaço de texto (chunk)
	 */
	public static void askStream(String systemInstruction, String userMessage, Consumer<String> onChunk) {
		askStream(systemInstruction, userMessage, onChunk, null, null);
	}

	/**
	 * Envia instrução de sistema e mensagem do usuário com streaming, informando como o stream
	 * terminou.
	 *
	 * <p>Síncrono: retorna depois do fim do stream. Exatamente um dos dois callbacks finais é
	 * chamado, uma única vez, na thread que chamou este método:</p>
	 * <ul>
	 *   <li>{@code onComplete} — a DeepSeek sinalizou o fim da resposta ({@code [DONE]});</li>
	 *   <li>{@code onError} — qualquer outro desfecho: chave não configurada, HTTP diferente de
	 *       200, resposta que não começou em 300 s, 120 s sem dados no meio do stream, conexão
	 *       encerrada antes do {@code [DONE]}, exceção lançada pelo {@code onChunk} ou thread
	 *       interrompida (o sinal de interrupção é mantido).</li>
	 * </ul>
	 *
	 * <p>Os pedaços recebidos antes de uma falha já foram entregues ao {@code onChunk}; é por isso
	 * que a falha precisa ser avisada — sem ela, uma resposta cortada pela metade parece
	 * completa.</p>
	 *
	 * @param systemInstruction instrução de sistema (pode ser {@code null} ou vazia)
	 * @param userMessage       mensagem do usuário
	 * @param onChunk           recebe cada pedaço de texto
	 * @param onComplete        chamado quando a resposta chegou inteira ({@code null} ignora)
	 * @param onError           chamado com a causa quando não chegou ({@code null} registra no
	 *                          console)
	 * @throws IllegalArgumentException se {@code onChunk} for nulo
	 */
	public static void askStream(String systemInstruction, String userMessage, Consumer<String> onChunk,
			Runnable onComplete, Consumer<Throwable> onError) {
		Dependencies.require(GSON_CLASS, GSON_COORDINATES, AI_FEATURE);
		if (onChunk == null) {
			throw new IllegalArgumentException("DeepSeek: o callback onChunk não pode ser nulo.");
		}
		String key = keyForCall();
		if (key == null) {
			// Na sobrecarga antiga (onError nulo) o motivo já foi registrado pelo initialize();
			// repetir aqui a cada chamada inundaria o console.
			if (onError != null) {
				onError.accept(new IllegalStateException(
						"DeepSeek não inicializado: defina DEEPSEEK_API_KEY no .env ou chame DeepSeek.initialize(chave, modelo)."));
			}
			return;
		}
		stream(Http.CLIENT, API_URI, key, model, systemInstruction, userMessage, REQUEST_TIMEOUT, STREAM_IDLE_TIMEOUT,
				onChunk, onComplete, onError);
	}

	// ==================== MÉTODOS INTERNOS ====================

	/**
	 * Chave para uma chamada, sem passar pela trava quando já há chave — e sem reler o
	 * {@code .env} (nem registrar erro) mais de uma vez a cada 30 s quando não há.
	 */
	private static String keyForCall() {
		String key = apiKey;
		if (key != null)
			return key;
		if (System.nanoTime() - nextKeyLookupNanos < 0)
			return null;
		return initialize() ? apiKey : null;
	}

	/**
	 * Uma requisição sem streaming, com prazo total.
	 *
	 * <p>{@code sendAsync(...).get(prazo)} e não {@code send}: o timeout do {@link HttpRequest}
	 * termina quando chegam os cabeçalhos, e o {@code send} esperava o corpo sem prazo.</p>
	 */
	static String exchange(HttpClient client, URI endpoint, String key, String modelName, String systemInstruction,
			String userMessage, Duration timeout) {
		HttpRequest request = newRequest(endpoint, key,
				JsonSupport.buildRequestBody(systemInstruction, userMessage, false, modelName), timeout);
		CompletableFuture<HttpResponse<String>> future = client.sendAsync(request,
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		try {
			HttpResponse<String> response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (response.statusCode() != 200) {
				Console.error("Erro na API DeepSeek: HTTP %d - %s", Integer.valueOf(response.statusCode()),
						safeSnippet(response.body()));
				return null;
			}
			return JsonSupport.parseResponse(response.body());
		} catch (TimeoutException e) {
			future.cancel(true);
			Console.error("A DeepSeek não respondeu por completo em %d s; a requisição foi cancelada.",
					Long.valueOf(timeout.toSeconds()));
			return null;
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			Console.warn("Chamada à DeepSeek interrompida.");
			return null;
		} catch (ExecutionException e) {
			Console.error("Erro ao chamar a API da DeepSeek", e.getCause());
			return null;
		}
	}

	/**
	 * Uma requisição com streaming SSE: prazo para começar, vigilância de inatividade e aviso
	 * explícito de como terminou (ver
	 * {@link #askStream(String, String, Consumer, Runnable, Consumer)}).
	 */
	static void stream(HttpClient client, URI endpoint, String key, String modelName, String systemInstruction,
			String userMessage, Duration startTimeout, Duration idleTimeout, Consumer<String> onChunk,
			Runnable onComplete, Consumer<Throwable> onError) {
		HttpRequest request = newRequest(endpoint, key,
				JsonSupport.buildRequestBody(systemInstruction, userMessage, true, modelName), startTimeout);
		CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(request,
				HttpResponse.BodyHandlers.ofInputStream());
		HttpResponse<InputStream> response;
		try {
			response = future.get(startTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			report(onError, new IOException("A DeepSeek não começou a responder em " + startTimeout.toSeconds() + " s."));
			return;
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			report(onError, e);
			return;
		} catch (ExecutionException e) {
			report(onError, e.getCause());
			return;
		}

		Throwable failure = null;
		IdleWatchdog watchdog = null;
		// try-with-resources no corpo: fechar o InputStream cancela a assinatura e libera a
		// conexão — inclusive quando o status não é 200, caso em que antes o corpo ficava aberto.
		try (InputStream body = response.body()) {
			watchdog = IdleWatchdog.watch(body, idleTimeout);
			if (response.statusCode() != 200) {
				failure = new IOException("A API da DeepSeek respondeu HTTP " + response.statusCode() + " - "
						+ safeSnippet(readSnippet(body)));
			} else {
				failure = readEvents(body, watchdog, onChunk);
			}
		} catch (IOException e) {
			failure = e;
		} catch (RuntimeException e) {
			failure = e; // lançada pelo onChunk: o stream é abandonado (e fechado) aqui
		} finally {
			if (watchdog != null) {
				watchdog.stop();
			}
		}

		if (failure instanceof IOException && response.statusCode() == 200 && watchdog != null && watchdog.fired()) {
			failure = new IOException("A DeepSeek ficou " + idleTimeout.toSeconds()
					+ " s sem enviar dados; o stream foi encerrado antes do fim da resposta.", failure);
		}
		if (failure == null) {
			if (onComplete != null) {
				onComplete.run();
			}
		} else {
			report(onError, failure);
		}
	}

	/**
	 * Lê os eventos SSE até o {@code [DONE]}.
	 *
	 * @return {@code null} se a resposta terminou com {@code [DONE]}; a falha, caso contrário
	 */
	private static Throwable readEvents(InputStream body, IdleWatchdog watchdog, Consumer<String> onChunk)
			throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			watchdog.touch();
			String data = sseData(line);
			if (data == null)
				continue;
			if ("[DONE]".equals(data))
				return null;
			String chunk = JsonSupport.parseStreamChunk(data);
			if (chunk != null && !chunk.isEmpty()) {
				onChunk.accept(chunk);
			}
		}
		return new IOException("A conexão com a DeepSeek terminou antes do fim da resposta (sem [DONE]).");
	}

	/**
	 * Conteúdo de uma linha {@code data:} do SSE, ou {@code null} para as demais (comentários de
	 * keep-alive, linhas vazias, outros campos). O espaço depois dos dois-pontos é opcional no SSE.
	 */
	static String sseData(String line) {
		if (line == null || !line.startsWith("data:"))
			return null;
		return line.substring(5).trim();
	}

	/** Primeiros bytes de um corpo de erro; ilegível vira texto vazio. */
	private static String readSnippet(InputStream body) {
		try {
			return new String(body.readNBytes(2048), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Trecho de uma linha para log, sem nada que pareça chave de API — provedores costumam ecoar a
	 * chave (mesmo que parcialmente) na mensagem de "chave inválida".
	 */
	static String safeSnippet(String text) {
		if (text == null)
			return "";
		String oneLine = text.replaceAll("sk-[A-Za-z0-9_\\-*]+", "sk-***").replaceAll("\\p{Cntrl}+", " ")
				.replaceAll("\\s{2,}", " ").strip();
		return oneLine.length() <= ERROR_SNIPPET_LENGTH ? oneLine : oneLine.substring(0, ERROR_SNIPPET_LENGTH) + "…";
	}

	private static void report(Consumer<Throwable> onError, Throwable failure) {
		if (onError != null) {
			onError.accept(failure);
		} else {
			Console.error("Erro no streaming da DeepSeek", failure);
		}
	}

	private static HttpRequest newRequest(URI endpoint, String key, String jsonBody, Duration timeout) {
		return HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + key)
				.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build();
	}

	/**
	 * Fecha o corpo de um stream que ficou tempo demais sem receber nada.
	 *
	 * <p>Fechar o {@code InputStream} do {@code HttpClient} de outra thread acorda a leitura
	 * bloqueada, que termina com {@link IOException}; a vigilância marca que foi ela, para a falha
	 * ser relatada como inatividade e não como erro de rede qualquer.</p>
	 */
	private static final class IdleWatchdog {

		/** Uma thread daemon com nome para todos os streams; tarefas canceladas saem da fila. */
		private static final ScheduledThreadPoolExecutor TIMER = createTimer();

		private final InputStream target;
		private final long idleNanos;
		private volatile long lastActivityNanos = System.nanoTime();
		private volatile boolean fired;
		private volatile ScheduledFuture<?> task;

		private IdleWatchdog(InputStream target, Duration idle) {
			this.target = target;
			this.idleNanos = idle.toNanos();
		}

		private static ScheduledThreadPoolExecutor createTimer() {
			ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
				Thread thread = new Thread(runnable, "Angatu-DeepSeek-Watchdog");
				thread.setDaemon(true);
				return thread;
			});
			timer.setRemoveOnCancelPolicy(true);
			return timer;
		}

		static IdleWatchdog watch(InputStream target, Duration idle) {
			IdleWatchdog watchdog = new IdleWatchdog(target, idle);
			long periodMillis = Math.max(50L, Math.min(1_000L, idle.toMillis() / 4));
			watchdog.task = TIMER.scheduleWithFixedDelay(watchdog::check, periodMillis, periodMillis,
					TimeUnit.MILLISECONDS);
			return watchdog;
		}

		void touch() {
			lastActivityNanos = System.nanoTime();
		}

		boolean fired() {
			return fired;
		}

		void stop() {
			ScheduledFuture<?> current = task;
			if (current != null) {
				current.cancel(false);
			}
		}

		private void check() {
			if (System.nanoTime() - lastActivityNanos < idleNanos) {
				return;
			}
			fired = true;
			stop();
			try {
				target.close();
			} catch (IOException ignored) {
				// Já fechado: a leitura termina de qualquer forma.
			}
		}
	}

	// ==================== IMPLEMENTAÇÃO JSON (GSON — LAZY) ====================

	/**
	 * Implementação de serialização/parsing com Gson. Classe separada para
	 * manter as referências ao Gson fora do bytecode da {@link DeepSeek} — a
	 * classe pública pode ser vinculada sem o gson e o guard exibe a mensagem
	 * de instalação antes de qualquer uso.
	 */
	private static final class JsonSupport {

		/** Holder lazy: evita resolver o Gson no classload. */
		private static final Gson INSTANCE = new GsonBuilder().create();

		private JsonSupport() {
		}

		static String buildRequestBody(String systemInstruction, String userMessage, boolean stream, String model) {
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
			return INSTANCE.toJson(body);
		}

		static String parseResponse(String json) {
			try {
				JsonObject obj = INSTANCE.fromJson(json, JsonObject.class);
				JsonArray choices = obj.getAsJsonArray("choices");
				if (choices != null && choices.size() > 0) {
					JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
					if (message != null && message.has("content")) {
						return message.get("content").getAsString();
					}
				}
			} catch (Exception e) {
				Console.error("Erro ao interpretar a resposta da DeepSeek", e);
			}
			return null;
		}

		static String parseStreamChunk(String chunkJson) {
			try {
				if (chunkJson == null || chunkJson.isEmpty())
					return null;
				JsonObject obj = INSTANCE.fromJson(chunkJson, JsonObject.class);
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
}
