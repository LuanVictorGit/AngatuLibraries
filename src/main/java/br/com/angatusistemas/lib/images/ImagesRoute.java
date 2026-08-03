package br.com.angatusistemas.lib.images;

import br.com.angatusistemas.lib.connection.StatusCode;
import br.com.angatusistemas.lib.console.Console;
import br.com.angatusistemas.lib.database.Saveable;
import br.com.angatusistemas.lib.images.objects.Image;
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

/**
 * Rota HTTP GET {@code /image?id=...} que busca uma imagem persistida no banco
 * (via {@link Saveable}) e a retorna com o MIME type correto.
 *
 * <p>É descoberta e registrada automaticamente durante a inicialização do
 * servidor, junto com as demais rotas.</p>
 *
 * @author Angatu Sistemas
 * @see Image
 */
public class ImagesRoute extends Route {

    /**
     * Cria a rota {@code GET /image}.
     */
    public ImagesRoute() {
        super("/image", RouteType.GET, request -> {
            try {

                String id = request.queryParam("id");
                if (id == null || id.trim().isEmpty()) {
                    request.status(StatusCode.BAD_REQUEST.code())
                           .result("Parâmetro 'id' é obrigatório.");
                    return;
                }

                Image image = Saveable.findById(Image.class, id);
                if (image == null) {
                    request.status(StatusCode.NOT_FOUND.code())
                           .result("Imagem não encontrada.");
                    return;
                }

                request.contentType(image.getMimeType());
                request.status(StatusCode.OK.code());
                request.result(image.getBytes());

            } catch (Exception e) {
                Console.error("Erro ao servir imagem id=%s", request.queryParam("id"), e);
                request.status(StatusCode.INTERNAL_SERVER_ERROR.code())
                       .result("Erro interno ao buscar imagem.");
            }
        });
    }
}