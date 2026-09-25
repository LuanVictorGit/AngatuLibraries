# Modelos de deploy — Coolify

Arquivos prontos para copiar na raiz de qualquer projeto que usa a AngatuLibraries:

| Arquivo | Destino | Papel |
|---|---|---|
| `Dockerfile` | raiz do projeto | build Maven em duas etapas + imagem de execução com JRE 21 |
| `.dockerignore` | raiz do projeto | mantém o contexto de build enxuto e sem segredos |

## 1. Inicialização da aplicação

O `AngatuLib` sobe só em **HTTP**: certificado, renovação e redirecionamento para HTTPS são
do Coolify, e a biblioteca não tem modo HTTPS próprio. A porta vem do ambiente.
Toda configuração do servidor vem **antes** do `new AngatuLib(...)`, para valer desde a
primeira requisição:

```java
import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.javalin.JavalinAPI;

public class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        JavalinAPI.setTrustedProxyHops(1);    // o proxy do Coolify está na frente (2 com Cloudflare)
        JavalinAPI.addIgnoredPath("/health"); // usado pelo HEALTHCHECK do contêiner

        new AngatuLib("meusite.com.br", port, true);
    }
}
```

A rota `/health` é do projeto — a biblioteca não registra nenhuma:

```java
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

public class HealthRoute extends Route {
    public HealthRoute() {
        super("/health", RouteType.GET, ctx -> ctx.json("{\"status\":\"ok\"}"));
    }
}
```

## 2. Configuração no Coolify

1. **Application → Dockerfile** como build pack, apontando para o repositório.
2. **Port**: `8080` (mesma do `EXPOSE`/`PORT`).
3. **Domain**: o domínio do projeto — o Coolify emite e renova o certificado.
4. **Persistent Storage**: volume nomeado montado em `/data` — um por projeto. É
   onde ficam o `database.db` daquele projeto (SQLite do `Saveable`), `.env` e
   uploads. Sem isso, os dados somem a cada deploy. Cada aplicação tem o seu banco;
   nada é compartilhado entre projetos.
5. **Resource Limits → Memory**: defina o limite. O `-XX:MaxRAMPercentage=75` do
   Dockerfile é uma porcentagem **desse limite**; sem limite, a conta é sobre a RAM do
   servidor inteiro. Em contêiner de 1 GB ou menos, ou com Playwright, baixe para 50–60:
   o cache do SQLite, as threads e o Chromium usam memória fora do heap.
6. **Environment Variables**: as chaves do `.env` do projeto (ex.: `EMAIL_KEY`).
   A biblioteca lê variáveis de ambiente pelo mesmo `Env.get()`.
7. **Logs**: cada requisição aparece numa linha — horário, método, caminho, IP, status e
   tempo —, inclusive a do `HEALTHCHECK`. Para ver só as falhas, `ANGATU_REQUEST_LOG=errors`
   como variável do serviço; `off` desliga.

> Prefira **volume nomeado** a caminho do host: o volume herda o dono de `/data`
> na imagem (uid 10001). Se usar caminho do host, rode `chown -R 10001:10001` nele.

## 3. Antes do primeiro deploy

- `public/styles/tailwind.css` **versionado** — o Coolify constrói a partir do
  repositório, não da sua máquina; CSS gerado e não commitado gera site sem estilo.
- Rota `GET /health` respondendo 200 e fora da verificação de segurança (seção 1).
- Nenhum gancho de desligamento próprio para o banco e as tarefas: o `AngatuLib` registra
  o dele, que para o servidor, espera a fila do `Task` e fecha o `Saveable` quando o Coolify
  para o contêiner. Um `Task.shutdown()` num gancho do projeto descartaria a fila.
- Se o projeto tiver `lombok.config` ou `.mvn/` na raiz, acrescente o `COPY` deles no
  Dockerfile (ele só copia `pom.xml` e `src/`).
- Teste local do mesmo Dockerfile:

```bash
docker build -t meuprojeto . && docker run --rm -p 8080:8080 -v meuprojeto-data:/data meuprojeto
```

## 4. Projetos que usam o BrowserAPI (Playwright)

A imagem `eclipse-temurin:21-jre` não traz as dependências do Chromium. Nesses
projetos, troque a etapa de execução por `mcr.microsoft.com/playwright/java:v1.58.0-jammy`
e mantenha o resto do arquivo. A versão da imagem precisa ser a mesma da dependência
`com.microsoft.playwright:playwright` do `pom.xml`.
