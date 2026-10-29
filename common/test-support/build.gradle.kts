// 모든 모듈이 testImplementation으로 공유하는 테스트 인프라.
dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // 컨트롤러 API 테스트(@AutoConfigureMockMvc, MockMvcTester)
    api("org.springframework.boot:spring-boot-starter-webmvc-test")
    api("org.springframework.modulith:spring-modulith-starter-test")
    api("org.testcontainers:testcontainers-postgresql")
    api("org.testcontainers:testcontainers-kafka")

    // 공통 컨테이너 설정의 실제 통신 검증에만 클라이언트를 사용한다.
    testImplementation(project(":kafka"))
    testImplementation(project(":redis"))

    // 테스트 로그인 헬퍼(TestLogin)
    implementation(project(":shared"))
    // 보안을 안 쓰는 모듈 테스트에 Spring Security가 끼어들지 않도록 compileOnly
    // (TestLogin을 쓰는 모듈은 security-test를 직접 추가한다)
    compileOnly("org.springframework.boot:spring-boot-starter-security-test")
    compileOnly("org.springframework.boot:spring-boot-starter-security-oauth2-client")

    runtimeOnly("org.postgresql:postgresql")
    // 모듈 테스트도 운영과 같이 Flyway 마이그레이션으로 스키마를 만든다(클래스패스에 있는 그 모듈의 SQL만 실행된다).
    api("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // 모듈 테스트에서도 운영과 같은 이벤트 발행 기록(JDBC)을 사용한다.
    runtimeOnly("org.springframework.modulith:spring-modulith-starter-jdbc")
}
