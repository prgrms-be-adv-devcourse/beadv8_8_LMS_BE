plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":member"))
    implementation(project(":appearance"))
    implementation(project(":lecture"))
    implementation(project(":billing"))
    implementation(project(":chat"))
    implementation(project(":customersupport"))
    implementation(project(":global"))
    implementation(project(":shared"))

    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

// IntelliJ 실행과 같은 작업 디렉터리(프로젝트 루트)를 써서 docker/compose.yaml 경로를 일치시킨다.
tasks.bootRun {
    workingDir = rootDir
}
