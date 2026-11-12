# 검색(search) 모듈

auxiliary-app 안의 검색 모듈(`:search`, 패키지 `com.hapbang.auxiliary.search`)이다. 로그인한 회원이 검색어·콘텐츠 분야·팔로워·출연 평점으로 인플루언서를 찾고, 비회원을 포함한 누구나 검색어로 판매 중인 강의를 찾는다. 결정의 배경은 부모 이슈 #767, 인프라 방향은 [검색 인프라](../architecture/infrastructure/search.md)에 있다. 강의 검색(부모 #881)은 index 채우기(#882, 13장), 검색 API(#883, 14장), 보정·처음 적재·재색인(#884, 15장)까지 왔다.

- 기준: #771(검색 1), #772(검색 2: 증분 보정·정합성 지표), #773(검색 3: 처음 적재·무중단 재색인), #868(공통 index 관리 코드 분리, 3장), #882(강의 검색 1: 사본과 강의 색인, 13장), #883(강의 검색 2: 검색 API, 14장), #909(강의 검색 4: 가격 표시와 가격 정렬, 13·14장).
- 이 문서와 코드가 다르면 코드가 맞다. 확인한 뒤 이 문서를 고친다.

## 1. 지금 범위

| 한다 (#771, #772, #773, #882, #883, #909, #884) | 아직 안 한다 |
| --- | --- |
| 회원 이벤트로 문서 넣기·숨기기(회원 version 조건부 쓰기, tombstone) | 원본·index 문서 수 차이 alert(지표만 낸다, #772 Q2) |
| 강의·리뷰·회원 이벤트로 강의 문서 넣기·숨기기·평점·판매자 반영(#882, 13장), 가격 칸·가격 정렬(#909) | 가격 구간 필터(FE 요청 때) |
| 강의·리뷰·판매자 사본 보정, 강의 index 처음 적재·무중단 재색인(#884, 15장) | 강의·리뷰 변경분 API(데이터가 커져 전체 읽기 보정이 길어지면, 15장) |
| 10분마다 통계(채널·팔로워·평점)와 분야 이름 맞추기 | |
| 10분마다 이벤트를 놓친 회원 바로잡기(증분 보정, #772) | 관리자 재색인 API, Kafka offset으로 다시 읽기(#773에서 검토하고 넣지 않음, 7장) |
| 원본·index 문서 수 비교 지표(#772) | 모집 검색, 통합 검색 |
| 이미 있는 인플루언서 처음 적재, 설정이 바뀐 index 무중단 재색인(#773, 7장) | |
| 7일 지난 tombstone 정리 | |
| `GET /api/v1/search/influencers`, 지표 | |
| `GET /api/v1/search/lectures`(비회원 포함), 회원·client IP 호출 제한(#883, 14장) | |

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
| in | `ApiV1SearchController`·`ApiV1SearchSpec` | 검색 API(대상별 경로, 인플루언서 8장·강의 14장) |
| in | `ClientIpKey` | 비회원 검색자 키. `getRemoteAddr()`의 HMAC(IPv6 /64), 공인 주소가 아니면 키 없음(14장) |
| in | `SearchMemberEventListener` | 회원 이벤트 소비(`member.events.v1`, 그룹 `auxiliary-app.search-member`). 대상마다 차례로 반영한다: 인플루언서 문서(5장) → 강의 판매자(13장). 대상마다 처음 적재 전이면 그 대상만 건너뛴다(#882 Q6) |
| in.kafka | `SearchKafkaConfig`, `SearchLectureKafkaConfig`, `SearchKafkaFactories`, `SearchKafkaSettings` | 회원·강의 소비 설정. 재시도·DLT·offset 규칙은 `SearchKafkaFactories` 하나다 |
| in.kafka | `TrustedEventDeserializer`, `MissingTypeEvent` | 강의 이벤트 역직렬화. 이 앱에 클래스가 없는 강의 이벤트는 읽지 않고 건너뛴다(알림 것을 복사, #882 Q3) |
| out | `MemberListSource`·`MemberApiListSource`, `MemberChangeSource`·`MemberApiChangeSource` | 회원 내부 API(회원 목록 #773, 변경분 #769). 인플루언서 처음 적재·증분 보정과 강의 판매자 사본이 함께 쓴다(#884) |
| out | `SyncCursorStore`, `SyncCursor` | 증분 보정 커서(`search.sync_cursor`, 대상마다 행 하나). JDBC |

### 공통 코드 (`search.common`)

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| app | `Reindexer` | 처음 적재·무중단 재색인 흐름(7장). 대상은 `IndexLoader`(새 index 채우기)·`IndexCatchUp`(교체 뒤 따라잡기)만 구현한다. 재색인 지표(`target` 태그)와 ERROR 표식을 남긴다 |
| app | `SynonymsCheck` | 동의어 묶음 확인 한 곳(#895, #868 R9). 10분마다 한 pod(`JobLock` 895_001)에서 묶음을 맞추고, 거절이면 `[SEARCH_SYNONYMS_REJECTED]` ERROR와 지표를 대상 수와 상관없이 한 번 남긴다 |
| app | `TombstoneCleanup` | 7일 지난 숨김 문서 정리(대상마다 얇은 하위 클래스) |
| app | `SearchJob`, `SearchJobRunner` | 한 pod 실행 락 번호와 실행기. alias가 없어(처음 적재 전) 못 한 회차는 실패가 아니라 건너뜀(INFO) |
| app | `SearchMetrics` | 검색 API 지표. `forTarget(대상)`으로 대상별 묶음을 받는다 |
| app | `ZeroResultKeywords` | 0건 검색어 모으기(9장). 대상마다 얇은 하위 클래스로 따로 센다(#883). 회원은 `record(검색어, 회원 ID)`, 비회원은 `record(검색어, client IP 해시)`(L19). 타입이 달라 둘이 같은 검색자로 섞이지 않는다 |
| app | `CachedRatingBaseline` | 베이지안 평균의 전체 평균 C를 pod 메모리에 두는 캐시(주기 10분, 실패하면 1분 동안 다시 묻지 않음, 다시 구하는 동안 다른 요청은 기다리지 않고 가진 값을 씀). 대상마다 얇은 하위 클래스(#883) |
| in | `SynonymsCheckBatch` | 동의어 묶음 확인 시각(기동 1분 뒤, 그 뒤 10분마다, `hapbang.search.synonyms-check-interval`). ES를 부르므로 재색인 실행기로 넘긴다(앱 공용 스케줄러 스레드를 붙잡지 않음). 모든 대상이 함께 쓰는 묶음이라 대상 배치가 아니라 공통에 둔다 |
| out | `IndexDefinition` | `search/<대상>/index.json` + 공통 사용자 사전으로 index 본문·설정 해시·이름(`search-<대상>-<해시>`)·alias(`search-<대상>`) |
| out | `IndexSetup` | index 하나의 쓰기 준비(alias 확인, 없으면 `IndexNotReadyException`), 상태(`IndexState`), 새 index 만들기·지우기·refresh, alias 원자 교체. 이 대상 index는 이름이 정확히 `search-<대상>-` + 해시 12자인 것만 본다(`IndexDefinition.ownsIndex`). 이름이 대상 + "-…"인 다른 대상의 index를 남은 index로 지우지 않게 한다 |
| out | `SynonymsSetup` | 동의어 묶음 하나를 공통 `synonyms.txt`와 맞춘다(모든 대상이 함께 씀) |
| out | `DocumentWrites` | 문서 쓰기의 대상 무관 부분: alias 없음 404 → 건너뜀, 그 문서 값 때문의 거절 판별(`DocumentRejectedException`), bulk 결과(`WriteOutcome`), 보이는 문서 세기, 숨김 문서 지우기 |
| out | `ConsistencyCheckStore` | 문서 수 정합성 점검 결과(`search.consistency_check`, 대상별 한 행). JDBC |

### 인플루언서 (`search.influencer`)

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| in | `SearchBatch` | 통계 동기화(끝난 뒤 10분), 증분 보정(끝난 뒤 10분), 처음 적재·재색인 확인(끝난 뒤 10분), tombstone 정리(매일 04:30), 인플루언서 0건 검색어 로그(10분) |
| in | `SearchIndexStartup` | 기동 뒤 다른 스레드에서 처음 적재·재색인 확인(동의어 포함) |
| app | `InfluencerMemberIndexer` | 회원 정보 → 넣기 또는 숨기기 |
| app | `InfluencerStatsSync` | 통계·분야 이름 동기화와 문서 수 정합성 점검(한 pod만, `JobLock` 771_002) |
| app | `MemberChangeReconciler` | 회원 변경분 증분 보정(한 pod만, `JobLock` 772_001). 결과 종류는 `ReconcileKind`. 재색인의 따라잡기 부품(`IndexCatchUp`)이기도 하다 |
| app | `InfluencerReindexer`, `InfluencerIndexLoader` | 공통 `Reindexer`에 인플루언서 부품을 끼운다(락 `JobLock` 773_001). 채우기는 회원 목록 + 통계, 따라잡기는 증분 보정 |
| app | `InfluencerTombstoneCleanup` | 인플루언서 index의 tombstone 정리(한 pod만, `JobLock` 771_001) |
| app | `SearchSyncMetrics` | 보정·정합성 지표 |
| app | `InfluencerSearchFacade` | 검색 조건 검사, 지표, 0건 검색어 |
| app | `ContentCategoryNames`, `RatingBaseline` | 분야 코드 → 이름, 평점 전체 평균 C(`CachedRatingBaseline` 하위 클래스). 둘 다 pod마다 10분 캐시 |
| app | `InfluencerZeroResultKeywords` | 인플루언서 0건 검색어(`target=influencer`) |
| out | `InfluencerIndexDefinition`, `InfluencerIndexSetup` | 대상 이름(`influencers`)·alias·지표 대상 값(`influencer`)과, 그 정의를 채운 `IndexSetup` |
| out | `InfluencerDocumentStore` | 회원·통계·분야 이름 쓰기(painless), 처음 적재용 bulk, 증분 보정용 문서 읽기. 나머지는 `DocumentWrites`에 맡긴다 |
| out | `InfluencerSearcher` | 검색 쿼리, 평균 C 집계 |
| out | `MemberApiStatsSource` | 회원 내부 API(통계·분야 목록 #769) |

### 강의 (`search.lecture`, #882)

| 계층 | 클래스 | 하는 일 |
| --- | --- | --- |
| in | `SearchLectureEventListener` | 강의 이벤트 소비(`lecture.events.v1`, 그룹 `auxiliary-app.search-lecture`). 강의·리뷰 변경 2종만 쓴다 |
| in | `LectureSearchBatch` | 강의 보정(끝난 뒤 10분), 판매자 사본 보정(끝난 뒤 10분), 리뷰 보정(끝난 뒤 1시간), 처음 적재·재색인 확인(끝난 뒤 10분), tombstone 정리(매일 04:40), 강의 0건 검색어 로그(10분) |
| in | `LectureIndexStartup` | 기동 뒤 다른 스레드에서 강의 처음 적재·재색인 확인(15장) |
| app | `LectureSearchFacade` | 강의 검색 조건 검사, 호출 제한, 지표, 0건 검색어(14장) |
| app | `LectureSearchRateLimit` | 회원 ID·client IP 키별 호출 제한과 지표(14장) |
| app | `LectureRatingBaseline`, `LectureZeroResultKeywords` | 강의 평점 전체 평균 C, 강의 0건 검색어(`target=lecture`) |
| config | `LectureRankingProperties`, `LectureRateLimitProperties`, `LectureSearchConfig` | 순위 계수(`hapbang.search.lecture-ranking.*`), 호출 제한 값(`hapbang.search.lecture-rate-limit.*`)과 limiter 빈 두 개 |
| app | `LectureIndexer` | 강의 → 문서 넣기·숨기기, 리뷰 → 리뷰 사본·평점 다시 세기 |
| app | `SellerIndexer` | 회원 → 회원 사본, 닉네임·상태가 바뀌면 그 판매자의 강의 문서 고치기 |
| app | `LectureTombstoneCleanup` | 강의 index의 tombstone 정리(한 pod만, `JobLock` 882_001) |
| app | `LectureReconciler` | 강의 보정: 강의 동기화 API 전체와 문서 version 비교, 원본에 없는 문서 숨김(한 pod만, `JobLock` 884_001, 15장) |
| app | `LectureReviewReconciler`, `LectureCopyAligner` | 리뷰 보정: 리뷰 사본 맞추기 + 모든 문서의 판매자·평점 칸을 사본과 맞추기(`JobLock` 884_002, 15장) |
| app | `SellerChangeReconciler` | 판매자 사본 증분 보정: 회원 변경분을 자기 커서(`seller-changes`)로 읽어 반영(`JobLock` 884_003, 15장) |
| app | `LectureReindexer`, `LectureIndexLoader`, `LectureIndexCatchUp` | 공통 `Reindexer`에 강의 부품을 끼운다(`JobLock` 884_004). 채우기는 회원 사본 → 리뷰 사본 → 강의, 따라잡기는 강의 보정 + 칸 맞추기(15장) |
| app | `LectureSyncMetrics`, `LectureReconcileKind` | 강의 보정 지표와 결과 종류(9장) |
| out | `LectureIndexDefinition`, `LectureIndexSetup` | 대상 이름(`lectures`)·alias·지표 대상 값(`lecture`)과, 그 정의를 채운 `IndexSetup` |
| out | `LectureDocumentStore` | 강의 쓰기(painless), 평점·판매자 칸 부분 갱신, 판매자 `update_by_query`, 처음 적재 bulk, 보정용 문서 읽기(mget·페이지)·원본에 없는 문서 숨기기(#884) |
| out | `LectureSyncSource`, `SearchLectureApiSyncSource` | 강의 동기화 내부 API(강의·리뷰 전체, [계약](../api/internal-lecture-sync.md) 4장) |
| out | `LectureSearcher` | 강의 검색 쿼리·정렬·highlight, 평균 C 집계(14장) |
| out | `SearchMemberCopyStore`, `LectureReviewCopyStore` | 회원 사본 `search.member_copy`, 리뷰 사본 `search.lecture_review_copy`. 여러 판매자·강의를 한 번에 읽기, 원본에 없는 리뷰 지우기(#884). JDBC |

### 새 대상(강의·모집)을 붙이는 법

1. `search/<대상>/index.json`을 만든다. 사용자 사전·동의어 묶음 자리표시자(`"__USER_DICTIONARY__"`, `__SYNONYMS_SET__`)를 넣고, 숨김 표시 필드 `hidden`(boolean)·`hiddenAt`(date)을 둔다(공통 세기·정리가 쓴다). 사용자 사전·동의어 파일은 `search/common/`의 것을 함께 쓴다. 새 용어를 더하면 모든 대상이 재색인한다. 동의어 묶음 확인·거절 알림은 공통 `SynonymsCheck`가 하므로 대상이 더할 것은 없다(#895).
2. `search.<대상>.out`에 `<대상>IndexDefinition`(대상 이름·alias·지표 대상 값)과 `<대상>IndexSetup extends IndexSetup`(생성자만)을 둔다.
3. 문서 저장소는 `DocumentWrites`를 써서 오류 처리·bulk 결과를 맞춘다. 원본 version 조건부 쓰기 script와 문서 모양은 대상이 정한다.
4. `IndexLoader`(원본 전체로 새 index 채우기: 보이는 문서 + 숨김 문서, 거절 문서는 건너뛰고 셈)와 `IndexCatchUp`(교체 뒤 따라잡기)을 구현하고, `<대상>Reindexer`에서 공통 `Reindexer`에 넘긴다. 따라잡기 부품은 겹쳐 읽기 값(`overlap()`)도 알려 준다(정기 보정과 같은 값). 락은 `SearchJob`에 **대상 이름을 붙인 새 키**(예: `LECTURE_REINDEX`, 번호는 그 작업을 더한 이슈 번호 × 1000 + 순번)를 더한다. 인플루언서 키(`REINDEX` 등)를 같이 쓰면 두 대상이 같은 락을 잡아 한쪽이 건너뛴다. 키를 한 enum에 모으는 것은 auxiliary database 하나를 모듈이 나눠 쓰므로 `SearchJobTest`가 번호 겹침을 한 번에 보게 하려는 것이다.
5. `<대상>TombstoneCleanup extends TombstoneCleanup`(생성자만, 새 락 키)을 두고 배치에 붙인다.
6. 검색 Facade는 `SearchMetrics.forTarget(대상)`을 받는다. 0건 검색어는 `<대상>ZeroResultKeywords extends ZeroResultKeywords`(생성자만, 대상 이름)를 두고 `record(검색어, 회원 ID 또는 IP 해시)`로 세며, 대상 배치가 10분마다 `flush()`한다. 베이지안 평점을 쓰면 `CachedRatingBaseline` 하위 클래스로 C를 둔다.
7. 공통 흐름은 `ReindexerTest`가 이미 시험용 대상으로 확인한다. 대상 테스트는 채우기·따라잡기 부품과 golden query를 본다.

## 4. index

- 이름 `search-influencers-<설정 해시 12자>`, 검색·쓰기는 alias `search-influencers`로만 한다(쓰기는 `require_alias`라 alias가 없으면 실패하고, ES가 같은 이름의 index를 멋대로 만들지 않는다).
- 원본은 레포 파일 `boundedContext/search/src/main/resources/search/`의 `influencers/index.json`(설정·mapping)과, 모든 대상이 함께 쓰는 `common/user-dictionary.txt`(사용자 사전)·`common/synonyms.txt`(동의어)다(#868, L17).
- 동의어 묶음 이름은 모든 대상이 함께 쓰는 `search-synonyms`다(#895, #868 R4). 옛 이름 `search-influencers-synonyms`에서 사용자 사전 강의 용어 추가와 함께 바꿔 재색인을 한 번으로 합쳤다. 이름이 index 본문에 들어가 바꾸면 모든 대상의 설정 해시가 바뀐다. 코드는 묶음을 지우지 않으므로 옛 묶음은 재색인이 옛 index를 지운 뒤 손으로 지운다(`DELETE _synonyms/search-influencers-synonyms`, #895 PR 본문).
- 운영 index는 #895 배포 뒤 `search-influencers-df33405ee9ab`(그 전 `d656dfa35eee`)이고 `InfluencerIndexDefinitionTest`가 이 값을 고정한다(사전·mapping·묶음 이름을 바꾸면 테스트 값도 고치고 PR에 재색인을 적는다).
- 설정 해시는 mapping·분석기·사용자 사전으로만 만든다. **동의어는 넣지 않는다**(검색할 때만 쓰여 재색인이 필요 없다).
- 기동하면(다른 스레드): 동의어 묶음(`search-synonyms`)이 파일과 다르면 묶음 전체를 PUT → alias가 없거나 다른 해시의 index를 가리키면 처음 적재·재색인을 한다(7장). 10분마다 다시 확인한다. 묶음 확인은 대상마다의 재색인과 별도로 `SynonymsCheck`도 10분마다 한다(거절 알림은 여기서만, 7장).
- **alias는 처음 적재가 index를 다 채우고 확인한 뒤에만 붙인다.** 이벤트 소비·보정이 alias가 없다고 빈 index에 alias를 붙이지 않는다(붙이면 "alias 있음, 해시 같음"으로 보여 처음 적재가 돌지 않는다).
- shard 1, replica 0(운영 ES가 1대), `dynamic: strict`.

### 분석

| 분석기 | 구성 | 쓰는 곳 |
| --- | --- | --- |
| `ko_index` | `nori_tokenizer`(`decompound_mode: discard`, 사용자 사전) + `nori_part_of_speech` + `lowercase` | 색인 |
| `ko_search` | `ko_index` + `synonym_graph`(Synonyms API 묶음, `updateable`) | 검색 |

- 사용자 사전은 한 단어 규칙만 둔다. 사전이 없으면 사라지거나(치지직·옵치, 캡컷 → 컷) 뜻이 깨지는(인플루언서 → 인/플/언서, 배그 → 배, 트위치 → 트/위치, 썸네일 → 썸/일) 단어다. 띄어 쓴 이름(리그 오브 레전드)은 동의어가 맡는다.
- 뜻 있는 두 낱말이 붙은 합성어(컷편집 → 컷/편집, 영상편집, 색보정)는 넣지 않는다. 넣으면 한 토큰이 되어 "편집"이나 띄어 쓴 "컷 편집"으로 찾지 못한다. 프리미어·OBS처럼 이미 쪼개지지 않는 말도 넣지 않는다(#895, `_analyze` 근거는 PR 본문).
- 동의어를 바꾸면 파일을 고쳐 배포한다. ES가 검색 분석기를 스스로 다시 읽는다. 쓰는 index가 있으면 묶음을 지울 수 없으므로 코드는 묶음을 지우지 않는다. 묶음 이름을 바꿔 아무 index도 쓰지 않게 된 옛 묶음만 사람이 지운다(위 묶음 이름 항목). 그 뒤 옛 코드로 되돌리면(롤백) 옛 코드가 옛 묶음을 다시 만들고 옛 설정으로 재색인하므로 따로 할 일은 없다.

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
- ES가 동의어 묶음을 내용 때문에 거절하면(400 `action_request_validation_exception`, 예: `롤 => 리그 => LoL`처럼 `=>`가 둘) 모든 대상이 재색인하지 않는다. `[SEARCH_SYNONYMS_REJECTED]` ERROR와 `hapbang.search.synonyms.rejected`는 `SynonymsCheck`가 10분마다 한 번만 남기고, 대상마다의 재색인은 `target=`이 붙은 WARN만 남기고 건너뛴다(#895, #868 R9). 재색인도 index를 만들기 전에 묶음을 맞춘다. pod가 떠 있는 동안 ES를 새로 만들면 묶음이 없기 때문이다. 재색인은 묶음이 있는지만 보장하고, 거절을 알리는 일은 `SynonymsCheck` 한 곳만 한다. `synonyms.txt`를 고쳐 배포해야 풀린다. 문서 쓰기는 동의어와 상관없어 지난 묶음으로 계속한다. 거절되는 규칙은 대개 CI의 `InfluencerIndexSetupTest`(실제 ES에 레포 파일을 올림)가 병합 전에 잡는다. `=> 롤`처럼 ES가 받고 무시하는 규칙도 있어 동의어를 고친 PR은 `_analyze`로 결과를 확인한다(PR #845 재리뷰).
- alias가 지금 설정 index와 다른 index를 함께 가리키면(사람이 손으로 바꾼 경우뿐) 지울 수 없어 `[SEARCH_REINDEX_FAILED]`로 멈춘다. alias를 한 index만 가리키게 고친다.
- 사람이 봐야 하는 실패라 ERROR에 alert 표식을 붙였지만 alert 규칙은 아직 두지 않았다(#772 Q2와 같이 지표·로그로 본다).

### 처음 적재하는 동안 (#773 Q1)

alias가 없는 동안은 쓸 곳이 없다. 이벤트 소비·증분 보정·통계 동기화·정리는 `IndexNotReadyException`을 받아 실패가 아니라 "건너뜀"으로 처리한다(5장). 검색 API는 503 `SEARCH_UNAVAILABLE`이다(처음 배포 때 한 번). 건너뛴 변경은 3(T 뒤 회원 목록)과 6(T − 1분부터 따라잡기)이 반영한다. 적재가 실패하면 alias가 없어 어긋난 index가 남지 않고, 다음 확인 때 전체를 다시 읽는다.

### 검토했지만 넣지 않은 것

- Kafka offset을 기록한 뒤 다시 읽기, 두 index에 함께 쓰기(dual write): consumer 제어와 경쟁 상태가 생긴다. 증분 보정 따라잡기로 같은 일을 더 단순하게 한다.
- 관리자 재색인 API: 처음 적재·설정 변경은 자동이고, 유실은 증분 보정이 맡는다. 무거운 작업을 밖에 열 이유가 없다.
- 처음 적재 때 빈 index에 alias를 먼저 붙이고 채우기: 검색이 부분 결과라도 나오지만, 수가 달라도 alias를 되돌릴 수 없고 처음 적재와 재색인이 다른 길이 된다(#773 Q1).

## 8. 검색 API

강의 검색 API는 14장이다.

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
| `hapbang.search.reindex.documents{target, reason}` | 처음 적재·재색인으로 새 index에 넣은 보이는 문서 수(인플루언서는 정상 인플루언서 수, 강의는 판매 중 강의 수) |
| `hapbang.search.lecture.reconcile.items{job, kind}` | 강의 보정이 본 항목 수(15장). job = lecture·review·seller. 놓친 이벤트를 바로잡은 kind는 inserted·overwritten·hidden(강의), copied·review_deleted(사본), seller_fixed·rating_fixed(칸 맞추기)이고 already_applied·skipped는 아니다. 인플루언서 `reconcile.members`와 이름을 나눈 것은 같은 이름에 태그 키가 다르면 Prometheus가 받지 않기 때문이다 |
| `hapbang.search.lecture.reconcile.duration{job, outcome}` | 강의 보정 1회 소요 시간. outcome = success·failure |
| `hapbang.search.rate.limit.rejected{target, requester}` | 호출 제한으로 429를 준 수(강의 검색만, #883). requester = member·guest |
| `hapbang.search.rate.limit.skipped{target}` | client IP가 공인 주소가 아니라 IP 호출 제한을 건너뛴 비회원 요청 수. 0이 아니면 Envoy 앞 경로를 확인한다(14장) |
| `hapbang.search.synonyms.rejected` | ES가 동의어 묶음을 내용 때문에 거절한 횟수(`SynonymsCheck` 10분 확인마다 한 번, 대상 수와 상관없음, #895). 묶음이 하나라 대상 태그가 없다. 0이 아니면 `synonyms.txt`를 고친다 |
| `hapbang.search.consistency.difference{target}` | 원본 정상 인플루언서 수 − 보이는 문서 수(6장 정합성 점검). DB의 점검 결과에서 읽어 어느 pod에서 봐도 같다. 점검한 적이 없거나, 마지막 회차에 점검하지 못했거나, 점검한 지 25분(점검 주기 두 번)이 지났으면 값이 없다. alias로 세므로 #773 재색인은 alias를 한 요청으로 바꿔 두 index를 함께 가리키는 순간이 없어야 한다 |
| `kafka_consumer_fetch_manager_records_lag` | 회원·강의 이벤트 소비 lag(Kafka client 지표). Kafka dashboard "그룹별 lag"에 `auxiliary-app.search-member`·`auxiliary-app.search-lecture`로 보인다 |

- 회원 ID는 지표 태그에 넣지 않는다. 문서 수 차이 alert는 두지 않았다(#773 처음 적재 전에는 차이가 늘 0이 아니다, #772 Q2).

- 0건 검색어는 사전·동의어를 늘릴 근거로 모은다(#771 Q9, PR #828 리뷰 뒤 Q18). 검색어는 남의 이름·닉네임·연락처일 수 있어 조건을 둔다.
  - **필터 없이 검색어만으로 0건**인 요청만 센다. 필터가 좁아 0건인 것은 사전 문제가 아니다(지표 `zero.results`도 같은 기준).
  - **서로 다른 검색자 2명 이상, 그중 회원 1명 이상이 찾은 말만** 로그에 나온다. 한 사람만 입력한 말은 남지 않는다. 검색자는 회원 ID, 강의 검색의 비회원은 client IP의 HMAC(14장)이고, 이 판단에만 메모리에서 쓰고(검색어마다 2명까지) 남기지 않는다. 회원이 꼭 있어야 하는 것은 한 사람이 망을 바꿔(Wi-Fi·LTE) 비회원 IP 둘로 넘기지 못하게 하려는 것이다(#883). 로그인 한 번·로그아웃 한 번(회원 1 + 비회원 1)으로는 넘을 수 있지만, 로그인한 회원 스스로의 기록이라 감수한다. 공인 주소가 아닌 비회원은 세지 않는다.
  - 이메일·URL·전화번호 모양(숫자 8개 이상)과 30자를 넘는 말은 세지 않는다.
  - 대상(인플루언서·강의)마다 따로 센다(#883). 요청에서는 세기만 하고 10분마다 배치 스레드가 대상마다 `여러 검색자가 찾았지만 결과가 없던 검색어를 모았다 target=…, keywords={…}` 한 줄(상위 100개)로 남긴다. 그래서 요청의 `userId`·`traceId`가 붙지 않는다. pod마다 따로 세고, 앱이 내려가면 남기지 않은 주기분은 사라진다.
- 인플루언서 검색에는 rate limit을 두지 않았다(로그인 전용, #767 Q28). 위 요청 수·p95와 k6 측정을 보고 정한다. 강의 검색은 비회원에게 열려 있어 처음부터 둔다(14장).

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
| `InfluencerIndexSetupTest` | 동의어 묶음(ES를 새로 만든 뒤 다시 맞추기, ES가 거절하면 `SynonymsRejectedException`이고 쓰기 준비는 계속 포함), alias가 없으면 만들지 않고 `IndexNotReadyException`, 설정이 바뀐 index 그대로 쓰기, 상태·남은 index, alias 원자 교체(거절되면 그대로), ES 장애, 분석기(사전·동의어, #895 강의 용어·합성어 기준·버튜버 동의어) |
| `ReindexerTest` | 공통 재색인 흐름을 시험용 대상(`search-reindexer-test`)과 가짜 채우기·따라잡기 부품으로: 처음 적재(alias·T − 부품의 겹쳐 읽기부터 따라잡기·`target` 태그 지표), 설정이 같으면 안 함, 수가 다르면 alias 안 붙이고 새 index 삭제, 따라잡기 BUSY면 기다림, 이름이 겹치는 다른 대상 index를 지우지 않음(#868) |
| `InfluencerReindexerTest` | 공통 흐름 + 인플루언서 부품으로 처음 적재(인플루언서 보이게·그 밖 숨김 문서·통계·alias·커서 T − 1분), 해시가 같으면 안 함, 설정이 바뀌면 옮기고 옛·남은 index 삭제, 재색인 중 바뀐 회원 따라잡기, **재색인 내내 검색 실패 없음**, 수가 다르거나 bulk가 실패하거나 새 index를 만들지 못하면 alias 그대로·새 index 삭제, 두 pod가 동시에 시작해도 한 번, 따라잡기가 BUSY면 기다림, 거절된 회원 건너뜀, alias 교체 응답만 실패, pod가 뜬 채 ES를 새로 만듦, 롤링 배포 중 옛 pod는 더 새 index를 되돌리지 않음(롤백은 되돌림, 시계 차이 1분 안은 되돌림), 동의어 거절이면 재색인 안 함(지표는 남기지 않음) |
| `SynonymsCheckTest` | 동의어 묶음 확인(#895): 맞추면 지표 없음, 거절이면 지표 한 번, **대상이 둘이어도 거절 지표 한 번이고 두 재색인 모두 건너뜀**(R9), 확인 락으로 실행 |
| `SearchReindexWiringTest` | 기동·10분 확인이 재색인 실행기를 주입받아 `search-reindex-` 스레드로 넘김(스케줄링·기동 재색인을 켠 컨텍스트, 재색인은 가짜) |
| `SynonymsCheckWiringTest` | 동의어 확인 배치가 재색인 실행기를 주입받아 `search-reindex-` 스레드로 넘김(스케줄링을 켠 컨텍스트, 확인은 가짜, #895) |
| `SearchMemberEventListenerTest` | 실제 Kafka로 가입·변경 반영, 다른 타입 건너뜀, DLT, 처음 적재 전 건너뜀, 같은 이벤트로 강의 판매자 반영(#882) |
| `SearchLectureEventListenerTest` | 실제 Kafka로 강의·리뷰 반영, 쓰지 않는 타입 건너뜀, **이 앱에 클래스가 없는 타입은 DLT로 가지 않고 건너뜀**, 깨진 본문은 DLT, 처음 적재 전엔 사본만(#882) |
| `LectureIndexerTest` | 강의 등록·수정·판매 중지·삭제, 리뷰 작성·수정·삭제, 판매자 닉네임 변경·정지, 순서 역전, 문서 쓰기 실패 뒤 재시도(가격과 함께 다시 와도 채움, #909), 문서를 만드는 사이 바뀐 사본 따라잡기, 처음 적재 전(PostgreSQL + ES) |
| `LectureDocumentStoreTest`, `LectureIndexSetupTest` | 강의 version 조건부 쓰기·tombstone·가격 칸(옛 메시지는 안 덮어씀, 같은 version은 결과 NOOP에 가격만)·판매자·평점 칸, 정지된 판매자 강의는 정리에서 남음, 강의 index 정의·mapping·인플루언서 index와 분리 |
| `SearchMemberCopyStoreTest`, `LectureReviewCopyStoreTest` | 회원 사본 version 규칙(같은 version 재수신 포함), 리뷰 사본과 counted만 평균(PostgreSQL 컨테이너) |
| `InfluencerStatsSyncTest` | 통계 반영·noop·평균 지움·문서 없음·숨김·여러 페이지·분야 이름, 정합성 점검(차이 0·양수·음수, 세기 실패) |
| `MemberChangeReconcilerTest` | 처음 적재 전은 실패 지표에 넣지 않음(ES 장애는 넣음), 증분 보정: 처음은 지금부터, 분야 변경·강등·승급을 놓친 경우, 1분 겹쳐 읽기, 이벤트와 둘 다 반영, version만 오름, 일반 유저, 멈춘 뒤 이어 가기, 바깥 시작 시각, 깨진 회원·ES가 거절한 회원 건너뜀, 다음 커서 없는 응답, `runFrom` 성공·실패 |
| `IndexDefinitionTest`, `InfluencerIndexDefinitionTest` | 대상 이름으로 alias·index 이름, 동의어는 해시에 안 들어감, 사전 규칙, 레포 파일 읽기, **운영 index와 같은 설정 해시**(#868) |
| `DocumentWritesRejectionTest`, `ZeroResultKeywordsTest` | 그 문서 값 때문의 거절만 건너뜀, 0건 검색어(서로 다른 검색자 2명·회원 1명 이상, 연락처 모양 제외, 비회원 IP 해시 키) |
| `SyncCursorStoreTest`, `ConsistencyCheckStoreTest` | 커서·점검 결과 저장(PostgreSQL 컨테이너) |
| `SearchModuleIntegrationTest` | 모듈만으로 컨텍스트, search schema, 검색 전용 client(nori), 재색인 실행기가 가상 스레드에서 trace를 이어받음 |
| `SearchSyncMetricsTest` | 보정 지연(끝까지 읽은 시각 기준), 문서 수 차이(DB, 25분 지나면 비움), kind별 건수 |
| `ApiV1SearchControllerTest` | 응답, 필터, 정렬, 동점 페이지 넘김, 400·401, 0건 검색어 |
| `RatingBaselineTest`, `InfluencerDocumentStoreBulkFailureTest` | 평균 C 실패 뒤 1분 대기, bulk 일부 실패 |
| `GoldenQueryTest` | 대표 검색어 23개 순위, 알려진 한계(G01b), 계수 격자(`build/golden-query.md`) |
| `LectureSearcherTest` | 강의 검색 쿼리(#883): 보일 강의(판매 중·정상 판매자)만, 네 필드·동의어, 기본·최신·평점·가격순(가격 없는 강의는 맨 뒤, 가격 mapping 없는 옛 index도 거절 안 함), 응답 가격, **highlights HTML escape(`<script>`·`<b>`·따옴표·`&`)와 `<em>`만**, 검색어가 있으면 highlights 객체(닉네임만 걸린 경우), C가 없으면 가산 없음, 반올림, 전체 수 상한, alias 없음 |
| `LectureSearchFacadeTest` | 정렬 허용 목록(가격 오름·내림, 강의 메시지), 1,000건, 503(평점순은 C 실패도), C를 모르면 가산 없이 검색, C를 구하는 경우, 0건 검색어(회원·IP 키·키 없음) |
| `CachedRatingBaselineTest` | 다시 구하는 동안 다른 요청은 가진 값을 바로 씀, 처음 값을 구하는 중이면 바로 실패 |
| `ApiV1LectureSearchControllerTest` | 강의 검색 API: 비회원·회원, 정렬(가격 포함), 응답 가격, 400 세 종류, 처음 적재 전 503, 회원 429·`Retry-After`, **비회원은 X-Forwarded-For·X-Real-IP·X-Envoy-External-Address를 바꿔도 같은 IP라 429**, 회원·비회원 한도 분리, 사설 주소는 건너뜀·지표, 0건 검색어 |
| `ClientIpKeyTest` | 공인 주소가 아닌 주소 12가지, 키에 IP 원문 없음, IPv6 /64, pod마다 다른 비밀값 |
| `LectureGoldenQueryTest` | 강의 대표 검색어 20개, LIKE 규칙 재현 비교와 평점 가산 격자(`build/golden-query-lecture.md`) |
| `LectureReconcilerTest` | 강의 보정(#884): 놓친 등록(판매자·평점 채움)·수정·판매 중지, 더 새 문서는 그대로, **원본에 없는 문서 숨김**(읽는 동안 생긴 강의·빈 원본은 그대로), 숨긴 뒤 원본에 다시 나타난 강의, 처음 적재 전 |
| `LectureReviewReconcilerTest` | 리뷰 보정: 놓친 리뷰·원본에 없는 리뷰, **평점 칸 경합으로 남은 옛 평균을 바로잡음**, 판매자 칸 맞추기(더 새 문서는 그대로), 처음 적재 전엔 사본만 |
| `SellerChangeReconcilerTest` | 판매자 사본 보정: 처음은 지금부터, 놓친 정지, 이미 반영·판매자 아닌 회원, 처음 적재 전엔 사본만 |
| `LectureReindexerTest` | 강의 처음 적재: 일부만 찬 사본도 모두 맞춤, 판매자·평점·숨김, **적재 중 바뀐 강의·리뷰·판매자가 교체 뒤 반영**, 해시가 같으면 안 함, 실패하면 alias 없음·새 index 삭제, 거절된 강의 건너뜀 |
| `LectureDocumentStoreReconcileTest`, `LectureReindexWiringTest` | 새 index bulk·mget·페이지 읽기·숨기기(강의 version 지움), 기동·10분 확인이 재색인 실행기로 넘김 |

## 12. 알려진 한계

- 사전에 없는 지어낸 닉네임은 잘게 쪼개져 다른 검색어에 걸릴 수 있다(예: "롤러스케이터"가 "롤" 검색에 걸림, G01b). 0건 검색어처럼 근거가 쌓이면 사전을 보강하거나 하위 필드를 더한다.
- 동의어 일치와 원문 일치의 가중치가 같다(G05). 원문 일치를 위로 올리려면 동의어 없는 하위 필드를 작은 가중치로 더한다.
- 승급한 회원의 채널·팔로워·평점은 다음 통계 동기화(최대 10분)까지 비어 있다. 재색인 중 승급해 따라잡기로 들어온 회원도 같다.
- 처음 적재·재색인은 한 pod가 회원 목록(모든 회원)을 500명씩 읽어 페이지마다 bulk로 넣는다. 일반 유저도 숨김 문서로 들어가 문서 수는 회원 수만큼이고 7일 뒤 정리된다. 회원 수만 명까지는 수십 초로 본다. 훨씬 커지면 `reindex.duration`을 보고 bulk 크기·refresh 끄기를 검토한다.
- #771·#772 코드로 이미 alias가 붙은 로컬 ES는 해시가 같아 처음 적재가 돌지 않는다(운영은 #860 전까지 검색이 꺼져 있어 index가 없다). 로컬의 `search-influencers-*`를 지우고 띄운다.

## 13. 강의 검색: 사본과 강의 색인 (#882)

강의·리뷰·회원 이벤트로 강의 index(`search-lectures-<해시>`, alias `search-lectures`)를 채운다. 결정은 강의 검색 grill L5·L7·L8과 #882 Q1~Q6이다. 검색 API는 14장(#883), 보정·처음 적재·재색인은 15장(#884)이다. 처음 적재가 끝나기 전(alias 없음)에는 문서 쓰기를 건너뛰고 사본만 고친다(정상).

### 사본 (search schema)

| 표 | 칸 | 채우는 곳 |
| --- | --- | --- |
| `search.member_copy` | 회원 ID, 닉네임, 상태, version | 회원 이벤트(그룹 `auxiliary-app.search-member`). 누가 판매자가 될지 몰라 모든 회원을 둔다 |
| `search.lecture_review_copy` | 리뷰 ID, 강의 ID, 별점, `counted`, `deleted`, version | 리뷰 이벤트 |

- 강의 자체는 사본을 두지 않는다. 강의 이벤트 값을 바로 문서로 쓰고, 처음 적재·보정은 강의 동기화 API를 읽는다(L13·L14).
- 둘 다 원본 version이 더 클 때만 반영한다(회원은 `UserDto` 규칙대로 version 없는 정보도 다룬다).

### 문서

| 필드 | 종류 | 출처 |
| --- | --- | --- |
| `lectureId` | long | 강의 ID. 최신순과 동점 정리 키(등록 시각이 DTO에 없어 ID 순서로 대신한다) |
| `lectureVersion` | long | 강의 version(조건부 쓰기) |
| `hidden`, `hiddenAt` | boolean, date | 판매 중이 아닌 강의(`onSale=false`: 판매 중지·검수 전·삭제)의 tombstone |
| `title`, `description`, `contentTitles` | text(nori) | 강의 이벤트 |
| `sellerUserId` | long | 강의 이벤트 |
| `priceHp` | long | 강의 이벤트(#909). 가격 필드가 생기기 전(#885)의 옛 메시지로 만든 문서면 없다 |
| `sellerNickname`, `sellerActive` | text(nori), boolean | 회원 사본(L5·L8). 정지는 숨김이 아니라 `sellerActive=false`다 |
| `sellerVersion` | long | 판매자 칸을 쓴 회원 version. 더 작은 version의 판매자 값으로 되돌리지 않는다(PR #906 리뷰 Q12) |
| `averageRating`, `reviewCount` | double, long | 리뷰 사본의 `counted` 리뷰 평균(반올림 안 함, 없으면 비움)·수 |

- 검색에 보일 강의 = `hidden=false` 그리고 `sellerActive=true`(검색 API #883이 거른다). 공통 "보이는 문서 세기"(`hidden=false`)는 판매 중 강의 수이고 판매자 상태와 상관없다.
- tombstone 정리(7일)는 숨김 문서만 지운다. 판매자가 오래 정지돼도 문서가 남아 정지가 풀리면 바로 다시 보인다.
- 이미 숨긴 강의에 더 새 숨김 이벤트가 오면(판매 중지 상태에서 수정) `hiddenAt`을 다시 찍는다. 인플루언서와 같다. 정리 기준이 마지막 숨김 이벤트에서 7일이 되어, 그 사이 늦게 들어온 옛 "판매 중" 이벤트를 version으로 막는다. 처음 숨긴 시각으로 두면 마지막 이벤트 뒤 7일이 안 돼 지워진 문서를 옛 이벤트가 되살릴 수 있다(PR #906 리뷰 Q11).

### 반영 규칙

- **칸마다 출처가 하나다.** 강의 칸은 강의 이벤트가, 판매자 칸은 회원 사본이, 평점 칸은 리뷰 사본이 쓴다. 강의를 고쳐도 판매자·평점 칸은 건드리지 않아 그사이 바뀐 닉네임·평점을 옛 값으로 되돌리지 않는다.
- **강의**: painless scripted upsert로 문서의 `lectureVersion`보다 클 때만 반영한다. ES external versioning은 판매자·평점 부분 갱신이 ES 버전을 올려 다음 강의 이벤트를 거절하게 만들어 쓰지 않는다(인플루언서 5장과 같은 이유). 문서가 없어도 숨김 문서를 만들어 삭제 뒤 늦은 이벤트가 되살리지 못한다.
- **가격**(#909): 강의 칸이지만 두 가지가 다르다. ① 넘어온 `priceHp`가 null(가격 필드가 생기기 전의 옛 메시지·응답)이면 문서 가격을 그대로 둔다(받는 쪽은 null을 "모름"으로 다룬다, `internal-lecture-sync.md`). ② **같은 version**이면 강의 쓰기는 지금처럼 `NOOP`이고, 넘어온 가격이 있으면 가격 전용 script(`PRICE_SCRIPT`)를 한 번 더 불러 문서 version이 같고 가격이 다를 때만 가격 칸 하나를 고친다. 결과는 그대로 `NOOP`이다. 강의 script 안에서 고쳐 `UPDATED`가 되면 "같은 이벤트 재수신이면 판매자·평점을 채운다"(아래, PR #906)가 빠지기 때문이다(#909). 같은 강의 ID·version은 이벤트와 동기화 API가 같은 값이라 맞는 값이다. 롤링 배포 중 가격을 모르는 옛 auxiliary pod가 더 새 version을 써 가격이 낡으면, 같은 version이 다시 올 때(재전달·발행 기록 재발행, 재색인, #884 보정이 생기면 그것) 고쳐진다. 그 전까지는 다음 강의 수정까지 낡은 가격이다. 더 작은 version은 가격도 그대로다. 강의 쓰기가 `NOOP`이고 가격이 있으면 가격이 같아도 ES 요청이 하나 더 간다(재전달·순서가 뒤바뀐 옛 이벤트일 때만이라 드물다). #884 보정·따라잡기는 "version이 더 크거나 같은 version에 가격이 다를 때만" 써서 이 추가 요청을 필요한 강의로 줄이고, bulk로 쓴다면 같은 version 가격 맞추기에 `PRICE_SCRIPT`도 함께 써야 한다.
- 가격 칸을 더해 강의 index 설정 해시가 바뀌었다(#909). 강의 index를 만들고 옮기는 처음 적재·재색인은 #884다. #884 전에는 운영에 강의 index가 없어 할 일이 없고, #884 처음 적재가 새 해시로 만들어 가격까지 채운다. #884 뒤에 해시가 바뀌면 재색인이 한 번 돌고 새 index는 동기화 API 전체로 채워져 기존 강의도 가격을 갖는다. 재색인이 alias를 옮기기 전 잠깐은 alias가 가격 mapping이 없는 옛 index를 가리킨다. 그동안 가격순은 `unmappedType`으로 거절 대신 최신순이 되고, 가격을 싣는 강의 쓰기는 strict mapping 오류로 재시도 뒤 DLT로 간다(재색인 따라잡기가 동기화 API로 채운다).
- **판매자·평점 칸은 강의 칸을 쓴 뒤 사본에서 읽어 채운다**(Q9·Q10). 강의 쓰기는 강의 칸만 쓴다(새 문서는 닉네임 없이 정상 판매자, 평점 없음). **보통의 수정(`UPDATED`)에서는 사본을 읽지 않고, 그 밖의 결과(`CREATED`·`NOOP`)면 읽어 채운다.** `NOOP`(같은 이벤트 재수신, 옛 이벤트)도 채우는 것은, 새 문서를 만든 뒤 채우기가 실패해 같은 이벤트를 다시 받으면 강의 쓰기가 `NOOP`이라 그때 채워야 복구되기 때문이다(PR #906 리뷰). 이 분기는 `LectureIndexerTest.fillsCopiesOnRedeliveryAfterFailure`·`fillsCopiesWhenRedeliveryAlignsPrice`(#909)가 지킨다. 문서를 만든 뒤에 읽으므로 그 전에 온 회원·리뷰 이벤트(없던 문서를 고치지 못함)의 값도 빠지지 않는다. 채우기 전 잠깐은 빈 값이지만 ES가 새 문서를 검색에 보이는 refresh(1초) 전이다.
- **리뷰**: 사본에 적고(version 조건) 그 강의의 `counted` 리뷰로 평균·수를 다시 세어 평점 칸을 고친다. 사본이 이미 같은 version이어도 다시 센다(사본은 커밋했는데 문서 쓰기가 실패해 같은 이벤트를 다시 받은 경우). 강의 문서가 없으면 건너뛴다(강의 이벤트가 문서를 만들 때 사본에서 채운다).
- **회원**: 사본에 적고, 닉네임·상태가 바뀌었으면(처음 받은 회원 포함) 그 판매자의 모든 강의 문서(숨김 포함)를 `update_by_query`로 고친다. 같은 version을 다시 받으면 사본 값으로 다시 고친다(재시도). 회원 소비자는 인플루언서 반영이 실패해도 판매자 반영은 하고 실패를 그 뒤에 던진다. 고치기 전에 refresh해 방금 만든 문서를 놓치지 않고, 강의 쓰기와 충돌한 문서가 있으면 실패로 보고 다시 시도한다(건너뛰면 옛 판매자 값이 남는다).
- **판매자 칸은 회원 version으로 옛 값을 막는다**(PR #906 리뷰 Q12). 채우기(강의 소비자)와 `update_by_query`(회원 소비자)는 다른 스레드라 동시에 돈다. 강의 소비자가 사본을 읽은 뒤 쓰기 전에 회원 소비자가 정지를 반영하면, 늦게 쓴 옛 "정상" 값이 정지를 되돌릴 수 있다. 그래서 문서의 `sellerVersion`보다 작은 회원 version이면 판매자 칸을 쓰지 않는다(같은 version은 쓴다. version 없는 정보는 문서에도 version이 없을 때만). #884 보정은 강의 version만 비교하므로 이 보호가 없으면 어긋남이 저절로 풀리지 않는다.
- **회원 파기(마스킹)**: 회원 앱은 복구 기한이 지난 탈퇴 회원을 파기할 때 닉네임을 `탈퇴회원#{회원ID}`로 바꾸고 `UserModifiedEvent`(version 증가)와 `UserMaskedEvent`를 함께 보낸다(정책 "파기 방법 (#594)"). 검색은 `UserModifiedEvent`로 회원 사본 닉네임을 바꾸고 그 판매자의 강의 문서 `sellerNickname`도 고치므로 원래 닉네임이 남지 않는다. 그래서 `UserMaskedEvent`는 받지 않는다(같은 일을 두 길로 하지 않게, PR #906 리뷰 Q13). 탈퇴한 판매자에게는 판매 중·검수 대기 강의가 없어 그 강의 문서는 숨김 문서로 7일 뒤 정리된다.
- 회원 사본에 아직 없는 판매자의 강의는 닉네임 없이 정상 판매자로 넣고 회원 이벤트가 채운다(L5). 처음 적재는 회원 목록 전체로 회원 사본을 먼저 맞춘다(15장).
- 사본(DB)은 alias가 없어도 고친다. 처음 적재(#884)가 사본으로 문서를 만들기 때문이다(L14). 문서 쓰기만 건너뛴다(DEBUG).

### 강의 이벤트 소비

- `lecture.events.v1`, 그룹 `auxiliary-app.search-lecture`, `earliest`부터. `LectureChangedEvent`·`LectureReviewChangedEvent`만 쓰고 나머지 강의 이벤트는 건너뛴다. 강의 이벤트 패키지 밖의 타입(`java.util` 등 역직렬화기 기본 신뢰 타입)은 건너뛰지 않고 재시도 없이 DLT로 보낸다.
- **이 앱에 클래스가 없는 강의 이벤트는 역직렬화 단계에서 건너뛴다**. `__TypeId__`가 `com.hapbang.shared.lecture.event` 바로 안의 클래스 이름인데 클래스가 없으면 본문을 읽지 않고 `MissingTypeEvent`로 넘긴다. 강의 앱이 새 이벤트를 더해 먼저 배포해도(#872) DLT로 가지 않아 "Kafka DLT 적재" 경보가 울리지 않는다. 회원 이벤트 소비(`auxiliary-app.search-member`)는 아직 클래스 이름 방식이다.
- 재시도·DLT는 회원 소비와 같다(1·2·4초 3회 뒤 `lecture.events.v1.auxiliary-app.search-lecture.DLT`, 보존 7일). 필수 값이 빠진 이벤트와 깨진 본문은 재시도 없이 DLT로 간다.
- ES가 7초보다 오래 멈추면 그동안의 강의·리뷰 이벤트가 DLT로 간다. #884 보정(강의 10분·리뷰 1시간)이 바로잡기 전까지는 [kafka.md "DLT 재처리 runbook"](../architecture/infrastructure/kafka.md#dlt-재처리-runbook)대로 되돌린다. version 조건이라 여러 번 넣어도 결과가 같다.

### 한계

- 평점 다시 세기와 보정·따라잡기(#884)가 같은 강의를 동시에 세면 먼저 읽고 늦게 쓴 쪽의 옛 값이 남을 수 있다(평점 칸은 판매자 칸과 달리 version 보호가 없다). 소비자 스레드가 하나라 이벤트끼리는 생기지 않고, 리뷰 보정의 칸 맞추기가 최대 1시간 안에 바로잡는다(15장).
- 판매자 정보를 강의 문서에 다시 맞출 때마다 강의 index를 refresh한다. 닉네임·상태 변경과 처음 보는 회원(새 가입)마다 한 번 돈다. 강의 index는 문서가 적고 가입 직후 회원은 강의가 없어 `update_by_query`가 0건으로 끝나므로 부담이 작다(#882 리뷰 Q8).

## 14. 강의 검색 API (#883)

`GET /api/v1/search/lectures` (누구나. 회원이면 회원 한도로 센다). 결정은 강의 검색 grill L2·L9~L12·L18~L20(#881)과 #883이다. 처음 적재가 끝나기 전(alias 없음)에는 503 `SEARCH_UNAVAILABLE`이다(정상, 15장).

| 파라미터 | 뜻 |
| --- | --- |
| `keyword` | 선택, 최대 50자. 제목 ×3, 콘텐츠 제목 ×2, 판매자 닉네임 ×2, 소개 ×1(`best_fields`, tie_breaker 0.3) |
| `page`, `size`, `sort` | size 기본 20·최대 100. sort는 `latest,desc`·`rating,desc`·`price,asc`·`price,desc`만(그 밖 400 `UNSUPPORTED_SORT`, 상세 메시지가 강의 정렬을 알린다). 정렬 이름은 문서 필드가 아니라 짧은 뜻 이름이다(인플루언서 `followers`와 같다, #909). Spring이 방향을 빠뜨린 `sort=price`를 오름차순으로 채워 `price,asc`와 같게 처리된다(구분할 수 없음) |

- 보이는 강의: `hidden=false` 그리고 `sellerActive=true`(13장).
- 순서: 기본은 검색어가 있으면 점수, 없으면 최신. `latest`는 강의 ID 내림차순(등록 시각 칸이 없고 ID가 등록 순서와 같다, #882 Q5, #883). `rating`은 베이지안 평균 (n×R + m×C)/(n+m) → 리뷰 수. `price`는 가격 오름·내림이고, 가격이 없는 강의는 어느 쪽이든 맨 뒤다(#909. 무료 강의가 없어(정책 "강의 가격") null은 0원이 아니라 모르는 값이다). **모든 순서의 마지막은 강의 ID 내림차순**이다.
- 점수: 검색어가 있으면 글 점수 × (1 + b × 베이지안 평균 / 5). b=0.1, m=5(`hapbang.search.lecture-ranking.*`). 가산이 "베이지안 평균 / 5"로 묶여 평점이 주는 이득은 보통 범위(보정 평균 3.5~4.8)에서 약 2.4%, 극단(5 대 1)에서 약 8%, 식의 상한은 10%다. 그래서 비슷한 글끼리만 가르고, 제목에 걸린 강의를 소개에만 걸린 고평점 강의가 이기지 않는다(글 점수 차이 약 3배). golden query 통과 구간은 0.05~1.0이다(0은 글이 같은 강의를 못 가르고, 5는 글이 조금 더 맞는 강의를 평점이 뒤집는다). 실제 리뷰 분포를 보고 조정한다(재색인 불필요).
- 평균 C는 검색에 보이는 강의의 리뷰 수 가중 평균이고 pod마다 10분 캐시다. 검색어가 있는 기본 순서와 평점순에만 구한다(최신순 목록은 구하지 않는다). 다시 구하는 동안 다른 요청은 기다리지 않고 가진 값을 쓴다. **C를 아직 모르면**(pod가 뜬 직후 집계 실패, 처음 적재 직후 등 1분 대기 중) 기본 순서의 검색어 검색은 503 대신 평점 가산 없이 글 점수만으로 찾고, 평점순만 503이다(#883).
- highlights(검색어가 있을 때만): ES `encoder: html`이 원문의 HTML 특수 문자를 escape하고 걸린 말만 `<em>…</em>`으로 감싼다. 제목·판매자 닉네임은 통째로, 소개는 걸린 곳 주변 한 조각(약 100자), 콘텐츠 제목은 걸린 것만 통째로다. 검색어가 있으면 걸린 칸이 없어도 `highlights` 객체를 주고(칸만 null), 검색어가 없으면 null이다.
- 응답 한 줄: `lectureId`, `title`, `priceHp`(HP, 모르면 null, #909), `sellerUserId`, `sellerNickname`, `averageRating`(소수 첫째 반올림, 강의 목록과 같음, 없으면 null), `reviewCount`, `highlights{title, sellerNickname, description, contentTitles[]}`. 썸네일은 강의 앱 일괄 API(`GET /api/v1/lectures/thumbnails?ids=`, #885)로 FE가 채운다.
- 앞에서 1,000건까지(`page × size + size ≤ 1000`, 넘으면 400 `PAGE_TOO_DEEP`). ES에 닿지 못하면 503 `SEARCH_UNAVAILABLE`(강의 목록 `GET /api/v1/lectures`는 그대로 쓸 수 있다).

### 호출 제한 (L2·L18)

- 회원은 회원 ID, 비회원은 client IP 키로 따로 센다. 둘 다 분당 60회(`hapbang.search.lecture-rate-limit.{member,guest}.*`, k6·429 지표를 보고 조정). 넘으면 429 `TOO_MANY_REQUESTS`와 `Retry-After`(global 처리기). 정렬·페이지 오류(400)도 센다. 검색어 50자 초과 같은 형식 오류는 Spring 메서드 검증이 컨트롤러 전에 400을 내 세지 않는다(ES까지 가지 않는다).
- 앱 메모리(`KeyedRateLimiter`)에서 센다. replica가 1개라 한도가 곧 전체 한도이고, 배포 중 두 pod가 겹치면 잠깐 최대 2배다.
- **client IP는 `request.getRemoteAddr()`만 쓴다.** 운영은 Tomcat `RemoteIpValve`가 Envoy가 붙인 실제 접속 주소를 넣고, 클라이언트가 보낸 `X-Forwarded-For`는 무시된다(#726 실측, [API Gateway](../architecture/infrastructure/api-gateway.md) rate limit). `X-Forwarded-For`·`X-Real-IP`·`X-Envoy-External-Address`는 위조할 수 있어 읽지 않는다. 로컬·테스트 profile은 `forward-headers-strategy`가 없어 직접 접속 주소다.
- IPv6는 앞 64비트(/64)로 센다. 공인 주소 판단과 /64 규칙은 회원 앱 이메일 인증(#557)을 그대로 옮겼다(`ClientIpKey`, #883). 두 앱이 같은 규칙이어야 하므로 한쪽을 고치면 다른 쪽도 고친다. common으로 옮길지는 rate limit 공용화 판단(#697) 때 본다.
- **키는 IP 원문이 아니라 HMAC-SHA256(pod마다 무작위 비밀값, IP) 앞 16자다**(#883). limiter가 거절 시작 때 남기는 WARN(`key=`)과 0건 검색어 메모리에 IP가 남지 않는다. 남용한 IP는 같은 시각 Envoy access log의 429와 `client_ip`로 찾는다.
- **공인 주소가 아니면(사설·루프백·링크 로컬 등) IP 한도를 건너뛴다**(#883). Envoy 앞 경로가 깨져 모두가 같은 내부 주소로 보이는 신호이고, 그때 한 사람이 모든 비회원을 429로 막지 않게 한다. 처음 한 번과 그 뒤 10분마다 WARN `비회원 client IP가 공인 주소가 아니라 IP 호출 제한을 건너뛰었다 … ip=…, skipped=…`를 남기고, 건수는 `hapbang.search.rate.limit.skipped`로 본다. 그동안 비회원도 1,000건 제한과 ES 장애 503은 그대로 받는다.
- 운영 확인(배포 뒤, 비회원·같은 PC): 1분 안에 61번 보내면 61번째가 429 → `X-Forwarded-For`를 다른 공인 IP로 바꿔도 계속 429 → 다른 망(테더링)에서는 200. 위조 무시는 Tomcat valve 동작이라 앱 테스트로 재현하지 못해 이것으로 대신한다.

### 0건 검색어 (L19)

- 검색어가 있는데 0건이면 회원은 회원 ID, 비회원은 client IP 키로 서로 다른 검색자를 센다. 로그에는 서로 다른 검색자 2명 이상, 그중 회원 1명 이상인 말만 남는다(#883). 공인 주소가 아닌 비회원은 세지 않는다. 강의 검색에는 필터가 없어 검색어가 있는 0건은 모두 센다. 나머지 규칙(2명 이상, 연락처 모양·30자 초과 제외, 10분마다 한 줄)은 9장과 같고, 인플루언서와 따로 `target=lecture`로 남긴다.

### golden query (L20)

`LectureGoldenQueryTest`가 강의 26개 표본(판매 중지 1, 정지 판매자 1 포함)과 대표 검색어 20개로 순위를 고정하고, 같은 표본에 기존 강의 목록 검색의 LIKE 규칙(판매 중 + 제목 포함 + 최신순, 판매자 상태는 안 봄)을 재현해 `build/golden-query-lecture.md`에 비교 표를 쓴다. 2026-11-12 결과: 찾아야 할 강의 44개 중 LIKE 19 · 검색 44, 앞 3개 중 정답 수 LIKE 18/38 · 검색 37/38. LIKE는 동의어(배그·브이튜버)·닉네임·콘텐츠 제목·소개로 찾지 못하고, 정지 판매자의 강의도 섞인다. 정답 목록은 표본을 만든 사람이 정해 검색에 유리할 수 있어, 실제 시드로 두 API를 비교하는 로컬 시험으로 보강한다.

### 한계

- 평균 C를 모르는 동안(pod가 뜬 직후 첫 집계 중이나 집계 실패, 처음 적재 직후 최대 1분)은 검색어 검색의 순서가 평점 가산 없이 나온다. 평점순은 그동안 503이다. 인플루언서 평점순도 pod가 뜬 직후 첫 집계가 도는 동안(보통 수십 ms) 들어온 요청은 503이다(요청 스레드가 ES를 기다리지 않게 한 대가).
- 0건 검색어의 "회원 1명 이상" 조건은 한 사람이 로그인·로그아웃으로 회원 1 + 비회원 1이 되면 넘을 수 있다(9장).
- 비회원이 무작위 검색어로 0건 기록 상한(한 주기 서로 다른 검색어 1,000개)을 채우면 그 주기의 다른 후보가 `dropped`로 빠진다. 잃는 것은 그 주기의 진단 로그뿐이다.
- lecture-app을 가격 필드 이전(#885 전) 코드로 되돌리면 그 사이 수정은 `priceHp` 없이 더 새 version으로 와서 가격이 이전 값으로 남는다. 다음 강의 수정이나 재색인까지 낡은 가격이다(#909).
- 비회원 limiter는 키를 10,000개까지 두고 넘으면 오래 안 쓴 키를 지운다. 그보다 많은 IP를 돌리면 밀려난 키가 새 한도로 시작한다. IP 1만 개 이상을 쓰는 남용은 IP별 한도로 막을 수 없는 규모이고, 지금 운영 도메인에는 IPv6(AAAA)가 없다.

## 15. 강의 보정과 처음 적재·재색인 (#884)

강의 이벤트를 놓쳐도 강의 index가 원본과 맞게 하고, 운영에 강의 index를 무중단으로 처음 적재·재색인한다. 흐름은 인플루언서(6·7장)와 같고, 강의·리뷰에는 변경분 API가 없어 **키셋 전체 읽기 + 비교**로 한다(챗봇 강의 싱크 #717과 같은 방식).

### 보정

| 보정 | 주기·락 | 하는 일 |
| --- | --- | --- |
| 강의(`LectureReconciler`) | 끝난 뒤 10분(기동 3분 뒤), `JobLock` 884_001 | 강의 동기화 API를 500개씩 끝까지 읽고, 페이지마다 문서를 한 번에 읽어(mget) 문서가 없거나 강의 version이 작은(없는) 강의만 이벤트와 같은 `LectureIndexer.apply`로 쓴다. 끝까지 읽은 뒤 보이는 문서 중 원본에 없는 강의를 숨긴다 |
| 판매자 사본(`SellerChangeReconciler`) | 끝난 뒤 10분(기동 4분 뒤), `JobLock` 884_003 | 회원 변경분(#769)을 자기 커서(`search.sync_cursor`의 `seller-changes`)부터 읽어, 사본보다 새 version인 회원만 이벤트와 같은 `SellerIndexer.apply`로 반영한다 |
| 리뷰(`LectureReviewReconciler`) | 끝난 뒤 1시간(기동 5분 뒤), `JobLock` 884_002 | 리뷰 동기화 API를 끝까지 읽어 리뷰 사본에 version 비교로 반영하고, 원본에 없는 리뷰는 사본에서 지운다. 이어서 **모든 문서의 판매자·평점 칸을 사본과 맞춘다**(`LectureCopyAligner`) |

- **비교는 한 곳**(`LectureReconciler.needsWrite`)이다. 같은 version인데 원본 가격이 있고 문서 가격과 다르면 다시 쓰는 조건은 강의 문서에 가격 칸이 생긴 뒤(#909)에만 넣을 수 있다. 가격 칸 없이 넣으면 모든 강의가 다르게 보여 10분마다 전체를 다시 쓴다. #909의 같은 version 가격 고치기는 강의 쓰기 결과가 `NOOP`이라, 보정 건수는 쓰기 결과가 아니라 쓰기 전 비교로 센다.
- **원본에 없는 문서 숨기기**: 강의는 Soft Delete라 평소에는 없다(강의 DB를 되돌린 경우). 읽는 동안 생긴 강의(마지막으로 읽은 ID보다 큰 ID)는 건너뛰고, 원본이 0개면 숨기지 않는다(강의 앱 응답이 잘못돼 모두 숨기지 않게). 숨길 때 **강의 version을 지운다.** IDENTITY는 먼저 받은 ID가 늦게 커밋될 수 있어 원본에 있는 강의를 잘못 숨길 수 있는데, version을 지워 두면 다음 보정·이벤트가 같은 version으로도 다시 보이게 한다.
- **평점 칸 맞추기**: 평점 칸은 판매자 칸(`sellerVersion`)과 달리 version 보호가 없어, 리뷰 반영(소비자)과 보정·따라잡기(다른 스레드)가 같은 강의를 동시에 세면 먼저 읽고 늦게 쓴 옛 평균이 남을 수 있다. 잠금(강의별 DB 잠금)이나 version(사본 갱신 번호)을 두지 않고, 리뷰 보정이 모든 문서를 리뷰 사본 집계와 비교해 최대 1시간 안에 바로잡는다. 잠금은 모든 평점 쓰기가 DB 트랜잭션 안에서 ES를 기다리게 하고, 갱신 번호는 커밋 순서가 번호 순서와 다를 때 구멍이 남는다.
- **판매자 칸 맞추기**: 회원 사본에 없는 판매자는 건너뛰고, 쓰기는 회원 version 조건이라 문서가 더 새 값이면 noop이다. 회원 이벤트가 사본은 고쳤지만 문서 쓰기가 실패해 DLT로 간 경우, 판매자 보정은 사본이 이미 그 version이라 건너뛰고 이 칸 맞추기가 고친다.
- **판매자 사본 보정이 인플루언서 증분 보정과 따로인 이유**: 인플루언서 index가 없거나 그쪽 쓰기가 실패해도 판매자 보정이 돌게 하고, "대상은 공통만 참조한다"(3장)를 지킨다. 같은 회원 변경분 API를 10분마다 한 번 더 읽는다(변경이 없으면 요청 1번). 같은 version을 다시 반영하면 사본이 재시도로 보고 `update_by_query`를 다시 돌리므로, 페이지마다 사본 version을 먼저 읽어 이미 반영한 회원은 부르지 않는다.
- 사본(DB)은 alias가 없어도 맞춘다. 문서 쓰기·칸 맞추기만 처음 적재 전이면 건너뛴다(INFO, 7장 "처음 적재하는 동안"과 같다).
- 보정한 항목은 하나마다 INFO 한 줄(`강의 이벤트를 놓친 강의 검색 문서를 보정했다`, `원본에 없는 강의의 검색 문서를 숨겼다`, `리뷰 이벤트를 놓친 리뷰 사본을 보정했다`, `회원 이벤트를 놓친 판매자 정보를 강의 검색 문서에 보정했다`, `… 판매자 칸을 고쳤다`, `… 평점 칸을 고쳤다`), 회차마다 INFO 한 줄로 종류별 건수를 남긴다. 지표는 9장 `hapbang.search.lecture.reconcile.*`다.
- 실패: 강의·회원 앱이나 ES를 부르지 못하면 회차를 멈추고(WARN) 다음 회차에 처음부터(판매자는 저장한 커서부터) 다시 한다. ES가 그 강의 값 때문에 거절한 강의는 건너뛰고 센다(`skipped`).

### 처음 적재·재색인

공통 `Reindexer`(7장)가 하고, 강의는 채우기(`LectureIndexLoader`)와 따라잡기(`LectureIndexCatchUp`)만 넘긴다. 기동 뒤 다른 스레드에서 한 번(`LectureIndexStartup`), 그 뒤 10분마다 확인한다(`LectureSearchBatch`, 인플루언서와 같은 `hapbang.search.reindex-check-interval`). 락은 `JobLock` 884_004라 인플루언서 처음 적재와 함께 돈다. 지표·로그의 `target=lecture`다.

1. (공통) 남은 index를 지우고, 시각 T를 기록한 뒤 새 index를 alias 없이 만든다.
2. **회원 사본 맞추기**: 회원 목록 API(모든 회원)를 끝까지 읽어 회원 사본에 version 비교로 반영한다. 판매자 칸을 채우기 전에 해야 기존 판매자의 닉네임·정지 상태가 빠지지 않는다.
3. **리뷰 사본 맞추기**: 리뷰 보정과 같은 코드로 리뷰 동기화 API 전체를 반영한다.
4. **강의 넣기**: 강의 동기화 API를 끝까지 읽어 페이지마다 그 판매자·평점 사본을 한 번에 읽고 bulk로 넣는다. 강의 이벤트와 같은 script·값이라 #909의 가격도 같이 들어가고, 판매 중이 아닌 강의는 숨김 문서다.
5. (공통) refresh 뒤 넣은 판매 중 강의 수와 보이는 문서 수(`hidden=false`)를 비교하고 alias를 한 요청으로 옮긴다.
6. **따라잡기**: 강의 보정 한 번 + 판매자·평점 칸 맞추기 한 번. 채우는 동안의 강의 이벤트는 옛 index로 갔거나(재색인) 건너뛰었고(처음 적재), 회원·리뷰 이벤트는 사본만 고쳤다. 변경분 API가 없어 시각이 아니라 전체 비교로 따라잡는다(겹쳐 읽기 없음). 강의 정기 보정과 같은 락이라 그 보정이 돌고 있으면 5초마다 최대 5분 다시 부른다.

- **사본은 "비어 있을 때만"이 아니라 처음 적재·재색인마다 맞춘다**(#881의 처음 적재 순서 "사본이 비어 있으면 채움"을 바꿈). 운영 사본은 #882 배포 뒤 들어온 이벤트로 일부만 차 있어 비었는지로 고르면 그 전 판매자·리뷰가 빠진다. 이미 같은 행은 version으로 그대로라 결과가 같고, 처음 적재와 재색인이 같은 길이 된다. 재색인은 사전·mapping이 바뀔 때만 돌아 드물고, 회원 목록 읽기는 인플루언서 재색인도 하는 일이다.
- 기존 강의의 가격(#885 전에 발행된 이벤트로 넣은 문서)은 처음 적재가 강의 동기화 API 전체를 읽어 채운다(#913). 강의 쓰기는 같은 version이면 noop이라 가격 칸이 생긴 뒤 보정만으로는 채워지지 않는다(#909가 같은 version 가격 고치기를 더한다).
- 실패하면 alias는 그대로(처음 적재면 alias 없음), 새 index를 지우고 10분 뒤 다시 확인한다(7장과 같다).

### 처음 적재 직후

- 적재 직후 최대 1분은 평균 C를 아직 몰라 검색어 검색이 평점 가산 없이 정렬되고, 평점순만 503이다(14장 한계, #883). C를 구하지 못하면 1분 동안 다시 묻지 않기 때문이다.
- 처음 적재가 끝나기 전 검색 API는 503 `SEARCH_UNAVAILABLE`이다. 운영에서 강의 검색이 열리는 때는 처음 적재가 끝난 때다(로그 `검색 index를 처음 적재 끝냈다 target=lecture`).

### 한계

- 보정 건수는 페이지 앞에서 한 번 읽은 문서와의 비교로 센다. 그 사이 같은 강의의 이벤트가 먼저 반영되면 놓친 이벤트가 아닌데도 보정으로 셀 수 있다(인플루언서 6장 한계와 같다).
- 강의·리뷰 보정은 매 회차 전체를 읽는다. 지금 규모(강의 수백~수천, 리뷰 수천)에서는 수 초다. 커지면 `reconcile.duration`을 보고 변경분 API(`lecture.updated_at` 인덱스 + `lecture_review.sync_changed_at`)를 검토한다. 보정은 인플루언서 증분 보정처럼 앱 공용 스케줄러 스레드에서 돈다.
- 판매자 사본 커서도 처음에는 지금 시각부터 읽는다. 첫 회차 전에 놓친 회원 이벤트는 처음 적재의 회원 사본 맞추기와 다음 재색인이 맞춘다.
