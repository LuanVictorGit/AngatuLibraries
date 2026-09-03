# Modelos de deploy — Coolify

Arquivos prontos para copiar na raiz de qualquer projeto que usa a AngatuLibraries:

| Arquivo | Destino | Papel |
|---|---|---|
| `Dockerfile` | raiz do projeto | build Maven em duas etapas + imagem de execução com JRE 21 |
| `.dockerignore` | raiz do projeto | mantém o contexto de build enxuto e sem segredos |

## 1. Inicialização da aplicação

O `AngatuLib` sobe em **HTTP** e o Coolify cuida do certificado. A porta vem do ambiente:

```java
public class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        JavalinAPI.setTrustedProxyHops(1); // o proxy do Coolify está na frente
        new AngatuLib("meusite.com.br", port, true);

        JavalinAPI.addIgnoredPath("/health"); // usado pelo HEALTHCHECK do contêiner
    }
}
```

Só peça HTTPS ao Javalin fora do Coolify, quando o próprio servidor tiver os certificados:

```java
new AngatuLib("meusite.com.br", 443, true, true); // exige /etc/letsencrypt/live/meusite.com.br
```

## 2. Configuração no Coolify

1. **Application → Docker­file** como build pack, apontando para o repositório.
2. **Port**: `8080` (mesma do `EXPOSE`/`PORT`).
3. **Domain**: o domínio do projeto — o Coolify emite e renova o certificado.
4. **Persistent Storage**: volume nomeado montado em `/data`. É onde ficam
   `database.db` (SQLite do `Saveable`), `.env` e uploads. Sem isso, os dados
   somem a cada deploy.
5. **Environment Variables**: as chaves do `.env` (`EMAIL_KEY`, `MP_ACCESS_TOKEN`, …).
   A biblioteca lê variáveis de ambiente pelo mesmo `Env.get()`.

> Prefira **volume nomeado** a caminho do host: o volume herda o dono de `/data`
> na imagem (uid 10001). Se usar caminho do host, rode `chown -R 10001:10001` nele.

## 3. Antes do primeiro deploy

- `public/styles/tailwind.css` **versionado** — o Coolify constrói a partir do
  repositório, não da sua máquina; CSS gerado e não commitado gera site sem estilo.
- Rota `GET /health` respondendo 200 e fora do rate limit.
- `Saveable.shutdown()` e `Task.shutdown()` em `Runtime.getRuntime().addShutdownHook(...)`.
- Teste local do mesmo Dockerfile:

```bash
docker build -t meuprojeto . && docker run --rm -p 8080:8080 -v meuprojeto-data:/data meuprojeto
```

## 4. Projetos que usam o BrowserAPI (Playwright)

A imagem `eclipse-temurin:21-jre` não traz as dependências do Chromium. Nesses
projetos, troque a etapa de execução por `mcr.microsoft.com/playwright/java:v1.58.0-jammy`
e mantenha o resto do arquivo.
