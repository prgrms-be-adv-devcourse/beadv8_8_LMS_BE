# 알림 본 부하 시험 (N15)

알림(auxiliary-app notification)의 본 부하 시험 L1~L6 결과다(N15, #970). 정책의 원본은 [알림 결정 기록](../notification/decisions.md) ND-155·ND-157·ND-187~ND-201, 시나리오와 합격 기준의 원본은 [알림 테스트 명세](../notification/test-spec.md) 2.18(NT-LOAD)이다. 이 문서는 측정 수치와 회차의 원본이다.

격리 시험(L3·L4·L5)은 B0 cluster(`k3d-hapbang-b0`)의 값이다. 운영 처리 용량이나 운영 지연을 보증하지 않는다(ND-155·ND-188).

## 요약

| 시험 | 환경 | 상태 | 판정 |
| --- | --- | --- | --- |
| L4 밀린 이벤트 회복 | 격리 | 실행(유효 1회) | **합격**: 할당 완료 → lag 0 46.7초(기준 300초) |
| L5S·L5B·L5M 예약 배치 | 격리 | 실행(각 유효 1회) | **합격**: 최대 지연 35.5초·95.0초·575.3초(기준 120·180·660초) |
| L5F 예약 실패 경로 | 격리 | 실행(유효 1회) | **합격**: 정상 990건 11회차 안, 최대 지연 635.1초(기준 720초), 실패 10건 3번째 시도에 건너뜀 |
| L3 저장→실시간 전달 | 격리 | 실행(유효 1회) | **합격**: p95 9.8ms(기준 1초), 유실 0/6,000 |
| L1 REST 조회 혼합 | 운영 | 미실행(알림 담당 실행) | — |
| L2 STOMP 연결 유지(50 → 100 → 200, 13분, ND-198) | 운영 | 미실행(알림 담당 실행) | — |
| L6 알림·챗봇 혼합 | 운영 | 미실행(챗봇 담당 확인 대기, ND-187). 스크립트만 준비 | — |

## 조건

### 격리(L3·L4·L5)

| 항목 | 값 |
| --- | --- |
| cluster | B0 cluster `k3d-hapbang-b0`([B0 기준선](notification-baseline.md) "조건"과 같은 구성: 앱 704Mi·`-Xmx256m`, PostgreSQL 512Mi, Kafka 700Mi, `auxiliary` database `synchronous_commit=off`, Gateway·NetworkPolicy 없음). 회차마다 `conditions.txt`로 확인 |
| 앱 이미지 | 2026-11-16 `b0.sh setup`으로 `0322495f`(운영 코드 변경 없음)에서 빌드, 이미지 ID `cf9d7470…` |
| 같은 pod의 다른 소비자 | 챗봇·검색 소비자도 같은 토픽(회원·출연·강의)을 소비한다(B0와 같음) |
| 도구 | `loadtest/notification/n15.sh`(b0.sh는 고치지 않음, ND-189), 판정 `verify_n15.py`(B0 V0~V8 함수 재사용) |
| 시계 | 앱·도구 pod는 같은 Docker VM 커널 시계. 호스트 - 도구 pod 시계 차를 회차마다 남긴다 |
| 호스트 | Windows Docker Desktop. 측정 전 사용하지 않는 앱을 닫았다(ND-185) |

## 결과

### L4 밀린 이벤트 회복

fixture: L4 전용 seed 20261116, 내용 해시 `d2e1fd93…`. 회원 1,000 + 정상 백로그 10,000(지갑 4,147·출연 2,853·강의 2,001·고객센터 501·채팅 498) + 실패 단계 150(지갑: 받는 사람 복제본 없음 100, 깨진 본문 50)(ND-189·ND-190).

| 회차 | 상태 | 할당 완료 → lag 0 | 비고 |
| --- | --- | --- | --- |
| `n15-l4-20261116-160239-r1` | 무효 | (참고) 50.1초 | 수집 단계 도구 결함(SIGPIPE)으로 시각 기록·검증을 못 함. 수정 뒤 2회차 |
| `n15-l4-20261116-162322-r2` | 유효 | **46.7초** | V0~V8·회복 모두 통과(ND-196 판정) |

2회차 세부(클러스터 시계):

| 구간 | 값 |
| --- | --- |
| 입력 완료 → replicas 1 | 0.6초 |
| replicas 1 → 다섯 group 할당 완료 | 24.7초(앱 기동). 다섯 group 할당 간격 0.2초 |
| 할당 완료 → 정상 백로그 처리 완료(성공 처리 10,000) | 46.2초(초당 약 216건) |
| 할당 완료 → lag 0(broker 관측, 관측 간격 약 2~8초) | 46.7초 |
| 실패 단계(의도한 DLT 150, 실패 시도 450) | 약 14분(재시도 대기) |
| Hikari | active 최대 4, pending 0, timeout 0 |
| CPU·메모리 | 최대 4,526m, 최대 617MiB |

기록 항목(판정 아님, ND-196):
- **실시간 전달 버림**: 백로그 회복 중 `realtime.dropped{reason=queue_full}` 1,335건(정상 백로그의 13%). 실시간 전달 실행기(스레드 1개, 줄 상한)가 초당 200건 넘는 저장을 따라가지 못해 줄이 찼다. 저장은 모두 정상이고, 버린 프레임은 FE가 REST로 맞춘다(ND-027). N17 개선 후보.
- **메모리**: 최대 617MiB로 704Mi limit의 약 88%. OOMKilled는 없었다. L2 기준(85%)을 L4에 소급하지 않지만 자원 위험으로 U13 전달 자료에 넣는다.

### L5 예약 배치 몰림

예약은 출연 요청 도착 이벤트로 실제 생성 경로를 거쳐 만들었다(출연 요청 만료 임박, 보낼 시각 = 수락 기한 − 12시간, ND-191). 모든 예약의 보낼 시각이 같다. 배치 크기 100, 매분 35초(KST) 회차. 지연 = `processed_at − send_at`. 회차 배정은 회차 끝 로그 기준(ND-197). 시나리오마다 유효 1회(ND-197). 판정 조건은 S6 리뷰 뒤 시험 명세에 맞춰 다시 판정했고 결과는 같다(ND-199, L5B 회차별 분포 판정 추가, L5F retry 수는 기록).

| 시나리오 | 회차 폴더 | 회차별 보냄 | 최대 지연(기준) | 회차 실행 최대 | 판정 |
| --- | --- | --- | --- | --- | --- |
| S 100 | `n15-l5S-20261116-164920-r1` | 1회차 100 | 35.5초(120) | 0.52초 | 합격 |
| B 101 | `n15-l5B-20261116-165911-r1` | 1회차 100, 2회차 1 | 95.0초(180) | 0.58초 | 합격 |
| M 1,000 | `n15-l5M-20261116-171007-r1` | 1~10회차 각 100 | 575.3초(660) | 0.47초 | 합격 |

- L5 유효 회차(S·B·M·F)는 fixture를 만든 Gradle 데몬이 호스트에 떠 있는 채로 쟀다(L3는 내림). 회차 실행이 1초 미만이라 판정에는 영향이 없다고 보고, 이후 회차는 L3와 같이 내린다.
- 세 시나리오 모두 회차 누락 0, 중복·누락 0, 예상 밖 오류 0. L5-M 동안 CPU 최대 1,826m, 메모리 최대 572MiB, Hikari active 최대 1·pending 0.
- 예약 배치는 회차마다 100건만 고르므로, 같은 시각에 몰린 예약 N건의 마지막 지연은 약 ⌈N/100⌉분이다. 회차 실행은 1초 미만이라 처리 시간이 아니라 배치 크기와 주기가 지연을 정한다.

#### L5-F 실패 경로

회차 폴더 `n15-l5F-20261116-192849-r1`. 정상 990 + 실패 10(받는 사람 10명의 복제본을 예약 생성 뒤 격리 DB에서 지움, 100건마다 51번째 자리) + 기한 지남 20(앱을 내린 동안 업무 기한이 지나게 둠)(ND-191·ND-194).

| 기준 | 결과 |
| --- | --- |
| 정상 990건이 ⌈(990 + 3×10)/100⌉ = 11회차 안 | 11회차까지(회차당 96~99건, 11회차 18건) |
| 최대 지연 12분 이하 | 635.1초 |
| 실패 10건이 첫 시도 회차 + 2회차에 건너뜀 | 10건 모두, 실패 3회 |
| ERROR `NOTIFICATION_SCHEDULE_FAILED`·abandoned 각 10 | 10·10(retry 20) |
| 실패 대상 복제본 재생성 없음 | 149번 확인 모두 0, 회원 토픽 offset 그대로 |
| 기한 지남 20건 | 기한 뒤 첫 회차(재기동 뒤, 측정 창 밖)에 건너뜀, 알림 0 |
| 회차 실행 60초 미만·회차 누락 0·중복·누락 0 | 최대 0.50초, 누락 0, 중복·누락 0 |

실패 예약이 회차마다 자리를 하나씩 차지해 정상 예약이 1회차 늦게 끝나는 것은 정해진 동작이다(ND-194). 실패한 예약 때문에 대기열이 막히지는 않았다.

### L3 저장→실시간 전달

회차 폴더 `n15-l3-20261116-195700-r1`(무효: k6 이미지가 노드에 들어가지 못함, 도구 결함), `n15-l3-20261116-201329-r2`(유효). 회원 100명이 각자 STOMP 연결 1개(k6 `grafana/k6:1.0.0`, 클러스터 안 agent 노드), 연결된 회원에게 가는 충전 완료 알림 6,000건을 초당 20건(5분)으로 투입했다. 지연 = 프레임 수신 시각 − `notification.created_at`(ND-193).

| 항목 | 값 |
| --- | --- |
| 저장→수신 | p50 5.7ms, **p95 9.8ms**, p99 12.9ms, 최대 352.6ms(기준 p95 1초) |
| 유실 | 0 / 6,000(기준 0.1% 미만) |
| 중복 수신·측정 제외(기록, ND-199) | 0·0 |
| 연결 | 100/100, 티켓 실패 0, 수집 전 끊김 0 |
| 보조 구간(합산하지 않음, ND-162) | publish p95 4.8ms·p99 7.3ms, send p95 4.8ms·p99 5.0ms. `realtime.dropped` 0 |
| 자원 | CPU 최대 1,283m, 메모리 최대 625MiB(704Mi의 약 89%), Hikari 대기 0 |

조건과 한계:
- 연결은 Gateway가 넣는 사용자 헤더를 직접 주고, 앱의 티켓 발급·출처 검사·티켓 검증·연결 만료는 그대로 거쳤다. Gateway의 JWT 서명 검증과 Envoy 경로는 거치지 않았다(ND-195).
- 앱과 k6 pod는 같은 Docker VM 커널 시계를 쓴다(시계 차 없음). `created_at`은 시간대 없는 시각이라 앱 시간대(운영 base 설정 `TZ=Asia/Seoul`을 B0도 그대로 씀)로 읽어 epoch로 바꿨다. 초당 20건은 소비 처리량보다 충분히 낮아 적체가 지연에 섞이지 않는다. 적체 중 실시간 전달은 L4 기록(버림 13%)을 본다.
- 격리 값이며 운영 지연을 보증하지 않는다(ND-188).

## 운영 실행 방법(L1·L2·L6)

운영 시험은 알림 담당이 실행한다(ND-174·ND-187). L1·L2는 운영 쓰기를 하지 않는다(GET·연결만). L6의 챗봇 FAQ 대화는 LLM을 불러 챗봇 쪽 기록과 외부 LLM 호출을 남길 수 있으므로 챗봇 담당 확인 뒤에만 실행한다. 일반 규칙(k6 버전 고정 컨테이너, 시각 기록, Prometheus 구간 값, CPU credit, Grafana 캡처)은 [부하 테스트 README](README.md)를 따르고, 아래는 알림에만 해당하는 것이다.

### 실행 전 확인

모두 만족할 때만 실행한다. 하나라도 아니면 실행하지 않고 미룬다.

1. 운영 auxiliary-app 이미지가 N14(`0322495f`) 이상이다.
2. N10 운영 E2E 기간이 아니다(ND-158: 그 기간에는 auxiliary-app 배포와 운영 부하 시험을 하지 않는다).
3. 팀에 시각을 공지했다. 같은 시각에 다른 부하 시험·배포가 없다.
4. CPU credit 잔량을 적었다(README "CPU credit"). 바닥이면 미룬다.
5. 실행할 IP에서 1분 안에 다른 비밀번호 로그인을 하지 않는다(로그인은 IP당 분당 10회, #506. L2·L6 setup이 계정 수만큼 로그인한다).

### 계정 파일

레포 밖 `~/.hapbang/load-tests/`(권한 0600)에 둔다. 레포·결과 문서·커밋에 계정 값을 쓰지 않는다(ND-174).

| 파일 | 내용 | 쓰는 시험 |
| --- | --- | --- |
| `notification.env` | `LT_EMAIL`, `LT_PASSWORD`, `WS_ORIGIN`(운영 FE 주소) | L1(계정 하나), smoke |
| `notification-accounts.json` | `[{"email": "...", "password": "..."}]`, 유저 역할 시험 계정 10개 | L2·L6 |

시험 계정 10개는 알림이 적거나 없어도 된다(조회·연결만 한다). 정지·탈퇴 계정은 쓰지 않는다.

### 실행

레포 루트에서 Git Bash로 실행한다. 비밀 값은 명령줄에 쓰지 않는다. 각 실행의 시작·끝 시각(KST)을 적는다.

```bash
set -a; . ~/.hapbang/load-tests/notification.env; set +a
export MSYS_NO_PATHCONV=1
OUT="$HOME/.hapbang/load-tests/runs"; mkdir -p "$OUT" && chmod 700 "$OUT"; SECRETS="$(cd ~/.hapbang/load-tests && pwd -W)"
k6run() { docker run --rm -v "$(pwd -W)/loadtest/k6:/scripts:ro" -v "$SECRETS:/secrets:ro" -v "$(cd "$OUT" && pwd -W):/out" \
  -e BASE_URL -e LT_EMAIL -e LT_PASSWORD -e WS_ORIGIN grafana/k6:1.0.0 run "$@"; }

# L1: 초당 100 요청 10분(GET만)
k6run --env PROFILE=load --summary-export /out/k6-notification-l1.json /scripts/notification/rest.js
# L2: 연결 50 → 100 → 200, 13분(ND-198). 단계 4분 20초에는 연결을 나눠 여는 시간(50·100 단계 30초, 200 단계 90초)이 들어 있어
#     200연결이 모두 열린 채 유지되는 시간은 약 2분 50초다. L1이 끝나고 지표가 가라앉은 뒤(5분 이상)
k6run --env PROFILE=load --env LT_ACCOUNTS_FILE=/secrets/notification-accounts.json \
  --summary-export /out/k6-notification-l2.json /scripts/notification/stomp.js
```

L6은 챗봇 담당이 요청 경로·비율(`CHATBOT_GET_RPS`, `CHATBOT_LLM_RPS`)을 확인한 뒤에만 실행한다(ND-187). 확인 표시가 없으면 스크립트가 멈춘다.

```bash
# 챗봇 단독(비교 기준) → 혼합 순서. 사이에 5분 이상 쉰다
k6run --env CHATBOT_CONFIRMED=yes --env CHATBOT_ONLY=1 --env CHATBOT_GET_RPS=<확인값> --env CHATBOT_LLM_RPS=<확인값> \
  --env LT_ACCOUNTS_FILE=/secrets/notification-accounts.json --summary-export /out/k6-notification-l6-chatbot.json /scripts/notification/l6.js
k6run --env CHATBOT_CONFIRMED=yes --env CHATBOT_GET_RPS=<확인값> --env CHATBOT_LLM_RPS=<확인값> \
  --env LT_ACCOUNTS_FILE=/secrets/notification-accounts.json --summary-export /out/k6-notification-l6.json /scripts/notification/l6.js
```

요약은 허용 목록 검사를 거쳐서만 레포에 둔다(`setup_data`의 access token을 뺀다).

```bash
python -I loadtest/k6/notification/sanitize_summary.py "$OUT/k6-notification-l1.json" docs/load-test/results/notification-l1.json
```

### 판정과 기록

| 시험 | k6가 판정하는 것(종료 코드 0이면 통과) | 지표로 따로 보는 것(README "Prometheus 구간 값", 앱 이름 `auxiliary-app`) |
| --- | --- | --- |
| L1 | p95 500ms 이하(목록·안 읽은 수 각각), 5xx 1% 미만, 보내지 못한 요청 60건 미만 | Hikari 연결 대기 시간 초과 0(`hikaricp_connections_timeout_total` 증가 0), 메모리·CPU·GC |
| L2 | 계획 전에 닫힌 연결 0(비정상 끊김·연결 만료, 서버가 1000으로 먼저 닫아도 셈). 시험 성립 조건: 연결 200개, 티켓 실패 0, 계정 수가 티켓 한도에 충분(모자라면 시작하지 않음). 연결 시간은 기록만 | OOMKilled 0, 메모리 최대가 limit의 85% 이하(`container_memory_working_set_bytes` / `container_spec_memory_limit_bytes`) |
| L6 | 알림 쪽 L1·L2 기준 | 챗봇 p95의 단독 대비 악화율(기록), 메모리 |

- 결과는 이 문서 "결과"에 시험별 절을 더해 적는다(README "결과 문서 양식"). CPU credit 전후 값도 적는다.
- 불합격이면 기준을 바꾸지 않고 원인(자원, credit, 앱)을 적어 보고한다.

## U13·NT-RES 전달문 초안

알림 담당이 인프라·챗봇 담당에게 보낼 글의 초안이다. 수치의 원본은 이 문서 "결과"이고, 확정은 받는 담당이 한다(ND-157·ND-187). 운영 L1·L2를 실행한 뒤 그 값을 더해, L6 실행 전에 보낸다(L6 경로·비율 확인 요청이 들어 있다). L6 결과는 실행한 뒤 따로 전한다.

> **알림 N15 부하 시험 결과 공유(U13 자원 예산 확인 요청)**
>
> auxiliary-app(알림 + 챗봇 + 검색, limit 704Mi·`-Xmx256m`, Hikari 5)의 알림 부하를 격리 환경(B0 cluster)에서 쟀습니다. 격리 값이라 운영 용량을 보증하지는 않습니다.
>
> - (L4) 밀린 이벤트 10,000건 회복: 46.7초. 이때 메모리 최대 617MiB(limit의 약 88%), CPU 최대 약 4.5코어(CPU limit 없음), Hikari active 최대 4·대기 0. 실시간 전달 13%를 버렸습니다(저장은 정상, FE가 REST로 맞춤).
> - (L5, NT-RES-02) 예약 알림 1,000건이 한 시각에 몰림: 10회차(약 10분)에 모두 보냄. 회차 실행 최대 0.5초, 건너뛴 회차 0. 챗봇 배치와 겹친 측정은 아닙니다.
> - (L3, NT-RES-03) STOMP 100연결 + 초당 20건 저장·전달: p95 9.8ms, 유실 0. 메모리 최대 625MiB(약 89%), CPU 최대 약 1.3코어, Hikari 대기 0.
> - 운영 L1(REST 초당 100)·L2(STOMP 200연결): (운영 L1·L2 실행 뒤 채움. L6 값은 넣지 않는다)
>
> 확인 요청:
> 1. (인프라) 메모리 최대가 limit의 85~89%였습니다. OOMKilled는 없었지만 여유가 작습니다. limit을 지금대로 둘지, 늘릴지 의견 부탁드립니다.
> 2. (챗봇) 같은 pod에서 챗봇 요청이 함께 들어오면 여유가 더 작아집니다. L6(알림 + 챗봇 혼합)에 쓸 챗봇 요청 경로와 초당 비율(LLM 없는 GET, LLM 대화)을 정해 주세요.
> 3. (공통) 위 값으로 U13 예산(Hikari 5, limit 704Mi·`-Xmx256m`)을 확정해도 되는지 확인 부탁드립니다.

## N17 개선 후보

판정과 별개로 측정에서 나온 개선 후보다. 고칠지는 N17에서 정한다.

- 실시간 전달 실행기(스레드 1개, 줄 상한)가 백로그 회복 중 초당 200건 넘는 저장을 따라가지 못해 13%를 버렸다(L4).
- 격리 시험의 메모리 최대가 limit의 85~89%였다(L3·L4).
- 레코드마다 커밋해 처리량이 디스크 동기화 속도에 묶이는 구조(N13 진단, [B0 기준선](notification-baseline.md)).
