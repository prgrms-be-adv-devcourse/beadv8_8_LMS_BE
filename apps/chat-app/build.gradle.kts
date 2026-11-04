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
    exclude("**/performance/**")
    // 클래스는 두 JVM으로 나눈다. Testcontainers는 JVM마다 DB·Redis·Kafka를 따로 만들므로
    // DB 전체 정리와 실행 중인 파이프라인이 다른 worker의 테스트에 섞이지 않는다.
    // 같은 JVM 안의 테스트 메서드는 순차 실행해 기존 컨텍스트·파이프라인 격리를 유지한다.
    maxParallelForks = minOf(2, Runtime.getRuntime().availableProcessors())
}

// 로컬 탐색 실험 전용. 일반 check/CI에서는 실행하지 않는다.
tasks.register<Test>("chatPerformance") {
    group = "verification"
    description = "격리된 DB·Redis로 단체방 WebSocket 전달 성능을 탐색한다."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/performance/ChatGroupPerformanceExperiment.class")
    maxParallelForks = 1
    // 서버와 부하 클라이언트가 같은 JVM이므로 운영 512MiB 한도 시험으로 해석하지 않는다.
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
}

tasks.register<Test>("chatK6Performance") {
    group = "verification"
    description = "호스트 포함 최대 200명 방에 별도 k6 프로세스로 부하를 보낸다."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/performance/ChatK6PerformanceExperiment.class")
    maxParallelForks = 1
    maxHeapSize = "512m"
    outputs.upToDateWhen { false }
}
