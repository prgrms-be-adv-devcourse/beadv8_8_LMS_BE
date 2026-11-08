# Coupon — AGENTS.md

쿠폰 모듈(`apps/lecture-app/boundedContext/coupon`)에서 작업할 때 따르는 명령이다. 설명과 세부 내용은 `docs/` 아래 문서에 있다.

- 이 파일은 루트 [`AGENTS.md`](../../../../AGENTS.md)보다 우선순위가 낮다. 루트 `AGENTS.md`, `docs/policy.md`, `docs/GLOSSARY.md`와 충돌하면 그쪽을 따르고 충돌을 보고한다.
- 작업 전에 [`docs/README.md`](docs/README.md) → `requirements.md` → `domain.md` → `design.md` → `contracts.md` → `operations.md` → `decisions.md` 순서로 읽는다.
- 범위는 [`docs/requirements.md`](docs/requirements.md)의 "제외 범위"를 따른다. 제외 항목을 구현하지 않는다.
- [`docs/operations.md`](docs/operations.md)의 invariant(I1~I10)를 바꾸지 않는다. 바꿔야 하면 먼저 질문한다. PR 본문에 이 변경이 건드리는 invariant와 그 검증을 적는다.
- [`docs/decisions.md`](docs/decisions.md)의 결정을 따른다. "구현 전 남은 결정"의 미정 항목은 추측으로 정하지 않고 질문한다.
- 강의 ↔ 쿠폰은 내부 HTTP API(`/api/v1/internal/coupons/...`, `X-Internal-Api-Key`)와 Kafka로만 통신한다. `com.hapbang.lecture..`를 import하지 않는다. `@ApplicationModuleListener`·`@EventListener`로 두 모듈을 잇지 않는다. 내부 API 호출을 호출자 트랜잭션 안에서 하지 않는다.
- 강의 모듈 코드는 [`docs/design.md`](docs/design.md) "프로젝트 구조"가 명시한 다섯 곳(강의 조회 내부 API, `out/coupon` 클라이언트, `OrderCreateUseCase` 단계 분리, 가격 수정 시 `priceRaiseBlocked` 호출, 결제·취소 완료 이벤트 externalize)만, 작업 지시서에 포함될 때만 수정한다. 그 밖의 다른 모듈·앱 코드는 수정하지 않는다.
- billing-app과 billing 계약(`docs/api/internal-billing.md`)은 수정하지 않는다. 필요한 계약은 [`docs/contracts.md`](docs/contracts.md) "billing 담당 계약"에 적어 보고한다.
- 팀에 없던 라이브러리·접근 방식을 들이지 않는다. DB는 JPA, HTTP는 `@HttpExchange`, Kafka·Redis는 `common/kafka`·`common/redis` 자동 설정을 쓴다.
- Redis key는 `CouponRedisKeys` 한 곳에서만 만들고 모든 key에 TTL을 둔다. 미처리 Stream 기록을 TTL·`MAXLEN`으로 버리지 않는다.
- 측정 전 운영 수치(TTL, 수량·메모리 상한, 배치 주기, 재시도 횟수)를 확정값으로 코드에 넣지 않는다. 설정 플레이스홀더와 코드 기본값으로 두고 [`docs/operations.md`](docs/operations.md)에 "확정 전"이라고 남긴다.
- 코드 주석에 이슈 번호를 쓰지 않는다. 뜻을 문장으로 쓴다.
- 테스트는 [`docs/operations.md`](docs/operations.md) "테스트 구성"의 피라미드를 따른다: 도메인 단위 테스트를 가장 많이, 서비스(UseCase)는 그보다 적게, Repository는 하나씩, e2e는 가장 적게. 같은 규칙을 여러 층에서 반복 검증하지 않는다. 검증 항목은 "필수 검증"을 기준으로 하고, 수정과 관련된 테스트만 돌린다.
- 사용자가 작업 시작을 명령하기 전에 이슈를 만들지 않는다.
- 리뷰는 Codex 리뷰를 쓰고 **최대 5회**까지만 받는다. 테스트 세션 검증을 통과하기 전에 Codex 리뷰를 받지 않는다. 리뷰 반영으로 코드가 바뀌면 테스트를 다시 받는다. 5회 안에 해결되지 않으면 멈추고 남은 지적을 보고한다.
- 원격에 올리기 전에 보고서를 만들어 사용자 리뷰를 받는다. 로컬 커밋은 해도 되지만 승인 전에 push·PR 생성·이슈 닫기를 하지 않는다. 흐름은 [`docs/workflow.md`](docs/workflow.md)를 따른다.
- 결정이 바뀌면 `docs/decisions.md`와 해당 문서를 같은 변경에서 고친다.
- `boundedContext/coupon` 바로 아래에 README.md를 만들지 않는다. 문서 안내는 `docs/README.md`다.
