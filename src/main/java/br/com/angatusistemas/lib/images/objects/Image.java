package br.com.angatusistemas.lib.images.objects;

import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;

/**
 * Imagem pequena persistida no SQLite pelo {@link Saveable} e servida pela rota
 * {@code GET /image?id=...} ({@link br.com.angatusistemas.lib.images.ImagesRoute}).
 *
 * <p><strong>Para que serve:</strong> ícone, avatar, QR Code, logo — imagem pequena e ocasional.
 * Crie com {@link br.com.angatusistemas.lib.images.ImageAPI#extractToImageObject(String, byte[])}
 * (ou as variantes para arquivo e {@code BufferedImage}), que reconhecem o formato pelos bytes e
 * gravam o tipo MIME real, e chame {@link #save()}.</p>
 *
 * <p><strong>Quando NÃO usar:</strong> galeria, foto de produto em quantidade, upload grande. O
 * {@code Saveable} grava o objeto inteiro em JSON, e o {@code byte[]} vira uma lista de números:
 * cada byte ocupa de 2 a 5 caracteres, então uma imagem de 100 KB vira cerca de 360 KB de texto, lido
 * e convertido a cada busca. Para isso, grave o arquivo no volume persistente e guarde só o caminho
 * numa entidade.</p>
 *
 * <p><strong>Formato do banco:</strong> tabela {@code images}, JSON com as chaves {@code id},
 * {@code mimeType} e {@code bytes} (lista de números). Os nomes dos campos e esse formato não mudam:
 * bancos existentes e versões anteriores da biblioteca continuam lendo os registros.</p>
 *
 * <p><strong>Imutável:</strong> os campos são {@code final}, então o {@code Saveable} não consegue
 * gerar um UUID para ela — informe o {@code id} ao criar.</p>
 *
 * @author Angatu Sistemas
 * @see br.com.angatusistemas.lib.images.ImageAPI
 * @see br.com.angatusistemas.lib.images.ImagesRoute
 */
@Getter
public class Image extends Saveable {

	/** Identificador do registro: a chave primária na tabela {@code images}. */
	private final String id;

	/**
	 * Tipo MIME da imagem (ex.: {@code image/png}). A rota {@code /image} só serve para exibição os
	 * tipos raster da lista dela; qualquer outro valor é entregue como download.
	 */
	private final String mimeType;

	/** Bytes do arquivo de imagem, como foram recebidos. */
	private final byte[] bytes;

	/**
	 * Cria a imagem com todos os campos.
	 *
	 * <p>Não valida nada: prefira as fábricas {@code ImageAPI.extractToImageObject(...)}, que
	 * reconhecem o formato pelos bytes e preenchem o tipo MIME real.</p>
	 *
	 * @param id       identificador do registro (obrigatório para gravar)
	 * @param mimeType tipo MIME da imagem
	 * @param bytes    bytes do arquivo de imagem
	 */
	public Image(String id, String mimeType, byte[] bytes) {
		this.id = id;
		this.mimeType = mimeType;
		this.bytes = bytes;
	}

	/**
	 * Construtor vazio exigido pelo contrato do {@link Saveable}: é o que o Gson usa para criar a
	 * instância antes de preencher os campos ao ler do banco. Sem ele, o Gson recorria à criação sem
	 * construtor ({@code Unsafe}), que pula toda inicialização da classe.
	 */
	protected Image() {
		this(null, null, null);
	}

	/**
	 * Identificador do registro.
	 *
	 * @return o {@code id} informado na criação
	 */
	@Override
	public String getId() {
		return id;
	}

}
