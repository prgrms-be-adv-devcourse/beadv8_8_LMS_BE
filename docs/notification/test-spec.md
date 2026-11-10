# 알림(notification) 모듈 — 테스트 검증 기준

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> 문서 지도: [모듈](module.md) · [결정 기록](decisions.md) · [알림 종류](notification-catalog.md) · [API 계약](../api/notification.md) · **[테스트 검증 기준](test-spec.md)** · [회귀 실행 기록](regression-log.md) · [진행](../../apps/auxiliary-app/boundedContext/notification/PROGRESS.md)
>
> - **역할**: 이 문서는 알림센터(auxiliary-app 알림 모듈)가 **무엇을 만족해야 하는가**(요구사항, 합격 기준, 근거 결정)를 `NT-*` ID로 정리한다. 통과·실패, 실행 날짜, 실행 건수는 적지 않는다. **실제로 실행한 결과는 [regression-log.md](regression-log.md)에만 적는다.**
> - **현재 상태(2026-11-09, N1 #797)**: 모듈 뼈대(회원 복제본, Kafka 소비·DLT 설정) 테스트가 생겼다. 연결된 항목은 4장 "N1 연결 테스트"에 있고, 나머지 항목은 "테스트 없음"이다. "유효"는 합격 기준이 결정과 맞는다는 뜻이지 구현·통과했다는 뜻이 아니다. 실행 결과는 [regression-log.md](regression-log.md)에만 있다.
> - 근거: 결정은 [decisions.md](decisions.md)의 `ND-*`·`U*`, 설계 원본은 [알림 설계](../architecture/infrastructure/notification.md)(이하 `N`, 장 번호로 적는다), Kafka 규칙은 [kafka.md](../architecture/infrastructure/kafka.md), 모듈 경계·자원은 [auxiliary-app README](../../apps/auxiliary-app/README.md)다. 이 문서는 결정을 새로 만들지 않는다.
> - 테스트 방식(단위·통합 기준, 테스트용 DB)은 팀 합의 전이다(루트 AGENTS 11장). "테스트 종류" 칸은 **제안**이다.

## 1. 표 읽는 법

### 1.1 칸

| 칸 | 적는 것 |
| --- | --- |
| 테스트 ID | `NT-<영역>-NN`. 한 번 붙인 ID는 바꾸거나 재사용하지 않는다. 대상이 없어지면 행을 지우지 않고 명세 상태를 바꾼다 |
| 대상 기능 | 검증할 기능 한 줄 |
| 선행 조건 | 테스트 전에 있어야 하는 데이터·상태. 아직 없는 발행 이벤트가 필요하면 N 8장 작업 ID(M1~M8)를 적는다 |
| 입력 | 소비할 이벤트(토픽·타입), 호출할 API, 배치 실행 |
| 예상 결과(합격 기준) | 테스트가 확인해야 하는 결과. 이것을 모두 만족해야 합격이다 |
| 예외·경계 조건 | 함께 확인할 경계, 그리고 결정이 없어 기준에 넣지 못한 점 |
| 관련 ND | 근거 결정. 미결이면 `U*`도 적는다 |
| 관련 계약 | API(→ [API 계약](../api/notification.md)), Kafka 토픽·이벤트, 종류 코드(→ [알림 종류](notification-catalog.md)) |
| 테스트 종류(제안) | 아래 1.2 |
| 명세 상태 | 아래 1.3. 실행 결과가 아니다 |

- 종류별 문구는 그 종류를 소비하는 작업에서 정하고(ND-050) 실제 문구가 미결(U3)이라 기준에 넣지 않는다. 생성 항목은 "문구가 비어 있지 않다"까지만 본다(문구 내용은 NT-GEN-10).
- 종류 코드는 [알림 종류](notification-catalog.md)의 코드를 기대값으로 쓴다. ND-012 규칙 밖의 코드 이름(`PAYOUT_COMPLETED_DAILY`, 역할 비교 승급 승인, 예약 5종)은 U11이 정해지면 바뀔 수 있다(NT-GEN-12). `GROUP_*`는 ND-043으로 정해졌다. 이름이 바뀌어도 다른 항목의 합격 기준은 그대로다.
- consumer group 이름은 ND-047(`auxiliary-app.notification-<목적>`)이다. 그룹 이름을 단언하는 항목은 NT-KAF-06, NT-ERR-03이고, 나머지 소비 항목은 그룹 이름을 기준에 넣지 않는다.

### 1.2 테스트 종류 (제안)

| 표기 | 뜻 |
| --- | --- |
| Unit | 스프링 없이 도메인·계산 (예: KST 날짜, 예약 시각 계산) |
| Integration(PG) | Testcontainers PostgreSQL, 앱 컨텍스트 |
| Integration(PG+Kafka) | PostgreSQL + Kafka Testcontainers. 소비·재시도·DLT |
| Integration(PG+Redis) | PostgreSQL + Redis Testcontainers, STOMP 클라이언트 |
| API | Integration(PG) 안에서 MockMvc 등으로 REST 호출 |
| Architecture | Spring Modulith `ApplicationModules.verify()`, 정적 검사 |
| Build | Gradle 태스크(`verifyModuleStructure`) |
| E2E | 로컬 k3d(`k3d-hapbang`)에 여러 앱을 띄워 확인 |
| 부하 | 자원 사용량 측정 |

### 1.3 명세 상태

| 값 | 뜻 |
| --- | --- |
| 유효 | 확정된 결정(ND)에 맞는 합격 기준이다. 구현·실행 여부와 무관하다 |
| 미결 (Uxx) | 합격 기준이 decisions.md 10장의 미결 질문에 달려 있다. 결정되면 기준을 고치고 "유효"로 바꾼다 |
| 미결 (결정 없음) | 필요한 동작을 정한 결정도, 등록된 미결 질문도 없다. decisions.md 10장에 새 U로 올릴 후보다. 예상 결과 칸은 "제안"이다. 2026-11-06 U16·U17 등록으로 해당 항목 없음 |
| 폐기 | 검증 대상이 없어졌다. 행은 남긴다 |
| 대체됨 → NT-… | 다른 NT가 이 기준을 이어받았다 |

지금 폐기·대체됨 항목은 없다.

## 2. 검증 대상 (`NT-*`)

### 2.1 GEN — 알림 생성·발생 조건

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-GEN-01 | 충전 완료 알림 (일반 유저 포함) | `notification_user`에 역할 유저(일반 유저)·정상 상태 회원 A | `billing.events.v1` `ChargeCompletedEvent`(충전한 회원 A) | A에게 알림 1건. 종류 `CHARGE_COMPLETED`, 받는 사람 A, 원본 이벤트 종류·원본 대상 ID 채움, 값(`jsonb`, `v`) 저장, 발생 시각 = 이벤트 업무 시각, 읽은 시각 없음, 문구 비어 있지 않음. A의 안 읽은 수 +1 | 인플루언서·인증된 인플루언서도 같은 결과 | ND-001, ND-003, ND-008, ND-011, ND-020 | Kafka `billing.events.v1`, 카탈로그 `CHARGE_COMPLETED` | Integration(PG+Kafka) | 유효 |
| NT-GEN-02 | 구매 완료 알림 (일반 유저) | 일반 유저 구매자 B. **M4**(`LectureOrderPaidEvent` 발행) 필요 | `lecture.events.v1` `LectureOrderPaidEvent` | B에게 종류 `LECTURE_ORDER_PAID` 알림. 항목별 건수(주문당·항목당)는 카탈로그를 따른다 | M4 전에는 실행할 수 없다. 11장 완료 증거에 넣을지는 U2(3장) | ND-003, ND-011 | Kafka `lecture.events.v1`, 카탈로그 `LECTURE_ORDER_PAID` | Integration(PG+Kafka) | 유효 |
| NT-GEN-03 | 출연 원천 지급 알림 | 게스트 회원 | `PayoutCompletedEvent` `sourceType` `APPEARANCE_REQUEST`·`RECRUIT_APPEARANCE`, `payoutType` `NORMAL`·`PENALTY` | 지급마다 `PAYOUT_COMPLETED` 1건(묶지 않는다). 같은 이벤트로 사건 알림이 따로 있어도 지급 알림은 별개로 생긴다 | `LECTURE_ORDER_ITEM` 원천은 이 종류로 만들지 않는다(→ BUN) | ND-013 | Kafka `billing.events.v1`, 카탈로그 `PAYOUT_COMPLETED` | Integration(PG+Kafka) | 유효 |
| NT-GEN-04 | 조건부 발송 | M3·M4 | `AppearanceRecruitClosedEvent`(`closeType`), `AppearanceConfirmedEvent`(`confirmType`), `LectureOrderItemConfirmedEvent`(`confirmationType`) | `DEADLINE`·`AUTO`일 때만 알림 1건. 조기 마감·직접 확인·직접 구매 확정은 알림 0이고 offset은 커밋(DLT 아님) | — | ND-011 | 카탈로그 `APPEARANCE_RECRUIT_CLOSED`, `APPEARANCE_CONFIRMED`, `LECTURE_ORDER_ITEM_CONFIRMED` | Integration(PG+Kafka) | 유효 |
| NT-GEN-05 | 받는 사람이 여럿인 알림 | 호스트·게스트, 구매자·판매자 복제본. M2·M3·M4 | `AppearanceRequestExpiredEvent`, `LectureObjectionFiledEvent`, `LectureObjectionResolvedEvent` | 받는 사람마다 1건씩(만료: 호스트·게스트, 이의 제기: 구매자·판매자). `accepted`에 따라 값이 달라진다 | 받는 사람 중 한 명이 탈퇴면 나머지만(NT-USR-03) | ND-011, ND-015 | 카탈로그 `APPEARANCE_REQUEST_EXPIRED`, `LECTURE_OBJECTION_FILED`, `LECTURE_OBJECTION_RESOLVED` | Integration(PG+Kafka) | 유효 |
| NT-GEN-06 | 역할 비교로 만드는 승인 알림 | 복제본 역할 유저(또는 인플루언서), 버전 n | `member.events.v1` `UserModifiedEvent` 버전 n+1 | 인플루언서 → 인증된 인플루언서: 승급 승인 1건. 유저 → 인플루언서 역할 변경만으로는 채널 인증 승인 알림을 만들지 않는다(ND-042, NT-GEN-14). 역할이 그대로인 수정은 알림 0. 비교 기준은 반영 전 복제본의 역할 | 자동·관리자 승인을 구분하지 않고 같은 종류·같은 값. 늦은 버전 이벤트는 비교하지 않는다(NT-USR-02). 승급 승인 코드 이름은 U11 | ND-016, ND-018, ND-042 | Kafka `member.events.v1`, 카탈로그 승급 승인 | Integration(PG+Kafka) | 유효 |
| NT-GEN-07 | 보내지 않는 것 | 관리자 회원, 일반 회원 | 관리자 `UserJoinedEvent`, 같은 토픽의 알림 대상 아닌 이벤트(예: `AppearanceReviewPublishedEvent`, `AppearanceChangedEvent`), 정지·탈퇴·로그아웃 이벤트 | 알림 0, 예약 0. offset 커밋, DLT 0. 관리자는 어떤 알림도 받지 않는다 | 카탈로그의 "보내지 않는 것" 목록 전체를 대상으로 한다 | ND-002, ND-003, ND-011 | 카탈로그 "보내지 않는 것" | Integration(PG+Kafka) | 유효 |
| NT-GEN-08 | 단체방 알림 3종의 받는 사람 | M8(`chat.events.v1`) | `GroupMembersInvitedEvent`, `GroupMemberKickedEvent`, `GroupRoomClosedEvent` | 초대: 이번 초대로 실제 참여한 회원마다 1건. 강퇴: 강퇴된 회원 1건. 폐쇄: 남은 참여자마다 1건, 폐쇄한 호스트 제외 | 종류 코드는 채팅 안내와 이름이 같지만 뜻·받는 사람이 다르다(ND-043) | ND-011, ND-043 | Kafka `chat.events.v1`, 카탈로그 단체방 3종 | Integration(PG+Kafka) | 유효 |
| NT-GEN-09 | 이의 제기 접수 한 번만 | M4, M7 | 바로 접수된 `LectureObjectionFiledEvent` / 재시도 배치 접수의 `LectureObjectionFiledEvent` / `LectureObjectionFileRejectedEvent` | 접수는 경로와 무관하게 이벤트 하나로 구매자·판매자 각 1건. 접수 불가는 재시도 배치가 끝낼 때만 구매자 1건 | 바로 거절된 접수는 알림 0(이벤트가 없다) | ND-015 | 카탈로그 `LECTURE_OBJECTION_FILED`, `LECTURE_OBJECTION_FILE_REJECTED` | Integration(PG+Kafka) | 유효 |
| NT-GEN-10 | 종류별 문구 | 그 종류를 소비하는 작업에서 문구 확정(ND-050) | 종류마다 대표 이벤트 | 저장된 문구가 확정된 문구와 같다 | 실제 문구가 없다 | ND-020, ND-039, ND-050, **U3** | 각 소비 작업의 문구 코드 | Unit | 미결 (U3) |
| NT-GEN-11 | 지급 알림의 출연·강의 제목 | M5(`PayoutCompletedEvent.sourceTitle`, ND-040) | `PayoutCompletedEvent` | 지급 알림(`PAYOUT_COMPLETED`, 하루 묶음)에 이벤트의 `sourceTitle`이 제목으로 들어 있다 | 지금 이벤트에 `sourceTitle`이 없다(M5 전에는 실행할 수 없다) | ND-014, ND-040 | Kafka `billing.events.v1` | Integration(PG+Kafka) | 유효 |
| NT-GEN-12 | 규칙 밖 종류 코드 이름 | 이름 확정 | 해당 알림 생성 | 저장된 `kind`가 확정된 이름이다(`PAYOUT_COMPLETED_DAILY`, 승급 승인, 예약 5종) | `GROUP_*`는 ND-043으로, 채널 인증 승인은 ND-042의 새 이벤트 이름으로 규칙 안에 든다(NT-GEN-08) | ND-012, **U11** | 카탈로그 | Unit | 미결 (U11) |
| NT-GEN-13 | 값 저장과 문구 재생성 | 아무 알림 | 알림 생성 | 값(`jsonb`)에 형식 버전 `v`가 있고, 저장된 값만으로 같은 문구를 다시 만들 수 있다 | — | ND-020, ND-025 | 테이블 `notification` | Unit, Integration(PG) | 유효 |
| NT-GEN-14 | 채널 인증 승인 알림의 회원 코드 불일치 경우 | M6(`InfluencerChannelApprovedEvent`, ND-042). 복제본 역할 유저 또는 인플루언서 | (1) 관리자 인플루언서 지정(#724)으로 나는 `UserModifiedEvent`(유저 → 인플루언서) (2) 이미 인플루언서인 회원의 `InfluencerChannelApprovedEvent` | (1) 채널 인증 승인 알림을 만들지 않는다 (2) 채널 인증 승인 알림 1건 | (1) 역할 비교(ND-016)로는 채널 인증 승인과 구분되지 않았다 (2) 역할이 바뀌지 않아 `UserModifiedEvent`가 나가지 않았다. 승급 승인(인플루언서 → 인증된 인플루언서)은 해당 없음(NT-GEN-06) | ND-016, ND-042 | Kafka `member.events.v1`(`InfluencerChannelApprovedEvent`), 카탈로그 채널 인증 승인 | Integration(PG+Kafka) | 유효 |

### 2.2 KAF — 이벤트 소비

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-KAF-01 | 토픽별 받는 타입 처리 | 7개 토픽, 받는 사람 복제본 | 토픽마다 받는 타입의 대표 이벤트 1건(`member`·`appearance`·`lecture`·`billing`·`customersupport`·`chat` `.events.v1`) | 각 이벤트가 카탈로그대로 알림(또는 복제본·예약)이 된다. 클래스 레벨 `@KafkaListener` + 타입별 `@KafkaHandler`. 처리 커밋 뒤 offset 커밋 | 아직 발행되지 않는 타입은 해당 M 뒤에 더한다 | ND-008, ND-010 | Kafka 토픽 표(kafka.md) | Integration(PG+Kafka) | 유효 |
| NT-KAF-02 | 모르는 이벤트 타입 무시·커밋 | — | 같은 토픽의 받지 않는 타입(예: 강의 AI 추천 동기화 이벤트 4종), 처음 보는 `eventType` | 알림 0, 예외 없음, offset 커밋, DLT 0, 뒤 메시지는 정상 처리 | native 이미지로 빌드하면 `eventType` 헤더 방식(#722)도 확인 | ND-010 | kafka.md "소비" | Integration(PG+Kafka) | 유효 |
| NT-KAF-03 | 앱 중단 뒤 재시작과 lag 감소 | 보존 72시간 안 | 소비자를 멈춘 채 이벤트 N건 발행 → 재시작 | 재시작 후 마지막 커밋 offset부터 읽어 lag가 0까지 줄고, N건이 모두 알림이 된다(중복 없이) | 멈춘 동안 같은 이벤트가 두 번 쌓여도 1건 | ND-008, ND-019 | kafka.md "장애"(consumer 중단) | Integration(PG+Kafka), E2E | 유효 |
| NT-KAF-04 | 보존 밖·첫 배포의 과거 이벤트 | 새 consumer group(`auto-offset-reset=earliest`) | 첫 기동 때 토픽에 남은 72시간 안의 과거 이벤트, 72시간 넘게 멈춘 뒤 재시작 | 과거 이벤트(가입 환영 등)를 알림으로 만들지 여부가 결정과 같다. 72시간이 지나 사라진 이벤트를 어떻게 다루는지가 결정과 같다 | 지금 설정대로면 과거 이벤트가 알림이 된다. 의도인지 정하지 않았다 | **U7** | kafka.md "소비"·보존 72시간 | Integration(PG+Kafka), E2E | 미결 (U7) |
| NT-KAF-05 | 채팅 내부 Stream을 구독하지 않음 | — | 알림 모듈 소비 설정 | 알림 모듈 입력은 Kafka 토픽뿐이다. Redis Streams 소비자·그룹이 없고 채팅 Stream 그룹에 알림 worker가 없다 | — | ND-009 | — | Architecture(정적 검사) | 유효 |
| NT-KAF-06 | consumer group 이름 | — | 소비자 기동 | 그룹 이름이 `auxiliary-app.notification-<목적>`이고(회원 복제본은 `auxiliary-app.notification-member-replica`), 목적마다 그룹이 따로 있다(회원 복제본·회원 알림·출연·강의·지갑·고객센터·채팅) | — | ND-047 | kafka.md 토픽 표 | Integration(PG+Kafka) | 유효 |

### 2.3 IDEM — 중복

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-IDEM-01 | 같은 이벤트 두 번 소비 | 회원 A | 같은 integration event(같은 key·값)를 두 번 발행 | 알림 1건, 안 읽은 수 1. 두 번째 소비는 예외 없이 커밋되고 DLT로 가지 않는다 | 두 번째 소비에서 실시간 프레임을 다시 보낼지는 정하지 않았다(기준에 넣지 않는다) | ND-019 | 유일 인덱스 (원본 이벤트 종류, 원본 대상 ID, 받는 사람) | Integration(PG+Kafka) | 유효 |
| NT-IDEM-02 | 동시 중복 소비 | 회원 A | 같은 이벤트를 동시에 처리(두 스레드, 또는 DLT runbook 재주입과 원본 처리 겹침) | 알림 1건. 유일 인덱스 위반은 중복으로 보고 성공 처리(재시도·DLT 아님) | — | ND-019 | 위와 같음 | Integration(PG) | 유효 |
| NT-IDEM-03 | 받는 사람이 여럿인 이벤트의 재소비 | 구매자·판매자 | `LectureObjectionFiledEvent` 두 번 | 받는 사람마다 1건(총 2건). 한 사람 행이 있어도 다른 사람 행은 생긴다 | — | ND-015, ND-019 | 위와 같음 | Integration(PG+Kafka) | 유효 |
| NT-IDEM-04 | 같은 대상의 다른 이벤트 | 환전 1건 | 같은 환전의 `ExchangeRejectedEvent`·`ExchangeFinalFailedEvent` 등 종류가 다른 이벤트 | 이벤트 종류마다 1건씩 생긴다(원본 이벤트 종류가 유일 키에 있다) | 같은 종류가 한 대상에서 여러 번 나는 경우는 NT-IDEM-05 | ND-019 | 위와 같음 | Integration(PG) | 유효 |
| NT-IDEM-05 | 한 대상에서 여러 번 나는 이벤트 | 환전 1건 | 같은 환전의 `ExchangePayoutFailedEvent` 두 번(송금 시도가 다름) | 실패마다 알림이 하나씩 생긴다(사건마다 다른 원본 대상 ID, ND-033) | 환전 ID를 쓰면 두 번째 실패가 유일 인덱스에 막힌다. 이 이벤트가 송금 시도처럼 실패마다 다른 ID를 주는지는 계약 확인 전이다 | ND-019, ND-033, **U5** | `ExchangePayoutFailedEvent` | Integration(PG+Kafka) | 미결 (U5) |

### 2.4 QRY — 조회·접근

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-QRY-01 | 회원별 목록 | A의 알림 25건 생성(5건은 Soft Delete) | A로 `GET /api/v1/notifications` | 삭제되지 않은 최신 20건만, 발생 시각 내림차순, `RsData`로 감싼다. cursor·페이지 파라미터가 없다 | 같은 발생 시각의 순서는 정하지 않았다(구현에서 정해 API 계약에 적는다) | ND-021, ND-022, ND-026 | `GET /api/v1/notifications` | API | 유효 |
| NT-QRY-02 | 정렬은 발생 시각 | 업무 시각 t1 < t2 | t2 이벤트(예: `ExchangeCompletedEvent`)를 먼저, t1 이벤트를 나중에 소비 | 목록이 t2, t1 순이다(받은 순서가 아니다) | 환전 이벤트 순서 바뀜(지갑 CONTEXT 9장), Modulith 전송 순서 바뀜 | ND-021 | 위와 같음 | Integration(PG+Kafka) | 유효 |
| NT-QRY-03 | 다른 회원 알림 조회 차단 | A·B 각자 알림 | B로 목록·안 읽은 수 조회, 비로그인 조회 | B 응답에 A의 알림이 없고 안 읽은 수도 B 것만 센다. 비로그인은 401(`ProblemDetail`). 관리자가 부르면 403 | — | ND-003, ND-026, ND-052 | `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count` | API | 유효 |
| NT-QRY-04 | 대상이 삭제돼도 알림 유지 | 강의·문의 등 대상에 대한 알림 | 대상 삭제(발행 모듈 쪽 삭제·삭제 이벤트) | 알림 행·이동 대상(종류·ID)이 그대로이고 목록에 나온다. 알림 모듈에 대상 삭제로 알림을 지우는 경로가 없다 | "삭제된 ○○" 화면은 이동한 대상 화면(대상 모듈·FE)의 몫이라 이 항목 밖이다 | ND-024 | `GET /api/v1/notifications` | Integration(PG) | 유효 |

### 2.5 READ — 읽음

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-READ-01 | 개별 읽음 | A의 안 읽은 알림 n | A로 `PATCH /api/v1/notifications/{id}/read` | 읽은 시각이 채워지고 안 읽은 수 −1 | 이미 읽은 알림을 다시 읽을 때 응답·읽은 시각 유지 여부는 API 계약을 따른다 | ND-023 | `PATCH /api/v1/notifications/{notificationId}/read` | API | 유효 |
| NT-READ-02 | 목록 열기로는 읽지 않음 | A의 안 읽은 알림 3건 | 목록·안 읽은 수 조회를 여러 번 | 읽은 시각이 모두 비어 있고 안 읽은 수 3 그대로 | WebSocket 프레임 수신도 읽음이 아니다 | ND-023 | `GET /api/v1/notifications` | API | 유효 |
| NT-READ-03 | 다른 회원 알림 읽음 차단 | A의 알림 n | B로 n 읽음, 없는 ID·Soft Delete된 ID 읽음 | 셋 모두 404 `NOTIFICATION_NOT_FOUND`로 같은 응답, A의 n은 안 읽음 그대로. 응답으로 다른 회원 알림의 존재를 구분할 수 없다 | — | ND-026, ND-052 | 위와 같음 | API | 유효 |
| NT-READ-04 | 전체 읽음 | A의 안 읽은 알림 여럿, B의 안 읽은 알림 | A로 `PATCH /api/v1/notifications/read-all` | A의 남은 알림이 모두 읽음, A의 안 읽은 수 0. B의 알림은 그대로 | 안 읽은 알림이 0이어도 성공 | ND-023, ND-026 | `PATCH /api/v1/notifications/read-all` | API | 유효 |
| NT-READ-05 | 안 읽은 수 최대 20 | — | A에게 안 읽은 알림 25건 생성 | 안 읽은 수 20. Soft Delete된(밀려난) 안 읽은 알림은 세지 않는다 | 실시간 프레임의 안 읽은 수도 같은 값 | ND-022 | `GET /api/v1/notifications/unread-count`, WebSocket 프레임 | API | 유효 |
| NT-READ-06 | 이미 읽은 알림 다시 읽음 | A의 알림 n을 시각 t1에 읽음 | 같은 n을 다시 읽음 처리 | 200, n의 읽은 시각은 t1 그대로, 안 읽은 수 변화 없음 | 전체 읽음을 두 번 해도 결과가 같다 | ND-023, ND-052 | `PATCH /api/v1/notifications/{notificationId}/read`, `PATCH /api/v1/notifications/read-all` | API | 유효 |

### 2.6 RET — 보존·Soft Delete

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-RET-01 | 21번째 저장 | A의 삭제되지 않은 알림 20건 | 더 최신인 21번째 알림 이벤트 | 같은 트랜잭션에서 발생 시각이 가장 오래된 1건의 삭제 시각이 채워진다(행은 남음). 목록 20건, 가장 오래된 것이 빠짐 | 다른 회원의 알림은 영향 없음. 저장이 롤백되면 Soft Delete도 롤백 | ND-022 | 테이블 `notification` | Integration(PG) | 유효 |
| NT-RET-02 | 밀려난 알림의 이벤트 재수신 | NT-RET-01 뒤 | 밀려난 알림의 원본 이벤트를 다시 소비 | 새 행이 생기지 않고 삭제 시각도 그대로다(되살아나지 않음). 목록·안 읽은 수 변화 없음. 커밋, DLT 아님 | — | ND-019, ND-022 | 유일 인덱스(삭제된 행 포함) | Integration(PG+Kafka) | 유효 |
| NT-RET-03 | 늦게 도착한 오래된 알림 | 더 최신 알림 20건 | 발생 시각이 20건 모두보다 이른 이벤트 | 발생 시각 기준 21번째가 되므로 저장과 같은 트랜잭션에서 그 알림이 Soft Delete된다. 목록·안 읽은 수 변화 없음 | ND-022를 그대로 적용한 결과다. 이때 실시간 프레임을 보낼지는 정하지 않았다(기준에 넣지 않는다) | ND-021, ND-022 | — | Integration(PG) | 유효 |
| NT-RET-04 | Hard Delete 없음 | — | 보존·읽음·대상 삭제 등 모든 경로 | `notification` 계열 테이블에 DELETE SQL을 내지 않는다(삭제 시각만 채운다) | — | ND-022, policy.md 7장 | — | Integration(PG), Architecture | 유효 |
| NT-RET-05 | 같은 회원에게 동시 저장 | A의 삭제되지 않은 알림 20건 | A에게 서로 다른 알림 둘을 동시에 저장 | 둘 다 저장되고 A의 삭제되지 않은 알림은 정확히 20건(가장 오래된 둘이 Soft Delete). 받는 사람별로 줄을 서므로 정리가 빠지지 않는다. 읽음·전체 읽음이 보존 정리와 겹쳐도 밀려난 알림의 삭제 시각이 되돌아가지 않고(보이는 알림 20건 유지), 전체 읽음이 읽음으로 센 알림의 읽은 시각이 지워지지 않는다 | 다른 회원 B의 동시 저장은 기다리지 않는다. 겹침은 실행 순서를 강제해 본다 | ND-022, ND-053 | 테이블 `notification`, `notification_user` 행 락 | Integration(PG) | 유효 |

### 2.7 BUN — 강의 지급 하루 묶음

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-BUN-01 | 판매자·날짜별 묶음 | 판매자 S·T | 같은 KST 날짜의 `PayoutCompletedEvent`(`LECTURE_ORDER_ITEM`) S 3건, T 1건 | S에 묶음 알림 1건(금액 합, 건수 3), T에 1건(건수 1) | 묶음 유일 키 (종류, 받는 사람, 묶음 키) | ND-013, ND-032 | Kafka `billing.events.v1`, 카탈로그 강의 지급 하루 묶음 | Integration(PG+Kafka) | 유효 |
| NT-BUN-02 | 같은 `settlementId` 중복 합산 방지 | S의 묶음 | 같은 `settlementId` 지급을 두 번(순차·동시) | 금액·건수는 한 번만 더해지고 `notification_payout_item`에 그 지급 ID가 1행 | 동시 소비도 합산 1번 | ND-032 | 테이블 `notification_payout_item`(지급 ID 유일) | Integration(PG) | 유효 |
| NT-BUN-03 | KST 날짜 경계(`paidAt`) | 판매자 S | `paidAt` 2026-11-06T14:59:59Z(KST 23:59:59)와 2026-11-06T15:00:00Z(KST 11-07 00:00) | 묶음 2개, 묶음 키는 각각 KST 11-06·11-07. JVM 시간대(UTC·KST)와 무관하게 같은 결과 | — | ND-032 | `PayoutCompletedEvent.paidAt` | Unit, Integration(PG) | 유효 |
| NT-BUN-04 | 묶음 갱신 | S의 같은 날짜 묶음(이미 읽음), 다른 알림들 | 같은 날짜 새 지급 1건 | 금액·건수 증가, 다시 안 읽음(안 읽은 수 +1), 발생 시각을 새로 고쳐 목록 맨 위, 갱신된 알림 한 건과 안 읽은 수를 실시간으로 보낸다 | 새 발생 시각이 새 지급의 `paidAt`인지 처리 시각인지 원문에 없다(맨 위로 오는지만 기준) | ND-021, ND-032, ND-027 | `/user/queue/notifications` | Integration(PG+Redis) | 유효 |
| NT-BUN-05 | Soft Delete된 묶음에 같은 날 지급 | S의 날짜 묶음이 20개 보존에서 밀려남 | 같은 날짜 새 지급 | 되살리거나 무시하는 결정대로 동작한다 | 유일 키 (종류, 받는 사람, 묶음 키)에 걸린다 | ND-022, ND-032, **U9** | — | Integration(PG) | 미결 (U9) |

### 2.8 SCH — 예약 알림

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-SCH-01 | 출연 요청 만료 임박 예약 | 게스트 복제본 | `AppearanceRequestSentEvent` | 게스트에게 `APPEARANCE_REQUEST_EXPIRING` 예약 1건, 보낼 시각 = 수락 기한 − 12시간, 상태 예약 | 이미 보낼 시각이 지난 예약을 만들지는 정하지 않았다(만들면 다음 배치에서 바로 나간다) | ND-029 | Kafka `appearance.events.v1`, 테이블 `notification_schedule` | Integration(PG+Kafka) | 유효 |
| NT-SCH-02 | 방송 임박·출연 확인 부탁 예약 | M3 | 요청 수락 / 지원 수락 이벤트 | `APPEARANCE_STARTING` 호스트·게스트 각 1건(방송 예정 일시 − 24시간), `APPEARANCE_CONFIRM_PERIOD_ENDING` 호스트 1건(방송 예정 일시 + 4×24시간 = 자동 확인 시각 − 3×24시간) | 출연은 시각 기준 | ND-029, ND-030 | 카탈로그 예약 종류 | Unit, Integration(PG+Kafka) | 유효 |
| NT-SCH-03 | 모집 마감 임박 예약 | M3 | `AppearanceRecruitOpenedEvent` | 호스트에게 `APPEARANCE_RECRUIT_CLOSING` 1건, 모집 마감 일시 − 24시간 | — | ND-029 | 카탈로그 | Integration(PG+Kafka) | 유효 |
| NT-SCH-04 | 구매 취소 마감 임박 예약 | M4 | `LectureOrderPaidEvent`(주문 항목 2개) | 항목마다 구매자에게 `LECTURE_ORDER_ITEM_CONFIRMING` 1건, (구매일 + 7일) 09:00 KST. 구매일은 KST 날짜 | 구매 시각 KST 23:59:59와 00:00:00 경계. 09:00과 운영 시간 충돌은 NT-SCH-10 | ND-029, ND-031 | 카탈로그 | Unit, Integration(PG+Kafka) | 유효 |
| NT-SCH-05 | 취소 이벤트로 예약 취소 | 예약 종류별 예약 | N 4장 표의 취소 이벤트(요청 수락·거절·응답 전 취소, 모집 마감, 합의 취소, 호스트 일방 취소, 출연 확인, 불참, 구매 취소, 구매 확정) | 해당 예약 상태가 취소, 배치가 보내지 않는다. 다른 받는 사람·다른 대상의 예약은 그대로 | 모집 전체 취소 이벤트는 NT-SCH-11 | ND-029 | 카탈로그 예약 취소 이벤트 | Integration(PG+Kafka) | 유효 |
| NT-SCH-06 | 취소 이벤트가 예약 생성보다 먼저 도착 | 예약 없음. 생성·취소 이벤트가 같은 (대상 ID, 받는 사람)을 주는 예약 알림(예약 알림별 계약 검증은 U16) | 취소 이벤트를 먼저, 생성 이벤트를 나중에 소비 | 취소 상태 예약 행이 먼저 남고, 늦게 온 생성 이벤트는 유일 키에 걸려 예약을 만들지 않는다. 예약 알림이 나가지 않는다 | 다른 key·Modulith 전송으로 순서가 바뀔 수 있다(kafka.md) | ND-029, ND-034, **U16** | `notification_schedule` 유일 (종류, 대상 ID, 받는 사람) | Integration(PG+Kafka) | 미결 (U16) |
| NT-SCH-07 | 보내기 직전 상태 재확인 | 보낼 시각이 지난 예약 | 배치가 대상을 읽은 뒤 보내기 전에 취소가 커밋 | 취소된 예약은 보내지 않는다. 이미 보낸 예약은 다시 보내지 않는다(알림 중복 없음) | 예약 뒤 받는 사람이 탈퇴한 경우는 정하지 않았다(NT-USR-03 참고) | ND-029 | — | Integration(PG) | 유효 |
| NT-SCH-08 | 1분 배치 처리 | 보낼 시각 전·후 예약 | 예약 배치 실행 | 보낼 시각이 지난 "예약" 상태만 알림을 만들고 상태를 "보냄"으로 바꾼다. 만든 알림에는 보존(20개)·실시간 규칙이 같이 적용된다. 보낼 시각 전 예약은 그대로 | — | ND-029, ND-022, ND-027 | — | Integration(PG) | 유효 |
| NT-SCH-09 | 예약 중복 방지 | — | 같은 생성 이벤트 두 번 | 예약 1건 | — | ND-019, ND-025 | `notification_schedule` 유일 키 | Integration(PG+Kafka) | 유효 |
| NT-SCH-10 | EC2 운영 시간과 예약 | 운영 시간 정책 확정 | 중지 시간대에 보낼 시각이 있는 예약, 09:00 예약 | 중지 시간 예약·기동 직후 예약을 결정대로 보낸다 | 중지 시간에는 `@Scheduled`가 돌지 않는다 | ND-029, ND-031, **U6** | — | E2E | 미결 (U6) |
| NT-SCH-11 | 모집 전체 취소의 예약 취소 | M3(`AppearanceRecruitCanceledEvent`, ND-041) | `AppearanceRecruitCanceledEvent` | 모집 마감 임박 예약이 취소되고 알림은 만들지 않는다 | 모집 전체 취소 자체의 알림 종류는 없다(ND-017) | ND-017, ND-041 | `appearance.events.v1` | Integration(PG+Kafka) | 유효 |

### 2.9 USR — 회원 복제본·탈퇴

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-USR-01 | 복제본 생성·갱신 | — | `UserJoinedEvent`, `UserModifiedEvent` | `notification_user`에 회원 ID·닉네임·역할·상태·버전이 생기고 변경 이벤트로 갱신된다. 복제본이 없을 때 온 변경 이벤트는 그 정보로 복제본을 만들고(DLT 아님), 그 뒤 늦게 온 가입은 건너뛴다(ND-049) | 같은 이벤트 두 번은 결과 같음 | ND-018, ND-049 | Kafka `member.events.v1`, 테이블 `notification_user` | Integration(PG+Kafka) | 유효 |
| NT-USR-02 | 복제본 버전 역전 | 복제본 버전 3 | 버전 2 `UserModifiedEvent`(늦게 도착), 버전 3 재수신 | 복제본은 버전 3 그대로, 승인 알림(NT-GEN-06)도 만들지 않는다. 커밋, DLT 아님 | 멱등 키는 (회원 ID, 원본 버전)(kafka.md "소비") | ND-018, ND-016 | `user.version` | Integration(PG+Kafka) | 유효 |
| NT-USR-03 | 탈퇴 회원 제외 | 복제본 상태 탈퇴인 회원 W, 정상 회원 A | W가 받는 사람인 이벤트, W·A가 함께 받는 이벤트, W의 예약 생성 이벤트 | W에게는 알림·예약 0, A에게만 1건. 커밋, DLT 아님 | 예약을 만든 뒤 탈퇴한 경우 보낼 때 다시 확인할지는 정하지 않았다 | ND-018 | `notification_user` 상태 | Integration(PG+Kafka) | 유효 |
| NT-USR-04 | 복제본이 아직 없는 받는 사람 | 받는 사람 복제본 없음(가입 이벤트가 늦게 옴) | 알림 대상 이벤트 | 알림을 만들지 않고 처리가 실패해 블로킹 재시도된다. 재시도 중 복제본이 생기면 알림이 만들어지고, 끝까지 없으면 그 그룹의 DLT로 간다. DLT로 간 메시지를 kafka.md "DLT 재처리 runbook"으로 다시 넣으면 알림이 한 번만 만들어진다(ND-019) | 복제본 없이 만들면 탈퇴 확인(ND-018)을 할 수 없다. 첫 배포 때 기존 회원은 NT-USR-06 | ND-018, ND-035 | `member.events.v1` | Integration(PG+Kafka) | 유효 |
| NT-USR-05 | 정지 회원 | 복제본 상태 정지 | 정지 회원이 받는 사람인 이벤트 | 결정대로 알림을 만들거나 만들지 않는다 | N 6.4 "탈퇴(이후 정지)"가 모호하다 | ND-018, **U8** | `notification_user` 상태 | Integration(PG+Kafka) | 미결 (U8) |
| NT-USR-06 | 첫 배포 때 이미 가입한 회원의 복제본 | U17 결정. Kafka 보존(72시간)보다 먼저 가입해 `UserJoinedEvent`가 토픽에 없는 회원 | 첫 배포 뒤 그 회원이 받는 사람인 알림 대상 이벤트 | U17에서 정한 방법(예: 첫 배포 Backfill)대로 복제본이 있고 알림이 만들어진다 | 가입 이벤트가 없다. 변경 이벤트가 오면 복제본이 생기지만(ND-049) 변경이 오지 않는 회원은 생기지 않는다. 과거 이벤트 처리 U7과 관련 | ND-018, **U17** | `member.events.v1`, 테이블 `notification_user` | Integration(PG), E2E | 미결 (U17) |
| NT-USR-07 | 파기된 회원의 닉네임이 들어간 저장 알림 | 회원 A의 행동으로 회원 B에게 저장된 알림이 있고, 그 문구·값에 A의 닉네임이 들어 있다 | A가 파기되어 `UserModifiedEvent`(닉네임 `탈퇴회원#{회원ID}`)와 `UserMaskedEvent`를 받는다. 그 뒤 B가 목록을 조회한다 | `UserMaskedEvent`를 받아 A가 들어간 저장 문구·값의 닉네임이 바뀌어, B의 목록에 A의 원래 닉네임이 보이지 않는다(ND-036, `policy.md` 7장 "파기 방법") | 복제본 닉네임만으로는 저장한 문구·값이 바뀌지 않는다. ND-051: 언급 회원 ID 칸으로 A가 든 행만 찾아 값 속 닉네임을 `탈퇴회원#{회원ID}`로 바꾸고 문구를 값으로 다시 만든다. 같은 `UserMaskedEvent`를 다시 받아도 결과가 같고, `UserModifiedEvent`와 순서가 바뀌어도 결과가 같다. A가 들지 않은 알림은 바뀌지 않는다. ND-054: 파기가 처리된 뒤 A의 옛 닉네임이 담긴 초안으로 저장해도 파기 닉네임으로 저장되고, A의 복제본이 없을 때 파기와 저장이 동시에 일어나도 옛 닉네임이 남지 않는다. 보존 정리가 그 알림을 밀어내는 저장과 파기가 겹쳐도, 한 알림에 함께 언급된 B·C의 파기가 겹쳐도 파기된 닉네임이 되돌아가지 않는다(실행 순서를 강제해 본다) | ND-018, ND-020, ND-036, ND-051, ND-054 | `member.events.v1`(`UserModifiedEvent`·`UserMaskedEvent`), 테이블 `notification` | Integration(PG+Kafka) | 유효 |

### 2.10 RT — 실시간·재접속

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-RT-01 | 저장 commit 뒤 실시간 도착 | A가 연결 티켓으로 WebSocket 연결, `/user/queue/notifications` 구독 | A에게 가는 이벤트 | A가 프레임 1개를 받는다. 프레임에 알림 한 건과 안 읽은 수가 있다. 프레임은 저장 commit 뒤에 나간다 | — | ND-027 | WebSocket `/api/v1/notifications/ws`, `/user/queue/notifications` | Integration(PG+Redis) | 유효 |
| NT-RT-02 | 롤백되면 보내지 않음 | A 연결 | 저장 트랜잭션을 실패시킴 | 프레임이 오지 않는다 | — | ND-027 | 위와 같음 | Integration(PG+Redis) | 유효 |
| NT-RT-03 | 다른 회원 연결로 가지 않음 | A·B 연결 | A에게 가는 이벤트 | B는 프레임을 받지 않는다 | — | ND-026, ND-027 | 위와 같음 | Integration(PG+Redis) | 유효 |
| NT-RT-04 | 유실 후 REST 재동기화 | A 연결 | Redis 발행 실패·연결 끊김 중 알림 여러 건 생성 → 재접속 후 목록 조회 | 저장·offset 커밋은 실시간 실패와 무관하게 끝난다. 재접속 후 목록이 유실 없이 받은 경우의 목록(DB 최신 20)과 같다 | 실시간 실패가 저장을 되돌리지 않는다는 것은 "DB가 원천"(N 2장)에서 읽은 기준이다 | ND-027 | `GET /api/v1/notifications` | Integration(PG+Redis), E2E | 유효 |
| NT-RT-05 | 연결 티켓 | 로그인 회원 | `POST /api/v1/notifications/ws-tickets` 후 연결, 티켓 없음·만료·재사용 | 유효한 티켓만 연결된다(채팅 `/api/v1/chats/ws-tickets`와 같은 규칙) | — | ND-028 | `POST /api/v1/notifications/ws-tickets` | Integration(PG+Redis) | 유효 |
| NT-RT-06 | STOMP 토큰 만료 헤더 | Gateway·global 필터 설정 확정 | `X-User-Token-Exp`가 붙은 연결, 토큰 만료 | 결정된 방식으로 만료된 연결을 다룬다 | 지금 auxiliary global 필터와 Gateway `claimToHeaders`에 없다 | ND-028, **U15** | Gateway `claimToHeaders` | E2E | 미결 (U15) |
| NT-RT-07 | WebSocket 경로 Gateway 시간 제한 | route 확정 | WebSocket을 기본 응답 시간(30초)보다 오래 연결 | 정한 시간 제한까지 연결이 유지된다 | HTTPRoute 기본 30초·1MiB | ND-026, **U14** | `k8s/base/gateway.yaml` HTTPRoute | E2E | 미결 (U14) |

### 2.11 ERR — 실패·재시도·DLT

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-ERR-01 | 처리 실패 재시도 | 처리 중 실패를 일으킬 수단 | 일시 실패 후 성공 / 계속 실패 | 블로킹 재시도(1초부터 최대 10초 지수, 3회). 그 사이 같은 partition 뒤 메시지는 기다린다. 성공하면 알림 1건, 계속 실패하면 그룹 DLT로 보내고 offset을 커밋해 뒤 메시지를 처리한다 | DLT 이름 단언은 NT-ERR-03 | ND-010 | kafka.md "재시도와 DLT" | Integration(PG+Kafka) | 유효 |
| NT-ERR-02 | 읽을 수 없는 메시지 | — | 깨진 JSON, 검증 실패 이벤트 | 재시도 없이 바로 DLT. 신뢰 패키지는 실제 이벤트 패키지만(`*` 없음) | — | ND-010 | 위와 같음 | Integration(PG+Kafka) | 유효 |
| NT-ERR-03 | DLT 이름과 선언 | — | 처리 실패 | `<topic>.<group>.DLT`로 간다(예: `member.events.v1.auxiliary-app.notification-member-replica.DLT`). DLT 토픽을 알림 모듈이 `NewTopic`으로 선언(partition 3, 원본과 같은 partition 번호, 보존 7일) | — | ND-010, ND-047 | kafka.md "재시도와 DLT" | Integration(PG+Kafka) | 유효 |
| NT-ERR-04 | DLT 발행 실패 | DLT 발행을 실패시킬 수단 | 처리 실패 + DLT 발행 실패 | 처리 성공으로 보지 않고 offset을 커밋하지 않는다. 다시 시도한다 | — | ND-010 | 위와 같음 | Integration(PG+Kafka) | 유효 |

### 2.12 BND — 모듈 경계·schema 격리

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-BND-01 | Modulith 모듈 경계 | `:notification` 모듈, `package-info.java` `@ApplicationModule(allowedDependencies = "global")` | `AuxiliaryModularityTest` | 새 모듈 `notification`을 찾고, `chatbot`·`search` 등 다른 업무 모듈을 직접 참조하지 않아 통과한다 | 모듈 사이 연동은 Spring 이벤트로만 | ND-006 | auxiliary-app README "모듈 경계" | Architecture | 유효 |
| NT-BND-02 | Gradle 의존 경계 | — | `./gradlew verifyModuleStructure` | `:notification`이 `:auxiliary-global`·`:shared`에만 의존한다 | `--parallel` 없이 실행 | ND-006 | 위와 같음 | Build | 유효 |
| NT-BND-03 | schema 격리 | Flyway 적용된 database `auxiliary` | 기동, Flyway 실행 | 모듈 첫 SQL이 `CREATE SCHEMA IF NOT EXISTS notification;`. 테이블(ND-025의 넷)은 처음 쓰는 작업에서 생기며(ND-046: N1 `notification_user`, N2 `notification`, N8 `notification_payout_item`, N10 `notification_schedule`) 모두 schema `notification`에 있고 엔티티는 `@Table(schema = "notification")`. Flyway 이력·Modulith 발행 기록은 schema `auxiliary` | — | ND-007, ND-025, ND-046 | auxiliary-app README "Flyway" | Integration(PG) | 유효 |
| NT-BND-04 | 다른 schema JOIN·FK 없음 | NT-BND-03 | 카탈로그 조회(`pg_constraint`), 쿼리·엔티티 정적 검사 | schema `notification` 테이블의 FK가 다른 schema를 가리키지 않는다. HQL·SQL이 다른 schema 테이블을 읽거나 쓰지 않는다. 회원은 ID(Long)로만 참조하고 JPA 연관관계가 없다 | 다른 schema와 같은 트랜잭션 쓰기도 없다 | ND-007, ND-018 | 루트 AGENTS 3장 경계 규칙 | Integration(PG), Architecture | 유효 |

### 2.13 CHAT — 채팅 알림 유지

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-CHAT-01 | 채팅 출연 알림 메시지 유지 | chat-app·auxiliary-app 실행 | `AppearanceRequestSentEvent`, `AppearanceApplicationSubmittedEvent` | 채팅 1:1 방의 `NOTIFICATION` 메시지와 알림센터 알림이 각각 생긴다. 채팅 쪽 결과는 알림 모듈이 생기기 전과 같다 | 채팅 쪽 판정은 chat-app 소유 테스트를 따른다(계약 원본 `apps/chat-app/docs/contracts/notification-data.md`) | ND-004 | Kafka `appearance.events.v1` | E2E | 유효 |
| NT-CHAT-02 | 소비 그룹 독립 | 두 앱 실행 | 알림 소비만 실패(알림 그룹 DLT) / 채팅 소비만 실패 | 한쪽 실패가 다른 쪽 결과를 바꾸지 않는다 | — | ND-004 | consumer group(이름은 U1) | E2E | 유효 |
| NT-CHAT-03 | 채팅 새 메시지·차단 제외 | — | 새 메시지·차단·나가기·호스트 자격 상실·일시 중지·재개 | 알림센터 알림 0(채팅 안 읽은 메시지 수가 맡는다) | 단체방 3종(NT-GEN-08)만 받는다 | ND-004, ND-011 | 카탈로그 "보내지 않는 것" | Integration(PG+Kafka) | 유효 |
| NT-CHAT-04 | 같은 사건이 채팅·알림센터에 함께 뜰 때 화면 | FE 정책 확정 | 출연 요청 도착, 단체방 초대 | 정한 방식대로 보인다 | 백엔드 기준이 생길지도 정해지지 않았다 | ND-004, **U12** | — | E2E(FE) | 미결 (U12) |

### 2.14 RES — 자원

| 테스트 ID | 대상 기능 | 선행 조건 | 입력 | 예상 결과(합격 기준) | 예외·경계 조건 | 관련 ND | 관련 계약 | 테스트 종류(제안) | 명세 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| NT-RES-01 | DB 연결 풀 공유 | 풀 크기 확정(지금 Hikari 5, 챗봇 기준) | 알림 소비·API·예약 배치와 챗봇 요청을 동시에 | 정한 예산 안에서 연결 대기 시간 초과가 없다 | PostgreSQL `max_connections` 100을 다른 앱과 나눔 | **U13** | auxiliary-app README "실행과 설정" | 부하 | 미결 (U13) |
| NT-RES-02 | 배치 스레드 공유 | 스레드 수 확정(지금 2) | 1분 예약 배치와 챗봇 배치가 겹침 | 예약 배치 지연이 정한 범위 안이다 | — | ND-029, **U13** | 위와 같음 | 부하 | 미결 (U13) |
| NT-RES-03 | 메모리 | 예산 확정(지금 limit 704Mi, `-Xmx256m`) | STOMP 연결 여럿 + 소비 + 챗봇 | OOMKilled 없이 정한 예산 안이다 | — | **U13** | 위와 같음 | 부하, E2E | 미결 (U13) |

## 3. 완료 증거 매핑

[알림 설계](../architecture/infrastructure/notification.md) 11장의 완료 증거 6개(원문 그대로)와 이를 판정하는 NT 항목이다. 증거를 시연·실행한 결과는 [regression-log.md](regression-log.md)에 남긴다. 여기 연결은 실행 기록이 아니다.

| # | 원문 | NT | 메모 |
| --- | --- | --- | --- |
| 1 | 일반 유저 계정이 충전 완료·구매 완료 알림을 받는다. | NT-GEN-01(충전 완료), NT-GEN-02(구매 완료) | 충전 완료는 ✅ `ChargeCompletedEvent`로 지금 가능하다. **구매 완료는 P1 이벤트 `LectureOrderPaidEvent`(M4)가 필요하다.** N 8장 "P0만으로 11장 완료 증거를 시연할 수 있다"와 맞지 않아 미결(U2) |
| 2 | 같은 integration event를 두 번 소비해도 알림은 한 번만 생긴다. | NT-IDEM-01, NT-IDEM-02 | 받는 사람이 여럿이면 NT-IDEM-03 |
| 3 | 실시간 알림이 WebSocket으로 도착한다. 실시간 전달을 일부러 유실시켜도 재접속 후 목록이 같다. | NT-RT-01, NT-RT-04 | 연결 티켓 NT-RT-05. 운영 Gateway에서 연결이 유지되는지는 NT-RT-07(U14) |
| 4 | 알림이 21개가 되면 가장 오래된 알림이 목록에서 빠지고, 그 알림의 이벤트를 다시 소비해도 되살아나지 않는다. | NT-RET-01, NT-RET-02 | 묶음 알림이 밀려난 경우는 NT-BUN-05(U9) |
| 5 | auxiliary-app을 끈 동안 쌓인 이벤트가 재시작 후 consumer lag가 줄면서 모두 알림이 된다. | NT-KAF-03 | Kafka 보존 72시간 안에서만 성립한다. 보존 밖·첫 배포의 과거 이벤트는 NT-KAF-04(U7) |
| 6 | 채팅의 출연 알림 메시지도 그대로 남는다. | NT-CHAT-01 | 소비 그룹 독립 NT-CHAT-02 |

## 4. 집계

이 문서의 항목 수는 이 표에서만 관리한다. 항목을 더하거나 상태를 바꾸면 이 표를 함께 고친다.

| 영역 | 항목 수 | 유효 | 미결 (Uxx) | 미결 (결정 없음) | 폐기·대체됨 |
| --- | --- | --- | --- | --- | --- |
| GEN 알림 생성·발생 조건 | 14 | 12 | 2 (U3, U11) | 0 | 0 |
| KAF 이벤트 소비 | 6 | 5 | 1 (U7) | 0 | 0 |
| IDEM 중복 | 5 | 4 | 1 (U5) | 0 | 0 |
| QRY 조회·접근 | 4 | 4 | 0 | 0 | 0 |
| READ 읽음 | 6 | 6 | 0 | 0 | 0 |
| RET 보존·Soft Delete | 5 | 5 | 0 | 0 | 0 |
| BUN 하루 묶음 | 5 | 4 | 1 (U9) | 0 | 0 |
| SCH 예약 | 11 | 9 | 2 (U6, U16) | 0 | 0 |
| USR 회원 복제본·탈퇴 | 7 | 5 | 2 (U8, U17) | 0 | 0 |
| RT 실시간·재접속 | 7 | 5 | 2 (U15, U14) | 0 | 0 |
| ERR 실패·재시도·DLT | 4 | 4 | 0 | 0 | 0 |
| BND 모듈 경계·schema 격리 | 4 | 4 | 0 | 0 | 0 |
| CHAT 채팅 알림 유지 | 4 | 3 | 1 (U12) | 0 | 0 |
| RES 자원 | 3 | 0 | 3 (U13) | 0 | 0 |
| **합계** | **85** | **70** | **15** | **0** | **0** |

- 연결된 테스트가 있는 항목: 아래 "N1 연결 테스트" 표. 1~3장 표에 "테스트" 칸을 더하는 일(5장 2번)은 아직 하지 않았다.

**연결 테스트(N1·N2)** (클래스는 `apps/auxiliary-app/boundedContext/notification/src/test/java/com/hapbang/auxiliary/notification/` 아래. "일부"는 합격 기준의 한 부분만 본다)

| NT | 테스트 | 범위 |
| --- | --- | --- |
| NT-USR-01 | `app.NotificationUserSyncServiceTest`, `in.event.NotificationMemberEventListenerTest`(가입·변경) | 전부 |
| NT-USR-02 | `domain.NotificationUserTest`, `app.NotificationUserSyncServiceTest`(같은 버전·낮은 버전), `in.event.NotificationMemberEventListenerTest` | 복제본 버전. 승인 알림(NT-GEN-06)은 N2 이후 |
| NT-USR-01(ND-049) | `app.NotificationUserSyncServiceTest`(변경으로 생성, 늦게 온 가입 건너뜀, 동시 처리), `in.event.NotificationMemberEventListenerTest`(가입보다 먼저 온 변경), `in.event.NotificationMemberEventDeadLetterTest`(복제본 없는 변경은 DLT 아님) | 전부 |
| NT-USR-04 | `app.NotificationWriterTest`(복제본 없으면 `RECEIVER_NOT_REPLICATED`로 실패) | 저장 서비스 수준. 재시도 뒤 DLT는 소비자(N5 이후) |
| NT-KAF-02 | `in.event.NotificationMemberEventListenerTest`(처리기 없는 종류·클래스패스에 없는 종류 건너뜀, DLT 0) | 회원 토픽만 |
| NT-KAF-06 | `in.event.kafka.NotificationKafkaDeadLetterTest`, `in.event.NotificationMemberEventListenerTest`(컨테이너 그룹) | 회원 복제본 그룹만 |
| NT-ERR-01 | `in.event.NotificationMemberEventDeadLetterTest`(DB 일시 장애가 계속되면 처음 1번 + 재시도 뒤 같은 partition 번호의 DLT, 다음 레코드 처리) | 일부: 재시도 중 성공 경우는 없음 |
| NT-ERR-02 | `in.event.NotificationMemberEventDeadLetterTest`(깨진 본문·신뢰 밖 타입·검증 실패는 재시도 없이 DLT) | 전부 |
| NT-ERR-03 | `in.event.kafka.NotificationKafkaDeadLetterTest`, `in.event.NotificationMemberEventDeadLetterTest`(같은 partition 번호, partition 수) | 전부 |
| NT-ERR-04 | `in.event.kafka.NotificationKafkaDeadLetterTest`(DLT 발행 실패 시 예외) | 일부: 복구기 단위 |
| NT-BND-01 | `apps/auxiliary-app` `AuxiliaryModularityTest#verify`(notification 모듈을 찾고 경계 검사) | 전부 |
| NT-BND-02 | `./gradlew verifyModuleStructure` | 전부 |
| NT-BND-03 | `NotificationModuleIntegrationTest`, `apps/auxiliary-app` `AuxiliaryApplicationTest#flywaySchemas` | N2까지(schema, `notification`·`notification_user`, 모듈 SQL 적용, 이력 위치) |
| NT-QRY-01~04 | `in.ApiV1NotificationControllerTest`(최신 20개·발생 시각 정렬, 다른 회원 차단·비로그인 401·관리자 403, 대상 삭제 무관) | 전부 |
| NT-READ-01~06 | `in.ApiV1NotificationControllerTest`(누를 때 읽음, 목록으로 안 읽음, 남의·없는·밀려난 알림 404 통일, 전체 읽음, 최대 20, 다시 읽음 200), `domain.NotificationTest` | 전부. 실시간 프레임의 안 읽은 수(READ-05)는 N3 |
| NT-RET-01~05 | `app.NotificationWriterTest`(21번째 Soft Delete, 밀려난 알림 재저장 안 됨, 늦게 온 오래된 알림, Hard Delete 없음, 같은 회원 동시 저장 20개 유지), `app.NotificationConcurrencyTest`(읽음·전체 읽음과 보존 정리의 겹침) | 저장 서비스 수준. RET-02의 Kafka 재수신은 N5 이후 |
| NT-IDEM-01·02 | `app.NotificationWriterTest`(같은 사건 두 번, 동시 세 번) | 저장 서비스 수준. Kafka 재소비는 N5 이후 |
| NT-GEN-13 | `domain.NotificationTest`, `app.NotificationWriterTest`(값 `v`, 저장된 값으로 같은 문구) | 전부 |
| NT-USR-03 | `app.NotificationWriterTest`(탈퇴 받는 사람 제외) | 저장 서비스 수준. 함께 받는 사람·예약은 N5·N10 |
| NT-USR-07 | `app.NotificationConcurrencyTest`(보존 정리와 파기의 겹침, B·C 두 파기의 겹침), `app.NotificationMaskingServiceTest`(저장된 알림·밀려난 알림, 파기 상태 복제본, 파기 뒤 옛 닉네임 초안 저장, 복제본 없는 회원의 파기와 저장 동시 처리), `in.event.NotificationMemberEventListenerTest`(파기 이벤트, 변경 이벤트와 순서, 재수신), `domain.NotificationUserTest`·`app.NotificationUserSyncServiceTest`(파기된 복제본은 가입·변경으로 바뀌지 않음) | 전부(ND-051·ND-054) |
- U2는 합격 기준이 아니라 완료 증거의 단계 문제라 NT 상태에 쓰지 않았다(3장 1번).
- 아직 발행되지 않는 이벤트(🆕·🟡·⚠️)가 필요한 유효 항목은 선행 조건에 M 작업을 적었다. 그 항목은 해당 M 전에는 실행할 수 없다.

## 5. 이 문서를 고칠 때

1. 결정(ND)이 바뀌거나 U가 결정되면 해당 행의 기준과 상태를 고친다. "미결 (결정 없음)" 항목은 decisions.md 10장에 U로 올린 뒤 "미결 (Uxx)"로 바꾼다.
2. 테스트 코드가 생기면 표에 "테스트" 칸(`클래스#메서드`, 없으면 "테스트 없음")을 더하고 실행 명령 장을 만든다.
3. 실행 결과·통과 여부는 이 문서에 적지 않고 [regression-log.md](regression-log.md)에 남긴다.
4. 4장 집계를 함께 고친다.

변경 이력
- 2026-11-06 처음 작성: N 3~6·11장, decisions.md ND-001~032·U1~U15, kafka.md, auxiliary-app README에서 80개 항목을 만들었다. 코드·테스트가 없어 실행하지 않았다.
- 2026-11-06 decisions.md U16·U17·U18 반영: NT-SCH-06 → 미결 (U16), NT-USR-04 → 미결 (U17), 새 항목 NT-GEN-14(채널 인증 승인 불일치, U18)·NT-USR-06(첫 배포 기존 회원 복제본, U17)을 더하고 NT-GEN-06에 NT-GEN-14 참조를 붙였다(NT-GEN-06 유효 유지). 82개 항목, "미결 (결정 없음)" 0.
- 2026-11-06 decisions.md U19 반영: NT-USR-07(파기된 회원의 닉네임이 들어간 저장 알림, 미결 U19)을 더했다. 집계 82 → 83.
- 2026-11-09 decisions.md ND-037~ND-044 반영: NT-GEN-11(ND-040, 선행 M5)·NT-GEN-14(ND-042, 선행 M6)·NT-SCH-11(ND-041, 선행 M3)을 유효로 바꿨다. NT-GEN-06은 ND-042에 따라 역할 변경만으로는 채널 인증 승인 알림을 만들지 않도록 예상 결과를 고쳤다. NT-GEN-08은 종류 코드를 ND-043으로, NT-GEN-10은 ND-039로 고쳤다(U3 미결 유지). NT-GEN-12는 남은 규칙 밖 코드만 남겼다(U11 미결 유지). 집계 유효 62 → 65, 미결 21 → 18.
- 2026-11-09 decisions.md ND-033~ND-036 반영: NT-USR-04(ND-035)를 유효로 바꾸고 DLT 재처리 경로(kafka.md runbook)를 예상 결과에 더했다. NT-SCH-06(ND-034)·NT-IDEM-05(ND-033)·NT-USR-07(ND-036)은 예상 결과를 결정에 맞추고, 남은 미결(U16 예약 알림별 계약 검증, U5 이벤트별 필드, U19 저장 구조·갱신 방법) 때문에 미결을 유지했다. NT-USR-06은 첫 배포 Backfill(U17)이 미결이라 바꾸지 않았다. 집계 유효 61 → 62, 미결 22 → 21.
- 2026-11-09 decisions.md ND-047 반영: NT-KAF-06·NT-ERR-03을 미결(U1)에서 유효로 바꾸고 그룹·DLT 이름을 예상 결과에 넣었다. 4장 집계 83개, 유효 67, 미결 16.
- 2026-11-09 N1(#797): NT-BND-03 기대 결과를 ND-046(테이블은 처음 쓰는 작업에서 생성)에 맞추고, 4장에 N1 연결 테스트 표를 더했다. 상태·집계는 바꾸지 않았다.
- 2026-11-09 decisions.md ND-049 반영(#797 리뷰): NT-USR-01 기대 결과에 변경 이벤트로 만드는 경우를 더하고, NT-USR-06 설명과 N1 연결 테스트 표(NT-USR-01·02·04, NT-ERR-01)를 맞췄다. 상태·집계는 바꾸지 않았다.
- 2026-11-09 decisions.md ND-050~ND-053 반영(#801 사전 결정): NT-GEN-10·1장 문구 설명(ND-050), NT-QRY-03 관리자 403·NT-READ-03 404(ND-052), NT-USR-07을 미결(U19)에서 유효로(ND-051) 바꾸고, NT-READ-06(다시 읽음)·NT-RET-05(동시 저장)를 더했다. 4장 집계 85개, 유효 70, 미결 15.
- 2026-11-10 N2(#801): 4장 연결 테스트 표를 N1·N2로 넓혀 NT-QRY·READ·RET·IDEM·GEN-13·USR-03·04·07과 NT-BND-03을 연결했다. 상태·집계는 바꾸지 않았다.
- 2026-11-10 decisions.md ND-054 반영(#801): NT-USR-07 예외·경계 조건에 파기 뒤 저장·동시 처리를 더하고 연결 테스트 표를 맞췄다. 상태·집계는 바꾸지 않았다.
- 2026-11-10 #801 PR 리뷰 반영: NT-RET-05·NT-USR-07 기대 결과에 같은 알림 행의 겹침(읽음·전체 읽음·파기와 보존 정리, 두 파기)을 더하고 연결 표에 `NotificationConcurrencyTest`를 넣었다. 상태·집계는 바꾸지 않았다.
