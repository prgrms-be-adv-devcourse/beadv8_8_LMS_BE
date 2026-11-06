rootProject.name = "hapbang"

// apps/common은 디렉터리이며 자체 Gradle 프로젝트나 통합 실행 서버가 아니다.
val applications = listOf(
    "member-app", "billing-app", "appearance-app",
    "lecture-app", "chat-app", "customer-support-app", "auxiliary-app",
)
val libraries = listOf("kafka", "redis", "shared", "test-support")

applications.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("apps/$name")
}
libraries.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("common/$name")
}

// auxiliary-app 안의 Gradle 프로젝트(#714). 실행 진입점은 :auxiliary-app, 공통 코드는 :auxiliary-global,
// 업무 모듈은 boundedContext/<모듈>이다(boundedContext는 Gradle 프로젝트가 아니라 모듈을 묶는 디렉터리).
// 하위 프로젝트 이름에 -app을 붙이지 않는다. verifyModuleStructure가 -app으로 끝나는 프로젝트를 실행 앱으로 센다.
val auxiliaryModules = listOf("chatbot")
include(":auxiliary-global")
project(":auxiliary-global").projectDir = file("apps/auxiliary-app/global")
auxiliaryModules.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("apps/auxiliary-app/boundedContext/$name")
}

// lecture-app 안의 Gradle 프로젝트. 실행 진입점은 :lecture-app이고, 쿠폰 모듈은 boundedContext/coupon이다.
// 같은 JVM에 실리지만 강의 ↔ 쿠폰은 내부 HTTP API와 Kafka로만 통신한다(apps/lecture-app/boundedContext/coupon/docs/design.md).
// 하위 프로젝트 이름에 -app을 붙이지 않는다. verifyModuleStructure가 -app으로 끝나는 프로젝트를 실행 앱으로 센다.
val lectureModules = listOf("coupon")
lectureModules.forEach { name ->
    include(":$name")
    project(":$name").projectDir = file("apps/lecture-app/boundedContext/$name")
}
