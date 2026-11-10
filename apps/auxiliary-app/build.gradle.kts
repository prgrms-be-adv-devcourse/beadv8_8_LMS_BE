plugins {
    id("org.springframework.boot")
}

// auxiliary-app 실행 진입점. 검색·챗봇·알림을 JVM 하나에 모은다(#684, #714).
// 공통 코드는 :auxiliary-global(global/), 업무 모듈은 boundedContext/<모듈>의 Gradle 프로젝트다(settings.gradle.kts).
// 이 프로젝트만 모든 모듈을 의존한다.
dependencies {
    implementation(project(":shared"))
    implementation(project(":auxiliary-global"))
    implementation(project(":chatbot"))
    implementation(project(":notification"))
    implementation(project(":search"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

springBoot {
    mainClass = "com.hapbang.auxiliary.AuxiliaryApplication"
}

tasks.bootJar {
    archiveFileName = "app.jar"
}

// 공통 로컬 인프라(docker/compose.yaml)와 .env는 저장소 루트 기준이다.
tasks.bootRun {
    workingDir = rootDir
}

// CI는 앱마다 :<앱>:check만 돌린다(.github/workflows/ci.yml). 이 앱 디렉터리 안의 하위 프로젝트(global, boundedContext/*)
// 테스트도 함께 돌도록 check에 건다. 모듈을 더하면 자동으로 포함된다.
tasks.check {
    dependsOn(rootProject.subprojects
        .filter { it != project && it.projectDir.startsWith(projectDir) }
        .map { "${it.path}:check" })
}
