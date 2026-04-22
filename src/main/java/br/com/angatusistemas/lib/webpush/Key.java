package br.com.angatusistemas.lib.webpush;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Key extends Saveable {

	private final String privateKey;
	private final String publicKey;
	@Override
	public String getId() {
		return "key";
	}
	
}
