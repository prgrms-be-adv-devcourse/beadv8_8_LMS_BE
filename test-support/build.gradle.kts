// 모든 모듈이 testImplementation으로 공유하는 테스트 인프라.
dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // 컨트롤러 API 테스트(@AutoConfigureMockMvc, MockMvcTester)
    api("org.springframework.boot:spring-boot-starter-webmvc-test")
    api("org.springframework.modulith:spring-modulith-starter-test")
    api("org.testcontainers:testcontainers-postgresql")

    runtimeOnly("org.postgresql:postgresql")
    // 모듈 테스트도 운영과 같이 Flyway 마이그레이션으로 스키마를 만든다(클래스패스에 있는 그 모듈의 SQL만 실행된다).
    api("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // 모듈 테스트에서도 운영과 같은 이벤트 발행 기록(JDBC)을 사용한다.
    runtimeOnly("org.springframework.modulith:spring-modulith-starter-jdbc")
}
