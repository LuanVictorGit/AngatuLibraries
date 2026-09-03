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

* 🪶 **Leve** — o JAR contém apenas o código da biblioteca (~185 KB). As dependências de terceiros **não são empacotadas**; você adiciona somente as dos módulos que usa.
* 🛡️ **Segura** — rate limiting com janela deslizante, detecção de SQL Injection/XSS, bloqueios permanentes persistidos e headers de segurança automáticos.
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

## 🏗️ Arquitetura

```
┌──────────────────────────────────────────────────────────────────────┐
│                         SUA APLICAÇÃO                                 │
│  Main → new AngatuLib(host, porta, rateLimit[, gerenciarSsl])        │
└──────────────────────────────┬───────────────────────────────────────┘
                               │ bootstrap
┌──────────────────────────────▼───────────────────────────────────────┐
│                        AngatuLibraries (JAR ~185 KB)                  │
│                                                                       │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────────────┐  │
│  │  Console    │  │  Dependencies│  │  Task (pools de threads)     │  │
│  │  (log ANSI) │  │  (detecção)  │  └──────────────┬──────────────┘  │
│  └──────┬──────┘  └──────┬───────┘                 │ async           │
│         │                │                         ▼                  │
│  ┌──────▼────────────────▼───────────────────────────────────────┐   │
│  │                  JavalinAPI (Web Server)                     │   │
│  │  HTTP (ou HTTPS opcional) · Rate limit · SQLi/XSS · Headers   │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐  │   │
│  │  │ Route (abstr.)│  │ HtmlRouteAPI │  │ AssetsAPI         │  │   │
│  │  │ rotas auto    │  │ páginas /public│ │ cache + MIME      │  │   │
│  │  └──────┬───────┘  └──────────────┘  └────────────────────┘  │   │
│  └─────────┼────────────────────────────────────────────────────┘   │
│            │ persistência (bloqueios, configurações)                 │
│  ┌─────────▼────────────────────────────────────────────────────┐   │
│  │  Saveable (ORM JSON → SQLite + HikariCP + WAL)                │   │
│  │  entidades: PermanentBlock · SuspectIp · RouteRateLimitConfig │   │
│  │            Key (VAPID) · Image · suas entidades (extends)      │   │
│  └───────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  Módulos opcionais (cada um com guard de dependência):                │
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
| **Escrita serializada** | Transações `IMMEDIATE` + `busy_timeout` + travas por registro: escritas concorrentes (threads ou processos) não se sobrepõem nem perdem alterações |
| **Janela deslizante** | Algoritmo de rate limiting por timestamps dentro de uma janela (segundo/minuto) — `SlidingWindowCounter` com fila O(1) |
| **Descoberta de rotas** | Subclasses de `Route` com construtor vazio são encontradas via Reflections e registradas no startup |
| **Bloqueios persistidos** | IPs suspeitos, bloqueios temporários e permanentes sobrevivem a reinicializações (tabelas `suspectips`, `permanentblocks`, `routeratelimitconfigs`) |
| **Log interceptado** | `System.out` é redirecionado para o `Console` (log colorido com timestamp); o stream original fica preservado |

### Fluxo de funcionamento

```
main()
 └─ new AngatuLib(host, porta, rateLimit[, gerenciarSsl])
     ├─ 1. Dependencies.require("io.javalin.Javalin", ...)   → mensagem clara se faltar
     ├─ 2. System.setOut(InterceptorOutputStream → Console)  → log colorido
     ├─ 3. Resolve o ambiente (ANGATU_ENV / host local) → isLocalhost()
     ├─ 4. JavalinAPI.setup(porta, rateLimit, gerenciarSsl, certs)
     │      ├─ loadPersistedConfigs()   → bloqueios/configs do banco
     │      ├─ HTTP na porta informada (padrão) ou HTTPS gerenciado (opcional)
     │      ├─ before-handler: headers + SQLi/XSS + rate limiting
     │      └─ Task.runTimerWithFixedDelay(cleanupOldData, 24h)
     ├─ 5. HtmlRouteAPI.registerAllRoutes()  → páginas /public/*.html
     └─ 6. Banner de inicialização
```

---

## ✨ Recursos disponíveis

| Módulo | Classe principal | Descrição |
|---|---|---|
| 🌐 **Web Server** | `JavalinAPI`, `HtmlRouteAPI`, `Route` | Servidor HTTP (Javalin 7.2.2) com rate limiting, proteção contra SQLi/XSS, rotas por convenção e HTTPS opcional sob demanda |
| 📁 **Assets** | `AssetsAPI` | Servir arquivos estáticos do classpath com cache e MIME types |
| 🗄️ **Persistência** | `Saveable` | ORM JSON sobre SQLite (HikariCP + WAL) com cache em memória e identidade por ID |
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

> Substitua `VERSION` pela versão desejada em https://jitpack.io/#LuanVictorGit/AngatuLibraries

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

> **Como funciona a verificação:** 39 das 43 classes públicas são **linkáveis sem nenhuma dependência** — os guards rodam no primeiro uso e exibem a mensagem de instalação. Quatro classes expõem tipos de terceiros na própria assinatura pública e, por contrato de API, exigem a dependência já no classload: `JavalinAPI` (tipos do Javalin), `QRCodeAPI` (parâmetro `ErrorCorrectionLevel` do ZXing) e os TypeAdapters de datas (estendem `TypeAdapter` do Gson). Para essas, a ausência gera `NoClassDefFoundError` nomeando a classe faltante — a dependência é obrigatória para usar a API.

> **Exemplo da mensagem exibida:**
> ```
> [AngatuLibraries] Dependência ausente: io.javalin:javalin:7.2.2
>
> A funcionalidade "Web Server (Javalin)" depende desta biblioteca, mas ela não foi encontrada no classpath.
>
> Para habilitar esta funcionalidade, adicione:
>
> Maven:
> <dependency>
>     <groupId>io.javalin</groupId>
>     <artifactId>javalin</artifactId>
>     <version>7.2.2</version>
> </dependency>
>
> Gradle:
> implementation("io.javalin:javalin:7.2.2")
> ```

### Tabela de dependências

| Módulo | Dependências necessárias |
|---|---|
| Web Server, HTML, Assets, Rotas | `io.javalin:javalin:7.2.2`, `io.javalin.community.ssl:javalin-ssl:7.2.2` (só no modo HTTPS gerenciado), `org.reflections:reflections:0.10.2` (rotas automáticas), + um binding SLF4J (ex: `org.slf4j:slf4j-simple:2.0.17`) |
| Persistência (`Saveable`) | `org.xerial:sqlite-jdbc:3.51.3.0`, `com.zaxxer:HikariCP:7.0.2`, `com.google.code.gson:gson:2.13.2` |
| JSON (`GsonAPI`) | `com.google.code.gson:gson:2.13.2` |
| `.env` (`Env`) | `io.github.cdimascio:dotenv-java:3.2.0` |
| Senhas (`Password`) | `org.mindrot:jbcrypt:0.4` |
| Web Push | `nl.martijndwars:web-push:5.1.2`, `org.bouncycastle:bcprov-jdk18on:1.83`, `org.bitbucket.b_c:jose4j:0.9.6`, `org.apache.httpcomponents:httpclient:4.5.14` |
| E-mail | `com.sun.mail:jakarta.mail:2.0.1`, `io.github.cdimascio:dotenv-java:3.2.0` |
| Discord | `net.dv8tion:JDA:6.4.1` |
| Pagamentos | `com.mercadopago:sdk-java:2.9.2` |
| Navegador (Playwright) | `com.microsoft.playwright:playwright:1.58.0` (+ executar `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` uma vez) |
| Imagens | `net.coobird:thumbnailator:0.4.21` (thumbnails), `com.twelvemonkeys.imageio:imageio-webp:3.12.0` e `imageio-tiff` (formatos extras) |
| QR Code | `com.google.zxing:core:3.5.3`, `com.google.zxing:javase:3.5.3` |

---

## ⚙️ Configuração

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

O ponto de entrada é a classe `AngatuLib`. O servidor sobe em **HTTP na porta informada** — o HTTPS é da hospedagem (o Coolify termina o TLS no proxy de borda). O Javalin só gerencia certificado quando isso é pedido explicitamente no quarto parâmetro.

```java
import br.com.angatusistemas.lib.AngatuLib;

public class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        // Padrão: HTTP na porta informada (Coolify cuida do HTTPS)
        new AngatuLib("meusite.com.br", port, true);

        // Desenvolvimento local
        // new AngatuLib("localhost", 8080, true);

        // Fora do Coolify, com Let's Encrypt no próprio servidor:
        // new AngatuLib("meusite.com.br", 443, true, true);
    }
}
```

**Assinaturas:**

```java
new AngatuLib(String host, int port, boolean bloqByMaxRequisitions)
new AngatuLib(String host, int port, boolean bloqByMaxRequisitions, boolean manageSsl)
```

- `manageSsl = false` (padrão) — HTTP na porta informada; certificado, renovação e redirecionamento ficam com o Coolify/proxy reverso.
- `manageSsl = true` — o Javalin assume o certificado de `/etc/letsencrypt/live/<host>` (HTTPS na porta informada, HTTP em `porta + 1` só para redirecionar). Se os arquivos `fullchain.pem`/`privkey.pem` não existirem, a inicialização falha com `IllegalStateException` — pedir HTTPS e servir HTTP calado seria um rebaixamento silencioso de segurança.

**Ambiente:** `isLocalhost()` não depende mais da pasta de certificados (dentro de um contêiner ela nunca existe). A resolução é: `ANGATU_ENV`/`ENVIRONMENT`/`-Dangatu.env` (`production` ou `development`) → `manageSsl` → nome de host local (`localhost`, `127.0.0.1`, `::1`, `*.local`) → e, na ausência de tudo, **produção**.

Ao iniciar, a biblioteca:
1. Verifica as dependências dos módulos usados (mensagens claras se faltarem);
2. Redireciona `System.out` para o log colorido do `Console`;
3. Configura o Javalin com headers de segurança, rate limiting e — se pedido — SSL;
4. Descobre e registra automaticamente todas as rotas (`Route`) e páginas HTML em `/public`;
5. Agenda a limpeza diária de bloqueios expirados.

---

## 📁 Estrutura recomendada para projetos

```
seu-projeto/
├── src/main/java/
│   └── com/suaempresa/
│       ├── Main.java                  ← new AngatuLib(...) no main
│       ├── rotas/                     ← classes que estendem Route (descoberta automática)
│       │   ├── HomeRoute.java
│       │   └── ApiRoute.java
│       ├── entidades/                 ← classes que estendem Saveable
│       │   ├── Usuario.java
│       │   └── Produto.java
│       ├── servicos/                  ← lógica de negócio (EmailAPI, WebPush, etc.)
│       └── config/                    ← rate limits, paths, credenciais
├── src/main/resources/
│   ├── public/                        ← HTML servidos automaticamente
│   │   ├── index.html
│   │   ├── sobre.html
│   │   ├── css/ · js/ · img/
│   └── emails/                        ← templates (loadHtmlTemplate)
├── .env                               ← EMAIL_KEY, DISCORD_BOT_TOKEN, ...
└── pom.xml / build.gradle
```

**Regras da estrutura:**
- Uma rota por classe (construtor vazio + `super(path, type, handler)`) — a descoberta automática as registra no startup;
- Uma entidade por classe (extends `Saveable` + construtor vazio + `getId()`) — tabela criada automaticamente;
- Toda configuração de segurança (`configureRateLimit`, `addIgnoredPath`) em um único lugar (classe `config`), chamada logo após o `new AngatuLib(...)`;
- `.env` na raiz (nunca versionar segredos).

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
| Proxy | `JavalinAPI.setTrustedProxyHops(1)` para o IP real chegar ao rate limiting |
| Volume | `/data` montado como *persistent storage*; `ANGATU_DB_PATH=/data/database.db` |
| Ambiente | `ANGATU_ENV=production` já vem no `Dockerfile` |
| Saúde | rota `GET /health` fora do rate limit, usada pelo `HEALTHCHECK` |

```bash
# valide a mesma imagem localmente antes de subir
docker build -t meuprojeto . && docker run --rm -p 8080:8080 -v meuprojeto-data:/data meuprojeto
```

---

## 📖 Guias por funcionalidade

### 🌐 Servidor web com segurança (JavalinAPI)

```java
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;

public class Config {
    public static void main(String[] args) {
        new AngatuLib("localhost", 8080, true);

        // Rate limit por rota: 3 req/s, 20 req/min, bloqueio de 2 min por IP
        JavalinAPI.configureRateLimit("/api/*", new RateLimitConfig(3, 20, 120));

        // Presets prontos
        JavalinAPI.configureApiRateLimit("/api/v1/*");
        JavalinAPI.configureLoginRateLimit("/login");

        // Paths especiais
        JavalinAPI.addUnlimitedPath("/downloads/*");   // sem limite
        JavalinAPI.addIgnoredPath("/health");          // ignorado pela segurança

        // Limites globais (fallback)
        JavalinAPI.setGlobalRateLimit(5, 30, 300);

        // Acessar a instância do Javalin para uso avançado
        // Javalin app = JavalinAPI.get();
    }
}
```

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

**Páginas HTML:** coloque arquivos `.html` em `src/main/resources/public/`. A biblioteca registra cada página como rota (ex: `public/sobre.html` → `/sobre`, `public/index.html` → `/`). Use `{{placeholder}}` e `{%nome_active}` no template base para menus dinâmicos.

### 🗄️ Persistência automática (Saveable)

Qualquer classe pode ser persistida em SQLite herdando `Saveable`:

```java
import br.com.angatusistemas.lib.database.Saveable;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class Usuario extends Saveable {
    private String id;
    private String nome;
    private String email;

    public Usuario() {} // obrigatório para desserialização

    @Override
    public String getId() { return id; }
}
```

```java
// Criar e salvar
Usuario u = new Usuario();
u.setNome("João");
u.save(); // gera UUID automaticamente se id for nulo

// Buscar — vai ao banco e devolve uma instância nova a cada chamada
Usuario joao = Saveable.findById(Usuario.class, u.getId());

// Alterar registro disputado sem perder a alteração de quem chegou junto
Saveable.mutate(Conta.class, id, conta -> conta.setSaldo(conta.getSaldo() + 100));

// Duas gravações que precisam valer juntas
Saveable.transaction(() -> {
    estoque.save();
    new Pedido(usuarioId, produtoId).save();
});

// Índice + busca por campo resolvida no SQL
Saveable.createIndex(Usuario.class, "email");
Usuario porEmail = Saveable.findFirstByField(Usuario.class, "email", "joao@exemplo.com");

// Consultas customizadas (sempre com parâmetros posicionais)
List<Usuario> joes = Saveable.query(Usuario.class,
    "SELECT data FROM usuarios WHERE json_extract(data, '$.nome') = ?", "João");

// Encerrar a aplicação
Saveable.shutdown();
```

**Formato do banco inalterado.** Continua um `database.db` **por aplicação**, com a
tabela no mesmo formato de sempre — `id TEXT PRIMARY KEY, data TEXT NOT NULL` — e
gravação por `INSERT OR REPLACE`. Nenhuma coluna é criada, alterada ou removida:
bancos de sistemas que rodam versões anteriores da biblioteca continuam funcionando,
e um banco escrito por esta versão segue legível pelas anteriores.

**Concorrência.** Um pool HikariCP para o banco daquela aplicação (antes era um pool
por classe de entidade, todos no mesmo arquivo), com SQLite em WAL, `busy_timeout` e
transações `IMMEDIATE` — a trava de escrita é tomada no início da transação, então
duas alterações simultâneas são serializadas em vez de se sobreporem, entre threads e
entre processos. `save()` é atômico e a última escrita vence; `mutate()` lê, altera e
grava dentro da mesma transação, sem atualização perdida; `transaction()` faz várias
gravações valerem juntas ou nenhuma.

**Banco em contêiner.** O arquivo padrão é `database.db` no diretório de trabalho —
um por projeto, como sempre. No Coolify, aponte para o volume persistente daquele
projeto com `ANGATU_DB_PATH=/data/database.db` (ou `-Dangatu.db=...`); sem isso o
banco vive dentro do contêiner e some no deploy seguinte.

> ⚠️ **Mudança de comportamento (só no código, não no banco):** não há mais cache
> total nem *identity map*. Um objeto alterado só é visível para os outros componentes
> depois do `save()`, e `findById` devolve instâncias distintas a cada chamada.
> Consultas frequentes por campo pedem `createIndex(...)`; `findAll`/`findByPredicate`
> percorrem a tabela.

### 📨 E-mail (EmailAPI)

```java
import br.com.angatusistemas.lib.email.EmailAPI;

// Configurar EMAIL_KEY e EMAIL_PASSWORD no .env

// Simples (assíncrono — o assunto ganha um código #XXX anti-spam)
EmailAPI.sendSimple("cliente@empresa.com", "Bem-vindo", "Olá!").thenAccept(ok -> {
    System.out.println(ok ? "Enviado" : "Falhou");
});

// HTML com template
String html = EmailAPI.loadHtmlTemplate("/emails/welcome.html", Map.of("nome", "João"));
EmailAPI.sendHtml("cliente@empresa.com", "Bem-vindo", html).join();

// Com anexos e múltiplos destinatários
EmailAPI.sendWithAttachments(List.of("a@x.com", "b@x.com"), null, null,
        "Relatório", "<b>Segue em anexo</b>", List.of(new File("relatorio.pdf")), true);
```

### 🔔 Web Push (WebPushAPI)

```java
import br.com.angatusistemas.lib.webpush.PushBootstrap;
import br.com.angatusistemas.lib.webpush.WebPushAPI;

// Inicializa: gera e persiste as chaves VAPID automaticamente
PushBootstrap.setup();

// Front-end envia a assinatura (endpoint, p256dh, auth) — serialize e guarde
WebPushAPI.Subscription sub =
    WebPushAPI.createSubscription(endpoint, p256dh, auth);
String json = WebPushAPI.subscriptionToJson(sub); // persistir

// Enviar notificação
WebPushAPI.sendNotification(sub, "Promoção!", "50% off hoje", null);

// Envio com resultado
WebPushAPI.sendNotificationAsync(sub, "Título", "Corpo", null)
    .thenAccept(result -> System.out.println("HTTP " + result.getStatusCode()));
```

### 🤖 Discord (Bot)

```java
import br.com.angatusistemas.lib.discord.Bot;

// Token no .env: DISCORD_BOT_TOKEN
Bot.setup();

Bot.sendMessage("123456789012345678", "Olá mundo!");
Bot.sendMessageWithButton("123456789012345678", "Confirma?", "btn_confirmar", "Sim");

Bot.onButtonClick("btn_confirmar", event ->
    event.reply("Confirmado!").setEphemeral(true).queue());
```

### 🧠 IA (DeepSeek)

```java
import br.com.angatusistemas.lib.ai.DeepSeek;

DeepSeek.initialize(); // chave em DEEPSEEK_API_KEY no .env

String resposta = DeepSeek.ask("Responda em português", "Qual a capital do Brasil?");

DeepSeek.askStream("Seja criativo", "Conte uma história", chunk -> System.out.print(chunk));
```

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

### 🖼️ Imagens e QR Codes (ImageAPI, QRCodeAPI)

```java
import br.com.angatusistemas.lib.images.ImageAPI;
import br.com.angatusistemas.lib.images.QRCodeAPI;

// Thumbnail
ImageAPI.createThumbnail("foto.png", "mini.png", 200, 200);

// QR Code
QRCodeAPI.generateAndSaveQRCode("https://site.com", "qrcode.png", 300, 300);
String texto = QRCodeAPI.readQRCodeFromFile("qrcode.png");
```

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
```

### ⚙️ Tarefas (Task)

```java
import br.com.angatusistemas.lib.task.Task;

Task.runAsync(() -> System.out.println("assíncrono"));
int id = Task.runLater(() -> System.out.println("daqui a 5s"), 5000);
Task.runTimerWithFixedDelay(() -> System.out.println("a cada hora"), 0, 3600_000);

Task.cancelTask(id);
Task.shutdown(); // ao encerrar
```

### 🔗 HTTP Client (Request)

```java
import br.com.angatusistemas.lib.connection.Request;
import br.com.angatusistemas.lib.connection.Response;

Response resp = Request.query("GET", "https://api.exemplo.com/users");
Response resp2 = Request.query("POST", "https://api.exemplo.com/users", "{\"nome\":\"João\"}", "meu-token");

if (resp2.isSuccess()) {
    System.out.println(resp2.getBody());
}
```

---

## 💡 Boas práticas

1. **Adicione apenas as dependências dos módulos usados** — consulte a [tabela de dependências](#-dependências-por-módulo). O sistema de detecção indica exatamente o que falta.
2. **Chame `Saveable.shutdown()` e `Task.shutdown()` ao encerrar a aplicação** para fechar pools e evitar vazamentos.
3. **Chame `BrowserAPI.shutdown()`** ao finalizar uso de scraping/screenshots (encerra os processos headless).
4. **Use `JavalinAPI.configureRateLimit()` antes de `AngatuLib`** para proteger rotas sensíveis (login, APIs).
5. **Crie índices com `Saveable.createIndex(Entidade.class, "campo")`** para toda consulta frequente por campo — sem cache em memória, o índice é o que segura o custo.
6. **Use `Saveable.mutate(...)` para alterar registro disputado** e `Saveable.transaction(...)` quando duas gravações precisam valer juntas.
7. **Declare `JavalinAPI.setTrustedProxyHops(1)` atrás do Coolify** (ou de qualquer proxy reverso) — sem isso o rate limiting enxerga o IP do proxy.
8. **Não guarde segredos no código** — use o arquivo `.env` (`Env.get()`), que também lê as variáveis de ambiente do Coolify.
9. **Use `Password.criptography()`/`checkCriptography()`** para senhas (BCrypt com salt automático).
10. **Configure `-Dangatu.debug=true` apenas em desenvolvimento** — logs de debug são silenciosos por padrão.

---

## 🔧 Solução de problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| `[AngatuLibraries] Dependência ausente: ...` | Módulo usado sem a dependência no classpath | Siga o snippet Maven/Gradle exibido na mensagem |
| `NoClassDefFoundError` citando `io/javalin/...`, `com/google/zxing/...` ou `com/google/gson/TypeAdapter` | Classe com tipos de terceiros na assinatura pública (`JavalinAPI`, `QRCodeAPI`, TypeAdapters) usada sem a dependência | Adicione a dependência correspondente — ela é obrigatória por contrato de API (ver nota acima) |
| `Não é possível criar uma rota antes de inicializar o servidor` | `Route` construída antes de `new AngatuLib(...)` | Construa o `AngatuLib` primeiro; rotas são descobertas automaticamente |
| `Javalin não foi inicializado` / retorno `null` do `JavalinAPI.setup` | Pasta `resources/public/index.html` ausente | Crie `src/main/resources/public/index.html` |
| `Credenciais de e-mail não configuradas` | `.env` sem `EMAIL_KEY`/`EMAIL_PASSWORD` | Configure as variáveis e reinicie |
| `Chaves VAPID não configuradas` | Web Push sem chaves | Chame `PushBootstrap.setup()` (gera e persiste automaticamente) |
| `Playwright` não abre navegador | Browser não instalado | `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"` |
| Consulta lenta no `Saveable` | `findAll`/`findByPredicate` percorrendo a tabela inteira | `Saveable.createIndex(Entidade.class, "campo")` + `findByField`/`query` com `json_extract` |
| Alteração some / valor volta ao anterior | Objeto alterado sem `save()`, ou dois componentes gravando o mesmo registro | Toda alteração exige `save()`; para registro disputado, use `Saveable.mutate(...)` |
| Banco vazio a cada deploy no Coolify | SQLite dentro do contêiner, sem volume | Monte `/data` como *persistent storage* e defina `ANGATU_DB_PATH=/data/database.db` |
| `HTTPS foi solicitado (manageSsl = true) mas os certificados não foram encontrados` | Quarto parâmetro `true` sem `fullchain.pem`/`privkey.pem` | Gere os certificados do domínio ou use o construtor de três parâmetros (HTTPS do Coolify) |
| `Não foi possível registrar a rota` | Registro manual antes do servidor ativo | Registre após o `setup` ou deixe a descoberta automática fazer o trabalho |
| Logs sem cor no terminal | Terminal sem suporte ANSI ou stream redirecionado | Use um terminal compatível (Windows Terminal, VS Code) |

---

## ❓ FAQ

**A biblioteca é pesada?**
Não. O JAR contém apenas o código da própria biblioteca (~185 KB). Dependências de terceiros são declaradas pelo consumidor sob demanda.

**Preciso adicionar todas as dependências de uma vez?**
Não. Adicione apenas as dos módulos que usar. Se algo faltar, a biblioteca exibe a mensagem com o trecho exato de Maven/Gradle.

**Posso usar `Console` antes de inicializar o `AngatuLib`?**
Sim. O `Console` funciona com fallback para `System.out` quando a biblioteca ainda não foi inicializada (correção incluída na versão atual).

**O rate limiting funciona por IP ou por rota?**
Ambos: a chave é `IP|path` quando `perIp = true` (padrão). Bloqueios temporários e permanentes são persistidos e recarregados ao reiniciar.

**O Saveable é seguro para múltiplas threads?**
Sim. Usa HikariCP (pool de 20 conexões), WAL mode, cache `ConcurrentHashMap` e escritas `INSERT OR REPLACE` transacionais.

**Preciso de certificados para rodar localmente?**
Não. O padrão é HTTP na porta informada — o HTTPS só entra quando você pede, passando `manageSsl = true` no quarto parâmetro do construtor. No Coolify, o certificado é da hospedagem.

**Playwright não funciona — o que fazer?**
Instale o browser uma vez: `mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"`.

---

## 🔄 Migração entre versões

### Para a versão atual (Javalin 7.2.2)

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
| **HTTPS deixou de ser automático** | O construtor de três parâmetros agora sobe em **HTTP na porta informada** (antes: porta 80 em localhost, HTTPS se houvesse certificados). Quem quiser o Javalin gerenciando o certificado passa `true` no quarto parâmetro: `new AngatuLib(host, 443, true, true)` |
| **`isLocalhost()` mudou de critério** | Não olha mais a pasta de certificados: declare `ANGATU_ENV=production` (o `Dockerfile` modelo já faz isso) ou use `localhost` como host em desenvolvimento; sem declaração, host real é tratado como produção |
| **`JavalinAPI.setup` com nova assinatura** | `setup(int port, boolean enableRateLimit, boolean manageSsl, File folderCerts)` — a ordem mudou de propósito, para que chamadas antigas quebrem no compilador em vez de inverterem o sentido do parâmetro |
| **`Saveable` sem cache em memória** | Toda leitura vai ao banco e devolve instância nova; alterações só valem após `save()`. Onde havia leitura-alteração-gravação concorrente, use `Saveable.mutate(...)`; consultas frequentes por campo pedem `Saveable.createIndex(...)` |
| **Banco de dados sem nenhuma mudança** | Mesmo arquivo (`database.db` por projeto), mesmas colunas (`id`, `data`) e mesmo `INSERT OR REPLACE` — nada a migrar, e os bancos de sistemas em produção continuam compatíveis com versões anteriores da lib |
| **Todo projeto passa a ter `Dockerfile`** | Copie `templates/Dockerfile` e `templates/.dockerignore`, exponha a porta de `PORT` e monte `/data` no Coolify |
| **Data holders agora `final`** | `Response`, `BlockInfo`, `RateLimitConfig`, `SlidingWindowCounter`, `CachedHtml`, TypeAdapters e opções do `BrowserAPI` não podem mais ser estendidos (nenhum caso de uso legítimo para herança) |

---

## 📝 Changelog resumido

### Versão atual
* 🐳 **Hospedagem no Coolify**: HTTP por padrão na porta informada e HTTPS só quando pedido (`new AngatuLib(host, port, rateLimit, manageSsl)`); modelos de `Dockerfile`/`.dockerignore` em `templates/`; ambiente resolvido por `ANGATU_ENV` em vez da pasta de certificados
* 🗄️ **`Saveable` sem dados em RAM**: cache total e *identity map* removidos — leitura e gravação direto no SQLite, um pool por aplicação (antes um por classe de entidade), transações `IMMEDIATE`, `busy_timeout` e travas por registro; novos `mutate()`, `transaction()`, `computeInTransaction()`, `saveAll()`, `createIndex()`, `findFirstByField()`; caminho do banco configurável por `ANGATU_DB_PATH`. **Formato do banco inalterado** (`id`, `data`, `INSERT OR REPLACE`): nenhuma migração, bancos existentes seguem compatíveis
* 🔒 **Restrições de inicialização**: construtores de `Saveable` e `Route` agora `protected` (uso exclusivo via `extends`, com mensagens claras de uso incorreto); `Route` valida servidor ativo e argumentos no construtor; data holders (`Response`, `BlockInfo`, `RateLimitConfig`, `SlidingWindowCounter`, `CachedHtml`, TypeAdapters, opções do `BrowserAPI`) e `Core` agora `final`
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

Biblioteca voltada para uso interno da **Angatu Sistemas**. Sugestões e melhorias podem ser propostas conforme necessidade dos projetos.

## 📄 Licença

Uso restrito à **Angatu Sistemas**. A utilização externa deve ser previamente autorizada.

---

## 🏢 Organização

Desenvolvido por **Angatu Sistemas**
