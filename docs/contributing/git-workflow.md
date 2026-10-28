# 작업 흐름 (이슈 → 브랜치 → PR → 병합)

이슈 생성·조회·상태 변경 명령은 [이슈 트래커](../agents/issue-tracker.md)에 있다. 커밋 메시지는 [커밋 규칙](commit.md)을 따른다.

## 1. 이슈

- 새 작업은 이슈부터 만든다. GitHub Project 83(`LastMenStanding`) 보드에 넣고 Status를 In progress로 둔다.
- 제목은 `[모듈] 요약`, 본문은 `## 목표` / `## 작업 내용` / `## API` / `## 완료 조건` / `## 참고` 순서다 ([이슈 트래커](../agents/issue-tracker.md)).

## 2. 브랜치

- `origin/dev`에서 `feature/tkt-<이슈번호>` 브랜치를 만든다.
- 작업 시작 전에 dev 배포가 실패 중인지 본다: `gh run list --branch dev --event push --limit 2`.
- 다른 팀원의 변경을 반영할 때는 `dev`를 최신화한 뒤 작업 브랜치에 병합한다.
- 커밋하지 않을 로컬 파일이 있으면 `git add -A` 대신 경로를 지정해 스테이징한다.

## 3. PR

- PR 직전에 `origin/dev`를 다시 병합하고 `./gradlew check`한다. 각각 통과한 PR도 dev에서 합쳐지면 깨질 수 있다(#184 테스트 + #182 생성자). dev 배포가 실패 중인지도 다시 본다.
- **공개 레포다.** PR·이슈·커밋 전에 diff와 본문에 버킷 이름·계정 ID·인스턴스 ID가 없는지 grep한다 ([보안 가이드](../deploy/security.md)).
- 만들기: `gh pr create --base dev --title "[모듈] 요약 (#N)" --body-file <파일>`.
- 본문은 `## 📌 작업 내용`(+ `- close #N`) / `## 🔧 변경 사항` / `## ✅ 테스트` 순서다. 결정·프론트 참고·확인 필요가 있으면 섹션을 더한다.
- 티켓 하나를 PR 여러 개로 나누면 마지막 PR만 `close #N`을 쓰고, 앞선 PR은 본문 `ref #N (1/2)`, 커밋 `Ref #N`으로 둔다. 앞선 PR이 병합되면 이슈는 열어 두고 Status를 In progress로 되돌린다.

## 4. 리뷰

- CodeRabbit 지적은 인라인 코멘트로 온다: `gh api repos/<owner>/<repo>/pulls/<N>/comments`.
- 인라인 답글은 `gh api repos/<owner>/<repo>/pulls/<N>/comments/<id>/replies`로 단다.
- CodeRabbit이 무료 한도에 걸리면 `Review limit reached` 코멘트만 남는다.

## 5. 병합과 이슈 닫기

- feature → dev는 squash merge로 한다. 커밋 하나로 합쳐지므로 병합할 때 커밋 제목을 [커밋 규칙](commit.md)대로 적는다: `gh pr merge <N> --squash --subject "<유형>: <제목> (#N)"`.
- dev → main은 merge commit으로 한다: `gh pr merge <N> --merge`.
- `close #N`·`Resolve #N`은 `dev` 병합으로는 이슈를 닫지 않는다(기본 브랜치가 `main`). 병합 뒤 `gh issue close <N> --reason completed`로 닫고 Status를 Done으로 바꾼다.

## 6. 배포 확인

- dev 배포: `gh run list --branch dev --event push --limit 1`(Deploy 워크플로). `--event` 없이 조회하면 다른 PR의 CI가 나올 수 있다. 끝까지 기다리려면 `gh run watch <id> --exit-status`.
- 실패 로그는 `gh run view <id> --log-failed`로 본다.
- `docker/compose.<env>.yaml`을 바꾼 PR은 병합 후 Deploy가 "서버 설정이 레포와 다름"으로 실패하는 게 정상이다. 새 env 키는 병합 전에 서버 env 파일에 넣고, compose를 반영한 뒤 `gh run rerun <id> --failed`로 다시 실행한다. 서버 파일은 한 벌이라 `compose.prod.yaml`은 main 병합 때 반영한다 ([운영](../deploy/operations.md)).
- 배포 뒤 dev 서버 확인은 [로컬·dev 확인](../dev/local.md).
