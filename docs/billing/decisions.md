# 지갑(Billing) 모듈 — 결정 기록

> 문서 지도: [현재 구현](module.md) · **[결정 기록](decisions.md)** · [테스트 검증 기준](test-spec.md) · [회귀 실행 기록](regression-log.md)
>
> - 이 문서는 Billing 설계 결정과 그 이유를 `BD-*` 번호로 모은다. 번호는 바꾸거나 재사용하지 않는다.
> - 원천은 작업 트리의 `apps/billing-app/PROGRESS.md` 3·4·5·7장과 `apps/billing-app/AGENTS.md`다(2026-10-29). 결정을 새로 만들거나 폐기하지 않았다. 원문에 적힌 상태를 옮겼고, 원문에 이유가 없으면 "기록 없음"으로 적었다.
> - 결정을 바꾸려면 팀과 먼저 합의하고, 기존 행의 상태를 바꾼 뒤 새 번호를 더한다.
> - 2026-10-29 dev da834b1 반영: Billing 결정은 바뀌지 않았다. #369 최종 인프라 결정이 재검토 대상(11장)에 주는 방향만 더했다.
> - 2026-10-30 PROGRESS 3.21(#273) 반영: BD-108을 결정했고 BD-111~BD-113을 더했다(9장). BD-28·BD-54는 일부 대체됐다. 이제 결정 원본은 이 문서이고, `PROGRESS.md` 3장은 결정 배경만 두고 각 절에서 `BD-*`를 가리킨다. 출처 칸의 `P`는 `PROGRESS.md`, `A`는 `apps/billing-app/AGENTS.md`, `C`는 [`CONTEXT.md`](CONTEXT.md)의 장 번호다.
> - 2026-11-02 #562 반영: 지갑의 나가는 호출(강의·출연·회원 내부 API, TossPayments)에 circuit breaker를 둔다(BD-134).
> - 2026-11-03 #577 반영: 서비스 간 HP 대사의 주체는 각 업무 모듈이고, 지갑은 보류·지급 조회 내부 API만 준다(BD-137).
> - 2026-11-03 #598 반영: 배치 5개와 회원 Backfill을 PostgreSQL advisory lock으로 pod 사이에서 한 곳만 실행한다(BD-138). `module.md` 한계 L4·L5를 해소했다.
> - 2026-11-03 #627 반영: 지갑 사용자 API 14개에 회원별 요청 한도를 둔다(BD-140). 넘으면 429 `TOO_MANY_REQUESTS` + `Retry-After`. `module.md`의 rate limit TBD를 해소했다.
> - 2026-11-03 #607 반영: 최근 72시간 안에 바뀐 지갑의 잔액 대사를 매일 자동 실행한다(BD-139). BD-20의 "Scheduler는 없다"와 BD-138 (3)의 락 번호 규칙을 대체했다. `module.md` 한계 L6을 일부 해소했다.
> - 2026-11-02 #564 반영: `V202610301400` 주석이 #497 이후와 맞지 않음을 BD-124·BD-130에 적었다. capture를 내부 API로 노출하지 않는다(BD-135). 회원 이벤트 DLT는 운영 절차와 Backfill로 복구한다(BD-136).
> - 2026-11-02 #552 반영: 회원 Backfill이 관리자가 아닌 회원의 지갑도 만든다(BD-132). Hikari pool 10 명시·관리 포트·첫 배포 Backfill 호출 경로를 정했다(BD-133). BD-104는 BD-132로 대체됐다.
> - 2026-11-02 #537 반영: 사용자 API를 Gateway 사용자 헤더로 인증하고, 충전·환불·환전 신청은 지갑 회원(복제본)도 확인한다(BD-131). 11장 BD-3·BD-102·BD-103 재검토 항목을 해소했다.
> - 2026-11-02 #497 반영: 지갑 이벤트를 Kafka로 발행한다(BD-130). BD-124는 대체됐다.
> - 2026-11-01 #494 반영: shared Command를 billing 내부 Command로 바꿔 넘긴다(BD-129).
> - 2026-10-31 #486 반영: `BillingPort`·`BillingPortAdapter`를 지웠다(BD-128). BD-51은 일부 대체, BD-52는 폐기됐다.
> - 2026-10-30 #479 반영: 보류 무효화 API를 더했다(BD-125·BD-126, 미결 BD-127).
> - 2026-10-30 #471 반영: Modulith 이벤트 기록 설정·테이블을 지웠다(BD-124).
> - 2026-10-30 #430 반영: 회원 이벤트를 Kafka로 받도록 구현했다. BD-6은 BD-123으로 대체됐고, BD-2·BD-103의 JVM 재검토를 해소했다.
> - 2026-10-30 #428 반영: Kafka 소비 규칙(#339)에 맞춰 회원 이벤트 멱등을 `eventId` inbox에서 자연 키로 고쳤다(BD-123, 11장 BD-2·BD-6·BD-103 행 해소).
> - 2026-10-30 #336·#453 반영: 플랫폼 지갑·환전 수수료 수입(BD-114~BD-118, 8장), 환불·환전 1 HP 단위와 송금액 버림(BD-119~BD-122, 7·8장). BD-87은 금액 규칙, BD-95는 수수료 계산이 대체됐다. 정책 원문은 `CONTEXT.md` 3·4장(출처 C3.x·C4.x)이다.

## 0. 읽는 법

### 0.1 상태

| 상태 | 뜻 |
| --- | --- |
| 유효 | 지금도 지켜야 하는 결정 |
| 대체됨 → BD-n | 다른 결정으로 바뀌었다. 기록으로만 남긴다 |
| 폐기 | 구현을 없앴다. 기록으로만 남긴다 |
| 미결 | 팀 결정 전이다. 임의로 정하지 않는다(루트 AGENTS 10·11장) |
| 확인 필요 | 문서상 유효지만 현재 코드와 다르다. 팀이 확인한 뒤 상태를 고친다 |

### 0.2 JVM 재검토

`JVM` 열은 그 결정이 단일 JVM을 전제로 하는지 표시한다. 전제란 같은 JVM 안의 Java 호출, 호출한 쪽 DB 트랜잭션 참여, 같은 프로세스 안의 Spring 이벤트, 공유 `global` 모듈을 말한다.

| 값 | 뜻 |
| --- | --- |
| — | 전제하지 않는다 |
| **재검토** | 독립 JVM 구조에서는 그대로 성립하지 않는다. 9장에 모았다 |
| 문구 | 내용은 유효하고 경로·모듈 이름만 바뀌었다 (예: `global` 모듈 → 앱 내부 `global` 패키지) |

### 0.3 decisions.md와 `docs/adr/`의 경계

| 기록 위치 | 대상 | 예 |
| --- | --- | --- |
| 이 문서 (`BD-*`) | Billing 안에서 닫히는 결정 | 락 순서, 멱등키 형식, 배분 순서, 오류 코드 |
| `docs/adr/NNNN-*.md` | 여러 모듈에 걸치고 되돌리기 어려운 결정 | BillingPort를 HTTP로 바꾸는 방식, 이벤트 전달 보장, database 분리 |

- `docs/adr/`는 아직 없다. `/grill-with-docs`(domain-modeling)가 결정이 실제로 정해질 때 만든다 (`docs/agents/domain.md`).
- ADR이 생기면 관련 `BD-*` 행의 "출처" 칸에 `→ ADR-000N` 링크만 더한다. 내용은 복사하지 않는다.

### 0.4 HEAD와 작업 트리의 차이

| 파일 | HEAD (5a4dc1a) | 작업 트리 |
| --- | --- | --- |
| `PROGRESS.md` | 마지막 갱신 2026-10-13(#134). 3.1~3.9까지 있고, 위약금 30%를 Billing이 계산한다 | 마지막 갱신 2026-10-16(#294). 3.10~3.20이 더해졌고, 위약금 계산이 출연 책임으로 바뀌었다(#145) |
| `AGENTS.md` | 8.0·8.1·10.1·10.2장, 9장 연동 계약 상세가 없다 | 위 장이 더해졌다 (+130줄) |

이 문서는 작업 트리를 기준으로 했다. HEAD만 보면 BD-46(30% 계산)이 유효한 것처럼 읽힌다.

## 1. 지갑 (#42)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-1 | 회원당 지갑 1개. `uk_wallet_user_id`가 최종 보장한다 | 기록 없음 | 유효 | P3.1 | — |
| BD-2 | 지갑은 가입 이벤트로 만들고, `GET /wallets/me`에서도 없으면 만든다. 두 경로 모두 `WalletCreateUseCase.getOrCreate`를 쓴다 | 이벤트는 커밋 뒤 비동기라 늦을 수 있고, Billing 이전 가입자에게는 이벤트가 없다 | 유효 | P3.1 | — (#430: 가입 이벤트를 Kafka로 받는다, BD-123) |
| BD-3 | ADMIN은 지갑이 없다. 이벤트에서 건너뛰고, 조회 API는 `@PreAuthorize`로 403. 가입 리스너는 역할 검사 없는 `getOrCreateWallet`을 쓴다 | 관리자는 HP를 쓰지 않는다 | 유효 | P3.1, A14 | — |
| BD-4 | 지갑 생성은 `REQUIRES_NEW`로 하고, 롤백이 끝난 뒤 바깥에서 다시 조회한다 | INSERT 실패로 오류 상태가 된 PostgreSQL 트랜잭션에서 재조회하지 않기 위해 | 유효 | P3.1 | — |
| BD-5 | 중복 판별은 SQLState `23505`와 제약 이름 `uk_wallet_user_id`가 둘 다 맞을 때만 한다 | 메시지 파싱은 서버 언어(`lc_messages`)가 바뀌면 깨진다 | 유효 | P3.1 | — |
| BD-6 | 리스너는 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`다. 실패하면 발행 기록이 미완료로 남아 재시작 때 다시 처리된다 | 리스너 트랜잭션까지 있으면 이벤트 하나가 커넥션 2개를 잡는다 | 대체됨 → BD-123 (#430: Kafka `@KafkaListener`, 실패는 블로킹 재시도 뒤 그룹별 DLT) | P3.1 | — |
| BD-123 | 회원 이벤트(`member.events.v1`)는 목적별 소비 그룹 둘로 받는다. `billing-app.member-replica`: 가입·변경 → `billing_user` Upsert, 멱등은 `(user.id, user.version)` 버전 비교(BD-103). `billing-app.wallet-creation`: 가입 → `getOrCreate`(ADMIN은 건너뜀, BD-3), 멱등은 회원 ID UNIQUE(BD-1·BD-5). 두 그룹 모두 별도 inbox·처리 기록 테이블을 두지 않는다. DLT는 `member.events.v1.<그룹>.DLT` | `eventId`가 없다(ADR 0002). 업무 행 자체가 자연 키 멱등 기록이라 반영과 같은 커밋에 남는다(kafka.md "멱등은 업무 자연 키"). 같은 그룹에 리스너 둘을 두면 partition을 나눠 가져 각자 일부만 받는다 | 유효 (#430에서 구현) | #428, kafka.md | — |
| BD-130 | 지갑은 HP 이동 결과(충전 완료, 지급 완료(정상·위약금), 환불 완료(전부·일부), 환전 완료·거절·송금 실패(시도별)·최종 실패)를 `billing.events.v1`에 발행한다. Modulith externalization(발행 기록 = outbox, Modulith 스키마 초기화, archive)으로, 끝 상태로 바꾸는 같은 트랜잭션에서 발행하고 매분 재발행한다. key는 `point-charge`·`settlement`·`point-refund-request`·`exchange` 접두어 + 대상 ID, 레코드는 `shared.billing.event`, `PayoutType`은 shared로 옮긴다. payload에 계좌·결제키·사유 원문은 없다 | 알림·감사 projection이 지갑을 동기 호출하지 않고 완료된 사실을 받게 한다. 팀 규칙(kafka.md, ADR 0001·0002)과 출연 발행 선례를 따른다. 송금 실패는 한 환전에서 여러 번 날 수 있어 시도 ID를 멱등 키로 한다 | 유효. `V202610301400`의 첫 줄 주석("지갑은 Spring Event를 발행하지도 받지도 않는다")은 #471 당시 기준이고, 지금은 Modulith 스키마 초기화가 두 테이블을 다시 만든다. 적용된 Flyway는 주석도 체크섬에 들어가 고치지 않는다(#564) | C9, #497 | — |
| BD-124 | 지갑은 Modulith 이벤트 기록을 두지 않는다. `spring.modulith.events.*` 설정을 지우고, Flyway `V202610301400`으로 `billing.event_publication`·`event_publication_archive`를 지운다(남은 기록은 확인하지 않고 함께 삭제). 앱·테스트 설정에서 `JdbcEventPublicationAutoConfiguration`·`EventPublicationAutoConfiguration`를 뺀다 | 지갑은 Spring Event를 발행하지도 받지도 않는다(#430). 테이블만 지우면 종료 때 이벤트 기록 레지스트리가 테이블을 조회하다 실패하고(확인함), JDBC 자동 설정만 빼면 레지스트리를 쓰는 설정이 기동에 실패해 둘 다 뺐다. 루트 Gradle은 바꾸지 않는다. 이벤트를 발행하게 되면(우선순위 6) 다시 만든다 | 대체됨 → BD-130 (#497에서 발행 기록·자동 설정을 다시 켬) | C5, #471. 이 결정으로 만든 `V202610301400`은 그대로 두며, 주석 정정은 BD-130 참고(#564) | — |
| BD-136 | 회원 이벤트 DLT(`member.events.v1.billing-app.{member-replica,wallet-creation}.DLT`)는 원래 리스너에 다시 넣는 도구를 두지 않는다. 운영 절차로 복구한다: kafka CLI로 DLT 메시지·실패 원인(헤더 `kafka_dlt-*`)을 확인 → 원인 수정 → 관리자 회원 Backfill로 지갑 회원·지갑을 다시 맞춘다. DLT 메시지는 지우지 않고 보존 기간(7일)이 지나 사라지게 둔다(`docs/deploy/k8s.md` "billing-app 회원 이벤트 DLT 복구") | 두 그룹이 만드는 데이터(지갑 회원·지갑)는 Backfill이 회원 앱의 현재 정보로 다시 맞추고 다시 실행해도 같다(BD-123·BD-132, #552 k3d 검증). 재처리 도구는 consumer·offset·중복 재처리를 따로 다뤄야 해 범위가 크다. 옛 이벤트를 다시 넣는 것보다 현재 정보로 맞추는 쪽이 늦게 온 정보로 덮을 위험도 없다 | 유효 (2026-11-02 사용자 결정, #564 로컬 k3d로 절차 확인) | #564 | — |
| BD-137 | 서비스 간 HP 대사(강의 주문 항목·출연 요청서·모집글·계약 ↔ 지갑 보류·지급)의 주체는 각 업무 모듈이다. 지갑은 조회 내부 API 둘만 준다: 참조 대상별 조회(`POST /api/v1/internal/billing/holds/query`, 1~500개, 요청 순서·없는 ID도 빈 기록)와 참조 유형별로 기간 안에 바뀐 보류 목록(`GET /api/v1/internal/billing/holds`, 마지막 변경 시각·ID 키셋, Flyway `V202611031200` 인덱스 `idx_point_hold_2`). 목록 조회는 최근 10초(확정 대기 시간 `hapbang.billing.hold-query.settle-window`) 안에 바뀐 보류를 주지 않는다. 커서(`nextUpdatedAfter`·`nextAfterId`)는 마지막 페이지에서도 주고 `hasMore`로 끝을 알린다(빈 페이지면 요청한 커서 그대로). 호출자는 마지막 커서를 저장해 다음 대사를 이어 시작한다. 지갑 앱 인스턴스 하나를 전제로 한다. 응답은 보류 상태·보류한 회원·**충전 HP·수익 HP별 금액과 합계**(최초·남은 보류는 보류 행, 반환·지급·확정 차감은 보류 상세 합)·무효화 기록·그 보류의 지급 건 전부다. 지갑·보류·원장 ID와 충전 건별 배분은 담지 않는다. 지갑 Enum은 같은 이름의 shared Enum(`BillingHoldStatus`·`BillingSettlementStatus`)으로 준다 | 확정 대기 시간: `updated_at`은 커밋이 아니라 플러시 시점의 앱 시각이라, 늦게 커밋된 보류가 지나간 커서 뒤에 끼어 빠질 수 있다(PR 리뷰). 시각 기록 뒤 가장 긴 대기는 같은 참조 대상 보류의 UNIQUE 대기이고 `lock_timeout` 3초에서 끝나므로 10초로 둔다. 호출자 규칙(커서를 되돌려 겹쳐 읽기)은 두 모듈이 각자 지켜야 해 쓰지 않았다. 마지막 페이지에서 커서를 null로 주면 호출자가 이어 시작할 위치를 잃어 매번 처음부터 읽게 되므로 항상 주고 `hasMore`를 더했다(PR 2차 리뷰). 기준 시각과 `updated_at`이 같은 앱 시각이라 지금(replica 1)은 시각 차이가 없고, 인스턴스를 늘리면 그 차이만큼 여유가 준다. 업무 상태 규칙은 그 상태 기계를 가진 모듈이 판단한다(A9 "거래 상태의 확정·취소 판단은 각 업무 도메인"). 지갑이 주체면 강의·출연 상태를 알아야 해 결합이 생긴다. 보류 상세는 반환·지급마다 배분 누적값과 대조(재생)한 값이라 원장을 다시 합산하지 않는다. HP 종류별 금액은 다른 내부 API 원칙(합계만)과 다르지만 대사에서 쓰도록 공개한다 | 유효 (2026-11-02 대사 주체, 2026-11-03 조회 세부 사용자 결정) | #577 | — |

## 2. 원장·멱등성·역분개·대사 (#57)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-7 | 잔액은 `LedgerRecordUseCase.record()`만 바꾼다. 원장 INSERT와 지갑 변경은 한 트랜잭션이다. ArchUnit `WalletApplyLedgerAccessTest`가 강제한다 | 기록 없음 | 유효 | P3.2, A6 | — |
| BD-8 | 재반영 방지는 `@Transient pendingApplication`으로 한다 | IDENTITY 전략은 INSERT 시점에 ID가 생겨 ID로 판단할 수 없다 | 유효 | P3.2 | — |
| BD-9 | 금융 거래 중에는 지갑을 자동 생성하지 않는다(`WALLET_NOT_FOUND`) | 기록 없음 | 유효 | P3.2, P4 | — |
| BD-10 | 지갑은 ID 오름차순으로 `PESSIMISTIC_WRITE` 잠근다 | 교착 방지 | 유효 | P3.2, A6 | — |
| BD-11 | 락 전에 flush, 락 후에 refresh한다 | 락 조회가 영속성 컨텍스트를 갱신하지 않아 생기는 lost update 방지, refresh가 미반영 변경을 지우지 않게 | 유효 | P3.2 | **재검토** (호출한 쪽 영속성 컨텍스트까지 flush하는 전제) |
| BD-12 | 원장 금액은 부호 있는 값이고 0은 금지한다. `Math.addExact`(`BALANCE_OVERFLOW`), 음수 잔액은 `INSUFFICIENT_BALANCE` | 기록 없음 | 유효 | P3.2 | — |
| BD-13 | 버킷 이동은 같은 작업 키로 `-금액`, `+금액` 두 줄이다 | 기록 없음 | 유효 | P3.2 | — |
| BD-14 | 원장 멱등키는 `{operationKey}:{줄번호}`(1부터)다. `operation_key` 단독 UNIQUE는 걸지 않는다 | 기록 없음 | 유효 | P3.3, A7 | — |
| BD-15 | 재요청 판정: 지갑 락 뒤 작업 키로 조회하고, 줄마다 내용이 같으면 `replayed`, 다르면 `IDEMPOTENCY_KEY_CONFLICT`. 시각·설명은 비교하지 않는다 | 기록 없음 | 유효 | P3.3 | — |
| BD-16 | 겹치지 않는 지갑의 같은 키는 UNIQUE 위반(23505 + 제약 이름)을 `IDEMPOTENCY_KEY_CONFLICT`로 바꾼다 | 락으로 직렬화되지 않는다 | 유효 | P3.3 | — |
| BD-17 | 작업 상태 테이블은 만들지 않는다. 업무 테이블의 멱등키와 호출한 쪽의 결정론적 키가 맡는다 | 기록 없음 | 유효 | P3.3 | — |
| BD-18 | 역분개: `originalLedgerId` 필수, 같은 지갑·종류·버킷·반대 부호, 누적 절댓값 ≤ 원본, 역분개의 역분개 금지, 원본 지갑 락으로 직렬화 | 기록 없음 | 유효 (main 코드에 호출부 없음, module.md 3.2) | P3.4 | — |
| BD-19 | 거래 취소·환불·보상은 각 업무 서비스가 맡는다. 원장 계층은 줄 단위 정합성만 책임진다 | 기록 없음 | 유효 | P3.4 | — |
| BD-20 | 잔액 대사는 `readOnly`, `REPEATABLE_READ`로 하고 불일치를 고치지 않는다. 관리자 API·Scheduler는 없다 | 같은 스냅숏에서 읽어 동시 거래 중 오탐을 막는다 | **일부 대체됨 → BD-139** (Scheduler 없음 부분. 고치지 않음·`REPEATABLE_READ`·관리자 API 없음은 유효) | P3.5 | — |

## 3. 보류 생성·반환 (#78, #100, #141)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-21 | 참조 대상당 보류 1건. 키 `HOLD:{referenceType}:{referenceId}`는 Billing이 만든다. `uk_point_hold_idempotency_key`가 보장한다 | 참조 유형을 Enum 이름으로만 써서 같은 대상의 중복 보류를 막는다 | 유효 | P3.6, A9 | — |
| BD-22 | 보류 단위: 강의 `LECTURE_ORDER_ITEM`(#100에서 변경), 출연 요청 `APPEARANCE_REQUEST`, 모집 `APPEARANCE_RECRUIT`. `LECTURE_ORDER`는 Enum·CHECK에 남긴다 | 항목별 개별 취소 | 유효 | P3.6, P3.7 | — |
| BD-23 | 보류 재요청: 같은 내용이면 처음 결과, 다르면 `IDEMPOTENCY_KEY_CONFLICT`, 비정상 상태면 `HOLD_STATE_INCONSISTENT` | 기록 없음 | 유효 | P3.6 | — |
| BD-24 | 수익 HP 먼저, 부족분만 충전 HP. 충전 HP는 COMPLETED·PARTIALLY_REFUNDED이고 가용 원금이 있는 건을 `completed_at, id` 순(FIFO)으로 쓴다. 배분은 `POINT_CHARGE_USAGE`에 기록한다 | 기록 없음 (policy.md 1장) | 유효 | P3.6, A8 | — |
| BD-25 | 배분 전 가용 원금 합 = `charge_balance_hp` 대조. 다르면 `CHARGE_PRINCIPAL_MISMATCH` | 기록 없음 | 유효 | P3.6 | — |
| BD-26 | 락 순서는 지갑 → 충전 건 | 교착이 없다 | 유효 | P3.6 | — |
| BD-27 | 조회 순서는 원장 → 보류 | READ COMMITTED에서 먼저 읽은 원장이 보이면 나중에 읽는 보류도 보인다. 반대 순서는 정상 경쟁을 500으로 잘못 분류했다 | 유효 (#78의 보류 → 원장 순서를 대체) | P3.6, A7, #141 | — |
| BD-28 | 잔액 부족은 예외로 알린다. `HoldFacade`에는 `@PreAuthorize`가 없고 공개 HTTP API도 없다 | 강의·출연이 결제자를 확인한 뒤 부르는 내부 기능 | **일부 대체됨 → BD-112** (`@PreAuthorize`·공개 HTTP API 없음 부분. 잔액 부족 예외는 유효) | P3.6 | 해소 (BD-112) |
| BD-29 | 반환은 충전 HP 먼저, 부족분만 수익 HP. 충전 원금은 최초 배분의 역순(LIFO)으로 복원한다 | 역순 규칙을 바꾸면 기존 상세를 재구성할 수 없다 | 유효 | P3.7, A8 | — |
| BD-30 | 반환 키 `RELEASE:{holdId}:{releaseRequestId}`. 요청 ID는 `[A-Za-z0-9_-]{1,200}`이고, 재전송 때 같은 값을 쓴다(UUID 금지) | `:`는 구분자, 원장 멱등키 255자 제한 | 유효 | P3.7 | — |
| BD-31 | 반환 요청 ID는 보류 안에서만 고유하다 | 키에 holdId가 있고, 전역 고유성을 요구하는 업무가 없다 | 유효 (1차 구현 대체) | P3.7 | — |
| BD-32 | 부분 반환 초과는 `RELEASE_AMOUNT_EXCEEDS_HELD`, `full`은 처리 시점의 남은 보류 전부. 같은 키는 처음 결과를 돌려준다 | 기록 없음 | 유효 | P3.7 | — |
| BD-33 | 스키마는 바꾸지 않고, 처리마다 상세를 ID 순서로 재생(`ChargeUsageHistory`)해 배분 누적값과 대조한다 | 상세와 배분 사이에 연결이 없다 | 유효 | P3.7 | — |
| BD-34 | 보류 상태: HELD → PARTIALLY_PROCESSED → RELEASED / PAID / MIXED(+CAPTURED, BD-83). 닫힌 보류는 `HOLD_ALREADY_CLOSED` | 기록 없음 | 유효 | P3.7, P3.9 | — |
| BD-35 | 반환 락 순서: 지갑 → 보류 → 충전 건(ID 오름차순) | 보류 생성 순서와 충돌하지 않는다 | 유효 | P3.7 | — |
| BD-125 | 보류 무효화 내부 API `POST /hold-voids`(`userId`·`referenceType`·`referenceId`). 남은 보류는 전부 반환(반환 요청 ID `VOID`) → `RELEASED`, 끝난 보류 → `ALREADY_CLOSED`, 보류 없음 → `VOIDED`. 세 경우 모두 `billing.hold_void`(참조 대상 UNIQUE)에 남기고, 다시 보내면 처음 결과를 `replayed`로 돌려준다. 지갑 락 아래에서 처리한다 | 반환만으로는 보류가 없는 것과 아직 도착하지 않은 것을 구분할 수 없어, 결과 불명 보류의 보상 뒤에 늦은 보류가 남는다. 보류 테이블·불변식을 바꾸지 않으려고 별도 테이블로 둔다 | 유효 | C6, #479 | — |
| BD-126 | 무효화 기록이 있는 참조 대상의 보류는 409 `HOLD_VOIDED`로 거절한다. 지갑 락 뒤, 기존 보류 재요청 확인보다 먼저 본다 | 무효화 전에 만든 보류의 재요청에 성공을 돌려주면 호출한 쪽이 반환된 보류를 살아 있다고 오해한다. 같은 지갑 락이라 보류와 무효화가 동시에 와도 "보류가 남은 채 무효화됨"이 없다 | 유효 | C6, #479 | — |

## 4. JPA·Enum (#116)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-36 | 애그리거트 사이는 ID 필드로 참조하고, 애그리거트 안만 연관관계를 둔다. DB FK·UNIQUE·CHECK는 그대로 둔다 | 멘토 리뷰, 추가 SELECT·N+1 감소 | 유효 | P3.8, A4 | — |
| BD-37 | Enum 상수마다 Javadoc을 달고 `description` 필드는 두지 않는다. 상수 이름 = DB 저장값 | 읽는 곳이 없다 | 유효 | P3.8, A4 | — |
| BD-38 | QueryDSL은 도입하지 않는다. 재검토 기준 4개(동적 조회, JOIN·프로젝션 증가, 다른 모듈 선도입, 호환 확인) | JPQL 4개뿐이라 이점이 작다 | 유효 (팀 결정 대기, 11장) | P3.8, P7 | — |

## 5. 지급·위약금 (#134, #145)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-39 | 지급은 수익 HP 보류 먼저, 충전 원금 FIFO 소진(돌려주지 않음). 받는 쪽은 출처와 무관하게 수익 HP 가용. 초과는 `PAYOUT_AMOUNT_EXCEEDS_HELD` | 기록 없음 | 유효 | P3.9, A8 | — |
| BD-40 | 내부 HP 지급은 `SETTLEMENT`에 COMPLETED로 즉시 INSERT한다. 현금 송금은 `EXCHANGE_PAYOUT`이다 | 기존 테이블로 표현된다 | 유효 | P3.9 | — |
| BD-41 | 지급 원천: 1:1 `APPEARANCE_REQUEST`+출연 계약 ID, 1:N `RECRUIT_APPEARANCE`+게스트별 계약 ID, 강의 `LECTURE_ORDER_ITEM`+항목 ID. 불일치는 `PAYOUT_SOURCE_MISMATCH` | 요청서 ID는 확정 계약과 맞지 않는다 | 유효 (2차안 대체) | P3.9, A8 | — |
| BD-42 | 한 원천은 한 번만 정산한다(`uk_settlement_source_type_source_id`, `PAYOUT_ALREADY_SETTLED`) | 계약·항목당 정산 하나 | 유효 | P3.9, A8 | — |
| BD-43 | 같은 원천의 NORMAL·PENALTY는 하나만 허용한다 | 1차에서 정상 지급 후 위약금이 둘 다 지급될 수 있었다 | 유효 | P3.9 | — |
| BD-44 | 지급 키 `PAYOUT:{holdId}:{payoutRequestId}`. 원장은 작업 하나, 줄 합계 0 | 기록 없음 | 유효 | P3.9 | — |
| BD-45 | 보류한 회원 자신에게 지급하면 `PAYOUT_TO_PAYER`(400) | 같은 지갑 안에서 충전 HP가 수익 HP로 바뀐다 | 유효 | P3.9, A8 | — |
| BD-46 | 위약금 = floor(기준액 × 30 / 100)을 Billing이 계산 | 당시 루트 AGENTS 수치 | **대체됨 → BD-56** | P3.9 | — |
| BD-47 | 계산된 위약금 0이면 거절 | 기록 없음 | **대체됨 → BD-57** | P3.9 | — |
| BD-48 | 위약금 키 `PENALTY:{holdId}:{cancellationId}`(`…:RELEASE`, `…:PAYOUT`). 반환 → 지급을 한 트랜잭션에서 처리한다 | 세 기록을 취소 ID로 함께 찾는다 | 유효 | P3.9, A8 | — |
| BD-49 | 지급·위약금은 `lockWallets`(flush → ID 오름차순 → refresh, `MANDATORY`) 뒤 보류 → 충전 건 순서로 잠근다. 위약금은 트랜잭션 없는 `HoldProcessing`을 쓴다 | 받는 쪽을 나중에 잠그면 순서가 뒤집힌다 | 유효 | P3.9 | — |
| BD-50 | 출연 계약 소속·계약 출연료·1인 1회는 출연이 검증한다 | Billing이 알 수 없다 | 유효 | P3.9 | — |
| BD-51 | 연동 계약은 `com.hapbang.shared.billing`에 둔다(`BillingPort` 4개 메서드, DTO 8개, Enum 2개). 강의·출연은 `:shared`만 의존한다 | Gradle 순환 없음 | **일부 대체됨 → BD-128** (`BillingPort` 부분. 계약 위치·`:shared`만 의존은 유효) | P3.10, A9 | 해소 (BD-128) |
| BD-52 | package-private `BillingPortAdapter`가 `HoldFacade`에 위임한다. 트랜잭션을 열지 않고 예외를 잡지 않는다 | `HoldFacade`가 직접 구현하면 내부 결과 검증을 잃는다 | 폐기 → BD-128 (#486에서 Adapter 삭제. 결과 변환은 `BillingResultDtos`가 내부 API에 계속 쓴다) | P3.10 | 해소 (BD-128) |
| BD-129 | 강의·출연 HP 결제 보류의 billing 내부 Command(`PaymentHoldCommand`·`PaymentReleaseCommand`·`PaymentPayoutCommand`·`PaymentPenaltyCommand`·`PaymentHoldVoidCommand`)를 `app`에 두고, `in.BillingCommands`가 shared Command를 바꿔 `HoldFacade`에 넘긴다. 환불·환전 `Internal*Command`와는 합치지 않는다. 내부 Command 생성자도 구조를 검사하고, 참조 유형 목록·요청 ID 형식은 `PaymentHoldRequests`에 복사해 shared와의 일치를 테스트로 고정한다. `app`·`domain`·`out`은 `shared.billing.dto`를 참조하지 않는다(`BillingSharedDtoAccessTest`) | HTTP 계약이 바뀌어도 UseCase가 묶이지 않게 한다. #273에서 `BillingPortAdapter`·Hold 테스트 영향으로 미뤘고, #486에서 Adapter를 지워 장애물이 없어졌다. Internal*과 합치면 BD-86의 타입 분리가 Facade 검사로 약해진다 | 유효 | C8, #494 | — |
| BD-128 | shared `BillingPort`와 billing-app `BillingPortAdapter`를 지운다. `shared.billing`에는 DTO·안정적인 Enum·오류 코드·공통 HTTP Client(`BillingApiClient`)만 두고 Java Port를 두지 않으며, `SharedBillingDependencyTest`가 port 패키지 없음·인터페이스는 sealed뿐을 고정한다. Port 테스트는 내부 API 테스트로 옮기고, 다른 모듈 트랜잭션 참여 검증은 요청 단위 롤백으로 대신한다 | 강의·출연이 내부 HTTP API로 전환해 운영에서 쓰는 곳이 없었다. 같은 JVM 호출 경로가 남으면 앱 단독 기동·모듈 경계가 다시 깨질 수 있다 | 유효 | #486, #280 | — |
| BD-53 | Billing UseCase는 호출한 쪽 트랜잭션에 참여한다(`REQUIRED`, `REQUIRES_NEW` 금지). 커밋은 호출한 쪽과 함께 한다 | 기록 없음 | 유효 | A9, P4 | **재검토** (원격 호출에서는 성립 불가) |
| BD-54 | shared는 구조만 검사한다(NPE·IAE → 500 `INTERNAL_ERROR`). 금융 검증은 `BillingCommandValidator`가 `BillingException`으로 한다. shared DTO를 Controller 요청 DTO로 쓰지 않는다 | 구조 오류는 내부 계약을 잘못 쓴 프로그래밍 오류 | **일부 대체됨 → BD-111** (내부 HTTP API에서는 shared Command가 요청 본문이고 구조 오류는 400. 같은 JVM 호출의 500은 #280까지 유효) | P3.10, A9 | 해소 (BD-111) / 문구(`global`) |
| BD-55 | 멱등키 생성을 `BillingKeys`로 옮기고 형식은 그대로 둔다. 호출한 쪽은 요청 ID만 넘긴다 | 기존 데이터를 그대로 찾는다 | 유효 | P3.10, A9 | — |
| BD-56 | 위약금 비율·금액 계산은 출연이 한다. Billing은 받은 `releaseAmountHp`(≥0)와 `payoutAmountHp`(>0)를 원자적으로 처리한다. 합이 남은 보류보다 크면 `PENALTY_BASE_EXCEEDS_HELD` | 기록 없음 | 유효 (BD-46 대체) | P3.10, A8 | — |
| BD-57 | 게스트 지급액 0이면 `PENALTY_AMOUNT_ZERO`(400), 음수면 `INVALID_POINT_AMOUNT` | 기록 없음 | 유효 (BD-47 대체) | P3.10, A8 | — |
| BD-58 | 호스트 반환액 0이면 RELEASE 상세·원장을 만들지 않고 PAYOUT만 기록한다 | 위약금이 기준액 전부인 비율(예: 100%)도 출연이 정할 수 있게 | 유효 | P3.10, A8 | — |
| BD-59 | 위약금 재요청은 반환액·지급액을 각각 대조한다 | #134는 합계만 비교했다 | 유효 | P3.10 | — |
| BD-60 | 호출한 쪽은 Billing 예외를 잡아 정상 결과로 바꾸지 않는다 | 트랜잭션이 이미 롤백 전용이라 원자성이 깨진다 | 유효 | P3.10, A9 | **재검토** (롤백 전용 트랜잭션 공유 전제. 원격 호출에서는 "오류를 성공으로 해석하지 않는다"만 남는다, BD-70) |
| BD-61 | 결과 DTO에는 호출한 쪽이 쓰는 값만 담는다 | 기록 없음 | 유효 | P3.10 | — |

## 6. 강의·출연 연동 (#142, #182, #188, #211)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-62 | Billing이 강의 지급 예정 건(SCHEDULED)을 관리한다(`LectureSettlementFacade`, suspend·resume·cancel) | 기록 없음 | **폐기** (#188). Flyway `V202610141530`·`SettlementStatus.CANCELLED`는 남김 | P3.11 | — |
| BD-63 | shared `LectureSettlementPort`로 구매·취소 때 예정 건 등록·취소 | 기록 없음 | **폐기** (#188) | P3.12 | — |
| BD-64 | 강의 = 동기 단일 트랜잭션. 구매 = 주문 저장 → `hold` → 커밋, 취소 = `release(full, "LECTURE-CANCEL-{itemId}")`, 지급 = 항목마다 새 트랜잭션. 실패하면 전부 롤백 | 기록 없음 | 유효 (#188 기준) | P3.12, A9 | **재검토** (강의 트랜잭션에 보류가 함께 참여하는 전제) |
| BD-65 | 출연 = 분리 트랜잭션 + HpTask. `HOLD_PENDING` 커밋 → 트랜잭션 밖 `hold` → `REQUESTED` / `HOLD_FAILED` / 결과 불명 503. 반환은 HpTask 커밋 후 실행 | 기존 설계 유지(팀 확정) | 유효 | P3.12, A9 | 부분 (트랜잭션 분리는 원격 호출에 맞다. 호출 수단만 바뀐다) |
| BD-66 | 출연 연동 모드 기본값 `appearance.billing.mode=fake`, `billing-port`일 때만 실제 호출 | 조용히 실제 금융으로 바뀌지 않게 | **확인 필요** — appearance-app 주석상 `FakeBillingClient`·모드 설정은 #289에서 삭제됨 | P3.12, A9 | **재검토** |
| BD-67 | 강의가 지급 시점·이의 제기·판매자 상태를 판단하고 `BillingPort.payout(full, "LECTURE-PAYOUT-{id}")`를 직접 부른다. Billing은 성공 시 COMPLETED를 한 번 INSERT한다 | 지급 원천 모듈이 업무 정책을 소유 | 유효 (BD-62·63 대체) | P3.13, A8.1 | **재검토** (호출 수단) |
| BD-68 | 구매 때 판매자 지갑을 보지 않는다. 지급 때 없으면 `WALLET_NOT_FOUND`로 롤백하고 같은 요청 ID로 재지급한다 | 기록 없음 | 유효 | P3.13, A8.1 | — |
| BD-69 | 중복 지급 오류 순서: 같은 요청 ID → replay, 끝난 보류 → `HOLD_ALREADY_CLOSED`, 남은 보류 + 같은 원천·다른 ID → `PAYOUT_ALREADY_SETTLED`. 순서를 바꾸지 않는다 | 모드 전환 시 호출 코드가 같다 | 유효 | P3.13, A8.1 | — |
| BD-70 | 지급 성공의 근거는 `payout()`의 정상 반환(처음 또는 replay)뿐이다. 재시도 가능 오류와 재시도 불가 오류를 나눈다 | 오류를 지급됨으로 바꾸면 HP가 가지 않은 항목이 PAID_OUT이 된다 | 유효 | A8.1, P3.14 | — (원격 호출에서 더 중요해진다) |
| BD-72 | 모듈 간 잠금 순서 `lecture_order → inquiry → order_item → wallet → point_hold → point_charge`. 호출한 쪽은 업무 행을 먼저 바꾸고 flush한 뒤 Billing을 부른다 | 순환 없음 | 유효 | P3.14, A8.1 | **재검토** (여러 모듈 락이 한 DB 트랜잭션 안에 있다는 전제) |
| BD-73 | 금융 경합은 지갑 → 보류 락으로 한쪽만 성공시키고, 업무 상태 경합은 강의 `order_item.version` 낙관적 락으로 막는다 | 금융 차단과 업무 정합성은 따로 검증한다 | 유효 | P3.14, A8.1 | 부분 (Billing 쪽 금융 차단은 그대로, 같은 트랜잭션 flush 전제는 재검토) |

## 7. 충전·보류 종류·환불 (#224, #241, #260, #453)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-75 | 충전 PROCESSING 상태, `pg_payment_key_hash` UNIQUE, `failure_reason`을 추가한다(`V202610151830`) | PENDING이면 동시 승인을 못 막고, FAILED면 승인된 결제를 잃는다 | 유효 (ERD 반영은 미결) | P3.15, A10.1 | — |
| BD-76 | claim 커밋 → PG 승인(트랜잭션 밖) → complete/fail. 바꾸지 않는다 | 먼저 커밋해야 어느 단계가 실패해도 추적·복구할 수 있다 | 유효 | P3.15, A10.1 | — |
| BD-77 | PG 결과 판정: 401·409·429 등은 불명확, 그 밖의 4xx는 FAILED, 5xx·타임아웃은 조회, 조회 404는 결과 확인 때만 같은 키로 재승인, PARTIAL_CANCELED는 FAILED 금지 | 돈 일부가 PG에 남는다, 실제 Toss는 승인 전 조회도 404 | 유효 | P3.15, A10.1 | — |
| BD-78 | `ChargeRecoveryBatch` 5분, 1분 경과, 100건. 미확정도 `updated_at` 갱신. 승인 결과가 주문과 다르면 `CHARGE_STATE_INCONSISTENT` | 같은 미확정 건만 반복하지 않게 | 유효 | P3.15, A10.1 | — |
| BD-79 | 충전 멱등성: 주문 ID·결제키 HMAC UNIQUE(`saveAndFlush`로 409 변환), 원장 `CHARGE:{chargeId}`, PG `hapbang-charge-confirm-{orderId}` | `EntityManager.flush()` 직접 호출은 500이 된다 | 유효 | P3.15, A10.1 | — |
| BD-80 | 결제키는 AES-256-GCM(무작위 IV) 암호화 + HMAC-SHA256(별도 키). 키가 없으면 503. HMAC 키는 바꾸지 않는다 | 기존 해시와 맞지 않게 된다 | 유효 | P3.15, A10.1 | — |
| BD-81 | 충전 1,000 이상·1,000 단위, 최대 금액·주문 만료 없음, 관리자 403 | 정책이 없다 | 유효 (최대·만료는 미결) | A10.1 | — |
| BD-82 | 보류 종류 MIXED(강의·출연)·CHARGE_ONLY(환불)·REVENUE_ONLY(환전)는 참조 유형이 정한다. `ck_point_hold_5` | 기록 없음 | 유효 | P3.16, A8.0 | — |
| BD-83 | 확정 차감(CAPTURE)은 받는 지갑 없이 최종 차감한다. 충전 원금 FIFO 소진, 상태 CAPTURED. MIXED 보류는 확정 차감 불가 | 기록 없음 | 유효 | P3.16, A8.0 | — |
| BD-84 | 충전 건 지정(`target_charge_id`)으로 PG 결제 건별 결과를 반영한다. 비어 있는 상세는 기존 규칙으로 재생한다 | FIFO로 차감하면 실제 PG 잔액과 어긋난다 | 유효 | P3.16, A8.0 | — |
| BD-85 | 충전 건 지정 확정 차감·반환은 금액 필수 | 부분 취소인데 전액 차감하는 실수 방지 | 유효 (1차 대체) | P3.16, A8.0 | — |
| BD-86 | 종류별 보류·확정 차감은 Billing 내부 경로(`InternalHoldFacade`)만 쓴다. shared `BillingPort`는 바꾸지 않는다. 새 오류 코드 3개 | 같은 보류를 지급과 확정 차감이 나눠 쓰지 않게 | 유효 | A8.0 | — |
| BD-87 | 환불 접수: `Idempotency-Key` 필수, 충전 HP만, 1,000 단위, 한 트랜잭션에서 요청 → CHARGE_ONLY 보류 → 개별 환불 PENDING. 회원 탈퇴 환불은 열지 않는다 | 탈퇴 미구현, 최소 금액 우회 방지 | 유효 (금액 규칙·회원 탈퇴 환불 제한은 BD-119로 대체) | P3.17, A10.2 | — |
| BD-88 | PG 취소 멱등키 `hapbang-refund-cancel-{UUID}`를 한 번 만들어 저장한다 | DB ID 기반 키는 다른 환경에서 재사용될 수 있다 | 유효 (ID 기반 키 대체) | P3.17, A10.2 | — |
| BD-89 | 순차 FIFO PG 취소(트랜잭션 밖), 요청 자체 오류만 FAILED, 환급 불가 4xx는 보류 유지 + 운영자 확인, 같은 표식 취소 여러 건은 운영자 확인 | 환급 불가여도 환불 청구는 남는다 | 유효 | P3.17, A10.2 | — |
| BD-90 | UNKNOWN 한도: 24시간 또는 결과 재확인 12회 → 운영자 확인. 자동 반환 없음 | 기록 없음 (확정 정책 ②) | 유효 | P3.17, A10.2 | — |
| BD-91 | 최초 PG 요청 + 14일부터 같은 키로 POST하지 않는다 | Toss 멱등키 15일 유효, 날짜 계산 여유 | 유효 | A10.2 | — |
| BD-92 | 환불 복구 배치 5분·1분·100건, 락 지갑 → 환불 요청 → 보류 → 충전 건, 회원 취소는 REQUESTED만, PG 요청한 건은 CANCELED 불가 | 기록 없음 (확정 정책 ①③) | 유효 | A10.2 | — |
| BD-93 | `restorePrincipal` 한도 = 충전액 − 가용 원금 − 환불 누계 | 환불한 원금이 돌아오지 않게 | 유효 | P3.17 | — |
| BD-119 | 환불 금액은 1 HP 이상·1 HP 단위다. 0 이하는 요청 DTO `@Positive`가 400 `INVALID_REQUEST`로 먼저 막는다. 회원 탈퇴 환불을 따로 막던 이유(최소 금액 우회)는 없어졌다 | 충전은 1,000 단위지만 사용 금액은 아니라 1,000 미만 끝자리 충전 HP를 현금화할 수 없었다. 정책(policy.md 2장)이 먼저 "0원 초과·잔액만큼"으로 바뀌었다 | 유효 | C4.1, P3.22, #453 | — |

## 8. 환전·회원 복제본·수수료 수입 (#285, #288, #294, #336, #453)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-94 | 계좌는 신청마다 입력받아 스냅샷으로 저장한다(사전 등록 없음). 암호화·마스킹, 검증 상태는 PENDING만 쓴다 | 사용자 결정 | 유효 | P3.18, A11 | — |
| BD-95 | 환전 신청: 인플루언서만, `Idempotency-Key` 필수, 한 트랜잭션에서 신청 → REVENUE_ONLY 보류 → 계좌. 수수료 10%(버림)는 신청 때 확정. 재요청은 복호화한 계좌로 비교한다 | 무작위 IV라 암호문끼리 비교할 수 없다 | 유효 (수수료 계산은 BD-120으로 대체) | P3.18, A11 | — |
| BD-96 | 잠정 정산 예정일: 1~9일 신청은 당월 10일, 10일 이후는 다음 달 10일, 주말이면 다음 월요일. 공휴일 미반영 | 실제 송금일을 보장하지 않는 잠정값 | 유효 (1차 대체) | P3.18, A11 | — |
| BD-97 | 환전 취소는 REQUESTED만, 반환 ID `EXCHANGE-CANCEL-{id}` | 기록 없음 | 유효 | A11 | — |
| BD-98 | 환전 잠금 계약: 지갑 → 환전 → 보류, 락 뒤 최신 상태로 판단, 반환·확정 차감 ID는 환전마다 고정 | 기록 없음 | 유효 | A11 | — |
| BD-99 | Mock 은행·HTTP Client는 같은 앱에 실제 HTTP로 요청하고, 트랜잭션 안에서 부르면 예외. 멱등 기록 `mock_payout_transfer`는 `exchange_payout`과 분리. 전용 보안 체인 + 무작위 접근 키 | 은행 기록과 우리 이력이 섞이지 않게 | 유효 | P3.19, A11 | — (같은 billing-app 안이라 분리 영향 없음) |
| BD-100 | Mock 활성 조건: `enabled=true` + 활성 프로필이 하나 이상이고 모두 local/test | 운영·dev에서 켜질 수 있던 결함 | 유효 (2·3차 대체) | P3.19, A11 | — |
| BD-101 | 송금: 기록 커밋 → 송금(트랜잭션 밖) → 결과 반영. 성공 = 확정 차감, 실패 = 자동 반환하지 않음, 불명확 = 같은 ID로 조회, 확정 실패 뒤 재송금은 새 시도 ID. 반환은 거절·취소가 최종 확정됐을 때만 | 같은 환전을 다시 송금할 수 있어야 한다 | 유효. 코드(#304)는 FAILED에서 보류를 유지하고 관리자 최종 실패(FINAL_FAILED) 때 반환한다. AGENTS 11장 끝의 "최종 실패 시 반환한다"는 이 FINAL_FAILED를 뜻하는지 **확인 필요** | A11 | — |
| BD-102 | 관리자 승인 REQUESTED → APPROVED(복제본 없음 409, 자격 없음 409), 거절 REJECTED + 사유 + 같은 트랜잭션 반환. 같은 관리자 재요청만 멱등 | 사용자 결정 | 유효 | P3.20, A11 | 문구 (`global` admin 경로 → 앱 내부 `SecurityConfig`) |
| BD-103 | `billing_user` 복제본: 가입·변경 이벤트 모두 Upsert, 비관적 락 + 회원 버전 비교, PK 충돌 3회 재시도. TIMESTAMP(TIMESTAMPTZ 예외) | 낙관적 락 + 3회로는 동시 10건 테스트가 실패했다 | 유효 | P3.20, A11 | — (#430: Kafka 그룹 `billing-app.member-replica`로 받는다) |
| BD-104 | 기존 회원 Backfill은 하지 않는다. 회원을 임의로 ACTIVE로 채우지 않는다 | Member 조회 Port·재발행이 없다 | 대체됨 → BD-132 (#324에서 Backfill을 구현했고, #552에서 지갑까지 만들게 했다) | P3.20, A11 | — |
| BD-114 | 플랫폼 지갑: `wallet.wallet_type`(MEMBER/PLATFORM), 플랫폼 지갑은 `user_id` NULL, 부분 유일 인덱스로 1개, Flyway(`V202610301000`)가 한 행을 넣는다. CHECK로 회원 지갑은 회원 있음·수수료 0, 플랫폼 지갑은 회원 없음·다른 칸 0 | 예약 회원 ID·설정값 방식은 회원 ID 공간과 섞이거나 환경마다 달라진다. user_id가 없으면 회원 API(`findByUserId`)로 닿지 않는다 | 유효 | C3.1, #336 | — |
| BD-115 | 수수료 수입은 HP 종류 `FEE`(가용 칸만), 캐시 `fee_balance_hp`. 다른 수수료성 수입도 같은 지갑에 모으고 거래 유형으로 구분한다. `reconcile`이 FEE 칸도 비교한다(회원 지갑은 0 확인). 점검 배치는 없다 | 수수료는 충전 HP도 수익 HP도 아니다(GLOSSARY). 수익 HP 칸을 쓰면 환전·반환 규칙과 섞인다 | 유효 | C3.2, C3.5, #336 | — |
| BD-116 | 송금 성공 처리에서 확정 차감과 같은 트랜잭션에 별도 작업 `EXCHANGE_FEE:{환전 ID}`(거래 유형 `EXCHANGE_FEE`, 참조 EXCHANGE)로 기록한다. 금액은 신청 때 저장한 `fee_amount_hp`, 확정 차감액 = 송금액 + 수수료를 검사한다. 송금 결과 반영·정산 시작은 회원·플랫폼 지갑을 ID 오름차순으로 잠근 뒤 환전 → 송금 시도 → 보류 순서. 최종 실패·취소·거절은 기록하지 않는다. 소급하지 않는다 | 환불도 쓰는 공통 capture를 바꾸지 않는다. 작업 키가 환전마다 고정이라 재확인·수동 재송금에도 한 번이다. dev·prod는 송금 성공 건이 없다 | 유효 | C3.3, #336 | — |
| BD-117 | 플랫폼 지갑 조회는 관리자 API만(`GET /api/v1/settlements/admin/platform-wallet`, `…/fee-incomes` 최신순 페이지), 기록은 송금 성공 처리만 한다. Wallet 도메인이 플랫폼 지갑의 충전·수익 원장, 회원 지갑의 FEE 원장, FEE 보류 칸을 거절한다 | 이슈는 "조회 API는 회의에서"였으나 잔액·내역 조회를 모두 만들기로 사용자가 정했다(2026-10-29) | 유효 | C3.4, C3.6, #336 | — |
| BD-118 | 테스트 DB 정리(`SchemaManager.truncate`)가 플랫폼 지갑도 지우므로 billing 테스트 설정의 Hibernate load script(`billing-test-platform-wallet.sql`)로 다시 넣는다. 회원 지갑 수 검사는 MEMBER만 센다 | test-support를 바꾸지 않고 운영처럼 "항상 있음"을 유지한다 | 유효 | C3.7, #336 | — |
| BD-120 | 환전 금액은 1 HP 이상·1 HP 단위다. 송금액 = floor(신청 × 90%), 수수료 = 신청 − 송금액(항상 1 HP 이상). 신청 때 확정·저장하고 `fee_rate`는 0.1000 그대로다. 이미 저장된 환전은 바뀌지 않는다(`ck_exchange_1` → `>= 1`, `V202610301200`) | 잔액 전부를 현금화하도록 최소 1 HP로 정했다(최소 1,000 유지·최소 2원 안도 있었다). 버림 대상을 송금액으로 바꿨다(사용자 결정) | 유효 (BD-95 수수료 계산 대체) | C4.1, C4.2, #453 | — |
| BD-121 | 송금액 0원 환전(신청 1 HP)은 허용한다. 정산 배치가 정산 예정 시각에 은행을 부르지 않고 같은 트랜잭션에서 확정 차감 + 수수료 수입 + COMPLETED로 끝낸다. 송금 시도(`exchange_payout`)는 남기지 않는다. dev·prod(Mock 꺼짐)는 다른 환전처럼 건너뛴다 | 송금할 금액이 없다. 회차·승인 흐름과 환경별 규칙은 그대로 둔다 | 유효 | C4.3, #453 | — |
| BD-122 | 오류 코드 `REFUND_AMOUNT_INVALID` → `REFUND_AMOUNT_TOO_SMALL`, `EXCHANGE_AMOUNT_INVALID` → `EXCHANGE_AMOUNT_TOO_SMALL`(1 HP 미만). 회원 API 코드라 shared `BillingFailureCode`와 관계없다 | 단위 검사가 없어져 뜻을 맞췄다(사용자 결정). 프론트와 맞춘다 | 유효 | C4.4, #453 | — |
| BD-131 | (1) 사용자 API는 Gateway(Envoy)가 JWT를 검증해 넘긴 `X-User-Id`·`X-User-Role`로 인증한다(`GatewayUserHeaderFilter`, 강의 #521·D72와 같은 방식). 세션·CSRF를 쓰지 않고(STATELESS), 헤더가 한쪽만 있거나 형식이 틀리면 익명 + 경고 로그(로그인이 필요한 API는 401). 내부 API·Mock 송금 체인은 그대로다. (2) HP를 받거나 내보내는 신청은 토큰 역할(`@PreAuthorize`)과 함께 지갑 회원(`billing_user`)의 상태·역할도 통과해야 한다(`WalletMemberCheck`): 충전 주문·승인 = ACTIVE + 유저·인플루언서·인증된 인플루언서, 환불 신청 = ACTIVE·SUSPENDED + 같은 역할, 환전 신청 = ACTIVE + 인플루언서 이상. 복제본 없음 404 `WALLET_MEMBER_NOT_FOUND`, 부적격 403 `WALLET_MEMBER_NOT_ALLOWED`. 충전 승인은 TossPayments 승인 전에 확인한다. 조회·취소와 내부 API(보류·반환·지급)는 확인하지 않는다 | 발급한 JWT는 회수할 수 없어 정지·강등이 최대 15분 늦다(api-gateway.md 인증 흐름 7). 환불은 회원이 결제한 돈을 돌려받는 것이라 정지로 막지 않는다. 상태를 모르면 돈을 움직이지 않는다(BD-102·채팅과 같다). 복제본 없음은 가입 직후 일시적일 수 있어 403과 나눈다. 취소는 보류 HP를 돌려받는 쪽이라 막지 않는다. 내부 API의 `userId`는 호출 앱이 넘기며, 정지 회원의 출연도 지급한다(policy 5장). 이미 PROCESSING인 충전은 복구 배치가 Facade를 거치지 않고 끝까지 확인한다 | 유효 (2026-11-02 사용자 결정, policy.md 2장 팀 확인 요청) | #537 | — |
| BD-132 | 관리자 회원 Backfill(`POST /api/v1/settlements/admin/billing-users/backfill`)은 회원마다 지갑 회원을 반영한 뒤, 관리자가 아닌 회원(상태 무관, 가입 리스너와 같은 기준)의 지갑을 `WalletFacade.getOrCreateWallet`으로 확인하고 없으면 만든다. 결과에 `walletsEnsured`(이미 있던 지갑 포함)를 더한다. 지갑에서 실패한 회원은 `failed`로만 센다(scanned 합계 유지). 원장·충전·환전은 건드리지 않는다. 지갑 생성 경로는 가입 이벤트·`GET /wallets/me`·이 Backfill 셋이다(BD-2 보강) | `member.events.v1` 보존이 72시간이라 billing 첫 배포 전에 가입한 회원은 지갑이 없다. 지갑이 없으면 충전 주문과 강의·출연의 보류·지급이 `WALLET_NOT_FOUND`이고, 받는 쪽(판매자·게스트)은 지갑 화면을 열지 않으면 계속 실패한다. 금융 거래 중 자동 생성(BD-9 변경)보다 운영 작업 한 번이 범위가 작다. 다시 실행해도 결과가 같다(복제본 버전 비교, 지갑 UNIQUE) | 유효 (2026-11-02 사용자 결정) | #552 | — |
| BD-133 | (1) Hikari `maximum-pool-size` 10을 명시한다(기본값 그대로). (2) actuator는 k8s에서 관리 포트 8082(`MANAGEMENT_SERVER_PORT`)로만 열고 `health,prometheus`를 노출한다. NetworkPolicy는 8080에 Gateway만, 8082에 Prometheus만 들인다(강의 #521과 같음). (3) prod 첫 배포의 Backfill은 route를 열기 전에 `kubectl port-forward`로 8080에 붙어 관리자 사용자 헤더로 호출한다(`docs/deploy/k8s.md` "billing-app 첫 배포") | (1) 지갑 락 대기가 최대 3초(BD-108)라 pool을 줄이면 동시 요청이 연결을 기다린다. 전체 계산은 최악 80/100(`database.md` 5항). (2) 앱이 사용자 헤더를 믿으므로 Prometheus가 8080에 닿으면 안 된다. (3) 코드·manifest 변경 없이 prod kubeconfig가 있는 사람만 할 수 있다. route를 일부만 여는 절차를 만들지 않는다 | 유효 (2026-11-02 사용자 결정) | #552 | — |
| BD-134 | 지갑이 다른 서비스를 부르는 호출에 Resilience4j circuit breaker를 둔다(`out.OutboundCircuitBreakers`). shared Client는 고치지 않고 지갑에서 감싼다. 회로는 상대마다 하나(`lecture`·`appearance`·`member`·`toss`), 기본값은 shared `BillingClientProperties`와 같다(최근 20번 중 10번 이상, 실패율 50%, 느린 호출 4초, OPEN 30초, HALF_OPEN 3회, `hapbang.billing.outbound.*`). (1) 강의·출연·회원: `*ApiCallException`의 `UNAVAILABLE`만 실패, `REJECTED`(4xx)는 성공으로 센다. 수익 조회만 연결 자체가 안 됐을 때 1회 더 보낸다(응답 시간 초과·5xx는 다시 보내지 않음). 회로가 열리면 바로 `EARNING_SOURCE_UNAVAILABLE`·`MEMBER_SOURCE_UNAVAILABLE`. (2) TossPayments: 결과 `Unknown`만 실패, `Rejected`·`NotFound`는 성공, 재시도 없음. 열린 채 호출되면 요청하지 않고 `Unknown(CIRCUIT_OPEN)`. 충전 주문·승인·환불 신청은 회로가 열려 있으면 상태를 바꾸기 전에 503 `CHARGE_UNAVAILABLE`·`REFUND_UNAVAILABLE`. 충전·환불 결과 확인 배치는 건마다 회로를 보고 열려 있으면 남은 건을 다음 회차로 넘긴다(`TossCircuitGate`). (3) 지표 `resilience4j.circuitbreaker.*`·`resilience4j.retry.calls`를 Prometheus로 낸다 | 루트 AGENTS 2장(앱 간 HTTP는 각 앱의 Resilience4j). 장애 상대를 계속 기다리면 요청 스레드·DB 연결을 붙잡는다. shared를 고치면 appearance·customer-support 동작이 바뀐다. 카드 거절 같은 4xx를 실패로 세면 다른 사용자 충전까지 막힌다. Toss 결과 불명은 이미 복구 배치가 같은 멱등키로 확인한다. 열린 동안 배치가 부르면 환불 결과 재확인 횟수(`result_check_count`, 12회 한도)만 늘어난다. 처음 값은 추정이라 배포 뒤 지표로 다시 맞춘다 | 유효 (2026-11-02 사용자 결정) | #562 | — |
| BD-138 | 스케줄 배치 5개(`ChargeRecoveryBatch`·`RefundRecoveryBatch`·`ExchangePayoutRecoveryBatch`·`ExchangeSettlementBatch`·`BillingEventRepublishBatch`)와 관리자 회원 Backfill은 시작할 때 PostgreSQL session advisory lock(`pg_try_advisory_lock`)을 기다리지 않고 잡고, 잡지 못하면 실행하지 않는다(`global.scheduling.JobLock`). (1) 락은 Hikari pool 밖의 **락 전용 연결 하나**로 잡고 푼다(호출 직렬화, 첫 시도 때 앱 datasource 접속 정보로 연결, `ApplicationName=billing-app-job-lock`). 호출이 한 모니터에서 줄을 서므로 연결 확인 1초·연결 3초·쿼리 3초로 상한을 짧게 둔다(호출당 최악 락 시도 약 7초, 해제 약 4초. 배치 5개와 Backfill이 모두 줄을 서면 마지막 호출은 그 합만큼 기다린다). (2) advisory lock은 같은 session이면 다시 잡혀도 성공하므로(재진입) 이 pod가 쥔 작업을 메모리에 두고, 이미 쥐고 있으면 DB에 묻지 않고 실패로 본다. Backfill의 `AtomicBoolean`을 이것으로 바꿨다. (3) 락 번호는 `BillingJob` enum의 고정 상수(598001~598006)이고 바꾸지 않는다. 새 작업은 다음 번호를 쓴다. (4) 배치는 `*Batch.run()`에서 감싼다. 다른 실행이 쥐고 있거나(info) 락을 시도하지 못하면(warn, DB 연결 불가·SQL 오류) 그 회차를 건너뛰고, 락 없이 실행하지 않는다. (5) Backfill은 다른 실행이 쥐고 있으면 409 `BACKFILL_ALREADY_RUNNING`(배포 중 다른 pod 포함), 락을 시도하지 못하면 503 `BACKFILL_LOCK_UNAVAILABLE`(내부 API에서 나오지 않아 `BillingFailureCode` 분류 없음). (6) 락 연결이 끊기면 그 연결의 락은 PostgreSQL이 풀고, 돌던 작업은 끝까지 둔다. 다음 락 시도 때 연결을 확인해 다시 맺고 warn을 남긴다. (7) 건너뛴 횟수를 `hapbang.billing.job.lock.skipped{job, reason=held|error}`로 낸다. (8) 막는 것은 두 pod가 **동시에** 실행하는 것뿐이다. 한쪽이 끝내고 푼 뒤 다른 쪽이 늦게 시작하면 같은 회차가 한 번 더 돈다. 기동 때 Modulith 미완료 이벤트 재발행은 이 락 밖이다 | 배포(`maxSurge: 1`, `maxUnavailable: 0`) 중 두 pod가 잠깐 함께 떠 같은 cron 시각에 배치가 겹칠 수 있다(L4), Backfill 실행 중 표시는 pod 메모리에만 있었다(L5). advisory lock은 이미 쓰는 PostgreSQL 기능이라 라이브러리·테이블이 늘지 않는다(ShedLock은 라이브러리·락 테이블 추가라 팀 합의가 필요하다). 트랜잭션 단위 락(`pg_try_advisory_xact_lock`)은 배치가 건마다 트랜잭션을 여러 번 열어 첫 트랜잭션에서 풀리므로 session 락을 쓴다. 스케줄러 스레드가 4개(`spring.task.scheduling.pool.size`)라 pool(10)에서 락 연결을 빌리면 최악의 경우 락 5개 + 업무 5개로 pool이 바닥난다. 전용 연결은 pod당 1개만 늘어난다. pod가 죽으면 연결이 끊겨 락이 자동으로 풀린다(락이 남지 않는다). DB가 안 되면 배치 업무도 어차피 실패하므로 락 없이 실행하지 않는다. 락 쿼리는 기다리지 않고 바로 답하므로 짧은 상한으로 충분하다(PR 리뷰에서 처음 값 연결 확인 2초·쿼리 10초가 호출당 약 15초 대기를 만든다는 지적을 받아 줄였다). 돌던 배치를 멈추려면 5개 UseCase 반복문을 모두 고쳐야 하고, 건마다 잡는 락·멱등성이 HP 중복 이동을 막는다. 같은 회차 재실행과 이벤트 중복 발행은 멱등성으로 허용한다 | 유효 (2026-11-03 사용자 결정. (3)의 새 번호 규칙은 BD-139로 대체) | #598 | — |
| BD-139 | 변경된 지갑의 잔액 대사를 매일 자동 실행한다(`app.WalletReconciliationBatch` → `ChangedWalletReconciliationUseCase`). (1) 대상은 기준 시각(지금 − `hapbang.billing.reconciliation.window`, 기본 72시간) 이후 `wallet.updated_at` 또는 `point_ledger.created_at`이 있는 지갑이다(플랫폼 지갑 포함). 마지막 실행 시점은 저장하지 않는다. 창보다 오래 바뀌지 않은 지갑은 대사하지 않는다(필요하면 창 설정을 길게 바꿔 한 번 돌린다). (2) 매일 04:02:45 KST(`hapbang.billing.reconciliation.cron`, 기본 `45 2 4 * * *`). (3) 지갑마다 `reconcile`(BD-20, 한 스냅숏)을 따로 부르고, 불일치를 고치지 않는다. 불일치 지갑마다 error 로그(지갑 ID, 차이 난 버킷의 캐시·원장·차이), 끝나면 info 요약 로그. (4) 한 지갑의 오류는 나머지를 막지 않는다. 오류 지갑은 전체를 한 번 돈 뒤 한 번 더 대사하고(warn), 그래도 실패하면 `failed`로 세고 pod 메모리에 두어 다음 회차 대상에 창과 관계없이 더한다. 성공하면 목록에서 뺀다. pod가 다시 뜨면 목록은 사라진다(새 테이블 없음). (5) 지표: `hapbang.billing.reconciliation.wallets{result=matched|mismatched|failed}`(마지막 실행의 결과별 지갑 수, gauge), `hapbang.billing.reconciliation.last.success`(대상 지갑을 모두 대사한 마지막 시각, epoch 초. 재시도 뒤 `failed`가 0일 때만 갱신, 불일치는 성공으로 본다, 대상 0개도 성공). pod가 다시 뜨면 첫 실행 전까지 0이다. alert는 #343에서 이 지표로 건다. (6) 실행 락(BD-138)을 쓴다. 락 번호는 607001이다. 이후 새 작업의 번호는 그 작업을 더한 이슈 번호 × 1000 + 순번이다(BD-138 (3)의 "다음 번호"를 대체). (7) Flyway `V202611031800`로 `wallet(updated_at)`·`point_ledger(created_at)` 인덱스를 더한다. (8) 결과 테이블·관리자 API·자동 보정은 두지 않는다. 서비스 간 HP 대사(BD-137, #580·#581)와는 다른 대사다 | 불일치를 사람이 찾아야 했다(L6). 지금 규모에서는 전체 대사도 가볍지만, 바뀐 지갑만 보면 원장이 쌓여도 매일 읽는 양이 거래량에 비례한다. 원장만 생긴 지갑(캐시 반영 누락)과 캐시만 바뀐 지갑을 둘 다 잡으려고 두 시각을 함께 본다. `updated_at`은 커밋 전에 앱이 찍은 시각이라 경계에 여유가 필요하고, 원장 ID는 커밋 순서와 달라 워터마크로 쓰지 않는다. 72시간 창이면 두 회차를 연달아 건너뛰어도 놓치지 않고, 그보다 오래 멈추면 마지막 성공 시각 지표로 안다. 시각은 환전 정산(10~23시)과 5분 단위 분, 복구·재발행 배치의 초(0·5·15·30)를 피했다. 강의의 서비스 간 대사(04:30:25, lecture D74)와도 겹치지 않는다. 지갑마다 트랜잭션을 나눠 긴 스냅숏을 열어 두지 않는다. 결과별 지갑 수 gauge는 '지금 몇 개가 틀렸는지'를 바로 보여 주고 누적 counter처럼 같은 불일치를 매일 다시 세지 않는다. 불일치를 자동으로 고치면 원인을 가리고 돈 정합성 규칙(BD-20)을 바꾼다. PR 리뷰에서 (4)(5)를 바꿨다: 창은 지갑의 마지막 변경 기준이라 창 끝에 가까운 지갑이 실패하면 다음 회차 창 밖으로 나가 다시 대사되지 않았고, 전부 실패해도 마지막 성공 시각이 갱신되어 '대사가 멈춤' alert가 DB 장애를 놓쳤다. 같은 실행의 재시도는 순간 오류를 흡수하고, 메모리 이월은 테이블 없이 창 밖 지갑을 다시 대사한다(pod 재시작 때 사라지는 것은 failed 지표·warn 로그와 last.success 정체로 안다) | 유효 (2026-11-03 사용자 결정) | #607 | — |
| BD-140 | 지갑 사용자 API에 회원별 요청 한도를 둔다(`global.ratelimit`). (1) 대상 14개: 신청·취소 6개(충전 주문·승인, 환불 신청·취소, 환전 신청·취소) 분당 10, 일반 조회 6개(내 지갑, 거래 내역, 충전·환불 조회, 환전 목록·상세) 분당 60, 수익 조회 2개(요약·목록) 분당 20. 내부 API·관리자 API·Mock 송금은 넣지 않는다. (2) 회원(`X-User-Id`) × API마다 따로 센다. Resilience4j `RateLimiter`(앱 메모리, 1분 주기, 기다리지 않음). 컨트롤러 메서드의 `@RateLimited`를 `RateLimitInterceptor`가 인증 필터 뒤·컨트롤러 앞에서 확인하므로 넘은 요청은 상태를 바꾸지 않는다. (3) 넘으면 429 `TOO_MANY_REQUESTS`(`GlobalErrorCode`, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.") + `Retry-After`(다시 시도할 수 있을 때까지 남은 초). 공통 `GlobalExceptionHandler`가 응답하고, 요청마다 warn 로그를 남기지 않는다. (4) 한도는 종류별 설정 3개 `hapbang.billing.rate-limit.write`·`read`·`earning`. (5) 거절 횟수 `hapbang.billing.rate.limit.rejected{api}`(회원 ID는 태그에 넣지 않음). (6) 두 주기 넘게 쓰지 않은 카운터는 한 주기마다 지운다. 사용 시각 갱신(`compute`)과 삭제(`computeIfPresent`)를 같은 키에서 직렬화해 지금 쓰는 카운터를 지우지 않는다. (7) 배포 중 두 pod가 겹치는 동안 한도가 잠깐 최대 2배가 되는 것은 허용한다. (8) member도 같은 이름 `TOO_MANY_REQUESTS`·429 `ProblemDetail`·`Retry-After`를 자기 `GlobalErrorCode`에 둔다. 두 앱 이상이 같은 코드를 쓰면 `common`으로 옮길지 다시 판단한다. (9) 인터셉터가 `@Valid` 검증·`WalletMemberCheck`·`@PreAuthorize`보다 먼저 세므로 400·403으로 끝나는 요청도 한도를 쓴다(의도). (10) 충전 승인이 202 PROCESSING이면 상태는 `GET /wallets/charges/{orderId}`(조회 한도 60)로 확인하고, 승인 요청은 응답을 받지 못했을 때만 다시 보낸다(Swagger 명세). (11) `Retry-After`를 계산하려고 Resilience4j `internal` 패키지의 `AtomicRateLimiter`를 쓴다(공개 `RateLimiter.Metrics`에 남은 대기 시간이 없음, 버전 2.4.0 고정). | 남용으로 DB·지갑 락·TossPayments 호출이 몰리면 다른 회원이 느려지거나 결제사 한도에 걸린다. 돈 정합성은 멱등키·락이 지키므로 자원 보호다. 충전 한 번이 주문 + 승인 2번이라 API마다 따로 세야 분당 충전 10번이 된다. 조회 20은 잔액 표시 반복과 처리 중 결과 polling(1초 간격)을 막아 60으로 올렸고, 수익 조회는 요청마다 다른 앱 2개를 불러 20으로 둔다. 대상 API는 모두 로그인이 필요해 IP 기준은 쓰지 않는다. replica 1개라 공유 저장소(Redis)가 필요 없다(api-gateway.md "rate limit"). 내부 API는 회원이 아니라 앱이 부르므로 회원 기준 한도가 맞지 않는다. 회원 ID 태그는 지표 수를 회원 수만큼 늘린다. 오류 코드는 특정 기능 이름이 없는 일반 이름이라 member가 그대로 쓸 수 있고, 채팅의 `TOO_MANY_TICKET_REQUESTS`와 겹치지 않는다. PR 리뷰로 (6)의 직렬화, (9)~(11)을 더했다: 잘못된 입력 반복도 자원을 쓰고, 세지 않으면 검증 실패 요청으로 한도를 피할 수 있으며, 같은 잘못된 입력은 다시 보내도 결과가 같다. 처음 승인 명세는 처리 중에 같은 요청을 다시 보내라고 안내해 polling이 신청 한도(10)에 걸릴 수 있었다 | 유효 (2026-11-03 사용자 결정) | #627 | — |
| BD-141 | 회원 탈퇴용 지갑 확인하고 닫기·다시 열기 내부 API(`POST /api/v1/internal/billing/withdrawals/{withdrawalId}/close`·`/reopen`, `WalletWithdrawalUseCase`, docs/api/internal-withdrawals.md 4장). (1) 닫기는 한 트랜잭션에서 회원 락 → 지갑 락 → 탈퇴 기록 확인 → 막힌 이유 계산 → 닫기. 지갑이 적는 막힌 이유는 충전 HP 잔액 > 0 `BILLING_CHARGE_BALANCE_REMAINING`, 수익 HP 잔액 > 0 `BILLING_REVENUE_BALANCE_REMAINING`, 환불 REQUESTED·PROCESSING·MANUAL_REVIEW_REQUIRED `BILLING_REFUND_IN_PROGRESS`, 환전 REQUESTED·APPROVED·PROCESSING·FAILED·UNKNOWN `BILLING_EXCHANGE_IN_PROGRESS`(모두 신청·승인이면 `availableAt` = 가장 늦은 정산 예정일 KST, 아니면 없음), 충전 PROCESSING `BILLING_CHARGE_IN_PROGRESS`다. PENDING 충전과 출연·강의 보류는 이유로 적지 않는다. (2) 탈퇴 기록 `wallet_withdrawal`(PK = 회원 앱의 탈퇴 작업 ID)이 멱등 키 저장소다. 같은 회원 재요청은 처음 결과(BLOCKED면 저장한 이유)를 replayed로, 다른 회원은 409 `IDEMPOTENCY_KEY_CONFLICT`(동시 PK 충돌 포함). 다시 열기 기록이 있는 탈퇴 작업의 닫기는 지갑을 닫지 않고 409 `WITHDRAWAL_ALREADY_REOPENED`. (3) 다시 열기는 이 탈퇴 작업으로 닫힌 지갑만 연다(`REOPENED`). 닫힌 적이 없거나 BLOCKED였거나 다른 탈퇴 작업으로 닫혔으면 바꾸지 않는다(`NOT_CLOSED`). 두 경우 모두 기록한다. (4) 닫힌 지갑(`wallet.closed_by_withdrawal_id`·`closed_at`)은 충전 주문, 충전 승인 시작(PROCESSING 저장·PG 호출 전), 새 보류(강의·출연 결제, 환불·환전 신청)를 409 `WALLET_CLOSED` "탈퇴한 회원의 지갑이에요."로 거절한다. 확인은 그 작업의 지갑 락 아래에서 한다. 닫기 전에 만든 보류의 재요청, 보류 반환·지급 받기·위약금, 충전·환불·환전 복구 배치는 받는다. 이미 닫힌 지갑을 다른 탈퇴 작업으로 다시 닫으면 마지막 작업으로 바꾼다. (5) 지갑이 아직 없는 회원은 지갑을 만들지 않고 CLOSED 기록만 남긴다. 지갑 생성(`WalletCreateUseCase`)과 닫기·다시 열기는 회원별 트랜잭션 advisory lock(`WalletUserLock`, `pg_advisory_xact_lock(623, 회원 ID)`)으로 직렬화하고, 지갑을 만들 때 회원의 마지막 CLOSED 기록이 다시 열리지 않았으면 닫힌 지갑으로 만든다. 지갑이 없을 때의 다시 열기는 그 작업이 회원의 마지막 CLOSED 기록이면 `REOPENED`다. (6) 닫힌 지갑의 보류 거절은 `WALLET_CLOSED`이고 `BillingFailureCode`에 더했다. 강의·출연이 이 코드를 정상 거절로 처리하는 일은 후속 이슈로 둔다. (7) 닫힌 지갑에 들어온 HP(보류 반환·지급 받기)는 지갑에 남는다. 닫힌 지갑은 환불·환전 신청을 받지 않으므로 회원이 30일 안에 복구해 지갑이 다시 열려야 쓸 수 있다. 막지 않는 대신 알린다: 쓸 수 있는 HP가 닫힌 지갑에 들어오면 원장 기록 때 warn 로그(`LedgerRecordUseCase`), 매일 잔액 대사 배치가 잔액이 남은 닫힌 지갑 수를 지표 `hapbang.billing.wallet.closed.with.balance`와 warn 로그(지갑 ID)로 낸다. 파기(30일) 뒤 남은 잔액의 처리는 #594에서 정한다 | 앱마다 DB가 달라 탈퇴를 한 트랜잭션으로 묶을 수 없어 지갑만 상태를 바꾼다. 확인과 닫기를 한 지갑 락 아래에서 해 새 결제가 끼어들 틈을 없앤다. Facade의 미리 확인만으로는 "확인 → 닫힘 → 처리" 틈이 생긴다. 이유가 있으면 자동 환불·취소하지 않고 회원이 정리한다. 늦은 닫기 거절은 보류 무효화(BD-125·126)와 같은 방식이다. 충전 승인은 PG를 부르기 전에 막아야 돈이 빠져나가지 않는다(BD-131과 같은 이유). 이미 생긴 보류의 재요청을 거절하면 강의·출연이 보류가 있는데 실패로 기록한다. 지갑이 없는 회원은 잠글 행이 없어, 같은 회원의 지갑 생성과 동시에 일어나면 기록은 CLOSED인데 지갑이 열린 채 남을 수 있다. advisory lock은 이미 쓰는 PostgreSQL 기능이고(BD-138) 두 int 키라 배치 실행 락(bigint 키)과 겹치지 않는다. 강의는 `WALLET_CLOSED`를 불변식 위반으로, 출연은 500 `HP_HOLD_FAILED`로 처리하지만, 탈퇴 직후 `UserModifiedEvent`가 오기 전 몇 초에만 생기고 돈을 잃지 않는다. 환전은 1원 단위(policy.md 2장)라 계약 6장의 "999 HP 이하 소액 수익 HP"는 해당이 없다. (7) 정상 흐름에서는 받을 돈이 남은 판매자·게스트를 강의(#625 `LECTURE_SALE_PAYOUT_PENDING`·`LECTURE_ON_SALE`)·출연(#624)이 지갑 닫기 전에 막아, 닫힌 지갑에 HP가 들어오는 것은 확인과 탈퇴 이벤트 도착 사이에 생긴 거래 같은 드문 경우다. 닫힌 지갑으로의 지급을 거절하면 강의·출연의 지급 흐름을 바꿔야 하고(이미 수강한 구매자에게 환불됨), 소멸·관리자 정산은 팀 정책이 필요해 범위를 넘는다. 돈은 원장으로 추적되고 복구하면 다시 쓸 수 있다 | 유효 (2026-11-03 사용자 결정: 지갑 없는 회원은 기록만, 회원별 advisory lock, 막힌 이유 이슈 표 그대로, `WALLET_CLOSED` 문구, 보류 거절 후속 이슈, PR 리뷰 뒤 닫힌 지갑에 들어온 HP는 남기고 알림) | #623 | — |

## 9. 오류·호출 계약 (5장·4장, #273)

| ID | 결정 | 이유 | 상태 | 출처 | JVM |
| --- | --- | --- | --- | --- | --- |
| BD-105 | `CHARGE_PRINCIPAL_MISMATCH`·`HOLD_STATE_INCONSISTENT`는 `INTERNAL_ERROR`로 바꾸지 않는다. `detail`은 일반 문구 | 테스트·로그에서 원인을 코드로 구분 | 유효 | P5 | — |
| BD-106 | 모듈 예외 핸들러를 없애고 공통 `GlobalExceptionHandler`가 `ProblemDetail` + `code`로 응답한다. 성공은 `RsData` | 기록 없음 | 유효 | P5, A14 | 문구 (공유 `global` 모듈 → `com.hapbang.billing.global`) |
| BD-107 | 호출한 쪽은 READ COMMITTED를 쓰고, UNIQUE 충돌로 `IDEMPOTENCY_KEY_CONFLICT`가 난 바깥 트랜잭션은 계속 쓰지 않는다 | RR 이상이면 직렬화 오류 | 유효 | P4 | **재검토** (호출한 쪽 트랜잭션 공유 전제) |
| BD-108 | Billing 전체에 `lock_timeout = 3s`(Hikari `connection-init-sql`)를 둔다. 55P03은 Facade 진입점에서 `HP_LOCK_TIMEOUT`(503)으로 바꾸고, shared `BillingFailureCode`에도 둔다 | Client 응답 timeout(5초)과 무제한 락 대기가 맞지 않는다 | 유효 (10장 미결에서 결정됨) | P3.21, C1.4 | — |
| BD-111 | 내부 HTTP API(`/api/v1/internal/billing/{holds,releases,payouts,penalty-settlements}`)는 shared Command를 요청 본문으로 그대로 받는다. 구조 오류(NPE·IAE)는 400 `INVALID_REQUEST`다. 회원이 부르는 사용자 API는 각 모듈 요청 DTO를 쓴다 | shared Command가 곧 모듈 간 HTTP 계약이다. BD-54는 같은 JVM 호출 전제였다 | 유효 (BD-54 일부 대체) | P3.21, C1.1, A9 | — |
| BD-112 | `HoldFacade`에 `@PreAuthorize("hasRole('INTERNAL')")`를 붙인다. 내부 API는 `X-Internal-Api-Key`로 인증하고, 호출한 쪽이 넘긴 `userId`를 믿는다 | #280 뒤 `HoldFacade`가 내부 API 전용 진입점이 된다(D63) | 유효 (BD-28 일부 대체) | P3.21, C1.2 | — |
| BD-113 | `BillingFailureCodeContractTest`가 `BillingErrorCode` 전체를 `default` 없는 `switch`로 `BillingFailureCode` 또는 `null`(내부 API에서 나오지 않음)로 분류한다 | Billing 오류 코드가 늘면 컴파일되지 않아 분류를 빠뜨리지 않는다 | 유효 | P3.21, C1.6 | — |
| BD-135 | 확정 차감(capture)은 강의·출연이 쓰는 내부 API(`/api/v1/internal/billing/*`)로 노출하지 않는다. 지갑 안의 환불(CHARGE_ONLY)·환전(REVENUE_ONLY) 경로(`InternalHoldFacade`)에서만 쓴다. 필요한 외부 흐름이 생기면 그때 계약·MIXED 보류 규칙과 함께 다시 정한다 | 강의·출연의 확정은 모두 받는 사람에게 옮기는 지급(payout)이고, HP를 최종 차감만 하는 외부 흐름이 없다. MIXED 보류(강의·출연)의 확정 차감은 이미 `HOLD_TYPE_NOT_SUPPORTED`로 막는다(billing AGENTS.md 8.0). MSA 전환 계획서의 Saga 표(Confirm = payout·capture)는 설계 대응표이며 실제 계약은 payout만이다 | 유효 (2026-11-02 사용자 결정) | #564, A8.0 | — |

## 10. 미결 항목

임의로 정하지 않는다. 정해지면 상태를 바꾸고 새 `BD-*`로 결정을 기록한다.

| ID | 질문 | 현재 | 출처 |
| --- | --- | --- | --- |
| BD-71 | dev DB에 #142·#182 레거시 SCHEDULED 지급 건이 남았는가, 남았다면 보정 방식 | 미확인. 별도 보정 Issue 권장, 엔진 재사용 분기는 권장하지 않음 | P3.13, A8.1 |
| BD-74 | 강의 지급 배치의 재시도 불가 오류 격리·알림, 판매자 탈퇴 후 최종 수익 정산 | 탈퇴 후 정책 미정 | P3.14 |
| BD-109 | 반환·지급 사유를 원장 `description`에 남길지 | 계약에 사유 필드 없음 | P7 |
| BD-127 | 참조 대상의 보류 상태 조회 API를 둘지 | 만들지 않는다(#479). 보상 판단은 무효화로 충분하다. 운영 확인·재확인에 필요해지면 다시 정한다 | C6 |
| BD-110 | 루트 AGENTS "모듈 간 통신은 이벤트 기본"의 예외로 동기 BillingPort를 적을지 | `BillingPort`는 #486에서 지웠다(BD-128). 루트 AGENTS 3장에 HP 이동 동기 HTTP 호출 예외가 이미 있다. PROGRESS 7장 항목은 갱신 필요 여부 확인 | P7 |
| (P7) | QueryDSL 도입·버전 | BD-38 | P3.8 |
| (P7) | `UserModifiedEvent`로 회원이 ADMIN이 될 때 지갑 처리 | 미정 | P7 |
| (P7) | 충전 최대 금액·PENDING 주문 만료, ERD에 `pg_payment_key_hash`·`failure_reason` 반영 | 미정 | P7 |

## 11. JVM 분리로 재검토할 결정

"이미 정해진 외부 방향" 열은 다른 문서에서 이미 정해진 것이다. Billing이 정할 수 없고 따라야 한다. 출처는 `docs/architecture/final-project-infrastructure.md`(#369, 아래 "인프라 문서")이고, 내용은 복사하지 않고 장 번호만 적는다.

| 결정 | 깨지는 전제 | 이미 정해진 외부 방향 | 재검토 질문 (TBD) | 기록 위치 후보 |
| --- | --- | --- | --- | --- |
| BD-51, BD-52, BD-53, BD-60, BD-107 | 강의·출연이 같은 JVM에서 `BillingPort`를 호출하고 Billing이 그 트랜잭션에 참여한다 | 내부 API로 전환(#271~#281). #486에서 `BillingPort`·Adapter를 지웠다(BD-128). BD-53은 지갑 안 호출(환불·환전 UseCase → 보류)에만 남는다. 앱 간 HTTP의 timeout·circuit breaker는 Resilience4j(인프라 문서 3장). database 분리 전에 보상 처리를 끝낸다(10장) | 원격 호출에서 원자성을 대신할 방식(호출 쪽 상태 + 멱등 재시도), 오류·타임아웃·결과 불명·circuit open 처리, shared 계약을 HTTP DTO로 바꾸는 범위 | `docs/adr/` (여러 모듈) |
| BD-64 | 강의 구매 = 주문 저장과 보류가 한 트랜잭션 | 보류 실패 → 주문 취소, 주문 실패 → 보류 반환(인프라 문서 10장 완료 증거) | 강의도 출연(BD-65)처럼 분리 트랜잭션 + 상태 복구로 갈지, 보상 반환의 요청 ID 규칙 | `docs/adr/` + 강의 결정 기록 |
| BD-72, BD-73 | 여러 모듈 테이블 락이 한 DB 트랜잭션 안에 있다 | 모듈별 database 분리 확정(#338, 인프라 문서 10장) → 한 트랜잭션 안의 모듈 간 락은 불가능해진다 | 모듈 간 락 순서를 폐기할지, Billing 안 락 순서만 남길지 | 이 문서 (Billing 쪽 결론) |
| BD-28, BD-54 | 내부 Java 호출이라 인증·예외 형식이 필요 없다 | 내부 API는 `X-Internal-Api-Key` 인증 유지, k3s에서는 Envoy route 없음 + NetworkPolicy(인프라 문서 3장) | 해소(#273): 400 응답은 BD-111, 인증·인가는 BD-112 | 9장 |
| BD-2, BD-6, BD-103 | 회원 이벤트가 같은 프로세스에서 전달된다 | Kafka(#339). member-app이 Modulith publication registry를 outbox로 `member.events.v1`(key 회원 ID)에 발행하고 `eventId`는 없다. 소비는 업무 자연 키로 멱등하고, 블로킹 재시도 3회 뒤 그룹별 DLT([`kafka.md`](../architecture/infrastructure/kafka.md) 소비·재시도와 DLT) | 해소(#428 결정, #430 구현): 소비 그룹 둘(`billing-app.member-replica`·`billing-app.wallet-creation`), 자연 키 멱등, inbox 테이블 없음 → BD-123. 기존 `event_publication` 기록 정리는 Spring Event 제거 때 | [ADR 0001](../adr/0001-context-topics-with-mixed-event-types.md), [ADR 0002](../adr/0002-modulith-externalization-without-event-id.md), BD-123 |
| BD-3, BD-102, BD-103 | 역할·회원 상태를 로그인 세션에서 바로 확인한다 | JWT 전환(#370~#372). HP가 움직이는 경로는 토큰 `role`만 믿지 않고 복제한 회원 상태를 함께 확인한다(인프라 문서 3장 인증 흐름 7) | 해소(#537): 충전 주문·승인·환불 신청·환전 신청에 지갑 회원 확인을 더했고, 복제본이 없으면 404로 거절한다. 보류·지급은 내부 API라 호출 앱이 확인한다 → BD-131 | BD-131, `docs/policy.md` 2장 |
| BD-66 | 출연 Fake 모드 | — | 코드에서 삭제됐는지 확인 후 상태 정리 | 이 문서 |

## 12. 결정 기록이 없는 구현 (코드에만 있음)

아래 기능은 코드와 테스트에 있지만 `PROGRESS.md` 결정 기록에 없다. 설계 이유를 확인하지 못해 결정으로 옮기지 않았다. 담당자가 이유를 확인해 주면 `BD-*`로 더한다.

| 기능 | Issue (코드 주석) | 코드에서 확인한 주요 규칙 | 근거 |
| --- | --- | --- | --- |
| 내 HP 거래 내역 | #295 | 작업 키 단위 합계, 14개 유형, 지갑 없으면 빈 페이지 | 미확인 |
| 수익 조회 | #295 | 매 요청 합산, 저장하지 않음, 한쪽 실패 = 전체 503, 깊이 1,000 | `docs/api/internal-earnings.md`(D63) |
| 환전 송금 실행·복구·관리자 조치 | #304 | 시도마다 새 UUID, 24시간 자동 확인 한도, FAILED 보류 유지, FINAL_FAILED 반환 | BD-101 일부 |
| 환전 정산 배치 | #305 | 매일 10~23시 5분, 100건 커서·실행당 1,000건, Client 없으면 건너뜀, 승인 시점에 회차 이동(`forApproval`) | BD-96 후속 계약 |
| 회원 Backfill | #324, #322 | 키셋 페이지, 최대 5,000명, 인스턴스 내부 동시 실행 금지 | BD-104와 충돌, 확인 필요 |
