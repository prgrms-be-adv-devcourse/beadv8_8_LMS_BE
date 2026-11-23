# Build: ./gradlew :member-app:bootJar
# Image: docker build -f apps/member-app/Dockerfile apps/member-app/build/libs
# 태그를 정확한 버전으로 고정한다. JDK 보안 업데이트는 이 태그를 바꿔야 운영에 들어가며, Dependabot이 갱신 PR을 올린다(#1004).
FROM eclipse-temurin:25.0.5.1_2-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
