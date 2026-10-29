# 4. Graceful Shutdown — 전체 workload drain

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369다.

graceful shutdown은 단순히 HTTP 요청을 기다리는 option이 아니다. web·consumer가 **새 작업을 받는 단계부터 차례로 닫히고**, PostgreSQL connection은 진행 중인 처리의 상태 저장이 끝날 때까지 살아 있어야 한다. Spring Boot 4.1은 embedded Tomcat의 graceful shutdown을 기본 활성화하며, application context 종료의 이른 `SmartLifecycle` phase에서 새 요청을 막고 기존 요청에 종료 유예 시간을 준다. phase별 timeout은 `spring.lifecycle.timeout-per-shutdown-phase`로 설정한다([Spring Boot 공식 문서](https://docs.spring.io/spring-boot/4.1/reference/web/graceful-shutdown.html)).

## 종료 계약과 순서

`DrainCoordinator`를 종료 순서를 소유하는 깊은 module로 두고 종료 동작을 여러 listener에 흩뿌리지 않는다. interface는 `drain(deadline)` 하나이며 내부에서 Web, Kafka, Redis용 drain adapter를 정해진 순서로 호출한다. 상태는 `ACCEPTING → DRAINING → STOPPED`이며 중복 호출해도 같은 결과인 idempotent operation이다. Kubernetes `preStop`은 localhost의 내부 drain endpoint를 호출하고 짧게 endpoint 전파를 기다린다. endpoint는 `/api/v1/internal/operations/drain`으로 두고 loopback과 내부 key로 제한하며 Envoy에는 route를 만들지 않는다.

종료 순서는 다음으로 고정한다.

1. Kubernetes가 Pod를 `Terminating`으로 만들면 EndpointSlice의 `ready`가 `false`가 되어 일반 Service traffic에서 제외된다. 동시에 `preStop`이 drain을 시작한다. Kubernetes는 `preStop` 실행 전부터 전체 종료 유예 시간을 세고, hook과 process 종료가 모두 그 안에 끝나지 않으면 강제 종료한다([Kubernetes Pod 종료 흐름](https://kubernetes.io/docs/tutorials/services/pods-and-endpoint-termination-flow/), [container lifecycle hook](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/)).
2. readiness를 `OUT_OF_SERVICE`로 바꾸고 application-level `acceptingWork=false` gate를 닫는다. web은 새 long-running command를 거절하고, 기존 짧은 HTTP 요청은 Spring Boot/Tomcat이 마칠 시간을 준다. WebSocket client에는 재연결 신호를 보낸 뒤 session을 닫으며, 재접속 후 PostgreSQL history sync로 gap을 복구한다.
3. Kafka listener는 새 poll 처리를 멈추고 현재 record까지만 끝낸 뒤 offset을 commit한다. 처리 결과가 commit되지 않은 record는 다음 consumer가 다시 받고 inbox 멱등성으로 흡수한다. Redis Streams worker는 새 `XREADGROUP`을 멈추고 DB commit과 fan-out이 끝난 건만 `XACK`한다. 미완료 건은 pending에 남아 다른 worker가 `XAUTOCLAIM`한다.
4. in-flight HTTP, WebSocket close, consumer 처리가 끝난 다음 executor를 종료한다. DataSource/HikariCP는 이 과정의 마지막까지 유지하고 Spring context가 닫을 때 종료한다. 종료 callback에서 DB pool을 직접 먼저 닫지 않는다.

Kubernetes 설정의 시작값은 `terminationGracePeriodSeconds: 120`, `preStop` drain·전파 예산 10초, application drain 예산 최대 90초, 강제 종료 전 여유 20초로 둔다. 이 숫자는 운영 SLO가 아니라 k6와 장애 test에서 측정해 조정할 초기값이다. `preStop`과 Spring의 phase timeout을 단순 합산했을 때 120초를 넘지 않게 CI에서 실제 종료 시간을 잰다.

EC2 매일 중지(18:00)는 pod 하나가 아니라 node 전체의 종료다. graceful node shutdown이 켜져 있으면 kubelet은 `shutdownGracePeriod` 안에서 일반 pod를 먼저 종료한다. 이때 pod에 주어지는 시간은 자기 `terminationGracePeriodSeconds`가 아니라 node 종료 예산으로 제한될 수 있다. 일반 pod 몫(`shutdownGracePeriod - shutdownGracePeriodCriticalPods`)을 앱 pod의 drain 예산보다 길게 잡는다. instance 중지 때 OS가 그만큼 기다려 주는지는 실제 중지로 확인한다. 기다려 주지 않으면 그 결과를 기록하고, 끝내지 못한 처리가 Kafka 재전달과 Redis pending claim으로 복구되는지 확인한다.

## 완료 증거

- 앱 pod에 `SIGTERM`을 보내면 새 요청을 거절하고 in-flight 요청과 consumer 처리를 마친 뒤 grace period 안에 종료한다.
- rolling update 동안 새 HTTP 요청은 새로 ready가 된 pod로 가고, 기존 요청은 deadline 안에 끝나며, WebSocket은 재연결 후 history gap이 없다.
- Kafka current record와 Redis pending message를 처리 중 종료해도 재할당·claim 뒤 최종 결과가 한 번이고, 종료 과정에서 DataSource closed 오류가 없다.
- 선택한 관측성 UI에 drain 시작·완료·timeout, restart count, forced termination 지표와 동일 correlation ID의 log를 남긴다.
