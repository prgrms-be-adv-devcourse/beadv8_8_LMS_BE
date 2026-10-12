dependencies {
    implementation(project(":shared"))
    // @LoginUserId
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
