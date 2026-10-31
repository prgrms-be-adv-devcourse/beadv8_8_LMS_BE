# Build: ./gradlew :chat-app:bootJar
# Image: docker build -f apps/chat-app/Dockerfile apps/chat-app/build/libs
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
