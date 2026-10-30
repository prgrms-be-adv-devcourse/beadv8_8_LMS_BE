# 고도화 결정 사항 (2026-10-29)

확정된 기술 결정이다. 근거와 세부 설계는 [설계 문서](../final-design.md)에 있다.

- **배포·검증 규모**: chat-app은 replica 1개다. 단일 앱의 여러 사용자 세션과 재시작·구독 복구를 검증하고, 복수 replica 동작을 완료 조건으로 요구하지 않는다.

- **단체채팅**: 기존 1:1을 유지하면서 단체채팅을 이번 설계·구현·통합 검증에 포함한다. 세부 기능·정책은 설계 문서 3·8장을 따른다. 기존 1000명 안은 방 잠금·참여자 조회·메시지 전달 비용의 부하 검증 대상으로 삼고, 검증 전 달성을 보장하지 않는다.
- **회원 이벤트(Kafka)**: `common/shared`의 `UserJoinedEvent`·`UserModifiedEvent` 레코드를 그대로 `member.events.v1` 토픽에 싣는다. key는 회원 ID다. 별도 envelope·`eventId`를 만들지 않고, 멱등은 `createIfAbsent`와 `memberVersion` 비교로 한다. 토픽·key는 shared 레코드의 `@Externalized`로 지정한다(Q13, 사용자 결정 2026-10-29).
- **진행 방식(2026-10-29 사용자 변경 결정)**: 회원 이벤트 Kafka 전환, Redis Streams·Pub/Sub 전환, 누적 수신 커서를 병렬로 구현한다. 같은 날의 순차 진행 결정을 대체한다. 단체방은 이들의 통합 검증 뒤 진행한다.
- **실시간 전달(Redis, 2026-10-29 변경 결정)**: 사용자의 도입 요청으로 기존 Streams 미도입 결정을 대체한다. 메시지·시스템 안내·연결 제어를 업무 변경과 같은 트랜잭션의 PostgreSQL outbox에 기록하고, **outbox → Redis Streams → consumer group → Redis Pub/Sub → 로컬 STOMP**로 전달한다. DB relay는 적재 선점·lease 회수·재시도를, Stream consumer는 후속 발행·PEL 회수·ACK를 맡는다. 이는 목표 설계이며 구현 완료가 아니다.
- **구독자 0명(2026-10-29 사용자 결정)**: replica 1개에서 `PUBLISH` 구독자 0명은 자기 구독 단절이다. 발행 완료로 기록하지 않고 `XACK`하지 않으며, 구독 복구 뒤 backoff·횟수 상한 안에서 재시도한다.
- **다른 참여자 수신 상태 표시**: 이번 범위에서 제외한다. 조회 권한·노출 범위·주기(Q11)를 정한 뒤 별도로 한다.
- **완료·복구 경계**: outbox `ENQUEUED`는 XADD 성공, DB의 그룹별 `PUBLISHED` 체크포인트는 Pub/Sub 발행 성공, XACK는 Stream 처리 완료다. 사용자 수신 완료와 구분한다. 체크포인트는 사용자별 수신 이력이 아니며 Redis 유실 시 DB 원본과 미완료 작업을 대조해 재적재하는 근거다. 상세 상태·재시도·격리·보존·메모리 상한은 설계 문서 4.5·Q12를 따른다.
- **누적 수신 관리(2026-10-29 사용자 결정)**: 메시지별·수신자별 이력 대신 기존 PostgreSQL 참여 행에 `last_received_sequence`를 추가한다. 현재 참여 세대에서 인증된 클라이언트의 누적 ACK로만 전진시키고 읽음과 독립 관리한다. 초기·재입장·기존 데이터는 수신 미확인으로 시작한다. 각 기기는 자기 로컬 확인 위치로 이력을 복구하며 서버 회원 커서를 모든 기기의 수신으로 해석하지 않는다. ACK 병합·검증·실패·다중 기기·테스트 계약은 설계 문서 4.4를 따른다. 이번 구현 범위에 포함하는 목표이며 구현 완료는 아니다.
- **그룹과 모듈 경계**: 메시지 전달·연결 제어는 별도 Stream·그룹으로 나누고 단일 JVM의 제한된 worker로 처리한다. notification-app의 입력은 Kafka를 유지한다. 다른 기능이 같은 이벤트를 독립 소비할 때는 별도 그룹·공개 계약·보존 예산을 설계하며, 다른 앱에서 chat DB 참조를 직접 읽지 않는다.
- chat-app 구독자는 로컬 STOMP 전달 직전에 회원 자격·현재 참여·참여 세대·가시 범위를 다시 확인한다.
- Redis 채널·키 규칙과 Kafka 소비 설정은 chat-app이 소유한다. Kafka 소비는 인프라 문서 1장(`docs/architecture/infrastructure/kafka.md`)의 규칙을 따른다: 그룹 `<앱>.<목적>`, 시작점 earliest, 블로킹 재시도(1초에서 최대 10초 지수 증가, 3회), DLT `<토픽>.<그룹>.DLT`(chat-app이 `NewTopic`으로 선언, partition 3, 보존 7일, 원본과 같은 partition 번호). 회원 이벤트는 `chat-app.member-replica`, 출연 이벤트는 `chat-app.appearance-notification`이다. 운영 정책(재시도·DLT·역직렬화)은 공통 코드 없이 chat-app이 구현하고(`in/event/kafka`, Q9), `common/kafka`는 연결 설정만 둔다. `common/kafka`·`common/redis`에 채팅 전용 빈을 넣지 않는다.
- **설정 위치(사용자 결정, 2026-10-30)**: chat-app 전용 설정(`hapbang.chat.*`)의 기본값은 `@ConfigurationProperties` 코드 기본값으로 두고 `application.yaml`에 두지 않는다. 같은 키의 yaml·환경 변수가 덮는다. 주소·인증·TLS 같은 환경 값(예: `hapbang.clients.member.base-url`, `spring.kafka.bootstrap-servers`)은 yaml의 환경 변수 자리표시자·k8s에 둔다.
- **이벤트 소비 규칙(사용자 결정, 2026-10-30, 회원·출연 이벤트 공통)**: 모르는 종류는 건너뛴다. 역직렬화 실패·신뢰 밖 타입·검증 실패는 DLT. 처리기가 없는 종류(회원 쪽이 같은 토픽에 더한 새 이벤트 등)와 값이 없는 레코드는 오류로 보지 않고 로그만 남긴 뒤 offset을 넘긴다. 로그아웃 처리는 6장·Q8 결정 뒤에 만든다.
- **클래스패스에 없는 새 종류(사용자 결정, 2026-10-30)**: `__TypeId__`가 신뢰 패키지(회원 `com.hapbang.shared.member.event`, 출연 `com.hapbang.shared.appearance.event`) 바로 안의 클래스 이름인데 chat-app 클래스패스에 그 클래스가 없으면 역직렬화 실패로 보내지 않고 건너뛴다(같은 로그 규칙, offset 커밋). 회원 앱이 새 이벤트를 chat-app보다 먼저 배포하는 배포 순서 때문이다. 신뢰 검사는 넓히지 않는다(하위 패키지·신뢰 밖·타입 헤더 없음·클래스는 있는데 깨진 본문은 그대로 DLT). 한계: 신뢰 패키지 안의 잘못 적은 클래스 이름도 건너뛴다.
- 건너뛰는 것은 tombstone·클래스패스에 없는 신뢰 패키지 종류·패키지가 정확히 신뢰 패키지인 레코드뿐이다. 역직렬화기의 기본 신뢰 타입(`java.lang`·`java.util`)은 신뢰 밖으로 보고 재시도 없이 DLT로 보내며 값을 로그에 남기지 않는다. 건너뛴 종류의 로그 계수는 64종까지 따로 세고, 넘는 새 종류는 "기타 종류" 하나로 함께 센다.
- tombstone은 값 바이트가 없는 레코드뿐이다. 바이트가 있는데 읽은 결과가 null(`java.lang.Void`, JSON `null`)이면 역직렬화 실패로 DLT에 보낸다. DLT 발행 실패 로그에는 key·값을 남기지 않는다.
- **출연 알림(사용자 결정, 2026-10-30)**: 알림 모듈 이관 전 출연 알림은 `appearance.events.v1`의 Kafka 소비(`chat-app.appearance-notification`)로 유지하고, 알림 모듈 이관 때 옮긴다(Q7). 출연 요청 도착·출연 모집 지원 도착만 처리하고, 멱등은 기존 `(알림 종류, 출연 알림 대상 ID)` 유일 인덱스(`uk_chat_message_notification`)로 한다. 신뢰 패키지는 `com.hapbang.shared.appearance.event`만이다.
- **출연 알림 문구(사용자 결정, 2026-10-30)**: 서버는 출연 알림의 문구를 만들지 않고 사실만 저장·전달하며 FE가 템플릿으로 문구를 만든다. 값은 `chat_message.notification_data`(jsonb, 형식 버전 `v`, KST 일시 문자열, 금액 숫자) 한 컬럼에 저장하고, 응답에는 알림 종류마다 값 필드 하나(`appearanceRequestSent`·`appearanceApplicationSubmitted`)로 보낸다. 서버 안에서도 종류마다 record 하나이며 sealed·공통 상위 타입을 두지 않는다(사용자 결정 2026-10-30). 새 알림의 `content`는 빈 문자열이고, FE는 템플릿 → 비어 있지 않은 `content` → 일반 문구 순으로 보여 준다. 계약은 [알림 값 계약](../contracts/notification-data.md)이다.
- **HTTP 호출(사용자 결정, 2026-10-30)**: chat-app의 HTTP 호출은 `@HttpExchange`로 만든다. 회원 내부 API도 shared `MemberApiClient` 대신 chat-app 전용 인터페이스(`out/member/ChatMemberInternalApi`)로 부른다(D62의 예외, 사용자 결정 2026-10-30). 다른 모듈은 바꾸지 않는다. 경로·내부 API 키 헤더·RsData 해제·제한 시간·설정 키(`hapbang.clients.member.*`)·거절(4xx)/호출 불가(5xx·연결 실패·시간 초과·읽을 수 없는 응답) 구분은 공통 Client와 같게 지키고, chat-app은 shared의 공통 HTTP Client를 스캔하지 않는다.
- **데이터 접근(사용자 결정, 2026-10-30)**: chat-app의 DB 접근은 Spring Data JPA로 한다. PostgreSQL 전용 문장(`FOR UPDATE SKIP LOCKED`, `ON CONFLICT DO NOTHING`, `RETURNING`, 영향 행 수로 판단하는 조건부 UPDATE)은 repository의 `@Query(nativeQuery = true)`로 둔다. `JdbcClient`·`JdbcTemplate`이나 QueryDSL·jOOQ 같은 다른 데이터 접근 방식·라이브러리는 묻지 않고 도입하지 않는다.

- **오픈 직후 급증 대응**: 초기 사용자 수와 관계없이 단일 인기 방의 전송 폭주·동시 입장·재접속 폭주·Redis 적체를 출시 전 검증한다. 수용량을 넘는 요청은 DB 진입 전 제한하고, 큐·대기 시간·재시도·송신 버퍼에 상한을 둔다. 미측정 수용량을 지원한다고 선언하지 않는다(설계 문서 4.6·4.7).
- **잠금 구간 축소**: 전송 트랜잭션은 방·발신 참여 상태 재검증, 멱등 조회, 순번·메시지·outbox 원자 저장을 맡는다. 단체방 수신자 전체 조회·응답 조립·직렬화·외부 I/O는 이 구간에서 제외한다. 참여 변경 때 영속화한 불변 수신자 집합의 버전을 메시지와 함께 확정하고 커밋 뒤 펼친다. 최신 참여자 목록으로 바꿔 읽지 않는다.
- **확장 경계**: 순서·저장·전달 계약은 `app` 인터페이스로 분리한다. 방별 단일 작성자, 저장 분할, 영속 로그 기반 입력은 측정 결과에 따른 발전 후보다. 이번 구현·검증은 replica 1개에서 기존 Redis의 Streams·Pub/Sub을 사용하며 추가 서버나 새 미들웨어를 요구하지 않는다. Streams가 방 잠금·브라우저 수신 보장을 대체하지 않는다.

## 서비스 경계 규칙 (사용자 결정, 2026-10-29)

회원 이벤트 Kafka 전환과 Redis Streams·Pub/Sub 전환을 진행하면서 해당 서비스 경계를 인터페이스로 분리한다. 이 규칙은 chat-app에 적용하며, 각 전환에서 수정하는 서비스부터 적용한다.

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
| `app` | 전달 작업 적재·소비·회수·ACK 계약 | `ChatDeliveryQueue` |
| `out/redis` | 전달 작업 계약의 Redis Streams 구현체 | `RedisChatDeliveryQueue implements ChatDeliveryQueue` |
| `out/redis` | 실시간 전파 계약의 Redis 구현체 | `RedisRealtimeChatFanout implements RealtimeChatFanout` |
| `in/event` | Kafka 수신 후 Facade를 호출하는 진입 어댑터 | 회원 이벤트 Kafka listener |
| 테스트 소스 | 외부 시스템 없이 계약을 검증하는 구현체 | `InMemoryRealtimeChatFanout` |

의존 흐름 예시: `in → Facade → app 서비스 인터페이스 → app 업무 구현체 → app 외부 기능 인터페이스 → out 구현체`. Spring 설정에서 실제 구현체를 연결한다. 같은 계약의 운영 빈을 여러 개 등록할 때는 [공통 빈 규칙](../../../../docs/dev/module-integration.md)에 따라 주입 대상을 명시한다.
