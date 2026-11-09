# 1. Kafka — 모듈 간 integration event

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 도입 결정은 #369, 토픽·발행·소비 구조는 #339에서 정했다. 되돌리기 어려운 결정은 ADR에 있다.

- [ADR 0001](../../adr/0001-context-topics-with-mixed-event-types.md): 발행 컨텍스트마다 토픽 하나에 이벤트 타입을 섞는다.
- [ADR 0002](../../adr/0002-modulith-externalization-without-event-id.md): Modulith externalization으로 발행하고 `eventId` 없이 자연 키로 멱등 처리한다.

이 문서의 운영 정책(재시도·DLT·역직렬화)은 공통 코드 없이 각 앱이 따라 구현한다. `common/kafka`는 연결 설정만 둔다.

## 적용 범위

Kafka에는 모든 Spring Event를 보내지 않고, 컨텍스트 밖에서 의미가 있는 integration event만 보낸다.

시연하기 좋은 흐름은 다음과 같다.

1. 회원 상태·역할 변경 → 출연·강의·채팅의 회원 복제 데이터 갱신
2. 출연·강의·지갑·회원·고객센터 이벤트 → notification 모듈의 알림 생성([8장](notification.md))과 감사 projection
3. 강의 구매 확정·지급 예정 → 정산 projection

HP 원장을 Kafka consumer가 직접 확정하게 만들지는 않는다. 원장 변경은 PostgreSQL 트랜잭션과 멱등키가 책임지고, Kafka는 완료된 사실을 다른 컨텍스트에 전달한다.

## 토픽 목록

| 토픽 | 발행 앱 | 이벤트 타입 | key | 소비 그룹 |
| --- | --- | --- | --- | --- |
| `member.events.v1` | member-app | `UserJoinedEvent`, `UserModifiedEvent`, `UserMaskedEvent` | 회원 ID | `appearance-app.member-replica`, `billing-app.member-replica`, `billing-app.wallet-creation`, `lecture-app.member-replica`, `chat-app.member-replica` |
| `appearance.events.v1` | appearance-app | `AppearanceRequestSentEvent`, `AppearanceApplicationSubmittedEvent`, `AppearanceReviewPublishedEvent`, `AppearanceChangedEvent`(#713) | `appearance-request:<요청 ID>`, `appearance-application:<지원 ID>`, `appearance-review-reviewee:<리뷰 받은 회원 ID>`, `appearance:<출연 ID>` | `chat-app.appearance-notification`, `member-app.appearance-review` (리뷰 공개, #555), `auxiliary-app.chatbot-appearance-copy` (AI 추천의 출연·리뷰 사본, 출연 변경·리뷰 공개만 쓰고 나머지는 건너뜀, #716) |
| `billing.events.v1` | billing-app | `ChargeCompletedEvent`, `PayoutCompletedEvent`, `RefundCompletedEvent`, `ExchangeCompletedEvent`, `ExchangeRejectedEvent`, `ExchangePayoutFailedEvent`, `ExchangeFinalFailedEvent` | `point-charge:<충전 ID>`, `settlement:<지급 건 ID>`, `point-refund-request:<환불 요청 ID>`, `exchange:<환전 ID>` | 없음 (알림 소비는 #193에서 정한다, #497) |
| `lecture.events.v1` | lecture-app | `LectureObjectionResolvedEvent`, `LectureObjectionWithdrawnEvent`, `LectureObjectionRequestRefusedEvent`, `LectureObjectionReturnCompletedEvent`, `LectureObjectionReturnActionRequiredEvent`, AI 추천용 동기화 `LectureChangedEvent`, `LecturePurchaseChangedEvent`, `CartItemChangedEvent`, `LectureReviewChangedEvent` (#712) | `order-item:<주문 항목 ID>`(이의 제기·구매), `lecture:<강의 ID>`, `cart-item:<장바구니 항목 ID>`, `lecture-review:<리뷰 ID>` | `customer-support-app.objection-result` (#279, 동기화 이벤트는 무시), aux-app AI context(동기화 이벤트, 별도 이슈) |
| `customersupport.events.v1` | customer-support-app | `LectureObjectionResolutionRequestedEvent`, `LectureObjectionWithdrawalRequestedEvent` | `inquiry:<문의 ID>` | `lecture-app.objection-command` (#278) |

1차 범위는 지금 다른 앱에 소비자가 있는 이벤트만이다. `UserLoggedOutEvent(userId, loggedOutAt)`는 #539에서 `member.events.v1`에 올렸다(chat이 회원 단위로 연결을 닫는다, #501). `UserMaskedEvent(userId, maskedAt)`는 #594에서 올렸다(탈퇴 회원 파기 신호. 지갑의 환전 계좌, 출연의 호스트 채널 주소처럼 회원 앱 밖의 파기 대상을 받은 앱이 지운다. 지갑은 #674에서 기존 그룹 `billing-app.member-replica`로, 출연은 #675에서 기존 그룹 `appearance-app.member-replica`로 받는다. 다른 앱의 소비는 각 앱 이슈). 출연 변경 이벤트 `AppearanceChangedEvent(AppearanceSyncDto)`는 #713에서 key `appearance:<출연 ID>`로 올렸다(AI 추천의 읽기용 사본. 출연의 모든 상태 전이에서 바뀐 뒤 상태와 version을 싣는다, [출연 동기화 내부 API](../../api/internal-appearance-sync.md)). 출연 취소 이벤트(요청·수락·거절)는 소비자가 생길 때 같은 key로 더한다. 소비 그룹 이름은 구현할 때 이 표를 고친다.

## 토픽 규칙

- 이름은 `<발행 컨텍스트>.events.v<N>`이다. 토픽 하나에 그 컨텍스트의 이벤트 타입을 섞는다([ADR 0001](../../adr/0001-context-topics-with-mixed-event-types.md)). 이름에 소비자 관점의 말(예: `notification`)을 넣지 않는다.
- key는 이벤트가 다루는 대상의 ID다. 같은 토픽에서 ID 종류가 여럿이면 번호가 겹치지 않게 종류 접두어를 붙인다(`appearance-request:12`). 회원 이벤트는 회원 ID만 쓴다. key가 같은 메시지끼리만 순서가 지켜진다.
- 토픽은 발행 앱이 `NewTopic` 빈으로 선언한다. broker의 자동 생성은 끈다(`auto.create.topics.enable=false`).
  - 예외: prod의 `member.events.v1`은 member-app의 선언(#416) 전이라 2026-10-30에 Redpanda Console로 손으로 만들었다(partition 3, replication factor 1, `cleanup.policy=delete`, 보존은 broker 기본값). #416은 같은 설정으로 선언한다. Spring Kafka(`KafkaAdmin`)는 실제 partition이 선언보다 적을 때만 늘리고, 많으면 줄이지 않는다. 손으로 만든 토픽의 partition이 3보다 많으면 선언과 어긋난 채 남는다.
- partition은 3개다. 한 그룹에서 동시에 일하는 consumer는 partition 수를 넘지 못한다.
- 보존은 compaction 없이 시간 기준이다(72시간, partition당 256MiB, broker 기본값). 보존 기간보다 오래된 데이터가 필요한 새 소비자는 자기 초기 적재로 채운다.
- 환경 prefix는 붙이지 않는다. EC2에는 prod만 있다(#369).

## 발행

- Spring Modulith event externalization을 쓴다([ADR 0002](../../adr/0002-modulith-externalization-without-event-id.md)). 앱의 publication registry가 outbox다. 업무 코드는 지금처럼 `publishEvent`만 부른다.
- 토픽과 key는 shared 레코드의 `@Externalized("<토픽>::#{<key 식>}")`로 지정한다.
- payload는 `common/shared`의 이벤트 레코드를 그대로 JSON으로 싣는다. 별도 envelope 레코드를 만들지 않는다.
- 헤더는 `eventType`(레코드 이름, 예: `UserModifiedEvent`)과 `eventVersion`(이벤트 타입의 payload 버전, 예: `1`) 두 개를 UTF-8 문자열로 싣는다. `EventExternalizationConfiguration.headers(...)`로 넣는다. Spring Kafka JSON 직렬화가 붙이는 `__TypeId__`(레코드 클래스 이름)는 역직렬화에 쓴다.
- `eventId`·`occurredAt`은 두지 않는다. 헤더 함수가 이벤트만 받아 재발행 때 같은 값을 만들 수 없다. 추적은 관측성(#343)의 trace 헤더가 맡는다.
- trace 헤더 `traceparent`(W3C)는 Spring Kafka observation이 보낼 때마다 붙인다(#630, [관측성](observability.md#앱-trace-계측-579-631-630)). 발행 계약이 아니므로 소비자는 업무에 쓰지 않는다. 받는 앱은 이 헤더를 부모로 consumer span을 만든다.
- Modulith는 기동할 때만 미완료 publication을 다시 보낸다(`republish-outstanding-events-on-restart`). 실행 중 재전송은 **발행하는 앱마다 반드시 둔다.** 지금 앱들은 `IncompleteEventPublications.resubmitIncompletePublications(Predicate)`로 자기 앱이 내보내는 이벤트·발행 후 경과 시간·건수를 거른다(billing·출연·강의·고객센터, 매분·1분·100건). 회원은 `resubmitIncompletePublications(ResubmissionOptions)`로 **실패(FAILED)한 기록만** DB에서 건수를 제한해 읽어 다시 보낸다(매분·1분·500건, #421). `Predicate` 방식은 미완료 기록 전부(아직 전송 중인 것 포함)를 읽어 메모리에서 거르므로, 전송 중인 기록을 겹쳐 보내고 장애가 길면 매분 전부를 읽는다. 회원은 대상 필터를 두지 않는다. Modulith가 필터를 DB에서 읽은 뒤에 적용해, 걸러지는 실패 기록이 batchSize만큼 쌓이면 보낼 기록이 영영 읽히지 않기 때문이다(회원 앱은 같은 JVM 리스너가 없어 실패 기록은 모두 Kafka 전송). 실행 중 재발행은 실패한 기록만 다루므로, 전송 중(PROCESSING)에 앱이 죽어 남은 기록은 재기동 때 보낸다. 주기와 기준 시간은 앱이 정한다. replica 여럿이 같은 publication을 다시 보내도 소비 멱등이 흡수하므로 분산 락은 두지 않는다.

## 소비

- 그룹 이름은 `<앱>.<목적>`이다(예: `billing-app.wallet-creation`). 한 앱의 리스너 여러 개가 같은 그룹을 쓰면 partition을 나눠 가져 각자 메시지 일부만 받는다. 목적이 다르면 그룹을 나눈다.
- 클래스 레벨 `@KafkaListener`에 받을 타입마다 `@KafkaHandler`를 둔다. 모르는 타입은 `@KafkaHandler(isDefault = true)`에서 무시하고 커밋한다.
- 역직렬화는 `ErrorHandlingDeserializer`로 감싼 JSON 역직렬화다. 신뢰 패키지는 실제 이벤트 패키지(예: `com.hapbang.shared.member.event`)만 지정하고 `*`를 쓰지 않는다. 깨진 메시지는 재시도 없이 DLT로 간다.
- **native 소비자는 `eventType` 헤더로 타입을 정한다(#722).** native 이미지는 등록하지 않은 클래스를 실행 중에 찾지 못한다. 받는 타입은 `@KafkaHandler` 파라미터라 Spring AOT가 등록하지만, 모르는 타입은 `__TypeId__`로 클래스를 찾다 역직렬화에 실패해 재시도 없이 DLT로 간다. 그래서 native 소비자는 다음처럼 한다.
  - `eventType` 값 → 받을 클래스 표를 가진 static 메서드를 `spring.json.value.type.method`로 넘긴다. 표의 값은 발행 앱의 `eventType` 상수와 같아야 하고, 발행 앱은 레코드 이름을 바꿔도 이 값을 그대로 둔다.
  - 표에 없는 값은 클래스를 찾지 않고 필드 없는 무시용 레코드로 읽어, 그 타입의 `@KafkaHandler`에서 무시하고 커밋한다. 헤더가 없으면 null을 돌려 지금처럼 `__TypeId__`로 찾는다(받을 이벤트를 조용히 버리지 않기 위해서다).
  - 그 메서드는 역직렬화기가 reflection으로 부르므로 native hints에 등록한다. 발행 앱이 같은 토픽에 새 타입을 더해도 소비자의 hints는 고치지 않는다.
  - 지금 native 소비자는 customer-support-app(`LectureEventTypeResolver`) 하나다. 확인 방법은 [Native Image](native-image.md#모르는-이벤트-타입-확인-722)에 있다.
- 새 그룹의 시작점은 `auto-offset-reset=earliest`다.
- 강의·고객센터 소비자(#278·#279)는 `allow.auto.create.topics=false`를 둔다. 운영 broker는 자동 생성이 꺼져 있어 차이가 없지만, 자동 생성이 켜진 테스트 broker에서 소비자가 먼저 붙으면 partition 1개짜리 토픽이 생겨 발행 앱의 선언(3개)과 어긋나고 다른 테스트가 메시지를 받지 못한다.
- 토픽이 아직 없어도 소비 앱은 뜬다(`spring.kafka.listener.missing-topics-fatal` 기본값 `false`). 소비자는 `UNKNOWN_TOPIC_OR_PARTITION` 경고를 남기며 기다리다가, 발행 앱이 토픽을 만들면 바로 붙어 처음부터 읽는다. 로컬 k3d에서 소비자를 먼저 띄우고 15초 뒤 토픽을 만들어 1초 안에 메시지를 받는 것을 확인했다(#429). 그래서 발행 앱과 소비 앱의 기동 순서를 맞추지 않는다. 기다리는 동안 경고는 약 1초마다 남는다(prod lecture-app 12분에 721건, 2026-10-30). 토픽이 오래 없으면 로그 양을 보고 토픽을 먼저 만든다.
- 멱등은 업무 자연 키로 한다. 상태 복제 이벤트는 `(대상 ID, 원본 버전)`(회원은 `user.id`, `user.version`), 한 번만 일어나는 사실은 `(eventType, 대상 ID)`다. 업무 반영과 멱등 기록은 같은 로컬 트랜잭션에서 하고, offset은 그 커밋 뒤에 커밋한다.
- Modulith 전송은 같은 key라도 순서가 뒤바뀔 수 있다. 순서가 중요한 소비자는 원본 버전으로 늦은 이벤트를 거른다.

## 재시도와 DLT

- 재시도는 블로킹 방식(`DefaultErrorHandler`)이다. 1초에서 시작해 최대 10초까지 지수로 늘리며 3회 재시도한 뒤 DLT로 보낸다. 재시도하는 동안 그 partition의 뒤 메시지는 기다리므로 같은 key의 순서가 지켜진다. non-blocking 재시도(`@RetryableTopic`)는 순서를 깨므로 쓰지 않는다.
- 역직렬화 실패·검증 실패처럼 재시도해도 결과가 같은 예외는 재시도 없이 바로 DLT로 보낸다.
- DLT 이름은 `<토픽>.<그룹>.DLT`다(예: `member.events.v1.billing-app.wallet-creation.DLT`). 그룹마다 실패를 따로 모아 어느 소비자가 실패했는지 구분한다. Spring Kafka 기본값(`<토픽>.DLT`)은 쓰지 않는다.
- DLT 토픽은 실패 처리를 소유한 소비 앱이 `NewTopic`으로 선언한다. partition은 원본과 같은 3개이고 원본과 같은 partition 번호로 보낸다. 보존은 7일(`retention.ms=604800000`)이다.
- DLT 발행이 실패하면 처리 성공으로 간주하지 않는다. offset을 커밋하지 않고 다시 시도한다.
- DLT로 보내는 template·리스너 컨테이너 팩토리를 앱이 직접 만들면 Boot의 `spring.kafka.template.observation-enabled`·`spring.kafka.listener.observation-enabled`가 적용되지 않는다. template은 `setObservationEnabled(true)`와, 빈이 아니면 `setObservationRegistry(...)`까지, 팩토리는 `getContainerProperties().setObservationEnabled(true)`를 직접 넣는다(#630). 빠지면 DLT 전송·소비가 trace에 남지 않는다.
- DLT 발행용 producer factory나 소비 ConsumerFactory를 앱이 직접 만들면, Boot가 자기 factory에 적용하는 customizer(`DefaultKafkaProducerFactoryCustomizer`·`DefaultKafkaConsumerFactoryCustomizer`)를 똑같이 적용한다. 적용하지 않으면 그 factory의 Kafka client 지표(DLT 전송, 소비 lag)가 빠진다. 빈이 아닌 DLT factory에는 이름 `deadLetterProducerFactory`를 붙여 지표의 `spring_id`로 업무 발행 producer와 구분한다(#339). 공통 코드로 두지 않고 앱마다 같은 모양으로 둔다.

## 이벤트 버전

- `eventVersion`은 이벤트 타입별 payload 버전이다. 같은 버전 안에서는 선택 필드 추가만 허용한다.
- 필드 삭제·이름 변경·타입 변경·필수 필드 추가는 같은 토픽에 새 레코드 타입을 더해서 한다(예: `UserModifiedEventV2`, `eventVersion=2`). 소비 앱을 먼저 배포해 두 타입을 받게 하고, 그다음 발행 앱이 새 타입으로 바꾼다. 옛 소비자는 모르는 타입을 무시한다.
- 토픽의 `.v<N>`은 key·partition 계약이 바뀔 때만 올린다. 이때는 새 토픽을 만들고 한동안 두 토픽에 함께 발행하며 옮긴다.
- 계약 검증은 shared 레코드 컴파일과 소비 테스트(Testcontainers)다. AsyncAPI와 schema CI 호환성 검사는 하지 않는다(#339, 2026-11-03, [7장](portfolio-items.md#kafka-이벤트-계약과-openapi-contract)).

## broker

- **Kafka instance는 1개만 띄운다.** EC2(와 로컬 k3d)에서 broker와 controller를 한 process로 합친 single-node KRaft로 실행한다. broker가 하나뿐이므로 topic replication factor는 1이다. 내부 topic 설정(`offsets.topic.replication.factor`, `transaction.state.log.replication.factor`)도 1로 맞춘다. 이 둘은 기본값이 3이라 그대로 두면 broker 1개에서 내부 topic을 만들지 못한다. 그러면 consumer group과 transaction이 동작하지 않는다. `min.insync.replicas`는 1로 둔다.
- 이것이 고가용성 구성이 아님을 README에 명시한다. broker가 내려가 있는 동안 발행할 이벤트는 Modulith publication(outbox)에 남아 있다가 재기동 후 다시 발행되므로 유실되지 않는다. 다만 consumer 쪽 처리는 그만큼 늦어진다. Kafka 공식 문서도 combined broker/controller mode를 개발·소규모 용도로 보고 중요 환경에서는 역할 분리를 권고한다([KRaft 문서](https://kafka.apache.org/42/operations/kraft/#deploying-considerations)).

## broker 선택 — Apache Kafka와 Redpanda

Redpanda는 Kafka API와 호환되는 broker다. 앱의 Kafka client(Spring Kafka, Modulith externalization)는 그대로 붙는다. 둘 다 "Kafka"라는 프로토콜을 쓰고, 고르는 것은 그 뒤의 서버 구현이다.

| 항목 | Apache Kafka (KRaft) | Redpanda |
| --- | --- | --- |
| 구현 | JVM. KRaft로 ZooKeeper 없이 broker 1개 실행 | C++(Seastar), 단일 binary. JVM·ZooKeeper 없음 |
| schema registry | 별도 서버 필요. 이 문서는 registry 없이 shared 레코드와 호환·배포 순서 규칙으로 대신한다([7장](portfolio-items.md#kafka-이벤트-계약과-openapi-contract)) | **broker에 내장**(port 8081). Avro, Protobuf, JSON Schema |
| 공식 메모리 요구 | 운영 최소치를 정하지 않음. heap은 설정으로 조절(이 문서 예산은 heap 512 MiB에 700 MiB) | 운영 broker는 **core당 최소 2 GB, core 최소 2개**. development mode는 최소 메모리를 강제하지 않는다. Docker 실행은 개발·test 용도라고 명시 |
| license | Apache 2.0 | Community Edition은 BSL. Redpanda를 남에게 상업 streaming·queue 서비스로 제공하는 것만 금지하고, 각 코드는 4년 뒤 Apache 2.0으로 바뀐다 |
| 생태계 | Kafka 공식 문서, 운영 지표, Testcontainers | Kafka client 호환, Testcontainers Redpanda module, 자체 CLI(`rpk`)와 Console |

근거: Redpanda production requirements("at least 2 GB of memory per core", "at least two physical CPU cores")([문서](https://docs.redpanda.com/current/deploy/redpanda/manual/production/requirements/)), development mode("No minimal memory limits are enforced")([문서](https://docs.redpanda.com/current/reference/rpk/rpk-redpanda/rpk-redpanda-mode/)), Docker quickstart("only for development and testing purposes")([문서](https://docs.redpanda.com/current/get-started/quick-start/)), schema registry 내장([문서](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/)), license([문서](https://docs.redpanda.com/current/get-started/licensing/overview/)).

**구성 요소 수로는 Redpanda가 가볍다.** broker와 schema registry가 한 binary다. 다만 이 문서는 registry 서버를 쓰지 않으므로([7장](portfolio-items.md#kafka-이벤트-계약과-openapi-contract)) 이 장점이 지금은 크지 않다. **메모리로는 공식 문서상 더 가볍다고 할 수 없다.**
- Redpanda의 운영 최소치는 2 core × 2 GB = 4 GB다. t3.large 8 GiB의 절반이라 [3장](k3s.md#ec2-2노드-자원-예산) 예산에 들어가지 않는다.
- t3.large에 맞추려면 development mode에 `--smp 1`과 작은 `--memory`로 돌려야 한다. 문서가 개발·test 용도라고 한 구성을 운영 서버에 두는 셈이고, 이 경우 필요한 메모리를 문서로 정할 수 없다.
- Kafka는 heap을 직접 정하므로 예산을 설정으로 맞출 수 있다(현재 700 MiB, heap 256 MiB면 더 줄일 수 있다).

판단: **broker는 Apache Kafka(KRaft, heap 256~512 MiB)를 유지한다.** Redpanda는 다음 경우에 다시 본다.
- 모듈을 별도 service로 떼어 내 runtime schema registry가 필요해질 때. 이때는 Kafka와 별도 registry 서버 대신 Redpanda 하나로 끝나므로 비교 결과가 달라진다.
- 로컬·CI에서만 쓸 때. Testcontainers Redpanda module은 JVM 기동이 없어 test 시작이 빠를 수 있다. 다만 운영 broker와 test broker가 다르면 동작 차이를 놓칠 수 있으므로, Kafka 고유 동작(transaction, consumer group rebalance)에 대한 test는 Kafka로 한다.

어느 쪽이든 앱 code는 Kafka client만 알고, broker는 설정(`bootstrap-servers`)으로만 바뀐다.

## 장애와 복구

**Kafka는 고가용성 구성이 아니다.** broker는 1개(single-node KRaft)이고 모든 토픽의 replication factor가 1이다. broker가 멈추면 그동안 발행과 소비가 모두 멈추고, broker의 디스크를 잃으면 토픽 데이터를 되살릴 수 없다. 대신 발행할 이벤트는 각 앱 DB의 publication(outbox)에 먼저 남으므로, broker가 돌아오면 다시 보낸다(아래 표). 소비는 그만큼 늦어진다.

| 장애 | 동작 | 복구 | 증거 |
| --- | --- | --- | --- |
| 업무 커밋 직후, 전송 전에 앱 종료 | publication이 미완료로 남는다 | 재기동할 때 다시 보낸다(`republish-outstanding-events-on-restart`) | `MemberEventKafkaRecoveryIntegrationTest` |
| broker 중단(발행 쪽) | 전송이 실패하고 publication이 미완료로 남는다 | 앱의 재발행 배치가 매분 다시 보낸다("발행" 절). 재기동해도 보낸다 | `BillingEventKafkaTest.republish()`, `MemberEventKafkaRecoveryIntegrationTest`, 앱별 `*EventRepublishUseCaseTest` |
| broker 중단(소비 쪽) | 소비자가 재연결을 기다린다. offset은 커밋된 곳에 그대로 있다 | broker가 돌아오면 이어서 읽는다 | `LectureMemberEventListenerTest.broker가_멈췄다_살아나면_그_뒤에_발행한_회원_이벤트를_반영한다` |
| consumer 중단(앱 장애·배포) | 그 그룹의 backlog가 쌓인다. Grafana lag series는 consumer가 없는 동안 사라진다 | 앱이 다시 뜨면 마지막 커밋 offset부터 읽고 lag가 줄어든다 | `LectureMemberEventListenerTest.소비를_멈춘_사이_쌓인_회원_이벤트를_재개하면_모두_반영한다`, 로컬 k3d 기록(#339) |
| 처리 실패 | 1·2·4초 간격으로 3번 재시도한 뒤 그룹별 DLT로 보내고 offset을 커밋해 뒤 메시지를 처리한다 | 원인을 고친 뒤 아래 runbook으로 골라 다시 넣는다 | 앱별 DLT 테스트(예: `LectureMemberEventListenerTest`), 로컬 k3d 기록(#339) |
| 읽을 수 없는 메시지 | 재시도 없이 바로 DLT로 보낸다 | 값을 고쳐 runbook으로 넣거나 그대로 둔다 | 앱별 "깨진 메시지" 테스트 |
| broker 디스크 유실 | 이미 완료 처리된 publication은 다시 보내지 않으므로 그 이벤트는 사라진다 | 회원 복제본은 관리자 Backfill로 회원 앱의 현재 정보에 다시 맞춘다(출연·지갑·채팅) | billing 절차는 [`docs/deploy/k8s.md`](../../deploy/k8s.md) "billing-app 회원 이벤트 DLT 복구" |

감시는 Grafana `Kafka` dashboard(`hapbang-kafka`)로 한다. consumer lag, "소비 그룹" 수(줄면 consumer가 멈춘 것), listener 처리 실패, 발행 실패, DLT 적재를 본다. 실패·DLT 값은 pod가 뜬 뒤 누적이라 재배포하면 0부터 다시 센다([관측성 "Dashboard" 절](observability.md)).

## DLT 재처리 runbook

DLT 메시지는 원인을 고친 뒤 원본 토픽에 다시 넣는다. 같은 토픽의 다른 그룹도 그 메시지를 한 번 더 받지만 소비 멱등이 흡수한다. 실행할 때마다 대상 메시지(DLT 이름·partition·offset), 원인, 결과를 해당 이슈 코멘트로 남긴다. 아래 명령은 로컬 k3d(`apache/kafka` 이미지)에서 확인했다.

**broker pod(`kafka-0`)에 `exec`로 들어가 콘솔 도구를 실행하지 않는다.** 콘솔 도구도 JVM이라 broker 컨테이너의 메모리 limit(700Mi)을 함께 쓴다. broker가 평소 550Mi 안팎을 쓰므로 도구를 띄우면 broker가 OOMKilled된다(로컬 k3d에서 소비자·프로듀서를 함께 띄워 재현, #429). 같은 이미지로 임시 client pod를 띄워 쓰고 끝나면 지운다. 이미지가 node에 있어 새로 내려받지 않는다.

0. client pod를 띄운다.

   ```bash
   kubectl --context hapbang-k3s -n hapbang-platform run kafka-client --image=apache/kafka:4.4.0 \
     --image-pull-policy=IfNotPresent --restart=Never \
     --overrides='{"spec":{"containers":[{"name":"kafka-client","image":"apache/kafka:4.4.0","command":["sleep","1800"],"resources":{"limits":{"memory":"512Mi"}}}]}}'
   kubectl --context hapbang-k3s -n hapbang-platform wait --for=condition=Ready pod/kafka-client --timeout=60s
   ```

1. DLT를 조회한다. Redpanda Console(#374, port-forward)로 보거나 client pod에서 다음을 실행한다. DLT 헤더에는 Spring Kafka가 붙인 원본 위치·예외 정보가 함께 나온다.

   ```bash
   kubectl --context hapbang-k3s -n hapbang-platform exec -it kafka-client -- \
     /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
       --topic <DLT 이름> --from-beginning --timeout-ms 10000 \
       --formatter-property print.partition=true --formatter-property print.offset=true \
       --formatter-property print.key=true --formatter-property print.headers=true
   ```

2. 다시 넣을 메시지를 골라 key와 `__TypeId__`·`eventType`·`eventVersion` 헤더만 남겨 원본 토픽에 보낸다. `traceparent`는 옮기지 않는다. 옮기면 재처리가 며칠 전 원래 trace에 붙는다. 형식은 `헤더<탭>key<탭>값`이다. Spring Kafka의 DLT 예외 헤더(`kafka_dlt-*`)는 옮기지 않는다. 역직렬화에 실패해 DLT로 간 메시지는 값이 원본 바이트 그대로이므로, 고친 값을 넣는다.

   ```bash
   printf '__TypeId__:<레코드 클래스 이름>,eventType:<레코드 이름>,eventVersion:<버전>\t<key>\t<JSON 값>\n' | \
     kubectl --context hapbang-k3s -n hapbang-platform exec -i kafka-client -- \
       /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic <원본 토픽> \
         --reader-property parse.key=true --reader-property parse.headers=true
   ```

3. 소비 앱 로그나 lag에서 처리됐는지 확인하고 이슈 코멘트에 결과를 남긴다. DLT의 메시지는 지우지 않고 보존 기간(7일)이 지나면 사라지게 둔다.

4. client pod를 지운다: `kubectl --context hapbang-k3s -n hapbang-platform delete pod kafka-client`

## 완료 증거

- 앱을 publication 저장 직후 종료해도 재기동 후 Kafka에 한 번 이상 전달된다.
- 같은 event를 두 번 소비해도 projection과 HP 원장 결과가 한 번 처리한 것과 같다.
- consumer를 중지했다 재시작하면 lag가 줄어들고 DLT를 선택적으로 재처리할 수 있다.
- Grafana에서 produce/consume rate, consumer lag, retry, DLT를 볼 수 있다. Kafka도 consumer max lag와 fetch rate 감시를 권장한다([공식 모니터링 문서](https://kafka.apache.org/42/operations/monitoring/)).
  - Grafana `Kafka` dashboard(`hapbang-kafka`, #597)에서 consume·produce rate, consumer lag, listener 실패(재시도 포함)를, "DLT" 행에서 그룹별 DLT 적재를 본다(#339, [관측성 "Dashboard" 절](observability.md)).
