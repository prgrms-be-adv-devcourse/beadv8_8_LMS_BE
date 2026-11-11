package com.hapbang.auxiliary.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.hapbang.auxiliary.search.app.InfluencerSearchCondition;
import com.hapbang.auxiliary.search.app.InfluencerSort;
import com.hapbang.auxiliary.search.app.dto.InfluencerSearchItem;
import com.hapbang.auxiliary.search.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.out.InfluencerSearcher;
import com.hapbang.auxiliary.search.out.InfluencerStats;
import com.hapbang.auxiliary.search.out.MemberProfile;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * golden query: 대표 검색어로 순위를 고정한다(#771, #767 Q23). 시험 코드 {@code GoldenQueryTest}의 표본 20명·케이스 24개를 실제 색인·검색
 * 코드({@link InfluencerDocumentStore}, {@link InfluencerSearcher})와 운영 index 정의(사전·동의어)로 옮겼다.
 * <ul>
 *     <li>운영 계수({@link SearchRankingProperties} 기본값: 팔로워 가산 a=0.05, 인증 1.05, 베이지안 m=5)로 모든 케이스가 통과해야 한다.</li>
 *     <li>G01b는 알려진 한계다: 사전에 없는 지어낸 닉네임 "롤러스케이터"가 롤/러스/케이/트로 쪼개져 "롤"에 걸린다. 지금 동작을 고정해 두고,
 *     바뀌면(사전 보강 등) 이 케이스를 고친다.</li>
 *     <li>계수 격자({@link #coefficientGrid()})로 a의 하한·상한 근거를 남긴다. 표는 {@code build/golden-query.md}에 쓴다.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenQueryTest {

    private static final SearchRankingProperties DEFAULT = new SearchRankingProperties(null, null, null, null);

    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("GAME", "게임"), Map.entry("MUKBANG", "먹방"), Map.entry("JUST_CHATTING", "저스트채팅"),
            Map.entry("MUSIC", "음악"), Map.entry("SPORTS", "스포츠"), Map.entry("EDUCATION", "교육·지식"),
            Map.entry("DAILY_LIFE", "일상"), Map.entry("LEAGUE_OF_LEGENDS", "리그 오브 레전드"), Map.entry("VALORANT", "발로란트"),
            Map.entry("MAPLESTORY", "메이플스토리"), Map.entry("BATTLEGROUNDS", "배틀그라운드"), Map.entry("LOST_ARK", "로스트아크"),
            Map.entry("OVERWATCH", "오버워치"), Map.entry("FC_ONLINE", "FC 온라인"), Map.entry("DUNGEON_FIGHTER", "던전앤파이터"),
            Map.entry("SINGING", "노래"), Map.entry("INSTRUMENT", "악기 연주"), Map.entry("COOKING", "요리"), Map.entry("TRAVEL", "여행"));

    record Doc(long id, String nickname, List<String> channels, List<String> codes, String bio, long followers,
               Double rating, long ratingCount, boolean verified, boolean hidden) {
    }

    /** 시험 표본 그대로. 17은 숨긴 문서다(닉네임을 남긴 채 숨겨 hidden 필터만으로 빠지는지 본다) */
    static final List<Doc> DOCS = List.of(
            new Doc(1, "페이커팬", List.of("페이커팬TV"), List.of("LEAGUE_OF_LEGENDS"), "롤 챌린저 강의", 50_000, 4.8, 20, true, false),
            new Doc(2, "롤하는곰", List.of("곰의 협곡"), List.of("LEAGUE_OF_LEGENDS", "GAME"), "리그 오브 레전드 다이아 방송", 1_200, 4.2, 3, false, false),
            new Doc(3, "협곡지배자", List.of("협곡지배자"), List.of("LEAGUE_OF_LEGENDS"), "LoL 프로 출신 코치", 300_000, 4.5, 50, true, false),
            new Doc(4, "먹방요정", List.of("요정의 식탁"), List.of("MUKBANG"), "매일 먹방 라이브", 800_000, 4.9, 2, false, false),
            new Doc(5, "mukbang king", List.of("킹먹"), List.of("MUKBANG", "COOKING"), "대식가 mukbang", 20_000, 4.6, 40, false, false),
            new Doc(6, "메이플용사", List.of("메이플 아저씨"), List.of("MAPLESTORY"), "메이플스토리 보스 공략", 90_000, 4.7, 15, false, false),
            new Doc(7, "단풍잎", List.of("단풍잎채널"), List.of("GAME"), "메이플 가끔 하는 종합게임", 400_000, 3.9, 100, true, false),
            new Doc(8, "배그장인", List.of("치킨사냥꾼"), List.of("BATTLEGROUNDS"), "PUBG 랭커", 150_000, 4.4, 30, false, false),
            new Doc(9, "옵치하는누나", List.of("누나TV"), List.of("OVERWATCH", "JUST_CHATTING"), "오버워치 탱커 저챗", 60_000, 5.0, 1, false, false),
            new Doc(10, "노래하는인플루언서", List.of("음색맛집"), List.of("SINGING", "MUSIC"), "인플루언서 합방 환영 커버곡", 3_000, 4.3, 12, false, false),
            new Doc(11, "기타치는형", List.of("기타형"), List.of("INSTRUMENT", "MUSIC"), "악기 연주 강의", 8_000, 4.8, 25, false, false),
            new Doc(12, "여행가는요리사", List.of("길 위의 부엌"), List.of("TRAVEL", "COOKING"), "여행지 요리", 45_000, 4.1, 6, false, false),
            new Doc(13, "발로란트 코치", List.of("에임교실"), List.of("VALORANT", "EDUCATION"), "발로 에임 강의", 22_000, 4.6, 18, false, false),
            new Doc(14, "로아고인물", List.of("아크라시아"), List.of("LOST_ARK"), "로스트아크 레이드", 70_000, 4.0, 9, false, false),
            new Doc(15, "치지직새내기", List.of("새싹방송"), List.of("JUST_CHATTING", "DAILY_LIFE"), "치지직 스트리머 일상", 500, null, 0, false, false),
            new Doc(16, "롤러스케이터", List.of("바퀴위"), List.of("SPORTS", "DAILY_LIFE"), "롤러스케이트 브이로그", 100_000, 3.8, 5, false, false),
            new Doc(17, "롤숨김", List.of(), List.of("LEAGUE_OF_LEGENDS"), null, 999_999, 5.0, 100, true, true),
            new Doc(18, "던파한량", List.of("던전방"), List.of("DUNGEON_FIGHTER"), "던전앤파이터 공략", 15_000, 4.2, 4, false, false),
            new Doc(19, "피파왕", List.of("FC온라인 챔피언"), List.of("FC_ONLINE"), "FC 온라인 감독모드", 25_000, 4.5, 10, false, false),
            new Doc(20, "음악하는사람", List.of("멜로디"), List.of("MUSIC"), "작곡 브이로그", 200_000, 4.0, 8, false, false));

    enum Mode { ORDER, SET }

    /**
     * @param expected       ORDER면 결과 앞부분이 이 순서, SET이면 결과에 모두 있다
     * @param mustNotContain 결과에 없어야 하는 ID
     */
    record Case(String name, String keyword, List<String> categories, Boolean verified, InfluencerSort sort, Mode mode,
                List<Long> expected, List<Long> mustNotContain) {

        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Case> CASES = List.of(
            new Case("G01 줄임말 '롤' → 닉네임 일치 먼저, 비슷하면 팔로워", "롤", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(2L, 3L, 1L), List.of(17L)),
            new Case("G02 정식 이름 '리그 오브 레전드'", "리그 오브 레전드", List.of(), null, InfluencerSort.DEFAULT, Mode.SET, List.of(1L, 2L, 3L), List.of(17L)),
            new Case("G03 영문 'LoL'", "LoL", List.of(), null, InfluencerSort.DEFAULT, Mode.SET, List.of(1L, 2L, 3L), List.of(17L)),
            new Case("G04 '먹방' → 닉네임 일치(4)가 먼저", "먹방", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(4L, 5L), List.of()),
            new Case("G05 'mukbang' 동의어 → 원문 일치와 동의어 일치 둘 다", "mukbang", List.of(), null, InfluencerSort.DEFAULT, Mode.SET, List.of(5L, 4L), List.of()),
            new Case("G06 '메이플' → 텍스트 관련도가 팔로워보다 우선(6이 팔로워 4배인 7보다 위)", "메이플", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(6L, 7L), List.of()),
            new Case("G07 '메이플스토리'", "메이플스토리", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(6L, 7L), List.of()),
            new Case("G08 '배그'", "배그", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(8L), List.of()),
            new Case("G09 '옵치'", "옵치", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(9L), List.of()),
            new Case("G10 사용자 사전 '치지직'", "치지직", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(15L), List.of()),
            new Case("G11 사용자 사전 '인플루언서'", "인플루언서", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(10L), List.of()),
            new Case("G12 '협곡' → 닉네임·채널(3)이 채널만(2)보다 위", "협곡", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(3L, 2L), List.of()),
            new Case("G13 채널 이름 '에임교실'", "에임교실", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(13L), List.of()),
            new Case("G14 '발로' 동의어", "발로", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(13L), List.of()),
            new Case("G15 '요리' → 닉네임·분야·소개 모두(12)가 분야만(5)보다 위", "요리", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(12L, 5L), List.of()),
            new Case("G16 '강의' 소개만 일치 → 팔로워로 가름", "강의", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(1L, 13L, 11L), List.of()),
            new Case("G17 '피파' 동의어(FC 온라인)", "피파", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(19L), List.of()),
            new Case("G18 검색어 없음 + 분야 MUSIC·SINGING → 일치 수 많은 순, 같으면 팔로워", null, List.of("MUSIC", "SINGING"), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(10L, 20L, 11L), List.of()),
            new Case("G19 검색어 '롤' + 인증만", "롤", List.of(), true, InfluencerSort.DEFAULT, Mode.ORDER, List.of(3L, 1L), List.of(2L, 17L)),
            new Case("G20 평점순(베이지안 m=5) → 리뷰 1건 5.0(9)은 위로 못 옴, 숨김(17) 제외", null, List.of(), null, InfluencerSort.RATING, Mode.ORDER, List.of(11L, 1L, 6L), List.of(17L)),
            new Case("G21 검색어 없음 → 팔로워 많은 순, 숨김 제외", null, List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(4L, 7L, 3L), List.of(17L)),
            new Case("G22 '코치' → 닉네임 일치 소규모(13, 2.2만)가 소개 일치 대형(3, 30만)보다 위", "코치", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(13L, 3L), List.of()),
            new Case("G23 '게임' → 분야 이름 일치(2,7) 중 팔로워 큰 7 먼저", "게임", List.of(), null, InfluencerSort.DEFAULT, Mode.ORDER, List.of(7L, 2L), List.of()));

    /** 알려진 한계(G01b): 지어낸 닉네임이 쪼개져 엉뚱한 검색어에 걸린다 */
    static final Case KNOWN_LIMIT = new Case("G01b '롤'에 롤러스케이터(16)가 걸림", "롤", List.of(), null,
            InfluencerSort.DEFAULT, Mode.SET, List.of(16L), List.of());

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private double ratingBaseline;

    @BeforeAll
    void load() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createIndex(client);
        InfluencerDocumentStore store = new InfluencerDocumentStore(client);
        for (Doc doc : DOCS) {
            store.show(doc.id(), 1L, new MemberProfile(doc.nickname(), doc.bio(),
                    doc.verified() ? "VERIFIED_INFLUENCER" : "INFLUENCER", doc.verified(),
                    doc.codes().stream().map(code -> new MemberProfile.Category(code, CATEGORY_NAMES.get(code))).toList()));
            store.applyStats(List.of(new InfluencerStats(doc.id(), doc.channels(),
                    doc.channels().isEmpty() ? null : doc.channels().getFirst(), doc.followers(), doc.rating(),
                    doc.ratingCount())));
            if (doc.hidden()) {
                client.update(u -> u.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(doc.id()))
                        .doc(Map.of("hidden", true)), Map.class);
            }
        }
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
        ratingBaseline = new InfluencerSearcher(client, DEFAULT).ratingBaseline();
    }

    static Stream<Case> cases() {
        return CASES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("운영 계수로 대표 검색어 순위가 기대와 같다")
    void goldenQuery(Case golden) {
        List<Long> ids = search(golden, DEFAULT);

        assertThat(failure(golden, ids)).as("결과 %s", ids).isNull();
    }

    @Test
    @DisplayName("알려진 한계 G01b: 사전에 없는 '롤러스케이터'가 '롤' 검색에 걸린다(바뀌면 이 테스트를 고친다)")
    void knownLimit() {
        assertThat(search(KNOWN_LIMIT, DEFAULT)).contains(16L);
    }

    @Test
    @DisplayName("평점 전체 평균 C는 숨기지 않은 문서의 리뷰 수 가중 평균이다(숨긴 17의 5.0·100건은 빠짐)")
    void ratingBaseline() {
        double sum = 0;
        double count = 0;
        for (Doc doc : DOCS) {
            if (!doc.hidden() && doc.rating() != null) {
                sum += doc.rating() * doc.ratingCount();
                count += doc.ratingCount();
            }
        }

        assertThat(ratingBaseline).isCloseTo(sum / count, org.assertj.core.data.Offset.offset(1e-6));
    }

    /**
     * 팔로워 가산 a와 인증 가산 v를 바꿔 가며 통과 수를 센다. 근거:
     * <ul>
     *     <li>a가 0이면 텍스트 점수가 거의 같은 G01·G19에서 팔로워가 순서를 가르지 못한다(하한).</li>
     *     <li>a가 0.5 이상이면(0.1이어도 인증 가산 1.1이면) 닉네임이 맞는 소규모(2, 1,200명)를 소개만 맞는 인증 대형(3, 30만)이
     *     넘어선다(G01, 상한). 시험(분야 이름 "리그오브레전드")에서는 상한이 1.0이었는데, 실제 분야 이름("리그 오브 레전드")이 동의어로
     *     더 많이 걸려 좁아졌다.</li>
     *     <li>그래서 운영값은 통과 구간(0.02~0.1)의 가운데인 0.05, 인증 가산은 0.1에서도 통과하는 1.05다.</li>
     * </ul>
     */
    @Test
    @DisplayName("계수 격자: 운영값(a=0.05, v=1.05)은 모두 통과하고, a=0은 팔로워가 순서를 못 가르며, a=0.5는 팔로워가 텍스트 일치를 뒤집는다")
    void coefficientGrid() throws IOException {
        double[] as = {0.0, 0.02, 0.05, 0.1, 0.5, 1.0, 5.0};
        double[] vs = {1.0, 1.05, 1.1};
        StringBuilder table = new StringBuilder("C = %.4f, m = %d\n\n| a | v=1.0 | v=1.05 | v=1.1 |\n|---|---|---|---|\n"
                .formatted(ratingBaseline, DEFAULT.ratingPriorReviews()));
        for (double a : as) {
            table.append("| ").append(a);
            for (double v : vs) {
                List<String> failed = failedCases(new SearchRankingProperties(a, v, null, null));
                table.append(" | ").append(CASES.size() - failed.size()).append('/').append(CASES.size())
                        .append(failed.isEmpty() ? "" : " (" + String.join(", ", failed) + ")");
            }
            table.append(" |\n");
        }
        Path out = Path.of("build", "golden-query.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table, StandardCharsets.UTF_8);

        assertThat(failedCases(DEFAULT)).isEmpty();
        assertThat(failedCases(new SearchRankingProperties(0.0, 1.05, null, null))).contains("G01", "G19");
        assertThat(failedCases(new SearchRankingProperties(0.5, 1.05, null, null))).contains("G01");
    }

    private List<String> failedCases(SearchRankingProperties ranking) {
        List<String> failed = new ArrayList<>();
        for (Case golden : CASES) {
            if (failure(golden, search(golden, ranking)) != null) {
                failed.add(golden.name().substring(0, 3));
            }
        }
        return failed;
    }

    private List<Long> search(Case golden, SearchRankingProperties ranking) {
        InfluencerSearchCondition condition = new InfluencerSearchCondition(golden.keyword(), golden.categories(),
                golden.verified(), null, null, golden.sort(), 0, 20);
        return new InfluencerSearcher(client, ranking).search(condition, ratingBaseline, 1_000).items().stream()
                .map(InfluencerSearchItem::id).toList();
    }

    /** 통과면 null, 아니면 이유 */
    private static String failure(Case golden, List<Long> ids) {
        List<Long> leaked = golden.mustNotContain().stream().filter(ids::contains).toList();
        if (!leaked.isEmpty()) {
            return "나오면 안 되는 " + leaked;
        }
        if (golden.mode() == Mode.SET) {
            return ids.containsAll(golden.expected()) ? null : "빠진 " + golden.expected();
        }
        List<Long> head = ids.subList(0, Math.min(golden.expected().size(), ids.size()));
        return head.equals(golden.expected()) ? null : "순서 " + head;
    }
}
