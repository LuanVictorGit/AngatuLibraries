package br.com.angatusistemas.lib.webpush;

import java.util.Objects;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Entidade persistida (via {@link Saveable}) que armazena o par de chaves VAPID
 * do serviço Web Push.
 *
 * <p>Como singleton: {@link #getId()} retorna sempre {@code "key"}, garantindo
 * que exista apenas um par de chaves no banco (tabela {@code keys}).</p>
 *
 * <p><strong>Atenção à ordem do construtor:</strong> {@link #Key(String, String)} recebe
 * <strong>primeiro a chave PRIVADA, depois a PÚBLICA</strong> — o contrário de todo o resto do
 * módulo ({@link WebPushAPI#initialize(String, String, String)},
 * {@link WebPushAPI.VapidKeys#VapidKeys(String, String)}), que usa (pública, privada). As duas
 * são {@code String}: trocar a ordem compila sem aviso e só aparece quando a inicialização
 * recusa as chaves. A ordem do construtor não muda — mudar trocaria as chaves, em silêncio, de
 * quem já o usa. Para código novo, prefira {@link #of(WebPushAPI.VapidKeys)} ou
 * {@link #ofPublicAndPrivate(String, String)}, que não têm como ser chamados na ordem errada.</p>
 *
 * <p><strong>Formato do banco:</strong> um único registro, com o JSON
 * {@code {"privateKey": ..., "publicKey": ...}}. Os nomes dos campos são o formato gravado: não
 * os renomeie, ou os bancos existentes deixam de ser lidos.</p>
 *
 * @author Angatu Sistemas
 * @see WebPushAPI
 * @see PushBootstrap
 */
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE, force = true)
public class Key extends Saveable {

	/** ID fixo do único registro de chaves VAPID. */
	static final String ID = "key";

	/** Chave privada VAPID (Base64URL, 43 caracteres): fica só no servidor, nunca vai ao front-end. */
	private final String privateKey;

	/** Chave pública VAPID (Base64URL, 87 caracteres): a {@code applicationServerKey} do front-end. */
	private final String publicKey;

	/**
	 * Cria o par de chaves — <strong>ATENÇÃO: a chave PRIVADA vem PRIMEIRO, a PÚBLICA depois</strong>,
	 * ao contrário do resto do módulo.
	 *
	 * <p>Para não depender da ordem, use {@link #of(WebPushAPI.VapidKeys)} ou
	 * {@link #ofPublicAndPrivate(String, String)}. Chaves gravadas trocadas são recusadas por
	 * {@link WebPushAPI#initialize(String, String, String)}, com o aviso de que estão trocadas.</p>
	 *
	 * @param privateKey Chave <strong>privada</strong> VAPID (Base64URL, 43 caracteres) — o
	 *                   primeiro parâmetro
	 * @param publicKey  Chave <strong>pública</strong> VAPID (Base64URL, 87 caracteres) — o
	 *                   segundo parâmetro
	 */
	public Key(String privateKey, String publicKey) {
		this.privateKey = privateKey;
		this.publicKey = publicKey;
	}

	/**
	 * Cria a entidade a partir do par gerado por {@link WebPushAPI#generateVapidKeys()} — a forma
	 * que não tem ordem para errar.
	 *
	 * @param keys Par de chaves VAPID
	 * @return Entidade pronta para {@link #save()}
	 * @throws NullPointerException se {@code keys} for {@code null}
	 */
	public static Key of(WebPushAPI.VapidKeys keys) {
		Objects.requireNonNull(keys, "keys não pode ser null");
		return new Key(keys.privateKey, keys.publicKey);
	}

	/**
	 * Cria a entidade com as chaves na ordem do resto do módulo: <strong>pública, depois
	 * privada</strong> — como diz o nome.
	 *
	 * @param publicKey  Chave pública VAPID (Base64URL, 87 caracteres)
	 * @param privateKey Chave privada VAPID (Base64URL, 43 caracteres)
	 * @return Entidade pronta para {@link #save()}
	 */
	public static Key ofPublicAndPrivate(String publicKey, String privateKey) {
		return new Key(privateKey, publicKey);
	}

	/**
	 * Identificador fixo: existe um único par de chaves VAPID por banco.
	 *
	 * @return Sempre {@code "key"}
	 */
	@Override
	public String getId() {
		return ID;
	}

}
