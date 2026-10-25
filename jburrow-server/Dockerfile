# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY jburrow-protocol/pom.xml jburrow-protocol/
COPY jburrow-server-core/pom.xml jburrow-server-core/
COPY jburrow-server-api/pom.xml jburrow-server-api/
COPY jburrow-server/pom.xml jburrow-server/
COPY jburrow-client-core/pom.xml jburrow-client-core/
COPY jburrow-cli/pom.xml jburrow-cli/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl jburrow-server -am dependency:go-offline
COPY . .
ARG GIT_COMMIT=unknown
# The build context has no .git, so the commit arrives as a build argument and lands in build-info.properties.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl jburrow-server -am package \
      -DskipTests -Dspotless.check.skip=true -Dmaven.gitcommitid.skip=true \
      -Djburrow.git.commit=${GIT_COMMIT}
RUN java -Djarmode=tools -jar jburrow-server/target/jburrow-server-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21-jre
ARG GIT_COMMIT=unknown
ARG VERSION=unknown
LABEL org.opencontainers.image.title="jburrow-server" \
      org.opencontainers.image.source="https://github.com/Wamitinewton/jburrow" \
      org.opencontainers.image.description="JBurrow tunneling server" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.version="${VERSION}"
ENV TZ=UTC
RUN groupadd --system --gid 10001 jburrow && useradd --system --uid 10001 --gid jburrow --no-create-home jburrow
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
EXPOSE 7080 7090 7091
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
