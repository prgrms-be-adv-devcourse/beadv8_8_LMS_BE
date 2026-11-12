# 알림(notification) 모듈 — 알림 종류

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> - 이 문서는 알림센터 알림 종류별 명세의 **유일한 원본**이다. 즉시 알림 49종, 예약 알림 5종, 하루 묶음 알림의 종류 코드·받는 사람·원본 이벤트·발생 조건·보내지 않는 경우를 둔다([ND-011](decisions.md#4-알림-종류와-받는-사람): "notification.md 3·4장 표가 원본이다(3.4단계에서 알림 종류 문서로 옮긴다)").
> - 원천은 [알림 설계](../architecture/infrastructure/notification.md)(이하 N, #710) 3장(알림 종류), 4장(예약 알림), 5장(하루 묶음 알림), 7장(발행 모듈에 요청할 이벤트)이다. 알림 이름·받는 사람·종류 코드·이벤트 이름은 원문 그대로 옮겼고, 원문에 없는 값은 "미정"으로 두었다(관련 미결이 있으면 U 번호).
> - 정책 결정과 그 이유는 [결정 기록](decisions.md)의 `ND-*`가 원본이다. 이 문서는 ND 번호로 참조만 한다.
> - 채팅 방 안 안내(`SYSTEM` 메시지)와 채팅 `NotificationKind` 계약은 채팅 소유라 넣지 않는다([결정 기록 12장](decisions.md#12-채팅-알림과의-경계), [`notification-data.md`](../../apps/chat-app/docs/contracts/notification-data.md)).
> - **구현 상태**: 2026-11-06 기준 알림 모듈 코드는 없다. 이 문서의 내용은 정책 명세이고 구현·테스트 상태가 아니다.
>
> 변경 이력
> - 2026-11-06 처음 작성: N 3·4·5·7장의 표를 옮겼다. 이벤트 계약 상태를 `common/shared` 코드로 다시 확인해 덧붙였다.
> - 2026-11-06 계약 판정 추가: 발행 앱 코드(dev) 조사로 행마다 충족·부분 충족·미충족과 근거, 재사용 후보(`AppearanceChangedEvent`·`LectureChangedEvent`·`LecturePurchaseChangedEvent`·`UserModifiedEvent`)의 한계를 이벤트 계약 상태 칸에 덧붙였다. 출연 절에 `AppearanceChangedEvent` 박스, 0.3에 U17, 7장에 U16, 채널 인증 승인 행에 U18을 표시했다. 원본 이벤트 이름·받는 사람·정책은 바꾸지 않았다.
> - 2026-11-06 미결 목록에 U19(닉네임 파기)를 더했다.
> - 2026-11-09 ND-050·ND-051 반영(#801): 0장 문구·파기 닉네임 설명과 9장 U3·U19를 맞췄다.
> - 2026-11-09 decisions.md ND-037~ND-044 반영: 1.1~6.1의 요청 이벤트 값 표를 [이벤트 계약 요청](event-contracts.md)으로 옮기고 참조만 남겼다. 지급 알림 제목(ND-040), 채널 인증 승인 원본 이벤트(ND-042), 단체방 이름(ND-043), 모집 전체 취소(ND-041), 문구(ND-039)를 반영하고 9장에서 결정된 U4·U10·U18을 뺐다.
> - 2026-11-09 decisions.md ND-033~ND-036 반영: 0.2 원본 대상 ID 칸 설명에 ND-033, 0.3에 ND-035·ND-036, 7장에 ND-034를 참조로 넣고, 9장 미결 목록은 남은 부분만 남겼다. 종류별 행의 원본 대상 ID "미정(U5)"는 그대로 두었다.
> - 2026-11-09 #794 리뷰 반영: 7장 예약보다 먼저 온 취소 항목에 예약 짝 키 위치(event-contracts 0.3)를 더했다.
> - 2026-11-09 ND-045 반영: 0.2 원본 대상 ID 칸 설명과 9장 U5에 회차 번호 규칙과 이벤트 계약 요청 위치를 더했다.
> - 2026-11-11 decisions.md ND-069~ND-079 반영(#859): N5 11종 행의 이동 대상(ND-074)·원본 대상 ID(ND-073)를 채우고, 0.3에 정지 회원(ND-072)·알림 시작 시각(ND-076)·Backfill(ND-077)·대체 시각(ND-081)을, 0.5 N5 문구(ND-075)를 더했다. 승급 승인 종류 코드(ND-071)와 9장 미결 목록을 맞췄다.
> - 2026-11-10 #818(강의 담당): 이의 제기 4종 행의 이벤트 계약 상태 칸에 값 추가 사실을 덧붙였다. 2026-11-06 판정은 그대로 두었다.
> - 2026-11-11 decisions.md ND-082~ND-087 반영(#862): N6 7종 행의 이동 대상(ND-084)·원본 대상 ID(ND-083)를 채우고, 0.6에 N6 문구(ND-085)를 더했다. 출연 취소 요청 3종 행에 #817 발행 사실을 덧붙였다(2026-11-06 판정은 그대로 둔다). 0.5(N5 11종 문구)는 N5(#859)가 더한다.
> - 2026-11-12 decisions.md ND-097~ND-102 반영(N8 사전 결정, #890): 1.1 종류 코드 칸, `PAYOUT_COMPLETED_DAILY` 행의 이동 대상(ND-102)·관련 ND·미결 칸, 8장(발생 시각·밀려난 묶음·문구·종류 코드 행), 9장 U11 행을 고쳤다. 2026-11-06 판정은 그대로 둔다. 같은 날 N8 구현 때 8장에 확정 문구와 저장 행(ND-103)을 더했다.
> - 2026-11-12 decisions.md ND-105~ND-108 반영(N7 지갑 묶음, #907): 지갑 4종 행의 이동 대상(ND-107)·원본 대상 ID(ND-106)·관련 ND를 채우고 #873 발행 사실을 덧붙였다(2026-11-06 판정은 그대로 둔다). 0.7에 지갑 4종 문구(ND-108)를 더했다.
> - 2026-11-12 decisions.md ND-110~ND-113 반영(N7 출연 묶음, #908): 출연 10종 행의 이동 대상(ND-112)·원본 대상 ID(ND-111)·관련 ND를 채우고 #871 발행 사실을 덧붙였다(2026-11-06 판정은 그대로 둔다). 0.8에 출연 10종 문구(ND-113)를 더했다.

## 0. 읽는 법

### 0.1 이벤트 계약 상태

알림의 정책 상태(유효·미결, [결정 기록 0장](decisions.md#0-읽는-법))와 따로, 원본 이벤트를 지금 받을 수 있는지를 "이벤트 계약 상태" 칸에 적는다. 표시는 N 3장 머리의 정의를 그대로 쓴다.

| 표시 | 뜻 (N 3장 원문) |
| --- | --- |
| ✅ | 지금 받을 수 있다. |
| 🟡 | shared에 있으나 Kafka로 나가지 않는다. |
| ⚠️ | 이벤트는 있으나 받는 사람 ID가 없다. |
| 🆕 | 새 이벤트가 필요하다(7장). |

- **원본 표시는 N 작성 시점(#710, 2026-11-05) 기준이다.**
- **재확인(2026-11-06)**: `common/shared`에서 이벤트 레코드가 있는지, `@Externalized`로 Kafka에 나가는지, 받는 사람 ID 필드가 있는지를 코드로 확인해 덧붙였다. 🆕 이벤트는 레포 전체 `*.java`에서 이름을 검색해 0건인지 확인했다. 원본 표시와 달라진 행은 없다.
- **계약 판정(2026-11-06)**: 원본 표시와 따로, 알림이 요구하는 사실·받는 사람·값을 지금 Kafka로 받을 수 있는지를 발행 앱 코드(dev)로 판정해 "2026-11-06 판정"으로 덧붙였다. 판정은 원본 이벤트를 바꾸지 않는다. 대체 후보는 참고이며 채택한 것이 아니다.

| 판정 | 뜻 |
| --- | --- |
| 충족 | 원본 이벤트가 Kafka로 나가고 받는 사람 ID와 발생 조건 판단에 필요한 값이 있다(제목 등 표시 값 부족은 별도 미결) |
| 부분 충족 | 원본 이벤트는 없지만 Kafka로 나가는 다른 이벤트로 사실 일부를 알 수 있다. 사건 구분·필드가 모자란다 |
| 미충족 | Kafka로 받을 수 있는 이벤트가 없거나, 있어도 받는 사람 ID가 없거나 사건을 식별할 수 없다 |

- 공통 사실(2026-11-06 조사): 각 앱은 Modulith outbox + shared 레코드 `@Externalized`로 발행하고 헤더 `eventType`·`eventVersion=1`을 붙이며 앱별 재발행 배치가 있다. 이벤트 ID가 없어 받는 쪽은 (eventType, 대상 ID)로 중복을 거른다. 새 이벤트마다 shared 레코드 `@Externalized`, 발행 앱 외부화 설정의 eventType 헤더, [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표 갱신이 필요하다. customer-support-app은 `lecture.events.v1`의 모르는 타입을 무시해(`LectureEventTypeResolver`) 새 강의 이벤트가 DLT로 가지 않는다.
- 토픽과 Kafka key는 [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표가 원본이다. Kafka key는 원본 대상 ID(U5)를 정한 것이 아니다.

### 0.2 칸 설명

| 칸 | 뜻 |
| --- | --- |
| 종류 코드 | 알림 `kind`. 규칙은 ND-012(원본 shared 이벤트 이름에서 `Event`를 뺀 UPPER_SNAKE). 규칙 밖의 코드는 승급 승인 ND-071, 하루 묶음 ND-098, 나머지(예약 5종)는 U11 |
| 알림 | N 3장의 알림 이름 |
| 받는 사람 | N 3장의 받는 사람 |
| 원본 이벤트 | 알림을 만드는 shared integration event |
| 발생 조건 | 알림을 만드는 사실. N 3장 표와 각 절 설명에서 옮겼다 |
| 보내지 않는 경우 | 이 행에만 걸리는 조건. 모든 행에 걸리는 공통 조건은 0.3 |
| 이동 대상 | 알림을 눌렀을 때 갈 대상(종류·ID, N 6.1). 원본에 종류별 값이 없어 모두 "미정" |
| 원본 대상 ID | 중복 방지 유일 인덱스(원본 이벤트 종류, 원본 대상 ID, 받는 사람)의 대상 ID(ND-019). 원칙은 ND-033이고, 회차 ID가 없는 반복 사건은 `<대상 ID>:<회차>`다(ND-045). 새로 요청하거나 값을 더하는 이벤트의 사건 ID는 [이벤트 계약 요청](event-contracts.md)이 원본이다. 행별 칸은 그 이벤트를 소비하는 작업에서 구현된 계약을 확인한 뒤 적는다. 그 전까지 "미정(U5)" |
| 이벤트 계약 상태 | 원본 표시 / 2026-11-06 코드 재확인 |
| 관련 ND | 이 행에 직접 걸리는 결정. 모든 행에 걸리는 ND-011·012·018·019·020은 생략 |
| 비고 | 관련 미결(U 번호), 이벤트 계약 요청 위치 등 |

### 0.3 모든 알림에 공통인 규칙

- 받는 사람이 `notification_user`에서 탈퇴한 회원이면 알림과 예약을 만들지 않는다(ND-018). 정지 회원도 같다(ND-072).
- 받는 사람의 `notification_user` 복제본이 아직 없으면(가입 이벤트가 늦게 옴) ND-035를 따른다(`NT-USR-04`). 첫 배포 전에 가입해 가입 이벤트가 토픽에 없는 회원의 복제본은 회원 목록 내부 API Backfill로 채우고, 알림을 만드는 소비자는 첫 Backfill 뒤 시작한다(ND-077·ND-078, `NT-USR-06`).
- 업무 시각이 알림 시작 시각보다 이른 사건은 알림을 만들지 않는다(ND-076, `NT-KAF-04`). 운영의 알림 시작 시각은 앱이 처음 뜬 시각이다(ND-095).
- 같은 이벤트를 다시 받아도 한 번만 만든다(ND-019).
- 문구는 알림 모듈이 만든다(ND-020). 종류별 문구는 그 종류를 소비하는 작업에서 정한다(ND-039·ND-050). N5 11종은 0.5(ND-075), N6 7종은 0.6(ND-085), N7 지갑 4종은 0.7(ND-108), N7 출연 10종은 0.8(ND-113), 하루 묶음은 8장(ND-100), 나머지는 미정(U3).
- 다른 회원의 닉네임이 들어간 문구·값은 그 회원이 파기되면 바꾼다(ND-036, `NT-USR-07`). 언급 회원 ID 칸으로 찾아 값 속 닉네임을 바꾸고 문구를 다시 만든다(ND-051).
- 목록은 이벤트의 업무 시각으로 정렬한다(ND-021). 사건 시각 필드가 없는 `AppearanceRequestSentEvent`·`UserJoinedEvent`·`UserModifiedEvent`는 Kafka 레코드 timestamp를 대체 시각으로 쓰며, 원래 사건 시각과 같다고 보장하지 않는다(ND-081).
- 관리자는 알림센터 알림을 받지 않는다(ND-003).

### 0.4 집계

| 구분 | 도메인 | 수 | 절 |
| --- | --- | --- | --- |
| 즉시 | 출연 | 15 | [1](#1-출연-15종) |
| 즉시 | 강의 | 11 | [2](#2-강의-11종) |
| 즉시 | 지갑·정산 | 11 | [3](#3-지갑정산-11종) |
| 즉시 | 회원 | 5 | [4](#4-회원-5종) |
| 즉시 | 고객센터 | 4 | [5](#5-고객센터-4종) |
| 즉시 | 채팅 | 3 | [6](#6-채팅-3종) |
| **즉시 합계** | | **49** | |
| 예약 | 출연 4, 강의 1 | **5** | [7](#7-예약-알림-5종) |

`PAYOUT_COMPLETED_DAILY`(하루 묶음, [8장](#8-하루-묶음-알림))는 강의 11종 안에 센다.

### 0.5 문구 (N5 11종, ND-075)

시각은 KST, 금액은 천 단위 쉼표다. `{닉네임}`은 언급 회원(ND-051)이라 파기되면 `탈퇴회원#{회원ID}`로 바뀐다(ND-054). 나머지 종류의 문구는 미정(U3).

| 종류 코드 | 문구 |
| --- | --- |
| `APPEARANCE_REQUEST_SENT` | {호스트 닉네임}님이 '{제목}' 출연을 요청했습니다. {M월 d일 HH:mm}까지 응답해 주세요. (응답 기한 `acceptDeadline`) |
| `APPEARANCE_APPLICATION_SUBMITTED` | {게스트 닉네임}님이 '{모집글 제목}' 모집에 지원했습니다. |
| `CHARGE_COMPLETED` | {충전 HP} HP 충전이 완료되었습니다. |
| `REFUND_COMPLETED` | 전부 환불: {환불 HP} HP 환불이 완료되었습니다. / 일부 환불: 환불 신청 {요청 HP} HP 중 {환불 HP} HP가 환불되었습니다. |
| `PAYOUT_COMPLETED` | `NORMAL`: 출연료 {지급 HP} HP가 지급되었습니다. / `PENALTY`: 위약금 {지급 HP} HP가 지급되었습니다. 제목(`sourceTitle`)이 생기면 앞에 '{제목}'을 붙인다(ND-070) |
| `EXCHANGE_REJECTED` | {신청 HP} HP 환전 신청이 거절되었습니다. |
| `EXCHANGE_COMPLETED` | {신청 HP} HP 환전 송금이 완료되었습니다. 입금액 {원}원 (수수료 {수수료 HP} HP) |
| `EXCHANGE_PAYOUT_FAILED` | 환전 송금({원}원)이 지연되고 있습니다. 다시 시도합니다. |
| `EXCHANGE_FINAL_FAILED` | 환전 송금에 실패해 {신청 HP} HP를 돌려드렸습니다. |
| `USER_JOINED` | 합방에 오신 것을 환영합니다. |
| `INFLUENCER_UPGRADE_REQUEST_APPROVED` | 인증된 인플루언서로 승급되었습니다. |

### 0.6 문구 (N6 7종, ND-085)

작은따옴표(`'…'`) 안은 출연 제목(`cancelRequest.title`) 또는 구매할 때의 강의 제목(`lectureTitle`)이다. 따옴표는 N5 문구(0.5)와 같다. `{게스트 닉네임}`은 언급 회원(ND-051)이라 파기되면 `탈퇴회원#{회원ID}`로 바뀐다(ND-054).

| 종류 코드 | 받는 사람 | 문구 |
| --- | --- | --- |
| `APPEARANCE_CANCEL_REQUESTED` | 호스트 | {게스트 닉네임}님이 '{제목}' 출연 취소를 요청했습니다. |
| `APPEARANCE_CANCEL_REQUEST_ACCEPTED` | 게스트 | '{제목}' 출연 취소 요청이 수락되어 출연이 취소되었습니다. |
| `APPEARANCE_CANCEL_REQUEST_REJECTED` | 게스트 | 거절: '{제목}' 출연 취소 요청이 거절되었습니다. 출연은 예정대로 진행됩니다. / 자동 거절(`autoRejected`): 호스트가 응답하지 않아 '{제목}' 출연 취소 요청이 자동 거절되었습니다. 출연은 예정대로 진행됩니다. |
| `LECTURE_OBJECTION_RESOLVED` | 구매자, 판매자 | 인정(`accepted`): 구매자 '{강의 제목}' 이의 제기가 인정되어 HP를 반환합니다. / 판매자 '{강의 제목}' 이의 제기가 인정되었습니다. 기각: 구매자·판매자 '{강의 제목}' 이의 제기가 기각되었습니다. |
| `LECTURE_OBJECTION_RETURN_COMPLETED` | 구매자 | '{강의 제목}' 이의 제기 HP 반환이 완료되었습니다. |
| `LECTURE_OBJECTION_WITHDRAWN` | 판매자 | '{강의 제목}' 이의 제기가 철회되었습니다. |
| `LECTURE_OBJECTION_RETURN_ACTION_REQUIRED` | 구매자 | '{강의 제목}' HP 반환이 늦어지고 있습니다. 완료되면 다시 알려 드립니다. |

### 0.7 문구 (N7 지갑 4종, ND-108)

시각은 KST, 금액은 천 단위 쉼표다(0.5와 같다). 다른 회원의 닉네임은 넣지 않는다.

| 종류 코드 | 문구 |
| --- | --- |
| `CHARGE_FAILED` | {충전 HP} HP 충전에 실패했습니다. (`amountHp`) |
| `REFUND_FAILED` | {요청 HP} HP 환불에 실패했습니다. 신청한 HP는 지갑으로 돌아왔습니다. (`requestedAmountHp`) |
| `REFUND_MANUAL_REVIEW_REQUIRED` | {요청 HP} HP 환불은 운영자 확인이 필요해 처리가 늦어지고 있습니다. (`requestedAmountHp`) |
| `EXCHANGE_APPROVED` | {신청 HP} HP 환전 신청이 승인되었습니다. {M월 d일}에 송금됩니다. (`requestedAmountHp`, 날짜는 `payoutScheduledAt`의 KST 날짜) |

### 0.8 문구 (N7 출연 10종, ND-113)

시각은 KST다(0.5와 같다). 작은따옴표(`'…'`) 안은 출연 제목(`request.title`·`appearance.title`) 또는 모집글 제목(`application.recruitTitle`·`recruit.title`)이다. `{… 닉네임}`은 행동한 상대이고 언급 회원(ND-051)이라 파기되면 `탈퇴회원#{회원ID}`로 바뀐다(ND-054). 방송 예정 일시는 요청 수락이 `request.scheduledAt`, 지원 선정이 이벤트의 `scheduledAt`이다. 호스트 일방 취소에는 위약금을 넣지 않는다(지급은 `PAYOUT_COMPLETED`, ND-013).

| 종류 코드 | 받는 사람 | 문구 |
| --- | --- | --- |
| `APPEARANCE_REQUEST_ACCEPTED` | 호스트 | {게스트 닉네임}님이 '{제목}' 출연 요청을 수락했습니다. {M월 d일 HH:mm} 방송 예정입니다. |
| `APPEARANCE_REQUEST_REJECTED` | 호스트 | {게스트 닉네임}님이 '{제목}' 출연 요청을 거절했습니다. |
| `APPEARANCE_REQUEST_CANCELED` | 게스트 | {호스트 닉네임}님이 '{제목}' 출연 요청을 취소했습니다. |
| `APPEARANCE_REQUEST_EXPIRED` | 호스트, 게스트 | 호스트: {게스트 닉네임}님이 응답하지 않아 '{제목}' 출연 요청이 만료되었습니다. / 게스트: 응답 기한이 지나 '{제목}' 출연 요청이 만료되었습니다. |
| `APPEARANCE_APPLICATION_SELECTED` | 게스트 | {호스트 닉네임}님의 '{모집글 제목}' 모집에 선정되었습니다. {M월 d일 HH:mm} 방송 예정입니다. |
| `APPEARANCE_APPLICATION_NOT_SELECTED` | 게스트 | '{모집글 제목}' 모집에 선정되지 않았습니다. |
| `APPEARANCE_RECRUIT_CLOSED` | 호스트 | '{모집글 제목}' 모집이 마감 일시가 되어 마감되었습니다. |
| `APPEARANCE_CANCELED_BY_HOST` | 게스트 | {호스트 닉네임}님이 '{제목}' 출연을 취소했습니다. |
| `APPEARANCE_CONFIRMED` | 호스트 | 출연 확인 기간이 지나 '{제목}' 출연이 자동 확인되었습니다. |
| `APPEARANCE_ABSENT` | 게스트 | '{제목}' 출연이 불참 처리되었습니다. |

## 1. 출연 (15종)

토픽 `appearance.events.v1`.

> **`AppearanceChangedEvent`는 M3(출연 🆕 이벤트) 전체를 대체할 수 없다** (2026-11-06 조사, dev)
>
> - 지금 Kafka로 나가는 출연 이벤트는 `AppearanceRequestSentEvent`, `AppearanceApplicationSubmittedEvent`, `AppearanceReviewPublishedEvent`, `AppearanceChangedEvent`(#713) 넷이다. M3 12종과 같은 이름의 레코드는 없다.
> - `AppearanceChangedEvent`의 값은 `AppearanceSyncDto`(id, hostId, guestId, result, scheduledAt, version)뿐이다. 금액·사유·출처(요청/모집)·제목이 없다(`common/shared/.../appearance/dto/AppearanceSyncDto.java`). key `appearance:<출연 ID>`.
> - 출연 상태 6개를 공개 결과 4개로 줄인다(`apps/appearance-app/.../domain/AppearanceStatus.java` 34~40행): `ESTABLISHED`·`CANCEL_REQUESTED` → `SCHEDULED`, `CONFIRMED` → `COMPLETED`, `CANCELED_BY_HOST`·`CANCELED_BY_AGREEMENT` → `CANCELED`, `ABSENT` → `NO_SHOW`.
> - AI 추천용 최신 상태 사본이라 순서를 보장하지 않고, 받는 쪽은 version이 큰 것만 반영한다([internal-appearance-sync.md](../api/internal-appearance-sync.md) 4장).
> - 그래서 요청 수락/지원 수락, 호스트 일방 취소/합의 취소, 호스트 확인/자동 확인, 출연 성립/취소 요청을 구분할 수 없고, 출연 전 단계(요청서·지원·모집글)의 전이 8종(요청 거절·응답 전 취소·만료·미선정·모집 열림·모집 마감·모집 전체 취소 등)은 다루지 않는다.
> - 받는 사람 ID(hostId·guestId)와 `scheduledAt`은 있어 예약(방송 임박·출연 확인 부탁) 판단 보조로 일부 쓸 수 있을 수 있다. **추측이며 검증하지 않았다.**

| 종류 코드 | 알림 | 받는 사람 | 원본 이벤트 | 발생 조건 | 보내지 않는 경우 | 이동 대상 | 원본 대상 ID | 이벤트 계약 상태 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `APPEARANCE_REQUEST_SENT` | 출연 요청 도착 | 게스트 | `AppearanceRequestSentEvent` | 출연 요청 도착 | — | `APPEARANCE_REQUEST` / `request.requestId`(ND-074) | `request.requestId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `appearance-request:<요청 ID>`), `AppearanceRequestDto`에 `guestId` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`guestId` 있음 | ND-004 | 채팅도 함께 보낸다(U12). 예약 `APPEARANCE_REQUEST_EXPIRING`도 이 이벤트로 만든다 |
| `APPEARANCE_REQUEST_ACCEPTED` | 출연 요청 수락 (출연 성립) | 호스트 | `AppearanceRequestAcceptedEvent` | 게스트가 출연 요청을 수락해 출연 성립 | — | `APPEARANCE` / `appearanceId`(ND-112) | `request.requestId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `AppearanceChangedEvent`(#713)(결과 `SCHEDULED`, `hostId` 있음). 요청 수락과 지원 수락을 구분할 수 없고, 출연 성립과 취소 요청(둘 다 `SCHEDULED`)도 구분할 수 없다. 요청서 ID·제목·출연료 없음, 순서 보장 없음 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-request:<요청 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |
| `APPEARANCE_REQUEST_REJECTED` | 출연 요청 거절 | 호스트 | `AppearanceRequestRejectedEvent` | 게스트가 출연 요청을 거절 | — | `APPEARANCE_REQUEST` / `request.requestId`(ND-112) | `request.requestId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`AppearanceRequestRejectUseCase` 38행 근처) / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-request:<요청 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |
| `APPEARANCE_REQUEST_CANCELED` | 응답 전 취소 | 게스트 | `AppearanceRequestCanceledEvent` | 호스트가 응답 전 출연 요청을 취소 | — | `APPEARANCE_REQUEST` / `request.requestId`(ND-112) | `request.requestId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`AppearanceRequestCancelUseCase` 38행 근처) / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-request:<요청 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |
| `APPEARANCE_REQUEST_EXPIRED` | 만료 | 호스트, 게스트 | `AppearanceRequestExpiredEvent` | 출연 요청 만료 | — | `APPEARANCE_REQUEST` / `request.requestId`(ND-112) | `request.requestId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`AppearanceRequestExpireUseCase` 45행 근처) / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-request:<요청 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |
| `APPEARANCE_APPLICATION_SUBMITTED` | 모집 지원 도착 | 호스트 | `AppearanceApplicationSubmittedEvent` | 모집 지원 도착 | — | `APPEARANCE_RECRUIT` / `application.recruitId`(ND-074) | `application.applicationId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `appearance-application:<지원 ID>`), `AppearanceApplicationDto`에 `hostId` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`hostId` 있음 | ND-004 | 채팅도 함께 보낸다(U12) |
| `APPEARANCE_APPLICATION_SELECTED` | 지원 수락 (출연 성립) | 게스트 | `AppearanceApplicationSelectedEvent` | 호스트가 지원을 수락해 출연 성립 | — | `APPEARANCE` / `appearanceId`(ND-112) | `application.applicationId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `AppearanceChangedEvent`(#713)(결과 `SCHEDULED`, `guestId` 있음). 지원 수락과 요청 수락을 구분할 수 없고 지원 ID·모집글 ID·제목 없음, 순서 보장 없음 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-application:<지원 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |
| `APPEARANCE_APPLICATION_NOT_SELECTED` | 미선정 (거절 또는 모집 마감) | 게스트 | `AppearanceApplicationNotSelectedEvent` | 지원 거절 또는 모집 마감. 모집 전체 취소 때 대기 중인 지원마다 | — | `APPEARANCE_RECRUIT` / `application.recruitId`(ND-112) | `application.applicationId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(거절 `AppearanceApplicationRespondUseCase.reject` 77행 근처, 마감 `AppearanceRecruitCloseRecorder` 37행 근처, 모집 전체 취소 때 대기 지원 `closeUnselected` `AppearanceRecruitCancelUseCase` 79~81행 근처). 출연이 아니라 지원 단계라 `AppearanceChangedEvent`도 나가지 않는다 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-application:<지원 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-017, ND-110 | 요청 값 1.1 |
| `APPEARANCE_RECRUIT_CLOSED` | 모집 마감 | 호스트 | `AppearanceRecruitClosedEvent` | 모집 마감 | `closeType`이 `DEADLINE`이 아닐 때(조기 마감은 본인 행동). `DEADLINE`일 때만 보낸다 | `APPEARANCE_RECRUIT` / `recruit.recruitId`(ND-112) | `recruit.recruitId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`AppearanceRecruitCloseUseCase` 39행, `AppearanceRecruitDeadlineCloseUseCase` 47행, `AppearanceRecruitCloseRecorder` 37행 근처). `closeType` 값도 없다 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance-recruit:<모집글 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |
| `APPEARANCE_CANCEL_REQUESTED` | 출연 취소 요청 도착 | 호스트 | `AppearanceCancelRequestedEvent` | 게스트의 출연 취소 요청 도착 | — | `APPEARANCE` / `cancelRequest.appearanceId`(ND-084) | `cancelRequest.cancelRequestId`(ND-083) | 원본 🟡 / 재확인: 레코드 있음, `@Externalized` 없음(그대로 🟡). `AppearanceCancelRequestDto`에 `hostId`·`guestId` 있음 / **2026-11-06 판정: 미충족(🟡 유지)** — JVM 안에서는 발행되지만(`AppearanceCancelRequestUseCase` 63행 근처) `@Externalized`가 없어 Kafka로 나가지 않는다(`AppearanceExternalEvents.java` 12행 근처). 필드는 충분 / **2026-11-10 #817: 발행** — `@Externalized`(key `appearance:<출연 ID>`)로 나가고 사건 시각이 붙었다([이벤트 계약 요청](event-contracts.md) 1장) | ND-082~ND-085 | Kafka 발행 요청(N 8장 M1) |
| `APPEARANCE_CANCEL_REQUEST_ACCEPTED` | 출연 취소 요청 수락 (합의 취소) | 게스트 | `AppearanceCancelRequestAcceptedEvent` | 호스트가 출연 취소 요청을 수락(합의 취소) | — | `APPEARANCE` / `cancelRequest.appearanceId`(ND-084) | `cancelRequest.cancelRequestId`(ND-083) | 원본 🟡 / 재확인: 레코드 있음, `@Externalized` 없음(그대로 🟡) / **2026-11-06 판정: 미충족(🟡 유지)** — JVM 안 발행(`AppearanceCancelRespondUseCase` 88행 근처), Kafka로 나가지 않는다. `AppearanceChangedEvent`의 `CANCELED`는 합의 취소와 일방 취소를 구분하지 못해 대체할 수 없다 / **2026-11-10 #817: 발행** — `@Externalized`(key `appearance:<출연 ID>`)로 나가고 사건 시각이 붙었다([이벤트 계약 요청](event-contracts.md) 1장) | ND-082~ND-085 | Kafka 발행 요청(M1) |
| `APPEARANCE_CANCEL_REQUEST_REJECTED` | 출연 취소 요청 거절 / 자동 거절 | 게스트 | `AppearanceCancelRequestRejectedEvent` | 출연 취소 요청 거절 또는 자동 거절. `autoRejected`로 문구를 나눈다 | — | `APPEARANCE` / `cancelRequest.appearanceId`(ND-084) | `cancelRequest.cancelRequestId`(ND-083) | 원본 🟡 / 재확인: 레코드 있음(`autoRejected` 필드 있음), `@Externalized` 없음(그대로 🟡) / **2026-11-06 판정: 미충족(🟡 유지)** — JVM 안 발행(`AppearanceCancelRespondUseCase` 115행, `AppearanceCancelAutoRejectUseCase` 80행 근처), Kafka로 나가지 않는다 / **2026-11-10 #817: 발행** — `@Externalized`(key `appearance:<출연 ID>`)로 나가고 사건 시각이 붙었다([이벤트 계약 요청](event-contracts.md) 1장) | ND-082~ND-085 | Kafka 발행 요청(M1) |
| `APPEARANCE_CANCELED_BY_HOST` | 호스트 일방 취소 | 게스트 | `AppearanceCanceledByHostEvent` | 호스트 일방 취소. 모집 전체 취소 때 성립한 출연마다 | — | `APPEARANCE` / `appearance.appearanceId`(ND-112) | `appearance.appearanceId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `AppearanceChangedEvent`(#713)(결과 `CANCELED`, `guestId` 있음). 일방 취소와 합의 취소를 구분할 수 없고 위약금·제목 없음, 순서 보장 없음 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance:<출연 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-017, ND-013, ND-110 | 위약금 지급 사실은 지갑 `PAYOUT_COMPLETED`가 따로 알린다. 요청 값 1.1 |
| `APPEARANCE_CONFIRMED` | 자동 확인 | 호스트 | `AppearanceConfirmedEvent` | 출연 자동 확인 | `confirmType`이 `AUTO`가 아닐 때(호스트 출연 확인은 본인 행동). `AUTO`일 때만 보낸다 | `APPEARANCE` / `appearance.appearanceId`(ND-112) | `appearance.appearanceId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `AppearanceChangedEvent`(#713)(결과 `COMPLETED`, `hostId` 있음). 호스트 확인과 자동 확인을 구분할 수 없어(`confirmType` 없음) "보내지 않는 경우"를 판단하지 못한다. 제목 없음 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance:<출연 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-013, ND-110 | 출연료 지급 사실은 `PAYOUT_COMPLETED`가 따로 알린다. 요청 값 1.1 |
| `APPEARANCE_ABSENT` | 불참 | 게스트 | `AppearanceAbsentEvent` | 불참 처리 | — | `APPEARANCE` / `appearance.appearanceId`(ND-112) | `appearance.appearanceId`(ND-111) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `AppearanceChangedEvent`(#713)(결과 `NO_SHOW`로 불참 판별 가능, `guestId` 있음). 제목 없음, 순서 보장 없음 / 2026-11-12 #871(M3)으로 발행(`@Externalized`, key `appearance:<출연 ID>`, 필드는 [이벤트 계약 요청](event-contracts.md) 1장) | ND-110 | 요청 값 1.1 |

- 모집 전체 취소는 종류를 따로 두지 않는다(ND-017). `AppearanceRecruitCanceledEvent`는 모집 마감 임박 예약을 취소하는 데만 쓴다(ND-041).
- 출연료·위약금을 실제로 받은 사실은 지갑 `PAYOUT_COMPLETED`가 따로 알린다. 사건 알림과 돈 알림을 둘 다 보낸다(ND-013).
- 참고: `AppearanceChangedEvent`(#713, AI 추천용 동기화)가 Kafka로 나가지만, N은 위 🆕 이벤트를 따로 요청했고 이 문서는 원본 이벤트를 바꾸지 않는다. 한계는 절 머리 박스를 본다.

**보내지 않는 것**: 등록 결과(호스트 본인 행동), 지원 취소(호스트), 호스트 출연 확인(본인 행동), 출연 리뷰 공개.

### 1.1 요청 이벤트 값

[이벤트 계약 요청](event-contracts.md) 1장이 원본이다(ND-037). `AppearanceRecruitOpenedEvent`·`AppearanceRecruitCanceledEvent`는 즉시 알림이 아니라 예약 알림(7장)에 쓴다.

## 2. 강의 (11종)

토픽 `lecture.events.v1`(`PAYOUT_COMPLETED_DAILY`만 `billing.events.v1`).

| 종류 코드 | 알림 | 받는 사람 | 원본 이벤트 | 발생 조건 | 보내지 않는 경우 | 이동 대상 | 원본 대상 ID | 이벤트 계약 상태 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `LECTURE_APPROVED` | 강의 검수 승인 | 판매자 | `LectureApprovedEvent` | 강의 검수 승인 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `LectureChangedEvent`(#712, `LectureSyncDto`: 제목·`sellerUserId` 있음). 검수 상태·발생 시각이 없고 `onSale`만 있어 검수 승인 사건으로 보려면 해석이 필요하다 / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | — | 요청 값 2.1 |
| `LECTURE_REJECTED` | 강의 검수 반려 (사유) | 판매자 | `LectureRejectedEvent` | 강의 검수 반려 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — `LectureChangedEvent`에 검수 상태·반려 사유가 없어 반려를 식별할 수 없다 / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | — | 요청 값 2.1 |
| `LECTURE_ORDER_PAID` | 구매 완료 | 구매자 | `LectureOrderPaidEvent` | 주문 결제(구매) 완료 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `LecturePurchaseChangedEvent`(#712, `LecturePurchaseSyncDto`: `buyerUserId`·`purchasedAt`, 결과 `OWNED`). 주문 항목 단위이고 제목·금액·주문 ID 없음 / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | — | 예약 `LECTURE_ORDER_ITEM_CONFIRMING`도 이 이벤트로 만든다(주문 항목마다). U2(P0 완료 증거). 요청 값 2.1 |
| `LECTURE_ORDER_HOLD_FAILED` | 결제 실패 (보류 거절) | 구매자 | `LectureOrderHoldFailedEvent` | 주문 보류 거절로 결제 실패 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `LecturePurchaseChangedEvent`(결과 `FAILED`). 제목·금액 없음, 항목 단위 / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장). 강의는 주문이 결제 실패가 되는 모든 경로(보류 거절, 보류 무효화, 쿠폰 예약 거절·응답 불일치, 관리자 실패 확정)에서 이 이벤트를 내고 실패 원인은 담지 않는다(강의 D85 (5)). 보류 거절만 알릴지는 알림 담당이 정한다 | — | 요청 값 2.1 |
| `LECTURE_ORDER_ITEM_CANCELED` | 구매 취소 완료 (반환 끝) | 구매자 | `LectureOrderItemCanceledEvent` | 구매 취소의 반환이 끝남 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 부분 충족** — 대체 후보 `LecturePurchaseChangedEvent`(결과 `CANCELED`). 제목·반환 금액 없음 / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | — | 요청 값 2.1 |
| `LECTURE_ORDER_ITEM_CONFIRMED` | 자동 구매 확정 | 구매자 | `LectureOrderItemConfirmedEvent` | 자동 구매 확정 | `confirmationType`이 `AUTO`가 아닐 때. `AUTO`일 때만 보낸다 | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 구매 확정·지급 전이는 동기화 이벤트도 보내지 않는다(`LectureSyncEvents.java` 59행 근처). 예약 `LECTURE_ORDER_ITEM_CONFIRMING` 취소에도 필요 / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | — | 요청 값 2.1 |
| `PAYOUT_COMPLETED_DAILY` | 강의 지급 (하루 묶음) | 판매자 | `PayoutCompletedEvent` | `sourceType` `LECTURE_ORDER_ITEM` 지급 완료. 판매자·날짜마다 하나로 묶는다(8장) | `sourceType`이 `LECTURE_ORDER_ITEM`이 아닐 때(출연 원천은 `PAYOUT_COMPLETED`) | 없음(비운다, ND-102) | 해당 없음: 묶음 키(종류, 받는 사람, 날짜)로 중복을 막는다(ND-019, ND-032) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(`billing.events.v1`, key `settlement:<지급 건 ID>`), `receiverUserId`·`sourceType`·`paidAt` 있음, 제목 없음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`receiverUserId`·`sourceType`·`paidAt` 있음. 제목은 ND-040(`sourceTitle`, 계약 요청 3장) | ND-013, ND-014, ND-032, ND-040, ND-097~ND-102, ND-104 | 종류 코드 ND-098. 강의 원천도 #872부터 `sourceTitle`을 싣지만 묶음 문구에는 넣지 않는다(ND-100·ND-104) |
| `LECTURE_OBJECTION_FILED` | 이의 제기 접수 (구매자: 접수됨, 판매자: 지급 중단) | 구매자, 판매자 | `LectureObjectionFiledEvent` | 강의 모듈이 이의 제기를 접수할 때(바로 접수와 고객센터 재시도 배치 접수 모두) | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`OrderObjectionFileUseCase.java` 31행 근처) / **2026-11-11 #872: 구현됨** (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | ND-015 | 구매자에게 같은 사실을 두 번 보내지 않는다. 요청 값 2.1 |
| `LECTURE_OBJECTION_RESOLVED` | 이의 제기 기각·인정 | 구매자, 판매자 | `LectureObjectionResolvedEvent` | 이의 제기 기각 또는 인정. `accepted`로 문구를 나눈다 | — | `LECTURE_ORDER_ITEM` / `orderItemId`(ND-084) | `inquiryId`(ND-083) | 원본 ⚠️ / 재확인: 레코드 있음, `@Externalized`(key `order-item:<주문 항목 ID>`), 필드 `commandId`·`inquiryId`·`orderItemId`·`accepted`, 구매자·판매자 ID 없음(그대로 ⚠️) / **2026-11-06 판정: 미충족(⚠️ 유지)** — `buyerUserId`·`sellerUserId`·제목 없음 / **2026-11-10 #818: 값 추가**: `buyerUserId`·`sellerUserId`·`lectureTitle`·`resolvedAt` (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | ND-082~ND-086 | 값 추가 요청(M2). 요청 값 2.1 |
| `LECTURE_OBJECTION_RETURN_COMPLETED` | 이의 제기 반환 완료 | 구매자 | `LectureObjectionReturnCompletedEvent` | 이의 제기 인정 뒤 반환 완료 | — | `LECTURE_ORDER_ITEM` / `orderItemId`(ND-084) | `inquiryId`(ND-083) | 원본 ⚠️ / 재확인: 레코드 있음, `@Externalized`, 필드 `inquiryId`·`orderItemId`뿐(그대로 ⚠️) / **2026-11-06 판정: 미충족(⚠️ 유지)** — 구매자 ID·제목 없음 / **2026-11-10 #818: 값 추가**: `buyerUserId`·`sellerUserId`·`lectureTitle`·`returnedAt` (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | ND-082~ND-086 | 값 추가 요청(M2). 요청 값 2.1 |
| `LECTURE_OBJECTION_WITHDRAWN` | 이의 제기 철회 | 판매자 | `LectureObjectionWithdrawnEvent` | 이의 제기 철회 | — | `LECTURE_ORDER_ITEM` / `orderItemId`(ND-084) | `inquiryId`(ND-083) | 원본 ⚠️ / 재확인: 레코드 있음, `@Externalized`, 필드 `commandId`·`inquiryId`·`orderItemId`뿐(그대로 ⚠️) / **2026-11-06 판정: 미충족(⚠️ 유지)** — 판매자 ID·제목 없음 / **2026-11-10 #818: 값 추가**: `buyerUserId`·`sellerUserId`·`lectureTitle`·`withdrawnAt` (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | ND-082~ND-086 | 값 추가 요청(M2). 요청 값 2.1 |

**보내지 않는 것**: 검수 요청 도착(관리자), 강의 판매·구매 취소 발생(판매자), 반환 자동 처리 멈춤(관리자 경보).

### 2.1 요청 이벤트 값

[이벤트 계약 요청](event-contracts.md) 2장이 원본이다(ND-037).

`LectureObjectionReturnActionRequiredEvent`는 강의 토픽 이벤트지만 알림은 고객센터 절(5장)에 둔다(N 3.5).

## 3. 지갑·정산 (11종)

토픽 `billing.events.v1`.

| 종류 코드 | 알림 | 받는 사람 | 원본 이벤트 | 발생 조건 | 보내지 않는 경우 | 이동 대상 | 원본 대상 ID | 이벤트 계약 상태 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `CHARGE_COMPLETED` | 충전 완료 | 충전한 회원 | `ChargeCompletedEvent` | 충전 완료 | — | `CHARGE` / `chargeId`(ND-074) | `chargeId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `point-charge:<충전 ID>`), `userId` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`userId` 있음 | — | |
| `CHARGE_FAILED` | 충전 실패 | 충전한 회원 | `ChargeFailedEvent` | 충전 실패 | — | `CHARGE` / `chargeId`(ND-107) | `chargeId`(ND-106) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`ChargeProcessing` 70행 근처). 본보기는 기존 7종(`BillingEventPublisher` 39~81행) / 2026-11-12 #873(M5)으로 발행(`@Externalized`, key `point-charge:<충전 ID>`, `userId`·`amountHp`·`failedAt`) | ND-105 | 요청 값 3.1 |
| `REFUND_COMPLETED` | 환불 완료 (전부·일부) | 신청한 회원 | `RefundCompletedEvent` | 환불 완료(전부 또는 일부) | — | `REFUND_REQUEST` / `refundRequestId`(ND-074) | `refundRequestId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `point-refund-request:<환불 요청 ID>`), `userId`·`fullyRefunded()` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`userId` 있음 | — | |
| `REFUND_FAILED` | 환불 실패 | 신청한 회원 | `RefundFailedEvent` | 환불 실패(환불한 금액 없이 끝남. 일부 환불은 `REFUND_COMPLETED`) | — | `REFUND_REQUEST` / `refundRequestId`(ND-107) | `refundRequestId`(ND-106) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`RefundProcessing` 192·231·277행 근처) / 2026-11-12 #873(M5)으로 발행(`@Externalized`, key `point-refund-request:<환불 요청 ID>`, `userId`·`requestedAmountHp`·`failedAt`) | ND-105 | 요청 값 3.1 |
| `REFUND_MANUAL_REVIEW_REQUIRED` | 환불 운영자 확인 필요 | 신청한 회원 | `RefundManualReviewRequiredEvent` | 환불이 운영자 확인 필요 상태가 됨 | — | `REFUND_REQUEST` / `refundRequestId`(ND-107) | `refundRequestId`(ND-106) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`RefundProcessing` 192·231·277행 근처) / 2026-11-12 #873(M5)으로 발행(`@Externalized`, key `point-refund-request:<환불 요청 ID>`, `userId`·`requestedAmountHp`·`manualReviewRequiredAt`) | ND-105 | 요청 값 3.1 |
| `PAYOUT_COMPLETED` | 지급 완료 (출연료, 위약금) | 게스트 | `PayoutCompletedEvent` | `sourceType` `APPEARANCE_REQUEST`·`RECRUIT_APPEARANCE` 지급 완료(`payoutType` `NORMAL`·`PENALTY`). 한 건씩 보낸다 | `sourceType`이 `LECTURE_ORDER_ITEM`일 때(`PAYOUT_COMPLETED_DAILY`로 보낸다) | `APPEARANCE` / `sourceId`(출연 ID, 원천이 요청·모집 모두, ND-074) | `settlementId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `settlement:<지급 건 ID>`), `receiverUserId`·`payoutType`·`sourceType`·`sourceId` 있음, 제목 없음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`receiverUserId` 있음. 제목은 ND-040(`sourceTitle`, 계약 요청 3장) | ND-013, ND-014, ND-040 | — |
| `EXCHANGE_REJECTED` | 환전 거절 | 신청한 회원 | `ExchangeRejectedEvent` | 환전 거절 | — | `EXCHANGE` / `exchangeId`(ND-074) | `exchangeId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `exchange:<환전 ID>`), `userId` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`userId` 있음 | ND-021 | |
| `EXCHANGE_APPROVED` | 환전 승인 (정산일) | 신청한 회원 | `ExchangeApprovedEvent` | 환전 승인 | — | `EXCHANGE` / `exchangeId`(ND-107) | `exchangeId`(ND-106) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`ExchangeReviewUseCase` 54~71행 근처) / 2026-11-12 #873(M5)으로 발행(`@Externalized`, key `exchange:<환전 ID>`, `userId`·`requestedAmountHp`·`payoutScheduledAt`·`approvedAt`) | ND-021, ND-105 | 요청 값 3.1 |
| `EXCHANGE_COMPLETED` | 환전 송금 완료 | 신청한 회원 | `ExchangeCompletedEvent` | 환전 송금 완료 | — | `EXCHANGE` / `exchangeId`(ND-074) | `exchangeId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `exchange:<환전 ID>`), `userId` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`userId` 있음 | ND-021 | |
| `EXCHANGE_PAYOUT_FAILED` | 환전 송금 실패 (지연) | 신청한 회원 | `ExchangePayoutFailedEvent` | 환전 송금 실패(지연) | — | `EXCHANGE` / `exchangeId`(ND-074) | `payoutAttemptId`(ND-073, ND-033) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `exchange:<환전 ID>`), `userId`·`payoutAttemptId`·`attemptNo` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`userId` 있음. 원본 대상 ID는 U5 | ND-021 | 한 환전에서 여러 번 난다. 환전 ID를 원본 대상 ID로 쓰면 두 번째 실패가 막힌다(U5) |
| `EXCHANGE_FINAL_FAILED` | 환전 최종 실패 (보류 HP 반환) | 신청한 회원 | `ExchangeFinalFailedEvent` | 환전 최종 실패, 보류 HP 반환 | — | `EXCHANGE` / `exchangeId`(ND-074) | `exchangeId`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key `exchange:<환전 ID>`), `userId` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`userId` 있음 | ND-021 | |

- `PayoutCompletedEvent` 하나를 원천으로 출연 원천은 `PAYOUT_COMPLETED`, 강의 원천은 `PAYOUT_COMPLETED_DAILY`로 나눈다(ND-013).
- 환전 이벤트는 순서가 바뀌어 올 수 있어 목록은 업무 시각으로 정렬한다(ND-021).
- N 3.3 절에는 "보내지 않는 것" 목록이 없다.

### 3.1 요청 이벤트 값

[이벤트 계약 요청](event-contracts.md) 3장이 원본이다(ND-037). 지급 알림 제목(`PayoutCompletedEvent.sourceTitle`, ND-040)도 그 장에 있다.

## 4. 회원 (5종)

토픽 `member.events.v1`.

| 종류 코드 | 알림 | 받는 사람 | 원본 이벤트 | 발생 조건 | 보내지 않는 경우 | 이동 대상 | 원본 대상 ID | 이벤트 계약 상태 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `USER_JOINED` | 가입 환영 | 새 회원 | `UserJoinedEvent` | 회원 가입 | — | 없음(ND-074) | `user.id`(ND-073) | 원본 ✅ / 재확인: 레코드 있음, `@Externalized`(key 회원 ID), `UserDto.id` 있음 / **2026-11-06 판정: 충족** — 레코드·`@Externalized`·`UserDto.id` 있음. 첫 배포 이전 가입 회원은 U7·U17 | — | 첫 배포 때 과거 가입 이벤트는 ND-076, 이전 가입 회원 복제본은 ND-077 |
| `INFLUENCER_CHANNEL_APPROVED` | 채널 인증 승인 (자동·관리자) | 회원 | 🆕 `InfluencerChannelApprovedEvent` (ND-042. 원본 표기: `UserModifiedEvent` "✅ 추정") | 회원의 채널 인증 승인(자동·관리자) | 역할 변경(`UserModifiedEvent`)만으로는 만들지 않는다(관리자 인플루언서 지정 #724 포함) | 미정 | 미정(U5) | 원본 ✅ 추정 / 재확인: 레코드 있음, `@Externalized`, `UserDto.role` 있음(`USER`·`INFLUENCER`·`VERIFIED_INFLUENCER`) / **2026-11-06 판정: 부분 충족(U18)** — `UserModifiedEvent`(`UserDto`: id, nickname, role, status, version)에 이전 역할이 없어 복제본 비교가 필요하다(복제본 없음은 U17). 관리자 인플루언서 지정(#724)과 구분되지 않고, 이미 인플루언서인 회원의 채널 승인은 역할이 바뀌지 않아 이벤트가 없다. 2026-11-09 ND-042로 새 이벤트를 요청했다(계약 요청 4장) | ND-042(ND-016 일부 대체) | 자동·관리자 승인을 같은 종류로 보낸다. 요청 값 4.1 |
| `INFLUENCER_CHANNEL_REJECTED` | 채널 인증 반려 (사유) | 회원 | `InfluencerChannelRejectedEvent` | 채널 인증 반려 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`MemberChannelReviewUseCase` 28~29행 근처) | — | 요청 값 4.1 |
| `INFLUENCER_UPGRADE_REQUEST_APPROVED` | 승급 승인 | 회원 | `UserModifiedEvent` (원본 표기: "✅ 추정") | 복제본의 이전 역할과 새 역할을 비교해 인플루언서 → 인증된 인플루언서로 바뀜 | 역할이 인플루언서 → 인증된 인플루언서로 바뀌지 않은 `UserModifiedEvent` | 없음(ND-074) | `<user.id>:<user.version>`(ND-073) | 원본 ✅ 추정 / 재확인: 위와 같음 / **2026-11-06 판정: 충족(복제본 비교 전제)** — 인플루언서 → 인증된 인플루언서는 승급 승인 경로에서만 바뀌어 판별 가능. 이전 역할은 이벤트에 없어 복제본에 의존(U17) | ND-016 | 자동·관리자 승인을 구분하지 않는다. 이벤트 이름 규칙 밖 코드라 ND-071로 정했다 |
| `INFLUENCER_UPGRADE_REQUEST_REJECTED` | 승급 반려 (사유) | 회원 | `InfluencerUpgradeRequestRejectedEvent` | 승급 반려 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`MemberUpgradeReviewUseCase` 60~61행 근처) | — | 요청 값 4.1 |

**보내지 않는 것**: 비밀번호 변경, 로그인 잠금, 정지, 탈퇴(알림센터를 볼 수 없다), 승인 요청·승급 신청 도착(관리자). 로그인하지 못한 회원에게 가야 하는 알림은 범위 밖이다(ND-002).

### 4.1 요청 이벤트 값

[이벤트 계약 요청](event-contracts.md) 4장이 원본이다(ND-037). 채널 인증 승인 이벤트(ND-042)도 그 장에 있다.

## 5. 고객센터 (4종)

토픽 `customersupport.events.v1`(`LECTURE_OBJECTION_RETURN_ACTION_REQUIRED`만 `lecture.events.v1`).

| 종류 코드 | 알림 | 받는 사람 | 원본 이벤트 | 발생 조건 | 보내지 않는 경우 | 이동 대상 | 원본 대상 ID | 이벤트 계약 상태 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `INQUIRY_ANSWERED` | 문의 답변 도착 | 문의한 회원 | `InquiryAnsweredEvent` | 문의 답변 도착 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`InquiryUseCase` 148~162행 근처) | — | 요청 값 5.1 |
| `INQUIRY_REPLY_EDITED` | 답변 수정 | 문의한 회원 | `InquiryReplyEditedEvent` | 답변 수정 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`InquiryUseCase` 148~162행 근처) | — | 요청 값 5.1 |
| `LECTURE_OBJECTION_FILE_REJECTED` | 이의 제기 접수 불가 (재시도 뒤) | 구매자 | `LectureObjectionFileRejectedEvent` (고객센터 발행) | 고객센터 재시도 배치가 이의 제기를 접수 불가로 끝낼 때 | 바로 거절된 접수(화면에서 바로 보인다). 재시도 배치가 접수 불가로 끝낼 때만 보낸다 | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 발행 없음(`LectureObjectionFiler` 108~124행 근처). 재시도 배치 경로는 `delete=false`로 식별 가능 | ND-015 | 접수 성공은 2장 `LECTURE_OBJECTION_FILED`. 요청 값 5.1 |
| `LECTURE_OBJECTION_RETURN_ACTION_REQUIRED` | 반환 지연 | 구매자 | `LectureObjectionReturnActionRequiredEvent` | 이의 제기 반환 지연 | — | `LECTURE_ORDER_ITEM` / `orderItemId`(ND-084) | `<inquiryId>:<returnDelaySequence>`(ND-083, ND-045) | 원본 ⚠️ / 재확인: 레코드 있음, `@Externalized`(`lecture.events.v1`, key `order-item:<주문 항목 ID>`), 필드 `inquiryId`·`orderItemId`뿐(그대로 ⚠️) / **2026-11-06 판정: 미충족(⚠️ 유지)** — `buyerUserId`·제목 없음 / **2026-11-10 #818: 값 추가**: `buyerUserId`·`sellerUserId`·`lectureTitle`·`returnDelaySequence`·`occurredAt` (필드 이름·타입은 [이벤트 계약 요청](event-contracts.md) 2장) | ND-082~ND-086 | 값 추가 요청(M2, 2.1) |

**보내지 않는 것**: 새 문의 도착(관리자), 답변 삭제, FAQ 등록·수정.

### 5.1 요청 이벤트 값

[이벤트 계약 요청](event-contracts.md) 5장이 원본이다(ND-037).

## 6. 채팅 (3종)

토픽 `chat.events.v1`(새 토픽, [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표에 아직 없다).

> **이름 주의(ND-043)**: 아래 `GROUP_*` 코드는 채팅 방 안 안내(`NotificationKind`)와 이름이 같지만 **뜻과 받는 사람이 다르다**(예: 강퇴 — 채팅은 남은 참여자, 알림센터는 강퇴된 회원). 이름은 그대로 쓰고 이 차이를 여기와 CONTEXT에 적는다(ND-043). 이 표는 알림센터 측 알림만 다룬다. 알림 3종 자체(받는 사람·조건)는 ND-011로 유효다.

| 종류 코드 | 알림 | 받는 사람 | 원본 이벤트 | 발생 조건 | 보내지 않는 경우 | 이동 대상 | 원본 대상 ID | 이벤트 계약 상태 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GROUP_MEMBERS_INVITED` | 단체방 초대 | 이번 초대 요청으로 실제 참여한 회원 | `GroupMembersInvitedEvent` | 단체방 초대 | 이번 초대 요청으로 실제 참여하지 않은 회원 | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 레코드 없음(2026-11-06 계약 조사 범위 밖, 대체 후보 미확인) | ND-004, ND-043 | U12. 요청 값 6.1 |
| `GROUP_MEMBER_KICKED` | 단체방 강퇴 | 강퇴된 회원 | `GroupMemberKickedEvent` | 단체방 강퇴 | — | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 레코드 없음(2026-11-06 계약 조사 범위 밖, 대체 후보 미확인) | ND-004, ND-043 | 채팅은 남은 참여자에게 안내(ND-043). U12. 요청 값 6.1 |
| `GROUP_ROOM_CLOSED` | 단체방 폐쇄 | 남은 참여자 (폐쇄한 호스트 제외) | `GroupRoomClosedEvent` | 단체방 폐쇄 | 폐쇄한 호스트 | 미정 | 미정(U5) | 원본 🆕 / 재확인: 레코드 없음 / **2026-11-06 판정: 미충족** — 레코드 없음(2026-11-06 계약 조사 범위 밖, 대체 후보 미확인) | ND-004, ND-043 | U12. 요청 값 6.1 |

- 채팅은 방 안 시스템 메시지(`notifyRule: NONE`)를 바꾸지 않고, 같은 시점에 이벤트를 더 발행한다. 초대된 회원과 강퇴된 회원은 방을 열지 않으면 알 방법이 없어 알림센터로 알린다(N 3.6).

**보내지 않는 것**: 새 메시지, 차단, 나가기, 호스트 자격 상실, 일시 중지·재개. 채팅 새 메시지와 차단은 채팅의 안 읽은 메시지 수가 맡는다(ND-004).

### 6.1 요청 이벤트 값

[이벤트 계약 요청](event-contracts.md) 6장이 원본이다(ND-037). 새 토픽 `chat.events.v1`은 ND-044.

## 7. 예약 알림 (5종)

알림 모듈이 이벤트를 받아 예약을 만들고, 취소 이벤트가 오면 취소한다. 발행 모듈은 예약을 모른다. 예약 배치는 1분마다 보낼 시각이 지난 예약을 처리하고 보내기 직전에 상태를 다시 확인한다(ND-029). 출연은 시각 기준, 강의는 날짜 기준이다([정책](../policy.md) "기한 계산"). 예약 5종의 종류 코드는 이벤트 이름 규칙 밖이다(U11). 받는 사람이 탈퇴 회원이면 예약을 만들지 않는다(ND-018).

| 종류 코드 | 알림 | 받는 사람 | 보낼 시각 | 예약을 만드는 이벤트 | 예약을 취소하는 이벤트 | 관련 ND | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `APPEARANCE_REQUEST_EXPIRING` | 출연 요청 만료 임박 | 게스트 | 수락 기한 − 12시간 | `AppearanceRequestSentEvent` | 요청 수락·거절·응답 전 취소 | ND-029 | U6 |
| `APPEARANCE_RECRUIT_CLOSING` | 모집 마감 임박 | 호스트 | 모집 마감 일시 − 24시간 | 🆕 `AppearanceRecruitOpenedEvent` | 모집 마감(조기 마감 포함), 모집 전체 취소 | ND-029, ND-041 | U6. 모집 전체 취소는 `AppearanceRecruitCanceledEvent`로 받는다(ND-041) |
| `APPEARANCE_STARTING` | 방송 임박 | 호스트, 게스트 | 방송 예정 일시 − 24시간 | 요청 수락, 지원 수락 | 합의 취소, 호스트 일방 취소 | ND-029 | U6 |
| `APPEARANCE_CONFIRM_PERIOD_ENDING` | 출연 확인 부탁 | 호스트 | 자동 확인 시각 − 3×24시간 (= 방송 예정 일시 + 4×24시간) | 요청 수락, 지원 수락 | 출연 확인(호스트·자동), 불참, 합의 취소, 호스트 일방 취소 | ND-029, ND-030 | U6 |
| `LECTURE_ORDER_ITEM_CONFIRMING` | 구매 취소 마감 임박 | 구매자 | (구매일 + 7일) 09:00 | `LectureOrderPaidEvent` (주문 항목마다) | 구매 취소, 구매 확정(직접·첫 재생·자동) | ND-029, ND-031 | U6(09:00이 EC2 기동 시각과 겹친다) |

- "예약을 만드는/취소하는 이벤트" 칸은 N 4장 원문 그대로다. 원문이 사건 이름(예: "요청 수락")으로 적은 칸의 이벤트 이름은 원문에 없어 옮기지 않았다.
- 이벤트 계약 상태(2026-11-06 재확인): `AppearanceRequestSentEvent`는 ✅(1장), `AppearanceRecruitOpenedEvent`·`AppearanceRecruitCanceledEvent`·`LectureOrderPaidEvent`는 레코드 없음(🆕).
- 2026-11-06 계약 판정(예약 생성·취소 입력):
  - `APPEARANCE_REQUEST_EXPIRING`: 생성 충족(`AppearanceRequestSentEvent`). 취소는 요청 수락만 부분 충족(`AppearanceChangedEvent`), 거절·응답 전 취소는 미충족(1장).
  - `APPEARANCE_RECRUIT_CLOSING`: 생성 미충족(모집 열림 발행 없음, `AppearanceRecruitHoldUseCase.markRecruiting` 79행 근처). 취소(모집 마감·모집 전체 취소)도 미충족.
  - `APPEARANCE_STARTING`·`APPEARANCE_CONFIRM_PERIOD_ENDING`: 생성·취소 모두 부분 충족. `AppearanceChangedEvent`에 받는 사람 ID·`scheduledAt`·결과가 있지만 요청 수락/지원 수락, 합의/일방 취소, 호스트/자동 확인을 구분하지 못한다(1장 박스). 예약 판단 보조로 쓸 수 있는지는 추측이며 검증하지 않았다.
  - `LECTURE_ORDER_ITEM_CONFIRMING`: 생성 부분 충족(`LecturePurchaseChangedEvent` `OWNED`, 항목 단위·`purchasedAt` 있음). 취소는 구매 취소만 부분 충족(`CANCELED`), 구매 확정은 미충족(확정 전이는 동기화 이벤트도 없음, `LectureSyncEvents.java` 59행 근처).
- **예약보다 먼저 온 취소**: ND-034를 따른다(`NT-SCH-06`). 생성·취소 이벤트는 key·토픽이 다를 수 있고 Modulith 전송은 같은 key도 순서가 바뀔 수 있다. `AppearanceChangedEvent`를 쓰더라도 순서 보장이 없다. 예약 알림별 짝 키(대상 ID, 받는 사람)는 [이벤트 계약 요청](event-contracts.md) 0.3에 정했다. 발행 구현 뒤 실제 이벤트가 그 값을 주는지는 확인하지 않았다(**U16**). 확인 전인 예약 알림에는 ND-034를 적용하지 않는다.
- 예약의 중복 방지 키는 `notification_schedule`의 (종류, 대상 ID, 받는 사람)이다(ND-025). 종류별 대상 ID는 미정(U5).

## 8. 하루 묶음 알림

강의 지급 `PAYOUT_COMPLETED_DAILY`(2장)는 판매자·날짜마다 알림 하나로 묶는다. 문구(2026-11-12 알림 담당, #890): "{M월 d일} 강의 판매 수익 {합계 HP} HP가 지급되었습니다. ({건수}건)". 예: "11월 6일 강의 판매 수익 12,000 HP가 지급되었습니다. (50건)". 날짜는 묶음 날짜(KST), 금액·건수는 천 단위 쉼표다. 정책은 ND-013·ND-032가 원본이다.

| 항목 | 규칙 |
| --- | --- |
| 원천 | `PayoutCompletedEvent` 중 `sourceType` `LECTURE_ORDER_ITEM`(ND-013) |
| 묶음 단위 | 판매자(`receiverUserId`) × `paidAt`의 KST 날짜. 강의 지급 배치가 매일 01:00에 돌아 하루치가 대부분 한 번에 온다 |
| 지급이 올 때 | 금액·건수를 더하고, 다시 안 읽음으로 만들고, 발생 시각을 새로 고쳐 목록 맨 위로 올린 뒤 실시간으로 보낸다 |
| 발생 시각 | 처음은 그 지급의 `paidAt`, 더할 때는 기존 값과 새 `paidAt` 중 늦은 값(ND-101) |
| 중복 | 같은 지급(`settlementId`)은 `notification_payout_item`에 기록해 한 번만 더한다. 알림 유일 키는 (종류, 받는 사람, 묶음 키)(ND-019) |
| 밀려난 묶음 | 20개 보존에서 밀려난 날짜 묶음에 새 지급이 오면 되살린다. 같은 지급의 재전달이면 되살리지 않는다(ND-097) |
| 문구·이동 대상 | 강의 제목 없이 건수·합계 HP(ND-100·ND-104, 문구는 이 장 머리), 이동 대상 없음(ND-102) |
| 저장 | 지급 확인·묶음 생성·갱신·지급 기록·20개 보존은 한 트랜잭션(ND-103) |
| 종류 코드 | ND-098 |

## 9. 관련 미결

임의로 정하지 않는다. 상세는 [결정 기록 10장](decisions.md#10-미결-항목).

| ID | 질문 |
| --- | --- |
| U3 | 아직 문구가 없는 종류의 실제 문구(각 종류를 소비하는 작업, 원칙은 ND-039·ND-050. N5는 ND-075, N6는 ND-085, N7 지갑은 ND-108, N7 출연은 ND-113) |
| U5 | 아직 정하지 않은 종류의 원본 대상 ID 필드(N5는 ND-073, N6는 ND-083, N7 지갑은 ND-106, N7 출연은 ND-111. 원칙은 ND-033·ND-045, 새로 요청하는 이벤트는 [이벤트 계약 요청](event-contracts.md)) |
| U11 | 규칙 밖 종류 코드 중 예약 5종(승급 승인은 ND-071, 하루 묶음은 ND-098, 단체방 3종 이름은 ND-043) |
| U12 | 채팅과 알림센터에 같은 사건이 함께 뜰 때 화면 표시(서버는 양쪽에 만든다, ND-004) |
| U16 | 예약 알림별로 생성·취소 이벤트가 같은 (대상 ID, 받는 사람)을 주는지 계약 검증(처리 방향은 ND-034) |
| U19 | 구조는 ND-051로 정했다. 구현 가능성 검증만 남았다(N2, #801) |
