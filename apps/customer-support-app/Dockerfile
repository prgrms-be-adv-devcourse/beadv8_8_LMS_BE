# Build: ./gradlew :customer-support-app:bootJar
# Image: docker build -f apps/customer-support-app/Dockerfile apps/customer-support-app/build/libs
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
