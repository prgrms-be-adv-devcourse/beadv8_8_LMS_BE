dependencies {
    implementation(project(":shared"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}

// Windows의 기본 인코딩(MS949)으로 컴파일하면 한글 주석·문자열이 깨지므로 UTF-8로 고정한다.
// TODO: 루트 build.gradle.kts의 subprojects에 같은 설정이 들어가면 이 블록은 지운다.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
