dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Page 응답 형식 설정 (PageConfig)
    implementation("org.springframework.data:spring-data-commons")

    // Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

    // S3 Presigned URL 발급·객체 확인 (storage).
    // SDK 기본 HTTP 클라이언트(Apache HttpClient 5)가 클래스패스에 있으면 Spring RestClient가 그걸 자동으로 골라
    // 쿠키·리다이렉트 처리가 바뀐다. Apache·netty 클라이언트를 빼고 JDK 기반 url-connection-client를 쓴다
    implementation(platform("software.amazon.awssdk:bom:2.55.5"))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
