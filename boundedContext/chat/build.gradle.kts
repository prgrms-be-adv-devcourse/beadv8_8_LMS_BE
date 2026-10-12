dependencies {
    implementation(project(":shared"))
    // 팀 공통 로그인(@LoginUserId)과 보안 설정
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // E2E 테스트 전용 로그인(OAuth2 로그인 세션을 직접 만든다)
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
}
