# syntax=docker/dockerfile:1
#
# Dockerfile padrão dos projetos Angatu Sistemas hospedados no Coolify.
# Copie para a raiz do projeto (junto do pom.xml) e ajuste apenas o EXPOSE/PORT
# se a aplicação usar outra porta.
#
# O que este arquivo assume:
#   - build Maven gerando em target/ o JAR da aplicação: o fino, com as
#     dependências em target/lib (o pom padrão), ou o JAR único do shade;
#   - Java 25 (projeto ainda no 21: troque 25 por 21 nos dois FROM e tire
#     -XX:+UseCompactObjectHeaders do JAVA_OPTS, que o 21 não reconhece);
#   - AngatuLib inicializado em HTTP (o TLS é do Coolify) lendo a porta de PORT;
#   - rota GET /health, fora da verificação de segurança;
#   - banco SQLite do Saveable em /data (volume persistente do Coolify).
#
# Onde o deploy ganha tempo (detalhes no README desta pasta):
#   - o repositório Maven fica num cache do BuildKit, e não numa camada: pom.xml
#     novo, commit novo ou ARG que o Coolify injeta não baixam de novo o que já
#     foi baixado;
#   - a etapa de execução não roda apt-get;
#   - o HEALTHCHECK tem os tempos que o Coolify usa para esperar o contêiner.

# ---------------------------------------------------------------- build ------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Maven de vida curta compila mais rápido só com o C1 e o coletor serial
ENV MAVEN_OPTS="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"

# Só pom.xml e src/ entram no build. Se o projeto tiver outro arquivo de build na
# raiz (lombok.config, .mvn/), acrescente um COPY para ele aqui.
COPY pom.xml .
COPY src ./src

# O cache "angatu-maven" é um só para todos os projetos do servidor: o Javalin, o
# SQLite e a própria biblioteca são baixados uma vez e servem a todos. Antes, o
# pom.xml copiado numa camada própria e o dependency:go-offline faziam qualquer
# mudança no pom — a cada atualização da biblioteca — baixar tudo de novo, e o
# ARG que o Coolify injeta depois de cada FROM tinha o mesmo efeito. A trava por
# arquivo deixa dois deploys simultâneos usarem o mesmo repositório sem corromper;
# o coletor "bf" baixa os POMs em paralelo quando o cache está vazio.
# Os testes rodam antes do push (mvn test), não no deploy: -Dmaven.test.skip.
RUN --mount=type=cache,id=angatu-maven,target=/root/.m2 \
    mvn -B -q -Dmaven.test.skip=true \
        -Daether.dependencyCollector.impl=bf \
        -Daether.syncContext.named.factory=file-lock \
        -Daether.syncContext.named.nameMapper=file-hgav \
        package \
 && mkdir -p /out/lib \
 && cp "$(ls -S target/*.jar | head -n1)" /out/app.jar \
 && if [ -d target/lib ]; then cp target/lib/*.jar /out/lib/; fi

# -------------------------------------------------------------- runtime ------
FROM eclipse-temurin:25-jre

# ANGATU_ENV=production faz a lib tratar a execução como produção (cookie Secure,
# nada de atalho de desenvolvimento). ANGATU_DB_PATH aponta o SQLite para o
# volume — sem isso o banco vive dentro do contêiner e some no próximo deploy.
#
# Memória: MaxRAMPercentage é relativo ao limite de memória do contêiner, que se
# define no Coolify (Resource Limits → Memory) — sem limite, a conta é sobre a RAM
# inteira do servidor. Nunca -Xmx. ExitOnOutOfMemoryError derruba o processo no
# primeiro OutOfMemoryError: sem ele, a JVM seguia viva com threads mortas, o
# /health continuava respondendo e o contêiner quebrado nunca era reiniciado.
# LANG=C.UTF-8: sem ele a saída padrão da JVM é ASCII e todo acento do log vira "?".
ENV TZ=America/Sao_Paulo \
    LANG=C.UTF-8 \
    ANGATU_ENV=production \
    ANGATU_DB_PATH=/data/database.db \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseCompactObjectHeaders -Djava.awt.headless=true"

# Sem apt-get: o tzdata já vem na imagem. Este RUN não usa rede, e o Coolify
# pode refazê-lo a cada deploy sem custo.
RUN useradd --system --uid 10001 --create-home app \
 && mkdir -p /data \
 && chown app:app /data

# O wget do HEALTHCHECK — e do healthcheck do próprio Coolify, se ligado no
# painel, que tenta curl e depois wget. É o binário estático do busybox: a imagem
# do Java 25 não traz curl nem wget, e instalá-los custava um apt-get update.
COPY --from=busybox:1.37-musl /bin/busybox /usr/local/bin/wget

# Dependências e aplicação em camadas separadas: um lib/ que não mudou é
# reaproveitado como está, e cada deploy grava só o JAR da aplicação.
COPY --from=build --chown=app:app /out/lib/ /opt/app/lib/
COPY --from=build --chown=app:app /out/app.jar /opt/app/app.jar

USER app
# O diretório de trabalho é o volume: arquivos relativos (.env, uploads, banco)
# nascem em disco persistente, e não dentro da camada descartável do contêiner
WORKDIR /data
EXPOSE 8080

# O Coolify lê estes tempos daqui e espera por eles: dorme o --start-period e
# depois pergunta ao Docker, a cada --interval, se o contêiner ficou saudável.
# Com 45 s e 30 s, todo deploy esperava ao menos 45 s, com a aplicação de pé em
# 3 s. Durante o --start-period o Docker sonda a cada --start-interval; as 24
# tentativas dão 2 minutos a uma subida lenta. A sonda que deu certo não aparece
# no log de requisições; a que falha, sim.
HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --start-interval=1s --retries=24 \
  CMD wget -q -T 2 -O /dev/null "http://127.0.0.1:${PORT}/health" || exit 1

# O "exec" deixa o Java como processo principal do contêiner: o SIGTERM de um deploy
# chega a ele, e os ganchos de desligamento da biblioteca rodam — param o servidor,
# esperam as tarefas, os e-mails e as notificações da fila, e fecham o banco. Sem o
# "exec", o sinal fica no shell e o Docker mata o Java só no fim do prazo de parada
# (30 s no Coolify), sem nada disso — e o deploy espera junto.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/app/app.jar"]
