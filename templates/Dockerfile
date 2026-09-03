# syntax=docker/dockerfile:1
#
# Dockerfile padrão dos projetos Angatu Sistemas hospedados no Coolify.
# Copie para a raiz do projeto (junto do pom.xml) e ajuste apenas o EXPOSE/PORT
# se a aplicação usar outra porta.
#
# O que este arquivo assume:
#   - build Maven com plugin shade/assembly gerando um JAR executável em target/;
#   - AngatuLib inicializado em HTTP (o TLS é do Coolify) lendo a porta de PORT;
#   - banco SQLite do Saveable em /data (volume persistente do Coolify).

# ---------------------------------------------------------------- build ------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependências primeiro: enquanto o pom.xml não mudar, esta camada é reaproveitada
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests \
 && mkdir -p /out \
 && cp "$(ls -S target/*.jar | head -n1)" /out/app.jar

# -------------------------------------------------------------- runtime ------
FROM eclipse-temurin:21-jre

# ANGATU_ENV=production faz a lib tratar a execução como produção (cookie Secure,
# nada de atalho de desenvolvimento). ANGATU_DB_PATH aponta o SQLite para o
# volume — sem isso o banco vive dentro do contêiner e some no próximo deploy.
ENV TZ=America/Sao_Paulo \
    ANGATU_ENV=production \
    ANGATU_DB_PATH=/data/database.db \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.awt.headless=true"

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --create-home app \
 && mkdir -p /data \
 && chown -R app:app /data

COPY --from=build --chown=app:app /out/app.jar /opt/app/app.jar

USER app
# O diretório de trabalho é o volume: arquivos relativos (.env, uploads, banco)
# nascem em disco persistente, e não dentro da camada descartável do contêiner
WORKDIR /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/app/app.jar"]
