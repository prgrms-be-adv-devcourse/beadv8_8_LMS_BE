# 1. Kafka — 모듈 간 integration event

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

## 적용 범위

Kafka에는 모든 Spring Event를 보내지 않고, 컨텍스트 밖에서 의미가 있는 integration event만 보낸다.

시연하기 좋은 흐름은 다음과 같다.

1. 회원 상태·역할 변경 → 출연·강의·채팅의 회원 복제 데이터 갱신
2. 출연·강의·지갑·회원·고객센터 이벤트 → notification 모듈의 알림 생성([8장](notification.md))과 감사 projection
3. 강의 구매 확정·지급 예정 → 정산 projection

HP 원장을 Kafka consumer가 직접 확정하게 만들지는 않는다. 원장 변경은 PostgreSQL 트랜잭션과 멱등키가 책임지고, Kafka는 완료된 사실을 다른 컨텍스트에 전달한다.

## 구현 원칙

- Spring Modulith event externalization을 사용해 기존 publication registry를 outbox seam으로 활용한다.
- event envelope에 `eventId`, `eventType`, `eventVersion`, `occurredAt`, `aggregateId`, `correlationId`를 둔다.
- topic은 기술 이름이 아니라 도메인 사실 기준으로 나눈다. 예: `member.events.v1`, `appearance.events.v1`, `lecture.events.v1`.
- key는 같은 aggregate의 순서를 보장할 ID를 사용한다.
- consumer는 `eventId` 기반 inbox/processed-event 테이블로 멱등 처리한다.
- retry 횟수와 backoff를 제한하고 최종 실패는 DLT로 보낸다. DLT 재처리 명령과 runbook을 함께 만든다.
- payload 변경은 version을 올리고 최소 한 버전의 하위 호환 consumer contract test를 둔다.
- **Kafka instance는 1개만 띄운다.** EC2(와 로컬 k3d)에서 broker와 controller를 한 process로 합친 single-node KRaft로 실행한다. prod와 dev가 함께 쓰게 되면 topic 이름에 환경 prefix를 붙여 나눈다. broker가 하나뿐이므로 topic replication factor는 1이다. 내부 topic 설정(`offsets.topic.replication.factor`, `transaction.state.log.replication.factor`)도 1로 맞춘다. 이 둘은 기본값이 3이라 그대로 두면 broker 1개에서 내부 topic을 만들지 못한다. 그러면 consumer group과 transaction이 동작하지 않는다. `min.insync.replicas`는 1로 둔다.
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

## 만들어야 할 깊은 module

- `IntegrationEventPublisher` interface: 업무 module이 알아야 할 것은 integration event 발행뿐이다.
- Kafka adapter: serialization, topic, header, retry 같은 구현을 숨긴다.
- in-memory/test adapter: 동일 interface로 contract와 업무 테스트를 수행한다.

Kafka template을 각 업무 module에서 직접 호출하면 topic·직렬화·오류 처리가 퍼져 얕은 module이 된다. 이 복잡성은 한 adapter에 모은다.

## 완료 증거

- 앱을 publication 저장 직후 종료해도 재기동 후 Kafka에 한 번 이상 전달된다.
- 같은 event를 두 번 소비해도 projection과 HP 원장 결과가 한 번 처리한 것과 같다.
- consumer를 중지했다 재시작하면 lag가 줄어들고 DLT를 선택적으로 재처리할 수 있다.
- Grafana에서 produce/consume rate, consumer lag, retry, DLT를 볼 수 있다. Kafka도 consumer max lag와 fetch rate 감시를 권장한다([공식 모니터링 문서](https://kafka.apache.org/42/operations/monitoring/)).
