package br.com.angatusistemas.lib.webpush;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Entidade persistida (via {@link Saveable}) que armazena o par de chaves VAPID
 * do serviço Web Push.
 *
 * <p>Como singleton: {@link #getId()} retorna sempre {@code "key"}, garantindo
 * que exista apenas um par de chaves no banco (tabela {@code keys}).</p>
 *
 * @author Angatu Sistemas
 * @see WebPushAPI
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor(access = lombok.AccessLevel.PRIVATE, force = true)
public class Key extends Saveable {

	private final String privateKey;
	private final String publicKey;

	@Override
	public String getId() {
		return "key";
	}

}
