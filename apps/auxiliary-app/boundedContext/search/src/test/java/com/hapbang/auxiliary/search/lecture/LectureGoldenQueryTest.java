package com.hapbang.auxiliary.search.lecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.hapbang.auxiliary.search.lecture.app.LectureSearchCondition;
import com.hapbang.auxiliary.search.lecture.app.LectureSort;
import com.hapbang.auxiliary.search.lecture.app.dto.LectureSearchItem;
import com.hapbang.auxiliary.search.lecture.config.LectureRankingProperties;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.LectureSearcher;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * 강의 golden query(#883 L11·L20): 대표 검색어로 순위를 고정하고, 같은 표본에 기존 강의 목록 검색({@code GET /api/v1/lectures?keyword=})의
 * LIKE 규칙을 재현해 비교한다. 실제 색인·검색 코드({@link LectureDocumentStore}, {@link LectureSearcher})와 운영 index 정의(사전·동의어)를 쓴다.
 * <ul>
 *     <li>운영 계수({@link LectureRankingProperties} 기본값)로 모든 케이스가 통과해야 한다.</li>
 *     <li>LIKE 규칙(강의 앱 {@code LectureRepository.ON_SALE_WITH_KEYWORD}): 판매 중 강의의 제목에 검색어가 그대로 들어 있는지(대소문자 무시),
 *     최신순. 판매자 상태는 보지 않는다.</li>
 *     <li>비교 표({@code build/golden-query-lecture.md}): 케이스마다 찾아야 할 강의 중 찾은 수, 앞 3개 중 정답 수, 나오면 안 되는
 *     강의(판매 중지·정지 판매자)가 섞였는지. 평점 가산 계수 격자도 함께 쓴다. 인플루언서 표({@code build/golden-query.md})와 파일을 나눠
 *     서로 덮어쓰지 않는다.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LectureGoldenQueryTest {

    private static final LectureRankingProperties DEFAULT = new LectureRankingProperties(null, null, null);
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");

    record Doc(long id, String title, String description, List<String> contents, String seller, Double rating,
               long reviews, boolean onSale, boolean sellerActive) {
    }

    private static Doc doc(long id, String title, String description, List<String> contents, String seller,
            Double rating, long reviews) {
        return new Doc(id, title, description, contents, seller, rating, reviews, true, true);
    }

    /** 16은 판매 중지, 17은 정지된 판매자의 강의다(둘 다 "프리미어"가 제목에 있어 검색어가 맞아도 빠지는지 본다) */
    static final List<Doc> DOCS = List.of(
            doc(1, "프리미어 프로 컷편집 입문", "처음 영상 편집을 배우는 분을 위한 강의", List.of("1강 설치", "2강 컷편집 단축키", "3강 자막 넣기"), "편집장", 4.8, 40),
            doc(2, "유튜브 영상 편집 A to Z", "프리미어로 브이로그를 편집합니다", List.of("1강 프리미어 화면", "2강 컷 편집", "3강 색보정"), "편집장", 4.5, 12),
            doc(3, "다빈치 리졸브 색보정", "무료 편집 프로그램으로 색감 잡기", List.of("1강 설치", "2강 컬러 휠"), "컬러리스트", 4.6, 8),
            doc(4, "캡컷으로 쇼츠 만들기", "휴대폰으로 숏폼 편집", List.of("1강 캡컷 기본", "2강 자막 템플릿"), "숏폼장인", 4.2, 20),
            doc(5, "OBS 방송 세팅 완벽 가이드", "치지직과 트위치 송출 설정", List.of("1강 OBS 설치", "2강 장면 구성", "3강 크로마키"), "방송기술자", 4.7, 25),
            doc(6, "치지직 스트리머 시작하기", "방송을 처음 켜는 분을 위한 강의", List.of("1강 OBS 연결", "2강 도네이션 설정"), "방송기술자", 4.0, 3),
            doc(7, "썸네일 디자인 클래스", "포토샵으로 클릭을 부르는 썸네일", List.of("1강 포토샵 기초", "2강 글씨 배치"), "디자인누나", 4.9, 1),
            doc(8, "포토샵 기초", "이미지 보정과 합성", List.of("1강 레이어"), "디자인누나", 4.4, 30),
            doc(9, "배틀그라운드 에임 강의", "PUBG 에임 연습 루틴", List.of("1강 감도 설정"), "배그장인", 4.3, 15),
            doc(10, "롤 정글 강의", "리그 오브 레전드 정글 동선", List.of("1강 초반 동선"), "협곡지배자", 4.5, 18),
            doc(11, "LoL 라인전 기초", "라인 관리와 견제", List.of(), "협곡지배자", 3.9, 6),
            doc(12, "버튜버 데뷔 가이드", "Live2D 모델과 방송 세팅", List.of("1강 모델 준비", "2강 OBS 연동"), "브이튜버랩", 4.6, 10),
            doc(13, "브이로그 촬영과 편집", "일상 브이로그 만들기", List.of("1강 촬영 구도", "2강 프리미어 컷편집"), "편집장", 4.1, 5),
            doc(14, "방송 음향 세팅", "마이크와 오디오 인터페이스", List.of("1강 마이크 고르기"), "사운드엔지니어", 4.3, 9),
            doc(15, "유튜브 채널 성장 전략", "알고리즘과 썸네일·제목", List.of("1강 채널 기획", "2강 썸네일 A/B"), "성장코치", 4.7, 22),
            new Doc(16, "프리미어 고급 모션그래픽", "판매 중지된 강의", List.of(), "편집장", 4.9, 50, false, true),
            new Doc(17, "프리미어 이펙트 모음", "정지된 판매자의 강의", List.of(), "정지된판매자", 5.0, 80, true, false),
            doc(18, "먹방 촬영 노하우", "먹방 조명과 카메라", List.of(), "먹방요정", 4.0, 4),
            doc(19, "합방 섭외 잘하는 법", "인플루언서에게 합방 제안서 쓰기", List.of(), "섭외왕", 4.4, 7),
            doc(20, "메이플스토리 보스 공략 방송", "메이플 방송 콘텐츠 기획", List.of(), "메이플용사", 4.2, 6),
            // 21·22: 글이 똑같고 평점만 다르다 → 평점이 가른다(평점 가산 하한 근거)
            doc(21, "라이브 방송 기획", "방송 기획서 쓰기", List.of(), "기획자A", 4.8, 30),
            doc(22, "라이브 방송 기획", "방송 기획서 쓰기", List.of(), "기획자B", 3.5, 30),
            // 23·24: 23이 글이 조금 더 맞고(짧은 제목) 평점이 낮다 → 글이 이겨야 한다(평점 가산 상한 근거)
            doc(23, "OBS 송출", "기본 설정", List.of(), "송출러A", 3.0, 40),
            doc(24, "OBS 송출 설정 방법 총정리", "기본 설정", List.of(), "송출러B", 5.0, 40),
            doc(25, "스트리머 장비 총정리", "음향 장비와 조명", List.of("1강 마이크", "2강 조명"), "장비리뷰어", 4.5, 14),
            doc(26, "쇼츠 기획과 편집", "숏폼 알고리즘 이해", List.of("1강 캡컷 컷편집"), "숏폼장인", 4.3, 11));

    enum Mode { ORDER, SET }

    /**
     * @param expected       ORDER면 결과 앞부분이 이 순서, SET이면 결과에 모두 있다(LIKE 비교의 정답 목록이기도 하다)
     * @param mustNotContain 결과에 없어야 하는 ID
     */
    record Case(String name, String keyword, LectureSort sort, Mode mode, List<Long> expected, List<Long> mustNotContain) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Long> HIDDEN = List.of(16L, 17L);

    static final List<Case> CASES = List.of(
            new Case("L01 '프리미어' → 제목(1)·소개와 콘텐츠(2)·콘텐츠만(13) 순, 판매 중지·정지 판매자 제외", "프리미어", LectureSort.DEFAULT, Mode.ORDER, List.of(1L, 2L, 13L), HIDDEN),
            new Case("L02 '컷편집' → 제목에 있는 1이 먼저, 콘텐츠의 '컷 편집'·'컷편집'도 찾음", "컷편집", LectureSort.DEFAULT, Mode.SET, List.of(1L, 2L, 13L, 26L), List.of()),
            new Case("L03 '편집' → 제목·소개·콘텐츠 어디든", "편집", LectureSort.DEFAULT, Mode.SET, List.of(2L, 13L, 26L, 1L, 3L, 4L), HIDDEN),
            new Case("L04 줄임말 '배그' → 배틀그라운드", "배그", LectureSort.DEFAULT, Mode.ORDER, List.of(9L), List.of()),
            new Case("L05 '롤' → 롤·LoL 제목, 평점 높은 10 먼저", "롤", LectureSort.DEFAULT, Mode.ORDER, List.of(10L, 11L), List.of()),
            new Case("L06 'obs'(소문자) → 제목·콘텐츠", "obs", LectureSort.DEFAULT, Mode.SET, List.of(5L, 6L, 12L, 23L, 24L), List.of()),
            new Case("L07 '방송 세팅' → 두 말이 모두 제목에 있는 강의", "방송 세팅", LectureSort.DEFAULT, Mode.SET, List.of(5L, 14L, 12L), List.of()),
            new Case("L08 '썸네일' → 제목(7)이 소개·콘텐츠(15)보다 위", "썸네일", LectureSort.DEFAULT, Mode.ORDER, List.of(7L, 15L), List.of()),
            new Case("L09 '포토샵' → 제목(8)이 소개·콘텐츠(7)보다 위", "포토샵", LectureSort.DEFAULT, Mode.ORDER, List.of(8L, 7L), List.of()),
            new Case("L10 '브이튜버' 동의어 → 버튜버 강의", "브이튜버", LectureSort.DEFAULT, Mode.ORDER, List.of(12L), List.of()),
            new Case("L11 판매자 닉네임 '편집장'", "편집장", LectureSort.DEFAULT, Mode.SET, List.of(1L, 2L, 13L), HIDDEN),
            new Case("L12 '치지직' → 제목(6)이 소개(5)보다 위", "치지직", LectureSort.DEFAULT, Mode.ORDER, List.of(6L, 5L), List.of()),
            new Case("L13 '자막' → 콘텐츠 제목만 있는 강의", "자막", LectureSort.DEFAULT, Mode.SET, List.of(1L, 4L), List.of()),
            new Case("L14 '인플루언서' 사용자 사전 → 소개", "인플루언서", LectureSort.DEFAULT, Mode.ORDER, List.of(19L), List.of()),
            new Case("L15 '메이플' 동의어 → 메이플스토리", "메이플", LectureSort.DEFAULT, Mode.ORDER, List.of(20L), List.of()),
            new Case("L16 '라이브 방송 기획' → 글이 같으면 평점 높은 21이 먼저", "라이브 방송 기획", LectureSort.DEFAULT, Mode.ORDER, List.of(21L, 22L), List.of()),
            new Case("L17 '송출' → 글이 더 맞는 23(평점 3.0)이 24(5.0)보다 위", "송출", LectureSort.DEFAULT, Mode.ORDER, List.of(23L, 24L), List.of()),
            new Case("L18 '마이크' → 콘텐츠·소개", "마이크", LectureSort.DEFAULT, Mode.SET, List.of(14L, 25L), List.of()),
            new Case("L19 평점순(베이지안 m=5) → 리뷰 많은 5.0(24)·4.8(1·21) 순, 리뷰 1건 4.9(7)는 위로 못 옴, 숨김 제외", null, LectureSort.RATING, Mode.ORDER, List.of(24L, 1L, 21L), HIDDEN),
            new Case("L20 검색어 없음 → 최신순(ID 내림차순), 숨김 제외", null, LectureSort.DEFAULT, Mode.ORDER, List.of(26L, 25L, 24L), HIDDEN));

    private final ElasticsearchClient client = SearchElasticsearch.client();

    private double ratingBaseline;

    @BeforeAll
    void load() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        LectureDocumentStore store = new LectureDocumentStore(client);
        for (Doc doc : DOCS) {
            List<LectureContentSyncDto> contents = new ArrayList<>();
            for (int i = 0; i < doc.contents().size(); i++) {
                contents.add(new LectureContentSyncDto((long) i + 1, doc.contents().get(i), i + 1));
            }
            long sellerUserId = 1_000 + doc.id();
            store.writeLecture(new LectureSyncDto(doc.id(), sellerUserId, doc.title(), doc.description(), doc.onSale(),
                            false, contents, 1L),
                    new Seller(sellerUserId, doc.seller(), doc.sellerActive()),
                    doc.rating() == null ? LectureRating.NONE : new LectureRating(doc.rating(), doc.reviews()), NOW);
        }
        client.indices().refresh(r -> r.index(LectureIndexDefinition.ALIAS));
        ratingBaseline = new LectureSearcher(client, DEFAULT).ratingBaseline();
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
    @DisplayName("평점 전체 평균 C는 검색에 보이는 강의(판매 중·정상 판매자)의 리뷰 수 가중 평균이다")
    void ratingBaseline() {
        double sum = 0;
        double count = 0;
        for (Doc doc : DOCS) {
            if (doc.onSale() && doc.sellerActive() && doc.rating() != null) {
                sum += doc.rating() * doc.reviews();
                count += doc.reviews();
            }
        }

        assertThat(ratingBaseline).isCloseTo(sum / count, org.assertj.core.data.Offset.offset(1e-6));
    }

    /**
     * LIKE 비교와 평점 가산 계수 격자를 표로 남긴다. 근거:
     * <ul>
     *     <li>b가 0이면 글이 같은 21·22를 평점이 가르지 못한다(L16, 하한).</li>
     *     <li>b가 크면 글이 조금 더 맞는 23보다 평점 높은 24가 위로 온다(L17, 상한). 가산이 베이지안 평균/5로 묶여 있어 글 차이가 크면 b가
     *     아무리 커도 뒤집히지 않는다.</li>
     * </ul>
     */
    @Test
    @DisplayName("LIKE보다 찾은 수·앞 3개 중 정답 수가 많고, 계수 격자에서 운영값은 모두 통과한다(b=0은 L16, 큰 b는 L17이 깨짐)")
    void likeComparisonAndGrid() throws IOException {
        StringBuilder table = new StringBuilder("## 강의 검색 golden query (#883)\n\n")
                .append("표본 %d개(판매 중지 1, 정지 판매자 1 포함), C = %.4f, m = %d, b = %s\n\n"
                        .formatted(DOCS.size(), ratingBaseline, DEFAULT.ratingPriorReviews(), DEFAULT.ratingBoost()))
                .append("### LIKE(기존 /lectures?keyword=) 대비\n\n")
                .append("찾은 수 = 찾아야 할 강의 중 결과(앞 20개)에 나온 수. 앞 3 = 결과 앞 3개 중 정답 수(최대 min(3, 정답 수)). 섞임 = 판매 중지·정지 판매자 강의가 나옴\n\n")
                .append("| 케이스 | 검색어 | 정답 | LIKE 찾은 수 | LIKE 앞 3 | LIKE 섞임 | ES 찾은 수 | ES 앞 3 | ES 섞임 |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        int likeFound = 0;
        int esFound = 0;
        int likeTop3 = 0;
        int esTop3 = 0;
        int total = 0;
        int top3Max = 0;
        for (Case golden : CASES) {
            if (golden.keyword() == null) {
                continue;
            }
            List<Long> like = like(golden.keyword());
            List<Long> es = search(golden, DEFAULT);
            int lf = found(golden, like);
            int ef = found(golden, es);
            int lt = top3(golden, like);
            int et = top3(golden, es);
            likeFound += lf;
            esFound += ef;
            likeTop3 += lt;
            esTop3 += et;
            total += golden.expected().size();
            top3Max += Math.min(3, golden.expected().size());
            table.append("| %s | %s | %d | %d | %d | %s | %d | %d | %s |\n".formatted(golden.name().substring(0, 3),
                    golden.keyword(), golden.expected().size(), lf, lt, leak(golden, like), ef, et, leak(golden, es)));
        }
        table.append("| **합계** | | %d | **%d** | **%d/%d** | | **%d** | **%d/%d** | |\n\n"
                .formatted(total, likeFound, likeTop3, top3Max, esFound, esTop3, top3Max));

        double[] bs = {0.0, 0.05, 0.1, 0.2, 0.5, 1.0, 5.0};
        table.append("### 평점 가산 계수 b 격자\n\n| b | 통과 |\n|---|---|\n");
        for (double b : bs) {
            List<String> failed = failedCases(new LectureRankingProperties(b, null, null));
            table.append("| ").append(b).append(" | ").append(CASES.size() - failed.size()).append('/').append(CASES.size())
                    .append(failed.isEmpty() ? "" : " (" + String.join(", ", failed) + ")").append(" |\n");
        }
        Path out = Path.of("build", "golden-query-lecture.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table, StandardCharsets.UTF_8);

        assertThat(esFound).isGreaterThan(likeFound);
        assertThat(esTop3).isGreaterThan(likeTop3);
        assertThat(failedCases(DEFAULT)).isEmpty();
        assertThat(failedCases(new LectureRankingProperties(0.0, null, null))).contains("L16");
        assertThat(failedCases(new LectureRankingProperties(5.0, null, null))).contains("L17");
    }

    /** 강의 앱 LIKE 규칙: 판매 중 + 제목에 검색어 그대로(대소문자 무시) + 최신순. 판매자 상태는 보지 않는다 */
    private static List<Long> like(String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        return DOCS.stream().filter(Doc::onSale).filter(doc -> doc.title().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparingLong(Doc::id).reversed()).limit(20).map(Doc::id).toList();
    }

    private static int found(Case golden, List<Long> ids) {
        return (int) golden.expected().stream().filter(ids::contains).count();
    }

    /** 결과 앞 3개 중 정답 수 */
    private static int top3(Case golden, List<Long> ids) {
        return (int) ids.subList(0, Math.min(3, ids.size())).stream().filter(golden.expected()::contains).count();
    }

    private static String leak(Case golden, List<Long> ids) {
        List<Long> leaked = golden.mustNotContain().stream().filter(ids::contains).toList();
        return leaked.isEmpty() ? "" : leaked.toString();
    }

    private List<String> failedCases(LectureRankingProperties ranking) {
        List<String> failed = new ArrayList<>();
        for (Case golden : CASES) {
            if (failure(golden, search(golden, ranking)) != null) {
                failed.add(golden.name().substring(0, 3));
            }
        }
        return failed;
    }

    private List<Long> search(Case golden, LectureRankingProperties ranking) {
        LectureSearchCondition condition = new LectureSearchCondition(golden.keyword(), golden.sort(), 0, 20);
        return new LectureSearcher(client, ranking).search(condition, ratingBaseline, 1_000).items().stream()
                .map(LectureSearchItem::lectureId).toList();
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
