dependencies {
    implementation(project(":shared"))
    // @LoginUserId
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    // 지갑 중복 생성 판별에 PSQLException의 제약 이름을 쓴다. 드라이버는 실행 시 apps(runtimeOnly)가 넣어 준다
    compileOnly("org.postgresql:postgresql")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
