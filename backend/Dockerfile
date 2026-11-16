FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /app && chown 10001:10001 /app
COPY --from=build /src/build/libs/*.jar /app/pvz.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/pvz.jar"]
