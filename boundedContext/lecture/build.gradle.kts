dependencies {
    implementation(project(":shared"))
    // @LoginUserId
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 썸네일 조회 URL 서명 실패(자격 증명) 구분용 SdkException (D26). 버전은 global의 AWS BOM과 맞춘다
    implementation(platform("software.amazon.awssdk:bom:2.55.5"))
    implementation("software.amazon.awssdk:sdk-core")
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.55")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
