# JDK 25에서는 virtual thread를 쓰지 않는다

main 코드와 설정은 virtual thread를 만들지 않는다. 별도 스레드가 필요하면 platform thread를 쓴다(`Thread.ofPlatform().daemon(true)`, Boot builder `virtualThreads(false)`). `spring.threads.virtual.enabled`도 켜지 않는다. `.github/scripts/check-virtual-threads.sh`가 PR마다 검사한다. 결정 과정은 #942다.

virtual thread는 carrier(virtual thread를 실행하는 OS 스레드, 기본 개수는 CPU 수)에 올라타 돌고, 기다릴 때 carrier에서 내려 다른 virtual thread에 자리를 내준다. 그런데 JDK 25에서는 JVM이 시작한 class loading처럼 스택에 native frame이 있으면 내려오지 못한다(pinning). 그래서 다음이 겹치면 앱 전체가 멈춘다.

- 여러 virtual thread가 클래스를 처음 읽다가 같은 배타 자원(jar 파일 monitor, class loading lock, `CleanerImpl` 목록 등)을 기다리며 carrier를 모두 붙잡는다.
- 그 자원을 쥔 쪽(또는 다음 차례)이 carrier에서 내려간 virtual thread라, 다시 돌 carrier가 없다.
- 같은 자원을 기다리는 platform thread(관리 포트 요청 수락, Kafka heartbeat, 스케줄러)도 함께 멈춘다.

auxiliary-app이 2026-11-14~19 운영에서 기동 34번 중 8번 이렇게 멈췄다(운영 node CPU 2개 = carrier 2개, 기동 순간 virtual thread 4개). 로컬 k3d에서 운영과 같은 JDK 25.0.5·CPU 2개로 재현하고 virtual thread까지 담은 dump로 확인했다(#942): Spring Boot nested jar의 `UrlNestedJarFile` monitor를 virtual thread `chatbot-faq-sync`가 쥔 채 carrier 없이 RUNNABLE로 기다렸고, carrier 2개는 같은 monitor를 기다리며 내려오지 못한 `notification-user-backfill`·`chatbot-lecture-index-check`가 붙잡고 있었다. 관리 포트 요청 수락, Kafka heartbeat, 스케줄러도 같은 monitor에서 멈췄다. 어떤 코드가 클래스를 처음 읽을지는 미리 알 수 없으므로(지연 로딩, 첫 예외, 첫 외부 호출) virtual thread에서 코드를 돌리는 것 자체가 전제 조건이다. 이 레포에서 virtual thread를 쓰던 곳은 동시 수가 1~4이거나 호출 수만큼이라, 적은 carrier로 많은 블로킹 작업을 돌리는 이점이 필요 없었다.

## Considered Options

- carrier 수 늘리기(`-Djdk.virtualThreadScheduler.parallelism`): 확률만 낮춘다. OpenJDK loom-dev에 보고된 같은 교착(2025-12 "class loading deadlock", JDK 25.0.1)은 carrier 32개에서 났다.
- jar를 풀어 실행하거나 AOT cache로 class loading 줄이기: 같은 보고 사례는 Spring Boot nested jar가 아니라 JDK의 일반 `JarFile`과 `CleanerImpl` 목록에서 났다. 배타 자원은 JDK 안에 남는다.
- JDK 업그레이드: 자원의 다음 차례가 내려간 virtual thread인 경우만 JDK-8369019로 JDK 26에서 고쳤다(2026-11 jdk25u 커밋에서 찾지 못했다). 주인이 내려간 virtual thread인 경우와 class loading 중 pinning을 없애는 일은 2025-12 기준 계획이 없다고 Oracle이 답했다.
- 기동 작업만 platform thread로 바꾸기: 실행기(LLM 호출, 재색인, Backfill)가 여전히 클래스를 처음 읽는다.

## Consequences

- 바꾼 곳은 스레드 종류만 바뀐다. 이름, trace 전파(Boot builder decorator), `InheritableThreadLocal` 상속, 취소 동작은 같다. virtual thread처럼 daemon으로 만들어 종료 동작도 같게 한다.
- `jvm_threads_live_threads`에 이 스레드들이 잡힌다(virtual thread는 세지 않았다).
- 동시 수가 큰 곳은 호출마다 스레드를 만들지 말고 상한 있는 풀을 쓴다.
- 2026-11-20 기준 아직 바꾸지 못한 곳은 검사 스크립트의 예외 목록에 후속 티켓과 함께 있다: chat-app 2곳(#1028), shared `BillingApiClient`(#1029).
- class loading 중 carrier에서 내려올 수 있게 고친 LTS JDK로 올리면 이 결정을 다시 본다.
- 앱이 기동 뒤 멈추면 `docs/deploy/k8s.md`의 "JVM 앱 멈춤 진단"으로 virtual thread까지 담은 thread dump를 받는다.
