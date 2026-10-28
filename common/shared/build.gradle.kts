dependencies {
    // BaseEntity(JPA Auditing)·회원 복제 클래스(@MappedSuperclass)용
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 제공 모듈의 내부 HTTP API를 부르는 공통 Client(shared.<제공 모듈>.out, D62). RestClient.Builder·Jackson 자동 설정
    implementation("org.springframework.boot:spring-boot-starter-restclient")
}
