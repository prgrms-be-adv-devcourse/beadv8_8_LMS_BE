# 로컬·dev 확인

## 로그인과 API 호출

- 로컬 로그인: `POST /api/v1/members/local-login?role=VERIFIED_INFLUENCER&number=1` (역할별 계정 2개).
- POST는 CSRF 검사를 받으므로 먼저 GET으로 `XSRF-TOKEN` 쿠키를 받고 `X-XSRF-TOKEN` 헤더로 보낸다 (Swagger는 자동).

## dev 서버

- dev 서버도 local-login이 켜져 있다 (#332, `application-dev.yaml`. prod는 꺼짐). 주소는 [배포 문서](../deploy/README.md).
- 앱 시작 시 역할별 테스트 계정이 2개씩(`test-<user|influencer|verified|admin>[-2]@hapbang.local`) 만들어진다. 공개 주소라 누구나 어느 역할로든 로그인할 수 있으니 dev 데이터는 팀 공용으로 다룬다.
- 배포 뒤 확인: Deploy 워크플로 성공(헬스 체크 포함), 비로그인 401, `/v3/api-docs`에 바뀐 명세가 반영됐는지.
- 밖에서 `/actuator/health`를 부르면 프론트 `index.html`(200)이 와서 확인이 안 된다.
- S3는 로컬 S3Mock이 서명·만료·권한을 검사하지 않으므로 dev 서버에서 꼭 확인한다 ([S3 문서](../deploy/s3.md)).

## 프레임워크 동작 확인

- 프레임워크 동작은 `~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<버전>/*/*-sources.jar`를 임시 폴더에 `unzip`해서 1차 자료로 확인한다 (Boot 4.1.1·Framework 7.0.9·Modulith 2.1.1).

## 라이브러리 추가

- Apache HttpClient 5가 클래스패스에 들어오면 Spring `RestClient`/`RestTestClient`가 자동으로 그걸 쓰고, 쿠키 저장소 때문에 세션이 재사용된다 (채팅 E2E 실패).
- 라이브러리를 추가하면 `./gradlew :<모듈>:dependencyInsight --configuration runtimeClasspath --dependency httpclient5`로 확인한다. AWS SDK는 `url-connection-client`만 쓴다.
