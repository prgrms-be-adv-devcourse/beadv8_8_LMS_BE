dependencies {
    implementation(project(":shared"))
    // @LoginUserId
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 구글 OAuth 로그인 처리(OAuth2UserService 구현)
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
