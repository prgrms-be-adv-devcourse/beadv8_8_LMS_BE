# 검색(search) 모듈

auxiliary-app 안의 검색 모듈(`:search`, 패키지 `com.hapbang.auxiliary.search`)이다. 로그인한 회원이 검색어·콘텐츠 분야·팔로워·출연 평점으로 인플루언서를 찾는다. 결정의 배경은 부모 이슈 #767, 인프라 방향은 [검색 인프라](../architecture/infrastructure/search.md)에 있다.

- 기준: #771(검색 1), #772(검색 2: 증분 보정·정합성 지표), #773(검색 3: 처음 적재·무중단 재색인), #868(공통 index 관리 코드 분리, 3장).
- 이 문서와 코드가 다르면 코드가 맞다. 확인한 뒤 이 문서를 고친다.

## 1. 지금 범위

| 한다 (#771, #772, #773) | 아직 안 한다 |
| --- | --- |
| 회원 이벤트로 문서 넣기·숨기기(회원 version 조건부 쓰기, tombstone) | 원본·index 문서 수 차이 alert(지표만 낸다, #772 Q2) |
| 10분마다 통계(채널·팔로워·평점)와 분야 이름 맞추기 | 강의·모집 검색, 통합 검색 |
| 10분마다 이벤트를 놓친 회원 바로잡기(증분 보정, #772) | 관리자 재색인 API, Kafka offset으로 다시 읽기(#773에서 검토하고 넣지 않음, 7장) |
| 원본·index 문서 수 비교 지표(#772) | |
| 이미 있는 인플루언서 처음 적재, 설정이 바뀐 index 무중단 재색인(#773, 7장) | |
| 7일 지난 tombstone 정리 | |
| `GET /api/v1/search/influencers`, 지표 | |

처음 적재(#773)가 끝나면 index가 원본 전체를 담는다. FE는 이 뒤에 연결한다.

## 2. 운영에서 켜기까지

#771은 검색 기능 스위치(`hapbang.search.enabled`, 기본 꺼짐)를 두고 병합했다. 운영 ES의 검색용 key(#806)가 없었고, 증분 보정(#772) 전에는 ES가 멈춰 DLT로 빠진 회원 이벤트를 사람이 되돌려야 했기 때문이다(5장 "ES가 멈췄을 때"). 두 이슈가 끝나 #860에서 스위치를 켜지 않고 지웠다(PR #828 리뷰, #771 Q19).

- 이제 검색 모듈 빈은 늘 뜬다. ES가 없거나 멈춰도 auxiliary-app(챗봇·알림)은 뜨고, 검색 API만 503 `SEARCH_UNAVAILABLE`이다.
- 운영 프로필은 `ES_SEARCH_API_KEY`(Secret `auxiliary-app-secrets`, #806)를 기본값 없이 읽는다. 값이 없으면 auxiliary-app이 뜨지 않는다.

## 3. 구조

코드는 대상과 상관없는 **공통 코드**(`search.common`)와 **대상별 구현**(`search.influencer`, 나중에 `search.lecture`·`search.recruit`)으로 나뉜다(#868). 공통 코드는 "검색 index 하나를 관리하는 일"(처음 적재·재색인·alias 교체·tombstone 정리·조건부 쓰기의 오류 처리·적재 중 건너뛰기·지표·job 잠금·0건 검색어)을 하고, 대상은 원본 읽기·문서 모양·mapping·점수만 구현한다(강의 검색 결정 L15). 계층은 다른 모듈과 같은 `in`(들어오는 어댑터)·`app`(처리)·`out`(ES·DB·다른 앱)이다.

- **방향**: 대상 → 공통만 참조한다. 공통 코드는 어느 대상이 쓰는지 모른다(`search.common`에 `search.influencer` import 0건).
- **모듈 전체가 함께 쓰는 것**은 `search` 바로 아래에 둔다(아래 첫 표).
- **대상을 공통 코드에 붙이는 두 방식**(#868 R1·R7)
  - 설정만 채우면 **얇은 하위 클래스**: `InfluencerIndexSetup extends IndexSetup`, `InfluencerTombstoneCleanup extends TombstoneCleanup`. 생성자만 두고 메서드를 덮어쓰지 않는다(동작은 공통 클래스에서 바꾼다). 쓰는 곳이 타입으로 대상을 가리켜 다른 대상의 index를 잘못 넘기지 못한다.
  - 대상별 동작을 끼우면 **부품 조합**: 공통 `Reindexer`에 채우기(`IndexLoader`)·따라잡기(`IndexCatchUp`) 구현을 넘긴다(`InfluencerReindexer`).

### 모듈 전체 (`search`)

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| config | `SearchElasticsearchConfig` | 검색 전용 `ElasticsearchClient`(빈 이름 `searchElasticsearchClient`, 기본 후보 아님). 챗봇은 Boot 자동 설정 client(`ES_API_KEY`)를 쓰고 검색은 같은 cluster에 `ES_SEARCH_API_KEY`로 따로 붙는다 |
| config | `SearchConfig` | 처음 적재·재색인 실행기(`searchReindexExecutor`, 가상 스레드, 7장) |
| app | `exception/SearchErrorCode`·`SearchException` | 검색 오류 코드 |
| in | `ApiV1SearchController`·`ApiV1SearchSpec` | 검색 API(대상별 경로, 8장) |

### 공통 코드 (`search.common`)

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| app | `Reindexer` | 처음 적재·무중단 재색인 흐름(7장). 대상은 `IndexLoader`(새 index 채우기)·`IndexCatchUp`(교체 뒤 따라잡기)만 구현한다. 재색인 지표(`target` 태그)와 ERROR 표식을 남긴다 |
| app | `TombstoneCleanup` | 7일 지난 숨김 문서 정리(대상마다 얇은 하위 클래스) |
| app | `SearchJob`, `SearchJobRunner` | 한 pod 실행 락 번호와 실행기. alias가 없어(처음 적재 전) 못 한 회차는 실패가 아니라 건너뜀(INFO) |
| app | `SearchMetrics` | 검색 API 지표. `forTarget(대상)`으로 대상별 묶음을 받는다 |
| app | `ZeroResultKeywords` | 0건 검색어 모으기(9장). 검색자 키는 회원 ID(`Long`) 또는 비회원 client IP 해시(`String`, L19) |
| out | `IndexDefinition` | `search/<대상>/index.json` + 공통 사용자 사전으로 index 본문·설정 해시·이름(`search-<대상>-<해시>`)·alias(`search-<대상>`) |
| out | `IndexSetup` | index 하나의 쓰기 준비(alias 확인, 없으면 `IndexNotReadyException`), 상태(`IndexState`), 새 index 만들기·지우기·refresh, alias 원자 교체 |
| out | `SynonymsSetup` | 동의어 묶음 하나를 공통 `synonyms.txt`와 맞춘다(모든 대상이 함께 씀) |
| out | `DocumentWrites` | 문서 쓰기의 대상 무관 부분: alias 없음 404 → 건너뜀, 그 문서 값 때문의 거절 판별(`DocumentRejectedException`), bulk 결과(`WriteOutcome`), 보이는 문서 세기, 숨김 문서 지우기 |
| out | `ConsistencyCheckStore` | 문서 수 정합성 점검 결과(`search.consistency_check`, 대상별 한 행). JDBC |

### 인플루언서 (`search.influencer`)

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| in | `SearchMemberEventListener` | 회원 이벤트 소비(`member.events.v1`, 그룹 `auxiliary-app.search-member`) |
| in | `SearchBatch` | 통계 동기화(끝난 뒤 10분), 증분 보정(끝난 뒤 10분), 처음 적재·재색인 확인(끝난 뒤 10분), tombstone 정리(매일 04:30), 0건 검색어 로그(10분) |
| in | `SearchIndexStartup` | 기동 뒤 다른 스레드에서 처음 적재·재색인 확인(동의어 포함) |
| app | `InfluencerMemberIndexer` | 회원 정보 → 넣기 또는 숨기기 |
| app | `InfluencerStatsSync` | 통계·분야 이름 동기화와 문서 수 정합성 점검(한 pod만, `JobLock` 771_002) |
| app | `MemberChangeReconciler` | 회원 변경분 증분 보정(한 pod만, `JobLock` 772_001). 결과 종류는 `ReconcileKind`. 재색인의 따라잡기 부품(`IndexCatchUp`)이기도 하다 |
| app | `InfluencerReindexer`, `InfluencerIndexLoader` | 공통 `Reindexer`에 인플루언서 부품을 끼운다(락 `JobLock` 773_001). 채우기는 회원 목록 + 통계, 따라잡기는 증분 보정 |
| app | `InfluencerTombstoneCleanup` | 인플루언서 index의 tombstone 정리(한 pod만, `JobLock` 771_001) |
| app | `SearchSyncMetrics` | 보정·정합성 지표 |
| app | `InfluencerSearchFacade` | 검색 조건 검사, 지표, 0건 검색어 |
| app | `ContentCategoryNames`, `RatingBaseline` | 분야 코드 → 이름, 평점 전체 평균 C. 둘 다 pod마다 10분 캐시 |
| out | `InfluencerIndexDefinition`, `InfluencerIndexSetup` | 대상 이름(`influencers`)·alias·지표 대상 값(`influencer`)과, 그 정의를 채운 `IndexSetup` |
| out | `InfluencerDocumentStore` | 회원·통계·분야 이름 쓰기(painless), 처음 적재용 bulk, 증분 보정용 문서 읽기. 나머지는 `DocumentWrites`에 맡긴다 |
| out | `InfluencerSearcher` | 검색 쿼리, 평균 C 집계 |
| out | `MemberApiStatsSource`, `MemberApiChangeSource`, `MemberApiListSource` | 회원 내부 API(통계·분야 목록·변경분 #769, 회원 목록 #773) |
| out | `SyncCursorStore` | 증분 보정 커서(`search.sync_cursor`). JDBC |

### 새 대상(강의·모집)을 붙이는 법

1. `search/<대상>/index.json`을 만든다. 사용자 사전·동의어 묶음 자리표시자(`"__USER_DICTIONARY__"`, `__SYNONYMS_SET__`)를 넣고, 숨김 표시 필드 `hidden`(boolean)·`hiddenAt`(date)을 둔다(공통 세기·정리가 쓴다). 사용자 사전·동의어 파일은 `search/common/`의 것을 함께 쓴다. 새 용어를 더하면 모든 대상이 재색인한다.
2. `search.<대상>.out`에 `<대상>IndexDefinition`(대상 이름·alias·지표 대상 값)과 `<대상>IndexSetup extends IndexSetup`(생성자만)을 둔다.
3. 문서 저장소는 `DocumentWrites`를 써서 오류 처리·bulk 결과를 맞춘다. 원본 version 조건부 쓰기 script와 문서 모양은 대상이 정한다.
4. `IndexLoader`(원본 전체로 새 index 채우기: 보이는 문서 + 숨김 문서, 거절 문서는 건너뛰고 셈)와 `IndexCatchUp`(교체 뒤 따라잡기)을 구현하고, `<대상>Reindexer`에서 공통 `Reindexer`에 넘긴다. 락은 `SearchJob`에 새 키(그 작업을 더한 이슈 번호 × 1000 + 순번)를 더한다.
5. `<대상>TombstoneCleanup extends TombstoneCleanup`(생성자만, 새 락 키)을 두고 배치에 붙인다.
6. 검색 Facade는 `SearchMetrics.forTarget(대상)`을 받고, 0건 검색어는 `ZeroResultKeywords.record(검색어, 검색자 키)`로 센다.
7. 공통 흐름은 `ReindexerTest`가 이미 시험용 대상으로 확인한다. 대상 테스트는 채우기·따라잡기 부품과 golden query를 본다.

## 4. index

- 이름 `search-influencers-<설정 해시 12자>`, 검색·쓰기는 alias `search-influencers`로만 한다(쓰기는 `require_alias`라 alias가 없으면 실패하고, ES가 같은 이름의 index를 멋대로 만들지 않는다).
- 원본은 레포 파일 `boundedContext/search/src/main/resources/search/`의 `influencers/index.json`(설정·mapping)과, 모든 대상이 함께 쓰는 `common/user-dictionary.txt`(사용자 사전)·`common/synonyms.txt`(동의어)다(#868, L17).
- 동의어 묶음 이름은 아직 `search-influencers-synonyms`다. 이름이 index 본문에 들어가 바꾸면 설정 해시가 바뀌므로, 공통 이름으로 바꾸는 일은 사용자 사전에 강의 용어를 더해 어차피 재색인하는 강의 검색 PR에서 함께 한다(#868 R4). 지금 운영 index는 `search-influencers-d656dfa35eee`이고 `InfluencerIndexDefinitionTest`가 이 값을 고정한다(사전·mapping을 바꾸면 테스트 값도 고치고 PR에 재색인을 적는다).
- 설정 해시는 mapping·분석기·사용자 사전으로만 만든다. **동의어는 넣지 않는다**(검색할 때만 쓰여 재색인이 필요 없다).
- 기동하면(다른 스레드): 동의어 묶음(`search-influencers-synonyms`)이 파일과 다르면 묶음 전체를 PUT → alias가 없거나 다른 해시의 index를 가리키면 처음 적재·재색인을 한다(7장). 10분마다 다시 확인한다.
- **alias는 처음 적재가 index를 다 채우고 확인한 뒤에만 붙인다.** 이벤트 소비·보정이 alias가 없다고 빈 index에 alias를 붙이지 않는다(붙이면 "alias 있음, 해시 같음"으로 보여 처음 적재가 돌지 않는다).
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
- **alias가 없으면(처음 적재 전) 이벤트를 건너뛴다**(DEBUG, 재시도·DLT 없음, #773 Q1). 처음 적재가 시각 T 뒤에 회원 목록을 읽고, alias를 붙인 뒤 T − 1분부터 따라잡으므로 잃는 변경이 없다. 증분 보정·통계 동기화·tombstone 정리도 그 회차를 건너뛴다(INFO).
- pod가 떠 있는 동안 ES를 새로 만들어 alias가 사라져도 같다. 문서 쓰기·읽기가 alias 없음(404 `index_not_found_exception`)을 `IndexNotReadyException`으로 바꿔 건너뛰고(DLT로 보내지 않는다), 다음 확인(7장)이 동의어부터 다시 맞춰 처음 적재한다(#773 리뷰).

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
- **바깥에서 시작 시각 정하기**: `MemberChangeReconciler.runFrom(시각)`은 커서를 그 시각(회원 ID 0)으로 옮기고 한 번 돈다. #773이 새 index로 alias를 바꾼 뒤 "시각 T 이후 따라잡기"에 쓴다. 겹쳐 읽기를 빼지 않으므로 여유는 부르는 쪽이 넣는다(T − 1분). 정기 회차와 같은 락이다. 결과는 `CatchUp`이다: 끝까지 따라잡았으면 `CAUGHT_UP`, 다른 회차가 락을 쥐고 있어 돌지 못했으면 `BUSY`(커서 그대로라 부르는 쪽이 다시 부른다), 중간에 실패했으면 `FAILED`(커서는 그 시각 뒤라 다음 정기 회차가 이어 간다). 락 없이 도는 `reconcile`은 바깥에서 부르지 않는다(package-private).
- 한계
  - 보정 건수는 페이지 앞에서 한 번 읽은 문서와 비교한다. 그 페이지(최대 500명)를 반영하는 몇 초 사이에 같은 회원의 이벤트가 먼저 반영되면(이벤트 도착 직전에 보정이 읽었거나, 같은 회원이 몇 초 안에 두 번 바뀜) 놓친 이벤트가 아닌데도 보정으로 셀 수 있다.
  - 회차가 중간에 실패하면 이미 반영한 페이지의 보정 건수는 지표에 남지 않고, 다음 회차에는 그 회원들이 `already_applied`로 보인다. 장애가 있던 시간의 보정 건수는 적게 잡힐 수 있으므로 회원별 INFO 로그(`회원 이벤트를 놓친 검색 문서를 보정했다`)로 확인한다.
  - 회원마다 ES 요청 1번이다(이벤트와 같은 반영 코드). 평소 10분 변경은 적고 장애 뒤 수천 명이어도 수 초다. 길어지면 `reconcile.duration`으로 보고 bulk를 검토한다.
  - 저장부터 커밋까지 1분 넘게 걸린 회원 변경은 겹쳐 읽기 밖이라 놓칠 수 있다. 그때는 회원 이벤트가 반영한다(#769).
  - 회원 앱이 여러 대가 되면 서버끼리 시계 차이도 1분 안이어야 한다(#769).

## 7. 처음 적재와 무중단 재색인 (#773)

이미 있는 인플루언서를 index에 처음 채우고, 사용자 사전·mapping이 바뀌면 검색을 멈추지 않고 새 index로 옮긴다. 사람이 손대지 않아도 된다.

흐름은 공통 `Reindexer`가 하고(#868), 인플루언서는 채우기(`InfluencerIndexLoader`: 아래 3)와 따라잡기(`MemberChangeReconciler.runFrom`: 아래 6)만 넘긴다. 끝 로그는 `target`·`visible`(넣은 보이는 문서 수)을 남기고, 통계를 쓰지 못한 회원 수(`statsFailed`)는 채우기 부품이 따로 INFO 한 줄로 남긴다.

### 언제 하나

- 기동 뒤 다른 스레드에서 한 번(`SearchIndexStartup`, 기동을 막지 않는다), 그 뒤 10분마다 확인한다(`SearchBatch`, `hapbang.search.reindex-check-interval`). 둘 다 재색인 실행기(`SearchConfig.searchReindexExecutor`, 가상 스레드)로 넘긴다. 적재가 몇 분 걸려도 앱 공용 스케줄러 스레드 2개를 붙잡지 않고, Boot builder로 만들어 10분 확인의 `traceId`가 재색인 로그에 이어진다(`docs/dev/logging.md` 스레드 경계, PR #845 리뷰). 한 pod만(`JobLock` 773_001). 배포 중 두 pod가 겹쳐도 한 번만 돌고, 락을 놓친 pod는 건너뛴다.
- 아래 둘 중 하나면 한다. 그 밖에는 alias를 한 번 읽고 끝난다.
  - alias가 없다(처음 배포, ES를 새로 만듦): **처음 적재**(`reason=first_load`)
  - alias가 다른 설정 해시의 index를 가리킨다(mapping·분석기·사용자 사전이 바뀜): **재색인**(`reason=settings_changed`)
- 동의어는 해시에 없어 바꿔도 재색인하지 않는다(4장).
- **alias가 가리키는 index가 이 pod가 뜬 뒤에(시계 차이 여유 1분을 넘겨) 만들어졌으면 건너뛴다**(WARN, PR #845 리뷰 Q4). 나중에 뜬 다른 배포가 만든 더 새 설정이기 때문이다. 사용자 사전을 바꿔 배포할 때 아직 내려가지 않은 옛 pod가 새 index를 옛 설정으로 되돌리지 않는다. 일부러 옛 코드로 되돌린 롤백은 새 index보다 나중에 뜨므로 되돌린다. 만든 시각은 ES의 `index.creation_date`, pod 시각은 재색인 빈이 만들어진 시각이다. 두 시각은 서로 다른 서버의 시계라 1분 여유를 두고 비교한다(PR #845 재리뷰).
  - 한계: pod는 "배포 중이라 곧 내려갈 옛 pod"인지 "배포를 되돌려 남은 pod"인지 구분할 수 없다. 지금 설정(pod 1개, 새 pod가 Ready가 되면 옛 pod를 내림)에서는 alias를 옮기는 시점(재색인 수십 초 뒤)에 옛 pod가 이미 없어, 되돌리면 옛 버전 pod가 새로 떠서 되돌린다. pod를 여러 개로 늘리거나, 새 pod가 Ready가 되지 못한 채 재색인을 마친 상태에서 되돌리면 alias가 새 설정에 남을 수 있다. 건너뛴다는 WARN이 10분마다 반복되면 배포를 되돌린 상태인지 확인하고 `kubectl --context hapbang-k3s -n hapbang-prod rollout restart deploy/auxiliary-app`으로 pod를 다시 띄운다(새로 뜬 pod가 자기 설정으로 옮긴다).
  - 옛 pod가 배포 도중 재시작되면 새로 뜬 것으로 보여 되돌릴 수 있지만, 새 pod가 다음 확인에서 다시 옮긴다.
- 실패하면 다음 확인(10분 뒤)에 처음부터 다시 한다(#773 Q2). ES·회원 앱이 잠깐 멈춘 경우 사람 없이 복구된다. 같은 이유로 계속 실패하면 10분마다 전체 적재를 다시 돌리므로, ERROR 로그와 지표로 보고 부하가 문제가 되면 "연달아 N번 실패하면 멈춤"을 붙인다.
- 데이터가 어긋났는데 설정은 그대로일 때 전체를 다시 채우고 싶으면, `index.json`을 바꿔(예: mappings `_meta`에 값을 더함) 설정 해시를 바꾼 뒤 배포한다(마지막 수단).

### 순서

1. 남은 index(alias가 가리키지 않는 `search-influencers-*`: 실패한 지난 시도, 지우지 못한 옛 index)를 지운다.
2. 시각 T를 기록하고, 지금 설정의 새 index를 **alias 없이** 만든다.
3. 회원 목록 내부 API(모든 회원, `docs/api/internal-members.md` 1장)를 500명씩 끝까지 읽어 bulk로 넣는다. 이벤트와 같은 회원 version 조건부 script다. 정상 인플루언서는 보이는 문서, **그 밖의 회원(일반 유저·강등·정지·탈퇴)은 숨김 문서**(version과 숨긴 시각만, 개인정보 없음, 7일 뒤 정리)다. 숨김 문서가 있어야 교체 뒤 늦게 처리된 옛 "보이기" 이벤트가 숨긴 회원을 다시 보이게 하지 못한다(5장과 같은 보호, #773 리뷰 Q3). 회원 정보가 빠졌거나 ES가 그 회원 값 때문에 거절한 회원은 WARN을 남기고 건너뛴다(증분 보정과 같다. 멈추면 처음 적재가 영영 끝나지 않는다). 그 밖의 쓰기 실패(장애)는 멈춘다. 이어서 통계 API(#769)를 끝까지 읽어 채운다(10분 통계 동기화와 같은 코드, 통계 일부 실패는 다음 동기화가 채운다).
4. 새 index를 refresh하고, **넣은 정상 인플루언서 수와 보이는 문서 수를 비교한다.** 다르면 멈춘다. 원본 수는 적재하며 읽은 수다(통계 API 수와 비교하면 그사이 승급·강등된 회원 때문에 멀쩡해도 다를 수 있다).
5. alias를 **한 요청으로** 옮긴다(`remove` 옛 index + `add` 새 index). ES가 한 번에 적용하므로 검색이 alias를 잃거나 두 index를 함께 보는 순간이 없다. 이 순간까지 검색·이벤트·보정은 옛 index를 쓴다.
6. 증분 보정을 **T − 1분부터** 한 번 돈다(`runFrom`). 재색인 중 이벤트는 옛 index로 갔으므로 그사이 바뀐 회원(분야 수정, 강등)을 새 index에 반영한다. T 전 변경은 3이 읽었고, 1분은 늦게 커밋된 변경 여유다(6장 겹쳐 읽기와 같다). 정기 보정이 락을 쥐고 있으면(`BUSY`) 5초마다 최대 5분 다시 부른다.
7. 옛 index를 지운다(지우지 못하면 다음 확인 때 1에서 지운다).

- 1~5에서 실패하면 alias는 옛 그대로 두고 만들던 새 index를 지운다. `[SEARCH_REINDEX_FAILED]` ERROR 한 줄(예외 포함)과 지표(`outcome=failure`)를 남긴다. 사용자 사전·mapping 오류로 ES가 새 index를 거절하는 경우(2)도 같다(10분마다 같은 이유로 실패하므로 사람이 봐야 한다, PR #845 리뷰). 상태를 읽지 못하는 ES 일시 장애는 WARN이다. 처음 적재면 alias가 없는 채로 남아 검색은 503, 이벤트는 계속 건너뛴다.
- alias 교체 응답만 실패하고(시간 초과) ES에는 적용됐을 수 있으므로, 지우기 전에 alias를 다시 읽는다. 새 index를 가리키면 교체한 것으로 보고 6으로 이어 가고, 읽지 못하면 새 index를 지우지 않고 다음 확인에 맡긴다(지우면 alias도 사라진다. 교체되지 않았다면 다음 확인이 1에서 지운다).
- 6이 끝내 `BUSY`이거나 `FAILED`면 `[SEARCH_REINDEX_CATCH_UP_FAILED]` ERROR와 `outcome=catch_up_failed`를 남긴다. `FAILED`는 커서가 이미 T − 1분 뒤에 있어 다음 정기 보정이 이어 간다. `BUSY`는 커서를 옮기지 못했으므로 재색인 중 바뀐 회원이 빠졌을 수 있다(5분 넘게 끝나지 않는 보정은 그 자체로 장애다).
- ES가 동의어 묶음을 내용 때문에 거절하면(400 `action_request_validation_exception`, 예: `롤 => 리그 => LoL`처럼 `=>`가 둘) 재색인하지 않고 `[SEARCH_SYNONYMS_REJECTED]` ERROR와 `hapbang.search.synonyms.rejected`를 남긴다. `synonyms.txt`를 고쳐 배포해야 풀린다. 문서 쓰기는 동의어와 상관없어 지난 묶음으로 계속한다. 거절되는 규칙은 대개 CI의 `InfluencerIndexSetupTest`(실제 ES에 레포 파일을 올림)가 병합 전에 잡는다. `=> 롤`처럼 ES가 받고 무시하는 규칙도 있어 동의어를 고친 PR은 `_analyze`로 결과를 확인한다(PR #845 재리뷰).
- alias가 지금 설정 index와 다른 index를 함께 가리키면(사람이 손으로 바꾼 경우뿐) 지울 수 없어 `[SEARCH_REINDEX_FAILED]`로 멈춘다. alias를 한 index만 가리키게 고친다.
- 사람이 봐야 하는 실패라 ERROR에 alert 표식을 붙였지만 alert 규칙은 아직 두지 않았다(#772 Q2와 같이 지표·로그로 본다).

### 처음 적재하는 동안 (#773 Q1)

alias가 없는 동안은 쓸 곳이 없다. 이벤트 소비·증분 보정·통계 동기화·정리는 `IndexNotReadyException`을 받아 실패가 아니라 "건너뜀"으로 처리한다(5장). 검색 API는 503 `SEARCH_UNAVAILABLE`이다(처음 배포 때 한 번). 건너뛴 변경은 3(T 뒤 회원 목록)과 6(T − 1분부터 따라잡기)이 반영한다. 적재가 실패하면 alias가 없어 어긋난 index가 남지 않고, 다음 확인 때 전체를 다시 읽는다.

### 검토했지만 넣지 않은 것

- Kafka offset을 기록한 뒤 다시 읽기, 두 index에 함께 쓰기(dual write): consumer 제어와 경쟁 상태가 생긴다. 증분 보정 따라잡기로 같은 일을 더 단순하게 한다.
- 관리자 재색인 API: 처음 적재·설정 변경은 자동이고, 유실은 증분 보정이 맡는다. 무거운 작업을 밖에 열 이유가 없다.
- 처음 적재 때 빈 index에 alias를 먼저 붙이고 채우기: 검색이 부분 결과라도 나오지만, 수가 달라도 alias를 되돌릴 수 없고 처음 적재와 재색인이 다른 길이 된다(#773 Q1).

## 8. 검색 API

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

## 9. 지표와 로그

| 지표 | 뜻 |
| --- | --- |
| `hapbang.search.requests{target, outcome}` | 검색 요청 수. outcome = ok·empty(0건)·error |
| `hapbang.search.es.latency{target}` | ES 검색 시간(histogram, p95) |
| `hapbang.search.zero.results{target}` | 필터 없이 검색어만으로 0건인 요청 수 |
| `hapbang.auxiliary.job.lock.skipped{job}` | 락 때문에 건너뛴 회차(global) |
| `hapbang.search.reconcile.members{kind}` | 증분 보정이 반영한 회원 수(6장 표). inserted·hidden·overwritten이 놓친 이벤트이고, 모든 kind의 합이 읽은 회원 수다 |
| `hapbang.search.reconcile.duration{outcome}` | 보정 1회 소요 시간. outcome = success·failure. 길어지면 규모를 다시 판단한다 |
| `hapbang.search.reconcile.lag` | 지금 − 마지막으로 끝까지 읽은 시각(초). 보정이 멈추면 커진다. DB 커서를 읽어 어느 pod에서 봐도 같다. 끝까지 읽은 적이 없으면 값이 없다 |
| `hapbang.search.reindex.duration{target, reason, outcome}` | 처음 적재·재색인 1회 소요 시간(7장). `target` 태그는 #868에서 더했다. reason = first_load·settings_changed, outcome = success·failure(alias 그대로)·catch_up_failed(옮겼지만 따라잡기를 못 함) |
| `hapbang.search.reindex.documents{target, reason}` | 처음 적재·재색인으로 새 index에 넣은 보이는 문서 수(인플루언서는 정상 인플루언서 수) |
| `hapbang.search.synonyms.rejected` | ES가 동의어 묶음을 내용 때문에 거절한 횟수(10분 확인마다). 묶음이 하나라 대상 태그가 없다. 0이 아니면 `synonyms.txt`를 고친다 |
| `hapbang.search.consistency.difference{target}` | 원본 정상 인플루언서 수 − 보이는 문서 수(6장 정합성 점검). DB의 점검 결과에서 읽어 어느 pod에서 봐도 같다. 점검한 적이 없거나, 마지막 회차에 점검하지 못했거나, 점검한 지 25분(점검 주기 두 번)이 지났으면 값이 없다. alias로 세므로 #773 재색인은 alias를 한 요청으로 바꿔 두 index를 함께 가리키는 순간이 없어야 한다 |
| `kafka_consumer_fetch_manager_records_lag` | 회원 이벤트 소비 lag(Kafka client 지표). Kafka dashboard "그룹별 lag"에 `auxiliary-app.search-member`로 보인다 |

- 회원 ID는 지표 태그에 넣지 않는다. 문서 수 차이 alert는 두지 않았다(#773 처음 적재 전에는 차이가 늘 0이 아니다, #772 Q2).

- 0건 검색어는 사전·동의어를 늘릴 근거로 모은다(#771 Q9, PR #828 리뷰 뒤 Q18). 검색어는 남의 이름·닉네임·연락처일 수 있어 조건을 둔다.
  - **필터 없이 검색어만으로 0건**인 요청만 센다. 필터가 좁아 0건인 것은 사전 문제가 아니다(지표 `zero.results`도 같은 기준).
  - **서로 다른 회원 2명 이상이 찾은 말만** 로그에 나온다. 한 사람만 입력한 말은 남지 않는다. 회원 ID는 이 판단에만 메모리에서 쓰고(검색어마다 2명까지) 남기지 않는다.
  - 이메일·URL·전화번호 모양(숫자 8개 이상)과 30자를 넘는 말은 세지 않는다.
  - 요청에서는 세기만 하고 10분마다 배치 스레드가 `여러 회원이 찾았지만 결과가 없던 검색어를 모았다 keywords={…}` 한 줄(상위 100개)로 남긴다. 그래서 요청의 `userId`·`traceId`가 붙지 않는다. pod마다 따로 세고, 앱이 내려가면 남기지 않은 주기분은 사라진다.
- rate limit은 두지 않았다. 위 요청 수·p95와 k6 측정을 보고 정한다(#767).

## 10. 로컬 실행

1. `docker compose -f apps/auxiliary-app/compose.yaml up -d` — ES는 nori를 설치한 이미지다(처음 한 번 빌드).
2. 회원 앱(`./gradlew :member-app:bootRun`, 8081)을 띄운다. 통계 동기화가 회원 내부 API를 부른다.
3. `./gradlew :auxiliary-app:bootRun`
4. `curl 'localhost:8087/api/v1/search/influencers?keyword=메이플' -H 'X-User-Id: 1' -H 'X-User-Role: USER'`

## 11. 테스트

ES는 검색 전용 Testcontainers(`SearchElasticsearchTestcontainersConfig`, 9.5.5 + nori, 보안 끔)를 JVM에서 하나만 띄워 클래스끼리 차례로 쓴다. ES를 쓰는 테스트는 시작할 때 `SearchElasticsearch.reset`으로 검색 index와 동의어 묶음을 지우고, 문서를 쓰는 테스트는 `SearchElasticsearch.createIndex`로 "처음 적재가 끝난 상태"(index + alias)를 만든다. 첫 실행은 이미지를 빌드한다(외부망, 약 1분).

| 테스트 | 보는 것 |
| --- | --- |
| `InfluencerDocumentStoreTest` | 순서 역전·중복·null version, 통계 갱신 뒤 회원 이벤트, tombstone(문서 없을 때 포함), 정리, require_alias, 이름을 정한 새 index에 bulk 넣기·통계·세기 |
| `InfluencerIndexSetupTest` | 동의어 묶음(ES를 새로 만든 뒤 다시 맞추기, ES가 거절하면 `SynonymsRejectedException`이고 쓰기 준비는 계속 포함), alias가 없으면 만들지 않고 `IndexNotReadyException`, 설정이 바뀐 index 그대로 쓰기, 상태·남은 index, alias 원자 교체(거절되면 그대로), ES 장애 |
| `ReindexerTest` | 공통 재색인 흐름을 시험용 대상(`search-reindexer-test`)과 가짜 채우기·따라잡기 부품으로: 처음 적재(alias·T − 1분 따라잡기·`target` 태그 지표), 설정이 같으면 안 함, 수가 다르면 alias 안 붙이고 새 index 삭제, 따라잡기 BUSY면 기다림(#868) |
| `InfluencerReindexerTest` | 공통 흐름 + 인플루언서 부품으로 처음 적재(인플루언서 보이게·그 밖 숨김 문서·통계·alias·커서 T − 1분), 해시가 같으면 안 함, 설정이 바뀌면 옮기고 옛·남은 index 삭제, 재색인 중 바뀐 회원 따라잡기, **재색인 내내 검색 실패 없음**, 수가 다르거나 bulk가 실패하거나 새 index를 만들지 못하면 alias 그대로·새 index 삭제, 두 pod가 동시에 시작해도 한 번, 따라잡기가 BUSY면 기다림, 거절된 회원 건너뜀, alias 교체 응답만 실패, pod가 뜬 채 ES를 새로 만듦, 롤링 배포 중 옛 pod는 더 새 index를 되돌리지 않음(롤백은 되돌림, 시계 차이 1분 안은 되돌림), 동의어 거절이면 재색인 안 함·지표 |
| `SearchReindexWiringTest` | 기동·10분 확인이 재색인 실행기를 주입받아 `search-reindex-` 스레드로 넘김(스케줄링·기동 재색인을 켠 컨텍스트, 재색인은 가짜) |
| `SearchMemberEventListenerTest` | 실제 Kafka로 가입·변경 반영, 다른 타입 건너뜀, DLT, 처음 적재 전 건너뜀 |
| `InfluencerStatsSyncTest` | 통계 반영·noop·평균 지움·문서 없음·숨김·여러 페이지·분야 이름, 정합성 점검(차이 0·양수·음수, 세기 실패) |
| `MemberChangeReconcilerTest` | 처음 적재 전은 실패 지표에 넣지 않음(ES 장애는 넣음), 증분 보정: 처음은 지금부터, 분야 변경·강등·승급을 놓친 경우, 1분 겹쳐 읽기, 이벤트와 둘 다 반영, version만 오름, 일반 유저, 멈춘 뒤 이어 가기, 바깥 시작 시각, 깨진 회원·ES가 거절한 회원 건너뜀, 다음 커서 없는 응답, `runFrom` 성공·실패 |
| `IndexDefinitionTest`, `InfluencerIndexDefinitionTest` | 대상 이름으로 alias·index 이름, 동의어는 해시에 안 들어감, 사전 규칙, 레포 파일 읽기, **운영 index와 같은 설정 해시**(#868) |
| `DocumentWritesRejectionTest`, `ZeroResultKeywordsTest` | 그 문서 값 때문의 거절만 건너뜀, 0건 검색어(서로 다른 검색자 2명, 연락처 모양 제외, 비회원 IP 해시 키) |
| `SyncCursorStoreTest`, `ConsistencyCheckStoreTest` | 커서·점검 결과 저장(PostgreSQL 컨테이너) |
| `SearchModuleIntegrationTest` | 모듈만으로 컨텍스트, search schema, 검색 전용 client(nori), 재색인 실행기가 가상 스레드에서 trace를 이어받음 |
| `SearchSyncMetricsTest` | 보정 지연(끝까지 읽은 시각 기준), 문서 수 차이(DB, 25분 지나면 비움), kind별 건수 |
| `ApiV1SearchControllerTest` | 응답, 필터, 정렬, 동점 페이지 넘김, 400·401, 0건 검색어 |
| `RatingBaselineTest`, `InfluencerDocumentStoreBulkFailureTest` | 평균 C 실패 뒤 1분 대기, bulk 일부 실패 |
| `GoldenQueryTest` | 대표 검색어 23개 순위, 알려진 한계(G01b), 계수 격자(`build/golden-query.md`) |

## 12. 알려진 한계

- 사전에 없는 지어낸 닉네임은 잘게 쪼개져 다른 검색어에 걸릴 수 있다(예: "롤러스케이터"가 "롤" 검색에 걸림, G01b). 0건 검색어처럼 근거가 쌓이면 사전을 보강하거나 하위 필드를 더한다.
- 동의어 일치와 원문 일치의 가중치가 같다(G05). 원문 일치를 위로 올리려면 동의어 없는 하위 필드를 작은 가중치로 더한다.
- 승급한 회원의 채널·팔로워·평점은 다음 통계 동기화(최대 10분)까지 비어 있다. 재색인 중 승급해 따라잡기로 들어온 회원도 같다.
- 처음 적재·재색인은 한 pod가 회원 목록(모든 회원)을 500명씩 읽어 페이지마다 bulk로 넣는다. 일반 유저도 숨김 문서로 들어가 문서 수는 회원 수만큼이고 7일 뒤 정리된다. 회원 수만 명까지는 수십 초로 본다. 훨씬 커지면 `reindex.duration`을 보고 bulk 크기·refresh 끄기를 검토한다.
- #771·#772 코드로 이미 alias가 붙은 로컬 ES는 해시가 같아 처음 적재가 돌지 않는다(운영은 #860 전까지 검색이 꺼져 있어 index가 없다). 로컬의 `search-influencers-*`를 지우고 띄운다.
