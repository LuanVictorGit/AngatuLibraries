package br.com.angatusistemas.lib.images.objects;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Image extends Saveable {

	private final String id;
	private final String mimeType;
	private final byte[] bytes;
	
	@Override
	public String getId() {
		return id;
	}

}
