# Spring 앱 이미지. CI가 테스트한 jar(apps/build/libs/app.jar)만 담고, 소스는 빌드하지 않는다.
# 빌드 컨텍스트는 apps/build/libs다(레포 루트가 아니므로 소스·.env가 이미지에 들어갈 수 없다).
# 로컬 빌드: ./gradlew :apps:bootJar && docker build -f docker/Dockerfile apps/build/libs

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
