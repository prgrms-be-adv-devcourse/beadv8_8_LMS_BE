# Chat App — AGENTS.md

채팅 앱(`apps/chat-app`)에서 작업할 때 따르는 명령이다. 설명과 세부 내용은 `docs/` 아래 문서에 있다.

- 이 파일은 루트 [`AGENTS.md`](../../AGENTS.md)보다 우선순위가 낮다. 루트 `AGENTS.md`, `docs/policy.md`, `docs/GLOSSARY.md`와 충돌하면 그쪽을 따르고 충돌을 보고한다.
- 작업 전에 `docs/rules/`의 규칙 문서를 확인한다. 설계 근거는 [`docs/final-design.md`](docs/final-design.md)를 본다.
- 범위는 [`docs/rules/scope.md`](docs/rules/scope.md)를 따른다.
- chat-app의 설계·구현·테스트·배포 기준은 replica 1개다. 복수 replica를 전제로 한 구현·검증을 요구하지 않는다.
- [`docs/rules/invariants.md`](docs/rules/invariants.md)의 불변식을 바꾸지 않는다. 바꿔야 하면 먼저 질문한다.
- [`docs/rules/decisions.md`](docs/rules/decisions.md)의 결정을 따른다. 미정 항목은 추측으로 정하지 않는다.
- 고도화는 [`docs/workflow/process.md`](docs/workflow/process.md)의 1. 회원 이벤트 Kafka 전환, 2. Redis Streams·Pub/Sub 전환, 3. 서비스 인터페이스 분리, 4. 단체방 구현을 모두 포함한다. 1·2번은 병렬로 진행하고 3번은 그 전환 중 함께 적용한다. 1~3번 통합 검증 뒤 4번을 진행한다.
- Redis Streams 도입에 따라 outbox 적재·Pub/Sub 발행·Stream ACK·사용자 수신의 완료 경계를 구분하고, 그룹 소비·회수·유실 재구성·보존 기준은 [`docs/final-design.md`](docs/final-design.md) 4.2·4.5를 따른다.
- 누적 수신 커서·클라이언트 ACK·기기별 복구를 이번 고도화에 포함하고, 저장 모델·세대 검증·ACK 병합·FE 계약은 [`docs/final-design.md`](docs/final-design.md) 4.4를 따른다.
- 서비스 인터페이스·구현체의 이름과 배치는 [`docs/rules/decisions.md`](docs/rules/decisions.md)의 서비스 경계 규칙을 따른다. Facade는 `app`의 인터페이스에 의존한다.
- 오픈 직후 트래픽 급증을 전제로 [`docs/final-design.md`](docs/final-design.md) 4.6·4.7의 잠금 구간·과부하 제어·확장 경계와 출시 전 검증 기준을 적용한다. 사용자 수가 적다는 이유로 생략하지 않는다.
- 사용자가 작업 시작을 명령하기 전에 이슈를 만들지 않는다.
- 테스트 세션 검증을 통과하기 전에 Codex 리뷰를 받지 않는다. 리뷰 반영으로 코드가 바뀌면 테스트를 다시 받는다.
- 테스트는 [`docs/workflow/test-criteria.md`](docs/workflow/test-criteria.md)를 기준으로 한다.
- 원격에 올리기 전에 보고서를 만들어 사용자 리뷰를 받는다. 승인 전에 커밋·push·PR 생성·이슈 닫기를 하지 않는다.
- 결정이 바뀌면 `docs/rules/decisions.md`와 `docs/final-design.md`를 같은 변경에서 고친다.
- 이 파일에는 명령만 둔다. 설명·예시·명령어 사용법은 `docs/`에 둔다.
- `apps/chat-app` 안에 README.md를 만들지 않는다.
- HTTP 호출은 `@HttpExchange`로 만든다. 세부는 [`docs/rules/decisions.md`](docs/rules/decisions.md).
