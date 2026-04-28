package br.com.angatusistemas.lib.payments;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.payment.PaymentCreateRequest;
import com.mercadopago.client.payment.PaymentPayerRequest;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.core.MPRequestOptions;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResultsResourcesPage;
import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;

/**
 * Classe utilitária completa para integração com o SDK oficial do Mercado Pago
 * (Java SDK 2.9.2).
 *
 * <p>
 * Oferece métodos estáticos para:
 * <ul>
 * <li>Inicialização e configuração do Access Token</li>
 * <li>Criação e gerenciamento de pagamentos (PIX, cartão, boleto)</li>
 * <li>Consulta e filtragem de pagamentos</li>
 * <li>Criação de preferências de checkout</li>
 * <li>Processamento de webhooks</li>
 * <li>Conversão de respostas para DTOs internos</li>
 * </ul>
 *
 * <p>
 * <b>Uso básico:</b>
 * 
 * <pre>{@code
 * MercadoPagoAPI.init(System.getenv("MP_ACCESS_TOKEN"));
 * PaymentDTO pix = MercadoPagoAPI.createPixPayment(100.00, "pagador@email.com", "Pedido #123", "order-001");
 * System.out.println(pix.getPixCopiaECola());
 * }</pre>
 *
 * @author Gerado por Claude (Anthropic)
 * @version 1.0
 */
public final class MercadoPagoAPI {

	// ─────────────────────────────────────────────────────────────────────────
	// Constantes e Logger
	// ─────────────────────────────────────────────────────────────────────────

	private static final Logger LOGGER = Logger.getLogger(MercadoPagoAPI.class.getName());

	/** Tempo padrão de conexão em milissegundos (10 segundos). */
	private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 10_000;

	/** Tempo padrão de leitura em milissegundos (30 segundos). */
	private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

	// ─────────────────────────────────────────────────────────────────────────
	// Estado da classe
	// ─────────────────────────────────────────────────────────────────────────

	private static boolean initialized = false;
	private static MPRequestOptions defaultRequestOptions;

	/** Construtor privado — classe utilitária, não deve ser instanciada. */
	private MercadoPagoAPI() {
		throw new UnsupportedOperationException("Classe utilitária — não instanciar.");
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Inicialização
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Inicializa o SDK com o Access Token fornecido, usando timeouts padrão.
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
	 * @param accessToken         Access Token do Mercado Pago
	 * @param connectionTimeoutMs Timeout de conexão em milissegundos
	 * @param readTimeoutMs       Timeout de leitura em milissegundos
	 * @throws IllegalArgumentException se o token for nulo ou vazio
	 */
	public static void init(String accessToken, int connectionTimeoutMs, int readTimeoutMs) {
		validateToken(accessToken);

		MercadoPagoConfig.setAccessToken(accessToken);
		MercadoPagoConfig.setConnectionTimeout(connectionTimeoutMs);
		MercadoPagoConfig.setSocketTimeout(readTimeoutMs);

		defaultRequestOptions = MPRequestOptions.builder().connectionTimeout(connectionTimeoutMs)
				.connectionRequestTimeout(readTimeoutMs).build();

		initialized = true;
		LOGGER.info("[MercadoPagoAPI] SDK inicializado com sucesso.");
	}

	/**
	 * Inicializa o SDK lendo o Access Token da variável de ambiente
	 * {@code MP_ACCESS_TOKEN}.
	 *
	 * @throws IllegalStateException se a variável de ambiente não estiver definida
	 */
	public static void initFromEnv() {
		String token = System.getenv("MP_ACCESS_TOKEN");
		if (token == null || token.isBlank()) {
			throw new IllegalStateException("[MercadoPagoAPI] Variável de ambiente MP_ACCESS_TOKEN não está definida.");
		}
		init(token);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// DTO interno
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
		private final String pixCopiaECola;
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
			this.pixCopiaECola = b.pixCopiaECola;
			this.pixQrCodeBase64 = b.pixQrCodeBase64;
			this.boletoUrl = b.boletoUrl;
			this.checkoutUrl = b.checkoutUrl;
			this.dateCreated = b.dateCreated;
			this.dateApproved = b.dateApproved;
		}

		/** @return ID do pagamento no Mercado Pago */
		public Long getId() {
			return id;
		}

		/**
		 * @return Status do pagamento (approved, pending, rejected, cancelled,
		 *         refunded, charged_back)
		 */
		public String getStatus() {
			return status;
		}

		/** @return Detalhe do status */
		public String getStatusDetail() {
			return statusDetail;
		}

		/** @return Método de pagamento (pix, credit_card, bolbradesco, etc.) */
		public String getPaymentMethodId() {
			return paymentMethodId;
		}

		/** @return Valor da transação */
		public BigDecimal getTransactionAmount() {
			return transactionAmount;
		}

		/** @return Referência externa (ID interno do sistema) */
		public String getExternalReference() {
			return externalReference;
		}

		/** @return Descrição do pagamento */
		public String getDescription() {
			return description;
		}

		/** @return Código PIX copia e cola (apenas para pagamentos PIX) */
		public String getPixCopiaECola() {
			return pixCopiaECola;
		}

		/** @return QR Code PIX em Base64 (apenas para pagamentos PIX) */
		public String getPixQrCodeBase64() {
			return pixQrCodeBase64;
		}

		/** @return URL do boleto bancário */
		public String getBoletoUrl() {
			return boletoUrl;
		}

		/** @return URL de checkout (para preferências) */
		public String getCheckoutUrl() {
			return checkoutUrl;
		}

		/** @return Data de criação do pagamento */
		public OffsetDateTime getDateCreated() {
			return dateCreated;
		}

		/** @return Data de aprovação do pagamento */
		public OffsetDateTime getDateApproved() {
			return dateApproved;
		}

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
			private String pixCopiaECola;
			private String pixQrCodeBase64;
			private String boletoUrl;
			private String checkoutUrl;
			private OffsetDateTime dateCreated;
			private OffsetDateTime dateApproved;

			public Builder id(Long id) {
				this.id = id;
				return this;
			}

			public Builder status(String status) {
				this.status = status;
				return this;
			}

			public Builder statusDetail(String statusDetail) {
				this.statusDetail = statusDetail;
				return this;
			}

			public Builder paymentMethodId(String paymentMethodId) {
				this.paymentMethodId = paymentMethodId;
				return this;
			}

			public Builder transactionAmount(BigDecimal transactionAmount) {
				this.transactionAmount = transactionAmount;
				return this;
			}

			public Builder externalReference(String externalReference) {
				this.externalReference = externalReference;
				return this;
			}

			public Builder description(String description) {
				this.description = description;
				return this;
			}

			public Builder pixCopiaECola(String pixCopiaECola) {
				this.pixCopiaECola = pixCopiaECola;
				return this;
			}

			public Builder pixQrCodeBase64(String pixQrCodeBase64) {
				this.pixQrCodeBase64 = pixQrCodeBase64;
				return this;
			}

			public Builder boletoUrl(String boletoUrl) {
				this.boletoUrl = boletoUrl;
				return this;
			}

			public Builder checkoutUrl(String checkoutUrl) {
				this.checkoutUrl = checkoutUrl;
				return this;
			}

			public Builder dateCreated(OffsetDateTime dateCreated) {
				this.dateCreated = dateCreated;
				return this;
			}

			public Builder dateApproved(OffsetDateTime dateApproved) {
				this.dateApproved = dateApproved;
				return this;
			}

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

		/** @return ID da preferência */
		public String getId() {
			return id;
		}

		/** @return URL de checkout (produção) */
		public String getInitPoint() {
			return initPoint;
		}

		/** @return URL de checkout (sandbox) */
		public String getSandboxInitPoint() {
			return sandboxInitPoint;
		}

		/** @return Referência externa */
		public String getExternalReference() {
			return externalReference;
		}

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
		APPROVED("approved"), PENDING("pending"), AUTHORIZED("authorized"), IN_PROCESS("in_process"),
		IN_MEDIATION("in_mediation"), REJECTED("rejected"), CANCELLED("cancelled"), REFUNDED("refunded"),
		CHARGED_BACK("charged_back"), UNKNOWN("unknown");

		private final String value;

		PaymentStatus(String value) {
			this.value = value;
		}

		/** @return Valor do status como String */
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

	// ─────────────────────────────────────────────────────────────────────────
	// Pagamentos — Criação
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Cria um pagamento PIX e retorna o QR Code (copia e cola e base64).
	 *
	 * <p>
	 * O pagamento fica com status {@code pending} até o pagador efetuar o PIX.
	 *
	 * @param amount            Valor do pagamento (não pode ser nulo ou negativo)
	 * @param payerEmail        E-mail do pagador
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema (ex: ID do pedido)
	 * @return {@link PaymentDTO} com {@code pixCopiaECola} e
	 *         {@code pixQrCodeBase64} preenchidos
	 * @throws MPException    em caso de erro no SDK
	 * @throws MPApiException em caso de erro retornado pela API
	 */
	public static PaymentDTO createPixPayment(double amount, String payerEmail, String description,
			String externalReference) throws MPException {

		ensureInitialized();
		LOGGER.fine("[MercadoPagoAPI] Criando pagamento PIX para: " + payerEmail);

		PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(BigDecimal.valueOf(amount))
				.description(description).paymentMethodId("pix").externalReference(externalReference)
				.payer(buildPayer(payerEmail)).build();

		return executePaymentCreation(request);
	}

	/**
	 * Cria um pagamento via boleto bancário (Bradesco).
	 *
	 * @param amount            Valor do pagamento
	 * @param payerEmail        E-mail do pagador
	 * @param payerFirstName    Primeiro nome do pagador
	 * @param payerLastName     Sobrenome do pagador
	 * @param payerCpf          CPF do pagador (somente dígitos)
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema
	 * @return {@link PaymentDTO} com {@code boletoUrl} preenchido
	 * @throws MPException em caso de erro no SDK
	 */
	public static PaymentDTO createBoletoPayment(double amount, String payerEmail, String payerFirstName,
			String payerLastName, String payerCpf, String description, String externalReference) throws MPException {

		ensureInitialized();
		LOGGER.fine("[MercadoPagoAPI] Criando boleto para: " + payerEmail);

		PaymentPayerRequest payer = PaymentPayerRequest.builder().email(payerEmail).firstName(payerFirstName)
				.lastName(payerLastName).identification(com.mercadopago.client.common.IdentificationRequest.builder()
						.type("CPF").number(payerCpf).build())
				.build();

		PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(BigDecimal.valueOf(amount))
				.description(description).paymentMethodId("bolbradesco").externalReference(externalReference)
				.payer(payer).build();

		return executePaymentCreation(request);
	}

	/**
	 * Cria um pagamento com cartão de crédito usando o token gerado pelo Checkout
	 * Bricks / SDK JS.
	 *
	 * @param amount            Valor do pagamento
	 * @param installments      Número de parcelas
	 * @param cardToken         Token do cartão gerado pelo frontend
	 * @param paymentMethodId   ID do método de pagamento (ex: {@code visa},
	 *                          {@code master})
	 * @param payerEmail        E-mail do pagador
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna do sistema
	 * @return {@link PaymentDTO} com o resultado da transação
	 * @throws MPException em caso de erro no SDK
	 */
	public static PaymentDTO createCreditCardPayment(double amount, int installments, String cardToken,
			String paymentMethodId, String payerEmail, String description, String externalReference)
			throws MPException {

		ensureInitialized();
		LOGGER.fine("[MercadoPagoAPI] Criando pagamento com cartão " + paymentMethodId + " para: " + payerEmail);

		PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(BigDecimal.valueOf(amount))
				.installments(installments).token(cardToken).paymentMethodId(paymentMethodId).description(description)
				.externalReference(externalReference).payer(buildPayer(payerEmail)).build();

		return executePaymentCreation(request);
	}

	/**
	 * Cria um pagamento genérico com metadados customizados (chave-valor).
	 *
	 * <p>
	 * Use {@code metadata} para armazenar informações adicionais do seu sistema sem
	 * interferir nos campos oficiais da API.
	 *
	 * @param amount            Valor do pagamento
	 * @param payerEmail        E-mail do pagador
	 * @param paymentMethodId   Método de pagamento (ex: {@code pix},
	 *                          {@code bolbradesco})
	 * @param description       Descrição do pagamento
	 * @param externalReference Referência interna
	 * @param metadata          Mapa de chave-valor com informações adicionais
	 * @return {@link PaymentDTO} com o resultado da transação
	 * @throws MPException em caso de erro no SDK
	 */
	public static PaymentDTO createPaymentWithMetadata(double amount, String payerEmail, String paymentMethodId,
			String description, String externalReference, Map<String, Object> metadata) throws MPException {

		ensureInitialized();
		LOGGER.fine("[MercadoPagoAPI] Criando pagamento com metadata: " + metadata);

		PaymentCreateRequest request = PaymentCreateRequest.builder().transactionAmount(BigDecimal.valueOf(amount))
				.description(description).paymentMethodId(paymentMethodId).externalReference(externalReference)
				.metadata(metadata != null ? metadata : Collections.emptyMap()).payer(buildPayer(payerEmail)).build();

		return executePaymentCreation(request);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Consultas
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Busca um pagamento pelo ID.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@link Optional} com o {@link PaymentDTO} se encontrado, ou vazio se
	 *         não existir
	 * @throws MPException    em caso de erro no SDK
	 * @throws MPApiException
	 */
	public static Optional<PaymentDTO> findById(Long paymentId) {
		try {
			ensureInitialized();
			LOGGER.fine("[MercadoPagoAPI] Buscando pagamento ID: " + paymentId);

			try {
				PaymentClient client = new PaymentClient();
				Payment payment = client.get(paymentId, defaultRequestOptions);
				return Optional.ofNullable(payment).map(MercadoPagoAPI::toDTO);
			} catch (MPApiException e) {
				if (e.getStatusCode() == 404) {
					return Optional.empty();
				}
				throw e;
			}
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Busca todos os pagamentos associados a uma {@code external_reference}.
	 *
	 * <p>
	 * Útil para rastrear pagamentos por ID de pedido interno do sistema.
	 *
	 * @param externalReference Referência externa definida na criação do pagamento
	 * @return Lista (possivelmente vazia) de {@link PaymentDTO}
	 * @throws MPException em caso de erro no SDK
	 */
	public static List<PaymentDTO> findByExternalReference(String externalReference) throws MPException {
		ensureInitialized();
		LOGGER.fine("[MercadoPagoAPI] Buscando pagamentos por external_reference: " + externalReference);

		Map<String, Object> filters = new HashMap<>();
		filters.put("external_reference", externalReference);

		return searchPayments(filters, 0, 50);
	}

	/**
	 * Lista pagamentos com filtros dinâmicos.
	 *
	 * <p>
	 * Filtros aceitos pela API (chaves como String):
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
	 * @param filters Mapa de filtros conforme documentação da API
	 * @param offset  Offset para paginação
	 * @param limit   Máximo de resultados por página (máx. 50)
	 * @return Lista de {@link PaymentDTO}
	 * @throws MPException em caso de erro no SDK
	 */
	public static List<PaymentDTO> searchPayments(Map<String, Object> filters, int offset, int limit)
			throws MPException {

		try {
			ensureInitialized();
			LOGGER.fine("[MercadoPagoAPI] Buscando pagamentos com filtros: " + filters);

			MPSearchRequest searchRequest = MPSearchRequest.builder()
					.filters(filters != null ? filters : Collections.emptyMap()).offset(offset)
					.limit(Math.min(limit, 50)).build();

			PaymentClient client = new PaymentClient();
			MPResultsResourcesPage<Payment> result = client.search(searchRequest, defaultRequestOptions);

			List<PaymentDTO> dtos = new ArrayList<>();
			if (result != null && result.getResults() != null) {
				for (Payment p : result.getResults()) {
					dtos.add(toDTO(p));
				}
			}
			return dtos;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}

	}

	/**
	 * Busca pagamentos por status.
	 *
	 * @param status Status desejado (usar valores de {@link PaymentStatus})
	 * @return Lista de {@link PaymentDTO}
	 * @throws MPException em caso de erro no SDK
	 */
	public static List<PaymentDTO> findByStatus(PaymentStatus status) throws MPException {
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
	 * @return {@link PaymentStatus} atual, ou {@link PaymentStatus#UNKNOWN} se não
	 *         encontrado
	 * @throws MPException em caso de erro no SDK
	 */
	public static PaymentStatus checkPaymentStatus(Long paymentId) throws MPException {
		return findById(paymentId).map(dto -> PaymentStatus.fromString(dto.getStatus())).orElse(PaymentStatus.UNKNOWN);
	}

	/**
	 * Verifica se um pagamento está aprovado.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@code true} se aprovado, {@code false} caso contrário
	 * @throws MPException em caso de erro no SDK
	 */
	public static boolean isApproved(Long paymentId) throws MPException {
		return checkPaymentStatus(paymentId) == PaymentStatus.APPROVED;
	}

	/**
	 * Verifica se um pagamento está pendente.
	 *
	 * @param paymentId ID do pagamento
	 * @return {@code true} se pendente, {@code false} caso contrário
	 * @throws MPException em caso de erro no SDK
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
	 * @throws MPException em caso de erro no SDK
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
	 * @param title             Título do produto/serviço
	 * @param quantity          Quantidade
	 * @param unitPrice         Preço unitário
	 * @param payerEmail        E-mail do pagador
	 * @param externalReference Referência interna do sistema
	 * @param successUrl        URL de redirecionamento após pagamento aprovado
	 * @param failureUrl        URL de redirecionamento após falha
	 * @param pendingUrl        URL de redirecionamento enquanto pendente
	 * @return {@link PreferenceDTO} com o link de checkout
	 * @throws MPException em caso de erro no SDK
	 */
	public static PreferenceDTO createPreference(String title, int quantity, double unitPrice, String payerEmail,
			String externalReference, String successUrl, String failureUrl, String pendingUrl) throws MPException {

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
	 * @return {@link PreferenceDTO} com o link de checkout
	 * @throws MPException em caso de erro no SDK
	 */
	public static PreferenceDTO createPreferenceWithItems(List<PreferenceItemRequest> items, String payerEmail,
			String externalReference, String successUrl, String failureUrl, String pendingUrl) throws MPException {

		try {
			ensureInitialized();
			LOGGER.fine("[MercadoPagoAPI] Criando preferência para: " + payerEmail);

			PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder().success(successUrl)
					.failure(failureUrl).pending(pendingUrl).build();

			PreferenceRequest request = PreferenceRequest.builder().items(items).backUrls(backUrls)
					.autoReturn("approved").externalReference(externalReference)
					.payer(com.mercadopago.client.preference.PreferencePayerRequest.builder().email(payerEmail).build())
					.build();

			PreferenceClient client = new PreferenceClient();
			Preference preference = client.create(request, defaultRequestOptions);

			return new PreferenceDTO(preference.getId(), preference.getInitPoint(), preference.getSandboxInitPoint(),
					preference.getExternalReference());
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Constrói um {@link PreferenceItemRequest} para uso em
	 * {@link #createPreferenceWithItems}.
	 *
	 * @param title       Título do item
	 * @param quantity    Quantidade
	 * @param unitPrice   Preço unitário
	 * @param description Descrição (pode ser {@code null})
	 * @param pictureUrl  URL da imagem do produto (pode ser {@code null})
	 * @return {@link PreferenceItemRequest} configurado
	 */
	public static PreferenceItemRequest buildPreferenceItem(String title, int quantity, double unitPrice,
			String description, String pictureUrl) {

		PreferenceItemRequest.PreferenceItemRequestBuilder builder = PreferenceItemRequest.builder().title(title)
				.quantity(quantity).unitPrice(BigDecimal.valueOf(unitPrice)).currencyId("BRL");

		if (description != null)
			builder.description(description);
		if (pictureUrl != null)
			builder.pictureUrl(pictureUrl);

		return builder.build();
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Webhooks
	// ─────────────────────────────────────────────────────────────────────────

	/**
	 * Extrai o ID do pagamento a partir de uma notificação de webhook do Mercado
	 * Pago.
	 *
	 * <p>
	 * O Mercado Pago envia webhooks com a seguinte estrutura JSON:
	 * 
	 * <pre>{@code
	 * {
	 *   "type": "payment",
	 *   "data": { "id": "123456789" }
	 * }
	 * }</pre>
	 *
	 * <p>
	 * <b>Importante:</b> valide o cabeçalho {@code x-signature} antes de processar.
	 * Consulte: <a href=
	 * "https://www.mercadopago.com.br/developers/pt/docs/your-integrations/notifications/webhooks">Documentação</a>
	 *
	 * @param webhookPayload Corpo JSON do webhook como {@link Map} (parseado
	 *                       previamente)
	 * @return {@link Optional} com o ID do pagamento (Long), ou vazio se não for
	 *         notificação de pagamento
	 */
	@SuppressWarnings("unchecked")
	public static Optional<Long> extractPaymentIdFromWebhook(Map<String, Object> webhookPayload) {
		if (webhookPayload == null)
			return Optional.empty();

		String type = (String) webhookPayload.get("type");
		if (!"payment".equals(type)) {
			LOGGER.fine("[MercadoPagoAPI] Webhook ignorado (tipo não é 'payment'): " + type);
			return Optional.empty();
		}

		Object dataObj = webhookPayload.get("data");
		if (!(dataObj instanceof Map))
			return Optional.empty();

		Map<String, Object> data = (Map<String, Object>) dataObj;
		Object idObj = data.get("id");
		if (idObj == null)
			return Optional.empty();

		try {
			return Optional.of(Long.parseLong(idObj.toString()));
		} catch (NumberFormatException e) {
			LOGGER.warning("[MercadoPagoAPI] Não foi possível converter ID do webhook: " + idObj);
			return Optional.empty();
		}
	}

	/**
	 * Processa um webhook e busca o pagamento correspondente na API.
	 *
	 * <p>
	 * Combina {@link #extractPaymentIdFromWebhook} com {@link #findById} para
	 * facilitar o fluxo completo de processamento.
	 *
	 * @param webhookPayload Corpo JSON do webhook como {@link Map}
	 * @return {@link Optional} com o {@link PaymentDTO} se o pagamento for
	 *         encontrado
	 * @throws MPException em caso de erro ao consultar a API
	 */
	public static Optional<PaymentDTO> processWebhook(Map<String, Object> webhookPayload) throws MPException {
		Optional<Long> paymentId = extractPaymentIdFromWebhook(webhookPayload);
		if (!paymentId.isPresent())
			return Optional.empty();

		LOGGER.info("[MercadoPagoAPI] Processando webhook para pagamento ID: " + paymentId.get());
		return findById(paymentId.get());
	}

	/**
	 * Valida a assinatura de um webhook do Mercado Pago.
	 *
	 * <p>
	 * Usa HMAC-SHA256 para verificar a autenticidade da notificação. Requer o
	 * {@code secret} configurado no painel do Mercado Pago (Webhooks).
	 *
	 * @param xSignatureHeader Valor do cabeçalho {@code x-signature}
	 * @param xRequestId       Valor do cabeçalho {@code x-request-id}
	 * @param dataId           ID do dado (ex: ID do pagamento da query string
	 *                         {@code data.id})
	 * @param secret           Secret configurado no painel do Mercado Pago
	 * @return {@code true} se a assinatura for válida, {@code false} caso contrário
	 */
	public static boolean validateWebhookSignature(String xSignatureHeader, String xRequestId, String dataId,
			String secret) {

		if (xSignatureHeader == null || xRequestId == null || dataId == null || secret == null) {
			return false;
		}

		try {
			// Extrai ts= e v1= do header x-signature
			String ts = null;
			String v1 = null;

			for (String part : xSignatureHeader.split(",")) {
				String trimmed = part.trim();
				if (trimmed.startsWith("ts=")) {
					ts = trimmed.substring(3);
				} else if (trimmed.startsWith("v1=")) {
					v1 = trimmed.substring(3);
				}
			}

			if (ts == null || v1 == null)
				return false;

			// Monta o manifest conforme documentação do MP
			String manifest = "id:" + dataId + ";request-id:" + xRequestId + ";ts:" + ts + ";";

			// Calcula HMAC-SHA256
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
					"HmacSHA256"));
			byte[] hash = mac.doFinal(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));

			StringBuilder hexHash = new StringBuilder();
			for (byte b : hash) {
				hexHash.append(String.format("%02x", b));
			}

			boolean valid = hexHash.toString().equals(v1);
			if (!valid) {
				LOGGER.warning("[MercadoPagoAPI] Assinatura de webhook inválida.");
			}
			return valid;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "[MercadoPagoAPI] Erro ao validar assinatura do webhook.", e);
			return false;
		}
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
	 */
	public static PaymentDTO toDTO(Payment payment) {
		if (payment == null) {
			throw new IllegalArgumentException("Payment não pode ser nulo.");
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

	// ─────────────────────────────────────────────────────────────────────────
	// Helpers privados
	// ─────────────────────────────────────────────────────────────────────────

	private static void ensureInitialized() {
		if (!initialized) {
			throw new IllegalStateException(
					"[MercadoPagoAPI] SDK não inicializado. Chame MercadoPagoAPI.init(accessToken) primeiro.");
		}
	}

	private static void validateToken(String token) {
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("[MercadoPagoAPI] Access Token não pode ser nulo ou vazio.");
		}
	}

	private static PaymentPayerRequest buildPayer(String email) {
		return PaymentPayerRequest.builder().email(email).build();
	}

	private static PaymentDTO executePaymentCreation(PaymentCreateRequest request) {
		try {
			PaymentClient client = new PaymentClient();
			Payment payment = client.create(request, defaultRequestOptions);
			PaymentDTO dto = toDTO(payment);
			LOGGER.info("[MercadoPagoAPI] Pagamento criado — ID: " + dto.getId() + " | Status: " + dto.getStatus());
			return dto;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
}

// =============================================================================
//  EXEMPLOS DE USO  (remover em produção ou mover para testes)
// =============================================================================
//
// ── 1. Inicialização ──────────────────────────────────────────────────────────
//
//   // Via variável de ambiente MP_ACCESS_TOKEN (recomendado)
//   MercadoPagoAPI.initFromEnv();
//
//   // Ou passando o token diretamente
//   MercadoPagoAPI.init(System.getenv("MP_ACCESS_TOKEN"));
//
//   // Com timeouts personalizados (ms)
//   MercadoPagoAPI.init("APP_USR-...", 5_000, 15_000);
//
//
// ── 2. Criando pagamento PIX ──────────────────────────────────────────────────
//
//   MercadoPagoAPI.initFromEnv();
//
//   PaymentDTO pix = MercadoPagoAPI.createPixPayment(
//           150.00,
//           "cliente@email.com",
//           "Pedido #1234 - Loja XYZ",
//           "order-1234");
//
//   System.out.println("ID:           " + pix.getId());
//   System.out.println("Status:       " + pix.getStatus());
//   System.out.println("Copia e Cola: " + pix.getPixCopiaECola());
//   System.out.println("QR Base64:    " + pix.getPixQrCodeBase64());
//
//
// ── 3. Criando boleto ─────────────────────────────────────────────────────────
//
//   PaymentDTO boleto = MercadoPagoAPI.createBoletoPayment(
//           200.00,
//           "cliente@email.com",
//           "João", "Silva", "12345678909",
//           "Assinatura mensal",
//           "sub-2024-09");
//
//   System.out.println("URL Boleto: " + boleto.getBoletoUrl());
//
//
// ── 4. Criando pagamento com metadata ─────────────────────────────────────────
//
//   Map<String, Object> meta = new HashMap<>();
//   meta.put("userId", "usr_42");
//   meta.put("planId", "premium");
//   meta.put("origin", "app-mobile");
//
//   PaymentDTO payment = MercadoPagoAPI.createPaymentWithMetadata(
//           99.90,
//           "user@empresa.com",
//           "pix",
//           "Assinatura Premium",
//           "invoice-0099",
//           meta);
//
//
// ── 5. Consultando pagamento por ID ──────────────────────────────────────────
//
//   Optional<PaymentDTO> found = MercadoPagoAPI.findById(123456789L);
//   found.ifPresent(p -> System.out.println("Status: " + p.getStatus()));
//
//
// ── 6. Buscando por external_reference ───────────────────────────────────────
//
//   List<PaymentDTO> payments = MercadoPagoAPI.findByExternalReference("order-1234");
//   payments.forEach(p -> System.out.println(p.getId() + " -> " + p.getStatus()));
//
//
// ── 7. Verificando status ─────────────────────────────────────────────────────
//
//   PaymentStatus status = MercadoPagoAPI.checkPaymentStatus(123456789L);
//   System.out.println("Status: " + status);              // APPROVED, PENDING, etc.
//
//   if (MercadoPagoAPI.isApproved(123456789L)) {
//       System.out.println("Pagamento aprovado! Liberar produto.");
//   }
//
//
// ── 8. Cancelando e reembolsando ─────────────────────────────────────────────
//
//   PaymentDTO cancelled = MercadoPagoAPI.cancelPayment(123456789L);
//   System.out.println("Status após cancelamento: " + cancelled.getStatus());
//
//   PaymentDTO refunded = MercadoPagoAPI.refundPayment(123456789L);
//   System.out.println("Status após reembolso: " + refunded.getStatus());
//
//   // Reembolso parcial de R$ 50,00
//   PaymentDTO partial = MercadoPagoAPI.refundPartial(123456789L, 50.00);
//
//
// ── 9. Criando preferência de checkout ───────────────────────────────────────
//
//   PreferenceDTO pref = MercadoPagoAPI.createPreference(
//           "Camiseta Geek XL",
//           2,
//           79.90,
//           "comprador@email.com",
//           "cart-555",
//           "https://loja.com/sucesso",
//           "https://loja.com/erro",
//           "https://loja.com/pendente");
//
//   System.out.println("Link checkout: " + pref.getInitPoint());
//   System.out.println("Link sandbox:  " + pref.getSandboxInitPoint());
//
//
// ── 10. Preferência com múltiplos itens ───────────────────────────────────────
//
//   List<PreferenceItemRequest> items = new ArrayList<>();
//   items.add(MercadoPagoAPI.buildPreferenceItem("Produto A", 1, 50.00, "Desc A", null));
//   items.add(MercadoPagoAPI.buildPreferenceItem("Produto B", 3, 25.00, "Desc B", "https://img.url/b.png"));
//
//   PreferenceDTO multiPref = MercadoPagoAPI.createPreferenceWithItems(
//           items,
//           "comprador@email.com",
//           "cart-multi-123",
//           "https://loja.com/ok",
//           "https://loja.com/fail",
//           "https://loja.com/wait");
//
//
// ── 11. Processando webhook ───────────────────────────────────────────────────
//
//   // Em um Controller Spring Boot, por exemplo:
//   @PostMapping("/webhook/mercadopago")
//   public ResponseEntity<Void> handleWebhook(
//           @RequestHeader("x-signature") String sig,
//           @RequestHeader("x-request-id") String reqId,
//           @RequestParam("data.id") String dataId,
//           @RequestBody Map<String, Object> payload) {
//
//       String secret = System.getenv("MP_WEBHOOK_SECRET");
//
//       if (!MercadoPagoAPI.validateWebhookSignature(sig, reqId, dataId, secret)) {
//           return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
//       }
//
//       MercadoPagoAPI.processWebhook(payload).ifPresent(payment -> {
//           if (MercadoPagoAPI.PaymentStatus.APPROVED == PaymentStatus.fromString(payment.getStatus())) {
//               // Liberar acesso, enviar e-mail, etc.
//           }
//       });
//
//       return ResponseEntity.ok().build();
//   }
//
// =============================================================================