dependencies {
    // BaseEntity(JPA Auditing)·회원 복제 클래스(@MappedSuperclass)용
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 제공 모듈의 내부 HTTP API를 부르는 공통 Client(shared.<제공 모듈>.out, D62). RestClient.Builder·Jackson 자동 설정
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // 지갑 내부 API Client의 Circuit Breaker·Retry·TimeLimiter와 지표(#411). starter 없이 코드로 조합한다
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.retry)
    implementation(libs.resilience4j.timelimiter)
    implementation(libs.resilience4j.micrometer)
    // 지갑 Client health. 앱에는 actuator가 있어 컴파일에만 쓴다
    compileOnly("org.springframework.boot:spring-boot-health")
    testImplementation("org.springframework.boot:spring-boot-health")
}
