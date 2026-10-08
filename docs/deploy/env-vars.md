# 환경변수 가이드

환경변수를 추가·변경·삭제할 때의 절차다. **값(특히 비밀값)은 레포, PR, 이슈, 채팅, 노션 어디에도 쓰지 않는다.** 레포에는 키 이름과 예시 값만 둔다.

## 먼저 분류한다

| 질문 | 예 | 아니오 |
| --- | --- | --- |
| 유출되면 문제인가? (비밀번호, API 키, OAuth client secret, 결제 키) | 비밀값 → 서버 env 파일에만 | 설정값 → yaml에 직접 써도 된다 |
| prod·dev 값이 다른가? | 서버 env 파일로 환경별 주입 | `application.yaml`(공통)에 둔다 |
| FE에서 쓰는가? | 아래 [FE 환경변수](#fe-환경변수) 참고 | — |

비밀값이 아니고 환경별로 같다면 환경변수로 만들 필요 없이 `application.yaml`에 쓰면 된다.

## 값이 흐르는 경로 (BE)

```
서버 /opt/hapbang/env/<env>.env      KAKAO_CLIENT_SECRET=...      ← 실제 값 (서버에만)
        │ --env-file (compose 치환용)
        ▼
docker/compose.<env>.yaml  app.environment:
        KAKAO_CLIENT_SECRET: ${KAKAO_CLIENT_SECRET:?...}           ← 컨테이너로 넘길 키를 명시
        │
        ▼
apps/src/main/resources/application-<env>.yaml
        ...client-secret: ${KAKAO_CLIENT_SECRET}                   ← Spring이 읽는다
```

> ⚠️ **env 파일에 추가하는 것만으로는 컨테이너에 전달되지 않는다.** env 파일은 compose 파일의 `${...}`를 치환하는 데만 쓰인다. 컨테이너로 넘기려면 `compose.<env>.yaml`의 `app.environment`에 키를 적어야 한다.

## BE 환경변수 추가 절차

예: 카카오 OAuth client secret `KAKAO_CLIENT_SECRET`을 추가한다.

### 1. 코드 (PR에 포함)

**`apps/src/main/resources/application-prod.yaml`, `application-dev.yaml`**

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          kakao:
            client-secret: ${KAKAO_CLIENT_SECRET}   # 기본값을 두지 않는다 → 없으면 기동 실패
```

**`application-local.yaml`** (로컬 개발): 비밀값이면 기본값을 두지 말고 IDE 실행 설정이나 셸의 환경변수로 넣는다. 비밀이 아닌 로컬용 값이면 yaml에 직접 써도 된다.

**`docker/compose.prod.yaml`, `docker/compose.dev.yaml`** — `app.environment`에 추가

```yaml
  app:
    environment:
      KAKAO_CLIENT_SECRET: ${KAKAO_CLIENT_SECRET:?KAKAO_CLIENT_SECRET가 필요하다}
```

`:?`는 서버 env 파일에 값이 없으면 compose가 바로 실패하게 한다. 빈 값으로 조용히 뜨는 것보다 낫다.

**`docker/.env.example`** — 키와 설명, **예시 값**만

```
# 카카오 OAuth client secret (카카오 개발자 콘솔 > 앱 > 보안)
KAKAO_CLIENT_SECRET=example
```

**테스트가 이 값을 필요로 하면**: 테스트용 프로필/설정에 가짜 값을 둔다. 실제 비밀값이 필요한 테스트는 만들지 않는다(필요하면 GitHub Secrets → ci.yml `env`, 로그에 출력 금지).

### 2. 서버 env 파일에 값 추가 (병합 전에)

서버에서 `ubuntu` 계정으로 실행한다. 값이 화면과 셸 기록에 남지 않도록 입력받는다.

```bash
# dev
sudo -u deploy sh -c 'printf "값 입력: " >&2; read -rs v; echo >&2; printf "KAKAO_CLIENT_SECRET=%s\n" "$v" >> /opt/hapbang/env/dev.env'
# prod
sudo -u deploy sh -c 'printf "값 입력: " >&2; read -rs v; echo >&2; printf "KAKAO_CLIENT_SECRET=%s\n" "$v" >> /opt/hapbang/env/prod.env'

# 확인 (값은 출력하지 않는다)
sudo -u deploy grep -c '^KAKAO_CLIENT_SECRET=' /opt/hapbang/env/dev.env /opt/hapbang/env/prod.env   # 각 1
sudo ls -l /opt/hapbang/env    # -rw------- deploy deploy 유지
```

값에 공백·`#`·`$`가 있으면 큰따옴표로 감싸야 한다. 그런 값은 `sudo -u deploy vi /opt/hapbang/env/dev.env`로 직접 편집한다.

### 3. compose 파일을 서버에 반영 (병합 전에)

[operations.md#설정-반영](operations.md#설정-반영) 절차로 `compose.prod.yaml`, `compose.dev.yaml`을 올리고 검증한다. 검증(`config --quiet`)은 env 파일에 값이 없으면 실패하므로 2번이 먼저다.

### 4. PR 병합 → 자동 배포

dev 병합 시 dev, main 병합 시 prod에 새 app이 뜬다. 순서가 바뀌면:

| 상황 | 결과 |
| --- | --- |
| 서버 env에 값 없이 병합 | compose가 시작 전에 실패 → 배포 실패, **기존 컨테이너는 그대로** 동작 |
| compose 반영 없이 병합 | 컨테이너에 값이 전달되지 않아 Spring 기동 실패 → **자동 롤백**. Actions에 "서버 설정이 레포와 다릅니다" 경고 |

### 5. 확인

```bash
cd /opt/hapbang
# 키가 컨테이너에 들어갔는지 (값 길이만 출력)
sudo -u deploy docker compose -f compose.dev.yaml --env-file env/dev.env exec app sh -c 'printf %s "$KAKAO_CLIENT_SECRET" | wc -c'
```

## 값 변경 (비밀값 교체 포함)

1. 서버 env 파일의 해당 줄을 수정한다(`sudo -u deploy vi /opt/hapbang/env/<env>.env`).
2. Actions에서 해당 환경의 **최근 BE Deploy run → `deploy` job 재실행**. 환경변수가 바뀌었으므로 app이 새로 만들어진다.

> ❌ 서버에서 `docker compose up`을 직접 실행하지 않는다. 이미지 태그가 지정되지 않아 예전 이미지로 바뀔 수 있다([operations.md#하지-말-것](operations.md#하지-말-것)).

**DB 비밀번호(`POSTGRES_PASSWORD`)는 예외다.** PostgreSQL은 처음 초기화할 때만 이 값을 쓰므로 env 파일만 바꾸면 app이 접속하지 못한다. [security.md#db-비밀번호-교체](security.md#db-비밀번호-교체)를 따른다.

## 삭제

코드에서 사용을 먼저 없애고 배포한 뒤 → `compose.*.yaml`의 키 삭제(설정 반영) → 서버 env 파일에서 줄 삭제 순서로 한다. 반대로 하면 `:?` 때문에 배포가 실패한다.

## FE 환경변수

Vite의 `VITE_*` 변수는 **빌드할 때 JS 번들에 그대로 박힌다.** 브라우저에서 누구나 볼 수 있다.

- **비밀값을 절대 넣지 않는다.** API 키 등은 BE를 통해 사용한다.
- **prod·dev에 같은 이미지를 쓴다.** `main`·`dev`가 각자 빌드하지만, 환경별로 다른 값을 넣는 구조가 아니다. API 주소는 환경변수 대신 상대 경로(`/api/v1/...`)를 쓴다.
- 환경별로 꼭 달라야 하는 공개 값이 생기면 FE 레포의 `deploy.yml` 빌드 단계에 build-arg를 추가해야 한다. 팀과 먼저 상의한다.

## edge(도메인) 변수

`/opt/hapbang/env/edge.env`의 `PROD_HOST`, `DEV_HOST`. 바꾸면 인증서를 다시 발급해야 한다([server-setup.md#4-https-인증서](server-setup.md#4-https-인증서)).

## 체크리스트

- [ ] 비밀값인지, 환경별로 다른지 분류했다
- [ ] `application-<env>.yaml`에 `${KEY}` (기본값 없음)
- [ ] `compose.prod.yaml`·`compose.dev.yaml` `app.environment`에 `KEY: ${KEY:?...}`
- [ ] `docker/.env.example`에 키·설명·예시 값
- [ ] 서버 `prod.env`·`dev.env`에 실제 값 (병합 전)
- [ ] compose 파일 서버 반영·검증 (병합 전)
- [ ] 병합 후 배포 성공, 컨테이너에 값 전달 확인
- [ ] 값이 PR·커밋·로그·채팅에 남지 않았다
