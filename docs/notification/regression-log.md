# 알림(notification) 모듈 — 회귀 실행 기록

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> 문서 지도: [모듈](module.md) · [결정 기록](decisions.md) · [알림 종류](notification-catalog.md) · [API 계약](../api/notification.md) · [테스트 검증 기준](test-spec.md) · **[회귀 실행 기록](regression-log.md)** · [진행](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md)
>
> - **역할**: 이 문서는 알림 모듈 테스트를 **실제로 실행한** 기록만 쌓는다: 언제, 어느 커밋에서, 무슨 명령을 돌렸고, 결과가 어땠고, 실패는 무엇 때문이었나. 무엇을 검증해야 하는지(합격 기준)는 [test-spec.md](test-spec.md)의 `NT-*`에 있다.
> - **현재 상태(2026-11-14)**: 구현 작업 N1~N10(dev `cced81b7`)의 테스트 실행 기록이 NR-001~NR-082에 있다. 로컬 기동·자원 측정은 NR-005·NR-017이다. 각 기록은 그 실행 시점의 사실이며 고쳐 쓰지 않는다(1.1 3번).
> - **설계 문서의 완료 증거를 실행 기록으로 옮기지 않는다.** [알림 설계](../architecture/infrastructure/notification.md) 11장의 완료 증거와 test-spec 3장의 매핑은 "무엇을 보여야 하는가"이지 실행 결과가 아니다. 증거를 실제로 실행·시연했을 때만 이 문서에 기록을 남긴다.

## 1. 작성 규칙과 기록 양식

### 1.1 작성 규칙

1. **실행하지 않은 것은 성공으로 적지 않는다.** 계획했지만 돌리지 않은 단계는 `미실행`으로 적는다.
2. 건수는 테스트 결과 XML(`build/test-results/test/TEST-*.xml`, 알림 모듈은 `apps/auxiliary-app/boundedContext/notification/build/...` 또는 실행한 프로젝트의 경로)에서 센다. 콘솔 요약이나 기억으로 적지 않는다. `UP-TO-DATE`·`FROM-CACHE`로 건너뛴 태스크는 새 실행이 아니다(`--rerun-tasks` 또는 `cleanTest`).
3. 실행마다 새 ID(`NR-001`, `NR-002`, …)를 쓴다. 새 기록은 3장 맨 위에 더하고, 지난 기록은 고치지 않는다. 잘못 적었으면 새 기록에 정정을 남긴다.
4. 실패마다 원인 분류(2.1)를 하나 붙인다. 분류하지 못했으면 `미분류`로 두고 4장 미해결 표에 올린다.
5. 관련 NT ID를 적는다. 명세 상태가 "미결"인 NT를 실행했으면 결과를 합격·불합격으로 판정하지 않고 `F-SPEC` 또는 메모로 남긴다.
6. 환경 정보에 비밀 값(내부 API 키, DB 비밀번호, JWT, 연결 티켓 값)을 적지 않는다.
7. 커밋하지 않은 변경이 있으면 "작업 트리 변경"에 파일 목록을 적는다. 커밋 해시만으로는 재현할 수 없다.
8. E2E(k3d)·수동 시연도 실행 기록이다. 명령 대신 절차와 확인 방법(로그·lag·화면)을 적는다. 운영 cluster(`hapbang-k3s`)에서 확인했으면 그렇게 적는다.
9. [test-spec.md](test-spec.md)는 고치지 않는다(실행 결과를 적지 않는 문서다).

### 1.2 기록 양식 (복사용)

<!--
#### NR-NNN · YYYY-MM-DD · <결과>

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-NNN |
| 실행 날짜 | YYYY-MM-DD HH:mm~HH:mm (KST) |
| 실행자 | 사람 / Claude Code / Codex CLI |
| 브랜치 | |
| 커밋 | (git rev-parse --short HEAD) |
| 작업 트리 변경 | 없음 / 파일 목록 |
| 대상 기능 | |
| 관련 NT ID | NT-…, NT-… (명세 상태가 미결인 것은 표시) |
| 명령 | (그대로 복사. E2E·수동은 절차) |
| 실행 환경 | OS, JDK, Gradle, Docker 버전·실행 여부, 실제로 뜬 컨테이너(Testcontainers postgres / kafka / redis), k3d 여부, `TZ`, 메모리. 비밀 값 금지 |
| 결과 | 통과 / 실패 / 부분 / 중단 / 미실행 |
| 건수 | 성공 n, 실패 n, 오류 n, 건너뜀 n (XML 기준) |
| 실패 원인 분류 | 실패마다 2.1 코드 하나. 실패 없으면 "실패 없음" |
| 수정 내용 | 고친 코드·테스트·설정과 이유. 없으면 "없음" |
| 재실행 결과 | 다시 돌린 명령과 결과. 없으면 "재실행 안 함" |
| 미검증 항목 | 이번에 확인하지 못한 NT와 이유(이벤트 미발행, 미결 U, 환경) |
| 관련 변경 | Issue·PR 번호 |

**실패 목록**

| 테스트 (클래스#메서드) | NT | 오류 요약 (첫 줄) | 원인 분류 | 근거 | 조치·Issue |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

**메모**: 재현 조건, 다음 실행에서 확인할 것
-->

## 2. 분류 기준

### 2.1 실패 원인 분류

| 코드 | 분류 | 뜻 | 후속 |
| --- | --- | --- | --- |
| F-CODE | 코드 결함 | 알림 모듈 운영 코드가 합격 기준과 다르다(진짜 회귀). 예: 중복 이벤트로 알림 2건, 다른 회원 알림 노출, 21번째 저장 뒤 20개 초과 | Issue 생성, 수정 |
| F-BND | 경계 위반 | `AuxiliaryModularityTest`·`verifyModuleStructure` 실패, 다른 schema 참조, 다른 업무 모듈 직접 참조 | 의존 제거. 경계 규칙을 바꿔야 하면 먼저 질문 |
| F-CONTRACT | 계약 불일치 | 받은 이벤트의 필드·`eventType` 헤더·토픽·key가 shared 계약이나 [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표와 다르다. API 응답이 [API 계약](../api/notification.md)과 다르다 | 발행 모듈 담당 또는 계약 문서와 대조 |
| F-COMM | 미구현 연동 | 필요한 발행 이벤트(N 8장 M1~M8), Gateway route, 토큰 헤더 등이 아직 없다 | 해당 작업 Issue에 연결. 코드 결함으로 세지 않는다 |
| F-SPEC | 명세 미결 | 합격 기준이 미결 U에 달려 있어 판정할 수 없다 | [decisions.md](decisions.md) 10장 해당 U에 연결 |
| F-TEST | 테스트 결함 | 테스트의 가정·데이터·타이밍 문제. 비동기 소비·STOMP 수신 대기 시간 부족 같은 불안정 포함 | 테스트 수정 Issue |
| F-ENV | 환경 | Docker 없음, Testcontainers 기동 실패, 메모리 부족, 포트 충돌, k3d 문제 | 환경을 기록하고 재실행 |
| F-CFG | 설정 | 필수 환경 변수·프로필·yaml·Flyway 위치 차이 | auxiliary-app README "실행과 설정"과 대조 |
| 미분류 | — | 원인을 아직 모른다 | 4장 미해결 표에 올린다 |

### 2.2 결과 표기

| 표기 | 뜻 |
| --- | --- |
| 통과 | 실행했고 실패·오류 0 |
| 실패 | 실패 또는 오류가 1건 이상 |
| 부분 | 일부 테스트·NT만 실행했다(관련 NT ID 칸에 적는다) |
| 중단 | 빌드·컨텍스트·환경 문제로 끝까지 실행하지 못했다 |
| 미실행 | 계획했지만 실행하지 않았다 |

## 3. 실행 기록

_새 기록은 1.2 양식을 복사해 이 줄 바로 아래(가장 최근 기록 위)에 붙인다._

#### NR-109 · 2026-11-17 · 통과 (N15 #970 PR Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-109 |
| 실행 날짜 | 2026-11-17 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `caaedd0a` 위 작업 트리 미커밋 |
| 작업 트리 변경 | `B0FixtureGenerator` 재전송 0건 처리, `L4FixtureGeneratorTest` 2건, `stomp-common.js`(계획 전 종료·계정 수 검사), `stomp.js`·`l6.js`(ND-200), `pace.sh`(늦으면 기준 시각 이동), `n15.sh`(context 서버 확인·정리·port-forward 제한 시간·L5 Gradle 데몬 내림), `verify_n15.py`(복제본 확인 기록 없음·실패 예약 시각 모름은 실패, 다음 경계 1초 안에 끝난 긴 회차), 시험, 문서 |
| 대상 기능 | N15 부하 도구·판정기, L4 fixture |
| 관련 NT ID | NT-LOAD-L2·L3·L4·L5·L6 |
| 명령 | `python -I -m unittest discover -s loadtest/notification/tests`, `verify_n15.py l5 …`(S·B·M·F 유효 회차 재판정), k6 `inspect`(rest load, stomp smoke·load, l6, 계정 8개), `bash -n`, `n15.sh --dry-run`, `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | 로컬 Windows, Docker Desktop |
| 결과 | 통과 |
| 건수 | 새 시험 3건 RED(복제본 확인 기록 없음, 실패 예약 시각 모름, 긴 회차 배정) → GREEN, 스크립트 시험 80 OK. L5 재판정 rc 0, 회차 배정·판정이 앞 판정과 같음(앞 판정 `result.before-botreview.json`). k6: 계정 10개 통과, 8개는 시작 전 거절(9개 필요). `:notification:test` 549 / 실패 0 / 오류 0 / 건너뜀 2, ND-201 반영 뒤 다시 548 / 0 / 0 / 2(B0 불변 시험 3 → 2건), `verifyModuleStructure` 성공, `:auxiliary-app:check` 성공(`:auxiliary-app:test` UP-TO-DATE) |
| 실패 원인 분류 | 환경(첫 Gradle 실행 때 Docker Desktop이 꺼져 Testcontainers 시험 259건 실패. 켠 뒤 다시 실행) |
| 수정 내용 | ND-200, ND-201(B0 불변 시험을 구조 해시로: 내용 해시가 공식 B0와 같은 코드에서 구조 해시 2개를 뽑아 고정, 뮤테이션 LEGACY 50→51로 2건 RED 확인 뒤 되돌림, B0 불변 2·B0 생성기 12·L4 7 GREEN). 리뷰 반영 목록은 PR 답변 |
| 재실행 결과 | 위 건수 |
| 미검증 항목 | 운영 실행(L1·L2·L6) |
| 관련 변경 | #970 |

#### NR-108 · 2026-11-17 · 통과 (N15 #970 S6 리뷰 반영, 검증기 판정 조건 정렬·재판정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-108 |
| 실행 날짜 | 2026-11-17 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준), 작업 트리 미커밋 |
| 작업 트리 변경 | NR-107과 같다 + `verify_n15.py` 판정 조건(ND-199)·머리말, `n15.sh` 머리말, `l6.js` 임계값(요청 이름별 p95·연결 만료·티켓 실패·checks), 문서 문구 |
| 대상 기능 | N15 판정기, 운영 L6 도구 |
| 관련 NT ID | NT-LOAD-L3·L5B·L5F·L6 |
| 명령 | `python -I -m unittest discover -s loadtest/notification/tests`, `verify_n15.py l5 …`(S·B·M·F 유효 회차), `verify_n15.py l3 …`(2회차), k6 `inspect`(l6·stomp load), `bash -n n15.sh`, `n15.sh --dry-run l4 1` |
| 실행 환경 | 로컬 Windows. 재판정은 저장된 회차 산출물만 읽음(cluster 쓰지 않음) |
| 결과 | 통과 |
| 건수 | 새 시험 3건 RED(L5B 분포 99/2, L5F retry 19, L3 중복·측정 제외 집계) → GREEN, 스크립트 시험 77 OK. 재판정 rc 0: L5-S·B·M·F, L3 2회차. L5B 회차별 100/1(기대 100/1), L5F retry 20(기록), L3 중복 수신 0·측정 제외 0·버림 0(기록). l6 임계값 11개 확인 |
| 실패 원인 분류 | 도구 결함(판정 조건이 시험 명세와 다름: 3개 더 보고 1개 덜 봄, S6 리뷰) |
| 수정 내용 | ND-199. 앞 판정은 각 회차 폴더 `result.before-specalign.json`으로 보존 |
| 재실행 결과 | 위 건수 |
| 미검증 항목 | Gradle 시험은 Java 변경이 없어 NR-107 결과를 쓴다 |
| 관련 변경 | #970 |

#### NR-107 · 2026-11-17 · 통과 (N15 #970 운영 준비 도구, 운영 실행 없음)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-107 |
| 실행 날짜 | 2026-11-16~17 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준), 작업 트리 미커밋 |
| 작업 트리 변경 | NR-106과 같다 + `rest.js`·`stomp.js` `PROFILE=load`, `stomp-common.js`, `l6.js`, `test_sanitize_summary.py` 1건, 문서(ND-198, test-spec L1·L2·L6, 결과 문서 운영 실행 방법·U13 전달문 초안) |
| 대상 기능 | 운영 부하 도구(L1·L2·L6)와 알림 회귀 |
| 관련 NT ID | NT-LOAD-L1·L2·L6(도구만), 알림 전체 회귀 |
| 명령 | `grafana/k6:1.0.0 inspect --execution-requirements`(rest load, stomp smoke·load, l6 혼합·챗봇만, l6 확인 표시 없음), `python -I -m unittest discover -s loadtest/notification/tests`, `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | 로컬 Windows, Docker Desktop. 네트워크 요청 없음(k6 inspect는 init 단계만), 가짜 계정 파일 |
| 결과 | 통과 |
| 건수 | k6 inspect: rest load 100/s 10분·최대 VU 200, stomp load 세 단계(0초 50·4분 20초 50·8분 40초 100) 최대 VU 200·끝 13분, l6 네 시나리오·최대 VU 410, 확인 표시 없으면 init에서 멈춤. 스크립트 시험 75 OK. `:notification:test` 547 / 실패 0 / 오류 0 / 건너뜀 2, `verifyModuleStructure` 성공, `:auxiliary-app:check` 성공(`:auxiliary-app:test`는 UP-TO-DATE, 17 / 0 / 0 / 0은 이전 실행 결과) |
| 실패 원인 분류 | 없음 |
| 수정 내용 | 첫 inspect에서 `docker -e PROFILE`이 k6 `__ENV`에 들어가지 않아 smoke로 읽혔다(inspect는 시스템 환경 변수를 넣지 않음). 확인 명령을 `--env`로 바꿔 다시 확인했다. 스크립트·절차서는 `k6 run`에 `--env`로 넘긴다 |
| 재실행 결과 | 위 건수가 재실행 결과다 |
| 미검증 항목 | 운영 L1·L2·L6 실행(알림 담당·챗봇 담당 확인 뒤), 실제 로그인·티켓 발급·연결(네트워크 없이 구성만 확인), 새 sanitize 시험은 기존 코드에서 처음부터 통과(특성 확인 시험, RED 없음) |
| 관련 변경 | #970 |

#### NR-106 · 2026-11-16 · 통과 (N15 #970 L3, 1회차 무효·2회차 유효)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-106 |
| 실행 날짜 | 2026-11-16 19:57~20:21 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준), 앱 이미지 `cf9d7470…`, k6 `grafana/k6:1.0.0`(`sha256:f2127029…`) |
| 작업 트리 변경 | NR-105와 같다 + `n15.sh` L3 k6 이미지 받기 방식, `verify_n15.py` 라벨 없는 histogram 분위수 |
| 대상 기능 | 저장→실시간 전달(격리, STOMP 100연결, 충전 완료 알림 6,000건 초당 20건) |
| 관련 NT ID | NT-LOAD-L3 |
| 명령 | `loadtest/notification/n15.sh l3 1`, `n15.sh l3 2` |
| 실행 환경 | NR-103과 같다. 앱 pod는 server 노드, k6 pod는 agent 노드(같은 Docker VM 커널 시계). 연결은 사용자 헤더 직접 + 앱 티켓 발급·출처 검사·티켓 검증 그대로, JWT 서명 검증 없음(ND-195) |
| 결과 | 통과(2회차) |
| 건수 | 저장→수신 p50 5.7ms·p95 9.8ms·p99 12.9ms·최대 352.6ms(기준 p95 1초), 유실 0/6,000, 중복 수신 0, 모르는 ID 0, 연결 100/100, 티켓 실패 0, 수집 전 끊김 0, `realtime.dropped` 0 |
| 실패 원인 분류 | 1회차: 도구 결함(`k3d image import`가 Docker Desktop containerd 저장소의 다중 플랫폼 이미지에서 노드 가져오기에 실패하고도 성공으로 끝남 → k6 pod `ErrImageNeverPull`) |
| 수정 내용 | k6 이미지는 노드가 버전 고정으로 직접 받게 함(IfNotPresent), pod 실패 시 상태·로그 보존, 이미지 ID 기록. 시험 `L3K6PodTest` RED → GREEN. S4: 같은 manifest의 짧은 pod로 100연결·티켓·출처 검사·정상 종료 확인. 구간 지표 분위수는 라벨 없는 histogram용 함수로 고침(시험 RED → GREEN, 같은 수집 데이터로 다시 기록, 원래 결과 `result.before-histogram.json` 보존. 판정 항목 아님) |
| 재실행 결과 | 2회차가 재실행이다 |
| 미검증 항목 | 운영 환경 지연(격리 값, ND-188), Gateway JWT 검증 구간(ND-195) |
| 관련 변경 | #970 |

**메모**
- 보조 구간(ND-162): publish p95 4.8ms·p99 7.3ms, send p95 4.8ms·p99 5.0ms(합산하지 않음).
- 자원: CPU 최대 1,283m, 메모리 최대 625MiB(704Mi의 약 89%), Hikari active 최대 0·pending 0·timeout 0(5초 표본).

#### NR-105 · 2026-11-16 · 통과 (N15 #970 L5-F)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-105 |
| 실행 날짜 | 2026-11-16 19:28~19:51 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준), 앱 이미지 `cf9d7470…` |
| 작업 트리 변경 | NR-104와 같다 + `verify_n15.py` 측정 창 밖 회차 분리 |
| 대상 기능 | 예약 배치 실패 경로: 정상 990 + 실패 10(복제본 삭제) + 기한 지남 20 |
| 관련 NT ID | NT-LOAD-L5F |
| 명령 | `loadtest/notification/n15.sh l5 F 1` |
| 실행 환경 | NR-103과 같다 |
| 결과 | 통과 |
| 건수 | 정상 990건 11회차 안(회차당 96~99건, 11회차 18건), 최대 지연 635.1초(기준 720), 실패 10건 모두 첫 시도 회차 + 2회차에 건너뜀(실패 3회), ERROR 10·abandoned 10·retry 20, 복제본 재생성 0(149번 확인), 기한 지남 20건 기한 뒤 건너뜀·알림 0, 회차 실행 최대 0.50초, 중복·누락 0 |
| 실패 원인 분류 | 실패 없음. 처음 판정의 "회차 누락" 실패는 검증기 결함: 앱 재기동 직후 보낼 시각 전 회차(기한 지남 처리, 회차 번호 -3)의 로그를 1회차부터 시작한다고 가정한 목록에 넣었다 |
| 수정 내용 | 측정 창 밖 회차는 `preWindowRuns`로 따로 남김, 시험 RED → GREEN. 같은 수집 데이터 재판정, 원래 결과 `result.before-prewindow.json` 보존. S·B·M 재판정 결과는 그대로 |
| 재실행 결과 | 재판정만 |
| 미검증 항목 | 없음(L5 시나리오 4개 완료) |
| 관련 변경 | #970 |

**메모**
- 계산식 전제(ND-194)가 운영 이미지에서도 성립했다(정상 11회차, 실패는 3회차 동안 자리 차지).
- 자원: CPU 최대 1,910m, 메모리 최대 585MiB, Hikari active 최대 1·pending 0.

#### NR-104 · 2026-11-16 · 통과 (N15 #970 L5-S·L5-B·L5-M)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-104 |
| 실행 날짜 | 2026-11-16 16:49~17:25 (KST), 재판정 17:48 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준), 앱 이미지 `cf9d7470…` |
| 작업 트리 변경 | NR-103과 같다 + `verify_n15.py` 회차 배정(row_run, ND-197) |
| 대상 기능 | 예약 배치 몰림: 한 회차 100건, 배치 경계 101건, 연속 1,000건(격리) |
| 관련 NT ID | NT-LOAD-L5S, NT-LOAD-L5B, NT-LOAD-L5M |
| 명령 | `loadtest/notification/n15.sh l5 S 1`, `l5 B 1`, `l5 M 1`. 재판정 `python -I loadtest/notification/verify_n15.py l5 <fixture>/manifest.json <회차 폴더>` |
| 실행 환경 | NR-103과 같다(B0 cluster, 회차마다 초기화) |
| 결과 | 통과 |
| 건수 | S: 1회차 100건, 최대 지연 35.5초(기준 120). B: 1회차 100·2회차 1, 최대 지연 95.0초(기준 180). M: 10회차 각 100, 최대 지연 575.3초(기준 660). 회차 실행 최대 0.58초, 회차 누락 0, 중복·누락 0, 예상 밖 오류 0 |
| 실패 원인 분류 | 실패 없음. 처음 판정의 M 회차별 집계(7회차 101·10회차 99)는 검증기 결함: 8회차가 계산상 발화보다 1ms 일찍 시작했다 |
| 수정 내용 | 회차 배정을 회차 끝 로그 기준으로 바꿈, 시험 RED → GREEN(70 OK). S·B·M 재판정, 원래 결과 `result.before-rowrun.json` 보존(ND-197) |
| 재실행 결과 | 재판정만(회차 다시 실행 안 함, ND-197) |
| 미검증 항목 | L5-F(다음 기록) |
| 관련 변경 | #970 |

**메모**
- 회차 폴더: `n15-l5S-20261116-164920-r1`, `n15-l5B-20261116-165911-r1`, `n15-l5M-20261116-171007-r1`. 첫 L5-S 시작은 스크립트 복사본 경로 오류로 첫 명령에서 멈췄다(cluster 변경 없음, 빈 폴더 지움).
- 지연 정의: `processed_at - send_at`. 첫 회차는 보낼 시각 뒤 첫 35초라 최소 지연이 약 35초다.

#### NR-103 · 2026-11-16 · 통과 (N15 #970 L4, 1회차 무효·2회차 유효)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-103 |
| 실행 날짜 | 2026-11-16 16:02~16:43 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준), 앱 이미지 `cf9d7470…`(운영 코드 변경 없음) |
| 작업 트리 변경 | NR-102와 같다(커밋 전) + `n15.sh` 수집 단계 수정(phase_clock), `verify_n15.py` 시계 차 필드·ND-196 기록 항목 |
| 대상 기능 | L4 밀린 이벤트 회복(격리) |
| 관련 NT ID | NT-LOAD-L4 |
| 명령 | `loadtest/notification/n15.sh l4 1`, `n15.sh l4 2`(B0 cluster `k3d-hapbang-b0`, fixture `~/.hapbang/load-tests/n15/l4`) |
| 실행 환경 | Windows 10 Pro, Docker Desktop 29.8.0, k3d B0 cluster(재생성 없이 start, `b0.sh setup`으로 이미지 다시 빌드). Kafka 700Mi, `synchronous_commit=off`. 측정 전 호스트 앱 정리 |
| 결과 | 통과(2회차) |
| 건수 | 2회차: V0-1~7·V1~V8·L4 회복 통과. 할당 완료 → lag 0 46.7초(기준 300초) |
| 실패 원인 분류 | 1회차: 도구 결함(n15.sh 수집 단계 `$(for … \| head -n 1)`가 pipefail·errexit 아래 SIGPIPE로 141 종료, timing.json·검증 없음) |
| 수정 내용 | `phase_clock`으로 파이프를 끊지 않게 고침, 시험 `ClockMarkTest` RED → GREEN(S4 최소 재현 rc=141). 검증기의 시계 차를 `clusterClock`에서 읽게 고침. ND-196: V8의 실시간 전달 버림 조건을 L4에서는 기록 항목으로 분리(같은 수집 데이터 재판정, 원래 결과 `result.before-timingfix.json`·`result.before-nd196.json` 보존) |
| 재실행 결과 | 2회차가 재실행이다 |
| 미검증 항목 | L4 반복 측정(ND-196으로 유효 1회), 운영 환경 회복 시간(격리 값) |
| 관련 변경 | #970 |

**메모**
- 1회차 참고값(무효): 할당 완료 → lag 0 50.1초.
- 2회차 기록 항목: `realtime.dropped{queue_full}` 1,335, 메모리 최대 617MiB(704Mi의 약 88%), CPU 최대 4,526m, Hikari active 최대 4·pending 0. 수치 원본은 `docs/load-test/notification-load.md`.
- 2회차가 도는 중에 `n15.sh`를 고쳐(L3 추가) bash가 바뀐 파일을 이어 읽을 위험이 있어, 회차 시작 때 내용으로 되돌렸다가 끝난 뒤 다시 넣었다. 이후 회차 중에는 스크립트를 고치지 않는다.

#### NR-102 · 2026-11-16 · 통과 (N15 #970 측정 도구 회귀)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-102 |
| 실행 날짜 | 2026-11-16 15:46~15:56 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-970` |
| 커밋 | `0322495f`(기준) |
| 작업 트리 변경 | 있음(커밋 전). 알림 문서(decisions·test-spec·이 문서·PROGRESS), `docs/load-test/{README.md,notification-load.md}`, 알림 테스트 소스(`loadtest` 생성기·시험, `app.ScheduleDispatcherFailureSlotTest`), `loadtest/notification/{n15.sh,verify_n15.py,k3d/tools/poll_group_lag.sh,queries/l5_*.sql,tests/test_n15_scripts.py}`. 운영 코드 변경 없음 |
| 대상 기능 | N15 격리 시험 도구: B0 fixture 불변, L4·L5·L3 fixture, L5-F 계산식 전제, L4·L5 판정·lag 관측·운영 context 거절 |
| 관련 NT ID | NT-LOAD-L4·L5S·L5B·L5M·L5F·L3(도구·전제 부분), NT-B0-01~10(회귀) |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check --continue`, `python -I -m unittest discover -s loadtest/notification/tests`, `bash loadtest/notification/tests/b0_guard_test.sh` |
| 실행 환경 | Windows 10 Pro, Gradle 9.7.1, Docker Desktop 실행 중, Testcontainers postgres·kafka·redis. B0 cluster는 멈춰 있음(측정 아님) |
| 결과 | 통과 |
| 건수 | `:notification` 성공 545(실패 0, 오류 0, 건너뜀 2: 환경 변수가 있을 때만 도는 내보내기 시험 `B0FixtureExportTest`·`N15FixtureExportTest`), `:auxiliary-app` 17, 스크립트 시험 62(B0 43 + N15 19), guard 통과. XML 기준, 새 실행 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음. 첫 명령은 `:notification:verifyModuleStructure`라는 없는 작업 이름으로 Gradle이 바로 실패했다(작업 선택 오류, 시험 실행 전). 이름을 루트 작업으로 고쳐 다시 실행했다 |
| 재실행 결과 | 위 명령이 재실행 결과다 |
| 미검증 항목 | 실제 L3·L4·L5 회차(B0 cluster, 다음 기록), L1·L2·L6(운영) |
| 관련 변경 | #970 |

**메모**
- 뮤테이션 확인(되돌림, 운영 코드 diff 0): B0 크기 하나(LEGACY 50→51) → `B0FixtureInvarianceTest` 3건 실패. `ScheduleDispatcher.MAX_FAILURES` 3→4 → `ScheduleDispatcherFailureSlotTest` 실패(건너뜀이 한 회차 늦음).
- `B0FixtureInvarianceTest`·`ScheduleDispatcherFailureSlotTest`는 지금 코드의 동작을 고정하는 시험이라 처음부터 통과했다(TDD의 실패 단계 대신 뮤테이션으로 검출력을 확인). `poll_group_lag.sh`의 커밋 전 partition 처리는 시험보다 먼저 고쳤다(순서 예외).

#### NR-101 · 2026-11-16 · 통과 (N14 #955 PR 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-101 |
| 실행 날짜 | 2026-11-16 13:50~14:27 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-955` |
| 커밋 | `40b23291` 위 미커밋 변경(PR 리뷰 반영) |
| 작업 트리 변경 | `loadtest/notification/b0.sh`, `loadtest/notification/tests/b0_guard_test.sh`, `.../in/event/kafka/RejectedEvents.java`, `.../in/event/NotificationLectureEventListener.java`, 새 파일 `.../in/event/kafka/RejectedEventsTest.java`(`...`는 `apps/auxiliary-app/boundedContext/notification/src/{main,test}/java/com/hapbang/auxiliary/notification`), `docs/notification/test-spec.md`·`decisions.md`·`regression-log.md` |
| 대상 기능 | ① `b0.sh` 끊김 확인 순서(스냅샷 → 대기 → 멈춤 → 비교, 스냅샷 실패·빈 로그는 실패) ② 강의 리스너 검증 변환 메시지를 실패 때만 만듦(`RejectedEvents.orReject(Supplier<String>, …)`) ③ test-spec NT-REF-01 문장 |
| 관련 NT ID | NT-B0-10, NT-REF-01~04·06, 회귀 전체 |
| 명령 | `bash loadtest/notification/tests/b0_guard_test.sh`(4회); `python -I -m unittest discover -s loadtest/notification/tests -p "test_*.py"`; `./gradlew :notification:test --tests "*.RejectedEventsTest"`; `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10, Git Bash, JDK 25(Gradle Toolchain). guard 시험의 dry-run 단계는 B0 cluster context `k3d-hapbang-b0`, 모의 실행 단계는 cluster 없이 모의 kubectl·모의 따라 받기 |
| 결과 | 통과. guard 시험 PASS 7·SKIP 1(cluster가 있어 "cluster 없음" 시험은 건너뜀)을 S6 반영 뒤 4회 연속, 모의 반복 프로세스 남음 0, 스크립트 시험 43 OK. `:notification:test` 529(실패 0·오류 0·건너뜀 1, 새 `RejectedEventsTest` 3 포함), `:auxiliary-app:test` 16, `verifyModuleStructure` 통과. 세 Gradle 태스크 모두 새로 실행(UP-TO-DATE 아님) |
| 건수 | 위 결과 |
| 실패 원인 분류 | — |
| 수정 내용 | PR 리뷰 지적 3건: 멈춘 뒤 스냅샷을 받아 생기는 거짓 실패, 문서의 빈 종류 수, 강의 리스너가 레코드마다 메시지를 미리 만듦. S6 리뷰 뒤: 따라 받기가 kill 전에 끝났으면 비교 결과와 상관없이 실패(그 뒤 앱 로그가 없으면 비교로 드러나지 않았다), guard 시험 중단 때 모의 프로세스 정리(trap)·함수 추출 확인. S6 두 축 리뷰 뒤: dry-run에 생존 확인 줄을 넣고 guard 시험이 스냅샷 → 대기 → 생존 확인 → kill → 비교 순서를 모두 단언, `RejectedEvents`의 String 판이 Supplier 판을 부르게 해 중복 제거(이 뒤 Gradle 세 태스크 다시 실행, 결과 같음) |
| 재실행 결과 | 위 결과 |
| RED 확인 | guard 시험이 수정 전 실패 4건: dry-run 순서(스냅샷=없음), 모의 정상 따라 받기의 거짓 실패(종료 코드 2), 먼저 끝난 따라 받기의 원인 문구 없음, 스냅샷 실패 문구 없음(종료 코드 1). `RejectedEventsTest`는 오버로드가 없어 컴파일 실패였다. 메시지를 먼저 만드는 뮤테이션에서 3건 모두 실패했고, 되돌린 뒤 통과했다(diff 16줄 그대로). S6 반영의 "먼저 끝난 따라 받기" 실패는 그 die를 지운 뮤테이션에서 두 사례가 실패(조용한 경우는 종료 코드 0으로 통과)했고, 되돌린 뒤 통과했다. 순서 단언은 생존 확인 dry-run 줄을 지운 뮤테이션에서 "생존=없음"으로 실패했고 되돌린 뒤 통과했다 |
| 미검증 항목 | 실제 B0 회차는 돌리지 않았다(사용자 결정, 측정 논리 무변경). 구성 시험 A·B 파일은 바꾸지 않았다 |
| 관련 변경 | #955 |

#### NR-100 · 2026-11-16 · 통과 (N14 #955 S6 리뷰 뒤 B0 도구 확인, 비공식 회차)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-100 |
| 실행 날짜 | 2026-11-16 12:35~12:46 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-955` |
| 커밋 | 앱 이미지 `f3c15dca`(pod 안 `app.jar` `6d56bbe7…`). 도구: 미커밋 변경(S6 반영: `cleanup()` 하나로 정리, `stop_log_follow()`의 끊김 확인, `.gitattributes` `* text eol=lf`) |
| 대상 기능 | ND-185 앱 로그 따라 받기의 멈춤·끊김 확인이 실제 회차에서 동작하는지 |
| 관련 NT ID | NT-B0-10 |
| 명령 | `bash loadtest/notification/tests/b0_guard_test.sh`(강화 전 RED → 뒤 GREEN); `python -I -m unittest discover -s loadtest/notification/tests`; `loadtest/notification/b0.sh run 9` |
| 실행 환경 | NR-098과 같다 |
| 결과 | 통과. guard 시험 통과, 스크립트 시험 43 OK. 도구 확인 회차 `b0-20261116-123539-r9`: V0~V8 PASS, F `5a7ab852…`, `logCoversRound=true`, 끊김 확인 통과(`app.log`·`app-final.log` 5,670,918바이트, `app-log.stderr` 0바이트). 처리량 197.0건/초·투입 242.4건/초는 공식 회차가 아니라 판정에 쓰지 않는다 |
| 건수 | 위 결과 |
| 실패 원인 분류 | — |
| 수정 내용 | S6 리뷰: guard 시험이 설명 로그 문장만 보아 실제 멈춤을 확인하지 않았다 → 멈춤 단계가 dry-run 표지(`+ kill <앱 로그 따라 받기>`, 끊김 확인)를 내게 하고 그것을 순서와 함께 단언 |
| 재실행 결과 | 위 결과 |
| RED 확인 | 강화한 guard 시험이 수정 전 "멈춤=없음 확인=없음"으로 실패, 수정 뒤 통과 |
| 미검증 항목 | 따라 받기가 실제로 끊기는 경우의 멈춤(재현하지 않음) |
| 관련 변경 | #955 |

#### NR-099 · 2026-11-16 · 통과(기능)·참고(성능) (N14 #955 ND-183 대조 측정, 리팩토링 전 dev 이미지)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-099 |
| 실행 날짜 | 2026-11-16 11:29~12:23 (KST) |
| 실행자 | Claude Code |
| 브랜치 | 이미지: worktree `../beadv8_8_LMS_BE-n14-control`(detached `3f5f8319`, 변경 0). 측정 도구: `feature/tkt-955` 작업 트리(NR-098과 같은 미커밋 도구 변경) |
| 커밋 | `3f5f8319`(리팩토링 전 dev). 회차 폴더 `conditions.txt`의 `git=`은 측정 도구를 돈 메인 작업 트리 HEAD(`f3c15dca`)가 찍혔으므로 이 표가 정정이다 |
| 대상 기능 | ND-183 대조 측정(N14 불합격 원인 가름) |
| 관련 NT ID | NT-REF-05(대조) |
| 명령 | `./gradlew -p <worktree> :auxiliary-app:bootJar` → `docker build`(태그 `hapbang/auxiliary-app:local`·`control-3f5f8319`) → `k3d image import` → `loadtest/notification/b0.sh run 1`~`5` |
| 실행 환경 | NR-098과 같은 호스트·Docker Desktop·`k3d-hapbang-b0`, 같은 fixture(`bea6c107…`)·Kafka 700Mi·`synchronous_commit=off`·sampler. Gradle daemon 정지 |
| 결과 | 기능 통과: 5회 모두 V0~V8 PASS, F `5a7ab852…`(5461행). 성능(참고, 판정에 쓰지 않음): 처리량 중앙값 199.01건/초, CV 21.86% |
| 건수 | 처리량 110.96 / 190.61 / 203.06 / 199.01 / 202.76, 투입 136.5 / 253.0 / 257.2 / 251.4 / 258.2, p95 38.08 / 26.48 / 26.67 / 26.57 / 26.50, p99 55.62 / 29.30 / 29.33 / 29.31 / 29.30, GC 27·24·24·24·24, Hikari 활성 최대 2~3·대기 0, 실패 단계 복구 398.3~433.4초. 회차 폴더 `b0-20261116-112932-r1`·`114014-r2`·`115100-r3`·`120142-r4`·`121223-r5` |
| 실패 원인 분류 | 성능 차이: 환경(F-ENV) 쪽 근거 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | 이미지 확인: 대조 jar에 리팩토링 클래스(`NotificationKafkaConsumerRegistrar`·`RejectedEvents`) 0개·기존 설정·리스너 클래스 있음. 회차마다 실행 중인 pod 안 `/app/app.jar` SHA-256이 대조 jar(`bec2d278…`)와 같았다. docker 이미지 `b64e261e…`, containerd `fdba615f…` |
| 미검증 항목 | 대조 1회차의 투입 속도 급락(136.5) 원인. 환경 변화와 리팩토링 영향의 정량 분리(같은 시간대 비교로 근거만 얻음) |
| 관련 변경 | #955. 측정 뒤 N14 이미지로 복구(docker `bec6d883…`, pod 안 jar `6d56bbe7…`, imageID `3b578c0a…`) |

#### NR-098 · 2026-11-16 · 불합격(성능, ND-175) (N14 #955 B0 재측정 공식 5회)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-098 |
| 실행 날짜 | 2026-11-16 09:09~11:16 (KST). 공식 5회 10:23~11:16 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-955` |
| 커밋 | 앱 이미지: `f3c15dca`(로컬 커밋, Push 전). 측정 도구: 같은 HEAD + 미커밋 변경(B0 도구 줄 끝 LF 변환, `b0.sh` 앱 로그 따라 받기, guard 시험, `loadtest/notification/.gitattributes`) |
| 작업 트리 변경 | 위 도구 변경. 앱 코드 변경 없음 |
| 대상 기능 | N14 리팩토링 뒤 B0 재측정(ND-175·ND-183) |
| 관련 NT ID | NT-REF-05, NT-B0-10(guard 시험 추가) |
| 명령 | `loadtest/notification/b0.sh setup`(bootJar·이미지 `bec6d883…`), `b0.sh run 1`~`5`; `bash loadtest/notification/tests/b0_guard_test.sh`; `python -I -m unittest discover -s loadtest/notification/tests` |
| 실행 환경 | Windows 10, Docker Desktop(측정 전 다시 켬), `k3d-hapbang-b0`(멈춰 있던 cluster를 `k3d cluster start`로 다시 시작, 재생성 아님), fixture `bea6c107…`, Kafka 700Mi, `auxiliary` `synchronous_commit=off`, sampler 그대로. 공식 5회 전 호스트 정리(Slack·Notion 종료, Chrome 창 축소, Gradle daemon 정지, 여유 메모리 2.5 → 4.0GB) |
| 결과 | 기능 통과: 5회 모두 V0~V8 PASS, F `5a7ab852…`(5461행, B0와 같음). 성능 불합격: 처리량 중앙값 198.66건/초 < 213.768(B0 237.52의 83.6%), CV 1.07%. p95 26.57·p99 29.31초(B0와 같은 10~30초 구간) |
| 건수 | 처리량 202.11 / 198.66 / 198.66 / 198.35 / 202.74, 투입 254.7 / 249.6 / 254.2 / 250.5 / 254.4(B0 290~303), GC 24~26회, Hikari 활성 최대 2~4·대기 0, 실패 단계 복구 402.4~434.4초, 관측 오차 2.3~2.5%. 회차 폴더 `b0-20261116-102321-r1`·`103405-r2`·`104432-r3`·`105508-r4`·`110538-r5`. 스크립트 시험 43 OK, guard 시험 통과 |
| 실패 원인 분류 | 미분류(성능). 대조 측정(NR-099)은 환경 쪽 근거 |
| 수정 내용 | 공식 5회 전 무효 회차: ① `b0-20261116-090943-r1` — 체크아웃이 B0 도구를 CRLF로 써서 도구 pod의 polling 스크립트가 문법 오류로 끝남(`$'\r': command not found`) → 작업 트리 도구 파일 27개를 LF로 바꾸고 `loadtest/notification/.gitattributes`(`* text eol=lf`) 추가. ② `092256-r1`·`093651-r2`·`094804-r3`·`095854-r4`(219.79 / 195.18 / 185.24 / 115.41건/초, 투입 274.7 → 139.4로 함께 하락) — r4는 앱 로그 회전으로 V8 `logCoversRound=false`(오류 건수는 기대와 같음) → `b0.sh`가 앱 기동 직후부터 `kubectl logs -f`로 받게 수정(V8 기준 그대로). 호스트 정리 뒤 1회차부터 다시 쟀다 |
| 재실행 결과 | 위 결과 |
| RED 확인 | guard 시험 "앱 로그 따라 받기"가 수정 전 실패(따라 받기 명령 없음), 수정 뒤 통과. 공식 5회 모두 `logCoversRound=true` |
| 미검증 항목 | 환경 변화의 원인(투입 속도 B0 대비 −15.7%), 운영 처리량. 공식 판정 후속은 ND-186(불합격 유지, 예외 병합 추진) |
| 관련 변경 | #955 |

#### NR-097 · 2026-11-16 · 통과 (N14 #955 S6 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-097 |
| 실행 날짜 | 2026-11-16 00:21~00:26 (KST). 의존 관계 시험 단독 실행 00:1x |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-955` |
| 커밋 | 미커밋 작업 트리(기준 `3f5f8319`) |
| 작업 트리 변경 | NR-096 변경 + S6 반영: 등록기 DLT 토픽 선언이 `NotificationKafkaTopicConfig` 빈에 의존하게 함(ND-184), `ValueFormat.valueType()`, `UnsupportedEvents.skipOrReject`, 강의 리스너 검증 변환을 `RejectedEvents.orReject`로, `NotificationKafkaConsumerDependencyTest` 추가 |
| 대상 기능 | N14 리팩토링 전후 동일성, 등록기 빈 의존 관계 |
| 관련 NT ID | NT-REF-01~04·06, 회귀 전체 |
| 명령 | `./gradlew :notification:test --tests '*NotificationKafkaConsumerDependencyTest'`(반영 전·뒤); `./gradlew :notification:test`; `./gradlew verifyModuleStructure`; `./gradlew :auxiliary-app:check`; `python -I -m unittest discover -s loadtest/notification/tests` |
| 실행 환경 | NR-096과 같다 |
| 결과 | 통과(모두 종료 코드 0). `:notification:test`·`:auxiliary-app:test`는 새로 실행했다 |
| 건수 | `:notification` 526(실패 0·오류 0·건너뜀 1), 이 중 구성 시험 A 55·B 1·의존 관계 6. `:auxiliary-app` 16. 스크립트 시험 43 |
| 실패 원인 분류 | — |
| 수정 내용 | S6 Spec 리뷰가 "supplier 안 `getBean`은 의존 관계를 남기지 않는다"고 지적했다. 시험을 먼저 써 보니 Spring은 supplier 안의 `getBean`도 만드는 빈의 의존 관계로 기록해, 빠진 것은 static 조립 메서드를 쓰는 DLT 토픽 선언의 `NotificationKafkaTopicConfig` 의존 하나였다. 등록기가 그 빈을 먼저 찾게 고쳤다 |
| 재실행 결과 | 위 결과 |
| RED 확인 | 의존 관계 시험 6건(소비자마다)이 DLT 토픽 → `notificationKafkaTopicConfig` 의존 없음으로 실패했다. 나머지 단정(팩토리·템플릿·오류 처리기·컨테이너 팩토리의 의존)은 반영 전에도 통과했다. 반영 뒤 6건 통과. 구성 시험 A·B 파일의 SHA-256은 NR-096과 같다 |
| 미검증 항목 | B0 재측정 5회(NT-REF-05, 커밋 뒤), CI 전체 `./gradlew check` |
| 관련 변경 | #955 |

#### NR-096 · 2026-11-16 · 통과 (N14 #955 구조 리팩토링 회귀)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-096 |
| 실행 날짜 | 2026-11-15 23:53 ~ 2026-11-16 00:01 (KST). 구성 시험 단독 실행은 2026-11-15 23:25~23:45 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-955` |
| 커밋 | 미커밋 작업 트리(기준 `3f5f8319`) |
| 작업 트리 변경 | Kafka 설정 공통화(`NotificationKafkaConsumer`·`NotificationKafkaConsumerRegistrar`, 설정 클래스 3개의 토픽별 `@Bean` 제거), 리스너 공통 부품(`NotificationEventSubscriber`·`RejectedEvents`·`UnsupportedEvents`, 리스너 6개), 구성 시험 2개 추가, `NotificationKafkaDeadLetterTest` 호출부 2곳(없앤 `@Bean` 메서드 대신 `NotificationKafkaConsumer.MEMBER.settings()`·`deadLetterProducerFactory`, 단정은 그대로) |
| 대상 기능 | N14 리팩토링 전후 동일성 |
| 관련 NT ID | NT-REF-01~04, 회귀 전체 |
| 명령 | `./gradlew :notification:test --tests '*NotificationKafkaConsumerConfigurationTest' --tests '*NotificationKafkaListenerConfigurationTest'`(리팩토링 전·뮤테이션·리팩토링 뒤); `./gradlew :notification:test`; `./gradlew verifyModuleStructure`; `./gradlew :auxiliary-app:check`; `python -I -m unittest discover -s loadtest/notification/tests`; `bash loadtest/notification/tests/b0_guard_test.sh` |
| 실행 환경 | Windows 10, Git Bash, JDK 25(Gradle toolchain), Docker Desktop(Testcontainers) |
| 결과 | 통과(모두 종료 코드 0). `:notification:test`·`:auxiliary-app:test`는 새로 실행했다(`:auxiliary-app:check` 안의 `:notification:test`는 바로 앞 실행의 UP-TO-DATE) |
| 건수 | `:notification` 520(실패 0·오류 0·건너뜀 1: 환경 변수가 있을 때만 도는 `B0FixtureExportTest`), 이 중 구성 시험 A 55·B 1. `:auxiliary-app` 16. 스크립트 시험 43, guard 시험 통과(cluster 없음 시험 1건은 `k3d-hapbang-b0`가 있어 건너뜀) |
| 실패 원인 분류 | — |
| 수정 내용 | 구성 시험 A 첫 작성 때 시험 쪽 결함 2건: ① broker 없는 문맥에서 Boot `KafkaAdmin`이 DLT 토픽 생성을 기다려 10분을 넘김 → 시험 문맥에서만 `spring.kafka.admin.auto-create=false`(NewTopic 빈은 그대로 만든다), ② 재시도 listener가 lambda라 `getEnclosingClass()`가 null → `getNestHost()`로 확인. 시험 B 첫 실행은 Docker Desktop이 꺼져 문맥이 뜨지 않음(환경) → 켠 뒤 통과 |
| 재실행 결과 | 위 결과 |
| RED 확인 | 구성 시험은 리팩토링 전 코드를 정답으로 고정하는 시험이라 처음부터 통과한다(ND-182). 뮤테이션으로 실패를 확인했다: 시험 A — backoff 최대값 +1(6건), 강의 ContainerFactory를 바로 시작하는 factory로(1건), 채팅 DLT 템플릿의 `defaultCandidate` 제거(플래그 1건과 Boot 기본 `kafkaTemplate`이 물러나 이름 없는 주입 1건) → 55건 중 9건 실패. 시험 B — 강의 바로 시작 뮤테이션에서 `notification-lecture-events` autoStartup 기대 false·실제 true. 모두 되돌린 뒤 `git diff` 운영 코드 0줄. 리팩토링 뒤 두 시험 파일의 SHA-256(`dab52c8a…`, `d82f50e8…`)이 리팩토링 전과 같다 |
| 미검증 항목 | B0 재측정 5회(NT-REF-05, 커밋 뒤), CI 전체 `./gradlew check` |
| 관련 변경 | #955 |

#### NR-095 · 2026-11-15 · 통과 (N13 #946 PR #954 CI 실패 수정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-095 |
| 실행 날짜 | 2026-11-15 21:46~21:52 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `a7e483d1`) |
| 작업 트리 변경 | `loadtest/B0ConsoleProducerCompatibilityTest.java`(투입 파일 복사 권한 0644 명시) |
| 대상 기능 | NT-B0-08(G8) |
| 관련 NT ID | NT-B0-08, 회귀 전체 |
| 명령 | `./gradlew -p apps/auxiliary-app :notification:test --tests '*B0ConsoleProducerCompatibilityTest'`; NR-090의 회귀 3명령 |
| 실행 환경 | NR-090과 같다 |
| 결과 | 통과(종료 코드 0). G8 1건 통과. 세 태스크 모두 새로 실행 |
| 건수 | `:notification` 464(실패 0·오류 0·건너뜀 1), `:auxiliary-app` 16 |
| 실패 원인 분류 | 테스트 환경(파일 권한) |
| 수정 내용 | CI(Linux)에서 G8이 90행(producer 종료 코드)에서 실패했다. Linux의 `Files.createTempFile`은 0600이고 Testcontainers `MountableFile.forHostPath(file)`는 그 권한 그대로 root 소유로 복사해, `apache/kafka:4.4.0` 컨테이너의 `appuser`(uid 1000)가 투입 파일을 읽지 못했다(`Permission denied`). Windows는 POSIX 권한이 없어 로컬에서 드러나지 않았다. 복사 권한을 0644로 명시했다. 검증 조건은 바꾸지 않았다 |
| 재실행 결과 | 위 결과 |
| RED 확인 | 로컬에서 복사 권한을 0600으로 강제하자 `bash: line 1: /tmp/b0/chat.events.v1.txt: Permission denied`, 기대 0·실제 1로 같은 90행에서 실패. 0644 명시 뒤 통과. 확인용 표식이 남지 않았음을 grep으로 확인 |
| 미검증 항목 | CI(Linux)에서의 재실행(Push 뒤 확인), 전체 `./gradlew check`(CI) |
| 관련 변경 | PR #954 CI |

#### NR-094 · 2026-11-15 · 통과 (N13 #946 PR 리뷰 반영 S6 재리뷰 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-094 |
| 실행 날짜 | 2026-11-15 21:11~21:18 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `6af69b6c`) |
| 작업 트리 변경 | NR-093 목록 + `NotificationOutcomeRecorder`의 전제 Javadoc과 필드 이름(`OUTSIDE_TX_OUTCOMES`), `b0.sh` Kafka 확인(실행 중 pod, 컨테이너 이름, 한도·요청, `B0_KAFKA_MEMORY`) |
| 대상 기능 | N13 PR 리뷰 반영 |
| 관련 NT ID | NT-OBS-11, 회귀 전체 |
| 명령 | NR-090과 같다 + B0 cluster에서 `kubectl get pod kafka-0`(Kafka 한도·요청 확인 명령) |
| 실행 환경 | NR-090과 같다 |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행. 스크립트 시험 통과, b0.sh 문법 검사 통과, Kafka 확인 `700Mi/700Mi` |
| 건수 | `:notification` 464(실패 0·오류 0·건너뜀 1), `:auxiliary-app` 16, 스크립트 시험 43 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | NR-093 |
| 미검증 항목 | 전체 `./gradlew check`(미실행, CI), B0 Kafka 700Mi patch로 회차 실행(미실행) |
| 관련 변경 | #946 PR Bot 리뷰 |

#### NR-093 · 2026-11-15 · 통과 (N13 #946 PR Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-093 |
| 실행 날짜 | 2026-11-15 21:00~21:06 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `6af69b6c`, origin/dev `2f6af390` 병합 포함) |
| 작업 트리 변경 | `app/NotificationOutcomeRecorder.java`(트랜잭션 밖 결과는 레코드 성공 뒤, ND-177), `in/event/kafka/NotificationKafkaMetrics.java`(성공 시점 호출), 시험 `app/NotificationOutcomeRecorderTest`·`in/event/NotificationAppearanceProgressAlertTest`, `loadtest/notification/k3d/platform/kustomization.yaml`(Kafka 700Mi, ND-178)·`b0.sh`(한도 확인), 문서 |
| 대상 기능 | N13 PR 리뷰 반영 |
| 관련 NT ID | NT-OBS-11, 회귀 전체 |
| 명령 | NR-090과 같다 |
| 실행 환경 | NR-090과 같다 |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행. 스크립트 시험 통과, guard 2 PASS·3 SKIP, b0.sh 문법 검사 통과 |
| 건수 | `:notification` 464(실패 0·오류 0·건너뜀 1), `:auxiliary-app` 16(dev 병합으로 늘어남), 스크립트 시험 43 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | 기록기에 빈 `succeeded()`만 둔 상태에서 새·바꾼 시험 3건이 실패(단위 0 대 1, 1 대 2, Kafka 0 대 2), 구현 뒤 통과 |
| 미검증 항목 | 전체 `./gradlew check`(미실행, CI), B0 Kafka 700Mi patch로 회차 실행(미실행, 렌더링·현재 cluster 값만 확인) |
| 관련 변경 | #946 PR Bot 리뷰 |

#### NR-092 · 2026-11-15 · 통과 (N13 #946 S6 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-092 |
| 실행 날짜 | 2026-11-15 20:33~20:40 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `52208c28`) |
| 작업 트리 변경 | NR-090 목록 + 리뷰 반영: `k3d/tools/poll_latency_count.sh`(추가 확인은 성공 조회만), `queries/commit_setting.sql`·`b0.sh`(앱 역할 설정까지 확인), `verify_b0.py`(V8 감싼 예외 면제 제거·로그 범위, V7 회차 전체, 명령줄 모드 제거), `loadtest/k6/notification/rest.js`(N15 프로필 제거), 생성기 seed 상수·예약 종류 상수 이름, 시험 |
| 대상 기능 | N13 |
| 관련 NT ID | NT-OBS-11·12, NT-B0-01~10, 회귀 전체 |
| 명령 | NR-090과 같다 |
| 실행 환경 | NR-090과 같다 |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행. 스크립트 시험 통과, guard 2 PASS·3 SKIP, b0.sh 문법 검사 통과 |
| 건수 | `:notification` 462(실패 0·오류 0·건너뜀 1), `:auxiliary-app` 13, 스크립트 시험 43 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | 공식 B0 5회(NR-091)의 수집 데이터를 고친 검증기로 다시 검증: 5회 모두 V0~V8 PASS, F 같음, 성능 값 같음(측정은 다시 하지 않음, 이전 결과는 회차 폴더의 `result.before-review.json`) |
| RED 확인 | 추가 시험 4건(V8 면제, V8 로그 범위, V7 회차 전체, polling 조회 실패)을 먼저 써서 실패를 확인했다. V8 시험은 `ListenerExecutionFailedException` 면제를 되돌린 뮤테이션에서 실패, 복원 뒤 `cmp` 같음 |
| 미검증 항목 | 전체 `./gradlew check`(미실행), 운영 smoke L1·L2(미실행) |
| 관련 변경 | #946 |

#### NR-091 · 2026-11-15 · 통과 (N13 #946 B0 공식 기준선 5회)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-091 |
| 실행 날짜 | 2026-11-15 19:20~20:13 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `52208c28`) |
| 작업 트리 변경 | NR-090 목록과 같다 |
| 대상 기능 | N13 B0(E2E, 로컬 k3d `k3d-hapbang-b0`) |
| 관련 NT ID | NT-B0-11 |
| 명령 | `bash loadtest/notification/b0.sh run <1~5>`(차례로, 실패하면 멈춤). fixture `bea6c107…`, B0 `auxiliary` database `synchronous_commit=off`(ND-170), 진단 끔 |
| 실행 환경 | Windows 10, Docker Desktop VM 12코어, k3d server·agent, 앱 704Mi·`-Xmx256m`, 도구 pod `b0-kafka-tools`(apache/kafka:4.4.0) |
| 결과 | 통과. 5회 모두 V0-1~V0-7·V1~V8 PASS, 결과 지문 F 5회 같음(`5a7ab852…`, 5,461행). 정상 단계 처리량 CV ≤ 10%. 회차별 수치와 통계의 원본은 [B0 기준선](../load-test/notification-baseline.md) |
| 건수 | 회차당 회원 1,000·알림 5,000(정상 4,925, 실패 75) |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | — (E2E) |
| 미검증 항목 | 운영 smoke L1·L2(미실행, 알림 담당). 전체 `./gradlew check`(미실행) |
| 관련 변경 | #946. 수치 원본은 [B0 기준선](../load-test/notification-baseline.md), 판정 기준 ND-175 |

#### NR-090 · 2026-11-15 · 통과 (N13 #946 구현·도구 회귀)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-090 |
| 실행 날짜 | 2026-11-15 20:15~20:22 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `52208c28`) |
| 작업 트리 변경 | 운영 코드 `app/NotificationAlertService.java`(사전 판단 건너뜀 집계, ND-164). 테스트 `app/NotificationOutcomeRecorderIntegrationTest`, `in/event/NotificationAppearanceProgressAlertTest`, `in/event/NotificationKafkaMetricsIntegrationTest`, 새 `in/event/kafka/B0FixtureContractTest`, 새 `loadtest/` 패키지(생성기·시험 10개 파일). 도구 `loadtest/notification/`(b0.sh, k3d·tools·diag·queries, normalize·verify·diag 스크립트, tests), `loadtest/k6/notification/`. 문서 decisions·module·test-spec·regression-log·PROGRESS, `docs/load-test/` |
| 대상 기능 | N13(ND-163~ND-175) |
| 관련 NT ID | NT-OBS-11·12, NT-B0-01~10, 회귀 전체 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check --rerun-tasks -x :chatbot:test -x :search:test`; `python -I -m unittest discover -s loadtest/notification/tests`; `bash loadtest/notification/tests/b0_guard_test.sh` |
| 실행 환경 | Windows 10, Git Bash, JDK 25(Gradle toolchain), Docker Desktop(Testcontainers) |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행. 스크립트 시험 통과. guard 시험 2 PASS, 3 SKIP(B0 cluster가 있어 "cluster 없음" 시험을 건너뜀) |
| 건수 | `:notification` 462(실패 0·오류 0·건너뜀 1: 환경 변수가 있을 때만 도는 `B0FixtureExportTest`), `:auxiliary-app` 13, 스크립트 시험 40 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | 사전 판단 집계 2건(서비스 0 대 1, Kafka 1 대 10), 가입 환영 기대값, V2 회차 전체·기대 밖 토픽, 단계 분리(G9 컴파일), 보간·건수·V0-7·완료 관측·도구 pod polling·클러스터 시계·진단 분석 시험을 먼저 써서 실패를 확인했다. `NotificationKafkaMetricsIntegrationTest`의 정확히 한 번 시험은 바로 통과해 지연 기록을 `intercept`로 옮긴 뮤테이션에서 7 대 8로 실패시킨 뒤 되돌렸다(`git diff` 0줄) |
| 미검증 항목 | 전체 `./gradlew check`(미실행), 챗봇·검색 시험(제외) |
| 관련 변경 | #946 |

#### NR-089 · 2026-11-15 · 기록 (N13 #946 공식 기준선 전 B0 회차)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-089 |
| 실행 날짜 | 2026-11-14 21:35 ~ 2026-11-15 19:17 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-946` |
| 커밋 | 미커밋 작업 트리(기준 `52208c28`, 회차마다 도구가 달랐다) |
| 작업 트리 변경 | 회차 사이에 도구·생성기를 고쳤다(아래 수정 내용) |
| 대상 기능 | N13 B0(E2E) |
| 관련 NT ID | NT-B0-11 |
| 명령 | `bash loadtest/notification/b0.sh run 1`(여러 번), `run 1~5`, 진단 `B0_DIAG=1 … run d1~d5` |
| 실행 환경 | NR-091과 같다(진단 회차 전에는 `synchronous_commit` 기본값 on) |
| 결과 | 공식 기준선 아님. 회차·이유는 [B0 기준선](../load-test/notification-baseline.md) "공식 기준선 전 회차 기록" 표. 161625~172205의 5회와 진단 5회는 모두 V0~V8 PASS·F 같음(기능 동일성 자료) |
| 건수 | — |
| 실패 원인 분류 | 도구 결함(토픽 삭제 대기, 도구 JVM 메모리, 덤프 인코딩, 종료 처리), 생성기 결함(가입 환영 누락), 구현 결함(사전 판단 건너뜀 집계 누락), 측정 방법(한 단계 투입, 완료 감지, polling 정밀도), 환경(WAL fsync 변동) |
| 수정 내용 | ND-163~ND-170. 기대값(manifest)은 실측에 맞추지 않고 카탈로그·정책으로 다시 계산했다 |
| 재실행 결과 | NR-091 |
| RED 확인 | NR-090 |
| 미검증 항목 | — |
| 관련 변경 | #946 |

#### NR-088 · 2026-11-14 · 통과 (N12 #944 S6 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-088 |
| 실행 날짜 | 2026-11-14 19:51~19:56 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-944` |
| 커밋 | 미커밋 작업 트리(기준 `c0414dbd`) |
| 작업 트리 변경 | NR-087 목록 + 연결 수 Gauge 등록 실패 격리, histogram 경계를 바꿀 수 없는 `List`로, `NotificationKafkaErrorHandlerConfig` 필드 위치, 테스트 import 정리, 새 `app.NotificationOutcomeRecorderIntegrationTest` |
| 대상 기능 | N12 |
| 관련 NT ID | NT-OBS-01~10, 회귀 전체 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check --rerun-tasks -x :chatbot:test -x :search:test` |
| 실행 환경 | NR-087과 같다 |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행 |
| 건수 | `:notification` 441(NR-087 + 1), `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | 새 통합 시험은 집계를 commit 뒤로 걸지 않게 바꾼 뮤테이션에서 "expected 0.0 but was 1.0"으로 실패, 되돌린 뒤 통과. 뮤테이션 표식이 남지 않았음을 grep으로 확인 |
| 미검증 항목 | NR-087과 같다(전체 check, 늦은 DLT 성공, 운영 수집) |
| 관련 변경 | #944 |

#### NR-087 · 2026-11-14 · 통과 (N12 #944 계측 보강 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-087 |
| 실행 날짜 | 2026-11-14 19:37~19:42 (KST). RED·뮤테이션 확인은 그 전 19:2x~19:35 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-944` |
| 커밋 | 미커밋 작업 트리(기준 `c0414dbd`) |
| 작업 트리 변경 | 새 코드 `NotificationKafkaMetrics`·`NotificationOutcomeRecorder`·`NotificationOutboundLatencyInterceptor`, 수정 `NotificationKafkaConfig`·`NotificationKafkaErrorHandlerConfig`·`NotificationWriter`·`NotificationRealtimeRelay`·`NotificationRealtimeRedisListener`·`NotificationWebSocketConfig`·`NotificationWebSocketSessions`. 새 테스트 6개 클래스와 기존 테스트 4개(생성자 인자·발행 구간 2건) |
| 대상 기능 | N12 |
| 관련 NT ID | NT-OBS-01~10, 회귀 NT-KAF·NT-ERR·NT-IDEM·NT-RT 전부 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check --rerun-tasks -x :chatbot:test -x :search:test` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0). OBS-02는 자동 생성을 끈 전용 Kafka 컨테이너(`apache/kafka:4.2.1`) |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행(`--rerun-tasks`) |
| 건수 | `:notification` 440(기존 408 + 새 32), `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| RED 확인 | (1) OBS-08 계약 2건은 구현 전 코드에서 통과(계약 고정), 지표 2건은 기대대로 실패. (2) 새 클래스 3개가 없어 테스트 컴파일 실패. (3) 구현 뒤 뮤테이션 4개(`RetryListener` 제거, DLT 지표 listener 제거, 저장 결과 집계 제거, 수신 시각을 일반 Map 헤더로 넘김)로 통합 12건 중 7건이 기대한 이유로 실패. 마지막 뮤테이션은 계약 시험이 FE 프레임에 `notificationReceivedAt` 헤더가 새는 것을 잡았다. 뮤테이션은 되돌리고 표식이 남지 않았음을 grep으로 확인 |
| 확인한 사실 | 역직렬화 실패도 `RetryListener.failedDelivery`로 온다(OBS-03, 대체 경로 불필요). DLT 발행이 실패하면 커밋 offset이 넘어가지 않고 DLT를 만들면 넘어간다(OBS-02) |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 복구기 대기 포기 뒤 브로커 늦은 성공(ND-159 한계) 재현 안 함. 운영 Prometheus 수집(배포 뒤) |
| 관련 변경 | #944 |

#### NR-086 · 2026-11-14 · 통과 (N11 #937 S6 재리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-086 |
| 실행 날짜 | 2026-11-14 13:33~13:36 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-937` |
| 커밋 | 미커밋 작업 트리(기준 `011647be`) |
| 작업 트리 변경 | NR-085 목록 + `ScheduleDispatcherTest` import 묶음, `rules.yaml` 새 series 갈래 주석 |
| 대상 기능 | N11 |
| 관련 NT ID | NT-SCH-01~24 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`, promtool `check rules`(NR-085와 같은 방법) |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0) |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure` 새로 실행. `:auxiliary-app:test`는 UP-TO-DATE(입력이 NR-085 뒤로 바뀌지 않음, 새 실행 아님). promtool `SUCCESS: 1 rules found` |
| 건수 | `:notification` 408(새 실행), `:auxiliary-app` 13(NR-085 결과). 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. Grafana 적용·실제 통보는 운영 적용 전 |
| 관련 변경 | #937 |

#### NR-085 · 2026-11-14 · 통과 (N11 #937 S6 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-085 |
| 실행 날짜 | 2026-11-14 13:26~13:30 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-937` |
| 커밋 | 미커밋 작업 트리(기준 `011647be`) |
| 작업 트리 변경 | NR-084 목록 + `app/ScheduleDispatcher.java`(record_failed 표식 ERROR, `Locale` import), `in/NotificationScheduleBatch.java`(잠금 시도 실패 ERROR), 시험 `ScheduleDispatcherTest`(같은 알림 sent, record_failed ERROR 확인)·`ScheduleDispatcherMetricsTest`(고른 뒤 끝난 예약 미집계)·새 `in/NotificationScheduleBatchTest`(로그 레벨 2건), 문서 |
| 대상 기능 | N11 지표·로그 레벨(ND-150 S6 리뷰 반영) |
| 관련 NT ID | NT-SCH-01~24 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`. NT-SCH-24: `docker run --rm --entrypoint promtool prom/prometheus:v2.53.0 check rules`(rules.yaml에서 새 규칙의 식을 뽑아 Prometheus 규칙 파일로 감쌈), YAML 파싱, `kubectl kustomize k8s/platform/overlays/{local,prod}` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0) |
| 결과 | 통과(종료 코드 0, 세 태스크 새로 실행). promtool `SUCCESS: 1 rules found`. 괄호를 뺀 식은 `parse error`로 실패해 검사가 문법 오류를 잡는 것을 확인 |
| 건수 | `:notification` 408, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음. 반영 중 red: 로그 레벨 시험 2건이 WARN이라 실패(의도한 red), "이미 끝난 예약 미집계" 통합 시험이 뮤테이션(NOT_SCHEDULED→sent)을 잡지 못해 단위 시험으로 바꾼 뒤 뮤테이션 red 확인·복원 |
| 수정 내용 | 위 작업 트리 변경 |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. Grafana 적용·실제 통보는 운영 적용 전이라 미실행 |
| 관련 변경 | #937 |

#### NR-084 · 2026-11-14 · 통과 (N11 #937 예약 알림 지표 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-084 |
| 실행 날짜 | 2026-11-14 13:06~13:16 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-937` |
| 커밋 | 미커밋 작업 트리(기준 `011647be`) |
| 작업 트리 변경 | `app/ScheduleDispatcher.java`(지표 두 개, 회차 결과, `recordFailure` 결과 반환), 시험 `app/ScheduleDispatcherTest.java`(지표 3건 추가)·`app/ScheduleDispatcherMetricsTest.java`(새 단위 5건), `k8s/platform/base/alerting/rules.yaml`, `docs/architecture/infrastructure/observability.md`, 알림 문서(decisions·test-spec·module·PROGRESS·이 문서) |
| 대상 기능 | N11 예약 알림 지표(ND-150)와 alert 규칙(ND-151) |
| 관련 NT ID | NT-SCH-01~23 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0) |
| 결과 | 통과(종료 코드 0). 세 태스크 모두 새로 실행(결과 XML이 모두 이번 실행) |
| 건수 | `:notification` 404, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. NT-SCH-24는 정적 확인만(YAML 파싱, `kubectl kustomize k8s/platform/overlays/{local,prod}`). PromQL 문법·Grafana 적용·실제 통보는 미실행(promtool 없음, 운영 적용 전) |
| 관련 변경 | #937 |

#### NR-083 · 2026-11-14 · 실패 (N11 #937 의도한 red)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-083 |
| 실행 날짜 | 2026-11-14 12:5x~13:0x (KST, 분 단위 미기록) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-937` |
| 커밋 | 미커밋 작업 트리(기준 `011647be`). 지표 구현 전 |
| 작업 트리 변경 | `app/ScheduleDispatcherTest.java`에 지표 시험 3건만 더함 |
| 대상 기능 | N11 예약 결과 지표(ND-150) |
| 관련 NT ID | NT-SCH-22 |
| 명령 | `./gradlew :notification:test --tests "*ScheduleDispatcherTest.보낸_예약과*" --tests "*ScheduleDispatcherTest.계속_실패하는*" --tests "*ScheduleDispatcherTest.실패_횟수_기록마저*"` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 실패(의도한 red). 3건 모두 `AssertionFailedError`(카운터 증가 기대 1·2·1, 실제 0). 이어서 `ScheduleDispatcherMetricsTest`는 `:notification:compileTestJava`에서 생성자 인자(`ObjectProvider<MeterRegistry>`) 없음으로 컴파일 실패(의도한 red) |
| 건수 | 3건 실행, 실패 3 |
| 실패 원인 분류 | 기능 없음(의도한 red) |
| 수정 내용 | NR-084 |
| 재실행 결과 | NR-084 통과 |
| 미검증 항목 | — |
| 관련 변경 | #937 |

#### NR-082 · 2026-11-13 · 통과 (N10 #928 PR #929 Bot 리뷰 반영 뒤 S6 재리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-082 |
| 실행 날짜 | 2026-11-13 11:17~11:21 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-928` |
| 커밋 | 미커밋 작업 트리(기준 `66f0af45`) |
| 작업 트리 변경 | NR-081 목록 + `notification/PROGRESS.md`, `docs/notification/regression-log.md`, `domain/ScheduleTimes.java`(대체 기한 인자 이름), Flyway `V202611131000__create_notification_schedule.sql`(부분 인덱스 `ix_notification_schedule_expiring`, 아직 병합 전 파일). S6 재리뷰 반영: `JdbcScheduleStore.skipExpired` 바깥 `status='SCHEDULED'` 조건과 항목별 트랜잭션 규칙의 예외 근거, 시험 주석(공유 DB 시각 창, 시계 호출 순서), `module.md` |
| 대상 기능 | N10 전체 |
| 관련 NT ID | NT-SCH-01~21, NT-GEN-12, NT-USR-03 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0) |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`·`:auxiliary-app:check` 새로 실행 |
| 건수 | `:notification` 396, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 실제 EC2 중지·기동(NT-SCH-10)은 같은 경로의 통합 시험만 |
| 관련 변경 | #928, PR #929 |

#### NR-081 · 2026-11-13 · 통과 (N10 예약 알림 #928 PR #929 Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-081 |
| 실행 날짜 | 2026-11-13 11:09~11:13 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-928` |
| 커밋 | 미커밋 작업 트리(기준 `66f0af45`) |
| 작업 트리 변경 | 수정: `notification/AGENTS.md`, `app/ScheduleDispatcher.java`, `app/ScheduleInputs.java`, `app/ScheduleStore.java`, `in/NotificationScheduleBatch.java`, `in/event/NotificationLectureEventListener.java`, `out/JdbcScheduleStore.java`(이상 `apps/auxiliary-app/boundedContext/notification/src/main/java/com/hapbang/auxiliary/notification/` 아래), 시험 `app/ScheduleDispatcherTest.java`·`app/ScheduleInputsTest.java`·`in/event/NotificationScheduleInputTest.java`, 문서 `docs/notification/{decisions,event-contracts,module,notification-catalog,test-spec}.md`. 새 파일: 시험 `app/ScheduleDispatcherSelectionTest.java`. 내용: 이의 제기 접수가 구매 취소 마감 임박 취소(ND-149), 기한 지난 예약 먼저 건너뜀·기한 남은 예약만 선택, 잠근 뒤 시각으로 판정·발생 시각(`runOnce(Clock)`), `runOnce` 선택 시험, NT-SCH-17 선행 조건 |
| 대상 기능 | N10 전체 |
| 관련 NT ID | NT-SCH-01~21, NT-GEN-12, NT-USR-03 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0) |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`·`:auxiliary-app:check` 새로 실행 |
| 건수 | `:notification` 396, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 실제 EC2 중지·기동(NT-SCH-10)은 같은 경로의 통합 시험만 |
| 관련 변경 | #928, PR #929 |
| 정정(NR-078~NR-080, 1.1-7) | 세 기록은 미커밋 작업 트리에서 실행했는데 파일 목록을 적지 않았다. 세 실행의 코드·시험은 커밋 `55990379`의 24개 파일과 같다(NR-078은 그중 ND-145·ND-146 반영 전, NR-079는 S6 리뷰 반영 전 상태). 문서는 `66f0af45`의 11개 파일이 실행 중에 함께 바뀌었다. 지난 기록은 고치지 않는다(1.1-3) |

#### NR-080 · 2026-11-13 · 통과 (N10 예약 알림 #928 S6 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-080 |
| 실행 날짜 | 2026-11-13 10:33~10:37 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-928` |
| 커밋 | 미커밋 작업 트리(기준 `c95d732d`) |
| 작업 트리 변경 | S6 리뷰 반영: 실패 횟수(`failure_count`, `REQUIRES_NEW`로 분리 기록, 3번 연속이면 건너뜀 + ERROR, ND-147), 예약 알림 발생 시각 = 보낸 시각(ND-148), 배치 실패 WARN, `NotificationJobTest`(락 번호), KST 상수 중복 제거, 배치가 먼저 잠근 경우의 동시성 시험 |
| 대상 기능 | N10 전체 |
| 관련 NT ID | NT-SCH-01~19, NT-GEN-12, NT-USR-03 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0) |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`·`:auxiliary-app:check` 새로 실행 |
| 건수 | `:notification` 390, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 실제 EC2 중지·기동(NT-SCH-10)은 같은 경로의 통합 시험만 |
| 관련 변경 | #928 |

#### NR-079 · 2026-11-13 · 통과 (N10 예약 알림 #928)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-079 |
| 실행 날짜 | 2026-11-13 10:04~10:14 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-928` |
| 커밋 | 미커밋 작업 트리(기준 `c95d732d`) |
| 작업 트리 변경 | NR-078의 회귀 수정: 예약 생성에 알림 시작 시각(ND-145), 예약 받는 사람 복제본 없음은 DLT·모두 수신 제외면 DLT 없음(ND-146). `ScheduleCommand.Create`에 사건 시각. `NotificationAppearanceProgressAlertTest`의 "받을 수 없는 호스트 + 게스트 복제본 없음" 기대를 DLT로 바꾸고, "호스트·게스트 모두 받을 수 없음" 시험을 더했다 |
| 대상 기능 | N10 예약 배치와 예약 알림 5종 전체 |
| 관련 NT ID | NT-SCH-01~18, NT-GEN-12, NT-USR-03 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다(Docker Desktop 29.8.0, Testcontainers) |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`·`:auxiliary-app:check` 모두 새로 실행(UP-TO-DATE 아님) |
| 건수 | `:notification` 386, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 실제 EC2 중지·기동 뒤 밀린 예약 발송(NT-SCH-10)은 같은 처리 경로의 통합 시험만 했다 |
| 관련 변경 | #928 |

#### NR-078 · 2026-11-13 · 실패 (N10 예약 알림 #928 첫 3명령)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-078 |
| 실행 날짜 | 2026-11-13 09:58~10:01 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-928` |
| 커밋 | 미커밋 작업 트리(기준 `c95d732d`) |
| 작업 트리 변경 | N10 구현(Flyway `V202611131000`, 예약 시각·종류·명령·저장소·서비스·배치·문구, 출연·강의 리스너 연결)과 시험 |
| 대상 기능 | N10 |
| 관련 NT ID | NT-SCH-01~16 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 실패. `:notification:test` 383 중 2 실패로 멈춰 `verifyModuleStructure`·`:auxiliary-app:check`는 실행되지 않았다 |
| 건수 | `:notification` 383, 실패 2 |
| 실패 원인 분류 | 회귀(구현 결함 아님, 정책 공백). `NotificationAppearanceProgressAlertTest` 2건: 요청 수락에서 게스트 복제본이 없고 호스트가 받을 수 없거나 사건이 알림 시작 시각 전이면 DLT 없이 넘겨야 하는데, 게스트가 방송 임박 예약의 받는 사람이 되어 예약 생성이 `RECEIVER_NOT_REPLICATED`로 실패했다. 탐침(복제본 없으면 넘김)으로 원인을 확인하고 되돌렸다 |
| 수정 내용 | 알림 담당 결정 ND-145·ND-146대로 고쳤다(NR-079) |
| 재실행 결과 | NR-079 통과 |
| 미검증 항목 | — |
| 관련 변경 | #928 |

#### NR-077 · 2026-11-12 · 통과 (N9 채팅 #921 PR 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-077 |
| 실행 날짜 | 2026-11-12 19:45~19:48 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-921` (worktree `beadv8_8_LMS_BE-n9-chat`) |
| 커밋 | 미커밋 작업 트리(기준 `164f9574`) |
| 작업 트리 변경 | PR 리뷰 반영: `NotificationChatAlertTest`가 DLT 조회 헬퍼(`MemberEventKafka`, 생성할 때 producer를 만든다)를 조회 뒤 닫는다. 알림 모듈 `AGENTS.md` 현재 상태 줄과 3.2장(채팅 소비자도 Backfill 뒤 시작) |
| 대상 기능 | NR-076과 같다 |
| 관련 NT ID | NT-GEN-24, NT-USR-06 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). `:notification:test`·`:auxiliary-app:check`는 새로 실행, `:auxiliary-app:test`·`:auxiliary-global`·`:chatbot`·`:search`는 UP-TO-DATE(NR-076 결과) |
| 건수 | `:notification` 326(`NotificationChatAlertTest` 8 포함). 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 같은 헬퍼를 닫지 않는 기존 시험 클래스(N5~N7)는 이번 범위 밖이라 그대로 |
| 관련 변경 | #921 |

#### NR-076 · 2026-11-12 · 통과 (N9 채팅 #921 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-076 |
| 실행 날짜 | 2026-11-12 19:24~19:33 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-921` (worktree `beadv8_8_LMS_BE-n9-chat`) |
| 커밋 | `03b29e9a`(origin/dev `384a871b` 병합 커밋)와 같은 내용(실행 때는 병합 커밋 전 스테이징한 작업 트리) |
| 작업 트리 변경 | origin/dev(회원 #915 PR #922, 검색 #923, 고객센터 #918 PR #926) 병합. 충돌 17파일은 dev 내용을 두고 채팅 변경을 다시 더해 풀었다(Kafka 설정·Starter·시험 설정은 고객센터 소비자 옆에 채팅 소비자). 채팅 번호를 ND-129~ND-132, NR-074·NR-075로 고쳤다 |
| 대상 기능 | 병합 결과 회귀(N7 회원 3종 + 고객센터 2종 + N9 채팅 3종, 소비자 시작 순서) |
| 관련 NT ID | NT-GEN-08, NT-GEN-10, NT-GEN-15, NT-GEN-22, NT-GEN-23, NT-GEN-24, NT-KAF-06, NT-KAF-10, NT-USR-06 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0, 모든 시험 태스크 새로 실행) |
| 건수 | `:notification` 326, `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 329. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #921 |

#### NR-075 · 2026-11-12 · 통과 (N9 채팅 #921 자체 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-075 (처음 NR-071로 적었다. NR-074와 같은 이유로 고쳤다) |
| 실행 날짜 | 2026-11-12 17:02~17:06 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-921` (worktree `beadv8_8_LMS_BE-n9-chat`) |
| 커밋 | 미커밋 작업 트리(기준 origin/dev `84b349c3`) |
| 작업 트리 변경 | NR-074 + S6 리뷰 반영: `GroupRoomAlerts` 변수 이름(`kickedUserId`), `NotificationChatEventListener` 주석(받는 사람마다 커밋), API 문서 2.2 `CHAT_ROOM` 행 |
| 대상 기능 | NR-074와 같다 |
| 관련 NT ID | NR-074와 같다 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). `:notification:test`·`:auxiliary-app:test`는 새로 실행, `:auxiliary-global`·`:chatbot`·`:search`는 UP-TO-DATE(NR-074 결과) |
| 건수 | `:notification` 303, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. origin/dev 병합 전(병합 뒤 검증은 NR-076) |
| 관련 변경 | #921 |

#### NR-074 · 2026-11-12 · 통과 (N9 채팅 단체방 3종 #921 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-074 (처음 NR-070으로 적었고, 회원 #915·고객센터 #918이 먼저 병합돼 dev의 다음 번호로 고쳤다) |
| 실행 날짜 | 2026-11-12 16:39~16:50 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-921` (worktree `beadv8_8_LMS_BE-n9-chat`) |
| 커밋 | 미커밋 작업 트리(기준 origin/dev `84b349c3`) |
| 작업 트리 변경 | N9 구현: `GroupRoomAlerts`·`GroupRoomAlertTemplates`·`NotificationChatEventListener` 새로 추가, `NotificationTemplates`·Kafka 설정 4개·`NotificationAlertListenerStarter`·시험 설정 수정, 시험 `GroupRoomAlertsTest`·`NotificationChatAlertTest` 추가, `NotificationAlertListenerStarterTest`에 채팅 컨테이너 추가 |
| 대상 기능 | 채팅 단체방 초대·강퇴·폐쇄 알림(새 그룹 `auxiliary-app.notification-chat-alert`), Backfill 뒤 시작 |
| 관련 NT ID | NT-GEN-08, NT-GEN-10, NT-GEN-15, NT-GEN-24, NT-KAF-06, NT-USR-06 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0, 모든 시험 태스크 새로 실행) |
| 건수 | `:notification` 303(dev 287 + 채팅 16), `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 290. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 없음. 앞선 RED 확인(구현 전 컴파일 오류, 리스너 처리기가 빈 상태의 Kafka 시험 실패)은 의도한 실패라 기록하지 않는다. RED2 첫 실행은 다른 터미널의 Gradle 데몬 중지로 중간에 끊겼다(F-ENV, 결과 파일 없음) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. origin/dev 병합 전(병합 뒤 검증은 NR-076) |
| 관련 변경 | #921 |
#### NR-073 · 2026-11-12 · 통과 (N7 고객센터 #918 PR 리뷰 반영: 테스트 설정의 고객센터 소비자 끄기)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-073 |
| 실행 날짜 | 2026-11-12 17:35~17:39 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-918` (worktree `beadv8_8_LMS_BE-n7-customersupport`) |
| 커밋 | `9be8102b` + 커밋 전 작업 트리 |
| 작업 트리 변경 | 시험 설정: `hapbang-module-test.yaml`·`AuxiliaryApplicationTest`에 `hapbang.notification.customersupport-events.enabled=false`(고객센터 시험 클래스·Starter 시험만 켠다). 설명: Starter 게이지·`NotificationAlertService`·`NotificationKafkaConstants` 주석, Starter 시험 주석. 문서: 모듈 AGENTS 4장·6장·55행, decisions ND-128, PROGRESS |
| 대상 기능 | Kafka가 없는 시험 컨텍스트에서 고객센터 소비자 빈·컨테이너를 만들지 않음 |
| 관련 NT ID | NT-KAF-10, NT-GEN-23(회귀) |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-070과 같다 |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`는 새로 실행, `:auxiliary-global`·`:chatbot`·`:search` 시험은 입력이 바뀌지 않아 `UP-TO-DATE`(새 실행 아님, 마지막 실행은 NR-072) |
| 건수 | 새로 실행: `:notification` 310, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 설정 누락을 직접 잡는 시험은 따로 없다(기존 시험이 모두 통과하는지로 확인) |
| 관련 변경 | #918 PR 리뷰 |

**메모**: 이번 실행은 3분 36초로 NR-072(15분 45초)보다 짧다. NR-072는 병합 뒤라 모든 모듈 시험을 새로 실행했고 이번에는 알림·앱 시험만 새로 실행했으므로, 시간 차이를 설정 수정의 효과로 보지 않는다.

#### NR-072 · 2026-11-12 · 통과 (N7 고객센터 #918 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-072 |
| 실행 날짜 | 2026-11-12 17:10~17:26 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-918` (worktree `beadv8_8_LMS_BE-n7-customersupport`) |
| 커밋 | `6f070e43`(origin/dev `71e413c6` 병합 커밋)과 같은 내용(실행 때는 병합 커밋 전 작업 트리) |
| 작업 트리 변경 | origin/dev(강의 #910 PR #920, 회원 #915 PR #922, 검색 #919·#923) 병합. 충돌 9파일(`NotificationTemplates`, PROGRESS, 알림 문서 7개)은 dev 내용을 두고 고객센터 변경을 더해 풀었다. 결정·명세 번호는 dev 다음 번호라 그대로, 실행 기록만 NR-065·NR-066 → NR-070·NR-071로 고쳤다 |
| 대상 기능 | 병합 결과 회귀(N7 강의 7종·회원 3종 + 고객센터 2종, 템플릿 등록 합침) |
| 관련 NT ID | NT-GEN-10, NT-GEN-15, NT-GEN-21, NT-GEN-22, NT-GEN-23, NT-KAF-09, NT-KAF-10 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-070과 같다 |
| 결과 | 통과(종료 코드 0). `:notification:test`, `verifyModuleStructure`, `:auxiliary-app:test`, `:auxiliary-global:test`, `:chatbot:test`, `:search:test` 모두 새로 실행(`UP-TO-DATE` 없음) |
| 건수 | `:notification` 310, `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 329. 실패 0·오류 0·건너뜀 0(XML 기준) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(사용자 결정, CI에서 확인) |
| 관련 변경 | #918 |

#### NR-071 · 2026-11-12 · 통과 (N7 고객센터 #918 S6 리뷰 반영: 빈 제목 DLT)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-071 (처음 NR-066으로 적었다가 dev 다음 번호로 고쳤다) |
| 실행 날짜 | 2026-11-12 16:40~16:51 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-918` (worktree `beadv8_8_LMS_BE-n7-customersupport`) |
| 커밋 | `b38a267f` + 커밋 전 작업 트리 |
| 작업 트리 변경 | NR-070 뒤: `CustomerSupportAlerts`(필수 값 이름표를 필드 이름으로, `replyId` 이중 검사 제거, 제목은 `isBlank`면 거절하고 정상 제목은 그대로(ND-127)), `NotificationTemplates` 줄 나눔, `NotificationKafkaConfig` 주석. 시험: 단위 2개(빈·공백 제목 거절, 앞뒤 공백 제목 그대로), 통합 DLT 시험에 공백 제목 추가. 문서(ND-127, kafka.md 30행 문구) |
| 대상 기능 | 고객센터 2종 변환의 필수 값 검사 |
| 관련 NT ID | NT-GEN-23, NT-KAF-10 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-070과 같다 |
| 결과 | 통과(종료 코드 0). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`는 새로 실행, `:auxiliary-global`·`:chatbot`·`:search` 시험은 입력이 바뀌지 않아 `UP-TO-DATE`(새 실행 아님, 마지막 실행은 NR-070) |
| 건수 | 새로 실행: `:notification` 278(새 시험 2), `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(⑤에서 묻는다) |
| 관련 변경 | #918 S6 리뷰 |

**메모**: 시험을 먼저 바꾸고 두 시험 클래스만 돌려 단위 `문의_제목이_빈_문자열이나_공백뿐이면_거절한다`·통합 `필수_값이_빠진_고객센터_이벤트는_DLT로_보낸다`가 실패하는 것(RED)을 본 뒤 구현했다. 앞뒤 공백 제목 시험은 지금 동작을 고정하는 시험이라 처음부터 통과했다. 리뷰 반영 전 시작한 검증 실행은 코드를 고치기 전에 멈췄다(결과 없음, 기록하지 않음).

#### NR-070 · 2026-11-12 · 통과 (N7 고객센터 #918 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-070 (처음 NR-065로 적었다가 강의 #910·회원 #915 병합 뒤 dev 다음 번호로 고쳤다, ND-105) |
| 실행 날짜 | 2026-11-12 16:06~16:22 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-918` (worktree `beadv8_8_LMS_BE-n7-customersupport`) |
| 커밋 | `b38a267f` + 커밋 전 작업 트리 |
| 작업 트리 변경 | 운영: 새 `CustomerSupportAlerts`·`CustomerSupportAlertTemplates`·`NotificationCustomerSupportEventListener`, 수정 `NotificationTemplates`·`NotificationKafka{Constants,Config,ErrorHandlerConfig,TopicConfig}`·`NotificationAlertListenerStarter`. 시험: 새 `CustomerSupportAlertsTest`(4)·`NotificationCustomerSupportAlertTest`(4), `NotificationAlertListenerStarterTest`에 고객센터 컨테이너 단언. 알림 문서(ND-122~ND-126 반영) |
| 대상 기능 | 고객센터 2종 알림(문의 답변 도착·이의 제기 접수 불가), 새 소비자 그룹·DLT, Backfill 뒤 시작 |
| 관련 NT ID | NT-GEN-10, NT-GEN-15, NT-GEN-23, NT-KAF-10 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1(wrapper), Docker 29.8.0 실행 중, Testcontainers(각 시험 설정대로 PostgreSQL·Kafka 등) |
| 결과 | 통과(종료 코드 0). `:notification:test`, `verifyModuleStructure`, `:auxiliary-app:test`, `:auxiliary-global:test`, `:chatbot:test`, `:search:test`가 모두 새로 실행됐다(`UP-TO-DATE` 없음) |
| 건수 | `:notification` 276, `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 290. 실패 0·오류 0·건너뜀 0(XML 기준) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(⑤에서 묻는다). 운영 Kafka에서 #875 이후 지난 M7 이벤트가 늦은 알림이 되는 것(ND-122)은 배포 뒤에만 확인할 수 있다 |
| 관련 변경 | #918 |

**메모**: 시험을 먼저 쓰고 컴파일 실패(RED: `CUSTOMERSUPPORT_EVENT_SETTINGS` 등 기호 없음)를 확인했다. 운영 코드 없이 동작으로 실패하는 단계는 건너뛰고 운영 코드를 쓴 뒤, 대신 변이로 시험을 확인했다(새 시험 3클래스만 실행): (1) 답변 도착 처리기가 저장하지 않음 → 통합 답변 시험 1개 실패 (2) Backfill 뒤 시작 목록에서 고객센터 리스너 제거 → Starter 시험 실패 (3) 답변 원본 대상 ID를 문의 ID로 → 단위 3개·통합 1개 실패. 변이는 되돌렸고 원본과 같은지 `cmp`로 확인했다.
#### NR-069 · 2026-11-12 · 통과 (N7 회원 #915 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-069 |
| 실행 날짜 | 2026-11-12 16:04~16:15 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-915` |
| 커밋 | `8ee9f33c`(`4b5bccca` + origin/dev `84b349c3` 병합. 실행은 병합 커밋 직전의 같은 작업 트리) |
| 작업 트리 변경 | origin/dev(출연 #908 PR #916, 검색 #914, 강의 #910 PR #920) 병합. 충돌 8파일(`NotificationTemplates`와 알림 문서 7개)은 dev 내용을 두고 회원 변경을 더해 풀었다. 겹친 실행 기록 번호만 NR-062~NR-064 → NR-066~NR-068로 고쳤다 |
| 대상 기능 | 병합 결과 회귀(N7 출연·강의·회원 알림이 함께 등록된 `NotificationTemplates`, 검색 #914) |
| 관련 NT ID | NT-GEN-20·21·22, NT-GEN-10·15, NT-KAF-09 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). 새로 실행: `:notification:test`, `verifyModuleStructure`, `:auxiliary-app:test`, `:auxiliary-global:test`, `:chatbot:test`, `:search:test` |
| 건수 | `:notification` 300, `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 290. 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음(R-01의 JobLock 시험도 이번에는 통과) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(⑤ Q2 a) |
| 관련 변경 | #915 |

#### NR-068 · 2026-11-12 · 통과 (N7 회원 3종 #915 자체 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-068 |
| 실행 날짜 | 2026-11-12 15:24~15:28 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-915` |
| 커밋 | origin/dev `ac27dfeb`(커밋 전 작업 트리) |
| 작업 트리 변경 | NR-066에 더해 `MemberReviewAlerts` import 순서(같은 패키지 선례), `NotificationMemberReviewAlertTest`에 같은 회차 채널 반려 재전송을 더했다 |
| 대상 기능 | N7 회원 3종 알림 |
| 관련 NT ID | NR-066와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). 새로 실행: `:notification:test`, `verifyModuleStructure`, `:auxiliary-app:test`. `:auxiliary-global`·`:chatbot`·`:search` 시험은 `UP-TO-DATE`(결과는 NR-067) |
| 건수 | `:notification` 256, `:auxiliary-app` 13(새 실행). 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #915 |

#### NR-067 · 2026-11-12 · 통과 (N7 회원 3종 #915 재실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-067 |
| 실행 날짜 | 2026-11-12 15:11~15:17 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-915` |
| 커밋 | origin/dev `ac27dfeb`(커밋 전 작업 트리) |
| 작업 트리 변경 | NR-066와 같다(바뀐 것 없음) |
| 대상 기능 | NR-066에서 실패한 `:auxiliary-global:test` 재실행 |
| 관련 NT ID | NR-066와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다. 다른 터미널의 출연·강의 worktree 빌드와 같은 시간대 |
| 결과 | 통과(종료 코드 0). 새로 실행: `verifyModuleStructure`, `:auxiliary-global:test`, `:chatbot:test`, `:search:test`. `:notification:test`·`:auxiliary-app:test`는 `UP-TO-DATE`(결과는 NR-066) |
| 건수 | `:auxiliary-global` 110, `:chatbot` 245, `:search` 222(새 실행). `:notification` 256, `:auxiliary-app` 13(NR-066). 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #915 |

#### NR-066 · 2026-11-12 · 실패 (N7 회원 3종 #915)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-066 |
| 실행 날짜 | 2026-11-12 15:05~15:09 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-915` |
| 커밋 | origin/dev `ac27dfeb`(커밋 전 작업 트리) |
| 작업 트리 변경 | `MemberReviewAlerts`·`MemberReviewTemplates` 추가, `NotificationTemplates` 등록, `NotificationMemberEventListener` 처리기 3개. 시험 `MemberReviewAlertsTest`(7)·`NotificationMemberReviewAlertTest`(6) |
| 대상 기능 | N7 회원 3종 알림(채널 인증 승인·반려, 승급 반려) |
| 관련 NT ID | NT-GEN-22, NT-GEN-10·15(회원 3종), NT-GEN-14 (2) |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다. 다른 터미널의 출연·강의 worktree 빌드와 같은 시간대 |
| 결과 | 실패(종료 코드 1). `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`는 새로 실행해 통과. `:auxiliary-global:test` 110건 중 1건 실패로 `:chatbot`·`:search` 시험은 돌지 않음 |
| 건수 | `:notification` 256, `:auxiliary-app` 13 통과(새 실행). `:auxiliary-global` 110 중 실패 1 |
| 실패 원인 분류 | F-TEST(추정). `JobLockPostgresIntegrationTest.connectionLost`의 92행 `podB.tryAcquire(FIRST).orElseThrow()`가 `NoSuchElementException`. 시험이 `pg_terminate_backend(pid)`를 종료를 기다리지 않고 부른 뒤 곧바로 같은 advisory lock을 잡으므로, 백엔드 종료가 늦으면 락이 아직 남아 있을 수 있다. 이번 변경은 `:auxiliary-global`을 바꾸지 않았다 |
| 수정 내용 | 없음. 시험은 auxiliary global(#798) 코드라 이 Issue 범위 밖 |
| 재실행 결과 | 그 시험 클래스만 3회 단독 실행(`:auxiliary-global:test --tests "*JobLockPostgresIntegrationTest" --rerun-tasks`) 모두 5/5 통과. 전체 재실행은 NR-067 통과 |
| 미검증 항목 | 추정 원인을 재현으로 확인하지 않았다(4장 R-01). 전체 `./gradlew check` 미실행 |
| 관련 변경 | #915 |

#### NR-065 · 2026-11-12 · 통과 (N7 강의 #910 origin/dev 병합 뒤, 1차 실패 → 시험 격리 수정 뒤 재실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-065 |
| 실행 날짜 | 1차 2026-11-12 15:27~15:31, 2차 15:33~15:44 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-910` (worktree `beadv8_8_LMS_BE-n7-lecture`) |
| 커밋 | `6d738b9f`(origin/dev `b38a267f` 병합 커밋)와 같은 내용(실행 때는 병합 커밋 전 작업 트리) |
| 작업 트리 변경 | origin/dev(지갑 #907 PR #912, 강의 #913, 검색 #914, 출연 #908 PR #916) 병합. 충돌 9파일(`NotificationTemplates`와 알림 문서 8개)은 dev 내용을 두고 강의 변경을 더해 풀었다. 지갑·출연이 먼저 병합돼 강의 결정을 ND-114~ND-117, 실행 기록을 NR-063·NR-064로 고쳤다 |
| 대상 기능 | 병합 결과 회귀(N7 지갑 4종 + 출연 10종 + 강의 7종) |
| 관련 NT ID | NT-GEN-10, NT-GEN-15, NT-GEN-19, NT-GEN-20, NT-GEN-21, NT-KAF-09 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 1차 실패(종료 코드 1, `:notification:test` 287개 중 6개 실패). 2차 통과(종료 코드 0, 모든 시험 태스크 새로 실행) |
| 건수 | 2차: `:notification` 287(dev 268 + 강의 19), `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 290. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | F-TEST(시험 격리). `NotificationLectureProgressAlertTest`와 `NotificationAppearanceProgressAlertTest`가 둘 다 회원 ID를 800_000부터 만들어, 같은 DB에서 `pk_notification_user` 기본 키가 겹쳤다(`Key (id)=(800001) already exists`). 두 클래스만 함께 돌리면 순서에 따라 어느 한쪽 6개가 실패했다 |
| 수정 내용 | 강의 Kafka 시험의 회원 ID 대역을 2_200_000으로 옮겼다(운영 코드 변경 없음). 두 클래스만 함께 돌려 통과를 확인한 뒤 2차 실행 |
| 재실행 결과 | 2차 통과 |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #910 |

#### NR-064 · 2026-11-12 · 통과 (N7 강의 #910 자체 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-064 |
| 실행 날짜 | 2026-11-12 14:18~14:22 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-910` (worktree) |
| 커밋 | origin/dev `26b44b5b` + 커밋 전 작업 트리 |
| 작업 트리 변경 | NR-063 + S6 리뷰 반영: `NotificationTemplates` 긴 줄 나눔, `module.md` 변경 이력 문장 |
| 대상 기능 | NR-063과 같다 |
| 관련 NT ID | NR-063과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0) |
| 건수 | 새로 실행: `:notification` 250, `:auxiliary-app` 13, `verifyModuleStructure`. `:auxiliary-global`·`:chatbot`·`:search`는 UP-TO-DATE(새 실행 아님, NR-063 결과). 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #910 |

#### NR-063 · 2026-11-12 · 통과 (N7 강의 #910, M4 7종 소비)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-063 |
| 실행 날짜 | 2026-11-12 14:02~14:13 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-910` (worktree) |
| 커밋 | origin/dev `26b44b5b` + 커밋 전 작업 트리 |
| 작업 트리 변경 | `LectureProgressAlerts`·`LectureProgressTemplates` 추가, `NotificationTemplates` 등록, `NotificationLectureEventListener`에 M4 7종(이전 형식 판별 없음). 앞선 TDD 실행: RED 1 단위 시험 컴파일 오류(클래스 없음), RED 2 단위 11 통과·Kafka 6 실패(리스너가 7종을 건너뛰어 0행·DLT 없음), GREEN 새 시험 19와 이의 제기 회귀(`NotificationCancelRequestAndObjectionAlertTest` 14, `LectureObjectionAlertsTest` 6) 통과 |
| 대상 기능 | N7 강의 알림 7종, ND-114~ND-117 |
| 관련 NT ID | NT-GEN-21, NT-KAF-09 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). 1차는 모든 시험 태스크를 새로 실행했으나 출력 파이프가 도구 시간 제한으로 끊겨, 같은 명령을 다시 돌려 종료 코드 0을 확인했다(2차는 모두 UP-TO-DATE, 새 실행 아님) |
| 건수 | 1차 새로 실행: `:notification` 250, `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 222. 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #910 |

#### NR-062 · 2026-11-12 · 통과 (N7 출연 #908 Bot 리뷰 반영: 상대 닉네임 읽는 순서 시험)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-062 |
| 실행 날짜 | 2026-11-12 15:02~15:05 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-908` (worktree `beadv8_8_LMS_BE-n7-appearance`) |
| 커밋 | `7e28fb12` + 커밋 전 작업 트리 |
| 작업 트리 변경 | 시험만 바꿨다(운영 코드 변경 없음). `NotificationAppearanceProgressAlertTest`·`NotificationCancelRequestAndObjectionAlertTest`에 각 3개: 받을 수 없는 호스트·시작 시각 전이면 상대 복제본이 없어도 DLT 없이 건너뜀, 받을 수 있는데 상대 복제본이 없으면 재시도 뒤 DLT. test-spec 4장 연결 표 |
| 대상 기능 | `NotificationAlertService.createMentioning`(N7 출연)·`cancelRequested`(N6)의 순서: 시작 시각·받는 사람을 본 뒤 상대 닉네임을 읽는다 |
| 관련 NT ID | NT-GEN-17, NT-GEN-20 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-058과 같다 |
| 결과 | 통과(종료 코드 0). `:notification:test`는 새로 실행, `:auxiliary-app`·`:auxiliary-global`·`:chatbot`·`:search` 시험은 입력이 바뀌지 않아 `UP-TO-DATE`(새 실행 아님, 마지막 실행은 NR-061) |
| 건수 | 새로 실행: `:notification` 268(새 시험 6). 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(CI에서 확인) |
| 관련 변경 | #908 Bot 리뷰 |

**메모**: 실행 전에 변이로 시험을 확인했다. 두 메서드가 상대 닉네임을 먼저 읽도록 잠시 바꾸고 두 시험 클래스를 돌리면 28개 중 4개(건너뜀 경로, 출연 2·N6 2)가 실패했다. 상대 복제본 없음 DLT 시험 2개는 순서와 상관없이 DLT로 가서 통과한다. 변이는 되돌렸고 원본과 diff가 없음을 확인했다.

#### NR-061 · 2026-11-12 · 통과 (N7 출연 #908 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-061 |
| 실행 날짜 | 2026-11-12 14:35~14:44 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-908` (worktree `beadv8_8_LMS_BE-n7-appearance`) |
| 커밋 | `d8d7d47e`(origin/dev `ac27dfeb` 병합 커밋)와 같은 내용(실행 때는 병합 커밋 전 작업 트리) |
| 작업 트리 변경 | origin/dev(지갑 #907 PR #912, 강의 #913) 병합. 충돌 8파일(`NotificationTemplates`와 알림 문서 7개)은 지갑 내용을 두고 출연 변경을 더해 풀었다. 지갑이 먼저 병합돼 출연 결정을 ND-110~ND-113, 실행 기록을 NR-058~NR-060으로 고쳤다 |
| 대상 기능 | 병합 결과 회귀(N7 지갑 4종 + N7 출연 10종), #913 shared `LectureSyncDto` 변경 |
| 관련 NT ID | NT-GEN-10, NT-GEN-15, NT-GEN-19, NT-GEN-20, NT-KAF-09 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-058과 같다. 실행 전 `./gradlew --status`로 바쁜 데몬이 없음을 확인 |
| 결과 | 통과(종료 코드 0, 모든 시험 태스크 새로 실행) |
| 건수 | `:notification` 262(출연 250 + 지갑 시험 12), `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 222. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(CI에서 확인) |
| 관련 변경 | #908 |

#### NR-060 · 2026-11-12 · 통과 (N7 출연 #908 자체 리뷰 반영 뒤 재검증)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-060 |
| 실행 날짜 | 2026-11-12 14:02~14:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-908` (worktree `beadv8_8_LMS_BE-n7-appearance`) |
| 커밋 | `26b44b5b`(origin/dev) + 커밋 전 작업 트리 |
| 작업 트리 변경 | NR-058 + 자체 리뷰 반영: `NotificationAppearanceEventListener`의 알리지 않는 경우 로그를 DEBUG로 바꾸고 메시지의 `eventType`을 뺐다. `NotificationAppearanceProgressAlertTest`에 조기 마감·호스트 확인의 DLT 없음과 불참 이동 대상 확인을 더했다. module.md N7 행을 줄였다 |
| 대상 기능 | N7 출연 10종 알림(ND-110~ND-113) |
| 관련 NT ID | NT-GEN-10, NT-GEN-15, NT-GEN-20, NT-KAF-09 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-058와 같다. 다른 빌드가 돌지 않는 상태에서 혼자 실행 |
| 결과 | 통과(종료 코드 0). `:notification:test`·`:auxiliary-app:test`는 새로 실행, `:auxiliary-global`·`:chatbot`·`:search` 시험은 입력이 바뀌지 않아 `UP-TO-DATE`(새 실행 아님, 마지막 실행은 NR-058) |
| 건수 | 새로 실행: `:notification` 250, `:auxiliary-app` 13. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | NR-059의 재실행이다 |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #908 |

#### NR-059 · 2026-11-12 · 실패 (N7 출연 #908 자체 리뷰 반영 뒤 재검증, 빌드 겹침)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-059 |
| 실행 날짜 | 2026-11-12 13:5x~14:01 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-908` (worktree `beadv8_8_LMS_BE-n7-appearance`) |
| 커밋 | `26b44b5b`(origin/dev) + 커밋 전 작업 트리(NR-060과 같다) |
| 작업 트리 변경 | NR-060과 같다 |
| 대상 기능 | N7 출연 10종 알림 |
| 관련 NT ID | NR-060과 같다 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-058와 같다. 바로 전에 같은 명령을 셸에서 중단했는데 Gradle 데몬이 그 빌드를 계속 돌리고 있었다(로그 첫 줄 "3 busy Daemons could not be reused") |
| 결과 | 실패(종료 코드 1). `:notification:test FAILED` — `NoSuchFileException: .../notification/build/test-results/test/binary/in-progress-results-generic.bin` |
| 건수 | 결과 XML이 새로 쓰이지 않았다(실패한 시험 보고 없음) |
| 실패 원인 분류 | F-ENV(같은 worktree에서 두 빌드가 같은 시험 결과 폴더를 함께 썼다) |
| 수정 내용 | 없음(코드 결함 아님) |
| 재실행 결과 | 겹친 빌드가 끝난 뒤 같은 명령을 혼자 다시 돌려 통과(NR-060) |
| 미검증 항목 | — |
| 관련 변경 | #908 |

**메모**: 로그의 `java.net.ConnectException` 스택은 통과한 NR-058 로그에도 있던 잡음이라 실패 원인이 아니다. 시험 실행을 셸에서 중단하면 데몬이 빌드를 이어 갈 수 있으니, 다시 돌리기 전에 `./gradlew --status`로 바쁜 데몬이 없는지 본다.

#### NR-058 · 2026-11-12 · 통과 (N7 출연 10종 #908 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-058 |
| 실행 날짜 | 2026-11-12 12:41~12:51 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-908` (worktree `beadv8_8_LMS_BE-n7-appearance`) |
| 커밋 | `26b44b5b`(origin/dev) + 커밋 전 작업 트리 |
| 작업 트리 변경 | 새 파일 `app/AppearanceProgressAlerts.java`, `app/AppearanceProgressTemplates.java`, 시험 `app/AppearanceProgressAlertsTest.java`, `in/event/NotificationAppearanceProgressAlertTest.java`. 수정 `app/NotificationAlertService.java`, `app/NotificationTemplates.java`, `in/event/NotificationAppearanceEventListener.java`. 문서 decisions·catalog·test-spec·module·event-contracts·api 2.2·PROGRESS |
| 대상 기능 | N7 출연 10종 알림(ND-110~ND-113) |
| 관련 NT ID | NT-GEN-10, NT-GEN-15, NT-GEN-20, NT-KAF-09 |
| 명령 | `./gradlew -p <worktree> :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다(Windows 10, JDK 25, Gradle 9.7.1, Docker, Testcontainers postgres·kafka·redis) |
| 결과 | 통과(종료 코드 0, 모든 시험 태스크 새로 실행) |
| 건수 | `:notification` 250(새 시험 19: `AppearanceProgressAlertsTest` 11, `NotificationAppearanceProgressAlertTest` 8), `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 222. 실패 0·오류 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 예약 입력(N10)은 범위 밖 |
| 관련 변경 | #908 |

**메모**: TDD red는 같은 날 단독 실행으로 확인했다(`AppearanceProgressAlertsTest`는 변환 클래스가 없어 컴파일 실패, `NotificationAppearanceProgressAlertTest`는 처리기가 없어 8개 모두 대기 시간 초과). NR 번호는 N7 지갑(#907)과 병합 순서에 따라 늦게 병합하는 쪽이 고친다(ND-110).

#### NR-057 · 2026-11-12 · 통과 (N7 지갑 #907 PR 리뷰 반영: 충전 실패 값의 결제 금액 제거)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-057 |
| 실행 날짜 | 2026-11-12 14:09~14:13 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-907` |
| 커밋 | `55d43c60`(커밋 전 작업 트리) |
| 작업 트리 변경 | `WalletAlerts.chargeFailed`가 값에 `paymentAmountKrw`를 넣지 않고 키 상수도 지웠다. `WalletAlertsTest` 4건에 값 키 단언(`containsOnlyKeys`)을 더했다(먼저 `paymentAmountKrw`가 있어 실패하는 것을 확인) |
| 대상 기능 | N7 지갑 4종 값 형식 |
| 관련 NT ID | NT-GEN-19, NT-GEN-13 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). 새로 실행: `:notification:test`, `verifyModuleStructure`, `:auxiliary-app:test`. `:auxiliary-global`·`:chatbot`·`:search` 시험은 `UP-TO-DATE`(결과는 NR-055·NR-056) |
| 건수 | `:notification` 243, `:auxiliary-app` 13(새 실행). 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #907 PR 리뷰 |

#### NR-056 · 2026-11-12 · 통과 (N7 지갑 #907 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-056 |
| 실행 날짜 | 2026-11-12 13:55~14:01 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-907` |
| 커밋 | `9c2f0a7d`(`aa54fe41` + origin/dev `26b44b5b` 병합, 충돌 없음) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | 병합 결과 회귀(#906 검색 사본·색인과 `AuxiliaryApplicationTest`) |
| 관련 NT ID | NR-055와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0). 새로 실행: `verifyModuleStructure`, `:auxiliary-app:test`, `:search:test`. `:notification:test`·`:auxiliary-global:test`·`:chatbot:test`는 `UP-TO-DATE`(병합이 입력을 바꾸지 않음, 결과는 NR-055) |
| 건수 | `:auxiliary-app` 13, `:search` 222(새 실행). `:notification` 243, `:auxiliary-global` 110, `:chatbot` 245(이전 결과). 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(⑤ 승인 Q2 a) |
| 관련 변경 | #907 |

#### NR-055 · 2026-11-12 · 통과 (N7 지갑 4종 #907)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-055 |
| 실행 날짜 | 1차 2026-11-12 12시 30분대(약 9분), 2차 12:45~12:48, 3차 13:43~13:46 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-907` |
| 커밋 | origin/dev `973ccbe5`(커밋 전 작업 트리) |
| 작업 트리 변경 | `WalletAlerts`·`WalletAlertTemplates` 추가, `NotificationTemplates` 등록, `NotificationBillingEventListener` 처리기 4개. 시험 `WalletAlertsTest`(6)·`NotificationWalletAlertTest`(4). 2차는 1차 뒤 찾은 결함(변환 검증 실패가 재시도됨)을 `RejectedEventException`으로 바꾸고 `NotificationBillingEventListenerTest`(1)와 Kafka 필수 값 DLT 시험(1)을 더한 뒤. 3차는 S6 리뷰 반영으로 Kafka 건너뜀 시험에 탈퇴 회원을 더한 뒤 |
| 대상 기능 | N7 지갑 4종 알림(충전 실패·환불 실패·환불 운영자 확인 필요·환전 승인) |
| 관련 NT ID | NT-GEN-19, NT-GEN-10·15(지갑 4종) |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 1차·2차·3차 모두 통과(종료 코드 0). 1차는 모든 시험 태스크 새로 실행. 2차는 `:notification:test`·`verifyModuleStructure`·`:auxiliary-app:test`만 새로 실행했고 `:auxiliary-global`·`:chatbot`·`:search` 시험은 `UP-TO-DATE`(1차 결과). 3차는 `:notification:test`·`verifyModuleStructure`만 새로 실행(나머지 `UP-TO-DATE`) |
| 건수 | 1차 `:notification` 241, 2차·3차 `:notification` 243. `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 174(1차). 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음. TDD RED 확인(단위: 클래스 없음 컴파일 오류, Kafka: 알림 0건 3건 실패, 리스너: `IllegalArgumentException`)은 이 기록 밖 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #907 |

#### NR-054 · 2026-11-12 · 통과 (N8 #890 origin/dev 병합 뒤, #872 강의 지급 제목)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-054 |
| 실행 날짜 | 2026-11-12 11:16~11:24 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-890` |
| 커밋 | `8c3e6148` + origin/dev `f17b91f0` 병합(커밋 전 작업 트리) |
| 작업 트리 변경 | origin/dev(#872·#893·#894·#895) 병합. 충돌은 `notification-catalog.md` 강의 표 하나(강의 7종 행은 dev의 #872 구현 표시, `PAYOUT_COMPLETED_DAILY` 행은 이 브랜치). ND-104(묶음 문구는 제목 없이 유지)와 관련 서술, Kafka 시험의 강의 원천 지급 하나에 제목을 실어 문구가 바뀌지 않는지 확인 |
| 대상 기능 | 병합 결과 회귀, ND-104 |
| 관련 NT ID | NR-050과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0, 모든 시험 태스크 새로 실행) |
| 건수 | `:notification` 231, `:auxiliary-app` 13, `:auxiliary-global` 110, `:chatbot` 245, `:search` 174. 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #890 |

#### NR-053 · 2026-11-12 · 통과 (N8 #890 PR 리뷰 반영: 묶음 잠금 순서)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-053 |
| 실행 날짜 | 1차 2026-11-12 10:50~10:53, 2차 10:57~11:00 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-890` |
| 커밋 | `d2cdf456`(커밋 전 작업 트리) |
| 작업 트리 변경 | `NotificationWriter.addTo`가 보이는 알림과 묶음 행을 한 번에 ID 오름차순으로 잠근다. NT-BUN-09 시험 2개(전체 읽음과 겹침). 2차는 S6 뒤 시험 단언(묶음 밖 알림 모두 읽음)·주석과 문서 보강 |
| 대상 기능 | 강의 지급 하루 묶음(N8) 잠금 순서 |
| 관련 NT ID | NT-BUN-09, NT-BUN-01~08, NT-RET, NT-READ |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 1차·2차 모두 통과(종료 코드 0) |
| 건수 | 1차 새로 실행: `:notification` 231, `:auxiliary-app` 13. 2차 새로 실행: `:notification` 231(`:auxiliary-app` test는 UP-TO-DATE, 1차 결과). 실패 0·건너뜀 0. `:auxiliary-global`·`:chatbot`·`:search` test는 UP-TO-DATE(마지막 실행 NR-052) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #890 |

**메모**: NT-BUN-09 시험 2개는 고치기 전 코드에서도 통과했다(10:4x `:notification:test --tests "*NotificationDailyPayoutTest"` 15/15). 이전 코드도 밀어냄이 일어나는 경우(되살림)에는 묶음 행이 전체 읽음의 잠금 대상이 아니고, 묶음 행이 대상인 경우(보이는 묶음 갱신)에는 밀어낼 행이 없어 교착에 이르지 않기 때문이다. 이번 수정은 잠금 순서 규칙을 코드가 직접 지키게 한 방어적 수정이고, 시험은 회귀 방지용이다.

#### NR-052 · 2026-11-12 · 통과 (N8 #890 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-052 |
| 실행 날짜 | 2026-11-12 10:30~10:38 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-890` |
| 커밋 | `5c6ca07c`(`5be856f4` Feat, `2ab396cf` Docs + origin/dev `b02e727c` 병합) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | 병합 결과 회귀 |
| 관련 NT ID | NR-050과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0, 세 태스크 모두 새로 실행) |
| 건수 | 모두 새로 실행: `:notification` 229, `:auxiliary-app` 13, `:auxiliary-global` 109, `:chatbot` 245, `:search` 168. 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행(사용자 결정, CI에 맡김) |
| 관련 변경 | #890 |

#### NR-051 · 2026-11-12 · 통과 (N8 #890 자체 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-051 |
| 실행 날짜 | 2026-11-12 10:16~10:19 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-890` |
| 커밋 | `69240da8`(커밋 전 작업 트리) |
| 작업 트리 변경 | NR-050 + S6 리뷰 반영: 로그 문장, 저장소 주석, 합산을 `PayoutDailyTemplate.added`로, `Outcome.UPDATED` 설명, 지급 항목 CHECK `> 0` → `>= 0`(0 HP 지급 허용, 롤백 시험은 음수), NT-BUN-08을 처음 보는 같은 지급 둘 + 재전달 셋의 경합으로 강화, NT-BUN-05에 되살린 뒤 재전달 확인 |
| 대상 기능 | 강의 지급 하루 묶음(N8) |
| 관련 NT ID | NR-050과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-050과 같다 |
| 결과 | 통과(종료 코드 0) |
| 건수 | 새로 실행: `:notification` 229, `:auxiliary-app` 13, `verifyModuleStructure`. 실패 0·건너뜀 0. `:auxiliary-global`·`:chatbot`·`:search` test는 UP-TO-DATE(입력 변경 없음, 새 실행 아님. 마지막 실행은 NR-050) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check` 미실행 |
| 관련 변경 | #890 |

**메모**: 실행 전 잠금 검증 실험. `addDailyPayout`의 받는 사람 행 잠금(`findForUpdate`)을 잠금 없는 조회로 잠깐 바꾸면 `NotificationDailyPayoutTest` 13개 중 경합 4개(같은 지급 동시, 서로 다른 지급 동시, 되살림과 새 알림, 되살림과 재전달)가 실패했다. 되돌린 뒤 위 명령을 실행했다.

#### NR-050 · 2026-11-12 · 통과 (N8 #890 하루 묶음, 1차 실패 → 시험 격리 수정 뒤 재실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-050 |
| 실행 날짜 | 1차 2026-11-12 09:50~09:53, 재실행 10:01~10:10 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-890` |
| 커밋 | `69240da8`(커밋 전 작업 트리) |
| 작업 트리 변경 | N8 구현(Flyway `V202611120950`, `DailyPayout`, `PayoutDailyTemplate`, `PayoutItemStore`·`JdbcPayoutItemStore`, `NotificationWriter`·`NotificationAlertService`·지갑 리스너·`Notification`·`NotificationRepository`), 새 시험 `DailyPayoutTest`·`NotificationDailyPayoutTest`, 시험 수정, 알림 문서 |
| 대상 기능 | 강의 지급 하루 묶음(N8) |
| 관련 NT ID | NT-BUN-01~08, NT-GEN-12(`PAYOUT_COMPLETED_DAILY`), NT-BND-03, NT-RT(묶음 프레임) |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0(Testcontainers postgres·kafka·redis) |
| 결과 | 1차 실패(`:notification:test` 229 중 9 실패, 나머지 두 태스크 미실행) → 재실행 통과(종료 코드 0, 세 태스크 모두 새로 실행) |
| 건수 | 재실행: `:notification` 229, `:auxiliary-app` 13, `:auxiliary-global` 109, `:chatbot` 244, `:search` 168. 실패 0·건너뜀 0 |
| 실패 원인 분류 | 1차 9건 모두 F-TEST |
| 수정 내용 | 시험 ID 범위가 겹치던 것을 나눴다: `NotificationMemberAlertTest` 700_000 → 750_000(`NotificationCancelRequestAndObjectionAlertTest`와 겹침), `NotificationRealtimeIntegrationTest` 40_000 → 45_000(`NotificationConcurrencyTest`와 겹침), 새 `NotificationDailyPayoutTest` 70_000 → 80_000(`NotificationHeartbeatTest`와 겹침). `NotificationUserSyncServiceTest`의 전체 행 수 단정(`count()==1`)을 그 회원 행만 보도록 바꿨다. 운영 코드는 바꾸지 않았다 |
| 재실행 결과 | 위 명령 재실행 통과 |
| 미검증 항목 | 전체 `./gradlew check` 미실행. 운영 배포 뒤 실제 강의 지급 배치로 묶음이 생기는지 미확인 |
| 관련 변경 | #890 |

**실패 목록 (1차)**

| 테스트 (클래스#메서드) | NT | 오류 요약 (첫 줄) | 원인 분류 | 근거 | 조치·Issue |
| --- | --- | --- | --- | --- | --- |
| `NotificationMemberAlertTest` 5건 | NT-GEN-06·16 | 다른 클래스 알림 행이 섞임(`Expected size: 1 but was: 2`), 복제본이 이미 있음 | F-TEST | `NotificationCancelRequestAndObjectionAlertTest`와 시작 ID가 같다. 같은 두 클래스를 `origin/dev`(`b02e727c`) 워크트리에서 돌리면 실행 순서가 반대라 통과하고, 이 브랜치에서는 `MemberAlert`가 먼저 돌아 `CancelRequest…`가 `pk_notification_user` 중복으로 실패한다 | 시작 ID 750_000 |
| `NotificationUserSyncServiceTest` 2건 | NT-USR-01·02 | `expected: 1L but was: 60L` | F-TEST | 전체 복제본 수를 단정해 다른 클래스보다 먼저 돌 때만 통과 | 그 회원 행만 확인 |
| `NotificationRealtimeIntegrationTest` 2건 | NT-RT-05, 묶음 프레임 | `Expecting actual not to be null`(프레임 없음) | F-TEST | `NotificationConcurrencyTest`와 시작 ID가 같다 | 시작 ID 45_000 |

**메모**: 새 시험 클래스가 늘어 Gradle의 클래스 실행 순서가 바뀌면서 기존 범위 충돌이 드러났다. 새 시험 클래스를 만들 때 다른 클래스의 `NEXT_ID` 시작값과 겹치지 않는지 확인한다.

#### NR-049 · 2026-11-11 · 통과 (N6 #862 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-049 |
| 실행 날짜 | 2026-11-11 17:17~17:25 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-862`(worktree) |
| 커밋 | `987024b7` + origin/dev `70c3f3ac` 병합(커밋 전 작업 트리) |
| 작업 트리 변경 | origin/dev(#869·#870·#878·#880) 병합. 충돌은 `kafka.md` 토픽 표 하나(member 행은 dev의 #874 이벤트 3종, appearance 행은 N6 설명) |
| 대상 기능 | 병합 결과 회귀 |
| 관련 NT ID | NR-047과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(`-p` worktree) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(종료 코드 0, `verifyModuleStructure` 실행) |
| 건수 | 모두 새로 실행: `:notification` 210, `:chatbot` 244, `:search` 168, `:auxiliary-global` 109, `:auxiliary-app` 13. 실패 0·건너뜀 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-046과 같다. 전체 `./gradlew check` 미실행 |
| 관련 변경 | #862 |

#### NR-048 · 2026-11-11 · 통과 (N6 #862 PR Bot 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-048 |
| 실행 날짜 | 2026-11-11 17:01~17:04 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-862`(worktree) |
| 커밋 | `d7c5f4d3` + 작업 트리 |
| 작업 트리 변경 | 시험 설정 두 곳(`hapbang-module-test.yaml`, `AuxiliaryApplicationTest`)에서 강의 알림 소비자를 끔. 주석(DLT partition 수, 대기 게이지 설명, 판단 이벤트 처리 단위)과 알림 AGENTS.md 현재 상태·3.2 |
| 대상 기능 | Bot 리뷰 반영(운영 동작 변경 없음) |
| 관련 NT ID | NR-047과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(`-p` worktree) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(종료 코드 0, `verifyModuleStructure` 실행) |
| 건수 | 새로 실행: `:notification` 210, `:auxiliary-app` 13. 실패 0·건너뜀 0. `:chatbot` 188·`:search` 159·`:auxiliary-global` 108은 UP-TO-DATE(새 실행 아님, NR-045 결과). 전체 실행 시간이 NR-047(9분 44초)보다 짧은 3분 1초다(Kafka 없는 컨텍스트에서 강의 소비자가 연결을 시도하지 않음) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-046과 같다 |
| 관련 변경 | #862 |

#### NR-047 · 2026-11-11 · 통과 (N6 #862 S6 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-047 |
| 실행 날짜 | 2026-11-11 16:32~16:42 (KST). red(16:28)·대상 시험 green(16:31)은 그 전 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-862`(worktree) |
| 커밋 | `6a11400d` + 작업 트리 |
| 작업 트리 변경 | NR-046에 더해: `ObjectionLegacyFormat`이 JSON 객체가 아닌 본문을 판별 불가로 봄, 검증 실패 → DLT 변환을 초안 만드는 단계로 좁힘(출연·강의 리스너, `NotificationAlertService.cancelRequested(event, checked)`), #818 이전 형식 로그를 종류마다 1·2·4…번째만 INFO. 시험: 객체가 아닌 본문(단위·통합), 파기된 게스트 닉네임 |
| 대상 기능 | S6 리뷰 반영 |
| 관련 NT ID | NT-GEN-17(파기 닉네임 경계), NT-KAF-08 |
| 명령 | red: `./gradlew :notification:test --tests '*ObjectionLegacyFormatTest' --tests '*NotificationCancelRequestAndObjectionAlertTest'`(객체가 아닌 본문 2건 실패: LEGACY로 판정, DLT 없음. 파기 닉네임은 기존 동작이라 처음부터 통과). green: 같은 명령 21건 통과. 회귀: `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(`-p` worktree) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(종료 코드 0, `verifyModuleStructure` 실행) |
| 건수 | 새로 실행: `:notification` 210, `:auxiliary-app` 13. 실패 0·건너뜀 0. `:chatbot` 188·`:search` 159·`:auxiliary-global` 108은 UP-TO-DATE(새 실행 아님, NR-045 결과) |
| 실패 원인 분류 | 실패 없음(red는 의도한 실패) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-046과 같다(파기 닉네임은 이번에 시험함) |
| 관련 변경 | #862 |

#### NR-046 · 2026-11-11 · 통과 (N6 #862 B단계: 소비 연결)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-046 |
| 실행 날짜 | 2026-11-11 16:05~16:14 (KST). red(15:53)·대상 시험 green(16:02)은 그 전 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-862`(worktree) |
| 커밋 | `6a11400d` + 작업 트리 |
| 작업 트리 변경 | 출연 리스너 처리기 3개, `NotificationLectureEventListener`(새), Kafka 설정·오류 처리·DLT 토픽·상수(강의), `NotificationAlertListenerStarter`, `NotificationAlertService.cancelRequested`, `NotificationTemplates` N6 등록. 시험 `NotificationCancelRequestAndObjectionAlertTest`(새), `NotificationAlertListenerStarterTest`, `AlertEventKafka.sendRaw` |
| 대상 기능 | N6 7종 소비·저장, #818 이전 형식 판별 연결(ND-086·ND-096), 리스너 재사용(ND-087) |
| 관련 NT ID | NT-GEN-17·18, NT-KAF-08·09, NT-IDEM-06 |
| 명령 | red·green: `./gradlew :notification:test --tests '*NotificationCancelRequestAndObjectionAlertTest' --tests '*NotificationAlertListenerStarterTest'`(red 13건 실패: 강의 리스너 컨테이너 없음. green 13건 통과). 회귀: `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(`-p` worktree) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(종료 코드 0, `verifyModuleStructure` 실행) |
| 건수 | 새로 실행: `:notification` 207, `:auxiliary-app` 13. 실패 0·건너뜀 0. `:chatbot` 188·`:search` 159·`:auxiliary-global` 108은 UP-TO-DATE(새 실행 아님, NR-045 결과) |
| 실패 원인 분류 | 실패 없음(red는 의도한 실패) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 지표 `hapbang.notification.alert.legacy.skipped` 값, 운영 Kafka의 새 그룹·DLT 생성(배포 뒤 확인), 취소 요청 도착에서 받을 수 없는 호스트·게스트 복제본 없음 경로(N5와 같은 코드 모양, 전용 시험 없음) |
| 관련 변경 | #862 |

#### NR-045 · 2026-11-11 · 통과 (N6 #862 origin/dev(N5 #859) 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-045 |
| 실행 날짜 | 2026-11-11 15:33~15:41 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-862`(worktree, origin/dev `6a11400d`로 fast-forward) |
| 커밋 | `6a11400d` + 작업 트리 |
| 작업 트리 변경 | NR-044의 새 파일과 같다. 문서는 N5 변경과 합쳤다(ND·NR 번호 정리, N5 완료 상태) |
| 대상 기능 | N6 A단계 코드가 N5 코드 위에서 그대로 컴파일·통과하는지 |
| 관련 NT ID | NR-044와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(`-p` worktree) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(종료 코드 0, `verifyModuleStructure` 실행) |
| 건수 | 새로 실행: `:notification` 195, `:chatbot` 188, `:search` 159, `:auxiliary-app` 13. 모두 실패 0·건너뜀 0. `:auxiliary-global` 108은 UP-TO-DATE(새 실행 아님, 이전 결과) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-044와 같다(소비 연결은 B단계) |
| 관련 변경 | #862 |

#### NR-044 · 2026-11-11 · 통과 (N6 #862 A단계: 변환·문구·이전 형식 판별)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-044 (작성 때는 NR-038이었다. N5 #859가 NR-036~NR-043을 써서 병합 때 다시 매겼다) |
| 실행 날짜 | 2026-11-11 13:31~13:39 (KST). red 확인과 대상 시험 green은 그 전(13:24~13:31) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-862`(worktree, origin/dev `e9a37bbd` 기준) |
| 커밋 | `e9a37bbd` + 작업 트리 |
| 작업 트리 변경 | 새 파일: `app/AppearanceCancelRequestAlerts`·`AppearanceCancelRequestTemplates`·`LectureObjectionAlerts`·`LectureObjectionTemplates`, `in/event/kafka/ObjectionLegacyFormat`과 시험 3개(`AppearanceCancelRequestAlertsTest`·`LectureObjectionAlertsTest`·`ObjectionLegacyFormatTest`). 문서(ND-082~ND-087 반영) |
| 대상 기능 | N6 변환(받는 사람·원본 대상 ID·이동 대상·발생 시각·필수 값 검증), 문구 0.6, #818 이전 형식 판별. 리스너 연결 전 |
| 관련 NT ID | NT-GEN-17·18(변환·문구 부분), NT-IDEM-06(원본 대상 ID 부분), NT-KAF-08(판별 부분, (4) 출연 필수 값) |
| 명령 | red: `./gradlew :notification:test --tests '*AppearanceCancelRequestAlertsTest' --tests '*LectureObjectionAlertsTest' --tests '*ObjectionLegacyFormatTest'`(빈 껍데기에서 17건 모두 실패, 기능 없음·예외 타입 다름). green: 같은 명령 17건 통과. 회귀: `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(`-p` worktree) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(회귀 명령 종료 코드 0, `verifyModuleStructure` 실행) |
| 건수 | `:notification` 140, `:chatbot` 135, `:search` 160, `:auxiliary-global` 108, `:auxiliary-app` 12. 모두 실패 0·건너뜀 0(이 worktree의 첫 실행이라 모두 새로 실행) |
| 실패 원인 분류 | 실패 없음(red는 의도한 실패) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | Kafka 소비·DLT·알림 저장·실시간 전달(NT-GEN-17·18 통합, NT-KAF-08 (1)~(3)의 커밋·DLT 동작, NT-KAF-09, NT-IDEM-06 저장 중복), 게스트 닉네임을 복제본에서 읽는 부분. 모두 N5 병합 뒤 B단계 |
| 관련 변경 | #862 |

#### NR-043 · 2026-11-11 · 통과 (#859 PR CI 실패 원인 수정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-043 |
| 실행 날짜 | 2026-11-11 15:15~15:25 (KST) |
| 실행자 | Claude Code (CI 실패는 GitHub Actions, 사용자가 로그 전달) |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `5bf3e3be`(CI 실패 커밋) + 작업 트리 |
| 작업 트리 변경 | `NotificationAlertListenerStarterTest`: 대기 게이지 0 단정을 컨테이너 시작과 같은 `untilAsserted` 안으로 옮김(운영 코드 변경 없음) |
| 대상 기능 | 시험 경합(CI에서만 실패) |
| 관련 NT ID | NT-USR-09 |
| 명령 | 재현: `[DEBUG-859a]` 임시 지연(컨테이너 시작과 게이지 0 사이 1.5초)을 넣고 `./gradlew :notification:test --tests "*NotificationAlertListenerStarterTest"` → 수정 뒤 같은 명령 → 지연 제거 뒤 같은 명령 → `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | 로컬은 NR-036과 같다. CI는 GitHub Actions(ubuntu) |
| 결과 | CI 실패 → 로컬 재현(지연 주입, 같은 90행·`expected 0.0 but was 1.0`) → 수정 뒤 지연 있는 채로 통과 → 지연 제거 뒤 통과 → 3명령 통과(Gradle 종료 코드 0) |
| 건수 | 3명령: `:notification` 성공 178, 실패 0, 오류 0, 건너뜀 0(새로 실행, XML 기준). `:auxiliary-app` test는 `UP-TO-DATE`(NR-042 결과 13, 실패 0) |
| 실패 원인 분류 | 시험 경합. Starter는 출연·지갑 컨테이너를 시작한 **뒤에** 대기 게이지를 0으로 바꾸는데, 시험은 컨테이너가 실행 중인 것만 기다리고 게이지 0을 곧바로 단정했다. 느린 CI에서 단정이 게이지 갱신보다 먼저 실행됐다. 기각한 가설: CI의 여러 MeterRegistry(OTLP), Backfill 재실행으로 게이지가 다시 1 |
| 수정 내용 | 게이지 0도 함께 기다리게 시험을 고쳤다. 운영 순서(시작 뒤 0)는 "기다리는 중"의 뜻에 맞아 그대로 둔다. 임시 지연은 지웠다(`DEBUG-859a` 검색 결과 없음) |
| 재실행 결과 | 위와 같다 |
| 미검증 항목 | CI 재실행 결과(Push 뒤 확인). NR-042의 미검증 항목은 그대로 |
| 관련 변경 | #859 |

#### NR-042 · 2026-11-11 · 통과 (#859 ND-095 알림 시작 시각 첫 기동 기록, 두 번째 실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-042 |
| 실행 날짜 | 2026-11-11 14:49~14:51(1차, 실패), 14:51~14:54(2차, 통과) (KST, 끝 시각은 실행 로그 파일 기준) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `01fbac8b` + 작업 트리 |
| 작업 트리 변경 | Flyway `V202611111530__create_notification_runtime_setting.sql`, 새 `AlertStartStore`·`JdbcAlertStartStore`, `AlertStartPolicy`(설정 우선, 없으면 빈 초기화 때 DB 기록), `application-prod.yaml`(임시 값 제거). 시험: `AlertStartPolicyConfigTest`(4), 새 `JdbcAlertStartStoreTest`(2), `AlertStartPolicyTest`, `NotificationModuleIntegrationTest`(테이블 목록), `AuxiliaryApplicationTest`(설정 없이 기동·기록 행). 문서 |
| 대상 기능 | ND-095(U20 해소) |
| 관련 NT ID | NT-KAF-04 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 1차 실패(Gradle 종료 코드 1), 2차 통과(종료 코드 0, 2분 41초) |
| 건수 | 2차: `:notification` 178, `:auxiliary-app` 13(첫 기동 기록 시험 포함), 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test는 `UP-TO-DATE` |
| 실패 원인 분류 | 1차: `NotificationModuleIntegrationTest#테이블은 처음 쓰는 작업에서 만든다`가 notification schema 테이블 목록을 둘(`notification`, `notification_user`)로 단정해, ND-095로 생긴 `notification_runtime_setting` 때문에 실패(결정이 바꾼 기대값, 시험 갱신 누락) |
| 수정 내용 | 그 시험의 기대값에 `notification_runtime_setting`을 더하고 이름을 N5 기준으로 고쳤다 |
| 재실행 결과 | 위 명령 2차 통과 |
| 미검증 항목 | 운영 첫 배포에서 실제로 행이 생기는지(E2E). 여러 pod 동시 첫 기동은 같은 DB에 8개 스레드로 흉내 냈다. NR-040의 미검증 항목은 그대로 |
| 관련 변경 | #859 |

#### NR-041 · 2026-11-11 · 통과 (#859 PR 리뷰 반영 뒤 origin/dev 병합)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-041 |
| 실행 날짜 | 2026-11-11 14:25~14:28 (KST, 끝 시각은 실행 로그 파일 기준) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `d53257ca`(`d33f12d3` + origin/dev `7690abd4` 병합: #861 쿠폰, #864 검색 기능 스위치 제거) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | dev 병합 뒤 회귀. 겹친 파일 `AuxiliaryApplicationTest`(검색 소비 끄기 줄 추가)·`application-prod.yaml`(검색 주석)은 자동 병합, 알림 설정 줄과 검색 줄이 함께 남은 것을 확인 |
| 관련 NT ID | NR-040과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 2분 48초) |
| 건수 | `:auxiliary-app` 12, `:search` 159 새로 실행·실패 0(병합으로 입력이 바뀜). `:notification` 175·`:auxiliary-global` 108·`:chatbot` 135는 `UP-TO-DATE`(NR-040 결과, 실패 0). `verifyModuleStructure` 새로 실행·통과 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-040과 같다 |
| 관련 변경 | #859 |

#### NR-040 · 2026-11-11 · 통과 (#859 PR 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-040 |
| 실행 날짜 | 2026-11-11 14:18~14:21 (KST, 끝 시각은 실행 로그 파일 기준) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `ca66ec01` + 작업 트리 |
| 작업 트리 변경 | 관리자 Backfill 비동기(ND-092): 새 `BackfillRun`·`BackfillRunStatus`·`BackfillRunStore`·`RedisBackfillRunStore`·`NotificationAdminBackfillExecutorConfig`, `NotificationAdminFacade`(시작·조회, 잠금·기록 실패 처리), `ApiV1NotificationAdminController`(202·GET), `NotificationUserBackfillService`(`acquire`·`runLocked`), `NotificationErrorCode`(`BACKFILL_STATUS_UNAVAILABLE`·`BACKFILL_RUN_NOT_FOUND`). 대기 게이지·정지 ERROR(ND-093): `NotificationAlertListenerStarter`, `k8s/platform/base/alerting/rules.yaml`(`hapbang-notification-backfill-waiting`), `docs/architecture/infrastructure/observability.md` 표. 시험: `ApiV1NotificationAdminControllerTest`(7), `NotificationAlertListenerStarterTest`(게이지·STALLED), `NotificationMemberAlertTest`(+저장 실패 롤백). 문서 |
| 대상 기능 | PR 리뷰 6건 반영(U20 확정 방식 ND-094, 비동기 Backfill, 롤백 시험, 정지 alert, AGENTS·PROGRESS) |
| 관련 NT ID | NT-USR-09·10, NT-GEN-16 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`, `kubectl kustomize k8s/platform/base` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 2분 33초). kustomize 빌드 통과 |
| 건수 | `:notification` 175, 실패 0(새로 실행). `:auxiliary-app` 12, 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test는 `UP-TO-DATE` |
| 실패 원인 분류 | 실패 없음(이 실행). 구현 중 RED: 정지 ERROR 표식 없음(시간 초과), 관리자 API 컨텍스트 실패(실행 상태 저장소 없음)·대기 게이지 없음 |
| 수정 내용 | 위 작업 트리 변경 |
| 재실행 결과 | — |
| 미검증 항목 | ND-093 여러 pod에서의 지표 의미(앱별 max·30분 지속)는 규칙·문서로만 두고 실행 검증하지 않았다(Grafana alert 평가는 운영에서만). pod 재시작으로 RUNNING이 남는 경우는 오래된 기록을 직접 넣어 UNKNOWN을 봤다(재시작 재현 아님). NR-038의 미검증 항목(동시 경합, 환불·환전 Kafka 경로, 운영 E2E)은 그대로 |
| 관련 변경 | #859 PR 리뷰 |

**메모(정정)**: NR-038의 실행 날짜는 실제로 13:39~13:42, NR-039는 13:50이다(실행 로그 파일의 끝 시각 기준). 두 기록은 시각을 어림해 적었다. 지난 기록은 고치지 않고 여기 남긴다(1.1 규칙 3).

#### NR-039 · 2026-11-11 · 통과 (#859 origin/dev 병합 뒤, 시험 태스크 UP-TO-DATE)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-039 |
| 실행 날짜 | 2026-11-11 14:05 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `a9efa88b`(`56634220` + origin/dev `e9a37bbd` 병합) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | dev 병합(#858: k8s kustomization·인프라 문서·alert 규칙, 알림 코드와 겹치는 파일 없음) 뒤 회귀 |
| 관련 NT ID | NR-038과 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 6초) |
| 건수 | 새로 실행한 시험 없음: `:notification:test`·`:auxiliary-app:test`·`:auxiliary-global`·`:chatbot`·`:search` test 모두 `UP-TO-DATE`(병합이 시험 입력을 바꾸지 않음. 결과 XML은 NR-038의 `:notification` 171·`:auxiliary-app` 12, 실패 0). `verifyModuleStructure`는 새로 실행·통과 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-038과 같다 |
| 관련 변경 | #859 |

#### NR-038 · 2026-11-11 · 통과 (#859 S6 리뷰 반영 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-038 |
| 실행 날짜 | 2026-11-11 13:43~13:46 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `35735bf1` + 작업 트리 |
| 작업 트리 변경 | NR-037 목록 + `NotificationAlertService`(받는 사람 확인을 언급 회원 닉네임 조회보다 먼저, 로그 형식), `NotificationAdminFacade`(장애는 503 `BACKFILL_FAILED`, ND-089), `NotificationErrorCode`(`BACKFILL_FAILED`), `NotificationAlertListenerStarter`(종료 신호 뒤 시작 안 함, 잠금 경쟁은 INFO), `NotificationUserBackfillService`(로그 ID 20개, JVM 시각), `NotificationUserRepository`(시각 파라미터), `AlertDrafts`(`appliedAt`), `AlertTemplate`(`PayoutType`), 리스너 import·로그. 시험: `NotificationAlertEventListenerTest`(+받을 수 없는 회원·언급 복제본 없음, +탈퇴), `NotificationMemberAlertTest`(+시작 시각 이전 승급), `ApiV1NotificationAdminControllerTest`(+503), 새 `NotificationMentionRetryTest`(ND-091 재시도) |
| 대상 기능 | S6 리뷰 결함(받을 수 없는 회원인데 언급 회원 복제본 없음으로 DLT) 수정, ND-089~ND-091 |
| 관련 NT ID | NT-GEN-07·16, NT-KAF-07, NT-USR-03·09·10 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과(종료 코드 0, 2분 40초) |
| 건수 | `:notification` 171, 실패 0(새로 실행). `:auxiliary-app` 12, 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test는 `UP-TO-DATE`(입력 변경 없음, 마지막 새 실행 NR-036) |
| 실패 원인 분류 | 실패 없음(이 실행). 수정 전 RED: `받을_수_없는_회원이면_언급_회원_복제본이_없어도_DLT_없이_건너뛴다`(결함 재현), `끝까지_읽지_못하면_결과_없이_503_BACKFILL_FAILED다`(500). `NotificationMentionRetryTest`는 수정 전부터 통과(재시도 가능 분류 확인용) |
| 수정 내용 | 위 작업 트리 변경 |
| 재실행 결과 | — |
| 미검증 항목 | NT-USR-08 실제 동시 경합(순서 실행만), NT-GEN-16의 알림 저장 실패 때 복제본 롤백(저장 실패를 일으킬 수단이 없어 시험하지 않음), 환불·환전 4종 Kafka 경로(단위 시험만), 운영 회원 API Backfill·첫 배포(E2E) |
| 관련 변경 | #859 |

#### NR-037 · 2026-11-11 · 통과 (ND-088 시작 시각 설정 검증, 대상 시험만)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-037 |
| 실행 날짜 | 2026-11-11 13:23~13:25 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `35735bf1` + 작업 트리 |
| 작업 트리 변경 | NR-036 목록 + `AlertStartPolicy`(설정 문자열을 `OffsetDateTime`으로 직접 읽음), 새 `AlertStartPolicyConfigTest`, `AlertStartPolicyTest`(생성자 두 개), `application-prod.yaml` 주석 |
| 대상 기능 | 알림 시작 시각 값이 없거나 잘못되면 기동 실패, 운영 형식(`+09:00`) 읽기 |
| 관련 NT ID | NT-KAF-04 |
| 명령 | `./gradlew :notification:test --tests "*AlertStartPolicyConfigTest" --tests "*AlertStartPolicyTest" --tests "*NotificationAlertEventListenerTest"` |
| 실행 환경 | NR-036과 같다 |
| 결과 | 통과 |
| 건수 | 3 + 3 + 9, 실패 0 |
| 실패 원인 분류 | 첫 실행에서 `운영과_같은_형식의_값은_그_시각을_그대로_쓴다` 실패: 빈 컨텍스트에 Boot 변환 서비스가 없어 `String → Instant` 변환 불가(시험 환경). 운영 형식 변환을 Spring에 기대지 않게 `AlertStartPolicy`가 직접 읽도록 고쳤다. 두 번째 실행은 `AlertStartPolicyTest`의 `new AlertStartPolicy(null)` 모호 호출로 컴파일 실패(시험 코드) → 형 지정 |
| 수정 내용 | 위와 같다 |
| 재실행 결과 | 위 명령 통과 |
| 미검증 항목 | 전체 3명령은 Phase 4 리뷰 반영 뒤 다시 돌린다 |
| 관련 변경 | #859 |

#### NR-036 · 2026-11-11 · 통과 (N5 #859 이벤트 소비 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-036 |
| 실행 날짜 | 2026-11-11 13:00~13:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-859` |
| 커밋 | `35735bf1` + 작업 트리 |
| 작업 트리 변경 | 알림 모듈: 새 `AlertDrafts`·`AlertTemplate`·`AlertStartPolicy`·`NotificationAlertService`·`NotificationMemberAlertService`·`NotificationUserBackfillService`·`NotificationMemberSource`·`NotificationJob`·`BackfillResult`·`NotificationAdminFacade`·`ApiV1NotificationAdminController`·`NotificationAppearanceEventListener`·`NotificationBillingEventListener`·`NotificationAlertListenerStarter`·`MemberApiNotificationMemberSource`, 고친 `ReceiverPolicy`·`NotificationWriter`·`NotificationWriteResult`(`SKIPPED_RECEIVER`)·`NotificationTemplates`·`NotificationErrorCode`·`NotificationMemberEventListener`·`NotificationKafka{Config,Constants,ErrorHandlerConfig,TopicConfig}`·`NotificationUserRepository`. 시험: 새 `AlertDraftsTest`·`AlertStartPolicyTest`·`ReceiverPolicyTest`·`NotificationUserBackfillServiceTest`·`ApiV1NotificationAdminControllerTest`·`NotificationAlertEventListenerTest`·`NotificationMemberAlertTest`·`NotificationAlertListenerStarterTest`·`AlertEventKafka`·`FakeMemberSource`, 고친 `NotificationWriterTest`·`MemberEventKafka`·`hapbang-module-test.yaml`. 앱: `application-{prod,local}.yaml`(`start-at`), `AuxiliaryApplicationTest`(새 스위치·`start-at`). 문서 |
| 대상 기능 | N5 11종 알림 생성(출연·지갑·회원), 알림 시작 시각·대체 시각, 받는 사람 정책, 회원 복제본 Backfill·시작 순서·관리자 재실행 API |
| 관련 NT ID | NT-GEN-01·03·06·07·10·12·13·15·16, NT-KAF-04·07, NT-IDEM-05, NT-USR-03·04·05·06·08·09·10 (NT-GEN-12는 승급 승인 이름만, 나머지는 미결 U11) |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다(Windows 10, JDK 25, Docker 29.8.0 Testcontainers PostgreSQL·Redis·Kafka) |
| 결과 | 통과(종료 코드 0, 6분 30초) |
| 건수 | `:notification` 163, 실패 0, 오류 0, 건너뜀 0(새로 실행). `:auxiliary-app` 12, 실패 0(새로 실행). `:auxiliary-global`·`:chatbot`·`:search` test도 새로 실행·통과 |
| 실패 원인 분류 | 실패 없음(이 실행). 구현 중 TDD RED는 기대한 실패라 기록하지 않는다. Docker가 꺼져 `NotificationWriterTest`가 `Could not find a valid Docker environment`로 실패한 적이 있다(환경, Docker를 켜고 다시 실행해 통과) |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NT-USR-08의 Backfill과 Kafka 변경이 **동시에** 겹치는 경우는 순서대로(Backfill 뒤 Kafka) 실행해서만 봤다(행 잠금 안 조건부 upsert에 기댐). 환불·환전 4종은 Kafka 경로를 종류마다 돌리지 않고 초안·문구 단위 시험으로 봤다(Kafka 경로는 충전·지급·송금 실패로 확인). `MENTIONED_NOT_REPLICATED` 재시도 뒤 DLT는 시험하지 않았다. 운영 회원 API를 실제로 부르는 Backfill과 운영 첫 배포(E2E)는 실행하지 않았다. 지급 제목(NT-GEN-11, M5 전) |
| 관련 변경 | #859 |

#### NR-035 · 2026-11-10 · 통과 (#847 재리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-035 |
| 실행 날짜 | 2026-11-10 20:58~21:01 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `24b1a2f6` + 작업 트리(NR-034 뒤 시험 1개 변경) |
| 작업 트리 변경 | NR-034 목록 + `NotificationWebSocketConfigTest`(스레드 스냅샷을 이 설정의 접두사 `notification-ws-in-`·`-out-`·`-heartbeat-`로만 셈, 생성 도우미로 중복 제거) |
| 대상 기능 | 재리뷰(S6) 지적: 연결 만료 스케줄러(`notification-ws-expiry-`)가 스냅샷에 섞여 거짓 실패할 위험 |
| 관련 NT ID | NT-RT-07 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 123, 실패 0(새로 실행). `:auxiliary-app:check`의 test 태스크는 모두 `UP-TO-DATE` |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-032와 같다 |
| 관련 변경 | #847 |

#### NR-034 · 2026-11-10 · 통과 (#847 Bot 리뷰 반영 뒤 자체 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-034 |
| 실행 날짜 | 2026-11-10 20:50~20:53 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `24b1a2f6` + 작업 트리 |
| 작업 트리 변경 | NR-033 목록 + `NotificationWebSocketConfigTest`(잘못된 값이면 스레드를 만들지 않음 단정, 올바른 값 시험의 정리를 try/finally로), `NotificationWebSocketConfig` Javadoc |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NT-RT-07 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 123, 실패 0(새로 실행). `:auxiliary-app:check`의 test 태스크는 모두 `UP-TO-DATE`(운영 코드 변경은 Javadoc뿐, 마지막 실행 NR-033·NR-032) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-032와 같다 |
| 관련 변경 | #847 |

#### NR-033 · 2026-11-10 · 통과 (#847 PR Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-033 |
| 실행 날짜 | 2026-11-10 20:45~20:49 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `24b1a2f6` + 작업 트리 |
| 작업 트리 변경 | `NotificationWebSocketConfig`(heart-beat 설정값 0 이하·5분 이상이면 생성자에서 `IllegalArgumentException`, 실행기 생성 전 검사, `MAX_HEARTBEAT`), `NotificationWebSocketConfigTest`(2건 추가), `gateway.yaml` 주석, decisions(ND-068)·api·module·test-spec·이 문서·PROGRESS |
| 대상 기능 | heart-beat 설정값 검증(ND-068) |
| 관련 NT ID | NT-RT-07 |
| 명령 | RED: `./gradlew :notification:test --tests "*NotificationWebSocketConfigTest"`. 회귀: `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 123(2건 추가), 실패 0. `:auxiliary-app:check`에서 `:auxiliary-app:test` 새로 실행 12, 실패 0. `:auxiliary-global`·`:chatbot`·`:search`는 `UP-TO-DATE`(마지막 실행 NR-032), `:notification:test`는 바로 앞 명령 결과로 `UP-TO-DATE` |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-032와 같다 |
| 관련 변경 | #847 |

**메모**
- RED: 잘못된 값(0s·-1s·5m·10m) 시험이 "Expecting code to raise a throwable"로 실패했다. 올바른 값(1ms·25s·5분-1ms) 시험은 기존 동작이라 처음부터 통과했다.

#### NR-032 · 2026-11-10 · 통과 (#847 origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-032 |
| 실행 날짜 | 2026-11-10 19:59~20:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `aa18750b`(origin/dev `2ed68b3a` 병합, #850 포함) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | 병합 뒤 회귀. NR-031 뒤 `NotificationHeartbeatTest`의 닫힘 뒤 `isOpen()` 단정을 상태가 바뀔 때까지 기다리게 고친 것(#849와 같은 이유) 포함 |
| 관련 NT ID | NR-029와 같다 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 121, 실패 0. `:auxiliary-app:check`에서 새로 실행: `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 113, `:search` 160, 실패 0. `:notification:test`는 바로 앞 명령 결과로 `UP-TO-DATE` |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 병합 충돌은 이 문서뿐이었다(#849의 NR-026~NR-028과 이 브랜치의 기록). 두 쪽을 모두 남기고 이 브랜치 기록을 NR-029~NR-031로 바꿨다 |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check`(PR CI), kubeconform(CI manifests job), 병합 뒤 Gateway E2E(manifest 변경 없음, NR-030) |
| 관련 변경 | #847 |

#### NR-031 · 2026-11-10 · 통과 (#847 자체 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-031 |
| 실행 날짜 | 2026-11-10 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `c67aa7b6` + 작업 트리 |
| 작업 트리 변경 | NR-029 목록 + `NotificationWebSocketConfig`(heart-beat 값을 `Duration`으로 두고 브로커 설정 때 배열을 만듦), `RawStomp`(`connectAndSubscribe`가 `connect`를 씀), api·INDEX 문서 |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NR-029와 같다 |
| 명령 | `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 121, 실패 0(새로 실행). `:auxiliary-app:check`에서 `:auxiliary-app:test` 새로 실행 12, 실패 0. `:auxiliary-global`·`:chatbot`·`:search`·`:notification` test는 `UP-TO-DATE`(마지막 실행 NR-029·바로 앞 명령) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | NR-029와 같다. Gateway E2E(NR-030)는 manifest를 바꾸지 않아 다시 돌리지 않았다 |
| 관련 변경 | #847 |

#### NR-030 · 2026-11-10 · 통과 (#847 Gateway E2E, 로컬 k3d)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-030 |
| 실행 날짜 | 2026-11-10 (KST), 3회 실행 중 마지막 |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `c67aa7b6` + 작업 트리 |
| 작업 트리 변경 | NR-029와 같다 |
| 대상 기능 | Gateway route·인증 경계·`exp` claim·WebSocket stream idle(ND-062~ND-064·ND-067) |
| 관련 NT ID | NT-RT-07(Gateway 쪽), NT-RT-09 |
| 명령 | 레포 밖 스크립트(시험용 backend + `run_e2e.py`, 커밋하지 않음). `k3d cluster create --config k8s/local/k3d.yaml` → `k8s/cluster/namespaces.yaml` → `bash k8s/gateway/tools/install-local.sh` → auxiliary-app local overlay의 HTTPRoute·SecurityPolicy·BackendTrafficPolicy를 경로 그대로 복제해 backend와 JWKS만 시험용으로 바꿔 적용 |
| 실행 환경 | k3d `hapbang`(k3s v1.36.5, 2 node), Envoy Gateway v1.9.3(`install/envoy-gateway`), Docker Desktop 8GiB. platform 없이 Gateway만 설치. tracing backend `hapbang-platform/alloy`가 없어 Gateway가 `Accepted=False`라 이 cluster에만 빈 대역 Service·pod(`alloy-standin`, busybox)를 두었다 |
| 결과 | 통과 21/21(종료 코드 0) |
| 건수 | 상태 7(route 3·SecurityPolicy 1·BackendTrafficPolicy 2 Accepted, WebSocket route가 SecurityPolicy 대상 아님), E1·E1b, E2 2건, E3·E3b, E4 3건, E6 3건, E5 2건 |
| 실패 원인 분류 | 최종 실패 없음. 이전 두 실행의 실패는 시험 장치(아래 메모) |
| 수정 내용 | 시험 스크립트만 고쳤다(제품 코드·manifest 변경 없음) |
| 재실행 결과 | 3회째 21/21 |
| 미검증 항목 | 실제 auxiliary-app·member를 거친 E2E(backend는 앱 동작을 흉내 낸 시험 서버), 실제 15분 만료까지 Envoy를 거친 4401(앱 쪽은 NR-029, Gateway 유지는 E5로 나눠 확인), WebSocket route에서 위조 헤더가 실제로 지워지는지(업그레이드만 확인, 지우는 정책은 Gateway 전체 `ClientTrafficPolicy`라 E4 REST 결과와 같다), 운영 EC2 |
| 관련 변경 | #847 |

**메모**
- 확인 결과: JWT 없는 `GET /api/v1/notifications/ws`와 잘못된 `Authorization`이 실린 요청 모두 Gateway를 지나 101(E1·E1b). 티켓 없음·틀림은 backend 401이 그대로 전달(E2). 티켓 발급 `POST`에 `X-User-Token-Exp` = 토큰 `exp`, 외부 위조 값은 지워지고 JWT 없는 요청에는 헤더가 없음(E3·E4). 잘못된 토큰의 발급 요청은 Envoy 401(E3b). `POST /ws`·`GET /ws/x`는 일반 route(E6).
- E5: 25초마다 양쪽이 heart-beat를 보낸 연결은 330초 뒤에도 열려 있었고(서버 heart-beat 13개), heart-beat 없는 대조 연결은 300.99초에 끊겼다(stream idle 5분).
- 1회째: `GET /ws/x` 웹소켓 업그레이드가 401이 아니라 403이었다. 일반 route는 웹소켓 업그레이드를 받지 않아 Envoy가 403으로 거절한다(본문 제한 정책이 걸린 route로 보임, 원인 확정 안 함). WebSocket route가 받지 않는다는 목적은 맞아 기대값을 "업그레이드 없음 → JWT 401, 업그레이드 → 101 아님"으로 고쳤다.
- 2회째: E3·E4(JWT 요청)가 401이었다. 스크립트가 실행마다 서명 키를 새로 만들고 ConfigMap을 바꾸지만, 이미 떠 있던 backend pod의 마운트가 늦게 갱신되어 이전 JWKS를 내주었다(실행 뒤 마운트와 ConfigMap이 같아진 것을 확인). 시험 자원을 지우고 다시 실행해 통과했다.
- Gateway 전체(상위)에 걸린 SecurityPolicy는 없다(`k8s/gateway/base`는 `kafka-ui` route의 basicAuth뿐). 그래서 WebSocket route를 targetRefs에서 빼면 JWT 필터가 붙지 않는다(ND-067).

#### NR-029 · 2026-11-10 · 통과 (#847 heart-beat 구현)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-029 |
| 실행 날짜 | 2026-11-10 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-847` |
| 커밋 | `c67aa7b6` + 작업 트리 |
| 작업 트리 변경 | `NotificationWebSocketConfig`(STOMP heart-beat 25초, 스케줄러 1스레드 소유·종료), 테스트 `NotificationHeartbeatTest`(새)·`NotificationWebSocketConfigTest`·`RawStomp`(heart-beat 보조), `apps/auxiliary-app/k8s/base/gateway.yaml`, 알림 문서(decisions·module·test-spec·api·PROGRESS·INDEX·AGENTS·이 문서) |
| 대상 기능 | 서버 STOMP heart-beat(ND-063) |
| 관련 NT ID | NT-RT-07(앱 쪽), NT-RT-05·06 회귀 |
| 명령 | RED·GREEN: `./gradlew :notification:test --tests "*NotificationHeartbeatTest"`(+ `*NotificationWebSocketConfigTest`). 회귀: `./gradlew :notification:test`, `./gradlew verifyModuleStructure`, `./gradlew :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(세 명령 모두 종료 코드 0) |
| 건수 | `:notification` 121, 실패 0. `:auxiliary-app:check`에서 새로 실행: `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 113, `:search` 132, 실패 0. `:notification:test`는 바로 앞 명령 결과로 `UP-TO-DATE` |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | — |
| 재실행 결과 | — |
| 미검증 항목 | 전체 `./gradlew check`, kubeconform(로컬에 없음, CI manifests job), Gateway 경유(NR-030) |
| 관련 변경 | #847 |

**메모**
- RED: `NotificationHeartbeatTest` 5건 중 3건이 기대한 이유로 실패했다(CONNECTED `heart-beat:0,0`, 서버 heart-beat 0개, 클라이언트 heart-beat가 끊겨도 닫히지 않음). 받기 0이면 서버 heart-beat 없음·heart-beat 중 4401은 기존 동작이라 처음부터 통과했다(회귀 보호).
- `NotificationWebSocketConfigTest`는 생성자 인자가 늘어 구현 전에는 컴파일되지 않는 상태로 고쳤고, 실패 출력은 따로 보지 않았다. 구현 뒤 스레드 5개(수신 2·송신 2·heart-beat 1)가 모두 끝나는 것을 확인했다.

#### NR-028 · 2026-11-10 · 통과 (#849 PR 리뷰 3차 반영, NR-027 근거 보충)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-028 |
| 실행 날짜 | 2026-11-10 17:35~17:37 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-849` |
| 커밋 | `6dafcd48` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeIntegrationTest`의 구독 거절 확인: 실패 메시지에 실제 대기 시간(6초 = WAIT 5초 + 닫힘 대기 1초) 표시, 설명을 `RawStomp` 큐 동작(닫히기 전에 받은 프레임은 남는다)에 맞게 고침. 이 문서 |
| 대상 기능 | #849 PR 리뷰 3차 |
| 관련 NT ID | NR-026과 같다 |
| 명령 | 두 클래스 `--rerun` 1회 + `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-026과 같다 |
| 결과 | 통과 |
| 건수 | 두 클래스 통과(17:35~17:36). 검증 명령(17:36~17:37): `:notification` 116 새 실행·통과, 나머지 `UP-TO-DATE`(새 실행 아님, 마지막 실행 NR-026 통과), 종료 코드 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 메시지·주석만(시험 판정 동작 변경 없음) |
| 재실행 결과 | — |
| 미검증 항목 | NR-026과 같다 |
| 관련 변경 | #849 |

**메모 — 1002 근거(NR-027 보충)**
- 확인한 대상: spring-websocket 7.0.9 jar의 `org.springframework.web.socket.messaging.StompSubProtocolHandler`, `javap -c -p`로 바이트코드를 봤다.
- `private void sendErrorMessage(WebSocketSession, Throwable)`가 `CloseStatus.PROTOCOL_ERROR`를 읽어 `WebSocketSession.close(CloseStatus)`를 부르는 곳이 3곳 있다. 클라이언트 프레임 처리 실패는 `handleError(WebSocketSession, Throwable, Message)` → `sendErrorMessage`로 이 경로를 탄다.
- `private void sendToClient(WebSocketSession, StompHeaderAccessor, byte[])`도 ERROR 프레임을 보낸 뒤 같은 코드로 닫는 곳이 3곳 있다.
- 알림 모듈에는 별도 STOMP 오류 처리기(`StompSubProtocolErrorHandler` 재정의)가 없다.

#### NR-027 · 2026-11-10 · 통과 (#849 PR 리뷰 반영, NR-026 정정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-027 |
| 실행 날짜 | 2026-11-10 17:15~17:20 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-849` |
| 커밋 | `7e4aa64d` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeIntegrationTest`: 중복·밀려난 알림 시험이 앞서 받은 20건의 `targetId`가 `new-0`~`new-19`와 정확히 같은지 확인, 구독 거절 시험의 닫힘 판정을 `PROTOCOL_ERROR`(1002)로 좁힘. 이 문서 |
| 대상 기능 | #849 PR 리뷰 지적 2건 |
| 관련 NT ID | NR-026과 같다 |
| 명령 | 두 클래스 `--rerun` 3회 + `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-026과 같다 |
| 결과 | 통과 |
| 건수 | 두 클래스 3회 모두 통과(`NotificationRealtimeIntegrationTest` 13건). 검증 명령: `:notification` 116 새 실행·통과, 나머지 `UP-TO-DATE`(새 실행 아님, 마지막 실행 NR-026 통과). 종료 코드 0 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 자체 리뷰(import 순서, 저장 목록을 한 번만 만들기) 반영 뒤 17:24~17:25 검증 명령 다시 실행: `:notification` 116 새 실행·통과(`NotificationRealtimeIntegrationTest` 13건), 나머지 `UP-TO-DATE`, 종료 코드 0 |
| 미검증 항목 | NR-026과 같다(CI 재실행 없이 연속 3회, B·C 로컬 재현 못 함) |
| 관련 변경 | #849 |

**메모**
- 1002 근거: spring-websocket 7.0.9 `StompSubProtocolHandler`는 클라이언트 프레임 처리 실패 때(`handleError` → `sendErrorMessage`) ERROR를 보내고 `CloseStatus.PROTOCOL_ERROR`로 닫는다(바이트코드 확인). 알림 모듈에는 별도 STOMP 오류 처리기가 없다.
- **NR-026 정정**: NR-026 메모의 줄 번호(196·117·343행)는 수정 전 파일 기준이라 수정 뒤 파일과 맞지 않는다. 메서드 이름으로 읽는다: A `중복과_밀려난_알림은_프레임을_보내지_않고_중복_방지와_20개_보존은_그대로다`, B `연결_만료가_되면_서버가_연결을_4401로_닫는다`, C `자기_알림_목적지_말고는_구독할_수_없다`, 추가 `Redis가_멈춰도_저장은_끝나고_되돌려지지_않으며_재접속_뒤_목록으로_맞춘다`.

#### NR-026 · 2026-11-10 · 통과 (#849 CI 간헐 실패 시험 수정)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-026 |
| 실행 날짜 | 2026-11-10 16:20~17:02 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-849`(worktree, origin/dev `6d8b1688` 기준) |
| 커밋 | `6d8b1688` |
| 작업 트리 변경 | 있음(커밋 전). 시험만: `NotificationRealtimeIntegrationTest`(중복·밀려난 알림은 먼저 연결하고 앞선 20건을 받아 비움, Redis 멈춤은 멈춘 동안 발행 실패가 기록된 뒤 되살림, 구독 거절은 ERROR 또는 연결 닫힘을 거절로 보고 거절된 연결로 다른 회원 프레임이 오지 않는지 확인), `NotificationConnectionExpiryTest`(만료 뒤 `isOpen()`이 바뀔 때까지 기다림). 운영 코드 변경 없음 |
| 대상 기능 | CI에서 간헐 실패한 실시간 시험(#849) |
| 관련 NT ID | NT-RT-01~06·08(시험 안정성), NT-RET-02·NT-IDEM-01 실시간 부분 |
| 명령 | ① 재현: `Relay.deliver` 앞에 150ms 지연을 임시로 넣고 `:notification:test --tests '*NotificationRealtimeIntegrationTest.중복*'` ② 지연을 둔 채 `--tests '*NotificationRealtimeIntegrationTest' --tests '*NotificationConnectionExpiryTest' --rerun` 3회 ③ 지연을 지우고 같은 명령 5회 ④ `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10, JDK 25, Docker Testcontainers(PostgreSQL, Redis 8, Kafka) |
| 결과 | 통과 |
| 건수 | ④ 새 실행(worktree 첫 빌드): `:notification` 116, `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 113, `:search` 132. 합계 481, 실패 0. ②는 3회 모두, ③은 5회 모두 두 클래스 통과 |
| 실패 원인 분류 | F-TEST(시험이 비동기 전달·세션 상태 전환 시점에 기댐). 운영 코드 결함 아님 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 위 ②③④. 자체 리뷰(S6) 반영 뒤 17:05~17:08: 두 클래스 `--rerun` 1회 통과, ④ 다시 실행(`:notification` 116 새 실행·통과, 나머지 `UP-TO-DATE`), 종료 코드 0 |
| 미검증 항목 | CI에서 재실행 없이 연속 3회 통과(이슈 완료 조건, PR 뒤 확인), B·C는 로컬에서 재현하지 못함 |
| 관련 변경 | #849 |

**메모 — 원인**
- A(`중복과_밀려난_알림은…` 196행): 지연 주입으로 재현했다. 받은 프레임은 `"targetId":"new-4"`였다. 연결 전에 저장한 20건의 프레임이 연결 뒤 늦게 도착한 것으로 확정했다. 먼저 연결하고 20건을 받은 뒤 확인하도록 바꿨다.
- 추가(`Redis가_멈춰도…`, 이슈 "공통" 점검 중 발견): 지연 주입 때 함께 실패했다. 발행 시도가 Redis를 되살린 뒤에 일어나면 성공해 `while-down` 프레임이 재접속한 연결로 왔다. 멈춘 동안 `hapbang.notification.realtime.dropped{reason=publish_failed}`가 오를 때까지 기다린 뒤 되살리도록 바꿨다(이 지표 이름·태그에 기댄다).
- B(`연결_만료가_되면…` 117행): 닫힘 콜백 안에서 깨어나 `isOpen()`을 바로 보는 경합으로 추정. 상태가 바뀔 때까지 기다리게 바꿨다(재현 못 함).
- C(`자기_알림_목적지_말고는…` 343행, 이슈 추가 보고): 로그가 없어 원인을 확정하지 못했다. ERROR 프레임 또는 정상 종료(1000)가 아닌 코드의 연결 닫힘을 거절로 보고, 둘 다 없으면 목적지를 실패 메시지에 남기게 바꿨다. 거절된 연결로 다른 회원의 프레임이 오지 않는지 확인을 더했다. `RawStomp`는 닫히기 전에 받은 프레임을 큐에 남긴다(확인).
- 같은 파일의 다른 "프레임 없음" 시험(롤백·commit 전·다른 회원·읽음·파기·SEND)은 앞선 저장이 없거나 먼저 비우므로 같은 위험이 없다.

#### NR-025 · 2026-11-10 · 통과 (#829 Bot 리뷰 2차 뒤 자체 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-025 |
| 실행 날짜 | 2026-11-10 14:51~14:53 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `006010c2` |
| 작업 트리 변경 | NR-024 목록 + `NotificationRealtimeRelay`(롤백 실패 `TransactionSystemException` 안의 발행 실패도 `publish_failed`로 분류, `publishCause()` 이름), `NotificationRealtimeRelayTest`(언급 회원 없는 경로 발행 실패, 롤백 실패 2건 추가), test-spec |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NR-022와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` 2회 |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과(두 번째 실행 종료 코드 0, BUILD SUCCESSFUL) |
| 건수 | 첫 실행에서 새로 실행: `:notification` 116, `:auxiliary-app` 12, 실패 0. 두 번째는 모든 테스트 태스크가 `UP-TO-DATE`. `:auxiliary-global`·`:chatbot`·`:search`는 두 번 모두 `UP-TO-DATE`(마지막 실행 NR-022 통과) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-022와 같다 |
| 관련 변경 | #829 |

**메모**
- 롤백 실패 시험은 고치기 전 실패했다(191행, `publish_failed` 0). 언급 회원 없는 경로의 발행 실패 시험은 이미 맞는 동작이라 처음부터 통과했다(빈 곳 보강).
- 첫 실행은 출력 줄 수 제한(`Select-Object -First`)이 Gradle 출력을 끊어 종료 코드가 255로 보였다. 제한 없이 다시 실행해 종료 코드 0을 확인했다.

#### NR-024 · 2026-11-10 · 통과 (#829 Bot 리뷰 2차 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-024 |
| 실행 날짜 | 2026-11-10 14:44~14:47 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `006010c2` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeRelay`(잠금 경로에서 Redis 발행 실패만 `publish_failed`·생략, 잠금·DB 실패는 `read_failed`), `NotificationWebSocketConfig`(채널 실행기를 소유하고 `destroy()`에서 종료, 미리 띄움), 테스트 `NotificationRealtimeRelayTest`(1건 추가)·`NotificationWebSocketConfigTest`(새), module·test-spec·이 문서·PROGRESS |
| 대상 기능 | Bot 리뷰 2차 2건 |
| 관련 NT ID | NR-022와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | 새로 실행: `:notification` 114, `:auxiliary-app` 12, 실패 0. `:auxiliary-global`(108)·`:chatbot`(112)·`:search`(93)는 `UP-TO-DATE`(새 실행 아님, 마지막 실행 NR-022 통과) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-022와 같다 |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- `NotificationWebSocketConfigTest`는 처음에 `destroy()`가 없어 컴파일에 실패했다. 빈 `destroy()` 껍데기로 바꾼 뒤 실행하자 미리 띄운 스레드가 없어 실패했다(27행).
- 잠금 경로 `read_failed` 시험은 고치기 전 실패했다(175행, 잠금 실패를 `publish_failed`로 세고 생략함).
- 채널 실행기 시험은 같은 JVM의 다른 시험 컨텍스트 스레드와 섞이지 않게, 이 설정이 만든 스레드만 추적하도록 썼다.

#### NR-023 · 2026-11-10 · 통과 (#829 Bot 리뷰 반영 뒤 자체 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-023 |
| 실행 날짜 | 2026-11-10 14:30~14:32 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `d03ef12c` |
| 작업 트리 변경 | NR-022 목록 + `NotificationRealtimeRelay`(DB 다시 읽기 실패를 `read_failed`로 따로 세고 생략 시간을 걸지 않음, 생략 로그 분리, 언급 회원 한 번만 계산), `NotificationRealtimeRelayTest`(read_failed, 언급 회원 칸이 빈 경우 잠금 경로), module 3.8, test-spec |
| 대상 기능 | 자체 리뷰(S6) 지적 반영 |
| 관련 NT ID | NR-022와 같다 |
| 명령 | `./gradlew :notification:test verifyModuleStructure :auxiliary-app:check`(사용자 지시로 cleanTest 없음) |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | 새로 실행: `:notification` 112, `:auxiliary-app` 12, 실패 0. `:auxiliary-global`(108)·`:chatbot`(112)·`:search`(93)는 `UP-TO-DATE`로 건너뜀(입력 변경 없음, 새 실행 아님. 마지막 실행은 NR-022 통과) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-022와 같다 |
| 관련 변경 | #829 |

**메모**
- `read_failed` 시험은 고치기 전 실패했다(`publish_failed`로 셈). 언급 회원 칸이 빈 경우의 잠금 경로 시험은 이미 있던 방어 동작이라 처음부터 통과했다.

#### NR-022 · 2026-11-10 · 통과 (#829 Bot 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-022 |
| 실행 날짜 | 2026-11-10 14:21~14:26 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `d03ef12c` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRealtimeRelay`(언급 회원 없는 알림은 잠금 없이 commit 뒤 발행, 발행 실패 뒤 생략 시간·`skipped` 지표, 복구 로그), 테스트 `NotificationRealtimeRelayTest`(다시 씀)·`NotificationRealtimeIntegrationTest`(출처 403·티켓 미소모, 생략 시간 반영)·`RawStomp`(출처 지정), 문서(module 3.8·As-Is, test-spec, 이 문서, PROGRESS) |
| 대상 기능 | Bot 리뷰 3건: Redis 무응답 때 DB 연결·잠금 점유, `publish_failed`·상태 전환 테스트, 비허용 출처 403·티켓 미소모 |
| 관련 NT ID | NT-RT-01~06·08, NT-READ-05, NT-USR-07(실시간 부분) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :search:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 110, `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 112, `:search` 93. 합계 435, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | 운영 Redis 장애 상황에서 생략·재시도 동작(단위·통합 테스트로만 확인), NR-016과 같은 항목 |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- 고치기 전에 Relay 단위 테스트 2건이 실패했다: 발행 실패 뒤 생략 없음(`skipped` 0), 언급 회원 없는 알림의 잠금 없는 경로 없음. 출처 시험(403·티켓 미소모)은 이미 있는 동작이라 처음부터 통과했다(빈 곳 보강).
- 중간에 껍데기 생성자가 둘이라 Spring이 생성자를 고르지 못해 통합 테스트가 컨텍스트 기동에 실패했다(F-TEST, `@Autowired`로 해결).
- 변형 확인: ① 모든 알림을 잠금 없는 경로로 보내자 파기 경합 통합 시험(ND-054)과 잠금 순서 단위 시험이 실패했다 ② 생략 판단을 빼자 생략 단위 시험이 실패했다. 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-021 · 2026-11-10 · 통과 (origin/dev 병합 뒤, NR-020 재실행)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-021 |
| 실행 날짜 | 2026-11-10 13:59~14:08 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0ee1b1aa`(`0a3c46d3`에 origin/dev `d055d191` 병합, 충돌 없음) |
| 작업 트리 변경 | 이 문서(NR-020·NR-021)만 |
| 대상 기능 | dev 병합 뒤 N3 회귀(검색 모듈 #828, 챗봇 싱크 #831 등 포함) |
| 관련 NT ID | NR-019와 같다 |
| 명령 | NR-020과 같다 |
| 실행 환경 | NR-016과 같다. 사용자가 Docker Desktop을 다시 시작한 뒤 실행 |
| 결과 | 통과 |
| 건수 | `:notification` 106, `:auxiliary-app` 12, `:auxiliary-global` 108, `:chatbot` 112, `:search` 93. 합계 431, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | NR-020(F-ENV)의 재실행 |
| 미검증 항목 | 전체 `./gradlew check`(PR CI에 맡김), Gateway를 거친 E2E(N4) |
| 관련 변경 | #829 |

#### NR-020 · 2026-11-10 · 실패 (F-ENV, origin/dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-020 |
| 실행 날짜 | 2026-11-10 13:57~13:58 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0ee1b1aa`(`0a3c46d3`에 origin/dev `d055d191` 병합) |
| 작업 트리 변경 | 없음(이 기록 제외) |
| 대상 기능 | dev 병합 뒤 N3 회귀 |
| 관련 NT ID | NR-019와 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :search:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Docker Desktop이 응답하지 않음(`docker version`: "Docker Desktop is unable to start") |
| 결과 | 실패 |
| 건수 | `:notification` 106 중 71 실패(Testcontainers가 필요한 통합 테스트 전부), 35 통과. `:notification:test`가 실패해 `:auxiliary-app:check`의 나머지 프로젝트는 돌지 않았다 |
| 실패 원인 분류 | F-ENV. 컨텍스트 기동 실패 `PostgresTestcontainersConfig` → `IllegalStateException: Could not find a valid Docker environment` |
| 수정 내용 | 없음(코드 원인 아님) |
| 재실행 결과 | Docker 복구 뒤 재실행 필요 |
| 미검증 항목 | 병합 뒤 전체 범위(통합 테스트, chatbot·search·global·app) |
| 관련 변경 | #829 |

#### NR-019 · 2026-11-10 · 통과 (#829 Redis 명령 시간 제한)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-019 |
| 실행 날짜 | 2026-11-10 12:46~12:50 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | NR-018 목록 + `apps/auxiliary-app/src/main/resources/application.yaml`(`spring.data.redis.timeout: 10s`), decisions(ND-061, ND-057 일부 보완됨), module·api |
| 대상 기능 | 앱 공통 Redis 명령 시간 제한(알림 담당 결정 Q1 b), ND-061 문서 반영 |
| 관련 NT ID | NR-018과 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 106, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 297, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | — |
| 미검증 항목 | NR-018과 같다. 10초 시간 제한을 Redis 장애 상황에서 따로 재지 않았다(앱 설정은 모듈 테스트가 읽지 않는다) |
| 관련 변경 | #829 |

#### NR-018 · 2026-11-10 · 통과 (#829 자체 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-018 |
| 실행 날짜 | 2026-11-10 12:37~12:41 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | NR-016 목록 + `NotificationRealtimeRelay`(파기 공유 잠금 안에서 읽기·발행, 버린 프레임 지표·상태 변화 로그), `NotificationConnectionTicketService`(공통 `RateLimitExceededException`), `NotificationErrorCode`(`TOO_MANY_TICKET_REQUESTS` 삭제), `NotificationWebSocketSessions`(닫은 연결은 STOMP 처리기에 넘기지 않음, 미사용 메서드 삭제), build.gradle.kts(micrometer-core), 테스트 `NotificationRealtimeRelayTest`·`NotificationStompInterceptorTest`(새)와 기존 3개 수정, 문서(module 3.8, api, test-spec, PROGRESS, 이 문서) |
| 대상 기능 | 자체 리뷰 지적 반영: 429 공통 처리, 유실 관측, 파기와 실시간 전달의 순서, 수신 검사 빈 곳 |
| 관련 NT ID | NT-RT-01~06·08, NT-READ-05, NT-USR-07(실시간 부분) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :notification:test verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | NR-016과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 106, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 297, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 최종 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | — |
| 미검증 항목 | NR-016과 같다. 발행 실패(`publish_failed`) 지표는 단위 테스트가 없다. `origin/dev`의 새 커밋(#828·#830)을 병합한 뒤의 실행은 아직 없다 |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- 고치기 전에 4건이 실패했다: 티켓 429가 공통 예외가 아님(단위), `Retry-After` 헤더·`TOO_MANY_REQUESTS` 없음(통합), 버린 프레임 지표 없음(단위), 파기 진행 중 옛 닉네임 프레임이 나감(통합, `whileMasking` not null).
- `NotificationStompInterceptorTest` 7건은 이미 있는 동작의 빈 곳(결합 안 된 CONNECT, 끝에 / 붙인 목적지, UNSUBSCRIBE·ACK)을 메운 시험이라 처음부터 통과했다.
- 변형 확인: Relay에서 파기 공유 잠금을 잠깐 빼자 파기 시험이 다시 실패했다(260줄). 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-017 · 2026-11-10 · 측정 (U13, 판정 안 함)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-017 |
| 실행 날짜 | 2026-11-10 11:59~12:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | NR-016과 같다 |
| 대상 기능 | STOMP 연결 수에 따른 메모리·스레드·연결 유지·프레임 지연(U13 근거) |
| 관련 NT ID | NT-RES-03(U13, 판정 안 함) |
| 명령 | `JAVA_TOOL_OPTIONS=-Xmx256m ./gradlew :auxiliary-app:bootRun`(local, compose의 postgres·kafka·redis) + 측정 스크립트(Node 24 내장 WebSocket, 저장소에 넣지 않음) 2회: ① 단계마다 60초 유지 ② 단계마다 10초 유지 후 `jcmd GC.run` 뒤 측정 |
| 실행 환경 | Windows 10, JDK 25, 앱 JVM heap 상한 256MiB(Old Gen max 268435456 확인), Hikari 최대 5(지표 확인). bootRun이라 `-XX:TieredStopAtLevel=1`·devtools 재시작 클래스로더가 붙어 운영 컨테이너와 다르다. 704Mi limit은 로컬에서 걸 수 없어 RSS(WorkingSet)로 비교만 했다 |
| 결과 | 800 연결까지 중단 조건(연결 실패 5% 초과, heap 90% 초과, 도착률 95% 미만, 연결 유지 95% 미만) 없음. OOM 없음 |
| 건수 | 연결 단계 50→100→200→400→800, 단계마다 표본 20명 전달 |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | — |
| 미검증 항목 | 운영 컨테이너(704Mi limit) 안의 RSS, Gateway(N4)를 거친 연결, 실제 알림 생성(N5) 부하와 동시 실행, 15분 연결 만료가 돌 만큼 긴 유지, 800 초과 |
| 관련 변경 | #829 |

**측정 ①(유지 60초, GC 강제 없음)**

| 연결 | 연결 유지율 | 전달(표본) | 지연 p50/최대(ms) | heap used/committed(MiB) | RSS(MiB) | 스레드 | GC 횟수·누적 정지 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 0(기동 직후) | — | — | — | 119.3 / 182 | 463.9 | 63 | 2 · 0.015s |
| 50 | 100% | 20/20 | 157 / 170 | 152.3 / 184 | 483.9 | 67 | 3 · 0.024s |
| 100 | 100% | 20/20 | 118 / 118 | 138.7 / 184 | 493.3 | 67 | 4 · 0.028s |
| 200 | 100% | 20/20 | 114 / 114 | 151.7 / 194 | 521.5 | 68 | 5 · 0.038s |
| 400 | 100% | 20/20 | 127 / 127 | 185.9 / 229 | 574.1 | 70 | 7 · 0.047s |
| 800 | 100% | 20/20 | 118 / 118 | 197.5 / 256 | 650.1 | 67 | 12 · 0.080s |
| 모두 닫은 직후 | — | — | — | 205.5 / 256 | 571.9 | 201 | 12 · 0.080s |

**측정 ②(유지 10초, 측정 전 `GC.run` = live heap)**

| 연결 | live heap(MiB) | RSS(MiB) | 스레드 | 지연 p50(ms) |
| --- | --- | --- | --- | --- |
| 0 | 82.0 | 568.5 | 81 | — |
| 50 | 87.8 | 573.2 | 82 | 121 |
| 100 | 93.3 | 577.9 | 82 | 127 |
| 200 | 104.4 | 587.1 | 82 | 116 |
| 400 | 126.3 | 606.6 | 82 | 112 |
| 800 | 170.8 | 647.7 | 82 | 132 |
| 모두 닫은 뒤 | 82.4 | 573.9 | 203 | — |

**메모**
- 지연은 측정 스크립트가 `docker exec … redis-cli PUBLISH` 직전에 넣은 시각과 브라우저 쪽 수신 시각의 차이라 `docker exec` 오버헤드(약 100ms)가 들어간 상한이다. 연결 수와 상관없이 110~170ms로 일정했다.
- live heap은 연결당 약 0.11MiB(800 연결에 +88.8MiB) 늘고, 모두 닫으면 기동 직후 값(82MiB)으로 돌아왔다(연결 정리 누수 없음).
- 스레드는 연결 수와 관계없이 일정했다(STOMP 수신·송신 각 2, 만료 예약 1, 발행 1). 800 연결을 한꺼번에 닫으면 Tomcat 작업 스레드가 일시적으로 늘었다(201). 모두 `TIMED_WAITING`(풀 대기)이었고 약 1분 뒤 82로 줄었다.
- **800 연결에서 RSS가 648~650MiB로 운영 메모리 limit 704Mi의 약 92%였다.** heap committed도 상한(256MiB)에 닿았다. 이 측정은 로컬 bootRun 조건(devtools·`TieredStopAtLevel=1`, 컨테이너 limit 없음)이라 운영 RSS와 같다고 볼 수 없고, **운영 환경에서 800 연결을 보장한다는 뜻이 아니다.** 실제 알림 생성 부하(N5)·챗봇·검색 요청이 함께 돌면 여유는 더 적다.
- 연결 수 상한과 메모리 예산 변경은 이 기록에서 정하지 않았다. 이 숫자로 다시 검토하고, U13은 인프라·챗봇 담당 확인 전까지 미결이다.
- 측정용 앱 JVM은 끝난 뒤 종료했고(bootRun 작업 중지, 8087 해제 확인), compose 컨테이너는 그대로 두었다.

#### NR-016 · 2026-11-10 · 통과 (#829 실시간 전달)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-016 |
| 실행 날짜 | 2026-11-10 11:12~12:12 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-829` |
| 커밋 | `0debc874` |
| 작업 트리 변경 | 있음(커밋 전). 알림 모듈: build.gradle.kts, `NotificationWriter`, `NotificationErrorCode`, 새 파일(app: `ConditionalOnNotificationRealtime`, `NotificationConnection{TicketService,TicketStore,Facade}`, `NotificationRealtime{Relay,Publisher,ExecutorConfig}`, dto `ConnectionTicket`·`ConnectionTicketResponse`·`NotificationFrame`·`RealtimeMessage` / in: `ApiV1NotificationConnectionController`, `in/websocket/*` 5개, `in/redis/*` 2개 / out: `out/redis/*` 3개, Lua 1개), 테스트 5개(새)·`hapbang-module-test.yaml`. auxiliary global `SecurityConfig`, `application-{local,prod}.yaml`. 문서: decisions·module·api·CONTEXT·INDEX·test-spec·이 문서·AGENTS·PROGRESS |
| 대상 기능 | 연결 티켓, 자체 STOMP, commit 뒤 Redis Pub/Sub 전달, 연결 만료 |
| 관련 NT ID | NT-RT-01~06·08, NT-READ-05, NT-RET-02·NT-IDEM-01(실시간 부분) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest verifyModuleStructure :auxiliary-app:check` |
| 실행 환경 | Windows 10, JDK 25, Docker Testcontainers(PostgreSQL, Redis 8, Kafka) |
| 결과 | 통과 |
| 건수 | `:notification` 96, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 287, 실패·오류·건너뜀 0 (XML 기준, 새 실행) |
| 실패 원인 분류 | 최종 실패 없음. 중간 실패 1건은 F-TEST(아래 메모) |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 위 명령 12:08~12:12 통과 |
| 미검증 항목 | Gateway(N4)를 거친 연결·`exp` claim, 이벤트 소비로 만든 알림의 실시간 전달(N5), 여러 replica |
| 관련 변경 | #829 |

**메모 — 테스트 먼저(red 확인)**
- 테스트 5개를 먼저 써서 컴파일 오류 25건을 확인했다. 시그니처만 둔 껍데기로 바꾼 뒤 28건이 모두 실패했다: 단위 10건은 `UnsupportedOperationException`, 통합 18건은 실시간 빈(티켓 저장소, `SimpUserRegistry`)이 없어 실패했다.
- 구현 뒤 27/28이 통과했다. 남은 1건(`Redis가_멈춰도…`)은 재접속 단계의 시험 도우미가, 닫은 연결의 구독이 서버 목록에서 빠지기 전에 개수를 센 경합이었다(F-TEST). 빠질 때까지 기다리게 고친 뒤 통과했다. 운영 코드 결함이 아니다.
- 변형 확인: ① Relay가 commit을 기다리지 않게 하면 `commit되기_전에는…`을 포함한 6건이 실패했다. ② STOMP SEND를 허용하면 `클라이언트_SEND로…`가 실패했다(다른 회원 연결이 주입된 프레임을 받음). 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-015 · 2026-11-10 · 통과 (#801 PR 리뷰: 같은 알림 행의 동시 쓰기)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-015 |
| 실행 날짜 | 2026-11-10 10:17~10:21 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `5be88868` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationRepository`(조건부 벌크 UPDATE, ID 오름차순 `FOR UPDATE`), `NotificationWriter`(보존 정리), `NotificationQueryFacade`(읽음·전체 읽음), `NotificationMaskingService`(행 잠금 조회), `Notification`(`@DynamicUpdate`, 엔티티 읽음·삭제 메서드 제거), 테스트 `NotificationConcurrencyTest`(새)·`NotificationTest`(읽음 단위 테스트 1건 제거), module·test-spec·이 문서·PROGRESS |
| 대상 기능 | 보존 정리·읽음·전체 읽음·파기가 같은 알림 행을 쓸 때 서로 덮어쓰지 않기 |
| 관련 NT ID | NT-RET-05, NT-USR-07(+ NR-013과 같다) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-013과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 68, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 259, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 아래 메모 |
| 재실행 결과 | 고친 뒤 10:12 `:notification:test` 68건 통과 |
| 미검증 항목 | NR-013과 같다 |
| 관련 변경 | #801 |

**메모 — 경합 테스트(먼저 쓰고 실패 확인, latch로 순서 강제)**
- 구현 전 `NotificationConcurrencyTest` 4건이 모두 실패했다(F-CODE): 보존 정리가 파기된 닉네임을 되돌림(`원래닉네임님이 …`), 전체 읽음이 센 수와 실제 읽은 수가 다름(`expected: 20 but was: 19`), 읽음 처리가 밀려난 알림의 삭제 시각을 비움(`not to be null`), 두 파기 중 하나의 닉네임이 되돌아옴.
- 고친 뒤 4건 통과. 각 테스트가 약 3초(3.0~3.6초)인 것은 먼저 시작한 쪽이 커밋 전 기다리는 상한(3초)까지 기다렸다는 뜻으로, 나중 쪽이 행 잠금에 실제로 막혔음을 보여 준다.
- 변형 확인: `@DynamicUpdate`는 둔 채 파기 조회의 `FOR UPDATE`만 잠깐 빼고 이 클래스만 돌리니 "두 파기" 테스트가 실패했다. 되돌린 뒤 위 명령으로 통과를 확인했다(`@DynamicUpdate`만으로는 해결되지 않는다).

#### NR-014 · 2026-11-10 · 통과 (#801 PR 리뷰)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-014 |
| 실행 날짜 | 2026-11-10 10:17 (KST, NR-015 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `5be88868` |
| 작업 트리 변경 | NR-015와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-015와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #801 |

#### NR-013 · 2026-11-10 · 통과 (N2 + ND-054 파기 표시, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-013 |
| 실행 날짜 | 2026-11-10 09:48~09:51 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | NR-011 + ND-054: Flyway `V202611101100__add_notification_user_masked_at.sql`, `NotificationUser`(파기 표시), `MemberMaskLocks`(회원 단위 advisory lock), `NotificationWriter`·`NotificationMaskingService`·`NotificationUserSyncService` 수정, 테스트(`NotificationUserTest`·`NotificationUserSyncServiceTest`·`NotificationMaskingServiceTest`·`NotificationMemberEventListenerTest`, fixtures) |
| 대상 기능 | ND-054: 파기 표시, 복제본 없는 회원의 파기 상태 복제본, 파기 뒤 저장되는 알림의 닉네임, 파기·저장 순서 잠금 |
| 관련 NT ID | NT-USR-07 외 NR-011과 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-011과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 65, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 256, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 구현 전 `:notification:compileTestJava` 실패(새 메서드 없음)를 먼저 확인했다. 첫 `:notification:test`에서 "복제본 없는 회원의 파기는 아무것도 남기지 않는다"는 기존 리스너 단언이 실패해(ND-054로 파기 상태 복제본이 생김) 단언을 고쳤다 |
| 재실행 결과 | 고친 뒤 `:notification:test` 65건 통과(09:45) |
| 미검증 항목 | NR-011의 미검증 항목 중 "파기 뒤 새로 저장되는 알림의 옛 닉네임"은 ND-054로 정해 이번에 검증했다. 나머지는 NR-011과 같다 |
| 관련 변경 | #801 |

**메모 — 동시 처리 테스트가 빈틈을 잡는지 확인(변형 확인)**: `NotificationWriter`의 공유 잠금(`maskLocks.lockShared`) 한 줄을 잠깐 막고 `NotificationMaskingServiceTest`만 돌리니 "복제본이 없는 회원의 파기와 저장이 동시에" 테스트가 15번 중 15번 옛 닉네임이 남아 실패했다(`expected: 0L but was: 15L`). 줄을 되돌린 뒤 위 명령으로 통과를 확인했다.

#### NR-012 · 2026-11-10 · 통과 (N2 + ND-054, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-012 |
| 실행 날짜 | 2026-11-10 09:48 (KST, NR-013 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | NR-013과 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-013과 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #801 |

#### NR-011 · 2026-11-10 · 통과 (N2 알림 저장·REST, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-011 |
| 실행 날짜 | 2026-11-10 09:24~09:28 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | 있음(커밋 전). 알림 모듈 `src/main`(Flyway `V202611101000__create_notification_table.sql`, `Notification`·`NotificationDraft`·`NotificationPayload`, `NotificationWriter`·`ReceiverPolicy`·`NotificationTemplate(s)`·`NotificationQueryFacade`·`NotificationMaskingService`·dto, `ApiV1NotificationController`, 리스너 파기 처리, 오류 코드, 저장소), `src/test`(새 테스트 5개 클래스·support, 기존 2개 수정), `docs/architecture/infrastructure/kafka.md`, 알림 문서 |
| 대상 기능 | N2: 알림 저장(중복·탈퇴 제외·20개 보존·받는 사람별 락), REST 4개, `UserMaskedEvent` 파기 닉네임 |
| 관련 NT ID | NT-QRY-01~04, NT-READ-01~06, NT-RET-01~05, NT-IDEM-01·02(저장 서비스 수준), NT-GEN-13, NT-USR-03·04(저장 서비스 수준)·07, NT-BND-03 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0. Testcontainers postgres·kafka(챗봇 테스트 컨테이너 포함) |
| 결과 | 통과 |
| 건수 | `:notification` 58, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 249, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 구현 전 `:notification:compileTestJava`가 새 타입이 없어 44건 오류로 실패하는 것을 먼저 확인했다. 첫 `:notification:test`에서 N1의 "테이블은 `notification_user` 하나" 단언이 실패해(N2에서 `notification`이 생김, ND-046 의도) 단언을 N2 기준으로 고쳤다 |
| 재실행 결과 | 고친 뒤 `:notification:test` 58건 통과(09:22) |
| 미검증 항목 | 이벤트 소비로 알림 만들기(N5 이후)의 Kafka 경로 NT-IDEM·NT-RET-02, 정지 회원(U8, NT-USR-05), 파기 뒤 새로 저장되는 알림에 옛 닉네임이 들어오는 경우(정책 미결, 보고서 참고), Gateway route(N4) |
| 관련 변경 | #801 |

**메모**: 동시 저장 테스트(같은 회원 6건 + 다른 회원 1건)는 Hikari 5로 일부가 연결을 기다리지만 받는 사람 행 잠금으로 줄을 서 정확히 20개를 남겼다.

#### NR-010 · 2026-11-10 · 통과 (N2, #801)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-010 |
| 실행 날짜 | 2026-11-10 09:24 (KST, NR-011 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-801` |
| 커밋 | `ab8f957d` |
| 작업 트리 변경 | NR-011과 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-011과 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #801 |

#### NR-009 · 2026-11-09 · 통과 (#797 PR 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-009 |
| 실행 날짜 | 2026-11-09 17:18~17:21 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `3e6aff2a` |
| 작업 트리 변경 | 있음(커밋 전). `NotificationUserSyncService`·`NotificationUserSyncResult`·`NotificationErrorCode`·`NotificationMemberEventListener`(ND-049 변경 이벤트 upsert), 테스트 `NotificationUserSyncServiceTest`·`NotificationMemberEventListenerTest`·`NotificationMemberEventDeadLetterTest`·`NotificationModuleIntegrationTest`·`AuxiliaryApplicationTest`, 알림 문서(decisions·module·test-spec·CONTEXT·AGENTS·이 문서) |
| 대상 기능 | ND-049(복제본 없을 때 온 변경 이벤트로 생성), Flyway 단언 보강(모듈 SQL 성공 적용, 앱에서 notification schema·테이블·이력) |
| 관련 NT ID | NT-USR-01, NT-USR-02, NT-ERR-01, NT-ERR-02, NT-ERR-03, NT-BND-01, NT-BND-03 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-007과 같다 |
| 결과 | 통과 |
| 건수 | `:notification` 30, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 221, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음(구현 전 `:notification:compileTestJava`가 `Outcome.CREATED_FROM_MODIFIED` 없음으로 실패하는 것을 먼저 확인했다) |
| 재실행 결과 | 같은 코드로 17:16 `:notification:test` 30건 통과 |
| 미검증 항목 | 동시 처리 테스트는 매 실행에서 충돌이 실제로 나는지 보장하지 않는다(충돌이 나면 재처리, 나지 않으면 바로 최종 상태를 확인). Flyway 단언 보강은 이미 있는 동작을 확인하는 것이라 실패 단계를 거치지 않았다 |
| 관련 변경 | #797 |

#### NR-008 · 2026-11-09 · 통과 (#797 PR 리뷰 반영)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-008 |
| 실행 날짜 | 2026-11-09 17:18 (KST, NR-009 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `3e6aff2a` |
| 작업 트리 변경 | NR-009와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-009와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

#### NR-007 · 2026-11-09 · 통과 (dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-007 |
| 실행 날짜 | 2026-11-09 17:00~17:07 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `1da5eb40` (병합 `722a939a`: `311f679a` + origin/dev `657bcfa6`, 그 뒤 `-parameters` 커밋) |
| 작업 트리 변경 | 없음 |
| 대상 기능 | N1이 dev(챗봇 #798·#800 등)와 합쳐진 상태, 알림 모듈 `-parameters` |
| 관련 NT ID | NR-004와 같다 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-004와 같다(Testcontainers. 챗봇 테스트의 컨테이너 포함). 로컬 compose(postgres·kafka·redis) 실행 중, 측정용 앱은 종료됨 |
| 결과 | 통과 |
| 건수 | `:notification` 25, `:auxiliary-app` 11, `:auxiliary-global` 108, `:chatbot` 72. 합계 216, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 병합 충돌(`AuxiliaryApplicationTest` properties)을 알림·챗봇 설정을 모두 남겨 해결 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | NR-002와 같다 |
| 관련 변경 | #797 |

#### NR-006 · 2026-11-09 · 통과 (dev 병합 뒤)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-006 |
| 실행 날짜 | 2026-11-09 17:00 (KST, NR-007 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `1da5eb40` |
| 작업 트리 변경 | 없음 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-007과 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

#### NR-005 · 2026-11-09 · 통과 (로컬 기동·자원 측정, 수동)

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-005 |
| 실행 날짜 | 2026-11-09 16:36~16:39 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-004와 같다 |
| 대상 기능 | N1 앱 기동, 회원 복제본 소비자 연결, 자원 측정(인프라 Issue·U13 근거) |
| 관련 NT ID | NT-KAF-06, NT-ERR-03, NT-RES-01·03(U13, 판정 안 함) |
| 명령 | 1) `docker compose -f docker/compose.yaml up -d postgres kafka redis`(기존 볼륨 유지) 2) 로컬 Kafka에 `member.events.v1`을 member-app 선언과 같게 생성(partition 3, 복제 1, `cleanup.policy=delete`) 3) `./gradlew :auxiliary-app:bootRun`(local 프로필) 4) `kafka-consumer-groups.sh --describe --group auxiliary-app.notification-member-replica` 5) `GET /actuator/health`, `GET /actuator/prometheus` 10초 간격 3회 |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0. compose: postgres:18, apache/kafka:4.4.0, redis:8. JVM 옵션 지정 없음(운영 `-Xmx256m`·limit 704Mi 아님), devtools 재시작 클래스로더 사용. 이벤트 유입 없음(유휴) |
| 결과 | 통과 |
| 건수 | 해당 없음(수동 확인) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 이벤트 처리 중 부하 값, 운영 JVM 옵션에서의 값, 운영 cluster |
| 관련 변경 | #797 |

**확인한 것**
- 기동 10.54초, Flyway가 `202611061400`(chatbot)·`202611091500`(notification) 두 버전을 schema `auxiliary` 이력에 적용, `/actuator/health` UP.
- 소비 그룹 `auxiliary-app.notification-member-replica`가 `member.events.v1` partition 0·1·2를 모두 받았다(lag 0, 이벤트 없음).
- 모듈이 DLT `member.events.v1.auxiliary-app.notification-member-replica.DLT`를 partition 3, `retention.ms=604800000`으로 선언했다.

**측정값** (`/actuator/prometheus`, 3회 같은 값 또는 범위)

| 지표 | 값 |
| --- | --- |
| `hikaricp_connections` (total) | 5 |
| `hikaricp_connections_active` | 0 |
| `hikaricp_connections_idle` | 5 |
| `hikaricp_connections_max` / `_min` / `_pending` | 5 / 5 / 0 |
| JVM heap used (heap pool 합) | 71.6 → 75.6 → 77.6 MiB |
| JVM heap committed | 104.0 MiB |
| JVM heap max | 4058.0 MiB(로컬 기본값, 물리 메모리 1/4. 운영은 `-Xmx256m`) |
| JVM non-heap used | 146.0~147.9 MiB |
| live threads | 39 |
| 프로세스 작업 집합(tasklist) | 약 347 MB |

**메모**: 측정 뒤 bootRun 백그라운드 작업을 멈췄지만 앱 JVM(PID 22636)이 남았고, 일반 종료 요청(`taskkill`, /F 없음)은 Windows가 거부했다. 사용자 확인(2026-11-09) 뒤 `taskkill /F /PID 22636`으로 그 프로세스만 강제 종료했다(포트 8087 해제 확인, 이벤트 처리 중이 아니었다). compose 컨테이너(postgres·kafka·redis)는 그대로 둔다.

#### NR-004 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-004 |
| 실행 날짜 | 2026-11-09 16:39~16:41 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-002 + `apps/auxiliary-app/src/test/java/com/hapbang/auxiliary/{AuxiliaryModularityTest,AuxiliaryApplicationTest}.java` |
| 대상 기능 | N1 마무리: 모듈 경계 단언에 notification 추가, 앱 기동 테스트에서 알림 회원 소비자 끄기 |
| 관련 NT ID | NR-002와 같다 + NT-BND-01(전부) |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | NR-002와 같다. 로컬 compose(postgres·kafka·redis)와 측정용 앱(8087)이 떠 있었다(테스트는 Testcontainers·임의 포트라 영향 없음) |
| 결과 | 통과 |
| 건수 | `:notification` 25, `:auxiliary-app` 10, `:auxiliary-global` 89, `:chatbot` 1. 합계 125, 실패·오류·건너뜀 0 (XML 기준, 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | NR-002와 같다 |
| 관련 변경 | #797 |

**메모**: `AuxiliaryApplicationTest` 55.5초(NR-002) → 20.7초, `localhost:9092` 연결 경고 51회 → 0회.

#### NR-003 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-003 |
| 실행 날짜 | 2026-11-09 16:39 (KST, NR-004 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-004와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계 |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` |
| 실행 환경 | NR-004와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

#### NR-002 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-002 |
| 실행 날짜 | 2026-11-09 16:27~16:29 (KST) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | 있음(커밋 전). 알림 모듈 등록(`settings.gradle.kts`, `apps/auxiliary-app/build.gradle.kts`), `apps/auxiliary-app/boundedContext/notification/{build.gradle.kts,src/**}`, 알림 문서(decisions·module·test-spec·INDEX·AGENTS·PROGRESS·이 문서) |
| 대상 기능 | N1 모듈 뼈대: 모듈 등록, schema·`notification_user`, 회원 복제본 소비자, Kafka 소비·DLT 공통 설정 (#797) |
| 관련 NT ID | NT-USR-01, NT-USR-02, NT-USR-04, NT-KAF-06, NT-ERR-02, NT-ERR-03, NT-BND-01, NT-BND-03 |
| 명령 | `./gradlew :notification:cleanTest :auxiliary-global:cleanTest :chatbot:cleanTest :auxiliary-app:cleanTest :auxiliary-app:check` |
| 실행 환경 | Windows 10 Pro, JDK 25.0.4.1, Gradle 9.7.1, Docker 29.8.0 실행 중. Testcontainers postgres·kafka(apache/kafka:4.2.1)·redis(앱 기동 테스트) |
| 결과 | 통과 |
| 건수 | `:notification` 성공 25(실패 0, 오류 0, 건너뜀 0), `:auxiliary-app` 10, `:auxiliary-global` 89, `:chatbot` 1. 합계 125 (XML 기준, 네 태스크 모두 새 실행) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함. 같은 날 16:23 `./gradlew :notification:test` 첫 실행도 25건 통과였다(같은 코드) |
| 미검증 항목 | 알림 생성·REST·STOMP(N2 이후), NT-USR-03·05·06·07(복제본 사용·U8·U17·U19), 로컬 `bootRun` 자원 측정(인프라 Issue용, 미실행) |
| 관련 변경 | #797 |

**메모**
- `AuxiliaryApplicationTest`(운영 설정으로 앱 전체 기동)는 Kafka broker 없이 띄워 알림 회원 소비자가 `localhost:9092` 연결 경고를 반복한다(통과, 55초). 테스트 결과에는 영향이 없다.
- 모듈 테스트 로그의 `OtlpMeterRegistry ... ConnectException`은 로컬 OTLP 수신기가 없어 나는 경고다(모듈 테스트는 앱 `application.yaml`의 지표 내보내기 끄기 설정을 읽지 않는다).

#### NR-001 · 2026-11-09 · 통과

| 항목 | 값 |
| --- | --- |
| 실행 ID | NR-001 |
| 실행 날짜 | 2026-11-09 16:24경 (KST, NR-002 전) |
| 실행자 | Claude Code |
| 브랜치 | `feature/tkt-797` |
| 커밋 | `7b6beb9a` |
| 작업 트리 변경 | NR-002와 같다 |
| 대상 기능 | Gradle 모듈 의존 경계(`:notification`은 `:auxiliary-global`·`:shared`만) |
| 관련 NT ID | NT-BND-02 |
| 명령 | `./gradlew verifyModuleStructure` (`--parallel` 없이 따로) |
| 실행 환경 | NR-002와 같다 |
| 결과 | 통과 |
| 건수 | 해당 없음(Gradle 검사 태스크, BUILD SUCCESSFUL) |
| 실패 원인 분류 | 실패 없음 |
| 수정 내용 | 없음 |
| 재실행 결과 | 재실행 안 함 |
| 미검증 항목 | 없음 |
| 관련 변경 | #797 |

## 4. 미해결 항목

실행 결과로 생긴 미해결 항목을 이 표에서 관리한다. 해결되면 지우지 않고 "해결" 칸에 실행 ID나 Issue를 적는다.

| 번호 | 처음 발견 | 테스트 / NT | 내용 | 원인 분류 | 관련 Issue | 상태 | 해결 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R-01 | NR-066 | `:auxiliary-global` `JobLockPostgresIntegrationTest.connectionLost`(알림 NT 밖) | 전체 검증 중 한 번 실패, 단독 3회·전체 재실행 통과. 추정 원인: 끊은 락 연결의 백엔드 종료를 기다리지 않고 다른 pod가 같은 락을 잡는다 | F-TEST(추정) | 없음(auxiliary global #798 담당에게 테스트 수정 Issue 제안) | 미해결 | — |

변경 이력
- 2026-11-06 처음 작성: 실행 기록 없음. 형식은 [지갑 회귀 실행 기록](../billing/regression-log.md)을 알림 모듈에 맞게 줄였다.
- 2026-11-09 NR-001·NR-002(N1, #797)를 더했다.
- 2026-11-09 NR-003·NR-004(N1 마무리 재실행), NR-005(로컬 기동·자원 측정)를 더했다.
- 2026-11-09 NR-006·NR-007(dev 병합 뒤 재실행)을 더했다.
- 2026-11-09 NR-008·NR-009(#797 PR 리뷰 반영: ND-049, Flyway 단언 보강)를 더했다.
- 2026-11-10 NR-010·NR-011(N2 알림 저장·REST, #801)을 더했다.
- 2026-11-10 NR-012·NR-013(ND-054 파기 표시, #801)을 더했다.
- 2026-11-10 NR-014·NR-015(#801 PR 리뷰: 같은 알림 행의 동시 쓰기)를 더했다.
- 2026-11-12 NR-066(실패, global JobLock 시험)·NR-067(N7 회원 3종, #915)과 4장 R-01을 더했다. 자체 리뷰 반영 뒤 NR-068을 더했다(작업 중 NR-062~NR-064였고 출연·강의 병합 뒤 고쳤다). origin/dev 병합 뒤 NR-069를 더했다.
