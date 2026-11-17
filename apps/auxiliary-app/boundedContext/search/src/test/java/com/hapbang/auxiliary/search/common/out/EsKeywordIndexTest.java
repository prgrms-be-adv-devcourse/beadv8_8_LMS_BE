package com.hapbang.auxiliary.search.common.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.hapbang.auxiliary.search.common.app.KeywordCorrection;
import com.hapbang.auxiliary.search.common.app.KeywordCorrection.Outcome;
import com.hapbang.auxiliary.search.common.app.KeywordCorrection.Result;
import com.hapbang.auxiliary.search.common.app.KeywordIndex;
import com.hapbang.auxiliary.search.influencer.config.SearchRankingProperties;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.influencer.out.InfluencerSearcher;
import com.hapbang.auxiliary.search.influencer.out.InfluencerStats;
import com.hapbang.auxiliary.search.influencer.out.MemberProfile;
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
 * 검색어 제안(#977)을 실제 ES(운영 index 정의·사전·nori)로: 강의·인플루언서 Searcher가 만든 {@link EsKeywordIndex}와
 * {@link KeywordCorrection}을 함께 돌린다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EsKeywordIndexTest {

    private static final Instant NOW = Instant.parse("2026-11-17T06:00:00Z");

    private final ElasticsearchClient client = SearchElasticsearch.client();
    private KeywordIndex lectures;
    private KeywordIndex influencers;

    @BeforeAll
    void load() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        SearchElasticsearch.createIndex(client);

        LectureDocumentStore lectureStore = new LectureDocumentStore(client);
        lecture(lectureStore, 1, "프리미어 프로 컷편집 입문", "처음 영상 편집을 배우는 분을 위한 강의", List.of("1강 설치", "2강 크로마키 합성"), "편집장", true, true);
        lecture(lectureStore, 2, "썸네일 디자인 클래스", "포토샵으로 클릭을 부르는 썸네일", List.of(), "디자인누나", true, true);
        lecture(lectureStore, 3, "방송 장비 추천", "마이크와 조명 고르기", List.of(), "장비리뷰어", true, true);
        lecture(lectureStore, 4, "Premiere 단축키 모음", "자주 쓰는 단축키", List.of(), "편집장", true, true);
        // 판매 중지 강의에만 있는 말(blender)은 제안하지 않는다
        lecture(lectureStore, 5, "Blender 3D 모델링", "판매 중지된 강의", List.of(), "모델러", false, true);
        // 부사 "가장"은 자모 칸에는 있지만 본 검색이 버린다(nori 품사 거름). 제안하면 눌렀을 때 0건이다
        lecture(lectureStore, 6, "가장 쉬운 OBS 입문", "처음 켜는 분", List.of(), "장비리뷰어", true, true);
        client.indices().refresh(r -> r.index(LectureIndexDefinition.ALIAS));

        InfluencerDocumentStore influencerStore = new InfluencerDocumentStore(client);
        influencer(influencerStore, 11, "메이플장인", "메이플스토리 보스 공략 방송", false);
        influencer(influencerStore, 12, "먹방요정", "오늘도 먹방", false);
        // 숨긴 인플루언서의 닉네임은 제안하지 않는다. 보이는 다른 사람 소개에 닉네임 조각(지킴이)이 있어도
        influencer(influencerStore, 13, "협곡지킴이", "롤 정글", true);
        influencer(influencerStore, 14, "정글러", "협곡 지킴이 브이로그", false);
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));

        lectures = new LectureSearcher(client, new LectureRankingProperties(null, null, null)).keywordIndex();
        influencers = new InfluencerSearcher(client, new SearchRankingProperties(null, null, null, null)).keywordIndex();
    }

    @ParameterizedTest(name = "강의 \"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "프리미여|프리미어",
            "썸내일 디자인|썸네일 디자인",
            "크로마기|크로마키",
            "편집쟝|편집장",
            "premeire|premiere",
            "쉬운 프리미여|쉬운 프리미어"
    })
    @DisplayName("강의: 제목(자모 칸)·콘텐츠 제목(원문 칸)·판매자 닉네임·영문 오타를 고친다. 관형형 어미가 든 검색어(쉬운)는 문장이 아니다")
    void lectureTypos(String keyword, String expected) {
        assertThat(KeywordCorrection.suggest(keyword, lectures)).isEqualTo(new Result(Outcome.SUGGESTED, expected));
    }

    @ParameterizedTest(name = "강의 \"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "컷편집|KNOWN",
            "방송을|KNOWN",
            "방송 장비 추천|KNOWN",
            "배그|KNOWN",
            "배그를|KNOWN",
            "가잔|NONE",
            "프리미어프로|NONE",
            "마이크 뭐 사야 돼요|SENTENCE",
            "blendr|NONE"
    })
    @DisplayName("강의: 있는 말·조사가 붙은 말·동의어(배그·배그를)는 고치지 않고, 본 검색이 버리는 말(가장)은 제안하지 않고, 붙여 쓴 말(프리미어프로)은 가까운 후보가 없어 제안하지 않고, 문장은 건너뛰고, 판매 중지 강의에만 있는 말은 제안하지 않는다")
    void lectureNoSuggestion(String keyword, Outcome expected) {
        assertThat(KeywordCorrection.suggest(keyword, lectures)).isEqualTo(new Result(expected, null));
    }

    @Test
    @DisplayName("인플루언서: 닉네임 오타를 고치고, 숨긴 인플루언서의 닉네임은 제안하지 않는다")
    void influencers() {
        assertThat(KeywordCorrection.suggest("메이플장임", influencers)).isEqualTo(new Result(Outcome.SUGGESTED, "메이플장인"));
        assertThat(KeywordCorrection.suggest("먹방요졍", influencers)).isEqualTo(new Result(Outcome.SUGGESTED, "먹방요정"));
        assertThat(KeywordCorrection.suggest("협곡지킴리", influencers).outcome()).isEqualTo(Outcome.NONE);
    }

    @Test
    @DisplayName("품사: nori 태그 이름만 낸다(VV(Verb) → VV)")
    void partsOfSpeech() {
        assertThat(lectures.partsOfSpeech("사야 돼요")).isNotEmpty().allMatch(tag -> !tag.contains("("))
                .anyMatch(tag -> tag.startsWith("V"));
    }

    private void lecture(LectureDocumentStore store, long id, String title, String description, List<String> contents,
            String seller, boolean onSale, boolean sellerActive) {
        List<LectureContentSyncDto> syncContents = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            syncContents.add(new LectureContentSyncDto((long) i + 1, contents.get(i), i + 1));
        }
        long sellerUserId = 1_000 + id;
        store.writeLecture(new LectureSyncDto(id, sellerUserId, title, description, 10_000L, onSale, false, syncContents, 1L),
                new Seller(sellerUserId, seller, sellerActive), LectureRating.NONE, NOW);
    }

    private void influencer(InfluencerDocumentStore store, long id, String nickname, String bio, boolean hidden)
            throws Exception {
        store.show(id, 1L, new MemberProfile(nickname, bio, "INFLUENCER", false,
                List.of(new MemberProfile.Category("GAME", "게임"))));
        store.applyStats(List.of(new InfluencerStats(id, List.of(), null, 100, null, 0)));
        if (hidden) {
            client.update(u -> u.index(InfluencerIndexDefinition.ALIAS).id(String.valueOf(id)).doc(Map.of("hidden", true)),
                    Map.class);
        }
    }
}
