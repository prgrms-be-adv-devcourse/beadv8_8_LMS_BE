plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // 기존 회원 백필의 회원 내부 API 호출(shared MemberApiClient가 RestClient.Builder를 쓴다, 설계 문서 5.4)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
}

springBoot {
    mainClass = "com.hapbang.chat.ChatApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}

tasks.test {
    // 클래스는 두 JVM으로 나눈다. Testcontainers는 JVM마다 DB·Redis·Kafka를 따로 만들므로
    // DB 전체 정리와 실행 중인 파이프라인이 다른 worker의 테스트에 섞이지 않는다.
    // 같은 JVM 안의 테스트 메서드는 순차 실행해 기존 컨텍스트·파이프라인 격리를 유지한다.
    maxParallelForks = minOf(2, Runtime.getRuntime().availableProcessors())
}
