# Kafka 공통 모듈

모든 앱이 루트 Gradle(`build.gradle.kts`의 `-app` 공통 의존성)로 의존하며 자동 설정(`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`의 `KafkaConfig`)으로 바로 쓴다. 연결·직렬화는 각 앱의 `spring.kafka.*`로 준다.

- Java Config는 Spring Boot의 연결 팩토리·`KafkaTemplate`·리스너 팩토리 설정을 사용한다.
- 연결 주소·인증·TLS 등은 실행 앱의 `spring.kafka.*` 설정으로 주입한다. 이 모듈에는 `application.yaml`이나 실제 서버 정보가 없다.
- 토픽·key·헤더·소비자 그룹 이름, 재시도·DLT·역직렬화 정책은 [Kafka 인프라 문서](../../docs/architecture/infrastructure/kafka.md)에 있고 각 앱이 그대로 구현한다(#339). 이 모듈에는 그 정책을 코드로 두지 않는다.
- `KafkaConfig`는 모든 앱 공통 Kafka 빈을 두는 자리다. 연결 팩토리·`KafkaTemplate`은 Boot의 `KafkaAutoConfiguration`이 만들고, 앱이 같은 빈을 직접 등록하면 앱의 빈이 우선한다.
- 이 모듈은 Kafka 서버를 배포하거나 Spring Event를 다른 프로세스로 전달하지 않는다.

테스트는 두지 않는다. 실제 통신 검증은 별도다.
