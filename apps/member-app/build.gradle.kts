plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // 요청 본문 검증(@Valid, @NotBlank). 관리자 반려 사유 (다른 모듈과 같음)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 구글 OAuth 로그인 처리(OAuth2UserService 구현)
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    // 치지직 Open API 호출 (채널 인증)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // 회원 ID로 그 회원의 세션을 찾아 역할 갱신 (인터페이스만, 실제 JDBC 저장소는 이 앱)
    implementation("org.springframework.session:spring-session-core")
    implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // 테스트에서 로그인 상태 만들기 (oauth2Login())
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

// 이 앱의 global.storage 구현에서 사용하는 S3 클라이언트.
dependencies {
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
}

springBoot {
    mainClass = "com.hapbang.member.MemberApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}

// 대용량 업로드 확인(@Tag("large-upload"))은 시간이 걸려 check에서 뺀다. 수동 실행: ./gradlew :member-app:largeUploadTest
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
