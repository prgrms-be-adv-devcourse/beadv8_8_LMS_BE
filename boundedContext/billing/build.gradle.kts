dependencies {
    implementation(project(":shared"))
    // @LoginUserId
    implementation(project(":global"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 충전 요청 값 검증 (@Valid, #224)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // TossPayments 결제 승인·조회 호출 (RestClient.Builder, #224)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // Swagger 명세용 어노테이션(@Tag, @Operation). global의 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // 지갑 중복 생성 판별에 PSQLException의 제약 이름을 쓴다. 드라이버는 실행 시 apps(runtimeOnly)가 넣어 준다
    compileOnly("org.postgresql:postgresql")
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
