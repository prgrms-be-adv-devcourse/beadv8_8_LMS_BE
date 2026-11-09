// 쿠폰 모듈(:coupon, 패키지 com.hapbang.coupon). lecture-app 안의 Gradle 하위 프로젝트다.
// 실행 진입점·application.yaml·Dockerfile은 :lecture-app이 가진다. :lecture-app이 runtimeOnly로 실어 같은 JVM에서 뜬다.
// 강의 코드를 의존하지 않는다(강의 ↔ 쿠폰은 내부 HTTP API와 Kafka로만 통신한다).
// 루트 build.gradle.kts의 공통 의존(Kafka·Redis·starter)은 -app 프로젝트에만 적용되므로 쓰는 것을 여기에 직접 적는다.
dependencies {
    implementation(project(":shared"))
    implementation(project(":kafka"))
    implementation(project(":redis"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Facade 역할 검사(@PreAuthorize). 메서드 보안과 사용자 헤더 인증은 같은 JVM의 lecture-app 보안 설정이 켠다
    implementation("org.springframework.security:spring-security-core")
    // 컨트롤러 Spec의 Swagger 애노테이션. springdoc 자체는 lecture-app이 싣는다
    implementation(libs.swagger.annotations)
    // 강의 조회 내부 API 호출의 circuit breaker. starter 없이 코드로 조합한다(지갑·회원과 같음)
    implementation(libs.resilience4j.circuitbreaker)
    // 발급 확정의 유일 제약 위반 판별(UniqueIndexViolation)에 PSQLException의 제약 이름을 쓴다(강의와 같음). 드라이버는 실행 시 lecture-app이 넣어 준다
    compileOnly("org.postgresql:postgresql")
    // 발급 복구·대사 지표(Micrometer). 레지스트리는 같은 JVM의 lecture-app actuator가 준다. 버전은 Boot BOM을 따른다
    implementation("io.micrometer:micrometer-core")
    // 매진 표시 메모리 캐시(TTL·항목 수 상한). 버전은 Boot BOM을 따른다
    implementation(libs.caffeine)

    testImplementation(project(":test-support"))
    // Facade 테스트의 역할 넣기(@WithMockUser)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
