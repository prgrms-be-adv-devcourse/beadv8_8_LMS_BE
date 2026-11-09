// 알림 업무 모듈(:notification, 패키지 com.hapbang.auxiliary.notification, docs/notification/decisions.md ND-006).
// :auxiliary-global과 :shared에만 의존한다. 같은 앱의 다른 업무 모듈을 의존하지 않는다(루트 verifyModuleStructure가 검사).
// Kafka는 실행 앱(:auxiliary-app)이 common :kafka를 의존하지만 업무 모듈은 :kafka 프로젝트를 의존할 수 없어, 같은 starter를 직접 쓴다.
dependencies {
    implementation(project(":auxiliary-global"))
    implementation("org.springframework.boot:spring-boot-starter-kafka")

    testImplementation(project(":test-support"))
}
