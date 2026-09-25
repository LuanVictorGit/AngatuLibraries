package br.com.angatusistemas.lib.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.dependencies.Dependencies;
import br.com.angatusistemas.lib.env.Env;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.common.IdentificationRequest;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.payment.PaymentCreateRequest;
import com.mercadopago.client.payment.PaymentPayerRequest;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePayerRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.Headers;
import com.mercadopago.net.HttpMethod;
import com.mercadopago.net.MPRequest;
import com.mercadopago.net.MPResponse;
import com.mercadopago.net.MPResultsResourcesPage;
import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.mercadopago.serialization.Serializer;

/**
 * Classe utilitária para integração com o SDK oficial do Mercado Pago
 * (Java SDK 2.9.2).
 *
 * <p><strong>Propósito:</strong> abstrair o SDK do Mercado Pago em chamadas
 * simples: criação de pagamentos (PIX, boleto, cartão), consultas, preferências
 * de checkout e processamento de webhooks, com DTOs próprios
 * ({@link PaymentDTO}, {@link PreferenceDTO}).</p>
 *
 * <p><strong>Quando usar:</strong> em aplicações que precisam receber
 * pagamentos via Mercado Pago (PIX, boleto, cartão) ou redirecionar para o
 * checkout.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> sem um Access Token válido do painel do
 * Mercado Pago nada funciona; para fluxos muito customizados use o SDK
 * diretamente.</p>
 *
 * <p><strong>Falha nunca se disfarça de "não existe":</strong> até a versão anterior, timeout,
 * token inválido e erro 500 viravam {@code null}, {@code Optional.empty()} ou
 * {@link PaymentStatus#UNKNOWN} — exatamente o que um pagamento inexistente devolve. Num webhook
 * de pagamento aprovado que chegava com a API instável, a aplicação respondia 200, o Mercado Pago
 * não reenviava a notificação e o pedido pago nunca era liberado. Agora só o HTTP 404 quer dizer
 * "não existe"; qualquer outra falha sobe:</p>
 * <ul>
 *   <li>os métodos que declaram {@code throws MPException} lançam {@link MPException} com mensagem
 *       em português; quando a falha veio da API, {@code getCause()} é a {@link MPApiException} do
 *       SDK, com {@code getStatusCode()} e {@code getApiResponse()};</li>
 *   <li>{@link #findById(Long)}, que não declara exceção verificada, lança
 *       {@link MercadoPagoException}, que é não verificada e traz o status HTTP.</li>
 * </ul>
 * <p>O SDK 2.9.2 relata falha de rede, timeout e erro de TLS como resposta HTTP 500, 400 ou 403
 * <em>sem corpo</em>: trate 5xx e respostas sem corpo como falha temporária.</p>
 *
 * <p><strong>Idempotência (cobrança dupla):</strong> os métodos de criação aceitam do chamador uma
 * chave de idempotência ({@code X-Idempotency-Key}): a mesma chave nunca cobra duas vezes. No
 * <strong>cartão</strong>, quando o chamador não informa uma, ela é derivada dos dados da cobrança
 * sempre que houver {@code externalReference} — repetir a mesma cobrança depois de um timeout não
 * cobra de novo. No PIX, no boleto e no pagamento com metadados, cada chamada sem chave é uma
 * cobrança nova, como sempre foi: ali uma cobrança a mais só expira sem pagamento, e gerar outro PIX
 * para um pedido cujo PIX venceu precisa continuar funcionando. Os detalhes estão em cada método.</p>
 *
 * <p><strong>Integração:</strong> usa {@link Console} para logs, {@link Dependencies} para
 * verificar a presença do SDK e {@link Env} para ler o {@code MP_ACCESS_TOKEN} em
 * {@link #initFromEnv()} (variável de ambiente ou {@code .env}).</p>
 *
 * <p><strong>Fluxo de utilização:</strong></p>
 * <ol>
 *   <li>{@code MercadoPagoAPI.init(accessToken)} (ou {@code initFromEnv()});</li>
 *   <li>Crie o pagamento ({@link #createPixPayment}, {@link #createBoletoPayment},
 *       {@link #createCreditCardPayment}) — PIX/boleto ficam {@code pending}
 *       até o pagador quitar;</li>
 *   <li>Verifique o status ({@link #isApproved}, {@link #checkPaymentStatus})
 *       ou processe o webhook ({@link #validateWebhookSignature} +
 *       {@link #processWebhook});</li>
 *   <li>Para checkout hospedado, use {@link #createPreference}.</li>
 * </ol>
 *
 * <p><strong>Exemplo:</strong></p>
 * <pre>
 * MercadoPagoAPI.initFromEnv();
 * PaymentDTO pix = MercadoPagoAPI.createPixPayment(
 *         150.00, "cliente@email.com", "Pedido #1234", "order-1234");
 * System.out.println(pix.getPixCopiaECola());
 * </pre>
 *
 * <p><strong>Boas práticas:</strong> guarde o token em variável de ambiente
 * (nunca no código); valide a assinatura do webhook antes de processar;
 * monitore {@code payment.getStatus()} em fluxos PIX/boleto (aprovação é
 * assíncrona); no webhook, responda erro (não 200) quando {@link #processWebhook}
 * lançar exceção — é isso que faz o Mercado Pago reenviar a notificação.</p>
 *
 * <p><strong>Limitações:</strong> requer {@code com.mercadopago:sdk-java:2.9.2} —
 * a classe é detectável (linkável) sem ela e o guard exibe instruções de
 * instalação; valores monetários são arredondados para 2 casas (reais e centavos).</p>
 *
 * <p><strong>Extensões futuras:</strong> cancelamento/reembolso
 * ({@code cancelPayment}, {@code refundPayment}) são adições naturais, sem
 * quebrar a API atual.</p>
 *
 * @author Angatu Sistemas
 * @version 1.0
 * @see PaymentDTO
 * @see PreferenceDTO
 */
public final class MercadoPagoAPI {

	/** Classe do SDK usada para detectar a dependência. */
	private static final String SDK_CLASS = "com.mercadopago.MercadoPagoConfig";
	/** Coordenadas Maven da dependência do SDK. */
	private static final String SDK_COORDINATES = "com.mercadopago:sdk-java:2.9.2";
	/** Nome da funcionalidade para mensagens de dependência ausente. */
	private static final String PAYMENTS_FEATURE = "Pagamentos (Mercado Pago)";
	/** Classe do dotenv-java: com ela presente, {@link #initFromEnv()} também lê o {@code .env}. */
	private static final String DOTENV_CLASS = "io.github.cdimascio.dotenv.Dotenv";

	// ─────────────────────────────────────────────────────────────────────────
	// Constantes
	// ─────────────────────────────────────────────────────────────────────────

	/** Tempo padrão de conexão em milissegundos (10 segundos). */
	private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 10_000;

	/** Tempo padrão de leitura em milissegundos (30 segundos). */
	private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

	/** Casas decimais do real: o valor enviado ao Mercado Pago sempre sai em reais e centavos. */
	private static final int MONEY_SCALE = 2;

	/**
	 * Tolerância padrão do carimbo de tempo ({@code ts}) do webhook: desligada ({@link Duration#ZERO}).
	 *
	 * <p>O Mercado Pago reenvia a notificação que não recebeu 200 — 15 minutos, 30 minutos, 6 horas,
	 * 48 horas e 96 horas depois da primeira — e a documentação oficial não diz que o reenvio ganha um
	 * {@code ts} novo; os cinco passos de validação dela não conferem o tempo. Uma janela curta por
	 * padrão recusaria justamente os reenvios, e o pagamento confirmado nunca chegaria ao sistema. A
	 * assinatura é conferida sempre; repetir uma notificação capturada só faz o sistema consultar de
	 * novo o pagamento. Quem quiser a janela liga com {@link #setWebhookTolerance(Duration)}, depois de
	 * ver no sandbox como os reenvios chegam.</p>
	 */
	public static final Duration DEFAULT_WEBHOOK_TOLERANCE = Duration.ZERO;

	/**
	 * A partir deste valor, o {@code ts} do webhook é lido como milissegundos; abaixo, como
	 * segundos. Um número de <em>segundos</em> só chega a 10<sup>11</sup> no ano 5138 e um de
	 * <em>milissegundos</em> passou disso em 1973 — para datas reais não há ambiguidade, e o
	 * código não depende de qual das duas unidades o Mercado Pago usa.
	 */
	private static final long TIMESTAMP_MILLIS_THRESHOLD = 100_000_000_000L;

	/** Prefixo versionado da chave de idempotência derivada: mudar a derivação exige mudar isto. */
	private static final String IDEMPOTENCY_DERIVATION = "angatu-mp-payment-v1";

	/** Tamanho máximo do trecho da resposta da API copiado para a mensagem de erro. */
	private static final int ERROR_SNIPPET_LENGTH = 500;

	/** Tolerância em uso para o {@code ts} do webhook (ver {@link #setWebhookTolerance}). */
	private static volatile Duration webhookTolerance = DEFAULT_WEBHOOK_TOLERANCE;

	/** Construtor privado — classe utilitária, não deve ser instanciada. */
	private MercadoPagoAPI() {
		throw new UnsupportedOperationException("Classe utilitária — não instanciar.");
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Inicialização
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Inicializa o SDK com o Access Token fornecido, usando timeouts padrão
	 * (10 s de conexão, 30 s de leitura).
	 *
	 * <p><strong>Pré-condições:</strong> dependência sdk-java no classpath
	 * (verificada com mensagem de instalação se ausente).</p>
	 *
	 * <p><strong>Pós-condições:</strong> SDK configurado; os métodos de
	 * pagamento passam a funcionar.</p>
	 *
	 * @param accessToken Access Token do Mercado Pago (obrigatório, não nulo/vazio)
	 * @throws IllegalArgumentException se o token for nulo ou vazio
	 */
	public static void init(String accessToken) {
		init(accessToken, DEFAULT_CONNECTION_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
	}

	/**
	 * Inicializa o SDK com Access Token e timeouts personalizados.
	 *
	 * <p>{@code connectionTimeoutMs} vale para abrir a conexão e também para esperar uma conexão
	 * livre no pool do SDK; {@code readTimeoutMs} é o tempo máximo sem receber dados de uma
	 * resposta.</p>
	 *
	 * @param accessToken         Access Token do Mercado Pago
	 * @param connectionTimeoutMs Timeout de conexão em milissegundos (maior que zero)
	 * @param readTimeoutMs       Timeout de leitura em milissegundos (maior que zero)
	 * @throws IllegalArgumentException se o token for nulo ou vazio, ou se algum timeout não for
	 *                                  positivo (zero, para o SDK, é "esperar para sempre")
	 */
	public static void init(String accessToken, int connectionTimeoutMs, int readTimeoutMs) {
		checkDependencies();
		validateToken(accessToken);
		if (connectionTimeoutMs <= 0 || readTimeoutMs <= 0) {
			throw new IllegalArgumentException(
					"[MercadoPagoAPI] Os timeouts precisam ser maiores que zero — zero faz o SDK esperar para sempre.");
		}
		MpSupport.init(accessToken, connectionTimeoutMs, readTimeoutMs);
		Console.log("[MercadoPagoAPI] SDK inicializado (conexão %d ms, leitura %d ms).",
				Integer.valueOf(connectionTimeoutMs), Integer.valueOf(readTimeoutMs));
	}

	/**
	 * Inicializa o SDK lendo o Access Token de {@code MP_ACCESS_TOKEN}.
	 *
	 * <p>O valor vem do {@link Env} da biblioteca — variável de ambiente do sistema e, em
	 * desenvolvimento, o arquivo {@code .env}. Sem o dotenv-java no classpath, lê só a variável
	 * de ambiente do sistema, como antes.</p>
	 *
	 * @throws IllegalStateException se {@code MP_ACCESS_TOKEN} não estiver definida
	 */
	public static void initFromEnv() {
		checkDependencies();
		String token = Dependencies.isPresent(DOTENV_CLASS) ? Env.get().get("MP_ACCESS_TOKEN")
				: System.getenv("MP_ACCESS_TOKEN");
		if (token == null || token.isBlank()) {
			throw new IllegalStateException(
					"[MercadoPagoAPI] MP_ACCESS_TOKEN não está definida (nem no ambiente, nem no .env).");
		}
		init(token.strip());
	}

	// ─────────────────────────────────────────────────────────────────────────
	// DTOs e tipos públicos
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * DTO (Data Transfer Object) que representa de forma simplificada um pagamento
	 * do Mercado Pago. Encapsula os campos mais relevantes retornados pela API.
	 */
	public static final class PaymentDTO {

		private final Long id;
		private final String status;
		private final String statusDetail;
		private final String paymentMethodId;
		private final BigDecimal transactionAmount;
		private final String externalReference;
		private final String description;
		private final String pixCopyPasteCode;
		private final String pixQrCodeBase64;
		private final String boletoUrl;
		private final String checkoutUrl;
		private final OffsetDateTime dateCreated;
		private final OffsetDateTime dateApproved;

		private PaymentDTO(Builder b) {
			this.id = b.id;
			this.status = b.status;
			this.statusDetail = b.statusDetail;
			this.paymentMethodId = b.paymentMethodId;
			this.transactionAmount = b.transactionAmount;
			this.externalReference = b.externalReference;
			this.description = b.description;
			this.pixCopyPasteCode = b.pixCopyPasteCode;
			this.pixQrCodeBase64 = b.pixQrCodeBase64;
			this.boletoUrl = b.boletoUrl;
			this.checkoutUrl = b.checkoutUrl;
			this.dateCreated = b.dateCreated;
			this.dateApproved = b.dateApproved;
		}

		/**
		 * ID do pagamento no Mercado Pago.
		 *
		 * @return ID do pagamento
		 */
		public Long getId() {
			return id;
		}

		/**
		 * Status do pagamento.
		 *
		 * @return Status (approved, pending, rejected, cancelled, refunded, charged_back…)
		 */
		public String getStatus() {
			return status;
		}

		/**
		 * Detalhe do status (motivo da recusa, por exemplo).
		 *
		 * @return Detalhe do status
		 */
		public String getStatusDetail() {
			return statusDetail;
		}

		/**
		 * Método de pagamento.
		 *
		 * @return Método (pix, credit_card, bolbradesco etc.)
		 */
		public String getPaymentMethodId() {
			return paymentMethodId;
		}

		/**
		 * Valor da transação.
		 *
		 * @return Valor da transação
		 */
		public BigDecimal getTransactionAmount() {
			return transactionAmount;
		}

		/**
		 * Referência externa (ID interno do sistema, normalmente o do pedido).
		 *
		 * @return Referência externa
		 */
		public String getExternalReference() {
			return externalReference;
		}

		/**
		 * Descrição do pagamento.
		 *
		 * @return Descrição
		 */
		public String getDescription() {
			return description;
		}

		/**
		 * Código PIX "copia e cola" (apenas para pagamentos PIX).
		 *
		 * @return Código PIX copia e cola, ou {@code null}
		 */
		public String getPixCopiaECola() {
			return pixCopyPasteCode;
		}

		/**
		 * QR Code PIX em Base64 (apenas para pagamentos PIX).
		 *
		 * @return Imagem do QR Code em Base64, ou {@code null}
		 */
		public String getPixQrCodeBase64() {
			return pixQrCodeBase64;
		}

		/**
		 * URL do boleto bancário.
		 *
		 * @return URL do boleto, ou {@code null}
		 */
		public String getBoletoUrl() {
			return boletoUrl;
		}

		/**
		 * URL de checkout (para preferências).
		 *
		 * @return URL de checkout, ou {@code null}
		 */
		public String getCheckoutUrl() {
			return checkoutUrl;
		}

		/**
		 * Data de criação do pagamento.
		 *
		 * @return Data de criação
		 */
		public OffsetDateTime getDateCreated() {
			return dateCreated;
		}

		/**
		 * Data de aprovação do pagamento.
		 *
		 * @return Data de aprovação, ou {@code null} se ainda não aprovado
		 */
		public OffsetDateTime getDateApproved() {
			return dateApproved;
		}

		/**
		 * Resumo legível do pagamento (sem dados do pagador).
		 *
		 * @return Texto com ID, status, método, valor e referência externa
		 */
		@Override
		public String toString() {
			return "PaymentDTO{id=" + id + ", status='" + status + "', method='" + paymentMethodId + "', amount="
					+ transactionAmount + ", externalRef='" + externalReference + "'}";
		}

		/** Builder para {@link PaymentDTO}. */
		public static final class Builder {
			private Long id;
			private String status;
			private String statusDetail;
			private String paymentMethodId;
			private BigDecimal transactionAmount;
			private String externalReference;
			private String description;
			private String pixCopyPasteCode;
			private String pixQrCodeBase64;
			private String boletoUrl;
			private String checkoutUrl;
			private OffsetDateTime dateCreated;
			private OffsetDateTime dateApproved;

			/** Cria um builder vazio. */
			public Builder() {
			}

			/**
			 * Define o ID do pagamento.
			 *
			 * @param id ID do pagamento no Mercado Pago
			 * @return este builder
			 */
			public Builder id(Long id) {
				this.id = id;
				return this;
			}

			/**
			 * Define o status.
			 *
			 * @param status Status do pagamento
			 * @return este builder
			 */
			public Builder status(String status) {
				this.status = status;
				return this;
			}

			/**
			 * Define o detalhe do status.
			 *
			 * @param statusDetail Detalhe do status
			 * @return este builder
			 */
			public Builder statusDetail(String statusDetail) {
				this.statusDetail = statusDetail;
				return this;
			}

			/**
			 * Define o método de pagamento.
			 *
			 * @param paymentMethodId Método de pagamento
			 * @return este builder
			 */
			public Builder paymentMethodId(String paymentMethodId) {
				this.paymentMethodId = paymentMethodId;
				return this;
			}

			/**
			 * Define o valor da transação.
			 *
			 * @param transactionAmount Valor da transação
			 * @return este builder
			 */
			public Builder transactionAmount(BigDecimal transactionAmount) {
				this.transactionAmount = transactionAmount;
				return this;
			}

			/**
			 * Define a referência externa.
			 *
			 * @param externalReference Referência externa
			 * @return este builder
			 */
			public Builder externalReference(String externalReference) {
				this.externalReference = externalReference;
				return this;
			}

			/**
			 * Define a descrição.
			 *
			 * @param description Descrição do pagamento
			 * @return este builder
			 */
			public Builder description(String description) {
				this.description = description;
				return this;
			}

			/**
			 * Define o código PIX "copia e cola".
			 *
			 * @param pixCopiaECola Código PIX copia e cola
			 * @return este builder
			 */
			public Builder pixCopiaECola(String pixCopiaECola) {
				this.pixCopyPasteCode = pixCopiaECola;
				return this;
			}

			/**
			 * Define o QR Code PIX em Base64.
			 *
			 * @param pixQrCodeBase64 QR Code em Base64
			 * @return este builder
			 */
			public Builder pixQrCodeBase64(String pixQrCodeBase64) {
				this.pixQrCodeBase64 = pixQrCodeBase64;
				return this;
			}

			/**
			 * Define a URL do boleto.
			 *
			 * @param boletoUrl URL do boleto
			 * @return este builder
			 */
			public Builder boletoUrl(String boletoUrl) {
				this.boletoUrl = boletoUrl;
				return this;
			}

			/**
			 * Define a URL de checkout.
			 *
			 * @param checkoutUrl URL de checkout
			 * @return este builder
			 */
			public Builder checkoutUrl(String checkoutUrl) {
				this.checkoutUrl = checkoutUrl;
				return this;
			}

			/**
			 * Define a data de criação.
			 *
			 * @param dateCreated Data de criação
			 * @return este builder
			 */
			public Builder dateCreated(OffsetDateTime dateCreated) {
				this.dateCreated = dateCreated;
				return this;
			}

			/**
			 * Define a data de aprovação.
			 *
			 * @param dateApproved Data de aprovação
			 * @return este builder
			 */
			public Builder dateApproved(OffsetDateTime dateApproved) {
				this.dateApproved = dateApproved;
				return this;
			}

			/**
			 * Monta o {@link PaymentDTO}.
			 *
			 * @return DTO com os valores definidos
			 */
			public PaymentDTO build() {
				return new PaymentDTO(this);
			}
		}
	}

	/**
	 * DTO simplificado de preferência de checkout.
	 */
	public static final class PreferenceDTO {
		private final String id;
		private final String initPoint;
		private final String sandboxInitPoint;
		private final String externalReference;

		private PreferenceDTO(String id, String initPoint, String sandboxInitPoint, String externalReference) {
			this.id = id;
			this.initPoint = initPoint;
			this.sandboxInitPoint = sandboxInitPoint;
			this.externalReference = externalReference;
		}

		/**
		 * ID da preferência.
		 *
		 * @return ID da preferência
		 */
		public String getId() {
			return id;
		}

		/**
		 * URL de checkout em produção.
		 *
		 * @return URL de checkout (produção)
		 */
		public String getInitPoint() {
			return initPoint;
		}

		/**
		 * URL de checkout do ambiente de testes.
		 *
		 * @return URL de checkout (sandbox)
		 */
		public String getSandboxInitPoint() {
			return sandboxInitPoint;
		}

		/**
		 * Referência externa.
		 *
		 * @return Referência externa
		 */
		public String getExternalReference() {
			return externalReference;
		}

		/**
		 * Resumo legível da preferência.
		 *
		 * @return Texto com ID, URL de checkout e referência externa
		 */
		@Override
		public String toString() {
			return "PreferenceDTO{id='" + id + "', initPoint='" + initPoint + "', externalRef='" + externalReference
					+ "'}";
		}
	}

	/**
	 * Enum com os possíveis status de um pagamento.
	 */
	public enum PaymentStatus {
		/** Pagamento aprovado e creditado. */
		APPROVED("approved"),
		/** Aguardando o pagamento (PIX ou boleto gerado e ainda não pago). */
		PENDING("pending"),
		/** Autorizado, aguardando captura. */
		AUTHORIZED("authorized"),
		/** Em análise. */
		IN_PROCESS("in_process"),
		/** Em disputa (mediação). */
		IN_MEDIATION("in_mediation"),
		/** Recusado. */
		REJECTED("rejected"),
		/** Cancelado ou expirado. */
		CANCELLED("cancelled"),
		/** Devolvido ao pagador. */
		REFUNDED("refunded"),
		/** Estornado pelo emissor do cartão (chargeback). */
		CHARGED_BACK("charged_back"),
		/** Status não reconhecido, ou pagamento não encontrado (HTTP 404). */
		UNKNOWN("unknown");

		private final String value;

		PaymentStatus(String value) {
			this.value = value;
		}

		/**
		 * Valor do status como a API o escreve.
		 *
		 * @return Valor do status como String
		 */
		public String getValue() {
			return value;
		}

		/**
		 * Converte uma String de status da API para o enum correspondente.
		 *
		 * @param status String de status retornada pela API
		 * @return {@link PaymentStatus} correspondente, ou {@link #UNKNOWN} se não
		 *         reconhecido
		 */
		public static PaymentStatus fromString(String status) {
			if (status == null)
				return UNKNOWN;
			for (PaymentStatus s : values()) {
				if (s.value.equalsIgnoreCase(status))
					return s;
			}
			return UNKNOWN;
		}
	}

	/**
	 * Falha ao falar com o Mercado Pago, lançada pelos métodos que não declaram exceção
	 * verificada (hoje, {@link #findById(Long)}).
	 *
	 * <p>Existe para que "o Mercado Pago falhou" não possa mais ser confundido com "o pagamento não
	 * existe": este último continua sendo {@code Optional.empty()}; todo o resto sobe como esta
	 * exceção. É não verificada porque {@code findById} nunca declarou {@code throws} e mudar isso
	 * quebraria quem já chama o método.</p>
	 */
	public static final class MercadoPagoException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/** Status HTTP da resposta, ou -1 quando não houve resposta da API. */
		private final int statusCode;

		private MercadoPagoException(String message, int statusCode, Throwable cause) {
			super(message, cause);
			this.statusCode = statusCode;
		}

		/**
		 * Status HTTP informado pelo SDK.
		 *
		 * <p>Atenção: o SDK 2.9.2 relata falha de rede e timeout como HTTP 500 sem corpo, então 500
		 * nem sempre é "o Mercado Pago respondeu 500" — trate 5xx como falha temporária.</p>
		 *
		 * @return Status HTTP, ou {@code -1} quando a falha aconteceu antes de haver resposta
		 */
		public int getStatusCode() {
			return statusCode;
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Pagamentos — Criação
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Cria um pagamento PIX e retorna o QR Code (copia e cola e base64).
	 *
	 * <p>O pagamento fica com status {@code pending} até o pagador efetuar o PIX. O valor é
	 * arredondado para centavos (duas casas, arredondamento comercial) antes do envio.</p>
	 *
	 * <p><strong>Idempotência:</strong> cada chamada cria um PIX novo — é o que permite gerar outro
	 * para um pedido cujo PIX venceu, e um PIX a mais não cobra nada: só expira sem pagamento. Para
	 * que a repetição de uma mesma tentativa (depois de um timeout) devolva o mesmo PIX, use a
	 * sobrecarga com {@code idempotencyKey}.</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema (ex: ID do pedido)
	 * @return {@link PaymentDTO} com {@code pixCopiaECola} e
	 *         {@code pixQrCodeBase64} preenchidos (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder (a causa traz a
	 *                                  {@link MPApiException} quando a falha veio da API)
	 * @throws IllegalArgumentException se o valor não for um número positivo
	 * @see #createPixPayment(BigDecimal, String, String, String, String)
	 */
	public static PaymentDTO createPixPayment(double amount, String payerEmail, String description,
			String externalReference) throws MPException {
		return createPixPayment(toMoney(amount), payerEmail, description, externalReference, null);
	}

	/**
	 * Cria um pagamento PIX com valor exato e chave de idempotência opcional.
	 *
	 * <p>Mesmo comportamento de {@link #createPixPayment(double, String, String, String)}, com o
	 * valor em {@link BigDecimal} (arredondado para centavos) e a chave de idempotência escolhida
	 * pelo chamador: a mesma chave devolve o mesmo PIX. {@code idempotencyKey} nulo ou em branco deixa
	 * o SDK sortear uma chave — cada chamada é um PIX novo.</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema (ex: ID do pedido)
	 * @param idempotencyKey    Chave de idempotência (ASCII visível, sem espaços) ou {@code null}
	 * @return {@link PaymentDTO} com os dados do PIX (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor ou a chave forem inválidos
	 */
	public static PaymentDTO createPixPayment(BigDecimal amount, String payerEmail, String description,
			String externalReference, String idempotencyKey) throws MPException {
		checkDependencies();
		BigDecimal value = toMoney(amount);
		String key = normalizeIdempotencyKey(idempotencyKey);
		Console.debug("[MercadoPagoAPI] Criando pagamento PIX (referência %s)", externalReference);
		return MpSupport.createPixPayment(value, payerEmail, description, externalReference, key);
	}

	/**
	 * Cria um pagamento via boleto bancário (Bradesco).
	 *
	 * <p>O valor é arredondado para centavos antes do envio. Idempotência: como em
	 * {@link #createPixPayment(double, String, String, String)} — cada chamada emite um boleto novo;
	 * para que a repetição de uma mesma tentativa devolva o mesmo boleto, use a sobrecarga com
	 * {@code idempotencyKey}.</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param payerFirstName    Primeiro nome do pagador
	 * @param payerLastName     Sobrenome do pagador
	 * @param payerCpf          CPF do pagador (somente dígitos)
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema
	 * @return {@link PaymentDTO} com {@code boletoUrl} preenchido (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor não for um número positivo
	 * @see #createBoletoPayment(BigDecimal, String, String, String, String, String, String, String)
	 */
	public static PaymentDTO createBoletoPayment(double amount, String payerEmail, String payerFirstName,
			String payerLastName, String payerCpf, String description, String externalReference) throws MPException {
		return createBoletoPayment(toMoney(amount), payerEmail, payerFirstName, payerLastName, payerCpf, description,
				externalReference, null);
	}

	/**
	 * Cria um boleto com valor exato e chave de idempotência opcional.
	 *
	 * <p>Mesmo comportamento de
	 * {@link #createBoletoPayment(double, String, String, String, String, String, String)};
	 * a mesma {@code idempotencyKey} devolve o mesmo boleto; nula ou em branco, cada chamada é um
	 * boleto novo.</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param payerFirstName    Primeiro nome do pagador
	 * @param payerLastName     Sobrenome do pagador
	 * @param payerCpf          CPF do pagador (somente dígitos)
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema
	 * @param idempotencyKey    Chave de idempotência (ASCII visível, sem espaços) ou {@code null}
	 * @return {@link PaymentDTO} com {@code boletoUrl} preenchido (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor ou a chave forem inválidos
	 */
	public static PaymentDTO createBoletoPayment(BigDecimal amount, String payerEmail, String payerFirstName,
			String payerLastName, String payerCpf, String description, String externalReference,
			String idempotencyKey) throws MPException {
		checkDependencies();
		BigDecimal value = toMoney(amount);
		String key = normalizeIdempotencyKey(idempotencyKey);
		Console.debug("[MercadoPagoAPI] Criando boleto (referência %s)", externalReference);
		return MpSupport.createBoletoPayment(value, payerEmail, payerFirstName, payerLastName, payerCpf,
				description, externalReference, key);
	}

	/**
	 * Cria um pagamento com cartão de crédito usando o token gerado pelo Checkout
	 * Bricks / SDK JS.
	 *
	 * <p>O valor é arredondado para centavos antes do envio.</p>
	 *
	 * <p><strong>Idempotência:</strong> com {@code externalReference} informado, a chave é derivada
	 * de referência, valor, parcelas, bandeira, e-mail, descrição <em>e do token do cartão</em>.
	 * Repetir a chamada com o mesmo token — por exemplo, depois de um timeout — não cobra de novo.
	 * Um token novo (outro cartão, ou o mesmo cartão digitado de novo) é uma tentativa nova: é isso
	 * que permite ao cliente tentar outro cartão depois de uma recusa. Consequência: se o cliente
	 * redigitar o cartão depois de um timeout, a chave muda; nesse caso consulte
	 * {@link #findByExternalReference(String)} antes de cobrar de novo, ou use a sobrecarga com
	 * {@code idempotencyKey} passando uma chave que só avança quando a tentativa anterior teve
	 * resposta definitiva (ex.: {@code "pedido-123-tentativa-1"}).</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param installments      Número de parcelas
	 * @param cardToken         Token do cartão gerado pelo frontend
	 * @param paymentMethodId   ID do método de pagamento (ex: {@code visa},
	 *                          {@code master})
	 * @param payerEmail        E-mail do pagador
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema
	 * @return {@link PaymentDTO} com o resultado da transação (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor não for um número positivo
	 * @see #createCreditCardPayment(BigDecimal, int, String, String, String, String, String, String)
	 */
	public static PaymentDTO createCreditCardPayment(double amount, int installments, String cardToken,
			String paymentMethodId, String payerEmail, String description, String externalReference)
			throws MPException {
		return createCreditCardPayment(toMoney(amount), installments, cardToken, paymentMethodId, payerEmail,
				description, externalReference, null);
	}

	/**
	 * Cria um pagamento com cartão com valor exato e chave de idempotência opcional.
	 *
	 * <p>Mesmo comportamento de
	 * {@link #createCreditCardPayment(double, int, String, String, String, String, String)};
	 * {@code idempotencyKey} nulo ou em branco usa a chave derivada dos dados da cobrança
	 * (incluindo o token do cartão).</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param installments      Número de parcelas
	 * @param cardToken         Token do cartão gerado pelo frontend
	 * @param paymentMethodId   ID do método de pagamento (ex: {@code visa})
	 * @param payerEmail        E-mail do pagador
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema
	 * @param idempotencyKey    Chave de idempotência (ASCII visível, sem espaços) ou {@code null}
	 * @return {@link PaymentDTO} com o resultado da transação (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor ou a chave forem inválidos
	 */
	public static PaymentDTO createCreditCardPayment(BigDecimal amount, int installments, String cardToken,
			String paymentMethodId, String payerEmail, String description, String externalReference,
			String idempotencyKey) throws MPException {
		checkDependencies();
		BigDecimal value = toMoney(amount);
		String key = resolveIdempotencyKey(idempotencyKey, "card", value, externalReference,
				Integer.valueOf(installments), paymentMethodId, cardToken, payerEmail, description);
		Console.debug("[MercadoPagoAPI] Criando pagamento com cartão %s (referência %s)", paymentMethodId,
				externalReference);
		return MpSupport.createCreditCardPayment(value, installments, cardToken, paymentMethodId, payerEmail,
				description, externalReference, key);
	}

	/**
	 * Cria um pagamento genérico com metadados customizados (chave-valor).
	 *
	 * <p>Use {@code metadata} para armazenar informações adicionais do seu sistema sem
	 * interferir nos campos oficiais da API. O valor é arredondado para centavos antes do envio.
	 * Idempotência: como em {@link #createPixPayment(double, String, String, String)} — cada chamada
	 * sem {@code idempotencyKey} é uma cobrança nova.</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param paymentMethodId   Método de pagamento (ex: {@code pix},
	 *                          {@code bolbradesco})
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna
	 * @param metadata          Mapa de chave-valor com informações adicionais
	 * @return {@link PaymentDTO} com o resultado da transação (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor não for um número positivo
	 * @see #createPaymentWithMetadata(BigDecimal, String, String, String, String, Map, String)
	 */
	public static PaymentDTO createPaymentWithMetadata(double amount, String payerEmail, String paymentMethodId,
			String description, String externalReference, Map<String, Object> metadata) throws MPException {
		return createPaymentWithMetadata(toMoney(amount), payerEmail, paymentMethodId, description, externalReference,
				metadata, null);
	}

	/**
	 * Cria um pagamento com metadados, valor exato e chave de idempotência opcional.
	 *
	 * <p>Mesmo comportamento de
	 * {@link #createPaymentWithMetadata(double, String, String, String, String, Map)}; a mesma
	 * {@code idempotencyKey} devolve o mesmo pagamento; nula ou em branco, cada chamada é uma cobrança
	 * nova.</p>
	 *
	 * @param amount            Valor do pagamento (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param paymentMethodId   Método de pagamento (ex: {@code pix})
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna
	 * @param metadata          Mapa de chave-valor com informações adicionais
	 * @param idempotencyKey    Chave de idempotência (ASCII visível, sem espaços) ou {@code null}
	 * @return {@link PaymentDTO} com o resultado da transação (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o valor ou a chave forem inválidos
	 */
	public static PaymentDTO createPaymentWithMetadata(BigDecimal amount, String payerEmail, String paymentMethodId,
			String description, String externalReference, Map<String, Object> metadata, String idempotencyKey)
			throws MPException {
		checkDependencies();
		BigDecimal value = toMoney(amount);
		String key = normalizeIdempotencyKey(idempotencyKey);
		Console.debug("[MercadoPagoAPI] Criando pagamento %s com metadados (referência %s)", paymentMethodId,
				externalReference);
		return MpSupport.createPaymentWithMetadata(value, payerEmail, paymentMethodId, description, externalReference,
				metadata, key);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Consultas
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Busca um pagamento pelo ID.
	 *
	 * <p>Só o HTTP 404 do Mercado Pago vira {@code Optional.empty()}. Timeout, token inválido,
	 * erro 5xx e qualquer outra falha lançam {@link MercadoPagoException} — antes elas também
	 * viravam "vazio", e quem consultava não tinha como saber que o pagamento talvez existisse e
	 * estivesse aprovado.</p>
	 *
	 * @param paymentId ID do pagamento ({@code null} devolve vazio)
	 * @return {@link Optional} com o {@link PaymentDTO} se encontrado, ou vazio se o pagamento não
	 *         existe
	 * @throws MercadoPagoException  se o Mercado Pago falhar ou não responder
	 * @throws IllegalStateException se o SDK não foi inicializado
	 */
	public static Optional<PaymentDTO> findById(Long paymentId) {
		checkDependencies();
		Console.debug("[MercadoPagoAPI] Buscando pagamento ID: %s", paymentId);
		return MpSupport.findPaymentUnchecked(paymentId);
	}

	/**
	 * Busca todos os pagamentos associados a uma {@code external_reference}.
	 *
	 * <p>Útil para rastrear pagamentos por ID de pedido interno do sistema — e para conferir, depois
	 * de um timeout na criação, se a cobrança chegou a ser feita.</p>
	 *
	 * @param externalReference Referência externa definida na criação do pagamento
	 * @return Lista (possivelmente vazia, nunca {@code null}) de {@link PaymentDTO}
	 * @throws MPException              se o Mercado Pago falhar ou não responder
	 * @throws IllegalArgumentException se a referência for nula ou vazia
	 */
	public static List<PaymentDTO> findByExternalReference(String externalReference) throws MPException {
		if (externalReference == null || externalReference.isBlank()) {
			throw new IllegalArgumentException("[MercadoPagoAPI] A referência externa não pode ser vazia.");
		}
		Map<String, Object> filters = new HashMap<>();
		filters.put("external_reference", externalReference);
		return searchPayments(filters, 0, 50);
	}

	/**
	 * Lista pagamentos com filtros dinâmicos.
	 *
	 * <p>Filtros aceitos pela API (chaves como String):</p>
	 * <ul>
	 * <li>{@code status} — ex: {@code approved}, {@code pending},
	 * {@code rejected}</li>
	 * <li>{@code payment_method_id} — ex: {@code pix}, {@code visa}</li>
	 * <li>{@code date_created.from} e {@code date_created.to} — formato ISO
	 * 8601</li>
	 * <li>{@code external_reference}</li>
	 * <li>{@code transaction_amount}</li>
	 * </ul>
	 *
	 * @param filters Mapa de filtros conforme documentação da API (sem valores nulos)
	 * @param offset  Offset para paginação
	 * @param limit   Máximo de resultados por página (máx. 50)
	 * @return Lista (possivelmente vazia, nunca {@code null}) de {@link PaymentDTO}
	 * @throws MPException              se o Mercado Pago falhar ou não responder
	 * @throws IllegalArgumentException se algum filtro tiver valor nulo
	 */
	public static List<PaymentDTO> searchPayments(Map<String, Object> filters, int offset, int limit)
			throws MPException {
		checkDependencies();
		if (filters != null) {
			for (Map.Entry<String, Object> filter : filters.entrySet()) {
				if (filter.getKey() == null || filter.getValue() == null) {
					// Um filtro nulo faria o SDK falhar com NullPointerException ao montar a URL; e
					// descartá-lo em silêncio devolveria MAIS pagamentos do que o chamador pediu.
					throw new IllegalArgumentException(
							"[MercadoPagoAPI] Filtro de busca com chave ou valor nulo: " + filter.getKey());
				}
			}
		}
		Console.debug("[MercadoPagoAPI] Buscando pagamentos com filtros: %s", filters);
		return MpSupport.searchPayments(filters, offset, limit);
	}

	/**
	 * Busca pagamentos por status.
	 *
	 * @param status Status desejado (usar valores de {@link PaymentStatus})
	 * @return Lista (possivelmente vazia, nunca {@code null}) de {@link PaymentDTO}
	 * @throws MPException              se o Mercado Pago falhar ou não responder
	 * @throws IllegalArgumentException se o status for nulo
	 */
	public static List<PaymentDTO> findByStatus(PaymentStatus status) throws MPException {
		if (status == null) {
			throw new IllegalArgumentException("[MercadoPagoAPI] O status da busca não pode ser nulo.");
		}
		Map<String, Object> filters = new HashMap<>();
		filters.put("status", status.getValue());
		return searchPayments(filters, 0, 50);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Verificação de Status
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifica o status atual de um pagamento pelo ID.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@link PaymentStatus} atual, ou {@link PaymentStatus#UNKNOWN} se o pagamento não
	 *         existe (HTTP 404) ou tem status desconhecido
	 * @throws MPException se o Mercado Pago falhar ou não responder — falha nunca vira
	 *                     {@code UNKNOWN}
	 */
	public static PaymentStatus checkPaymentStatus(Long paymentId) throws MPException {
		checkDependencies();
		return MpSupport.findPayment(paymentId).map(dto -> PaymentStatus.fromString(dto.getStatus()))
				.orElse(PaymentStatus.UNKNOWN);
	}

	/**
	 * Verifica se um pagamento está aprovado.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@code true} se aprovado, {@code false} caso contrário
	 * @throws MPException se o Mercado Pago falhar ou não responder
	 */
	public static boolean isApproved(Long paymentId) throws MPException {
		return checkPaymentStatus(paymentId) == PaymentStatus.APPROVED;
	}

	/**
	 * Verifica se um pagamento está pendente.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@code true} se pendente, em processamento ou autorizado
	 * @throws MPException se o Mercado Pago falhar ou não responder
	 */
	public static boolean isPending(Long paymentId) throws MPException {
		PaymentStatus status = checkPaymentStatus(paymentId);
		return status == PaymentStatus.PENDING || status == PaymentStatus.IN_PROCESS
				|| status == PaymentStatus.AUTHORIZED;
	}

	/**
	 * Verifica se um pagamento foi rejeitado.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@code true} se rejeitado, {@code false} caso contrário
	 * @throws MPException se o Mercado Pago falhar ou não responder
	 */
	public static boolean isRejected(Long paymentId) throws MPException {
		return checkPaymentStatus(paymentId) == PaymentStatus.REJECTED;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Preferências de Checkout
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Cria uma preferência de checkout com um único item.
	 *
	 * <p>O preço é arredondado para centavos antes do envio. Preferência não é cobrança — uma
	 * duplicada é só um link a mais —, então aqui o SDK gera a própria chave de idempotência.</p>
	 *
	 * @param title             Título do produto/serviço
	 * @param quantity          Quantidade
	 * @param unitPrice         Preço unitário (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param externalReference Referência interna do sistema
	 * @param successUrl        URL de redirecionamento após pagamento aprovado
	 * @param failureUrl        URL de redirecionamento após falha
	 * @param pendingUrl        URL de redirecionamento enquanto pendente
	 * @return {@link PreferenceDTO} com o link de checkout (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o preço não for um número positivo
	 */
	public static PreferenceDTO createPreference(String title, int quantity, double unitPrice, String payerEmail,
			String externalReference, String successUrl, String failureUrl, String pendingUrl) throws MPException {
		return createPreference(title, quantity, toMoney(unitPrice), payerEmail, externalReference, successUrl,
				failureUrl, pendingUrl);
	}

	/**
	 * Cria uma preferência de checkout com um único item e preço exato.
	 *
	 * <p>Mesmo comportamento de
	 * {@link #createPreference(String, int, double, String, String, String, String, String)}, com
	 * o preço em {@link BigDecimal} (arredondado para centavos).</p>
	 *
	 * @param title             Título do produto/serviço
	 * @param quantity          Quantidade
	 * @param unitPrice         Preço unitário (maior que zero)
	 * @param payerEmail        E-mail do pagador
	 * @param externalReference Referência interna do sistema
	 * @param successUrl        URL de redirecionamento após pagamento aprovado
	 * @param failureUrl        URL de redirecionamento após falha
	 * @param pendingUrl        URL de redirecionamento enquanto pendente
	 * @return {@link PreferenceDTO} com o link de checkout (nunca {@code null})
	 * @throws MPException              se o Mercado Pago recusar ou não responder
	 * @throws IllegalArgumentException se o preço não for um número positivo
	 */
	public static PreferenceDTO createPreference(String title, int quantity, BigDecimal unitPrice, String payerEmail,
			String externalReference, String successUrl, String failureUrl, String pendingUrl) throws MPException {
		checkDependencies();
		List<PreferenceItemRequest> items = Collections
				.singletonList(buildPreferenceItem(title, quantity, unitPrice, null, null));
		return createPreferenceWithItems(items, payerEmail, externalReference, successUrl, failureUrl, pendingUrl);
	}

	/**
	 * Cria uma preferência de checkout com múltiplos itens.
	 *
	 * @param items             Lista de {@link PreferenceItemRequest} (use
	 *                          {@link #buildPreferenceItem})
	 * @param payerEmail        E-mail do pagador
	 * @param externalReference Referência interna do sistema
	 * @param successUrl        URL de redirecionamento após pagamento aprovado
	 * @param failureUrl        URL de redirecionamento após falha
	 * @param pendingUrl        URL de redirecionamento enquanto pendente
	 * @return {@link PreferenceDTO} com o link de checkout (nunca {@code null})
	 * @throws MPException se o Mercado Pago recusar ou não responder
	 */
	public static PreferenceDTO createPreferenceWithItems(List<PreferenceItemRequest> items, String payerEmail,
			String externalReference, String successUrl, String failureUrl, String pendingUrl) throws MPException {

		checkDependencies();
		Console.debug("[MercadoPagoAPI] Criando preferência (referência %s)", externalReference);
		return MpSupport.createPreferenceWithItems(items, payerEmail, externalReference, successUrl, failureUrl,
				pendingUrl);
	}

	/**
	 * Constrói um {@link PreferenceItemRequest} para uso em
	 * {@link #createPreferenceWithItems}.
	 *
	 * <p>O preço é arredondado para centavos (duas casas, arredondamento comercial): um
	 * {@code double} como {@code 19.9 * 3} vale {@code 59.699999999999996}, e era isso que ia para o
	 * Mercado Pago.</p>
	 *
	 * @param title       Título do item
	 * @param quantity    Quantidade
	 * @param unitPrice   Preço unitário (maior que zero)
	 * @param description Descrição (pode ser {@code null})
	 * @param pictureUrl  URL da imagem do produto (pode ser {@code null})
	 * @return {@link PreferenceItemRequest} configurado
	 * @throws IllegalArgumentException se o preço não for um número positivo
	 */
	public static PreferenceItemRequest buildPreferenceItem(String title, int quantity, double unitPrice,
			String description, String pictureUrl) {
		return buildPreferenceItem(title, quantity, toMoney(unitPrice), description, pictureUrl);
	}

	/**
	 * Constrói um {@link PreferenceItemRequest} com preço exato.
	 *
	 * @param title       Título do item
	 * @param quantity    Quantidade
	 * @param unitPrice   Preço unitário (maior que zero; arredondado para centavos)
	 * @param description Descrição (pode ser {@code null})
	 * @param pictureUrl  URL da imagem do produto (pode ser {@code null})
	 * @return {@link PreferenceItemRequest} configurado
	 * @throws IllegalArgumentException se o preço não for um número positivo
	 */
	public static PreferenceItemRequest buildPreferenceItem(String title, int quantity, BigDecimal unitPrice,
			String description, String pictureUrl) {
		checkDependencies();
		return MpSupport.buildPreferenceItem(title, quantity, toMoney(unitPrice), description, pictureUrl);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Webhooks
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Extrai o ID do pagamento a partir de uma notificação de webhook do Mercado
	 * Pago.
	 *
	 * <p>O Mercado Pago envia webhooks com a seguinte estrutura JSON:</p>
	 *
	 * <pre>{@code
	 * {
	 *   "type": "payment",
	 *   "data": { "id": "123456789" }
	 * }
	 * }</pre>
	 *
	 * <p>O ID pode chegar como texto ou como número — um {@code Map} montado pelo Gson traz números
	 * como {@code Double} ({@code 1.23456789E8}), e os dois formatos são aceitos.</p>
	 *
	 * <p><b>Importante:</b> valide o cabeçalho {@code x-signature} antes de processar
	 * (consulte {@link #validateWebhookSignature}).</p>
	 *
	 * @param webhookPayload Corpo JSON do webhook como {@link Map} (parseado
	 *                       previamente)
	 * @return {@link Optional} com o ID do pagamento (Long), ou vazio se não for
	 *         notificação de pagamento
	 */
	public static Optional<Long> extractPaymentIdFromWebhook(Map<String, Object> webhookPayload) {
		if (webhookPayload == null)
			return Optional.empty();

		Object type = webhookPayload.get("type");
		if (!"payment".equals(type)) {
			Console.debug("[MercadoPagoAPI] Webhook ignorado (tipo não é 'payment'): %s", type);
			return Optional.empty();
		}

		if (!(webhookPayload.get("data") instanceof Map<?, ?> data))
			return Optional.empty();

		Object rawId = data.get("id");
		Optional<Long> id = parsePaymentId(rawId);
		if (id.isEmpty() && rawId != null) {
			Console.warn("[MercadoPagoAPI] ID de pagamento ilegível no webhook: %s", rawId);
		}
		return id;
	}

	/**
	 * Processa um webhook e busca o pagamento correspondente na API.
	 *
	 * <p>Combina {@link #extractPaymentIdFromWebhook} com a consulta do pagamento. Devolve vazio
	 * quando a notificação não é de pagamento ou quando o pagamento não existe (HTTP 404) — nesses
	 * casos responda 200 ao Mercado Pago. <strong>Qualquer falha de consulta lança
	 * {@link MPException}</strong>: responda erro (5xx) ao Mercado Pago, que então reenvia a
	 * notificação. Responder 200 numa falha é o que deixava pedido pago sem liberação.</p>
	 *
	 * @param webhookPayload Corpo JSON do webhook como {@link Map}
	 * @return {@link Optional} com o {@link PaymentDTO} se o pagamento for
	 *         encontrado
	 * @throws MPException se o Mercado Pago falhar ou não responder
	 */
	public static Optional<PaymentDTO> processWebhook(Map<String, Object> webhookPayload) throws MPException {
		Optional<Long> paymentId = extractPaymentIdFromWebhook(webhookPayload);
		if (paymentId.isEmpty())
			return Optional.empty();

		checkDependencies();
		Console.debug("[MercadoPagoAPI] Processando webhook do pagamento %s", paymentId.get());
		return MpSupport.findPayment(paymentId.get());
	}

	/**
	 * Valida a assinatura de um webhook do Mercado Pago, com a tolerância de tempo configurada
	 * (padrão: desligada — ver {@link #DEFAULT_WEBHOOK_TOLERANCE}).
	 *
	 * <p>Usa HMAC-SHA256 para verificar a autenticidade da notificação, como na documentação
	 * oficial. Requer o {@code secret} configurado no painel do Mercado Pago (Webhooks). O manifesto
	 * assinado é {@code id:<dataId>;request-id:<xRequestId>;ts:<ts>;}, e o par cujo valor não veio
	 * na notificação ({@code data.id} ou {@code x-request-id} ausente) fica de fora dele.</p>
	 *
	 * <p>A comparação é feita em tempo constante ({@link MessageDigest#isEqual}): comparar texto
	 * com {@code equals} para no primeiro caractere diferente, e o tempo de resposta deixa um
	 * atacante descobrir a assinatura aos poucos. Com uma tolerância configurada, o {@code ts}
	 * também precisa estar dentro dela.</p>
	 *
	 * @param xSignatureHeader Valor do cabeçalho {@code x-signature}
	 * @param xRequestId       Valor do cabeçalho {@code x-request-id} ({@code null} se não veio)
	 * @param dataId           ID do dado (ex: ID do pagamento da query string
	 *                         {@code data.id}; {@code null} se não veio)
	 * @param secret           Secret configurado no painel do Mercado Pago
	 * @return {@code true} se a assinatura for válida (e o {@code ts} estiver na tolerância, quando
	 *         houver uma), {@code false} caso contrário
	 * @see #setWebhookTolerance(Duration)
	 */
	public static boolean validateWebhookSignature(String xSignatureHeader, String xRequestId, String dataId,
			String secret) {
		return validateWebhookSignature(xSignatureHeader, xRequestId, dataId, secret, webhookTolerance);
	}

	/**
	 * Valida a assinatura de um webhook do Mercado Pago com uma tolerância de tempo explícita.
	 *
	 * <p>Igual a {@link #validateWebhookSignature(String, String, String, String)}, mas com a
	 * tolerância do {@code ts} informada na chamada. {@code null} usa a tolerância configurada;
	 * zero ou negativa desliga a verificação de tempo (só a assinatura é conferida).</p>
	 *
	 * @param xSignatureHeader Valor do cabeçalho {@code x-signature}
	 * @param xRequestId       Valor do cabeçalho {@code x-request-id}
	 * @param dataId           ID do dado (ex: ID do pagamento da query string {@code data.id})
	 * @param secret           Secret configurado no painel do Mercado Pago
	 * @param tolerance        Diferença máxima aceita entre o {@code ts} e o relógio local
	 * @return {@code true} se a assinatura for válida e o {@code ts} estiver na tolerância
	 */
	public static boolean validateWebhookSignature(String xSignatureHeader, String xRequestId, String dataId,
			String secret, Duration tolerance) {
		return verifyWebhookSignature(xSignatureHeader, xRequestId, dataId, secret,
				tolerance != null ? tolerance : webhookTolerance, System.currentTimeMillis());
	}

	/**
	 * Define a tolerância do carimbo de tempo ({@code ts}) usada por
	 * {@link #validateWebhookSignature(String, String, String, String)}.
	 *
	 * <p>Ligue só depois de ver no sandbox como os reenvios chegam: se o Mercado Pago reenviar uma
	 * notificação com o {@code ts} original, ela é recusada, o console mostra "fora da janela" com a
	 * diferença medida, e o pagamento só chega ao sistema quando alguém perceber (ver
	 * {@link #DEFAULT_WEBHOOK_TOLERANCE}).</p>
	 *
	 * @param tolerance Nova tolerância; {@code null} volta ao padrão
	 *                  ({@link #DEFAULT_WEBHOOK_TOLERANCE}, desligada); zero ou negativa desliga a
	 *                  verificação de tempo
	 */
	public static void setWebhookTolerance(Duration tolerance) {
		webhookTolerance = tolerance != null ? tolerance : DEFAULT_WEBHOOK_TOLERANCE;
	}

	/**
	 * Tolerância atual do carimbo de tempo ({@code ts}) do webhook.
	 *
	 * @return Tolerância em uso
	 */
	public static Duration getWebhookTolerance() {
		return webhookTolerance;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Conversão para DTO
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Converte um objeto {@link Payment} retornado pelo SDK para
	 * {@link PaymentDTO}.
	 *
	 * @param payment Objeto retornado pelo SDK (não nulo)
	 * @return {@link PaymentDTO} preenchido com os dados relevantes
	 * @throws IllegalArgumentException se {@code payment} for nulo
	 */
	public static PaymentDTO toDTO(Payment payment) {
		checkDependencies();
		return MpSupport.toDTO(payment);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Regras puras (sem SDK) — dinheiro, idempotência e assinatura
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Converte um {@code double} em valor monetário com duas casas.
	 *
	 * <p>{@link BigDecimal#valueOf(double)} parte da representação decimal mais curta do
	 * {@code double} ({@code 1.005} vira {@code "1.005"}, e não {@code 1.00499999…}), então o
	 * arredondamento comercial dá o resultado que uma pessoa espera.</p>
	 */
	static BigDecimal toMoney(double amount) {
		if (!Double.isFinite(amount)) {
			throw new IllegalArgumentException("[MercadoPagoAPI] Valor monetário inválido: " + amount);
		}
		return toMoney(BigDecimal.valueOf(amount));
	}

	/**
	 * Arredonda para centavos (HALF_UP) e exige valor positivo depois do arredondamento — um
	 * {@code 0.004} vira {@code 0.00}, e cobrança de zero reais não existe.
	 */
	static BigDecimal toMoney(BigDecimal amount) {
		if (amount == null) {
			throw new IllegalArgumentException("[MercadoPagoAPI] O valor não pode ser nulo.");
		}
		BigDecimal rounded = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		if (rounded.signum() <= 0) {
			throw new IllegalArgumentException(
					"[MercadoPagoAPI] O valor precisa ser maior que zero (recebido: " + amount.toPlainString() + ").");
		}
		return rounded;
	}

	/**
	 * Escolhe a chave de idempotência de uma cobrança com cartão: a do chamador, se houver; senão, a
	 * derivada dos dados da cobrança; senão ({@code null}), a aleatória que o SDK gera sozinho. PIX,
	 * boleto e pagamento com metadados usam só a chave do chamador (ver a documentação da classe).
	 */
	static String resolveIdempotencyKey(String explicitKey, String operation, BigDecimal amount,
			String externalReference, Object... discriminators) {
		String key = normalizeIdempotencyKey(explicitKey);
		return key != null ? key : deriveIdempotencyKey(operation, amount, externalReference, discriminators);
	}

	/**
	 * Valida a chave informada pelo chamador.
	 *
	 * <p>Só ASCII visível: a chave vira cabeçalho HTTP, e o cliente HTTP do SDK não rejeita quebra
	 * de linha — uma chave vinda de entrada de usuário com {@code \r\n} injetaria cabeçalhos no
	 * pedido ao Mercado Pago.</p>
	 *
	 * @return a chave sem espaços nas pontas, ou {@code null} se nula ou em branco
	 */
	static String normalizeIdempotencyKey(String key) {
		if (key == null || key.isBlank()) {
			return null;
		}
		String trimmed = key.strip();
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c < 0x21 || c > 0x7E) {
				throw new IllegalArgumentException("[MercadoPagoAPI] Chave de idempotência inválida: use apenas "
						+ "caracteres ASCII visíveis, sem espaços nem quebras de linha.");
			}
		}
		return trimmed;
	}

	/**
	 * Deriva a chave de idempotência dos dados da cobrança.
	 *
	 * <h4>Por que derivar e não sortear</h4>
	 * <p>Uma chave nova a cada chamada — o que o SDK faz sozinho — não protege nada: se a criação dá
	 * timeout depois de o Mercado Pago já ter cobrado, a nova tentativa sai com outra chave e cobra
	 * de novo. A chave precisa ser a mesma para "a mesma cobrança", e o que define a mesma cobrança
	 * é o conteúdo dela: pedido ({@code externalReference}), tipo, valor e os demais dados. Qualquer
	 * mudança real (outro valor, outro cartão) produz outra chave, e portanto outra cobrança.</p>
	 *
	 * <p>Sem {@code externalReference} não há como saber que duas chamadas são o mesmo pedido, e a
	 * chave fica a cargo do SDK (aleatória), como antes.</p>
	 *
	 * <p>O formato é um UUID (RFC 9562, versão 8) montado sobre o SHA-256 dos campos, cada um
	 * prefixado pelo tamanho — assim {@code "ab"+"c"} e {@code "a"+"bc"} não colidem. UUID porque é
	 * o formato que o próprio SDK envia, então é aceito com certeza.</p>
	 *
	 * @return a chave, ou {@code null} quando não há referência externa
	 */
	static String deriveIdempotencyKey(String operation, BigDecimal amount, String externalReference,
			Object... discriminators) {
		if (externalReference == null || externalReference.isBlank()) {
			return null;
		}
		StringBuilder canonical = new StringBuilder();
		appendField(canonical, IDEMPOTENCY_DERIVATION);
		appendField(canonical, operation);
		appendField(canonical, externalReference);
		appendField(canonical, amount == null ? null : amount.toPlainString());
		if (discriminators != null) {
			for (Object discriminator : discriminators) {
				appendField(canonical, canonicalValue(discriminator));
			}
		}
		byte[] digest = sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
		long mostSignificant = 0;
		long leastSignificant = 0;
		for (int i = 0; i < 8; i++) {
			mostSignificant = (mostSignificant << 8) | (digest[i] & 0xFF);
			leastSignificant = (leastSignificant << 8) | (digest[i + 8] & 0xFF);
		}
		mostSignificant = (mostSignificant & ~0xF000L) | 0x8000L; // versão 8: formato definido pela aplicação
		leastSignificant = (leastSignificant & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // variante RFC
		return new UUID(mostSignificant, leastSignificant).toString();
	}

	/** Campo com prefixo de tamanho; {@code null} tem marca própria, diferente de texto vazio. */
	private static void appendField(StringBuilder target, String value) {
		if (value == null) {
			target.append("-;");
			return;
		}
		target.append(value.length()).append(':').append(value).append(';');
	}

	/**
	 * Texto estável de um valor: mapas saem com as chaves em ordem, para que a mesma cobrança dê a
	 * mesma chave independentemente da ordem em que os metadados foram montados.
	 */
	private static String canonicalValue(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Map<?, ?> map) {
			TreeMap<String, String> sorted = new TreeMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				sorted.put(String.valueOf(entry.getKey()), canonicalValue(entry.getValue()));
			}
			StringBuilder text = new StringBuilder("{");
			for (Map.Entry<String, String> entry : sorted.entrySet()) {
				appendField(text, entry.getKey());
				appendField(text, entry.getValue());
			}
			return text.append('}').toString();
		}
		return String.valueOf(value);
	}

	private static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		} catch (NoSuchAlgorithmException e) {
			// Toda JVM é obrigada a ter SHA-256 (MessageDigest, "Standard Algorithm Names").
			throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
		}
	}

	/**
	 * Lê o ID de pagamento de um webhook: texto ou número inteiro. Número com fração, texto não
	 * numérico ou valor fora do intervalo de {@code long} dão vazio.
	 */
	static Optional<Long> parsePaymentId(Object raw) {
		if (raw == null) {
			return Optional.empty();
		}
		try {
			if (raw instanceof Number number) {
				// Map montado pelo Gson traz número como Double: 123456789 chega como 1.23456789E8.
				return Optional.of(new BigDecimal(number.toString()).longValueExact());
			}
			return Optional.of(Long.parseLong(raw.toString().trim()));
		} catch (NumberFormatException | ArithmeticException e) {
			return Optional.empty();
		}
	}

	/**
	 * Verificação da assinatura com relógio injetável (para teste). Ver
	 * {@link #validateWebhookSignature(String, String, String, String, Duration)}.
	 */
	static boolean verifyWebhookSignature(String xSignatureHeader, String xRequestId, String dataId, String secret,
			Duration tolerance, long nowMillis) {
		if (xSignatureHeader == null || secret == null || secret.isEmpty()) {
			return false;
		}

		// Extrai ts= e v1= do header x-signature
		String ts = null;
		String v1 = null;
		for (String part : xSignatureHeader.split(",")) {
			int separator = part.indexOf('=');
			if (separator <= 0) {
				continue;
			}
			String name = part.substring(0, separator).trim();
			String value = part.substring(separator + 1).trim();
			if ("ts".equals(name)) {
				ts = value;
			} else if ("v1".equals(name)) {
				v1 = value;
			}
		}
		if (ts == null || v1 == null || !isDigits(ts, 18)) {
			return false;
		}

		byte[] received;
		try {
			received = HexFormat.of().parseHex(v1);
		} catch (IllegalArgumentException e) {
			return false;
		}

		// Manifest da documentação oficial: id:{data.id};request-id:{x-request-id};ts:{ts}; — o par
		// cujo valor não veio na notificação fica de fora, em vez de entrar como "null".
		StringBuilder manifest = new StringBuilder(128);
		if (dataId != null && !dataId.isEmpty()) {
			manifest.append("id:").append(dataId).append(';');
		}
		if (xRequestId != null && !xRequestId.isEmpty()) {
			manifest.append("request-id:").append(xRequestId).append(';');
		}
		manifest.append("ts:").append(ts).append(';');
		byte[] expected;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			expected = mac.doFinal(manifest.toString().getBytes(StandardCharsets.UTF_8));
		} catch (GeneralSecurityException e) {
			Console.error("[MercadoPagoAPI] Não foi possível calcular o HMAC-SHA256 do webhook.", e);
			return false;
		}

		// Tempo constante: o equals de String para no primeiro byte diferente e vaza, pelo tempo de
		// resposta, quantos bytes da assinatura já estão certos.
		if (!MessageDigest.isEqual(expected, received)) {
			Console.warn("[MercadoPagoAPI] Assinatura de webhook inválida.");
			return false;
		}

		if (tolerance != null && !tolerance.isZero() && !tolerance.isNegative()) {
			long timestamp = Long.parseLong(ts);
			long timestampMillis = timestamp >= TIMESTAMP_MILLIS_THRESHOLD ? timestamp : timestamp * 1000L;
			long differenceMillis = Math.abs(nowMillis - timestampMillis);
			if (differenceMillis > tolerance.toMillis()) {
				Console.warn("[MercadoPagoAPI] Webhook com assinatura válida, mas fora da janela de %d s "
						+ "(diferença de %d s para o relógio local): recusado como possível repetição. Se forem "
						+ "reenvios legítimos do Mercado Pago, ajuste MercadoPagoAPI.setWebhookTolerance(...).",
						Long.valueOf(tolerance.toSeconds()), Long.valueOf(differenceMillis / 1000L));
				return false;
			}
		}
		return true;
	}

	private static boolean isDigits(String text, int maxLength) {
		if (text.isEmpty() || text.length() > maxLength) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	/** Trecho de texto de uma linha, sem caracteres de controle, para mensagem de erro. */
	static String snippet(String text, int maxLength) {
		if (text == null) {
			return "";
		}
		String oneLine = text.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").strip();
		return oneLine.length() <= maxLength ? oneLine : oneLine.substring(0, maxLength) + "…";
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Helpers privados
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Verifica a presença da dependência do SDK (mensagem de instalação se ausente).
	 *
	 * <p>Precisa vir antes de qualquer toque em {@link MpSupport}: carregar essa classe sem o SDK
	 * dá {@code NoClassDefFoundError}, e não a mensagem de instalação.</p>
	 */
	private static void checkDependencies() {
		Dependencies.require(SDK_CLASS, SDK_COORDINATES, PAYMENTS_FEATURE);
	}

	private static void validateToken(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("[MercadoPagoAPI] Access Token não pode ser nulo ou vazio.");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Implementação (SDK — LAZY)
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Estado e lógica que dependem do SDK do Mercado Pago. Classe separada para
	 * manter as referências ao SDK fora do bytecode da {@link MercadoPagoAPI} —
	 * a classe pública pode ser vinculada sem o sdk-java e o guard exibe a
	 * mensagem de instalação antes de qualquer uso.
	 *
	 * <p>Todo estado aqui é {@code volatile}: a biblioteca roda em servidores web com muitas
	 * threads, e sem isso uma thread podia continuar vendo o SDK como "não inicializado" (ou ver
	 * as opções pela metade) depois de outra ter chamado {@code init}.</p>
	 */
	private static final class MpSupport {

		/** Rota de criação de pagamento — a mesma que o {@code PaymentClient.create} do SDK usa. */
		private static final String PAYMENTS_PATH = "/v1/payments";

		/**
		 * Nome do cabeçalho exatamente como o {@code MercadoPagoClient} do SDK procura
		 * ({@code Headers.IDEMPOTENCY_KEY} em minúsculas) ao decidir se gera a própria chave.
		 */
		private static final String IDEMPOTENCY_HEADER = Headers.IDEMPOTENCY_KEY.toLowerCase(Locale.ROOT);

		private static final String NOT_INITIALIZED =
				"[MercadoPagoAPI] SDK não inicializado. Chame MercadoPagoAPI.init(accessToken) primeiro.";

		/** Opções de cada chamada (timeouts); {@code null} até o primeiro {@code init}. */
		private static volatile MPRequestOptions requestOptions;

		/**
		 * Clientes do SDK, criados uma vez e reaproveitados.
		 *
		 * <h4>Por que não {@code new PaymentClient()} a cada chamada</h4>
		 * <p>No SDK 2.9.2, o construtor de {@code PaymentClient}, {@code PaymentRefundClient} (criado
		 * dentro dele) e {@code PreferenceClient} pega um {@code new ConsoleHandler()} e o adiciona a
		 * um {@code Logger} <strong>estático</strong>. Um cliente por chamada somava dois handlers
		 * por pagamento consultado, para sempre: medido, 1.000 clientes deixam 1.000 handlers em cada
		 * logger. Os clientes não guardam estado de chamada — só o cliente HTTP (com pool próprio,
		 * seguro entre threads) e cabeçalhos fixos lidos a cada envio —, então um de cada basta.</p>
		 *
		 * <p>Criados sob demanda, e não no carregamento da classe, porque o construtor fixa o cliente
		 * HTTP em uso no SDK naquele momento; se a criação falhar, a próxima chamada tenta de novo.</p>
		 */
		private static volatile IdempotentPaymentClient paymentClient;
		private static volatile PreferenceClient preferenceClient;

		private MpSupport() {
		}

		static void init(String accessToken, int connectionTimeoutMs, int readTimeoutMs) {
			MercadoPagoConfig.setAccessToken(accessToken);
			MercadoPagoConfig.setConnectionTimeout(connectionTimeoutMs);
			// Espera por conexão livre no pool: é espera de conexão, não de leitura. Antes o tempo de
			// leitura ia parar aqui e o socket timeout ficava com o padrão do SDK.
			MercadoPagoConfig.setConnectionRequestTimeout(connectionTimeoutMs);
			MercadoPagoConfig.setSocketTimeout(readTimeoutMs);

			requestOptions = MPRequestOptions.builder().connectionTimeout(connectionTimeoutMs)
					.connectionRequestTimeout(connectionTimeoutMs).socketTimeout(readTimeoutMs).build();
		}

		/** Opções vigentes; lança se o SDK ainda não foi inicializado. */
		static MPRequestOptions options() {
			MPRequestOptions options = requestOptions;
			if (options == null) {
				throw new IllegalStateException(NOT_INITIALIZED);
			}
			return options;
		}

		static IdempotentPaymentClient payments() {
			IdempotentPaymentClient client = paymentClient;
			if (client == null) {
				synchronized (MpSupport.class) {
					client = paymentClient;
					if (client == null) {
						client = new IdempotentPaymentClient();
						paymentClient = client;
					}
				}
			}
			return client;
		}

		static PreferenceClient preferences() {
			PreferenceClient client = preferenceClient;
			if (client == null) {
				synchronized (MpSupport.class) {
					client = preferenceClient;
					if (client == null) {
						client = new PreferenceClient();
						preferenceClient = client;
					}
				}
			}
			return client;
		}

		static PaymentPayerRequest buildPayer(String email) {
			return PaymentPayerRequest.builder().email(email).build();
		}

		static PaymentDTO createPixPayment(BigDecimal amount, String payerEmail, String description,
				String externalReference, String idempotencyKey) throws MPException {
			PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(amount)
					.description(description).paymentMethodId("pix").externalReference(externalReference)
					.payer(buildPayer(payerEmail)).build();
			return executePaymentCreation("PIX", request, idempotencyKey);
		}

		static PaymentDTO createBoletoPayment(BigDecimal amount, String payerEmail, String payerFirstName,
				String payerLastName, String payerCpf, String description, String externalReference,
				String idempotencyKey) throws MPException {
			PaymentPayerRequest payer = PaymentPayerRequest.builder().email(payerEmail).firstName(payerFirstName)
					.lastName(payerLastName)
					.identification(IdentificationRequest.builder().type("CPF").number(payerCpf).build()).build();

			PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(amount)
					.description(description).paymentMethodId("bolbradesco").externalReference(externalReference)
					.payer(payer).build();
			return executePaymentCreation("por boleto", request, idempotencyKey);
		}

		static PaymentDTO createCreditCardPayment(BigDecimal amount, int installments, String cardToken,
				String paymentMethodId, String payerEmail, String description, String externalReference,
				String idempotencyKey) throws MPException {
			PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(amount)
					.installments(installments).token(cardToken).paymentMethodId(paymentMethodId)
					.description(description).externalReference(externalReference).payer(buildPayer(payerEmail))
					.build();
			return executePaymentCreation("com cartão", request, idempotencyKey);
		}

		static PaymentDTO createPaymentWithMetadata(BigDecimal amount, String payerEmail, String paymentMethodId,
				String description, String externalReference, Map<String, Object> metadata, String idempotencyKey)
				throws MPException {
			PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(amount)
					.description(description).paymentMethodId(paymentMethodId).externalReference(externalReference)
					.metadata(metadata != null ? metadata : Collections.emptyMap()).payer(buildPayer(payerEmail))
					.build();
			return executePaymentCreation("com metadados", request, idempotencyKey);
		}

		/**
		 * Consulta um pagamento. Só o 404 é "não existe"; o resto sobe como {@link MPException}.
		 */
		static Optional<PaymentDTO> findPayment(Long paymentId) throws MPException {
			MPRequestOptions options = options();
			if (paymentId == null) {
				return Optional.empty();
			}
			try {
				Payment payment = payments().get(paymentId, options);
				return Optional.ofNullable(payment).map(MpSupport::toDTO);
			} catch (MPApiException e) {
				if (e.getStatusCode() == 404) {
					return Optional.empty();
				}
				throw failure("consultar o pagamento " + paymentId, e);
			} catch (MPException e) {
				throw failure("consultar o pagamento " + paymentId, e);
			}
		}

		/** {@link #findPayment} para métodos sem {@code throws}: a falha sobe não verificada. */
		static Optional<PaymentDTO> findPaymentUnchecked(Long paymentId) {
			try {
				return findPayment(paymentId);
			} catch (MPException e) {
				throw new MercadoPagoException(e.getMessage(), statusCodeOf(e), e);
			}
		}

		static List<PaymentDTO> searchPayments(Map<String, Object> filters, int offset, int limit)
				throws MPException {
			MPRequestOptions options = options();
			MPSearchRequest searchRequest = MPSearchRequest.builder()
					.filters(filters != null ? filters : Collections.emptyMap()).offset(offset)
					.limit(Math.min(limit, 50)).build();
			try {
				MPResultsResourcesPage<Payment> result = payments().search(searchRequest, options);
				List<PaymentDTO> dtos = new ArrayList<>();
				if (result != null && result.getResults() != null) {
					for (Payment p : result.getResults()) {
						dtos.add(toDTO(p));
					}
				}
				return dtos;
			} catch (MPApiException e) {
				throw failure("buscar pagamentos", e);
			} catch (MPException e) {
				throw failure("buscar pagamentos", e);
			}
		}

		static PreferenceDTO createPreferenceWithItems(List<PreferenceItemRequest> items, String payerEmail,
				String externalReference, String successUrl, String failureUrl, String pendingUrl)
				throws MPException {
			MPRequestOptions options = options();
			PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder().success(successUrl)
					.failure(failureUrl).pending(pendingUrl).build();

			PreferenceRequest request = PreferenceRequest.builder().items(items).backUrls(backUrls)
					.autoReturn("approved").externalReference(externalReference)
					.payer(PreferencePayerRequest.builder().email(payerEmail).build()).build();
			try {
				Preference preference = preferences().create(request, options);
				return new PreferenceDTO(preference.getId(), preference.getInitPoint(), preference.getSandboxInitPoint(),
						preference.getExternalReference());
			} catch (MPApiException e) {
				throw failure("criar a preferência de checkout", e);
			} catch (MPException e) {
				throw failure("criar a preferência de checkout", e);
			}
		}

		static PreferenceItemRequest buildPreferenceItem(String title, int quantity, BigDecimal unitPrice,
				String description, String pictureUrl) {
			PreferenceItemRequest.PreferenceItemRequestBuilder builder = PreferenceItemRequest.builder().title(title)
					.quantity(quantity).unitPrice(unitPrice).currencyId("BRL");

			if (description != null)
				builder.description(description);
			if (pictureUrl != null)
				builder.pictureUrl(pictureUrl);

			return builder.build();
		}

		static PaymentDTO executePaymentCreation(String label, PaymentCreateRequest request, String idempotencyKey)
				throws MPException {
			MPRequestOptions options = options();
			try {
				Payment payment = payments().createWithKey(request, options, idempotencyKey);
				PaymentDTO dto = toDTO(payment);
				Console.info("[MercadoPagoAPI] Pagamento %s criado — ID %s, status %s, referência %s", label,
						dto.getId(), dto.getStatus(), dto.getExternalReference());
				return dto;
			} catch (MPApiException e) {
				throw failure("criar o pagamento " + label, e);
			} catch (MPException e) {
				throw failure("criar o pagamento " + label, e);
			}
		}

		static PaymentDTO toDTO(Payment payment) {
			if (payment == null) {
				throw new IllegalArgumentException("[MercadoPagoAPI] O pagamento (Payment) não pode ser nulo.");
			}

			PaymentDTO.Builder builder = new PaymentDTO.Builder().id(payment.getId()).status(payment.getStatus())
					.statusDetail(payment.getStatusDetail()).paymentMethodId(payment.getPaymentMethodId())
					.transactionAmount(payment.getTransactionAmount()).externalReference(payment.getExternalReference())
					.description(payment.getDescription()).dateCreated(payment.getDateCreated())
					.dateApproved(payment.getDateApproved());

			// Extrai dados PIX (QR Code e copia e cola)
			if (payment.getPointOfInteraction() != null && payment.getPointOfInteraction().getTransactionData() != null) {

				var txData = payment.getPointOfInteraction().getTransactionData();
				builder.pixCopiaECola(txData.getQrCode());
				builder.pixQrCodeBase64(txData.getQrCodeBase64());
			}

			// Extrai URL do boleto
			if (payment.getTransactionDetails() != null) {
				builder.boletoUrl(payment.getTransactionDetails().getExternalResourceUrl());
			}

			return builder.build();
		}

		/**
		 * Falha da API como {@link MPException} com mensagem em português. A
		 * {@link MPApiException} vai como causa: ela não é subclasse de {@code MPException} e os
		 * métodos públicos só declaram {@code MPException} — declarar as duas quebraria quem já
		 * compila contra esta classe.
		 */
		private static MPException failure(String action, MPApiException e) {
			MPResponse response = e.getApiResponse();
			String content = response != null ? response.getContent() : null;
			StringBuilder message = new StringBuilder("[MercadoPagoAPI] Não foi possível ").append(action)
					.append(": o Mercado Pago respondeu HTTP ").append(e.getStatusCode());
			if (content == null || content.isBlank()) {
				message.append(" sem corpo (é assim que o SDK relata falha de rede, timeout e erro de TLS).");
			} else {
				message.append(" — ").append(snippet(content, ERROR_SNIPPET_LENGTH));
			}
			return new MPException(message.toString(), e);
		}

		/** Falha do SDK antes ou depois do HTTP (JSON ilegível, pedido malformado…). */
		private static MPException failure(String action, MPException e) {
			return new MPException("[MercadoPagoAPI] Não foi possível " + action + ": " + e.getMessage(), e);
		}

		/** Status HTTP da primeira {@link MPApiException} na cadeia de causas, ou -1. */
		private static int statusCodeOf(Throwable error) {
			Throwable current = error;
			for (int depth = 0; current != null && depth < 10; depth++) {
				if (current instanceof MPApiException api) {
					return api.getStatusCode();
				}
				current = current.getCause();
			}
			return -1;
		}

		/**
		 * {@link PaymentClient} que aceita uma chave de idempotência escolhida por nós.
		 *
		 * <h2>Por que não {@code MPRequestOptions.customHeaders}</h2>
		 * <p>No SDK 2.9.2, o {@code MercadoPagoClient} decide se gera a própria chave olhando apenas
		 * os cabeçalhos do {@link MPRequest} — e o {@code PaymentClient.create} monta esse pedido sem
		 * cabeçalho nenhum. Verificado com um cliente HTTP falso: com a chave em
		 * {@code customHeaders}, o SDK gera mesmo assim um UUID aleatório em
		 * {@code X-Idempotency-Key} e acrescenta a nossa como {@code x-idempotency-key}; os dois
		 * cabeçalhos vão para a rede e qual deles o Mercado Pago considera não está sob nosso
		 * controle. Chave aleatória a cada tentativa é justamente o que permite a cobrança dupla.</p>
		 *
		 * <p>Aqui a chave entra nos cabeçalhos do próprio {@link MPRequest}, com o nome que o SDK
		 * procura; ao encontrá-la, ele não gera outra e só a nossa segue no pedido. Rota, corpo e
		 * desserialização são os mesmos do {@code PaymentClient.create} do SDK.</p>
		 */
		private static final class IdempotentPaymentClient extends PaymentClient {

			Payment createWithKey(PaymentCreateRequest request, MPRequestOptions options, String idempotencyKey)
					throws MPException, MPApiException {
				if (idempotencyKey == null) {
					return create(request, options);
				}
				// Mapa mutável: o SDK acrescenta os cabeçalhos padrão nele antes de enviar.
				Map<String, String> headers = new HashMap<>();
				headers.put(IDEMPOTENCY_HEADER, idempotencyKey);
				MPRequest mpRequest = MPRequest.builder().uri(PAYMENTS_PATH).method(HttpMethod.POST).headers(headers)
						.payload(Serializer.serializeToJson(request)).build();
				MPResponse response = send(mpRequest, options);
				Payment payment = Serializer.deserializeFromJson(Payment.class, response.getContent());
				payment.setResponse(response);
				return payment;
			}
		}
	}
}
