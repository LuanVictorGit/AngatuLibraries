package br.com.angatusistemas.lib;

import java.util.UUID;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class User extends Saveable {

	private final UUID id;
	private String name;
	private String phone;
	
	@Override
	public String getId() {
		return id.toString();
	}
	
	
	
}
