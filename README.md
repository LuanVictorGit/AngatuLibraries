<p align="center">
  <img src="https://angatusistemas.com.br/favicon.ico" alt="Angatu Sistemas" width="120"/>
</p>

<h1 align="center">AngatuLibraries</h1>

<p align="center">
  <strong>Framework de utilidades para projetos Java</strong><br/>
  Web · Persistência · E-mail · Web Push · Discord · IA · Imagens · QR Code · Pagamentos
</p>

> 📖 **Nota para agentes de IA:** este README é a documentação principal da
> biblioteca. Consulte o [índice](#-índice) para navegar; cada seção é
> autocontida e as [seções de arquitetura](#-arquitetura) descrevem os padrões
> de uso esperados. Para detalhes de API, consulte os JavaDocs das classes
> citadas (visíveis na IDE ou gerados com `mvn javadoc:javadoc`).

---

## 📑 Índice

1. [Introdução](#-introdução)
2. [Filosofia e objetivos](#-filosofia-e-objetivos)
3. [Arquitetura](#-arquitetura)
4. [Recursos disponíveis](#-recursos-disponíveis)
5. [Instalação](#-instalação)
6. [Dependências por módulo](#-dependências-por-módulo)
7. [Configuração](#-configuração)
8. [Primeiros passos](#-primeiros-passos)
9. [Estrutura recomendada para projetos](#-estrutura-recomendada-para-projetos)
10. [Deploy no Coolify (Docker)](#-deploy-no-coolify-docker)
11. [Guias por funcionalidade](#-guias-por-funcionalidade)
12. [Boas práticas](#-boas-práticas)
13. [Solução de problemas comuns](#-solução-de-problemas-comuns)
14. [FAQ](#-faq)
15. [Migração entre versões](#-migração-entre-versões)
16. [Changelog resumido](#-changelog-resumido)

---

## 📌 Introdução

O **AngatuLibraries** é uma biblioteca utilitária desenvolvida pela **Angatu Sistemas** que centraliza soluções comuns de backend Java em uma única dependência: servidor web com segurança integrada (Javalin), persistência automática em SQLite, envio de e-mails, notificações Web Push, bot de Discord, cliente de IA (DeepSeek), captura de tela e scraping (Playwright), geração/leitura de QR Codes, manipulação de imagens e integração com Mercado Pago.

A biblioteca foi projetada para ser **leve, modular e segura**:

* 🪶 **Leve** — o JAR contém apenas o código da biblioteca (~380 KB). As dependências de terceiros **não são empacotadas**; você adiciona somente as dos módulos que usa.
* 🛡️ **Segura** — rate limiting com janela deslizante, recusa de entrada com cara de SQL Injection/XSS, bloqueios longos persistidos e headers de segurança automáticos.
* 🧩 **Modular** — cada funcionalidade é opcional e detecta dependências ausentes com instruções claras de instalação.
* 📖 **Documentada** — todas as APIs públicas possuem JavaDocs completos (visíveis nas IDEs).

---

## 🧭 Filosofia e objetivos

* **Leveza por design** — o JAR contém apenas o código da biblioteca. Nada de dependências embutidas: o consumidor declara somente o que usa, e a biblioteca orienta a instalação quando algo falta.
* **Segurança por padrão** — servidor web nasce com rate limiting, proteção contra SQL Injection/XSS, headers de segurança e bloqueios persistidos. Segurança não é configuração opcional: é o estado inicial.
* **Produtividade com convenções** — rotas por descoberta automática, persistência por herança (`extends Saveable`), logging centralizado. Menos boilerplate, menos erro humano.
* **Compatibilidade como contrato** — a API pública é estável; melhorias entram por sobrecarga, novas classes ou novas interfaces, nunca por quebra de assinatura.
* **Segurança arquitetural** — classes-base (`Saveable`, `Route`) só funcionam por herança; utilitários são `final` com construtor privado. Uso incorreto falha cedo, com mensagens que explicam o caminho certo.

---

## 🏗 Arquitetura

```
┌──────────────────────────────────────────────────────────────────────┐
│                            SUA APLICAÇÃO                             │
│  Main → new AngatuLib(host, porta, rateLimit)                        │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ bootstrap
┌──────────────────────────────▼───────────────────────────────────────┐
│                    AngatuLibraries (JAR ~380 KB)                     │
│                                                                      │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────────────────┐   │
│  │  Console    │  │ Dependencies │  │  Task (pools de threads)   │   │
│  │  (log ANSI) │  │  (detecção)  │  └─────────────┬──────────────┘   │
│  └──────┬──────┘  └──────┬───────┘                │ async            │
│         │                │                        │                  │
│  ┌──────▼────────────────▼────────────────────────▼──────────────┐   │
│  │                   JavalinAPI (servidor web)                   │   │
│  │ HTTP (TLS no Coolify) · Headers · Rate limit · SQLi/XSS · Log │   │
│  │ ┌────────────────┐  ┌─────────────────┐  ┌──────────────────┐ │   │
│  │ │ Route (abstr.) │  │ HtmlRouteAPI    │  │ AssetsAPI        │ │   │
│  │ │ rotas auto     │  │ páginas /public │  │ MIME, sem cache  │ │   │
│  │ └───────┬────────┘  └─────────────────┘  └──────────────────┘ │   │
│  └─────────┼─────────────────────────────────────────────────────┘   │
│            │ persistência (bloqueios, configurações)                 │
│  ┌─────────▼─────────────────────────────────────────────────────┐   │
│  │  Saveable (ORM JSON → SQLite + HikariCP + WAL)                │   │
│  │  entidades: PermanentBlock · SuspectIp · RouteRateLimitConfig │   │
│  │             Key (VAPID) · Image · suas entidades (extends)    │   │
│  └───────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  Módulos opcionais (cada um confere a própria dependência):          │
│  EmailAPI → jakarta.mail        WebPushAPI → web-push + BC + jose4j  │
│  Bot → JDA                      DeepSeek → gson + java.net.http      │
│  BrowserAPI → Playwright        MercadoPagoAPI → sdk-java            │
│  ImageAPI/QRCodeAPI → thumbnailator/zxing/twelvemonkeys              │
│  GsonAPI/Env/Password/StringAPI/DataTime/Request → utilitários       │
└──────────────────────────────────────────────────────────────────────┘
```

**Camadas:**

| Camada | Papel |
|---|---|
| **Bootstrap** | `AngatuLib` — inicialização única, detecção de dependências, redirecionamento do log |
| **Núcleo** | `Console`, `Dependencies`, `Task`, `GsonAPI`, `Env`, `StringAPI`, `DataTime`, `Password`, `Request` — sem dependências externas ou com as mínimas |
| **Web** | `JavalinAPI`, `HtmlRouteAPI`, `AssetsAPI`, `Route`/`RouteType`, `IP` — servidor e segurança |
| **Persistência** | `Saveable` (abstrato, por herança) + entidades internas |
| **Integrações** | `EmailAPI`, `WebPushAPI`, `Bot`, `DeepSeek`, `BrowserAPI`, `ImageAPI`, `QRCodeAPI`, `MercadoPagoAPI` — cada um com dependência opcional própria |

### Conceitos principais

| Conceito | Descrição |
|---|---|
| **Guard de dependência** | Verificação via reflection no primeiro uso de um módulo; se a biblioteca externa faltar, imprime coordenadas + snippets Maven/Gradle e lança `MissingDependencyException` com a mesma mensagem |
| **Persistência direta** | O `Saveable` lê e grava direto no SQLite a cada operação — sem cache em memória. Cada busca devolve uma instância nova e toda alteração exige `save()` |
| **Um escritor, muitos leitores** | Toda gravação do processo passa por uma única conexão de escrita, numa fila justa; as leituras correm em paralelo num pool só de leitura (SQLite em WAL). Não existe disputa pelo arquivo dentro do processo, e nenhuma leitura segura duas conexões — é o que sustenta milhares de leituras e gravações ao mesmo tempo |
| **Janela deslizante** | Algoritmo de rate limiting por timestamps dentro de uma janela (segundo/minuto) — `SlidingWindowCounter` com fila O(1) |
| **Descoberta de rotas** | Subclasses de `Route` com construtor vazio são encontradas via Reflections e registradas antes de o servidor aceitar conexões |
| **IP do cliente** | `IP.get(ctx)` é a regra única: respeita `setTrustedProxyHops`, e sem ele só lê o `X-Forwarded-For` quando a conexão vem de rede privada (o proxy do Coolify) |
| **Bloqueios persistidos** | Bloqueios longos (24 h) e as configurações de rota sobrevivem a reinicializações (tabelas `permanentblocks`, `routeratelimitconfigs`); o histórico de violações fica em `suspectips`. Bloqueio temporário vive só em memória |
| **Log interceptado** | `System.out` é redirecionado para o `Console` (log colorido com timestamp); o stream original fica preservado |

### Fluxo de funcionamento

```
main()
 └─ new AngatuLib(host, porta, rateLimit)
     ├─ 1. Dependencies.require("io.javalin.Javalin", ...)   → mensagem clara se faltar
     ├─ 2. System.setOut(InterceptorOutputStream → Console)  → log colorido
     ├─ 3. Resolve o ambiente (ANGATU_ENV / host local) → isLocalhost()
     ├─ 4. JavalinAPI.setup(porta, rateLimit, HtmlRouteAPI::registerAllRoutes)
     │      ├─ loadPersistedConfigs()   → bloqueios longos e configs do banco, índices
     │      ├─ filtros: headers + rate limiting + SQLi/XSS (HTTP e upgrade de WebSocket)
     │      ├─ log de requisições: uma linha por requisição, escrita por fila própria
     │      ├─ rotas descobertas (Route) e páginas /public/*.html
     │      ├─ start: HTTP na porta informada (o TLS é do Coolify)
     │      └─ Task: limpeza diária do banco + varredura do rate limit a cada minuto
     └─ 5. Banner de inicialização
```

---

## ✨ Recursos disponíveis

| Módulo | Classe principal | Descrição |
|---|---|---|
| 🌐 **Web Server** | `JavalinAPI`, `HtmlRouteAPI`, `Route`, `IP` | Servidor HTTP (Javalin 7.2.3) com rate limiting, recusa de SQLi/XSS, rotas por convenção e uma linha no terminal para cada requisição; o TLS é do Coolify |
| 📁 **Assets** | `AssetsAPI` | Ler e servir arquivos de `public/` com o MIME type certo (sem cache por padrão) |
| 🗄️ **Persistência** | `Saveable` | ORM JSON sobre SQLite (HikariCP + WAL), sem nada em memória: uma conexão de escrita, leitura em paralelo |
| 📨 **E-mail** | `EmailAPI`, `EmailFormatter` | Envio SMTP (Gmail) assíncrono, HTML, anexos, múltiplos destinatários e validação |
| 🔔 **Web Push** | `WebPushAPI`, `PushBootstrap` | Notificações push (VAPID/AES128GCM), geração de chaves e assinaturas |
| 🤖 **Discord** | `Bot` | Mensagens, imagens e botões interativos via JDA |
| 🧠 **IA** | `DeepSeek` | Chat completions com streaming (SSE) |
| 🖥️ **Navegador** | `BrowserAPI` | Screenshots full-page e scraping headless (Playwright) + utilitários de HTML |
| 🖼️ **Imagens** | `ImageAPI`, `QRCodeAPI` | Redimensionamento, thumbnails, GIF animado, Base64 e QR Codes |
| 💳 **Pagamentos** | `MercadoPagoAPI` | PIX, boleto, cartão, preferências e webhooks |
| ⚙️ **Tarefas** | `Task` | Execução assíncrona, com delay, timers e cancelamento |
| 🔤 **Utilidades** | `StringAPI`, `DataTime`, `Password`, `Env`, `Console` | Strings, datas, BCrypt, variáveis de ambiente (.env) e log colorido |
| 🔗 **HTTP Client** | `Request` | Requisições HTTP simples com token Bearer (sem dependências) |

---

## 📦 Instalação

### Requisitos

* **Java 21** ou superior
* **Maven** ou **Gradle**

### Maven (via JitPack)

Adicione o repositório:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

Adicione a dependência:

```xml
<dependency>
    <groupId>com.github.LuanVictorGit</groupId>
    <artifactId>AngatuLibraries</artifactId>
    <version>VERSION</version>
</dependency>
```

> Substitua `VERSION` pelo **hash de um commit da `main`** — o repositório não publica tags nem
> releases, e o JitPack gera a versão a partir do commit (veja em
> https://jitpack.io/#LuanVictorGit/AngatuLibraries). Evite `main-SNAPSHOT`: a camada de
> dependências do `Dockerfile` congela o snapshot que resolveu primeiro, e o deploy seguinte
> pode subir com uma versão antiga sem aviso.

### Gradle (via JitPack)

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.LuanVictorGit:AngatuLibraries:VERSION'
}
```

---

## 🧩 Dependências por módulo

A biblioteca **não** empacota nem propaga dependências de terceiros. Cada módulo verifica a presença da sua dependência em tempo de execução: se faltar, exibe uma mensagem padronizada com as instruções exatas de instalação (Maven e Gradle) — sem travar a inicialização da aplicação.

> **Como funciona a verificação:** a maioria das classes públicas é **linkável sem as dependências** — os guards rodam no primeiro uso e exibem a mensagem de instalação. Classes que expõem tipos de terceiros na própria assinatura pública — como `JavalinAPI` (tipos do Javalin) e os TypeAdapters de datas (estendem `TypeAdapter` do Gson) — exigem a dependência já no carregamento: a ausência gera `NoClassDefFoundError` nomeando a classe que falta.

> **Exemplo da mensagem exibida:**
> ```
> [AngatuLibraries] Dependência ausente: io.javalin:javalin:7.2.3
>
> A funcionalidade "Web Server (Javalin)" depende desta biblioteca, mas ela não foi encontrada no classpath.
>
> Para habilitar esta funcionalidade, adicione:
>
> Maven:
> <dependency>
>     <groupId>io.javalin</groupId>
>     <artifactId>javalin</artifactId>
>     <version>7.2.3</version>
> </dependency>
>
> Gradle:
> implementation("io.javalin:javalin:7.2.3")
> ```

### Tabela de dependências

| Módulo | Dependências necessárias |
|---|---|
| Web Server, HTML, Assets, Rotas | `io.javalin:javalin:7.2.3`, `org.reflections:reflections:0.10.2` (rotas automáticas), + um binding SLF4J (ex: `org.slf4j:slf4j-simple:2.0.17`), **+ as de Persistência** (os bloqueios e as configurações de rota são gravados pelo `Saveable`) |
| Persistência (`Saveable`) | `org.xerial:sqlite-jdbc:3.51.3.0`, `com.zaxxer:HikariCP:7.0.2`, `com.google.code.gson:gson:2.13.2` |
| JSON (`GsonAPI`) | `com.google.code.gson:gson:2.13.2` |
| `.env` (`Env`) | `io.github.cdimascio:dotenv-java:3.2.0` |
| Senhas (`Password`) | `org.mindrot:jbcrypt:0.4` |
| Web Push | `nl.martijndwars:web-push:5.1.2`, `org.bouncycastle:bcprov-jdk18on:1.86`, `org.bitbucket.b_c:jose4j:0.9.6`, `org.apache.httpcomponents:httpclient:4.5.14`, `com.google.code.gson:gson:2.13.2`, **+ as de Persistência** (o `PushBootstrap` guarda as chaves VAPID pelo `Saveable`) |
| E-mail | `com.sun.mail:jakarta.mail:2.0.2`, `io.github.cdimascio:dotenv-java:3.2.0` |
| Discord | `net.dv8tion:JDA:6.4.1`, `io.github.cdimascio:dotenv-java:3.2.0` (o token vem do `Env`) |
| IA (`DeepSeek`) — só para o AngatuCRM | `com.google.code.gson:gson:2.13.2`, `io.github.cdimascio:dotenv-java:3.2.0` |
| Pagamentos (`MercadoPagoAPI`) — só para o AngatuCRM | `com.mercadopago:sdk-java:2.9.2`, `io.github.cdimascio:dotenv-java:3.2.0` (para `initFromEnv`) |
| Navegador (Playwright) | `com.microsoft.playwright:playwright:1.58.0` (+ executar `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` uma vez) |
| Imagens | `net.coobird:thumbnailator:0.4.21` (thumbnails), `com.twelvemonkeys.imageio:imageio-webp:3.12.0` e `com.twelvemonkeys.imageio:imageio-tiff:3.12.0` (formatos extras) |
| QR Code | `com.google.zxing:core:3.5.3`, `com.google.zxing:javase:3.5.3` |

> **Pagamentos e IA em projeto cliente:** projetos da Angatu cobram e usam IA pela API do
> AngatuCRM, nunca por `MercadoPagoAPI` ou `DeepSeek` direto — essas classes existem para o
> próprio CRM, o único lugar onde mora a credencial do provedor.

---

## ⚙ Configuração

### Arquivo `.env`

Várias funcionalidades leem credenciais do arquivo `.env` na raiz do projeto:

```env
# E-mail (EmailAPI)
EMAIL_KEY=seuemail@gmail.com
EMAIL_PASSWORD=senhaapp

# Discord (Bot)
DISCORD_BOT_TOKEN=seu_token_do_bot

# IA (DeepSeek)
DEEPSEEK_API_KEY=sua_chave
```

Uma variável de ambiente de verdade vence o `.env` com o mesmo nome. As configurações da própria
biblioteca (`ANGATU_ENV`, `ANGATU_DB_PATH`, `ANGATU_DB_*`, `ANGATU_TASK_THREADS`,
`ANGATU_WEBPUSH_SUBJECT`) são lidas **só** do ambiente do processo: no `.env` elas não valem. No
Coolify, cadastre-as como variáveis do serviço. Um `.env` malformado (BOM, aspas sem fechar,
`export CHAVE=`) não derruba mais a aplicação: o aviso aponta a linha e a chave, nunca o valor.

### Modo debug

Habilite logs de debug (nível `DEBUG`) via propriedade de sistema ou em tempo de execução:

```bash
java -Dangatu.debug=true -jar sua-app.jar
```

```java
Console.setDebugEnabled(true);
```

---

## 🚀 Primeiros passos

O ponto de entrada é a classe `AngatuLib`. A biblioteca roda **só atrás do Coolify**: o servidor sobe em **HTTP na porta informada**, e certificado, renovação e redirecionamento para HTTPS são do proxy de borda do Coolify. Não há modo HTTPS próprio nem pasta de certificados.

```java
import br.com.angatusistemas.lib.AngatuLib;

public class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        // Coolify: HTTP na porta que vem do ambiente
        new AngatuLib("meusite.com.br", port, true);

        // Desenvolvimento local
        // new AngatuLib("localhost", 8080, true);
    }
}
```

**Assinatura:** `new AngatuLib(String host, int port, boolean bloqByMaxRequisitions)`. O construtor de quatro parâmetros, do antigo HTTPS gerenciado, está depreciado: com `manageSsl = false` ele é igual a este; com `true`, lança `UnsupportedOperationException` antes de tocar em qualquer coisa.

**Ambiente:** `isLocalhost()` é decidido nesta ordem:
1. `-Dangatu.env`, depois `ANGATU_ENV`, depois `ENVIRONMENT` — valor começando por `prod` é produção; começando por `dev`, ou `local`/`test`, é desenvolvimento;
2. host local (`localhost`, `127.0.0.1`, `0.0.0.0`, `::1`, `[::1]`, `host.docker.internal`, `*.local`) → desenvolvimento;
3. na ausência de tudo, **produção**.

Ao iniciar, a biblioteca:
1. Verifica as dependências dos módulos usados (mensagens claras se faltarem);
2. Redireciona `System.out` para o log colorido do `Console`;
3. Configura o Javalin com headers de segurança, rate limiting e o log de requisições (uma linha no terminal para cada uma);
4. Registra as rotas (`Route`) e as páginas HTML de `/public` **antes** de aceitar conexões — nenhuma requisição chega sem filtro ou antes da rota existir;
5. Sobe o servidor e agenda a limpeza diária do banco e a varredura do rate limit a cada minuto.

---

## 📁 Estrutura recomendada para projetos

```
seu-projeto/
├── src/main/java/
│   └── com/suaempresa/
│       ├── Main.java                  ← configuração + new AngatuLib(...) no main
│       ├── routes/                    ← classes que estendem Route (descoberta automática)
│       │   ├── HealthRoute.java
│       │   └── ListOrdersRoute.java
│       ├── entities/                  ← classes que estendem Saveable
│       │   ├── User.java
│       │   └── Product.java
│       ├── services/                  ← lógica de negócio (EmailAPI, WebPush, etc.)
│       └── config/                    ← rate limits, paths, proxy
├── src/main/resources/
│   └── public/                        ← HTML servidos automaticamente
│       ├── index.html                 ← template base (não vira rota)
│       ├── sobre.html                 ← vira GET /sobre
│       ├── emails/                    ← templates de e-mail (loadHtmlTemplate); não viram rota
│       └── css/ · js/ · img/
├── .env                               ← EMAIL_KEY, DISCORD_BOT_TOKEN, ...
└── pom.xml / build.gradle
```

**Regras da estrutura:**
- Pacotes, classes, métodos e variáveis em inglês; Javadoc e comentários em português;
- Uma rota por classe (construtor vazio + `super(path, type, handler)`) — a descoberta automática as registra no startup;
- Uma entidade por classe (extends `Saveable` + construtor vazio + `getId()`) — tabela criada automaticamente;
- Toda configuração de segurança (`setTrustedProxyHops`, `configureRateLimit`, `addIgnoredPath`) em um único lugar (classe `config`), chamada **antes** do `new AngatuLib(...)`, para valer desde a primeira requisição;
- `.env` na raiz (nunca versionar segredos). Tudo o que está em `public/` é público — inclusive `public/emails/`.

---

## 🐳 Deploy no Coolify (Docker)

Todo projeto que usa a AngatuLibraries é publicado pelo **Coolify** e, por isso,
tem um `Dockerfile` na raiz. Os modelos prontos estão em
[`templates/`](templates/) — `Dockerfile` e `.dockerignore` para copiar no projeto,
com o passo a passo em [`templates/README.md`](templates/README.md).

O essencial:

| Item | Valor |
|---|---|
| Porta | lida de `PORT` (padrão `8080`), exposta no `Dockerfile` e configurada no Coolify |
| TLS | do Coolify — a aplicação sobe em HTTP (`new AngatuLib(host, port, true)`) |
| Proxy | `JavalinAPI.setTrustedProxyHops(1)` (2 com Cloudflare na frente) para o IP real chegar ao rate limiting |
| Volume | `/data` montado como *persistent storage*; `ANGATU_DB_PATH=/data/database.db` |
| Memória | limite definido no Coolify (Resource Limits); o `-XX:MaxRAMPercentage=75` do `Dockerfile` é relativo a ele |
| Ambiente | `ANGATU_ENV=production` já vem no `Dockerfile` |
| Saúde | `GET /health` → 200 — **rota do projeto** (a biblioteca não registra nenhuma), com `JavalinAPI.addIgnoredPath("/health")`; usada pelo `HEALTHCHECK` |

```bash
# valide a mesma imagem localmente antes de subir
docker build -t meuprojeto . && docker run --rm -p 8080:8080 -v meuprojeto-data:/data meuprojeto
```

---

## 📖 Guias por funcionalidade

### 🌐 Servidor web com segurança (JavalinAPI)

```java
import br.com.angatusistemas.lib.AngatuLib;
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;

public class Main {
    public static void main(String[] args) {
        // Toda a configuração ANTES do new AngatuLib: vale desde a primeira requisição
        JavalinAPI.setTrustedProxyHops(1);                 // proxy do Coolify na frente

        // Em cada rota de /api: 3 req/s, 20 req/min, bloqueio de 2 min por IP
        JavalinAPI.configureRateLimit("/api/*", new RateLimitConfig(3, 20, 120));

        // Presets prontos
        JavalinAPI.configureApiRateLimit("/api/v1/*");
        JavalinAPI.configureLoginRateLimit("/login");

        // Paths especiais
        JavalinAPI.addUnlimitedPath("/downloads/*");   // sem limite
        JavalinAPI.addIgnoredPath("/health");          // ignorado pela segurança

        // Limites globais (fallback)
        JavalinAPI.setGlobalRateLimit(5, 30, 300);

        new AngatuLib("localhost", 8080, true);

        // Acessar a instância do Javalin para uso avançado
        // Javalin app = JavalinAPI.get();
    }
}
```

**Como o limite decide:**
- **Padrões de caminho** — exato (`/login`), prefixo (`/api/*` vale para `/api` e tudo abaixo, mas não para `/apiary`) e segmento variável (`/api/pedidos/{id}`, `<id>` ou `*` no meio). Vence o exato; depois, o padrão mais específico. A configuração fica gravada e volta a cada subida — `JavalinAPI.removeRateLimit("/padrao")` desfaz.
- **O contador é da rota, não da URL** — conta o IP naquela **rota**: o molde que vai atender o pedido, com os parâmetros (`/api/pedidos/{id}`). `/api/pedidos/1` e `/api/pedidos/2` dividem o contador, e um limite em `/api/cupom/*` segura a tentativa de códigos em série. Um limite em `/api/*` continua valendo **por rota**, não para a pasta inteira. Caminho que não é rota (404, varredura) divide um contador só por IP. Uma tela que pede muitos itens seguidos da mesma rota precisa de um limite próprio para ela, com `configureRateLimit`.
- **Caminho canônico** — `/api//login`, `/api/login/` e `//api/login` contam como `/api/login`, exatamente como o roteador do Javalin os trata.
- **Quem é o cliente** — `IP.get(ctx)`: com `setTrustedProxyHops(n)`, o item do `X-Forwarded-For` contado da direita (todas as linhas do cabeçalho, na ordem); sem ele, o último item só quando a conexão vem de rede privada (o proxy); direto da internet, o IP do socket, sem colchetes. IPv6 conta por `/64`, que é o que um cliente controla — e, num limite configurado, o `/48` inteiro soma no máximo 16 vezes o limite de um cliente, para quem troca de `/64` a cada pedido; esse freio da rede nunca vira violação nem bloqueio longo. Atrás de uma CDN que já manda o IP num cabeçalho próprio: `JavalinAPI.setClientIpHeader("CF-Connecting-IP")`.
- **Fora do limite** — arquivo estático (GET/HEAD de um caminho com extensão de estático — `.json` e `.wasm` inclusive — que **não** é rota), `addUnlimitedPath`, a galeria `GET /image` da própria biblioteca e o preflight de CORS (`OPTIONS` com `Access-Control-Request-Method`), que o plugin de CORS responde sem rodar rota.
- **Presets** — `configureLoginRateLimit`: 2 por segundo (o duplo clique no botão passa), 5 por minuto, bloqueio de 15 minutos. `configureApiRateLimit`: 3 por segundo, 20 por minuto, bloqueio de 2 minutos.
- **Bloqueio longo (24 h)** — 10 violações numa hora, de um IP público. Vale para rota limitada (API, login, escrita) e para o upgrade de WebSocket; página pública e arquivo estático continuam abrindo. A decisão é em memória; o banco guarda o registro e o recarrega na subida.
- **Recusa** — navegador recebe a página de recusa; chamada de API recebe `{"error": …, "message": …}` em JSON, com `Retry-After` no 429. A página é uma tela que um cliente de verdade lê: o projeto a desenha com o próprio sistema de design e a marca da Angatu por `JavalinAPI.setDenyPage(aviso -> html)`, que recebe status, título, mensagem e segundos até a liberação (`DenyNotice`). Se a função falhar, vale a página padrão.
- **Cache** — `JavalinAPI.setSecurityHeader("Cache-Control", "no-store")` (e `Pragma`, `Expires`) declarado **antes** do `new AngatuLib` vale para páginas, rotas **e** arquivos estáticos; sem isso, o servidor de estáticos sai com o `max-age=0` do Javalin.
- **Conteúdo com cara de SQLi/XSS** — procurado em nome e valor de parâmetro, em cabeçalho e em corpo de texto de até 64 KiB (JSON, formulário, XML, `text/*`), por palavra inteira, já decodificado: o formulário campo a campo, o JSON com os escapes `\uXXXX` resolvidos. Recusado com 403, sem contar violação — mas o pedido entra na conta do limite, que é conferido **antes** da varredura: um IP bloqueado não gasta CPU com ela. Corpo varrido fica em cache no Javalin (`ctx.body()`, `bodyAsClass`); quem lê em fluxo (`bodyInputStream()`) deve usar `bodyAsBytes()`.
- **Tamanho de corpo** — até 16 MiB lidos em memória (`ctx.body()`); `JavalinAPI.setMaxRequestSize(bytes)` antes do `new AngatuLib` muda. Upload multipart não passa por esse limite.

**Log de requisições:** toda requisição que chega ao servidor vira uma linha no terminal — rota, página, arquivo estático, 404, recusa do filtro, erro e upgrade de WebSocket —, sem nenhuma configuração por rota: rota nova já nasce com log.

```text
[25/09 22:14:03] GET     /api/pedidos/42          IP=189.40.12.7 STATUS=200 TIME=14ms
[25/09 22:14:04] GET     /api/produtos            IP=189.40.12.7 STATUS=200 TIME=3ms PARAMS=pagina=2&token=***
[25/09 22:14:05] POST    /api/login               IP=189.40.12.7 STATUS=429 TIME=<1ms DENIED=too_many_requests
[25/09 22:15:21] POST    /api/usuarios            IP=189.40.12.7 STATUS=500 TIME=142ms ERROR=NullPointerException
```

- **O que cada linha traz** — data e hora, método, caminho, IP (o de `IP.get(ctx)`, com a regra de proxy: nunca o `X-Forwarded-For` que o cliente escreveu), status, tempo total e, quando houver, os parâmetros da URL, o motivo da recusa do filtro (`DENIED`, o mesmo código do JSON que o cliente recebe) e o tipo da exceção (`ERROR`).
- **O que nunca entra** — corpo de requisição ou de resposta, arquivo enviado, cabeçalho, cookie e a mensagem da exceção (só o tipo). Dos parâmetros, o valor de todo nome com cara de segredo ou de dado pessoal (senha, token, chave, código, e-mail, CPF, telefone, cartão...) sai como `***`, e o resto sai cortado. O caminho sai como veio: segredo não vai na URL.
- **Não atrasa a resposta** — o `requestLogger` do Javalin roda na thread da requisição, que só põe um registro numa fila; uma thread própria formata e escreve em lote, pelo `Console`. Com o terminal lento, a fila (16 mil registros) enche e o excedente é descartado, com um aviso de quantas linhas faltaram — a requisição nunca espera. Na carga do teste, 2.400 requisições em 8 threads levaram praticamente o mesmo tempo com e sem log. Ao parar o servidor, a fila é escrita antes de o processo sair.
- **Exceções** — a que escapa da rota aparece como `ERROR=Tipo`, e a resposta de erro continua a do Javalin, sem mudança. A rota que captura a exceção e responde sozinha põe o tipo na linha com `JavalinAPI.markRequestError(ctx, e)`.
- **Reduzir ou desligar** — `ANGATU_REQUEST_LOG=errors` (só status 400 ou mais, ou exceção) ou `off`, como variável do serviço no Coolify; em código, `JavalinAPI.setRequestLogMode(RequestLogMode.ERRORS)`, que vale na hora e vence a variável. O padrão é `all`.
- **Nenhuma rota escreve o próprio log de acesso** — um `Console.log` em cada rota duplicaria a linha.

**Rotas automáticas:** crie classes que estendem `Route` com construtor vazio — elas são descobertas e registradas no startup:

```java
import br.com.angatusistemas.lib.javalin.routes.Route;
import br.com.angatusistemas.lib.javalin.routes.RouteType;

public class HealthRoute extends Route {
    public HealthRoute() {
        super("/health", RouteType.GET, ctx ->
            ctx.json("{\"status\":\"ok\"}")
        );
    }
}
```

A rota WebSocket (`super("/ws/canal", ws -> …)`) passa pelo bloqueio e pelo rate limit no upgrade, mas **quem conecta não é autenticado pela biblioteca**: confira a sessão dentro da rota, no `onConnect`, antes de aceitar qualquer mensagem.

**Páginas HTML:** coloque arquivos `.html` em `src/main/resources/public/`. O `public/index.html` é o **template base** e não vira rota (o `/` entrega o arquivo cru). Cada outro `.html` vira `GET /<nome-do-arquivo>` — só o nome do arquivo conta, então `public/blog/post.html` vira `/post`; `emails/` e `others/` ficam de fora. A página é montada dentro do template: o conteúdo dela entra em `{content}`, `{page}` recebe o nome com inicial maiúscula e `{%<nome>_active}` vira `bg-blue-600 text-white` na página atual. Sem `{content}` no `index.html`, toda página mostra o próprio `index.html`. Duas páginas com o mesmo nome, ou uma página com o mesmo caminho de uma `Route`, não derrubam a subida: a segunda é ignorada com aviso. `/pagina.html` redireciona para `/pagina`.

**Rota da própria biblioteca:** `GET /image?id=…` serve as imagens gravadas pelo `ImageAPI` (entidade `Image`) para qualquer um que tenha o id — não guarde imagem privada nela. Os nomes `Image`, `Key`, `PermanentBlock`, `SuspectIp` e `RouteRateLimitConfig` são entidades da biblioteca: uma entidade do projeto com o mesmo nome cairia na mesma tabela.

### 🗄️ Persistência automática (Saveable)

Qualquer classe pode ser persistida em SQLite herdando `Saveable`:

```java
import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class User extends Saveable {
    private String id;
    private String name;
    private String email;

    public User() {} // obrigatório para desserialização

    @Override
    public String getId() { return id; }
}
```

```java
// Criar e salvar
User u = new User();
u.setName("João");
u.save(); // gera UUID automaticamente se id for nulo

// Buscar — vai ao banco e devolve uma instância nova a cada chamada
User joao = Saveable.findById(User.class, u.getId());

// Alterar registro disputado sem perder a alteração de quem chegou junto
Saveable.mutate(Account.class, id, account -> account.setBalance(account.getBalance() + 100));

// Duas gravações que precisam valer juntas
Saveable.transaction(() -> {
    stock.save();
    new Order(userId, productId).save();
});

// Índice + busca por campo resolvida no SQL
Saveable.createIndex(User.class, "email");
User byEmail = Saveable.findFirstByField(User.class, "email", "joao@exemplo.com");

// Consultas customizadas (sempre com parâmetros posicionais); o nome da tabela vem do método
List<User> joaos = Saveable.query(User.class,
    "SELECT data FROM " + Saveable.tableName(User.class) + " WHERE json_extract(data, '$.name') = ?", "João");

// Encerrar a aplicação: espera a gravação e as leituras em curso, e fecha as conexões
Saveable.shutdown();
```

**Nome da tabela.** `Saveable.tableName(Classe.class)` devolve o nome exato: o nome simples da
classe em minúsculas, com um `s` acrescentado **só quando ele ainda não termina em `s`** — `User`
→ `users`, `Address` → `address` (nunca `addresss`), `CompanySettings` → `companysettings`,
`Category` → `categorys`. Não há outra flexão de plural. Duas classes com o mesmo nome simples,
em pacotes diferentes, caem na mesma tabela; classe anônima não pode ser entidade.

**Busca por campo.** `findByField`/`findFirstByField` resolvem no SQL, com `json_extract`, todo
valor que vira primitivo no JSON gravado: texto, número, booleano, enum (pelo nome), `UUID`, datas
do `GsonAPI` e `null` (campo nulo ou ausente). `BigDecimal("10.50")` encontra `10.5`. Só valor que
vira objeto ou lista cai no filtro em memória. Registro gravado antes de o campo existir não tem a
chave no JSON — para o SQL, o campo dele é `null`.

**Consultas customizadas.** `query()` manda o `SELECT` — mesmo com comentário na frente — ao pool
de leitura; qualquer outro comando é gravação e entra na fila de escrita. Fora de transação, ele
roda sozinho, em autocommit, como sempre rodou: é o que mantém `VACUUM`, `ATTACH` e a troca de
`journal_mode` funcionando, porque o SQLite os recusa dentro de uma transação. Controle de
transação (`BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT`, `RELEASE`) é recusado com
`IllegalArgumentException`: a transação é da biblioteca — use `Saveable.transaction(...)`.

**Formato do banco inalterado.** Continua um `database.db` **por aplicação**, com a
tabela no mesmo formato de sempre — `id TEXT PRIMARY KEY, data TEXT NOT NULL` — e
gravação por `INSERT OR REPLACE`. Nenhuma coluna é criada, alterada ou removida:
bancos de sistemas que rodam versões anteriores da biblioteca continuam funcionando,
e um banco escrito por esta versão segue legível pelas anteriores.

**Concorrência — milhares de leituras e gravações ao mesmo tempo.** O SQLite aceita um escritor
por vez no arquivo, e a biblioteca organiza o processo em torno disso:

- **Uma conexão de escrita, numa fila justa.** Toda gravação — `save`, `delete`, `mutate`,
  `transaction`, criação de tabela e de índice — passa por ela, na ordem de chegada. Dentro do
  processo não existe disputa pelo arquivo: nada de `SQLITE_BUSY`, nada de trava por registro
  (que, pega na ordem errada, travava duas threads para sempre).
- **Leitura em paralelo.** As buscas usam um pool separado, só de leitura (`query_only`), em WAL:
  o leitor não espera o escritor. Uma leitura nunca segura duas conexões — o JSON vira objeto
  depois que a conexão voltou ao pool, então um construtor que consulta o banco não seca o pool.
- **Transações.** `save()` é atômico e a última escrita vence; `mutate()` lê, altera e grava na
  mesma transação, sem atualização perdida, e o bloco dele pode gravar outras entidades (entra
  na mesma transação); `transaction()` faz tudo valer junto ou nada. Uma transação aninhada é
  um *savepoint*: se falhar, só o que ela fez é desfeito. Leituras dentro da transação enxergam o
  que ela já gravou.
- **Tabela nova dentro de transação.** A primeira vez de uma entidade pode cair dentro de uma
  transação. A tabela só passa a valer para o processo depois do commit — se a transação
  desfizer, ela é recriada na chamada seguinte, em vez de a entidade ficar "pronta" sem tabela.
- **Transação que o próprio SQLite desfaz.** Disco cheio, erro de E/S, falta de memória e
  `ON CONFLICT ROLLBACK` fazem o SQLite desfazer a transação **inteira**, não só o comando que
  falhou. A biblioteca confere a transação depois de toda falha: perdida, ela não roda mais nada e
  termina em `PersistenceException` ("Transação perdida"), mesmo que o bloco tenha capturado a
  falha e seguido. Sem isso, o resto do bloco era gravado fora da transação, cada comando
  confirmado sozinho — o crédito sem o débito.
- **Espera com prazo, sem desistir no pedido de interrupção.** Quem espera a vez de gravar, ou uma
  conexão de leitura, por mais que `ANGATU_DB_BUSY_TIMEOUT_MS` (padrão 30 s) recebe
  `PersistenceException`. Um pedido de interrupção pendente não derruba a operação — a tarefa
  cancelada ainda grava o estado dela — e volta à thread no fim. Uma gravação que segura a vez por
  mais de 2 s aparece no log com o ponto do código que a chamou.
- **Falha é exceção.** Erro de banco sobe como `PersistenceException`, dentro ou fora de
  transação — nunca volta disfarçado de "não existe" (`null`) ou "lista vazia". Entidade sem campo
  de ID que o `getId()` leia lança `IllegalStateException` no `save()`.

> Não faça chamada de rede nem trabalho lento dentro de `transaction` ou do bloco do `mutate`:
> enquanto ele roda, nenhuma outra gravação do processo acontece.

**Banco em contêiner.** O arquivo padrão é `database.db` no diretório de trabalho —
um por projeto, como sempre. No Coolify, aponte para o volume persistente daquele
projeto com `ANGATU_DB_PATH=/data/database.db` (ou `-Dangatu.db=...`); sem isso o
banco vive dentro do contêiner e some no deploy seguinte.

> ⚠️ **Sem dados em memória:** não há cache total nem *identity map*. Um objeto alterado só é
> visível para os outros componentes depois do `save()`, e `findById` devolve instâncias
> distintas a cada chamada. Consultas frequentes por campo pedem `createIndex(...)`;
> `findAll`/`findByPredicate` percorrem a tabela.

**Memória do banco, em contêiner apertado.** Cada conexão carrega o próprio cache de páginas do
SQLite, que é memória **nativa**: não aparece no gráfico de heap e é contada inteira pelo limite
do contêiner. Os ajustes, todos por variável de ambiente e com padrão seguro:

| Variável | Padrão | O que é |
|---|---|---|
| `ANGATU_DB_CACHE_KIB` | `8192` (8 MiB por conexão) | Cache de páginas do SQLite, em **KiB** |
| `ANGATU_DB_POOL_SIZE` | `12` | Conexões de **leitura** (a de escrita é uma à parte) |
| `ANGATU_DB_BUSY_TIMEOUT_MS` | `30000` | Prazo de toda espera do banco, em ms |

Com os padrões, o teto é ~104 MiB nativos (13 conexões). Num contêiner de 1 GB rodando com folga,
algo como `ANGATU_DB_CACHE_KIB=4096` e `ANGATU_DB_POOL_SIZE=6` corta isso para ~28 MiB sem efeito
perceptível em consultas indexadas.

### 📨 E-mail (EmailAPI)

```java
import br.com.angatusistemas.lib.email.EmailAPI;

// Configurar EMAIL_KEY e EMAIL_PASSWORD no .env

// Simples (assíncrono — o assunto ganha um código #XXX anti-spam)
EmailAPI.sendSimple("cliente@empresa.com", "Bem-vindo", "Olá!").thenAccept(ok -> {
    System.out.println(ok ? "Enviado" : "Falhou");
});

// HTML com template de public/emails/ — cada valor recebe escape HTML
String html = EmailAPI.loadHtmlTemplate("/emails/welcome.html", Map.of("nome", nomeDoCliente));
EmailAPI.sendHtml("cliente@empresa.com", "Bem-vindo", html).join();

// Trecho de HTML montado pelo sistema (as linhas de um pedido): entra sem escape,
// e o dado de cliente dentro dele é escapado na montagem
String linhas = itens.stream()
        .map(item -> "<tr><td>" + EmailAPI.escapeHtml(item.getNome()) + "</td></tr>")
        .collect(Collectors.joining());
String pedido = EmailAPI.loadHtmlTemplateWithTrustedHtml("/emails/pedido.html",
        Map.of("nome", nomeDoCliente), Map.of("itens", linhas));

// Com anexos e múltiplos destinatários (um endereço por item da lista)
EmailAPI.sendWithAttachments(List.of("a@x.com", "b@x.com"), null, null,
        "Relatório", "<b>Segue em anexo</b>", List.of(new File("relatorio.pdf")), true);
```

- **O future sempre completa**: `true` quando o servidor SMTP aceitou a mensagem, `false` em
  qualquer falha — destinatário inválido, anexo ausente ou ilegível (nada é enviado), credenciais
  ausentes, fila cheia, erro SMTP. O motivo vai para o log.
- **Um endereço por item**: `"a@x.com,b@y.com"` num item só, grupo de endereços, quebra de linha ou
  caractere de controle recusam o envio inteiro — um campo de formulário não escolhe destinatários
  extras.
- **Trate o template como público**: ele fica em `src/main/resources/public/emails/`, dentro da
  pasta que o servidor publica na raiz do site. Nada sigiloso no arquivo; dado de cliente entra só
  pelos placeholders. O escape protege texto e atributo entre aspas, não `href`, `<script>` nem
  `<style>` — link é montado e validado pelo sistema.
- **Transporte**: Gmail com STARTTLS obrigatório, certificado e nome do servidor conferidos, e prazo
  de conexão, leitura e escrita. O envio roda numa fila própria (4 simultâneos, 1.000 na fila),
  separada do `Task`. As threads são daemon: um programa que termina logo depois de enviar espera o
  future antes de sair.

### 🔔 Web Push (WebPushAPI)

```java
import br.com.angatusistemas.lib.webpush.PushBootstrap;
import br.com.angatusistemas.lib.webpush.WebPushAPI;
import nl.martijndwars.webpush.Subscription;

// Inicializa: gera e persiste as chaves VAPID na primeira vez. O subject é o contato do
// projeto para os push services (mailto: ou https://)
PushBootstrap.setup("mailto:contato@seudominio.com.br");

// Rota que recebe a assinatura do navegador (PushSubscription.toJSON())
Subscription sub;
try {
    sub = WebPushAPI.parseSubscriptionFromJson(ctx.body());
} catch (IllegalArgumentException e) {
    ctx.status(400).result(e.getMessage()); // push service desconhecido ou JSON inválido
    return;
}
String json = WebPushAPI.subscriptionToJson(sub); // persistir

// Envio com resultado: o future nunca completa com exceção
WebPushAPI.sendNotificationAsync(sub, "Título", "Corpo", null).thenAccept(result -> {
    if (result.isExpired()) {
        // assinatura morta (404/410): remova do banco
    }
});
```

- **Só push services conhecidos.** O endpoint vem do navegador, ou de quem forjar a requisição.
  São aceitos só `https`, na porta 443, de `fcm.googleapis.com`, `*.push.services.mozilla.com`,
  `*.push.apple.com` e `*.notify.windows.com`. `createSubscription` e `parseSubscriptionFromJson`
  lançam `IllegalArgumentException` para o resto. Outro serviço entra com
  `WebPushAPI.allowPushServiceHost("push.exemplo.com")` na subida, e `isAllowedEndpoint(url)`
  confere sem lançar.
- **Subject**: o parâmetro de `setup(subject)`; sem ele, a propriedade `-Dangatu.webpush.subject`,
  depois a variável `ANGATU_WEBPUSH_SUBJECT`. Sem nenhum dos três, vale o contato da Angatu.
- **Envio numa fila própria**, separada do `Task`: 32 envios simultâneos e 10.000 na fila. Os prazos
  são 5 s para conectar, 10 s por leitura e 30 s no total. Redirecionamento não é seguido, e a
  resposta é lida até 4 KB. Com a fila cheia, o envio completa na hora como falha, por isso um
  disparo para mais de ~10.000 assinaturas vai em lotes.
- **Chave inválida derruba a subida.** `PushBootstrap.setup` lança exceção quando as chaves gravadas
  não formam par. **Não apague a linha da chave**: chaves novas invalidam as assinaturas de todos os
  navegadores. Corrija o registro.

### 🤖 Discord (Bot)

```java
import br.com.angatusistemas.lib.discord.Bot;

// Token no .env: DISCORD_BOT_TOKEN
Bot.setup();

Bot.sendMessage("123456789012345678", "Olá mundo!");
Bot.sendMessageWithButton("123456789012345678", "Confirma?", "btn_confirmar", "Sim");

Bot.onButtonClick("btn_confirmar", event ->
    event.reply("Confirmado!").setEphemeral(true).queue());

Bot.shutdown(); // ao encerrar a aplicação
```

- `setup()` espera o Discord por até 30 s e devolve `false` se não conectar, sem deixar nada
  rodando. A permissão de ler o texto das mensagens (MESSAGE_CONTENT) não é mais pedida: o `Bot`
  não lê mensagem, e pedir essa permissão sem ativá-la no portal impedia a conexão. Quem lê o texto
  pelo `getJDA()` usa `Bot.setup(token, true)` e ativa "Message Content Intent" no portal.
- Cada envio tem prazo (15 s para texto, 60 s para arquivo). Imagem por URL só vem de endereço
  público `http`/`https`, com no máximo 10 MiB.

### 🧠 IA (DeepSeek)

```java
import br.com.angatusistemas.lib.ai.DeepSeek;

DeepSeek.initialize(); // chave em DEEPSEEK_API_KEY no .env

String resposta = DeepSeek.ask("Responda em português", "Qual a capital do Brasil?");

DeepSeek.askStream("Seja criativo", "Conte uma história", chunk -> System.out.print(chunk));
```

`ask` tem prazo de 300 s para a resposta inteira. `askStream` fecha a conexão em qualquer
desfecho e para se o servidor ficar 120 s calado. A sobrecarga com `onComplete` e `onError` chama
exatamente um dos dois, e só dá como concluído o stream que chegou ao `[DONE]`.

### 🖥️ Screenshots e scraping (BrowserAPI)

```java
import br.com.angatusistemas.lib.browser.BrowserAPI;

// Screenshot full-page
BrowserAPI.captureFullPageScreenshotToFile("https://site.com", "site.png");

// Scraping
String titulo = BrowserAPI.extractText("https://site.com", "h1");
String html = BrowserAPI.getPageHtml("https://site.com");

// Utilitários de HTML (não precisam do Playwright)
List<String> links = BrowserAPI.extractLinks(html);
Map<String, String> metas = BrowserAPI.extractMetaTags(html);

BrowserAPI.shutdown(); // ao encerrar a aplicação
```

- **Só endereço público.** O navegador não acessa loopback, rede privada nem o metadado da nuvem: a
  URL é conferida antes, e cada conexão do Chromium passa por um proxy local que confere o endereço
  de verdade. Isso vale para redirecionamento, iframe, imagem carregada depois, WebSocket e
  *worker*. Para capturar as páginas do próprio sistema em `localhost` (desenvolvimento), chame
  `BrowserAPI.setAllowPrivateNetworkAccess(true)`; isso vale para a JVM inteira, então não ligue num
  processo que também captura URL vinda de usuário. `file:`, `data:` e `about:` são recusados como
  destino: para HTML próprio, use os métodos `*FromHtml`.
- **Dois navegadores, no máximo.** Com os dois ocupados por 30 s, a chamada falha com "BrowserAPI
  ocupada" em vez de abrir mais Chromium. Navegador que morreu é trocado por outro.
- As opções valem de verdade: `quality` (JPEG), `fullPage`, recorte (`clipX/Y/Width/Height`, os quatro
  juntos), `blockImages`/`blockCss`/`blockFonts`, `waitForNetworkIdle` e `waitForImages`. Valor
  inválido lança `IllegalArgumentException`.

### 🖼️ Imagens e QR Codes (ImageAPI, QRCodeAPI)

```java
import br.com.angatusistemas.lib.images.ImageAPI;
import br.com.angatusistemas.lib.images.QRCodeAPI;
import br.com.angatusistemas.lib.images.objects.Image;

// Thumbnail
ImageAPI.createThumbnail("foto.png", "mini.png", 200, 200);

// Upload guardado no banco: o formato vem dos bytes, nunca da extensão nem do navegador
Image imagem = ImageAPI.extractToImageObject(id, bytesDoUpload); // IllegalArgumentException se não for imagem aceita
imagem.save();                                                   // servida em GET /image?id=...

// QR Code
QRCodeAPI.generateAndSaveQRCode("https://site.com", "qrcode.png", 300, 300);
String texto = QRCodeAPI.readQRCodeFromFile("qrcode.png");
```

- **Formatos aceitos**: PNG, JPEG, GIF, BMP, TIFF e WebP, reconhecidos pelo conteúdo. SVG, HTML e
  qualquer outro arquivo são recusados, porque um SVG com script servido pela origem do site vira
  XSS.
- **Bomba de descompressão**: a leitura confere largura × altura no cabeçalho antes de alocar os
  pixels. O teto é de 40 megapixels (`ImageAPI.setMaxPixels(n)` muda). Um PNG de 12.000 × 12.000
  pedia 765 MB de memória; agora é recusado com menos de 10 MB.
- **`GET /image?id=...` é público**: quem conhece o `id` recebe a imagem, então não guarde nessa
  entidade imagem que exija login. A rota só exibe tipos raster; o resto sai como download, e toda
  resposta leva `nosniff` e `Content-Security-Policy: sandbox`.
- **`imageUrlToBase64(url)`** baixa só `http`/`https` de endereço público: rede interna, loopback e
  metadado de nuvem são recusados a cada redirecionamento, com limite de 20 MB e 30 s.
- **Foto de celular sai em pé**: a orientação EXIF do JPEG é aplicada na leitura, como no
  `createThumbnail`.

### 💳 Pagamentos (MercadoPagoAPI)

```java
import br.com.angatusistemas.lib.payments.MercadoPagoAPI;
import br.com.angatusistemas.lib.payments.MercadoPagoAPI.PaymentDTO;

MercadoPagoAPI.init("SEU_ACCESS_TOKEN");

PaymentDTO pix = MercadoPagoAPI.createPixPayment(
    99.90, "cliente@email.com", "Compra #123", "pedido-123");

if (MercadoPagoAPI.isApproved(pix.getId())) {
    // liberar pedido
}

// Webhook: assinatura conferida como na documentação oficial do Mercado Pago
boolean autentico = MercadoPagoAPI.validateWebhookSignature(
        ctx.header("x-signature"), ctx.header("x-request-id"), ctx.queryParam("data.id"), segredo);
```

- **Falha não se disfarça de "não encontrado"**: só o HTTP 404 vira vazio. Com a API fora do ar,
  `findById` lança `MercadoPagoException` e `processWebhook` lança `MPException`. Responda 5xx ao
  webhook: o Mercado Pago reenvia, em vez de o pagamento ficar sem liberação.
- **Idempotência**: toda criação aceita uma `idempotencyKey` (sobrecargas com `BigDecimal`). No
  cartão, sem chave, ela é derivada dos dados da cobrança, então repetir depois de um timeout não
  cobra duas vezes. PIX e boleto seguem criando uma cobrança nova a cada chamada: um PIX a mais só
  expira, e "gerar outro PIX" para um pedido vencido continua funcionando.
- **Webhook**: comparação em tempo constante; o par ausente (`data.id` ou `x-request-id`) sai do
  manifesto, como manda a documentação. A janela de tempo do `ts` vem desligada, porque o Mercado
  Pago reenvia por até 96 horas. `setWebhookTolerance(...)` liga a janela, depois de conferir no
  sandbox como os reenvios chegam.
- Valores arredondados para centavos, e recusados se forem zero, negativos ou `NaN`. Um cliente do
  SDK por JVM, sem o vazamento de um *handler* de log a cada chamada.

### ⚙️ Tarefas (Task)

```java
import br.com.angatusistemas.lib.task.Task;

Task.runAsync(() -> System.out.println("assíncrono"));
int id = Task.runLater(() -> System.out.println("daqui a 5s"), 5000);
Task.runTimerWithFixedDelay(() -> System.out.println("a cada hora"), 0, 3600_000);

Task.cancelTask(id);
```

O trabalho avulso (`runAsync`, `runLater`) roda num pool de trabalhadores (`ANGATU_TASK_THREADS`,
padrão 16); as tarefas periódicas rodam no relógio, separado — uma rajada de envios não atrasa a
limpeza periódica do servidor. Qualquer falha dentro de uma tarefa, `Error` inclusive, é registrada
no log, e a periódica continua agendada. No encerramento, `Task.drain(ms)` deixa terminar o que
está na fila — inclusive a tarefa de prazo vencido que o relógio, ocupado, ainda não tinha
disparado; `Task.shutdown()` descarta. Com o `AngatuLib`, o gancho de desligamento da própria
biblioteca já chama o `drain`.

O ID devolvido é sempre positivo e nunca é entregue a outra tarefa enquanto a dele estiver
registrada — nem quando o contador dá a volta, o que numa aplicação com mil tarefas por segundo
acontece em menos de um mês no ar. Guarde o ID do timer criado na subida sem medo: o
`cancelTask` dele continua parando aquele timer.

### 🔗 HTTP Client (Request)

```java
import br.com.angatusistemas.lib.connection.Request;
import br.com.angatusistemas.lib.connection.Response;
import br.com.angatusistemas.lib.console.Console;

Response resp = Request.query("GET", "https://api.exemplo.com/users");
Response resp2 = Request.query("POST", "https://api.exemplo.com/users", "{\"nome\":\"João\"}", "meu-token");

if (resp2.isSuccess()) {
    System.out.println(resp2.getBody());
} else if (resp2.isNetworkError()) {
    Console.warn("Sem resposta do servidor: %s", resp2.getError().getMessage());
}
```

- **Nunca lança por falha de rede**: o `Response` volta com `isNetworkError()` e a causa em
  `getError()`. `getCode()` é o código que o servidor mandou (207, 522...), e `isSuccess()` vale
  para qualquer 2xx.
- **Prazo de 15 s para a chamada inteira**, corpo incluído (`Request.setTimeout(...)` muda). Um
  servidor que pinga um byte por segundo não segura mais a thread para sempre.
- **Resposta limitada a 10 MiB** (`Request.setMaxBodyBytes(...)`); acima disso, o erro é
  `ResponseTooLargeException`, dentro do `Response`.
- Qualquer método HTTP, `PATCH` incluído. No máximo 4 redirecionamentos, nunca de `https` para
  `http`, e sem levar `Authorization` nem `Cookie` para outro host.

---

## 💡 Boas práticas

1. **Adicione apenas as dependências dos módulos usados** — consulte a [tabela de dependências](#-dependências-por-módulo). O sistema de detecção indica exatamente o que falta.
2. **O `AngatuLib` já cuida do encerramento** — o gancho de desligamento dele para o servidor, espera as tarefas enfileiradas (`Task.drain`) e fecha o banco (`Saveable.shutdown`). Não chame `Task.shutdown()` num gancho seu: ele descarta a fila que o `drain` está esperando. Sem o `AngatuLib`, chame `Task.drain(ms)` e `Saveable.shutdown()` no seu próprio gancho.
3. **Chame `BrowserAPI.shutdown()`** ao finalizar o uso de scraping/screenshots (encerra os processos headless).
4. **Configure tudo antes do `new AngatuLib(...)`** — `setTrustedProxyHops`, `configureRateLimit`, `addIgnoredPath` — para valer desde a primeira requisição.
5. **Crie índices com `Saveable.createIndex(Entidade.class, "campo")`** para toda consulta frequente por campo — sem cache em memória, o índice é o que segura o custo.
6. **Use `Saveable.mutate(...)` para alterar registro disputado** e `Saveable.transaction(...)` quando duas gravações precisam valer juntas — sem chamada de rede dentro.
7. **Declare `JavalinAPI.setTrustedProxyHops(1)` atrás do Coolify** (2 com Cloudflare na frente) — a regra automática cobre o caso comum, mas a declaração deixa explícito quantos proxies existem.
8. **Não guarde segredos no código** — use o arquivo `.env` (`Env.get()`), que também lê as variáveis de ambiente do Coolify.
9. **Use `Password.hash()` para a senha digitada e `Password.checkCriptography()` no login** (BCrypt com salt automático). `criptography()` é para regravar um valor que pode já estar em hash.
10. **Configure `-Dangatu.debug=true` apenas em desenvolvimento** — logs de debug são silenciosos por padrão.
11. **No log, passe o dado como argumento** (`Console.info("Busca: %s", termo)`) — assim um `&` do valor aparece como está, em vez de virar código de cor.

---

## 🔧 Solução de problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `[AngatuLibraries] Dependência ausente: ...` | Módulo usado sem a dependência no classpath | Siga o snippet Maven/Gradle exibido na mensagem |
| `NoClassDefFoundError` citando `io/javalin/...` ou `com/google/gson/TypeAdapter` | Classe com tipos de terceiros na assinatura pública (`JavalinAPI`, TypeAdapters) usada sem a dependência | Adicione a dependência correspondente — ela é obrigatória por contrato de API (ver nota acima) |
| `Não é possível criar uma rota antes de inicializar o servidor` | `Route` construída antes de `new AngatuLib(...)` | Construa o `AngatuLib` primeiro; rotas são descobertas automaticamente |
| `IllegalStateException: O servidor web não iniciou — veja o erro acima` | Porta em uso ou pasta `src/main/resources/public` ausente | Leia a linha `Falha ao iniciar Javalin` logo acima: ela traz a causa. O `JavalinAPI.setup` direto devolve `null` no mesmo caso |
| Todo visitante recebe 429 / bloqueio vale para todos | O rate limit está enxergando o IP do proxy, não o do visitante | `JavalinAPI.setTrustedProxyHops(1)` antes do `new AngatuLib` (2 com Cloudflare na frente) |
| Uma tela que carrega vários itens seguidos leva 429 | O contador é da rota: `/api/pedidos/1`, `/api/pedidos/2`… contam juntos em `/api/pedidos/{id}` | `JavalinAPI.configureRateLimit("/api/pedidos/{id}", new RateLimitConfig(…))` com o ritmo que a tela precisa, antes do `new AngatuLib` |
| `PersistenceException: Banco ocupado: a vez de gravar não chegou em …` | Uma transação segurou a gravação por mais de `ANGATU_DB_BUSY_TIMEOUT_MS` | Procure no log o aviso "segurou a vez de gravar" — ele diz onde; tire chamada de rede e trabalho lento de dentro de `transaction`/`mutate` |
| `PersistenceException: Erro ao …` | O banco recusou a operação (disco cheio, arquivo travado por outro processo, SQL inválido) | A causa vem encadeada na exceção; a falha nunca volta disfarçada de `null` ou lista vazia |
| `PersistenceException: Transação perdida: depois de uma falha, o SQLite desfez tudo…` | Um comando da transação falhou de um jeito que faz o SQLite desfazer a transação inteira (disco cheio, erro de E/S, `ON CONFLICT ROLLBACK`), e o bloco capturou a falha e seguiu | A causa é a linha anterior do log, terminada em "o SQLite desfez a transação inteira". Nada da transação foi gravado: resolva a causa e refaça a operação inteira |
| `IllegalArgumentException: BEGIN não passa pelo query()` | Controle de transação escrito à mão no `query()` | Troque o `BEGIN … COMMIT` por `Saveable.transaction(() -> { … })` |
| Rota que lê JSON responde 413 | Corpo acima de 16 MiB | `JavalinAPI.setMaxRequestSize(bytes)` antes do `new AngatuLib`; para arquivo, prefira upload multipart |
| 403 "Pedido recusado" numa requisição legítima | Texto com cara de SQL Injection/XSS em parâmetro, cabeçalho ou corpo de até 64 KiB | Veja o texto enviado; o filtro procura palavras inteiras como `select … from` e `<script` |
| Acento aparece como `?` no log do contêiner | Contêiner sem `LANG`: a saída padrão da JVM fica em ASCII | `ENV LANG=C.UTF-8` no Dockerfile (o modelo em `templates/` já tem) |
| `Credenciais de e-mail não configuradas` | `.env` sem `EMAIL_KEY`/`EMAIL_PASSWORD` | Configure as variáveis e reinicie |
| E-mail volta `false` com `Destinatário inválido` ou `Cada item da lista de destinatários deve ser um único endereço` | Item com vírgula, grupo, quebra de linha ou endereço malformado | Um endereço por item da lista; valide o campo com `EmailFormatter.isValidNormal` antes |
| `WebPushAPI não inicializado` | Envio antes da subida do Web Push | Chame `PushBootstrap.setup(subject)` na subida (gera e persiste as chaves na primeira vez) |
| `Erro ao configurar o Web Push: o WebPushAPI recusou as chaves VAPID salvas no banco` | Registro de chave corrompido ou com as chaves trocadas | Corrija o registro indicado na mensagem — não apague: chaves novas invalidam todas as assinaturas |
| `IllegalArgumentException` ao receber assinatura de push | Endpoint fora dos push services conhecidos | Responda 400; se o serviço for legítimo, `WebPushAPI.allowPushServiceHost(host)` na subida |
| `Playwright` não abre navegador | Browser não instalado | `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` |
| Consulta lenta no `Saveable` | `findAll`/`findByPredicate` percorrendo a tabela inteira | `Saveable.createIndex(Entidade.class, "campo")` + `findByField`/`query` com `json_extract` |
| Alteração some / valor volta ao anterior | Objeto alterado sem `save()`, ou dois componentes gravando o mesmo registro | Toda alteração exige `save()`; para registro disputado, use `Saveable.mutate(...)` |
| Banco vazio a cada deploy no Coolify | SQLite dentro do contêiner, sem volume | Monte `/data` como *persistent storage* e defina `ANGATU_DB_PATH=/data/database.db` |
| `UnsupportedOperationException: HTTPS gerenciado saiu da biblioteca` | `new AngatuLib(host, porta, rateLimit, true)` ou `JavalinAPI.setup` com `manageSsl = true`, do tempo em que o Javalin cuidava do certificado | Use `new AngatuLib(host, porta, rateLimit)`: a aplicação sobe em HTTP e o Coolify termina o TLS |
| `Não foi possível registrar a rota` | Registro manual antes do servidor ativo | Registre após o `setup` ou deixe a descoberta automática fazer o trabalho |
| Logs sem cor no terminal | Terminal sem suporte ANSI ou stream redirecionado | Use um terminal compatível (Windows Terminal, VS Code) |
| Terminal cheio de linhas de requisição (o `HEALTHCHECK` também aparece) | O log de requisições escreve toda requisição, por padrão | `ANGATU_REQUEST_LOG=errors` no Coolify para ver só as falhas, ou `off` para desligar |
| `Log de requisições: N linha(s) descartada(s)` | O terminal não acompanhou o ritmo das requisições e a fila do log encheu | Nada quebrou: as requisições foram atendidas. Se for constante, `ANGATU_REQUEST_LOG=errors` |

---

## ❓ FAQ

**A biblioteca é pesada?**
Não. O JAR contém apenas o código da própria biblioteca (~380 KB). Dependências de terceiros são declaradas pelo consumidor sob demanda.

**Preciso adicionar todas as dependências de uma vez?**
Não. Adicione apenas as dos módulos que usar. Se algo faltar, a biblioteca exibe a mensagem com o trecho exato de Maven/Gradle.

**Posso usar `Console` antes de inicializar o `AngatuLib`?**
Sim. O `Console` funciona com fallback para `System.out` quando a biblioteca ainda não foi inicializada (correção incluída na versão atual).

**O rate limiting funciona por IP ou por rota?**
Ambos: com `perIp = true` (padrão), o contador é do IP naquela rota — o molde dela (`/api/pedidos/{id}`), não a URL, então `/api/pedidos/1` e `/api/pedidos/2` contam juntos. IPv6 conta por `/64`, e num limite configurado o `/48` inteiro tem um teto de 16 clientes. Bloqueios longos (24 h) são gravados e recarregados ao reiniciar; os temporários vivem só em memória.

**O Saveable aguenta muitas requisições ao mesmo tempo?**
Sim — é o contrato dele, coberto por teste com milhares de leituras e gravações simultâneas, em threads virtuais e em pool de plataforma. Uma conexão de escrita numa fila justa (sem disputa pelo arquivo dentro do processo), leitura em paralelo num pool só de leitura (WAL), nenhuma leitura segurando duas conexões, `mutate` sem atualização perdida e prazo em toda espera. Nada fica em memória: cada busca vai ao banco.

**Preciso de certificados?**
Não, nem para rodar localmente nem em produção. A biblioteca sobe só em HTTP, na porta informada; no Coolify, certificado, renovação e redirecionamento para HTTPS são do proxy de borda.

**Playwright não funciona — o que fazer?**
Instale o browser uma vez: `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`.

---

## 🔄 Migração entre versões

### Para a versão atual (Javalin 7.2.3, setembro de 2026)

Nada muda no banco: mesmo arquivo, mesmas colunas, mesmo `INSERT OR REPLACE`. As mudanças de
código que um projeto pode notar:

| Mudança | O que fazer |
|---|---|
| Javalin **7.2.3** (Jetty 12.1.12, sem os três CVEs de 2026 do 12.1.8), `jakarta.mail` **2.0.2** (CVE-2025-7962, injeção SMTP), `bcprov-jdk18on` **1.86** | Atualize as versões no `pom.xml` do projeto |
| `Saveable`: falha de banco dentro de transação agora é `PersistenceException` (antes `IllegalStateException`) | Quem capturava `IllegalStateException` em volta de `transaction` passa a capturar `PersistenceException` |
| `Saveable.save()` de entidade sem campo de ID que o `getId()` leia lança `IllegalStateException` (antes devolvia `false` sem gravar) | Declare um campo `id` (`String` ou `UUID`) e devolva-o no `getId()` — o campo pode estar numa classe base |
| `Saveable`: transação aninhada é *savepoint* — se a interna falhar e a externa seguir, o que a interna gravou é desfeito | Se algum código dependia de a interna valer pela metade, ele estava errado; nada a fazer no caso normal |
| `Saveable.findByField(..., null)` resolve no SQL (`IS NULL`), inclusive para registro sem a chave no JSON | Nada, no caso normal |
| `Saveable.mutate` recusa bloco que troca o ID do registro (`IllegalStateException`) | Crie o registro novo com `save()` em vez de trocar o ID no `mutate` |
| `Saveable.tableName(Classe.class)` público | Use no SQL de `query()` em vez de escrever o nome da tabela à mão |
| `Saveable`: transação que o SQLite desfaz sozinho (disco cheio, E/S, `ON CONFLICT ROLLBACK`) termina em `PersistenceException` mesmo que o bloco capture a falha e siga | Nada, no caso normal — antes, o resto do bloco era gravado fora da transação |
| `Saveable.query()` recusa controle de transação (`BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT`, `RELEASE`) com `IllegalArgumentException` | Troque o `BEGIN … COMMIT` escrito à mão por `Saveable.transaction(...)` |
| `Saveable.shutdown()` espera as leituras em curso (até `ANGATU_DB_BUSY_TIMEOUT_MS`) antes de fechar | Nada — antes, a conexão de uma leitura em curso ficava aberta para sempre, segurando o arquivo |
| `Task`: o ID devolvido é sempre positivo e não se repete enquanto a tarefa dele estiver registrada | Nada |
| `JavalinAPI`: corpo lido em memória limitado a **16 MiB** (antes 1 GB) | Se alguma rota recebe corpo maior que isso sem multipart, chame `JavalinAPI.setMaxRequestSize(bytes)` antes do `new AngatuLib` |
| `JavalinAPI`: sem `setTrustedProxyHops`, o `X-Forwarded-For` passa a valer quando a conexão vem de rede privada (o proxy do Coolify) | Declare `setTrustedProxyHops(n)` para fixar a regra; `setTrustedProxyHops(0)` volta a ignorar o cabeçalho sempre |
| `IP.get(ctx)` segue a mesma regra do rate limit (antes devolvia o **primeiro** item do `X-Forwarded-For`, que o cliente escreve) | Nada — o valor passa a ser o IP real |
| `JavalinAPI`: filtro de conteúdo por palavra inteira, corpo de texto até 64 KiB, sem JSON comum recusado | Nada |
| `JavalinAPI`: arquivo estático fora do limite só em GET/HEAD de caminho que não é rota; `/image` fora do limite | Nada |
| **HTTPS gerenciado saiu: a biblioteca roda só atrás do Coolify.** `new AngatuLib(host, porta, rateLimit, true)` e `JavalinAPI.setup(porta, rateLimit, true, pasta)` lançam `UnsupportedOperationException`; `isManageSsl()` é sempre `false`, e `getCertsPath()`/`getFolderCerts()` devolvem `null` | Use `new AngatuLib(host, porta, rateLimit)` e tire `javalin-ssl` do `pom.xml`; para subir só o servidor, `JavalinAPI.setup(porta, rateLimit)` ou `setup(porta, rateLimit, antesDeSubir)` |
| `JavalinAPI`: o contador do limite é da **rota** (o molde, com os parâmetros), não da URL — `/api/pedidos/1` e `/api/pedidos/2` contam juntos; caminho que não é rota divide um contador só | Procure as telas que pedem muitos itens seguidos da mesma rota com parâmetro e dê a essa rota um limite próprio com `configureRateLimit` |
| `JavalinAPI.configureLoginRateLimit`: 2 por segundo (antes 1), para o duplo clique no botão não virar 429 | Nada |
| `JavalinAPI`: preflight de CORS fora do limite; IPv6 com teto de 16 clientes por `/48` num limite configurado; pedido recusado pelo filtro de conteúdo conta no limite; formulário e JSON varridos já decodificados; `.json` e `.wasm` contam como arquivo estático | Nada |
| **Toda requisição vira uma linha no terminal** (novo, ligado por padrão); o `debug` "Acessando […]" das páginas saiu, porque a linha da requisição o substitui | Tire o `Console.log` de acesso que alguma rota escreva, para não duplicar; para reduzir, `ANGATU_REQUEST_LOG=errors` ou `JavalinAPI.setRequestLogMode(...)` |
| `JavalinAPI.addIgnoredPath("/x")` vale por segmento (`/x` e `/x/...`, não `/xy`) | Confira se algum projeto dependia do prefixo solto |
| `AssetsAPI.serveAsset` não define mais `Cache-Control` (era um dia) | O projeto decide o cabeçalho de cache |
| `AngatuLib` lança `IllegalStateException` quando o servidor não sobe (antes seguia com o servidor fora do ar) e registra o próprio gancho de desligamento | Não chame `Task.shutdown()` num gancho seu (ele descarta a fila); se precisar, use `Task.drain(ms)` |
| `Password.hash(senha)` para a senha digitada; `criptography` só repassa hash de custo 4 a 12 | Troque `criptography` por `hash` onde a entrada vem do usuário |
| `Console`: argumentos de texto das mensagens formatadas aparecem como estão (um `&` não vira cor) | Nada |
| `EmailAPI.loadHtmlTemplate`: os valores recebem escape HTML | Quem passava HTML pronto num placeholder usa `loadHtmlTemplateWithTrustedHtml(caminho, textos, htmlConfiavel)` |
| `EmailAPI`: item da lista com mais de um endereço, grupo ou quebra de linha recusa o envio; anexo ausente ou ilegível também (antes era pulado e o envio devolvia `true`) | Um endereço por item; confira os anexos antes de enviar |
| `EmailAPI`: certificado do servidor SMTP verificado (antes aceitava qualquer um para `smtp.gmail.com`), STARTTLS obrigatório e prazo em toda operação de rede | Nada, com o Gmail |
| `EmailFormatter`: provedores reais saíram da lista de descartáveis, subdomínio de descartável passa a ser descartável, espaço nas pontas é ignorado e `o'brien@...` é aceito | Nada |
| `WebPushAPI`: endpoint fora dos push services conhecidos lança `IllegalArgumentException` em `createSubscription`/`parseSubscriptionFromJson` | Responda 400 na rota; `allowPushServiceHost(host)` para um serviço legítimo fora da lista |
| `WebPushAPI`: o future de envio nunca completa com exceção — falha de rede vira `SendResult` com status 0 | Quem tratava `exceptionally` passa a olhar `result.isSuccess()` |
| `PushBootstrap.setup()` lança exceção quando as chaves gravadas não formam par (antes registrava "inicializado com sucesso" e nenhum push saía); novo `setup(subject)` | Informe o contato do projeto em `setup(subject)` ou em `ANGATU_WEBPUSH_SUBJECT` |
| `ImageAPI`: o formato vem dos bytes; SVG, HTML, WBMP, PCX, PNM e RAW são recusados; `extractToImageObject(id, bytes)` substitui a versão com `mimeType` (que passa a ignorá-lo) | Trate a `IllegalArgumentException` do upload como 400 |
| `ImageAPI`: imagem acima de 40 megapixels é recusada na leitura | `ImageAPI.setMaxPixels(n)` se o projeto precisa de mais |
| `ImageAPI`: a orientação EXIF é aplicada na leitura; `resize`/`rotate` devolvem `TYPE_INT_RGB` ou `TYPE_INT_ARGB` | Tire a rotação manual que vinha depois de `readImage`, senão a foto gira duas vezes |
| `ImageAPI`: falha ao gravar lança `IOException` (antes o arquivo simplesmente não aparecia); argumento nulo lança `IllegalArgumentException` | Nada, no caso normal |
| `ImagesRoute`: tipo gravado fora da lista de imagens sai como download | Nada — registros antigos de SVG deixam de rodar script |
| `ImageAPI`: os três métodos de vídeo (nunca implementados) viraram `@Deprecated(forRemoval = true)` | Não use |
| `Request`: prazo de 15 s para a chamada inteira, corpo incluído; resposta acima de 10 MiB falha; no máximo 4 redirecionamentos; corpo de erro vazio é `""` (antes `null`) | `Request.setTimeout(...)` / `setMaxBodyBytes(...)` para uma API lenta ou grande conhecida |
| `Response.getCode()` devolve o código recebido (207, 522...) em vez de `-1`; `isSuccess()` vale para todo 2xx | Nada, no caso normal |
| `DataTime.parseDate`/`parseDateTime` são estritos: `31/02/2025` é recusado (antes virava 28/02) | Trate a exceção onde a data vem de formulário |
| `GsonAPI` lê e grava `LocalDateTime`, `LocalTime`, `ZonedDateTime`, `Instant` e `Duration` em ISO-8601 (antes lançava `JsonIOException`); data inválida vira `JsonSyntaxException` | Nada — responda 400 onde já se tratava `JsonParseException` |
| `Env`: variável de ambiente real vence o `.env`; as `ANGATU_*` só valem no ambiente do processo; `.env` malformado não derruba a aplicação | Cadastre as `ANGATU_*` como variáveis do serviço no Coolify |
| `MercadoPagoAPI.findById` lança `MercadoPagoException` quando a API falha (antes devolvia vazio, como se o pagamento não existisse); `processWebhook` lança `MPException` | Responda 5xx ao webhook nessa exceção, para o Mercado Pago reenviar |
| `MercadoPagoAPI.validateWebhookSignature` aceita notificação sem `x-request-id` ou sem `data.id` (o par sai do manifesto, como na documentação oficial) | Nada |
| `MercadoPagoAPI`: pagamento com cartão com `externalReference` ganha chave de idempotência derivada dos dados | Para cobrar de novo o mesmo pedido com o mesmo cartão, passe uma `idempotencyKey` nova |
| `Bot.setup()` não pede mais a permissão MESSAGE_CONTENT e espera o Discord por até 30 s | Quem lê o texto das mensagens pelo `getJDA()` usa `Bot.setup(token, true)` |
| `BrowserAPI` recusa loopback, rede privada e metadado da nuvem, inclusive em redirecionamento, iframe e imagem do HTML renderizado | Em desenvolvimento, HTML com imagem de `http://localhost:porta` precisa de `BrowserAPI.setAllowPrivateNetworkAccess(true)`; em produção, com o domínio público, nada muda |
| `BrowserAPI`: `data:` e `about:` recusados como URL; com os dois navegadores ocupados por 30 s, a chamada falha; opção inválida lança exceção | Use `*FromHtml` para HTML próprio |

### Para a versão anterior (Javalin 7.2.2)

| Mudança | O que fazer |
|---|---|
| **Dependências não são mais empacotadas** | Adicione ao seu `pom.xml`/`build.gradle` as dependências dos módulos usados (tabela acima) |
| Javalin atualizado de 7.2.0 → **7.2.2** | Patch release — nenhuma mudança de código necessária |
| `javalin-ssl` atualizado de 7.1.0 → **7.2.2** | Apenas atualize a versão no `pom.xml` |
| Classes `Request.Response` e `Response` unificadas | `Response` agora tem `getStatusCode()`, `getCode()` e `isSuccess()` além de `getStatus()`/`ok()` — o código antigo continua compilando |
| Opções duplicadas do `BrowserAPI` removidas | Use as inner classes `BrowserAPI.ScreenshotOptions` / `BrowserAPI.ScrapeOptions` / `BrowserAPI.BaseBrowserOptions` (as classes standalone foram removidas) |
| Logging do `EmailAPI` padronizado | Mensagens agora usam `Console` (mesma formatação do restante da biblioteca) |
| **Construtores de `Route` agora `protected`** | Não quebra subclasses existentes (construtor vazio + `super(...)` continua válido); apenas instanciação direta (já impossível — classe abstrata) fica formalmente bloqueada |
| **Construtor de `Saveable` agora `protected`** | Mesma política — uso exclusivo via `extends`, sem quebra de subclasses existentes |
| **HTTPS deixou de ser automático** | O construtor de três parâmetros agora sobe em **HTTP na porta informada** (antes: porta 80 em localhost, HTTPS se houvesse certificados). Quem quiser o Javalin gerenciando o certificado passa `true` no quarto parâmetro: `new AngatuLib(host, 443, true, true)` — HTTPS gerenciado que saiu na versão atual |
| **`isLocalhost()` mudou de critério** | Não olha mais a pasta de certificados: declare `ANGATU_ENV=production` (o `Dockerfile` modelo já faz isso) ou use `localhost` como host em desenvolvimento; sem declaração, host real é tratado como produção |
| **`JavalinAPI.setup` com nova assinatura** | `setup(int port, boolean enableRateLimit, boolean manageSsl, File folderCerts)` — a ordem mudou de propósito, para que chamadas antigas quebrem no compilador em vez de inverterem o sentido do parâmetro (depreciada na versão atual: use `setup(porta, rateLimit)`) |
| **`Saveable` sem cache em memória** | Toda leitura vai ao banco e devolve instância nova; alterações só valem após `save()`. Onde havia leitura-alteração-gravação concorrente, use `Saveable.mutate(...)`; consultas frequentes por campo pedem `Saveable.createIndex(...)` |
| **Banco de dados sem nenhuma mudança** | Mesmo arquivo (`database.db` por projeto), mesmas colunas (`id`, `data`) e mesmo `INSERT OR REPLACE` — nada a migrar, e os bancos de sistemas em produção continuam compatíveis com versões anteriores da lib |
| **Todo projeto passa a ter `Dockerfile`** | Copie `templates/Dockerfile` e `templates/.dockerignore`, exponha a porta de `PORT` e monte `/data` no Coolify |
| **Data holders agora `final`** | `Response`, `BlockInfo`, `RateLimitConfig`, `SlidingWindowCounter`, `CachedHtml`, TypeAdapters, `ScreenshotOptions` e `ScrapeOptions` do `BrowserAPI` não podem mais ser estendidos (nenhum caso de uso legítimo para herança) |

---

## 📝 Changelog resumido

### Versão atual (setembro de 2026)
* 🗄️ **`Saveable` para milhares de acessos simultâneos**: uma conexão de escrita numa fila justa, leitura em paralelo num pool `query_only`, nenhuma leitura segurando duas conexões, prazo em toda espera; sem trava por registro (que podia travar duas threads para sempre); gravação dentro de `mutate` entra na mesma transação; transação aninhada é savepoint
* 🐛 **Tabela "pronta" sem existir**: a primeira vez de uma entidade dentro de uma transação que desfazia deixava a entidade sem tabela até reiniciar — agora a tabela só vale depois do commit, e uma tabela que sumiu se recria na chamada seguinte
* 🐛 **Transação desfeita pelo próprio SQLite** (disco cheio, erro de E/S, `ON CONFLICT ROLLBACK`): quem capturava a falha e seguia gravava o resto fora da transação, cada comando confirmado sozinho — agora a transação termina em falha, sem nada gravado
* 🐛 `commit()` do driver reabria a transação e podia acusar erro depois de o dado estar gravado; o `cache_size` configurado nunca chegava ao SQLite; `findByField` com `BigDecimal` não achava nada; ID em classe base e `boolean paid` antes do `id` quebravam o `save()`; nome de tabela igual a palavra reservada quebrava o SQL
* 🐛 `shutdown()` com leitura em curso deixava a conexão dela aberta para sempre (arquivo preso, `-wal` sem checkpoint); pedido de interrupção pendente derrubava a gravação quando havia fila; `BEGIN`/`COMMIT` avulsos no `query()` desmontavam a transação da biblioteca e agora são recusados
* 🔒 **Rate limit**: variações de barra (`/api//login`) não furam mais o limite; janela de 1 s contava 2 s; bloqueio longo decidido em memória (antes, uma consulta ao banco por requisição do IP bloqueado); IPv6 por `/64`; upgrade de WebSocket passa pelo bloqueio; histórico de violações gravado em lote; contador por molde de rota (antes, cada URL inventada abria um contador novo na memória, e um código de cupom tentado em série nunca batia no limite); teto por `/48` para quem troca de `/64` a cada pedido; preflight de CORS fora da conta (gastava o limite do login); limite conferido antes da varredura de conteúdo
* 🔒 **IP do cliente**: `IP.get` segue a regra de proxy (antes devolvia o valor que o cliente escreve); regra automática atrás de proxy em rede privada; `setClientIpHeader` para CDN; todas as linhas do `X-Forwarded-For` (antes só a primeira, que pode ser a do cliente); IP do socket sem colchetes
* 🔒 **Filtro de conteúdo**: palavra inteira e distância limitada (JSON comum com `updatedAt`, `setor`, `offset` era recusado; texto de 128 KB custava 10 s de CPU); corpo lido só se for texto de até 64 KiB; limite de corpo de 16 MiB (antes 1 GB); formulário e JSON varridos já decodificados (`%3Cscript` e `<script` passavam)
* 🔒 Redirecionamento de `.html` sem open redirect, sem laço e com a query; `X-Content-Type-Options: nosniff`; `AssetsAPI` sem path traversal
* 🎨 Página de recusa (429/403) desenhada pelo projeto com `JavalinAPI.setDenyPage` — se a função falhar de qualquer jeito, até por exceção verificada ou estouro de pilha, vale a página padrão; cabeçalhos de cache declarados antes da subida valem também para os arquivos estáticos
* 🌐 Rotas e páginas registradas antes de o servidor aceitar conexões; descoberta de rotas também no `mvn exec:java`; página duplicada vira aviso em vez de derrubar a subida
* 🧾 **Log de requisições**: uma linha por requisição — data e hora, método, caminho, IP, status, tempo, parâmetros mascarados, motivo da recusa e tipo da exceção —, sem configuração por rota, escrita por uma fila própria que nunca atrasa a resposta; `ANGATU_REQUEST_LOG` e `JavalinAPI.setRequestLogMode` reduzem ou desligam
* 🐳 **Só atrás do Coolify**: o HTTPS gerenciado e a pasta de certificados saíram — o servidor sobe em HTTP, e o construtor ou o `setup` que ainda pedem HTTPS falham na hora, sem subir nada; `javalin-ssl` deixou de ser dependência
* ⚙️ **`Task`**: relógio separado do trabalho (a limpeza periódica não fica atrás de envios lentos), `Error` não mata timer, sem vazamento de entradas, `drain()` para desligar sem perder a fila — nem a tarefa de prazo vencido que o relógio ocupado ainda não tinha disparado; ID que dá a volta não sobrescreve mais o registro de um timer vivo (o `cancelTask` dele deixava de pará-lo)
* 🚀 **`AngatuLib`**: falha na subida lança exceção (antes deixava o processo vivo sem servidor); gancho de desligamento que para o servidor, espera as tarefas e fecha o banco
* 🔐 **`Password`**: hash de custo alto não é mais aceito como veio (cada login custava horas de CPU); `$2b$`/`$2y$` verificam; `hash()` para senha digitada
* 🪵 **Log**: `&` no dado não vira mais código de cor; uma linha lógica, uma linha de log; sem recursão do interceptador; linha longa cortada sem partir um acento ao meio; a última linha sem quebra sai no desligamento
* 🖥️ **Navegador**: sem acesso a rede interna, nem por redirecionamento (um proxy local confere cada conexão do Chromium); pool fixo de dois navegadores (antes, cada chamada simultânea além de duas abria mais um Chromium, sem limite); opções de captura que eram ignoradas; `minifyHtml`, `stripHtml` e extração de links lineares (200 KB em milissegundos, antes 30 s)
* 💳 **Pagamentos**: falha da API não se disfarça mais de "pagamento não encontrado"; webhook com comparação em tempo constante e manifesto da documentação oficial; idempotência no cartão; valores em centavos exatos; um cliente do SDK por JVM (antes vazava um *handler* de log a cada chamada); prazos que o SDK tratava como "esperar para sempre"
* 🤖 **Discord**: conecta sem a permissão privilegiada, com prazo na subida e em cada envio, `shutdown()`, sessão encerrada pelo Discord reconectada; download de imagem só de endereço público
* 🧠 **IA**: prazo na resposta inteira, stream que sempre fecha a conexão e avisa o fim ou o erro, chave da API mascarada no log
* 🔗 **`Request`**: prazo para a chamada inteira, resposta limitada, `PATCH`, redirecionamento sem vazar `Authorization`, código HTTP real no `Response`, cliente HTTP compartilhado com threads limitadas
* 🧰 **Utilitários**: `DataTime` estrito (31/02 não vira 28/02) e com nomes em português; `StringAPI.randomCode` com `SecureRandom`; `maskString` que devolvia o texto sem máscara; cinco tipos de `java.time` novos no `GsonAPI`, que também leem o formato antigo; `.env` malformado não derruba mais a aplicação
* 📨 **E-mail**: escape HTML nos valores de template (`loadHtmlTemplateWithTrustedHtml` para HTML montado pelo sistema); um endereço por item, e uma vírgula num campo de formulário não acrescenta destinatário; certificado SMTP verificado e STARTTLS obrigatório; prazo em conexão, leitura e escrita; fila própria; future sempre completa; anexo ausente não passa mais como enviado
* 🖼️ **Imagens**: formato pelo conteúdo (SVG e HTML recusados), teto de pixels contra bomba de descompressão, GIF animado que saía vazio, PNG com transparência gravado como JPEG que não gravava nada, foto de celular de lado, download de URL sem acesso a rede interna, `/image` com `nosniff` e `sandbox`; `QRCodeAPI` e `ImageAPI` carregam sem as dependências e mostram a mensagem de instalação
* 🔔 **Web Push**: só push services conhecidos (o endpoint servia de porta para requisição a endereço interno); cliente HTTP com prazos, sem redirecionamento e com resposta limitada; fila própria de envio; `Urgency` chega ao push service; futures sempre completam; subject configurável; geração de chaves sem corrida
* ⬆️ Javalin **7.2.3**, `jakarta.mail` **2.0.2**, `bcprov-jdk18on` **1.86**
* 🐳 Dockerfile modelo com `-XX:+ExitOnOutOfMemoryError` e `LANG=C.UTF-8`; `.dockerignore` exclui `.env` e bancos em qualquer pasta
* 🧪 Primeiros testes automatizados (JUnit 5): persistência, concorrência, servidor real em processo, rate limit, tarefas, senha e log

### Versão anterior
* 🐳 **Hospedagem no Coolify**: HTTP por padrão na porta informada e HTTPS só quando pedido (`new AngatuLib(host, port, rateLimit, manageSsl)`); modelos de `Dockerfile`/`.dockerignore` em `templates/`; ambiente resolvido por `ANGATU_ENV` em vez da pasta de certificados
* 🗄️ **`Saveable` sem dados em RAM**: cache total e *identity map* removidos — leitura e gravação direto no SQLite, um pool por aplicação (antes um por classe de entidade), transações `IMMEDIATE`, `busy_timeout` e travas por registro; novos `mutate()`, `transaction()`, `computeInTransaction()`, `saveAll()`, `createIndex()`, `findFirstByField()`; caminho do banco configurável por `ANGATU_DB_PATH`. **Formato do banco inalterado** (`id`, `data`, `INSERT OR REPLACE`): nenhuma migração, bancos existentes seguem compatíveis
* 🔒 **Restrições de inicialização**: construtores de `Saveable` e `Route` agora `protected` (uso exclusivo via `extends`, com mensagens claras de uso incorreto); `Route` valida servidor ativo e argumentos no construtor; data holders (`Response`, `BlockInfo`, `RateLimitConfig`, `SlidingWindowCounter`, `CachedHtml`, TypeAdapters, `ScreenshotOptions` e `ScrapeOptions` do `BrowserAPI`) e `Core` agora `final`
* 🔌 **Carregamento lazy de dependências**: todos os usos de bibliotecas de terceiros movidos para classes helper aninhadas — 39/43 classes públicas passam a ser linkáveis sem dependências e os guards de instalação disparam de fato no primeiro uso (validado por testes de runtime)
* 📖 JavaDocs estruturados para humanos e IAs (propósito, quando usar/não usar, integração, fluxo, pré/pós-condições, efeitos colaterais, limitações, extensões)
* 📚 README como documentação principal: arquitetura com diagrama, filosofia, conceitos, fluxo de funcionamento, estrutura recomendada de projeto, índice navegável, troubleshooting e notas para agentes de IA
* 🪶 JAR leve: dependências de terceiros removidas do empacotamento (scope `optional`/`provided`)
* 🔍 Detecção automática de dependências ausentes com instruções Maven/Gradle padronizadas
* ⬆️ Javalin atualizado para **7.2.2** (e javalin-ssl 7.2.2)
* 🐛 Correção: `Console` não lança mais NPE quando usado antes da inicialização
* 🐛 Correção: TypeAdapters de datas tratam corretamente JSON `null`
* 🐛 Correção: `Env` não quebra mais a inicialização sem arquivo `.env`
* 🐛 Correção: stack traces agora são impressas corretamente em todos os logs de erro
* ⚡ Performance: hash de IP com tabela hexadecimal, regex pré-compiladas, cache de formatadores, `ArrayDeque` no sliding window, QR Code com escrita de pixels em lote, cliente HTTP compartilhado no WebPush
* 🧹 Código morto removido (OkHttp, JCodec, SLF4J não utilizados; classes de opções duplicadas do BrowserAPI)
* 🧹 `Request.Response` e `Response` unificadas em uma única API
* 📖 JavaDocs completos em todas as APIs públicas
* 📖 README completo com guias, exemplos e FAQ

---

## 🤝 Contribuição

Biblioteca voltada para uso interno da **Angatu Sistemas**. Sugestões e melhorias podem ser propostas conforme a necessidade dos projetos.

## 📄 Licença

Uso restrito à **Angatu Sistemas**. A utilização externa deve ser previamente autorizada.

---

## 🏢 Organização

Desenvolvido por **Angatu Sistemas**
