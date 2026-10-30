# 로컬 실행

전체 구조와 미완료 연동은 [앱 구조](../architecture/app-modules.md)를 먼저 확인한다. 실행 JAR를 만들 수 있다는 것과 서비스 간 기능이 동작한다는 것은 별개다.

## 앱별 명령

프로젝트 루트에서 실행한다. Gradle 프로젝트는 `:member-app`, `:billing-app`, `:appearance-app`, `:lecture-app`, `:chat-app`, `:customer-support-app`이다.

```bash
./gradlew :member-app:bootJar
./gradlew :member-app:bootRun
```

설정은 각 앱의 `src/main/resources/application*.yaml`에 둔다. 여러 앱을 한 머신에서 실행하면 `SERVER_PORT`를 서로 다르게 지정한다. DB와 내부 API 목적지·비밀값은 해당 앱의 실행 환경에 주입한다. `common` 모듈에 운영 주소나 인증 정보를 하드코딩하지 않는다.

`BillingPort`가 필요한 출연 앱과 강의 이의 제기 연동이 끊긴 고객센터 앱(#433, #278)은 통신 어댑터 전환 전까지 실제 실행에 제약이 있다. 운영 소스에 Fake 빈을 넣어 기동만 성공시키지 않는다. 회원 가입 이벤트가 다른 앱으로 자동 전달되거나 세션이 모든 서버에서 호환된다고 가정하지 않는다.

## 로컬 인프라와 로그인

기존 `docker/compose.yaml`의 PostgreSQL·S3Mock은 로컬 개발 보조 설정으로 보존한다. 앱별 설정에서 사용하는 DB·마이그레이션 이력은 [앱 구조](../architecture/app-modules.md)의 전환 제약을 따른다. 기존 운영 DB에 새 구성을 바로 적용하지 않는다.

- 회원 앱의 로컬 로그인은 `POST /api/v1/members/local-login?role=VERIFIED_INFLUENCER&number=1`이다.
- 세션 인증의 POST는 CSRF 검사를 받는다. GET으로 받은 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보낸다.
- S3Mock은 서명·만료·권한을 검사하지 않는다. 실제 S3 연동 확인은 [S3 문서](../deploy/s3.md)를 따른다.

## 기존 dev 환경

기존 dev 서버의 주소와 운영 절차는 [배포 문서](../deploy/README.md)에 남아 있다. 워크플로는 dev 버전이며 기존 단일 앱 배포를 전제한다. 앱별 이미지 빌드와 배포는 별도 전환이 필요하다.

## 프레임워크와 라이브러리

- 동작 확인에는 `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<버전>/*/*-sources.jar`의 해당 버전 소스를 우선 사용한다.
- Apache HttpClient 5가 클래스패스에 들어오면 Spring의 HTTP 클라이언트 선택이 달라져 쿠키 저장소가 테스트에 영향을 줄 수 있다.
- 라이브러리를 추가하면 `./gradlew :<앱>:dependencyInsight --configuration runtimeClasspath --dependency httpclient5`로 확인한다. AWS SDK는 `url-connection-client`를 사용한다.
