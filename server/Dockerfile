FROM eclipse-temurin:17-jre-jammy

ARG APP_JAR=target/ai-friend-server-0.0.1-SNAPSHOT.jar

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 aifriend \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin aifriend \
    && mkdir --parents /app \
    && chown 10001:10001 /app

WORKDIR /app

COPY --chown=10001:10001 ${APP_JAR} /app/ai-friend-server.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/ai-friend-server.jar"]
