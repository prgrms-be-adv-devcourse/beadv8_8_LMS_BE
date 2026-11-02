# Build: ./gradlew :member-app:bootJar
# Image: docker build -f apps/member-app/Dockerfile apps/member-app/build/libs
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
