# 알림(notification) 모듈 — AGENTS.md

> 문서 탐색: [알림 문서 INDEX](../../../../docs/notification/INDEX.md)

auxiliary-app 안 알림 모듈(알림센터)에서 에이전트와 팀원이 지킬 개발 규칙이다.

- **현재 상태(2026-11-09)**: Gradle 모듈 `:notification`이다(N1 #797). 회원 복제본과 Kafka 소비·DLT 공통 설정만 있고 알림 생성 코드는 없다. 구현 범위는 [`docs/notification/module.md`](../../../../docs/notification/module.md) 2장, 진행 상태는 [`PROGRESS.md`](PROGRESS.md)에 있다.
- **규칙 상속 순서**: 루트 [`AGENTS.md`](../../../../AGENTS.md) → [`apps/auxiliary-app/README.md`](../../README.md) → 이 문서. 이 문서와 상위 문서가 충돌하면 상위 문서를 따르고, 충돌을 보고한다.
- 이 문서는 규칙만 적는다. 결정의 이유·대안·이력은 [`docs/notification/decisions.md`](../../../../docs/notification/decisions.md)의 `ND-*` 번호로 참조하고 여기에 옮기지 않는다.

## 1. 책임과 범위

- 알림 모듈은 알림센터를 맡는다: 종 아이콘 목록, 안 읽은 수 배지, 읽음 처리(ND-001).
- 앱 안에서만 보낸다. 이메일·푸시, 로그인하지 못한 회원에게 가야 하는 알림은 만들지 않는다(ND-002).
- 받는 사람은 유저·인플루언서·인증된 인플루언서다. 관리자 알림은 만들지 않는다(ND-003).
- **챗봇 모듈과 분리**: 같은 앱(auxiliary-app)에 있어도 `:chatbot` 코드를 직접 참조하지 않는다. 모듈 사이 연동은 Spring 이벤트로만 한다(auxiliary-app README "모듈 경계"·"모듈 사이 이벤트", ND-006).
- **채팅 알림과의 경계**: 출연 알림 메시지·단체방 안내·`/user/queue/chats/notifications`는 채팅이 소유한다. 옮기거나 없애지 않고, 채팅 계약(`NotificationKind`, `notification_data`)을 이 모듈 문서·코드로 옮기지 않는다. 채팅 새 메시지와 차단은 알림센터에 넣지 않는다(ND-004, decisions.md 12장).

## 2. 문서 읽는 순서와 역할

작업 전에 위에서부터 읽는다.

| 순서 | 문서 | 역할 |
| --- | --- | --- |
| 1 | [`docs/policy.md`](../../../../docs/policy.md) | 업무 규칙 원본(SoT). 알림센터 장은 아직 없다. 장을 더하는 일은 팀 승인을 받는 후속 작업이고, 그때까지 알림센터 정책은 decisions.md에만 있다 |
| 2 | [`docs/notification/decisions.md`](../../../../docs/notification/decisions.md) | 결정·이유·상태(`ND-*`), 미결(U1~U19), 팀 결정 필요(T1~T8) |
| 3 | [`docs/notification/CONTEXT.md`](../../../../docs/notification/CONTEXT.md) | 배경과 도메인 맥락 |
| 4 | [`docs/notification/module.md`](../../../../docs/notification/module.md) | 모듈 구조(현재 상태와 제안 To-Be) |
| 5 | [`docs/notification/notification-catalog.md`](../../../../docs/notification/notification-catalog.md) | 알림 종류·받는 사람·발생 조건 |
| 6 | [`docs/api/notification.md`](../../../../docs/api/notification.md) | 외부 API·WebSocket 계약 |
| 7 | [`docs/notification/test-spec.md`](../../../../docs/notification/test-spec.md) | 테스트 검증 기준 |
| 8 | [`docs/notification/regression-log.md`](../../../../docs/notification/regression-log.md) | 테스트 실행 기록 |
| 9 | [`PROGRESS.md`](PROGRESS.md) | 진행 상태·작업 계획·차단 요소 |

알림이 발행 모듈에 요청하는 이벤트 계약은 [`docs/notification/event-contracts.md`](../../../../docs/notification/event-contracts.md)다(ND-037). Kafka 공통 규칙은 [`docs/architecture/infrastructure/kafka.md`](../../../../docs/architecture/infrastructure/kafka.md), 원천 설계는 [`docs/architecture/infrastructure/notification.md`](../../../../docs/architecture/infrastructure/notification.md)다.

## 3. 구현 규칙

결정(`ND-*`)이 있는 것만 적는다. 여기에 없는 구현 선택은 4장을 따른다.

### 3.1 모듈과 저장소

- Gradle `:notification`, 패키지 `com.hapbang.auxiliary.notification`, 디렉터리 `boundedContext/notification`이다. `:auxiliary-global`·`:shared`에만 의존한다(ND-006).
- 저장소는 database `auxiliary` 안의 schema `notification`이다. 엔티티는 `@Table(schema = "notification")`, SQL의 테이블 이름에도 schema를 붙인다. 다른 모듈 schema와 JOIN·FK·같은 트랜잭션 쓰기를 하지 않는다. 첫 SQL은 `CREATE SCHEMA IF NOT EXISTS notification;`이다(ND-007).
- 테이블은 `notification`, `notification_payout_item`, `notification_schedule`, `notification_user` 넷이다(ND-025). 컬럼·인덱스 세부는 미결 항목(U9·U19)을 정한 뒤 확정한다.

### 3.2 입력

- 입력은 다른 앱의 Kafka integration event뿐이다. 발행 모듈에 알림 문구·받는 사람을 요구하지 않는다. 이벤트에는 사실만 담는다(ND-008).
- 채팅 내부 Redis Streams를 구독하지 않는다. 독립 consumer group으로 소비한다(ND-009).
- 처리하지 못한 메시지는 `<topic>.<group>.DLT`로 보낸다. 받지 않는 타입은 기본 `@KafkaHandler`로 무시하고 커밋한다(ND-010, kafka.md "소비"·"재시도와 DLT").
- 받는 사람 상태·역할·닉네임은 회원 복제본 `notification_user`에서 읽는다. 탈퇴한 회원이면 알림과 예약을 만들지 않는다(ND-018). 정지 회원 처리는 미결(U8).
- 회원 복제본은 가입 이벤트로 만들고, 복제본이 없을 때 온 변경 이벤트로도 만든다(있으면 더 큰 버전만 반영, ND-049). 이것은 첫 배포 Backfill(U17)을 대체하지 않는다.
- 받는 사람 복제본이 아직 없으면 알림을 만들지 않고 처리를 실패시켜 kafka.md "재시도와 DLT"대로 재시도 뒤 DLT로 보낸다(ND-035). 첫 배포 Backfill은 미결(U17).

### 3.3 생성·저장

- 같은 이벤트를 다시 받아도 유일 인덱스 (원본 이벤트 종류, 원본 대상 ID, 받는 사람)로 한 번만 만든다. 묶음 알림은 (종류, 받는 사람, 묶음 키)다. 삭제된 행도 유일 인덱스에 남긴다(ND-019).
- 원본 대상 ID는 ND-033·ND-045를 따른다. 새로 요청하거나 값을 더하는 이벤트의 사건 ID는 `docs/notification/event-contracts.md`에 있다. 이미 발행 중인 이벤트의 필드는 미결(U5)이므로 그 이벤트를 소비하는 작업에서 발행 계약을 확인한 뒤 정한다.
- 문구는 알림 모듈이 만들어 저장하고, 값(`jsonb`, 형식 버전 `v`)도 함께 저장한다(ND-020). 종류별 문구는 N2 구현 때 정한다(ND-039, 실제 문구는 U3).
- 다른 회원의 닉네임을 문구에 넣으면 값에 그 회원 ID를 함께 저장하고, 그 회원의 `UserMaskedEvent`를 받으면 문구·값의 닉네임을 바꾼다(ND-036). 저장 구조와 갱신 방법은 미결(U19).
- 종류 코드는 shared 이벤트 이름에서 `Event`를 뺀 UPPER_SNAKE다(ND-012). 규칙 밖 코드는 미결(U11). 단체방 3종 코드는 채팅 안내와 이름이 같지만 뜻이 다르다(ND-043).
- 목록은 이벤트의 업무 시각(발생 시각)으로 정렬한다. 받은 순서로 정렬하지 않는다(ND-021).
- 보존은 회원별 삭제되지 않은 최신 20개다. 새 알림을 저장하는 **같은 트랜잭션 안에서** 21번째부터 Soft Delete한다. Hard Delete하지 않는다(ND-022, policy.md 7장 "삭제 정책").
- 대상이 삭제돼도 알림은 남긴다(ND-024).
- 강의 지급 하루 묶음은 ND-032, 지급 알림 분리는 ND-013을 따른다.

### 3.4 조회·읽음·API

- 모든 API는 로그인 회원 본인의 알림만 다룬다. 다른 회원의 알림 ID로 요청하면 받는 사람이 본인인지 확인한다(ND-026, ND-018).
- 경로는 `/api/v1/notifications` 아래다(ND-026). 세부 계약은 [`docs/api/notification.md`](../../../../docs/api/notification.md)를 따른다.
- 알림을 눌렀을 때만 읽음으로 바꾼다. 목록 조회로 읽음 처리하지 않는다(ND-023).
- 응답·오류 형식은 루트 AGENTS 7장과 [`docs/api/conventions.md`](../../../../docs/api/conventions.md)를 따른다. 모듈에 `@RestControllerAdvice`를 만들지 않는다.

### 3.5 실시간 전달

- 저장 트랜잭션이 **commit된 뒤** Redis Pub/Sub으로 발행하고, 알림 모듈 자체 STOMP의 `/user/queue/notifications`로 보낸다. chat-app 연결을 빌리지 않는다(ND-027).
- 실시간 전달은 유실될 수 있다. DB가 원천이고, 재접속하면 REST 목록으로 맞춘다(ND-027).
- STOMP 연결 티켓·Pub/Sub·Kafka 소비 설정은 채팅 코드를 복사해 쓴다. 다른 앱 코드를 의존하지 않는다(ND-028). 토큰 만료 헤더(U15)와 Gateway 경로(U14)는 미결이다.

### 3.6 예약 알림

- 예약은 알림 모듈이 이벤트를 받아 만들고 취소 이벤트로 취소한다. 보내기 직전에 상태를 다시 확인한다(ND-029~ND-031). 운영 시간과의 관계는 미결(U6).
- 예약이 없을 때 온 취소는 취소 상태 행을 먼저 남긴다(ND-034). 짝 키(대상 ID, 받는 사람)는 `docs/notification/event-contracts.md` 0.3에 있다. 실제 생성·취소 이벤트가 그 값을 주는지 확인하지 않은 예약 알림에는 적용하지 않는다(U16).

### 3.7 트랜잭션 경계 (확정된 범위만)

| 경계 | 규칙 | 근거 |
| --- | --- | --- |
| 알림 저장 | 알림 저장과 21번째부터 Soft Delete를 한 트랜잭션에서 한다 | ND-022 |
| 실시간 전달 | 저장 commit 뒤에 Redis Pub/Sub으로 발행한다. 트랜잭션 안에서 발행하지 않는다 | ND-027 |
| 그 밖 | 멱등 기록과 offset commit 순서는 kafka.md "소비"의 공통 규칙을 따른다. 그 밖의 경계는 정하지 않았다. 질문한다 | kafka.md |

## 4. 임의로 정하지 않는 것

- 미결 U1~U19과 팀 결정 필요 T1~T8은 [decisions.md 10장](../../../../docs/notification/decisions.md#10-미결-항목)에 있다. 해당 작업에 닿으면 구현하지 말고 결정 주체에게 질문한다. 질문과 결과는 기록한다(루트 AGENTS 10장). 결정되면 decisions.md에 새 `ND-*`로 먼저 적는다.
- **consumer group 이름**은 `auxiliary-app.notification-<목적>`이다(ND-047). 새 목적의 그룹을 더하면 kafka.md 토픽 표 반영을 팀에 요청한다.
- **패키지 구조**는 제안 단계다([`module.md`](../../../../docs/notification/module.md)의 제안 To-Be). 루트 AGENTS 11장에 따라 코드 컨벤션은 팀 합의 전이므로 확정된 규칙으로 쓰지 않는다.
- 발행 모듈 이벤트 계약은 알림 담당이 정해 이벤트 계약 요청 문서에 두고, 발행 담당이 Issue로 받아 구현한다(ND-037). 발행 모듈 코드나 `common/shared` 이벤트를 알림 작업에서 임의로 만들거나 고치지 않는다.
- 테스트 방식은 루트 AGENTS 11장에 따라 미정이다. [`test-spec.md`](../../../../docs/notification/test-spec.md)의 기준 밖은 질문한다.

## 5. 모듈을 실제로 만들 때

- auxiliary-app README "모듈 추가 방법" 1~7단계를 따른다(`settings.gradle.kts`의 `auxiliaryModules`, 모듈 `build.gradle.kts`, 앱 의존, `package-info.java`, 첫 Flyway SQL, Gateway route, `verifyModuleStructure :auxiliary-app:check`).
- 이 문서의 결정 범위 밖(예: WebSocket 경로의 Gateway 시간 제한, 자원 예산 U13)은 단계를 진행하기 전에 질문한다.
- Flyway 버전은 작성 시각 `V{yyyyMMddHHmm}__...`이고, 같은 분에 다른 모듈 SQL과 겹치지 않는지 병합 전에 확인한다(README "Flyway").

## 6. 무단 변경 금지

다음 팀 공유 문서는 알림 작업에서 고치지 않는다. 고칠 필요가 있으면 이유와 함께 먼저 확인한다.

- 루트 `AGENTS.md`, `docs/policy.md`, `docs/GLOSSARY.md`
- `docs/architecture/infrastructure/kafka.md`, `docs/architecture/final-project-infrastructure.md`
- 채팅 문서(`apps/chat-app/docs/**`)와 채팅 코드
- `apps/auxiliary-app/README.md`, 챗봇 모듈(`boundedContext/chatbot`)

## 7. 문서 작성 규칙

- 이 문서와 PROGRESS.md에 이유·이력 문장을 쓰지 않는다. 결정은 `ND-*` 번호로 참조한다.
- 작업을 마치면 decisions.md(결정), PROGRESS.md(진행 상태)를 갱신한다.
