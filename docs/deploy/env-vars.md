# 환경변수 가이드

환경변수를 추가·변경·삭제할 때의 절차다. **값(특히 비밀값)은 레포, PR, 이슈, 채팅, 노션 어디에도 쓰지 않는다.** 레포에는 키 이름과 예시 값만 둔다. 운영은 k3s 한 벌(prod)이다([배포 개요](README.md)).

## 먼저 분류한다

| 질문 | 예 | 아니오 |
| --- | --- | --- |
| 유출되면 문제인가? (비밀번호, API 키, OAuth client secret, 결제 키) | 비밀값 → 앱 SealedSecret | 설정값 → 앱 ConfigMap 또는 yaml |
| 로컬과 prod 값이 다른가? | `application-prod.yaml`에 `${KEY}`로 두고 ConfigMap·Secret으로 주입 | `application.yaml`(공통)에 둔다 |
| FE에서 쓰는가? | 아래 [FE 환경변수](#fe-환경변수) 참고 | — |

비밀값이 아니고 환경별로 같다면 환경변수로 만들 필요 없이 `application.yaml`에 쓰면 된다.

## 값이 흐르는 경로 (BE)

```
~/.hapbang/prod-secrets.env (레포 밖)   KAKAO_CLIENT_SECRET=...   ← 실제 값
        │ k8s/tools/seal-app-secrets.sh (kubeseal)
        ▼
apps/<앱>/k8s/overlays/prod/<앱>-secrets.yaml   SealedSecret      ← 암호문만 커밋
        │ Sealed Secrets controller가 Secret <앱>-secrets로 푼다
        ▼
apps/<앱>/k8s/base/deployment.yaml   envFrom: <앱>-config(ConfigMap), <앱>-secrets(Secret)
        │
        ▼
apps/<앱>/src/main/resources/application-prod.yaml
        ...client-secret: ${KAKAO_CLIENT_SECRET}                  ← Spring이 읽는다
```

- 비밀이 아닌 값은 `apps/<앱>/k8s/base/kustomization.yaml`의 `configMapGenerator`(`<앱>-config`)에 적는다. 이름에 내용 hash가 붙어 값을 바꾸면 앱이 다시 배포된다.
- Secret은 앱마다 하나이고 그 앱만 읽는다. 키 목록과 봉인 방법은 [k8s.md#secret](k8s.md#secret)이 원본이다.

## BE 환경변수 추가 절차

예: 회원 앱에 카카오 OAuth client secret `KAKAO_CLIENT_SECRET`을 추가한다.

### 1. 코드 (PR에 포함)

**`apps/member-app/src/main/resources/application-prod.yaml`**

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

**비밀값이면**

- `k8s/tools/seal-app-secrets.sh`의 `spec()`에서 그 앱의 필수 또는 선택 키 목록에 더한다.
- [k8s.md#secret](k8s.md#secret)의 Secret 표에 키를 더한다.
- local overlay(`apps/<앱>/k8s/overlays/local/kustomization.yaml`)의 `secretGenerator`에 가짜 값을 더한다.

**비밀이 아니면**: `apps/<앱>/k8s/base/kustomization.yaml`의 `configMapGenerator` `literals`에 `KEY=값`을 더한다.

**테스트가 이 값을 필요로 하면**: 테스트용 프로필/설정에 가짜 값을 둔다. 실제 비밀값이 필요한 테스트는 만들지 않는다(필요하면 GitHub Secrets → ci.yml `env`, 로그에 출력 금지).

### 2. SealedSecret 다시 만들기 (비밀값일 때, PR에 포함)

`~/.hapbang/prod-secrets.env`에 `KEY=VALUE` 줄을 더하고 그 앱만 다시 봉인한다. 값은 화면에 출력되지 않는다.

```bash
k8s/tools/seal-app-secrets.sh member
git diff --stat apps/member-app/k8s/overlays/prod   # <앱>-secrets.yaml만 바뀌었는지
```

- `~/.hapbang`의 파일은 팀 비밀 보관소에도 둔다([k8s.md#secret](k8s.md#secret)).
- 봉인은 EC2 cluster의 controller 공개 키로 한다. 로컬 kubeconfig에 `hapbang-k3s` context가 있어야 한다([로컬 kubectl 접근](aws-k3s.md#로컬-kubectl-접근)).

### 3. PR 병합 → 배포

`main`에 병합하면 바뀐 앱 이미지가 발행되고, Deploy를 실행하면 그 앱의 overlay(SealedSecret 포함)가 적용된다([운영 앱 배포](k8s.md#운영-앱-배포)). 이미지가 바뀌지 않고 Secret만 바뀐 경우에도 `apps/<앱>/`가 바뀌었으므로 배포 대상이 된다.

### 4. 확인

```bash
# 키가 pod에 들어갔는지 (값 길이만 출력)
kubectl --context hapbang-k3s -n hapbang-prod exec deploy/member-app -- sh -c 'printf %s "$KAKAO_CLIENT_SECRET" | wc -c'
```

## 값 변경 (비밀값 교체 포함)

1. `~/.hapbang/prod-secrets.env`의 값을 바꾸고 그 앱을 다시 봉인해 PR로 병합한다. 내부 API 키와 `DB_PASSWORD`는 이 파일에서 읽지 않으므로 아래 절차를 따른다.
2. Deploy를 `apps`에 그 앱을 넣어 실행한다. envFrom은 pod가 시작할 때만 읽으므로, 배포로 pod가 다시 만들어져야 새 값이 들어간다.

- `HAPBANG_INTERNAL_API_KEY`는 `seal-app-secrets.sh`가 `prod-secrets.env`가 아니라 `~/.hapbang/internal-api-key`에서 읽는다. env 파일만 고치면 다시 봉인해도 기존 키가 그대로 들어간다. 바꿀 때는 이 파일을 새 값으로 바꾸고(예: `(umask 077; openssl rand -hex 32 | tr -d '\n' > ~/.hapbang/internal-api-key)`), 앱 이름 없이 `k8s/tools/seal-app-secrets.sh`를 실행해 앱 6개를 함께 다시 봉인한다. PR 병합 뒤 Deploy의 `apps`에 앱 6개를 모두 넣어 배포한다. 앱 6개가 같은 값을 써야 하므로 배포가 끝날 때까지 앱 사이 내부 호출이 잠깐 401로 실패할 수 있다. 팀 비밀 보관소의 파일도 새 값으로 바꾼다.
- `DB_PASSWORD`는 `postgres-credentials`와 같아야 하고, PostgreSQL은 첫 기동 때만 이 값으로 role을 만든다. 바꿀 때는 [security.md](security.md#db-비밀번호-교체)를 따른다.

## 삭제

코드에서 사용을 먼저 없애고 배포한 뒤 → ConfigMap 또는 `seal-app-secrets.sh`·Secret 표에서 키를 지우고 다시 봉인한다. 반대로 하면 기본값 없는 `${KEY}` 때문에 앱이 기동하지 못한다.

## FE 환경변수

Vite의 `VITE_*` 변수는 **빌드할 때 JS 번들에 그대로 박힌다.** 브라우저에서 누구나 볼 수 있다.

- **비밀값을 절대 넣지 않는다.** API 키 등은 BE를 통해 사용한다.
- API 주소는 환경변수 대신 상대 경로(`/api/v1/...`)를 쓴다. FE와 API는 같은 도메인에서 Envoy가 경로로 나눈다.
- 환경별로 꼭 달라야 하는 공개 값이 생기면 FE 레포의 빌드 단계에 build-arg를 추가해야 한다. 팀과 먼저 상의한다.

## 체크리스트

- [ ] 비밀값인지, 환경별로 다른지 분류했다
- [ ] `application-prod.yaml`에 `${KEY}` (기본값 없음)
- [ ] 비밀값: `seal-app-secrets.sh` 키 목록, k8s.md Secret 표, local overlay `secretGenerator` 가짜 값, 다시 봉인한 SealedSecret
- [ ] 설정값: 앱 `configMapGenerator`
- [ ] 병합 후 Deploy 성공, pod에 값 전달 확인
- [ ] 값이 PR·커밋·로그·채팅에 남지 않았다
