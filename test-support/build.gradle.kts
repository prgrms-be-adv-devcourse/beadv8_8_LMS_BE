// 모든 모듈이 testImplementation으로 공유하는 테스트 인프라.
dependencies {
    api("org.springframework.boot:spring-boot-starter-test")
    api("org.springframework.modulith:spring-modulith-starter-test")
    api("org.testcontainers:testcontainers-postgresql")

    runtimeOnly("org.postgresql:postgresql")
}
