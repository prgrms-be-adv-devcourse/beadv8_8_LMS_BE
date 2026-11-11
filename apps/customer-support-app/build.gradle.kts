plugins {
    id("org.springframework.boot")
    // GraalVM Native Image 시험 적용(#609). 이 플러그인이 있으면 Spring Boot가 processAot·nativeCompile을 만든다.
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Swagger 명세용 어노테이션(@Tag, @Operation). 루트에서 관리하는 springdoc 3.1.1이 쓰는 버전과 맞춘다
    implementation(libs.swagger.annotations)
    // Facade 역할 검사(@PreAuthorize). 메서드 보안은 global SecurityConfig가 켠다
    implementation("org.springframework.security:spring-security-core")
    // 이의 제기 판단·철회 요청을 Modulith 발행 기록(event_publication)을 거쳐 Kafka로 내보낸다(이벤트 외부화, #279). 버전은 Modulith BOM을 따른다
    implementation("org.springframework.modulith:spring-modulith-events-kafka")

    // Facade 테스트의 역할 넣기(@WithMockUser). API 테스트 로그인은 사용자 헤더(support.GatewayLogin)다
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

springBoot {
    mainClass = "com.hapbang.customersupport.CustomerSupportApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}

// AOT는 빈 조건(@Profile·@ConditionalOnProperty)을 빌드 때 확정한다. 운영 조건으로 처리해야 운영에서 필요한 빈이 빠지지 않는다(#609).
// - prod 프로필, 내부 API 포트(InternalApiPortConfig), 관리 포트(별도 actuator context)는 k8s ConfigMap과 같은 값이다.
// - 값 자체는 실행 때 환경 변수로 다시 읽는다. 여기서는 빈을 만들지 정하는 데만 쓴다.
// - 플레이스홀더 값(LECTURE_API_BASE_URL 등)은 조건 평가 때 풀려야 해서 빌드용 값을 준다.
tasks.named<org.springframework.boot.gradle.tasks.aot.ProcessAot>("processAot") {
    args(
        "--spring.profiles.active=prod",
        "--hapbang.internal-api.port=8081",
        "--management.server.port=8082",
        // trace 전송(#663). OTLP exporter 빈은 이 속성이 있을 때만 생기므로 빌드 때 넣지 않으면 native에서 trace를 보내지 않는다
        "--management.opentelemetry.tracing.export.otlp.endpoint=http://alloy.hapbang-platform.svc.cluster.local:4317",
    )
    environment(
        mapOf(
            "LECTURE_API_BASE_URL" to "http://lecture-app:8081",
            "DB_URL" to "jdbc:postgresql://localhost:5432/customersupport",
            "DB_USERNAME" to "customersupport",
            "DB_PASSWORD" to "aot-build-placeholder",
        ),
    )
}

// 실행 파일 이름을 앱마다 같게 둔다(Dockerfile.native가 build/native/nativeCompile/app을 복사한다).
// 루트가 모든 모듈에 java-library를 적용하면 Native Build Tools는 공유 라이브러리(app.so)를 기본값으로 만든다.
// 실행 파일로 만들도록 끄고, application 플러그인이 없으니 main class도 직접 넘긴다.
graalvmNative {
    binaries.named("main") {
        imageName = "app"
        mainClass = springBoot.mainClass
        sharedLibrary = false
    }
}
