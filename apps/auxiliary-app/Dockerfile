# Build: ./gradlew :auxiliary-app:bootJar
# Image: docker build -f apps/auxiliary-app/Dockerfile apps/auxiliary-app/build/libs
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
