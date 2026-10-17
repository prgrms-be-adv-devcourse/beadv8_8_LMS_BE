dependencies {
    implementation(project(":shared"))
    // 팀 공통 로그인(@LoginUserId)과 오류 응답(BusinessException)
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")

    // 테스트에서 로그인 상태 만들기 (oauth2Login(), csrf())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
