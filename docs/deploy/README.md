# 배포 개요

합방은 EC2 1대에 **운영(prod)**과 **개발 서버(dev)**를 함께 올린다. FE·BE 레포가 각자 이미지를 만들고, 서버 구성(compose·nginx·배포 스크립트)은 BE 레포 `docker/`에 있다.

| 환경 | 주소 | 브랜치 | Swagger |
| --- | --- | --- | --- |
| prod | https://hapbang.duckdns.org | `main` | 비활성 |
| dev | https://dev.hapbang.duckdns.org | `dev` | https://dev.hapbang.duckdns.org/swagger-ui.html |

## 문서

| 문서 | 내용 |
| --- | --- |
| [env-vars.md](env-vars.md) | 환경변수 추가·변경·삭제 절차 |
| [operations.md](operations.md) | 상태 확인, 로그, 배포 실패 대응, 롤백, 설정 반영, DB 백업, 하지 말 것 |
| [server-setup.md](server-setup.md) | 서버 최초 설정·재구축, 배포 키, 인증서 발급 |
| [security.md](security.md) | 자격 증명 관리, 공개 레포 원칙, 유출 시 대응 |

## 구조

```
[인터넷] :443 ─ edge nginx ─┬─ prod 도메인 ─┬─ /api, /swagger-ui, /v3/api-docs → prod app(Spring)
           (:80은 301)     │               └─ 그 외 → prod web(React)
                           └─ dev 도메인  ─┬─ /api, /swagger-ui, /v3/api-docs → dev app
                                           └─ 그 외 → dev web
       prod·dev는 각자 PostgreSQL을 가진다. app·web·DB는 외부 포트를 열지 않는다.
```

- **edge nginx**: 유일한 진입점. HTTPS(Let's Encrypt, 자동 갱신), 도메인으로 prod/dev, 경로로 app/web을 나눈다.
- **web**: React 정적 파일만 서빙한다. 백엔드 주소를 모르므로 **API는 상대 경로(`/api/v1/...`)로 호출**한다.
- **app**: Spring Boot. `prod`·`dev` 프로필로 실행되고 DB 접속 정보 등은 서버의 env 파일에서 주입된다.
- **시간대**: 모든 컨테이너와 DB는 `Asia/Seoul`.

## 배포 흐름

```
PR → dev/main 병합 → GitHub Actions
  ci      테스트(BE: ./gradlew check, FE: lint·build)
  build   이미지 빌드 → GHCR(private) push   :dev|:latest, :sha-<커밋>
  deploy  EC2에 SSH → deploy.sh "<env> <app|web> <커밋>"
            → 해당 커밋 이미지 pull → 해당 컨테이너만 교체 → healthy 확인
```

- **각 레포는 자기 컨테이너만 바꾼다.** BE 배포는 해당 환경의 app(필요 시 postgres), FE 배포는 web만 교체한다.
- **준비 확인**: 새 컨테이너가 healthy(web: HTTP 응답, app: 8080 포트)가 될 때까지 최대 180초 기다린다.
- **자동 롤백**: healthy가 되지 않으면 로그를 남기고 교체 전 이미지로 되돌린 뒤 배포를 실패로 표시한다.
- **수동 롤백**: Actions에서 되돌릴 버전의 Deploy run을 열고 `deploy` job만 다시 실행한다.
- **배포 가능 시간**: EC2는 09:00~18:00에 자동으로 켜져 있다. 그 외 시간에 병합하면 deploy job이 SSH 타임아웃으로 실패하므로, 서버가 켜진 뒤 다시 실행한다.

## 자주 하는 작업

| 하고 싶은 것 | 방법 |
| --- | --- |
| dev에 반영 | 기능 브랜치 → `dev` PR 병합 |
| 운영 반영 | `dev` → `main` PR 병합 |
| 롤백 | 되돌릴 버전의 Deploy run → `deploy` job 재실행 |
| 환경변수 추가 | [env-vars.md](env-vars.md) |
| 배포 실패 확인 | [operations.md#배포-실패-대응](operations.md#배포-실패-대응) |
| compose·nginx 수정 | PR 병합 + 서버에 직접 반영 → [operations.md#설정-반영](operations.md#설정-반영) |

## 미정 사항 (팀 결정 필요)

| 항목 | 현재 상태 |
| --- | --- |
| DB 스키마 관리 | Flyway·Liquibase·`ddl-auto` 설정 없음 → **테이블이 자동으로 만들어지지 않는다** |
| DB 백업 | 자동 백업 없음. 수동 절차만 있음([operations.md](operations.md#db-백업복원)) |
| dev 접근 제한 | dev(Swagger 포함)가 인터넷에 공개되어 있음 |
| 헬스 체크 | 포트 확인 방식. Actuator readiness로 교체 가능 |
| prod 배포 승인 | `main` 병합 즉시 배포. GitHub Environment 승인 규칙 미적용 |
