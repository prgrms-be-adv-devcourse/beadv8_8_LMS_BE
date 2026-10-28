# 빌드와 테스트

## Gradle

- 앱: `:member-app`, `:billing-app`, `:appearance-app`, `:lecture-app`, `:chat-app`, `:customer-support-app`.
- 공통 라이브러리: `:shared`, `:test-support`, `:kafka`, `:redis`.
- `./gradlew check`는 활성 테스트를 실행하며 PostgreSQL Testcontainers를 사용하는 테스트는 Docker가 필요하다.
- `./gradlew verifyModuleStructure`는 앱 간 실행 의존과 common → 앱 의존을 막고 필수 실행 파일을 검사한다.
- 앱별 검증: `./gradlew :member-app:check :member-app:bootJar`.
- 실제 실행 여부는 `apps/<앱>/build/test-results/test/TEST-*.xml`로 확인한다. up-to-date로 생략된 테스트를 새 실행으로 보고하지 않는다.

## 테스트 경계

기존 업무 모듈 테스트와 앱 내부 global 테스트는 각 앱의 `src/test`에 있다. `common/test-support`가 PostgreSQL·S3Mock·테스트 로그인 지원을 제공한다. 앱 테스트가 다른 앱을 Gradle 의존으로 가져와 모든 빈을 한 컨텍스트에 올리면 독립 배포 경계를 검증할 수 없으므로 금지한다.

기존 단일 `apps`의 통합 테스트는 현재 `check`에 포함되지 않는다. 이전 시나리오는 Git 이력에서 확인하고 서비스 간 HTTP·이벤트·인증으로 전환하면서 재작성해야 한다. 현재 테스트 통과는 분산 거래 전체의 동작 보장이 아니다. 자세한 미완료 범위는 [앱 구조](../architecture/app-modules.md)를 따른다.

다른 서비스의 포트를 대체하는 Fake는 테스트 소스에만 둔다. 앱 실행을 위해 Fake를 운영 소스에 넣지 않는다.

## 설정과 내부 API

- 내부 API 키가 필수인 컨텍스트는 `hapbang.internal-api.key`를 테스트 값으로 설정한다. `@IntegrationTest`는 테스트 키를 제공한다. 직접 `@SpringBootTest`를 쓰는 테스트도 필요한 값을 명시해야 한다.
- 앱의 YAML이 테스트에 영향을 주지 않도록 테스트 설정의 우선순위와 활성 프로필을 확인한다. 실제 기동 검증과 서비스 일부만 로드하는 테스트를 구분한다.
- Jackson으로 읽은 `OffsetDateTime`의 offset이 UTC로 정규화될 수 있으므로 같은 시각인지 확인할 때는 instant를 비교한다.
- S3를 호출하는 테스트는 [S3 문서](../deploy/s3.md)의 테스트 설정을 사용한다.

## 이벤트 리스너

- `@ApplicationModuleListener`를 테스트할 때는 `TransactionTemplate` 안에서 이벤트를 발행하고 `await()`로 커밋 뒤 비동기 처리를 확인한다.
- "처리되지 않음"은 `await().during(Duration.ofMillis(500))`처럼 관찰 구간을 둔다.
- 이 검증은 같은 JVM 내부의 Spring Event 처리다. 다른 앱으로 전달되는 Kafka 연동 검증이 아니다.
- 실패한 이벤트가 남으면 종료 시 `publications left unfinished` 로그가 찍힐 수 있다.

## 배치

- 배치를 켠 `@SpringBootTest`에는 `@DirtiesContext`를 붙여 캐시된 컨텍스트의 배치가 다음 테스트 DB를 바꾸지 않도록 한다.
- 실패 격리는 `@MockitoSpyBean`으로 특정 항목 조회가 실패하도록 해 확인한다.
- 기존 통합 `SchedulingEnabledTest` 시나리오는 Git 이력을 참고해 앱별 배치 등록·cron 검증으로 옮긴다.

## CI 전환

워크플로는 dev 버전을 사용한다. 기존 `verify`는 제거된 `:apps:bootJar`를 호출하므로 앱별 구조에 맞는 CI 수정이 필요하다.
