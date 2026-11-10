// 검색 업무 모듈(:search, 패키지 com.hapbang.auxiliary.search, #767·#771).
// :auxiliary-global과 :shared에만 의존한다. 같은 앱의 다른 업무 모듈을 의존하지 않는다(루트 verifyModuleStructure가 검사).
dependencies {
    implementation(project(":auxiliary-global"))
    // 검색 전용 ElasticsearchClient(ES_SEARCH_API_KEY)를 직접 만든다. 버전은 Boot BOM(elasticsearch-java 9.4.5) 그대로다(#771 시험 결과)
    implementation("org.springframework.boot:spring-boot-starter-elasticsearch")
    // 회원 이벤트 소비. 앱은 common :kafka(자동 설정)를 함께 쓰지만 업무 모듈은 :kafka 프로젝트를 의존할 수 없어 starter를 직접 적는다
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    // shared MemberApiClient가 쓰는 RestClient.Builder(10분 통계 동기화)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    // 검색 요청 수·ES 응답 시간·결과 0건 지표
    implementation("io.micrometer:micrometer-core")

    testImplementation(project(":test-support"))
}

// 메서드 인자 이름을 class 파일에 남긴다(auxiliary-app README "모듈 추가 방법"). Spring Boot 플러그인을 쓰는 실행 앱은 자동으로 켜지지만
// 이 하위 프로젝트는 직접 켠다. 없으면 @RequestParam·@PathVariable 등이 인자 이름을 읽지 못한다
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}
