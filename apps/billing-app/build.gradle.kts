import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 충전 요청 값 검증 (@Valid, #224)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // TossPayments 결제 승인·조회 호출 (RestClient.Builder, #224)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 앱 안의 global.config.SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // 보안 체인(SecurityFilterChain): 사용자 API(SecurityConfig), 내부 API(InternalApiSecurityConfig), 가상 송금 Mock 은행 API(#288)
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // 지갑 중복 생성 판별에 PSQLException의 제약 이름을 쓴다. 드라이버는 실행 시 이 앱(runtimeOnly)가 넣어 준다
    compileOnly("org.postgresql:postgresql")
    // 지갑 이벤트를 Modulith 발행 기록(event_publication)을 거쳐 Kafka로 내보낸다(이벤트 외부화, #497). 버전은 Modulith BOM을 따른다
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    // 다른 앱 내부 API·TossPayments 호출의 circuit breaker·재시도와 그 지표 (#562), 사용자 API의 회원별 rate limit (#627)
    // (루트 AGENTS.md 2장). 버전은 레포 카탈로그를 따른다
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.resilience4j.micrometer)
    // 테스트 보안 지원(@WithMockUser). API 로그인은 Gateway 사용자 헤더(fixture.GatewayLogin)로 만든다 (#537)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

springBoot {
    mainClass = "com.hapbang.billing.BillingApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}

// 별도 JVM에서 실제 BillingApplication을 실행하는 HTTP E2E 전용 서버.
// 일반 test 클래스/리소스는 넣지 않으며 production app.jar에도 fixture를 넣지 않는다.
val e2eServer by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<BootJar>("e2eServerJar") {
    targetJavaVersion.set(JavaVersion.VERSION_25)
    group = "verification"
    description = "실제 billing 엔진과 격리 DB fixture API만 담은 E2E 서버를 만든다."
    archiveFileName.set("billing-e2e-server.jar")
    mainClass.set("com.hapbang.billing.e2e.BillingE2eServer")
    classpath(e2eServer.runtimeClasspath)
    dependsOn(e2eServer.classesTaskName)
}
