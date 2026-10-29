plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 요청 값 검증 (@Valid, @NotBlank 등)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다 (채팅 모듈과 동일)
    implementation(libs.swagger.annotations)
    // 역할 검사(@PreAuthorize)와 거절 예외(AccessDeniedException). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // HP 작업 처리 지표(Micrometer). 앱에서는 Actuator의 MeterRegistry에 등록된다
    implementation("io.micrometer:micrometer-core")
    // 테스트에서 로그인 상태 만들기 (TestLogin)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // E2E 테스트에서 실제 포트로 HTTP 요청 보내기. 버전은 gradle/libs.versions.toml에서 관리한다
    testImplementation(libs.rest.assured)
    testImplementation(libs.wiremock.standalone)
}

springBoot {
    mainClass = "com.hapbang.appearance.AppearanceApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}
