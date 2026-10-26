# 배포 개요

합방은 EC2 1대에 **운영(prod)**과 **개발 서버(dev)**를 함께 올린다. FE·BE 레포가 각자 이미지를 만들고, 서버 구성(compose·nginx·배포 스크립트)은 BE 레포 `docker/`에 있다.

| 환경 | 주소 | 브랜치 | Swagger |
| --- | --- | --- | --- |
| prod | https://hapbang.duckdns.org | `main` (수동 배포) | 비활성 |
| dev | https://dev.hapbang.duckdns.org | `dev` | https://dev.hapbang.duckdns.org/swagger-ui.html |

## 문서

| 문서 | 내용 |
| --- | --- |
| [env-vars.md](env-vars.md) | 환경변수 추가·변경·삭제 절차 |
| [operations.md](operations.md) | 상태 확인, 로그, 배포 실패 대응, 롤백, 설정 반영, DB 백업, 하지 말 것 |
| [server-setup.md](server-setup.md) | 서버 최초 설정·재구축, 배포 키, 인증서 발급 |
| [security.md](security.md) | 자격 증명 관리, 공개 레포 원칙, 유출 시 대응 |
| [s3.md](s3.md) | 파일 업로드(S3) 구조, 버킷·IAM 역할 설정, 확인 절차, 로컬 S3Mock |
| [infra/terraform](../../infra/terraform/README.md) | AWS 리소스(EC2·네트워크·S3·IAM) 코드, 다른 계정으로 옮기기 |

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
PR → GitHub Actions(CI)
  verify  테스트·jar 빌드 → 그 jar로 이미지 빌드 확인(push 안 함)

dev/main 병합 → GitHub Actions(Deploy)
  ci      PR과 같은 verify(BE: ./gradlew check :apps:bootJar, FE: lint·build) → jar를 artifact로 넘김
  build   테스트한 jar를 이미지에 담아 GHCR(private) push   :dev|:latest, :sha-<커밋>
          (BE 이미지는 소스를 다시 빌드하지 않는다. 테스트한 jar = 배포한 jar)
  deploy  BE: dev만(main은 이미지까지만). FE: dev·main 모두

(BE) 운영 배포: Actions → Deploy prod → Run workflow(main) + 배포할 main 커밋 sha
  resolve sha를 40자로 풀기 → main에 포함된 커밋인지, :sha-<커밋> 이미지가 GHCR에 있는지 확인
  deploy  (아래와 같음, 서버 설정은 main 최신 파일과 비교)

deploy  EC2에 SSH → (BE) deploy.sh "<env> check" → 서버 compose.<env>.yaml이 레포와 다르면 배포하지 않고 실패
        → deploy.sh "<env> <app|web> <커밋>"
          → 해당 커밋 이미지 pull → 해당 컨테이너만 교체 → healthy 확인
```

- **BE 운영 배포는 `main` 병합과 분리되어 있다.** `main` 병합은 이미지까지만 만들고, 운영에 올리는 시점은 사람이 정해 Deploy prod를 실행한다(#111). **FE는 아직 `main` 병합 시 운영에 자동 배포된다.**
- **각 레포는 자기 컨테이너만 바꾼다.** BE 배포는 해당 환경의 app(필요 시 postgres), FE 배포는 web만 교체한다.
- **준비 확인**: 새 컨테이너가 healthy(web: HTTP 응답, app: `GET /actuator/health`가 200 UP. DB 연결 포함)가 될 때까지 최대 180초 기다린다.
- **자동 롤백**: healthy가 되지 않으면 로그를 남기고 교체 전 이미지로 되돌린 뒤 배포를 실패로 표시한다.
- **수동 롤백**: BE 운영은 되돌릴 커밋의 sha로 Deploy prod를 실행한다. dev와 FE는 되돌릴 버전의 Deploy run을 열고 `deploy` job만 다시 실행한다.
- **배포 가능 시간**: EC2는 09:00~18:00에 자동으로 켜져 있다. 그 외 시간에 dev에 병합하면 deploy job이 SSH 타임아웃으로 실패하므로, 서버가 켜진 뒤 다시 실행한다. BE 운영 배포는 서버가 켜져 있을 때 실행한다.

## 자주 하는 작업

| 하고 싶은 것 | 방법 |
| --- | --- |
| dev에 반영 | 기능 브랜치 → `dev` PR 병합 |
| 운영 반영 | [operations.md#운영-배포](operations.md#운영-배포)를 순서대로 따른다. `dev` → `main` PR 병합 → (BE) 서버 설정 확인 → Actions → Deploy prod에 main 커밋 sha를 넣고 실행 |
| 롤백 | BE 운영: 되돌릴 커밋 sha로 Deploy prod 실행. dev·FE: 되돌릴 버전의 Deploy run → `deploy` job 재실행 |
| 환경변수 추가 | [env-vars.md](env-vars.md) |
| DB 스키마 변경 | Flyway 마이그레이션 SQL 추가, 앱 시작 시 자동 적용 → [db-migration.md](../db-migration.md) |
| 배포 실패 확인 | [operations.md#배포-실패-대응](operations.md#배포-실패-대응) |
| compose·nginx 수정 | PR 병합 + 서버에 직접 반영 → [operations.md#설정-반영](operations.md#설정-반영) |

## 미정 사항 (팀 결정 필요)

| 항목 | 현재 상태 |
| --- | --- |
| DB 백업 | 자동 백업 없음. 수동 절차만 있음([operations.md](operations.md#db-백업복원)) |
| dev 접근 제한 | dev(Swagger 포함)가 인터넷에 공개되어 있음 |
| prod 배포 승인 | BE는 Deploy prod 수동 실행(실행하는 사람이 곧 승인). GitHub Environment 승인 규칙은 쓰지 않는다([security.md](security.md#알려진-위험)). FE는 `main` 병합 즉시 배포 |
