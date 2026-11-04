# Modulith externalization으로 발행하고 eventId 없이 자연 키로 멱등 처리한다

Kafka 발행은 Spring Modulith event externalization으로 한다. 각 앱의 기존 publication registry가 outbox가 되고 발행 코드(`publishEvent`)는 바뀌지 않는다. payload는 `common/shared` 레코드를 그대로 싣고, 헤더에는 `eventType`과 `eventVersion`만 둔다. Modulith의 헤더 함수(`EventExternalizationConfiguration.headers(event -> …)`)는 이벤트 객체만 받아서, 여기서 만든 `eventId`는 재발행할 때마다 달라지고 `occurredAt`은 보낸 시각이 된다. 그래서 `eventId`를 두지 않고, 소비자는 업무 자연 키로 중복을 거른다. 결정 과정은 #339다.

## Considered Options

- 직접 만든 outbox 테이블과 relay: id 순 전송으로 순서를 보장할 수 있지만 테이블·relay·잠금·정리 배치를 앱마다 만들어야 한다. 순서는 소비자의 원본 버전 비교로 방어하므로 이 비용을 들이지 않는다.
- shared 레코드에 `eventId`·`occurredAt` 필드 추가: 발행하는 곳마다 값을 채워야 하고, payload를 레코드 그대로 싣는다는 결정(2026-10-29)을 다시 열게 된다.

## Consequences

- 멱등 키는 상태 복제 이벤트면 `(대상 ID, 원본 버전)`(예: `user.id`, `user.version`), 한 번만 일어나는 사실이면 `(eventType, 대상 ID)`다. 원본 버전이 없는 상태 변경 이벤트를 새로 만들 때는 버전을 함께 실어야 한다.
- Modulith는 이벤트마다 비동기로 보내므로 같은 key라도 전송 순서가 뒤바뀔 수 있다. 순서가 중요한 소비자는 원본 버전으로 늦은 이벤트를 거른다.
- Modulith는 기동할 때만 미완료 publication을 다시 보낸다. 실행 중 재전송은 각 앱이 직접 둔다.
- 추적 ID(`correlationId`)는 헤더로 만들지 않고 관측성(#343)의 trace 헤더로 대신한다.
