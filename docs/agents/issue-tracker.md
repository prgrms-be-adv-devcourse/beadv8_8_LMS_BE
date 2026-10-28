# 이슈 트래커: GitHub

이 레포의 이슈와 스펙은 GitHub 이슈로 관리하고, 모든 이슈를 GitHub Project 83(`LastMenStanding`) 보드에 올린다. 모든 작업은 `gh` CLI로 한다. 레포는 `git remote -v`로 정해지고, 클론 안에서 실행하면 `gh`가 자동으로 찾는다.

브랜치·PR·리뷰·병합 절차는 [`docs/contributing/git-workflow.md`](../contributing/git-workflow.md)에 있다. 이 문서는 스킬이 쓰는 `gh` 명령을 모은다.

## 규칙

- **제목**: `[모듈] 요약`. 접두어는 `[Lecture]`, `[Appearance]`, `[Member]`, `[Billing]`, `[Chat]`, `[Global]`, `[Infra]` 등 모듈 이름이다. 이모지는 넣지 않는다.
- **본문**: `## 목표` / `## 작업 내용` / `## API` / `## 완료 조건` / `## 참고` 순서로 쓴다. 정하지 못한 것이 있으면 `## 참고` 아래에 `### 작업 전 결정할 것`을 둔다. 해당 없는 섹션은 뺀다.
- **공개 레포**: 제목·본문·댓글에 버킷 이름, 계정 ID, 인스턴스 ID, 토큰을 넣지 않는다.
- **이슈 만들기**: `gh issue create --project "LastMenStanding" --title "[모듈] ..." --body-file <파일>`. heredoc을 쓸 때는 `<<'EOF'`처럼 따옴표를 붙여 본문의 백틱·`$`가 실행되지 않게 한다.
  - `Content already exists in this project`로 실패해도 이슈는 이미 만들어져 보드에 들어가 있다. 다시 실행하지 말고 `gh issue list`로 확인한다.
- **이슈 읽기**: `gh issue view <번호> --json number,title,body,labels,comments,projectItems`
- **이슈 목록**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`에 `--label`·`--state` 필터를 더한다.
- **보드 상태 바꾸기**: `gh project item-edit 83 --owner prgrms-be-adv-devcourse --url <이슈 URL> --field "Status" --value "<Backlog|Ready|In progress|In review|Done>"`. 확인은 `gh issue view <번호> --json projectItems`로 한다(`gh project item-list`에는 새 이슈가 안 보일 때가 있다).
- **하위 이슈로 묶기**: `gh issue create --parent <부모> ...` 또는 나중에 `gh issue edit <부모> --add-sub-issue <자식>`. 하위 이슈를 쓸 수 없으면 자식 본문 맨 위에 `Part of #<부모>`를 쓴다.
- **댓글**: `gh issue comment <번호> --body-file <파일>`
- **라벨 붙이기·떼기**: `gh issue edit <번호> --add-label "..."` / `--remove-label "..."`
- **닫기**: PR 본문의 `close #N`은 `dev` 병합으로는 이슈를 닫지 않는다(기본 브랜치가 `main`). 병합 뒤 `gh issue close <번호> --reason completed`로 닫고 Status를 Done으로 바꾼다.

## PR을 요청 창구로 쓰는지

**PRs as a request surface: no.** _(외부 PR을 기능 요청으로 다루려면 `yes`로 바꾼다. `/triage`가 이 값을 읽는다.)_

`yes`이면 PR도 이슈와 같은 라벨·상태로 다루고 `gh pr` 명령을 쓴다.

- **PR 읽기**: `gh pr view <번호> --comments`, diff는 `gh pr diff <번호>`.
- **트리아지할 외부 PR 목록**: `gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`
- **댓글·라벨·닫기**: `gh pr comment`, `gh pr edit --add-label`/`--remove-label`, `gh pr close`.

GitHub는 이슈와 PR이 번호를 같이 쓰므로 `#42`만 보고는 어느 쪽인지 모른다. `gh pr view 42`로 확인하고, 없으면 `gh issue view 42`를 쓴다.

## 스킬이 "이슈 트래커에 올린다"고 할 때

위 규칙대로 GitHub 이슈를 만들고 Project 83 보드에 넣는다.

## 스킬이 "관련 티켓을 가져온다"고 할 때

위의 **이슈 읽기**대로 읽는다.

## Wayfinding

`/wayfinder`가 쓴다. **map**은 이슈 하나이고, 그 **하위 이슈**가 티켓이다.

- **Map**: `wayfinder:map` 라벨을 붙인 이슈 하나. 본문에 Notes / Decisions-so-far / Fog를 둔다. `gh issue create --project "LastMenStanding" --label wayfinder:map ...`
- **하위 티켓**: 위의 **하위 이슈로 묶기**대로 map에 연결한다. 라벨은 `wayfinder:<type>`(`research`/`prototype`/`grilling`/`task`). 맡으면 작업하는 사람을 담당자로 지정한다.
- **선행 관계**: GitHub의 이슈 의존 관계를 쓴다. `gh api --method POST repos/prgrms-be-adv-devcourse/beadv8_8_LMS_BE/issues/<자식>/dependencies/blocked_by -F issue_id=<선행 이슈 DB id>`. DB id는 `gh api repos/prgrms-be-adv-devcourse/beadv8_8_LMS_BE/issues/<번호> --jq .id`로 얻는다(`#번호`나 `node_id`가 아니다). 열린 선행 이슈는 `issue_dependencies_summary.blocked_by`로 본다. 의존 관계를 쓸 수 없으면 자식 본문 맨 위에 `Blocked by: #<n>, #<n>`을 쓴다. 선행 이슈가 모두 닫히면 막힘이 풀린다.
- **다음 티켓 고르기**: map의 열린 하위 이슈 가운데 열린 선행 이슈가 있거나 담당자가 있는 것을 빼고, map 순서상 첫 번째를 고른다.
- **맡기**: 첫 쓰기 작업으로 `gh issue edit <번호> --add-assignee @me`.
- **끝내기**: `gh issue comment <번호> --body-file <답>` → `gh issue close <번호>` → map의 Decisions-so-far에 요지와 링크를 더한다.
