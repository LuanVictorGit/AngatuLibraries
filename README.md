<p align="center">
  <img src="https://angatusistemas.com.br/favicon.ico" alt="Angatu Sistemas" width="120"/>
</p>

<h1 align="center">AngatuLibraries</h1>

<p align="center">
  <strong>Framework de utilidades para projetos Java</strong><br/>
  Web · Persistência · E-mail · Web Push · Discord · IA · Imagens · QR Code · Pagamentos
</p>

---

## 📌 Introdução

O **AngatuLibraries** é uma biblioteca utilitária desenvolvida pela **Angatu Sistemas** que centraliza soluções comuns de backend Java em uma única dependência: servidor web com segurança integrada (Javalin), persistência automática em SQLite, envio de e-mails, notificações Web Push, bot de Discord, cliente de IA (DeepSeek), captura de tela e scraping (Playwright), geração/leitura de QR Codes, manipulação de imagens e integração com Mercado Pago.

A biblioteca foi projetada para ser **leve, modular e segura**:

* 🪶 **Leve** — o JAR contém apenas o código da biblioteca (~185 KB). As dependências de terceiros **não são empacotadas**; você adiciona somente as dos módulos que usa.
* 🛡️ **Segura** — rate limiting com janela deslizante, detecção de SQL Injection/XSS, bloqueios permanentes persistidos e headers de segurança automáticos.
* 🧩 **Modular** — cada funcionalidade é opcional e detecta dependências ausentes com instruções claras de instalação.
* 📖 **Documentada** — todas as APIs públicas possuem JavaDocs completos (visíveis nas IDEs).

---

## ✨ Recursos disponíveis

| Módulo | Classe principal | Descrição |
|---|---|---|
| 🌐 **Web Server** | `JavalinAPI`, `HtmlRouteAPI`, `Route` | Servidor HTTP/HTTPS (Javalin 7.2.2) com rate limiting, proteção contra SQLi/XSS, SSL automático e rotas por convenção |
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
| Web Server, HTML, Assets, Rotas | `io.javalin:javalin:7.2.2`, `io.javalin.community.ssl:javalin-ssl:7.2.2` (HTTPS), `org.reflections:reflections:0.10.2` (rotas automáticas), + um binding SLF4J (ex: `org.slf4j:slf4j-simple:2.0.17`) |
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

O ponto de entrada é a classe `AngatuLib`. Em modo local (sem certificados), o servidor sobe em HTTP na porta **80**; com certificados Let's Encrypt em `/etc/letsencrypt/live/<dominio>`, sobe em **HTTPS** na porta informada (HTTP na porta + 1, com redirecionamento).

```java
import br.com.angatusistemas.lib.AngatuLib;

public class Main {
    public static void main(String[] args) {
        // Local: http://localhost:80
        new AngatuLib("localhost", 80, true);

        // Produção: https://meusite.com.br:443 (HTTP redireciona para HTTPS)
        // new AngatuLib("meusite.com.br", 443, false);
    }
}
```

Ao iniciar, a biblioteca:
1. Verifica as dependências dos módulos usados (mensagens claras se faltarem);
2. Redireciona `System.out` para o log colorido do `Console`;
3. Configura o Javalin com headers de segurança, rate limiting e SSL (se houver certificados);
4. Descobre e registra automaticamente todas as rotas (`Route`) e páginas HTML em `/public`;
5. Agenda a limpeza diária de bloqueios expirados.

---

## 📖 Guias por funcionalidade

### 🌐 Servidor web com segurança (JavalinAPI)

```java
import br.com.angatusistemas.lib.javalin.JavalinAPI;
import br.com.angatusistemas.lib.javalin.classes.RateLimitConfig;

public class Config {
    public static void main(String[] args) {
        new AngatuLib("localhost", 80, true);

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

// Buscar (mesma instância sempre — cache em memória)
Usuario joao = Saveable.findById(Usuario.class, u.getId());

// Consultas customizadas com json_extract (crie índices para performance)
Saveable.query(Usuario.class,
    "CREATE INDEX IF NOT EXISTS idx_nome ON usuarios(json_extract(data, '$.nome'))");
List<Usuario> joes = Saveable.query(Usuario.class,
    "SELECT data FROM usuarios WHERE json_extract(data, '$.nome') = ?", "João");

// Encerrar a aplicação
Saveable.shutdown();
```

> ⚠️ O cache total carrega a tabela inteira em memória no primeiro acesso — ideal para até centenas de milhares de registros. Para volumes maiores, consulte o JavaDoc de `Saveable`.

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
5. **Crie índices `json_extract`** nas tabelas do Saveable para consultas frequentes.
6. **Não guarde segredos no código** — use o arquivo `.env` (`Env.get()`).
7. **Use `Password.criptography()`/`checkCriptography()`** para senhas (BCrypt com salt automático).
8. **Configure `-Dangatu.debug=true` apenas em desenvolvimento** — logs de debug são silenciosos por padrão.

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
Não. Sem a pasta de certificados, o `AngatuLib` ativa automaticamente o modo localhost (HTTP na porta 80).

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

---

## 📝 Changelog resumido

### Versão atual
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
