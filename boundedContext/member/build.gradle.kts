dependencies {
    implementation(project(":shared"))
    // @LoginUserId
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 구글 OAuth 로그인 처리(OAuth2UserService 구현)
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    // 치지직 Open API 호출 (채널 인증)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // 회원 ID로 그 회원의 세션을 찾아 역할 갱신 (인터페이스만, 실제 JDBC 저장소는 apps)
    implementation("org.springframework.session:spring-session-core")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
