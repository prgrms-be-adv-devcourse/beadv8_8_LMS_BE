# 빌드와 테스트

## Gradle

- Gradle 모듈 이름은 `:lecture`, `:global` 등이다 (`:boundedContext:lecture` 아님).
- `./gradlew check`는 Docker(Testcontainers)가 필요하다.
- Boot 앱은 `apps` 하나뿐이고 나머지는 `java-library`다. 모듈 테스트는 `apps`의 yaml을 읽지 않는다.
- Gradle이 테스트를 up-to-date로 건너뛸 수 있다. 실제 실행 여부는 `build/test-results/test/TEST-*.xml`로 확인한다.

## apps 통합 테스트

- 시작할 때 테스트 계정(8개)이 만들어지고 각 모듈로 복제된다. 행 개수는 절대값 대신 변경 전후로 비교한다.
- `hapbang.internal-api.key`가 비면 앱이 시작하지 않는다. `@IntegrationTest`는 키를 넣지만, 직접 `@SpringBootTest(properties = …)`를 쓰는 테스트는 `"hapbang.internal-api.key=" + IntegrationTest.INTERNAL_API_KEY`를 넣어야 한다 (#309가 빠뜨려 dev CI 44건 실패 → #313). 새 필수 설정을 더할 때는 `git grep -l '@SpringBootTest'`로 이런 테스트를 함께 고친다.
- apps 테스트에서만 설정을 바꾸려면 `apps/src/test/resources/config/application.yaml`을 쓴다 (`classpath:/config/`가 main `application.yaml`보다 우선). 프로필 파일(`application-local.yaml`)의 값은 이걸로 덮어쓸 수 없다. 루트 `application.yaml`을 test에 두면 main 파일 전체를 가린다.
- apps의 Jackson으로 읽은 `OffsetDateTime`은 같은 시각의 UTC offset으로 바뀐다 (응답 본문은 `+09:00`). 내부 API Client 테스트는 instant로 비교한다.
- S3를 쓰는 테스트는 [S3 문서](../deploy/s3.md)의 "테스트"를 따른다.

## 이벤트 리스너

- `@ApplicationModuleListener`를 붙인 리스너는 `TransactionTemplate` 안에서 `publishEvent`하고 `await()`로 결과를 기다린다 (리스너가 커밋 뒤 비동기로 돈다, [모듈 연동](module-integration.md)).
- "처리되지 않음"은 `await().during(Duration.ofMillis(500))`로 확인한다.
- 실패한 이벤트가 남으면 종료 시 `publications left unfinished` 로그가 찍히는데, 이는 정상이다.

## 배치

- 배치를 켠(`hapbang.scheduling.enabled=true`) `@SpringBootTest`에는 `@DirtiesContext`를 붙인다. 붙이지 않으면 캐시된 컨텍스트에서 배치가 계속 돌아 다른 테스트의 DB를 바꾼다.
- 실패 격리는 `@MockitoSpyBean`으로 Support 조회가 한 항목에서만 예외를 던지게 해서 확인한다.
- 배치 빈 등록과 cron 해석은 apps `SchedulingEnabledTest`(스케줄링을 켠 컨텍스트)로 확인한다. 다른 테스트가 통과하는 것만으로는 등록 여부를 알 수 없다. 새 배치는 `SchedulingEnabledTest`의 모듈별 cron 등록 테스트(예: `강의_배치가_정한_cron으로_등록된다`)에 (`패키지.클래스.메서드`, cron) 튜플을 더한다 (`ScheduledTaskHolder`).
