# 10. 모듈별 database 분리

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

모듈마다 database를 나누는 것은 확정이다(AGENTS.md 3장, #338). #363은 중간 단계로 database 하나에 앱별 스키마를 나눴다(`docs/architecture/app-modules.md`). 스키마는 권한을 걸어야 JOIN이 막히지만 database는 connection 자체가 다른 database에 붙지 못하므로, 목표는 database 분리로 유지하고 #338에서 스키마를 database로 옮긴다. 이 장은 **instance까지 나눌지**를 판단하고, 분리에 따라 바뀌는 것을 정리한다.

## 판단: instance는 1개, database만 앱별로

| 기준 | instance 1개 + 모듈별 database | 모듈별 instance |
| --- | --- | --- |
| 모듈 경계 강제 | PostgreSQL connection은 한 database에만 붙는다. 다른 database의 table을 JOIN할 수 없다(`dblink`·`postgres_fdw`를 설치하지 않으면). 경계가 이미 강제된다 | 같음. 경계 면에서 더 얻는 것이 없다 |
| 계정 분리 | database마다 소유 role을 따로 두고 `REVOKE CONNECT ... FROM PUBLIC`. 모듈 계정은 자기 database에만 접속한다 | 같음 |
| 메모리 | instance 고정 비용을 한 번만 낸다(`shared_buffers` 기본 128 MB, background process, WAL buffer). 현재 예산 512 MiB 유지 | database 9개면 고정 비용이 9번. `shared_buffers`만 기본값으로 1 GiB를 넘어 [3장](k3s.md#ec2-2노드-자원-예산) 예산에 들어가지 않는다 |
| 자원·장애 격리 | 한 모듈의 무거운 query가 다른 모듈에 영향을 준다 | process는 나뉘지만 같은 EC2의 CPU·disk·kernel을 쓴다. 단일 host에서는 격리 효과가 작다 |
| 백업·복구 | `pg_dump`는 database 단위로 가능하다. 시점 복구(PITR)는 WAL이 instance 전체라 모듈 하나만 되돌릴 수 없다 | 모듈별 PITR 가능 |
| version 업그레이드 | 모든 모듈이 함께 올린다 | 모듈별로 따로 올린다 |
| 운영 | Kubernetes StatefulSet 1개, 백업·모니터링 1벌 | StatefulSet·Secret·백업·대시보드가 모듈 수만큼 |

근거: "It is not possible to access more than one database per connection"([PostgreSQL Managing Databases](https://www.postgresql.org/docs/current/managing-databases.html)). `shared_buffers` 기본값 128 MB([PostgreSQL Resource Consumption](https://www.postgresql.org/docs/current/runtime-config-resource.html)).

모듈별 instance가 더 얻는 것은 모듈별 PITR, 독립 업그레이드, process 격리다. 모두 단일 EC2에서는 효과가 작거나 필요 없다. 그 대가는 [3장](k3s.md#ec2-2노드-자원-예산) 예산을 넘는 메모리다. 따라서 **instance 1개를 두고, 그 안에서 앱마다 database를 나눈다.** EC2에는 prod만 두므로 instance도 하나다. 개발용 DB는 로컬과 PR k3d에서 띄운다.

instance를 나누는 시점은 모듈을 별도 service로 떼어 내 따로 배포하거나, 관리형 DB(RDS 등)로 옮길 때다([11장](../final-project-infrastructure.md#11-실서비스-전환-시-고려사항)).

## database 목록

| database | 소유 앱 | 내용 |
| --- | --- | --- |
| `member` | 회원 | 회원, 역할, 채널 인증, refresh token |
| `appearance` | 출연 | 출연 요청·모집, 회원 복제본 |
| `lecture` | 강의 | 강의, 장바구니, 주문, 리뷰, 회원 복제본 |
| `billing` | 지갑·정산 | 지갑, 원장, 보류, 지급, 환전 |
| `chat` | 채팅 | 대화, 차단, 회원 복제본 |
| `customersupport` | 고객센터 | 공지, FAQ, 문의, RAG 색인 작업·재처리 상태(검색 문서는 고객센터 소유 ES index) |
| `notification` | 알림 | 알림, 회원 복제본, inbox |
| `search` | 검색 | 이벤트 inbox, 색인 상태(문서는 ES) |

Spring Session을 쓰지 않으므로([3장 "인증 흐름"](api-gateway.md#인증-흐름)) 공용 `platform` database는 두지 않는다.

## 분리하면 바뀌는 것 (instance 결정과 무관)

instance를 하나로 두어도 database가 나뉘는 순간 다음이 바뀐다. 실제 작업량의 대부분은 여기에 있다.

1. **모듈을 넘는 transaction이 사라진다.**
   - 강의 주문 생성은 예전에 주문 저장과 billing의 HP 보류를 한 transaction으로 처리했다(`OrderCreateUseCase` → `BillingPort.hold`). database가 다르면 이 둘을 원자적으로 묶을 수 없다.
   - 그래서 강의(#275~#277)와 출연(#274)은 HP 이동을 자기 transaction 밖의 billing 내부 HTTP 호출로 바꾸고, 결과는 HP 작업·보상 처리로 기록한다. 지금 다른 모듈의 transaction에 참여하는 HP 호출은 없다.
   - 남은 shared `BillingPort`·`BillingPortAdapter`는 쓰는 곳이 없고 #280에서 지운다.
2. **Modulith 이벤트 저장소는 앱마다 자기 database에 둔다.**
   - Spring Modulith의 JDBC publication registry는 `DataSource` 하나에 묶인다(`JdbcEventPublicationAutoConfiguration`, 2.1.1 소스 확인).
   - 앱마다 database가 하나이므로 업무 data와 publication이 같은 database에 있다. "업무 transaction 안에서 이벤트를 기록한다"는 outbox 보장이 앱 안에서 그대로 지켜진다.
   - 각 앱이 자기 registry의 publication을 Kafka로 externalization한다. 토픽·헤더·재전송 규칙은 [1장](kafka.md#발행)을 따른다.
3. **앱마다 `DataSource`와 Flyway가 하나씩이다.** 앱 하나가 database 하나에만 붙으므로 모놀리스처럼 모듈별 transaction manager를 나눌 필요가 없다. 버전 충돌 검사(`uniq -d`)는 앱 안에서만 의미가 있다.
4. **기존 데이터와 migration 이력을 옮긴다.** 지금 운영 DB는 database 하나에 모든 모듈 table과 Flyway 이력이 있다. 앱별 database로 옮길 때 데이터와 이력을 보존하는 절차가 필요하다(#338, `docs/architecture/app-modules.md`).
5. **connection 수를 다시 계산한다.**
   - 앱은 자기 database에만 pool을 연다. 총 connection은 `(앱 수 + rolling surge pod 수) × pool 크기`다.
   - 앱 8개와 surge pod 1개가 pool을 5개씩 열면 이론상 45개다. `max_connections`는 계산값에 여유를 더해 정한다. connection마다 backend process 메모리가 드므로 [3장](k3s.md#ec2-2노드-자원-예산) PostgreSQL 예산도 함께 다시 잰다.
6. **test 지원을 바꾼다.** 앱 test는 자기 database만 띄운다. `common/test-support`의 DB 정리와 Testcontainers 설정이 이에 맞는지 확인한다.

## 진행 순서

1. 모듈을 넘는 동기 호출을 내부 API와 보상 처리로 바꾼다(#271~#281).
2. 앱별 publication externalization을 만든다(Kafka 도입과 함께, #339).
3. #363의 앱별 스키마를 하나씩 앱별 database로 옮긴다. 데이터 이전은 `pg_dump`/restore로 하고 Flyway 이력을 보존한다(#338).
4. connection 수와 PostgreSQL 메모리를 재측정한다(실행 전 허락).

## 완료 증거

- 모듈 계정으로 다른 모듈 database에 접속하면 거부된다.
- 강의 주문 중 HP 보류가 실패하면 주문은 `HOLD_FAILED`로 남고, 이미 성공한 항목 보류는 보상 RELEASE 작업으로 모두 반환된다(D58·D69).
- 업무 commit 직후 앱을 종료해도 모듈 outbox의 이벤트가 재기동 후 Kafka로 나간다.
- rolling update 중 최대 connection 수가 `max_connections` 안에 있다.
