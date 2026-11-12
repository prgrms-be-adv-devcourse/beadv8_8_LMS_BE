package com.hapbang.auxiliary.search.in;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hapbang.auxiliary.global.auth.GatewayUserHeaderFilter;
import com.hapbang.auxiliary.search.config.SearchElasticsearchConfig;
import com.hapbang.auxiliary.search.lecture.app.LectureZeroResultKeywords;
import com.hapbang.auxiliary.search.lecture.out.LectureDocumentStore;
import com.hapbang.auxiliary.search.lecture.out.LectureIndexDefinition;
import com.hapbang.auxiliary.search.lecture.out.LectureRating;
import com.hapbang.auxiliary.search.lecture.out.Seller;
import com.hapbang.auxiliary.search.support.SearchElasticsearch;
import com.hapbang.auxiliary.search.support.SearchElasticsearchTestcontainersConfig;
import com.hapbang.shared.lecture.dto.LectureContentSyncDto;
import com.hapbang.shared.lecture.dto.LectureSyncDto;
import com.hapbang.testsupport.IntegrationTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 강의 검색 API(#883). 보안 설정(비회원 허용)·검증·공통 오류 처리·호출 제한을 그대로 거친다. ES는 nori 컨테이너다. 한도는 시험하기 쉽게 3회로
 * 줄였고, 테스트마다 다른 회원 ID·IP를 써서 서로의 한도에 걸리지 않게 한다. 순위 자체는 쿼리 테스트와 golden query 테스트가 본다.
 * <p>
 * 운영의 "위조한 X-Forwarded-For 무시"는 Tomcat {@code RemoteIpValve}(운영 프로필)의 동작이라 MockMvc로 재현하지 못한다. 여기서는 앱이 그 헤더를
 * 읽지 않는 것만 확인하고, 운영은 배포 뒤 HTTP로 확인한다(PR 본문).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(SearchElasticsearchTestcontainersConfig.class)
@TestPropertySource(properties = {
        "hapbang.search.lecture-rate-limit.member.limit=3",
        "hapbang.search.lecture-rate-limit.guest.limit=3"})
@DirtiesContext
class ApiV1LectureSearchControllerTest {

    private static final String URL = "/api/v1/search/lectures";
    private static final Instant NOW = Instant.parse("2026-11-12T06:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier(SearchElasticsearchConfig.CLIENT)
    private ElasticsearchClient client;

    @Autowired
    private LectureDocumentStore store;

    @Autowired
    private LectureZeroResultKeywords zeroResultKeywords;

    @Autowired
    private MeterRegistry registry;

    @BeforeEach
    void seed() throws Exception {
        SearchElasticsearch.reset(client);
        SearchElasticsearch.createLectureIndex(client);
        zeroResultKeywords.flush();
        // 평점순(1 → 2 → 3, 리뷰 없는 2는 전체 평균)이 최신순(3 → 2 → 1)과 반대가 되게 둔다
        lecture(1L, "프리미어 컷편집 입문", "처음 쓰는 분을 위한 강의", List.of("1강 설치"), new LectureRating(4.85, 20));
        lecture(2L, "포토샵 보정", "사진 보정", List.of("3강 프리미어 연동"), LectureRating.NONE);
        lecture(3L, "OBS 방송 세팅", "방송 장비", List.of(), new LectureRating(4.0, 30));
        client.indices().refresh(r -> r.index(LectureIndexDefinition.ALIAS));
    }

    @Test
    @DisplayName("비회원도 검색한다. 응답은 강의 카드 칸과 highlights다(검색어가 있으면 관련도순)")
    void guestSearch() throws Exception {
        mockMvc.perform(guest(get(URL).param("keyword", "프리미어"), "203.0.113.10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].lectureId", contains(1, 2)))
                .andExpect(jsonPath("$.data.content[0].title").value("프리미어 컷편집 입문"))
                .andExpect(jsonPath("$.data.content[0].sellerUserId").value(7))
                .andExpect(jsonPath("$.data.content[0].sellerNickname").value("편집장"))
                .andExpect(jsonPath("$.data.content[0].averageRating").value(4.9))
                .andExpect(jsonPath("$.data.content[0].reviewCount").value(20))
                .andExpect(jsonPath("$.data.content[0].highlights.title").value("<em>프리미어</em> 컷편집 입문"))
                .andExpect(jsonPath("$.data.content[1].highlights.title").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].highlights.contentTitles[0]").value("3강 <em>프리미어</em> 연동"))
                .andExpect(jsonPath("$.data.page.totalElements").value(2));
    }

    @Test
    @DisplayName("회원도 검색한다. 검색어가 없으면 최신순이고 highlights가 없다. latest·rating 정렬")
    void memberSearchAndSorts() throws Exception {
        mockMvc.perform(member(get(URL), 201L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].lectureId", contains(3, 2, 1)))
                .andExpect(jsonPath("$.data.content[0].highlights").value(nullValue()));
        mockMvc.perform(member(get(URL).param("keyword", "프리미어").param("sort", "latest,desc"), 201L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[*].lectureId", contains(2, 1)));
        mockMvc.perform(member(get(URL).param("sort", "rating,desc"), 201L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[*].lectureId", contains(1, 2, 3)));
    }

    @Test
    @DisplayName("허용 밖 정렬은 400 UNSUPPORTED_SORT, 1,000건 밖은 400 PAGE_TOO_DEEP, 50자 넘는 검색어는 400 INVALID_REQUEST다")
    void badRequests() throws Exception {
        // 한도는 검증보다 먼저 세므로(잘못된 요청 남용도 막음) 요청마다 다른 IP를 쓴다
        List<String> sorts = List.of("createdAt,desc", "latest,asc", "followers,desc", "price,asc");
        for (int i = 0; i < sorts.size(); i++) {
            mockMvc.perform(guest(get(URL).param("sort", sorts.get(i)), "203.0.113." + (11 + i)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_SORT"))
                    .andExpect(jsonPath("$.detail", containsString("latest,desc")));
        }
        mockMvc.perform(member(get(URL).param("page", "50").param("size", "20"), 202L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAGE_TOO_DEEP"));
        mockMvc.perform(member(get(URL).param("keyword", "가".repeat(51)), 203L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("강의 index가 없으면(처음 적재 전) 503 SEARCH_UNAVAILABLE이다")
    void unavailableBeforeFirstLoad() throws Exception {
        SearchElasticsearch.reset(client);

        // 검색어 없는 최신순으로 본다. 검색어가 있으면 평균 C도 실패해 1분 동안 다시 묻지 않아(CachedRatingBaseline)
        // 같은 컨텍스트의 다른 테스트가 그 1분 안에 503을 받는다
        mockMvc.perform(member(get(URL), 204L))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
    }

    @Test
    @DisplayName("회원은 회원 ID마다 한도를 센다. 넘으면 429와 Retry-After이고, 다른 회원은 그대로 쓴다")
    void memberRateLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(member(get(URL), 301L)).andExpect(status().isOk());
        }
        mockMvc.perform(member(get(URL), 301L))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(member(get(URL), 302L)).andExpect(status().isOk());
        Assertions.assertThat(registry.counter("hapbang.search.rate.limit.rejected", "target", "lecture",
                "requester", "member").count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("비회원은 접속 IP마다 센다. X-Forwarded-For·X-Real-IP를 바꿔도 같은 IP라 429이고, 다른 IP는 200이다")
    void guestRateLimitIgnoresForwardedHeaders() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(guest(get(URL), "198.51.100.20")).andExpect(status().isOk());
        }
        mockMvc.perform(guest(get(URL), "198.51.100.20")).andExpect(status().isTooManyRequests());
        for (String forged : List.of("198.51.100.77", "203.0.113.99")) {
            mockMvc.perform(guest(get(URL), "198.51.100.20").header("X-Forwarded-For", forged)
                    .header("X-Real-IP", forged).header("X-Envoy-External-Address", forged))
                    .andExpect(status().isTooManyRequests());
        }
        mockMvc.perform(guest(get(URL), "198.51.100.21")).andExpect(status().isOk());
        Assertions.assertThat(registry.counter("hapbang.search.rate.limit.rejected", "target", "lecture",
                "requester", "guest").count()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("회원과 비회원 한도는 따로다. 같은 IP에서 비회원 한도를 다 써도 로그인하면 회원 한도로 쓴다")
    void memberAndGuestSeparate() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(guest(get(URL), "198.51.100.30")).andExpect(status().isOk());
        }
        mockMvc.perform(guest(get(URL), "198.51.100.30")).andExpect(status().isTooManyRequests());
        mockMvc.perform(member(guest(get(URL), "198.51.100.30"), 303L)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("비회원 IP가 사설 주소면(경로가 깨진 신호) IP 한도를 건너뛰고 skipped 지표를 남긴다")
    void privateAddressSkipsLimit() throws Exception {
        double before = registry.counter("hapbang.search.rate.limit.skipped", "target", "lecture").count();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(guest(get(URL), "10.42.0.9")).andExpect(status().isOk());
        }
        Assertions.assertThat(registry.counter("hapbang.search.rate.limit.skipped", "target", "lecture").count())
                .isEqualTo(before + 5);
    }

    @Test
    @DisplayName("0건 검색어는 회원 ID와 비회원 IP 키로 서로 다른 검색자를 센다. 사설 주소 비회원은 세지 않는다")
    void zeroResultKeywords() throws Exception {
        mockMvc.perform(guest(get(URL).param("keyword", "다빈치리졸브"), "10.42.0.9")).andExpect(status().isOk());
        mockMvc.perform(guest(get(URL).param("keyword", "다빈치리졸브"), "10.42.0.10")).andExpect(status().isOk());
        Assertions.assertThat(zeroResultKeywords.flush()).isEmpty();

        mockMvc.perform(member(get(URL).param("keyword", "다빈치리졸브"), 401L)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page.totalElements").value(0));
        mockMvc.perform(guest(get(URL).param("keyword", "다빈치리졸브"), "198.51.100.40")).andExpect(status().isOk());
        Assertions.assertThat(zeroResultKeywords.flush()).containsOnlyKeys("다빈치리졸브");
    }

    private void lecture(long id, String title, String description, List<String> contentTitles, LectureRating rating) {
        List<LectureContentSyncDto> contents = new java.util.ArrayList<>();
        for (int i = 0; i < contentTitles.size(); i++) {
            contents.add(new LectureContentSyncDto((long) i + 1, contentTitles.get(i), i + 1));
        }
        store.writeLecture(new LectureSyncDto(id, 7L, title, description, 10_000L, true, false, contents, 1L),
                new Seller(7L, "편집장", true), rating, NOW);
    }

    private static MockHttpServletRequestBuilder guest(MockHttpServletRequestBuilder request, String remoteAddr) {
        return request.with(r -> {
            r.setRemoteAddr(remoteAddr);
            return r;
        });
    }

    private static MockHttpServletRequestBuilder member(MockHttpServletRequestBuilder request, long userId) {
        return request.header(GatewayUserHeaderFilter.USER_ID, String.valueOf(userId))
                .header(GatewayUserHeaderFilter.USER_ROLE, "USER");
    }
}
