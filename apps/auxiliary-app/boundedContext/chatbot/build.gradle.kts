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

    testImplementation(project(":test-support"))
}
