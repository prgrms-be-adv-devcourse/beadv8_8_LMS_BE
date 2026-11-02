# 이의 제기 내부 API·이벤트 계약

고객센터가 강의 내용 다름 이의 제기를 강의에 접수·판단·철회할 때 쓰는 계약이다(#278·#279). 접수는 강의 내부 HTTP, 판단·철회와 그 결과는 Kafka 이벤트다. 결정 기록은 강의 설계 문서 D60·D71이고, 흐름 그림은 [강의 모듈 연동](../lecture/integration.md) 6장에 있다.

- 공통 규칙(경로, 내부 API 키 인증, 회원 세션 불가, 진입점 차단, RsData·ProblemDetail, Swagger 노출)은 [수익 내부 API 계약 1장](internal-earnings.md#1-공통-규칙)과 같다.
- 계약 타입은 `common/shared`에 있다: `shared.lecture.dto`(요청·결과), `shared.lecture.domain`(`LectureObjectionCommandType`, `LectureObjectionFailureCode`), `shared.lecture.event`(강의 결과 이벤트), `shared.customersupport.event`(고객센터 요청 이벤트), `shared.lecture.out.LectureApiClient`.
- 고객센터 앱은 `hapbang.clients.lecture.base-url`(환경 변수 `LECTURE_API_BASE_URL`)로 강의 앱을 부른다([k8s 문서](../deploy/k8s.md#앱-사이-내부-호출-주소)).

## 1. 명령 ID

명령 하나는 문의 하나의 접수·판단·철회 중 하나다. 고객센터가 만들고 강의가 명령 이력(`lecture_objection_command`)으로 한 번만 처리한다.

| 종류 | 명령 ID | 전달 |
| --- | --- | --- |
| `FILE` | `FILE:<문의 ID>` | 내부 HTTP (2장) |
| `RESOLVE` | `RESOLVE:<문의 ID>` | `customersupport.events.v1` (3장) |
| `WITHDRAW` | `WITHDRAW:<문의 ID>` | `customersupport.events.v1` (3장) |

- 같은 명령 ID·같은 내용이 다시 오면 강의는 처음 결과를 재현하고 항목을 다시 바꾸지 않는다. 이벤트도 다시 내지 않는다.
- 같은 명령 ID·다른 내용이면 처음 결과를 그대로 두고 `OBJECTION_COMMAND_CONFLICT`로 거절한다.
- 내용 비교는 명령 종류와 값을 `|`로 이은 문자열의 SHA-256이다. 접수: 문의 ID·주문 항목 ID·구매자·사유, 판단: 문의 ID·주문 항목 ID·인정 여부·관리자, 철회: 문의 ID·주문 항목 ID·구매자.
- 실패한 명령의 운영 복구(#267)는 원인을 고친 뒤 다른 명령 ID를 쓴다.
- 추적 ID(`correlationId`)는 두지 않는다. 추적은 trace가 맡는다(ADR 0002).

## 2. 접수 `POST /api/v1/internal/lectures/objections/file`

주문 항목을 이의 제기 접수(`FILED`)로 바꿔 판단·철회 전까지 지급·구매 취소를 멈춘다.

요청 본문 (`LectureObjectionFileRequest`)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `commandId` | string | `FILE:<문의 ID>` |
| `inquiryId` | number | 고객센터 문의 ID |
| `orderItemId` | number | 이의 제기할 강의 주문 항목 ID |
| `buyerUserId` | number | 문의를 쓴 회원 ID. 주문 항목의 구매자여야 한다 |
| `reason` | string | 이의 제기 사유(문의 내용). 비어 있으면 안 된다 |

성공 200 `data` (`LectureObjectionFiledDto`): `commandId`, `inquiryId`, `orderItemId`. 같은 요청을 다시 보내도 같다.

| 상태 | `code` | 뜻 |
| --- | --- | --- |
| 404 | `OBJECTION_NOT_BUYER` | 구매자 본인 항목이 아니다(없는 항목 포함) |
| 409 | `OBJECTION_NOT_OBJECTIONABLE` | 구매 완료 전이거나 취소·반환된(처리 중 포함) 항목이다 |
| 409 | `OBJECTION_ALREADY_PAID_OUT` | 이미 판매자에게 지급된 항목이다 |
| 409 | `OBJECTION_ALREADY_FILED` | 이미 접수되었거나 판단된 항목이다 |
| 409 | `OBJECTION_PERIOD_EXPIRED` | 지급 예정 시각(구매일 다음 날부터 30일)이 지났다 |
| 409 | `OBJECTION_COMMAND_CONFLICT` | 같은 명령 ID로 다른 내용을 보냈다 |
| 400 | `INVALID_REQUEST` | 빠진 값·빈 사유 |
| 401 | (본문 없음) | 내부 API 키가 없거나 틀리다 |
| 5xx·시간 초과 | | 결과를 모른다. 같은 요청을 다시 보낸다 |

- 거절도 명령 이력에 커밋한 뒤 응답한다. 같은 요청을 다시 보내면 같은 사유다.
- 고객센터 `LectureApiClient.fileObjection`은 4xx를 `LectureApiCallException(REJECTED, code)`, 5xx·연결 실패·시간 초과·읽을 수 없는 응답을 `UNAVAILABLE`로 던진다. 고객센터는 `code`가 `LectureObjectionFailureCode`가 아니면(401 등) 설정 문제로 보고 거절로 기록하지 않는다.

## 3. 이벤트

토픽·key·헤더·재시도 규칙은 [Kafka 문서](../architecture/infrastructure/kafka.md)를 따른다. 본문은 shared 레코드 JSON이고 헤더는 `eventType`(레코드 이름)·`eventVersion`(`1`)이다.

| 이벤트 | 토픽 · key | 필드 | 받는 쪽 |
| --- | --- | --- | --- |
| `LectureObjectionResolutionRequestedEvent` | `customersupport.events.v1` · `inquiry:<문의 ID>` | `commandId`, `inquiryId`, `orderItemId`, `accepted`, `adminUserId` | 강의 `lecture-app.objection-command` |
| `LectureObjectionWithdrawalRequestedEvent` | `customersupport.events.v1` · `inquiry:<문의 ID>` | `commandId`, `inquiryId`, `orderItemId`, `buyerUserId` | 강의 `lecture-app.objection-command` |
| `LectureObjectionResolvedEvent` | `lecture.events.v1` · `order-item:<주문 항목 ID>` | `commandId`, `inquiryId`, `orderItemId`, `accepted` | 고객센터 `customer-support-app.objection-result` |
| `LectureObjectionWithdrawnEvent` | `lecture.events.v1` · `order-item:<주문 항목 ID>` | `commandId`, `inquiryId`, `orderItemId` | 고객센터 |
| `LectureObjectionRequestRefusedEvent` | `lecture.events.v1` · `order-item:<주문 항목 ID>` | `commandId`, `inquiryId`, `orderItemId`, `commandType`(`RESOLVE`·`WITHDRAW`), `failureCode` | 고객센터 |
| `LectureObjectionReturnCompletedEvent` | `lecture.events.v1` · `order-item:<주문 항목 ID>` | `inquiryId`, `orderItemId` | 고객센터 |
| `LectureObjectionReturnActionRequiredEvent` | `lecture.events.v1` · `order-item:<주문 항목 ID>` | `inquiryId`, `orderItemId` | 고객센터 |

- 판단·철회 거절 사유(`failureCode`): `OBJECTION_NOT_BUYER`(철회), `OBJECTION_NOT_FILED`, `OBJECTION_INQUIRY_MISMATCH`, `OBJECTION_COMMAND_CONFLICT`.
- 인정 결과는 HP 반환 완료가 아니다. 인정하면 항목은 반환 처리 중이고, 반환은 강의 HP 작업 배치가 한다. 반환이 끝나면 `ReturnCompletedEvent`, 자동 처리가 멈추면 `ReturnActionRequiredEvent`가 나간다. 관리자가 반환 작업을 다시 시도해 또 멈추면 다시 나간다.
- 같은 key라도 재발행 때 순서가 바뀔 수 있다. 고객센터는 처리 중인 명령(명령 ID가 같음)의 결과만 기록하고, HP 반환 상태는 `PENDING < ACTION_REQUIRED < COMPLETED` 순서로만 바꾼다. 반환 이벤트가 판단 결과보다 먼저 와도 된다.
- 업무 거절은 이벤트다. 낙관적 락 충돌·DB 장애 같은 기술 실패는 받는 쪽 리스너가 예외를 던져 Kafka 재시도(3회) 뒤 그룹별 DLT로 보낸다.

## 4. 고객센터 응답

고객센터 사용자 API(`/api/v1/inquiries`)가 연동 결과를 어떻게 보여 주는지는 Swagger(`ApiV1InquirySpec`)와 D60·D71에 있다. 요약:

| 요청 | 응답 |
| --- | --- |
| 이의 제기 문의 접수 | 강의 접수 성공 201 `FILED`, 결과 불명 202 `FILING`(서버가 매분 같은 명령으로 다시 보냄), 바로 거절 4xx(문의 삭제). 202 뒤 거절은 문의를 남기고 `FILE_REJECTED` + `objectionNotice: "접수 불가 · 사유"` |
| 관리자 판단 | 202 `RESOLUTION_PENDING` → 강의 반영 뒤 `RESOLVED`(같은 요청을 다시 보내면 200). 다른 판단·처리 중 409 `OBJECTION_IN_PROGRESS`, 강의 거절 뒤 409 `OBJECTION_RECOVERY_REQUIRED` |
| 구매자 문의 삭제(철회) | 202 `WITHDRAWAL_PENDING` → 강의 반영 뒤 삭제(다시 보내면 204). 강의 거절이면 `WITHDRAWAL_FAILED` |

- 응답 필드: `objectionLinkStatus`(연동 상태), `hpReturnStatus`(인정한 이의 제기의 HP 반환 상태), `objectionNotice`(사용자 안내). 관리자 응답에만 `objectionFailureCode`·`objectionCommandId`가 있다.
