dependencies {
    implementation(project(":shared"))
    // @LoginUserId (세션의 로그인 회원 ID)
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 요청 값 검증 (@Valid, @NotBlank 등)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다 (채팅 모듈과 동일)
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    // 역할 검사(@PreAuthorize)와 거절 예외(AccessDeniedException). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // 테스트에서 로그인 상태 만들기 (TestLogin)
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

// Windows의 기본 인코딩(MS949)으로 컴파일하면 한글 주석·문자열이 깨지므로 UTF-8로 고정한다.
// TODO: 루트 build.gradle.kts의 subprojects에 같은 설정이 들어가면 이 블록은 지운다.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
