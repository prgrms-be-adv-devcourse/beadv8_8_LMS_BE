# 알림(notification) 모듈 — 결정 기록

> - 이 문서는 auxiliary-app 안 알림 모듈(알림센터)의 결정과 그 이유를 `ND-*` 번호로 모은다. 번호는 바꾸거나 재사용하지 않는다.
> - 원천은 [알림 설계](../architecture/infrastructure/notification.md)(#710, 2026-11-05)다. 루트 `AGENTS.md`, [Kafka](../architecture/infrastructure/kafka.md), [최종 인프라 설계](../architecture/final-project-infrastructure.md), [auxiliary-app README](../../apps/auxiliary-app/README.md)(#714)로 바뀐 점을 반영했다. 결정을 새로 만들지 않았고, 원문에 이유가 없으면 "기록 없음"으로 적었다.
> - **구현 상태**: 2026-11-06 기준 알림 모듈 코드는 없다(`apps/auxiliary-app/boundedContext/`에는 `chatbot`만 있다). 이 문서의 "유효"는 정책이 확정됐다는 뜻이고, 구현·테스트를 마쳤다는 뜻이 아니다.
> - **정책 원본 위치(2026-11-06 사용자 결정)**: 알림센터 업무 정책은 지금 이 문서에만 둔다. 팀 규칙상 정책 원본은 [`docs/policy.md`](../policy.md)이므로, policy.md에 알림센터 장을 더하는 일은 팀 승인을 받는 후속 작업으로 남긴다. 장이 생기면 업무 규칙은 policy.md가 원본이 되고 이 문서는 이유·대안·이력을 맡는다.
> - 결정을 바꾸려면 기존 행의 상태를 바꾸고 새 번호를 더한다.
>
> 변경 이력
> - 2026-11-06 처음 작성: notification.md 1~6·9장의 확정 결정을 옮겼다. #714로 바뀐 앱 이름·저장소를 ND-006·ND-007로 기록했다. #710 이전 결정은 11장에 폐기로 남겼다. 미결 U1~U15를 10장에 적었다.
> - 2026-11-06 검토 반영: 상태 칸의 괄호 메모(ND-004·007·009·026)를 출처 칸으로 옮겼다. 원문의 부정문을 대안으로 적었던 ND-022·027의 "검토한 대안"을 "기록 없음"으로 고쳤다. `GROUP_*` 이름을 유효로 옮기지 않은 이유를 4장에 적었다. 11장 목록에서 이미 대체 표시가 붙은 `docs/billing/CONTEXT.md` 9.4를 뺐다. T6·T7은 Billing 결정 기록 10장을 가리킨다.

## 0. 읽는 법

| 상태 | 뜻 |
| --- | --- |
| 유효 | 지금 적용하는 확정 정책 |
| 미결 | 결정이 더 필요하다. 임의로 정하지 않는다. 10장에 둔다 |
| 폐기 | 더 이상 적용하지 않는다. 대신한 결정이 있으면 `폐기 → ND-n` |
| 대체됨 → ND-n | 다른 결정으로 전부 바뀌었다 |
| 일부 대체됨 → ND-n | 일부만 바뀌었다. 괄호 안에 바뀐 부분과 남은 부분을 적는다 |

- 상태 칸에는 위 다섯 가지와 대체 범위만 적는다. 구현·테스트 상태는 적지 않는다.
- 출처의 `N`은 [notification.md](../architecture/infrastructure/notification.md)의 장 번호다.
- 이 문서는 알림센터(auxiliary-app 알림 모듈)만 다룬다. 채팅의 출연 알림 메시지·단체방 안내·실시간 새 메시지 알림은 채팅이 소유하며 여기서 정하지 않는다(12장).

## 1. 범위와 책임

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-001 | 알림 모듈은 알림센터를 맡는다. 화면 위쪽 종 아이콘으로 여는 목록, 안 읽은 수 배지, 읽음 처리 | 채팅 출연 알림은 채팅할 수 있는 회원끼리만, 보낸 사람이 있어야 쓸 수 있어 일반 유저(강의 구매자)나 시스템 알림(지급 완료, 심사 결과, 문의 답변)을 담을 수 없다 | 기록 없음 | 유효 | N1, #193, #369 |
| ND-002 | 앱 안에서만 보낸다. 이메일·푸시는 없다. 로그인하지 못한 회원에게 가야 하는 알림(비밀번호 변경, 로그인 잠금, 탈퇴 복구 기한)은 범위 밖이다 | 기록 없음 | 기록 없음 | 유효 | N1, N9 |
| ND-003 | 받는 사람은 유저·인플루언서·인증된 인플루언서다. 관리자는 알림센터를 쓰지 않고, 관리자 업무(검수·심사 요청, 새 문의)는 관리자 화면 목록에서 처리한다 | 기록 없음 | 기록 없음 | 유효 | N1, N9 |
| ND-004 | 채팅의 기존 알림(출연 알림 메시지, 단체방 안내, `/user/queue/chats/notifications`)은 옮기거나 없애지 않는다. 알림 모듈은 같은 이벤트를 따로 받아 알림센터에 함께 보낸다. 채팅 새 메시지와 차단은 알림센터에 넣지 않는다(채팅의 안 읽은 메시지 수가 맡는다). 두 기능은 consumer group이 달라 서로 영향을 주지 않는다 | 채팅 알림은 채팅의 일로 남긴다 | 채팅 출연 알림·`/user` 경로를 알림 모듈로 옮기고 채팅 쪽을 없애는 안(ND-901·ND-902, 폐기) | 유효 | N1, N6.4 7항, N9. 2026-11-05 #710 |

## 2. 배포 단위와 저장소

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-005 | 알림은 별도 앱이 아니라 검색·챗봇과 같은 JVM 앱(`aux-app`)의 모듈로 둔다. database 이름은 `notification`이다 | JVM 앱은 heap과 상관없이 약 300 MiB를 고정으로 써서 세 기능을 앱 셋으로 띄울 메모리가 없다 | 별도 `notification-app`(ND-903, 폐기) | **일부 대체됨 → ND-006·ND-007** (앱 이름과 database 부분. 검색·챗봇·알림을 한 앱의 모듈로 둔다는 것은 유효) | N2, N9, #684, #710 |
| ND-006 | 앱 이름은 `auxiliary-app`, 알림은 그 안의 업무 모듈 `notification`(Gradle `:notification`, 패키지 `com.hapbang.auxiliary.notification`, 디렉터리 `boundedContext/notification`)이다. 업무 모듈은 `:auxiliary-global`·`:shared`에만 의존하고 다른 업무 모듈을 직접 참조하지 않는다(`AuxiliaryModularityTest`, `verifyModuleStructure`) | Windows가 `aux`를 예약 장치 이름으로 막아 Git for Windows가 경로를 거부한다 | `aux-app`(ND-005) | 유효 | 루트 AGENTS 3장, auxiliary-app README, #714 |
| ND-007 | 저장소는 앱 공통 database `auxiliary` 안의 schema `notification`이다. 엔티티는 `@Table(schema = "notification")`, Flyway 이력과 Modulith 발행 기록은 `auxiliary` schema에 둔다. 다른 모듈 schema의 테이블과 JOIN·FK·같은 트랜잭션 쓰기를 하지 않는다. 모듈 첫 SQL은 `CREATE SCHEMA IF NOT EXISTS notification;`이다 | 한 앱이라 DataSource·JPA·Flyway·트랜잭션 관리자가 하나다. 모듈마다 database를 두면 연결을 나눠야 한다 | database `notification`(ND-005) | 유효 | 루트 AGENTS 2·3장, auxiliary-app README "모듈 경계"·"Flyway", #714. 루트 AGENTS 3장 경계 규칙의 auxiliary-app 예외 |

## 3. 입력

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-008 | 입력은 각 모듈의 Kafka integration event뿐이다. 발행하는 모듈은 알림의 존재를 모르고, 알림 모듈이 받는 사람·종류·문구를 정한다. 이벤트에는 문구가 아니라 사실(ID, 제목, 금액, 일시)을 담는다 | 발행 모듈과 알림의 결합을 없앤다 | 기록 없음 | 유효 | N2 |
| ND-009 | 채팅 내부 전달 큐(Redis Streams)는 구독하지 않는다. 알림은 독립 consumer group으로 Kafka를 소비·재처리하고, 같은 Stream 그룹에 채팅과 알림 worker를 넣지 않는다 | 채팅 내부 Stream에는 chat DB 참조만 담긴다 | 기록 없음 | 유효 | N2 "채팅 Streams와의 경계". 2026-10-29 |
| ND-010 | 처리하지 못한 메시지는 다른 앱과 같은 규칙으로 `<topic>.<group>.DLT`에 보낸다. 같은 토픽의 받지 않는 타입(예: 강의 AI 추천 동기화 이벤트 4종)은 기본 `@KafkaHandler`로 무시하고 커밋한다 | Kafka 공통 규칙 | — | 유효 | N2, kafka.md "소비", `docs/api/internal-lecture-sync.md` |

consumer group 이름(`notification.<목적>`)은 notification.md 9장에 결정으로 적혀 있으나 kafka.md의 팀 규칙 `<앱>.<목적>`과 달라 유효로 옮기지 않았다(U1).

## 4. 알림 종류와 받는 사람

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-011 | 즉시 알림 49종(출연 15, 강의 11, 지갑·정산 11, 회원 5, 고객센터 4, 채팅 3)과 예약 알림 5종을 둔다. 종류·받는 사람·발생 조건·"보내지 않는 것"은 notification.md 3·4장 표가 원본이다(3.4단계에서 알림 종류 문서로 옮긴다) | 기록 없음 | 기록 없음 | 유효 | N1 "규모", N3, N4 |
| ND-012 | 종류 코드는 원본 shared 이벤트 이름에서 `Event`를 뺀 UPPER_SNAKE다(`AppearanceRequestSentEvent` → `APPEARANCE_REQUEST_SENT`). 이벤트가 아직 없으면 발행 모듈의 상태 enum·도메인 메서드 이름으로 이벤트 이름을 정하고 같은 규칙을 쓴다 | 채팅 `NotificationKind`와 같은 규칙 | — | 유효 | N3. 미결: 규칙 밖의 코드(`PAYOUT_COMPLETED_DAILY`, 역할 비교로 만드는 승인 2종, 예약 5종)와 채팅 `GROUP_*` 이름 재사용은 U11 |
| ND-013 | 지갑 `PayoutCompletedEvent` 하나를 원천으로 나눈다. 출연 원천(`APPEARANCE_REQUEST`·`RECRUIT_APPEARANCE`, `NORMAL`·`PENALTY`)은 `PAYOUT_COMPLETED`로 한 건씩, 강의 원천(`LECTURE_ORDER_ITEM`)은 `PAYOUT_COMPLETED_DAILY`로 하루씩 묶는다. 출연·강의의 사건 알림과 돈(지급) 알림을 둘 다 보낸다 | 강의 지급은 배치(01:00)로 하루치가 한꺼번에 온다 | 기록 없음 | 유효 | N3.1, N3.3, N5 |
| ND-014 | 지급 알림에는 출연·강의 제목을 넣는다. 가져오는 방법은 미결(U4) | 기록 없음 | — | 유효 | N3.3, N9, N10 |
| ND-015 | 강의 이의 제기 접수는 강의가 접수할 때 발행하는 `LectureObjectionFiledEvent` 하나로 구매자·판매자에게 보낸다(바로 접수든 고객센터 재시도 배치 접수든). 고객센터는 재시도 배치가 접수 불가로 끝낼 때만 `LectureObjectionFileRejectedEvent`로 알린다 | 구매자에게 같은 사실을 두 번 보내지 않는다. 바로 거절된 접수는 화면에서 바로 보인다 | 기록 없음 | 유효 | N3.2, N3.5, N9 |
| ND-016 | 채널 인증 승인·승급 승인은 회원 복제본의 이전 역할과 `UserModifiedEvent`의 새 역할을 비교해 알아낸다. 자동 승인과 관리자 승인을 구분하지 않고 같은 종류·문구로 보낸다 | 구분할 수 있는 이벤트가 없다 | — | 유효 | N3.4 |
| ND-017 | 모집 전체 취소는 종류를 따로 두지 않는다. 출연 모듈이 성립한 출연마다 `AppearanceCanceledByHostEvent`, 대기 중인 지원마다 `AppearanceApplicationNotSelectedEvent`를 발행하고 기존 종류를 쓴다 | 용어집상 모집 전체 취소는 성립한 출연마다 호스트 일방 취소다 | — | 유효 | N3.1. 미결: 예약 취소에 쓰는 `AppearanceRecruitCanceledEvent`와의 관계는 U10 |

notification.md 3장은 "채팅이 이미 쓰는 이름(`GROUP_*`)은 그대로 쓴다"고 정했으나, 같은 이름이 채팅의 방 안 안내(`NotificationKind`)와 뜻·받는 사람이 달라(강퇴: 채팅은 남은 참여자, 알림센터는 강퇴된 회원) ND-012에 유효로 옮기지 않았다(U11). 3.6의 단체방 알림 3종 자체(받는 사람·조건)는 ND-011로 유효다.

## 5. 생성·저장·조회

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-018 | 받는 사람 상태·역할·닉네임은 회원 이벤트로 만든 복제본 `notification_user`에서 읽는다(가입 이벤트로 만들고 변경 이벤트로 갱신, 채팅 `chat_user`와 같은 방식). 탈퇴한 회원이면 알림과 예약을 만들지 않는다 | 다른 모듈과 같은 복제 방식(루트 AGENTS 3장) | — | 유효 | N2, N6.1, N6.4 1항. 미결: 정지 회원 처리는 U8 |
| ND-019 | 같은 이벤트를 다시 받아도 유일 인덱스 (원본 이벤트 종류, 원본 대상 ID, 받는 사람)로 한 번만 만든다. 묶음 알림은 (종류, 받는 사람, 묶음 키). 삭제된 행도 유일 인덱스에 남긴다 | Kafka는 같은 메시지를 다시 줄 수 있다. 채팅의 유일 인덱스 방식을 쓴다 | — | 유효 | N6.1, N6.4 2항. 미결: 이벤트별 원본 대상 ID는 U5 |
| ND-020 | 문구는 알림 모듈이 만들어 저장한다. 값(`jsonb`, 형식 버전 `v`)도 함께 저장해 문구를 다시 만들 수 있게 한다 | 기록 없음 | 기록 없음 (채팅은 FE 템플릿을 쓰지만 채팅 소유 결정이다) | 유효 | N6.4 3항, N9. 미결: 종류별 템플릿은 U3 |
| ND-021 | 목록은 받은 순서가 아니라 이벤트의 업무 시각(발생 시각)으로 정렬한다 | 환전 이벤트처럼 순서가 바뀌어 올 수 있다(지갑 CONTEXT 9장) | — | 유효 | N3.3, N6.4 4항 |
| ND-022 | 보존은 기간이 아니라 회원별로 삭제되지 않은 알림 중 발생 시각 기준 최신 20개다. 새 알림을 저장하는 트랜잭션에서 21번째부터 오래된 순으로 Soft Delete한다. 밀려난 알림의 이벤트가 다시 와도 유일 인덱스에 걸려 되살아나지 않는다. 안 읽은 수는 남은 20개 안에서 센다(배지 최대 20) | Hard Delete 금지(policy.md 7장 삭제 정책) | 기록 없음 | 유효 | N6.2, N9. 미결: 묶음 알림과 겹칠 때는 U9 |
| ND-023 | 알림을 눌렀을 때 읽음으로 바꾼다. 목록을 연 것만으로는 읽지 않는다. 전체 읽음을 둔다 | 기록 없음 | 기록 없음 | 유효 | N6.3, N9 |
| ND-024 | 대상이 삭제돼도 알림은 남기고, 눌러서 이동한 화면이 "삭제된 ○○"으로 보여 준다 | 기록 없음 | 기록 없음 | 유효 | N6.4 6항, N9 |
| ND-025 | 테이블은 `notification`(받는 사람, 종류, 원본 이벤트 종류·대상 ID, 이동 대상, 문구, 값, 묶음 키, 발생·읽은·만든·삭제 시각), `notification_payout_item`(지급 ID 유일), `notification_schedule`((종류, 대상 ID, 받는 사람) 유일, 상태 예약·보냄·취소), `notification_user`(회원 복제본) 넷이다. 모두 schema `notification`에 둔다(ND-007) | — | — | 유효 | N6.1, ND-007 |
| ND-026 | 모든 API는 로그인 회원 본인의 알림만 다룬다. 목록(최신 20개, cursor 없음), 안 읽은 수, 읽음, 전체 읽음, 연결 티켓, WebSocket을 `/api/v1/notifications` 아래에 둔다 | 루트 AGENTS 3장 진입 경로 | — | 유효 | N6.3, 루트 AGENTS 3장. 세부 계약은 3.4단계 API 문서로 옮긴다 |

## 6. 실시간 전달

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-027 | 저장이 commit된 뒤 Redis Pub/Sub으로 발행하고, 알림 모듈 자체 STOMP가 받는 사람 연결의 `/user/queue/notifications`로 보낸다. chat-app 연결을 빌리지 않는다. 실시간 전달은 유실될 수 있으므로 재접속하면 REST 목록으로 맞춘다(DB가 원천) | 기록 없음 | 기록 없음 | 유효 | N2, N6.4 5항, N9 |
| ND-028 | 채팅 코드를 가져와 쓴다: STOMP 연결 티켓(`/api/v1/chats/ws-tickets`와 같은 방식), Redis Pub/Sub 전달, Kafka 소비·DLT 설정, 유일 인덱스 중복 흡수 | 같은 문제를 이미 푼 코드다. 앱끼리 의존하지 않으므로 복사한다(auxiliary-app README) | — | 유효 | N2. 미결: 토큰 만료 헤더는 U15, Gateway 경로는 U14 |

## 7. 예약 알림과 하루 묶음

| ID | 결정 | 이유 | 검토한 대안 | 상태 | 출처·관련 |
| --- | --- | --- | --- | --- | --- |
| ND-029 | 예약 알림은 알림 모듈이 이벤트를 받아 만들고, 취소 이벤트가 오면 취소한다. 발행 모듈은 예약을 모른다. 예약 배치는 1분마다 보낼 시각이 지난 예약을 처리하고 보내기 직전에 상태를 다시 확인한다. 보낼 시각은 notification.md 4장 표를 따른다(출연은 시각 기준, 강의는 날짜 기준) | 발행 모듈에 알림 책임을 두지 않는다 | — | 유효 | N4, policy.md "기한 계산". 미결: 운영 시간과의 충돌은 U6 |
| ND-030 | 출연 확인 부탁은 자동 확인 시각 − 3×24시간에 보낸다 | 불참은 출연 확인 기간 안에서만, 번복 없이 응답할 수 있다(출연 E3). 주말이 끼어도 방송 기록을 확인할 시간을 준다 | 하루 전(시간이 모자람) | 유효 | N4 |
| ND-031 | 구매 취소 마감 임박은 (구매일 + 7일) 09:00에 보낸다 | 자동 구매 확정이 (구매일 + 8일) 00:00이라 전날 낮 동안 구매 취소할 수 있다. 다른 앱의 하루 배치와 겹치지 않는다 | — | 유효 | N4. 미결: 09:00은 EC2 기동 시각과 겹친다, U6 |
| ND-032 | 강의 지급(`PAYOUT_COMPLETED_DAILY`)은 판매자·날짜(`paidAt`의 KST 날짜)마다 알림 하나로 묶는다. 지급이 올 때마다 금액·건수를 더하고, 다시 안 읽음으로 만들고, 발생 시각을 새로 고쳐 맨 위로 올린 뒤 실시간으로 보낸다. 같은 지급(`settlementId`)은 `notification_payout_item`으로 한 번만 더한다 | 강의 지급 배치가 하루에 여러 건을 한꺼번에 낸다 | — | 유효 | N5 |

## 8. 발행 모듈 이벤트 (계약 요청)

notification.md 7장의 이벤트 요청(출연 12종 + 🟡 3종 Kafka 발행, 강의 7종 + ⚠️ 4종 값 추가, 지갑 4종, 회원 2종, 고객센터 3종, 채팅 3종과 새 토픽 `chat.events.v1`)은 알림 모듈 단독 결정이 아니다. 각 발행 모듈 담당과 계약(N0)을 합의하기 전이므로 결정으로 옮기지 않았다. 합의되면 이벤트별로 ND를 더하고, 토픽·key는 [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표에 둔다.

## 9. 구현 순서

notification.md 8장(X1·N0~N10·M1~M8, P0~P2)은 결정이 아니라 작업 계획이다. 3.4단계에서 진행 문서와 이슈로 옮긴다. P0 완료 기준의 모순은 U2다.

## 10. 미결 항목

임의로 정하지 않는다. 정해지면 새 `ND-*`로 기록한다. 분류: 정책(업무 선택), 계약(다른 앱·Kafka 계약), 기술(구현 방식·설정), 불일치(문서 사이 차이), 검증(사실 확인 필요).

| ID | 질문 | 분류 | 현재 상태·근거 | 결정 주체 | 언제까지 |
| --- | --- | --- | --- | --- | --- |
| U1 | consumer group 이름 | 계약·불일치 | `kafka.md` 규칙 `<앱>.<목적>`(예 `billing-app.wallet-creation`), notification.md 2·9장 `notification.<목적>` `notification-app.<목적>`(폐기된 앱 이름). 후보: `notification.<목적>` + kafka.md에 모듈 예외 명시 / `auxiliary-app.notification-<목적>` (팀 결정 T2) | 팀(Kafka 규칙) + 알림 담당 | 알림 모듈 뼈대(N1) 전 |
| U2 | P0 완료 기준 | 정책 | notification.md 8장 "P0만으로 11장 완료 증거 시연 가능"인데 11장 "구매 완료 알림"은 P1 `LectureOrderPaidEvent`(M4)가 필요하다. 후보: 구매 완료를 P1 증거로 옮김 / M4 일부를 P0로 (T3) | 알림 담당 + 강의 담당 | 작업 계획 확정 전 |
| U3 | 종류별 문구 템플릿 | 정책 | notification.md 10장 "구현을 시작할 때 정한다" | 알림 담당(+ FE) | 이벤트 계약 합의(N0) |
| U4 | 지급 알림에 넣을 출연·강의 제목을 가져오는 방법 | 계약 | `PayoutCompletedEvent`에 제목이 없다. 이벤트에 더할지, 다른 이벤트로 받아 둘지 | 알림 + 지갑·강의·출연 담당 | N0 |
| U5 | 이벤트별 원본 대상 ID | 기술·계약 | 표가 없다. `ExchangePayoutFailedEvent`는 한 환전에서 여러 번 나고 지갑은 송금 시도 ID를 멱등 키로 쓴다(BD-130). 환전 ID를 쓰면 두 번째 실패 알림이 유일 인덱스에 막힌다 | 알림 담당 | 테이블 설계(N1) 전 |
| U6 | EC2 운영 시간(09:00~18:00 자동 시작·중지)과 예약 알림 | 정책 | 중지 시간의 `@Scheduled`는 돌지 않는다(최종 인프라 설계 "운영 시간"). 밤 시각 예약은 다음 날 기동 뒤 나가고, 09:00 예약은 기동 시각과 겹친다. ND-031의 근거로 든 다른 배치(01:00·04:00)도 꺼진 시간이다 (T5) | 팀 | 예약 배치(N10) 전 |
| U7 | 첫 배포 때 과거 이벤트 처리 | 정책·기술 | 새 그룹은 `auto-offset-reset=earliest`라 보존 72시간 안의 과거 이벤트(가입 환영 등)를 알림으로 만든다. 의도인지 정하지 않았다. 11장 "끈 동안 쌓인 이벤트가 모두 알림이 된다"도 72시간 안에서만 성립 (T5) | 알림 담당 + 팀 | 첫 배포 전 |
| U8 | 정지 회원 처리 | 정책 | notification.md 6.4 "탈퇴(이후 정지)한 회원"이 정지 회원도 빼는지 모호하다. 3.4장은 정지를 "보내지 않는 것"으로만 둔다 | 팀(회원 정책) | 알림 저장(N2) 전 |
| U9 | 하루 묶음과 20개 보존이 겹칠 때 | 기술 | 이미 밀려나 Soft Delete된 날짜 묶음에 같은 날 지급이 또 오면 유일 키 (종류, 받는 사람, 묶음 키)에 걸린다. 되살릴지, 무시할지 | 알림 담당 | 하루 묶음(N8) 전 |
| U10 | `AppearanceRecruitCanceledEvent` | 불일치·계약 | 4장 예약 취소와 7장 요청 목록에는 있고 3.1과 ND-017에는 없다 | 알림 + 출연 담당 | 출연 이벤트(M3) 전 |
| U11 | 채팅 단체방 알림 3종과 규칙 밖 종류 코드 | 계약·불일치 | 알림센터 `GROUP_MEMBERS_INVITED`·`GROUP_MEMBER_KICKED`·`GROUP_ROOM_CLOSED`가 채팅 방 안 안내(`NotificationKind`)와 이름이 같은데 뜻과 받는 사람이 다르다(강퇴: 채팅은 남은 참여자, 알림센터는 강퇴된 회원). 후보: 같은 이름 + 차이 명시 / 알림센터용 이름 (T4) | 알림 + 채팅 담당 | 채팅 이벤트(M8) 전 |
| U12 | 채팅과 알림센터에 같은 사건이 함께 뜨는 것 | 정책 | ND-004로 출연 요청·모집 지원 도착과 단체방 초대·강퇴·폐쇄는 양쪽에 다 뜬다. FE에서 어떻게 보일지 정하지 않았다 | 팀 + FE | 해당 알림 소비(N5·N9) 전 |
| U13 | 자원 예산 | 기술 | Hikari 5, 스케줄 스레드 2, limit 704Mi·`-Xmx256m`를 챗봇과 나눠 쓴다. README: 모듈을 더하는 PR에서 다시 정한다 | 알림 + 챗봇 + 인프라 담당 | 알림 모듈 추가 PR |
| U14 | WebSocket 경로의 Gateway 시간 제한 | 기술 | README: HTTPRoute 기본 30초·1MiB, SSE·WebSocket은 경로를 따로 만들어 시간 제한을 따로 둔다. notification.md는 "route를 aux-app으로"만 적었다 | 알림 + 인프라 담당 | Envoy route(N4) |
| U15 | STOMP 토큰 만료 헤더 `X-User-Token-Exp` | 기술·검증 | auxiliary global 필터와 Gateway `claimToHeaders`에 없다(README, chat-app global에서 복사할 때 뺌). 연결 티켓을 쓰는 모듈이 Gateway 설정과 함께 더한다 | 알림 + 인프라 담당 | STOMP(N3) |

### 팀 결정 필요 사항 (2026-11-06 조사 T1~T8)

| ID | 대상 | 상태 | 기록 위치 |
| --- | --- | --- | --- |
| T1 | BD-127 처리 | 미결 | `docs/billing/decisions.md` 10장 BD-127 |
| T2 | consumer group 이름 | 미결 | U1 |
| T3 | P0 완료 기준 | 미결 | U2 |
| T4 | 채팅 단체방 알림 코드 | 미결 | U11 |
| T5 | 운영 시간·예약 알림·과거 이벤트 | 미결 | U6, U7 |
| T6 | 루트 AGENTS 3장 동기 HTTP 예외 범위(위약금·보류 무효화·대사 조회·탈퇴 지갑) | 미결. 루트 AGENTS는 고치지 않았다 | `docs/billing/decisions.md` 10장 (2026-11-06 행) |
| T7 | Billing 기준 ERD | 미결 | `docs/billing/decisions.md` 10장 (2026-11-06 행) |
| T8 | chat-app 불변식(`apps/chat-app/docs/rules/invariants.md:11`, "알림 모듈이 생기면 `/user` 경로를 옮긴다") 변경 | 미결. 채팅 소유라 고치지 않았다. ND-004와 반대 방향이다 | 12장 |

## 11. 폐기된 옛 결정

#710 이전 문서에 있던 결정이다. 아래 위치에는 폐기 표시 없이 남아 있고 3.4단계 이후 각 소유자가 정리한다. (`docs/billing/CONTEXT.md` 9.4는 2026-11-06 Billing 문서 정리에서 대체 표시를 붙였다.)

| ID | 옛 결정 | 상태 | 남아 있는 곳 |
| --- | --- | --- | --- |
| ND-901 | 채팅 출연 알림을 알림 모듈로 옮기고, FE 전환 뒤 채팅 리스너를 지운다 | 폐기 → ND-004 | `apps/chat-app/docs/final-design.md` 1·4·5.5·7장, Q7, `apps/chat-app/docs/rules/decisions.md`·`scope.md`, `AppearanceNotificationEventListener` 주석, `final-project-infrastructure.md` Phase 4 |
| ND-902 | 채팅의 `/user` 경로(새 메시지 알림·안 읽은 수)를 알림 모듈로 옮기고 없앤다 | 폐기 → ND-004 | `final-design.md` 머리·4장, Q20, `apps/chat-app/docs/rules/invariants.md:11`(T8) |
| ND-903 | 알림을 별도 `notification-app`(모듈 앱 8개)으로 분리한다. 소비 그룹 `notification-app.<목적>` | 폐기 → ND-005 (→ ND-006·ND-007) | `final-project-infrastructure.md` 결론·목표 구조·Phase 4, `docs/architecture/infrastructure/k3s.md`, `docs/deploy/aws-k3s.md`, `final-design.md` 4·5장 |

## 12. 채팅 알림과의 경계

| 구분 | 채팅 알림 (`chat-app`) | 알림센터 (이 문서) |
| --- | --- | --- |
| 대상 | 출연 알림 메시지, 단체방 안내(`SYSTEM` 메시지), 실시간 새 메시지 알림 | 49+5종 알림센터 알림 |
| 문구 | FE 템플릿(채팅 결정) | 알림 모듈이 만들어 저장(ND-020) |
| 정책 소유 | 채팅 | 알림 |

- 채팅 내부 계약(`NotificationKind`, `notification_data`, 방 안 전달 구조)은 이 문서에 옮기지 않는다. 계약은 [`apps/chat-app/docs/contracts/notification-data.md`](../../apps/chat-app/docs/contracts/notification-data.md)가 원본이다.
- 이 문서가 다루는 채팅 관련 내용은 알림센터가 채팅 이벤트를 받는 조건, 받는 사람, 종류, 중복 처리뿐이다.
- 같은 이름의 `GROUP_*` 코드는 뜻과 받는 사람을 따로 확인한다(U11).
