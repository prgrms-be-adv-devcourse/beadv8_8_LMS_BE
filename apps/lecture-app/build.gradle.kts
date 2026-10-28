plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // 썸네일 조회 URL 서명 실패(자격 증명) 구분용 SdkException (D28). 버전은 루트의 AWS BOM과 맞춘다
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:sdk-core")
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // 동시 요청의 유일 인덱스 위반 판별(UniqueIndexViolation)에 PSQLException의 제약 이름을 쓴다(billing과 같음). 드라이버는 실행 시 이 앱(runtimeOnly)가 넣어 준다
    compileOnly("org.postgresql:postgresql")
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
    mainClass = "com.hapbang.lecture.LectureApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}

// 대용량 업로드 확인(@Tag("large-upload"))은 시간이 걸려 check에서 뺀다. 수동 실행: ./gradlew :lecture-app:largeUploadTest
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
