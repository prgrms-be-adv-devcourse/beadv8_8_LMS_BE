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
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // 가상 송금 Mock 은행 API 경로만의 보안 체인(SecurityFilterChain, #288). 실행 시 클래스는 global이 이미 넣는다
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // 지갑 중복 생성 판별에 PSQLException의 제약 이름을 쓴다. 드라이버는 실행 시 이 앱(runtimeOnly)가 넣어 준다
    compileOnly("org.postgresql:postgresql")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
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
