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

    testImplementation(project(":test-support"))
    // Facade 테스트의 역할 넣기(@WithMockUser)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
