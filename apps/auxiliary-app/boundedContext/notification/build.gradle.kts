// 알림 업무 모듈(:notification, 패키지 com.hapbang.auxiliary.notification, docs/notification/decisions.md ND-006).
// :auxiliary-global과 :shared에만 의존한다. 같은 앱의 다른 업무 모듈을 의존하지 않는다(루트 verifyModuleStructure가 검사).
// Kafka는 실행 앱(:auxiliary-app)이 common :kafka를 의존하지만 업무 모듈은 :kafka 프로젝트를 의존할 수 없어, 같은 starter를 직접 쓴다.
dependencies {
    implementation(project(":auxiliary-global"))
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // 실시간 전달(N3, ND-027·ND-028): 자체 STOMP와 Redis Pub/Sub·연결 티켓. Redis는 실행 앱이 common :redis를 의존하지만 업무 모듈은
    // :redis 프로젝트를 의존할 수 없어 같은 starter를 직접 쓴다(Kafka와 같은 이유)
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 실시간 전달에서 버린 프레임 지표(NotificationRealtimeRelay). 버전은 Boot BOM
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":test-support"))
}

// 메서드 인자 이름을 class 파일에 남긴다(auxiliary-app README "모듈 추가 방법"). Spring Boot 플러그인을 쓰는 실행 앱은 자동으로 켜지지만
// 이 하위 프로젝트는 직접 켠다. 없으면 @RequestParam·@PathVariable 등이 인자 이름을 읽지 못한다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
