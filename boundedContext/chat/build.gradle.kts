dependencies {
    implementation(project(":shared"))
    // 팀 공통 로그인(@LoginUserId)과 보안 설정
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    // Controller 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")

    // E2E 테스트 전용 로그인(OAuth2 로그인 세션을 직접 만든다)
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
}
