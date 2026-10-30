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
| `member.events.v1` | member-app | `UserJoinedEvent`, `UserModifiedEvent` | 회원 ID | `appearance-app.member-replica`, `billing-app.member-replica`, `billing-app.wallet-creation`, `lecture-app.member-replica`, `chat-app.member-replica` |
| `appearance.events.v1` | appearance-app | `AppearanceRequestSentEvent`, `AppearanceApplicationSubmittedEvent` | `appearance-request:<요청 ID>`, `appearance-application:<지원 ID>` | `chat-app.appearance-notification` |

1차 범위는 지금 다른 앱에 소비자가 있는 이벤트만이다. `UserLoggedOutEvent`는 HTTP 세션 ID를 담아 JWT 전환과 함께 다시 정하므로 올리지 않는다. 출연 취소 이벤트(요청·수락·거절)는 소비자가 생길 때 `appearance.events.v1`에 key `appearance:<출연 ID>`로 더한다. 소비 그룹 이름은 구현할 때 이 표를 고친다.

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
- Modulith는 기동할 때만 미완료 publication을 다시 보낸다(`republish-outstanding-events-on-restart`). 실행 중 재전송(`IncompleteEventPublications.resubmitIncompletePublicationsOlderThan`)은 **발행하는 앱마다 반드시 둔다.** 주기와 기준 시간은 앱이 정한다. replica 여럿이 같은 publication을 다시 보내도 소비 멱등이 흡수하므로 분산 락은 두지 않는다.

## 소비

- 그룹 이름은 `<앱>.<목적>`이다(예: `billing-app.wallet-creation`). 한 앱의 리스너 여러 개가 같은 그룹을 쓰면 partition을 나눠 가져 각자 메시지 일부만 받는다. 목적이 다르면 그룹을 나눈다.
- 클래스 레벨 `@KafkaListener`에 받을 타입마다 `@KafkaHandler`를 둔다. 모르는 타입은 `@KafkaHandler(isDefault = true)`에서 무시하고 커밋한다.
- 역직렬화는 `ErrorHandlingDeserializer`로 감싼 JSON 역직렬화다. 신뢰 패키지는 실제 이벤트 패키지(예: `com.hapbang.shared.member.event`)만 지정하고 `*`를 쓰지 않는다. 깨진 메시지는 재시도 없이 DLT로 간다.
- 새 그룹의 시작점은 `auto-offset-reset=earliest`다.
- 토픽이 아직 없어도 소비 앱은 뜬다(`spring.kafka.listener.missing-topics-fatal` 기본값 `false`). 소비자는 `UNKNOWN_TOPIC_OR_PARTITION` 경고를 남기며 기다리다가, 발행 앱이 토픽을 만들면 바로 붙어 처음부터 읽는다. 로컬 k3d에서 소비자를 먼저 띄우고 15초 뒤 토픽을 만들어 1초 안에 메시지를 받는 것을 확인했다(#429). 그래서 발행 앱과 소비 앱의 기동 순서를 맞추지 않는다. 기다리는 동안 경고는 약 1초마다 남는다(prod lecture-app 12분에 721건, 2026-10-30). 토픽이 오래 없으면 로그 양을 보고 토픽을 먼저 만든다.
- 멱등은 업무 자연 키로 한다. 상태 복제 이벤트는 `(대상 ID, 원본 버전)`(회원은 `user.id`, `user.version`), 한 번만 일어나는 사실은 `(eventType, 대상 ID)`다. 업무 반영과 멱등 기록은 같은 로컬 트랜잭션에서 하고, offset은 그 커밋 뒤에 커밋한다.
- Modulith 전송은 같은 key라도 순서가 뒤바뀔 수 있다. 순서가 중요한 소비자는 원본 버전으로 늦은 이벤트를 거른다.

## 재시도와 DLT

- 재시도는 블로킹 방식(`DefaultErrorHandler`)이다. 1초에서 시작해 최대 10초까지 지수로 늘리며 3회 재시도한 뒤 DLT로 보낸다. 재시도하는 동안 그 partition의 뒤 메시지는 기다리므로 같은 key의 순서가 지켜진다. non-blocking 재시도(`@RetryableTopic`)는 순서를 깨므로 쓰지 않는다.
- 역직렬화 실패·검증 실패처럼 재시도해도 결과가 같은 예외는 재시도 없이 바로 DLT로 보낸다.
- DLT 이름은 `<토픽>.<그룹>.DLT`다(예: `member.events.v1.billing-app.wallet-creation.DLT`). 그룹마다 실패를 따로 모아 어느 소비자가 실패했는지 구분한다. Spring Kafka 기본값(`<토픽>.DLT`)은 쓰지 않는다.
- DLT 토픽은 실패 처리를 소유한 소비 앱이 `NewTopic`으로 선언한다. partition은 원본과 같은 3개이고 원본과 같은 partition 번호로 보낸다. 보존은 7일(`retention.ms=604800000`)이다.
- DLT 발행이 실패하면 처리 성공으로 간주하지 않는다. offset을 커밋하지 않고 다시 시도한다.

## 이벤트 버전

- `eventVersion`은 이벤트 타입별 payload 버전이다. 같은 버전 안에서는 선택 필드 추가만 허용한다.
- 필드 삭제·이름 변경·타입 변경·필수 필드 추가는 같은 토픽에 새 레코드 타입을 더해서 한다(예: `UserModifiedEventV2`, `eventVersion=2`). 소비 앱을 먼저 배포해 두 타입을 받게 하고, 그다음 발행 앱이 새 타입으로 바꾼다. 옛 소비자는 모르는 타입을 무시한다.
- 토픽의 `.v<N>`은 key·partition 계약이 바뀔 때만 올린다. 이때는 새 토픽을 만들고 한동안 두 토픽에 함께 발행하며 옮긴다.
- 1차 계약 검증은 shared 레코드 컴파일과 소비 테스트(Testcontainers)다. AsyncAPI와 CI 호환성 검사는 [7장](portfolio-items.md#kafka-schema와-apievent-contract)의 2차 작업이다.

## broker

- **Kafka instance는 1개만 띄운다.** EC2(와 로컬 k3d)에서 broker와 controller를 한 process로 합친 single-node KRaft로 실행한다. broker가 하나뿐이므로 topic replication factor는 1이다. 내부 topic 설정(`offsets.topic.replication.factor`, `transaction.state.log.replication.factor`)도 1로 맞춘다. 이 둘은 기본값이 3이라 그대로 두면 broker 1개에서 내부 topic을 만들지 못한다. 그러면 consumer group과 transaction이 동작하지 않는다. `min.insync.replicas`는 1로 둔다.
- 이것이 고가용성 구성이 아님을 README에 명시한다. broker가 내려가 있는 동안 발행할 이벤트는 Modulith publication(outbox)에 남아 있다가 재기동 후 다시 발행되므로 유실되지 않는다. 다만 consumer 쪽 처리는 그만큼 늦어진다. Kafka 공식 문서도 combined broker/controller mode를 개발·소규모 용도로 보고 중요 환경에서는 역할 분리를 권고한다([KRaft 문서](https://kafka.apache.org/42/operations/kraft/#deploying-considerations)).

## broker 선택 — Apache Kafka와 Redpanda

Redpanda는 Kafka API와 호환되는 broker다. 앱의 Kafka client(Spring Kafka, Modulith externalization)는 그대로 붙는다. 둘 다 "Kafka"라는 프로토콜을 쓰고, 고르는 것은 그 뒤의 서버 구현이다.

| 항목 | Apache Kafka (KRaft) | Redpanda |
| --- | --- | --- |
| 구현 | JVM. KRaft로 ZooKeeper 없이 broker 1개 실행 | C++(Seastar), 단일 binary. JVM·ZooKeeper 없음 |
| schema registry | 별도 서버 필요. 이 문서는 registry 없이 레포 schema 파일과 CI 검사로 대신한다([7장](portfolio-items.md#kafka-schema와-apievent-contract)) | **broker에 내장**(port 8081). Avro, Protobuf, JSON Schema |
| 공식 메모리 요구 | 운영 최소치를 정하지 않음. heap은 설정으로 조절(이 문서 예산은 heap 512 MiB에 700 MiB) | 운영 broker는 **core당 최소 2 GB, core 최소 2개**. development mode는 최소 메모리를 강제하지 않는다. Docker 실행은 개발·test 용도라고 명시 |
| license | Apache 2.0 | Community Edition은 BSL. Redpanda를 남에게 상업 streaming·queue 서비스로 제공하는 것만 금지하고, 각 코드는 4년 뒤 Apache 2.0으로 바뀐다 |
| 생태계 | Kafka 공식 문서, 운영 지표, Testcontainers | Kafka client 호환, Testcontainers Redpanda module, 자체 CLI(`rpk`)와 Console |

근거: Redpanda production requirements("at least 2 GB of memory per core", "at least two physical CPU cores")([문서](https://docs.redpanda.com/current/deploy/redpanda/manual/production/requirements/)), development mode("No minimal memory limits are enforced")([문서](https://docs.redpanda.com/current/reference/rpk/rpk-redpanda/rpk-redpanda-mode/)), Docker quickstart("only for development and testing purposes")([문서](https://docs.redpanda.com/current/get-started/quick-start/)), schema registry 내장([문서](https://docs.redpanda.com/current/manage/schema-reg/schema-reg-overview/)), license([문서](https://docs.redpanda.com/current/get-started/licensing/overview/)).

**구성 요소 수로는 Redpanda가 가볍다.** broker와 schema registry가 한 binary다. 다만 이 문서는 registry 서버를 쓰지 않으므로([7장](portfolio-items.md#kafka-schema와-apievent-contract)) 이 장점이 지금은 크지 않다. **메모리로는 공식 문서상 더 가볍다고 할 수 없다.**
- Redpanda의 운영 최소치는 2 core × 2 GB = 4 GB다. t3.large 8 GiB의 절반이라 [3장](k3s.md#ec2-2노드-자원-예산) 예산에 들어가지 않는다.
- t3.large에 맞추려면 development mode에 `--smp 1`과 작은 `--memory`로 돌려야 한다. 문서가 개발·test 용도라고 한 구성을 운영 서버에 두는 셈이고, 이 경우 필요한 메모리를 문서로 정할 수 없다.
- Kafka는 heap을 직접 정하므로 예산을 설정으로 맞출 수 있다(현재 700 MiB, heap 256 MiB면 더 줄일 수 있다).

판단: **broker는 Apache Kafka(KRaft, heap 256~512 MiB)를 유지한다.** Redpanda는 다음 경우에 다시 본다.
- 모듈을 별도 service로 떼어 내 runtime schema registry가 필요해질 때. 이때는 Kafka와 별도 registry 서버 대신 Redpanda 하나로 끝나므로 비교 결과가 달라진다.
- 로컬·CI에서만 쓸 때. Testcontainers Redpanda module은 JVM 기동이 없어 test 시작이 빠를 수 있다. 다만 운영 broker와 test broker가 다르면 동작 차이를 놓칠 수 있으므로, Kafka 고유 동작(transaction, consumer group rebalance)에 대한 test는 Kafka로 한다.

어느 쪽이든 앱 code는 Kafka client만 알고, broker는 설정(`bootstrap-servers`)으로만 바뀐다.

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

2. 다시 넣을 메시지를 골라 key와 `__TypeId__`·`eventType`·`eventVersion` 헤더만 남겨 원본 토픽에 보낸다. 형식은 `헤더<탭>key<탭>값`이다. Spring Kafka의 DLT 예외 헤더(`kafka_dlt-*`)는 옮기지 않는다. 역직렬화에 실패해 DLT로 간 메시지는 값이 원본 바이트 그대로이므로, 고친 값을 넣는다.

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
