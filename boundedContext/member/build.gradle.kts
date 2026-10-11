dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 구글 OAuth 로그인 처리(OAuth2UserService 구현)
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
}
