# 작업 흐름 (이슈 → 브랜치 → PR → 병합)

이슈 생성·조회·상태 변경 명령은 [이슈 트래커](../agents/issue-tracker.md)에 있다. 커밋 메시지는 [커밋 규칙](commit.md)을 따른다.

## 1. 이슈

- 새 작업은 이슈부터 만든다. GitHub Project 83(`LastMenStanding`) 보드에 넣고 Status를 In progress로 둔다.
- 제목은 `[모듈] 요약`, 본문은 `## 목표` / `## 작업 내용` / `## API` / `## 완료 조건` / `## 참고` 순서다 ([이슈 트래커](../agents/issue-tracker.md)).

## 2. 브랜치

- `origin/dev`에서 `feature/tkt-<이슈번호>` 브랜치를 만든다.
- 다른 팀원의 변경을 반영할 때는 `dev`를 최신화한 뒤 작업 브랜치에 병합한다.
- 커밋하지 않을 로컬 파일이 있으면 `git add -A` 대신 경로를 지정해 스테이징한다.

## 3. PR

- PR 직전에 `origin/dev`를 다시 병합하고 `./gradlew check`한다. 각각 통과한 PR도 dev에서 합쳐지면 깨질 수 있다(#184 테스트 + #182 생성자).
- **공개 레포다.** PR·이슈·커밋 전에 diff와 본문에 버킷 이름·계정 ID·인스턴스 ID가 없는지 grep한다 ([보안 가이드](../deploy/security.md)).
- 만들기: `gh pr create --base dev --title "[모듈] 요약 (#N)" --body-file <파일>`.
- 본문은 `## 📌 작업 내용`(+ `- close #N`) / `## 🔧 변경 사항` / `## ✅ 테스트` 순서다. 결정·프론트 참고·확인 필요가 있으면 섹션을 더한다.
- 티켓 하나를 PR 여러 개로 나누면 마지막 PR만 `close #N`을 쓰고, 앞선 PR은 본문 `ref #N (1/2)`, 커밋 `Ref #N`으로 둔다. 앞선 PR이 병합되면 이슈는 열어 두고 Status를 In progress로 되돌린다.

## 4. 리뷰

- CodeRabbit 지적은 인라인 코멘트로 온다: `gh api repos/<owner>/<repo>/pulls/<N>/comments`.
- 인라인 답글은 `gh api repos/<owner>/<repo>/pulls/<N>/comments/<id>/replies`로 단다.
- CodeRabbit이 무료 한도에 걸리면 `Review limit reached` 코멘트만 남는다.
- Claude Review(`.github/workflows/claude-review.yml`)는 dev로 가는 PR에 커밋이 올라올 때마다 돈다. CodeRabbit이 한도에 걸린 PR에서도 리뷰가 남도록 전체 범위를 보며, CodeRabbit과 지적이 겹칠 수 있다. 작성자는 `github-actions[bot]`이다.
  - 첫 리뷰는 지적을 인라인 코멘트로, 요약을 PR 코멘트 하나로 단다. 재리뷰는 새 지적만 인라인으로 달고, 새 지적이 없으면 PR 코멘트를 달지 않는다. 이전 Claude 지적이 고쳐졌을 때만 고쳐진 지적을 적은 PR 코멘트 하나를 단다.
  - 리뷰 기준(루트와 `apps/<앱>/`의 AGENTS.md, `docs/`)은 PR head가 아니라 dev에서 읽는다. PR이 고친 기준 문서는 리뷰 대상이다.
  - run마다 turn·비용·토큰·도구별 호출 수가 Actions run의 Summary에 남는다(#700).
  - Draft·포크·Dependabot PR과 base가 dev가 아닌 PR(쌓은 PR)에는 돌지 않는다.

## 5. 병합과 이슈 닫기

- feature → dev는 squash merge로 한다. 커밋 하나로 합쳐지므로 병합할 때 커밋 제목을 [커밋 규칙](commit.md)대로 적는다: `gh pr merge <N> --squash --subject "<유형>: <제목> (#N)"`.
- dev → main은 merge commit으로 한다: `gh pr merge <N> --merge`.
- 기본 브랜치는 `dev`다(2026-11-02, #559). PR 본문·커밋의 `close #N`·`Resolve #N`은 dev 병합 때 이슈를 닫고, Project 자동화(Item closed)가 Status를 바꾼다. 병합 뒤 이슈가 닫히고 Status가 Done인지 본다. 키워드 없이 병합했으면 `gh issue close <N> --reason completed`로 닫는다.

## 6. 배포 확인

- dev 병합이 곧 운영 배포다(#559). dev push마다 Publish and deploy가 바뀐 앱을 테스트·발행하고 운영 k3s에 배포한다([운영 앱 배포](../deploy/k8s.md#운영-앱-배포)). 최근 실행은 `gh run list --workflow publish.yml --limit 1`, 끝까지 기다리려면 `gh run watch <id> --exit-status`.
- main에는 서버가 없다. `release/review-N` → main 리뷰 제출용으로만 병합하고, main 병합 때는 workflow가 돌지 않는다.
- 18:00~09:00에는 서버가 꺼져 배포 job이 실패한다. 다음 병합이나 `gh workflow run deploy.yml --ref dev`가 따라잡는다.
- 실패 로그는 `gh run view <id> --log-failed`로 본다.
- 새 환경변수는 [env-vars.md](../deploy/env-vars.md) 절차로 ConfigMap·SealedSecret에 넣는다.
