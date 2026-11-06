# 지갑(Billing) 문서 INDEX

> 이 문서는 지갑 문서를 찾아가는 **지도**다. 정책·결정·설계·테스트의 원본이 아니며, 내용을 복사하지 않고 원본 문서의 위치와 읽을 때를 적는다.
> 알림 문서 INDEX: [`docs/notification/INDEX.md`](../notification/INDEX.md) (같은 목차)

## 1. 도메인 개요와 적용 범위

지갑은 HP 충전·환불, 원장, 보류·반환·지급, 환전을 맡는다(루트 [`AGENTS.md`](../../AGENTS.md) 3장 모듈 표).

| 포함 | 제외 |
| --- | --- |
| `apps/billing-app` 코드·테스트·Flyway·k8s | 다른 앱의 업무 상태 판단(강의·출연이 소유) |
| `docs/billing/` 문서 | 다른 모듈의 정책 변경 |
| 지갑이 제공·호출하는 내부 API 계약, 지갑 Kafka 이벤트 | 알림센터 정책([알림 INDEX](../notification/INDEX.md)) |

## 2. 현재 구현 상태

**구현이 있는 도메인이다.** 독립 앱 `billing-app`이 운영 k3s에 배포되어 있다.

| 무엇 | 어디서 확인하나 |
| --- | --- |
| 지금 코드가 어떻게 동작하는가(패키지, 진입점, 상태 전이, 테이블·Flyway, 오류 코드, API) | [`module.md`](module.md). 문서와 코드가 다르면 코드가 맞다 |
| 작업 진행 상태, Issue·PR | [`apps/billing-app/PROGRESS.md`](../../apps/billing-app/PROGRESS.md) |
| 결정 상태(유효·미결·폐기·대체됨·일부 대체됨) | [`decisions.md`](decisions.md) 0장·10장 |
| 테스트 요구사항과 수 | [`test-spec.md`](test-spec.md) 7.2 (수치는 이곳에서만 관리) |
| 실제 실행 결과 | [`regression-log.md`](regression-log.md) |

## 3. 문서 레지스트리

| 문서 | 위치 | 역할 |
| --- | --- | --- |
| 개발 규칙 | [`apps/billing-app/AGENTS.md`](../../apps/billing-app/AGENTS.md) | 지켜야 할 구현 규칙과 BD 참조 |
| 진행 상태 | [`apps/billing-app/PROGRESS.md`](../../apps/billing-app/PROGRESS.md) | 진행 현황, 결정 배경, 남은 일 |
| 작업 맥락 | [`CONTEXT.md`](CONTEXT.md) | 2026-10-29~11-02 작업 세션의 합의 맥락(배경·검토한 대안) |
| 결정 기록 | [`decisions.md`](decisions.md) | `BD-*` 결정·이유·대체 관계·미결 |
| 현재 구현 | [`module.md`](module.md) | 코드 동작 설명 |
| 테스트 요구사항 | [`test-spec.md`](test-spec.md) | `BT-*` 요구사항과 합격 기준 |
| 실행 기록 | [`regression-log.md`](regression-log.md) | 실제로 실행한 테스트 결과 |

## 4. 문서별 책임과 원본 관계

문서 역할의 원본은 [`decisions.md`](decisions.md) 0.5장이다. 여기서는 요약만 둔다.

| 정보 | 원본 |
| --- | --- |
| 업무 규칙(돈·HP, 역할·권한) | [`docs/policy.md`](../policy.md) |
| 지갑 결정과 이유 | `decisions.md` (`BD-*`) |
| 구현 규칙 | billing `AGENTS.md` |
| 코드 동작 | 소스 코드와 설정, 설명은 `module.md` |
| 테스트 요구사항 / 실행 결과 | `test-spec.md` / `regression-log.md` |
| 진행 상태 | billing `PROGRESS.md` |
| 문서 탐색 | 이 문서 |

## 5. 기본 문서 읽기 순서

모든 문서를 매번 읽지 않는다. 6장에서 작업에 맞는 문서만 고른다.

1. 루트 [`AGENTS.md`](../../AGENTS.md)
2. [`apps/billing-app/AGENTS.md`](../../apps/billing-app/AGENTS.md)
3. 이 문서
4. [`apps/billing-app/PROGRESS.md`](../../apps/billing-app/PROGRESS.md)
5. [`module.md`](module.md)
6. 필요한 [`CONTEXT.md`](CONTEXT.md) 항목
7. 관련 `BD-*`([`decisions.md`](decisions.md))와 [`docs/policy.md`](../policy.md)
8. 관련 [`test-spec.md`](test-spec.md) 항목
9. 필요한 API·Kafka 계약(7장)
10. 실제 코드와 테스트

실제 테스트 결과가 필요하면 [`regression-log.md`](regression-log.md)를 더 읽는다. 읽는 순서는 탐색 순서일 뿐이고, 먼저 읽은 문서가 정책상 우선하지 않는다(우선순위는 4장).

## 6. 작업 유형별 문서 선택

| 작업 | 읽는 문서 (순서대로, 필요한 절만) | 고치는 문서 |
| --- | --- | --- |
| 기능 개발 | 이 문서 → PROGRESS → module → 관련 BD·policy → 7장 계약 → test-spec → 코드·테스트 | 코드·테스트, 필요 시 module·test-spec·decisions·PROGRESS |
| 정책 검토 | 이 문서 → policy → decisions → 필요한 CONTEXT → 관련 Issue·계약·코드 | 승인 범위의 policy·decisions |
| 버그 수정 | 이 문서 → 코드·테스트 → 관련 정책·계약 → module → 필요 시 regression-log | 코드·테스트, 필요 시 regression-log·PROGRESS |
| 테스트 검증 | 이 문서 → test-spec → 테스트 코드 → 실행 환경·명령([`docs/dev/testing.md`](../dev/testing.md)) | regression-log |
| 문서 정합성 확인 | 이 문서 → 관련 문서 → 정책·계약 원본 → 코드·설정 → git diff | 관련 문서 |

개발 단계별 읽기·수정 기준:

| 단계 | 주로 읽는 문서 | 수정 대상 |
| --- | --- | --- |
| 기능 시작 | INDEX, PROGRESS, module | 필요 시 PROGRESS |
| 미결 정책 탐색 | decisions, CONTEXT, module, 관련 계약 | 원칙적으로 없음 |
| 정책 결정·정리 | policy, decisions, 관련 계약 | 승인 범위의 policy·decisions |
| 구현 계획 | module, test-spec, decisions, PROGRESS | test-spec, PROGRESS |
| 기능 구현 | AGENTS, 정책·구조·계약·테스트·코드 | 코드·테스트, 필요 시 관련 문서 |
| 테스트 검증 | test-spec, 테스트 코드, 실행 환경 | regression-log |
| 완료 보고 | regression-log, PROGRESS | PROGRESS |
| PR 준비·리뷰 대응 | AGENTS, 변경 코드, 테스트 결과, 정책 | 필요한 코드·테스트·regression-log·PROGRESS |

다른 앱 문서는 실제 연동이 필요할 때만 읽는다.

## 7. 관련 API·Kafka·외부 연동 계약

| 계약 | 문서 | 지갑의 역할 |
| --- | --- | --- |
| 공통 API 규칙 | [`docs/api/conventions.md`](../api/conventions.md) | 따름 |
| 지갑 내부 API(보류·반환·지급·위약금·무효화·대사 조회) | [`docs/api/internal-billing.md`](../api/internal-billing.md) | 제공 |
| 탈퇴 지갑 닫기·다시 열기 | [`docs/api/internal-withdrawals.md`](../api/internal-withdrawals.md) | 제공 |
| 수익 조회(강의·출연) | [`docs/api/internal-earnings.md`](../api/internal-earnings.md) | 호출 |
| 회원 목록(Backfill) | [`docs/api/internal-members.md`](../api/internal-members.md) | 호출 |
| Kafka 토픽·소비·DLT 규칙(`billing.events.v1`, `member.events.v1`) | [`docs/architecture/infrastructure/kafka.md`](../architecture/infrastructure/kafka.md), [ADR 0001](../adr/0001-context-topics-with-mixed-event-types.md), [ADR 0002](../adr/0002-modulith-externalization-without-event-id.md) | 발행·소비 |
| 강의 연동(HP 작업·지급) | [`docs/lecture/integration.md`](../lecture/integration.md) | 호출받음 |
| 출연 연동 | [`docs/appearance/module.md`](../appearance/module.md) | 호출받음 |
| 모듈 연동 일반·영속성 | [`docs/dev/module-integration.md`](../dev/module-integration.md), [`docs/dev/persistence.md`](../dev/persistence.md), [`docs/db-migration.md`](../db-migration.md) | 따름 |
| 배포·보안·Gateway·관측성 | [`docs/deploy/k8s.md`](../deploy/k8s.md), [`docs/deploy/security.md`](../deploy/security.md), [`docs/deploy/gateway.md`](../deploy/gateway.md), [`observability.md`](../architecture/infrastructure/observability.md) | 따름 |

Flyway 마이그레이션은 `apps/billing-app/src/main/resources/db/migration`에 있다. 목록은 [`module.md`](module.md) 8.2.

## 8. 정책 결정과 미결 사항 찾기

- 결정 하나를 찾을 때: [`decisions.md`](decisions.md)에서 `BD-n`. 상태 표기는 0.1장.
- 미결은 [`decisions.md`](decisions.md) 10장에만 있다. 항목 목록과 본문은 그곳에서 읽고, 이 문서에는 옮기지 않는다(목록이 바뀌어도 이 문서는 고치지 않는다).
- 차단 요소와 후속 작업: [`apps/billing-app/PROGRESS.md`](../../apps/billing-app/PROGRESS.md) 7장.
- 미결은 임의로 정하지 않는다(루트 AGENTS 10·11장).

## 9. 문서 수정 기준

| 바뀐 것 | 고칠 문서 |
| --- | --- |
| 업무 규칙 | `docs/policy.md` (팀 승인) |
| 결정 | `decisions.md` (기존 행 상태를 바꾸고 새 `BD-*`를 더한다) |
| 코드 동작·구조 | `module.md` |
| 테스트 요구사항 | `test-spec.md` |
| 실행 결과 | `regression-log.md` |
| 진행 상태 | billing `PROGRESS.md` |

이 INDEX는 문서가 새로 생기거나 옮겨질 때, 문서 역할·원본 위치·계약 문서 위치·읽는 순서가 바뀔 때, 링크가 깨졌을 때만 고친다. 결정 추가, 코드 변경, 실행 기록 추가, 진행 상태 변경만으로는 고치지 않는다(그 때문에 이 문서의 안내가 틀리게 되면 그 부분만 고친다).

## 10. 중복 정책 방지

- 정책·결정 본문은 원본 한 곳에만 둔다. 다른 문서는 `BD-n`이나 "policy.md N장"으로 가리킨다.
- 오류 코드 목록, 테스트 목록, 테이블 수 같은 수치를 이 문서에 복사하지 않는다.
- 실행 결과를 이 문서나 test-spec에 적지 않는다.

## 11. 문서 충돌 처리

| 유형 | 처리 |
| --- | --- |
| policy와 decisions가 다름 | policy가 원본이다. policy는 구현보다 먼저 바뀔 수 있으니 오류로 단정하지 말고 근거와 시점을 확인해 보고한다 |
| 결정 상태가 문서마다 다름 | `decisions.md` 상태 칸과 대체 관계를 따른다 |
| module과 코드가 다름 | 코드를 확인하고 module을 고친다 |
| 계약 문서와 발행·호출 코드가 다름 | 계약 소유자와 확인한다 |
| PROGRESS 완료인데 기능이 없음 | 작업 근거(git·코드)로 진행 상태를 바로잡는다 |
| test-spec에 실행 결과가 적힘 | regression-log·실행 증거로 옮긴다 |
| 이동·삭제된 경로 참조 | 실제 경로로 고친다 |

처리 순서: 충돌 위치 확인 → 결정·계약 확인 → 코드·설정·테스트 확인 → 단순 문서 오류인지 정책 충돌인지 구분 → 정책 충돌이면 사용자·팀에 묻는다 → 승인된 변경을 원본에 반영 → 관련 문서·INDEX 참조 정리 → 검증. 코드가 있다는 이유만으로 정책을 바꾸지 않고, 폐기된 결정을 새 결정 없이 되살리지 않는다.

## 12. 다른 도메인과의 경계

- 다른 모듈은 회원 ID로만 참조하고, 다른 모듈의 테이블·트랜잭션을 쓰지 않는다(루트 AGENTS 3장).
- 강의·출연의 업무 상태(구매 확정, 이의 제기, 출연 확인 등)는 그 모듈이 판단한다. 지갑은 HP 이동과 원장 정합성만 맡는다.
- 지갑 이벤트를 받는 알림센터의 정책은 [알림 INDEX](../notification/INDEX.md)에서 찾는다.

## 13. 문서 검증과 유지보수

- 문서를 고친 뒤 상대 링크가 실제 파일을 가리키는지, 경로 대소문자가 맞는지 확인한다.
- `BD-*` 참조가 1~145 안에 있는지, 결정 상태 표기가 0.1장 다섯 가지인지 확인한다.
- 수치(테스트 수, 오류 코드 수, 테이블 수)는 원본 한 곳에서만 고친다.
- 문서 정리 이력은 [`decisions.md`](decisions.md) 머리말과 각 문서의 갱신 이력에 있다.
