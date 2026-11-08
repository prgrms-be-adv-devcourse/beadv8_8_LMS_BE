// 챗봇 업무 모듈(:chatbot, 패키지 com.hapbang.auxiliary.chatbot).
// :auxiliary-global과 :shared에만 의존한다. 같은 앱의 다른 업무 모듈을 의존하지 않는다(루트 verifyModuleStructure가 검사).
dependencies {
    implementation(project(":auxiliary-global"))

    testImplementation(project(":test-support"))
}
