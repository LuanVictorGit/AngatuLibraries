package br.com.angatusistemas.lib.images;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.images.objects.Image;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;
import io.javalin.http.Context;

/**
 * Rota HTTP GET {@code /image?id=...} que busca uma imagem persistida no banco (via
 * {@link Saveable}) e a devolve ao navegador.
 *
 * <p>É descoberta e registrada automaticamente durante a inicialização do servidor, junto com as
 * demais rotas. Qualquer um que conheça o {@code id} recebe a imagem: não guarde nesta entidade
 * imagem que exija login.</p>
 *
 * <h2>Por que a rota não confia no tipo gravado</h2>
 * <p>A resposta sai da origem da própria aplicação. Se ela devolvesse o tipo gravado como veio, um
 * SVG com {@code <script>} gravado como {@code image/svg+xml} — ou um HTML gravado como
 * {@code text/html} — rodaria como página da aplicação, com acesso aos cookies e à sessão de quem o
 * abrisse (XSS armazenado). Versões antigas da biblioteca gravavam o tipo informado pelo chamador,
 * então esses registros existem em bancos de produção. Por isso, em toda resposta:</p>
 * <ul>
 *   <li>só os tipos raster que o navegador exibe como imagem e nunca executa — PNG, JPEG, GIF, WebP,
 *       AVIF, BMP e ICO — são servidos para exibição; qualquer outro vai como
 *       {@code application/octet-stream} com {@code Content-Disposition: attachment}, um download;</li>
 *   <li>{@code X-Content-Type-Options: nosniff} impede o navegador de "adivinhar" outro tipo pelo
 *       conteúdo;</li>
 *   <li>{@code Content-Security-Policy: default-src 'none'; sandbox} faz com que, mesmo aberto
 *       direto numa aba, o arquivo não carregue nada nem execute script, numa origem isolada.</li>
 * </ul>
 * <p>As mensagens de erro são fixas: nada interno (exceção, SQL, caminho) chega ao cliente.</p>
 *
 * @author Angatu Sistemas
 * @see Image
 * @see ImageAPI#extractToImageObject(String, byte[])
 */
public class ImagesRoute extends Route {

    /** Política desta rota: nada carrega e nada executa, nem se o arquivo for um documento. */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; sandbox";

    /** Tipo de tudo o que não está na lista de exibição: o navegador baixa, não interpreta. */
    static final String DOWNLOAD_TYPE = "application/octet-stream";

    /** Tipos raster que o navegador exibe como imagem e nunca executa como documento. */
    private static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
            "image/avif", "image/bmp", "image/x-icon");

    /** Grafias antigas ou alternativas, gravadas por versões e sistemas anteriores, do mesmo tipo. */
    private static final Map<String, String> ALIASES = Map.of(
            "image/jpg", "image/jpeg",
            "image/pjpeg", "image/jpeg",
            "image/x-png", "image/png",
            "image/x-ms-bmp", "image/bmp",
            "image/vnd.microsoft.icon", "image/x-icon");

    /** Tamanho máximo do {@code id} copiado para o log. */
    private static final int MAX_LOGGED_ID = 80;

    /**
     * Cria a rota {@code GET /image}. Chamado pela descoberta automática de rotas, com o servidor já
     * inicializado.
     */
    public ImagesRoute() {
        super("/image", RouteType.GET, ImagesRoute::serve);
    }

    /**
     * Atende {@code GET /image?id=...}: 400 sem {@code id}, 404 sem imagem, 500 se o banco falhar e
     * 200 com os bytes — com o tipo e os cabeçalhos descritos no Javadoc da classe.
     */
    static void serve(Context request) {
        // Antes de qualquer resposta, inclusive as de erro.
        request.header("X-Content-Type-Options", "nosniff");
        request.header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        String id = request.queryParam("id");
        try {
            if (id == null || id.trim().isEmpty()) {
                request.status(StatusCode.BAD_REQUEST.code())
                       .result("Parâmetro 'id' é obrigatório.");
                return;
            }

            Image image = Saveable.findById(Image.class, id);
            if (image == null || image.getBytes() == null) {
                request.status(StatusCode.NOT_FOUND.code())
                       .result("Imagem não encontrada.");
                return;
            }

            String inlineType = inlineContentType(image.getMimeType());
            if (inlineType != null) {
                request.contentType(inlineType);
            } else {
                request.contentType(DOWNLOAD_TYPE);
                request.header("Content-Disposition", "attachment");
            }
            request.status(StatusCode.OK.code());
            request.result(image.getBytes());

        } catch (Exception e) {
            Console.error("Erro ao servir imagem id=%s", printable(id), e);
            request.status(StatusCode.INTERNAL_SERVER_ERROR.code())
                   .result("Erro interno ao buscar imagem.");
        }
    }

    /**
     * Tipo com que um registro pode ser servido para exibição, ou {@code null} quando ele deve ir
     * como download.
     *
     * <p>Normaliza o que foi gravado — minúsculas, sem parâmetros ({@code ; charset=...}), grafias
     * antigas como {@code image/jpg} — e só então confere a lista. Nunca devolve o texto gravado:
     * só uma constante da lista.</p>
     *
     * @param storedMimeType tipo gravado no registro (pode ser nulo ou qualquer texto)
     * @return tipo canônico da lista de exibição, ou {@code null}
     */
    static String inlineContentType(String storedMimeType) {
        if (storedMimeType == null) {
            return null;
        }
        String type = storedMimeType;
        int parameters = type.indexOf(';');
        if (parameters >= 0) {
            type = type.substring(0, parameters);
        }
        type = type.trim().toLowerCase(Locale.ROOT);
        type = ALIASES.getOrDefault(type, type);
        for (String allowed : INLINE_TYPES) {
            if (allowed.equals(type)) {
                return allowed;
            }
        }
        return null;
    }

    /** O {@code id} vem do cliente: sem quebra de linha nem sequência de controle no log, e curto. */
    private static String printable(String id) {
        if (id == null) {
            return "null";
        }
        StringBuilder clean = new StringBuilder(Math.min(id.length(), MAX_LOGGED_ID));
        for (int i = 0; i < id.length() && clean.length() < MAX_LOGGED_ID; i++) {
            char c = id.charAt(i);
            clean.append(Character.isISOControl(c) ? '?' : c);
        }
        return clean.toString();
    }
}
