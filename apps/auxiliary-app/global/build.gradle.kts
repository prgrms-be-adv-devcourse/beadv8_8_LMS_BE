// auxiliary-app의 공통 코드(:auxiliary-global, 패키지 com.hapbang.auxiliary.global).
// 업무 모듈이 쓰는 웹·보안·JPA·Swagger 의존을 api로 넘겨 모듈마다 다시 적지 않게 한다.
// 다른 앱의 global을 의존하지 않는다(앱끼리 의존 금지). chat-app global을 복사해 시작했다.
dependencies {
    api(project(":shared"))
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-security")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    // Swagger 명세용 어노테이션(@Tag, @Operation)과 SwaggerConfig의 OpenAPI 모델
    api(libs.swagger.annotations)
    implementation(rootProject.libs.springdoc.openapi.webmvc.ui)
    // 회원별 요청 한도(ratelimit/KeyedRateLimiter, #715): 키별 limiter와 그 캐시
    implementation(libs.resilience4j.ratelimiter)
    implementation("com.github.ben-manes.caffeine:caffeine")
    // ObservationConfig(trace 제외 규칙, 비동기 trace 전파)
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    testImplementation(project(":test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // test-support TestLogin이 쓴다. 실행 앱(-app)은 루트 build.gradle.kts가 넣어 주지만 하위 프로젝트는 직접 적는다
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
}
