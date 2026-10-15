dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Page 응답 형식 설정 (PageConfig)
    implementation("org.springframework.data:spring-data-commons")

    // Swagger
    implementation(libs.springdoc.openapi.webmvc.ui)

    // S3 업로드·조회 URL 발급·객체 확인 (storage).
    // SDK 기본 HTTP 클라이언트(Apache HttpClient 5)가 클래스패스에 있으면 Spring RestClient가 그걸 자동으로 골라
    // 쿠키·리다이렉트 처리가 바뀐다. Apache·netty 클라이언트를 빼고 JDK 기반 url-connection-client를 쓴다
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")

    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

// 대용량 업로드 확인(@Tag("large-upload"))은 시간이 걸려 check에서 뺀다. 수동 실행: ./gradlew :global:largeUploadTest
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("large-upload") }
}

// 힙보다 큰 본문을 올려 업로드가 본문을 메모리에 모으지 않는지 확인한다(모으면 OOM). docs/deploy/s3.md
tasks.register<Test>("largeUploadTest") {
    description = "힙(256MB)보다 큰 파일을 S3Mock에 올려 스트리밍 업로드를 확인한다."
    group = "verification"
    val test = project.the<JavaPluginExtension>().sourceSets["test"]
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    useJUnitPlatform { includeTags("large-upload") }
    maxHeapSize = "256m"
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
