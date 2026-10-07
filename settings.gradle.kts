rootProject.name = "hapbang"

// boundedContext는 Gradle 프로젝트가 아닌 업무 모듈을 묶는 디렉터리다.
val boundedContexts = listOf(
    "member",
    "appearance",
    "lecture",
    "billing",
    "chat",
    "customersupport",
)

boundedContexts.forEach { context ->
    include(":$context")
    project(":$context").projectDir = file("boundedContext/$context")
}

include(":apps", ":global", ":shared", ":test-support")
