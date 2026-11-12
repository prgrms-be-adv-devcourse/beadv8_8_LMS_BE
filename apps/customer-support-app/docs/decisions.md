# 고객센터(문의) 앱 결정 사항

customer-support-app에서 확정한 결정이다. 이의 제기 강의 연동 결정은 강의 결정 기록(`docs/lecture/decisions.md` D60·D61·D71)에 있다.

- **Gateway 사용자 헤더 인증(2026-11-02, #540, 강의 D72·출연 #395와 같은 방식)**: 회원 앱이 JWT로 바뀌어(#370) 세션 설정으로는 로그인이 필요한 문의 API가 모두 401이었다. Envoy가 JWT를 검증해 넘기는 `X-User-Id`·`X-User-Role`로 `global/auth/GatewayUserHeaderFilter`가 로그인 상태를 만든다(`getName()`은 회원 ID, 권한은 `ROLE_{역할}`이라 `@LoginUserId`와 Facade의 `@PreAuthorize`는 그대로다). 헤더가 없으면 익명, 한쪽만 있거나 형식이 틀리면 익명 + 경고 로그다. 서명은 다시 검증하지 않는다(Gateway가 외부 `X-User-*`를 지우고 NetworkPolicy가 8080에 Gateway만 들인다). 쿠키로 인증하지 않으므로 CSRF를 끄고 세션을 만들지 않는다(STATELESS, `RequestAttributeSecurityContextRepository`). 내부 API(`/api/v1/internal/**`)는 지금처럼 `X-Internal-Api-Key`로만 인증한다. 필터와 테스트 로그인(`support.GatewayLogin`)은 앱 안에 둔다. 프론트는 문의 API에 `X-XSRF-TOKEN`을 더는 보내지 않아도 된다. actuator 관리 포트 분리는 #528에서 한다.
- **알림용 고객센터 이벤트(2026-11-12, #875, 알림 계약 M7)**: `customersupport.events.v1`에 key `inquiry:<문의 ID>`로 두 이벤트를 더 낸다. 발행은 판단·철회 요청과 같이 Modulith 발행 기록과 `@Externalized`이고, 재발행 배치도 그대로 다룬다.
  - `InquiryAnsweredEvent`는 관리자가 직접 다는 답변(`InquiryUseCase.reply`)에서만 낸다. 이의 제기 판단 안내 답변(`Inquiry.applyResolved`)에서는 내지 않는다. 판단 알림은 알림 모듈이 강의의 `LectureObjectionResolvedEvent`로 보낸다. 고객센터는 자기 사실만 이벤트로 내고, 알릴지는 알림 모듈이 정한다. 답변 ID가 INSERT 때 생기므로 이벤트를 만들기 전에 flush한다.
  - 계약의 `InquiryReplyEditedEvent`(답변 수정)는 내지 않는다. 답변 수정은 대부분 오타 고치기라 알릴 일이 아니고, 내용을 바로잡을 때는 새 답변을 달면 답변 도착 알림이 간다. 그래서 수정 회차 칸과 Flyway도 없다(사용자 결정, 이슈 원문과 다름).
  - `LectureObjectionFileRejectedEvent`는 재시도 배치가 보낸 접수를 강의가 거절해 문의가 접수 처리 중에서 접수 불가로 바뀔 때만 낸다(문의마다 한 번). 요청 안에서 바로 거절된 접수는 화면에 바로 보이므로 내지 않는다(알림 ND-015).
