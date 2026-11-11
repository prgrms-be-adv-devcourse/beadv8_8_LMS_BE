plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":shared"))
    // 쿠폰 모듈(boundedContext/coupon). 같은 jar·같은 JVM에 싣되 강의 코드가 쿠폰 클래스를 컴파일 시점에 볼 수 없게 runtimeOnly다
    runtimeOnly(project(":coupon"))
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
    // 주문·구매 취소 회원별 요청 한도(D80), 파일 업로드 동시 수 상한(D81)과 그 지표. starter 없이 코드로 조합한다(지갑·shared와 같음)
    implementation(libs.resilience4j.ratelimiter)
    implementation(libs.resilience4j.bulkhead)
    implementation(libs.resilience4j.micrometer)
    // 쿠폰 사용 내부 API 호출(CouponApiClient)의 circuit breaker. 쿠폰 모듈의 강의 조회 호출과 같은 라이브러리다
    implementation(libs.resilience4j.circuitbreaker)
    // 이의 제기 결과·반환 이벤트를 Modulith 발행 기록(event_publication)을 거쳐 Kafka로 내보낸다(이벤트 외부화, #278). 버전은 Modulith BOM을 따른다
    implementation("org.springframework.modulith:spring-modulith-events-kafka")
    // 동시 요청의 유일 인덱스 위반 판별(UniqueIndexViolation)에 PSQLException의 제약 이름을 쓴다(billing과 같음). 드라이버는 실행 시 이 앱(runtimeOnly)가 넣어 준다
    compileOnly("org.postgresql:postgresql")
    // Facade 테스트의 역할 넣기(@WithMockUser). API 테스트 로그인은 사용자 헤더(support.GatewayLogin)다
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

// CI는 앱마다 :<앱>:check만 돌린다(.github/workflows/ci.yml). 이 앱 디렉터리 안의 하위 프로젝트(boundedContext/*)
// 테스트도 함께 돌도록 check에 건다(auxiliary-app과 같다). 모듈을 더하면 자동으로 포함된다.
tasks.check {
    dependsOn(rootProject.subprojects
        .filter { it != project && it.projectDir.startsWith(projectDir) }
        .map { "${it.path}:check" })
}

// 대용량 업로드 확인(@Tag("large-upload"))은 시간이 걸려 check에서 뺀다. 수동 실행: ./gradlew :lecture-app:largeUploadTest
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("large-upload") }
}

// billing의 Java 코드는 테스트 클래스패스에 넣지 않는다. 별도 실행 산출물만 HTTP 하네스에 전달한다.
tasks.withType<Test>().configureEach {
    dependsOn(":billing-app:e2eServerJar")
    systemProperty("hapbang.billing.e2e.enabled", "true")
    systemProperty("hapbang.billing.e2e.jar", rootProject.file("apps/billing-app/build/libs/billing-e2e-server.jar").absolutePath)
    inputs.file(rootProject.file("apps/billing-app/build/libs/billing-e2e-server.jar"))
        .withPropertyName("billingE2eServerJar")
    systemProperty("sun.net.http.retryPost", "false")
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
