package br.com.angatusistemas.lib.connection;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class Response {

	private final String body;
	private final StatusCode status;
	
	public boolean ok() {
		return status == StatusCode.OK;
	}
	
}
