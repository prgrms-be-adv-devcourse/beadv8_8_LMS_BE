# 8. 알림(notification) 모듈

상위 문서: [최종 프로젝트 인프라 설계](../final-project-infrastructure.md). 결정 기록은 #369(채팅에서 분리), #684(aux-app), #193(알림 기능)이다.

## 1. 범위

| 항목 | 결정 |
| --- | --- |
| 형태 | 알림센터. 화면 위쪽의 종 아이콘을 누르면 열리는 목록, 안 읽은 수 배지, 읽음 처리 |
| 채널 | 앱 안에서만 보낸다. 이메일·푸시는 없다. 그래서 로그인하지 못한 회원에게 가야 하는 알림(비밀번호 변경, 로그인 잠금, 탈퇴 복구 기한)은 범위 밖이다 |
| 받는 사람 | 유저·인플루언서·인증된 인플루언서. 일반 유저도 받는다. 관리자는 받지 않고 관리자 업무(검수·심사 요청, 새 문의)는 관리자 화면 목록에서 처리한다 |
| 채팅과의 관계 | 채팅의 기존 알림(출연 알림 메시지, 단체방 안내, `/user/queue/chats/notifications`)은 그대로 둔다. 알림 모듈은 같은 이벤트를 따로 받아 알림센터에 함께 보낸다. 채팅 새 메시지와 차단은 알림센터에 넣지 않는다(채팅의 안 읽은 메시지 수가 맡는다) |
| 규모 | 즉시 알림 49종, 예약 알림 5종 |

### 배경

알림은 채팅 모듈 안에서 시작했다(#229, #232). 출연 이벤트를 받으면 `ChatNotificationUseCase`가 두 회원의 1:1 채팅방에 `NOTIFICATION` 메시지를 남긴다. 이 방식은 채팅할 수 있는 회원끼리만 쓸 수 있고 보낸 사람이 있어야 하므로, 일반 유저인 강의 구매자나 시스템이 보내는 알림(지급 완료, 심사 결과, 문의 답변)을 담을 수 없다. 그래서 알림센터는 별도 모듈로 만들고, 채팅의 알림은 채팅의 일로 남긴다.

## 2. 구조

```
각 앱 ──(Kafka integration event)──▶ auxiliary-app
 (알림을 모른다)                       ├ search        검색
                                      ├ chatbot       고객센터 hybrid RAG
                                      └ notification  ──▶ PostgreSQL database `auxiliary`, schema `notification`
                                           │ 저장 commit 뒤
                                           └▶ Redis Pub/Sub ─▶ notification 모듈의 STOMP
                                                               `/user/queue/notifications`
프론트 재접속 ─▶ REST `/api/v1/notifications`로 최신 20개를 다시 읽는다 (DB가 원천)
```

- **배포 단위**: 검색·챗봇·알림을 JVM 앱 하나(`auxiliary-app`)에 모듈로 담는다(#684). 세미 프로젝트의 멀티모듈 모놀리스와 같은 구조다. notification 모듈은 앱 공통 database `auxiliary`의 자기 schema `notification`에만 테이블을 두고, 같은 앱의 다른 모듈과 테이블·트랜잭션을 나누지 않는다(AGENTS.md 경계 규칙의 auxiliary-app 예외, #714).
- **입력**: 각 모듈의 integration event([1장 Kafka](kafka.md))다. 발행하는 모듈은 알림의 존재를 모른다. 알림 모듈이 받는 사람, 종류, 문구를 정한다. 이벤트에는 문구가 아니라 사실(ID, 제목, 금액, 일시)을 담는다.
- **실시간 전달**: 저장이 commit된 뒤 Redis Pub/Sub으로 발행하고, notification 모듈의 STOMP가 받는 사람의 연결로 보낸다. 실시간 전달은 유실될 수 있으므로 재접속하면 REST 목록으로 맞춘다.
- **채팅 코드 재사용**: STOMP 연결 티켓(`ChatConnectionTicketHandshakeInterceptor`, `/api/v1/chats/ws-tickets`), Redis Pub/Sub 전달, Kafka 소비·DLT 설정, 유일 인덱스로 이벤트 중복을 흡수하는 방식을 가져와 쓴다.
- **회원 정보**: 받는 사람의 상태(탈퇴·정지)·역할·닉네임은 회원 이벤트로 만든 복제본(`notification_user`)에서 읽는다. 다른 모듈처럼 가입 이벤트로 만들고 변경 이벤트로 갱신한다.

### Kafka 소비

consumer group 이름은 `notification.<목적>`이다. 처리하지 못한 메시지는 다른 앱과 같은 규칙으로 `<topic>.<group>.DLT`에 보낸다.

| consumer group | 토픽 | 목적 |
| --- | --- | --- |
| `notification.member-replica` | `member.events.v1` | 회원 복제본 |
| `notification.member-event` | `member.events.v1` | 회원 알림 |
| `notification.appearance-event` | `appearance.events.v1` | 출연 알림, 예약 알림 |
| `notification.lecture-event` | `lecture.events.v1` | 강의 알림, 예약 알림 |
| `notification.billing-event` | `billing.events.v1` | 지갑·정산 알림 |
| `notification.customersupport-event` | `customersupport.events.v1` | 고객센터 알림 |
| `notification.chat-event` | `chat.events.v1` (새 토픽) | 단체방 알림 |

**채팅 Streams와의 경계(2026-10-29)**: 채팅 내부 전달 큐가 Redis Streams여도 알림 모듈의 입력은 Kafka다. 알림은 독립 consumer group으로 소비·재처리하고, chat DB 참조만 담긴 채팅 내부 Stream은 구독하지 않는다. 같은 Stream 그룹에 채팅과 알림 worker를 함께 넣지 않는다.

## 3. 알림 종류

**종류 코드 규칙**: 채팅의 `NotificationKind`처럼 원본 shared 이벤트 이름에서 `Event`를 뺀 UPPER_SNAKE를 쓴다(예: `AppearanceRequestSentEvent` → `APPEARANCE_REQUEST_SENT`). 아직 이벤트가 없으면 발행 모듈의 상태 enum과 도메인 메서드 이름으로 이벤트 이름을 정하고 같은 규칙으로 코드를 만든다. 채팅이 이미 쓰는 이름(`GROUP_*`)은 그대로 쓴다.

**이벤트 상태**: ✅ 지금 받을 수 있다. 🟡 shared에 있으나 Kafka로 나가지 않는다. ⚠️ 이벤트는 있으나 받는 사람 ID가 없다. 🆕 새 이벤트가 필요하다(7장).

### 3.1 출연 (15종)

| 알림 | 받는 사람 | 종류 코드 | 이벤트 |
| --- | --- | --- | --- |
| 출연 요청 도착 | 게스트 | `APPEARANCE_REQUEST_SENT` | ✅ `AppearanceRequestSentEvent` |
| 출연 요청 수락 (출연 성립) | 호스트 | `APPEARANCE_REQUEST_ACCEPTED` | 🆕 `AppearanceRequestAcceptedEvent` |
| 출연 요청 거절 | 호스트 | `APPEARANCE_REQUEST_REJECTED` | 🆕 `AppearanceRequestRejectedEvent` |
| 응답 전 취소 | 게스트 | `APPEARANCE_REQUEST_CANCELED` | 🆕 `AppearanceRequestCanceledEvent` |
| 만료 | 호스트, 게스트 | `APPEARANCE_REQUEST_EXPIRED` | 🆕 `AppearanceRequestExpiredEvent` |
| 모집 지원 도착 | 호스트 | `APPEARANCE_APPLICATION_SUBMITTED` | ✅ `AppearanceApplicationSubmittedEvent` |
| 지원 수락 (출연 성립) | 게스트 | `APPEARANCE_APPLICATION_SELECTED` | 🆕 `AppearanceApplicationSelectedEvent` |
| 미선정 (거절 또는 모집 마감) | 게스트 | `APPEARANCE_APPLICATION_NOT_SELECTED` | 🆕 `AppearanceApplicationNotSelectedEvent` |
| 모집 마감 | 호스트 | `APPEARANCE_RECRUIT_CLOSED` | 🆕 `AppearanceRecruitClosedEvent`. `closeType`이 `DEADLINE`일 때만 보낸다(조기 마감은 본인 행동) |
| 출연 취소 요청 도착 | 호스트 | `APPEARANCE_CANCEL_REQUESTED` | 🟡 `AppearanceCancelRequestedEvent` |
| 출연 취소 요청 수락 (합의 취소) | 게스트 | `APPEARANCE_CANCEL_REQUEST_ACCEPTED` | 🟡 `AppearanceCancelRequestAcceptedEvent` |
| 출연 취소 요청 거절 / 자동 거절 | 게스트 | `APPEARANCE_CANCEL_REQUEST_REJECTED` | 🟡 `AppearanceCancelRequestRejectedEvent` (`autoRejected`로 문구를 나눈다) |
| 호스트 일방 취소 | 게스트 | `APPEARANCE_CANCELED_BY_HOST` | 🆕 `AppearanceCanceledByHostEvent` |
| 자동 확인 | 호스트 | `APPEARANCE_CONFIRMED` | 🆕 `AppearanceConfirmedEvent`. `confirmType`이 `AUTO`일 때만 보낸다 |
| 불참 | 게스트 | `APPEARANCE_ABSENT` | 🆕 `AppearanceAbsentEvent` |

- **모집 전체 취소**는 종류를 따로 두지 않는다. 용어집대로 성립한 출연마다 호스트 일방 취소로 처리하므로, 출연 모듈이 출연마다 `AppearanceCanceledByHostEvent`, 대기 중인 지원마다 `AppearanceApplicationNotSelectedEvent`를 발행하고 알림은 기존 종류를 쓴다.
- 출연료·위약금을 실제로 받은 사실은 지갑의 `PAYOUT_COMPLETED`가 따로 알린다. 사건 알림과 돈 알림을 둘 다 보낸다.
- 보내지 않는 것: 등록 결과(호스트 본인 행동), 지원 취소(호스트), 호스트 출연 확인(본인 행동), 출연 리뷰 공개.

### 3.2 강의 (11종)

| 알림 | 받는 사람 | 종류 코드 | 이벤트 |
| --- | --- | --- | --- |
| 강의 검수 승인 | 판매자 | `LECTURE_APPROVED` | 🆕 `LectureApprovedEvent` |
| 강의 검수 반려 (사유) | 판매자 | `LECTURE_REJECTED` | 🆕 `LectureRejectedEvent` |
| 구매 완료 | 구매자 | `LECTURE_ORDER_PAID` | 🆕 `LectureOrderPaidEvent` |
| 결제 실패 (보류 거절) | 구매자 | `LECTURE_ORDER_HOLD_FAILED` | 🆕 `LectureOrderHoldFailedEvent` |
| 구매 취소 완료 (반환 끝) | 구매자 | `LECTURE_ORDER_ITEM_CANCELED` | 🆕 `LectureOrderItemCanceledEvent` |
| 자동 구매 확정 | 구매자 | `LECTURE_ORDER_ITEM_CONFIRMED` | 🆕 `LectureOrderItemConfirmedEvent`. `confirmationType`이 `AUTO`일 때만 보낸다 |
| 강의 지급 (하루 묶음, 5장) | 판매자 | `PAYOUT_COMPLETED_DAILY` | ✅ `PayoutCompletedEvent` (`sourceType` `LECTURE_ORDER_ITEM`) |
| 이의 제기 접수 (구매자: 접수됨, 판매자: 지급 중단) | 구매자, 판매자 | `LECTURE_OBJECTION_FILED` | 🆕 `LectureObjectionFiledEvent` |
| 이의 제기 기각·인정 | 구매자, 판매자 | `LECTURE_OBJECTION_RESOLVED` | ⚠️ `LectureObjectionResolvedEvent` (`accepted`로 문구를 나눈다) |
| 이의 제기 반환 완료 | 구매자 | `LECTURE_OBJECTION_RETURN_COMPLETED` | ⚠️ `LectureObjectionReturnCompletedEvent` |
| 이의 제기 철회 | 판매자 | `LECTURE_OBJECTION_WITHDRAWN` | ⚠️ `LectureObjectionWithdrawnEvent` |

- 이의 제기 접수는 바로 접수된 경우와 고객센터 재시도 배치가 나중에 접수한 경우 모두 강의 모듈이 접수할 때 발행하는 이벤트 하나로 알린다. 구매자에게 같은 사실을 두 번 보내지 않는다.
- 보내지 않는 것: 검수 요청 도착(관리자), 강의 판매·구매 취소 발생(판매자), 반환 자동 처리 멈춤(관리자 경보).

### 3.3 지갑·정산 (11종)

| 알림 | 받는 사람 | 종류 코드 | 이벤트 |
| --- | --- | --- | --- |
| 충전 완료 | 충전한 회원 | `CHARGE_COMPLETED` | ✅ `ChargeCompletedEvent` |
| 충전 실패 | 충전한 회원 | `CHARGE_FAILED` | 🆕 `ChargeFailedEvent` |
| 환불 완료 (전부·일부) | 신청한 회원 | `REFUND_COMPLETED` | ✅ `RefundCompletedEvent` |
| 환불 실패 | 신청한 회원 | `REFUND_FAILED` | 🆕 `RefundFailedEvent` |
| 환불 운영자 확인 필요 | 신청한 회원 | `REFUND_MANUAL_REVIEW_REQUIRED` | 🆕 `RefundManualReviewRequiredEvent` |
| 지급 완료 (출연료, 위약금) | 게스트 | `PAYOUT_COMPLETED` | ✅ `PayoutCompletedEvent` (`sourceType` `APPEARANCE_REQUEST`·`RECRUIT_APPEARANCE`, `payoutType` `NORMAL`·`PENALTY`) |
| 환전 거절 | 신청한 회원 | `EXCHANGE_REJECTED` | ✅ `ExchangeRejectedEvent` |
| 환전 승인 (정산일) | 신청한 회원 | `EXCHANGE_APPROVED` | 🆕 `ExchangeApprovedEvent` |
| 환전 송금 완료 | 신청한 회원 | `EXCHANGE_COMPLETED` | ✅ `ExchangeCompletedEvent` |
| 환전 송금 실패 (지연) | 신청한 회원 | `EXCHANGE_PAYOUT_FAILED` | ✅ `ExchangePayoutFailedEvent` |
| 환전 최종 실패 (보류 HP 반환) | 신청한 회원 | `EXCHANGE_FINAL_FAILED` | ✅ `ExchangeFinalFailedEvent` |

- `PayoutCompletedEvent` 하나를 원천으로 나눈다. 출연 원천은 `PAYOUT_COMPLETED`로 한 건씩, 강의 원천은 `PAYOUT_COMPLETED_DAILY`로 하루씩 묶어 보낸다.
- 지급 알림에는 출연·강의 제목을 넣는다. 이벤트에 제목이 없으므로 가져오는 방법은 구현할 때 정한다.
- 환전 이벤트는 순서가 바뀌어 올 수 있다([지갑 CONTEXT](../../billing/CONTEXT.md) 9장). 목록은 이벤트의 업무 시각으로 정렬한다.

### 3.4 회원 (5종)

| 알림 | 받는 사람 | 종류 코드 | 이벤트 |
| --- | --- | --- | --- |
| 가입 환영 | 새 회원 | `USER_JOINED` | ✅ `UserJoinedEvent` |
| 채널 인증 승인 (자동·관리자) | 회원 | `INFLUENCER_CHANNEL_APPROVED` | ✅ 추정: `UserModifiedEvent`에서 역할이 유저 → 인플루언서로 바뀜 |
| 채널 인증 반려 (사유) | 회원 | `INFLUENCER_CHANNEL_REJECTED` | 🆕 `InfluencerChannelRejectedEvent` |
| 승급 승인 | 회원 | `INFLUENCER_UPGRADE_REQUEST_APPROVED` | ✅ 추정: `UserModifiedEvent`에서 역할이 인플루언서 → 인증된 인플루언서로 바뀜 |
| 승급 반려 (사유) | 회원 | `INFLUENCER_UPGRADE_REQUEST_REJECTED` | 🆕 `InfluencerUpgradeRequestRejectedEvent` |

- 승인은 회원 복제본의 이전 역할과 새 역할을 비교해 알아낸다. 자동 승인과 관리자 승인을 구분할 수 없으므로 같은 종류·같은 문구로 보낸다.
- 보내지 않는 것: 비밀번호 변경, 로그인 잠금, 정지, 탈퇴(알림센터를 볼 수 없다), 승인 요청·승급 신청 도착(관리자).

### 3.5 고객센터 (4종)

| 알림 | 받는 사람 | 종류 코드 | 이벤트 |
| --- | --- | --- | --- |
| 문의 답변 도착 | 문의한 회원 | `INQUIRY_ANSWERED` | 🆕 `InquiryAnsweredEvent` |
| 답변 수정 | 문의한 회원 | `INQUIRY_REPLY_EDITED` | 🆕 `InquiryReplyEditedEvent` |
| 이의 제기 접수 불가 (재시도 뒤) | 구매자 | `LECTURE_OBJECTION_FILE_REJECTED` | 🆕 `LectureObjectionFileRejectedEvent` (고객센터 발행) |
| 반환 지연 | 구매자 | `LECTURE_OBJECTION_RETURN_ACTION_REQUIRED` | ⚠️ `LectureObjectionReturnActionRequiredEvent` |

- 이의 제기 접수는 3.2의 `LECTURE_OBJECTION_FILED`로 보낸다. 바로 거절된 접수는 화면에서 바로 보이므로, 재시도 배치가 접수 불가로 끝낼 때만 보낸다.
- 보내지 않는 것: 새 문의 도착(관리자), 답변 삭제, FAQ 등록·수정.

### 3.6 채팅 (3종)

| 알림 | 받는 사람 | 종류 코드 | 이벤트 |
| --- | --- | --- | --- |
| 단체방 초대 | 이번 초대 요청으로 실제 참여한 회원 | `GROUP_MEMBERS_INVITED` | 🆕 `GroupMembersInvitedEvent` |
| 단체방 강퇴 | 강퇴된 회원 | `GROUP_MEMBER_KICKED` | 🆕 `GroupMemberKickedEvent` |
| 단체방 폐쇄 | 남은 참여자 (폐쇄한 호스트 제외) | `GROUP_ROOM_CLOSED` | 🆕 `GroupRoomClosedEvent` |

- 채팅은 초대·강퇴·폐쇄 안내를 방 안 시스템 메시지(`notifyRule: NONE`)로만 남긴다. 초대된 회원과 강퇴된 회원은 방을 열지 않으면 알 방법이 없으므로 알림센터로 알린다. 채팅의 방 안 안내는 바꾸지 않고, 채팅이 같은 시점에 이벤트를 더 발행한다.
- 보내지 않는 것: 새 메시지, 차단, 나가기, 호스트 자격 상실, 일시 중지·재개.

## 4. 예약 알림

정한 시각에 보낸다. 알림 모듈이 이벤트를 받아 예약을 만들고, 취소 이벤트가 오면 예약을 취소한다. 발행 모듈은 예약을 모른다. 예약 배치는 1분마다 보낼 시각이 지난 예약을 처리하고, 보내기 직전에 예약 상태를 다시 확인한다.

| 알림 | 받는 사람 | 종류 코드 | 보낼 시각 | 예약을 만드는 이벤트 | 예약을 취소하는 이벤트 |
| --- | --- | --- | --- | --- | --- |
| 출연 요청 만료 임박 | 게스트 | `APPEARANCE_REQUEST_EXPIRING` | 수락 기한 − 12시간 | `AppearanceRequestSentEvent` | 요청 수락·거절·응답 전 취소 |
| 모집 마감 임박 | 호스트 | `APPEARANCE_RECRUIT_CLOSING` | 모집 마감 일시 − 24시간 | 🆕 `AppearanceRecruitOpenedEvent` | 모집 마감(조기 마감 포함), 모집 전체 취소 |
| 방송 임박 | 호스트, 게스트 | `APPEARANCE_STARTING` | 방송 예정 일시 − 24시간 | 요청 수락, 지원 수락 | 합의 취소, 호스트 일방 취소 |
| 출연 확인 부탁 | 호스트 | `APPEARANCE_CONFIRM_PERIOD_ENDING` | 자동 확인 시각 − 3×24시간 (= 방송 예정 일시 + 4×24시간) | 요청 수락, 지원 수락 | 출연 확인(호스트·자동), 불참, 합의 취소, 호스트 일방 취소 |
| 구매 취소 마감 임박 | 구매자 | `LECTURE_ORDER_ITEM_CONFIRMING` | (구매일 + 7일) 09:00 | `LectureOrderPaidEvent` (주문 항목마다) | 구매 취소, 구매 확정(직접·첫 재생·자동) |

- 출연은 시각 기준, 강의는 날짜 기준이다([정책](../../policy.md) "기한 계산").
- **출연 확인 부탁을 3일 전에 보내는 이유**: 불참은 출연 확인 기간 안에서만 응답할 수 있고 번복할 수 없다([출연 문서](../../appearance/module.md) E3). 하루 전이면 방송 기록을 확인할 시간이 모자라므로, 주말이 끼어도 확인할 수 있게 3일 전에 보낸다.
- **구매 취소 마감 임박을 09:00에 보내는 이유**: 자동 구매 확정이 (구매일 + 8일) 00:00이므로 전날 낮 동안 구매 취소할 수 있다. 다른 앱의 하루 배치(01:00 강의 지급, 04:00~04:30 대사, 10:00 팔로워 갱신, 10:30 마스킹, 10:05~23:05 환전 정산)와 겹치지 않는다.

## 5. 하루 묶음 알림

강의 지급(`PAYOUT_COMPLETED_DAILY`)은 판매자·날짜마다 알림 하나로 묶는다. 예: "오늘 강의 지급 12,000 HP가 지급되었습니다(50건)".

- 날짜는 `paidAt`의 KST 날짜다. 강의 지급 배치가 매일 01:00에 돌므로 하루치가 대부분 한 번에 온다.
- 지급이 올 때마다 그 알림의 금액·건수를 더하고, 다시 안 읽음으로 만들고, 발생 시각을 새로 고쳐 목록 맨 위로 올린 뒤 실시간으로 보낸다.
- 같은 지급(`settlementId`)이 다시 와도 한 번만 더하도록, 더한 지급 ID를 `notification_payout_item`에 기록한다.

## 6. 데이터, API, 처리 규칙

### 6.1 테이블 (database `auxiliary`, schema `notification`)

| 테이블 | 주요 컬럼 | 규칙 |
| --- | --- | --- |
| `notification` | 받는 사람 ID, 종류(`kind`), 원본 이벤트 종류, 원본 대상 ID, 이동 대상(종류·ID), 문구, 값(`jsonb`, 형식 버전 `v`), 묶음 키(묶음 알림의 날짜), 발생 시각, 읽은 시각, 만든 시각, 삭제 시각 | 유일 인덱스 (원본 이벤트 종류, 원본 대상 ID, 받는 사람), 묶음은 (종류, 받는 사람, 묶음 키). 삭제된 행도 유일 인덱스에 남긴다 |
| `notification_payout_item` | 지급 ID(`settlementId`), 묶음 알림 ID | 지급 ID 유일 |
| `notification_schedule` | 받는 사람 ID, 종류, 대상 ID, 보낼 시각, 상태(예약·보냄·취소) | (종류, 대상 ID, 받는 사람) 유일 |
| `notification_user` | 회원 ID, 닉네임, 역할, 상태, 버전 | 회원 복제본. 채팅의 `chat_user`와 같은 방식 |

### 6.2 보존: 최신 20개

- 기간으로 지우지 않는다. 회원마다 삭제되지 않은 알림을 **발생 시각 기준 최신 20개**까지만 둔다.
- 새 알림을 저장하는 트랜잭션에서 21번째부터 오래된 순으로 삭제 시각을 채운다(Soft Delete, [정책](../../policy.md) 7장).
- 밀려난 알림의 이벤트가 다시 와도 유일 인덱스에 걸려 다시 생기지 않는다.
- 안 읽은 수는 남은 20개 안에서 센다. 배지는 최대 20이다.

### 6.3 API

모든 API는 로그인 회원 본인의 알림만 다룬다. Envoy에 `/api/v1/notifications`(REST, WebSocket) route를 auxiliary-app으로 둔다.

| 기능 | API | 비고 |
| --- | --- | --- |
| 목록 | `GET /api/v1/notifications` | 최신 20개. cursor 없음 |
| 안 읽은 수 | `GET /api/v1/notifications/unread-count` | |
| 읽음 | `PATCH /api/v1/notifications/{notificationId}/read` | 알림을 눌렀을 때 부른다. 목록을 연 것만으로는 읽지 않는다 |
| 전체 읽음 | `PATCH /api/v1/notifications/read-all` | |
| 연결 티켓 | `POST /api/v1/notifications/ws-tickets` | 채팅 `/api/v1/chats/ws-tickets`와 같은 방식 |
| WebSocket | `/api/v1/notifications/ws` | 받는 곳 `/user/queue/notifications`. 프레임에 알림 한 건(묶음은 갱신된 알림)과 안 읽은 수를 담는다 |

### 6.4 처리 규칙

1. **받는 사람 확인**: `notification_user`에서 탈퇴(이후 정지)한 회원이면 알림과 예약을 만들지 않는다.
2. **중복**: 같은 이벤트를 다시 받아도 유일 인덱스로 한 번만 만든다.
3. **문구**: 알림 모듈이 만들어 저장한다. 값도 함께 저장해 문구를 다시 만들 수 있게 한다. 종류별 문구 템플릿은 구현을 시작할 때 정한다.
4. **순서**: 목록은 이벤트의 업무 시각(발생 시각)으로 정렬한다. 받은 순서가 아니다.
5. **실시간**: 저장 commit 뒤 Redis Pub/Sub → STOMP로 보낸다. 유실되면 재접속 때 목록 API로 맞춘다.
6. **대상이 삭제된 경우**: 알림은 남긴다. 눌러서 이동한 화면이 "삭제된 ○○"으로 보여 준다.
7. **채팅과 동시 전송**: 출연 요청 도착·모집 지원 도착과 단체방 초대·강퇴·폐쇄는 채팅도 자기 방식으로 계속 보낸다. consumer group이 달라 서로 영향을 주지 않는다.

## 7. 발행 모듈에 요청할 이벤트

알림 모듈이 쓰는 이벤트다. 모듈마다 담당자가 `common/shared` 계약과 발행을 만든다. 이벤트 이름은 3장의 규칙을 따른다.

| 모듈 (토픽) | 이벤트 | 담을 값 |
| --- | --- | --- |
| 출연 (`appearance.events.v1`) | 🟡 `AppearanceCancelRequestedEvent`, `AppearanceCancelRequestAcceptedEvent`, `AppearanceCancelRequestRejectedEvent`를 Kafka로 발행 | 이미 있다 |
| | 🆕 `AppearanceRequestAcceptedEvent`, `AppearanceRequestRejectedEvent`, `AppearanceRequestCanceledEvent`, `AppearanceRequestExpiredEvent` | 요청서 ID, 출연 ID(수락), 호스트·게스트 ID, 제목, 출연료, 방송 예정 일시 |
| | 🆕 `AppearanceApplicationSelectedEvent`, `AppearanceApplicationNotSelectedEvent` | 지원 ID, 모집글 ID, 출연 ID(수락), 호스트·게스트 ID, 모집글 제목, 방송 예정 일시 |
| | 🆕 `AppearanceRecruitOpenedEvent`, `AppearanceRecruitClosedEvent`, `AppearanceRecruitCanceledEvent` | 모집글 ID, 호스트 ID, 제목, 모집 마감 일시, `closeType`(마감) |
| | 🆕 `AppearanceCanceledByHostEvent`, `AppearanceConfirmedEvent`, `AppearanceAbsentEvent` | 출연 ID, 호스트·게스트 ID, 제목, 방송 예정 일시, 위약금(일방 취소), `confirmType`(확인) |
| 강의 (`lecture.events.v1`) | ⚠️ `LectureObjectionResolvedEvent`, `LectureObjectionReturnCompletedEvent`, `LectureObjectionWithdrawnEvent`, `LectureObjectionReturnActionRequiredEvent`에 값 추가 | `buyerUserId`, `sellerUserId`, 강의 제목 |
| | 🆕 `LectureApprovedEvent`, `LectureRejectedEvent` | 강의 ID, 판매자 ID, 제목, 반려 사유 |
| | 🆕 `LectureOrderPaidEvent`, `LectureOrderHoldFailedEvent` | 주문 ID, 구매자 ID, 주문 항목(항목 ID, 강의 ID, 제목, 금액), 구매 시각 |
| | 🆕 `LectureOrderItemCanceledEvent`, `LectureOrderItemConfirmedEvent` | 주문 항목 ID, 구매자 ID, 제목, 반환 금액(취소), `confirmationType`(확정) |
| | 🆕 `LectureObjectionFiledEvent` | 문의 ID, 주문 항목 ID, 구매자·판매자 ID, 제목 |
| 지갑 (`billing.events.v1`) | 🆕 `ChargeFailedEvent`, `RefundFailedEvent`, `RefundManualReviewRequiredEvent`, `ExchangeApprovedEvent` | 회원 ID, 대상 ID, 금액, 정산일(환전 승인) |
| 회원 (`member.events.v1`) | 🆕 `InfluencerChannelRejectedEvent`, `InfluencerUpgradeRequestRejectedEvent` | 회원 ID, 반려 사유 |
| 고객센터 (`customersupport.events.v1`) | 🆕 `InquiryAnsweredEvent`, `InquiryReplyEditedEvent` | 문의 ID, 작성자 ID, 제목, 유형 |
| | 🆕 `LectureObjectionFileRejectedEvent` (재시도 배치가 접수 불가로 끝낼 때) | 문의 ID, 구매자 ID, 주문 항목 ID |
| 채팅 (`chat.events.v1`, 새 토픽) | 🆕 `GroupMembersInvitedEvent`, `GroupMemberKickedEvent`, `GroupRoomClosedEvent` | 방 ID, 모집글 ID·제목, 호스트 ID, 대상 회원 ID(초대는 참여한 회원 목록, 폐쇄는 남은 참여자 목록) |

## 8. 구현 순서

| ID | 우선순위 | 작업 | 담당 | 선행 |
| --- | --- | --- | --- | --- |
| X1 | P0 | auxiliary-app 생성 (모듈 구조, k8s, CI) | 검색·챗봇·알림 공동 | 없음 |
| N0 | P0 | 이벤트 계약(7장 값) 합의, 문구 템플릿 확정 | 알림 | 없음 |
| N1 | P0 | notification 모듈 뼈대: database, 테이블, 회원 복제본, Kafka 소비·DLT 설정 | 알림 | X1 |
| N2 | P0 | 알림 저장, 20개 유지, REST API | 알림 | N1 |
| N3 | P0 | STOMP, 연결 티켓, Redis Pub/Sub 전달 | 알림 | N1 |
| N4 | P0 | Envoy route (REST, WebSocket) | 알림 | X1 |
| N5 | P0 | ✅ 이벤트 소비: 출연 요청 도착·모집 지원 도착, 지갑 7종(묶음 제외), 회원 가입·승인 추정 2종 | 알림 | N0, N2 |
| M1 | P0 | 출연: 출연 취소 요청 이벤트 3종 Kafka 발행 | 출연 | 없음 |
| M2 | P0 | 강의: 이의 제기 이벤트 4종에 구매자·판매자 ID 추가 | 강의 | N0 |
| N6 | P0 | M1·M2 이벤트 소비 | 알림 | M1 또는 M2, N2 |
| M3 | P1 | 출연 🆕 이벤트 12종 | 출연 | N0 |
| M4 | P1 | 강의 🆕 이벤트 7종 | 강의 | N0 |
| M5 | P1 | 지갑 🆕 이벤트 4종 | 지갑 | N0 |
| M6 | P1 | 회원 🆕 이벤트 2종 | 회원 | N0 |
| M7 | P1 | 고객센터 🆕 이벤트 3종 | 고객센터 | N0 |
| N7 | P1 | M3~M7 이벤트 소비 (모듈별로 따로) | 알림 | 해당 M, N2 |
| N8 | P1 | 강의 지급 하루 묶음 | 알림 | N2 |
| M8 | P2 | 채팅: 단체방 이벤트 3종, `chat.events.v1` 토픽 | 채팅 | N0 |
| N9 | P2 | M8 이벤트 소비 | 알림 | M8, N2 |
| N10 | P2 | 예약 배치와 예약 알림 5종 | 알림 | N2. 출연 요청 만료 임박은 N5, 출연 예약 3종은 M3, 강의 예약은 M4 |

- P0: 뼈대와, 지금 있는 이벤트나 작은 수정으로 끝까지 이어지는 알림. P0만으로 11장 완료 증거를 시연할 수 있다.
- P1: 거래·돈에 영향이 있는 새 이벤트와 하루 묶음.
- P2: 채팅 단체방과 예약 알림.

### 병렬로 할 수 있는 작업

```
선행 없이 바로 시작
├ 알림          N0
├ 공동          X1
├ 출연 담당     M1 ─(N0 뒤)─▶ M3
└ 다른 담당     N0 뒤에 M2·M4(강의), M5(지갑), M6(회원), M7(고객센터), M8(채팅)

알림 쪽
X1 ─▶ N1 ─┬▶ N2 ─┬▶ N5 ─▶ N6 ─▶ N7(모듈별) ─▶ N9
          │      ├▶ N8
          │      └▶ N10
          └▶ N3
X1 ─▶ N4
```

- 발행 작업 M1~M8은 서로 독립이다. 각자 다른 shared 이벤트를 더하므로 동시에 해도 충돌하지 않는다. 알림 쪽 작업(X1~N4)과도 독립이고, 둘을 잇는 것은 N0의 이벤트 계약뿐이다. M1은 있는 이벤트를 Kafka로 내보내기만 하므로 N0도 기다리지 않는다.
- 알림 쪽에서는 N1 뒤에 N2와 N3을, X1 뒤에 N4를 함께 할 수 있다. N7은 모듈마다 독립이라 발행을 먼저 끝낸 모듈부터 붙인다. N8·N10은 N2 뒤라면 소비 작업과 병렬이다.

## 9. 결정 기록

| 항목 | 결정 |
| --- | --- |
| 배포 단위 | 별도 `notification-app`을 만들지 않고 auxiliary-app 안의 모듈로 둔다(#684). database는 앱 공통 `auxiliary`, schema는 `notification`이다(#714) |
| consumer group | `notification.<목적>` |
| 실시간 전달 | auxiliary-app notification 모듈의 자체 STOMP. chat-app 연결을 빌리지 않는다 |
| 채팅 알림 | 옮기거나 없애지 않는다. 채팅과 알림센터가 같은 이벤트로 함께 보낸다. 채팅 모듈의 "알림" 책임은 그대로다 |
| 관리자 | 알림센터를 쓰지 않는다 |
| 이메일·푸시 | 쓰지 않는다 |
| 보존 | 기간 없이 회원별 최신 20개 |
| 읽음 | 알림을 눌렀을 때 |
| 문구 | 알림 모듈이 만든다. 템플릿은 구현을 시작할 때 정한다 |
| 이의 제기 접수 | 강의의 `LectureObjectionFiledEvent` 하나로 구매자·판매자에게 보낸다 |
| 대상이 삭제된 알림 | 남기고, 이동한 화면에서 "삭제된 ○○"으로 보인다 |
| 지급 알림 제목 | 넣는다. 가져오는 방법은 구현할 때 정한다 |
| 예약 시각 | 3장·4장 표 |

## 10. 구현할 때 정할 것

- 종류별 문구 템플릿
- 지급 알림에 넣을 출연·강의 제목을 가져오는 방법

## 11. 완료 증거

- 일반 유저 계정이 충전 완료·구매 완료 알림을 받는다.
- 같은 integration event를 두 번 소비해도 알림은 한 번만 생긴다.
- 실시간 알림이 WebSocket으로 도착한다. 실시간 전달을 일부러 유실시켜도 재접속 후 목록이 같다.
- 알림이 21개가 되면 가장 오래된 알림이 목록에서 빠지고, 그 알림의 이벤트를 다시 소비해도 되살아나지 않는다.
- auxiliary-app을 끈 동안 쌓인 이벤트가 재시작 후 consumer lag가 줄면서 모두 알림이 된다.
- 채팅의 출연 알림 메시지도 그대로 남는다.
