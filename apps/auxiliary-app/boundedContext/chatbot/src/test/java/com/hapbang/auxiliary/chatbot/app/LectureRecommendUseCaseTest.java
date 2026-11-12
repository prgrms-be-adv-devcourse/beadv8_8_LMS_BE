package com.hapbang.auxiliary.chatbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.micrometer.core.instrument.MeterRegistry;

import com.hapbang.auxiliary.chatbot.domain.IndexAction;
import com.hapbang.auxiliary.chatbot.domain.IndexType;
import com.hapbang.auxiliary.chatbot.domain.LectureRecommendReason;
import com.hapbang.auxiliary.chatbot.domain.RecommendBasis;
import com.hapbang.auxiliary.chatbot.out.RecommendVectorIndex;
import com.hapbang.auxiliary.chatbot.support.ChatbotIntegrationTestBase;
import com.hapbang.auxiliary.chatbot.support.ElasticsearchTestcontainersConfig;
import com.hapbang.auxiliary.chatbot.support.FakeAiModelsConfig;
import com.hapbang.auxiliary.chatbot.support.FakeEmbeddingModel;
import com.hapbang.auxiliary.chatbot.support.RecommendRows;

/**
 * 강의 추천 후보 생성을 실제 DB·ES(9.5.5)와 가짜 임베딩으로 확인한다 (#718).
 * 강의 벡터는 축으로 정해 유사도를 통제한다(8차원, {@link FakeEmbeddingModel}). 강의 제목이 임베딩 표의 열쇠다.
 */
@Import({FakeAiModelsConfig.class, ElasticsearchTestcontainersConfig.class})
class LectureRecommendUseCaseTest extends ChatbotIntegrationTestBase {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    private LectureRecommendUseCase useCase;

    @Autowired
    private LectureRecommendTools tools;

    @Autowired
    private LectureIndexTaskHandler handler;

    @Autowired
    private RecommendVectorIndex index;

    @Autowired
    private FakeEmbeddingModel embedding;

    @Autowired
    private MeterRegistry meterRegistry;

    private RecommendRows data;

    @BeforeEach
    void setUp() throws Exception {
        embedding.reset();
        deleteIndex();
        data = new RecommendRows(jdbcTemplate);
        for (long seller : List.of(100L, 200L, 300L, 400L, 500L)) {
            data.member(seller);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteIndex();
    }

    @Test
    @DisplayName("제외: 보유·결제 중·장바구니·본인 강의·판매 중 아님·삭제·원본 없음·탈퇴 판매자·사본 없는 판매자·"
            + "마스킹 판매자(상태는 ACTIVE)는 가까워도 빠지고, 5개를 채우는 인기순에서도 빠진다")
    void exclusions() {
        data.member(1);
        data.member(600, "WITHDRAWN");
        data.member(650);
        rows.masked(650);
        lecture(1, 100, "스프링 입문", FakeEmbeddingModel.axis(0));
        lecture(2, 100, "스프링 결제중", FakeEmbeddingModel.withCosine(0, 1, 0.99));
        lecture(3, 100, "스프링 장바구니", FakeEmbeddingModel.withCosine(0, 2, 0.9));
        lecture(4, 1, "스프링 본인", FakeEmbeddingModel.withCosine(0, 1, 0.98));
        data.lecture(5, 200, "스프링 판매중지", "소개", List.of(), false, false, false);
        data.lecture(6, 200, "스프링 삭제", "소개", List.of(), true, true, false);
        data.lecture(7, 200, "스프링 원본없음", "소개", List.of(), true, false, true);
        embedding.put("스프링 판매중지", FakeEmbeddingModel.withCosine(0, 1, 0.97));
        embedding.put("스프링 삭제", FakeEmbeddingModel.withCosine(0, 1, 0.97));
        embedding.put("스프링 원본없음", FakeEmbeddingModel.withCosine(0, 1, 0.97));
        lecture(8, 600, "스프링 탈퇴판매자", FakeEmbeddingModel.withCosine(0, 1, 0.96));
        lecture(9, 700, "스프링 사본없는판매자", FakeEmbeddingModel.withCosine(0, 1, 0.96));
        lecture(10, 200, "스프링 추천", FakeEmbeddingModel.withCosine(0, 1, 0.95));
        lecture(11, 650, "스프링 마스킹판매자", FakeEmbeddingModel.withCosine(0, 1, 0.96));
        data.purchase(1, 1, 1, "OWNED", RecommendRows.AT);
        data.purchase(2, 1, 2, "PENDING", RecommendRows.AT);
        data.cart(1, 1, 3, false, RecommendRows.AT);
        indexAll(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);

        LectureRecommendation result = useCase.recommend(1, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(10L);
        assertThat(result.needsInterest()).isFalse();
        assertThat(result.basis()).isEqualTo(RecommendBasis.PERSONALIZED);
        // 구매 중심(축 0)이 장바구니 강의보다 가깝다. 가장 가까운 구매 강의 제목을 문장에 넣는다
        assertThat(result.items().getFirst().reason()).isEqualTo(LectureRecommendReason.SIMILAR_TO_PURCHASED);
        assertThat(result.items().getFirst().reasonText()).isEqualTo("구매하신 「스프링 입문」 강의와 비슷해요.");
    }

    @Test
    @DisplayName("대화 Tool 결과에는 회원이 산 강의 제목이 없다(바깥 대화 LLM이 읽는다). 회원 본인 화면(API)의 문장에만 넣는다")
    void toolHidesBasisTitle() {
        data.member(6);
        lecture(60, 100, "회원이 산 강의", FakeEmbeddingModel.axis(2));
        lecture(61, 200, "추천 강의", FakeEmbeddingModel.withCosine(2, 3, 0.95));
        data.purchase(60, 6, 60, "OWNED", RecommendRows.AT);
        indexAll(60, 61);
        ToolContext context = new ToolContext(ChatToolContext.of(6L, List.of()));

        LectureRecommendation forScreen = useCase.recommend(6, null);
        LectureRecommendTools.Result forTool = tools.recommendLectures(null, context);
        String toolJson = ToolCallbacks.from(tools)[0].call("{}", context);

        assertThat(forScreen.items().getFirst().reasonText()).isEqualTo("구매하신 「회원이 산 강의」 강의와 비슷해요.");
        assertThat(forTool.items()).extracting(LectureRecommendTools.Item::lectureId, LectureRecommendTools.Item::reason,
                        LectureRecommendTools.Item::reasonText)
                .containsExactly(tuple(61L, LectureRecommendReason.SIMILAR_TO_PURCHASED, "구매하신 강의와 비슷해요."));
        assertThat(toolJson).contains("\"lectureId\":61").doesNotContain("회원이 산 강의");
    }

    @Test
    @DisplayName("판매자당 2개까지 5개. 모자라면 인기순(구매 수)으로 채운다: 아직 색인되지 않은 강의도 사본으로 채워지고 POPULAR·점수 0")
    void sellerCapAndPopularFill() {
        data.member(2);
        lecture(20, 100, "파이썬 입문", FakeEmbeddingModel.axis(1));
        lecture(21, 200, "파이썬 A", FakeEmbeddingModel.withCosine(1, 3, 0.99));
        lecture(22, 200, "파이썬 B", FakeEmbeddingModel.withCosine(1, 3, 0.98));
        lecture(23, 200, "파이썬 C", FakeEmbeddingModel.withCosine(1, 3, 0.97));
        lecture(24, 300, "파이썬 D", FakeEmbeddingModel.withCosine(1, 3, 0.96));
        lecture(25, 300, "파이썬 E", FakeEmbeddingModel.withCosine(1, 3, 0.95));
        lecture(26, 300, "파이썬 F", FakeEmbeddingModel.withCosine(1, 3, 0.94));
        data.lecture(27, 400, "인기 강의");
        data.purchase(2, 2, 20, "OWNED", RecommendRows.AT);
        for (long buyer = 50; buyer < 53; buyer++) {
            data.purchase(buyer, buyer, 27, "OWNED", RecommendRows.AT);
        }
        indexAll(20, 21, 22, 23, 24, 25, 26);

        LectureRecommendation result = useCase.recommend(2, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::reason)
                .containsExactly(
                        tuple(21L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(22L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(24L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(25L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(27L, LectureRecommendReason.POPULAR));
        assertThat(result.items().getLast().score()).isZero();
        assertThat(result.items().getLast().reasonText()).isEqualTo("요즘 많이 듣는 강의예요.");
        assertThat(result.basis()).isEqualTo(RecommendBasis.PERSONALIZED);
    }

    @Test
    @DisplayName("신호도 관심사도 없으면 인기순만(구매 수 내림차순, 같으면 강의 ID 내림차순, 본인 강의 제외)이고 needsInterest")
    void noSignalIsPopularAndNeedsInterest() {
        data.member(3);
        data.lecture(30, 100, "강의 30");
        data.lecture(31, 200, "강의 31");
        data.lecture(32, 300, "강의 32");
        data.lecture(33, 400, "강의 33");
        data.lecture(34, 3, "본인 강의");
        owned(30, 2);
        owned(31, 2);
        owned(32, 1);
        owned(34, 5);

        LectureRecommendation result = useCase.recommend(3, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(31L, 30L, 32L, 33L);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.reason()).isEqualTo(LectureRecommendReason.POPULAR));
        assertThat(result.needsInterest()).isTrue();
        assertThat(result.basis()).isEqualTo(RecommendBasis.POPULAR);
    }

    @Test
    @DisplayName("인기순 채우기는 숨김 판매자를 거른 뒤 센다: 탈퇴 판매자 강의가 인기 상위를 모두 차지해도 보이는 강의로 채운다")
    void popularFillSkipsHiddenSellersBeforeLimit() {
        data.member(4);
        data.member(800, "WITHDRAWN");
        // 인기순으로 미리 읽는 수(기본 50)보다 많은 탈퇴 판매자 강의가 더 많이 팔렸다
        for (long id = 1000; id < 1051; id++) {
            data.lecture(id, 800, "탈퇴 판매자 강의 " + id);
            owned(id, 1);
        }
        data.lecture(1100, 100, "보이는 강의");

        LectureRecommendation result = useCase.recommend(4, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(1100L);
    }

    @Test
    @DisplayName("활동이 있어도 그 강의가 아직 색인 전이면 인기순과 needsInterest(관심사를 주면 맞춤으로 찾을 수 있다)")
    void unindexedSignalNeedsInterest() {
        data.member(5);
        lecture(50, 100, "색인된 강의", FakeEmbeddingModel.axis(0));
        data.lecture(51, 200, "방금 산 강의");
        data.purchase(5, 5, 51, "OWNED", RecommendRows.AT);
        indexAll(50);

        LectureRecommendation result = useCase.recommend(5, null);

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(50L);
        assertThat(result.items()).allSatisfy(item -> assertThat(item.reason()).isEqualTo(LectureRecommendReason.POPULAR));
        assertThat(result.needsInterest()).isTrue();
        assertThat(result.basis()).isEqualTo(RecommendBasis.POPULAR);
    }

    @Test
    @DisplayName("신호가 없고 관심사만 있으면 관심사 벡터로 찾는다(MATCHES_INTEREST, needsInterest 아님)")
    void interestOnly() {
        data.member(4);
        lecture(40, 100, "데이터 분석 입문", FakeEmbeddingModel.withCosine(2, 3, 0.95));
        lecture(41, 200, "스프링 웹", FakeEmbeddingModel.axis(0));
        embedding.put(FakeEmbeddingModel.query("데이터 분석"), FakeEmbeddingModel.axis(2));
        indexAll(40, 41);

        LectureRecommendation result = useCase.recommend(4, "데이터 분석");

        assertThat(result.items().getFirst().lectureId()).isEqualTo(40L);
        assertThat(result.items().getFirst().reason()).isEqualTo(LectureRecommendReason.MATCHES_INTEREST);
        assertThat(result.items().getFirst().reasonText()).isEqualTo("입력하신 관심사와 맞는 강의예요.");
        assertThat(result.needsInterest()).isFalse();
        assertThat(result.basis()).isEqualTo(RecommendBasis.PERSONALIZED);
    }

    @Test
    @DisplayName("신호와 관심사를 (1−α)·신호 + α·관심사(α 0.3)로 섞는다. 이유는 강의마다 더 가까운 쪽")
    void mixesSignalAndInterest() {
        data.member(5);
        lecture(50, 100, "백엔드 기초", FakeEmbeddingModel.axis(0));
        lecture(51, 200, "백엔드 심화", FakeEmbeddingModel.axis(0));
        lecture(52, 300, "백엔드와 데이터", new float[] {0.6f, 0, 0.8f, 0, 0, 0, 0, 0});
        lecture(53, 400, "데이터 시각화", FakeEmbeddingModel.axis(2));
        data.purchase(5, 5, 50, "OWNED", RecommendRows.AT);
        embedding.put(FakeEmbeddingModel.query("데이터"), FakeEmbeddingModel.axis(2));
        indexAll(50, 51, 52, 53);

        // 질의 = 0.7·e0 + 0.3·e2 → 코사인 51: 0.92, 52: 0.87, 53: 0.39
        assertThat(useCase.recommend(5, "데이터").items())
                .extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::reason)
                .containsExactly(
                        tuple(51L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(52L, LectureRecommendReason.MATCHES_INTEREST),
                        tuple(53L, LectureRecommendReason.MATCHES_INTEREST));
        // 관심사가 없으면 신호만: 52(0.6)가 53(0)보다 앞이고 이유는 모두 구매
        assertThat(useCase.recommend(5, null).items())
                .extracting(LectureRecommendation.Item::lectureId, LectureRecommendation.Item::reason)
                .containsExactly(
                        tuple(51L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(52L, LectureRecommendReason.SIMILAR_TO_PURCHASED),
                        tuple(53L, LectureRecommendReason.SIMILAR_TO_PURCHASED));
    }

    @Test
    @DisplayName("리뷰 별점이 가중에 반영돼 순서가 바뀐다: 5점 강의(1.5)가 1점 강의(0.5)보다 취향을 더 끈다")
    void reviewRatingChangesOrder() {
        data.member(6);
        data.member(7);
        lecture(60, 100, "요리 기초", FakeEmbeddingModel.axis(0));
        lecture(61, 100, "운동 기초", FakeEmbeddingModel.axis(1));
        lecture(62, 200, "요리 심화", FakeEmbeddingModel.withCosine(0, 3, 0.9));
        lecture(63, 300, "운동 심화", FakeEmbeddingModel.withCosine(1, 3, 0.9));
        data.purchase(60, 6, 60, "OWNED", RecommendRows.AT);
        data.purchase(61, 6, 61, "OWNED", RecommendRows.AT);
        data.review(60, 6, 60, 5);
        data.review(61, 6, 61, 1);
        data.purchase(70, 7, 60, "OWNED", RecommendRows.AT);
        data.purchase(71, 7, 61, "OWNED", RecommendRows.AT);
        data.review(70, 7, 60, 1);
        data.review(71, 7, 61, 5);
        indexAll(60, 61, 62, 63);

        assertThat(useCase.recommend(6, null).items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(62L, 63L);
        assertThat(useCase.recommend(7, null).items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(63L, 62L);
    }

    @Test
    @DisplayName("장바구니가 더 가까우면 SIMILAR_TO_CART, 장바구니 강의 제목을 문장에 넣는다")
    void cartReason() {
        data.member(8);
        lecture(80, 100, "자바 장바구니", FakeEmbeddingModel.axis(3));
        lecture(81, 200, "자바 추천", FakeEmbeddingModel.withCosine(3, 4, 0.95));
        data.cart(80, 8, 80, false, RecommendRows.AT);
        indexAll(80, 81);

        LectureRecommendation.Item item = useCase.recommend(8, null).items().getFirst();

        assertThat(item.lectureId()).isEqualTo(81L);
        assertThat(item.reason()).isEqualTo(LectureRecommendReason.SIMILAR_TO_CART);
        assertThat(item.reasonText()).isEqualTo("장바구니에 담은 「자바 장바구니」 강의와 비슷해요.");
    }

    @Test
    @DisplayName("요청 회원이 탈퇴(숨김)면 빈 결과(인기순도 주지 않는다)")
    void hiddenRequesterGetsNothing() {
        data.member(9, "WITHDRAWN");
        data.lecture(90, 100, "강의 90");
        owned(90, 3);

        LectureRecommendation result = useCase.recommend(9, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.needsInterest()).isFalse();
    }

    @Test
    @DisplayName("요청 회원이 마스킹이면 사본 상태가 ACTIVE여도 빈 결과(인기순도 주지 않는다)")
    void maskedRequesterGetsNothing() {
        data.member(9);
        rows.masked(9);
        data.lecture(90, 100, "강의 90");
        owned(90, 3);

        LectureRecommendation result = useCase.recommend(9, null);

        assertThat(result.items()).isEmpty();
        assertThat(result.needsInterest()).isFalse();
    }

    @Test
    @DisplayName("인기순 캐시를 읽은 뒤 판매 중지·판매자 마스킹이 된 강의는 응답에 나오지 않는다(응답 직전에 ID로 다시 확인하고, "
            + "모자라면 인기순 쿼리를 직접 돈다)")
    void popularCacheRechecksBeforeAnswer() {
        data.member(20);
        popularLectures();
        assertThat(useCase.recommend(20, null).items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(120L, 121L, 122L, 123L, 124L);

        jdbcTemplate.update("UPDATE chatbot.lecture_copy SET on_sale = FALSE WHERE id = 120");
        rows.masked(200);

        assertThat(useCase.recommend(20, null).items()).extracting(LectureRecommendation.Item::lectureId)
                .containsExactly(121L, 124L, 125L);
    }

    @Test
    @DisplayName("인기순 캐시는 popular-cache-ttl(1분) 동안 다시 읽지 않는다. 지나면 새 순서를 읽는다")
    void popularCacheTtl() {
        data.member(20);
        popularLectures();
        useCase.recommend(20, null);
        data.lecture(126, 400, "강의 126");
        owned(126, 10);

        assertThat(useCase.recommend(20, null).items()).extracting(LectureRecommendation.Item::lectureId)
                .doesNotContain(126L);
        clock.advance(Duration.ofMinutes(1));
        assertThat(useCase.recommend(20, null).items().getFirst().lectureId()).isEqualTo(126L);
    }

    // 판매자 100·200·300이 2개씩, 구매 수 6~1. 판매자당 2개라 상위 5개가 120~124다
    private void popularLectures() {
        long[][] lectures = {{120, 100}, {121, 100}, {122, 200}, {123, 200}, {124, 300}, {125, 300}};
        for (int i = 0; i < lectures.length; i++) {
            data.lecture(lectures[i][0], lectures[i][1], "강의 " + lectures[i][0]);
            owned(lectures[i][0], 6 - i);
        }
    }

    @Test
    @DisplayName("색인을 쓸 수 없으면(index 없음) 관심사가 있든 없든 인기순·POPULAR_TEMPORARY로 답하고 검색 장애로 센다"
            + "(LLM 실패가 아니라 fallbacks에는 세지 않는다)")
    void unavailableIndex() throws Exception {
        data.member(10);
        lecture(100, 100, "스프링 입문", FakeEmbeddingModel.axis(0));
        data.lecture(101, 200, "강의 101");
        data.purchase(100, 10, 100, "OWNED", RecommendRows.AT);
        indexAll(100);
        deleteIndex();
        double searchErrors = searchErrors();
        double fallbacks = fallbacks("error");

        LectureRecommendation result = useCase.recommend(10, null);
        LectureRecommendation withInterest = useCase.recommend(10, "데이터");

        for (LectureRecommendation answer : List.of(result, withInterest)) {
            assertThat(answer.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(101L);
            assertThat(answer.basis()).isEqualTo(RecommendBasis.POPULAR_TEMPORARY);
            assertThat(answer.needsInterest()).isFalse();
        }
        assertThat(searchErrors()).isEqualTo(searchErrors + 2);
        assertThat(fallbacks("error")).isEqualTo(fallbacks);
    }

    @Test
    @DisplayName("관심사 임베딩이 실패하면 인기순·POPULAR_TEMPORARY(오류로 답하지 않는다)")
    void interestEmbeddingFailure() {
        data.member(11);
        data.lecture(110, 100, "강의 110");
        embedding.failWith(new IllegalStateException("임베딩 장애"));

        LectureRecommendation result = useCase.recommend(11, "데이터");

        assertThat(result.items()).extracting(LectureRecommendation.Item::lectureId).containsExactly(110L);
        assertThat(result.basis()).isEqualTo(RecommendBasis.POPULAR_TEMPORARY);
    }

    private double searchErrors() {
        var counter = meterRegistry.find("hapbang.chatbot.recommend.search.errors").tag("feature", "lecture_recommend")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double fallbacks(String reason) {
        var counter = meterRegistry.find("hapbang.chatbot.fallbacks").tag("feature", "lecture_recommend")
                .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private void lecture(long id, long sellerId, String title, float[] vector) {
        data.lecture(id, sellerId, title);
        embedding.put(title, vector);
    }

    // 다른 회원들이 산 수(인기)
    private void owned(long lectureId, int count) {
        for (int i = 0; i < count; i++) {
            long orderItemId = lectureId * 100 + i;
            data.purchase(orderItemId, 9000 + orderItemId, lectureId, "OWNED", RecommendRows.AT);
        }
    }

    private void indexAll(long... ids) {
        SequencedMap<String, IndexAction> docs = new LinkedHashMap<>();
        for (long id : ids) {
            docs.put(String.valueOf(id), IndexAction.UPSERT);
        }
        assertThat(handler.handle(docs)).isEmpty();
        index.refresh(IndexType.LECTURE);
    }

    private static void deleteIndex() throws Exception {
        HTTP.send(HttpRequest.newBuilder(URI.create(ElasticsearchTestcontainersConfig.uri() + "/chatbot-lectures-test-8"))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }
}
