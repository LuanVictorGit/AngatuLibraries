# Runtime stage of templates/Dockerfile, with the E2E app instead of a project JAR: same base image,
# same JAVA_OPTS, same /data volume and the HEALTHCHECK timing Coolify waits on. The build context is
# prepared by container.sh: classes/ (E2E app), deps/ (runtime libraries) and angatu.jar (library).
FROM eclipse-temurin:25-jre

ENV TZ=America/Sao_Paulo \
    LANG=C.UTF-8 \
    ANGATU_ENV=production \
    ANGATU_DB_PATH=/data/database.db \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseCompactObjectHeaders -Djava.awt.headless=true"

RUN useradd --system --uid 10001 --create-home app \
 && mkdir -p /data \
 && chown app:app /data

COPY --from=busybox:1.37-musl /bin/busybox /usr/local/bin/wget

COPY --chown=app:app deps/ /opt/app/lib/
COPY --chown=app:app angatu.jar /opt/app/lib/angatu.jar
COPY --chown=app:app classes/ /opt/app/classes/

USER app
WORKDIR /data
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --start-interval=1s --retries=24 \
  CMD wget -q -T 2 -O /dev/null "http://127.0.0.1:${PORT}/e2e/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp '/opt/app/classes:/opt/app/lib/*' br.com.angatusistemas.lib.e2e.E2EApp"]
