// 쿠폰 모듈(:coupon, 패키지 com.hapbang.coupon). lecture-app 안의 Gradle 하위 프로젝트다.
// 실행 진입점·application.yaml·Dockerfile은 :lecture-app이 가진다. :lecture-app이 runtimeOnly로 실어 같은 JVM에서 뜬다.
// 강의 코드를 의존하지 않는다(강의 ↔ 쿠폰은 내부 HTTP API와 Kafka로만 통신한다).
dependencies {
    implementation(project(":shared"))

    testImplementation(project(":test-support"))
}
