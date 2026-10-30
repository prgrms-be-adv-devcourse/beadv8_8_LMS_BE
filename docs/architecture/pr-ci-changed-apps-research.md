# PR CI에서 바뀐 앱만 테스트하기 조사

조사일: 2026-10-30
결정 반영: 2026-10-30 (#488)

## 질문

feature → dev PR의 CI가 8~9분 걸려 작업이 밀린다. PR에서 바뀐 앱만 테스트하면 얼마나 줄고, 무엇을 잃는가.

## 결론

PR CI는 **바뀐 앱만, 앱마다 러너 하나에서** 테스트한다. 바뀐 앱은 `.github/scripts/apps.sh tested`가 경로로 고르고, 필수 체크 `verify`는 결과를 모으는 job으로 남긴다. main 발행(`publish.yml`)은 지금처럼 여섯 앱을 모두 테스트한다.

- Java 변경이 없는 PR(최근 40개 중 13개)은 Gradle을 돌리지 않는다.
- 앱 하나만 바꾼 PR(17개)은 그 앱만 러너를 혼자 쓰며 테스트한다.
- common·Gradle·CI 파일을 바꾼 PR(10개)은 여섯 앱을 모두 테스트하되 앱마다 러너를 따로 써서, 가장 느린 앱 하나의 시간에 가깝게 끝난다.

바꾼 뒤 시간은 이 PR(#488)의 CI로 재서 아래 "측정"에 적는다.

## 바꾸기 전 CI

`ci.yml`의 `verify` job 하나가 4코어 러너(`ubuntu-24.04`)에서 `./gradlew verifyModuleStructure`와 `./gradlew check bootJar --parallel`(#354)로 여섯 앱과 common을 모두 테스트하고, 여섯 앱의 이미지를 빌드했다. 무엇을 바꿨는지와 상관없다.

문서와 `publish.yml`만 바꾼 #485(run 41025842116)의 `verify`는 9분 42초였다.

| 단계 | 시간 |
| --- | --- |
| 모듈 구조 검사 | 38초 |
| 테스트·jar 빌드 | 8분 38초 |
| 이미지 빌드 확인 | 14초 |
| (`manifests` job, 병렬) | 24초 |

같은 run의 Gradle 로그에서 앱별 테스트 구간(`> Task :<앱>:test`가 찍힌 때부터 `:<앱>:check`까지, 어림값)은 아래와 같다. 테스트 JVM과 Postgres 컨테이너가 4코어를 나눠 써서 서로 늦춘다. member-app은 다른 앱이 끝날 때까지 컴파일도 시작하지 못하고 약 5분 30초를 기다렸다.

| 앱 | 테스트 구간 |
| --- | --- |
| chat-app | 약 6분 |
| billing-app | 약 6분 |
| appearance-app | 약 4분 10초 |
| lecture-app | 약 3분 |
| member-app | 약 2분 15초 |
| customer-support-app | 약 45초 |

같은 날 다른 run의 `테스트·jar 빌드`도 `common/shared`를 바꾼 #483(run 41023922286)이 8분 24초, chat만 바꾼 #481(run 41024514314)이 6분 52초였다. job 대기는 2~3초로, 시간은 대부분 테스트 실행이다.

## 바뀐 범위 분포

2026-10-30 기준 dev에 squash 병합된 최근 PR 40개(#409~#483)의 병합 커밋에 `apps.sh tested <커밋^> <커밋>`을 돌린 결과다. 파일 목록으로 따로 분류한 결과와 40개 모두 같았다.

| 바뀐 범위 | PR 수 | 예 |
| --- | --- | --- |
| Java 변경 없음(docs·`k8s/`·infra, 앱 manifest·문서만) | 13 | #452 Compose 배포 경로 삭제, #458 Pod Security |
| 앱 하나 | 17 | #481 chat, #473 billing, #482 appearance |
| 전체(common·`gradle/`·루트 빌드 파일·CI 파일) | 10 | #483 `common/shared`, #412 `gradle/libs.versions.toml` |

전체에 해당한 PR은 대부분 `common/shared`(서비스 간 HTTP Client·이벤트 레코드)를 바꿨다.

## 선택지

| 선택지 | 판단 |
| --- | --- |
| 한 job에서 바뀐 앱의 task만 실행 | 변경이 가장 작다. 전체 PR(4개 중 1개)은 여전히 4코어 하나에서 8분대다 |
| **바뀐 앱 + 앱마다 러너(matrix)** | 선택. 전체 PR도 앱끼리 CPU를 나눠 쓰지 않는다. job이 늘고 발행 artifact를 앱별로 나눠야 한다 |
| Gradle build cache로 안 바뀐 모듈을 `FROM-CACHE` | 의존 관계를 정확히 따르지만, `setup-gradle`은 기본 브랜치(main)에서만 캐시를 쓰고 dev push에는 CI가 없어 PR이 읽을 dev 기준 캐시가 없다. dev push CI와 캐시 크기 관리가 따로 필요해 보류한다 |
| 더 큰 러너 | org가 free 플랜이라 쓰지 않는다 |

## 판정 규칙

| 바뀐 경로 | 테스트할 앱 | 이유 |
| --- | --- | --- |
| `apps/<앱>/**` | 그 앱 | 앱끼리 의존하지 않는다(`verifyModuleStructure`) |
| `common/**`, `gradle/**`, 루트 `build.gradle.kts`·`settings.gradle.kts`, `gradlew` | 전체 | 모든 앱이 의존한다 |
| `.github/workflows/ci.yml`, `.github/scripts/apps.sh` | 전체 | CI 자체를 바꾼 PR은 전체 경로로 확인한다 |
| `apps/<앱>/k8s/**`, `*.md` | 판정에서 뺀다 | manifest는 `manifests` job이 검사하고, 테스트가 문서를 읽지 않는다 |
| 그 밖(`docs/`, 루트 `k8s/`, 다른 workflow 등) | 없음 | |
| base를 알 수 없음(빈 값, `0000…`, 저장소에 없음) | 전체 | 판정하지 않는다 |

발행·배포가 쓰는 `apps.sh changed`·`source`는 바꾸지 않았다. 이미지 태그(`sha-<그 앱을 마지막으로 바꾼 main 커밋>`)가 앱 manifest 변경에도 바뀌는 규칙을 그대로 둔다.

## 잃는 것과 대응

| 위험 | 대응 |
| --- | --- |
| 판정이 틀리면 바뀐 앱의 테스트가 조용히 빠진다 | `apps-test.sh`가 임시 git 저장소에서 규칙을 확인하고 `changes` job이 매번 실행한다. 공유 경로·CI 파일·알 수 없는 base는 전체로 보낸다 |
| 앱이 다른 앱에 의존하게 바뀌면 "그 앱만" 판정이 틀린다 | Java가 바뀐 PR마다 `structure` job이 `verifyModuleStructure`로 앱 간 의존을 막는다 |
| PR에서 돌지 않은 앱은 dev 병합 뒤에도 돌지 않는다(dev push CI 없음) | main 발행이 여섯 앱을 모두 테스트한다. PR 전 로컬 `./gradlew check`(작업 흐름 문서)는 그대로다 |
| PR의 base 이후 dev가 움직임 | base와 merge 커밋을 비교하므로 다른 PR의 변경까지 더 테스트할 뿐 빠뜨리지 않는다 |
| 앱별 matrix job 이름(`test (<앱>)`)은 PR마다 달라 필수 체크로 걸 수 없다 | `verify` job이 결과를 모은다. 건너뜀은 통과, 실패·취소는 실패. ruleset은 바꾸지 않는다 |
| 동시 job이 늘어 org 한도(free 플랜, 20개)에 걸릴 수 있다 | 전체 PR 하나가 동시에 최대 8개(structure·test 6·manifests)를 쓴다. 지금 대기는 2~3초다 |

## 측정

이 PR(#488)은 `ci.yml`을 바꿔 전체(여섯 앱)로 판정된다. 병합 전 CI 시간을 여기에 적는다.

| run | 바뀐 범위 | 전체 시간 | 가장 느린 앱 job |
| --- | --- | --- | --- |
| 41025842116 (#485, 바꾸기 전) | 문서 | 9분 42초 | (job 하나) |
