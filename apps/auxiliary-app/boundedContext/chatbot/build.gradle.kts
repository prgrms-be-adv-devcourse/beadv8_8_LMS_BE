// 챗봇 업무 모듈(:chatbot, 패키지 com.hapbang.auxiliary.chatbot).
// :auxiliary-global과 :shared에만 의존한다. 같은 앱의 다른 업무 모듈을 의존하지 않는다(루트 verifyModuleStructure가 검사).
dependencies {
    implementation(project(":auxiliary-global"))
    // 출연 이벤트 소비(#716). 앱은 common :kafka(자동 설정)를 함께 쓰지만 업무 모듈은 :kafka 프로젝트를 의존할 수 없어 starter를 직접 적는다
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // shared AppearanceApiClient가 쓰는 RestClient.Builder (#716 싱크)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // 싱크 지표 (#716)
    implementation("io.micrometer:micrometer-core")

    // Spring AI(#715). 버전은 BOM 하나로 맞춘다. 공급자는 spring.ai.model.* 속성으로 하나만 켠다(local=ollama, prod=openai).
    // 이 모듈만 쓰므로 루트 버전 카탈로그(gradle/)에 두지 않는다. 카탈로그를 바꾸면 CI가 모든 앱을 다시 테스트·배포한다
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.1"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-elasticsearch")
    // ES 연결(Rest5Client)·SSL bundle·API key 설정(spring.elasticsearch.*). Spring AI starter에는 들어 있지 않다
    implementation("org.springframework.boot:spring-boot-starter-elasticsearch")
    // LLM 호출 장애 대비(circuit breaker). 회원별 호출 제한은 global의 KeyedRateLimiter를 쓴다
    implementation(libs.resilience4j.circuitbreaker)
    // SSE 스트림(Flux). Spring AI가 이미 가져오지만 직접 쓰므로 명시한다
    implementation("io.projectreactor:reactor-core")

    testImplementation(project(":test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("io.projectreactor:reactor-test")
}

// 메서드 인자 이름을 class 파일에 남긴다. Spring Boot 플러그인을 쓰는 실행 앱은 자동으로 켜지지만 이 하위 프로젝트는 직접 켠다.
// 없으면 @Tool 인자 이름이 arg0으로 모델에게 가고(Tool 설명이 망가짐), @RequestParam·@PathVariable 이름도 읽지 못한다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

// 실제 모델(Ollama)을 부르는 테스트는 @Tag("llm")을 붙이고 기본 빌드(CI)에서 뺀다. 로컬에서 llmTest로 돌린다(README "로컬 실행")
tasks.test {
    useJUnitPlatform {
        excludeTags("llm")
    }
}

tasks.register<Test>("llmTest") {
    description = "로컬 Ollama로 실제 모델 연동·FAQ 검색 평가를 돌린다(@Tag(\"llm\"))."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("llm")
    }
    // 평가 결과 표를 콘솔에서 바로 보게 한다
    testLogging {
        showStandardStreams = true
    }
}
