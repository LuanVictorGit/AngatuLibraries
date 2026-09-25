package br.com.angatusistemas.lib.discord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Teto de tamanho do download de imagem: o corpo é cortado no meio, e não lido inteiro para ser
 * descartado depois.
 *
 * @author Angatu Sistemas
 */
class BotImageSizeLimitTest {

    /** Assinatura falsa que só registra o que o assinante pediu. */
    private static final class RecordingSubscription implements Flow.Subscription {
        long requested;
        boolean cancelled;

        @Override
        public void request(long n) {
            requested += n;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private static byte[] body(Bot.CappedBody subscriber) throws Exception {
        return subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("corpo dentro do limite chega inteiro")
    void bodyWithinLimitIsKept() throws Exception {
        Bot.CappedBody subscriber = new Bot.CappedBody(10, OptionalLong.empty(), false);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), ByteBuffer.wrap(new byte[] { 4, 5 })));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] { 6, 7, 8, 9, 10 })));
        subscriber.onComplete();
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 }, body(subscriber));
        assertTrue(subscription.requested > 0);
    }

    @Test
    @DisplayName("passar do limite cancela a conexão e falha o download")
    void exceedingLimitCancels() {
        Bot.CappedBody subscriber = new Bot.CappedBody(10, OptionalLong.empty(), false);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[8])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[3])));
        assertTrue(subscription.cancelled);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> body(subscriber));
        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    @DisplayName("Content-Length acima do limite falha antes de ler qualquer byte")
    void declaredLengthAboveLimitFailsImmediately() {
        Bot.CappedBody subscriber = new Bot.CappedBody(10, OptionalLong.of(11), false);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        assertTrue(subscription.cancelled);
        assertEquals(0, subscription.requested);
        assertThrows(ExecutionException.class, () -> body(subscriber));
    }

    @Test
    @DisplayName("resposta que não é 2xx (redirecionamento, erro) não tem o corpo lido")
    void discardModeNeverReads() throws Exception {
        Bot.CappedBody subscriber = new Bot.CappedBody(0, OptionalLong.empty(), true);
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        assertTrue(subscription.cancelled);
        assertEquals(0, subscription.requested);
        assertEquals(0, body(subscriber).length);
    }

    @Test
    @DisplayName("o limite padrão é de 10 MiB")
    void defaultLimitIsTenMebibytes() {
        assertEquals(10 * 1024 * 1024, Bot.MAX_IMAGE_BYTES);
    }
}
