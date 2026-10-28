# 독립 애플리케이션 구조 (#356)

## 이번 변경의 범위

사용자는 구조·빌드·앱 실행 기반까지만 변경하고 서비스 간 통신 전환은 별도 작업으로 남기기로 결정했다. 후속 이슈는 각 담당자가 만든다. 이 작업에서는 이벤트 스키마, Schema Registry, contracts 모듈, Gateway 애플리케이션과 새 deploy 디렉터리를 만들지 않는다.

이 변경은 서비스별 실행 JAR와 컨테이너 이미지를 만드는 기반이다. 기존 기능이 독립 프로세스 사이에서 모두 동작한다는 뜻은 아니다. 서비스 간 호출을 임의의 성공 응답이나 운영용 Fake로 대체하지 않는다.

사용자는 이번 작업에서 PostgreSQL database 하나를 사용하고 앱별 스키마를 분리하기로 결정했다. dev의 `AGENTS.md`는 요청대로 그대로 유지하므로, 그 문서의 모듈별 database 분리 규칙과는 차이가 있다. 팀 문서의 최종 정합성은 별도 합의가 필요하다.

## 디렉터리와 Gradle 프로젝트

```text
hapbang-backend/
├── apps/
│   ├── member-app/
│   ├── billing-app/
│   ├── appearance-app/
│   ├── lecture-app/
│   ├── chat-app/
│   └── customer-support-app/
├── common/
│   ├── kafka/
│   ├── redis/
│   ├── shared/
│   └── test-support/
├── gradle/libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

각 앱은 `Dockerfile`, `build.gradle.kts`, `src/main/java`, `src/main/resources/application.yaml`을 가진다. 예를 들어 회원 앱의 Gradle 프로젝트 경로는 `:member-app`이며 파일 경로는 `apps/member-app`이다. `apps`와 `common`은 모듈을 묶는 디렉터리다.

`global` Gradle 모듈은 없다. 각 앱의 필요한 공통 코드는 `com.hapbang.<업무>.global`에 둔다. 다른 앱의 global 패키지를 의존하지 않는다. 응답 형식과 오류 처리 규칙은 [API 컨벤션](../api/conventions.md)을 따른다.

앱은 필요한 common 라이브러리만 의존한다. 앱 사이의 Gradle 의존은 금지한다. `common/kafka`와 `common/redis`는 클라이언트 설정 라이브러리이며 Kafka·Redis 서버를 실행하는 모듈이 아니다. 두 모듈 모두 모든 앱이 루트 Gradle로 의존하며 자동 설정으로 바로 쓴다. 연결·직렬화는 각 앱의 `spring.kafka.*`, `spring.data.redis.*`로 준다.

기존 `shared`의 포트·이벤트 DTO는 이번에 `common/shared`로 위치만 옮겼다. 서비스별 DTO와 네트워크 계약으로 바꾸는 작업은 통신 전환 범위에 속한다. 새 이벤트 스키마나 공유 계약 모듈을 추가한 것은 아니다.

## 빌드와 컨테이너 이미지

```bash
./gradlew verifyModuleStructure :member-app:check :member-app:bootJar

docker build -f apps/member-app/Dockerfile apps/member-app/build/libs
```

실행 JAR는 `apps/<앱>/build/libs/app.jar`다. Dockerfile은 CI에서 검증한 JAR를 이미지에 복사한다. 빌드 컨텍스트는 해당 JAR 디렉터리이며 소스나 `.env`를 이미지에 넣지 않는다.

루트 Gradle에서 Java·Spring Boot 버전과 공통 빌드 규칙을 관리한다. `verifyModuleStructure`는 앱 사이의 실행 의존성, common의 앱 의존성, 실행 진입점·설정·Dockerfile 누락을 검사한다.

## CI와 배포 경계

워크플로는 dev 버전을 그대로 유지한다. 기존 CI는 `:apps:bootJar`, `apps/build/libs/app.jar`, 단일 앱 Dockerfile을 사용하므로 변경된 구조에서는 실패한다. 기존 dev 배포도 단일 앱 이미지를 전제로 한다.

구조 변경은 별도 통합 브랜치에서 인프라 담당자의 앱별 CI/CD 수정과 검증을 거친 뒤 dev에 반영한다. 현재 워크플로가 앱별 이미지를 발행하거나 앱별 배포를 수행한다고 가정하지 않는다.

Kubernetes 배포, Gateway와 실제 서비스 전환은 별도 작업이다. `docker/`와 기존 배포 문서는 기존 환경의 기록으로 남겨 두며, 새 앱 이미지에 기존 단일 app 배포 절차를 적용하면 안 된다.

## 아직 해결하지 않은 독립 실행 제약

| 영역 | 현재 남아 있는 제약 | 후속 작업 |
| --- | --- | --- |
| 출연·강의 → billing | `BillingPort` 구현은 billing 프로세스에만 있다. 소비 앱은 필요한 빈이 없어 시작하지 못할 수 있다. | 내부 HTTP API와 멱등성·실패 복구 계약으로 전환 |
| 고객센터 → 강의 | `LectureObjectionPort` 구현은 강의 프로세스에만 있다. | 이의 제기 접수의 동기 API와 상태 복구 설계 |
| 회원 복제·지갑 생성·알림 | Spring Event는 다른 JVM으로 전달되지 않는다. | 이벤트 전송·재처리·중복 처리 구현 |
| 인증 | 기존 JDBC 세션 principal에 회원 앱의 클래스가 포함되어 있다. DB 세션 공유만으로 다른 앱 인증을 보장하지 못한다. | 서비스 간 인증 데이터와 세션/JWT 전환 |
| DB 이력 | 앱별 Flyway·이벤트 발행 이력을 분리한다. 기존 공용 이력 DB의 전환은 별도 계획이 필요하다. | 기존 데이터·마이그레이션 이력을 보존하는 전환 절차 검증 |
| 통합 테스트 | 단일 JVM에서 모든 모듈을 조립하던 테스트는 독립 서비스 동작을 검증하지 못한다. | 내부 API·이벤트·인증 경계를 대상으로 통합 테스트 재작성 |

보류·반환·지급의 기존 업무 정책은 변경하지 않는다. 특히 같은 트랜잭션으로 처리하던 호출을 HTTP로 바꿀 때는 통신 실패를 성공으로 간주하거나 무조건 재시도하지 않는다.

## 기존 테스트 보존

기존 모듈 테스트는 각 앱의 `src/test`로 옮긴다. 기존 최상위 `apps`의 단일 프로세스 통합 테스트는 현재 활성 테스트에 포함되지 않는다. 이전 시나리오는 Git 이력에서 확인하고 서비스 간 HTTP·이벤트·인증 경계에 맞게 재작성해야 한다. 현재 `check` 통과를 전체 분산 거래 시나리오 통과로 해석하지 않는다.

## 이번 변경의 실행 검증

새 PostgreSQL 인스턴스에서 실제 실행 JAR를 별도 프로세스로 기동해 확인했다. 회원·billing·채팅 앱은 `/actuator/health`가 UP으로 응답했다. 출연·강의 앱은 `BillingPort`, 고객센터 앱은 `LectureObjectionPort` 빈이 없어 기동에 실패했다. 이는 위의 미완료 서비스 간 통신 범위이며 성공 처리로 대체하지 않았다. 여섯 앱의 Docker 이미지 빌드도 확인했다.

이 결과는 새 DB에서 앱별 기동과 이미지 생성을 확인한 것이다. 기존 운영 DB의 마이그레이션 전환, 앱 사이의 로그인 공유와 HP 거래 전체 시나리오를 검증한 결과는 아니다.

## 검증 결과

- `./gradlew verifyModuleStructure check bootJar` 통과. 활성 테스트 2,939개, 실패·오류·건너뜀 0개.
- 앱 6개의 Docker 이미지 빌드 통과. 각 실행 JAR에 다른 업무 앱의 JAR가 포함되지 않는 것을 확인했다.
- 임시 PostgreSQL에서 실제 JAR 실행: 회원·billing·채팅은 health `UP`; 출연·강의·고객센터는 위 표의 미구현 서비스 포트로 기동이 중단된다.
- 기존 모놀리스 통합 테스트는 활성 테스트 수에 포함하지 않는다. 이벤트 전달·분산 거래·서비스 간 인증·운영 DB 이력 전환 검증은 이번 범위에 포함하지 않는다.
