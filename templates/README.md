---
tags:
  - deploy
  - coolify
  - docker
---

# Modelos de deploy — Coolify

Arquivos prontos para copiar na raiz de qualquer projeto que usa a AngatuLibraries:

| Arquivo | Destino | Papel |
|---|---|---|
| `Dockerfile` | raiz do projeto | build Maven com cache compartilhado + imagem de execução com JRE 25 |
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

## 2. `pom.xml`: JAR fino, com as dependências ao lado

O `Dockerfile` aceita os dois formatos — o JAR único do `maven-shade-plugin` e o JAR fino —,
mas o fino deixa cada deploy mais curto: não há o passo do shade, que desmonta e remonta todas
as dependências a cada build, e as dependências ficam numa camada própria da imagem, que se
repete de um deploy para o outro enquanto o `pom.xml` não muda. No lugar do bloco do
`maven-shade-plugin`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-jar-plugin</artifactId>
  <version>3.4.2</version>
  <configuration>
    <archive>
      <manifest>
        <mainClass>com.empresa.loja.Main</mainClass>
        <addClasspath>true</addClasspath>
        <classpathPrefix>lib/</classpathPrefix>
        <useUniqueVersions>false</useUniqueVersions>
      </manifest>
      <manifestEntries>
        <Enable-Native-Access>ALL-UNNAMED</Enable-Native-Access>
      </manifestEntries>
    </archive>
  </configuration>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <version>3.8.1</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>copy-dependencies</goal></goals>
      <configuration>
        <outputDirectory>${project.build.directory}/lib</outputDirectory>
        <includeScope>runtime</includeScope>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`mvn package` passa a gerar `target/<app>.jar` e `target/lib/`; `java -jar target/<app>.jar`
continua funcionando, porque o manifesto aponta para `lib/` ao lado do JAR. Sem o shade, também
não é preciso tirar as assinaturas do BouncyCastle nem juntar os arquivos de `META-INF/services`.

## 3. Configuração no Coolify

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
6. **Environment Variables**: as chaves do `.env` do projeto (ex.: `EMAIL_KEY`), cada uma
   com **Build time → Not available during build**. A biblioteca lê as variáveis na
   execução, pelo mesmo `Env.get()`; no build elas só viravam `ARG`, invalidavam o cache a
   cada mudança de valor e deixavam o segredo gravado nos metadados da imagem.
7. **Health Check**: deixe o do painel **desligado**. O Coolify usa então o `HEALTHCHECK` do
   Dockerfile e lê dele quanto esperar: dorme o `--start-period` e consulta o Docker a cada
   `--interval`. Ligado no painel, ele troca o do Dockerfile pelo dele, com os tempos do
   painel — se ligar, use **Path** `/health`, **Interval** 5 e **Start period** 5.
8. **Advanced**: **Build cache → Use Docker build cache**; **Build arguments → Managed
   manually in Dockerfile** (o Dockerfile não usa nenhum); **Source commit availability →
   Runtime only**. Com cache desligado, ou com o hash do commit no build, todo deploy
   recompila do zero.
9. **Servidor → Docker Cleanup → Cleanup trigger: Only above disk threshold.** A outra opção,
   *Run on every schedule*, roda `docker builder prune -af` toda madrugada e apaga o cache do
   Maven de todos os projetos: o primeiro deploy de cada dia baixa tudo de novo.
10. **Logs**: cada requisição aparece numa linha — horário, método, caminho, IP, status e
    tempo. A sonda do `HEALTHCHECK` que deu certo fica de fora (ela se repete a cada 5 s); a
    que falha aparece. Para ver só as falhas, `ANGATU_REQUEST_LOG=errors` como variável do
    serviço; `off` desliga.

> Prefira **volume nomeado** a caminho do host: o volume herda o dono de `/data`
> na imagem (uid 10001). Se usar caminho do host, rode `chown -R 10001:10001` nele.

> Os nomes em negrito são os do painel do Coolify 4.5. Em versões mais antigas, o mesmo
> ajuste pode ter outro rótulo.

## 4. Quanto cada deploy espera

Medido com um projeto de exemplo (Javalin, SQLite, e-mail), Docker 29, numa máquina de 6
núcleos — no servidor os números absolutos são maiores, mas a proporção é a mesma:

| Situação | Modelo anterior | Este modelo |
|---|---|---|
| Build com só o código mudado | 13 s | 6 s com JAR fino (9 s com shade) |
| Build com o `pom.xml` mudado — biblioteca atualizada | 105 s | 8 s |
| Build depois de o Coolify mudar um `ARG` injetado | 133 s | 7 s |
| Build com o cache vazio (primeiro deploy, limpeza do servidor) | 128 s | 24 s |
| Espera do Coolify pelo contêiner saudável | 45 s | 5 s |
| Parada do contêiner antigo | menos de 1 s | menos de 1 s |

De onde veio cada ganho:

- **Cache do Maven fora das camadas.** O `pom.xml` numa camada própria com
  `dependency:go-offline` só se reaproveitava enquanto o `pom.xml` não mudasse — e ele muda a
  cada atualização da biblioteca. Agora o repositório Maven vive num cache do BuildKit
  (`--mount=type=cache,id=angatu-maven`), único no servidor, e só o que é novo é baixado.
- **Nenhum `apt-get`.** O `curl` vinha de um `apt-get update` na imagem de execução: dezenas
  de segundos sempre que a camada era refeita. O `tzdata` já vem na imagem, e o `wget` da sonda
  é o binário estático do busybox, copiado da imagem dele.
- **`HEALTHCHECK` com os tempos do Coolify.** Com `--start-period=45s`, o Coolify dormia 45 s
  antes de olhar o contêiner, mesmo com a aplicação de pé em 3 s.
- **JAR fino** (seção 2): sem o passo do shade e com as dependências numa camada que se repete.
- **Coletor paralelo do Maven** (`-Daether.dependencyCollector.impl=bf`): com o cache vazio, os
  POMs são baixados ao mesmo tempo, e não um por um — o build frio caiu de 38 s para 24 s.
- **Subida mais curta.** A busca de rotas pula as classes das bibliotecas de terceiros: o
  scan de 5 mil classes caiu de ~550 ms para ~80 ms.

### 4.1 Biblioteca atualizada: o JitPack compila no push

O JitPack só compila um commit quando alguém pede por ele, e o build da biblioteca leva uns 4
minutos. Antes, quem pedia era o primeiro deploy de um projeto que subiu o hash — e o deploy
ficava parado no Maven esperando. Agora o workflow `JitPack` do repositório da biblioteca pede o
build assim que o commit chega na `main`. Ao subir o hash num projeto, confira antes que o check
**JitPack** do commit está verde no GitHub: verde, a versão já está publicada e o deploy não
espera por ela.

## 5. Antes do primeiro deploy

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

## 6. Projetos que usam o BrowserAPI (Playwright)

A imagem `eclipse-temurin:25-jre` não traz as dependências do Chromium. Nesses projetos, troque
o `FROM` da etapa de execução pela imagem do Playwright com a mesma versão da dependência
`com.microsoft.playwright:playwright` do `pom.xml`, e copie o Java 25 por cima dela — a imagem
traz o JDK 21, e o JAR compilado para o 25 não sobe nele (`UnsupportedClassVersionError`):

```dockerfile
FROM mcr.microsoft.com/playwright/java:v1.58.0-jammy
COPY --from=eclipse-temurin:25-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH
# ... o resto da etapa de execução, igual ao Dockerfile
```
