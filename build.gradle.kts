import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

description = "hapbang"

allprojects {
    group = "com.hapbang"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
            mavenBom(rootProject.libs.spring.modulith.bom.get().toString())
        }
    }

    dependencies {
        add("implementation", "org.springframework.modulith:spring-modulith-api")
        add("implementation", "org.springframework.modulith:spring-modulith-events-api")
        add("compileOnly", "org.projectlombok:lombok")
        add("annotationProcessor", "org.projectlombok:lombok")
        add("testCompileOnly", "org.projectlombok:lombok")
        add("testAnnotationProcessor", "org.projectlombok:lombok")
        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        if (project.name.endsWith("-app") || project.path == ":shared") {
            add("testImplementation", project(":test-support"))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// 실행 앱의 기술 의존성만 공통으로 관리한다. 앱끼리의 프로젝트 의존성은 허용하지 않는다.
// Kafka 클라이언트(common/kafka)도 모든 앱이 공통으로 의존하고 자동 설정으로 바로 쓴다.
// Redis 클라이언트(common/redis)도 모든 앱이 공통으로 의존하고 자동 설정으로 바로 쓴다.
configure(subprojects.filter { it.name.endsWith("-app") }) {
    dependencies {
        add("implementation", project(":kafka"))
        add("implementation", project(":redis"))
        add("implementation", "org.springframework.modulith:spring-modulith-starter-core")
        add("implementation", "org.springframework.modulith:spring-modulith-starter-jdbc")
        add("implementation", "org.springframework.boot:spring-boot-starter-security")
        add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
        add("implementation", "org.springframework.boot:spring-boot-starter-flyway")
        add("implementation", rootProject.libs.springdoc.openapi.webmvc.ui)
        add("runtimeOnly", "org.flywaydb:flyway-database-postgresql")
        add("runtimeOnly", "org.postgresql:postgresql")
        add("testImplementation", "org.springframework.boot:spring-boot-starter-security-test")
        add("testImplementation", "org.springframework.boot:spring-boot-starter-security-oauth2-client-test")
    }
    // developmentOnly는 각 앱에 Boot 플러그인이 적용된 후 생성된다.
    pluginManager.withPlugin("org.springframework.boot") {
        dependencies {
            add("developmentOnly", "org.springframework.boot:spring-boot-devtools")
            add("developmentOnly", "org.springframework.boot:spring-boot-docker-compose")
        }
    }
}

tasks.register("verifyModuleStructure") {
    group = "verification"
    description = "앱별 실행 파일과 런타임 프로젝트 의존성 경계를 검사한다."
    doLast {
        val applications = subprojects.filter { it.name.endsWith("-app") }
        check(applications.size == 6) { "여섯 개의 독립 실행 앱이 필요합니다." }
        check(findProject(":global") == null && findProject(":apps") == null) {
            "global/통합 apps 실행 프로젝트는 제거해야 합니다."
        }
        applications.forEach { app ->
            check(app.file("Dockerfile").isFile) { "${app.path}: Dockerfile 누락" }
            check(app.file("src/main/resources/application.yaml").isFile) { "${app.path}: application.yaml 누락" }
            check(app.tasks.findByName("bootJar") != null) { "${app.path}: bootJar 누락" }
            val mainClass = app.extensions
                .getByType<org.springframework.boot.gradle.dsl.SpringBootExtension>().mainClass.orNull
            check(mainClass != null && app.file("src/main/java/${mainClass.replace('.', '/')}.java").isFile) {
                "${app.path}: 명시적인 실행 진입점 누락"
            }
        }
        subprojects.forEach { module ->
            val appDependencies = module.configurations.getByName("runtimeClasspath")
                .incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                .map { it.projectPath }
                .filter { dependency -> dependency != module.path && applications.any { it.path == dependency } }
            check(appDependencies.isEmpty()) {
                "${module.path} 런타임이 다른 앱에 의존합니다: $appDependencies"
            }
        }
    }
}
