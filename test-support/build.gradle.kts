// 모든 모듈이 testImplementation으로 공유하는 테스트 인프라.
dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.boot:spring-boot-starter-data-jpa-test")
    // 컨트롤러 API 테스트(@AutoConfigureMockMvc, MockMvcTester)
    api("org.springframework.boot:spring-boot-starter-webmvc-test")
    api("org.springframework.modulith:spring-modulith-starter-test")
    api("org.testcontainers:testcontainers-postgresql")

    // 테스트 로그인 헬퍼(TestLogin)
    implementation(project(":shared"))
    // 보안을 안 쓰는 모듈 테스트에 Spring Security가 끼어들지 않도록 compileOnly
    // (TestLogin을 쓰는 모듈은 security-test를 직접 추가한다)
    compileOnly("org.springframework.boot:spring-boot-starter-security-test")
    compileOnly("org.springframework.boot:spring-boot-starter-security-oauth2-client")

    runtimeOnly("org.postgresql:postgresql")
    // 모듈 테스트에서도 운영과 같은 이벤트 발행 기록(JDBC)을 사용한다.
    runtimeOnly("org.springframework.modulith:spring-modulith-starter-jdbc")
}
