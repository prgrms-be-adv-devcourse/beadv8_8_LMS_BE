# 알림 이벤트 계약 요청

> 문서 탐색: [알림 문서 INDEX](INDEX.md)

> - 알림 모듈이 소비할 발행 모듈 이벤트의 계약(이벤트 이름, 토픽·key, 담을 값)을 모은 **요청 원본**이다. 알림 담당이 정하고, 발행 담당은 이 문서를 Issue로 받아 `common/shared` 이벤트와 발행을 구현한다([ND-037](decisions.md)).
> - 실제로 구현된 계약의 원본은 `common/shared` 코드와 [kafka.md](../architecture/infrastructure/kafka.md) 토픽 표다. 이 문서는 요청과 그 진행 상태만 맡는다.
> - 알림 종류별 명세(받는 사람, 발생 조건, 보내지 않는 경우)는 [알림 종류](notification-catalog.md)가 원본이다. 이 문서는 종류 코드로만 가리킨다.
> - 구현 상태(2026-11-09): 🆕는 발행되지 않고, 🟡는 JVM 안에서만 발행된다. ⚠️와 "기존 변경"은 이미 Kafka로 나가지만 요청한 값이 아직 없다. 근거는 알림 종류 각 행의 "2026-11-06 판정"이다.
>
> 변경 이력
> - 2026-11-13 #929 Bot 리뷰(알림 담당, ND-149): 0.3 구매 취소 마감 임박의 취소 이벤트와 2장 예약 기한 불변식에 이의 제기 접수(`filedAt`)를 더했다.
> - 2026-11-13 N10 예약 알림 결정(#928, 알림 담당): 0.3 끝 문단에 짝 키 확인 결과(ND-138)를 적고, 2장에 구매 취소 마감 임박 예약이 기대는 강의 이벤트 시각 불변식(ND-143)을 더했다. 강의 담당 확인은 PR 리뷰에서 받는다.
> - 2026-11-09 처음 작성: 원천 설계 [notification.md 7장](../architecture/infrastructure/notification.md)의 요청(알림 종류 문서 1.1~6.1에 있던 표)을 옮기고, ND-038 공통 값과 ND-040·ND-041·ND-042·ND-044를 반영했다.
> - 2026-11-09 확정: 발행 앱 코드를 조사해(7장) 사건 ID·key·필드를 정하고 ND-045(회차 번호)를 반영했다. 모든 이벤트를 "확정"으로 바꿨다.
> - 2026-11-09 PR 리뷰 반영(#794): 구현 상태 서술을 이미 발행 중인 ⚠️·기존 변경 이벤트에 맞게 고쳤다. 0.3 예약 짝 키를 새로 두었다(U16 계약 쪽). 3장에 ND-040의 지급 요청 변경 범위를 적고 7장에 근거 행을 더했다.
> - 2026-11-09 PR 리뷰 반영(#794, 2차): 7장 ND-040 근거에 강의 지급 경로(`LectureHpTaskExecutor`)와 출연·강의 HP 작업 엔티티에 제목이 없는 점을 더했다. 채팅 토픽 key를 ND-044·kafka.md 규칙대로 방 ID(접두어 없음)로 고쳤다.
> - 2026-11-10 M2 구현(#818, 강의 담당): 이의 제기 4종의 계약 상태를 "구현됨"으로 바꾸고 2장에 구현된 필드 이름을 적었다. 8장 M2 행에 Issue를 적었다.
> - 2026-11-10 M1 구현(#817, 출연 담당): 취소 요청 3종의 계약 상태를 "구현됨"으로 바꾸고 1장에 구현된 필드 이름을 적었다. 8장 M1 행에 Issue를 적었다.
> - 2026-11-11 M6 구현(#874, 회원 담당): 회원 3종의 계약 상태를 "구현됨"으로 바꾸고 4장에 구현된 필드 이름을 적었다. 심사 회차 문장을 등록 때 자동 승인을 포함하도록 고쳤다(#874 댓글에서 알림 담당 동의). 8장 M6 행에 Issue를 적었다.
> - 2026-11-12 N9 알림 소비 결정(#921, 알림 담당): 8장 M8 행에 소비 작업을 적었다.
> - 2026-11-12 M8 구현(#897, 채팅 담당): 채팅 3종의 계약 상태를 "구현됨"으로 바꾸고 6장에 구현된 필드 이름과 초대 요청 ID의 발번 방식을 적었다. 8장 M8 행에 Issue를 적었다.
> - 2026-11-12 M7 구현(#875, 고객센터 담당): `InquiryAnsweredEvent`·`LectureObjectionFileRejectedEvent`의 계약 상태를 "구현됨"으로 바꾸고 5장에 구현된 필드 이름을 적었다. `InquiryReplyEditedEvent`는 고객센터 담당 결정으로 "발행하지 않음"으로 바꿨다. 답변 이벤트는 이의 제기 판단 안내 답변에서 내지 않는다고 적었다. 8장 M7 행에 Issue를 적었다.
> - 2026-11-12 N7 고객센터 묶음(#918, 알림 담당): 5장 `InquiryReplyEditedEvent` 행의 쓰는 알림에 폐기(ND-123)를 표시하고, 8장 M7 행에 알림 소비 Issue를 적었다. 계약은 바꾸지 않았다.
> - 2026-11-11 M4 구현(#872, 강의 담당): 강의 새 이벤트 7종의 계약 상태를 "구현됨"으로 바꾸고 2장에 구현된 필드 이름을 적었다. 강의가 지급 요청에 구매 때 강의 제목을 넘기는 것(ND-040)을 3장에 적었다. 8장 M4 행에 Issue를 적었다.

## 0. 읽는 법

### 0.1 계약 상태

| 상태 | 뜻 |
| --- | --- |
| 초안 | 알림 담당이 검토하기 전이다. 발행 Issue를 보내지 않는다 |
| 확정 | 알림 담당이 확정했다. 발행 Issue를 보낼 수 있다 |
| 계약 확정 전 | 발행 담당이 그대로 구현할 수 없다고 Issue에 남겼다. 알림 담당이 다시 정할 때까지 구현하지 않는다(ND-037) |
| 구현됨 | `common/shared` 이벤트와 발행, kafka.md 토픽 표가 반영됐다 |
| 발행하지 않음 | 발행 담당이 이유와 함께 발행하지 않기로 정했다. 알림 담당이 그 알림 종류를 정리한다 |

구분 칸의 표시는 원천 설계와 같다: 🟡 shared에 있으나 Kafka로 나가지 않는다, ⚠️ 이벤트는 있으나 값이 모자란다, 🆕 새 이벤트가 필요하다. "기존 변경"은 이미 Kafka로 나가는 이벤트에 값을 더하는 요청이다.

### 0.2 모든 이벤트에 공통인 규칙

- 사건 시각을 payload 필드로 담는다(목록 정렬 기준, ND-021). 공통 봉투(`eventId`·`occurredAt`)는 두지 않는다(ND-038).
- 그 사건을 한 번만 나타내는 ID를 담는다(ND-033). 아래 "사건 ID" 칸이 알림이 원본 대상 ID로 쓰는 값이다.
- 같은 대상에서 여러 번 나지만 회차 ID가 없는 사건은 발행 모듈이 대상별 회차 번호(1부터)를 저장해 담고, 사건 ID는 `<대상 ID>:<회차>`다(ND-045). 여러 값을 잇는 사건 ID는 `:`로 잇는다.
- 받는 사람 회원 ID를 담는다(ND-038).
- 예약을 취소하는 이벤트는 그 예약을 만든 이벤트와 같은 (대상 ID, 받는 사람)을 담는다(ND-034).
- 필드 이름·타입은 그 발행 모듈의 기존 shared 이벤트 관례를 따른다(ND-038). 아래 필드 이름은 계약에서 정한 뜻이며, 발행 모듈에 같은 뜻의 기존 이름이 있으면 그것을 쓴다. 시각 타입은 출연 `LocalDateTime`, 강의 `OffsetDateTime`, 지갑·회원 `Instant`, 고객센터·채팅 `Instant`(새로 정함)다.
- Kafka key는 kafka.md "key는" 규칙을 따른다. 한 토픽에 ID 종류가 여럿이면 `<대상>:<ID>`로 접두어를 붙이고, 하나뿐이면 ID만 쓴다(회원 토픽은 회원 ID, 채팅 토픽은 방 ID, ND-044). 발행은 Modulith outbox + shared 레코드 `@Externalized` + eventType 헤더다(kafka.md "발행"). 새 이벤트·key·토픽의 kafka.md 토픽 표 반영은 발행 Issue에서 팀 승인으로 한다.

### 0.3 예약 짝 키

예약 알림은 (종류, 대상 ID, 받는 사람)으로 한 건이다(ND-025). 예약을 만드는 이벤트와 취소하는 이벤트는 아래 값을 모두 담아야 한다(ND-034). 이 값은 "사건 ID" 칸(알림 중복 방지 키)과 다를 수 있다. 예약을 만들고 취소하는 사건은 [알림 종류](notification-catalog.md) 7장이 원본이다.

| 예약 알림 | 대상 ID | 받는 사람 | 예약을 만드는 이벤트 | 예약을 취소하는 이벤트 |
| --- | --- | --- | --- | --- |
| `APPEARANCE_REQUEST_EXPIRING` | 요청서 ID | 게스트 | `AppearanceRequestSentEvent`(기존, `requestId`·`guestId`) | `AppearanceRequestAccepted`·`Rejected`·`CanceledEvent` |
| `APPEARANCE_RECRUIT_CLOSING` | 모집글 ID | 호스트 | `AppearanceRecruitOpenedEvent` | `AppearanceRecruitClosed`·`CanceledEvent` |
| `APPEARANCE_STARTING` | 출연 ID | 호스트, 게스트 각각 | `AppearanceRequestAcceptedEvent`, `AppearanceApplicationSelectedEvent` | `AppearanceCancelRequestAcceptedEvent`, `AppearanceCanceledByHostEvent` |
| `APPEARANCE_CONFIRM_PERIOD_ENDING` | 출연 ID | 호스트 | `AppearanceRequestAcceptedEvent`, `AppearanceApplicationSelectedEvent` | `AppearanceConfirmedEvent`, `AppearanceAbsentEvent`, `AppearanceCancelRequestAcceptedEvent`, `AppearanceCanceledByHostEvent` |
| `LECTURE_ORDER_ITEM_CONFIRMING` | 주문 항목 ID | 구매자 | `LectureOrderPaidEvent`(주문 항목마다) | `LectureOrderItemCanceledEvent`, `LectureOrderItemConfirmedEvent`, `LectureObjectionFiledEvent`(ND-149) |

1장·2장 표의 "담을 값"은 위 대상 ID와 받는 사람 ID를 모두 담는다. 2026-11-13 `common/shared` 레코드로 5종 모두 생성·취소 이벤트가 이 값을 주는 것을 확인했다(ND-138, #928).

## 1. 출연 (`appearance.events.v1`, M1·M3)

출연 이벤트는 DTO 관례(`hostId`·`guestId`, `long fee`, `LocalDateTime`)를 쓴다(`AppearanceRequestDto`, `AppearanceCancelRequestDto`).

| 이벤트 | 구분 | key | 담을 값 | 사건 ID | 사건 시각 | 받는 사람 | 쓰는 알림 | 계약 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `AppearanceCancelRequestedEvent` | 🟡 (M1) | `appearance:<출연 ID>` | 지금 값(`AppearanceCancelRequestDto`: 취소 요청 ID, 출연 ID, 제목, 호스트·게스트 ID, 차수, 방송 예정 일시) + 사건 시각 | 취소 요청 ID | 요청 시각(추가) | 호스트 | `APPEARANCE_CANCEL_REQUESTED` | 구현됨(#817) |
| `AppearanceCancelRequestAcceptedEvent` | 🟡 (M1) | `appearance:<출연 ID>` | 위와 같음 + 사건 시각 | 취소 요청 ID | 수락 시각(추가) | 게스트 | `APPEARANCE_CANCEL_REQUEST_ACCEPTED`, 예약 취소 입력(합의 취소) | 구현됨(#817) |
| `AppearanceCancelRequestRejectedEvent` | 🟡 (M1) | `appearance:<출연 ID>` | 위와 같음 + `autoRejected` + 사건 시각 | 취소 요청 ID | 거절 시각(추가) | 게스트 | `APPEARANCE_CANCEL_REQUEST_REJECTED` | 구현됨(#817) |
| `AppearanceRequestAcceptedEvent` | 🆕 (M3) | `appearance-request:<요청 ID>` | 요청서 ID, 출연 ID, 호스트·게스트 ID, 제목, 출연료, 방송 예정 일시, 수락 시각 | 요청서 ID | 수락 시각 | 호스트 | `APPEARANCE_REQUEST_ACCEPTED`, 예약 생성 입력(출연 시작·확인 기간), 예약 취소 입력(요청 만료 임박) | 구현됨(#871) |
| `AppearanceRequestRejectedEvent` | 🆕 (M3) | `appearance-request:<요청 ID>` | 요청서 ID, 호스트·게스트 ID, 제목, 출연료, 방송 예정 일시, 거절 시각 | 요청서 ID | 거절 시각 | 호스트 | `APPEARANCE_REQUEST_REJECTED`, 예약 취소 입력 | 구현됨(#871) |
| `AppearanceRequestCanceledEvent` | 🆕 (M3) | `appearance-request:<요청 ID>` | 요청서 ID, 호스트·게스트 ID, 제목, 출연료, 방송 예정 일시, 취소 시각 | 요청서 ID | 취소 시각 | 게스트 | `APPEARANCE_REQUEST_CANCELED`, 예약 취소 입력 | 구현됨(#871) |
| `AppearanceRequestExpiredEvent` | 🆕 (M3) | `appearance-request:<요청 ID>` | 요청서 ID, 호스트·게스트 ID, 제목, 출연료, 방송 예정 일시, 만료 시각 | 요청서 ID | 만료 시각 | 호스트, 게스트 | `APPEARANCE_REQUEST_EXPIRED` | 구현됨(#871) |
| `AppearanceApplicationSelectedEvent` | 🆕 (M3) | `appearance-application:<지원 ID>` | 지원 ID, 모집글 ID, 출연 ID, 호스트·게스트 ID, 모집글 제목, 방송 예정 일시, 선정 시각 | 지원 ID | 선정 시각 | 게스트 | `APPEARANCE_APPLICATION_SELECTED`, 예약 생성 입력(출연 시작·확인 기간) | 구현됨(#871) |
| `AppearanceApplicationNotSelectedEvent` | 🆕 (M3) | `appearance-application:<지원 ID>` | 지원 ID, 모집글 ID, 호스트·게스트 ID, 모집글 제목, 방송 예정 일시, 미선정 시각 | 지원 ID | 미선정 시각 | 게스트 | `APPEARANCE_APPLICATION_NOT_SELECTED` | 구현됨(#871) |
| `AppearanceRecruitOpenedEvent` | 🆕 (M3) | `appearance-recruit:<모집글 ID>` | 모집글 ID, 호스트 ID, 제목, 모집 마감 일시, 모집 시작 시각 | 모집글 ID | 모집 시작 시각 | 호스트 | 예약 생성 입력(`APPEARANCE_RECRUIT_CLOSING`) | 구현됨(#871) |
| `AppearanceRecruitClosedEvent` | 🆕 (M3) | `appearance-recruit:<모집글 ID>` | 모집글 ID, 호스트 ID, 제목, 모집 마감 일시, `closeType`, 마감 시각 | 모집글 ID | 마감 시각 | 호스트 | `APPEARANCE_RECRUIT_CLOSED`, 예약 취소 입력 | 구현됨(#871) |
| `AppearanceRecruitCanceledEvent` | 🆕 (M3) | `appearance-recruit:<모집글 ID>` | 모집글 ID, 호스트 ID, 제목, 취소 시각 | 모집글 ID | 취소 시각 | 호스트(예약 취소 키) | 예약 취소 입력만(ND-041). 알림 종류 없음 | 구현됨(#871) |
| `AppearanceCanceledByHostEvent` | 🆕 (M3) | `appearance:<출연 ID>` | 출연 ID, 호스트·게스트 ID, 제목, 방송 예정 일시, 위약금, 취소 시각 | 출연 ID | 취소 시각 | 게스트 | `APPEARANCE_CANCELED_BY_HOST`, 예약 취소 입력 | 구현됨(#871) |
| `AppearanceConfirmedEvent` | 🆕 (M3) | `appearance:<출연 ID>` | 출연 ID, 호스트·게스트 ID, 제목, 방송 예정 일시, `confirmType`, 확인 시각 | 출연 ID | 확인 시각 | 호스트 | `APPEARANCE_CONFIRMED`, 예약 취소 입력 | 구현됨(#871) |
| `AppearanceAbsentEvent` | 🆕 (M3) | `appearance:<출연 ID>` | 출연 ID, 호스트·게스트 ID, 제목, 방송 예정 일시, 불참 처리 시각 | 출연 ID | 불참 처리 시각 | 게스트 | `APPEARANCE_ABSENT`, 예약 취소 입력 | 구현됨(#871) |

- M1 구현(#817, 출연): 3종 모두 `@Externalized`로 key `appearance:<출연 ID>`에 나간다. `eventType` 헤더는 레코드 이름과 같다. 기존 필드(`cancelRequest`: `AppearanceCancelRequestDto` 7개 값, 거절의 `autoRejected`) 뒤에 사건 시각(`LocalDateTime`, KST)을 붙였다. 요청 `requestedAt`, 수락 `acceptedAt`, 거절 `rejectedAt`(자동 거절이면 자동 거절을 처리한 시각)이다. 값은 상태를 바꾼 처리의 시각이다(수락·거절은 이력의 응답 시각과 같고, 요청은 이력의 요청 시각과 몇 ms 다를 수 있다. 운영 서버 시간대가 `Asia/Seoul`이라 둘 다 KST다). 한 취소 요청에 결과 이벤트(수락 또는 거절)는 많아야 하나다.
- M3 구현(#871, 출연): 12종 모두 `@Externalized`로 위 key에 나가고 `eventType` 헤더는 레코드 이름과 같다. 필드는 출연 DTO 관례를 따른다. 요청서 4종은 `request`(`AppearanceRequestDto`: `requestId`·`hostId`·`guestId`·`title`·`fee`·`scheduledAt`·`acceptDeadline`) + 수락만 `appearanceId` + 사건 시각 `acceptedAt`·`rejectedAt`·`canceledAt`·`expiredAt`. 지원 2종은 `application`(`AppearanceApplicationDto`: `applicationId`·`recruitId`·`recruitTitle`·`hostId`·`guestId`·`appliedAt`) + 선정만 `appearanceId` + `scheduledAt` + `selectedAt`·`notSelectedAt`. 모집글 3종은 `recruit`(`AppearanceRecruitDto`: `recruitId`·`hostId`·`title`·`recruitDeadline`) + `openedAt`, 마감은 `closeType`(`DEADLINE`·`EARLY`) + `closedAt`, 취소는 `canceledAt`. 출연 3종은 `appearance`(`AppearanceDto`: `appearanceId`·`title`(출처 제목)·`hostId`·`guestId`·`scheduledAt`) + 일방 취소 `penaltyAmount`·`canceledAt`, 확인 `confirmType`(`HOST`·`AUTO`)·`confirmedAt`, 불참 `absentAt`. 사건 시각은 상태를 바꾼 처리의 시각(KST)이다. 미선정은 지원 거절, 마감 때 대기 지원, 모집 전체 취소 때 대기 지원마다 한 번 나간다(마감 뒤 취소에서는 다시 나가지 않는다). 발행 위치는 출연 문서([module.md](../appearance/module.md) 11.1.1)에 있다.

예약을 만들고 취소하는 짝은 [알림 종류](notification-catalog.md) 7장이 원본이다. 짝을 이루는 이벤트는 같은 (대상 ID, 받는 사람)을 준다(ND-034).

## 2. 강의 (`lecture.events.v1`, M2·M4)

강의 shared 이벤트는 `xxxUserId`, `OffsetDateTime` 관례를 쓴다(`LecturePurchaseSyncDto`, `LectureSyncDto`).

| 이벤트 | 구분 | key | 담을 값 | 사건 ID | 사건 시각 | 받는 사람 | 쓰는 알림 | 계약 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `LectureObjectionResolvedEvent` | ⚠️ (M2) | `order-item:<주문 항목 ID>` | 지금 값 + `buyerUserId`, `sellerUserId`, 강의 제목, 처리 시각 | 문의 ID | 처리 시각(추가) | 구매자, 판매자 | `LECTURE_OBJECTION_RESOLVED` | 구현됨(#818) |
| `LectureObjectionReturnCompletedEvent` | ⚠️ (M2) | `order-item:<주문 항목 ID>` | 지금 값 + `buyerUserId`, `sellerUserId`, 강의 제목, 완료 시각 | 문의 ID | 완료 시각(추가) | 구매자 | `LECTURE_OBJECTION_RETURN_COMPLETED` | 구현됨(#818) |
| `LectureObjectionWithdrawnEvent` | ⚠️ (M2) | `order-item:<주문 항목 ID>` | 지금 값 + `buyerUserId`, `sellerUserId`, 강의 제목, 철회 시각 | 문의 ID | 철회 시각(추가) | 판매자 | `LECTURE_OBJECTION_WITHDRAWN` | 구현됨(#818) |
| `LectureObjectionReturnActionRequiredEvent` | ⚠️ (M2) | `order-item:<주문 항목 ID>` | 지금 값 + `buyerUserId`, `sellerUserId`, 강의 제목, 반환 지연 회차, 발생 시각 | `<문의 ID>:<반환 지연 회차>`(ND-045) | 발생 시각(추가) | 구매자 | `LECTURE_OBJECTION_RETURN_ACTION_REQUIRED` | 구현됨(#818) |
| `LectureApprovedEvent` | 🆕 (M4) | `lecture:<강의 ID>` | 강의 ID, 판매자 ID, 제목, 검수 회차, 승인 시각 | `<강의 ID>:<검수 회차>`(ND-045) | 승인 시각 | 판매자 | `LECTURE_APPROVED` | 구현됨(#872) |
| `LectureRejectedEvent` | 🆕 (M4) | `lecture:<강의 ID>` | 강의 ID, 판매자 ID, 제목, 반려 사유, 검수 회차, 반려 시각 | `<강의 ID>:<검수 회차>`(ND-045) | 반려 시각 | 판매자 | `LECTURE_REJECTED` | 구현됨(#872) |
| `LectureOrderPaidEvent` | 🆕 (M4) | `order:<주문 ID>` | 주문 ID, 구매자 ID, 주문 항목(항목 ID, 강의 ID, 제목, 금액), 구매 시각 | 주문 ID | 구매 시각 | 구매자 | `LECTURE_ORDER_PAID`, 예약 생성 입력(주문 항목마다) | 구현됨(#872) |
| `LectureOrderHoldFailedEvent` | 🆕 (M4) | `order:<주문 ID>` | 주문 ID, 구매자 ID, 주문 항목(항목 ID, 강의 ID, 제목, 금액), 실패 시각 | 주문 ID | 실패 시각 | 구매자 | `LECTURE_ORDER_HOLD_FAILED` | 구현됨(#872) |
| `LectureOrderItemCanceledEvent` | 🆕 (M4) | `order-item:<주문 항목 ID>` | 주문 항목 ID, 구매자 ID, 제목, 반환 금액, 취소 시각 | 주문 항목 ID | 취소 시각 | 구매자 | `LECTURE_ORDER_ITEM_CANCELED`, 예약 취소 입력 | 구현됨(#872) |
| `LectureOrderItemConfirmedEvent` | 🆕 (M4) | `order-item:<주문 항목 ID>` | 주문 항목 ID, 구매자 ID, 제목, `confirmationType`, 확정 시각 | 주문 항목 ID | 확정 시각 | 구매자 | `LECTURE_ORDER_ITEM_CONFIRMED`, 예약 취소 입력 | 구현됨(#872) |
| `LectureObjectionFiledEvent` | 🆕 (M4) | `order-item:<주문 항목 ID>` | 문의 ID, 주문 항목 ID, 구매자·판매자 ID, 제목, 접수 시각 | 문의 ID | 접수 시각 | 구매자, 판매자 | `LECTURE_OBJECTION_FILED`(ND-015) | 구현됨(#872) |

- M2 구현(#818, 강의 D83): 4종 모두 기존 필드 뒤에 `buyerUserId`·`sellerUserId`(`Long`), `lectureTitle`(`String`, 구매할 때의 강의 제목), 사건 시각(`OffsetDateTime`, `+09:00`)을 붙였다. 사건 시각 필드는 판단 `resolvedAt`, 반환 완료 `returnedAt`, 철회 `withdrawnAt`, 반환 지연 `occurredAt`이다. 반환 지연 회차는 `returnDelaySequence`(`Integer`)이고 강의가 주문 항목(`return_delay_count`)에 저장한다. #818 배포 전에 저장된 발행 기록을 강의가 다시 보내면 새 필드가 없어 `null`로 읽힌다.
- M4 구현(#872, 강의 D85): 7종 모두 `@Externalized`로 위 key에 나가고 `eventType` 헤더는 레코드 이름과 같다. 필드는 wrapper 타입이고 사건 시각은 `OffsetDateTime`(`+09:00`)이다.
  - 검수 승인 `lectureId`, `sellerUserId`, `lectureTitle`(그때 강의 제목), `inspectionSequence`(`Integer`), `approvedAt`. 검수 반려는 `approvedAt` 대신 `rejectionReason`, `rejectedAt`이다.
  - 결제 완료 `orderId`, `buyerUserId`, `items`, `paidAt`. 결제 실패는 `paidAt` 대신 `failedAt`이다. `items`의 항목은 `orderItemId`, `lectureId`, `lectureTitle`, `buyerAmountHp`(구매자가 내는 HP = 가격 − 쿠폰 할인, 0일 수 있음)다.
  - 구매 취소 `orderItemId`, `buyerUserId`, `lectureTitle`, `buyerAmountHp`(돌려준 HP), `canceledAt`.
  - 구매 확정 `orderItemId`, `buyerUserId`, `lectureTitle`, `confirmationType`(`DIRECT`·`FIRST_PLAY`·`AUTO`), `confirmedAt`. 자동 구매 확정 시각이 지난 뒤의 직접 확정·재생 동의도 `AUTO`이고(강의 D34), `AUTO`의 `confirmedAt`은 자동 구매 확정 시각(구매일 + 8일 00:00)이다.
  - 이의 제기 접수 `inquiryId`, `orderItemId`, `buyerUserId`, `sellerUserId`, `lectureTitle`, `filedAt`.
  - 주문·구매의 `lectureTitle`은 구매 때 저장한 제목이다. 사건 시각은 강의에 저장한 시각이고, 결제 실패만 저장 칸이 없어 결제 실패를 기록한 시각이다.
  - 결제 실패는 보류 거절·보류 무효화·쿠폰 예약 거절·관리자 실패 확정 모두에서 주문마다 한 번 나간다. 구매 확정은 직접 확정·재생 동의·자동 확정 모두에서 항목마다 한 번, 이의 제기 접수는 처음 처리한 접수 명령에서만 나간다.
- **예약 기한 불변식(ND-143, #928, 강의 담당 확인은 #928 PR 리뷰)**: 알림의 구매 취소 마감 임박 예약은 아래를 전제로 한다. 강의가 이 관계를 바꾸면 알림 담당과 먼저 협의한다.
  - `LectureOrderPaidEvent.paidAt`은 주문 항목의 구매 시각과 같다(`LectureOrder.markPaid`가 같은 시각으로 `OrderItem.markPurchased`를 부른다). 구매일은 그 KST 날짜이고 자동 구매 확정 시각은 (구매일 + 8일) 00:00이다(policy.md "기한 계산").
  - `LectureOrderItemCanceledEvent.canceledAt`, `LectureOrderItemConfirmedEvent.confirmedAt`, `LectureObjectionFiledEvent.filedAt`(ND-149, 접수는 구매 상태에서만 된다 `OrderItem.fileObjection`)은 그 항목의 구매 시각 이후다.
  - `confirmedAt`은 자동 구매 확정 시각 이하다(`OrderItem.confirmFirstOf`). `canceledAt`은 반환 완료 시각(`OrderItem.completeCancel`)이라 자동 구매 확정 시각을 넘을 수 있다.
- 검수 회차는 강의가 검수 신청(`requestInspection`)될 때마다 1씩 늘어나는 값이다. 반환 지연 회차는 같은 문의에서 반환 지연 이벤트가 날 때마다 1씩 늘어나는 값이다. 강의는 등록을 1회차로 센다(`lecture.inspection_count`, 기존 강의는 1).
- `LectureObjectionReturnActionRequiredEvent`는 강의 토픽 이벤트지만 알림은 알림 종류 5장(고객센터)에 있다.

## 3. 지갑 (`billing.events.v1`, M5·ND-040)

지갑 이벤트는 `Long ...Id`·`userId`, `long ...Hp`, `Instant ...At` 관례를 쓴다(예: `ChargeCompletedEvent`, `ExchangePayoutFailedEvent`).

| 이벤트 | 구분 | key | 담을 값 | 사건 ID | 사건 시각 | 받는 사람 | 쓰는 알림 | 계약 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ChargeFailedEvent` | 🆕 (M5) | `point-charge:<충전 ID>` | `chargeId`, `userId`, 금액, `failedAt` (구현: `amountHp`·`paymentAmountKrw`) | `chargeId` | `failedAt` | 충전한 회원 | `CHARGE_FAILED` | 구현됨 (#873) |
| `RefundFailedEvent` | 🆕 (M5) | `point-refund-request:<환불 요청 ID>` | `refundRequestId`, `userId`, 금액, `failedAt` (구현: `requestedAmountHp`. 환불 요청 FAILED만, 일부 환불은 `RefundCompletedEvent`) | `refundRequestId` | `failedAt` | 신청한 회원 | `REFUND_FAILED` | 구현됨 (#873) |
| `RefundManualReviewRequiredEvent` | 🆕 (M5) | `point-refund-request:<환불 요청 ID>` | `refundRequestId`, `userId`, 금액, 발생 시각 (구현: `requestedAmountHp`·`manualReviewRequiredAt`) | `refundRequestId` | `manualReviewRequiredAt` | 신청한 회원 | `REFUND_MANUAL_REVIEW_REQUIRED` | 구현됨 (#873) |
| `ExchangeApprovedEvent` | 🆕 (M5) | `exchange:<환전 ID>` | `exchangeId`, `userId`, 금액, `payoutScheduledAt`(정산 예정 시각), `approvedAt` (구현: `requestedAmountHp`) | `exchangeId` | `approvedAt` | 신청한 회원 | `EXCHANGE_APPROVED` | 구현됨 (#873) |
| `PayoutCompletedEvent` | 기존 변경 (ND-040) | `settlement:<지급 건 ID>`(지금 그대로) | 지금 값 + `sourceTitle`(출처 제목, `String`. 길이 상한은 지갑 BD-163. 제목 없는 요청은 null이고 JSON에 쓰지 않는다) | `settlementId`(지금 그대로) | `paidAt`(지금 그대로) | `receiverUserId` | `PAYOUT_COMPLETED`, `PAYOUT_COMPLETED_DAILY` | 구현됨 (#873) |

- ND-040 변경 범위: 지급 요청 `PayoutCommand`(정상 지급)와 `PenaltyCommand`(위약금 지급)에 출처 제목을 더하고, 출연·강의가 지급을 요청할 때 넘긴다. 출연·강의는 저장해 둔 HP 작업으로 나중에 요청하므로, 작업에 제목을 어떻게 보관할지는 출연·강의 담당이 정한다. 지갑은 그 값을 `PayoutCompletedEvent.sourceTitle`로 담는다. 이벤트는 지급 건(`Settlement`)에서 만들므로 값을 어떻게 보관할지(저장 칸 추가 등)는 지갑 담당이 정한다. 지갑·출연·강의 세 곳의 Issue가 필요하다.
- 강의 구현(#872, 강의 D85 (6)): 강의 지급 요청(`PayoutCommand`)의 `sourceTitle`은 구매 때 저장한 강의 제목(`lectureTitleSnapshot`)이다. HP 작업에 저장하지 않고 지급 요청을 보낼 때 주문 항목에서 읽으므로, 배포 전에 만든 지급 작업도 제목이 실리고 구매 뒤 강의 제목이 바뀌어도 그대로다. 강의는 `PenaltyCommand`를 쓰지 않는다.
- 참고: 지갑은 이미 수익 조회 내부 API로 출처 제목을 받는다([internal-earnings.md](../api/internal-earnings.md) 3.2 `title`, 6.2 `title`).

## 4. 회원 (`member.events.v1`, M6·ND-042)

key는 회원 ID다(토픽 표). 회원 이벤트는 `xxxId`, `Instant xxxAt` 관례를 쓴다(`UserMaskedEvent`).

| 이벤트 | 구분 | key | 담을 값 | 사건 ID | 사건 시각 | 받는 사람 | 쓰는 알림 | 계약 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `InfluencerChannelRejectedEvent` | 🆕 (M6) | 회원 ID | 회원 ID, 채널 ID, 심사 회차, 반려 사유, 반려 시각 | `<채널 ID>:<심사 회차>`(ND-045) | 반려 시각 | 회원 | `INFLUENCER_CHANNEL_REJECTED` | 구현됨(#874) |
| `InfluencerUpgradeRequestRejectedEvent` | 🆕 (M6) | 회원 ID | 회원 ID, 승급 신청 ID, 반려 사유, 반려 시각 | 승급 신청 ID | 반려 시각 | 회원 | `INFLUENCER_UPGRADE_REQUEST_REJECTED` | 구현됨(#874) |
| `InfluencerChannelApprovedEvent` | 🆕 (ND-042) | 회원 ID | 회원 ID, 채널 ID, 심사 회차, 승인 방식(자동·관리자), 승인 시각 | `<채널 ID>:<심사 회차>`(ND-045) | 승인 시각 | 회원 | `INFLUENCER_CHANNEL_APPROVED` | 구현됨(#874) |

심사 회차는 채널의 심사가 시작된 횟수다. 등록만 되면 0이고, 등록하며 바로 자동 승인되면 1이며, 승인 요청(`requestApproval`)마다 1씩 는다. 승인·반려 이벤트는 그때의 회차를 담으므로 1부터 시작한다. 승인 방식은 `verifiedBy`가 없으면 자동, 있으면 관리자다. 자동 승인은 등록 때와 승인 요청 때 모두 승인 이벤트를 낸다.

- M6 구현(#874, 회원): 3종 모두 `@Externalized`로 key 회원 ID(접두어 없음)에 나간다. `eventType` 헤더는 레코드 이름과 같다. 필드는 다음과 같다(시각은 `Instant`).
  - `InfluencerChannelApprovedEvent(userId, channelId, reviewSequence, autoApproved, approvedAt)`: `reviewSequence`는 `Integer`, `autoApproved`는 `boolean`(자동 승인이면 `true`)이다.
  - `InfluencerChannelRejectedEvent(userId, channelId, reviewSequence, rejectionReason, rejectedAt)`
  - `InfluencerUpgradeRequestRejectedEvent(userId, upgradeRequestId, rejectionReason, rejectedAt)`
- 심사 회차는 회원이 채널(`members.influencer_channel.review_count`)에 저장하고, 승인 요청은 채널 행을 잠가 회차가 동시 요청에 덮어써지지 않게 한다. #874 배포 전 채널은 등록만 된 채널 0, 나머지(심사 대기·반려·승인) 1로 채웠다(지난 요청 횟수는 기록이 없어 최소값). 배포 전 심사 결과는 이벤트로 나간 적이 없어 사건 ID가 겹치지 않는다. 그래서 회차는 사건을 구분하는 데만 쓰고, 실제 심사 횟수로 화면에 보여 주거나 통계에 쓰지 않는다.
- 사건 시각은 상태를 바꾼 처리의 시각(채널 `verifiedAt`, 승급 신청 `reviewedAt`)이다. 승인은 역할 변경(`UserModifiedEvent`)과 같은 트랜잭션에서 나가고, 역할이 바뀌지 않는 승인(이미 인플루언서인 회원의 채널)에도 나간다. 예: 심사 대기 채널이 있는 회원이 다른 채널의 자동 승인이나 관리자 인플루언서 지정(#724)으로 먼저 인플루언서가 된 뒤 그 채널이 승인될 때.

## 5. 고객센터 (`customersupport.events.v1`, M7)

key는 `inquiry:<문의 ID>`다(토픽 표). 고객센터 이벤트의 시각 필드는 `Instant`로 정했다(그전 고객센터 이벤트에는 시각 필드가 없었다).

| 이벤트 | 구분 | key | 담을 값 | 사건 ID | 사건 시각 | 받는 사람 | 쓰는 알림 | 계약 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `InquiryAnsweredEvent` | 🆕 (M7) | `inquiry:<문의 ID>` | 문의 ID, 답변 ID, 작성자 ID, 제목, 유형, 답변 시각 (구현: `inquiryId`, `replyId`, `userId`, `title`, `category`, `answeredAt`). 관리자가 직접 단 답변에서만 낸다. 이의 제기 판단 안내 답변은 내지 않는다(판단 알림은 `LectureObjectionResolvedEvent`) | `replyId` | `answeredAt` | 문의한 회원(`userId`) | `INQUIRY_ANSWERED` | 구현됨(#875) |
| `InquiryReplyEditedEvent` | 🆕 (M7) | `inquiry:<문의 ID>` | 문의 ID, 답변 ID, 작성자 ID, 제목, 유형, 수정 회차, 수정 시각 | `<답변 ID>:<수정 회차>`(ND-045) | 수정 시각 | 문의한 회원 | ~~`INQUIRY_REPLY_EDITED`~~(폐기, ND-123) | 발행하지 않음(#875, 고객센터 담당 결정. 답변 수정은 대부분 오타 고치기라 알릴 일이 아니고, 내용을 바로잡을 때는 새 답변을 달면 답변 도착 알림이 간다. 수정 회차 칸도 두지 않는다) |
| `LectureObjectionFileRejectedEvent` | 🆕 (M7) | `inquiry:<문의 ID>` | 문의 ID, 구매자 ID, 주문 항목 ID, 접수 불가 시각 (구현: `inquiryId`, `orderItemId`, `buyerUserId`, `fileRejectedAt`) | `inquiryId` | `fileRejectedAt` | 구매자(`buyerUserId`) | `LECTURE_OBJECTION_FILE_REJECTED`(ND-015) | 구현됨(#875) |

문의 하나에 답변이 여러 개 달릴 수 있어 답변 이벤트의 사건 ID는 답변 ID다.

- M7 구현(#875, 고객센터): 2종 모두 `@Externalized`로 key `inquiry:<문의 ID>`에 나간다. `eventType` 헤더는 레코드 이름과 같다. 시각은 `Instant`다.
  - `InquiryAnsweredEvent(inquiryId, replyId, userId, title, category, answeredAt)`: `userId`는 문의(`Inquiry.userId`)를 쓴 회원이다. `category`는 고객센터 문의 유형 enum의 이름(`String`, 예: `SERVICE_COMPLAINT`)이다. shared에 문의 유형 enum이 없어 문자열로 둔다. `answeredAt`은 답변의 작성 시각(화면의 답변 작성 시각과 같다)이다. 관리자 답변 API(`InquiryUseCase.reply`)에서만 낸다. 이의 제기 판단 안내 답변은 강의가 판단을 반영할 때 고객센터가 다는 답변이라 내지 않는다(판단 알림 `LECTURE_OBJECTION_RESOLVED`는 강의의 `LectureObjectionResolvedEvent`로 간다). 판단을 마친 이의 제기 문의에 관리자가 답변을 더 달면 낸다.
  - `LectureObjectionFileRejectedEvent(inquiryId, orderItemId, buyerUserId, fileRejectedAt)`: 재시도 배치가 보낸 접수를 강의가 거절해 문의가 접수 불가가 될 때 낸다. 문의가 접수 처리 중에서 접수 불가로 바뀔 때만 내므로 문의마다 한 번이다. 회원 요청 안에서 바로 거절된 접수는 내지 않는다(ND-015).

## 6. 채팅 (`chat.events.v1` 새 토픽, M8·ND-044)

채팅은 지금 Kafka로 발행하는 이벤트가 없다(Modulith 설정만 있다). ND-044에 따라 새 토픽을 요청한다. 종류 코드는 채팅 안내와 이름이 같지만 뜻·받는 사람이 다르다(ND-043). 시각 타입은 `Instant`로 정했다.

| 이벤트 | 구분 | key | 담을 값 | 사건 ID | 사건 시각 | 받는 사람 | 쓰는 알림 | 계약 상태 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `GroupMembersInvitedEvent` | 🆕 (M8) | 방 ID | 방 ID, 초대 요청 ID, 모집글 ID·제목, 호스트 ID, 이번 초대로 실제 참여한 회원 ID 목록(`JOINED`만), 초대 시각 (구현: `chatRoomId`, `inviteRequestId`, `recruitId`, `title`, `hostUserId`, `invitedUserIds`, `invitedAt`) | `<chatRoomId>:<inviteRequestId>` | `invitedAt` | 참여한 회원(`invitedUserIds`) | `GROUP_MEMBERS_INVITED` | 구현됨(#897) |
| `GroupMemberKickedEvent` | 🆕 (M8) | 방 ID | 방 ID, 모집글 ID·제목, 호스트 ID, 강퇴된 회원 ID, `participationVersion`, 강퇴 시각 (구현: `chatRoomId`, `recruitId`, `title`, `hostUserId`, `kickedUserId`, `participationVersion`, `kickedAt`) | `<chatRoomId>:<kickedUserId>:<participationVersion>` | `kickedAt` | 강퇴된 회원(`kickedUserId`) | `GROUP_MEMBER_KICKED` | 구현됨(#897) |
| `GroupRoomClosedEvent` | 🆕 (M8) | 방 ID | 방 ID, 모집글 ID·제목, 호스트 ID, 폐쇄 시점 참여자 ID 목록(호스트 포함), 폐쇄 시각 (구현: `chatRoomId`, `recruitId`, `title`, `hostUserId`, `memberUserIds`, `closedAt`) | `chatRoomId` | `closedAt` | 남은 참여자(`memberUserIds`, 알림이 호스트를 뺀다) | `GROUP_ROOM_CLOSED` | 구현됨(#897) |

- 초대 요청 ID는 같은 방에서 다시 쓰지 않는다. 채팅이 초대 결과 기록 행 ID를 발번해 해결했다(아래 M8 구현).
- 실제로 참여한 회원이 없는 초대는 이벤트를 내지 않는다.
- 폐쇄 이벤트는 폐쇄 시점 참여자 전원을 담고, 폐쇄한 호스트를 빼는 일은 알림이 한다(알림 종류 6장).
- M8 구현(#897, 채팅): 3종 모두 `@Externalized`로 key 방 ID(접두어 없음)에 나간다. `eventType` 헤더는 레코드 이름과 같다. 시각은 `Instant`이고 상태를 바꾼 처리의 시각이다. ID는 `Long`, `participationVersion`은 `int`다. 필드 이름은 채팅의 기존 이름을 따랐다(방 ID `chatRoomId`, 호스트 `hostUserId`, 모집글 제목 `title`, 초대 안내의 `invitedUserIds`, 강퇴 안내의 `kickedUserId`).
  - `GroupMembersInvitedEvent(chatRoomId, inviteRequestId, recruitId, title, hostUserId, invitedUserIds, invitedAt)`: `inviteRequestId`는 채팅이 초대 결과 기록(`chat_group_invite_request`)마다 새로 발번하는 행 ID다. 클라이언트의 멱등 키 `requestId`(UUID)가 아니다. 보존 기간이 지난 같은 `requestId`를 새 요청으로 처리할 때도 기존 행을 지우고 새 행을 만들어 새 ID를 받으므로, 같은 방에서 초대 요청 ID가 다시 쓰이지 않는다. 보존 기간 안의 같은 `requestId` 재시도는 저장한 결과만 돌려주고 이벤트를 내지 않는다. `invitedUserIds`는 요청 순서다.
  - `GroupMemberKickedEvent(chatRoomId, recruitId, title, hostUserId, kickedUserId, participationVersion, kickedAt)`: `participationVersion`은 끝난 참여 세대다(1부터, 재초대마다 1씩 는다). 같은 세대의 강퇴 재시도는 이벤트를 내지 않는다.
  - `GroupRoomClosedEvent(chatRoomId, recruitId, title, hostUserId, memberUserIds, closedAt)`: 처음 폐쇄할 때만 낸다. `memberUserIds`는 폐쇄 시점 참여자(호스트 포함, 나간·강퇴된 회원 제외)이고 회원 ID 오름차순이다. 호스트 자격 상실로 읽기 전용이 된 방은 내지 않는다(ND-004).
  - `title`은 방이 가진 모집글 제목 사본이다(방을 만들 때 복사하고 초대 후보 검색 때 출연 앱 값으로 갱신한다). 출연 앱의 지금 제목과 다를 수 있다.

## 7. 확정 근거 (2026-11-09 발행 앱 코드 조사)

클래스는 각 앱 `src/main/java` 아래에 있다. 코드를 읽어 확인했고 실행하지는 않았다. 줄 번호는 바뀌기 쉬워 적지 않는다.

| 이벤트 | 확인한 사실 | 계약 |
| --- | --- | --- |
| 모집글 3종 | `AppearanceRecruit`(Long id). 마감·취소는 모집글마다 한 번이다. 모집글 key가 토픽 표에 없다 | 사건 ID 모집글 ID, key `appearance-recruit:` |
| 강의 검수 2종 | 반려 → `Lecture.requestInspection` 재신청 → 승인·반려가 반복되고 검수 이력 엔티티가 없다 | 검수 회차(ND-045) |
| 강의 주문 2종 | `LectureOrder`(Long id). 결제(보류) 실패는 주문마다 한 번이다. 주문 key가 토픽 표에 없다 | 사건 ID 주문 ID, key `order:` |
| 반환 지연 | 같은 문의에서 관리자 재시도 뒤 `ReturnResultHandler.onDead`가 다시 난다 | 반환 지연 회차(ND-045) |
| 충전·환불 실패, 운영자 확인 | 충전 실패는 충전(`PointCharge`)마다 한 번, 환불 실패·운영자 확인은 환불 요청(`PointRefundRequest`)마다 한 번이다 | 기존 ID |
| 지급 완료 제목(ND-040) | 정상 지급 요청 `PayoutCommand`와 위약금 지급 요청 `PenaltyCommand`(shared)에 제목 필드가 없다. 출연은 `HpTaskExecuteUseCase`에서 `PayoutCommand`·`PenaltyCommand`를, 강의는 `LectureHpTaskExecutor`에서 `PayoutCommand`를 만든다. 두 앱 모두 저장해 둔 HP 작업(`HpTask`, `LectureHpTask`)으로 나중에 요청하는데, 두 작업 엔티티에 제목이 없다. 지갑은 `BillingEventPublisher`가 지급 건(`Settlement`)에서 이벤트를 만들고, `Settlement`에도 제목이 없다 | 두 요청에 출처 제목 추가. 작업에서의 보관은 출연·강의 담당, 지급 건에서의 보관은 지갑 담당 |
| 예약 짝 키(0.3) | `AppearanceRequestSentEvent`는 `AppearanceRequestDto`(`requestId`·`hostId`·`guestId`)를 담는다. 나머지 짝 이벤트는 1·2장 표에서 대상 ID와 받는 사람을 담도록 정했다 | 0.3 표 |
| 환전 승인 | 정산일 필드는 없고 `Exchange.approve`가 `payoutScheduledAt`(`Instant`)을 정한다 | `payoutScheduledAt` |
| 채널 인증 2종 | 같은 채널로 반려 → `InfluencerChannel.requestApproval` 재요청이 반복되고 심사 이력이 없다. 자동 승인은 `verifiedBy`가 없다 | 심사 회차(ND-045) |
| 승급 반려 | `InfluencerUpgradeRequest`는 신청 1건이 1행이다 | 승급 신청 ID |
| 답변 2종 | 문의(`Inquiry`) 하나에 답변이 여러 개 달린다. `InquiryReply.edit`는 횟수 제한·이력이 없다 | 답변 ID. 답변 수정은 발행하지 않아 수정 회차를 두지 않는다(#875, 5장) |
| 접수 불가 | 문의마다 한 번이다 | 문의 ID |
| 채팅 3종 | 단체방은 `ChatRoom`(Long id). 초대 요청 기록은 방별 `requestId`가 유일하나 보존 기간 뒤 재사용 가능. 강퇴 기록 ID는 없고 재초대 뒤 다시 강퇴될 수 있다(`participationVersion`). 폐쇄는 한 번이고 안내 대상에 호스트가 포함된다. Kafka 발행 코드가 없다 | 위 6장 |

## 8. 발행 Issue 현황

| 묶음 | 발행 담당 | 이벤트 | Issue | 상태 |
| --- | --- | --- | --- | --- |
| M1 | 출연 | 취소 요청 3종 Kafka 발행 | #817 | 구현됨 |
| M2 | 강의 | 이의 제기 4종 값 추가(반환 지연 회차 포함) | #818 | 구현됨 |
| M3 | 출연 | 출연 🆕 12종, 지급 요청에 출처 제목(ND-040) | #871 | 12종 구현됨. 출처 제목은 출연 HP 작업에 보관해 지급 요청에 싣는다(`HpTaskExecuteUseCase`, #873 shared 필드). 알림 소비는 N7 출연(#908) |
| M4 | 강의 | 강의 🆕 7종(검수 회차 포함), 지급 요청에 출처 제목(ND-040) | #872 | 구현됨. 알림 소비는 N7 강의(#910, ND-114) |
| M5 | 지갑 | 지갑 🆕 4종, `PayoutCompletedEvent.sourceTitle` | #873 | 구현됨(PR #886, 지갑 결정 BD-162·BD-163). 출연·강의가 제목을 넘기는 일은 M3·M4 |
| M6 | 회원 | 회원 🆕 3종(심사 회차 포함) | #874 | 구현됨. 알림 소비는 N7 회원(#915, ND-118) |
| M7 | 고객센터 | 고객센터 🆕 2종(답변 도착·이의 제기 접수 불가). 답변 수정은 발행하지 않음(5장) | #875 | 구현됨. 알림 소비는 N7 고객센터(#918) |
| M8 | 채팅 | 채팅 🆕 3종, 새 토픽 `chat.events.v1` | #897 | 구현됨. 알림 소비는 N9(#921, ND-129) |
