# Spring 앱 이미지. 레포 루트를 빌드 컨텍스트로 쓴다.
# 빌드: docker build -f docker/Dockerfile .
# 테스트는 PR CI에서 돌리므로 이미지 빌드에서는 생략한다.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:bootJar -x test --no-daemon \
    && find apps/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
