“합방” 백엔드 레포에서 작업하는 모든 AI 에이전트와 팀원이 공통으로 지키는 규칙이다.

- 용어 정리: 기획서 안의 “용어 정리” 데이터베이스

## 1. 프로젝트 개요

합방은 인플루언서 출연 요청을 중개하고, 인증된 인플루언서의 강의를 판매하는 포인트(HP) 기반 플랫폼이다.

- 코어: 출연 (출연 요청 1:1, 출연 모집 1:N)
- 서포팅: 강의 (장바구니 → 주문 → 구매 확정)
- 완성 기준은 돈이 한 바퀴 도는 것이다.
    - 충전 → 출연 거래 → 출연 확인 → 지급 → 환전
    - 충전 → 강의 구매 → 구매 확정 → 지급 → 환전

### MVP 범위 밖 (구현하지 않는다)

커뮤니티, 연관 인플루언서 추천 서비스, 기업 광고 연동, 멘토링, 맞합방 제공, 인기도, 인스타그램 등 SNS 인플루언서

## 2. 기술 스택

- Java 25 (Gradle Toolchain), Spring Boot 4.1.1, Gradle 멀티모듈
- DB: PostgreSQL 1개
- 인증: OAuth 소셜 로그인 + Session
- 결제: 충전 예치금 + HP 원장 + TossPayments 테스트 API
- 이벤트: Spring Event (1차). Kafka 도입 여부는 최종 프로젝트에서 판단한다.
- Front-End: React (별도 레포)
- 팀 합의 없이 새 인프라·미들웨어(Kafka, Redis 등)를 도입하지 않는다.

## 3. 모듈 구조와 경계

세미 프로젝트는 **멀티모듈 모놀리스**다. 모듈은 바운디드 컨텍스트 단위이며, 각 모듈을 그대로 서비스로 떼어낼 수 있어야 한다.

| 모듈 | 책임 | API 진입 경로 |
| --- | --- | --- |
| 회원 | OAuth 로그인, 역할, 채널 인증, 승급 | `/api/v1/members` |
| 출연 | 출연 요청·모집, 수락·거절·취소, 출연 취소 요청, 확인, 이의 제기, 파일(S3) | `/api/v1/appearances` |
| 강의 | 강의, 장바구니, 주문, 리뷰, 파일(S3) | `/api/v1/lectures` |
| 지갑 + 정산 | HP 충전·환불, 원장, 보류, 지급, 환전 | `/api/v1/wallets`, `/api/v1/settlements` |
| 채팅 | 1:1 대화, 알림, 차단 | `/api/v1/chatRooms` |
| 고객센터 | 공지, FAQ, 문의, 이의 제기 접수 | `/api/v1/inquiries` |

### 경계 규칙 (반드시 지킨다)

- 회원 도메인은 반드시 별도 모듈로 분리한다.
- 다른 모듈은 **회원 ID(Long)로만 참조**한다. 다른 모듈의 엔티티를 `@ManyToOne` 등 JPA 연관관계로 걸지 않는다.
- 모듈 간 **테이블 JOIN 금지, 스키마 간 FK 금지**. 모듈은 ID와 Event로만 연결한다.
- 다른 모듈의 Repository·엔티티를 직접 사용하지 않는다.
- 컨텍스트 간에 필요한 유저 정보는 테이블을 복제하여 연결한다 (기획서 참고 사항).
- 모듈 간 통신은 Spring Event를 기본으로 한다.
- DB는 하나이며, 컨텍스트별로 스키마를 분리한다. 스키마 이름은 모듈명과 동일하게 한다.
- 이 규칙을 지키기 어려운 요구가 나오면 임의로 우회하지 말고 질문한다.

## 4. 용어 (유비쿼터스 언어)

모든 문서·코드·API에서 [`docs/GLOSSARY.md`](docs/GLOSSARY.md)의 용어만 쓴다. 비슷한 뜻의 다른 말로 바꾸지 않는다. 엔티티·테이블 이름은 기획서 ERD의 이름을 따른다.

## 5. 돈과 포인트 규칙 (가장 중요)

돈·HP 규칙은 [`docs/policy.md`](docs/policy.md)의 "돈과 포인트 규칙"에 있다. 이 영역은 정합성이 최우선이다. 돈·HP를 다루는 코드를 고치기 전에 반드시 먼저 읽는다.

## 6. 역할과 권한

역할은 Spring Security Role로 표현한다. 역할별 권한 표는 [`docs/policy.md`](docs/policy.md)의 "역할과 권한"에 있다.

## 7. API 규칙

- 모든 API는 `/api/v1/...` 아래에 둔다 (3장의 진입 경로 참고).
- API 명세는 Swagger로 관리한다. 프론트와 API 설계를 먼저 맞추고, 명세(Controller)를 먼저 만들어 동시에 작업할 수 있게 한다.
- 성공 응답은 `global`의 `RsData`(`statusCode`, `message`, `data`)로 감싼다. 오류는 `global`의 `GlobalExceptionHandler` 하나가 `ProblemDetail`(+ `code`)로 응답한다. **모듈은 `@RestControllerAdvice`를 만들지 않고**, `{Module}ErrorCode implements BaseErrorCode`와 `{Module}Exception extends BusinessException`만 둔다.
- 모듈 간 내부 HTTP API는 `/api/v1/internal/<모듈>/...`에 둔다. 회원 세션이 아니라 `X-Internal-Api-Key`로 인증하고, 서버 밖에서는 nginx가 404로 막는다 (D63, `docs/api/internal-earnings.md`).
- 파일(S3) 업로드는 서버 경유다. 본문 업로드 API는 모든 모듈이 `PUT /api/v1/{모듈}/files/{id}/content`(요청 본문이 파일, multipart 아님, `Content-Length` 필수)를 따르고, 받은 스트림을 `global/storage`의 `S3FileStorage.upload`로 넘긴다. nginx는 이 경로에만 큰 본문을 허용한다 (`docs/deploy/s3.md`, #106).
- RsData 반환 방법, 오류 코드·예외 작성법, 공통 핸들러가 처리하는 상황은 [`docs/api/conventions.md`](docs/api/conventions.md)에 있다.

## 8. 인프라와 배포

- AWS EC2 1대(t3.large까지), VPC 1개, S3 버킷 1개 범위에서만 운영한다. 범위 밖 서비스(RDS 등)는 추가하지 않는다. S3 용도는 사진 및 영상 업로드다.
- 서버 구성(Nginx·Spring·PostgreSQL을 EC2에 Docker 컨테이너로)과 배포 흐름(PR마다 테스트, dev 병합 시 자동 배포, 운영은 Deploy prod 워크플로를 수동 실행)은 [`docs/deploy/README.md`](docs/deploy/README.md)에 있다.

## 9. Git 워크플로우

- 새 작업은 GitHub Project 83에 이슈부터 만들고, `origin/dev`에서 `feature/tkt-<이슈번호>` 브랜치를 만들어 작업한 뒤 PR로 `dev`에 병합한다.
- PR을 올리기 전에 테스트를 통과시킨다 (PR마다 CI 테스트가 돈다).
- 다른 팀원의 변경을 반영할 때는 `dev`를 최신화한 뒤 작업 브랜치에 병합한다.
- PR 형식, 리뷰, 병합 뒤 이슈 닫기, 배포 확인은 [`docs/contributing/git-workflow.md`](docs/contributing/git-workflow.md), 커밋 메시지는 [`docs/contributing/commit.md`](docs/contributing/commit.md)를 따른다.

## 10. AI 에이전트 작업 원칙

- 작업 전에 기획서와 용어 정리를 먼저 확인한다.
- 기획서·용어 정리·요구사항이 서로 충돌하거나, 근거가 부족해 결정을 내리기 어려우면 **추측으로 구현하지 말고 먼저 질문한다.**
- 질문한 사실과 그 결과(결정 내용)는 기록으로 남긴다.
- 요청받은 범위만 최소한으로 변경한다. 요청과 무관한 코드·포맷은 건드리지 않는다.
- 위 3장의 모듈 경계, 5장의 돈 규칙을 어기는 변경은 하지 않는다. 불가피하면 이유와 함께 먼저 확인한다.
- 이 파일에 없는 사항은 기획서를 근거로 하고, 기획서에도 없으면 질문한다.

## 11. 미정 항목 (임의로 정하지 않는다)

아래는 팀 합의 전이다. 에이전트가 임의로 정해서 코드에 넣지 않고 질문한다.

- 코드 컨벤션 (네이밍, 패키지 구조 등. 오류 응답 형식은 [`docs/api/conventions.md`](docs/api/conventions.md)에서 정했다)
- 테스트 방식 (단위·통합 테스트 기준, 테스트용 DB)
- 지갑 잔액 변경의 동시성 제어 방식
- 지갑과 정산의 모듈·스키마 분리 방식

## 12. 작업별로 읽을 문서

아래 문서는 자동으로 읽히지 않는다. 해당 작업을 시작하기 전에 읽는다.

| 작업 | 문서 |
| --- | --- |
| 돈·HP를 다루는 코드, 권한 검사 | [`docs/policy.md`](docs/policy.md) |
| 이름·문서·이슈에 도메인 용어를 쓸 때 | [`docs/GLOSSARY.md`](docs/GLOSSARY.md) |
| 컨트롤러·요청 DTO·오류 코드 | [`docs/api/conventions.md`](docs/api/conventions.md) |
| 모듈 간 연동(shared 계약, 내부 API, 이벤트), 빈·배치 추가 | [`docs/dev/module-integration.md`](docs/dev/module-integration.md) |
| 엔티티·Repository·HQL | [`docs/dev/persistence.md`](docs/dev/persistence.md), [`docs/db-migration.md`](docs/db-migration.md) |
| 테스트 작성·실행 | [`docs/dev/testing.md`](docs/dev/testing.md) |
| 파일 업로드(S3) | [`docs/deploy/s3.md`](docs/deploy/s3.md) |
| 로컬·dev 서버 확인, 라이브러리 추가 | [`docs/dev/local.md`](docs/dev/local.md) |
| 모듈 작업 | `docs/<모듈>/module.md`(강의 [`docs/lecture/module.md`](docs/lecture/module.md), 출연 [`docs/appearance/module.md`](docs/appearance/module.md)), `boundedContext/<모듈>/AGENTS.md` |
| 이슈·브랜치·PR·병합·배포 확인 | [`docs/contributing/git-workflow.md`](docs/contributing/git-workflow.md) |
| 서버·배포·AWS | [`docs/deploy/README.md`](docs/deploy/README.md), [`docs/deploy/security.md`](docs/deploy/security.md) |

## Agent skills

### Issue tracker

GitHub 이슈 + Project 83(`LastMenStanding`) 보드에서 관리한다. 제목은 `[모듈] 요약`이다. See `docs/agents/issue-tracker.md`.

### Triage labels

기본 라벨 다섯 개(`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`)를 그대로 쓴다. See `docs/agents/triage-labels.md`.

### Domain docs

single-context: 용어는 `docs/GLOSSARY.md`, 정책은 `docs/policy.md`와 모듈 문서의 "정책 요약", 결정은 모듈 문서의 D번호(+ 생기면 `docs/adr/`). 용어집은 루트가 아니라 `docs/GLOSSARY.md`에 둔다. See `docs/agents/domain.md`.
