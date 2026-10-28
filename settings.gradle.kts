rootProject.name = "hapbang"

// apps/common은 디렉터리이며 자체 Gradle 프로젝트나 통합 실행 서버가 아니다.
val applications = listOf(
    "member-app", "billing-app", "appearance-app",
    "lecture-app", "chat-app", "customer-support-app",
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
