package com.hapbang.auxiliary.search.in;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.influencer.app.InfluencerZeroResultKeywords;
import com.hapbang.auxiliary.search.influencer.out.InfluencerDocumentStore;
import com.hapbang.auxiliary.search.influencer.out.InfluencerIndexDefinition;
import com.hapbang.auxiliary.search.influencer.out.InfluencerStats;
import com.hapbang.auxiliary.search.influencer.out.MemberProfile;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.auxiliary.search.support.SearchElasticsearchTestcontainersConfig;
import com.hapbang.testsupport.IntegrationTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 인플루언서 검색 API(#771). Gateway 사용자 헤더로 로그인해 보안 설정·메서드 보안·검증·공통 오류 처리를 그대로 거친다. ES는 nori 컨테이너다.
 * 순위 자체는 golden query 테스트가 본다.
 * <p>
 * 호출 제한(#1012)은 기본값(본 검색 60, 자동완성 120) 그대로 본다. 한도 테스트는 저마다 다른 회원 ID를 써서, 회원 100으로 부르는 다른 테스트와
 * 한도가 섞이지 않는다.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(SearchElasticsearchTestcontainersConfig.class)
class ApiV1SearchControllerTest {

    private static final String URL = "/api/v1/search/influencers";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier(SearchElasticsearchConfig.CLIENT)
    private ElasticsearchClient client;

    @Autowired
    private InfluencerDocumentStore store;

    @Autowired
    private InfluencerZeroResultKeywords zeroResultKeywords;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void seed() throws Exception {
        SearchElasticsearch.reset(client);
        // 다른 테스트가 지운 뒤라 이 컨텍스트의 준비 상태를 믿지 않고 새로 맞춘다
        SearchElasticsearch.createIndex(client);
        zeroResultKeywords.flush();
        influencer(1L, "메이플용사", "VERIFIED_INFLUENCER", List.of("GAME", "MAPLESTORY"), "메이플스토리 보스 공략",
                new InfluencerStats(1L, List.of("메이플 아저씨", "다시보기"), "메이플 아저씨", 90_000, 4.7, 15));
        influencer(2L, "단풍잎", "INFLUENCER", List.of("GAME"), "메이플 가끔 하는 종합게임",
                new InfluencerStats(2L, List.of("단풍잎채널"), "단풍잎채널", 400_000, 3.9, 100));
        influencer(3L, "요리왕", "INFLUENCER", List.of("COOKING"), null,
                new InfluencerStats(3L, List.of(), null, 500, null, 0));
        store.hide(4L, 1L, Instant.parse("2026-11-10T00:00:00Z"));
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));
    }

    @Test
    @DisplayName("검색어로 찾아 응답 한 줄(분야 코드·이름, 대표 채널, 팔로워, 평점)을 준다. 숨긴 문서는 나오지 않는다")
    void searchByKeyword() throws Exception {
        mockMvc.perform(as(get(URL).param("keyword", "메이플")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.content[*].id", contains(1, 2)))
                .andExpect(jsonPath("$.data.content[0].nickname").value("메이플용사"))
                .andExpect(jsonPath("$.data.content[0].role").value("VERIFIED_INFLUENCER"))
                .andExpect(jsonPath("$.data.content[0].contentCategories[1].code").value("MAPLESTORY"))
                .andExpect(jsonPath("$.data.content[0].contentCategories[1].name").value("메이플스토리"))
                .andExpect(jsonPath("$.data.content[0].bio").value("메이플스토리 보스 공략"))
                .andExpect(jsonPath("$.data.content[0].channelName").value("메이플 아저씨"))
                .andExpect(jsonPath("$.data.content[0].followerCount").value(90_000))
                .andExpect(jsonPath("$.data.content[0].appearanceRating.average").value(4.7))
                .andExpect(jsonPath("$.data.content[0].appearanceRating.count").value(15))
                .andExpect(jsonPath("$.data.page.totalElements").value(2))
                .andExpect(jsonPath("$.data.page.size").value(20));
    }

    @Test
    @DisplayName("검색어가 없으면 팔로워 많은 순이다. 리뷰가 없으면 평균은 null이다")
    void defaultOrder() throws Exception {
        mockMvc.perform(as(get(URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", contains(2, 1, 3)))
                .andExpect(jsonPath("$.data.content[2].appearanceRating.average").doesNotExist())
                .andExpect(jsonPath("$.data.content[2].appearanceRating.count").value(0));
    }

    @Test
    @DisplayName("필터: 분야(OR), 인증 여부, 팔로워 최소값, 평점 최소값(리뷰 없는 사람은 빠짐)")
    void filters() throws Exception {
        mockMvc.perform(as(get(URL).param("contentCategories", "MAPLESTORY", "COOKING")))
                .andExpect(jsonPath("$.data.content[*].id", containsInAnyOrder(1, 3)));
        mockMvc.perform(as(get(URL).param("verified", "true")))
                .andExpect(jsonPath("$.data.content[*].id", contains(1)));
        mockMvc.perform(as(get(URL).param("verified", "false")))
                .andExpect(jsonPath("$.data.content[*].id", contains(2, 3)));
        mockMvc.perform(as(get(URL).param("minFollowers", "100000")))
                .andExpect(jsonPath("$.data.content[*].id", contains(2)));
        mockMvc.perform(as(get(URL).param("minRating", "4.0")))
                .andExpect(jsonPath("$.data.content[*].id", contains(1)));
    }

    @Test
    @DisplayName("고를 수 있는 정렬: followers,desc는 팔로워순, rating,desc는 리뷰 수로 보정한 평점순(리뷰 없는 3은 전체 평균 4.0으로 3.9인 2보다 위)")
    void sorts() throws Exception {
        mockMvc.perform(as(get(URL).param("keyword", "메이플").param("sort", "followers,desc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", contains(2, 1)));
        mockMvc.perform(as(get(URL).param("sort", "rating,desc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id", contains(1, 3, 2)));
    }

    @Test
    @DisplayName("점수·팔로워가 같으면 회원 ID 순이라, 페이지를 넘겨도 같은 사람이 또 나오거나 빠지지 않는다")
    void stablePaging() throws Exception {
        for (long id = 14; id >= 10; id--) {
            influencer(id, "그림쟁이" + id, "INFLUENCER", List.of("DRAWING"), null,
                    new InfluencerStats(id, List.of(), null, 0, null, 0));
        }
        client.indices().refresh(r -> r.index(InfluencerIndexDefinition.ALIAS));

        mockMvc.perform(as(get(URL).param("contentCategories", "DRAWING").param("size", "2")))
                .andExpect(jsonPath("$.data.content[*].id", contains(10, 11)));
        mockMvc.perform(as(get(URL).param("contentCategories", "DRAWING").param("size", "2").param("page", "1")))
                .andExpect(jsonPath("$.data.content[*].id", contains(12, 13)));
        mockMvc.perform(as(get(URL).param("contentCategories", "DRAWING").param("size", "2").param("page", "2")))
                .andExpect(jsonPath("$.data.content[*].id", contains(14)));
    }

    @Test
    @DisplayName("허용 밖 정렬은 400 UNSUPPORTED_SORT(오름차순, 다른 필드, 둘 이상)")
    void unsupportedSort() throws Exception {
        for (List<String> sort : List.of(List.of("followers,asc"), List.of("followers"), List.of("nickname,desc"),
                List.of("followers,desc", "rating,desc"))) {
            mockMvc.perform(as(get(URL).param("sort", sort.toArray(String[]::new))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_SORT"));
        }
    }

    @Test
    @DisplayName("앞에서 1,000건 밖의 페이지는 400 PAGE_TOO_DEEP. 마지막 허용 페이지는 된다")
    void pageTooDeep() throws Exception {
        mockMvc.perform(as(get(URL).param("page", "49").param("size", "20"))).andExpect(status().isOk());
        mockMvc.perform(as(get(URL).param("page", "50").param("size", "20")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_TOO_DEEP"));
    }

    @Test
    @DisplayName("값 제약을 어기면 400 INVALID_REQUEST(검색어 101자, 분야 11개, 음수 팔로워, 평점 5 초과)")
    void invalidRequest() throws Exception {
        mockMvc.perform(as(get(URL).param("keyword", "가".repeat(101))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(as(get(URL).param("contentCategories", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(as(get(URL).param("minFollowers", "-1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(as(get(URL).param("minRating", "5.1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401이다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("필터 없이 검색어만으로 0건이면, 로그인 회원 ID로 서로 다른 회원 수를 세어 모은다")
    void collectsZeroResultKeyword() throws Exception {
        mockMvc.perform(as(get(URL).param("keyword", "  롤토체스  "))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
        org.assertj.core.api.Assertions.assertThat(zeroResultKeywords.flush()).isEmpty();

        mockMvc.perform(as(get(URL).param("keyword", "롤토체스")));
        mockMvc.perform(get(URL).param("keyword", "롤토체스").header(GatewayUserHeaderFilter.USER_ID, "101")
                .header(GatewayUserHeaderFilter.USER_ROLE, "INFLUENCER"));

        org.assertj.core.api.Assertions.assertThat(zeroResultKeywords.flush()).containsEntry("롤토체스", 2L);
    }

    @Test
    @DisplayName("검색어가 초성뿐이면 닉네임 초성 칸에서 낱말 앞부분으로 찾고, 0건이어도 0건 검색어로 모으지 않는다(#973)")
    void chosungOnly() throws Exception {
        mockMvc.perform(as(get(URL).param("keyword", "ㅁㅇㅍ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(org.hamcrest.Matchers.contains(1)));
        mockMvc.perform(as(get(URL).param("keyword", "ㄷㅍ"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].id").value(org.hamcrest.Matchers.contains(2)));

        mockMvc.perform(as(get(URL).param("keyword", "ㅋㅋㅋ"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
        mockMvc.perform(get(URL).param("keyword", "ㅋㅋㅋ").header(GatewayUserHeaderFilter.USER_ID, "101")
                .header(GatewayUserHeaderFilter.USER_ROLE, "INFLUENCER"));
        org.assertj.core.api.Assertions.assertThat(zeroResultKeywords.flush()).isEmpty();
    }

    @Test
    @DisplayName("자동완성: 닉네임 앞부분·치는 도중 글자·초성으로 후보(회원 ID·닉네임)를 주고, 숨긴 사람은 빠진다. 공백뿐이면 빈 목록이다(#973)")
    void suggestions() throws Exception {
        String url = URL + "/suggestions";
        mockMvc.perform(as(get(url).param("keyword", "메이")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(1))
                .andExpect(jsonPath("$.data[0].nickname").value("메이플용사"))
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(as(get(url).param("keyword", "단ㅍ"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].userId").value(org.hamcrest.Matchers.contains(2)));
        mockMvc.perform(as(get(url).param("keyword", "ㅇㄹ"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].userId").value(org.hamcrest.Matchers.contains(3)));
        mockMvc.perform(as(get(url).param("keyword", "  "))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("자동완성: 비회원은 401, 검색어가 없으면 400, 50자를 넘으면 400 INVALID_REQUEST다(#973)")
    void suggestionRequests() throws Exception {
        String url = URL + "/suggestions";
        mockMvc.perform(get(url).param("keyword", "메이")).andExpect(status().isUnauthorized());
        mockMvc.perform(as(get(url))).andExpect(status().isBadRequest());
        mockMvc.perform(as(get(url).param("keyword", "가".repeat(51)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("본 검색은 회원 ID마다 분당 60회다. 같은 회원의 61번째는 429와 Retry-After이고, 다른 회원은 그대로 쓴다(#1012)")
    void memberRateLimit() throws Exception {
        double before = rejected("influencer");
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(as(get(URL), 501L)).andExpect(status().isOk());
        }
        mockMvc.perform(as(get(URL), 501L))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(as(get(URL), 502L)).andExpect(status().isOk());

        Assertions.assertThat(rejected("influencer") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("자동완성은 본 검색과 따로 회원 ID마다 분당 120회다. 121번째는 429여도 본 검색은 된다. 지표는 target=influencer_suggestion이다(#1012)")
    void suggestionRateLimitSeparate() throws Exception {
        String url = URL + "/suggestions";
        double before = rejected("influencer_suggestion");
        for (int i = 0; i < 120; i++) {
            mockMvc.perform(as(get(url).param("keyword", "메"), 503L)).andExpect(status().isOk());
        }
        mockMvc.perform(as(get(url).param("keyword", "메"), 503L))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(as(get(URL), 503L)).andExpect(status().isOk());
        mockMvc.perform(as(get(url).param("keyword", "메"), 504L)).andExpect(status().isOk());

        Assertions.assertThat(rejected("influencer_suggestion") - before).isEqualTo(1);
    }

    @Test
    @DisplayName("Swagger 설명에 인플루언서 검색·자동완성의 호출 한도와 429를 적는다(#1012)")
    void swaggerDescribesRateLimit() throws Exception {
        // 모듈 테스트 설정은 swagger.yml(앱 경로)을 읽지 않아 springdoc 기본 경로다. 그 경로는 permitAll이 아니라 로그인해서 부른다
        mockMvc.perform(as(get("/v3/api-docs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/search/influencers'].get.description",
                        containsString("1분에 60번까지 부를 수 있고 넘으면 429 TOO_MANY_REQUESTS")))
                .andExpect(jsonPath("$.paths['/api/v1/search/influencers/suggestions'].get.description",
                        containsString("1분에 120번까지 부를 수 있고 넘으면 429 TOO_MANY_REQUESTS")));
    }

    private double rejected(String target) {
        return registry.counter("hapbang.search.rate.limit.rejected", "target", target, "requester", "member").count();
    }

    private void influencer(long id, String nickname, String role, List<String> codes, String bio, InfluencerStats stats) {
        store.show(id, 1L, new MemberProfile(nickname, bio, role, "VERIFIED_INFLUENCER".equals(role),
                codes.stream().map(code -> new MemberProfile.Category(code, switch (code) {
                    case "GAME" -> "게임";
                    case "MAPLESTORY" -> "메이플스토리";
                    default -> "요리";
                })).toList()));
        store.applyStats(List.of(stats));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return as(request, 100L);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, long userId) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId))
                .header(GatewayUserHeaderFilter.USER_ROLE, "USER");
    }
}
