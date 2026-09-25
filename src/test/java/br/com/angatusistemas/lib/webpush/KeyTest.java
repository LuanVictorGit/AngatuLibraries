package br.com.angatusistemas.lib.webpush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import br.com.angatusistemas.lib.gson.GsonAPI;

/**
 * Ordem das chaves na entidade {@link Key} e o formato gravado no banco.
 *
 * <p>O construtor recebe (privada, pública), ao contrário do resto do módulo, e não pode mudar:
 * mudar a ordem trocaria as chaves, em silêncio, de quem já o usa. Estes testes fixam a ordem
 * do construtor, a das fábricas novas e os nomes dos campos no JSON — os bancos existentes
 * dependem deles.</p>
 *
 * @author Angatu Sistemas
 */
class KeyTest {

    private static final String PUBLIC = "chave-publica";
    private static final String PRIVATE = "chave-privada";

    @Test
    @DisplayName("o construtor recebe a chave privada primeiro e a pública depois")
    void constructorTakesPrivateFirst() {
        Key key = new Key(PRIVATE, PUBLIC);

        assertEquals(PRIVATE, key.getPrivateKey());
        assertEquals(PUBLIC, key.getPublicKey());
    }

    @Test
    @DisplayName("ofPublicAndPrivate recebe a pública primeiro, como diz o nome")
    void ofPublicAndPrivateTakesPublicFirst() {
        Key key = Key.ofPublicAndPrivate(PUBLIC, PRIVATE);

        assertEquals(PUBLIC, key.getPublicKey());
        assertEquals(PRIVATE, key.getPrivateKey());
    }

    @Test
    @DisplayName("of(VapidKeys) guarda cada chave no seu campo")
    void ofVapidKeysKeepsEachKeyInPlace() {
        Key key = Key.of(new WebPushAPI.VapidKeys(PUBLIC, PRIVATE));

        assertEquals(PUBLIC, key.getPublicKey());
        assertEquals(PRIVATE, key.getPrivateKey());
        assertThrows(NullPointerException.class, () -> Key.of(null));
    }

    @Test
    @DisplayName("o JSON gravado mantém os nomes de campo de sempre, e só eles")
    void persistedJsonKeepsTheFieldNames() {
        JsonObject json = JsonParser.parseString(GsonAPI.get().toJson(Key.ofPublicAndPrivate(PUBLIC, PRIVATE)))
                .getAsJsonObject();

        assertEquals(2, json.size(), json.toString());
        assertEquals(PRIVATE, json.get("privateKey").getAsString());
        assertEquals(PUBLIC, json.get("publicKey").getAsString());
    }

    @Test
    @DisplayName("um registro gravado pelas versões anteriores continua legível")
    void readsRecordsWrittenByPreviousVersions() {
        Key key = GsonAPI.get().fromJson("{\"privateKey\":\"" + PRIVATE + "\",\"publicKey\":\"" + PUBLIC + "\"}", Key.class);

        assertEquals(PRIVATE, key.getPrivateKey());
        assertEquals(PUBLIC, key.getPublicKey());
        assertEquals("key", key.getId());
    }
}
