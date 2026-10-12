dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Page 응답 형식 설정 (PageConfig)
    implementation("org.springframework.data:spring-data-commons")

    // Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
