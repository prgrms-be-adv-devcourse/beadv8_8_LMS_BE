# 챗봇(chatbot) API — FE 계약

> - 대상: auxiliary-app `chatbot` 모듈의 외부 API 중 챗봇 화면·추천 위젯이 쓰는 것(#715 FAQ, #718 강의 추천, #719 인플루언서 추천, #720 대화 흐름).
> - 원천: 코드와 Swagger(`/api/v1/auxiliary/swagger-ui.html`). 동작 설명은 [`apps/auxiliary-app/README.md`](../../apps/auxiliary-app/README.md) "챗봇" 절에 있다.
> - 공통 규칙: 성공 응답 `RsData`, 오류 `ProblemDetail`(+ `code`)은 [API 규칙](conventions.md)을 따른다.

## 1. 공통

| 항목 | 내용 |
| --- | --- |
| 인증 | 로그인 회원 전체(USER 이상). 비로그인이면 401이고 FE는 챗봇 대신 로그인 안내를 띄운다 |
| 호출 제한 | 회원별. 넘으면 429 + `Retry-After`. 위젯은 429면 영역을 숨긴다 |
| 대화 저장 | 서버는 대화를 저장하지 않는다. 자유 질문은 FE가 화면의 대화를 요청마다 `history`로 보낸다(새로고침·로그아웃 때 비움) |

## 2. 엔드포인트

| 화면 | 메서드·경로 | 본문 | LLM | 호출 제한(회원별) |
| --- | --- | --- | --- | --- |
| 강의 추천 위젯 | `GET /api/v1/chatbot/recommendations/lectures?interest=` | | 없음 | 분당 60회, interest가 있으면 분당 10회도 함께 |
| 인플루언서 추천 위젯 | `GET /api/v1/chatbot/recommendations/influencers` | | 없음 | 분당 60회 |
| 챗봇 "FAQ 모아보기" | `GET /api/v1/chatbot/faqs?category=` | | 없음 | 없음 |
| 챗봇 "강의 추천받기" | `POST /api/v1/chatbot/recommendations/lectures/explained` | `{interest?}` (없어도 됨) | 쿼리 확장 + 리랭킹·이유 | 분당 10회 |
| 챗봇 "인플루언서 추천받기" | `POST /api/v1/chatbot/recommendations/influencers/explained` | `{interest?}` (#720에서 추가, 없어도 됨) | 쿼리 확장 + 리랭킹·이유 | 분당 10회 |
| 챗봇 "자유 질문하기" | `POST /api/v1/chatbot/messages` | `{message, history}` | Tool Calling | FAQ 검색·대화와 합쳐 분당 10회 |
| 〃 (스트림) | `POST /api/v1/chatbot/messages/stream` (SSE) | `{message, history}` | Tool Calling | 〃 |

`interest`는 앞뒤 공백을 뺀 1~100자다. 어기면 400 `INVALID_REQUEST`.

## 3. 추천 응답(위젯·버튼 공통)

| 필드 | 뜻 | FE |
| --- | --- | --- |
| `items` | 추천(순서대로 보여 준다). 강의는 `lectureId·title·score·reason·reasonText`, 인플루언서는 `memberId·categoryNames·completedAppearances·averageRating·reasons·explanation?` | 카드 |
| `needsInterest` | 맞춤으로 고를 재료가 없다. 관심사를 입력받아 다시 부르면 맞춤으로 찾는다 | 관심사 입력을 연다 |
| `basis` | 후보의 근거. `PERSONALIZED`(맞춤 후보가 있음), `POPULAR`(맞춤 재료가 없는 인기순, 다시 시도해도 같음), `POPULAR_TEMPORARY`(검색이 잠시 실패해 인기순으로 대신 답함) | `POPULAR_TEMPORARY`일 때만 "지금은 맞춤 추천을 준비하지 못해 인기순으로 보여 줘요, 잠시 뒤 다시 시도" 안내와 다시 시도 버튼(문구는 FE) |
| `fallback` | 버튼 경로에서 LLM 이유를 받지 못해 정해진 문장·코드 순서로 답했다. 위젯은 늘 `false` | 표시 여부는 FE가 정한다 |
| `notice` | 추천하지 못한 까닭(요청 회원 정보를 아직 받지 못함 등). 추천했으면 없다 | 빈 목록 대신 이 문구 |

- 강의 응답의 `personalized`는 #720에서 지웠다(`basis`로 대신).
- 강의 위젯의 관심사 요청이 검색 장애로 503 `LECTURE_RECOMMEND_UNAVAILABLE`을 주던 것(#718)은 없앴다. 이제 200 + 인기순 + `basis=POPULAR_TEMPORARY`다.

## 4. 자유 질문

요청 `history`: `[{role: "USER"|"ASSISTANT", content}]`, 오래된 것부터.

| 규칙 | 어기면 |
| --- | --- |
| 최근 12개만 쓴다 | 오류 아님(앞에서 버림) |
| `role`은 `USER`·`ASSISTANT`만 | 400 `INVALID_REQUEST` |
| `content`는 USER 1~500자, ASSISTANT 1~2,000자. 빈 내용 불가 | 400 `INVALID_CHAT_HISTORY` |
| 쓰는(최근 12개) 대화의 글자 수 합 8,000자까지 | 400 `INVALID_CHAT_HISTORY` |
| 50개까지 보낼 수 있다 | 400 `INVALID_CHAT_HISTORY` |
| Tool 결과·추천 카드는 보내지 않는다 | 받는 필드가 없다 |

응답(`RsData<ChatAnswerResDto>`): `answer`(설명 글), `sources`(근거 FAQ), `cards`(`{lectures?: {basis, items}, influencers?: {basis, items}}`, 이번 요청에서 추천 Tool을 부른 종류만), `fallback`.

- **카드는 `cards`만 그린다.** `answer`는 설명이다. 스트림 글에서는 후보 ID를 검사할 수 없기 때문이다.
- 강의 카드는 위젯 응답과 같은 `items` 모양이다. 다만 `reasonText`에 기준 강의 제목을 넣지 않는다("구매하신 강의와 비슷해요.").
- `history` 규칙 위반(400)은 호출 제한 횟수를 쓰지 않는다.
- LLM이 실패하면 `fallback=true`. 카드를 이미 만들었으면 카드 + "추천 결과를 아래에 보여 드려요.", 아니면 FAQ 상위 1건 또는 안내 문구.

스트림(SSE) 이벤트:

| 이벤트 | data | 언제 |
| --- | --- | --- |
| `token` | `{"text": "..."}` | 답 조각, 0번 이상 |
| `cards` | `{"lectures": {...}, "influencers": {...}}` | 추천 Tool을 불렀을 때, 글 뒤 한 번 |
| `done` | `{"sources": [...], "fallback": false}` | 정상 끝, 마지막 한 번 |
| `error` | `{"message": "..."}` | 조각을 보낸 뒤 실패. 카드가 있으면 `cards` 다음에 온다 |

첫 조각 전에 실패하면 정해진 답을 `token` 한 번 + (`cards`) + `done(fallback=true)`로 보낸다. 호출 제한·`history` 오류는 스트림을 열지 않고 429·400(ProblemDetail)이다.
