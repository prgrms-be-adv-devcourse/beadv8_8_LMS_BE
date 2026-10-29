# 고도화 결정 사항 (2026-10-29)

확정된 기술 결정이다. 근거와 세부 설계는 [설계 문서](../final-design.md)에 있다.

- **배포·검증 규모**: chat-app은 replica 1개다. 단일 앱의 여러 사용자 세션과 재시작·구독 복구를 검증하고, 복수 replica 동작을 완료 조건으로 요구하지 않는다.

- **단체채팅**: 기존 1:1을 유지하면서 단체채팅을 이번 설계·구현·통합 검증에 포함한다. 세부 기능·정책은 설계 문서 3·8장을 따른다. 기존 1000명 안은 방 잠금·참여자 조회·메시지 전달 비용의 부하 검증 대상으로 삼고, 검증 전 달성을 보장하지 않는다.
- **회원 이벤트(Kafka)**: `common/shared`의 `UserJoinedEvent`·`UserModifiedEvent` 레코드를 그대로 `member.events.v1` 토픽에 싣는다. key는 회원 ID다. 별도 envelope·`eventId`를 만들지 않고, 멱등은 `createIfAbsent`와 `memberVersion` 비교로 한다.
- **실시간 전달(Redis)**: 메시지·시스템 안내·연결 제어를 업무 변경과 같은 트랜잭션의 PostgreSQL outbox에 기록한다. DB relay가 선점·lease 회수·재시도·실패 격리를 맡고 Redis Pub/Sub으로 단일 chat-app 구독자에게 전달한다. **Redis Streams는 구현하지 않는다.**
- chat-app 구독자는 로컬 STOMP 전달 직전에 회원 자격·현재 참여·참여 세대·가시 범위를 다시 확인한다.
- Redis 채널·키 규칙과 Kafka 소비 설정(역직렬화, 그룹, 재시도·DLT)은 chat-app이 소유한다. `common/kafka`·`common/redis`에 채팅 전용 빈을 넣지 않는다.

- **오픈 직후 급증 대응**: 초기 사용자 수와 관계없이 단일 인기 방의 전송 폭주·동시 입장·재접속 폭주·Redis 적체를 출시 전 검증한다. 수용량을 넘는 요청은 DB 진입 전 제한하고, 큐·대기 시간·재시도·송신 버퍼에 상한을 둔다. 미측정 수용량을 지원한다고 선언하지 않는다(설계 문서 4.6·4.7).
- **잠금 구간 축소**: 전송 트랜잭션은 방·발신 참여 상태 재검증, 멱등 조회, 순번·메시지·outbox 원자 저장을 맡는다. 단체방 수신자 전체 조회·응답 조립·직렬화·외부 I/O는 이 구간에서 제외한다. 참여 변경 때 영속화한 불변 수신자 집합의 버전을 메시지와 함께 확정하고 커밋 뒤 펼친다. 최신 참여자 목록으로 바꿔 읽지 않는다.
- **확장 경계**: 순서·저장·전달 계약은 `app` 인터페이스로 분리한다. 방별 단일 작성자, 저장 분할, 영속 로그 기반 입력은 측정 결과에 따른 발전 후보다. 이번 구현·검증은 replica 1개와 PostgreSQL outbox → Redis Pub/Sub을 유지하며 Redis Streams·추가 인프라를 도입하지 않는다.

## 서비스 경계 규칙 (사용자 결정, 2026-10-29)

회원 이벤트 Kafka 전환과 Redis Pub/Sub 전환을 진행하면서 해당 서비스 경계를 인터페이스로 분리한다. 이 규칙은 chat-app에 적용하며, 각 전환에서 수정하는 서비스부터 적용한다.

1. **계약은 `app`에 둔다.** Facade가 사용하는 서비스와 외부 기능을 요청하는 계약은 `com.hapbang.chat.app` 아래의 인터페이스로 정의한다. Facade의 생성자·필드는 구현 클래스 대신 이 인터페이스 타입을 사용한다.
2. **인터페이스는 역할로 이름 짓는다.** `ChatUserSyncService`, `ChatMessageService`, `RealtimeChatFanout`처럼 제공하는 기능을 드러낸다. `RedisService`, `KafkaService`, `InMemoryService`처럼 기술·저장 방식을 인터페이스 이름으로 쓰지 않는다. 이름은 역할을 설명하기 위한 예시이며 실제 이름은 담당 기능에 맞춘다.
3. **구현체는 책임에 맞는 계층에 둔다.** 업무 처리·트랜잭션 조정 구현체는 `app`, Redis·Kafka·DB 등 외부 시스템에 접근하는 구현체는 `out`, HTTP·STOMP·Kafka 수신 진입 어댑터는 `in`에 둔다. Redis 구현체(`RedisRealtimeChatFanout` 등)는 `out/redis`에 둔다. 구현체가 인터페이스를 구현하며, 인터페이스를 구현체 패키지로 옮기지 않는다.
4. **Facade는 구현 기술을 선택하지 않는다.** 업무 서비스 구현체는 필요한 `app` 계약을 통해 외부 어댑터를 호출한다. Redis·Kafka 템플릿, 채널·토픽과 직렬화 타입은 구현체가 소유한다. Facade나 서비스 계약에 노출하거나 Facade에서 직접 구현체를 생성·형변환·분기하지 않는다.
5. **기술 이름은 구현체에서 쓴다.** Redis 구현은 `RedisRealtimeChatFanout`, 테스트용 메모리 구현은 `InMemoryRealtimeChatFanout`처럼 역할과 구현 방식을 함께 드러낸다. in-memory 구현은 테스트 소스에 두고 운영의 자동 대체 경로로 등록하지 않는다.
6. **기존 처리 경계를 보존한다.** 권한 검사·재시도 조정 등 Facade의 기존 책임과 서비스 구현체의 트랜잭션 경계를 확인하며 추출한다. 메시지·outbox의 원자 저장, 커밋 뒤 전달, Redis I/O 중 DB 트랜잭션을 유지하지 않는 규칙을 보존한다. 인터페이스 추출을 이유로 모든 서비스를 한 트랜잭션으로 묶지 않는다.

배치 예시:

| 위치 | 종류·역할 | 예시 |
| --- | --- | --- |
| `app` | Facade가 주입받는 업무 서비스 인터페이스 | `ChatUserSyncService`, `ChatMessageService` |
| `app` | 업무 규칙·트랜잭션을 처리하는 구현체 | `DefaultChatUserSyncService`, `DefaultChatMessageService` |
| `app` | 실시간 전파를 요청하는 인터페이스 | `RealtimeChatFanout` |
| `out/redis` | 실시간 전파 계약의 Redis 구현체 | `RedisRealtimeChatFanout implements RealtimeChatFanout` |
| `in/event` | Kafka 수신 후 Facade를 호출하는 진입 어댑터 | 회원 이벤트 Kafka listener |
| 테스트 소스 | 외부 시스템 없이 계약을 검증하는 구현체 | `InMemoryRealtimeChatFanout` |

의존 흐름 예시: `in → Facade → app 서비스 인터페이스 → app 업무 구현체 → app 외부 기능 인터페이스 → out 구현체`. Spring 설정에서 실제 구현체를 연결한다. 같은 계약의 운영 빈을 여러 개 등록할 때는 [공통 빈 규칙](../../../../docs/dev/module-integration.md)에 따라 주입 대상을 명시한다.
