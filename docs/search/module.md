# 검색(search) 모듈

auxiliary-app 안의 검색 모듈(`:search`, 패키지 `com.hapbang.auxiliary.search`)이다. 로그인한 회원이 검색어·콘텐츠 분야·팔로워·출연 평점으로 인플루언서를 찾는다. 결정의 배경은 부모 이슈 #767, 인프라 방향은 [검색 인프라](../architecture/infrastructure/search.md)에 있다.

- 기준: #771(검색 1). 증분 보정·정합성 지표는 #772(검색 2), 처음 적재·무중단 재색인은 #773(검색 3)이 더한다.
- 이 문서와 코드가 다르면 코드가 맞다. 확인한 뒤 이 문서를 고친다.

## 1. 지금 범위

| 한다 (#771) | 아직 안 한다 |
| --- | --- |
| 회원 이벤트로 문서 넣기·숨기기(회원 version 조건부 쓰기, tombstone) | 이벤트를 놓친 회원 바로잡기(증분 보정, #772) |
| 10분마다 통계(채널·팔로워·평점)와 분야 이름 맞추기 | 원본·index 문서 수 비교 알림(#772) |
| 7일 지난 tombstone 정리 | 이미 있는 인플루언서 처음 적재, 설정 해시가 바뀐 index 재색인(#773) |
| `GET /api/v1/search/influencers`, 지표 | 강의·모집 검색, 통합 검색 |

이 이슈만으로는 이미 있는 인플루언서가 index에 없다(이벤트가 온 회원만 들어간다). FE 연결은 #773 뒤에 한다.

## 2. 검색 기능 스위치 (임시)

`hapbang.search.enabled`(기본 `false`)가 켜져야 검색 모듈의 빈(ES client, 이벤트 소비자, 배치, API)이 하나라도 뜬다. 운영 ES의 검색용 API key(`ES_SEARCH_API_KEY`, #806)가 생기기 전에 병합하려고 둔 장치다. 꺼져 있어도 auxiliary-app(챗봇·알림)은 그대로 뜨고 `search` schema만 생긴다.

**#806이 끝나면 스위치를 켜는 것이 아니라 지운다**: `ConditionalOnSearchEnabled`와 그 사용처, `application.yaml`의 `hapbang.search.enabled`, 모듈 테스트 설정의 `enabled: true`, `AuxiliaryApplicationTest#searchSwitchOff`.

## 3. 구조

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| config | `SearchElasticsearchConfig` | 검색 전용 `ElasticsearchClient`(빈 이름 `searchElasticsearchClient`, 기본 후보 아님). 챗봇은 Boot 자동 설정 client(`ES_API_KEY`)를 쓰고 검색은 같은 cluster에 `ES_SEARCH_API_KEY`로 따로 붙는다 |
| in | `SearchMemberEventListener` | 회원 이벤트 소비(`member.events.v1`, 그룹 `auxiliary-app.search-member`) |
| in | `SearchBatch` | 통계 동기화(끝난 뒤 10분), tombstone 정리(매일 04:30), 0건 검색어 로그(10분) |
| in | `SearchIndexStartup` | 기동 뒤 다른 스레드에서 index·동의어 준비 |
| in | `ApiV1SearchController`·`ApiV1SearchSpec` | 검색 API |
| app | `InfluencerMemberIndexer` | 회원 정보 → 넣기 또는 숨기기 |
| app | `InfluencerStatsSync` | 통계·분야 이름 동기화(한 pod만, `JobLock` 771_002) |
| app | `TombstoneCleanup` | tombstone 정리(한 pod만, `JobLock` 771_001) |
| app | `InfluencerSearchFacade` | 검색 조건 검사, 지표, 0건 검색어 |
| app | `ContentCategoryNames`, `RatingBaseline` | 분야 코드 → 이름, 평점 전체 평균 C. 둘 다 pod마다 10분 캐시 |
| out | `InfluencerIndexDefinition`, `InfluencerIndexSetup` | index 정의(레포 파일)와 준비 |
| out | `InfluencerDocumentStore` | 문서 쓰기(회원·통계·분야 이름·정리) |
| out | `InfluencerSearcher` | 검색 쿼리, 평균 C 집계 |
| out | `MemberApiStatsSource` | 회원 내부 API(통계·분야 목록, #769) |

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

## 6. 10분 동기화와 정리

- 통계 동기화(한 pod만): 분야 목록을 다시 읽어 분야가 있는 모든 문서의 이름을 맞춘다(update_by_query, 바뀐 것만) → 인플루언서 통계(#769)를 500명씩 끝까지 읽어 bulk 부분 갱신한다. 값이 같거나 숨긴 문서는 noop, 문서가 없는 회원은 건너뛴다(넣는 것은 #772). 결과는 INFO 한 줄(`pages·updated·noop·missing·categoryDocuments`).
- 분야 이름 목록과 평점 전체 평균 C는 pod마다 직접 구한다(#771 Q8). 동기화는 한 pod에서만 돌아 다른 pod에 값을 줄 수 없다. 분야 목록을 읽지 못하면 가진 목록으로 색인하고 1분 동안 다시 묻지 않는다.
- tombstone 정리(한 pod만, 매일 04:30 KST): `hiddenAt`이 7일(회원 이벤트 DLT 보존 기간)보다 오래된 숨김 문서를 지운다.

## 7. 검색 API

`GET /api/v1/search/influencers` (로그인 회원 전체. 비회원 401)

| 파라미터 | 뜻 |
| --- | --- |
| `keyword` | 최대 100자. 닉네임 ×3, 채널 이름 ×3, 분야 이름 ×2, 자기소개 ×1(`best_fields`, tie_breaker 0.3) |
| `contentCategories` | 분야 코드 최대 10개. 하나라도 가진 사람(filter, OR) + 많이 맞을수록 위(should) |
| `verified` | true면 인증만, false면 인증 안 된 사람만, 없으면 모두 |
| `minFollowers`, `minRating` | 0 이상, 0~5. 평점 조건은 원래 평균이라 리뷰 없는 사람은 빠진다 |
| `page`, `size`, `sort` | size 기본 20·최대 100. sort는 `followers,desc`·`rating,desc`만(그 밖 400 `UNSUPPORTED_SORT`) |

- 순서: 기본은 검색어나 분야가 있으면 점수 → 팔로워, 둘 다 없으면 팔로워. 검색어가 있으면 점수 = 텍스트 관련도 × (1 + a·log10(1+팔로워)) × (인증이면 v). 평점순은 베이지안 평균 (n×R + m×C)/(n+m) → 팔로워.
- 계수: a=0.05, v=1.05, m=5(`hapbang.search.ranking.*`). golden query(`GoldenQueryTest`) 23개가 모두 통과하는 값이다. a의 통과 구간은 0.02~0.1이다(0은 팔로워가 순서를 못 가르고, 0.5 이상은 팔로워가 텍스트 일치를 뒤집는다).
- 앞에서 1,000건까지(`page × size + size ≤ 1000`, 넘으면 400 `PAGE_TOO_DEEP`). `totalElements`도 1,000까지만 센다.
- ES에 닿지 못하면 503 `SEARCH_UNAVAILABLE`. 회원 앱 인플루언서 목록(`GET /api/v1/members/influencers`)은 그대로 쓸 수 있다.
- 응답 한 줄: `id`, `nickname`, `role`, `contentCategories[{code, name}]`, `bio`, `channelName`(대표), `followerCount`, `appearanceRating{average, count}`. 프로필 사진은 없다(#770 API로 FE가 채운다).

## 8. 지표와 로그

| 지표 | 뜻 |
| --- | --- |
| `hapbang.search.requests{target, outcome}` | 검색 요청 수. outcome = ok·empty(0건)·error |
| `hapbang.search.es.latency{target}` | ES 검색 시간(histogram, p95) |
| `hapbang.search.zero.results{target}` | 검색어가 있는데 0건인 요청 수 |
| `hapbang.auxiliary.job.lock.skipped{job}` | 락 때문에 건너뛴 회차(global) |

- 0건 검색어는 **누가 검색했는지 빼고** 모은다(#771 Q9): 요청에서는 메모리에 세기만 하고, 10분마다 배치 스레드가 `결과가 없던 검색어를 모았다 keywords={…}` 한 줄(상위 100개)로 남긴다. 사전·동의어를 늘릴 근거로 쓴다. pod마다 따로 세고, 앱이 내려가면 남기지 않은 주기분은 사라진다.
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
| `InfluencerStatsSyncTest` | 통계 반영·noop·평균 지움·문서 없음·숨김·여러 페이지·분야 이름 |
| `ApiV1SearchControllerTest` | 응답, 필터, 정렬, 400·401, 0건 검색어 |
| `GoldenQueryTest` | 대표 검색어 23개 순위, 알려진 한계(G01b), 계수 격자(`build/golden-query.md`) |

## 11. 알려진 한계

- 사전에 없는 지어낸 닉네임은 잘게 쪼개져 다른 검색어에 걸릴 수 있다(예: "롤러스케이터"가 "롤" 검색에 걸림, G01b). 0건 검색어처럼 근거가 쌓이면 사전을 보강하거나 하위 필드를 더한다.
- 동의어 일치와 원문 일치의 가중치가 같다(G05). 원문 일치를 위로 올리려면 동의어 없는 하위 필드를 작은 가중치로 더한다.
- 승급한 회원의 채널·팔로워·평점은 다음 통계 동기화(최대 10분)까지 비어 있다.
