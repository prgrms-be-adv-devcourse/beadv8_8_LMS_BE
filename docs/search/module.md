# 검색(search) 모듈

auxiliary-app 안의 검색 모듈(`:search`, 패키지 `com.hapbang.auxiliary.search`)이다. 로그인한 회원이 검색어·콘텐츠 분야·팔로워·출연 평점으로 인플루언서를 찾는다. 결정의 배경은 부모 이슈 #767, 인프라 방향은 [검색 인프라](../architecture/infrastructure/search.md)에 있다.

- 기준: #771(검색 1), #772(검색 2: 증분 보정·정합성 지표). 처음 적재·무중단 재색인은 #773(검색 3)이 더한다.
- 이 문서와 코드가 다르면 코드가 맞다. 확인한 뒤 이 문서를 고친다.

## 1. 지금 범위

| 한다 (#771, #772) | 아직 안 한다 |
| --- | --- |
| 회원 이벤트로 문서 넣기·숨기기(회원 version 조건부 쓰기, tombstone) | 이미 있는 인플루언서 처음 적재, 설정 해시가 바뀐 index 재색인(#773) |
| 10분마다 통계(채널·팔로워·평점)와 분야 이름 맞추기 | 원본·index 문서 수 차이 alert(지표만 낸다, #772 Q2) |
| 10분마다 이벤트를 놓친 회원 바로잡기(증분 보정, #772) | 강의·모집 검색, 통합 검색 |
| 원본·index 문서 수 비교 지표(#772) | |
| 7일 지난 tombstone 정리 | |
| `GET /api/v1/search/influencers`, 지표 | |

이 이슈만으로는 이미 있는 인플루언서가 index에 없다(이벤트가 온 회원만 들어간다). FE 연결은 #773 뒤에 한다.

## 2. 검색 기능 스위치 (임시)

`hapbang.search.enabled`(기본 `false`)가 켜져야 검색 모듈의 빈(ES client, 이벤트 소비자, 배치, API)이 하나라도 뜬다. 운영 ES의 검색용 API key(`ES_SEARCH_API_KEY`, #806)가 생기기 전에 병합하려고 둔 장치다. 꺼져 있어도 auxiliary-app(챗봇·알림)은 그대로 뜨고 `search` schema만 생긴다.

**#806(검색 key)과 #772(증분 보정)가 모두 끝나면 스위치를 켜는 것이 아니라 지운다**(PR #828 리뷰, #771 Q19). #772는 들어왔으므로 #806이 끝나면 작은 PR로 지운다. 증분 보정을 기다리는 이유는 5장 "ES가 멈췄을 때"다. 지울 것: `ConditionalOnSearchEnabled`와 그 사용처, `application.yaml`의 `hapbang.search.enabled`, 모듈 테스트 설정의 `enabled: true`, `AuxiliaryApplicationTest#searchSwitchOff`.

## 3. 구조

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| config | `SearchElasticsearchConfig` | 검색 전용 `ElasticsearchClient`(빈 이름 `searchElasticsearchClient`, 기본 후보 아님). 챗봇은 Boot 자동 설정 client(`ES_API_KEY`)를 쓰고 검색은 같은 cluster에 `ES_SEARCH_API_KEY`로 따로 붙는다 |
| in | `SearchMemberEventListener` | 회원 이벤트 소비(`member.events.v1`, 그룹 `auxiliary-app.search-member`) |
| in | `SearchBatch` | 통계 동기화(끝난 뒤 10분), 증분 보정(끝난 뒤 10분), tombstone 정리(매일 04:30), 0건 검색어 로그(10분) |
| in | `SearchIndexStartup` | 기동 뒤 다른 스레드에서 index·동의어 준비 |
| in | `ApiV1SearchController`·`ApiV1SearchSpec` | 검색 API |
| app | `InfluencerMemberIndexer` | 회원 정보 → 넣기 또는 숨기기 |
| app | `InfluencerStatsSync` | 통계·분야 이름 동기화와 문서 수 정합성 점검(한 pod만, `JobLock` 771_002) |
| app | `MemberChangeReconciler` | 회원 변경분 증분 보정(한 pod만, `JobLock` 772_001). 결과 종류는 `ReconcileKind` |
| app | `SearchSyncMetrics` | 보정·정합성 지표 |
| app | `TombstoneCleanup` | tombstone 정리(한 pod만, `JobLock` 771_001) |
| app | `InfluencerSearchFacade` | 검색 조건 검사, 지표, 0건 검색어 |
| app | `ContentCategoryNames`, `RatingBaseline` | 분야 코드 → 이름, 평점 전체 평균 C. 둘 다 pod마다 10분 캐시 |
| out | `InfluencerIndexDefinition`, `InfluencerIndexSetup` | index 정의(레포 파일)와 준비 |
| out | `InfluencerDocumentStore` | 문서 쓰기(회원·통계·분야 이름·정리) |
| out | `InfluencerSearcher` | 검색 쿼리, 평균 C 집계 |
| out | `MemberApiStatsSource`, `MemberApiChangeSource` | 회원 내부 API(통계·분야 목록·변경분, #769) |
| out | `SyncCursorStore`, `ConsistencyCheckStore` | 증분 보정 커서(`search.sync_cursor`)와 문서 수 정합성 점검 결과(`search.consistency_check`). JDBC(Repository 빈에는 스위치를 붙일 수 없다) |

## 4. index

- 이름 `search-influencers-<설정 해시 12자>`, 검색·쓰기는 alias `search-influencers`로만 한다(쓰기는 `require_alias`라 alias가 없으면 실패하고, ES가 같은 이름의 index를 멋대로 만들지 않는다).
- 원본은 레포 파일 `boundedContext/search/src/main/resources/search/influencers/`의 `index.json`(설정·mapping), `user-dictionary.txt`(사용자 사전), `synonyms.txt`(동의어)다.
- 설정 해시는 mapping·분석기·사용자 사전으로만 만든다. **동의어는 넣지 않는다**(검색할 때만 쓰여 재색인이 필요 없다).
- 기동하면: 동의어 묶음(`search-influencers-synonyms`)이 파일과 다르면 묶음 전체를 PUT → alias가 없으면 지금 해시의 index를 만들고 alias를 붙인다. alias가 다른 해시의 index를 가리키면 그대로 쓰고 WARN만 남긴다(재색인은 #773).
- shard 1, replica 0(운영 ES가 1대), `dynamic: strict`.

### 분석

| 분석기 | 구성 | 쓰는 곳 |
| --- | --- | --- |
| `ko_index` | `nori_tokenizer`(`decompound_mode: discard`, 사용자 사전) + `nori_part_of_speech` + `lowercase` | 색인 |
| `ko_search` | `ko_index` + `synonym_graph`(Synonyms API 묶음, `updateable`) | 검색 |

- 사용자 사전은 한 단어 규칙만 둔다. 사전이 없으면 사라지거나(치지직·옵치) 뜻이 깨지는(인플루언서 → 인/플/언서, 배그 → 배) 단어다. 띄어 쓴 이름(리그 오브 레전드)은 동의어가 맡는다.
- 동의어를 바꾸면 파일을 고쳐 배포한다. ES가 검색 분석기를 스스로 다시 읽는다. 쓰는 index가 있으면 묶음을 지울 수 없으므로 묶음을 지우는 일은 하지 않는다.

### 필드

| 필드 | 종류 | 출처 |
| --- | --- | --- |
| `userId` | long | 문서 ID와 같은 회원 ID. 검색 정렬의 마지막 키 |
| `memberVersion` | long | 회원 이벤트(`UserDto.version`) |
| `hidden`, `hiddenAt` | boolean, date | 숨김(tombstone)과 숨긴 시각 |
| `nickname`, `bio` | text(nori) | 회원 이벤트 |
| `role`, `verified` | keyword, boolean | 회원 이벤트(VERIFIED_INFLUENCER면 verified) |
| `contentCategories.code`, `contentCategories.name` | keyword, text(nori) | 코드는 회원 이벤트, 이름은 분야 목록 내부 API. 회원이 고른 순서 |
| `channelNames` | text(nori) | 통계(인증된 채널 모두) |
| `representativeChannelName` | keyword(색인 안 함) | 통계(팔로워가 가장 많은 채널, 같으면 채널 ID 작은 쪽) |
| `followerCount` | long | 통계(가장 큰 채널 값. 합하지 않는다) |
| `ratingAverage`, `ratingCount` | double, long | 통계(받은 출연 리뷰 전체. 리뷰가 없으면 평균 없음) |

## 5. 회원 이벤트 반영

- `UserJoinedEvent`·`UserModifiedEvent`만 쓴다. 파기(`UserMaskedEvent`)는 받지 않는다(숨길 때 개인정보를 이미 지우고, 파기에는 version이 없다). 다른 타입은 DEBUG로 건너뛴다.
- 색인 대상은 인플루언서·인증된 인플루언서 + ACTIVE다. 대상이면 프로필을 넣거나 덮고, 아니면 숨긴다.
- **회원 version 조건부 쓰기**: ES external versioning은 쓰지 않는다. 10분 통계 부분 갱신이 ES 문서 버전을 올려 다음 회원 이벤트를 거절하게 만들기 때문이다(#771 시험). 회원 version은 `memberVersion` 필드에 두고 painless scripted upsert로 "들어온 version이 더 클 때만" 반영한다. 늦게 온 이벤트·같은 이벤트 두 번은 noop이다. version이 없는 정보는 `UserDto` javadoc 규칙대로 문서에 version이 없을 때만 반영한다.
- **tombstone**: 숨길 때 문서를 지우지 않고 `hidden=true`·`hiddenAt`·version을 남기고 닉네임·자기소개·채널 이름·대표 채널을 지운다. 지우면 늦게 온 옛 이벤트가 문서를 되살린다(#771 시험). **문서가 없어도 숨김 문서를 만든다**(#771 Q6): 강등(v6)이 승급(v5)보다 먼저 와도 v5가 문서를 보이게 만들지 못한다. 그래서 일반 유저의 가입·변경 이벤트에도 개인정보 없는 숨김 문서가 생기고 7일 뒤 지워진다.
- 재시도·DLT는 [kafka.md](../architecture/infrastructure/kafka.md) 규칙이다: 1·2·4초 3회 뒤 `member.events.v1.auxiliary-app.search-member.DLT`(partition 3, 보존 7일). 회원 ID·역할·상태가 없는 이벤트는 재시도 없이 DLT로 간다.
- 새 그룹은 `earliest`부터 읽는다(보존 기간 안의 과거 회원 이벤트로 그동안 승급한 인플루언서가 들어간다).

### ES가 멈췄을 때

- 재시도는 팀 규칙대로 3번(약 7초)이다. ES가 그보다 오래 멈추면(재시작, ECK 롤링) 그동안 온 회원 이벤트가 DLT로 간다. 승급·강등 이벤트가 그중에 있으면 검색 노출이 원본과 어긋난다.
- 10분 증분 보정(6장)이 회원 변경분을 다시 읽어 저절로 바로잡는다. ES가 살아난 뒤 다음 회차에 맞춰지므로 **DLT를 되돌리지 않아도 된다.** 보정이 멈춘 동안 바뀐 회원도 커서가 그 앞에 남아 있어 따라잡는다.
- 보정으로도 맞지 않으면(커서 밖의 변경, 6장 "한계") 아래처럼 DLT를 되돌릴 수 있다.
- 되돌리기: [kafka.md "DLT 재처리 runbook"](../architecture/infrastructure/kafka.md#dlt-재처리-runbook)대로 `member.events.v1.auxiliary-app.search-member.DLT`를 조회해, ES가 살아난 뒤 메시지를 `member.events.v1`에 다시 넣는다. 같은 토픽의 다른 그룹(회원 복제본들)도 다시 받지만 version 비교로 무시한다. 검색 문서도 version 조건부 쓰기라 여러 번 넣거나 순서가 섞여도 결과가 같다. 회원 정보가 빠져 DLT로 간 메시지(`IllegalArgumentException`)는 넣지 않는다.

## 6. 10분 동기화와 정리

- 통계 동기화(한 pod만): 분야 목록을 다시 읽어 **보이는** 문서 중 분야가 있는 문서의 이름을 맞춘다(update_by_query, 바뀐 것만. 숨긴 문서는 일반 유저 가입마다 늘어나 훑지 않는다) → 인플루언서 통계(#769)를 500명씩 끝까지 읽어 bulk 부분 갱신한다. 값이 같거나 숨긴 문서는 noop, 문서가 없는 회원은 건너뛴다(넣는 것은 #772). 결과는 INFO 한 줄(`pages·updated·noop·missing·failed·categoryDocuments`).
- 실패를 가둔다: 분야 목록을 읽지 못하면 분야 이름만 건너뛰고(`categoryDocuments=-1`) 통계는 맞춘다. bulk 항목 일부가 실패하면(겹친 쓰기 등) 그 회원만 `failed`로 세고 WARN을 남긴 뒤 다음 페이지를 계속한다. 다음 회차에 다시 쓴다.
- 분야 이름 목록과 평점 전체 평균 C는 pod마다 직접 구한다(#771 Q8). 동기화는 한 pod에서만 돌아 다른 pod에 값을 줄 수 없다. 둘 다 구하지 못하면 가진 값을 쓰고 1분 동안 다시 묻지 않는다(ES·회원 앱이 멈췄을 때 요청마다 시간 제한까지 기다려 요청 스레드를 붙잡지 않게). 평균 C를 한 번도 구하지 못했으면 평점순 검색은 503이다.
- 정합성 점검(#772): 통계를 끝까지 읽은 정상 인플루언서 수(원본)와 ES의 보이는 문서 수(`hidden=false`)를 비교해 결과를 `search.consistency_check`에 남기고, 차이(원본 − index)를 지표로 낸다. 점검은 한 pod에서만 돌므로 결과를 DB에 두어 모든 pod가 같은 값을 낸다(PR #833 리뷰 Q2). 양수면 index에 빠진 인플루언서(승급을 놓침), 음수면 보이면 안 되는 문서(강등을 놓침)가 있다. 원본은 수 분에 걸쳐 페이지로 읽고 ES는 마지막에 한 번 세므로 그사이 바뀐 회원만큼 잠깐 다를 수 있다. 세지 못하거나 동기화가 중간에 실패하면 WARN을 남기고 점검 결과를 지운다(`indexVisible=-1`, 낡은 차이를 보여 주지 않게). #773 처음 적재 전에는 이미 있던 인플루언서가 index에 없어 차이가 늘 크다. 그래서 alert는 두지 않고 지표로만 본다(#772 Q2, 필요해지면 붙인다).
- tombstone 정리(한 pod만, 매일 04:30 KST): `hiddenAt`이 7일(회원 이벤트 DLT 보존 기간)보다 오래된 숨김 문서를 지운다.

### 증분 보정 (#772)

회원 이벤트를 놓쳐도(DLT, 소비 버그) 검색 문서가 원본과 저절로 맞춰지게 한다. 버킷 해시 비교(anti-entropy)는 넣지 않았다. 증분 보정이 대부분을 잡고, 남는 어긋남은 정합성 점검 지표로 본다.

- 끝난 뒤 10분마다(기동 2분 뒤 시작), 한 pod만(`JobLock` 772_001). 회원 변경분 내부 API(#769, `docs/api/internal-members.md` 4장)를 커서부터 끝까지 500명씩 읽는다. 변경분은 모든 역할·상태라 강등·정지·탈퇴한 회원도 나온다.
- **같은 반영 코드**: 받은 `UserDto`마다 이벤트와 같은 `InfluencerMemberIndexer.apply`를 부른다(넣기·숨기기·덮기, 5장). 회원 version 조건부 쓰기라 이벤트로 이미 반영한 회원은 noop이고 놓친 회원만 바뀐다. 이벤트와 보정이 같은 변경을 둘 다 반영해도 결과가 같다.
- **커서**: `search.sync_cursor`의 `member-changes` 행에 마지막으로 반영한 회원의 `(updatedAt, 회원 ID)`를 둔다. 페이지마다 회원 앱이 준 다음 커서를 저장하므로 회차 중간에 멈춰도(ES·회원 앱 장애, 배포) 다음 회차가 거기서 이어 간다. 커서 시각은 회원 앱이 준 값(Asia/Seoul, 오프셋 없음) 그대로다.
- **1분 겹쳐 읽기**: 저장한 커서 시각에서 1분 뺀 시각(회원 ID 0)부터 읽는다. 회원 앱은 `updated_at`을 저장(flush) 때 찍고 커밋은 그 뒤라, 늦게 커밋된 행이 지나간 커서 앞에 나타나기 때문이다. 겹쳐 읽은 회원은 version으로 걸러진다(`already_applied`).
- **처음**: 커서가 없으면 지금 시각을 커서로 먼저 저장하고 거기서부터 읽는다(먼저 저장해야 첫 회차에 변경이 없어도 다음 회차가 그사이 변경을 놓치지 않는다). 그 전 회원은 #773 처음 적재가 맡는다.
- **끝까지 읽은 시각**: `hasNext=false`까지 읽으면 `caught_up_at`을 남긴다. 커서 시각은 변경이 없는 동안 멈춰 있으므로, 보정이 밀리는지는 이 값으로 잰다(#772 Q3).
- **보정 건수**(#772 Q1): 쓰기 결과가 아니라 **검색에 보이는 내용이 실제로 바뀌었는지**로 센다. 프로필 사진 변경처럼 이벤트 없이 version만 오르는 경우를 보정으로 세지 않기 위해서다. 페이지마다 그 회원들의 문서를 한 번에(mget) 읽어 비교한다.

| 종류(`kind`) | 뜻 | 놓친 이벤트 |
| --- | --- | --- |
| `inserted` | 안 보이던(문서 없음·숨김) 회원을 보이게 했다 | 승급 |
| `hidden` | 보이던 회원을 숨겼다 | 강등·정지·탈퇴 |
| `overwritten` | 보이는 회원의 닉네임·자기소개·역할·분야를 고쳤다 | 프로필 변경 |
| `version_only` | 보이는 내용은 같고 version만 올렸다(사진 변경, 일반 유저의 숨김 문서) | - |
| `already_applied` | 이미 같거나 더 새 version이라 noop(이벤트가 반영, 겹쳐 읽음) | - |
| `skipped` | 회원 ID·역할·상태가 빠진 정보, 또는 ES가 그 회원 값 때문에 거절(`document_parsing_exception`·`mapper_parsing_exception`). WARN을 남기고 다음 회원을 계속한다(멈추면 커서가 그 페이지를 넘지 못한다) | - |

- `inserted`·`hidden`·`overwritten`은 회원마다 INFO 한 줄(`userId`, `kind`, `memberVersion`)을 남겨 누구의 이벤트가 빠졌는지 찾게 한다. 회차마다 INFO 한 줄로 종류별 건수와 마지막 커서를 남긴다.
- 실패: ES·회원 앱을 부르지 못하면(연결 실패, 5xx, 429) 회차를 멈춘다(WARN, 다음 회차에 저장한 커서부터). ES가 400으로 거절해도 회원 값 문제가 아니면(script 오류 `script_exception`, mapping에 없는 칸 `strict_dynamic_mapping_exception` 등 배포 실수) 멈춘다. 모든 회원이 같은 이유로 거절되는데 건너뛰면 커서가 넘어가 그 회원들을 다시 보정하지 못하기 때문이다(PR #833 리뷰 Q1, 오류 이름은 ES 9.5.5로 시험해 확인). 같은 페이지를 다시 반영해도 version으로 걸러진다. 회원 앱 응답에 다음 커서가 빠졌으면(#769 계약 위반) 같은 요청을 반복하지 않고 회차를 멈춘다.
- **바깥에서 시작 시각 정하기**: `MemberChangeReconciler.runFrom(시각)`은 커서를 그 시각(회원 ID 0)으로 옮기고 한 번 돈다. #773이 새 index로 alias를 바꾼 뒤 "시각 T 이후 따라잡기"에 쓴다. 겹쳐 읽기를 빼지 않으므로 여유는 부르는 쪽이 넣는다(T − 1분). 정기 회차와 같은 락이다. **끝까지 따라잡았을 때만 `true`**이고, 다른 회차가 돌고 있어 건너뛰었거나 중간에 실패했으면 `false`다. 락 없이 도는 `reconcile`은 바깥에서 부르지 않는다(package-private).
- 한계
  - 보정 건수는 페이지 앞에서 한 번 읽은 문서와 비교한다. 그 페이지(최대 500명)를 반영하는 몇 초 사이에 같은 회원의 이벤트가 먼저 반영되면(이벤트 도착 직전에 보정이 읽었거나, 같은 회원이 몇 초 안에 두 번 바뀜) 놓친 이벤트가 아닌데도 보정으로 셀 수 있다.
  - 회차가 중간에 실패하면 이미 반영한 페이지의 보정 건수는 지표에 남지 않고, 다음 회차에는 그 회원들이 `already_applied`로 보인다. 장애가 있던 시간의 보정 건수는 적게 잡힐 수 있으므로 회원별 INFO 로그(`회원 이벤트를 놓친 검색 문서를 보정했다`)로 확인한다.
  - 회원마다 ES 요청 1번이다(이벤트와 같은 반영 코드). 평소 10분 변경은 적고 장애 뒤 수천 명이어도 수 초다. 길어지면 `reconcile.duration`으로 보고 bulk를 검토한다.
  - 저장부터 커밋까지 1분 넘게 걸린 회원 변경은 겹쳐 읽기 밖이라 놓칠 수 있다. 그때는 회원 이벤트가 반영한다(#769).
  - 회원 앱이 여러 대가 되면 서버끼리 시계 차이도 1분 안이어야 한다(#769).

## 7. 검색 API

`GET /api/v1/search/influencers` (로그인 회원 전체. 비회원 401)

| 파라미터 | 뜻 |
| --- | --- |
| `keyword` | 최대 100자. 닉네임 ×3, 채널 이름 ×3, 분야 이름 ×2, 자기소개 ×1(`best_fields`, tie_breaker 0.3) |
| `contentCategories` | 분야 코드 최대 10개. 하나라도 가진 사람(filter, OR) + 많이 맞을수록 위(should) |
| `verified` | true면 인증만, false면 인증 안 된 사람만, 없으면 모두 |
| `minFollowers`, `minRating` | 0 이상, 0~5. 평점 조건은 원래 평균이라 리뷰 없는 사람은 빠진다 |
| `page`, `size`, `sort` | size 기본 20·최대 100. sort는 `followers,desc`·`rating,desc`만(그 밖 400 `UNSUPPORTED_SORT`) |

- 순서: 기본은 검색어나 분야가 있으면 점수 → 팔로워, 둘 다 없으면 팔로워. 검색어가 있으면 점수 = 텍스트 관련도 × (1 + a·log10(1+팔로워)) × (인증이면 v). 평점순은 베이지안 평균 (n×R + m×C)/(n+m) → 팔로워. **모든 순서의 마지막은 회원 ID 오름차순**이다(동점이 많아, 없으면 통계 동기화가 문서를 다시 쓸 때 페이지 사이에서 같은 사람이 또 나오거나 빠진다).
- 계수: a=0.05, v=1.05, m=5(`hapbang.search.ranking.*`). golden query(`GoldenQueryTest`) 23개가 모두 통과하는 값이다. a의 통과 구간은 0.02~0.1이다(0은 팔로워가 순서를 못 가르고, 0.5 이상은 팔로워가 텍스트 일치를 뒤집는다).
- 앞에서 1,000건까지(`page × size + size ≤ 1000`, 넘으면 400 `PAGE_TOO_DEEP`). `totalElements`도 1,000까지만 센다.
- ES에 닿지 못하면 503 `SEARCH_UNAVAILABLE`. 회원 앱 인플루언서 목록(`GET /api/v1/members/influencers`)은 그대로 쓸 수 있다.
- 응답 한 줄: `id`, `nickname`, `role`, `contentCategories[{code, name}]`, `bio`, `channelName`(대표), `followerCount`, `appearanceRating{average, count}`. 프로필 사진은 없다(#770 API로 FE가 채운다).

## 8. 지표와 로그

| 지표 | 뜻 |
| --- | --- |
| `hapbang.search.requests{target, outcome}` | 검색 요청 수. outcome = ok·empty(0건)·error |
| `hapbang.search.es.latency{target}` | ES 검색 시간(histogram, p95) |
| `hapbang.search.zero.results{target}` | 필터 없이 검색어만으로 0건인 요청 수 |
| `hapbang.auxiliary.job.lock.skipped{job}` | 락 때문에 건너뛴 회차(global) |
| `hapbang.search.reconcile.members{kind}` | 증분 보정이 반영한 회원 수(6장 표). inserted·hidden·overwritten이 놓친 이벤트이고, 모든 kind의 합이 읽은 회원 수다 |
| `hapbang.search.reconcile.duration{outcome}` | 보정 1회 소요 시간. outcome = success·failure. 길어지면 규모를 다시 판단한다 |
| `hapbang.search.reconcile.lag` | 지금 − 마지막으로 끝까지 읽은 시각(초). 보정이 멈추면 커진다. DB 커서를 읽어 어느 pod에서 봐도 같다. 끝까지 읽은 적이 없으면 값이 없다 |
| `hapbang.search.consistency.difference{target}` | 원본 정상 인플루언서 수 − 보이는 문서 수(6장 정합성 점검). DB의 점검 결과에서 읽어 어느 pod에서 봐도 같다. 점검한 적이 없거나, 마지막 회차에 점검하지 못했거나, 점검한 지 25분(점검 주기 두 번)이 지났으면 값이 없다. alias로 세므로 #773 재색인은 alias를 한 요청으로 바꿔 두 index를 함께 가리키는 순간이 없어야 한다 |
| `kafka_consumer_fetch_manager_records_lag` | 회원 이벤트 소비 lag(Kafka client 지표). Kafka dashboard "그룹별 lag"에 `auxiliary-app.search-member`로 보인다 |

- 회원 ID는 지표 태그에 넣지 않는다. 문서 수 차이 alert는 두지 않았다(#773 처음 적재 전에는 차이가 늘 0이 아니다, #772 Q2).

- 0건 검색어는 사전·동의어를 늘릴 근거로 모은다(#771 Q9, PR #828 리뷰 뒤 Q18). 검색어는 남의 이름·닉네임·연락처일 수 있어 조건을 둔다.
  - **필터 없이 검색어만으로 0건**인 요청만 센다. 필터가 좁아 0건인 것은 사전 문제가 아니다(지표 `zero.results`도 같은 기준).
  - **서로 다른 회원 2명 이상이 찾은 말만** 로그에 나온다. 한 사람만 입력한 말은 남지 않는다. 회원 ID는 이 판단에만 메모리에서 쓰고(검색어마다 2명까지) 남기지 않는다.
  - 이메일·URL·전화번호 모양(숫자 8개 이상)과 30자를 넘는 말은 세지 않는다.
  - 요청에서는 세기만 하고 10분마다 배치 스레드가 `여러 회원이 찾았지만 결과가 없던 검색어를 모았다 keywords={…}` 한 줄(상위 100개)로 남긴다. 그래서 요청의 `userId`·`traceId`가 붙지 않는다. pod마다 따로 세고, 앱이 내려가면 남기지 않은 주기분은 사라진다.
- rate limit은 두지 않았다. 위 요청 수·p95와 k6 측정을 보고 정한다(#767).

## 9. 로컬 실행

1. `docker compose -f apps/auxiliary-app/compose.yaml up -d` — ES는 nori를 설치한 이미지다(처음 한 번 빌드).
2. 회원 앱(`./gradlew :member-app:bootRun`, 8081)을 띄운다. 통계 동기화가 회원 내부 API를 부른다.
3. `HAPBANG_SEARCH_ENABLED=true ./gradlew :auxiliary-app:bootRun`
4. `curl 'localhost:8087/api/v1/search/influencers?keyword=메이플' -H 'X-User-Id: 1' -H 'X-User-Role: USER'`

## 10. 테스트

ES는 검색 전용 Testcontainers(`SearchElasticsearchTestcontainersConfig`, 9.5.5 + nori, 보안 끔)를 JVM에서 하나만 띄워 클래스끼리 차례로 쓴다. ES를 쓰는 테스트는 시작할 때 `SearchElasticsearch.reset`으로 검색 index와 동의어 묶음을 지운다. 첫 실행은 이미지를 빌드한다(외부망, 약 1분).

| 테스트 | 보는 것 |
| --- | --- |
| `InfluencerDocumentStoreTest` | 순서 역전·중복·null version, 통계 갱신 뒤 회원 이벤트, tombstone(문서 없을 때 포함), 정리, require_alias |
| `SearchMemberEventListenerTest` | 실제 Kafka로 가입·변경 반영, 다른 타입 건너뜀, DLT |
| `InfluencerStatsSyncTest` | 통계 반영·noop·평균 지움·문서 없음·숨김·여러 페이지·분야 이름, 정합성 점검(차이 0·양수·음수, 세기 실패) |
| `MemberChangeReconcilerTest` | 증분 보정: 처음은 지금부터, 분야 변경·강등·승급을 놓친 경우, 1분 겹쳐 읽기, 이벤트와 둘 다 반영, version만 오름, 일반 유저, 멈춘 뒤 이어 가기, 바깥 시작 시각, 깨진 회원·ES가 거절한 회원 건너뜀, 다음 커서 없는 응답, `runFrom` 성공·실패 |
| `SyncCursorStoreTest`, `ConsistencyCheckStoreTest` | 커서·점검 결과 저장(PostgreSQL 컨테이너) |
| `SearchSyncMetricsTest` | 보정 지연(끝까지 읽은 시각 기준), 문서 수 차이(DB, 25분 지나면 비움), kind별 건수 |
| `ApiV1SearchControllerTest` | 응답, 필터, 정렬, 동점 페이지 넘김, 400·401, 0건 검색어 |
| `RatingBaselineTest`, `InfluencerDocumentStoreBulkFailureTest` | 평균 C 실패 뒤 1분 대기, bulk 일부 실패 |
| `GoldenQueryTest` | 대표 검색어 23개 순위, 알려진 한계(G01b), 계수 격자(`build/golden-query.md`) |

## 11. 알려진 한계

- 사전에 없는 지어낸 닉네임은 잘게 쪼개져 다른 검색어에 걸릴 수 있다(예: "롤러스케이터"가 "롤" 검색에 걸림, G01b). 0건 검색어처럼 근거가 쌓이면 사전을 보강하거나 하위 필드를 더한다.
- 동의어 일치와 원문 일치의 가중치가 같다(G05). 원문 일치를 위로 올리려면 동의어 없는 하위 필드를 작은 가중치로 더한다.
- 승급한 회원의 채널·팔로워·평점은 다음 통계 동기화(최대 10분)까지 비어 있다.
