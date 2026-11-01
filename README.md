# 합방 백엔드

인플루언서 출연 요청과 강의 거래를 제공하는 HP 기반 플랫폼의 백엔드다.

## 프로젝트 구조

- `apps/*-app`: 회원·지갑/정산·출연·강의·채팅·고객센터의 Spring Boot 앱. 각각 실행 진입점, 설정, Dockerfile을 가진다.
- `common/{kafka,redis,shared,test-support}`: 앱이 필요한 것만 사용하는 공통 Gradle 라이브러리.
- 각 앱의 `com.hapbang.<업무>.global`: 해당 앱의 보안·응답·예외 처리 등. 공통 global Gradle 모듈은 없다.

```bash
./gradlew verifyModuleStructure :member-app:check :member-app:bootJar
docker build -f apps/member-app/Dockerfile apps/member-app/build/libs
```

현재 변경은 구조·빌드·실행 기반까지다. 기존 Spring Event, 프로세스 내부 포트와 세션 인증의 서비스 간 전환은 남아 있다(인증은 JWT로 전환, #370). 독립 서버에서 거래가 모두 동작하는 상태로 간주하지 않는다. CI는 앱별 테스트·jar·이미지 빌드를 검증한다. 서버는 EC2 k3s prod 한 벌이고 dev 서버는 없다. 기존 Docker Compose는 2026-10-30에 지웠다(#440). main push 때 바뀐 앱 이미지를 발행하고, prod 배포는 수동 dispatch로 바뀐 앱만 한다(#374, [`docs/deploy/README.md`](docs/deploy/README.md)).

## 문서

- [배포·운영](docs/deploy/README.md): 환경 주소, 배포 흐름, 환경변수 추가, 운영·보안 가이드
- [최종 프로젝트 인프라 설계](docs/architecture/final-project-infrastructure.md): k3s, Envoy Gateway·JWT, 앱별 DB, Kafka·Redis, Elasticsearch·관측성 목표 구조와 구현 순서
- [Elasticsearch 통합 범위 조사](docs/architecture/elasticsearch-consolidation-research.md): Elasticsearch 검색·Vector DB 선택과 Elastic Observability 제외 근거
- [앱 구조와 전환 범위](docs/architecture/app-modules.md)
- [로컬 실행](docs/dev/local.md)
- [빌드와 테스트](docs/dev/testing.md)
- [모듈 연동](docs/dev/module-integration.md)
