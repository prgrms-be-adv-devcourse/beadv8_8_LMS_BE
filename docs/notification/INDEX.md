# 알림(notification) 문서 INDEX

> 이 문서는 알림센터 문서를 찾아가는 **지도**다. 정책·결정·설계·테스트의 원본이 아니며, 내용을 복사하지 않고 원본 문서의 위치와 읽을 때를 적는다.
> 지갑 문서 INDEX: [`docs/billing/INDEX.md`](../billing/INDEX.md) (같은 목차)

## 1. 도메인 개요와 적용 범위

알림 모듈은 auxiliary-app 안의 업무 모듈로, 알림센터 알림을 만들고 보관하고 실시간으로 전달한다(루트 [`AGENTS.md`](../../AGENTS.md) 3장 모듈 표, [ND-001](decisions.md)·ND-006).

| 포함 | 제외 |
| --- | --- |
| `apps/auxiliary-app/boundedContext/notification` | 채팅 알림(출연 알림 메시지·단체방 SYSTEM 안내·실시간 새 메시지 알림) — `apps/chat-app` 소유 |
| `docs/notification/` 문서, [`docs/api/notification.md`](../api/notification.md) | 챗봇 모듈 |
| 알림이 소비할 Kafka 이벤트 계약 | 다른 모듈의 정책 변경 |

## 2. 현재 구현 상태

**구현이 없는 도메인이다.** `boundedContext/notification/`에는 `AGENTS.md`·`PROGRESS.md`만 있고, Gradle 모듈로 등록되지 않았으며(`settings.gradle.kts`의 `auxiliaryModules`), Java 코드·Flyway·테스트 코드가 없다. 문서 디렉터리가 있다고 기능이 구현된 것이 아니다.

| 무엇 | 어디서 확인하나 |
| --- | --- |
| 코드로 확인한 사실(As-Is)과 설계(확정 To-Be·제안 To-Be·미결) | [`module.md`](module.md) |
| 작업 진행 상태, 구현 작업 X1·N0~N10·M1~M8, 차단 요소 | [`apps/auxiliary-app/boundedContext/notification/PROGRESS.md`](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md) |
| 결정과 미결 | [`decisions.md`](decisions.md) (ND, 10장 U·T) |
| 알림 종류와 수, 발행 이벤트 계약 판정 | [`notification-catalog.md`](notification-catalog.md) |
| 테스트 요구사항과 수 | [`test-spec.md`](test-spec.md) 4장 집계 (수치는 이곳에서만 관리) |
| 실제 실행 결과 | [`regression-log.md`](regression-log.md) |

## 3. 문서 레지스트리

| 문서 | 위치 | 역할 |
| --- | --- | --- |
| 개발 규칙 | [`apps/auxiliary-app/boundedContext/notification/AGENTS.md`](../../apps/auxiliary-app/boundedContext/notification/AGENTS.md) | 알림 모듈 전용 개발 규칙 |
| 진행 상태 | [`apps/auxiliary-app/boundedContext/notification/PROGRESS.md`](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md) | 문서 구축·구현 진행 상태 |
| 도메인 개념 | [`CONTEXT.md`](CONTEXT.md) | 알림센터 개념과 용어 |
| 결정 기록 | [`decisions.md`](decisions.md) | ND 결정·이유·대체 관계·미결 |
| 설계·구현 구조 | [`module.md`](module.md) | As-Is·확정 To-Be·제안 To-Be·미결 |
| 알림 종류 | [`notification-catalog.md`](notification-catalog.md) | 알림 종류별 명세의 유일한 원본 |
| 테스트 요구사항 | [`test-spec.md`](test-spec.md) | `NT-*` 요구사항과 합격 기준 |
| 실행 기록 | [`regression-log.md`](regression-log.md) | 실제로 실행한 테스트 결과 |
| API 계약 | [`docs/api/notification.md`](../api/notification.md) | 알림센터 API (미구현) |

진행 문서는 `docs/notification/`이 아니라 모듈 디렉터리에 있다.

## 4. 문서별 책임과 원본 관계

| 정보 | 원본 |
| --- | --- |
| 업무 규칙 | [`docs/policy.md`](../policy.md). **알림센터 장은 아직 없다.** 그래서 확정된 알림 정책은 지금 `decisions.md`에만 있고, policy.md 반영은 팀 승인 후속이다. `decisions.md`가 팀 규칙보다 우선하는 전역 원본이 되는 것은 아니다 |
| 알림 결정과 이유 | `decisions.md` (ND) |
| 구현 규칙 | 알림 `AGENTS.md` (루트 AGENTS → [auxiliary README](../../apps/auxiliary-app/README.md) → 이 규칙 순서로 상속) |
| 도메인 개념 | `CONTEXT.md` |
| 알림 종류별 명세 | `notification-catalog.md` |
| 코드 동작 | 소스 코드와 설정(지금은 없음), 설명은 `module.md` |
| API 계약 | `docs/api/notification.md` |
| Kafka 계약 | [`kafka.md`](../architecture/infrastructure/kafka.md)와 발행 앱의 실제 이벤트 |
| 테스트 요구사항 / 실행 결과 | `test-spec.md` / `regression-log.md` |
| 진행 상태 | 알림 `PROGRESS.md` |
| 문서 탐색 | 이 문서 |

## 5. 기본 문서 읽기 순서

모든 문서를 매번 읽지 않는다. 6장에서 작업에 맞는 문서만 고른다.

1. 루트 [`AGENTS.md`](../../AGENTS.md)
2. auxiliary-app 공통 규칙 [`apps/auxiliary-app/README.md`](../../apps/auxiliary-app/README.md) (`apps/auxiliary-app/AGENTS.md`는 없다)
3. 알림 [`AGENTS.md`](../../apps/auxiliary-app/boundedContext/notification/AGENTS.md)
4. 이 문서
5. [`docs/policy.md`](../policy.md) (알림센터 장 없음)
6. [`decisions.md`](decisions.md)
7. [`CONTEXT.md`](CONTEXT.md)
8. [`notification-catalog.md`](notification-catalog.md)
9. [`module.md`](module.md)
10. [`docs/api/notification.md`](../api/notification.md)
11. 필요하면 [`test-spec.md`](test-spec.md)
12. 필요하면 [`regression-log.md`](regression-log.md)
13. 알림 [`PROGRESS.md`](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md)

읽는 순서는 탐색 순서일 뿐이고, 먼저 읽은 문서가 정책상 우선하지 않는다(우선순위는 4장).

## 6. 작업 유형별 문서 선택

| 작업 | 읽는 문서 (순서대로, 필요한 절만) | 고치는 문서 |
| --- | --- | --- |
| 기능 개발 | 이 문서 → PROGRESS(차단 요소) → 관련 ND → catalog → module → 7장 계약 → test-spec → 관련 앱 코드 | 코드·테스트(모듈 생성은 auxiliary README 절차), 필요 시 module·test-spec·decisions·PROGRESS |
| 정책 검토 | 이 문서 → policy → decisions → 필요한 CONTEXT → 관련 Issue·계약·코드 | 승인 범위의 decisions(policy는 팀 승인) |
| 버그 수정 | 이 문서 → 코드·테스트 → 관련 정책·계약 → module → 필요 시 regression-log | 코드·테스트, 필요 시 regression-log·PROGRESS |
| 테스트 검증 | 이 문서 → test-spec → 테스트 코드 → 실행 환경·명령 | regression-log |
| 문서 정합성 확인 | 이 문서 → 관련 문서 → 정책·계약 원본 → 코드·설정 → git diff | 관련 문서 |

개발 단계별 읽기·수정 기준은 [지갑 INDEX](../billing/INDEX.md) 6장 표와 같다(INDEX·PROGRESS·module·decisions·test-spec·regression-log의 역할이 같다). 알림에서는 알림 종류를 다룰 때 `notification-catalog.md`를 함께 읽는다. 다른 앱 문서는 실제 연동이 필요할 때만 읽는다.

## 7. 관련 API·Kafka·외부 연동 계약

| 계약 | 문서 | 알림의 역할 |
| --- | --- | --- |
| 알림 API | [`docs/api/notification.md`](../api/notification.md) | 제공 (미구현) |
| 공통 API 규칙 | [`docs/api/conventions.md`](../api/conventions.md) | 따름 |
| Kafka 토픽·key·소비·DLT 규칙 | [`kafka.md`](../architecture/infrastructure/kafka.md) | 소비 (consumer group 이름은 미결 U1) |
| 발행 모듈 이벤트 계약 현황(M1~M7) | [`notification-catalog.md`](notification-catalog.md) 행별 판정, [`module.md`](module.md) 5장 | 소비 예정 |
| 회원 목록(복제본 Backfill 검토) | [`docs/api/internal-members.md`](../api/internal-members.md) | 호출 후보 (미결 U17) |
| 앱 구조·모듈 경계·Flyway·배포 | [`apps/auxiliary-app/README.md`](../../apps/auxiliary-app/README.md), [`final-project-infrastructure.md`](../architecture/final-project-infrastructure.md) | 따름 |
| Gateway·관측성 | [`docs/deploy/gateway.md`](../deploy/gateway.md), [`observability.md`](../architecture/infrastructure/observability.md) | 따름 |
| 원본 설계 | [`docs/architecture/infrastructure/notification.md`](../architecture/infrastructure/notification.md) | 이 문서 체계의 원천(보존) |

`final-project-infrastructure.md`에는 폐기된 notification-app 서술이 남아 있다([ND-903](decisions.md)). 현재 구조는 ND-006·ND-007이다.

## 8. 정책 결정과 미결 사항 찾기

- 결정 하나를 찾을 때: [`decisions.md`](decisions.md)에서 `ND-nnn`. 폐기된 옛 결정은 11장(ND-901~903)이며 현재 정책으로 쓰지 않는다.
- 미결 U·팀 결정 T는 [`decisions.md`](decisions.md) 10장에만 있다. 이 문서에는 본문을 옮기지 않는다.
- 구현 전에 먼저 봐야 할 차단 요소는 알림 [`PROGRESS.md`](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md) 4장에 결정 주체와 함께 있다. 특히 다음은 구현 설계에 바로 닿는다:

| 구분 | 무엇 | 자세히 |
| --- | --- | --- |
| U16 | 예약 취소 이벤트가 생성보다 먼저 도착 | decisions 10장, test-spec `NT-SCH-06` |
| U17 | 받는 사람 복제본 없음, 첫 배포 Backfill | decisions 10장, test-spec `NT-USR-04`·`NT-USR-06`, module 5장 |
| U18 | 채널 인증 승인 판정과 회원 코드의 불일치 | decisions 10장, test-spec `NT-GEN-14` |
| U19 | 저장한 알림 문구·값의 닉네임과 파기 규칙 | decisions 10장, test-spec `NT-USR-07` |
| M1~M7 | 발행 모듈 이벤트 계약(신규 이벤트·계약 보강) | catalog 판정, module 5장, PROGRESS 3장 |

- 발행 이벤트 계약 판정 결과(부분 충족·미충족 수, `AppearanceChangedEvent`가 M3를 대체할 수 없다는 판단)는 [`notification-catalog.md`](notification-catalog.md)와 [`module.md`](module.md) 5장이 원본이다.
- Billing 쪽 팀 결정(T1·T6·T7)은 [지갑 결정 기록](../billing/decisions.md) 10장에 있다.
- 미결은 임의로 정하지 않는다(루트 AGENTS 10·11장, 알림 AGENTS 4장).

## 9. 문서 수정 기준

| 바뀐 것 | 고칠 문서 |
| --- | --- |
| 업무 규칙 | `docs/policy.md` (팀 승인, 알림센터 장 신설 전) |
| 결정 | `decisions.md` (기존 행 상태를 바꾸고 새 `ND-*`를 더한다) |
| 알림 종류·발생 조건·받는 사람 | `notification-catalog.md` |
| 구조·구현 상태 | `module.md` |
| API | `docs/api/notification.md` |
| 테스트 요구사항 | `test-spec.md` (4장 집계 함께) |
| 실행 결과 | `regression-log.md` |
| 진행 상태 | 알림 `PROGRESS.md` (이유·이력 문장은 쓰지 않는다, 알림 AGENTS 7장) |

이 INDEX는 문서가 새로 생기거나 옮겨질 때, 문서 역할·원본 위치·계약 문서 위치·읽는 순서가 바뀔 때, 링크가 깨졌을 때만 고친다. 결정 추가, 코드 변경, 실행 기록 추가, 진행 상태 변경만으로는 고치지 않는다(그 때문에 이 문서의 안내가 틀리게 되면 그 부분만 고친다). 알림 모듈이 Gradle에 등록되고 코드가 생기면 2장을 고친다.

## 10. 중복 정책 방지

- 정책·결정 본문은 `decisions.md` 한 곳에만 둔다. 다른 문서는 `ND-nnn`으로 가리킨다.
- 알림 종류별 명세는 `notification-catalog.md`에만 둔다.
- 테스트 수·알림 종류 수 같은 수치를 이 문서에 복사하지 않는다.
- 채팅 내부 계약을 알림 문서에 복사하지 않는다.

## 11. 문서 충돌 처리

| 유형 | 처리 |
| --- | --- |
| policy와 decisions가 다름 | 지금은 policy에 알림 장이 없다. 장이 생기면 policy가 원본이다. 차이는 근거와 시점을 확인해 보고한다 |
| 결정 상태가 문서마다 다름 | `decisions.md` 상태 칸과 대체 관계(ND-005 → ND-006·007, ND-901~903 폐기)를 따른다 |
| module과 실제 코드·설정이 다름 | 코드·설정을 확인하고 module As-Is를 고친다 |
| 이벤트 계약 문서와 발행 코드가 다름 | 발행 앱 코드와 계약 소유자를 확인한다. 미확정 계약을 확정으로 쓰지 않는다 |
| PROGRESS 완료인데 기능이 없음 | 작업 근거로 진행 상태를 바로잡는다 |
| test-spec 요구사항을 실행 성공으로 표시 | regression-log·실행 증거로 옮긴다 |
| 채팅 정책이 알림 문서에 섞임 | 채팅 소유로 되돌리고 알림센터 측 조건만 남긴다 |

처리 순서는 [지갑 INDEX](../billing/INDEX.md) 11장과 같다. 코드가 있다는 이유만으로 정책을 바꾸지 않고, 폐기된 결정을 새 결정 없이 되살리지 않는다.

## 12. 채팅과 다른 도메인과의 경계

- 알림센터와 채팅 알림은 다른 기능이다([ND-004](decisions.md), decisions 12장, [`CONTEXT.md`](CONTEXT.md)). 채팅의 `NOTIFICATION` 메시지 계약, SYSTEM 안내, FE 문구 규칙은 채팅이 관리하며 알림 문서에 옮기지 않는다.
- 같은 종류 코드 이름(`GROUP_*`)이어도 같은 뜻으로 보지 않는다(U11). 채팅과 알림센터에 같은 사건이 함께 뜨는 문제는 U12, 관련 팀 결정은 T4·T8.
- 알림센터가 채팅 이벤트를 소비할 때는 알림센터 쪽 발생 조건과 받는 사람만 알림 문서에서 관리한다.
- 채팅 문서는 실제 연동이나 옛 설계와의 충돌을 확인할 때만 읽는다: [`notification-data.md`](../../apps/chat-app/docs/contracts/notification-data.md), [`final-design.md`](../../apps/chat-app/docs/final-design.md). 채팅 소유 정책은 채팅 담당 승인 없이 바꾸지 않는다.
- 챗봇 모듈과는 코드로 직접 참조하지 않고 같은 database의 다른 schema를 쓴다(ND-007, auxiliary README).

## 13. 문서 검증과 유지보수

- 문서를 고친 뒤 상대 링크가 실제 파일을 가리키는지, 경로 대소문자가 맞는지 확인한다.
- 문서에 쓴 `ND-*`·`U*`·`T*`가 `decisions.md`에 정의돼 있는지 확인한다.
- `test-spec.md`를 고치면 4장 집계를 함께 고친다. 알림 종류를 고치면 `notification-catalog.md`와 원본 설계의 차이를 확인한다.
- 문서별 갱신 이력은 각 문서(decisions 머리말, module 7장, catalog·test-spec 변경 이력)에 있다. AGENTS·PROGRESS에는 이력을 쓰지 않는다.
