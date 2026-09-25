package br.com.angatusistemas.lib.payments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assinatura do webhook do Mercado Pago: HMAC-SHA256 do manifesto, comparação em tempo constante e
 * janela de tempo do {@code ts}.
 *
 * <p>O HMAC esperado é calculado aqui de forma independente, direto com {@link Mac}. O tempo
 * constante vem de {@code MessageDigest.isEqual} e não é mensurável com segurança num teste de
 * unidade — o custo do HMAC encobre a diferença de nanossegundos —, então o que se testa são os
 * casos que uma comparação ingênua costuma errar: assinatura errada só no primeiro ou só no último
 * byte, tamanho diferente, hexadecimal inválido.</p>
 *
 * @author Angatu Sistemas
 */
class MercadoPagoWebhookSignatureTest {

    private static final String SECRET = "segredo-do-painel";
    private static final String DATA_ID = "123456789";
    private static final String REQUEST_ID = "bb56a2f1-6aae-46ac-982e-9dcd3581d08e";
    private static final long NOW_MILLIS = 1_727_000_000_000L;
    private static final long NOW_SECONDS = NOW_MILLIS / 1000L;
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    @AfterEach
    void restoreDefaultTolerance() {
        MercadoPagoAPI.setWebhookTolerance(null);
    }

    private static String hmac(String secret, String dataId, String requestId, String ts) throws Exception {
        return hmacOf(secret, "id:" + dataId + ";request-id:" + requestId + ";ts:" + ts + ";");
    }

    private static String hmacOf(String secret, String manifest) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
    }

    private static String header(String ts, String v1) {
        return "ts=" + ts + ",v1=" + v1;
    }

    private static boolean verify(String header, Duration tolerance) {
        return MercadoPagoAPI.verifyWebhookSignature(header, REQUEST_ID, DATA_ID, SECRET, tolerance, NOW_MILLIS);
    }

    private static String flipHexDigit(String hex, int index) {
        char original = hex.charAt(index);
        char replacement = original == '0' ? '1' : '0';
        return hex.substring(0, index) + replacement + hex.substring(index + 1);
    }

    // ==================== ASSINATURA ====================

    @Test
    @DisplayName("assinatura correta e recente é aceita")
    void acceptsValidRecentSignature() throws Exception {
        String ts = String.valueOf(NOW_SECONDS - 30);
        assertTrue(verify(header(ts, hmac(SECRET, DATA_ID, REQUEST_ID, ts)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("assinatura com um único dígito trocado, no início ou no fim, é recusada")
    void rejectsSignatureWrongInFirstOrLastByte() throws Exception {
        String ts = String.valueOf(NOW_SECONDS);
        String good = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        assertFalse(verify(header(ts, flipHexDigit(good, 0)), FIVE_MINUTES));
        assertFalse(verify(header(ts, flipHexDigit(good, good.length() - 1)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("segredo errado, outro ID ou outro request-id invalidam a assinatura")
    void rejectsSignatureForOtherSecretOrManifest() throws Exception {
        String ts = String.valueOf(NOW_SECONDS);
        String signedWithOtherSecret = hmac("outro-segredo", DATA_ID, REQUEST_ID, ts);
        assertFalse(verify(header(ts, signedWithOtherSecret), FIVE_MINUTES));

        String good = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, good), REQUEST_ID, "987654321", SECRET,
                FIVE_MINUTES, NOW_MILLIS));
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, good), "outro-request", DATA_ID, SECRET,
                FIVE_MINUTES, NOW_MILLIS));
    }

    @Test
    @DisplayName("hexadecimal em maiúsculas e espaços entre as partes do cabeçalho são aceitos")
    void acceptsUppercaseHexAndSpacedHeader() throws Exception {
        String ts = String.valueOf(NOW_SECONDS);
        String upper = hmac(SECRET, DATA_ID, REQUEST_ID, ts).toUpperCase(Locale.ROOT);
        assertTrue(verify(" ts=" + ts + " , v1=" + upper + " ", FIVE_MINUTES));
    }

    @Test
    @DisplayName("assinatura de tamanho errado, vazia ou fora do hexadecimal é recusada sem lançar exceção")
    void rejectsMalformedSignatureWithoutThrowing() throws Exception {
        String ts = String.valueOf(NOW_SECONDS);
        String good = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        assertFalse(verify(header(ts, good.substring(0, good.length() - 2)), FIVE_MINUTES));
        assertFalse(verify(header(ts, good + "00"), FIVE_MINUTES));
        assertFalse(verify(header(ts, ""), FIVE_MINUTES));
        assertFalse(verify(header(ts, good.substring(1)), FIVE_MINUTES)); // quantidade ímpar de dígitos
        assertFalse(verify(header(ts, "zz" + good.substring(2)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("cabeçalho sem ts ou v1, ts não numérico, cabeçalho nulo, segredo vazio ou valor que sumiu do manifesto dão falso")
    void rejectsIncompleteInput() throws Exception {
        String ts = String.valueOf(NOW_SECONDS);
        String good = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        assertFalse(verify("v1=" + good, FIVE_MINUTES));
        assertFalse(verify("ts=" + ts, FIVE_MINUTES));
        assertFalse(verify(header("12a4", good), FIVE_MINUTES));
        assertFalse(verify(header("-" + ts, good), FIVE_MINUTES));
        assertFalse(verify(null, FIVE_MINUTES));
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, good), null, DATA_ID, SECRET, FIVE_MINUTES,
                NOW_MILLIS));
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, good), REQUEST_ID, null, SECRET, FIVE_MINUTES,
                NOW_MILLIS));
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, good), REQUEST_ID, DATA_ID, "", FIVE_MINUTES,
                NOW_MILLIS));
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, good), REQUEST_ID, DATA_ID, null, FIVE_MINUTES,
                NOW_MILLIS));
    }

    @Test
    @DisplayName("sem x-request-id ou sem data.id, o par sai do manifesto, como manda a documentação oficial")
    void missingValuesLeaveTheManifest() throws Exception {
        String ts = String.valueOf(NOW_SECONDS);
        String withoutRequestId = hmacOf(SECRET, "id:" + DATA_ID + ";ts:" + ts + ";");
        assertTrue(MercadoPagoAPI.verifyWebhookSignature(header(ts, withoutRequestId), null, DATA_ID, SECRET,
                FIVE_MINUTES, NOW_MILLIS));

        String withoutDataId = hmacOf(SECRET, "request-id:" + REQUEST_ID + ";ts:" + ts + ";");
        assertTrue(MercadoPagoAPI.verifyWebhookSignature(header(ts, withoutDataId), REQUEST_ID, "", SECRET,
                FIVE_MINUTES, NOW_MILLIS));

        String onlyTimestamp = hmacOf(SECRET, "ts:" + ts + ";");
        assertTrue(MercadoPagoAPI.verifyWebhookSignature(header(ts, onlyTimestamp), null, null, SECRET,
                FIVE_MINUTES, NOW_MILLIS));
        assertFalse(MercadoPagoAPI.verifyWebhookSignature(header(ts, onlyTimestamp), REQUEST_ID, DATA_ID, SECRET,
                FIVE_MINUTES, NOW_MILLIS), "o par presente entra no manifesto");
    }

    // ==================== JANELA DE TEMPO ====================

    @Test
    @DisplayName("no padrão, o reenvio do Mercado Pago 96 horas depois, com o ts original, é aceito")
    void defaultAcceptsLateRetryWithTheOriginalTimestamp() throws Exception {
        // O Mercado Pago manda o ts em milissegundos e reenvia até 96 horas depois
        String ts = String.valueOf(System.currentTimeMillis() - Duration.ofHours(96).toMillis());
        String signature = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        assertTrue(MercadoPagoAPI.validateWebhookSignature(header(ts, signature), REQUEST_ID, DATA_ID, SECRET));
        assertFalse(MercadoPagoAPI.validateWebhookSignature(header(ts, flipHexDigit(signature, 3)), REQUEST_ID,
                DATA_ID, SECRET), "sem janela, a assinatura continua conferida");
    }

    @Test
    @DisplayName("assinatura válida mas antiga demais é recusada como possível repetição")
    void rejectsValidButStaleSignature() throws Exception {
        String ts = String.valueOf(NOW_SECONDS - Duration.ofMinutes(6).toSeconds());
        assertFalse(verify(header(ts, hmac(SECRET, DATA_ID, REQUEST_ID, ts)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("carimbo de tempo no futuro além da tolerância também é recusado")
    void rejectsTimestampTooFarInTheFuture() throws Exception {
        String ts = String.valueOf(NOW_SECONDS + Duration.ofMinutes(6).toSeconds());
        assertFalse(verify(header(ts, hmac(SECRET, DATA_ID, REQUEST_ID, ts)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("carimbo exatamente no limite da tolerância ainda é aceito")
    void acceptsTimestampExactlyAtTheLimit() throws Exception {
        String ts = String.valueOf(NOW_SECONDS - FIVE_MINUTES.toSeconds());
        assertTrue(verify(header(ts, hmac(SECRET, DATA_ID, REQUEST_ID, ts)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("ts em milissegundos é entendido como milissegundos")
    void acceptsTimestampInMilliseconds() throws Exception {
        String recent = String.valueOf(NOW_MILLIS - 10_000L);
        assertTrue(verify(header(recent, hmac(SECRET, DATA_ID, REQUEST_ID, recent)), FIVE_MINUTES));

        String stale = String.valueOf(NOW_MILLIS - Duration.ofMinutes(10).toMillis());
        assertFalse(verify(header(stale, hmac(SECRET, DATA_ID, REQUEST_ID, stale)), FIVE_MINUTES));
    }

    @Test
    @DisplayName("tolerância zero ou negativa desliga só a verificação de tempo, não a da assinatura")
    void nonPositiveToleranceDisablesOnlyTheTimeCheck() throws Exception {
        String ts = String.valueOf(NOW_SECONDS - Duration.ofDays(30).toSeconds());
        String good = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        assertTrue(verify(header(ts, good), Duration.ZERO));
        assertTrue(verify(header(ts, good), Duration.ofMinutes(-1)));
        assertFalse(verify(header(ts, flipHexDigit(good, 10)), Duration.ZERO));
    }

    @Test
    @DisplayName("a tolerância configurada vale para o método de quatro argumentos; nula volta ao padrão, desligado")
    void configuredToleranceIsUsedAndNullRestoresDefault() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L - 120L);
        String signature = hmac(SECRET, DATA_ID, REQUEST_ID, ts);

        MercadoPagoAPI.setWebhookTolerance(Duration.ofMinutes(1));
        assertFalse(MercadoPagoAPI.validateWebhookSignature(header(ts, signature), REQUEST_ID, DATA_ID, SECRET));

        MercadoPagoAPI.setWebhookTolerance(null);
        assertEquals(MercadoPagoAPI.DEFAULT_WEBHOOK_TOLERANCE, MercadoPagoAPI.getWebhookTolerance());
        assertEquals(Duration.ZERO, MercadoPagoAPI.getWebhookTolerance());
        assertTrue(MercadoPagoAPI.validateWebhookSignature(header(ts, signature), REQUEST_ID, DATA_ID, SECRET));
    }

    @Test
    @DisplayName("a sobrecarga com tolerância explícita ignora a configurada")
    void explicitToleranceOverridesConfiguredOne() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L - 120L);
        String signature = hmac(SECRET, DATA_ID, REQUEST_ID, ts);
        MercadoPagoAPI.setWebhookTolerance(Duration.ofMinutes(1));
        assertTrue(MercadoPagoAPI.validateWebhookSignature(header(ts, signature), REQUEST_ID, DATA_ID, SECRET,
                Duration.ofMinutes(10)));
    }
}
